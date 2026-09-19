package mihon.feature.translation.overlay

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.QualityReviewRenderEvidence
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationSettings
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Explicit local-only workload. Every image and evidence directory belongs to this invocation. */
@RunWith(AndroidJUnit4::class)
class QualityReviewRenderPerformanceTest {
    @Test
    fun oneColdAndThreeWarmBoundedPreviewPreparations() = measuredRun("cold-warm") {
        report.put("requested_preparations", 4)
        report.put("cold_definition", "First renderer prepare; OS, Android graphics and font caches are not flushed")
        repeat(4) { iteration -> prepareAndCleanup(if (iteration == 0) "first-prepare" else "warm", iteration) }
    }

    @Test
    fun threeMinuteSequentialBoundedPreviewWorkload() = measuredRun("sustained") {
        val started = SystemClock.elapsedRealtime()
        report.put("workload_started_elapsed_ms", started).put("requested_duration_ms", SUSTAINED_MS)
        persistAndEmit()
        var iteration = 0
        var lastProgress = started
        withTimeout(SUSTAINED_MS + 60_000) {
            do {
                prepareAndCleanup("sustained", iteration++)
                val now = SystemClock.elapsedRealtime()
                if (now - lastProgress >= 30_000) {
                    persistAndEmit()
                    lastProgress = now
                }
            } while (SystemClock.elapsedRealtime() - started < SUSTAINED_MS)
        }
        val completed = SystemClock.elapsedRealtime()
        report.put("workload_completed_elapsed_ms", completed)
            .put("workload_elapsed_ms", completed - started)
            .put("duration_includes", "Sequential preparation, validation, cleanup, observations and evidence writes")
        assertTrue(completed - started >= SUSTAINED_MS)
    }

    private fun measuredRun(label: String, block: suspend Run.() -> Unit) = runBlocking<Unit> {
        assumeTrue(
            "Enable only for requested local performance evidence",
            InstrumentationRegistry.getArguments().getString("translation.renderPerformance") == "true",
        )
        val base = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(
            "Run this workload through the normal minified benchmark variant",
            base.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
        withContext(Dispatchers.IO) {
            val run = Run(base, label)
            var failure: Throwable? = null
            try {
                run.prepareSource()
                run.persistAndEmit()
                run.block()
                run.report.put("status", "passed")
            } catch (error: Throwable) {
                failure = error
                run.report.put("status", "failed").put("failure_class", error.javaClass.name)
                    .put("failure_message", error.message ?: "Unavailable")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    val removed = run.work.deleteRecursively()
                    run.report.put("owned_work_removed", removed && !run.work.exists())
                    if (!removed || run.work.exists()) {
                        run.report.put("status", "failed_cleanup")
                        if (failure == null) failure = IllegalStateException("Owned performance work was not removed")
                    }
                    run.report.put("finished_epoch_ms", System.currentTimeMillis())
                    run.report.put("memory_after_owned_cleanup", memory())
                    run.persistAndEmit()
                }
            }
            failure?.let { throw it }
        }
    }

    private class Run(base: Context, label: String) {
        private val runId = UUID.randomUUID().toString()
        private val output = File(
            checkNotNull(base.getExternalFilesDir(null)),
            "translation-acceptance/render-performance-$label-$runId",
        ).apply { check(mkdirs()) }
        val work = File(base.cacheDir, "review-render-performance-$runId").apply { check(mkdirs()) }
        private val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(work, "files").apply { check(isDirectory || mkdirs()) }
        }
        private val renderer = AndroidQualityReviewRenderer(context)
        private val records = File(output, "iterations.jsonl")
        private var previewHash: String? = null
        private var count = 0
        private var preparedCount = 0
        private var totalPrepareNanos = 0L
        private var totalCleanupNanos = 0L
        private var minPrepareNanos = Long.MAX_VALUE
        private var maxPrepareNanos = 0L
        private var maxObservedJavaBytes = 0L
        private var maxObservedNativeBytes = 0L
        private val firstRecords = JSONArray()
        private lateinit var original: TranslationImage
        private lateinit var baseline: TranslationPageResult
        private val style = OverlayStyle(
            minFontSize = 12f,
            maxFontSize = 42f,
            textColor = Color.BLACK.toLong(),
            backgroundColor = Color.WHITE.toUInt().toLong(),
            outlineColor = Color.LTGRAY.toUInt().toLong(),
            outlineWidth = 2f,
            padding = 5f,
            italic = true,
        )
        val report = JSONObject().put("schema_version", 1).put("run_id", runId)
            .put("case", label).put("status", "running").put("started_epoch_ms", System.currentTimeMillis())
            .put("started_elapsed_ms", SystemClock.elapsedRealtime()).put("pid", android.os.Process.myPid())
            .put("package", base.packageName).put("sdk_int", Build.VERSION.SDK_INT)
            .put("build_fingerprint", Build.FINGERPRINT)
            .put("runtime_page_size", Os.sysconf(OsConstants._SC_PAGESIZE))
            .put("renderer_version", AndroidQualityReviewRenderer.VERSION)
            .put("provider_dispatches", 0).put("credential_accesses", 0).put("queue_jobs_created", 0)
            .put("source_pixels", WIDTH.toLong() * HEIGHT).put("preview_pixel_ceiling", 2L * 1024 * 1024)
            .put("argb_pixel_buffer_bytes", WIDTH.toLong() * HEIGHT * 4)
            .put(
                "memory_scope",
                "Boundary snapshots; not peak memory, allocations, GPU memory or a whole-process bound",
            )
            .put("gpu_counters", JSONObject.NULL).put("gpu_note", "Unavailable from these process counters")
            .put("gc_policy", "No explicit GC or cache flushing")
            .put("report_path", File(output, "report.json").path).put("iterations_path", records.path)

