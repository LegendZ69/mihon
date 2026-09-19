package tachiyomi.domain.translation.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.domain.translation.model.GeometryCorrectionAttempt
import tachiyomi.domain.translation.model.GeometryCorrectionAttemptState
import tachiyomi.domain.translation.model.GeometryCorrectionCheckpoint
import tachiyomi.domain.translation.model.GeometryCorrectionState
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationPageResult
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** One correction pass. Every possible dispatch consumes a durable reservation first. */
class GeometryCorrectionCoordinator(
    private val repository: TranslationRepository,
    private val execute: suspend (GeometryCorrectionCheckpoint, GeometryCorrectionAttempt) -> TranslationPageResult,
) {
    /** An explicit user Resume may reopen the same pass, but never reset its attempts or input policy. */
    suspend fun resumePaused(checkpoint: GeometryCorrectionCheckpoint): Boolean =
        locks[(checkpoint.id.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val current = repository.geometryCorrection(checkpoint.id) ?: return@withLock false
            if (current != checkpoint || current.state != GeometryCorrectionState.PAUSED ||
                current.attempts.size >= current.maxTransportAttempts
            ) {
                return@withLock false
            }
            repository.compareAndSetGeometryCorrection(
                current,
                current.copy(
                    state = GeometryCorrectionState.QUEUED,
                    version = current.version + 1,
                    updatedAt = now(current),
                    message = "Explicit Resume requested; " +
                        "${current.attempts.size} reserved correction attempts retained",
                ),
            )
        }

    suspend fun run(checkpoint: GeometryCorrectionCheckpoint): GeometryCorrectionCheckpoint? =
        locks[(checkpoint.id.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            var current = repository.createGeometryCorrection(checkpoint) ?: return@withLock null
            if (current.state in finishedOrPaused) return@withLock current
            if (current.state == GeometryCorrectionState.RUNNING) {
                // A new process cannot know whether the previous request reached its provider.
                val interrupted = current.finishAttempt(
                    GeometryCorrectionState.INTERRUPTED,
                    GeometryCorrectionAttemptState.INTERRUPTED,
                    message = "Previous correction attempt was interrupted; its reservation remains consumed",
                )
                if (!repository.compareAndSetGeometryCorrection(current, interrupted)) {
                    return@withLock repository.geometryCorrection(current.id)
                }
                current = interrupted
            }
            while (current.attempts.size < current.maxTransportAttempts) {
                coroutineContext.ensureActive()
                val attempt = GeometryCorrectionAttempt(current.attempts.size + 1, now(current))
                val reserved = current.copy(
                    state = GeometryCorrectionState.RUNNING,
                    version = current.version + 1,
                    attempts = current.attempts + attempt,
                    updatedAt = attempt.startedAt,
                    message = "Geometry correction attempt ${attempt.number}/${current.maxTransportAttempts} reserved",
                )
                if (!repository.compareAndSetGeometryCorrection(current, reserved)) {
                    return@withLock repository.geometryCorrection(current.id)
                }
                current = reserved
                try {
                    coroutineContext.ensureActive()
                    val result = execute(current, attempt)
                    coroutineContext.ensureActive()
                    val completed = current.finishAttempt(
                        GeometryCorrectionState.COMPLETED,
                        GeometryCorrectionAttemptState.SUCCEEDED,
                        message = "Region geometry corrected; source text and translations preserved",
                    ).copy(result = result)
                    if (!repository.compareAndSetGeometryCorrection(current, completed)) {
                        return@withLock repository.geometryCorrection(current.id)
                    }
                    return@withLock completed
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) {
                        repository.compareAndSetGeometryCorrection(
                            current,
                            current.finishAttempt(
                                GeometryCorrectionState.INTERRUPTED,
                                GeometryCorrectionAttemptState.INTERRUPTED,
                                message = "Correction interrupted; its attempt reservation remains consumed",
                            ),
                        )
                    }
                    throw cancelled
                } catch (error: Exception) {
                    val kind = (error as? TranslationException)?.kind ?: if (error is IOException) {
                        TranslationFailureKind.TRANSIENT
                    } else {
                        TranslationFailureKind.GEOMETRY
                    }
                    val pause = kind == TranslationFailureKind.AUTHENTICATION ||
                        kind == TranslationFailureKind.CONFIGURATION
                    val retryable =
                        kind == TranslationFailureKind.TRANSIENT || kind == TranslationFailureKind.RATE_LIMIT
                    val retry = retryable &&
                        current.attempts.size < current.maxTransportAttempts
                    val failed = current.finishAttempt(
                        when {
                            pause -> GeometryCorrectionState.PAUSED
                            retry -> GeometryCorrectionState.QUEUED
                            else -> GeometryCorrectionState.FAILED
                        },
                        GeometryCorrectionAttemptState.FAILED,
                        kind,
                        error.message ?: "Geometry correction failed; retry the affected unfinished image",
                    )
                    if (!repository.compareAndSetGeometryCorrection(current, failed)) {
                        return@withLock repository.geometryCorrection(current.id)
                    }
                    current = failed
                    if (!retry) return@withLock current
                    val maximum = current.settings.provider.maxRetryMillis.coerceAtLeast(1)
                    val base = current.settings.provider.initialRetryMillis.coerceIn(1, maximum)
                    val jitter = Random.nextLong(base).coerceAtMost(maximum - base)
                    val waiting = maxOf(base + jitter, (error as? TranslationException)?.retryAfterMillis ?: 0L)
                        .coerceAtMost(maximum)
                    delay(waiting)
                }
            }
            val exhausted = current.copy(
                state = GeometryCorrectionState.FAILED,
                version = current.version + 1,
                updatedAt = now(current),
                message = "Geometry correction attempt limit reached, including interrupted requests. " +
                    "Retry the affected unfinished image to start an explicitly requested pass.",
            )
            if (repository.compareAndSetGeometryCorrection(current, exhausted)) {
                exhausted
            } else {
                repository.geometryCorrection(current.id)
            }
        }

    private fun GeometryCorrectionCheckpoint.finishAttempt(
        state: GeometryCorrectionState,
        attemptState: GeometryCorrectionAttemptState,
        failureKind: TranslationFailureKind? = null,
        message: String,
    ): GeometryCorrectionCheckpoint {
        val time = now(this)
        return copy(
            state = state,
            version = version + 1,
            updatedAt = time,
            message = message,
            attempts = attempts.dropLast(1) + listOfNotNull(
                attempts.lastOrNull()?.copy(
                    state = attemptState,
                    completedAt = time,
                    failureKind = failureKind,
                    message = message,
                ),
            ),
        )
    }

    private fun now(checkpoint: GeometryCorrectionCheckpoint) = maxOf(System.currentTimeMillis(), checkpoint.updatedAt)

    companion object {
        // Shared across coordinator instances; a bounded stripe array cannot leak one mutex per historical page.
        private val locks = Array(64) { Mutex() }
        private val finishedOrPaused = setOf(
            GeometryCorrectionState.COMPLETED,
            GeometryCorrectionState.FAILED,
            GeometryCorrectionState.PAUSED,
            GeometryCorrectionState.SUPERSEDED,
        )
    }
}
