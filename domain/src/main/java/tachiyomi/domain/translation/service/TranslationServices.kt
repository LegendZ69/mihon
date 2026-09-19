package tachiyomi.domain.translation.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.translation.model.GeometryCorrectionCheckpoint
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrSettings
import tachiyomi.domain.translation.model.ProviderCapabilities
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.QualityReviewCheckpoint
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.QualityReviewResponse
import tachiyomi.domain.translation.model.QualityReviewSettings
import tachiyomi.domain.translation.model.TranslationBatch
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationResponse
import tachiyomi.domain.translation.model.TranslationSettings

interface TranslationProvider {
    fun capabilities(settings: ProviderSettings): ProviderCapabilities
    suspend fun testConnection(settings: ProviderSettings): String
    suspend fun countTokens(request: TranslationRequest): Long?
    suspend fun translate(request: TranslationRequest): TranslationResponse

    /** One transport attempt; the durable coordinator owns retry limits. */
    suspend fun review(request: QualityReviewRequest): QualityReviewResponse =
        throw UnsupportedOperationException("Provider review is unavailable")
}

interface OcrEngine {
    suspend fun recognize(image: TranslationImage, settings: OcrSettings): OcrPageResult
    suspend fun release()
}

interface TranslationRepository {
    /** Compact optimistic-lock snapshot, taken before provider dispatch. */
    suspend fun resultRevision(jobId: String, imageId: String): Long? =
        results(jobId).firstOrNull { it.imageId == imageId }?.revision

    suspend fun geometryCorrection(id: String): GeometryCorrectionCheckpoint? = null
    suspend fun createGeometryCorrection(checkpoint: GeometryCorrectionCheckpoint): GeometryCorrectionCheckpoint? =
        throw UnsupportedOperationException("Atomic geometry correction persistence is unavailable")
    suspend fun compareAndSetGeometryCorrection(
        expected: GeometryCorrectionCheckpoint,
        updated: GeometryCorrectionCheckpoint,
    ): Boolean = throw UnsupportedOperationException("Atomic geometry correction persistence is unavailable")

    suspend fun chapterSeriesIds(): List<Long> = emptyList()
    fun observeOperations(
        jobId: String? = null,
        limit: Long = 200,
        offset: Long = 0,
    ): Flow<List<tachiyomi.domain.translation.model.TranslationOperation>> = flowOf(emptyList())

    /** Read-only history page; unlike the live observer, this does not reconcile process sessions. */
    suspend fun operationPage(
        jobId: String? = null,
        limit: Long = 100,
        offset: Long = 0,
        stage: tachiyomi.domain.translation.model.TranslationStage? = null,
        since: Long? = null,
        until: Long? = null,
        provider: String? = null,
        model: String? = null,
        state: tachiyomi.domain.translation.model.TranslationOperationState? = null,
    ): List<tachiyomi.domain.translation.model.TranslationOperation> = emptyList()
    suspend fun saveOperation(operation: tachiyomi.domain.translation.model.TranslationOperation) = Unit
    fun observeEventPage(
        query: tachiyomi.domain.translation.model.TranslationLogQuery,
    ): Flow<List<TranslationEvent>> = observeEvents(query.jobId)
    suspend fun eventPage(
        query: tachiyomi.domain.translation.model.TranslationLogQuery,
    ): List<TranslationEvent> = emptyList()
    suspend fun usage(id: String): tachiyomi.domain.translation.model.TranslationUsageRecord? = null
    suspend fun usagePage(
        since: Long,
        until: Long,
        provider: String? = null,
        model: String? = null,
        limit: Long = 100,
        offset: Long = 0,
        jobId: String? = null,
    ): List<tachiyomi.domain.translation.model.TranslationUsageRecord> = emptyList()
    suspend fun saveUsage(record: tachiyomi.domain.translation.model.TranslationUsageRecord) = Unit
    fun observeUsage(
        since: Long = 0,
    ): Flow<List<tachiyomi.domain.translation.model.TranslationUsageRecord>> = flowOf(emptyList())
    suspend fun saveBilling(snapshot: tachiyomi.domain.translation.model.TranslationBillingSnapshot) = Unit
    fun observeBilling(): Flow<List<tachiyomi.domain.translation.model.TranslationBillingSnapshot>> = flowOf(
        emptyList(),
    )
    suspend fun billingStats(connectionId: String): tachiyomi.domain.translation.model.TranslationBillingStorageStats =
        tachiyomi.domain.translation.model.TranslationBillingStorageStats()
    suspend fun deleteJobEvents(jobId: String) = Unit
    suspend fun deleteOperations(jobId: String) = Unit
    suspend fun deleteUsage(jobId: String) = Unit
    suspend fun deleteBilling(connectionId: String) = Unit

