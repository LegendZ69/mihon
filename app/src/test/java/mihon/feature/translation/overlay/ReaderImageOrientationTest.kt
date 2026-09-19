package mihon.feature.translation.overlay

import ca.mpreg.webgpuviewer.restoreReaderSourcePixels
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class ReaderImageOrientationTest {
    @Test
    fun `all EXIF permutations restore RGBA8 RGBA16F and one or three channel gainmap bytes losslessly`() {
        val oriented = listOf(
            listOf(1, 2, 3, 4, 5, 6),
            listOf(3, 2, 1, 6, 5, 4),
            listOf(6, 5, 4, 3, 2, 1),
            listOf(4, 5, 6, 1, 2, 3),
            listOf(1, 4, 2, 5, 3, 6),
            listOf(4, 1, 5, 2, 6, 3),
            listOf(6, 3, 5, 2, 4, 1),
            listOf(3, 6, 2, 5, 1, 4),
        )
        for (bytesPerPixel in listOf(1, 3, 4, 8)) {
            for (orientation in 1..8) {
                val input = ByteBuffer.allocateDirect(6 * bytesPerPixel + 3)
                input.position(3)
                for (pixel in oriented[orientation - 1]) {
                    repeat(bytesPerPixel) { input.put((pixel * 16 + it).toByte()) }
                }
                input.flip().position(3)
                val restored = restoreReaderSourcePixels(
                    input,
                    if (orientation < 5) 3 else 2,
                    if (orientation < 5) 2 else 3,
                    bytesPerPixel,
                    orientation,
                )
                assertEquals(3, restored.width)
                assertEquals(2, restored.height)
                assertEquals(3, input.position(), "Must not consume the decoder's buffer")
                val actual = ByteArray(6 * bytesPerPixel).also { restored.pixels.duplicate().get(it) }
                val expected = ByteArray(actual.size) { ((it / bytesPerPixel + 1) * 16 + it % bytesPerPixel).toByte() }
                assertArrayEquals(expected, actual, "EXIF $orientation, pixel stride $bytesPerPixel")
            }
        }
    }

    @Test
    fun `normal missing or invalid EXIF orientation shares pixels without another allocation`() {
        val input = ByteBuffer.allocateDirect(24)
        for (orientation in listOf(-1, 0, 1, 9)) {
            val restored = restoreReaderSourcePixels(input, 3, 2, 4, orientation)
            assertSame(input, restored.pixels)
            assertEquals(3, restored.width)
            assertEquals(2, restored.height)
        }
    }

    @Test
    fun `invalid dimensions overflow and truncated frames are rejected before allocation`() {
        val input = ByteBuffer.allocateDirect(24)
        for ((width, height, stride) in listOf(Triple(0, 2, 4), Triple(3, -1, 4), Triple(3, 3, 4))) {
            assertThrows(IllegalArgumentException::class.java) {
                restoreReaderSourcePixels(input, width, height, stride, 6)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            restoreReaderSourcePixels(input, Int.MAX_VALUE, Int.MAX_VALUE, 8, 6)
        }
    }
}
