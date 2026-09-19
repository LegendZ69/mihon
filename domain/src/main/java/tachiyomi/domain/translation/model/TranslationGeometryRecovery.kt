package tachiyomi.domain.translation.model

import kotlinx.serialization.Serializable

/** Application policy; provider documentation does not prescribe automatic geometry repair. */
@Serializable
data class GeometryRecoverySettings(val enabled: Boolean = true)

@Serializable
data class RegionGeometryIssue(val regionId: String, val issue: GeometryIssue)

/** Exact mapping from one supplied image/tile into its unchanged original image. */
@Serializable
data class TranslationInputTransform(
    val original: TranslationImage,
    val left: Int = 0,
    val top: Int = 0,
    val cropWidth: Int = original.width,
    val cropHeight: Int = original.height,
    val inputWidth: Int = cropWidth,
    val inputHeight: Int = cropHeight,
) {
    fun validate(input: TranslationImage) {
        require(original.width > 0 && original.height > 0)
        require(left >= 0 && top >= 0 && cropWidth > 0 && cropHeight > 0)
        require(left.toLong() + cropWidth <= original.width && top.toLong() + cropHeight <= original.height)
        require(inputWidth == input.width && inputHeight == input.height && inputWidth > 0 && inputHeight > 0)
    }

    fun toOriginal(point: TranslationPoint) = TranslationPoint(
        left + point.x * cropWidth / inputWidth,
        top + point.y * cropHeight / inputHeight,
    )
}
