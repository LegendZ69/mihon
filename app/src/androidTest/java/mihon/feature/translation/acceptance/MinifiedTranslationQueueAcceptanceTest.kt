package mihon.feature.translation.acceptance

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zacsweers.metro.createGraphFactory
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.activeNetworkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mihon.app.di.AppGraph
import mihon.core.metro.GraphProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OcrSettings
import tachiyomi.domain.translation.model.OpenAiDialect
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.QualityReviewSettings
import tachiyomi.domain.translation.model.TranslationBatch
import tachiyomi.domain.translation.model.TranslationConcurrency
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationLogSettings
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationSettings
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Requires a freshly started host fixture server and -e translation.acceptance true.
 * Only cached synthetic images enter a private graph/database. No enqueue/resume/retry calls are
 * made: those launch the app-wide WorkManager. These tests own runQueue coroutines directly.
 */
@RunWith(AndroidJUnit4::class)
class MinifiedTranslationQueueAcceptanceTest {
    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    @Test
    fun modesPartialResponsesAndTransientRetriesPersistCompleteResults() = acceptance("queue-modes") { fixture ->
        for (mode in TranslationMode.entries.filter { it != TranslationMode.STRUCTURED_FILES }) {
            val job = fixture.seed(mode, "success", 5)
            fixture.graph.translationManager.runQueue()
            fixture.completed(job, 5)
            val batches = fixture.graph.translationRepository.batches(job.id)
            val requests = fixture.requests(job.id)
            val expected = when (mode) {
                TranslationMode.STRUCTURED_FILES -> error("Structured imports are tested without provider work")
                TranslationMode.VERTEX -> 5
                TranslationMode.CUSTOM -> 3
                TranslationMode.MAX, TranslationMode.HALVING -> 1
            }
            assertEquals("$mode request count", expected, requests.size)
            val ordered = batches.sortedBy { batch -> batch.imageIds.first().toInt() }
            val expectedSizes = when (mode) {
                TranslationMode.STRUCTURED_FILES -> error("Structured imports are tested without provider work")
                TranslationMode.VERTEX -> listOf(1, 1, 1, 1, 1)
                TranslationMode.CUSTOM -> listOf(2, 2, 1)
                TranslationMode.MAX, TranslationMode.HALVING -> listOf(5)
            }
            assertEquals(expectedSizes, ordered.map { it.imageIds.size })
            assertEquals((0..4).map(Int::toString), ordered.flatMap { it.imageIds })
        }

        val halving = fixture.seed(TranslationMode.HALVING, "halving-content", 5)
        fixture.graph.translationManager.runQueue()
        fixture.completed(halving, 5)
        assertEquals(9, fixture.requests(halving.id).size)
        val splitBatches = fixture.graph.translationRepository.batches(halving.id)
        val root = splitBatches.single { it.parentId == null }
        assertEquals(listOf(5, 3, 2, 1, 1, 1, 2, 1, 1), batchSizes(root, splitBatches))
        assertTrue(splitBatches.filter { it.imageIds.size == 1 }.all { it.state == "COMPLETED" })

        val partial = fixture.seed(TranslationMode.HALVING, "partial-once", 5)
        fixture.graph.translationManager.runQueue()
        fixture.completed(partial, 5)
        assertEquals(3, fixture.requests(partial.id).size)
        val partialBatches = fixture.graph.translationRepository.batches(partial.id)
        val partialRoot = partialBatches.single { it.parentId == null }
        assertEquals(listOf(5, 2, 2), batchSizes(partialRoot, partialBatches))
        assertTrue(
            "The completed first page must not be submitted again",
            partialBatches.filter {
                it.parentId != null
            }.none { "0" in it.imageIds },
        )

        val retry = fixture.seed(TranslationMode.VERTEX, "retry-429-503", 1)
        fixture.graph.translationManager.runQueue()
        fixture.completed(retry, 1)
        assertEquals(3, fixture.requests(retry.id).size)
        val statuses = fixture.events(retry.id).filter { it.stage == "generateContent" }
            .mapNotNull { it.details["httpStatus"] }.groupingBy { it }.eachCount()
        assertEquals(mapOf("429" to 1, "503" to 1, "200" to 1), statuses)
        assertEquals(setOf("1", "2", "3"), fixture.requests(retry.id).map { it.details["attempt"] }.toSet())

        val content = fixture.seed(TranslationMode.VERTEX, "body-error-once", 1)
        fixture.graph.translationManager.runQueue()
        fixture.snapshot(content, "content_failure")
        // Content errors belong to the retryable singleton batch; the chapter reports unresolved pages.
        assertEquals(TranslationJobState.PARTIAL, fixture.current(content).state)
        assertEquals(0, fixture.current(content).completedImages)
        assertEquals("1 images need retry", fixture.current(content).message)
        assertTrue(fixture.graph.translationRepository.results(content.id).isEmpty())
        val failedBatch = fixture.graph.translationRepository.batches(content.id).single()
        assertEquals(listOf("0"), failedBatch.imageIds)
        assertEquals("FAILED", failedBatch.state)
        assertEquals(1, fixture.requests(content.id).size)
        fixture.graph.translationRepository.saveJob(fixture.current(content).copy(state = TranslationJobState.QUEUED))
        fixture.graph.translationManager.runQueue()
        fixture.completed(content, 1)
        assertEquals(2, fixture.requests(content.id).size)
    }