        fun prepareSource() {
            val started = SystemClock.elapsedRealtimeNanos()
            val source = File(work, "source.png")
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val passages = buildList {
                for (row in 0 until 5) {
                    for (column in 0 until 4) {
                        val left = 28f + column * 340f
                        val top = 30f + row * 290f
                        val index = row * 4 + column
                        add(
                            TextRegion(
                                "passage-$index",
                                listOf(
                                    TranslationPoint(left, top),
                                    TranslationPoint(left + 310f, top),
                                    TranslationPoint(left + 310f, top + 250f),
                                    TranslationPoint(left, top + 250f),
                                ),
                                sourceText = "Synthetic original passage $index",
                                translatedText = when (index % 4) {
                                    0 -> "KEEP DOOR CLOSED. 危険！ 닫아!"
                                    1 -> "Stop! It's dangerous! مرحبًا नमस्ते"
                                    2 -> "Please wait here. 請關門。မြန်မာ"
                                    else -> "Source angle retained. 日本語 한국어"
                                },
                                readingOrder = index,
                                rotation = when (index % 4) {
                                    1 -> 90f
                                    2 -> -17f
                                    else -> 0f
                                },
                            ),
                        )
                    }
                }
            }
            try {
                bitmap.eraseColor(Color.rgb(226, 232, 238))
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.DKGRAY
                    textSize = 20f
                }
                passages.forEach { region ->
                    val point = region.points.first()
                    canvas.drawText(region.sourceText, point.x + 10f, point.y + 40f, paint)
                    canvas.drawLine(point.x, point.y + 255f, point.x + 310f, point.y + 255f, paint)
                }
                source.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            original = TranslationImage(
                "page",
                0,
                source.path,
                "image/png",
                WIDTH,
                HEIGHT,
                hash(source),
                source.length(),
            )
            baseline = TranslationPageResult(original.id, original.contentHash, WIDTH, HEIGHT, passages, revision = 42)
            report.put("source_generated_once", true).put("source_width", WIDTH).put("source_height", HEIGHT)
                .put("source_sha256", original.contentHash).put("source_png_bytes", original.byteSize)
                .put("region_count", passages.size).put("baseline_revision", baseline.revision)
                .put("source_generation_nanos", SystemClock.elapsedRealtimeNanos() - started)
                .put("source_generation_in_prepare_timings", false)
                .put("memory_after_source_generation", observedMemory())
        }

