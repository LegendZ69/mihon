package mihon.feature.translation.acceptance

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.BatteryManager
import android.os.Debug
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.app.di.appGraph
import mihon.feature.translation.ocr.PaddleModelManager
import mihon.feature.translation.ocr.PaddleModelStatus
import mihon.feature.translation.ocr.PaddleOcrEngine
import mihon.feature.translation.ocr.PaddleOperationContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OcrSettings
import tachiyomi.domain.translation.model.OpenAiDialect
import tachiyomi.domain.translation.model.PaddleProfile
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.QualityReviewSettings
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationLogSettings
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Explicitly opt in with -e translation.acceptance true against the minified benchmark APK.
 * Requires the local fixture server on adb-reversed port 8765; never accepts a provider URL or key.
 * Model downloads use the production manager's pinned official artifacts. Debug discovery reports a skip.
 */
@RunWith(AndroidJUnit4::class)
class MinifiedTranslationAcceptanceTest {
    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    @Test
    fun localOcrOperationsReportMeasuredNativeStagesAndPreserveRawResults() = acceptance("ocr-operations") { run ->
        val operations = mutableListOf<TranslationOperation>()
        val events = mutableListOf<TranslationEvent>()
        val repository = object : TranslationRepository by run.context.appGraph.translationRepository {
            override suspend fun saveOperation(operation: TranslationOperation) {
                operations += operation
            }
            override suspend fun addEvent(event: TranslationEvent) {
                events += event
            }
        }
        val offlineClient = OkHttpClient.Builder().addInterceptor {
            error("This stage acceptance requires the existing official Tiny model pack; downloads are disabled")
        }.build()
        val manager = PaddleModelManager(run.context, offlineClient)
        val engine = PaddleOcrEngine(run.context, manager, repository)
        val image = run.image("measured-native-stages", "HELLO MANGA 123")
        val parentId = "acceptance-local-ocr"
        val context = PaddleOperationContext(
            "acceptance-ocr-operations",
            parentId,
            image.id,
            TranslationLogSettings(enabled = true, captureRaw = false),
        )
        try {
            val result = engine.recognize(
                image,
                OcrSettings(pipeline = OcrPipeline.PADDLE, profile = PaddleProfile.TINY, language = "en"),
                decodedMemoryMb = 128,
                operationContext = context,
            )
            val completed = operations.filter { it.state == TranslationOperationState.COMPLETED }
            val detection = completed.single { it.stage == TranslationStage.DETECTION }
            val recognition = completed.single { it.stage == TranslationStage.RECOGNITION }
            run.record(
                buildJsonObject {
                    put("stage", "measured_local_ocr_operations")
                    put("image", json.parseToJsonElement(json.encodeToString(image)))
                    put("ocr", json.parseToJsonElement(json.encodeToString(result)))
                    put("operations", json.parseToJsonElement(json.encodeToString(operations)))
                    put("events", json.parseToJsonElement(json.encodeToString(events)))
                    put("provider_requests", 0)
                    put("model_downloads", "Disabled; existing verified official Tiny pack required")
                    put("persistence_scope", "Repository observer only; no saved translation or operation rows changed")
                },
            )
            validateOcr(result, image, "HELLO MANGA 123")
            assertTrue(completed.any { it.stage == TranslationStage.PREPROCESS })
            assertTrue(
                operations.all {
                    it.parentId == parentId && it.imageId == image.id && it.jobId == context.jobId
                },
            )
            assertTrue(detection.completed > 0)
            assertEquals(detection.completed, detection.total)
            assertEquals(detection.completed, recognition.completed)
            assertEquals(recognition.completed, recognition.total)
            assertEquals(
                result.timingsMillis["detection"]?.toString(),
                events.last {
                    it.operationId == detection.id && "measured.detection.millis" in it.details
                }.details["measured.detection.millis"],
            )
            assertEquals(
                result.timingsMillis["recognition"]?.toString(),
                events.last {
                    it.operationId == recognition.id && "measured.recognition.millis" in it.details
                }.details["measured.recognition.millis"],
            )
            assertTrue(
                events.filter { it.operationId != null }.all { event ->
                    operations.any {
                        it.id ==
                            event.operationId
                    }
                },
            )
        } finally {
            engine.release()
        }
    }