    @Test
    fun pauseAndCoroutineRestartPreserveAlreadyCompletedPages() = acceptance("queue-recovery") { fixture ->
        suspend fun runInterrupted(pause: Boolean) = coroutineScope {
            val job = fixture.seed(TranslationMode.VERTEX, "delayed-success", 2)
            val first = fixture.graph.translationRepository.images(job.id).first()
            fixture.graph.translationRepository.saveResult(
                job.id,
                TranslationPageResult(first.id, first.contentHash, first.width, first.height, emptyList()),
            )
            // Revisions are assigned on persistence; compare the committed result after recovery.
            val preserved = fixture.graph.translationRepository.results(job.id).single()
            fixture.graph.translationRepository.saveJob(job.copy(completedImages = 1))
            val worker = launch { fixture.graph.translationManager.runQueue() }
            try {
                // The persisted transport-start event is a barrier, not a wall-clock sleep.
                fixture.graph.translationRepository.observeEvents(job.id).first { events ->
                    events.any(::requestStarted)
                }
                if (pause) {
                    fixture.graph.translationManager.pause(job.id)
                    worker.join()
                    assertEquals(TranslationJobState.PAUSED, fixture.current(job).state)
                } else {
                    worker.cancelAndJoin()
                    assertEquals(TranslationJobState.QUEUED, fixture.current(job).state)
                    assertTrue(fixture.current(job).message.orEmpty().contains("checkpoint"))
                }
            } finally {
                worker.cancelAndJoin()
            }
            assertEquals(listOf(preserved), fixture.graph.translationRepository.results(job.id))
            assertTrue(fixture.graph.translationRepository.batches(job.id).any { it.state == "INTERRUPTED" })
            fixture.snapshot(job, if (pause) "paused" else "owned_coroutine_cancelled")

            val before = fixture.current(job)
            fixture.graph.translationRepository.saveJob(
                before.copy(
                    state = TranslationJobState.QUEUED,
                    settings = before.settings.copy(
                        provider = before.settings.provider.copy(
                            extraHeaders = mapOf("X-Mihon-Fixture-Scenario" to "success"),
                        ),
                    ),
                ),
            )
            if (!pause) fixture.reopenGraph()
            fixture.graph.translationManager.runQueue()
            fixture.completed(job, 2)
            assertEquals(preserved, fixture.graph.translationRepository.results(job.id).single { it.imageId == "0" })
            assertTrue(fixture.graph.translationRepository.batches(job.id).all { "0" !in it.imageIds })
            assertEquals("Only the unfinished page may be retried", 2, fixture.requests(job.id).size)
        }
        runInterrupted(pause = true)
        runInterrupted(pause = false)
    }

