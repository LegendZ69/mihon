package mihon.feature.translation.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.text.TextPaint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.color.MaterialColors
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import java.io.File

@RunWith(AndroidJUnit4::class)
class TranslationOverlayLayoutDiagnosticsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun sampledColorsPreserveExplicitOverridesAndReportLowCustomContrast() {
        val themed = resolveReviewTheme(context, ThemeMode.DARK, AppTheme.CATPPUCCIN, false)
        for (customColor in listOf(Color.WHITE, Color.BLUE)) {
            val chosen = OverlayStyle(sampleBackground = true, textColor = customColor.toLong())
            val baseline = page("Keep the door closed", 150f, 90f).let {
                it.copy(regions = it.regions.map { region -> region.copy(style = chosen) })
            }
            val document = TranslationOverlayDocument.create(
                themed,
                baseline,
                OverlayStyle(textColor = Color.BLACK.toLong()),
                mapOf("passage" to Color.WHITE),
            )
            val diagnostic = document.layoutDiagnostics.single()
            assertEquals(customColor.toUInt().toLong(), diagnostic.resolvedTextColor)
            assertTrue(diagnostic.customTextColor)
            assertFalse(diagnostic.contrastBackgroundEstimated)
            if (customColor == Color.WHITE) {
                assertEquals(1.0, checkNotNull(diagnostic.contrastRatio), 0.00001)
                assertTrue(diagnostic.message.orEmpty().contains("Custom text color has low contrast"))
            } else {
                assertTrue(checkNotNull(diagnostic.contrastRatio) > 8.5)
                assertFalse(diagnostic.message.orEmpty().contains("low contrast"))
            }
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.MAGENTA)
                document.draw(Canvas(bitmap))
                assertEquals(
                    "Artwork outside the original region is preserved",
                    Color.MAGENTA,
                    bitmap.getPixel(150, 150),
                )
                val blackPixels = (160 until 250).sumOf { y ->
                    (160 until 310).count { x -> bitmap.getPixel(x, y) == Color.BLACK }
                }
                assertEquals("Automatic black must never overwrite a custom foreground", 0, blackPixels)
                if (customColor == Color.BLUE) {
                    val bluePixels = (160 until 250).sumOf { y ->
                        (160 until 310).count { x -> bitmap.getPixel(x, y) == Color.BLUE }
                    }
                    assertTrue("The explicit custom color is actually rasterized", bluePixels > 50)
                }
                assertSame(baseline, document.result)
                assertEquals(chosen, document.result.regions.single().style)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun unavailableSourceSamplesKeepTheThemeFallbackAndLabelTranslucentEstimates() {
        val themed = resolveReviewTheme(context, ThemeMode.DARK, AppTheme.CATPPUCCIN, false)
        val baseline = page("Keep the door closed", 150f, 90f)
        val style = OverlayStyle(sampleBackground = true)
        val expected = MaterialColors.getColor(
            themed,
            com.google.android.material.R.attr.colorOnSurface,
            Color.BLACK,
        )
        val fallback = TranslationOverlayDocument.create(themed, baseline, style)
        val opaque = fallback.layoutDiagnostics.single()
        assertEquals(expected.toUInt().toLong(), opaque.resolvedTextColor)
        assertFalse(opaque.customTextColor)
        assertFalse(opaque.contrastBackgroundEstimated)
        val translucent = TranslationOverlayDocument.create(themed, baseline, style.copy(backgroundOpacity = 0.3f))
            .layoutDiagnostics.single()
        assertEquals(expected.toUInt().toLong(), translucent.resolvedTextColor)
        assertTrue(translucent.contrastBackgroundEstimated)
        assertTrue(translucent.message.orEmpty().contains("Source background is unavailable"))
        assertSame(baseline, fallback.result)
    }

    @Test
    fun belowMinimumAndExplicitFixedSizeAreReportedWithoutMutatingCachedResults() {
        val result = page("Keep the door closed", 100f, 22f)
        val style = style().copy(minFontSize = 24f)
        val fitted = TranslationOverlayDocument.create(context, result, style)
        assertSame(result, fitted.result)
        val diagnostic = fitted.layoutDiagnostics.single()
        assertTrue(diagnostic.belowPreferredMinimum)
        assertTrue(diagnostic.effectiveFontSize in 1f..<24f)
        assertFalse(diagnostic.overflow)
        assertEquals(0, diagnostic.forcedWordBreaks)
        val limited = TranslationOverlayDocument.create(context, result, style.copy(allowSmallerText = false))
        assertEquals(24f, limited.layoutDiagnostics.single().effectiveFontSize, 0f)
        assertTrue(limited.layoutDiagnostics.single().overflow)
        val fixed = TranslationOverlayDocument.create(context, result, style.copy(autoFit = false, fontSize = 30f))
        assertEquals(30f, fixed.layoutDiagnostics.single().effectiveFontSize, 0f)
        assertTrue(fixed.layoutDiagnostics.single().overflow)
        assertEquals(result, fitted.result)
        assertEquals(result, limited.result)
        assertEquals(result, fixed.result)
    }

