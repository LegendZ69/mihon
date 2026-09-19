package mihon.feature.translation.ocr

import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Coordinates here always refer to original image pixels, including after region decoding. */
internal object PaddleOcrGeometry {
    const val MAX_TILE_PIXELS = 2 * 1024 * 1024
    private const val MAX_TILE_SIDE = 2048

    data class Tile(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    fun tiles(width: Int, height: Int, overlap: Int, pixelBudget: Int = MAX_TILE_PIXELS): List<Tile> {
        require(width > 0 && height > 0)
        require(overlap in 0..256) { "OCR tile overlap must be 0–256 pixels" }
        require(pixelBudget in 4096..MAX_TILE_PIXELS)
        val tileWidth = min(width, min(MAX_TILE_SIDE, sqrt(pixelBudget.toDouble()).toInt()))
        val tileHeight = min(height, min(MAX_TILE_SIDE, pixelBudget / tileWidth))
        val xs = starts(width, tileWidth, overlap.coerceAtMost((tileWidth - 1) / 2))
        val ys = starts(height, tileHeight, overlap.coerceAtMost((tileHeight - 1) / 2))
        require(xs.size.toLong() * ys.size <= 10000) { "Image exceeds the OCR tile count limit" }
        return ys.flatMap { y -> xs.map { x -> Tile(x, y, min(x + tileWidth, width), min(y + tileHeight, height)) } }
    }

    private fun starts(total: Int, limit: Int, overlap: Int): List<Int> {
        if (total <= limit) return listOf(0)
        val values = mutableListOf(0)
        val step = limit - overlap * 2
        var position = 0
        while (position.toLong() + limit < total) {
            position = min(position.toLong() + step, total.toLong() - limit).toInt()
            values += position
            require(values.size <= 10000) { "Image exceeds the OCR tile count limit" }
        }
        return values
    }

    fun restore(
        points: List<TranslationPoint>,
        tile: Tile,
        decodedWidth: Int,
        decodedHeight: Int,
    ): List<TranslationPoint> = points.map {
        TranslationPoint(
            x = (tile.left + it.x * tile.width / decodedWidth).coerceIn(tile.left.toFloat(), tile.right.toFloat()),
            y = (tile.top + it.y * tile.height / decodedHeight).coerceIn(tile.top.toFloat(), tile.bottom.toFloat()),
        )
    }

    fun rotation(points: List<TranslationPoint>): Float =
        if (points.size < 2) {
            0f
        } else {
            Math.toDegrees(
                atan2((points[1].y - points[0].y).toDouble(), (points[1].x - points[0].x).toDouble()),
            ).toFloat()
        }

    fun deduplicate(regions: List<TextRegion>): List<TextRegion> {
        val kept = mutableListOf<TextRegion>()
        // Prefer the more confident transcription where overlapping tiles detected the same line.
        for (candidate in regions.sortedByDescending { it.recognitionConfidence ?: 0f }) {
            val candidateBounds = Bounds(candidate)
            if (kept.none { existing -> candidateBounds.overlapOfSmaller(Bounds(existing)) > 0.75f }) {
                kept += candidate
            }
        }
        return kept
    }

    fun order(regions: List<TextRegion>, setting: String, language: String): List<TextRegion> {
        if (regions.isEmpty()) return emptyList()
        val mode = setting.lowercase()
        val rightToLeft = mode in setOf("rtl", "right-to-left", "vertical", "vertical-rtl") ||
            (mode == "auto" && language.lowercase().substringBefore('-') in setOf("ja", "jpn", "japanese"))
        val vertical = mode in setOf("vertical", "vertical-rtl")
        val ordered = if (vertical) {
            regions.sortedWith(compareBy<TextRegion> { -Bounds(it).right }.thenBy { Bounds(it).top })
        } else {
            // First group overlapping text rows; sort each row in the requested script direction.
            val pending = regions.sortedBy { Bounds(it).top }.toMutableList()
            buildList {
                while (pending.isNotEmpty()) {
                    val first = pending.removeAt(0)
                    val rowBounds = Bounds(first)
                    val row = mutableListOf(first)
                    val iterator = pending.iterator()
                    while (iterator.hasNext()) {
                        val next = iterator.next()
                        val box = Bounds(next)
                        if (abs(box.centerY - rowBounds.centerY) <= min(box.height, rowBounds.height) * 0.5f) {
                            row += next
                            iterator.remove()
                        }
                    }
                    val orderedRow = if (rightToLeft) {
                        row.sortedByDescending { Bounds(it).right }
                    } else {
                        row.sortedBy { Bounds(it).left }
                    }
                    addAll(orderedRow)
                }
            }
        }
        return ordered.mapIndexed { index, region -> region.copy(readingOrder = index) }
    }

    private class Bounds(region: TextRegion) {
        val left = region.points.minOf { it.x }
        val right = region.points.maxOf { it.x }
        val top = region.points.minOf { it.y }
        val bottom = region.points.maxOf { it.y }
        val height = bottom - top
        val centerY = (top + bottom) / 2
        private val area = (right - left) * height

        fun overlapOfSmaller(other: Bounds): Float {
            val intersection = max(0f, min(right, other.right) - max(left, other.left)) *
                max(0f, min(bottom, other.bottom) - max(top, other.top))
            return intersection / min(area, other.area).coerceAtLeast(1f)
        }
    }
}
