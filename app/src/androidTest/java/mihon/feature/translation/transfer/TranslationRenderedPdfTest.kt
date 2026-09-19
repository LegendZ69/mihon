package mihon.feature.translation.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPdfPageSize
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationRenderedFormat
import java.io.File
import java.util.UUID
import kotlin.math.floor
import kotlin.math.min

/** Independent platform PDF parser: every source row has a nine-bit marker outside overlay polygons. */
@RunWith(AndroidJUnit4::class)
class TranslationRenderedPdfTest {
    @Test
    fun a4KeepsChapterOrderEveryStripRowSharedMasksAndOriginalSfx() {
        verify(TranslationPdfPageSize.A4, listOf(141, 141, 118))
    }

    @Test
    fun letterKeepsChapterOrderEveryStripRowSharedMasksAndOriginalSfx() {
        verify(TranslationPdfPageSize.LETTER, listOf(129, 129, 129, 13))
    }

    private fun verify(paper: TranslationPdfPageSize, stripRows: List<Int>) = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val directory = File(context.cacheDir, "translation-pdf-test-${UUID.randomUUID()}")
            check(directory.mkdirs())
            try {
                val first = original(directory, "first.png", 80) { eraseColor(Color.GREEN) }
                val strip = original(directory, "strip.png", 400) {
                    Canvas(this).apply {
                        drawColor(Color.WHITE)
                        val paint = Paint().apply { color = Color.BLACK }
                        drawRect(10f, 10f, 90f, 60f, paint)
                        paint.color = Color.RED
                        drawRect(10f, 170f, 90f, 200f, paint)
                        paint.color = Color.BLUE
                        drawRect(0f, 398f, 100f, 400f, paint)
                    }
                    for (y in 0 until height) {
                        for (bit in 0..8) {
                            setPixel(91 + bit, y, if (y and (1 shl bit) != 0) Color.WHITE else Color.BLACK)
                        }
                    }
                }
                val firstImage = image(first, "0", 0, 80)
                val stripImage = image(strip, "1", 1, 400)
                val firstResult = TranslationPageResult("0", firstImage.contentHash, 100, 80, emptyList(), revision = 1)
                val stripResult = TranslationPageResult(
                    "1",
                    stripImage.contentHash,
                    100,
                    400,
                    listOf(
                        TextRegion("dialogue", points(10f, 60f), "原文", "Close"),
                        TextRegion("sfx", points(170f, 200f), "쿵", "THUD", type = "sound_effect"),
                    ),
                    revision = 19,
                )
                val style = OverlayStyle(backgroundColor = 0xFFFFFFFF, textColor = 0xFF000000, padding = 4f)
                val pdf = File(directory, "translated.pdf")
                val report = pdf.outputStream().use { output ->
                    TranslationRenderedWriter().write(
                        flowOf(
                            TranslationRenderedPage("0", 0) {
                                AndroidTranslationPageRaster.open(
                                    context,
                                    firstImage,
                                    firstResult,
                                    style,
                                    TranslationContentPolicy(),
                                )
                            },
                            TranslationRenderedPage("1", 1) {
                                AndroidTranslationPageRaster.open(
                                    context,
                                    stripImage,
                                    stripResult,
                                    style,
                                    TranslationContentPolicy(),
                                )
                            },
                        ),
                        2,
                        TranslationRenderedFormat.PDF,
                        output,
                        paper,
                    )
                }
                assertTrue(report.complete)
                assertEquals(2, report.pagesWritten)
                PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                    assertEquals(1 + stripRows.size, renderer.pageCount)
                    var sourceTop = 0
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            assertEquals(paper.widthPoints, page.width)
                            assertEquals(paper.heightPoints, page.height)
                            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val rows = if (index == 0) 80 else stripRows[index - 1]
                                val scale = min(page.width / 100.0, page.height / rows.toDouble())
                                val left = (page.width - 100 * scale) / 2
                                // Export sheets preserve proportional scale, center horizontally and start at the top.
                                val top = 0.0
                                fun color(x: Double, y: Double): Int = bitmap.getPixel(
                                    floor(left + x * scale).toInt(),
                                    floor(top + y * scale).toInt(),
                                )
                                if (index == 0) {
                                    assertEquals(
                                        "Ordinary chapter page must come first",
                                        Color.GREEN,
                                        color(50.5, 40.5),
                                    )
                                } else {
                                    for (row in 0 until rows) {
                                        var decoded = 0
                                        for (bit in 0..8) {
                                            if (Color.red(color(91.5 + bit, row + 0.5)) >
                                                127
                                            ) {
                                                decoded = decoded or (1 shl bit)
                                            }
                                        }
                                        assertEquals(
                                            "${paper.name} sheet $index source row ${sourceTop + row}",
                                            sourceTop + row,
                                            decoded,
                                        )
                                    }
                                    if (sourceTop == 0) assertEquals(Color.WHITE, color(11.5, 11.5))
                                    if (180 in sourceTop until sourceTop + rows) {
                                        assertEquals(
                                            "Ignored SFX artwork stays visible",
                                            Color.RED,
                                            color(
                                                40.5,
                                                180.5 - sourceTop,
                                            ),
                                        )
                                    }
                                    if (399 in
                                        sourceTop until sourceTop + rows
                                    ) {
                                        assertEquals(
                                            Color.BLUE,
                                            color(
                                                50.5,
                                                399.5 - sourceTop,
                                            ),
                                        )
                                    }
                                    sourceTop += rows
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                    assertEquals(400, sourceTop)
                }
                assertEquals(firstImage.contentHash, TranslationBackupCodec.sha256(first))
                assertEquals(stripImage.contentHash, TranslationBackupCodec.sha256(strip))
                assertEquals(19L, stripResult.revision)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun original(directory: File, name: String, height: Int, draw: Bitmap.() -> Unit): File {
        val bitmap = Bitmap.createBitmap(100, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.draw()
            File(directory, name).also { file ->
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun image(file: File, id: String, index: Int, height: Int) =
        TranslationImage(
            id,
            index,
            file.path,
            "image/png",
            100,
            height,
            TranslationBackupCodec.sha256(file),
            file.length(),
        )

    private fun points(top: Float, bottom: Float) = listOf(
        TranslationPoint(10f, top),
        TranslationPoint(90f, top),
        TranslationPoint(90f, bottom),
        TranslationPoint(10f, bottom),
    )
}
