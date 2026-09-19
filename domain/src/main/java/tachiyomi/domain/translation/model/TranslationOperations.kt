package tachiyomi.domain.translation.model

import kotlinx.serialization.Serializable

/** A real unit of work, independent of whether verbose diagnostics are retained. */
@Serializable
enum class TranslationStage(val label: String) {
    ACQUISITION("Acquire original images"),
    MODEL_MANIFEST("Resolve model pack"),
    MODEL_DOWNLOAD("Download model artifact"),
    MODEL_VERIFY("Verify model artifact"),
    MODEL_INSTALL("Install model pack"),
    PREPROCESS("Prepare image"),
    DETECTION("Detect text regions"),
    RECOGNITION("Recognize source text"),
    LOCAL_OCR("Local OCR"),
    AI_OCR_TRANSLATION("AI OCR and translation"),
    TEXT_TRANSLATION("Translate recognized text"),
    TOKEN_COUNT("Count input tokens"),
    REQUEST("Provider request"),
    VALIDATION("Validate provider output"),
    GEOMETRY_CORRECTION("Correct rejected region geometry"),
    SAVE("Save translation"),
    REVIEW("Review saved page"),
    RENDER("Render overlay"),
    EXPORT("Export"),
    DELETE("Delete selected translation data"),
    RESTORE("Restore backup"),
    BILLING_SYNC("Synchronize cloud accounting"),
    ACCOUNTING_IMPORT("Import accounting statement"),
    LEGACY("Historical stage unavailable"),
}

@Serializable
enum class TranslationOperationState {
    QUEUED,
    ACTIVE,
    WAITING,
    RETRY,
    COMPLETED,
    PARTIAL,
    FAILED,
    PAUSED,
    CANCELLED,
    INTERRUPTED,
    ;

    val terminal: Boolean
        get() = this in setOf(COMPLETED, PARTIAL, FAILED, CANCELLED, INTERRUPTED)
}

@Serializable
enum class TranslationProgressUnit(val label: String) {
    PAGES("pages"),
    REGIONS("regions"),
    BYTES("bytes"),
    ARTIFACTS("artifacts"),
    REQUESTS("requests"),
    STEPS("steps"),
}

@Serializable
data class TranslationOperation(
    val id: String,
    val jobId: String,
    val stage: TranslationStage,
    val parentId: String? = null,
    val batchId: String? = null,
    val imageId: String? = null,
    val reviewId: String? = null,
    val artifactId: String? = null,
    val state: TranslationOperationState = TranslationOperationState.QUEUED,
    val completed: Long = 0,
    val total: Long? = null,
    val unit: TranslationProgressUnit = TranslationProgressUnit.STEPS,
    val attempt: Int? = null,
    val startedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val message: String? = null,
    val requestId: String? = null,
    val captureId: String? = null,
    val processSession: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val geometryCorrection: GeometryCorrectionCheckpoint? = null,
) {
    fun validate() {
        require(id.isNotBlank() && jobId.isNotBlank())
        require(completed >= 0 && (total == null || total >= completed))
        require(attempt == null || attempt > 0)
        require(endedAt == null || startedAt == null || endedAt >= startedAt)
    }
}

/** Stable attempt identity deduplicates replay; missing token/cost fields remain unknown. */
@Serializable
data class TranslationUsageRecord(
    val id: String,
    val jobId: String,
    val operationId: String? = null,
    val batchId: String? = null,
    val reviewId: String? = null,
    val requestId: String? = null,
    val provider: String,
    val model: String,
    val time: Long,
    val usage: TranslationUsage = TranslationUsage(),
    val estimatedUsd: String? = null,
    val pricingSource: String? = null,
    val pricingVerifiedAt: String? = null,
    val exchangeRate: String? = null,
    val exchangeRateSource: String? = null,
    val reservedCurrency: String? = null,
    val reservedAmount: String? = null,
    val outcomeUncertain: Boolean = false,
)

@Serializable
enum class TranslationBillingSource { GOOGLE_BIGQUERY, OPENAI_ORGANIZATION, IMPORTED_STATEMENT }

@Serializable
data class TranslationBillingSnapshot(
    val id: String,
    val connectionId: String,
    val source: TranslationBillingSource,
    val scope: String,
    val periodStart: Long,
    val periodEnd: Long,
    val syncedAt: Long,
    val currency: String,
    val amount: String,
    val invoice: Boolean = false,
    val sourceUrl: String,
    val notes: String = "",
)

data class TranslationLogQuery(
    val jobId: String? = null,
    val operationId: String? = null,
    val batchId: String? = null,
    val imageId: String? = null,
    val level: String? = null,
    val search: String = "",
    val limit: Long = 100,
    val offset: Long = 0,
    val since: Long? = null,
    val until: Long? = null,
    val provider: String? = null,
    val model: String? = null,
)
