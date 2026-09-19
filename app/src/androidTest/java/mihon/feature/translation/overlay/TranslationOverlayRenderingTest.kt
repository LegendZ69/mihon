package mihon.feature.translation.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUExtent3D
import androidx.webgpu.GPUTexelCopyBufferInfo
import androidx.webgpu.GPUTexelCopyBufferLayout
import androidx.webgpu.GPUTexelCopyTextureInfo
import androidx.webgpu.GPUTextureDescriptor
import androidx.webgpu.MapMode
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureUsage
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.renderer.ImageOverlay
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
import ca.mpreg.webgpuviewer.viewer.ImagePage
import com.davemorrissey.labs.subscaleview.decoder.Decoder
import com.davemorrissey.labs.subscaleview.provider.InputProvider
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class TranslationOverlayRenderingTest {
    @Test
    fun importedGeometryAndSfxPolicyRenderWithoutChangingImportedContent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val codec = mihon.feature.translation.transfer.StructuredTranslationCodec()
        val json = """{"format":"mihon-structured-translations","version":1,"pages":[{
            "imageId":"opaque-label","width":256,"height":256,"coordinateSpace":"original_pixels",
            "regions":[
            {"id":"sign","sourceText":"出口","translatedText":"EXIT","type":"sign","readingOrder":0,
             "points":[{"x":20,"y":20},{"x":230,"y":20},{"x":230,"y":100},{"x":20,"y":100}]},
            {"id":"sound","sourceText":"쿵","translatedText":"THUD","type":"sound_effect","readingOrder":1,
             "points":[{"x":20,"y":130},{"x":230,"y":130},{"x":230,"y":230},{"x":20,"y":230}]}
            ]}]}"""
        val decoded = codec.decode(json.byteInputStream(), "overlay.json")
        val image = tachiyomi.domain.translation.model.TranslationImage(
            "original",
            0,
            "",
            "image/png",
            256,
            256,
            "a".repeat(64),
            100,
        )
        val plan = codec.plan(decoded, listOf(image), emptyList(), mapOf(decoded.pages.single().key to image.id))
        val saved = requireNotNull(plan.readyPages.single().result)
        val style = OverlayStyle(backgroundColor = Color.WHITE.toLong(), textColor = Color.BLACK.toLong())
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val page = ImagePage.ImageSingle(solidImage(256, Color.RED))
        try {
            bitmap.eraseColor(Color.RED)
            val document = TranslationOverlayDocument.create(context, saved, style)
            document.draw(Canvas(bitmap))
            assertEquals(Color.WHITE, bitmap.getPixel(25, 25))
            assertEquals(Color.RED, bitmap.getPixel(25, 135))
            assertEquals(Color.RED, bitmap.getPixel(5, 5))
            assertTrue((30 until 90).any { y -> (30 until 220).any { x -> bitmap.getPixel(x, y) == Color.BLACK } })
            page.replaceOverlay(document.gpuOverlay())
            val gpu = render(page)
            assertPixel(Color.WHITE, gpu, 25, 25)
            assertPixel(Color.RED, gpu, 25, 135)
            assertPixel(Color.RED, gpu, 5, 5)
            assertEquals("THUD", saved.regions.single { it.id == "sound" }.translatedText)
            assertEquals(saved, document.result)
            val included = TranslationOverlayDocument.create(
                context,
                saved,
                style,
                contentPolicy = tachiyomi.domain.translation.model.TranslationContentPolicy(ignoreSoundEffects = false),
            )
            page.replaceOverlay(included.gpuOverlay())
            assertPixel(Color.WHITE, render(page), 25, 135)
            assertEquals(saved.revision, included.result.revision)
        } finally {
            bitmap.recycle()
            page.cleanup()
        }
    }

    @Test
    fun sampledBackgroundKeepsAutomaticGlyphsVisibleInBothThemes() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val region = TextRegion(
            "speech",
            listOf(
                TranslationPoint(30f, 30f),
                TranslationPoint(230f, 30f),
                TranslationPoint(230f, 150f),
                TranslationPoint(30f, 150f),
            ),
            sourceText = "Original",
            translatedText = "Keep the door closed",
        )
        val result = TranslationPageResult("0", "contrast-fixture", 256, 256, listOf(region), revision = 41)
        for ((mode, source, expectedText) in listOf(
            Triple(ThemeMode.DARK, Color.WHITE, Color.BLACK),
            Triple(ThemeMode.LIGHT, Color.BLACK, Color.WHITE),
        )) {
            val context = resolveReviewTheme(base, mode, AppTheme.CATPPUCCIN, false)
            val styles = listOf(1f, 0.35f, 0f).map {
                OverlayStyle(sampleBackground = true, backgroundOpacity = it)
            } + listOf(
                OverlayStyle(backgroundColor = expectedText.toLong(), backgroundOpacity = 0.1f),
                OverlayStyle(
                    backgroundColor = (expectedText and 0x00FFFFFF or 0x80000000.toInt()).toLong(),
                    backgroundOpacity = 0.5f,
                ),
            )
            for (style in styles) {
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                val page = ImagePage.ImageSingle(solidImage(256, source))
                try {
                    bitmap.eraseColor(source)
                    val png = ByteArrayOutputStream().also {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }.toByteArray()
                    val sampled = sampleTranslationBackgrounds({ png.inputStream() }, result, style)
                    val document = TranslationOverlayDocument.create(context, result, style, sampled)
                    document.draw(Canvas(bitmap))
                    val canvasGlyphs = (30 until 150).sumOf { y ->
                        (30 until 230).count { x -> bitmap.getPixel(x, y) == expectedText }
                    }
                    assertTrue("$mode mask style=$style must retain contrasting glyphs", canvasGlyphs > 50)
                    page.replaceOverlay(document.gpuOverlay())
                    val gpu = render(page)
                    val expectedChannel = Color.red(expectedText)
                    val gpuGlyphs = (30 until 150).sumOf { y ->
                        (30 until 230).count { x ->
                            val offset = (y * 256 + x) * 4
                            (0..2).all { (gpu[offset + it].toInt() and 255) == expectedChannel }
                        }
                    }
                    assertTrue("GPU uses the same contrasting glyph color", gpuGlyphs > 50)
                    assertEquals(source, bitmap.getPixel(10, 10))
                    assertPixel(source, gpu, 10, 10)
                    assertEquals(result, document.result)
                } finally {
                    bitmap.recycle()
                    page.cleanup()
                }
            }
        }
    }

    @Test
    fun correctedOcrNeverReplacesTranslatedGlyphsInCanvasOrGpu() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val region = TextRegion(
                "speech",
                listOf(
                    TranslationPoint(30f, 30f),
                    TranslationPoint(230f, 30f),
                    TranslationPoint(230f, 150f),
                    TranslationPoint(30f, 150f),
                ),
                sourceText = "raw OCR",
                translatedText = "Keep the door closed",
            )
            val style = OverlayStyle(
                backgroundColor = Color.WHITE.toLong(),
                textColor = Color.BLACK.toLong(),
                autoFit = false,
                fontSize = 16f,
            )
            for (translation in listOf("Keep the door closed", "")) {
                val translated = region.copy(translatedText = translation)
                val expected = TranslationPageResult("0", "fixture", 256, 256, listOf(translated))
                val corrected = expected.copy(
                    regions = listOf(translated.copy(correctedText = "OCR TRANSCRIPTION CORRECTION")),
                    revision = expected.revision + 1,
                )
                val expectedDocument = TranslationOverlayDocument.create(context, expected, style)
                val correctedDocument = TranslationOverlayDocument.create(context, corrected, style)
                val expectedBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                val correctedBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                val page = ImagePage.ImageSingle(solidImage(256, Color.RED))
                try {
                    expectedBitmap.eraseColor(Color.RED)
                    correctedBitmap.eraseColor(Color.RED)
                    expectedDocument.draw(Canvas(expectedBitmap))
                    correctedDocument.draw(Canvas(correctedBitmap))
                    assertTrue(
                        "Corrected source transcription must not alter Canvas translation or mask pixels",
                        expectedBitmap.sameAs(correctedBitmap),
                    )
                    page.replaceOverlay(expectedDocument.gpuOverlay())
                    val expectedPixels = render(page)
                    page.replaceOverlay(correctedDocument.gpuOverlay())
                    val correctedPixels = render(page)
                    assertTrue(
                        "Corrected source transcription must not alter GPU translation or mask pixels",
                        expectedPixels.contentEquals(correctedPixels),
                    )
                    if (translation.isBlank()) {
                        assertEquals(Color.RED, correctedBitmap.getPixel(100, 80))
                        assertPixel(Color.RED, correctedPixels, 100, 80)
                    } else {
                        var textPixels = 0
                        for (y in 30 until 150) {
                            for (x in 30 until 230) {
                                if (correctedBitmap.getPixel(x, y) == Color.BLACK) textPixels++
                            }
                        }
                        assertTrue("Fixture translation must produce visible glyphs", textPixels > 0)
                    }
                } finally {
                    expectedBitmap.recycle()
                    correctedBitmap.recycle()
                    page.cleanup()
                }
            }
        }

    @Test
    fun polygonMaskPreservesArtworkOutsideItsShape() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result =
            TranslationPageResult(
                "0",
                "fixture",
                200,
                200,
                listOf(
                    TextRegion(
                        "speech",
                        listOf(
                            TranslationPoint(100f, 20f),
                            TranslationPoint(180f, 100f),
                            TranslationPoint(100f, 180f),
                            TranslationPoint(20f, 100f),
                        ),
                        "original",
                        "Translated",
                    ),
                ),
            )
        val style =
            OverlayStyle(
                backgroundColor = Color.WHITE.toLong(),
                textColor = Color.BLACK.toLong(),
                autoFit = false,
                fontSize = 16f,
            )
        val document = TranslationOverlayDocument.create(context, result, style)
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.MAGENTA)
            document.draw(Canvas(bitmap))
            assertEquals(Color.MAGENTA, bitmap.getPixel(30, 30))
            assertEquals(Color.MAGENTA, bitmap.getPixel(190, 100))
            assertEquals(Color.WHITE, bitmap.getPixel(100, 40))
            var textPixels = 0
            for (y in 80..120) {
                for (x in 40..160) {
                    if (bitmap.getPixel(x, y) == Color.BLACK) textPixels++
                }
            }
            assertTrue("Translated glyphs should be rasterized within the polygon", textPixels > 0)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun pinnedNativeDecoderAndCropExposeTheActualOriginalRectangle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
        val decoder = Decoder(cropBorders = true)
        try {
            original.eraseColor(Color.WHITE)
            Canvas(original).drawRect(20f, 10f, 100f, 80f, Paint().apply { color = Color.BLACK })
            val encoded =
                ByteArrayOutputStream()
                    .also {
                        original.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }.toByteArray()
            val size = decoder.init(context, InputProvider { encoded.inputStream() })
            val crop = decoder.sourceCrop
            assertEquals(128, decoder.sourceImageWidth)
            assertEquals(96, decoder.sourceImageHeight)
            assertEquals(crop.width(), size.x)
            assertEquals(crop.height(), size.y)
            assertTrue("Fixture has an asymmetric white border", crop.left > 0 && crop.top > 0 && crop.left != crop.top)
            val tile = decoder.decodeRegion(Rect(0, 0, crop.width(), crop.height()), 1)
            try {
                assertEquals(
                    original.getPixel(crop.centerX(), crop.centerY()),
                    tile.getPixel(
                        tile.width / 2,
                        tile.height / 2,
                    ),
                )
            } finally {
                tile.recycle()
            }
            // Loading the original resize JNI as well catches accidental omission during AAR merge.
            val rgba = ByteBuffer.allocateDirect(32 * 32 * 4)
            repeat(32 * 32 * 4) { rgba.put(255.toByte()) }
            rgba.flip()
            val half =
                ca.mpreg.webgpuviewer.ImageUtil
                    .resize(rgba, 32, 32)
            assertEquals(16 * 16 * 4, half.capacity())
            assertEquals(255, half.get(128).toInt() and 255)
        } finally {
            decoder.recycle()
            original.recycle()
        }
    }

    @Test
    fun nativeReaderKeepsRawSourceCoordinatesForExifOrientedJpeg() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("translation-exif", ".jpg", context.cacheDir)
        val decoder = Decoder(cropBorders = false)
        try {
            original.eraseColor(Color.WHITE)
            Canvas(original).drawRect(0f, 0f, 64f, 48f, Paint().apply { color = Color.RED })
            file.outputStream().use { original.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val size = decoder.init(context, InputProvider { file.inputStream() })
            assertEquals(128, size.x)
            assertEquals(96, size.y)
            val tile = decoder.decodeRegion(Rect(0, 0, 128, 96), 1)
            try {
                assertTrue(
                    "Native reader and upload copies must agree on raw top-left coordinates",
                    Color.red(tile.getPixel(10, 10)) > 240 && Color.green(tile.getPixel(10, 10)) < 20,
                )
                assertTrue(Color.green(tile.getPixel(100, 70)) > 240)
            } finally {
                tile.recycle()
            }
        } finally {
            decoder.recycle()
            original.recycle()
            file.delete()
        }
    }

    @Test
    fun realGpuOverlayTracksSourcePixelsAndClearsOnToggle() =
        runBlocking {
            val page = ImagePage.ImageSingle(solidImage(256, Color.RED))
            try {
                page.replaceOverlay(overlay(100f, 120f))
                page.x = 0.05f
                page.y = 0.03f
                page.scale = 2f
                val translated = render(page)
                assertPixel(Color.WHITE, translated, 110, 140)
                assertPixel(Color.RED, translated, 70, 140)
                page.replaceOverlay(null)
                val original = render(page)
                assertPixel(Color.RED, original, 110, 140)
            } finally {
                page.cleanup()
            }
        }

    @Test
    fun realGpuSpreadKeepsOverlayOnItsOriginalPage() =
        runBlocking {
            val left = ImagePage.ImageSingle(solidImage(256, Color.RED))
            val right = ImagePage.ImageSingle(solidImage(256, Color.BLUE))
            val spread = ImagePage.ImageSpread(left, right)
            try {
                left.replaceOverlay(overlay(64f, 80f))
                spread.scale = 0.5f
                val pixels = render(spread)
                assertPixel(Color.WHITE, pixels, 34, 106)
                assertPixel(Color.RED, pixels, 20, 106)
                assertPixel(Color.BLUE, pixels, 162, 106)
            } finally {
                spread.cleanup()
                left.cleanup()
                right.cleanup()
            }
        }

    @Test
    fun repeatedTranslucentRevisionsDoNotAccumulateOrLeaveOldRegions() =
        runBlocking {
            val page = ImagePage.ImageSingle(solidImage(256, Color.RED))
            try {
                val translucentWhite = Color.argb(128, 255, 255, 255)
                val pixels =
                    render(page, frames = 20) { frame ->
                        page.replaceOverlay(
                            overlay(
                                if (frame % 2 ==
                                    0
                                ) {
                                    40f
                                } else {
                                    80f
                                },
                                60f,
                                translucentWhite,
                                frame.toLong(),
                            ),
                        )
                    }
                assertPixel(Color.RED, pixels, 42, 62)
                assertPixel(Color.rgb(255, 128, 128), pixels, 82, 62)
            } finally {
                page.cleanup()
            }
        }

    @Test
    fun canonicalRasterizedMaskMatchesCanvasOpacity() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val result =
                TranslationPageResult(
                    "0",
                    "fixture",
                    256,
                    256,
                    listOf(
                        TextRegion(
                            "speech",
                            listOf(
                                TranslationPoint(40f, 40f),
                                TranslationPoint(160f, 40f),
                                TranslationPoint(160f, 160f),
                                TranslationPoint(40f, 160f),
                            ),
                            "original",
                            "Translated",
                        ),
                    ),
                )
            val style =
                OverlayStyle(
                    backgroundColor = Color.WHITE.toLong(),
                    backgroundOpacity = 128f / 255f,
                    textColor = Color.BLACK.toLong(),
                    autoFit = false,
                    fontSize = 16f,
                )
            val document = TranslationOverlayDocument.create(context, result, style)
            val canvasBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val page = ImagePage.ImageSingle(solidImage(256, Color.RED))
            try {
                canvasBitmap.eraseColor(Color.RED)
                document.draw(Canvas(canvasBitmap))
                page.replaceOverlay(document.gpuOverlay())
                val pixels = render(page)
                assertEquals(Color.rgb(255, 128, 128), canvasBitmap.getPixel(60, 60))
                assertPixel(canvasBitmap.getPixel(60, 60), pixels, 60, 60)
                assertPixel(Color.RED, pixels, 30, 60)
            } finally {
                canvasBitmap.recycle()
                page.cleanup()
            }
        }

    @Test
    fun zoomedTransparentEdgesInterpolatePremultipliedColor() =
        runBlocking {
            val page = ImagePage.ImageSingle(solidImage(256, Color.RED))
            try {
                val rgba = ByteBuffer.allocateDirect(16 * 16 * 4)
                repeat(16 * 16) { pixel ->
                    val channel = if (pixel % 16 < 8) 255.toByte() else 0.toByte()
                    rgba
                        .put(channel)
                        .put(channel)
                        .put(channel)
                        .put(channel)
                }
                rgba.flip()
                val image = Image(rgba, 16, 16, createMipMaps = false, backgroundColor = Color.TRANSPARENT)
                page.replaceOverlay(ImageOverlay(1, 256, 256, listOf(ImageOverlay.Layer(image, 100f, 120f, 16f, 16f))))
                page.scale = 2f
                val pixels = render(page)
                assertPixel(Color.WHITE, pixels, 86, 120)
                assertPixel(Color.rgb(255, 191, 191), pixels, 87, 120)
                assertPixel(Color.rgb(255, 64, 64), pixels, 88, 120)
                assertPixel(Color.RED, pixels, 89, 120)
            } finally {
                page.cleanup()
            }
        }

    private suspend fun overlay(
        left: Float,
        top: Float,
        color: Int = Color.WHITE,
        revision: Long = 1L,
    ) = ImageOverlay(
        revision,
        256,
        256,
        listOf(ImageOverlay.Layer(solidImage(16, color), left, top, 16f, 16f)),
    )

    private suspend fun solidImage(
        size: Int,
        color: Int,
    ): Image {
        val rgba = ByteBuffer.allocateDirect(size * size * 4)
        repeat(size * size) {
            rgba
                .put(Color.red(color).toByte())
                .put(Color.green(color).toByte())
                .put(Color.blue(color).toByte())
                .put(Color.alpha(color).toByte())
        }
        rgba.flip()
        return Image(rgba, size, size, createMipMaps = false, backgroundColor = color)
    }

    /** Offscreen draw and real Dawn readback exercise the shipped JNI and shader pipeline. */
    private suspend fun render(
        page: ImagePage.ImageSingle,
        frames: Int = 1,
        beforeFrame: suspend (Int) -> Unit = {},
    ): ByteArray =
        WebGpuRenderer.withContext { device ->
            val extent = GPUExtent3D(256, 256)
            val texture =
                device.createTexture(
                    GPUTextureDescriptor(
                        usage = TextureUsage.RenderAttachment or TextureUsage.CopySrc or TextureUsage.TextureBinding,
                        size = extent,
                        format = TextureFormat.RGBA8Unorm,
                    ),
                )
            val size = 256L * 256 * 4
            val buffer =
                device.createBuffer(
                    GPUBufferDescriptor(
                        size = size,
                        usage =
                        BufferUsage.CopyDst or BufferUsage.MapRead,
                    ),
                )
            try {
                repeat(frames) { frame ->
                    beforeFrame(frame)
                    val draw = device.createCommandEncoder()
                    page.renderWith(draw, 0f, 0f, 1f, texture)
                    device.queue.submit(arrayOf(draw.finish()))
                }
                val encoder = device.createCommandEncoder()
                encoder.copyTextureToBuffer(
                    GPUTexelCopyTextureInfo(texture),
                    GPUTexelCopyBufferInfo(buffer, GPUTexelCopyBufferLayout(bytesPerRow = 1024, rowsPerImage = 256)),
                    extent,
                )
                device.queue.submit(arrayOf(encoder.finish()))
                withTimeout(15_000) {
                    coroutineScope {
                        val pump =
                            launch {
                                while (isActive) {
                                    WebGpuRenderer.instance.processEvents()
                                    delay(1)
                                }
                            }
                        try {
                            buffer.mapAndAwait(MapMode.Read, 0, size)
                        } finally {
                            pump.cancel()
                        }
                    }
                }
                val mapped = requireNotNull(buffer.getConstMappedRange(0, size))
                ByteArray(size.toInt()).also { mapped.get(it) }
            } finally {
                buffer.unmap()
                buffer.destroy()
                texture.destroy()
            }
        }

    private fun assertPixel(
        expected: Int,
        pixels: ByteArray,
        x: Int,
        y: Int,
    ) {
        val offset = (y * 256 + x) * 4
        val actual =
            Color.argb(
                pixels[offset + 3].toInt() and 255,
                pixels[offset].toInt() and 255,
                pixels[offset + 1].toInt() and 255,
                pixels[offset + 2].toInt() and 255,
            )
        assertEquals("pixel ($x,$y)", expected, actual)
    }
}
