package mihon.feature.translation.transfer

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.TranslationRenderedFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO

class TranslationRenderedWriterTest {
    @Test
    fun `present originals without translations remain in CBZ with incomplete status`() = runBlocking {
        val output = ByteArrayOutputStream()
        val report = TranslationRenderedWriter().write(
            flowOf(
                TranslationRenderedPage("untranslated", 0, contentComplete = false) {
                    Raster(16, 12, 0xFF334455.toInt())
                },
            ),
            1,
            TranslationRenderedFormat.CBZ,
            output,
        )
        assertFalse(report.complete)
        assertEquals(1, report.pagesWritten)
        assertTrue(report.warnings.any { it.contains("untranslated") })
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            assertEquals("000001.png", zip.nextEntry.name)
            assertEquals(12, javax.imageio.ImageIO.read(zip).height)
        }
    }

    @Test
    fun `empty unknown export never claims a complete translated chapter`() = runBlocking {
        val report = TranslationRenderedWriter().write(
            kotlinx.coroutines.flow.emptyFlow(),
            0,
            TranslationRenderedFormat.CBZ,
            ByteArrayOutputStream(),
        )
        assertFalse(report.complete)
    }

    @Test
    fun `A4 PDF splits long strip into consecutive sheets without dropping source rows`() = runBlocking {
        val output = ByteArrayOutputStream()
        val report = TranslationRenderedWriter().write(
            flowOf(
                TranslationRenderedPage("strip", 0) {
                    Raster(100, 400, 0xFFFFBB22.toInt())
                },
            ),
            1,
            TranslationRenderedFormat.PDF,
            output,
        )
        assertTrue(report.complete)
        assertEquals(1, report.pagesWritten)
        val bytes = output.toByteArray()
        val text = bytes.toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.7"))
        assertTrue(text.contains("/Type /Pages /Count 3"))
        assertEquals(3, Regex("/MediaBox \\[0 0 595 842\\]").findAll(text).count())
        val images = Regex(
            "/Subtype /Image /Width 100 /Height (\\d+) .*?/Length (\\d+) 0 R[^>]*>>\nstream\n",
        ).findAll(text).toList()
        assertEquals(listOf(141, 141, 118), images.map { it.groupValues[1].toInt() })
        val lastSourceRows = listOf(140, 281, 399)
        images.forEachIndexed { index, match ->
            val lengthId = match.groupValues[2]
            val length = Regex("\n$lengthId 0 obj\n(\\d+)\nendobj").find(text)!!.groupValues[1].toInt()
            val imageBytes = InflaterInputStream(ByteArrayInputStream(bytes, match.range.last + 1, length)).readBytes()
            assertEquals(100 * listOf(141, 141, 118)[index] * 3, imageBytes.size)
            val last = imageBytes.takeLast(3).map { it.toInt() and 255 }
            val row = lastSourceRows[index]
            assertEquals(listOf(0, row / 256, row % 256), last)
        }
    }

    @Test
    fun `CBZ preserves every source row and orders complete pages including long strips`() = runBlocking {
        val output = ByteArrayOutputStream()
        val first = TranslationRenderedPage("first", 0) { Raster(16, 12, 0xFF334455.toInt()) }
        val second = TranslationRenderedPage("strip", 1) { Raster(16, 1200, 0xFFFFBB22.toInt()) }
        val report = TranslationRenderedWriter().write(flowOf(first, second), 2, TranslationRenderedFormat.CBZ, output)
        assertTrue(report.complete)
        assertEquals(2, report.pagesWritten)
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            assertEquals("000001.png", zip.nextEntry?.name)
            val one = ImageIO.read(ByteArrayInputStream(zip.readBytes()))
            assertEquals(16, one.width)
            assertEquals(12, one.height)
            assertEquals(0xFF334455.toInt(), one.getRGB(0, 0))
            assertEquals(0xFF00000B.toInt(), one.getRGB(15, 11))
            assertEquals("000002.png", zip.nextEntry?.name)
            val two = ImageIO.read(ByteArrayInputStream(zip.readBytes()))
            assertEquals(1200, two.height)
            assertEquals(0xFFFFBB22.toInt(), two.getRGB(0, 0))
            assertEquals(0xFF0004AF.toInt(), two.getRGB(15, 1199))
        }
    }

    private class Raster(
        override val width: Int,
        override val height: Int,
        private val color: Int,
    ) : TranslationPageRaster {
        override suspend fun rows(top: Int, count: Int, consume: suspend (IntArray, Int) -> Unit) {
            for (y in top until top + count) {
                val row = IntArray(width) { color }
                row[width - 1] = 0xFF000000.toInt() or y
                consume(row, 1)
            }
        }
        override fun close() = Unit
    }
}
