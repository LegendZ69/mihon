package mihon.feature.translation.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.QualityReviewCheckpoint
import tachiyomi.domain.translation.model.QualityReviewSettings
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint

class TranslationRegionEditsTest {
    private val box =
        listOf(
            TranslationPoint(10f, 10f),
            TranslationPoint(90f, 10f),
            TranslationPoint(90f, 70f),
            TranslationPoint(10f, 70f),
        )
    private val region =
        TextRegion(
            "a",
            box,
            "Raw original",
            "Old translation",
            correctedText = "Saved correction",
            detectionConfidence = 0.8f,
            recognitionConfidence = 0.9f,
            style = OverlayStyle(fontSize = 18f),
        )
    private val raw = OcrPageResult("page", listOf(region), rawJson = "immutable engine payload")
    private val page =
        TranslationPageResult(
            "page",
            "hash",
            100,
            100,
            listOf(region, region.copy(id = "b", readingOrder = 1)),
            rawOcr = raw,
            revision = 77,
        )

    @Test
    fun `manual correction cannot replace raw transcription or measured scores`() {
        val edited = TranslationRegionEdits.update(
            page,
            region.copy(
                sourceText = "must not replace raw",
                correctedText = "User correction",
                translatedText = "New translation",
                detectionConfidence = 0.1f,
            ),
        )
        assertEquals("Raw original", edited.regions[0].sourceText)
        assertEquals("User correction", edited.regions[0].correctedText)
        assertEquals("New translation", edited.regions[0].translatedText)
        assertEquals(0.8f, edited.regions[0].detectionConfidence)
        assertEquals(0.9f, edited.regions[0].recognitionConfidence)
        assertEquals(raw, edited.rawOcr)
        assertEquals(77, edited.revision)
    }

