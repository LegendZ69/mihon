package mihon.feature.translation.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
import ca.mpreg.webgpuviewer.viewer.ImagePage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationSettings
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Cached authored regions on the unchanged corpus page; no OCR or provider work. */
@RunWith(AndroidJUnit4::class)
class TranslationSoundEffectOverlayTest {
    @Test
    fun defaultPresentationPreservesSoundEffectPixelsWhileTranslatingSignsInCanvasAndGpu() = runBlocking<Unit> {
        val directory = InstrumentationRegistry.getArguments().getString("translation.geometryFixtures")
        assumeTrue("Requires the retained signs-and-SFX fixture directory", directory != null)
        val file = File(requireNotNull(directory), "08-signs-and-sfx.png")
        val bytes = file.readBytes()
        assertEquals(SOURCE_HASH, sha256(bytes))
        val source = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        assertEquals(WIDTH, source.width)
        assertEquals(HEIGHT, source.height)
        val style = TranslationSettings().style.copy(
            textColor = Color.WHITE.toLong(),
            backgroundColor = Color.BLUE.toLong(),
            maxFontSize = 48f,
            showIgnored = true,
        )
        val regions = listOf(
            TextRegion(
                "sign-ja",
                rectangle(455f, 250f, 745f, 330f),
                "立入禁止",
                "No entry",
                readingOrder = 0,
                type = "sign",
            ),
            TextRegion(
                "sfx-ko",
                rectangle(756f, 683f, 903f, 806f),
                "쿵!",
                "THUD!",
                readingOrder = 1,
                type = "sound_effect",
                style = style.copy(backgroundColor = Color.MAGENTA.toLong(), enabled = true),
            ),
            TextRegion(
                "sign-hant",
                rectangle(458f, 1198f, 742f, 1281f),
                "緊急出口",
                "Emergency exit",
                readingOrder = 2,
                type = "sign",
            ),
        )
        val saved = TranslationPageResult("7", SOURCE_HASH, WIDTH, HEIGHT, regions, revision = 42)
        val originalResult = saved.copy(regions = regions.map { it.copy(points = it.points.toList()) })
        val document = TranslationOverlayDocument.create(
            InstrumentationRegistry.getInstrumentation().targetContext,
            saved,
            style,
        )
        val original = pixels(source)
        val composite = source.copy(Bitmap.Config.ARGB_8888, true)
        val rgba = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4)
        original.forEach { color ->
            rgba.put(Color.red(color).toByte()).put(Color.green(color).toByte())
                .put(Color.blue(color).toByte()).put(Color.alpha(color).toByte())
        }
        rgba.flip()
        val page = ImagePage.ImageSingle(Image(rgba, WIDTH, HEIGHT, createMipMaps = false))
        try {
            document.draw(Canvas(composite))
            val canvasPixels = pixels(composite)
            val gpuOriginal = render(page)
            page.replaceOverlay(document.gpuOverlay())
            val gpuTranslated = render(page)
            // The whole authored SFX container must remain original, including the old mask area.
            val sfxPixels = area(750, 677, 910, 813)
            val canvasChanged = sfxPixels.count { original[it] != canvasPixels[it] }
            val gpuChanged = sfxPixels.count { gpuOriginal[it] != gpuTranslated[it] }
            val signs = area(455, 250, 745, 330) + area(458, 1198, 742, 1281)
            val canvasSignChanges = signs.count { original[it] != canvasPixels[it] }
            val gpuSignChanges = signs.count { gpuOriginal[it] != gpuTranslated[it] }
            val report = JSONObject().put("case", "cached-signs-and-sound-effects")
                .put("source_sha256", SOURCE_HASH).put("saved_revision", saved.revision)
                .put("canvas_sound_effect_pixels_changed", canvasChanged)
                .put("gpu_sound_effect_pixels_changed", gpuChanged)
                .put("canvas_sign_pixels_changed", canvasSignChanges)
                .put("gpu_sign_pixels_changed", gpuSignChanges)
                .put("show_ignored", true).put("sound_effect_region_style_override", true)
                .put("provider_dispatches", 0)
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply { putString("sound_effect_overlay_report_json", report.toString()) },
            )
            assertTrue("Meaningful signs still receive Canvas translations", canvasSignChanges > 1000)
            assertTrue("Meaningful signs still receive GPU translations", gpuSignChanges > 1000)
            assertSame("Presentation retains the exact cached result", saved, document.result)
            assertEquals(
                "Saved content, geometry, style overrides and revision remain unchanged",
                originalResult,
                saved,
            )
            assertEquals(
                "Canvas must retain every original SFX pixel without mask or translated glyph",
                0,
                canvasChanged,
            )
            assertEquals("GPU must retain every original SFX pixel without mask or translated glyph", 0, gpuChanged)
            page.replaceOverlay(null)
            assertTrue("Original comparison restores the page", gpuOriginal.contentEquals(render(page)))
        } finally {
            page.cleanup()
            composite.recycle()
            source.recycle()
        }
    }

    @Test
    fun cachedPolicyToggleRetainsUnknownTypesAndInvalidatesGpuPresentation() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val style = TranslationSettings().style.copy(
            textColor = Color.WHITE.toLong(),
            backgroundColor = Color.BLUE.toLong(),
            maxFontSize = 48f,
            showIgnored = true,
        )
        val regions = listOf(
            TextRegion(
                "sfx",
                rectangle(100f, 200f, 350f, 350f),
                "THUD!",
                "THUD!",
                correctedText = "Manually corrected source",
                type = "sfx",
                recognitionConfidence = 0.91f,
                style = style.copy(backgroundColor = Color.MAGENTA.toLong()),
            ),
            TextRegion("unknown", rectangle(700f, 200f, 950f, 350f), "THUD!", "THUD!", type = "unclassified"),
        )
        val rawOcr = OcrPageResult("cached", regions, rawJson = "{\"retained\":true}")
        val saved = TranslationPageResult("cached", "fixture", WIDTH, HEIGHT, regions, rawOcr, revision = 42)
        val baseline = saved.copy(regions = regions.map { it.copy(points = it.points.toList()) })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacy = TranslationOverlayDocument.create(
            context,
            saved,
            style,
            contentPolicy = TranslationContentPolicy.Legacy,
        )
        val ignored = TranslationOverlayDocument.create(context, saved, style)
        val original = pixels(source)
        val canvasLegacy = compositePixels(source, legacy)
        val canvasIgnored = compositePixels(source, ignored)
        val sfx = area(100, 200, 350, 350)
        val unknown = area(700, 200, 950, 350)
        assertTrue(
            "Legacy Canvas includes the eligible cached SFX overlay",
            sfx.count { original[it] != canvasLegacy[it] } > 1000,
        )
        assertTrue("Default Canvas preserves the original SFX", sfx.all { original[it] == canvasIgnored[it] })
        assertTrue(
            "Unknown types remain translated despite SFX-looking text",
            unknown.count { original[it] != canvasIgnored[it] } > 1000,
        )
        assertTrue(
            "Policy changes do not alter unknown-type presentation",
            unknown.all {
                canvasLegacy[it] ==
                    canvasIgnored[it]
            },
        )
        val page = createPage(source)
        val originalImage = page.image
        try {
            val gpuOriginal = render(page)
            page.replaceOverlay(legacy.gpuOverlay())
            val gpuLegacy = render(page)
            val priorFrame = page.frameVersion
            page.replaceOverlay(ignored.gpuOverlay())
            assertTrue(
                "Same-result-revision policy changes invalidate GPU and transition presentation",
                page.frameVersion != priorFrame,
            )
            val gpuIgnored = render(page)
            assertTrue(
                "Legacy GPU includes the eligible cached SFX overlay",
                sfx.count { gpuOriginal[it] != gpuLegacy[it] } > 1000,
            )
            assertTrue("Default GPU preserves the original SFX", sfx.all { gpuOriginal[it] == gpuIgnored[it] })
            assertTrue(
                "Unknown types retain their GPU overlay",
                unknown.count { gpuOriginal[it] != gpuIgnored[it] } > 1000,
            )
            assertTrue("Unknown GPU presentation is unchanged", unknown.all { gpuLegacy[it] == gpuIgnored[it] })
            page.replaceOverlay(legacy.gpuOverlay())
            assertTrue("Opt-out reuses eligible cached text without translation", gpuLegacy.contentEquals(render(page)))
            assertSame("Policy toggles preserve the original GPU image", originalImage, page.image)
            assertSame("Raw OCR is not rewritten", rawOcr, ignored.result.rawOcr)
            assertSame("Both policies retain the cached result", saved, legacy.result)
            assertSame("Filtering retains the cached result", saved, ignored.result)
            assertEquals("Manual text, scores, styles, geometry and revision remain unchanged", baseline, saved)
        } finally {
            page.cleanup()
            source.recycle()
        }
    }

    @Test
    fun diagnosticBoxesCannotRestoreSoundEffectMasksOrGlyphs() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val style = TranslationSettings().style.copy(
            textColor = Color.BLUE.toLong(),
            backgroundColor = Color.MAGENTA.toLong(),
            showBoxes = true,
            showIgnored = true,
            showCoordinates = true,
            showConfidence = true,
            showReadingOrder = true,
        )
        val region = TextRegion(
            "sfx",
            rectangle(100f, 200f, 500f, 400f),
            "쿵!",
            "THUD!",
            type = " SFX ",
            detectionConfidence = 0.98f,
            style = style,
        )
        val saved = TranslationPageResult("diagnostic", "fixture", WIDTH, HEIGHT, listOf(region))
        val document = TranslationOverlayDocument.create(
            InstrumentationRegistry.getInstrumentation().targetContext,
            saved,
            style,
        )
        val original = pixels(source)
        val canvas = compositePixels(source, document)
        val interior = area(104, 204, 496, 396)
        val container = area(98, 198, 502, 402)
        assertTrue(
            "An excluded SFX diagnostic outline remains available on Canvas",
            container.any {
                original[it] !=
                    canvas[it]
            },
        )
        assertTrue(
            "Canvas diagnostics do not restore masks, glyphs or labels",
            interior.all {
                original[it] ==
                    canvas[it]
            },
        )
        assertTrue("Excluded SFX has no translated layout diagnostics", document.layoutDiagnostics.isEmpty())
        val page = createPage(source)
        try {
            val gpuOriginal = render(page)
            page.replaceOverlay(document.gpuOverlay())
            val gpu = render(page)
            assertTrue(
                "An excluded SFX diagnostic outline remains available on GPU",
                container.any {
                    gpuOriginal[it] !=
                        gpu[it]
                },
            )
            assertTrue(
                "GPU diagnostics do not restore masks, glyphs or labels",
                interior.all {
                    gpuOriginal[it] ==
                        gpu[it]
                },
            )
            assertSame("Inspection does not rewrite the cached region", region, document.result.regions.single())
        } finally {
            page.cleanup()
            source.recycle()
        }
    }

    private fun compositePixels(source: Bitmap, document: TranslationOverlayDocument): IntArray {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        return try {
            document.draw(Canvas(bitmap))
            pixels(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun createPage(source: Bitmap): ImagePage.ImageSingle {
        val rgba = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4)
        pixels(source).forEach { color ->
            rgba.put(Color.red(color).toByte()).put(Color.green(color).toByte())
                .put(Color.blue(color).toByte()).put(Color.alpha(color).toByte())
        }
        rgba.flip()
        return ImagePage.ImageSingle(Image(rgba, WIDTH, HEIGHT, createMipMaps = false))
    }

    private fun pixels(bitmap: Bitmap) = IntArray(WIDTH * HEIGHT).also {
        bitmap.getPixels(it, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
    }

    private fun area(left: Int, top: Int, right: Int, bottom: Int) = buildList {
        for (y in top until bottom) for (x in left until right) add(y * WIDTH + x)
    }

    private fun rectangle(left: Float, top: Float, right: Float, bottom: Float) = listOf(
        TranslationPoint(left, top),
        TranslationPoint(right, top),
        TranslationPoint(right, bottom),
        TranslationPoint(left, bottom),
    )

    private suspend fun render(page: ImagePage.ImageSingle): IntArray = withTimeout(30_000) {
        WebGpuRenderer.withContext { device ->
            val extent = GPUExtent3D(WIDTH, HEIGHT, 1)
            val stride = ((WIDTH * 4 + 255) / 256) * 256
            val size = stride.toLong() * HEIGHT
            device.createTexture(
                GPUTextureDescriptor(
                    size = extent,
                    format = TextureFormat.RGBA8Unorm,
                    usage = TextureUsage.RenderAttachment or TextureUsage.CopySrc,
                ),
            ).use { target ->
                try {
                    device.createBuffer(
                        GPUBufferDescriptor(size = size, usage = BufferUsage.CopyDst or BufferUsage.MapRead),
                    ).use { buffer ->
                        try {
                            device.createCommandEncoder().use { encoder ->
                                page.renderWith(encoder, 0f, 0f, 1f, target)
                                encoder.copyTextureToBuffer(
                                    GPUTexelCopyTextureInfo(target),
                                    GPUTexelCopyBufferInfo(
                                        buffer,
                                        GPUTexelCopyBufferLayout(bytesPerRow = stride, rowsPerImage = HEIGHT),
                                    ),
                                    extent,
                                )
                                encoder.finish().use { commands -> device.queue.use { it.submit(arrayOf(commands)) } }
                            }
                            coroutineScope {
                                val pump = launch {
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
                            try {
                                val bytes = buffer.getConstMappedRange(0, size)
                                IntArray(WIDTH * HEIGHT) { index ->
                                    val offset = index / WIDTH * stride + index % WIDTH * 4
                                    Color.argb(
                                        bytes.get(offset + 3).toInt() and 255,
                                        bytes.get(offset).toInt() and 255,
                                        bytes.get(offset + 1).toInt() and 255,
                                        bytes.get(offset + 2).toInt() and 255,
                                    )
                                }
                            } finally {
                                buffer.unmap()
                            }
                        } finally {
                            buffer.destroy()
                        }
                    }
                } finally {
                    target.destroy()
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val WIDTH = 1200
        private const val HEIGHT = 1600
        private const val SOURCE_HASH = "8ec84f9854dc46f072ec7e4587e93f69a02c14f7495d1a71e1a245ee4ebbeb14"
    }
}
