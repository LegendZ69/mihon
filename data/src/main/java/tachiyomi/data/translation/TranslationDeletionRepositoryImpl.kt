package tachiyomi.data.translation

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import tachiyomi.data.Database
import tachiyomi.domain.translation.model.TranslationDeletionScope
import tachiyomi.domain.translation.model.TranslationDeletionSnapshot
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationStoredRecords
import tachiyomi.domain.translation.service.TranslationDeletionRepository
import tachiyomi.domain.translation.service.TranslationJobSnapshot
import java.security.MessageDigest

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class TranslationDeletionRepositoryImpl(private val database: Database) : TranslationDeletionRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val queries get() = database.translationQueries

    override suspend fun deletionSnapshots(
        jobIds: Set<String>,
    ): List<TranslationDeletionSnapshot> = database.transactionWithResult {
        jobIds.sorted().map { snapshot(it) }
    }

    override suspend fun deleteRecords(
        expected: List<TranslationDeletionSnapshot>,
        scopes: Set<TranslationDeletionScope>,
    ): Boolean =
        database.transactionWithResult {
            val current = expected.map { snapshot(it.jobId) }
            if (current.zip(expected).any { (now, before) ->
                    now.jobFingerprint != before.jobFingerprint || scopes.any { now.groups[it] != before.groups[it] }
                }
            ) {
                return@transactionWithResult false
            }
            current.forEach { item ->
                if (TranslationDeletionScope.TRANSLATIONS in scopes) {
                    queries.deleteReviews(item.jobId)
                    queries.deleteResults(item.jobId)
                    queries.deleteGeometryCorrections(item.jobId)
                    item.job?.let { job ->
                        val stopped = job.copy(
                            state = TranslationJobState.PAUSED,
                            completedImages = 0,
                            reviewReturnState = null,
                            reviewImageIds = null,
                            geometryRecoveryId = null,
                            updatedAt = System.currentTimeMillis(),
                            message = "Saved translations deleted; original pages retained. " +
                                "Retry starts a new geometry recovery policy.",
                        )
                        queries.upsertJob(
                            stopped.id,
                            stopped.mangaId,
                            stopped.chapterId,
                            stopped.priority,
                            stopped.updatedAt,
                            json.encodeToString(stopped),
                        )
                    }
                }
                if (TranslationDeletionScope.LOGS in scopes) {
                    queries.deleteJobEvents(item.jobId)
                    queries.deleteOperations(item.jobId)
                    queries.deleteJobBatches(item.jobId)
                }
                if (TranslationDeletionScope.ACCOUNTING in scopes) queries.deleteUsage(item.jobId)
            }
            true
        }

    private suspend fun snapshot(jobId: String): TranslationDeletionSnapshot {
        val groups = TranslationDeletionScope.entries.associateWith { Accumulator() }
        var job: TranslationJob? = null
        var jobFingerprint = "missing"
        var offset = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = queries.deletionRecords(jobId, 128, offset) { kind, id, payload ->
                if (kind == "job") {
                    job = TranslationJobSnapshot.decode(json, payload)
                    jobFingerprint = digest(payload.toByteArray())
                } else {
                    val scope = when (kind) {
                        "result", "review" -> TranslationDeletionScope.TRANSLATIONS
                        "operation" -> {
                            val operation = json.decodeFromString<TranslationOperation>(payload)
                            if (operation.geometryCorrection != null) {
                                TranslationDeletionScope.TRANSLATIONS
                            } else {
                                TranslationDeletionScope.LOGS
                            }
                        }
                        "event", "batch" -> TranslationDeletionScope.LOGS
                        "usage" -> TranslationDeletionScope.ACCOUNTING
                        else -> error("Unknown deletion record")
                    }
                    groups.getValue(scope).add(kind, id, payload)
                }
                Unit
            }.awaitAsList()
            if (page.size < 128) break
            offset += page.size
        }
        return TranslationDeletionSnapshot(jobId, job, jobFingerprint, groups.mapValues { it.value.finish() })
    }

    private class Accumulator {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var count = 0L
        private var bytes = 0L
        fun add(kind: String, id: String, payload: String) {
            val encoded = payload.toByteArray()
            listOf(kind.toByteArray(), id.toByteArray(), encoded).forEach { part ->
                digest.update(part.size.toString().toByteArray())
                digest.update(0.toByte())
                digest.update(part)
            }
            bytes += encoded.size
            count++
        }
        fun finish() = TranslationStoredRecords(count, bytes, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }
}
