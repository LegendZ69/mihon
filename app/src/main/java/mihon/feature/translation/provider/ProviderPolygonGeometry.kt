package mihon.feature.translation.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import tachiyomi.domain.translation.model.GeometryIssue
import tachiyomi.domain.translation.model.GeometryIssueCode
import tachiyomi.domain.translation.model.PolygonNormalizationOutcome
import tachiyomi.domain.translation.model.PolygonNormalizationStatus
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.service.QualityReviewValidation
import tachiyomi.domain.translation.service.TranslationGeometry

internal object ProviderPolygonGeometry {
    fun parse(value: JsonElement?, width: Int, height: Int): PolygonNormalizationOutcome? {
        if (value == null || value == JsonNull) return null
        fun invalid(
            reason: String,
            indices: List<Int> = emptyList(),
            code: GeometryIssueCode = GeometryIssueCode.INVALID_POLYGON,
        ) =
            PolygonNormalizationOutcome(
                PolygonNormalizationStatus.REJECTED,
                emptyList(),
                emptyList(),
                issues = listOf(GeometryIssue(code, indices, reason)),
            )
        val vertices = value as? JsonArray ?: return invalid("Polygon must be an array of [x,y] vertices")
        if (vertices.size !in
            3..256
        ) {
            return invalid(
                "Polygon must have 3–256 input vertices and at most 32 after lossless normalization",
                code = GeometryIssueCode.VERTEX_COUNT,
            )
        }
        val points = mutableListOf<TranslationPoint>()
        vertices.forEachIndexed { index, vertex ->
            val pair = vertex as? JsonArray ?: return invalid("Vertex $index must be an [x,y] array", listOf(index))
            if (pair.size != 2) return invalid("Vertex $index needs exactly x and y", listOf(index))
            val values = pair.map { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.floatOrNull }
            if (values.any { it == null || !it.isFinite() }) {
                return invalid(
                    "Vertex $index requires finite numeric x and y",
                    listOf(index),
                    GeometryIssueCode.NONFINITE_COORDINATE,
                )
            }
            points += TranslationPoint(values[0]!!, values[1]!!)
        }
        val normalized = TranslationGeometry.normalizeAndValidate(points, 1000, 1000)
        if (!normalized.accepted) return normalized
        val restored = normalized.points.map { TranslationPoint(it.x * width / 1000, it.y * height / 1000) }
        if (width <= 0 || height <= 0 || !QualityReviewValidation.isConvexPolygon(restored)) {
            return normalized.copy(
                status = PolygonNormalizationStatus.REJECTED,
                issues = listOf(
                    GeometryIssue(
                        GeometryIssueCode.INVALID_POLYGON,
                        normalized.sourceVertexIndices,
                        "Polygon loses a valid outline in original image coordinates",
                    ),
                ),
            )
        }
        return normalized.copy(points = restored)
    }
}
