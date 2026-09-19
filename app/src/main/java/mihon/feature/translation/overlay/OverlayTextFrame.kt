package mihon.feature.translation.overlay

import tachiyomi.domain.translation.model.TranslationPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A centered text rectangle whose rotation stays within the source polygon. */
internal data class OverlayTextFrame(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
) {
    val left get() = centerX - width / 2
    val top get() = centerY - height / 2

    companion object {
        fun fit(
            points: List<TranslationPoint>,
            rotation: Float,
            padding: Float,
        ): OverlayTextFrame? {
            if (points.size < 3 || !rotation.isFinite() || !padding.isFinite() ||
                points.any { !it.x.isFinite() || !it.y.isFinite() }
            ) {
                return null
            }
            val centerX = points.map { it.x.toDouble() }.average()
            val centerY = points.map { it.y.toDouble() }.average()
            val twiceArea =
                points.indices.sumOf { index ->
                    val point = points[index]
                    val next = points[(index + 1) % points.size]
                    point.x.toDouble() * next.y - next.x.toDouble() * point.y
                }
            if (abs(twiceArea) < 1e-8) return null
            val direction = if (twiceArea > 0) 1 else -1
            val angle = Math.toRadians(rotation.toDouble())
            val cosine = cos(angle)
            val sine = sin(angle)
            val constraints =
                points.indices.mapNotNull { index ->
                    val point = points[index]
                    val next = points[(index + 1) % points.size]
                    val length = hypot((next.x - point.x).toDouble(), (next.y - point.y).toDouble())
                    if (length < 1e-8) return@mapNotNull null
                    val nx = direction * (next.y - point.y) / length
                    val ny = direction * (point.x - next.x) / length
                    val clearance = -nx * (centerX - point.x) - ny * (centerY - point.y)
                    Constraint(
                        abs(nx * cosine + ny * sine),
                        abs(-nx * sine + ny * cosine),
                        2 * (clearance - padding.coerceAtLeast(0f)),
                    )
                }
            if (constraints.size < 3 || constraints.any { it.limit <= 0 }) return null
            var bestWidth = 0.0
            var bestHeight = 0.0

            fun candidate(
                width: Double,
                height: Double,
            ) {
                if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 ||
                    constraints.any { it.width * width + it.height * height > it.limit + 1e-7 }
                ) {
                    return
                }
                if (width * height > bestWidth * bestHeight) {
                    bestWidth = width
                    bestHeight = height
                }
            }
            // A maximum-area rectangle touches either one edge constraint at its
            // product maximum, or two constraints at their intersection.
            constraints.forEachIndexed { index, first ->
                if (first.width > 1e-10 && first.height > 1e-10) {
                    candidate(first.limit / (2 * first.width), first.limit / (2 * first.height))
                }
                for (other in index + 1 until constraints.size) {
                    val second = constraints[other]
                    val determinant = first.width * second.height - second.width * first.height
                    if (abs(determinant) > 1e-10) {
                        candidate(
                            (first.limit * second.height - second.limit * first.height) / determinant,
                            (first.width * second.limit - second.width * first.limit) / determinant,
                        )
                    }
                }
            }
            if (bestWidth < 1 || bestHeight < 1) return null
            return OverlayTextFrame(centerX.toFloat(), centerY.toFloat(), bestWidth.toFloat(), bestHeight.toFloat())
        }
    }

    private data class Constraint(
        val width: Double,
        val height: Double,
        val limit: Double,
    )
}