    /** Two separate instrumentation invocations; start is expected to die through external am crash. */
    @Test
    fun actualProcessCrashRetainsCommittedPages() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("translation.processRecovery") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.mihon.benchmark", context.packageName)
        assertTrue(BuildConfig.TRANSLATION_LOCAL_FIXTURES_ENABLED)
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertTrue(context.activeNetworkState().isOnline)
        val runId = requireNotNull(arguments.getString("translation.recoveryRunId"))
        require(UUID.fromString(runId).toString() == runId) { "A canonical UUID is required" }
        val stage = requireNotNull(arguments.getString("translation.recoveryStage"))
        require(stage == "start" || stage == "resume") { "Stage must be start or resume" }
        withContext(Dispatchers.IO) {
            val fixture = Fixture(context, "queue-process-recovery", runId, stage == "resume", durable = true)
            try {
                withTimeout(90_000) {
                    fixture.initialize()
                    if (stage == "start") fixture.awaitActualCrash() else fixture.resumeAfterActualCrash()
                }
                fixture.close()
                fixture.finish("passed")
            } catch (error: Throwable) {
                fixture.finish("failed", error.javaClass.name)
                throw error
            } finally {
                // A real process crash cannot execute this cleanup. Resume removes only its dummy credential.
                withContext(NonCancellable) { fixture.close() }
            }
        }
    }

    private fun batchSizes(batch: TranslationBatch, all: List<TranslationBatch>): List<Int> =
        listOf(batch.imageIds.size) + all.filter { it.parentId == batch.id }
            .sortedBy { it.imageIds.first().toInt() }.flatMap { batchSizes(it, all) }

    private fun requestStarted(event: TranslationEvent): Boolean =
        event.stage == "generateContent" && event.message.startsWith("Request started")

    private fun acceptance(label: String, block: suspend (Fixture) -> Unit) = runBlocking {
        assumeTrue(
            "Queue acceptance requires a minified benchmark APK and -e translation.acceptance true",
            InstrumentationRegistry.getArguments().getString("translation.acceptance") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.mihon.benchmark", context.packageName)
        assertTrue(BuildConfig.TRANSLATION_LOCAL_FIXTURES_ENABLED)
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertTrue("The production scheduler requires a validated network", context.activeNetworkState().isOnline)
        withContext(Dispatchers.IO) {
            val fixture = Fixture(context, label)
            try {
                withTimeout(120_000) {
                    fixture.initialize()
                    block(fixture)
                }
                fixture.finish("passed")
            } catch (error: Throwable) {
                fixture.finish("failed", error.javaClass.name)
                throw error
            } finally {
                withContext(NonCancellable) { fixture.close() }
            }
        }
    }

    private inner class Fixture(
        private val base: Context,
        label: String,
        private val id: String = UUID.randomUUID().toString(),
        private val reopening: Boolean = false,
        private val durable: Boolean = false,
    ) {
        private val root = if (durable) {
            File(base.noBackupFilesDir, "translation-process-acceptance/$id")
        } else {
            File(base.cacheDir, "translation-queue-acceptance/$id")
        }.apply {
            if (reopening) check(isDirectory) { "The named run must already exist" } else check(mkdirs())
        }
        private val report =
            File(checkNotNull(base.getExternalFilesDir(null)), "translation-acceptance/$label-$id/report.json")
        private var isolated = IsolatedContext(base, root)
        var graph = isolated.graph
            private set
        private val previous = if (reopening) json.parseToJsonElement(report.readText()).jsonObject else null
        private val records = previous?.get("records")?.jsonArray?.toMutableList() ?: mutableListOf<JsonElement>()
        private val credentialId = "queue-fixture-$id"
        private var server: JsonObject? = previous?.get("fixture_server") as? JsonObject
        private val checkpointFile = File(root, "process-checkpoint.json")
        private var exitEvidence: JsonObject? = null
        private var credentialRemoved = false
        private var nextChapter = 0L

        suspend fun initialize() {
            val previousServer = server
            if (reopening) {
                assertEquals("awaiting_external_crash", previous?.get("status")?.jsonPrimitive?.content)
                assertTrue("A durable checkpoint must exist", checkpointFile.isFile)
            }
            val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url("http://127.0.0.1:8765/health").build()).execute().use { response ->
                assertEquals("A local fixture server must be reachable", 200, response.code)
                server = json.parseToJsonElement(response.body.string()).jsonObject
                assertEquals("true", server!!["fixture"]?.jsonPrimitive?.content)
                assertEquals("false", server!!["cloud_forwarding"]?.jsonPrimitive?.content)
            }
            if (reopening) {
                assertEquals(
                    "Resume must use the same host ledger/session",
                    previousServer?.get("run_id"),
                    server?.get("run_id"),
                )
                assertTrue(
                    "The disposable credential must survive process death",
                    graph.translationCredentialVault.info(credentialId) != null,
                )
                assertEquals(1, graph.translationRepository.jobs().size)
            } else {
                graph.translationCredentialVault.import(
                    credentialId,
                    "mihon-fixture-only",
                    TranslationProviderKind.OPENAI,
                )
                assertTrue("This database must start empty", graph.translationRepository.jobs().isEmpty())
            }
            finish("running")
            println("TRANSLATION_QUEUE_EVIDENCE=${report.absolutePath}")
        }

        suspend fun awaitActualCrash(): Unit = coroutineScope {
            check(durable && !reopening)
            val original = seed(TranslationMode.VERTEX, "recovery-delay", 2)
            val settings = original.settings.copy(
                provider = original.settings.provider.copy(timeoutSeconds = 180, totalAttempts = 1),
                concurrency = TranslationConcurrency(series = 1, chapters = 1, images = 1, requests = 1),
            )
            val job = original.copy(settings = settings)
            graph.translationPreferences.update(settings)
            graph.translationRepository.saveJob(job)
            val worker = launch { graph.translationManager.runQueue() }
            try {
                withTimeout(30_000) {
                    graph.translationRepository.observeEvents(job.id).first { events ->
                        events.count(::requestStarted) >= 2
                    }
                }
                val preserved = graph.translationRepository.results(job.id).single()
                assertTrue(
                    "One real fixture response must commit before the barrier",
                    preserved.imageId in listOf("0", "1"),
                )
                assertEquals(TranslationJobState.TRANSLATING, current(job).state)
                assertEquals(2, requests(job.id).size)
                writeAtomic(
                    checkpointFile,
                    buildJsonObject {
                        put("run_id", id)
                        put("job_id", job.id)
                        put("start_pid", Process.myPid())
                        put("start_elapsed_millis", Process.getStartElapsedRealtime())
                        put("checkpoint_epoch_millis", System.currentTimeMillis())
                        put("preserved_result", json.parseToJsonElement(json.encodeToString(preserved)))
                        put(
                            "original_file_hashes",
                            JsonObject(
                                graph.translationRepository.images(job.id).associate {
                                    it.id to JsonPrimitive(fileHash(File(it.filePath)))
                                },
                            ),
                        )
                    },
                )
                snapshot(job, "committed_first_page_before_actual_crash")
                finish("awaiting_external_crash")
                println("TRANSLATION_PROCESS_READY run=$id pid=${Process.myPid()} report=${report.absolutePath}")
                // Do not cancel/requeue gracefully: the host must crash this process within this bounded window.
                delay(60_000)
                error("No external process crash arrived during the 60-second barrier")
            } finally {
                // Only executes on timeout/test failure; an actual OS process crash bypasses it.
                worker.cancelAndJoin()
            }
        }

        suspend fun resumeAfterActualCrash() {
            check(durable && reopening)
            val checkpoint = json.parseToJsonElement(checkpointFile.readText()).jsonObject
            assertEquals(id, checkpoint["run_id"]?.jsonPrimitive?.content)
            val oldPid = checkpoint.getValue("start_pid").jsonPrimitive.content.toInt()
            val oldStart = checkpoint.getValue("start_elapsed_millis").jsonPrimitive.content.toLong()
            assertTrue(
                "Resume must execute in a different process instance",
                oldPid != Process.myPid() || oldStart != Process.getStartElapsedRealtime(),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val activity = base.getSystemService(ActivityManager::class.java)
                val since = checkpoint.getValue("checkpoint_epoch_millis").jsonPrimitive.content.toLong()
                val exit = withTimeout(10_000) {
                    var observed: ApplicationExitInfo? = null
                    while (observed == null) {
                        observed = activity.getHistoricalProcessExitReasons(base.packageName, oldPid, 10)
                            .firstOrNull { it.pid == oldPid && it.timestamp >= since }
                        if (observed == null) delay(100)
                    }
                    observed
                }
                exitEvidence = buildJsonObject {
                    put("pid", exit.pid)
                    put("reason", exit.reason)
                    put("status", exit.status)
                    put("timestamp", exit.timestamp)
                    put("resume_pid", Process.myPid())
                }
                assertTrue(
                    "A recorded Java/native crash is required; force-stop, low-memory kill and cancellation are different tests",
                    exit.reason == ApplicationExitInfo.REASON_CRASH ||
                        exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE,
                )
            }
            val job = graph.translationRepository.jobs().single {
                it.id ==
                    checkpoint.getValue("job_id").jsonPrimitive.content
            }
            assertEquals("Actual crash must leave the in-flight checkpoint", TranslationJobState.TRANSLATING, job.state)
            assertTrue(graph.translationRepository.batches(job.id).any { it.state == "RUNNING" })
            val before = graph.translationRepository.results(job.id).single()
            assertEquals(checkpoint["preserved_result"], json.parseToJsonElement(json.encodeToString(before)))
            val hashes = checkpoint.getValue("original_file_hashes").jsonObject
            graph.translationRepository.images(job.id).forEach { image ->
                assertEquals(hashes.getValue(image.id).jsonPrimitive.content, image.contentHash)
                assertEquals(image.contentHash, fileHash(File(image.filePath)))
            }
            assertEquals(2, requests(job.id).size)
            snapshot(job, "actual_crash_reopened_before_manager_recovery")
            // Normal runQueue startup recovers active checkpoints. No manual state rewrite or WorkManager dispatch.
            graph.translationManager.runQueue()
            completed(job, 2)
            val after = graph.translationRepository.results(job.id).single { it.imageId == before.imageId }
            assertEquals("Committed revision, geometry and translated regions must not change", before, after)
            assertEquals(3, requests(job.id).size)
            val batches = graph.translationRepository.batches(job.id)
            assertEquals(
                "Completed page must have only its original batch",
                1,
                batches.count {
                    before.imageId in
                        it.imageIds
                },
            )
            assertEquals(
                "Only unresolved page is dispatched again",
                2,
                batches.count {
                    before.imageId !in it.imageIds
                },
            )
            snapshot(job, "actual_crash_recovered_completed")
        }

        suspend fun seed(mode: TranslationMode, scenario: String, count: Int): TranslationJob {
            val settings = TranslationSettings(
                provider = ProviderSettings(
                    kind = TranslationProviderKind.OPENAI,
                    credentialId = credentialId,
                    model = "mihon-fixture",
                    baseUrl = "http://127.0.0.1:8765/v1",
                    dialect = OpenAiDialect.CHAT_COMPLETIONS,
                    extraHeaders = mapOf("X-Mihon-Fixture-Scenario" to scenario),
                    maxOutputTokens = 4096,
                    customInputTokenLimit = 65536,
                    customOutputTokenLimit = 4096,
                    totalAttempts = 3,
                    timeoutSeconds = 30,
                    initialRetryMillis = 100,
                    maxRetryMillis = 1000,
                    imagePreparationEnabled = false,
                ),
                mode = mode,
                customBatchSize = if (mode == TranslationMode.CUSTOM) 2 else 1,
                ocr = OcrSettings(pipeline = OcrPipeline.AI),
                qualityReview = QualityReviewSettings(enabled = false),
                concurrency = TranslationConcurrency(series = 1, chapters = 1, images = 2, requests = 1),
                logs = TranslationLogSettings(captureRaw = false, maxStorageMb = 32),
                wifiOnly = false,
            )
            graph.translationPreferences.update(settings)
            val job =
                TranslationJob(
                    UUID.randomUUID().toString(),
                    1,
                    ++nextChapter,
                    "Controlled fixture",
                    "$mode $scenario",
                    settings,
                )
            graph.translationRepository.saveJob(job)
            graph.translationRepository.saveImages(job.id, List(count) { image(job.id, it) })
            return job
        }

        suspend fun current(job: TranslationJob) = graph.translationRepository.jobs().single { it.id == job.id }
        suspend fun events(jobId: String) = graph.translationRepository.observeEvents(jobId).first()
        suspend fun requests(jobId: String) = events(jobId).filter(::requestStarted)

        suspend fun completed(job: TranslationJob, count: Int) {
            snapshot(job, "completed_assertion")
            assertEquals(TranslationJobState.COMPLETED, current(job).state)
            assertEquals(count, current(job).completedImages)
            val images = graph.translationRepository.images(job.id).associateBy { it.id }
            val pages = graph.translationRepository.results(job.id)
            assertEquals((0 until count).map(Int::toString).toSet(), pages.map { it.imageId }.toSet())
            pages.forEach { page ->
                assertEquals(images.getValue(page.imageId).contentHash, page.imageHash)
                assertEquals(960, page.width)
                assertEquals(320, page.height)
            }
        }

        suspend fun snapshot(job: TranslationJob, stage: String) {
            records += buildJsonObject {
                put("stage", stage)
                put("job", json.parseToJsonElement(json.encodeToString(current(job))))
                put("images", json.parseToJsonElement(json.encodeToString(graph.translationRepository.images(job.id))))
                put(
                    "results",
                    json.parseToJsonElement(json.encodeToString(graph.translationRepository.results(job.id))),
                )
                put(
                    "batches",
                    json.parseToJsonElement(json.encodeToString(graph.translationRepository.batches(job.id))),
                )
                put("events", json.parseToJsonElement(json.encodeToString(events(job.id))))
            }
            finish("running")
        }

        fun reopenGraph() {
            isolated = IsolatedContext(base, root)
            graph = isolated.graph
        }

        fun finish(status: String, failure: String? = null) {
            check(report.parentFile!!.isDirectory || report.parentFile!!.mkdirs())
            writeAtomic(
                report,
                buildJsonObject {
                    put("schema", 2)
                    put("run_id", id)
                    put("process_recovery", durable)
                    put("process_exit", exitEvidence ?: JsonNull)
                    if (durable) put("credential_removed", credentialRemoved)
                    put("package", base.packageName)
                    put("status", status)
                    put("failure_class", failure?.let(::JsonPrimitive) ?: JsonNull)
                    put("fixture_server", server ?: JsonNull)
                    put("records", JsonArray(records))
                    put("network_target", "http://127.0.0.1:8765/v1; fixed synthetic token and model only")
                    put(
                        "isolation",
                        "Separate graph, preferences, database, cache, and credential files; cached images bypass source acquisition",
                    )
                    put(
                        "recovery_limit",
                        if (durable) {
                            "Explicit start/resume invocations, persisted crash reason and external am crash receipt; manager called directly, not WorkManager or force-stop"
                        } else {
                            "Coroutine cancellation and a new graph over the same persisted database; not Android process death or WorkManager"
                        },
                    )
                    put(
                        "request_count_basis",
                        "Persisted provider transport-start events; local server ledger provides independent wire evidence",
                    )
                    put("meaning_review", "not_assessed; translations are synthetic markers")
                },
            )
        }

        private fun writeAtomic(destination: File, value: JsonElement) {
            val file = AtomicFile(destination)
            val stream = file.startWrite()
            try {
                stream.write(json.encodeToString(value).toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
        }

        private fun fileHash(file: File): String =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

        suspend fun close() {
            // Retain only this disposable graph's files for examination; never clear global app state.
            graph.translationCredentialVault.remove(credentialId)
            check(graph.translationCredentialVault.info(credentialId) == null)
            credentialRemoved = true
            graph.translationPreferences.reset()
        }

        private fun image(jobId: String, index: Int): TranslationImage {
            val file = File(root, "$jobId-$index.png")
            val bitmap = Bitmap.createBitmap(960, 320, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                canvas.drawText(
                    "FIXTURE PAGE $index",
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
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
                "%02x".format(it)
            }
            return TranslationImage(
                index.toString(),
                index,
                file.absolutePath,
                "image/png",
                960,
                320,
                hash,
                file.length(),
            )
        }
    }

    private class IsolatedContext(base: Context, private val root: File) :
        ContextWrapper(
            base,
        ),
        GraphProvider<AppGraph> {
        override val graph: AppGraph by lazy {
            createGraphFactory<AppGraph.Factory>().create(context = this, isDebugBuild = false)
        }

        override fun getApplicationContext(): Context = this
        override fun getDataDir(): File = root
        override fun getCacheDir(): File = directory("cache")
        override fun getCodeCacheDir(): File = directory("code-cache")
        override fun getFilesDir(): File = directory("files")
        override fun getNoBackupFilesDir(): File = directory("no-backup")
        override fun getExternalFilesDir(type: String?): File = directory("external-${type ?: "default"}")
        override fun getDir(name: String, mode: Int): File = directory("app-$name")
        override fun getDatabasePath(name: String): File = File(directory("databases"), File(name).name)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("queue-acceptance-${root.name}-$name", mode)

        private fun directory(name: String) = ensureReviewAcceptanceDirectory(File(root, name))
    }
}