        suspend fun prepareAndCleanup(phase: String, iteration: Int) {
            val reviewId = UUID.randomUUID().toString()
            val request = QualityReviewRequest(
                "synthetic-render-performance-$runId",
                reviewId,
                TranslationSettings(style = style),
                original,
                baseline,
            )
            val record = JSONObject().put("iteration", iteration).put("phase", phase).put("review_id", reviewId)
                .put("started_elapsed_ms", SystemClock.elapsedRealtime()).put("memory_before", observedMemory())
            var evidence: QualityReviewRenderEvidence? = null
            val started = SystemClock.elapsedRealtimeNanos()
            try {
                evidence = renderer.prepare(request)
                val prepareNanos = SystemClock.elapsedRealtimeNanos() - started
                record.put("prepare_nanos", prepareNanos).put("memory_after_prepare", observedMemory())
                preparedCount++
                totalPrepareNanos += prepareNanos
                minPrepareNanos = minOf(minPrepareNanos, prepareNanos)
                maxPrepareNanos = maxOf(maxPrepareNanos, prepareNanos)
                val validateStarted = SystemClock.elapsedRealtimeNanos()
                val preview = File(evidence.preview.filePath)
                val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(preview.path, dimensions)
                assertEquals(WIDTH, dimensions.outWidth)
                assertEquals(HEIGHT, dimensions.outHeight)
                assertEquals(WIDTH, evidence.preview.width)
                assertEquals(HEIGHT, evidence.preview.height)
                assertEquals(1.0, evidence.scaleX, 0.0)
                assertEquals(1.0, evidence.scaleY, 0.0)
                assertEquals(original.contentHash, hash(File(original.filePath)))
                assertEquals(original.contentHash, hash(File(evidence.original.filePath)))
                assertEquals(evidence.preview.contentHash, hash(preview))
                if (previewHash == null) previewHash = evidence.preview.contentHash
                assertEquals(previewHash, evidence.preview.contentHash)
                assertEquals(42L, request.baseline.revision)
                record.put("preview_width", dimensions.outWidth).put("preview_height", dimensions.outHeight)
                    .put("original_sha256", original.contentHash).put("preview_sha256", evidence.preview.contentHash)
                    .put("preview_png_bytes", preview.length()).put("source_revision", evidence.sourceRevision)
                    .put("diagnostic_regions", evidence.presentation.layoutDiagnostics.size)
                    .put("overflow_regions", evidence.presentation.layoutDiagnostics.count { it.overflow })
                    .put(
                        "below_minimum_regions",
                        evidence.presentation.layoutDiagnostics.count { it.belowPreferredMinimum },
                    )
                    .put("validation_nanos", SystemClock.elapsedRealtimeNanos() - validateStarted)
                    .put("status", "prepared")
            } catch (error: Throwable) {
                record.put("status", "failed").put("failure_class", error.javaClass.name)
                    .put("failed_iteration_elapsed_nanos", SystemClock.elapsedRealtimeNanos() - started)
                throw error
            } finally {
                withContext(NonCancellable) {
                    val cleanupStarted = SystemClock.elapsedRealtimeNanos()
                    var cleanupFailure: Throwable? = null
                    try {
                        evidence?.let { renderer.cleanup(it) }
                    } catch (error: Throwable) {
                        cleanupFailure = error
                        record.put("status", "failed_cleanup").put("cleanup_failure_class", error.javaClass.name)
                    }
                    val cleanupNanos = SystemClock.elapsedRealtimeNanos() - cleanupStarted
                    totalCleanupNanos += cleanupNanos
                    val evidenceDirectory = File(context.filesDir, "translation/review-evidence/$reviewId")
                    record.put("cleanup_nanos", cleanupNanos).put("evidence_removed", !evidenceDirectory.exists())
                        .put("memory_after_cleanup", observedMemory())
                        .put("completed_elapsed_ms", SystemClock.elapsedRealtime())
                    FileOutputStream(records, true).bufferedWriter().use {
                        it.append(record.toString()).append('\n')
                    }
                    count++
                    if (firstRecords.length() < 4) firstRecords.put(record)
                    cleanupFailure?.let { throw it }
                    assertFalse("Only this iteration's evidence should be removed", evidenceDirectory.exists())
                }
            }
        }

        private fun observedMemory(): JSONObject = memory().also {
            maxObservedJavaBytes = maxOf(maxObservedJavaBytes, it.getLong("java_used_bytes"))
            maxObservedNativeBytes = maxOf(maxObservedNativeBytes, it.getLong("native_allocated_bytes"))
        }

        fun persistAndEmit() {
            report.put("iterations", count).put("successful_preparations", preparedCount)
                .put("first_four_iterations", firstRecords)
                .put("updated_elapsed_ms", SystemClock.elapsedRealtime()).put("total_prepare_nanos", totalPrepareNanos)
                .put("min_prepare_nanos", if (preparedCount > 0) minPrepareNanos else JSONObject.NULL)
                .put("max_prepare_nanos", if (preparedCount > 0) maxPrepareNanos else JSONObject.NULL)
                .put("total_cleanup_nanos", totalCleanupNanos).put("preview_sha256", previewHash ?: JSONObject.NULL)
                .put("max_boundary_java_used_bytes", maxObservedJavaBytes)
                .put("max_boundary_native_allocated_bytes", maxObservedNativeBytes)
            if (records.exists()) report.put("iterations_sha256", hash(records))
            val file = File(output, "report.json")
            val temporary = File(output, "report.json.part")
            FileOutputStream(temporary).use { stream ->
                stream.write(report.toString(2).toByteArray())
                stream.fd.sync()
            }
            check(temporary.renameTo(file)) { "Cannot persist the renderer performance report" }
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply {
                    putString("quality_render_performance_report_json", report.toString())
                    putString("quality_render_performance_report_path", file.path)
                    putString("stream", "\nQUALITY_RENDER_PERFORMANCE_REPORT=${file.path}\n")
                },
            )
        }
    }

    companion object {
        private const val WIDTH = 1400
        private const val HEIGHT = 1497
        private const val SUSTAINED_MS = 180_000L

        private fun memory(): JSONObject {
            val started = SystemClock.elapsedRealtimeNanos()
            val runtime = Runtime.getRuntime()
            val details = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            return JSONObject().put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
                .put("java_used_bytes", runtime.totalMemory() - runtime.freeMemory())
                .put("java_committed_bytes", runtime.totalMemory()).put("java_max_bytes", runtime.maxMemory())
                .put("native_allocated_bytes", Debug.getNativeHeapAllocatedSize())
                .put("native_heap_size_bytes", Debug.getNativeHeapSize()).put("process_pss_kib", details.totalPss)
                .put("snapshot_nanos", SystemClock.elapsedRealtimeNanos() - started)
        }

        private fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(bytes)
                    if (read < 0) break
                    digest.update(bytes, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
