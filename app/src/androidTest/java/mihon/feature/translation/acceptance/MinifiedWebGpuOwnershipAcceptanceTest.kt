package mihon.feature.translation.acceptance

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUExtent3D
import androidx.webgpu.GPUTexelCopyBufferInfo
import androidx.webgpu.GPUTexelCopyBufferLayout
import androidx.webgpu.GPUTexelCopyTextureInfo
import androidx.webgpu.GPUTexture
import androidx.webgpu.GPUTextureDescriptor
import androidx.webgpu.MapMode
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureUsage
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.renderer.ImageOverlay
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
import ca.mpreg.webgpuviewer.viewer.ImagePage
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.app.di.appGraph
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.TranslationJobState
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/** Native allocation regression, using disposable pixels and an offscreen target only. */
@RunWith(AndroidJUnit4::class)
class MinifiedWebGpuOwnershipAcceptanceTest {
    @Test
    fun repeatedOverlayDrawsReleaseNativeReferencesAfterGpuCompletion() = runBlocking<Unit> {
        assumeTrue(
            "Native ownership acceptance requires -e translation.acceptance true and the minified benchmark APK",
            InstrumentationRegistry.getArguments().getString("translation.acceptance") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.mihon.benchmark", context.packageName)
        assertTrue(BuildConfig.TRANSLATION_LOCAL_FIXTURES_ENABLED)
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val exportId = InstrumentationRegistry.getArguments().getString("translation.webgpu.exportReportId")
        if (exportId != null) {
            require(exportId.matches(Regex("webgpu-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            val existing = File(context.getExternalFilesDir(null), "translation-acceptance/$exportId/report.json")
            emitReport(JSONObject(existing.readText()), "existing_report:$exportId")
            return@runBlocking
        }
        assertTrue(
            "Pause disposable benchmark jobs before measuring native allocations",
            context.appGraph.translationRepository.jobs().none {
                it.state in setOf(
                    TranslationJobState.QUEUED,
                    TranslationJobState.ACQUIRING,
                    TranslationJobState.OCR,
                    TranslationJobState.TRANSLATING,
                )
            },
        )
        val directory = File(context.getExternalFilesDir(null), "translation-acceptance/webgpu-${UUID.randomUUID()}")
        check(directory.mkdirs())
        Os.chmod(directory.path, 0b111000000)
        val report = JSONObject().put("package", context.packageName)
            .put("flow", "128 warm frames then two 512-frame blocks; eight fixed overlay tiles")
            .put("new_provider_calls", 0)
            .put("queue_completion_barrier", "after every frame")
            .put("measurement", "Debug.getNativeHeapAllocatedSize bytes; not PSS, Java or graphics memory")
        val samples = JSONArray()
        report.put("samples", samples)
        var page: ImagePage.ImageSingle? = null
        var target: GPUTexture? = null
        try {
            withTimeout(120_000) {
                WebGpuRenderer.withContext { device ->
                    val subject = ImagePage.ImageSingle(image(128, 128, 255, 255, 255))
                    page = subject
                    val overlays = (0 until 8).map { index ->
                        ImageOverlay.Layer(image(16, 16, 255, 0, 0), index * 16f, 16f, 16f, 16f)
                    }
                    subject.replaceOverlay(ImageOverlay(1, 128, 128, overlays))
                    val destination = device.createTexture(
                        GPUTextureDescriptor(
                            usage = TextureUsage.RenderAttachment or TextureUsage.CopySrc,
                            size = GPUExtent3D(128, 128, 1),
                            format = TextureFormat.RGBA8Unorm,
                        ),
                    )
                    target = destination
                    suspend fun drawFrames(count: Int) {
                        repeat(count) {
                            device.createCommandEncoder().use { encoder ->
                                subject.renderWith(encoder, 0f, 0f, 1f, destination)
                                encoder.finish().use { commands -> device.queue.use { it.submit(arrayOf(commands)) } }
                            }
                            awaitPumped { device.queue.use { it.onSubmittedWorkDone() } }
                        }
                    }
                    drawFrames(128)
                    assertPixel(destination, 8, 24, listOf(255, 0, 0, 255))
                    assertPixel(destination, 8, 80, listOf(255, 255, 255, 255))
                    samples.put(snapshot("warm_complete"))
                    drawFrames(512)
                    samples.put(snapshot("block_1_complete"))
                    drawFrames(512)
                    samples.put(snapshot("block_2_complete"))
                    assertPixel(destination, 120, 24, listOf(255, 0, 0, 255))
                    subject.replaceOverlay(null)
                    drawFrames(1)
                    assertPixel(destination, 8, 24, listOf(255, 255, 255, 255))
                    report.put("overlay_and_original_pixels", "passed")
                    val retainedGrowth = samples.getJSONObject(2).getLong("native_allocated_bytes") -
                        samples.getJSONObject(1).getLong("native_allocated_bytes")
                    report.put("second_block_native_growth_bytes", retainedGrowth)
                    report.put("regression_growth_ceiling_bytes", 4 * 1024 * 1024)
                    assertTrue(
                        "Native heap grew $retainedGrowth bytes over 512 warmed frames after GPU completion; " +
                            "see ${File(directory, "report.json")}",
                        retainedGrowth < 4 * 1024 * 1024,
                    )
                }
            }
            report.put("status", "passed_native_ownership_regression")
        } catch (error: Throwable) {
            report.put("status", "failed").put("failure", error.javaClass.name)
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    page?.cleanup()
                    WebGpuRenderer.onDispatcher { target?.use { it.destroy() } }
                } catch (error: Throwable) {
                    report.put("status", "failed_cleanup").put("cleanup_failure", error.javaClass.name)
                    throw error
                } finally {
                    val output = File(directory, "report.json")
                    try {
                        output.writeText(report.toString(2))
                        Os.chmod(output.path, 0b110000000)
                    } finally {
                        emitReport(report, "current_run:${directory.name}")
                    }
                    println("TRANSLATION_ACCEPTANCE_EVIDENCE=${output.absolutePath}")
                }
            }
        }
    }

    private fun emitReport(report: JSONObject, source: String) {
        val serialized = report.toString()
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("webgpu_ownership_report_json", serialized)
                putString("webgpu_ownership_report_source", source)
                putString("stream", "\nWEBGPU_OWNERSHIP_REPORT_JSON=$serialized\n")
            },
        )
    }

