package tachiyomi.domain.translation.service

import tachiyomi.domain.translation.model.GeometryIssue
import tachiyomi.domain.translation.model.GeometryIssueCode
import tachiyomi.domain.translation.model.PolygonNormalizationChange
import tachiyomi.domain.translation.model.PolygonNormalizationKind
import tachiyomi.domain.translation.model.PolygonNormalizationOutcome
import tachiyomi.domain.translation.model.PolygonNormalizationStatus
import tachiyomi.domain.translation.model.TranslationPoint

/** Lossless cleanup of provider outlines; never infers missing geometry or changes perimeter order. */
object TranslationGeometry {
    const val MAX_INPUT_VERTICES = 256
    const val MAX_VERTICES = 32

    fun normalizeAndValidate(
        points: List<TranslationPoint>,
        width: Int,
        height: Int,
    ): PolygonNormalizationOutcome {
        if (points.size > MAX_INPUT_VERTICES) {
            return PolygonNormalizationOutcome(
                PolygonNormalizationStatus.REJECTED,
                points,
                emptyList(),
                issues = listOf(
                    GeometryIssue(
                        GeometryIssueCode.VERTEX_COUNT,
                        reason = "Polygon has ${points.size} input vertices; at most $MAX_INPUT_VERTICES are accepted",
                    ),
                ),
            )
        }
        val normalized = points.toMutableList()
        val indices = points.indices.toMutableList()
        val changes = mutableListOf<PolygonNormalizationChange>()
        fun outcome(issues: List<GeometryIssue>) = PolygonNormalizationOutcome(
            when {
                issues.isNotEmpty() -> PolygonNormalizationStatus.REJECTED
                changes.isNotEmpty() -> PolygonNormalizationStatus.NORMALIZED
                else -> PolygonNormalizationStatus.UNCHANGED
            },
            normalized.toList(),
            indices.toList(),
            changes.toList(),
            issues,
        )
        val inputIssues = buildList {
            if (width <= 0 || height <= 0) {
                add(
                    GeometryIssue(
                        GeometryIssueCode.INVALID_DIMENSIONS,
                        reason = "Original dimensions must be positive",
                    ),
                )
            }
            points.forEachIndexed { index, point ->
                if (!point.x.isFinite() || !point.y.isFinite()) {
                    add(
                        GeometryIssue(
                            GeometryIssueCode.NONFINITE_COORDINATE,
                            listOf(index),
                            "Vertex $index is not finite",
                        ),
                    )
                } else if (point.x !in 0f..width.toFloat() || point.y !in 0f..height.toFloat()) {
                    add(
                        GeometryIssue(
                            GeometryIssueCode.OUT_OF_BOUNDS,
                            listOf(index),
                            "Vertex $index (${point.x}, ${point.y}) is outside original image ${width}x$height",
                        ),
                    )
                }
            }
        }
        // Check the input before cleanup: an invalid coordinate cannot disappear as a duplicate.
        if (inputIssues.isNotEmpty()) return outcome(inputIssues)

        fun remove(index: Int, kind: PolygonNormalizationKind) {
            changes += PolygonNormalizationChange(kind, indices[index])
            normalized.removeAt(index)
            indices.removeAt(index)
        }
        while (normalized.size > 1 && sameLocation(normalized.first(), normalized.last())) {
            remove(normalized.lastIndex, PolygonNormalizationKind.REMOVED_CLOSING_VERTEX)
        }
        var current = 1
        while (current < normalized.size) {
            if (sameLocation(normalized[current], normalized[current - 1])) {
                remove(current, PolygonNormalizationKind.REMOVED_CONSECUTIVE_DUPLICATE)
            } else {
                current++
            }
        }

        // Reject retraced or intersecting outlines before removing any straight-edge points.
        val outlineIssues = outlineIssues(normalized, indices)
        if (outlineIssues.isNotEmpty()) return outcome(outlineIssues)
        current = 0
        while (current < normalized.size && normalized.size >= 3) {
            val before = normalized[(current + normalized.size - 1) % normalized.size]
            val point = normalized[current]
            val after = normalized[(current + 1) % normalized.size]
            if (cross(before, point, after) == 0.0 && between(before, point, after)) {
                remove(current, PolygonNormalizationKind.REMOVED_COLLINEAR_VERTEX)
                current = 0
            } else {
                current++
            }
        }
        val finalIssues = buildList {
            if (normalized.size !in 3..MAX_VERTICES) {
                add(
                    GeometryIssue(
                        GeometryIssueCode.VERTEX_COUNT,
                        indices,
                        "Polygon has ${normalized.size} distinct corners after cleanup; expected 3–$MAX_VERTICES",
                    ),
                )
            } else if (!QualityReviewValidation.isConvexPolygon(normalized)) {
                // Keep the existing minimum-turn predicate for saved and manually edited regions.
                add(
                    GeometryIssue(
                        GeometryIssueCode.INVALID_POLYGON,
                        indices,
                        "Polygon has a near-zero turn and cannot form a nondegenerate convex region",
                    ),
                )
            }
        }
        return outcome(finalIssues)
    }