    @Test
    fun officialModelProfilesRunColdAndWarmThroughTheMinifiedEngine() = acceptance("ocr-models") { run ->
        val graph = run.context.appGraph
        val engine = PaddleOcrEngine(run.context, graph.paddleModelManager)
        val warmRuns = InstrumentationRegistry.getArguments().getString("translation.warmRuns")?.toInt() ?: 3
        require(warmRuns in 0..3) { "translation.warmRuns must be 0–3" }
        val variants = listOf(
            Triple(PaddleProfile.TINY, "en", "HELLO MANGA 123"),
            Triple(PaddleProfile.SMALL, "en", "HELLO MANGA 123"),
            Triple(PaddleProfile.MEDIUM, "en", "HELLO MANGA 123"),
            Triple(PaddleProfile.SMALL, "ko", "안녕하세요"),
        )
        try {
            for ((profile, language, expected) in variants) {
                engine.release()
                val image = run.image("${profile.name.lowercase()}-$language", expected)
                val settings = OcrSettings(pipeline = OcrPipeline.PADDLE, profile = profile, language = language)
                val downloadStart = SystemClock.elapsedRealtime()
                graph.paddleModelManager.download(profile, language)
                graph.paddleModelManager.refresh()
                val model = graph.paddleModelManager.states.value.single {
                    it.profile == profile && it.korean == (language == "ko")
                }
                assertEquals(PaddleModelStatus.INSTALLED, model.status)
                run.record(
                    buildJsonObject {
                        put("stage", "model_ready")
                        put("profile", profile.name)
                        put("language", language)
                        put("detector", model.detectorModel)
                        put("recognizer", model.recognizerModel)
                        put("verified_model_bytes", model.totalBytes)
                        put("download_or_revalidation_millis", SystemClock.elapsedRealtime() - downloadStart)
                        put("excluded_from_inference_timing", true)
                    },
                )
                repeat(warmRuns + 1) { index ->
                    val before = memory()
                    val start = SystemClock.elapsedRealtime()
                    val result = engine.recognize(image, settings, decodedMemoryMb = 128)
                    run.record(
                        buildJsonObject {
                            put("stage", "ocr")
                            put("profile", profile.name)
                            put("language", language)
                            put("run", if (index == 0) "cold_engine" else "warm_$index")
                            put("elapsed_millis", SystemClock.elapsedRealtime() - start)
                            put("before_memory", before)
                            put("after_memory", memory())
                            put("image", json.parseToJsonElement(json.encodeToString(image)))
                            put("expected_transcription", expected)
                            put("ocr", json.parseToJsonElement(json.encodeToString(result)))
                            put(
                                "cold_definition",
                                "New native detector/recognizer sessions; OS page cache is not flushed",
                            )
                        },
                    )
                    assertEquals(model.detectorModel, result.detectorModel)
                    assertEquals(model.recognizerModel, result.recognizerModel)
                    if (language == "ko") assertEquals("korean_PP-OCRv5_mobile_rec_onnx", result.recognizerModel)
                    validateOcr(result, image, expected)
                    if (index > 0) assertEquals(0L, result.timingsMillis["cold_load"])
                }
            }
        } finally {
            engine.release()
        }
    }

