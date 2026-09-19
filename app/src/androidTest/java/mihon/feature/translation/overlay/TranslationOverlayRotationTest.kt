package mihon.feature.translation.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
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
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class TranslationOverlayRotationTest {
    @Test
    fun autofitRetainsAllRotatedGlyphInkInsideTheSourceMask() {
        for (rotation in listOf(0f, -17f, 17f, 45f, 90f, 135f, 180f, 270f, 315f)) {
            assertNoClippedGlyphs(rotation, 0f)
        }
        assertNoClippedGlyphs(-17f, 34f)
    }

    @Test
    fun autofitAlsoContainsGlyphsInsideTiltedSourceQuadrilaterals() {
        assertNoClippedGlyphs(-17f, 0f, -17f)
        assertNoClippedGlyphs(24f, 0f, 24f)
        assertNoClippedGlyphs(0f, 0f, -17f)
    }

    private fun assertNoClippedGlyphs(
        regionRotation: Float,
        styleRotation: Float,
        maskRotation: Float = 0f,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result =
            TranslationPageResult(
                "rotation-fixture",
                "generated-only",
                512,
                512,
                listOf(
                    TextRegion(
                        "warning",
                        listOf(
                            TranslationPoint(80f, 80f),
                            TranslationPoint(375f, 80f),
                            TranslationPoint(375f, 216f),
                            TranslationPoint(80f, 216f),
                        ).map { point ->
                            val angle = Math.toRadians(maskRotation.toDouble())
                            val dx = point.x - 227.5f
                            val dy = point.y - 148f
                            TranslationPoint(
                                (227.5 + cos(angle) * dx - sin(angle) * dy).toFloat(),
                                (148.0 + sin(angle) * dx + cos(angle) * dy).toFloat(),
                            )
                        },
                        sourceText = "멈춰! 위험해!",
                        translatedText = "Stop! It's dangerous!",
                        rotation = regionRotation,
                    ),
                ),
            )
        val style =
            OverlayStyle(
                textColor = Color.WHITE.toLong(),
                backgroundColor = Color.BLACK.toLong(),
                autoFit = true,
                minFontSize = 8f,
                maxFontSize = 48f,
                padding = 2f,
                rotation = styleRotation,
            )
        val document = TranslationOverlayDocument.create(context, result, style)
        val masked = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val unmasked = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            document.draw(Canvas(masked))
            val oracle =
                object : Canvas(unmasked) {
                    var clips = 0

                    override fun clipPath(path: Path): Boolean {
                        clips++
                        return true
                    }
                }
            document.draw(oracle)
            assertEquals("The oracle bypasses only the source mask's clip", 1, oracle.clips)
            var opaqueGlyphPixels = 0
            var lostGlyphPixels = 0
            for (y in 0 until masked.height) {
                for (x in 0 until masked.width) {
                    val expected = unmasked.getPixel(x, y)
                    if (Color.alpha(expected) >= 250 && Color.red(expected) >= 245) {
                        opaqueGlyphPixels++
                        val actual = masked.getPixel(x, y)
                        if (Color.alpha(actual) < 250 || Color.red(actual) < 245) lostGlyphPixels++
                    }
                }
            }
            assertTrue("The fixture must draw substantial glyph ink", opaqueGlyphPixels > 100)
            assertEquals(
                "AutoFit lost $lostGlyphPixels/$opaqueGlyphPixels opaque glyph pixels at " +
                    "region rotation $regionRotation and style rotation $styleRotation",
                0,
                lostGlyphPixels,
            )
        } finally {
            masked.recycle()
            unmasked.recycle()
        }
    }
}