    private fun outlineIssues(points: List<TranslationPoint>, indices: List<Int>): List<GeometryIssue> = buildList {
        if (points.size < 3) {
            add(
                GeometryIssue(
                    GeometryIssueCode.VERTEX_COUNT,
                    indices,
                    "Polygon has fewer than three distinct corners",
                ),
            )
            return@buildList
        }
        points.indices.groupBy {
            val point = points[it]
            TranslationPoint(if (point.x == 0f) 0f else point.x, if (point.y == 0f) 0f else point.y)
        }.values.filter { it.size > 1 }.forEach { repeated ->
            val original = repeated.map { indices[it] }
            add(
                GeometryIssue(
                    GeometryIssueCode.REPEATED_VERTEX,
                    original,
                    "Nonconsecutive vertices ${original.joinToString()} occupy the same point",
                ),
            )
        }
        val turnDirections = mutableSetOf<Int>()
        points.indices.forEach { index ->
            val previous = (index + points.size - 1) % points.size
            val next = (index + 1) % points.size
            val turn = cross(points[previous], points[index], points[next])
            if (turn == 0.0 && !between(points[previous], points[index], points[next])) {
                val original = listOf(indices[previous], indices[index], indices[next])
                add(
                    GeometryIssue(
                        GeometryIssueCode.BACKTRACKING,
                        original,
                        "Vertices ${original.joinToString()} reverse along the same edge",
                    ),
                )
            } else if (turn != 0.0) {
                turnDirections += if (turn > 0.0) 1 else -1
            }
            for (other in index + 1 until points.size) {
                if (other == next || (index == 0 && other == points.lastIndex)) continue
                val otherNext = (other + 1) % points.size
                if (intersects(points[index], points[next], points[other], points[otherNext])) {
                    val original = listOf(indices[index], indices[next], indices[other], indices[otherNext])
                    add(
                        GeometryIssue(
                            GeometryIssueCode.SELF_INTERSECTION,
                            original,
                            "Nonadjacent edges ${original[0]}–${original[1]} and " +
                                "${original[2]}–${original[3]} intersect",
                        ),
                    )
                }
            }
        }
        val twiceArea = points.indices.sumOf { index ->
            val next = points[(index + 1) % points.size]
            points[index].x.toDouble() * next.y - next.x.toDouble() * points[index].y
        }
        if (twiceArea == 0.0) {
            add(GeometryIssue(GeometryIssueCode.ZERO_AREA, indices, "Polygon encloses zero signed area"))
        }
        if (turnDirections.size > 1 && none { it.code == GeometryIssueCode.SELF_INTERSECTION }) {
            add(GeometryIssue(GeometryIssueCode.CONCAVE, indices, "Polygon changes turning direction and is concave"))
        }
    }

    private fun intersects(
        a: TranslationPoint,
        b: TranslationPoint,
        c: TranslationPoint,
        d: TranslationPoint,
    ): Boolean {
        val abc = cross(a, b, c)
        val abd = cross(a, b, d)
        val cda = cross(c, d, a)
        val cdb = cross(c, d, b)
        return (
            ((abc > 0.0 && abd < 0.0) || (abc < 0.0 && abd > 0.0)) &&
                ((cda > 0.0 && cdb < 0.0) || (cda < 0.0 && cdb > 0.0))
            ) ||
            (abc == 0.0 && between(a, c, b)) ||
            (abd == 0.0 && between(a, d, b)) ||
            (cda == 0.0 && between(c, a, d)) ||
            (cdb == 0.0 && between(c, b, d))
    }

    private fun cross(a: TranslationPoint, b: TranslationPoint, c: TranslationPoint): Double =
        (b.x.toDouble() - a.x) * (c.y.toDouble() - a.y) - (b.y.toDouble() - a.y) * (c.x.toDouble() - a.x)

    private fun sameLocation(a: TranslationPoint, b: TranslationPoint) = a.x == b.x && a.y == b.y

    private fun between(a: TranslationPoint, b: TranslationPoint, c: TranslationPoint): Boolean =
        b.x >= minOf(a.x, c.x) && b.x <= maxOf(a.x, c.x) && b.y >= minOf(a.y, c.y) && b.y <= maxOf(a.y, c.y)
}