    private suspend fun image(width: Int, height: Int, r: Int, g: Int, b: Int): Image {
        val bytes = ByteBuffer.allocateDirect(width * height * 4)
        repeat(width * height) { bytes.put(r.toByte()).put(g.toByte()).put(b.toByte()).put(255.toByte()) }
        bytes.flip()
        return Image(bytes, width, height, createMipMaps = false, backgroundColor = 0xFFFFFFFF.toInt())
    }

    private fun snapshot(stage: String) = JSONObject().put("stage", stage)
        .put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
        .put("native_allocated_bytes", Debug.getNativeHeapAllocatedSize())

    private suspend fun assertPixel(texture: GPUTexture, x: Int, y: Int, expected: List<Int>) {
        val device = WebGpuRenderer.device
        val size = 128L * 128 * 4
        device.createBuffer(GPUBufferDescriptor(size = size, usage = BufferUsage.CopyDst or BufferUsage.MapRead))
            .use { buffer ->
                device.createCommandEncoder().use { encoder ->
                    encoder.copyTextureToBuffer(
                        GPUTexelCopyTextureInfo(texture),
                        GPUTexelCopyBufferInfo(
                            buffer,
                            GPUTexelCopyBufferLayout(bytesPerRow = 128 * 4, rowsPerImage = 128),
                        ),
                        GPUExtent3D(128, 128, 1),
                    )
                    encoder.finish().use { commands -> device.queue.use { it.submit(arrayOf(commands)) } }
                }
                awaitPumped { buffer.mapAndAwait(MapMode.Read, 0, size) }
                try {
                    val bytes = buffer.getConstMappedRange(0, size)
                    val offset = (y * 128 + x) * 4
                    assertEquals("RGBA at $x,$y", expected, (0..3).map { bytes.get(offset + it).toInt() and 255 })
                } finally {
                    buffer.unmap()
                }
            }
    }

    private suspend fun awaitPumped(block: suspend () -> Unit) = coroutineScope {
        val pump = launch {
            while (isActive) {
                WebGpuRenderer.instance.processEvents()
                delay(1)
            }
        }
        try {
            block()
        } finally {
            pump.cancel()
        }
    }
}