    @Test
    fun allThreePipelinesUseTheRealVaultPreferencesAndLocalProvider() = acceptance("pipelines") { run ->
        val graph = run.context.appGraph
        val engine = PaddleOcrEngine(run.context, graph.paddleModelManager)
        val previous = graph.translationPreferences.settings.value
        val credentialId = "acceptance-${UUID.randomUUID()}"
        val image = run.image("pipeline-shared", "HELLO MANGA 123")
        val jobIds = mutableListOf<String>()
        try {
            graph.translationCredentialVault.import(
                credentialId,
                "mihon-fixture-only",
                TranslationProviderKind.OPENAI,
                "Disposable local acceptance fixture",
            )
            assertNotNull(graph.translationCredentialVault.info(credentialId))
            for (pipeline in OcrPipeline.entries) {
                val settings = TranslationSettings(
                    provider = ProviderSettings(
                        kind = TranslationProviderKind.OPENAI,
                        credentialId = credentialId,
                        model = "mihon-fixture",
                        baseUrl = "http://127.0.0.1:8765/v1",
                        dialect = OpenAiDialect.CHAT_COMPLETIONS,
                        extraHeaders = mapOf("X-Mihon-Fixture-Scenario" to "success"),
                        maxOutputTokens = 4096,
                        customInputTokenLimit = 65536,
                        customOutputTokenLimit = 4096,
                        timeoutSeconds = 30,
                        totalAttempts = 1,
                        imagePreparationEnabled = false,
                    ),
                    mode = TranslationMode.VERTEX,
                    ocr = OcrSettings(pipeline = pipeline, profile = PaddleProfile.SMALL, language = "en"),
                    qualityReview = QualityReviewSettings(enabled = false),
                    sourceLanguage = "en",
                    logs = TranslationLogSettings(enabled = true, captureRaw = false),
                    wifiOnly = false,
                )
                graph.translationPreferences.update(settings)
                assertEquals(settings, graph.translationPreferences.settings.value)
                val local = if (pipeline == OcrPipeline.AI) {
                    emptyList()
                } else {
                    listOf(engine.recognize(image, settings.ocr, decodedMemoryMb = 128)).also {
                        validateOcr(it.single(), image, "HELLO MANGA 123")
                    }
                }
                val jobId = "acceptance-${UUID.randomUUID()}"
                jobIds += jobId
                val started = SystemClock.elapsedRealtime()
                val result = graph.translationProvider.translate(
                    TranslationRequest(jobId, UUID.randomUUID().toString(), settings, listOf(image), local),
                )
                run.record(
                    buildJsonObject {
                        put("stage", "provider_pipeline")
                        put("pipeline", pipeline.name)
                        put("source_image_hash", image.contentHash)
                        put("elapsed_millis", SystemClock.elapsedRealtime() - started)
                        put("local_ocr", json.parseToJsonElement(json.encodeToString(local)))
                        put("pages", json.parseToJsonElement(json.encodeToString(result.pages)))
                        put("usage", json.parseToJsonElement(json.encodeToString(result.usage)))
                        put("request_id", result.requestId?.let(::JsonPrimitive) ?: JsonNull)
                        put("synthetic_translation_only", true)
                        put("meaning_review", "not_assessed_by_this_test")
                    },
                )
                assertEquals(1, result.pages.size)
                val page = result.pages.single()
                assertEquals(image.id, page.imageId)
                assertEquals(image.contentHash, page.imageHash)
                assertEquals(image.width, page.width)
                assertEquals(image.height, page.height)
                assertTrue(page.regions.isNotEmpty())
                assertTrue(page.regions.all { it.translatedText.startsWith("[fixture] ") })
                if (pipeline == OcrPipeline.PADDLE) {
                    val originals = local.single().regions.associateBy { it.id }
                    assertEquals(originals.keys, page.regions.map { it.id }.toSet())
                    page.regions.forEach { region ->
                        val original = originals.getValue(region.id)
                        assertEquals(original.points, region.points)
                        assertEquals(original.sourceText, region.sourceText)
                        assertEquals(original.detectionConfidence, region.detectionConfidence)
                        assertEquals(original.recognitionConfidence, region.recognitionConfidence)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    engine.release()
                } finally {
                    graph.translationPreferences.update(previous)
                    try {
                        graph.translationCredentialVault.remove(credentialId)
                    } finally {
                        jobIds.forEach { graph.translationProvider.clearCheckpoints(it) }
                    }
                }
            }
        }
    }

    private fun validateOcr(result: OcrPageResult, image: TranslationImage, expected: String) {
        assertEquals(image.id, result.imageId)
        val text = result.regions.joinToString("") { it.sourceText }.filterNot(Char::isWhitespace)
        assertTrue(
            "Expected controlled transcription: $expected; received: $text",
            text.contains(expected.filterNot(Char::isWhitespace)),
        )
        assertNotNull(result.rawJson)
        assertTrue(result.timingsMillis.containsKey("total"))
        assertTrue(result.timingsMillis.containsKey("cold_load"))
        assertTrue(result.timingsMillis.values.all { it >= 0L })
        assertTrue(
            result.regions.all { region ->
                region.points.size == 4 && region.points.all { point ->
                    point.x.isFinite() && point.y.isFinite() && point.x in 0f..image.width.toFloat() &&
                        point.y in 0f..image.height.toFloat()
                }
            },
        )
        assertTrue(
            result.regions.all {
                it.detectionConfidence?.let { score -> score.isFinite() && score in 0f..1f } ==
                    true
            },
        )
        assertTrue(
            result.regions.all {
                it.recognitionConfidence?.let { score ->
                    score.isFinite() && score > 0f &&
                        score <= 1f
                } ==
                    true
            },
        )
        assertTrue(result.regions.all { it.aiConfidence == null })
    }

    private fun acceptance(label: String, block: suspend (Run) -> Unit) = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Requires -Ptranslation.testBuildType=benchmark and -e translation.acceptance true; " +
                "debug runs do not validate minified acceptance",
            arguments.getString("translation.acceptance") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            "This test must never touch the personal Mihon package",
            "app.mihon.benchmark",
            context.packageName,
        )
        assertTrue("Benchmark fixture access must be enabled", BuildConfig.TRANSLATION_LOCAL_FIXTURES_ENABLED)
        assertFalse(
            "A debug APK does not validate R8/native release behavior",
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
        withContext(Dispatchers.IO) {
            val inactive = setOf(
                TranslationJobState.COMPLETED,
                TranslationJobState.PARTIAL,
                TranslationJobState.FAILED,
                TranslationJobState.CANCELLED,
                TranslationJobState.PAUSED,
            )
            assertTrue(
                "Pause benchmark translation jobs before collecting isolated OCR measurements",
                context.appGraph.translationRepository.jobs().all { it.state in inactive },
            )
            val run = Run(context, label)
            try {
                withTimeout(30 * 60_000L) { block(run) }
                run.finish("passed")
            } catch (error: Throwable) {
                run.finish("failed", error.javaClass.name)
                throw error
            }
        }
    }

