package mihon.feature.translation.acceptance

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.activeNetworkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.app.di.appGraph
import mihon.feature.translation.TranslationWorker
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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
import tachiyomi.domain.translation.model.TranslationConcurrency
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

/** Explicit durable phases. Collection does not control the queue; cleanup is always separate. */
@RunWith(AndroidJUnit4::class)
class MinifiedTranslationWorkerRecoveryAcceptanceTest {
    @Test
    fun durableActualWorkerRecovery() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("translation.workerRecovery") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.mihon.benchmark", context.packageName)
        assertTrue(BuildConfig.TRANSLATION_LOCAL_FIXTURES_ENABLED)
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val runId = requireNotNull(arguments.getString("translation.workerRunId"))
        require(UUID.fromString(runId).toString() == runId)
        val mode = requireNotNull(arguments.getString("translation.workerMode"))
        require(mode in setOf("screen-off-thaw", "force-stop"))
        val phase = requireNotNull(arguments.getString("translation.workerPhase"))
        require(phase in setOf("start", "collect", "cleanup"))
        withContext(Dispatchers.IO) {
            val run = RecoveryRun(context, runId, mode, phase == "start")
            when (phase) {
                "start" -> run.start()
                "collect" -> run.collect()
                "cleanup" -> run.cleanup()
            }
        }
    }

    private class RecoveryRun(
        private val context: Context,
        private val runId: String,
        private val mode: String,
        creating: Boolean,
    ) {
        private val graph = context.appGraph
        private val repository = graph.translationRepository
        private val work = WorkManager.getInstance(context)
        private val root = File(context.noBackupFilesDir, "translation-worker-recovery/$runId")
        private val checkpointFile = File(root, "checkpoint.json")
        private val reportFile = File(root, "report.json")
        private val ownedId = "worker-recovery-$runId"
        private val report: JSONObject
        private val checkpoint: JSONObject

        init {
            if (creating) {
                check(root.mkdirs()) { "Start requires a fresh run UUID" }
                checkpoint = JSONObject().put("schema", 1).put("run_id", runId).put("mode", mode)
                    .put("package", context.packageName).put("job_id", ownedId).put("credential_id", ownedId)
                    .put("created_epoch_ms", System.currentTimeMillis())
                    .put("start_pid", Process.myPid()).put("start_elapsed_ms", Process.getStartElapsedRealtime())
                    .put("boot_count", bootCount())
                report = JSONObject().put("schema", 1).put("run_id", runId).put("mode", mode)
                    .put("package", context.packageName).put("records", JSONArray())
                    .put("cloud_forwarding", false).put("cleanup_required", true)
                    .put("acceptance", "Requires independently retained host actions and fixture ledger")
            } else {
                require(checkpointFile.isFile && reportFile.isFile) { "Named durable run does not exist" }
                checkpoint = JSONObject(checkpointFile.readText())
                report = JSONObject(reportFile.readText())
                require(checkpoint.getString("run_id") == runId && checkpoint.getString("mode") == mode)
                require(checkpoint.getString("package") == context.packageName)
                require(checkpoint.getString("job_id") == ownedId && checkpoint.getString("credential_id") == ownedId)
            }
        }

        suspend fun start() {
            try {
                assertTrue("Benchmark queue must be empty; no data is reset", repository.jobs().isEmpty())
                assertTrue("Finish existing translator work first", workInfos().all { it.state.isFinished })
                assertTrue(context.activeNetworkState().isOnline)
                assertTrue(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
                assertFalse(graph.translationPreferences.settings.value.autoTranslate)
                assertEquals(0, graph.translationPreferences.settings.value.chaptersAhead)
                checkpoint.put("settings_sha256", settingsHash())
                    .put("prior_work_ids", JSONArray(workInfos().map { it.id.toString() }))
                    .put(
                        "prior_background_event_ids",
                        JSONArray(
                            repository.observeEvents("queue").first().map {
                                it.id
                            },
                        ),
                    )
                val health = fixtureHealth()
                checkpoint.put("fixture_server", health)
                val images = List(2) { makeImage(it) }
                checkpoint.put("images", JSONArray(Json.encodeToString(images)))
                saveCheckpoint()
                emit("prepared")
                graph.translationCredentialVault.import(ownedId, "mihon-fixture-only", TranslationProviderKind.OPENAI)
                val settings = TranslationSettings(
                    provider = ProviderSettings(
                        kind = TranslationProviderKind.OPENAI,
                        credentialId = ownedId,
                        model = "mihon-fixture",
                        baseUrl = "http://127.0.0.1:8765/v1",
                        dialect = OpenAiDialect.CHAT_COMPLETIONS,
                        extraHeaders = mapOf("X-Mihon-Fixture-Scenario" to "worker-$mode"),
                        maxOutputTokens = 4096,
                        customInputTokenLimit = 65536,
                        customOutputTokenLimit = 4096,
                        timeoutSeconds = 180,
                        totalAttempts = 4,
                        initialRetryMillis = 1000,
                        maxRetryMillis = 4000,
                        imagePreparationEnabled = false,
                    ),
                    mode = TranslationMode.VERTEX,
                    ocr = OcrSettings(pipeline = OcrPipeline.AI),
                    qualityReview = QualityReviewSettings(enabled = false),
                    concurrency = TranslationConcurrency(series = 1, chapters = 1, images = 1, requests = 1),
                    logs = TranslationLogSettings(enabled = true, captureRaw = false),
                    wifiOnly = false,
                )
                val job = TranslationJob(
                    ownedId,
                    Long.MAX_VALUE,
                    Long.MAX_VALUE,
                    "Disposable worker recovery",
                    mode,
                    settings,
                    state = TranslationJobState.PAUSED,
                    imageCount = 2,
                )
                repository.saveJob(job)
                repository.saveImages(ownedId, images)
                repository.saveJob(job.copy(state = TranslationJobState.QUEUED))
                TranslationWorker.start(context)
                await(60_000) { requests() == 2 && repository.results(ownedId).size == 1 }
                assertTrue(ownedWork().any { it.state == WorkInfo.State.RUNNING })
                await(10_000) {
                    context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 7101 }
                }
                checkpoint.put(
                    "preserved_result",
                    JSONObject(Json.encodeToString(repository.results(ownedId).single())),
                )
                    .put("barrier_epoch_ms", System.currentTimeMillis())
                    .put("barrier_elapsed_ms", SystemClock.elapsedRealtime())
                    .put("barrier_work_ids", JSONArray(ownedWork().map { it.id.toString() }))
                saveCheckpoint()
                if (mode == "force-stop") {
                    snapshot("awaiting_settings_force_stop")
                    // Settings Force stop must kill this process. Normal test completion is never a passing start.
                    await(60_000) { false }
                } else {
                    snapshot("awaiting_screen_off")
                    val power = context.getSystemService(PowerManager::class.java)
                    await(30_000) { !power.isInteractive }
                    snapshot("observed_screen_off")
                    // The host wakes after 60 seconds. Do not retain the old completion-while-off assertion here.
                    await(120_000) { power.isInteractive }
                    assertEquals(checkpoint.getInt("start_pid"), Process.myPid())
                    assertEquals(checkpoint.getLong("start_elapsed_ms"), Process.getStartElapsedRealtime())
                    snapshot("observed_screen_on")
                    val recoveryStarted = SystemClock.elapsedRealtime()
                    await(180_000) { current().state == TranslationJobState.COMPLETED }
                    await(30_000) { ownedWork().all { it.state.isFinished } }
                    preservedResult()
                    assertEquals(2, current().completedImages)
                    assertTrue("Only an interrupted second page may need one retry", requests() in 2..3)
                    report.put("automatic_after_screen_on", "completed")
                        .put("recovery_elapsed_ms", SystemClock.elapsedRealtime() - recoveryStarted)
                        .put("recovery_trigger", "screen_on_only_no_launch_resume_or_queue_write")
                    snapshot("automatic_completion_assertions")
                    emit("start_observation_passed_cleanup_required")
                }
            } catch (error: Throwable) {
                report.put("start_failure_class", error.javaClass.name).put("start_failure_message", error.message)
                runCatching { snapshot("start_observation_failed") }.exceptionOrNull()?.let {
                    if (it !== error) error.addSuppressed(it)
                }
                runCatching { emit("start_observation_failed_cleanup_required") }
                throw error
            }
            // Do not clean up here: force-stop cannot execute finally; both cases use the same explicit cleanup phase.
        }

        suspend fun collect() {
            val observation = requireNotNull(
                InstrumentationRegistry.getArguments().getString("translation.workerObservation"),
            )
            require(observation in setOf("post-thaw", "post-launch", "post-manual-resume", "before-cleanup"))
            report.put("collector_controls_queue", false)
                .put("collector_initializes_target_process", true)
                .put(
                    "collection_limit",
                    "Collection cannot prove the recovery trigger; bind prior UI/system/ledger receipts",
                )
                .put("collection_observation_host_label", observation)
            try {
                collectSnapshot(observation)
            } catch (error: Throwable) {
                report.put("collection_failure_class", error.javaClass.name)
                    .put("collection_failure_message", error.message)
                runCatching { snapshot("collection_failed_$observation") }.exceptionOrNull()?.let {
                    if (it !== error) error.addSuppressed(it)
                }
                runCatching { emit("collection_failed_$observation") }.exceptionOrNull()?.let {
                    if (it !== error) error.addSuppressed(it)
                }
                throw error
            }
        }

        private suspend fun collectSnapshot(observation: String) {
            require(checkpoint.has("preserved_result")) { "Run never reached its committed-page barrier" }
            require(checkpoint.getInt("boot_count") == bootCount()) { "Reboot is a separate recovery case" }
            assertOnlyOwnedJob()
            preservedResult()
            assertEquals(checkpoint.getString("settings_sha256"), settingsHash())
            val exits = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, checkpoint.getInt("start_pid"), 10)
                .filter { it.timestamp >= checkpoint.getLong("barrier_epoch_ms") }
            report.put(
                "exit_history",
                JSONArray(
                    exits.map {
                        JSONObject().put("pid", it.pid).put("reason", it.reason).put("status", it.status)
                            .put("timestamp_ms", it.timestamp)
                    },
                ),
            )
            if (Build.VERSION.SDK_INT >= 35) {
                runCatching {
                    context.getSystemService(ActivityManager::class.java).getHistoricalProcessStartReasons(10).map {
                        JSONObject().put("pid", it.pid).put("was_force_stopped", it.wasForceStopped())
                            .put(
                                "startup_timestamps",
                                JSONObject(
                                    it.startupTimestamps.mapKeys { entry ->
                                        entry.key.toString()
                                    },
                                ),
                            )
                    }
                }.onSuccess { report.put("start_history", JSONArray(it)) }
                    .onFailure { report.put("start_history_unavailable", it.javaClass.name) }
            }
            snapshot("collected_read_only_$observation")
            // Deliberately no health request, import, saveJob, start, resume, runQueue, cancel or remove.
        }

        suspend fun cleanup() = withContext(NonCancellable) {
            assertOnlyOwnedJob()
            var failure: Throwable? = null
            suspend fun step(block: suspend () -> Unit) {
                try {
                    block()
                } catch (error: Throwable) {
                    val previous = failure
                    if (previous == null) {
                        failure = error
                    } else if (previous !== error) {
                        previous.addSuppressed(error)
                    }
                }
            }
            step { snapshot("before_explicit_cleanup") }
            step { graph.translationManager.pause(ownedId) }
            step {
                ownedWork().filter { !it.state.isFinished }.forEach {
                    step { work.cancelWorkById(it.id).result.get(10, TimeUnit.SECONDS) }
                }
            }
            step { graph.translationManager.remove(ownedId) }
            step { graph.translationProvider.clearCheckpoints(ownedId) }
            step { graph.translationCredentialVault.remove(ownedId) }
            step {
                val absent = repository.jobs().none { it.id == ownedId }
                report.put("owned_job_removed", absent)
                assertTrue(absent)
            }
            step {
                val absent = graph.translationCredentialVault.info(ownedId) == null
                report.put("credential_removed", absent)
                assertTrue(absent)
            }
            step {
                val unchanged = checkpoint.getString("settings_sha256") == settingsHash()
                report.put("settings_unchanged", unchanged)
                assertTrue(unchanged)
            }
            report.put("cleanup_required", failure != null)
            failure?.let {
                report.put("cleanup_failure_class", it.javaClass.name)
                    .put(
                        "suppressed_cleanup_failure_classes",
                        JSONArray(
                            it.suppressed.map { error ->
                                error.javaClass.name
                            },
                        ),
                    )
            }
            step { emit(if (failure == null) "cleanup_passed" else "cleanup_failed") }
            failure?.let { throw it }
        }

        private suspend fun assertOnlyOwnedJob() {
            assertTrue("Refusing to affect unrelated benchmark work", repository.jobs().all { it.id == ownedId })
        }

        private suspend fun preservedResult() {
            val expected = Json.decodeFromString<TranslationPageResult>(
                checkpoint.getJSONObject("preserved_result").toString(),
            )
            assertEquals(expected, repository.results(ownedId).single { it.imageId == expected.imageId })
            val images = Json.decodeFromString<List<TranslationImage>>(checkpoint.getJSONArray("images").toString())
            images.forEach {
                require(File(it.filePath).canonicalFile.parentFile == root.canonicalFile)
                assertEquals(it.contentHash, hash(File(it.filePath).readBytes()))
            }
            assertEquals(images, repository.images(ownedId))
        }

        private fun workInfos() = work.getWorkInfosForUniqueWork("translator_queue").get(10, TimeUnit.SECONDS)

        private fun ownedWork(): List<WorkInfo> {
            val previous = checkpoint.optJSONArray("prior_work_ids") ?: return emptyList()
            val ids = (0 until previous.length()).map { previous.getString(it) }.toSet()
            return workInfos().filter { it.id.toString() !in ids }
        }

        private suspend fun current() = repository.jobs().single { it.id == ownedId }

        private suspend fun requests() = repository.observeEvents(ownedId).first().count {
            it.stage == "generateContent" && it.message.startsWith("Request started")
        }

        private fun settingsHash() = hash(
            Json.encodeToString(graph.translationPreferences.settings.value).toByteArray(),
        )

        private fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

        private suspend fun snapshot(stage: String) {
            val previousEvents = checkpoint.optJSONArray("prior_background_event_ids") ?: JSONArray()
            val previousEventIds = (0 until previousEvents.length()).map { previousEvents.getString(it) }.toSet()
            report.getJSONArray("records").put(
                JSONObject().put("stage", stage).put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
                    .put("epoch_ms", System.currentTimeMillis()).put("pid", Process.myPid())
                    .put("process_start_elapsed_ms", Process.getStartElapsedRealtime())
                    .put("interactive", context.getSystemService(PowerManager::class.java).isInteractive)
                    .put("online", context.activeNetworkState().isOnline)
                    .put("jobs", JSONArray(Json.encodeToString(repository.jobs().filter { it.id == ownedId })))
                    .put("results", JSONArray(Json.encodeToString(repository.results(ownedId))))
                    .put("batches", JSONArray(Json.encodeToString(repository.batches(ownedId))))
                    .put("events", JSONArray(Json.encodeToString(repository.observeEvents(ownedId).first())))
                    .put(
                        "new_background_events",
                        JSONArray(
                            Json.encodeToString(
                                repository.observeEvents("queue").first().filter { it.id !in previousEventIds },
                            ),
                        ),
                    )
                    .put(
                        "work",
                        JSONArray(
                            ownedWork().map {
                                JSONObject().put("id", it.id.toString()).put("state", it.state.name)
                                    .put("run_attempt_count", it.runAttemptCount).put("stop_reason", it.stopReason)
                            },
                        ),
                    ),
            )
            emit(stage)
        }

        private fun fixtureHealth(): JSONObject {
            val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
            return client.newCall(Request.Builder().url("http://127.0.0.1:8765/health").build()).execute().use {
                assertEquals(200, it.code)
                val health = JSONObject(requireNotNull(it.body).string())
                assertTrue(health.getBoolean("fixture"))
                assertFalse(health.getBoolean("cloud_forwarding"))
                val scenarios = health.getJSONArray("scenarios")
                assertTrue((0 until scenarios.length()).any { index -> scenarios.getString(index) == "worker-$mode" })
                health
            }
        }

        private suspend fun await(timeoutMs: Long, ready: suspend () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (true) {
                check(SystemClock.elapsedRealtime() < deadline) { "Observation timed out after $timeoutMs ms" }
                if (ready()) {
                    check(SystemClock.elapsedRealtime() <= deadline) { "Observation arrived after $timeoutMs ms" }
                    return
                }
                delay(100)
            }
        }

        private fun saveCheckpoint() = writeAtomic(checkpointFile, checkpoint)

        private fun emit(stage: String) {
            report.put("status", stage).put("updated_elapsed_ms", SystemClock.elapsedRealtime())
            writeAtomic(reportFile, report)
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString("translation_worker_recovery_status", stage)
                    putString("translation_worker_recovery_report_json", report.toString())
                },
            )
            println("TRANSLATION_WORKER_RECOVERY_STATUS=$stage RUN=$runId PID=${Process.myPid()}")
        }

        private fun writeAtomic(file: File, value: JSONObject) {
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(value.toString(2).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
        }

        private fun makeImage(index: Int): TranslationImage {
            val file = File(root, "page-$index.png")
            val bitmap = Bitmap.createBitmap(960, 320, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply {
                    drawColor(Color.WHITE)
                    drawText(
                        "WORKER RECOVERY $index",
                        40f,
                        170f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.BLACK
                            textSize = 64f
                        },
                    )
                }
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            return TranslationImage(
                index.toString(),
                index,
                file.absolutePath,
                "image/png",
                960,
                320,
                hash(file.readBytes()),
                file.length(),
            )
        }

        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
