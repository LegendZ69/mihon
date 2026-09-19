package tachiyomi.domain.translation.model

import kotlinx.serialization.Serializable

/** Application policy, independent of provider generation defaults. */
@Serializable
data class QualityReviewSettings(
    val enabled: Boolean = true,
    val coverage: QualityReviewCoverage = QualityReviewCoverage.FULL_PAGE_WHEN_AVAILABLE,
    val maxTransportAttempts: Int = 2,
    val includeRenderedPreview: Boolean = true,
) {
    fun validate() = require(maxTransportAttempts in 1..2) { "Review transport attempts must be 1–2" }
}

@Serializable
enum class QualityReviewCoverage { FULL_PAGE_WHEN_AVAILABLE, TEXT_ONLY }

@Serializable
enum class QualityReviewState {
    QUEUED,
    RUNNING,
    PAUSED,
    PASSED,
    REPAIRED,
    NEEDS_REVIEW,
    INCOMPLETE,
    SUPERSEDED,
    UNDONE,
    ;

    val pending: Boolean get() = this in setOf(QUEUED, RUNNING, PAUSED)
}

/** Findings and confidence are AI assessments, never human passage approval or measured OCR scores. */
@Serializable
data class QualityReviewFinding(
    val code: String,
    val description: String,
    val regionIds: List<String> = emptyList(),
    val aiConfidence: Float? = null,
)

@Serializable
data class QualityReviewAttempt(
    val number: Int,
    val startedAt: Long,
    val completedAt: Long? = null,
    val failureKind: TranslationFailureKind? = null,
    val message: String? = null,
    val usage: TranslationUsage? = null,
    val requestId: String? = null,
)

/** The baseline and attempt reservation survive interruption; a missing receipt remains a spent attempt. */
@Serializable
data class QualityReviewCheckpoint(
    val id: String,
    val jobId: String,
    val imageId: String,
    val sourceRevision: Long,
    val beforeResult: TranslationPageResult,
    val settings: QualityReviewSettings,
    val state: QualityReviewState = QualityReviewState.QUEUED,
    val attempts: List<QualityReviewAttempt> = emptyList(),
    val findings: List<QualityReviewFinding> = emptyList(),
    val message: String? = null,
    val repairedRevision: Long? = null,
    val visualComplete: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    // Missing on pre-preview checkpoints: do not change an already reserved input policy.
    val renderPreviewRequested: Boolean = false,
    val renderStyle: OverlayStyle? = null,
    val renderEvidence: QualityReviewRenderEvidence? = null,
    // Missing on legacy checkpoints: retain the translation job's saved content policy.
    val contentPolicy: TranslationContentPolicy? = null,
    // Null on legacy checkpoints falls back to the immutable originating job snapshot.
    val prompts: TranslationPromptPair? = null,
    // Pin runtime substitution data before first execution of newly snapshotted prompts.
    val promptContext: String? = null,
    // Explicit imported-page reviews can use current provider settings without changing the imported job.
    val executionSettings: TranslationSettings? = null,
)

data class QualityReviewRequest(
    val jobId: String,
    val reviewId: String,
    val settings: TranslationSettings,
    val image: TranslationImage,
    val baseline: TranslationPageResult,
    val context: String = "",
    val renderEvidence: QualityReviewRenderEvidence? = null,
)

/** Immutable auxiliary evidence; preview.id never becomes another translation target. */
@Serializable
data class QualityReviewRenderEvidence(
    val original: TranslationImage,
    val preview: TranslationImage,
    val sourceRevision: Long,
    val rendererVersion: String,
    val presentationFingerprint: String,
    val scaleX: Double,
    val scaleY: Double,
    val presentation: QualityReviewPresentation,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Resolved appearance used to create the diagnostic, independent of later preferences. */
@Serializable
data class QualityReviewPresentation(
    val requestedStyle: OverlayStyle,
    val resolvedStyle: OverlayStyle,
    val resolvedRegionStyles: Map<String, OverlayStyle> = emptyMap(),
    val fontIdentities: Map<String, String> = emptyMap(),
    val sampledBackgrounds: Map<String, Int> = emptyMap(),
    val layoutDiagnostics: List<RegionLayoutDiagnostic> = emptyList(),
    val requestedRegionStyles: Map<String, OverlayStyle?> = emptyMap(),
    val contentPolicy: TranslationContentPolicy = TranslationContentPolicy.Legacy,
)

data class QualityReviewResponse(
    val candidate: TranslationPageResult,
    val findings: List<QualityReviewFinding>,
    val usage: TranslationUsage = TranslationUsage(),
    val requestId: String? = null,
    val finishReason: String? = null,
    val visualComplete: Boolean = true,
)
