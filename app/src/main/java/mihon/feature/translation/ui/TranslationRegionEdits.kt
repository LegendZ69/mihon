package mihon.feature.translation.ui

import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import kotlin.math.abs

/** Manual inspector operations preserve raw transcription and measured OCR provenance. */
internal object TranslationRegionEdits {
    enum class SplitAxis { LEFT_RIGHT, TOP_BOTTOM }

    fun validPolygon(points: List<TranslationPoint>, width: Int, height: Int): Boolean {
        if (points.size !in 3..32 || points.distinct().size != points.size || width <= 0 || height <= 0) return false
        if (points.any {
                !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..width.toFloat() ||
                    it.y !in 0f..height.toFloat()
            }
        ) {
            return false
        }
        var direction = 0
        var twiceArea = 0.0
        points.indices.forEach { index ->
            val a = points[index]
            val b = points[(index + 1) % points.size]
            val c = points[(index + 2) % points.size]
            if (a == b) return false
            twiceArea += a.x.toDouble() * b.y - b.x.toDouble() * a.y
            val cross = (b.x - a.x).toDouble() * (c.y - b.y) - (b.y - a.y).toDouble() * (c.x - b.x)
            if (abs(cross) < 0.000001) return false
            val sign = if (cross > 0) 1 else -1
            if (direction != 0 && direction != sign) return false
            direction = sign
        }
        if (direction == 0 || abs(twiceArea) < 2.0) return false
        // Local turns alone also accept star polygons. Every vertex must remain
        // on the same interior side of every edge of a convex, simple polygon.
        return points.indices.all { index ->
            val a = points[index]
            val b = points[(index + 1) % points.size]
            points.all { point ->
                val cross = (b.x - a.x).toDouble() * (point.y - a.y) - (b.y - a.y).toDouble() * (point.x - a.x)
                cross * direction >= -0.00001
            }
        }
    }

    fun moveCorner(
        page: TranslationPageResult,
        regionId: String,
        corner: Int,
        point: TranslationPoint,
    ): TranslationPageResult {
        require(point.x.isFinite() && point.y.isFinite()) { "Coordinates must be finite" }
        val region = page.regions.first { it.id == regionId }
        require(corner in region.points.indices)
        val bounded =
            TranslationPoint(point.x.coerceIn(0f, page.width.toFloat()), point.y.coerceIn(0f, page.height.toFloat()))
        val points = region.points.toMutableList().apply { this[corner] = bounded }
        require(validPolygon(points, page.width, page.height)) { "Keep the polygon convex and its corners in order" }
        return page.copy(regions = page.regions.map { if (it.id == regionId) it.copy(points = points) else it })
    }

    fun update(page: TranslationPageResult, edited: TextRegion): TranslationPageResult {
        val original = page.regions.first { it.id == edited.id }
        require(validPolygon(edited.points, page.width, page.height)) {
            "Use a convex polygon inside the original image"
        }
        require(edited.rotation.isFinite() && edited.rotation in -360f..360f) {
            "Rotation must be between -360 and 360 degrees"
        }
        require(edited.readingOrder in page.regions.indices) {
            "Choose an order between 0 and ${page.regions.lastIndex}"
        }
        val preserved = edited.copy(
            sourceText = original.sourceText,
            detectionConfidence = original.detectionConfidence,
            recognitionConfidence = original.recognitionConfidence,
            aiConfidence = original.aiConfidence,
        )
        val ordered = page.regions.sortedBy { it.readingOrder }.filterNot { it.id == edited.id }.toMutableList()
        ordered.add(edited.readingOrder, preserved)
        return page.copy(regions = ordered.mapIndexed { index, region -> region.copy(readingOrder = index) })
    }

    fun moveOrder(page: TranslationPageResult, regionId: String, delta: Int): TranslationPageResult {
        val ordered = page.regions.sortedBy { it.readingOrder }
        val from = ordered.indexOfFirst { it.id == regionId }
        require(from >= 0)
        return update(page, ordered[from].copy(readingOrder = (from + delta).coerceIn(ordered.indices)))
    }

    fun rotate(page: TranslationPageResult, regionId: String, degrees: Int): TranslationPageResult {
        require(degrees == -90 || degrees == 90)
        val region = page.regions.first { it.id == regionId }
        val rotation = ((region.rotation + degrees + 180f) % 360f + 360f) % 360f - 180f
        return update(page, region.copy(rotation = rotation))
    }

    fun split(
        page: TranslationPageResult,
        regionId: String,
        axis: SplitAxis,
        firstSource: String,
        firstTranslation: String,
        secondSource: String,
        secondTranslation: String,
        firstId: String,
        secondId: String,
    ): TranslationPageResult {
        require(listOf(firstSource, firstTranslation, secondSource, secondTranslation).all { it.isNotBlank() }) {
            "Assign source and translated text to both halves explicitly"
        }
        require(firstId.isNotBlank() && secondId.isNotBlank() && firstId != secondId)
        require(page.regions.none { it.id == firstId || it.id == secondId })
        val original = page.regions.first { it.id == regionId }
        require(validPolygon(original.points, page.width, page.height)) { "Split requires a convex region" }
        val halves = splitPoints(original.points, axis)
        require(halves.all { validPolygon(it, page.width, page.height) }) { "The region is too small to split" }
        fun child(id: String, points: List<TranslationPoint>, source: String, translation: String) = original.copy(
            id = id,
            points = points,
            sourceText = "",
            correctedText = source,
            translatedText = translation,
            included = true,
            ignoredReason = null,
            detectionConfidence = null,
            recognitionConfidence = null,
            aiConfidence = null,
        )
        val ordered = page.regions.sortedBy { it.readingOrder }.flatMap { region ->
            if (region.id != regionId) {
                listOf(region)
            } else {
                listOf(
                    original.copy(
                        included = false,
                        ignoredReason = "Manually split into $firstId and $secondId; original OCR retained",
                    ),
                    child(firstId, halves[0], firstSource, firstTranslation),
                    child(secondId, halves[1], secondSource, secondTranslation),
                )
            }
        }
        return page.copy(regions = ordered.mapIndexed { index, region -> region.copy(readingOrder = index) })
    }

    private fun splitPoints(points: List<TranslationPoint>, axis: SplitAxis): List<List<TranslationPoint>> {
        fun coordinate(point: TranslationPoint) = if (axis == SplitAxis.LEFT_RIGHT) point.x else point.y
        val middle = (points.minOf(::coordinate) + points.maxOf(::coordinate)) / 2f
        return listOf(true, false).map { first ->
            val clipped = mutableListOf<TranslationPoint>()
            points.indices.forEach { index ->
                val a = points[index]
                val b = points[(index + 1) % points.size]
                val aInside = if (first) coordinate(a) <= middle else coordinate(a) >= middle
                val bInside = if (first) coordinate(b) <= middle else coordinate(b) >= middle
                if (aInside) clipped += a
                if (aInside != bInside) {
                    val fraction = (middle - coordinate(a)) / (coordinate(b) - coordinate(a))
                    clipped += TranslationPoint(a.x + fraction * (b.x - a.x), a.y + fraction * (b.y - a.y))
                }
            }
            clipped.distinct()
        }
    }
}