    /** Compact source identities only; production queries do not decode raw OCR or region payloads. */
    suspend fun chapterCoverage(chapterId: Long): TranslationCoverage {
        val history = jobs().filter { it.chapterId == chapterId }.sortedByDescending { it.createdAt }
        val originals = history.firstNotNullOfOrNull { images(it.id).takeIf { list -> list.isNotEmpty() } }.orEmpty()
        return TranslationCoverage.calculate(
            originals,
            history.flatMap {
                results(it.id)
            },
            history.firstOrNull()?.imageCount?.takeIf {
                it >
                    0
            },
        )
    }
    fun observeChapterCoverage(
        chapterId: Long,
    ): Flow<TranslationCoverage> = kotlinx.coroutines.flow.flow { emit(chapterCoverage(chapterId)) }
    fun observeJobs(): Flow<List<TranslationJob>>
    fun observeEvents(jobId: String? = null): Flow<List<TranslationEvent>>
    fun observeResults(chapterId: Long): Flow<List<TranslationPageResult>>
    suspend fun jobs(): List<TranslationJob>
    suspend fun saveJob(job: TranslationJob)

    /** Atomically create a replacement and reuse identity-matched saved pages without changing their revisions. */
    suspend fun replaceUnfinishedJob(source: TranslationJob, replacement: TranslationJob): TranslationJob? =
        throw UnsupportedOperationException("Atomic replacement persistence is unavailable")
    suspend fun removeJob(id: String)
    suspend fun images(jobId: String): List<TranslationImage>
    suspend fun saveImages(jobId: String, images: List<TranslationImage>)
    suspend fun results(jobId: String): List<TranslationPageResult>
    suspend fun saveResult(jobId: String, result: TranslationPageResult)
    suspend fun replaceResult(jobId: String, result: TranslationPageResult, expectedRevision: Long): Boolean {
        if (results(jobId).firstOrNull { it.imageId == result.imageId }?.revision != expectedRevision) return false
        saveResult(jobId, result)
        return true
    }

    /** Save the initial page and its optional review checkpoint in the same transaction. */
    suspend fun saveNewResult(jobId: String, result: TranslationPageResult, review: QualityReviewSettings) {
        check(!review.enabled) { "Atomic review persistence is unavailable" }
        saveResult(jobId, result)
    }
    fun observeReviewSummaries(
        jobId: String? = null,
    ): Flow<List<tachiyomi.domain.translation.model.QualityReviewSummary>> = flowOf(emptyList())
    suspend fun reviewSummaries(
        jobId: String? = null,
    ): List<tachiyomi.domain.translation.model.QualityReviewSummary> = emptyList()
    fun observeReviews(jobId: String? = null): Flow<List<QualityReviewCheckpoint>> = flowOf(emptyList())
    suspend fun reviews(jobId: String? = null): List<QualityReviewCheckpoint> = emptyList()
    suspend fun createReview(
        jobId: String,
        imageId: String,
        settings: QualityReviewSettings,
    ): QualityReviewCheckpoint? =
        throw UnsupportedOperationException("Review persistence is unavailable")

    /** Queue explicit page review and its job snapshot together; retain existing attempt reservations. */
    suspend fun scheduleReviews(
        job: TranslationJob,
        imageIds: Set<String>,
        settings: QualityReviewSettings,
        contentPolicy: TranslationContentPolicy = job.settings.contentPolicy,
        prompts: tachiyomi.domain.translation.model.TranslationPromptPair = job.settings.prompts.qualityReview,
        executionSettings: TranslationSettings? = null,
    ): Boolean =
        throw UnsupportedOperationException("Review persistence is unavailable")
    suspend fun saveReview(review: QualityReviewCheckpoint): Boolean =
        throw UnsupportedOperationException("Review persistence is unavailable")

    /** Compare the durable attempt count before reserving exactly one further attempt. */
    suspend fun reserveReviewAttempt(review: QualityReviewCheckpoint): Boolean =
        throw UnsupportedOperationException("Review persistence is unavailable")

    /** Atomically compare the source revision, commit the candidate and close its review. */
    suspend fun completeReview(review: QualityReviewCheckpoint, candidate: TranslationPageResult?): Boolean =
        throw UnsupportedOperationException("Review persistence is unavailable")
    suspend fun undoRepair(jobId: String, imageId: String): Boolean =
        throw UnsupportedOperationException("Review persistence is unavailable")
    suspend fun batches(jobId: String): List<TranslationBatch>
    suspend fun saveBatch(batch: TranslationBatch)
    suspend fun addEvent(event: TranslationEvent)
    suspend fun deleteEvents(before: Long)
    suspend fun deleteResults(jobId: String)
}