    @Test
    fun impossibleFrameRetainsAllCharactersAndReportsActionableOverflow() {
        val text = "This complete passage cannot fit in one source pixel."
        val fit = OverlayTextFitter.fit(text, TextPaint(), style(), 1f, 1f, usableFrame = false)
        assertEquals(1f, fit.fontSize, 0f)
        assertTrue(fit.overflow)
        assertEquals(text.length, fit.layout.getLineEnd(fit.layout.lineCount - 1))
        assertTrue((0 until fit.layout.lineCount).all { fit.layout.getEllipsisCount(it) == 0 })
        val document = TranslationOverlayDocument.create(context, page(text, 3f, 3f), style())
        assertTrue(document.layoutDiagnostics.single().overflow)
        assertTrue(document.layoutDiagnostics.single().message!!.contains("Complete text does not fit"))
    }

    @Test
    fun normalCjkBreaksAndLongForcedWordsRemainDistinct() {
        val cjk = OverlayTextFitter.fit("危険！ここに入らないで。請關門。", TextPaint(), style(), 45f, 160f, true)
        assertFalse(cjk.overflow)
        assertEquals(0, cjk.forcedWordBreaks)
        val forced = OverlayTextFitter.fit("W".repeat(120), TextPaint(), style(), 4f, 400f, true)
        assertFalse(forced.overflow)
        assertTrue(forced.forcedWordBreaks > 0)
        assertEquals(120, forced.layout.getLineEnd(forced.layout.lineCount - 1))
    }

    @Test
    fun italicOutlineFallbackScriptsAndPhysicalRotationRetainGlyphInk() {
        for (rotation in listOf(0f, 31f, 90f, -90f)) {
            for (alignment in listOf("left", "center", "right")) {
                val document = TranslationOverlayDocument.create(
                    context,
                    page("f Ág مرحبًا नमस्ते\nမြန်မာ 危険！ 닫아!", 160f, 110f, rotation),
                    style().copy(
                        italic = true,
                        outlineWidth = 2f,
                        outlineColor = Color.WHITE.toLong(),
                        alignment = alignment,
                    ),
                )
                assertFalse(document.layoutDiagnostics.single().overflow)
                assertNoLostGlyphs(document)
            }
        }
    }

    @Test
    fun importedFontFitsThroughTheSameShapingPath() {
        val source = File("/system/fonts").listFiles().orEmpty().firstOrNull {
            it.isFile && it.canRead() && it.extension.lowercase() in setOf("ttf", "otf")
        }
        checkNotNull(source) { "Android runtime must provide a readable font for this import-path fixture" }
        val imported = File.createTempFile("overlay-imported-font-", ".${source.extension}", context.cacheDir)
        try {
            source.copyTo(imported, overwrite = true)
            val document = TranslationOverlayDocument.create(
                context,
                page("Keep the door closed 閉めて 닫아", 130f, 60f),
                style().copy(fontPath = imported.absolutePath, italic = true, outlineWidth = 1f),
            )
            assertFalse(document.layoutDiagnostics.single().overflow)
            assertNoLostGlyphs(document)
        } finally {
            imported.delete()
        }
    }

    @Test
    fun sourceBoxAndMaskVisibilityDoNotChangeTheFittedLayout() {
        val result = page("KEEP DOOR CLOSED", 44f, 220f)
        val document = TranslationOverlayDocument.create(context, result, style().copy(backgroundOpacity = 0.35f))
        val expected = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val actual = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            document.draw(Canvas(expected))
            val diagnostic = document.layoutDiagnostics.toList()
            document.draw(Canvas(actual), drawMasks = false)
            document.draw(Canvas(actual), drawTranslations = false)
            assertEquals(diagnostic, document.layoutDiagnostics)
            actual.eraseColor(Color.TRANSPARENT)
            document.draw(Canvas(actual))
            assertTrue(expected.sameAs(actual))
            assertSame(result, document.result)
        } finally {
            expected.recycle()
            actual.recycle()
        }
    }

    private fun style() = OverlayStyle(textColor = Color.WHITE.toLong(), backgroundColor = Color.BLACK.toLong())

    private fun page(text: String, width: Float, height: Float, rotation: Float = 0f) = TranslationPageResult(
        "fit-diagnostics",
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
                "source",
                text,
                rotation = rotation,
            ),
        ),
    )

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
                    if (Color.alpha(expected) >= 240 && Color.red(expected) >= 240) {
                        ink++
                        val actual = masked.getPixel(x, y)
                        if (Color.alpha(actual) < 240 || Color.red(actual) < 240) lost++
                    }
                }
            }
            assertTrue("The oracle must draw visible translation glyphs", ink > 20)
            assertEquals("Source mask clipped $lost/$ink shaped glyph pixels", 0, lost)
        } finally {
            masked.recycle()
            unmasked.recycle()
        }
    }
}