    @Test
    fun `corner dragging keeps original coordinates and rejects crossed geometry`() {
        val moved = TranslationRegionEdits.moveCorner(page, "a", 0, TranslationPoint(-5f, 5f))
        assertEquals(TranslationPoint(0f, 5f), moved.regions[0].points[0])
        assertEquals(box.drop(1), moved.regions[0].points.drop(1))
        assertEquals(raw, moved.rawOcr)
        assertThrows(IllegalArgumentException::class.java) {
            TranslationRegionEdits.moveCorner(page, "a", 0, TranslationPoint(95f, 90f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TranslationRegionEdits.moveCorner(page, "a", 0, TranslationPoint(Float.NaN, 5f))
        }
    }

    @Test
    fun `convex validation rejects stars repeated corners and off-image points`() {
        val star =
            listOf(
                TranslationPoint(50f, 0f),
                TranslationPoint(79f, 90f),
                TranslationPoint(2f, 35f),
                TranslationPoint(98f, 35f),
                TranslationPoint(21f, 90f),
            )
        assertFalse(TranslationRegionEdits.validPolygon(star, 100, 100))
        assertFalse(TranslationRegionEdits.validPolygon(box + box.first(), 100, 100))
        assertFalse(TranslationRegionEdits.validPolygon(box.map { it.copy(x = it.x + 30f) }, 100, 100))
        assertTrue(TranslationRegionEdits.validPolygon(box.reversed(), 100, 100))
    }

    @Test
    fun `quarter turns preserve masks and text while order controls remain contiguous`() {
        var rotated = page
        repeat(4) { rotated = TranslationRegionEdits.rotate(rotated, "a", 90) }
        assertEquals(page, rotated)
        val moved = TranslationRegionEdits.moveOrder(page, "b", -1)
        assertEquals(listOf("b", "a"), moved.regions.map { it.id })
        assertEquals(listOf(0, 1), moved.regions.map { it.readingOrder })
        assertEquals(box, moved.regions[1].points)
        assertEquals(region.style, moved.regions[1].style)
        assertEquals("Saved correction", moved.regions[1].correctedText)
    }

    @Test
    fun `split requires an explicit source and translation for each child`() {
        assertThrows(IllegalArgumentException::class.java) {
            TranslationRegionEdits.split(
                page, "a", TranslationRegionEdits.SplitAxis.TOP_BOTTOM,
                "", "first", "second", "second", "first-id", "second-id",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TranslationRegionEdits.split(
                page, "a", TranslationRegionEdits.SplitAxis.TOP_BOTTOM,
                "first", "first", "second", "", "first-id", "second-id",
            )
        }
    }

    @Test
    fun `split retains one excluded raw parent and never assigns its paragraph or confidence to children`() {
        val split = TranslationRegionEdits.split(
            page.copy(
                rawOcr = null,
            ),
            "a", TranslationRegionEdits.SplitAxis.TOP_BOTTOM,
            "Upper source", "Upper translation", "Lower source", "Lower translation", "first", "second",
        )
        assertEquals(listOf("a", "first", "second", "b"), split.regions.map { it.id })
        assertFalse(split.regions[0].included)
        assertEquals("Raw original", split.regions[0].sourceText)
        assertEquals(box, split.regions[0].points)
        assertEquals(0.8f, split.regions[0].detectionConfidence)
        val first = split.regions[1]
        val second = split.regions[2]
        assertEquals("", first.sourceText)
        assertEquals("Upper source", first.correctedText)
        assertEquals("Upper translation", first.translatedText)
        assertEquals("Lower source", second.correctedText)
        assertEquals("Lower translation", second.translatedText)
        assertEquals(
            listOf(
                TranslationPoint(10f, 10f),
                TranslationPoint(90f, 10f),
                TranslationPoint(90f, 40f),
                TranslationPoint(10f, 40f),
            ),
            first.points,
        )
        assertEquals(40f, second.points.minOf { it.y })
        assertEquals(70f, second.points.maxOf { it.y })
        for (child in listOf(first, second)) {
            assertTrue(child.included)
            assertNull(child.detectionConfidence)
            assertNull(child.recognitionConfidence)
            assertNull(child.aiConfidence)
        }
        assertNull(split.rawOcr)
    }

    @Test
    fun `split of tilted convex geometry retains the source envelope in both directions`() {
        val tilted =
            listOf(
                TranslationPoint(20f, 5f),
                TranslationPoint(95f, 20f),
                TranslationPoint(80f, 90f),
                TranslationPoint(5f, 75f),
            )
        for (axis in TranslationRegionEdits.SplitAxis.entries) {
            val split = TranslationRegionEdits.split(
                page.copy(
                    regions = listOf(region.copy(points = tilted)),
                ),
                "a", axis, "one", "one translated", "two", "two translated", "one", "two",
            )
            assertEquals(raw, split.rawOcr)
            assertTrue(split.regions.drop(1).all { TranslationRegionEdits.validPolygon(it.points, 100, 100) })
            assertEquals(tilted, split.regions[0].points)
            assertEquals(listOf(0, 1, 2), split.regions.map { it.readingOrder })
        }
    }

    @Test
    fun `queue quality filtering uses the newest assessment for each job and page`() {
        fun checkpoint(
            id: String,
            job: String,
            image: String,
            state: QualityReviewState,
            updatedAt: Long,
        ) = QualityReviewCheckpoint(
            id,
            job,
            image,
            page.revision,
            page,
            QualityReviewSettings(),
            state,
            updatedAt = updatedAt,
        )
        val oldPass = checkpoint("old", "job", "page", QualityReviewState.PASSED, 10)
        val newIncomplete = checkpoint("new", "job", "page", QualityReviewState.INCOMPLETE, 20)
        val otherPage = checkpoint("other", "job", "another-page", QualityReviewState.PASSED, 15)
        val otherJob = checkpoint("elsewhere", "other-job", "page", QualityReviewState.REPAIRED, 30)
        assertEquals(
            setOf(newIncomplete, otherPage, otherJob),
            latestQualityReviews(listOf(newIncomplete, oldPass, otherJob, otherPage)).toSet(),
        )
    }
}
