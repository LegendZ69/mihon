package mihon.feature.translation.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.text.MeasuredText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint

@RunWith(AndroidJUnit4::class)
class TranslationOverlayTextFitTest {
    @Test
    fun preferredMinimumCannotClipTheCompleteTranslation() {
        val document = document("Keep the door closed", 100f, 22f, OverlayStyle(minFontSize = 24f))
        assertNoLostGlyphs(document)
    }

    @Test
    fun narrowEnglishUsesWholeWordsWhenASmallerSizeFits() {
        val document = document("KEEP DOOR CLOSED", 44f, 220f, OverlayStyle(minFontSize = 8f))
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            val canvas = RunCanvas(bitmap, "KEEP DOOR CLOSED")
            document.draw(canvas)
            val lines = canvas.runs.groupBy {
                it.baseline
            }.values.map { line -> line.joinToString("") { it.text }.trim() }
            assertTrue("The real StaticLayout draw must produce text runs", lines.isNotEmpty())
            assertEquals("Whole words must remain intact: $lines", listOf("KEEP", "DOOR", "CLOSED"), lines)
        } finally {
            bitmap.recycle()
        }
    }

    private fun document(text: String, width: Float, height: Float, style: OverlayStyle): TranslationOverlayDocument {
        val result = TranslationPageResult(
            "text-fit",
            "generated-only",
            512,
            512,
            listOf(
                TextRegion(
                    "passage",
                    listOf(
                        TranslationPoint(160f, 160f),
                        TranslationPoint(160f + width, 160f),
                        TranslationPoint(160f + width, 160f + height),
                        TranslationPoint(160f, 160f + height),
                    ),
                    "original",
                    text,
                ),
            ),
        )
        return TranslationOverlayDocument.create(
            ApplicationProvider.getApplicationContext<Context>(),
            result,
            style.copy(textColor = Color.WHITE.toLong(), backgroundColor = Color.BLACK.toLong()),
        )
    }

    private fun assertNoLostGlyphs(document: TranslationOverlayDocument) {
        val masked = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val unmasked = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            document.draw(Canvas(masked))
            document.draw(object : Canvas(unmasked) {
                override fun clipPath(path: Path) = true
            })
            var ink = 0
            var lost = 0
            for (y in 0 until 512) {
                for (x in 0 until 512) {
                    val expected = unmasked.getPixel(x, y)
                    if (Color.alpha(expected) > 0 && Color.red(expected) > 0) {
                        ink++
                        val actual = masked.getPixel(x, y)
                        if (actual != expected) lost++
                    }
                }
            }
            assertTrue("The oracle must draw visible translation glyphs; observed $ink antialiased pixels", ink > 20)
            assertEquals("Saved preferred minimum lost $lost/$ink glyph pixels", 0, lost)
        } finally {
            masked.recycle()
            unmasked.recycle()
        }
    }

    private data class Run(val text: String, val baseline: Float)

    private class RunCanvas(bitmap: Bitmap, private val original: String) : Canvas(bitmap) {
        val runs = mutableListOf<Run>()

        override fun drawTextRun(
            text: MeasuredText,
            start: Int,
            end: Int,
            contextStart: Int,
            contextEnd: Int,
            x: Float,
            y: Float,
            isRtl: Boolean,
            paint: Paint,
        ) {
            runs += Run(original.substring(start, end), y)
            super.drawTextRun(text, start, end, contextStart, contextEnd, x, y, isRtl, paint)
        }

        override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) {
            runs += Run(String(text, index, count), y)
            super.drawText(text, index, count, x, y, paint)
        }

        override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            runs += Run(text.subSequence(start, end).toString(), y)
            super.drawText(text, start, end, x, y, paint)
        }

        override fun drawTextRun(
            text: CharArray,
            index: Int,
            count: Int,
            contextIndex: Int,
            contextCount: Int,
            x: Float,
            y: Float,
            isRtl: Boolean,
            paint: Paint,
        ) {
            runs += Run(String(text, index, count), y)
            super.drawTextRun(text, index, count, contextIndex, contextCount, x, y, isRtl, paint)
        }

        override fun drawTextRun(
            text: CharSequence,
            start: Int,
            end: Int,
            contextStart: Int,
            contextEnd: Int,
            x: Float,
            y: Float,
            isRtl: Boolean,
            paint: Paint,
        ) {
            runs += Run(text.subSequence(start, end).toString(), y)
            super.drawTextRun(text, start, end, contextStart, contextEnd, x, y, isRtl, paint)
        }

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            runs += Run(text, y)
            super.drawText(text, x, y, paint)
        }

        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            runs += Run(text.substring(start, end), y)
            super.drawText(text, start, end, x, y, paint)
        }
    }
}
