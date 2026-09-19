package tachiyomi.domain.translation.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.QualityReviewAttempt
import tachiyomi.domain.translation.model.QualityReviewCheckpoint
import tachiyomi.domain.translation.model.QualityReviewCoverage
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationProgressUnit
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.model.accountingProvider
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** One bounded review pass. Attempts are durably reserved before dispatch, including uncertain interruptions. */
class QualityReviewCoordinator(
    private val repository: TranslationRepository,
    private val provider: TranslationProvider,
    private val renderer: QualityReviewRenderer? = null,
) {
    suspend fun run(
        checkpoint: QualityReviewCheckpoint,
        settings: TranslationSettings,
        image: TranslationImage,
        context: String,
    ) {
        val current = repository.reviews(checkpoint.jobId).firstOrNull { it.id == checkpoint.id } ?: return
        if (!current.state.pending) return
        val operation = TranslationOperation(
            current.id, current.jobId, TranslationStage.REVIEW,
            imageId = current.imageId, reviewId = current.id,
            provider = settings.provider.accountingProvider(), model = settings.provider.model,
            state = TranslationOperationState.ACTIVE, total = 1, unit = TranslationProgressUnit.PAGES,
            startedAt = System.currentTimeMillis(),
        )
        repository.saveOperation(operation)
        var interrupted = false
        try {
            runReview(current, settings, image, context)
        } catch (error: CancellationException) {
            interrupted = true
            throw error
        } finally {
            withContext(NonCancellable) {
                val outcome = repository.reviews(current.jobId).firstOrNull { it.id == current.id }
                val complete = outcome?.state in setOf(QualityReviewState.PASSED, QualityReviewState.REPAIRED)
                val ended = maxOf(operation.startedAt!!, System.currentTimeMillis())
                repository.saveOperation(
                    operation.copy(
                        state = when {
                            interrupted -> TranslationOperationState.INTERRUPTED
                            complete -> TranslationOperationState.COMPLETED
                            outcome?.state == QualityReviewState.PAUSED -> TranslationOperationState.PAUSED
                            outcome?.state == QualityReviewState.SUPERSEDED -> TranslationOperationState.CANCELLED
                            else -> TranslationOperationState.PARTIAL
                        },
                        completed = if (complete) 1 else 0,
                        updatedAt = ended,
                        endedAt = ended,
                        message = outcome?.message ?: "Review interrupted; saved translation is retained",
                    ),
                )
            }
        }
    }

    private suspend fun runReview(
        checkpoint: QualityReviewCheckpoint,
        settings: TranslationSettings,
        image: TranslationImage,
        context: String,
    ) {
        var review = repository.reviews(checkpoint.jobId).firstOrNull { it.id == checkpoint.id } ?: return
        if (review.prompts != null && review.promptContext == null && review.state.pending) {
            review = review.copy(promptContext = context)
            if (!repository.saveReview(review)) return
        }
        val promptContext = review.promptContext ?: context
        val executionSettings = review.executionSettings ?: settings
        val visual = executionSettings.ocr.pipeline != OcrPipeline.PADDLE &&
            review.settings.coverage == QualityReviewCoverage.FULL_PAGE_WHEN_AVAILABLE
        val preview = visual && review.renderPreviewRequested && review.settings.includeRenderedPreview
        val effective = executionSettings.copy(
            style = review.renderStyle ?: executionSettings.style,
            qualityReview = review.settings.copy(includeRenderedPreview = preview),
            contentPolicy = review.contentPolicy ?: executionSettings.contentPolicy,
            prompts = executionSettings.prompts.copy(
                qualityReview = review.prompts ?: executionSettings.prompts.qualityReview,
            ),
        )
        try {
            if (!review.state.pending) return
            if (review.attempts.size >= review.settings.maxTransportAttempts) {
                execute(review, effective, image, promptContext)
                return
            }
            if (preview && review.renderEvidence == null) {
                review = review.copy(renderStyle = effective.style)
                if (!repository.saveReview(review)) return
                try {
                    val evidence = TranslationOperationRecorder(repository).run(
                        TranslationOperation(
                            "${review.id}:render", review.jobId, TranslationStage.RENDER,
                            parentId = review.id, reviewId = review.id, imageId = review.imageId,
                            provider = effective.provider.accountingProvider(), model = effective.provider.model,
                            total = 1, unit = TranslationProgressUnit.PAGES,
                        ),
                    ) {
                        requireNotNull(renderer) { "Rendered review evidence is unavailable" }.prepare(
                            QualityReviewRequest(
                                review.jobId,
                                review.id,
                                effective,
                                image,
                                review.beforeResult,
                                promptContext,
                            ),
                        )
                    }
                    review = review.copy(renderEvidence = evidence)
                    require(
                        evidence.sourceRevision == review.sourceRevision &&
                            evidence.original.id == image.id && evidence.original.contentHash == image.contentHash &&
                            evidence.original.width == image.width && evidence.original.height == image.height,
                    ) { "Rendered review evidence does not match its saved page" }
                    if (!repository.saveReview(review)) return
                    log(
                        review,
                        "Original and rendered preview saved for this review",
                        mapOf(
                            "originalHash" to evidence.original.contentHash,
                            "previewHash" to evidence.preview.contentHash,
                            "presentationFingerprint" to evidence.presentationFingerprint,
                            "rendererVersion" to evidence.rendererVersion,
                            "previewScaleX" to evidence.scaleX.toString(),
                            "previewScaleY" to evidence.scaleY.toString(),
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    repository.completeReview(
                        review.copy(
                            state = QualityReviewState.INCOMPLETE,
                            message = error.message ?: "Rendered preview unavailable; saved translation retained",
                        ),
                        null,
                    )
                    log(review, "Rendered review incomplete before provider dispatch")
                    return
                }
            }
            execute(review, effective, if (preview) review.renderEvidence?.original ?: image else image, promptContext)
        } finally {
            val evidence = review.renderEvidence
            if (evidence != null) {
                withContext(NonCancellable) {
                    val current = repository.reviews(review.jobId).firstOrNull { it.id == review.id }
                    if (current == null || !current.state.pending || current.renderEvidence != evidence) {
                        runCatching { renderer?.cleanup(evidence) }
                            .onFailure { log(review, "Temporary review evidence cleanup deferred") }
                    }
                }
            }
        }
    }

    private suspend fun execute(
        checkpoint: QualityReviewCheckpoint,
        settings: TranslationSettings,
        image: TranslationImage,
        context: String,
    ) {
        var review = repository.reviews(checkpoint.jobId).firstOrNull { it.id == checkpoint.id } ?: return
        if (!review.state.pending) return
        val effective = settings
        val visual = effective.ocr.pipeline != OcrPipeline.PADDLE &&
            review.settings.coverage == QualityReviewCoverage.FULL_PAGE_WHEN_AVAILABLE
        val request = QualityReviewRequest(
            review.jobId,
            review.id,
            effective,
            image,
            review.beforeResult,
            context,
            renderEvidence = review.renderEvidence.takeIf { effective.qualityReview.includeRenderedPreview },
        )
        while (review.attempts.size < review.settings.maxTransportAttempts.coerceIn(1, 2)) {
            coroutineContext.ensureActive()
            val attempt = QualityReviewAttempt(review.attempts.size + 1, System.currentTimeMillis())
            review = review.copy(
                state = QualityReviewState.RUNNING,
                attempts = review.attempts + attempt,
                message = null,
            )
            if (!repository.reserveReviewAttempt(review)) return
            log(review, "Review attempt ${attempt.number} reserved; saved translation remains available")
            try {
                val response = provider.review(request)
                coroutineContext.ensureActive()
                review = review.copy(
                    attempts = review.attempts.dropLast(1) + attempt.copy(
                        completedAt = System.currentTimeMillis(),
                        usage = response.usage,
                        requestId = response.requestId,
                    ),
                    findings = response.findings,
                    visualComplete = response.visualComplete && visual,
                )
                if (visual && !response.visualComplete) {
                    repository.completeReview(
                        review.copy(
                            state = QualityReviewState.INCOMPLETE,
                            message = "Complete visual review was unavailable",
                        ),
                        null,
                    )
                    log(review, "Visual review incomplete; original saved result retained")
                    return
                }
                val candidate = QualityReviewValidation.candidate(request, response)
                val changed = hasRepairChanges(review.beforeResult, candidate)
                val outcome = review.copy(
                    state = when {
                        changed -> QualityReviewState.REPAIRED
                        response.findings.isNotEmpty() -> QualityReviewState.NEEDS_REVIEW
                        else -> QualityReviewState.PASSED
                    },
                    message = if (visual) {
                        "AI assessment; review meaning and visual output"
                    } else {
                        "Text-only AI assessment; review meaning and visual output"
                    },
                )
                val committed = repository.completeReview(outcome, candidate.takeIf { changed })
                log(
                    outcome,
                    if (committed) {
                        "${outcome.state}: ${outcome.message}"
                    } else {
                        "Review superseded by a newer page revision"
                    },
                    mapOf(
                        "requestId" to response.requestId.orEmpty(),
                        "finishReason" to response.finishReason.orEmpty(),
                        "inputTokens" to response.usage.inputTokens.toString(),
                        "outputTokens" to response.usage.outputTokens.toString(),
                        "reasoningTokens" to response.usage.reasoningTokens.toString(),
                    ),
                )
                return
            } catch (e: CancellationException) {
                // The reserved attempt remains RUNNING/uncertain. Restart cannot obtain a free extra attempt.
                throw e
            } catch (e: Exception) {
                val kind = (e as? TranslationException)?.kind ?: if (e is IOException) {
                    TranslationFailureKind.TRANSIENT
                } else {
                    TranslationFailureKind.CONTENT
                }
                val pause = kind in setOf(TranslationFailureKind.AUTHENTICATION, TranslationFailureKind.CONFIGURATION)
                val retry = kind in setOf(TranslationFailureKind.TRANSIENT, TranslationFailureKind.RATE_LIMIT) &&
                    review.attempts.size < review.settings.maxTransportAttempts
                review = review.copy(
                    attempts = review.attempts.dropLast(1) + review.attempts.last().copy(
                        completedAt = System.currentTimeMillis(),
                        failureKind = kind,
                        message = e.message,
                    ),
                    state = when {
                        pause -> QualityReviewState.PAUSED
                        retry -> QualityReviewState.QUEUED
                        kind == TranslationFailureKind.LIMIT -> QualityReviewState.INCOMPLETE
                        else -> QualityReviewState.NEEDS_REVIEW
                    },
                    message = e.message ?: "Review failed; saved translation retained",
                )
                if (!repository.saveReview(review)) return
                log(review, "${review.state}: ${review.message}")
                if (pause) throw e
                if (!retry) return
                val base = effective.provider.initialRetryMillis.coerceAtLeast(1)
                val maximum = effective.provider.maxRetryMillis.coerceAtLeast(base)
                val wait = maxOf(base + Random.nextLong(base), (e as? TranslationException)?.retryAfterMillis ?: 0L)
                    .coerceAtMost(maximum)
                log(review, "Review transport backoff", mapOf("retryDelayMillis" to wait.toString()))
                delay(wait)
            }
        }
        repository.saveReview(
            review.copy(
                state = QualityReviewState.NEEDS_REVIEW,
                message = "Review attempt limit reached, including interrupted attempts; saved translation retained",
            ),
        )
    }

    private fun hasRepairChanges(before: TranslationPageResult, candidate: TranslationPageResult): Boolean {
        val original = before.regions.associateBy { it.id }
        // Language labels and AI confidence alone do not repair text, region geometry or reading order.
        return candidate.copy(
            detectedLanguage = before.detectedLanguage,
            regions = candidate.regions.map { it.copy(aiConfidence = original[it.id]?.aiConfidence) },
        ) != before
    }

    private suspend fun log(
        review: QualityReviewCheckpoint,
        message: String,
        details: Map<String, String> = emptyMap(),
    ) {
        if (repository.jobs().firstOrNull { it.id == review.jobId }?.settings?.logs?.enabled == false) return
        repository.addEvent(
            TranslationEvent(
                UUID.randomUUID().toString(),
                review.jobId,
                review.id,
                review.imageId,
                stage = "quality-review",
                operationId = review.id,
                message = message,
                details = details + mapOf(
                    "sourceRevision" to review.sourceRevision.toString(),
                    "reviewId" to review.id,
                ),
            ),
        )
    }
}
