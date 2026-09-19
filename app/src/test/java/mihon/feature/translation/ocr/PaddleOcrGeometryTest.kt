package mihon.feature.translation.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationPoint

class PaddleOcrGeometryTest {
    @Test
    fun `long webtoon covers the full original with bounded overlapping tiles`() {
        val tiles = PaddleOcrGeometry.tiles(1440, 50000, 64)
        assertEquals(0, tiles.first().top)
        assertEquals(50000, tiles.last().bottom)
        assertTrue(tiles.all { it.width.toLong() * it.height <= PaddleOcrGeometry.MAX_TILE_PIXELS })
        assertTrue(tiles.zipWithNext().all { (first, second) -> second.top < first.bottom })
    }

    @Test
    fun `wide pages are tiled in both dimensions without a gap`() {
        val tiles = PaddleOcrGeometry.tiles(8000, 6000, 64)
        for (y in 0 until 6000 step 113) {
            for (x in 0 until 8000 step 127) {
                assertTrue(tiles.any { x in it.left until it.right && y in it.top until it.bottom })
            }
        }
    }

    @Test
    fun `lower decoded memory budget produces smaller tiles without gaps`() {
        val budget = 32 * 1024 * 1024 / 64
        val tiles = PaddleOcrGeometry.tiles(1440, 5000, 64, budget)
        assertTrue(tiles.all { it.width.toLong() * it.height <= budget })
        assertTrue(tiles.size > PaddleOcrGeometry.tiles(1440, 5000, 64).size)
        for (y in 0 until 5000 step 97) {
            for (x in 0 until 1440 step 83) {
                assertTrue(tiles.any { x in it.left until it.right && y in it.top until it.bottom })
            }
        }
    }

    @Test
    fun `geometry restores decoder scale and tile offset`() {
        val result = PaddleOcrGeometry.restore(
            listOf(TranslationPoint(10f, 20f)),
            PaddleOcrGeometry.Tile(100, 1000, 300, 1400),
            100,
            200,
        ).single()
        assertEquals(TranslationPoint(120f, 1040f), result)
    }

    @Test
    fun `duplicate overlap chooses stronger transcription but repeated separate text survives`() {
        val weaker = region("weak", 0f, 0f, 0.4f)
        val stronger = region("strong", 2f, 1f, 0.9f)
        val separate = region("other", 200f, 0f, 0.8f)
        assertEquals(
            setOf("strong", "other"),
            PaddleOcrGeometry.deduplicate(listOf(weaker, stronger, separate)).map {
                it.id
            }.toSet(),
        )
    }

    @Test
    fun `Japanese and left to right reading orders preserve rows`() {
        val left = region("left", 0f, 0f)
        val right = region("right", 200f, 0f)
        val nextRow = region("next", 0f, 100f)
        assertEquals(
            listOf("right", "left", "next"),
            PaddleOcrGeometry.order(listOf(nextRow, left, right), "auto", "ja").map {
                it.id
            },
        )
        assertEquals(
            listOf("left", "right", "next"),
            PaddleOcrGeometry.order(listOf(nextRow, right, left), "ltr", "ja").map {
                it.id
            },
        )
    }

    private fun region(id: String, x: Float, y: Float, confidence: Float = 1f) = TextRegion(
        id = id,
        sourceText = "同じ",
        points = listOf(
            TranslationPoint(x, y),
            TranslationPoint(x + 100, y),
            TranslationPoint(x + 100, y + 40),
            TranslationPoint(x, y + 40),
        ),
        recognitionConfidence = confidence,
    )
}