    private fun memory(): JsonObject = buildJsonObject {
        val runtime = Runtime.getRuntime()
        put("java_used_bytes", runtime.totalMemory() - runtime.freeMemory())
        put("native_allocated_bytes", Debug.getNativeHeapAllocatedSize())
        put("process_pss_kib", Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss)
        put("gpu_allocated_bytes", JsonNull)
        put("gpu_note", "Not exposed by these process counters; requires a separate supported device trace")
    }

    private inner class Run(val context: Context, label: String) {
        private val directory = File(
            checkNotNull(context.getExternalFilesDir(null)),
            "translation-acceptance/$label-${UUID.randomUUID()}",
        ).apply { check(mkdirs()) }
        private val records = mutableListOf<JsonElement>()
        private val started = System.currentTimeMillis()

        init {
            finish("running")
            println("TRANSLATION_ACCEPTANCE_EVIDENCE=${File(directory, "report.json").absolutePath}")
        }

        fun record(record: JsonObject) {
            records += record
            finish("running")
        }

        fun finish(status: String, failureClass: String? = null) {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val report = buildJsonObject {
                put("schema", 1)
                put("package", context.packageName)
                put("build_type", BuildConfig.BUILD_TYPE)
                put("kernel_page_size", Os.sysconf(OsConstants._SC_PAGESIZE))
                put("started_unix_millis", started)
                put("updated_unix_millis", System.currentTimeMillis())
                put("status", status)
                put("failure_class", failureClass?.let(::JsonPrimitive) ?: JsonNull)
                put("plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1)
                put("records", JsonArray(records))
                put(
                    "coverage_limit",
                    "Controlled OCR and direct provider integration; reader, queue, process death, " +
                        "thermal endurance and passage meaning are separate checks",
                )
            }
            File(directory, "report.json").writeText(json.encodeToString(report))
        }

        fun image(id: String, text: String): TranslationImage {
            val file = File(directory, "$id.png")
            val bitmap = Bitmap.createBitmap(960, 320, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                canvas.drawText(
                    text,
                    40f,
                    170f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 72f
                    },
                )
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            return TranslationImage(id, 0, file.absolutePath, "image/png", 960, 320, hash, file.length())
        }
    }
}
