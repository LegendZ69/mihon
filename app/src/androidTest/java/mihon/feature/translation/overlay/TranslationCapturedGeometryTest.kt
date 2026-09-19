package mihon.feature.translation.overlay

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
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
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Authored geometry replay, not a model-repair or human-meaning assessment. Push the exact synthetic
 * corpus images 05-mixed.png and 07-rotated.png to an app-readable directory and pass
 * -e translation.geometryFixtures DIRECTORY on the minified benchmark test invocation.
 * The deficient warning baseline is a diagnostic input, not an expected application failure.
 */
@RunWith(AndroidJUnit4::class)
class TranslationCapturedGeometryTest {
    @Test
    fun authoredWarningMaskCoversSourceInkWithoutChangingOutsideArtwork() = runBlocking<Unit> {
        val source = fixture("05-mixed.png", WARNING_HASH)
        val baseline = TextRegion(
            "4:region-3",
            rectangle(351.6f, 1308.8f, 848.4f, 1334.4f),
            sourceText = "KEEP DOOR CLOSED",
            translatedText = "KEEP DOOR CLOSED",
            readingOrder = 3,
        )
        val corrected = baseline.copy(points = rectangle(351.6f, 1296f, 848.4f, 1334.4f))
        val report = report("clipped-warning", WARNING_HASH)
        try {
            val ink = sourceInk(source, Rect(354, 1296, 845, 1333))
            assertEquals("Exact retained synthetic warning ink", 5395, ink.size)
            val before = ink.count { inside(it % WIDTH + 0.5f, it / WIDTH + 0.5f, baseline.points) }
            val after = ink.count { inside(it % WIDTH + 0.5f, it / WIDTH + 0.5f, corrected.points) }
            report.put("source_ink_pixels", ink.size).put("baseline_geometry_covered", before)
                .put("authored_geometry_covered", after)
                .put("baseline_scope", "Intentionally deficient input geometry; not a renderer defect")
            assertEquals("Retain the captured coverage deficit as a diagnostic", 3421, before)
            assertEquals(5395, after)
            val badOverlay = raster(document(baseline, WARNING_HASH))
            try {
                assertTrue(
                    "Rendering the deficient baseline must still expose original ink",
                    ink.any { Color.alpha(badOverlay.getPixel(it % WIDTH, it / WIDTH)) < 250 },
                )
            } finally {
                badOverlay.recycle()
            }
            assertCandidate(source, corrected, WARNING_HASH, ink, report)
            report.put("status", "passed_authored_geometry_canvas_and_gpu")
        } finally {
            source.recycle()
            emit(report)
        }
    }

    @Test
    fun authoredClockwiseJapaneseUsesTheSameGeometryInCanvasAndGpu() = runBlocking<Unit> {
        val source = fixture("07-rotated.png", JAPANESE_HASH)
        val corrected = TextRegion(
            "6:region-1",
            listOf(
                TranslationPoint(637f, 1012f),
                TranslationPoint(637f, 1247f),
                TranslationPoint(562f, 1247f),
                TranslationPoint(562f, 1012f),
            ),
            sourceText = "逃げて！",
            translatedText = "Run!",
            readingOrder = 1,
            rotation = 90f,
        )
        val report = report("sideways-japanese", JAPANESE_HASH)
        try {
            val ink = sourceInk(source, Rect(570, 1020, 629, 1239))
            assertEquals("Exact retained synthetic Japanese ink", 2388, ink.size)
            assertEquals(2388, ink.count { inside(it % WIDTH + 0.5f, it / WIDTH + 0.5f, corrected.points) })
            report.put("source_ink_pixels", ink.size).put("authored_geometry_covered", ink.size)
                .put("authored_clockwise_rotation_degrees", 90)
            assertClockwiseGlyphs(corrected, report)
            assertCandidate(source, corrected, JAPANESE_HASH, ink, report)
            report.put("status", "passed_authored_geometry_canvas_and_gpu")
        } finally {
            source.recycle()
            emit(report)
        }
    }

