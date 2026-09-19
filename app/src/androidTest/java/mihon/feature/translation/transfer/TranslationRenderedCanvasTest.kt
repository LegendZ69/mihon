package mihon.feature.translation.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationRenderedFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class TranslationRenderedCanvasTest {
    @Test
    fun boundedCbzCanvasPreservesSfxArtworkFinalRowsAndSourceWhileMaskingDialogue() = runBlocking {
        withContext(Dispatchers.IO) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val directory = File(context.cacheDir, "translation-render-export-${UUID.randomUUID()}").apply {
                check(mkdirs())
            }
            try {
                val file = File(directory, "original.png")
                val bitmap = Bitmap.createBitmap(100, 30000, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(bitmap).apply {
                        drawColor(Color.WHITE)
                        val paint = Paint().apply { color = Color.BLACK }
                        drawRect(10f, 20950f, 90f, 21050f, paint)
                        paint.color = Color.RED
                        drawRect(10f, 24000f, 90f, 25000f, paint)
                        paint.color = Color.BLUE
                        drawRect(0f, 29999f, 100f, 30000f, paint)
                    }
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    bitmap.recycle()
                }
                val hash = TranslationBackupCodec.sha256(file)
                val image = TranslationImage("0", 0, file.path, "image/png", 100, 30000, hash, file.length())
                fun points(
                    top: Float,
                    bottom: Float,
                ) = listOf(
                    TranslationPoint(10f, top),
                    TranslationPoint(90f, top),
                    TranslationPoint(90f, bottom),
                    TranslationPoint(10f, bottom),
                )
                val result = TranslationPageResult(
                    "0",
                    hash,
                    100,
                    30000,
                    listOf(
                        TextRegion("dialogue", points(20950f, 21050f), "原文", "Keep closed"),
                        TextRegion("sfx", points(24000f, 25000f), "쿵", "THUD", type = "sound_effect"),
                    ),
                    revision = 19,
                )
                val style = OverlayStyle(backgroundColor = 0xFFFFFFFF, textColor = 0xFF000000, padding = 4f)
                val output = ByteArrayOutputStream()
                val page =
                    TranslationRenderedPage("0", 0) {
                        AndroidTranslationPageRaster.open(context, image, result, style, TranslationContentPolicy())
                    }
                val report = TranslationRenderedWriter().write(flowOf(page), 1, TranslationRenderedFormat.CBZ, output)
                assertTrue(report.complete)
                ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                    assertEquals("000001.png", zip.nextEntry?.name)
                    val bytes = zip.readBytes()
                    val rendered = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
                    try {
                        assertEquals(30000, rendered.height)
                        assertEquals(Color.WHITE, rendered.getPixel(11, 20951))
                        assertEquals(Color.RED, rendered.getPixel(40, 24500))
                        assertEquals(Color.BLUE, rendered.getPixel(50, 29999))
                        assertEquals(Color.WHITE, rendered.getPixel(5, 21010))
                    } finally {
                        rendered.recycle()
                    }
                }
                assertEquals(hash, TranslationBackupCodec.sha256(file))
                assertEquals(19L, result.revision)
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
