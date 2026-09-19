package mihon.feature.translation.acceptance

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
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
import kotlinx.coroutines.withTimeout
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
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationSettings
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in actual WorkManager/foreground notification checks; never calls runQueue directly. */
@RunWith(AndroidJUnit4::class)
class MinifiedTranslationWorkerAcceptanceTest {
    @Test
    fun inspectWorkerPreflightOnly() =
        runBlocking<Unit> {
            assumeTrue(InstrumentationRegistry.getArguments().getString("translation.workerPreflight") == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assertEquals("app.mihon.benchmark", context.packageName)
            assertTrue(BuildConfig.TRANSLATION_LOCAL_FIXTURES_ENABLED)
            assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            withContext(Dispatchers.IO) {
                val jobs = context.appGraph.translationRepository.jobs()
                val infos =
                    WorkManager
                        .getInstance(context)
                        .getWorkInfosForUniqueWork("translator_queue")
                        .get(10, TimeUnit.SECONDS)
                val report =
                    JSONObject()
                        .put("preflight_only", true)
                        .put("package", context.packageName)
                        .put("job_count", jobs.size)
                        .put("job_states", JSONObject(jobs.groupingBy { it.state.name }.eachCount()))
                        .put(
                            "unfinished_work",
                            JSONArray(
                                infos.filter { !it.state.isFinished }.map {
                                    JSONObject().put("id", it.id.toString()).put("state", it.state.name)
                                },
                            ),
                        ).put("online", context.activeNetworkState().isOnline)
                        .put(
                            "notifications_enabled",
                            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
                        )
                        .put(
                            "ready",
                            jobs.isEmpty() && infos.all {
                                it.state.isFinished
                            } && context.activeNetworkState().isOnline,
                        )
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    0,
                    Bundle().apply {
                        putString("translation_worker_preflight_json", report.toString())
                    },
                )
                println("TRANSLATION_WORKER_PREFLIGHT_JSON=$report")
            }
        }