    private suspend fun assertCandidate(
        source: Bitmap,
        region: TextRegion,
        hash: String,
        ink: List<Int>,
        report: JSONObject,
    ) {
        val document = document(region, hash)
        val overlay = raster(document)
        val composite = source.copy(Bitmap.Config.ARGB_8888, true)
        try {
            document.draw(Canvas(composite))
            assertEquals(
                "The actual Canvas mask must cover every known source-ink pixel",
                ink.size,
                ink.count { Color.alpha(overlay.getPixel(it % WIDTH, it / WIDTH)) >= 250 },
            )
            val originalPixels = pixels(source)
            val canvasPixels = pixels(composite)
            val untouched = outsideMask(region)
            assertUnchanged(originalPixels, canvasPixels, untouched, "Canvas")
            report.put("canvas_source_ink_covered", ink.size)
                .put("canvas_outside_mask_pixels_unchanged", untouched.count { it })
            val rgba = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4)
            originalPixels.forEach { color ->
                rgba.put(Color.red(color).toByte()).put(Color.green(color).toByte())
                    .put(Color.blue(color).toByte()).put(Color.alpha(color).toByte())
            }
            rgba.flip()
            val page = ImagePage.ImageSingle(Image(rgba, WIDTH, HEIGHT, createMipMaps = false))
            try {
                val originalGpu = render(page)
                assertUnchanged(originalPixels, originalGpu, untouched, "GPU source upload")
                page.replaceOverlay(document.gpuOverlay())
                val translatedGpu = render(page)
                assertUnchanged(originalGpu, translatedGpu, untouched, "GPU overlay")
                assertEquals(
                    "GPU mask/white glyphs must replace every known dark source-ink pixel",
                    ink.size,
                    ink.count { Color.blue(translatedGpu[it]) >= 250 },
                )
                val overlayPixels = pixels(overlay)
                val opaqueGlyphs = overlayPixels.indices.filter {
                    whiteGlyph(overlayPixels[it]) && Color.red(overlayPixels[it]) >= 250 &&
                        Color.green(overlayPixels[it]) >= 250
                }
                assertTrue("The translated passage must be visible", opaqueGlyphs.size > 100)
                assertEquals(
                    "GPU must preserve all opaque Canvas translation glyph pixels at source coordinates",
                    0,
                    opaqueGlyphs.count { !whiteGlyph(translatedGpu[it]) },
                )
                page.replaceOverlay(null)
                assertTrue(
                    "Original comparison must restore every source pixel",
                    originalGpu.contentEquals(render(page)),
                )
                report.put("canvas_and_gpu_source_ink_covered", ink.size)
                    .put("outside_mask_pixels_unchanged", untouched.count { it })
                    .put("opaque_translation_glyph_pixels_preserved", opaqueGlyphs.size)
                    .put("original_comparison_restored", true)
            } finally {
                page.cleanup()
            }
        } finally {
            overlay.recycle()
            composite.recycle()
        }
    }

    private fun assertClockwiseGlyphs(region: TextRegion, report: JSONObject) {
        val actual = raster(document(region, JAPANESE_HASH))
        val unclipped = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val expected = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val oracle = object : Canvas(unclipped) {
                var clips = 0

                override fun clipPath(path: Path): Boolean {
                    clips++
                    return true
                }
            }
            document(region, JAPANESE_HASH).draw(oracle)
            assertEquals("Only the region clip is bypassed", 1, oracle.clips)
            val actualPixels = pixels(actual)
            val unclippedPixels = pixels(unclipped)
            val referenceInk = unclippedPixels.indices.filter { whiteGlyph(unclippedPixels[it]) }
            assertTrue(referenceInk.size > 100)
            assertEquals(
                "Authored +90 glyphs must not be clipped",
                0,
                referenceInk.count {
                    !whiteGlyph(actualPixels[it])
                },
            )

            // Independent image-space oracle: draw the same passage horizontally in the swapped
            // 235 x 75 rectangle, then rotate the complete Canvas clockwise around its shared center.
            // A one-pixel neighbourhood allows integer StaticLayout width/centering rounding only.
            val horizontal = region.copy(points = rectangle(482f, 1092f, 717f, 1167f), rotation = 0f)
            Canvas(expected).apply {
                rotate(90f, 599.5f, 1129.5f)
                document(horizontal, JAPANESE_HASH).draw(this)
            }
            val expectedPixels = pixels(expected)
            val expectedInk = expectedPixels.indices.filter { whiteGlyph(expectedPixels[it]) }
            assertTrue(expectedInk.size > 100)
            assertTrue(
                "Clockwise glyph orientation must match the independent rotated Canvas",
                expectedInk.all {
                    nearWhite(actualPixels, it)
                },
            )
            assertTrue(
                "Clockwise replay must not introduce differently oriented glyphs",
                referenceInk.all {
                    nearWhite(expectedPixels, it)
                },
            )
            report.put(
                "clockwise_orientation_reference",
                "Horizontal passage Canvas rotated +90; one-pixel rounding tolerance",
            )
                .put("unclipped_reference_glyph_pixels", referenceInk.size).put("clipped_glyph_pixels", 0)
        } finally {
            actual.recycle()
            unclipped.recycle()
            expected.recycle()
        }
    }

    private fun fixture(name: String, hash: String): Bitmap {
        val arguments = InstrumentationRegistry.getArguments()
        val directory = arguments.getString("translation.geometryFixtures")
        assumeTrue("Requires explicit translation.geometryFixtures and the minified benchmark APK", directory != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.mihon.benchmark", context.packageName)
        assertTrue(BuildConfig.TRANSLATION_LOCAL_FIXTURES_ENABLED)
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val file = File(requireNotNull(directory), name)
        assertTrue("Explicit synthetic fixture must exist: $name", file.isFile)
        val bytes = file.readBytes()
        assertEquals("Never replace a retained fixture with arbitrary chapter content", hash, sha256(bytes))
        val bitmap =
            requireNotNull(
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply {
                        inScaled =
                            false
                    },
                ),
            )
        assertEquals(WIDTH, bitmap.width)
        assertEquals(HEIGHT, bitmap.height)
        return bitmap
    }

    private fun document(region: TextRegion, hash: String) = TranslationOverlayDocument.create(
        InstrumentationRegistry.getInstrumentation().targetContext,
        TranslationPageResult(region.id.substringBefore(':'), hash, WIDTH, HEIGHT, listOf(region)),
        OverlayStyle(textColor = Color.WHITE.toLong(), backgroundColor = Color.BLUE.toLong(), maxFontSize = 48f),
    )

    private fun raster(document: TranslationOverlayDocument) = Bitmap.createBitmap(
        WIDTH,
        HEIGHT,
        Bitmap.Config.ARGB_8888,
    )
        .also { document.draw(Canvas(it)) }

    private fun pixels(bitmap: Bitmap) = IntArray(WIDTH * HEIGHT).also {
        bitmap.getPixels(it, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
    }

    private fun sourceInk(bitmap: Bitmap, roi: Rect): List<Int> = buildList {
        for (y in roi.top until roi.bottom) {
            for (x in roi.left until roi.right) {
                val color = bitmap.getPixel(x, y)
                assertEquals("Synthetic ink must remain grayscale", Color.red(color), Color.green(color))
                assertEquals(Color.red(color), Color.blue(color))
                if (Color.red(color) < 128) add(y * WIDTH + x)
            }
        }
    }

    private fun outsideMask(region: TextRegion): BooleanArray {
        val left = region.points.minOf { it.x } - 2
        val right = region.points.maxOf { it.x } + 2
        val top = region.points.minOf { it.y } - 2
        val bottom = region.points.maxOf { it.y } + 2
        return BooleanArray(WIDTH * HEIGHT) { index ->
            val x = index % WIDTH + 0.5f
            val y = index / WIDTH + 0.5f
            x < left || x > right || y < top || y > bottom
        }
    }

    private fun assertUnchanged(before: IntArray, after: IntArray, indices: BooleanArray, label: String) {
        assertEquals(
            "$label must preserve source artwork outside the mask's antialiased perimeter",
            0,
            indices.indices.count {
                indices[it] &&
                    before[it] != after[it]
            },
        )
    }

    private fun whiteGlyph(color: Int) =
        Color.alpha(color) >= 250 && Color.red(color) >= 245 && Color.green(color) >= 245

    private fun nearWhite(pixels: IntArray, index: Int): Boolean {
        val x = index % WIDTH
        val y = index / WIDTH
        return (y - 1..y + 1).any { yy ->
            (x - 1..x + 1).any { xx ->
                xx in 0 until WIDTH && yy in 0 until HEIGHT &&
                    whiteGlyph(pixels[yy * WIDTH + xx])
            }
        }
    }

    private fun inside(x: Float, y: Float, points: List<TranslationPoint>): Boolean {
        val signs = points.indices.mapNotNull { index ->
            val a = points[index]
            val b = points[(index + 1) % points.size]
            val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            if (abs(cross) < 1e-6f) null else cross > 0
        }
        return signs.all { it } || signs.none { it }
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
                    usage =
                    TextureUsage.RenderAttachment or TextureUsage.CopySrc,
                ),
            ).use { target ->
                try {
                    device.createBuffer(
                        GPUBufferDescriptor(
                            size = size,
                            usage =
                            BufferUsage.CopyDst or BufferUsage.MapRead,
                        ),
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

    private fun report(case: String, hash: String) = JSONObject().put("case", case).put("source_sha256", hash)
        .put("status", "incomplete").put("assessment", "AUTHORED_GEOMETRY_REPLAY")
        .put("human_meaning_review", "pending").put("test_provider_dispatches", 0)
        .put(
            "scope",
            "One selected region; source-ink ROI and pixels outside its mask bounds, not all artwork inside the mask",
        )

    private fun emit(report: JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("captured_geometry_report_json", report.toString())
            },
        )
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }

    companion object {
        private const val WIDTH = 1200
        private const val HEIGHT = 1600
        private const val WARNING_HASH = "4df2188c319afaf90f8b0e947df081c0a3cc99ebe8dd564c9a3b2ef24834b5ce"
        private const val JAPANESE_HASH = "caa85f68b36c050d3182a0ec46f69ccca96050aaf7c9642cb4367f94efbff384"
    }
}
