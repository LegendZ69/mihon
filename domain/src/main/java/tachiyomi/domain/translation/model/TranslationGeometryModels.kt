package tachiyomi.domain.translation.model

import kotlinx.serialization.Serializable

@Serializable
enum class GeometryIssueCode {
    INVALID_DIMENSIONS,
    VERTEX_COUNT,
    NONFINITE_COORDINATE,
    OUT_OF_BOUNDS,
    REPEATED_VERTEX,
    BACKTRACKING,
    SELF_INTERSECTION,
    ZERO_AREA,
    CONCAVE,
    INVALID_POLYGON,
}

/** Vertex indices always refer to the candidate before normalization. */
@Serializable
data class GeometryIssue(
    val code: GeometryIssueCode,
    val vertexIndices: List<Int> = emptyList(),
    val reason: String,
)

@Serializable
enum class PolygonNormalizationKind {
    REMOVED_CLOSING_VERTEX,
    REMOVED_CONSECUTIVE_DUPLICATE,
    REMOVED_COLLINEAR_VERTEX,
}

@Serializable
data class PolygonNormalizationChange(
    val kind: PolygonNormalizationKind,
    val originalVertexIndex: Int,
)

@Serializable
enum class PolygonNormalizationStatus { UNCHANGED, NORMALIZED, REJECTED }

/** A rejected outline must never be replaced by a rectangle or convex hull. */
@Serializable
data class PolygonNormalizationOutcome(
    val status: PolygonNormalizationStatus,
    val points: List<TranslationPoint>,
    val sourceVertexIndices: List<Int>,
    val changes: List<PolygonNormalizationChange> = emptyList(),
    val issues: List<GeometryIssue> = emptyList(),
) {
    val accepted: Boolean get() = status != PolygonNormalizationStatus.REJECTED
}
