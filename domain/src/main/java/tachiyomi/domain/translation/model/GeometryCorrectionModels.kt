package tachiyomi.domain.translation.model

import kotlinx.serialization.Serializable

@Serializable
enum class GeometryCorrectionState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, INTERRUPTED, SUPERSEDED }

@Serializable
enum class GeometryCorrectionAttemptState { RESERVED, SUCCEEDED, FAILED, INTERRUPTED }

@Serializable
data class GeometryCorrectionAttempt(
    val number: Int,
    val startedAt: Long,
    val state: GeometryCorrectionAttemptState = GeometryCorrectionAttemptState.RESERVED,
    val completedAt: Long? = null,
    val failureKind: TranslationFailureKind? = null,
    val message: String? = null,
)

/** One immutable rejected input and one correction pass; reserved attempts remain spent after interruption. */
@Serializable
data class GeometryCorrectionCheckpoint(
    val id: String,
    val jobId: String,
    val policyId: String,
    val image: TranslationImage,
    val transform: TranslationInputTransform,
    val settings: TranslationSettings,
    val ocr: List<OcrPageResult>,
    val context: String,
    val candidateJson: String,
    val issues: List<RegionGeometryIssue>,
    val state: GeometryCorrectionState = GeometryCorrectionState.QUEUED,
    val version: Long = 0,
    val attempts: List<GeometryCorrectionAttempt> = emptyList(),
    val result: TranslationPageResult? = null,
    val sourceRevision: Long? = null,
    val message: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val batchId: String? = null,
) {
    val maxTransportAttempts: Int get() = settings.provider.totalAttempts.coerceIn(1, 2)
}
