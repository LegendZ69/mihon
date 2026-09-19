package mihon.feature.translation.overlay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PageImageTransformTest {
    @Test
    fun `odd width split uses actual encoded edge rectangles`() {
        val left = PageImageTransform.LEFT_HALF.map(101, 60)
        val right = PageImageTransform.RIGHT_HALF.map(101, 60)
        assertEquals(50, left.width)
        assertEquals(50f, left.pieces.single().right)
        assertEquals(51f, right.pieces.single().left)
        assertEquals(0f to 10f, right.pieces.single().map(51f, 10f))
        assertEquals(50f to 60f, right.pieces.single().map(101f, 60f))
    }

    @Test
    fun `clockwise rotation maps corners into positive output coordinates`() {
        val mapping = PageImageTransform.ROTATE_CLOCKWISE.map(100, 60)
        val piece = mapping.pieces.single()
        assertEquals(60, mapping.width)
        assertEquals(100, mapping.height)
        assertEquals(60f to 0f, piece.map(0f, 0f))
        assertEquals(0f to 100f, piece.map(100f, 60f))
    }

    @Test
    fun `counterclockwise rotation and clockwise rotation cancel`() {
        val forward = PageImageTransform.ROTATE_CLOCKWISE.map(100, 60).pieces.single()
        val backward = PageImageTransform.ROTATE_COUNTERCLOCKWISE.map(60, 100).pieces.single()
        val rotated = forward.map(23f, 49f)
        assertEquals(23f to 49f, backward.map(rotated.first, rotated.second))
    }

    @Test
    fun `webtoon right above left maps each half independently`() {
        val mapping = PageImageTransform.RIGHT_ABOVE_LEFT.map(101, 60)
        assertEquals(50, mapping.width)
        assertEquals(120, mapping.height)
        assertEquals(0f to 0f, mapping.pieces[0].map(51f, 0f))
        assertEquals(50f to 60f, mapping.pieces[0].map(101f, 60f))
        assertEquals(0f to 60f, mapping.pieces[1].map(0f, 0f))
        assertEquals(50f to 120f, mapping.pieces[1].map(50f, 60f))
    }

    @Test
    fun `webtoon inverse stacking places right half after original height`() {
        val mapping = PageImageTransform.LEFT_ABOVE_RIGHT.map(100, 70)
        assertEquals(4f to 6f, mapping.pieces[0].map(4f, 6f))
        assertEquals(4f to 76f, mapping.pieces[1].map(54f, 6f))
    }

    @Test
    fun `ordinary tall image remains one logical original image`() {
        val mapping = PageImageTransform.ORIGINAL.map(1080, 50000)
        assertEquals(50000, mapping.height)
        assertEquals(123f to 49000f, mapping.pieces.single().map(123f, 49000f))
    }
}
