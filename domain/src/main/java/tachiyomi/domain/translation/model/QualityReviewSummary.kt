package tachiyomi.domain.translation.model

/** Compact presentation facts; the pre-repair result, OCR and render evidence are not loaded. */
data class QualityReviewSummary(
    val id: String,
    val jobId: String,
    val imageId: String,
    val state: QualityReviewState,
    val updatedAt: Long,
    val message: String? = null,
    val findings: List<QualityReviewFinding> = emptyList(),
)
