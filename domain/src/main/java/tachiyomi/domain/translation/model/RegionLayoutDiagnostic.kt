package tachiyomi.domain.translation.model

import kotlinx.serialization.Serializable

/** Deterministic presentation measurements; these are not AI assessments or OCR confidence. */
@Serializable
data class RegionLayoutDiagnostic(
    val regionId: String,
    val effectiveFontSize: Float,
    val preferredMinFontSize: Float,
    val belowPreferredMinimum: Boolean,
    val overflow: Boolean,
    val forcedWordBreaks: Int,
    val message: String? = null,
    val lineCount: Int = 0,
    val frameWidth: Float = 0f,
    val frameHeight: Float = 0f,
    val resolvedTextColor: Long? = null,
    val compositedBackgroundColor: Long? = null,
    val contrastRatio: Double? = null,
    val contrastBackgroundEstimated: Boolean = false,
    val customTextColor: Boolean = false,
)