    @Test
    fun localForegroundWorkerPreservesCommittedPages() =
        runBlocking<Unit> {
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue(arguments.getString("translation.workerAcceptance") == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assertEquals("app.mihon.benchmark", context.packageName)
            assertTrue(BuildConfig.TRANSLATION_LOCAL_FIXTURES_ENABLED)
            assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            val mode = requireNotNull(arguments.getString("translation.workerMode"))
            require(
                mode in
                    setOf(
                        "notification",
                        "notification-actions",
                        "notification-cancel",
                        "network",
                        "screen-off",
                        "active-resume",
                    ),
            )
            val automatedNotification = mode in setOf("notification-actions", "notification-cancel")
            val scenario = if (automatedNotification) "worker-notification" else "worker-$mode"
            val runId = requireNotNull(arguments.getString("translation.workerRunId"))
            require(UUID.fromString(runId).toString() == runId)
            withContext(Dispatchers.IO) {
                val graph = context.appGraph
                val manager = graph.translationManager
                val repository = graph.translationRepository
                val work = WorkManager.getInstance(context)

                fun workInfos() = work.getWorkInfosForUniqueWork("translator_queue").get(10, TimeUnit.SECONDS)
                assertTrue(
                    "No queue data is reset: this protocol requires an empty benchmark translation queue",
                    repository.jobs().isEmpty(),
                )
                assertTrue(
                    "Finish existing benchmark translator WorkManager work first",
                    workInfos().all {
                        it.state.isFinished
                    },
                )
                assertTrue(context.activeNetworkState().isOnline)
                val originalWorkIds = workInfos().map { it.id }.toSet()
                val settingsBefore = graph.translationPreferences.settings.value
                if (automatedNotification || mode == "notification") {
                    val notifications = context.getSystemService(NotificationManager::class.java)
                    assertTrue(
                        "Enable only benchmark notifications for this run, and restore the recorded policy afterward",
                        notifications.areNotificationsEnabled(),
                    )
                    assertTrue(
                        "Published child actions require at least one configured card",
                        settingsBefore.notificationChapterCards > 0,
                    )
                    assertTrue(
                        "The translator channel must be enabled for published-action acceptance",
                        notifications.getNotificationChannel("translator_progress")?.importance !=
                            NotificationManager.IMPORTANCE_NONE,
                    )
                }
                if (mode == "active-resume") {
                    assertFalse(settingsBefore.autoTranslate)
                    assertEquals(0, settingsBefore.chaptersAhead)
                }
                val backgroundIdsBefore =
                    repository
                        .observeEvents("queue")
                        .first()
                        .map { it.id }
                        .toSet()
                val root = File(context.noBackupFilesDir, "translation-worker-acceptance/$runId")
                check(root.mkdirs()) { "Run UUID must be new" }
                val credentialId = "worker-fixture-$runId"
                val jobId = "worker-fixture-$runId"
                val records = JSONArray()
                val report =
                    JSONObject()
                        .put("schema", 1)
                        .put("run_id", runId)
                        .put("mode", mode)
                        .put("package", context.packageName)
                        .put("pid", Process.myPid())
                        .put("cloud_forwarding", false)
                        .put("records", records)
                        .put(
                            "limits",
                            "Actual foreground Worker under active instrumentation; " +
                                "not natural idle, force-stop, process death, or OEM eviction",
                        )

                fun emit(stage: String) {
                    report.put("status", stage).put("updated_uptime_ms", SystemClock.elapsedRealtime())
                    val file = AtomicFile(File(root, "report.json"))
                    val stream = file.startWrite()
                    try {
                        stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
                        file.finishWrite(stream)
                    } catch (error: Throwable) {
                        file.failWrite(stream)
                        throw error
                    }
                    InstrumentationRegistry.getInstrumentation().sendStatus(
                        0,
                        Bundle().apply {
                            putString("translation_worker_status", stage)
                            putString("translation_worker_report_json", report.toString())
                        },
                    )
                    println("TRANSLATION_WORKER_STATUS=$stage RUN=$runId PID=${Process.myPid()}")
                }

                fun publishedChapterNotifications(): JSONArray {
                    val notifications = context.getSystemService(NotificationManager::class.java)
                    return JSONArray(
                        notifications.activeNotifications.filter {
                            it.id == 7102 && it.tag == jobId &&
                                it.notification.group == "mihon.translation.operations"
                        }.map {
                            val notification = it.notification
                            JSONObject()
                                .put("id", it.id)
                                .put("tag", it.tag)
                                .put("post_time_epoch_ms", it.postTime)
                                .put("notification_when_epoch_ms", notification.`when`)
                                .put("title", notification.extras.getCharSequence("android.title")?.toString())
                                .put("text", notification.extras.getCharSequence("android.text")?.toString())
                                .put("big_text", notification.extras.getCharSequence("android.bigText")?.toString())
                                .put("actions", JSONArray(notification.actions.orEmpty().map { it.title.toString() }))
                        },
                    )
                }

                suspend fun snapshot(stage: String) {
                    val info = workInfos().filter { it.id !in originalWorkIds }
                    records.put(
                        JSONObject()
                            .put("stage", stage)
                            .put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
                            .put("online", context.activeNetworkState().isOnline)
                            .put("interactive", context.getSystemService(PowerManager::class.java).isInteractive)
                            .put("published_chapter_notifications", publishedChapterNotifications())
                            .put("jobs", JSONArray(Json.encodeToString(repository.jobs().filter { it.id == jobId })))
                            .put("results", JSONArray(Json.encodeToString(repository.results(jobId))))
                            .put("batches", JSONArray(Json.encodeToString(repository.batches(jobId))))
                            .put("events", JSONArray(Json.encodeToString(repository.observeEvents(jobId).first())))
                            .put(
                                "background_events",
                                JSONArray(
                                    Json.encodeToString(
                                        repository.observeEvents("queue").first().filter {
                                            it.id !in
                                                backgroundIdsBefore
                                        },
                                    ),
                                ),
                            ).put(
                                "work",
                                JSONArray(
                                    info.map {
                                        JSONObject()
                                            .put("id", it.id.toString())
                                            .put("state", it.state.name)
                                            .put("run_attempt_count", it.runAttemptCount)
                                            .put("stop_reason", it.stopReason)
                                    },
                                ),
                            ),
                    )
                    emit(stage)
                }

                suspend fun await(
                    timeout: Long = 60_000,
                    predicate: suspend () -> Boolean,
                ) {
                    withTimeout(timeout) { while (!predicate()) delay(100) }
                }

                suspend fun requestCount() =
                    repository.observeEvents(jobId).first().count {
                        it.stage == "generateContent" && it.message.startsWith("Request started")
                    }

                suspend fun current() = repository.jobs().single { it.id == jobId }
                var seeded = false
                var failure: Throwable? = null

                fun remember(error: Throwable) {
                    val previous = failure
                    if (previous == null) {
                        failure = error
                    } else if (previous !== error) {
                        previous.addSuppressed(error)
                    }
                }
                try {
                    val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
                    client.newCall(Request.Builder().url("http://127.0.0.1:8765/health").build()).execute().use {
                        assertEquals(200, it.code)
                        val health = JSONObject(requireNotNull(it.body).string())
                        assertTrue(health.getBoolean("fixture"))
                        assertFalse(health.getBoolean("cloud_forwarding"))
                        assertTrue(
                            (0 until health.getJSONArray("scenarios").length()).any { index ->
                                health.getJSONArray("scenarios").getString(index) == scenario
                            },
                        )
                        report.put("fixture_server", health)
                    }
                    graph.translationCredentialVault.import(
                        credentialId,
                        "mihon-fixture-only",
                        TranslationProviderKind.OPENAI,
                    )
                    val settings =
                        TranslationSettings(
                            provider =
                            ProviderSettings(
                                kind = TranslationProviderKind.OPENAI,
                                credentialId = credentialId,
                                model = "mihon-fixture",
                                baseUrl = "http://127.0.0.1:8765/v1",
                                dialect = OpenAiDialect.CHAT_COMPLETIONS,
                                extraHeaders = mapOf("X-Mihon-Fixture-Scenario" to scenario),
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
                    val images = List(2) { makeImage(root, it) }
                    // Start paused so an unrelated scheduler invocation cannot acquire sources between these writes.
                    val job =
                        TranslationJob(
                            jobId,
                            Long.MAX_VALUE,
                            Long.MAX_VALUE,
                            "Disposable worker acceptance",
                            mode,
                            settings,
                            state = TranslationJobState.PAUSED,
                            imageCount = 2,
                        )
                    repository.saveJob(job)
                    seeded = true
                    repository.saveImages(jobId, images)
                    repository.saveJob(job.copy(state = TranslationJobState.QUEUED))
                    // Global settings remain untouched; per-job image allowance is one and no other job exists.
                    TranslationWorker.start(context)
                    await { requestCount() == 2 && repository.results(jobId).size == 1 }
                    val firstSavedObservedAt = SystemClock.elapsedRealtime()
                    val preserved = repository.results(jobId).single()
                    assertTrue(workInfos().any { it.id !in originalWorkIds && it.state == WorkInfo.State.RUNNING })
                    val notifications = context.getSystemService(NotificationManager::class.java)
                    await(10_000) { notifications.activeNotifications.any { it.id == 7101 } }
                    val notification = notifications.activeNotifications.single { it.id == 7101 }
                    assertEquals(
                        "Mihon translator",
                        notification.notification.extras
                            .getCharSequence("android.title")
                            .toString(),
                    )
                    assertTrue(notification.notification.actions.any { it.title.toString() == "Pause chapters" })
                    report.put("images", JSONArray(Json.encodeToString(images)))
                    if (automatedNotification || mode == "notification") {
                        val observations = JSONArray()
                        report.put("published_progress_observations", observations)
                            .put("first_saved_observed_elapsed_ms", firstSavedObservedAt)
                            .put("published_progress_deadline_ms", 1_500)
                        var matched = false
                        var matchedAt: Long? = null
                        do {
                            val activeOperation = repository.observeOperations(limit = 1_000).first().firstOrNull {
                                it.jobId == jobId && it.state == TranslationOperationState.ACTIVE
                            }
                            val stage = activeOperation?.let {
                                "${it.stage.label} · ${it.completed}/${it.total ?: "?"} ${it.unit.label}"
                            }
                            val cards = publishedChapterNotifications()
                            val expectedPages = "${repository.results(jobId).size}/${current().imageCount} saved pages"
                            matched = cards.length() == 1 && stage != null &&
                                cards.getJSONObject(0).optString("big_text").let { text ->
                                    text.contains(expectedPages) && text.contains(stage)
                                }
                            if (matched) matchedAt = SystemClock.elapsedRealtime()
                            observations.put(
                                JSONObject().put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
                                    .put("expected_saved_pages", expectedPages)
                                    .put("expected_operation_id", activeOperation?.id)
                                    .put("expected_stage", stage)
                                    .put("matches_saved_result_and_active_stage", matched)
                                    .put("posted_cards", cards),
                            )
                            if (matched || SystemClock.elapsedRealtime() - firstSavedObservedAt >= 1_500) break
                            delay(100)
                        } while (true)
                        snapshot("published_progress_after_first_saved_page")
                        assertTrue(
                            "Published chapter extras must show the durably saved 1/2 pages and active stage " +
                                "within 1500 ms; HyperOS visible modal text is a separate observation",
                            matched && checkNotNull(matchedAt) - firstSavedObservedAt <= 1_500,
                        )
                        report.put("published_progress_matched_elapsed_ms", matchedAt)
                    }
                    when (mode) {
                        "active-resume" -> {
                            val ownedBefore = workInfos().filter { it.id !in originalWorkIds }
                            assertEquals(1, ownedBefore.size)
                            assertEquals(WorkInfo.State.RUNNING, ownedBefore.single().state)
                            assertEquals(TranslationJobState.TRANSLATING, current().state)
                            snapshot("before_resume_while_worker_running")
                            assertEquals(listOf(preserved), repository.results(jobId))
                            assertEquals(2, requestCount())
                            val resumedAt = SystemClock.elapsedRealtime()
                            report.put("resume_called_elapsed_ms", resumedAt)
                                .put("resume_action", "public_per_job_resume_while_actual_worker_running")
                                .put("resume_completion_budget_ms", 90_000)
                                .put("running_work_id_before_resume", ownedBefore.single().id.toString())
                            // Intentionally no Pause or teardown wait. The second response remains stalled for 120 s.
                            manager.resume(jobId)
                            val beforeIds = ownedBefore.map { it.id }.toSet()
                            await(10_000) {
                                workInfos().any { it.id !in originalWorkIds && it.id !in beforeIds }
                            }
                            snapshot("after_resume_while_old_worker_unfinished")
                            // Keep the deadline relative to the public action, including enqueue observation overhead.
                            await((90_000 - (SystemClock.elapsedRealtime() - resumedAt)).coerceAtLeast(1)) {
                                current().state == TranslationJobState.COMPLETED
                            }
                            assertTrue(SystemClock.elapsedRealtime() - resumedAt <= 90_000)
                            report.put("resume_completion_elapsed_ms", SystemClock.elapsedRealtime() - resumedAt)
                        }

                        "notification" -> {
                            snapshot("awaiting_notification_pause")
                            // Only the operator taps the actual notification action; no direct pause call here.
                            await { current().state == TranslationJobState.PAUSED }
                            await { workInfos().filter { it.id !in originalWorkIds }.all { it.state.isFinished } }
                            assertEquals(listOf(preserved), repository.results(jobId))
                            assertEquals(2, requestCount())
                            assertTrue(repository.batches(jobId).any { it.state == "INTERRUPTED" })
                            snapshot("paused_by_notification")
                            // This is the same per-job resume action the queue UI calls; it enqueues a real Worker.
                            manager.resume(jobId)
                        }

                        "notification-actions", "notification-cancel" -> {
                            suspend fun publishedAction(title: String): android.app.PendingIntent {
                                await(10_000) {
                                    notifications.activeNotifications.any { item ->
                                        item.id == 7102 && item.tag == jobId &&
                                            item.notification.actions.any { it.title.toString() == title }
                                    }
                                }
                                return notifications.activeNotifications.single { it.id == 7102 && it.tag == jobId }
                                    .notification.actions.single { it.title.toString() == title }.actionIntent
                            }
                            report.put("notification_actions_dispatched_from_published_cards", true)
                            snapshot("before_published_child_pause")
                            publishedAction("Pause").send()
                            await { current().state == TranslationJobState.PAUSED }
                            await { workInfos().filter { it.id !in originalWorkIds }.all { it.state.isFinished } }
                            assertEquals(listOf(preserved), repository.results(jobId))
                            assertEquals(2, requestCount())
                            assertTrue(repository.batches(jobId).any { it.state == "INTERRUPTED" })
                            snapshot("paused_by_published_child_action")
                            if (mode == "notification-cancel") {
                                // Wait for the paused card before selecting Cancel.
                                publishedAction("Resume")
                                publishedAction("Cancel").send()
                                await { current().state == TranslationJobState.CANCELLED }
                                delay(1_500)
                                assertEquals(2, requestCount())
                                assertEquals(listOf(preserved), repository.results(jobId))
                                assertTrue(workInfos().filter { it.id !in originalWorkIds }.all { it.state.isFinished })
                                snapshot("cancelled_by_published_child_action")
                            } else {
                                publishedAction("Resume").send()
                                snapshot("resume_dispatched_from_published_child_action")
                            }
                        }

                        "network" -> {
                            snapshot("awaiting_network_loss")
                            await { !context.activeNetworkState().isOnline }
                            await { current().state in setOf(TranslationJobState.WAITING, TranslationJobState.QUEUED) }
                            await {
                                workInfos().filter { it.id !in originalWorkIds }.none {
                                    it.state ==
                                        WorkInfo.State.RUNNING
                                }
                            }
                            assertEquals(listOf(preserved), repository.results(jobId))
                            assertEquals(2, requestCount())
                            snapshot("awaiting_network_restore")
                            await { context.activeNetworkState().isOnline }
                            snapshot("network_restored")
                            // No manager.resume, state rewrite, or start call: WorkManager must recover by itself.
                        }

                        "screen-off" -> {
                            snapshot("awaiting_screen_off")
                            val power = context.getSystemService(PowerManager::class.java)
                            await(30_000) { !power.isInteractive }
                            snapshot("observed_screen_off")
                            withTimeout(90_000) {
                                while (current().state != TranslationJobState.COMPLETED) {
                                    assertFalse("Keep the display off until worker completion", power.isInteractive)
                                    delay(100)
                                }
                            }
                            assertFalse(power.isInteractive)
                        }
                    }
                    if (mode == "notification-cancel") {
                        assertEquals(TranslationJobState.CANCELLED, current().state)
                        assertEquals(1, current().completedImages)
                        assertEquals(listOf(preserved), repository.results(jobId))
                        assertEquals(2, requestCount())
                    } else {
                        await(180_000) { current().state == TranslationJobState.COMPLETED }
                        await { workInfos().filter { it.id !in originalWorkIds }.all { it.state.isFinished } }
                        assertEquals(2, current().completedImages)
                        assertEquals(preserved, repository.results(jobId).single { it.imageId == preserved.imageId })
                        assertEquals(if (mode == "screen-off") 2 else 3, requestCount())
                    }
                    images.forEach { assertEquals(it.contentHash, hash(File(it.filePath))) }
                    assertEquals(settingsBefore, graph.translationPreferences.settings.value)
                    snapshot("completed_assertions")
                } catch (error: Throwable) {
                    remember(error)
                    report.put("failure_class", error.javaClass.name).put("failure_message", error.message)
                    try {
                        if (seeded) snapshot("failed_before_cleanup") else emit("failed_preflight")
                    } catch (reportError: Throwable) {
                        remember(reportError)
                    }
                } finally {
                    withContext(NonCancellable) {
                        suspend fun cleanupStep(block: suspend () -> Unit) {
                            try {
                                block()
                            } catch (error: Throwable) {
                                remember(error)
                            }
                        }
                        if (seeded) {
                            cleanupStep { manager.pause(jobId) }
                            cleanupStep {
                                workInfos().filter { it.id !in originalWorkIds && !it.state.isFinished }.forEach {
                                    cleanupStep { work.cancelWorkById(it.id).result.get(10, TimeUnit.SECONDS) }
                                }
                            }
                            cleanupStep { manager.remove(jobId) }
                            cleanupStep { graph.translationProvider.clearCheckpoints(jobId) }
                        }
                        cleanupStep { graph.translationCredentialVault.remove(credentialId) }
                        var credentialRemoved = false
                        var jobRemoved = false
                        var settingsUnchanged = false
                        cleanupStep {
                            credentialRemoved = graph.translationCredentialVault.info(credentialId) == null
                            assertTrue("Disposable credential must be removed", credentialRemoved)
                        }
                        cleanupStep {
                            jobRemoved = repository.jobs().none { it.id == jobId }
                            assertTrue("Disposable job must be removed", jobRemoved)
                        }
                        cleanupStep {
                            settingsUnchanged = settingsBefore == graph.translationPreferences.settings.value
                            assertTrue("Global settings must remain unchanged", settingsUnchanged)
                        }
                        report.put("credential_removed", credentialRemoved)
                        report.put("owned_job_removed", jobRemoved)
                        report.put("settings_unchanged", settingsUnchanged)
                        failure?.let {
                            report.put("failure_class", it.javaClass.name).put("failure_message", it.message)
                            report.put(
                                "suppressed_failure_classes",
                                JSONArray(
                                    it.suppressed.map { error ->
                                        error.javaClass.name
                                    },
                                ),
                            )
                        }
                        cleanupStep { emit(if (failure == null) "passed" else "failed") }
                    }
                }
                failure?.let { throw it }
            }
        }

    private fun makeImage(
        root: File,
        index: Int,
    ): TranslationImage {
        val file = File(root, "page-$index.png")
        val bitmap = Bitmap.createBitmap(960, 320, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText(
                    "WORKER FIXTURE $index",
                    40f,
                    170f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 72f
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
            hash(file),
            file.length(),
        )
    }

    private fun hash(file: File): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}
