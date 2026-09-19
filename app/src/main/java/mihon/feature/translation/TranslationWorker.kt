package mihon.feature.translation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationJobState
import java.util.UUID
import java.util.concurrent.TimeUnit

class TranslationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val manager = context.appGraph.translationManager
    private val publisher = TranslationNotifications(context)
    private val notificationCenter = context.appGraph.translationNotificationCenter
    private val runJobs = mutableSetOf<String>()

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Preparing translation queue")

    override suspend fun doWork(): Result = try {
        setForeground(getForegroundInfo())
        coroutineScope {
            val notifications = launch {
                val snapshots = combine(
                    manager.jobs,
                    manager.repository.observeOperations(limit = 1000),
                    manager.preferences.settings,
                ) {
                        jobs,
                        operations,
                        settings,
                    ->
                    runJobs +=
                        jobs.filter {
                            it.state in TranslationManager.activeStates ||
                                it.state == TranslationJobState.QUEUED
                        }.map { it.id }
                    TranslationNotificationSnapshot(
                        jobs.filter { it.id in runJobs },
                        operations.filter {
                            it.jobId in
                                runJobs
                        },
                        settings.notificationChapterCards,
                    )
                }
                snapshots.notificationUpdates { it.stateKey }.collect { snapshot ->
                    setForeground(foreground(snapshot))
                    notificationCenter.chapterProgress(snapshot)
                }
            }
            try {
                manager.runQueue()
            } finally {
                withContext(NonCancellable) {
                    notifications.cancelAndJoin()
                    val jobs = manager.repository.jobs().filter { it.id in runJobs }
                    notificationCenter.chapterWorkerStopped(
                        TranslationNotificationSnapshot(
                            jobs,
                            emptyList(),
                            manager.preferences.settings.value.notificationChapterCards,
                        ),
                    )
                }
            }
        }
        val waiting = manager.repository.jobs().any { it.state == TranslationJobState.WAITING }
        if (waiting) Result.retry() else Result.success()
    } catch (e: CancellationException) {
        withContext(NonCancellable) {
            manager.repository.addEvent(
                TranslationEvent(
                    UUID.randomUUID().toString(),
                    "queue",
                    stage = "background",
                    message = "Worker stopped; completed images are saved",
                    details = mapOf("stopReason" to stopReason.toString()),
                ),
            )
        }
        throw e
    } catch (e: Exception) {
        manager.repository.addEvent(
            TranslationEvent(
                UUID.randomUUID().toString(),
                "queue",
                stage = "background",
                level = "ERROR",
                message = e.message ?: "Foreground translation could not start",
            ),
        )
        Result.retry()
    }

    private fun foreground(message: String): ForegroundInfo = foregroundInfo(publisher.summary(null, message))
    private fun foreground(
        snapshot: TranslationNotificationSnapshot,
    ): ForegroundInfo = foregroundInfo(publisher.summary(notificationCenter.withModels(snapshot)))
    private fun foregroundInfo(notification: android.app.Notification) = ForegroundInfo(
        TranslationNotifications.SUMMARY_ID,
        notification,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    companion object {
        private const val CHANNEL = "translator_progress"
        private const val NOTIFICATION_ID = 7101
        private const val WORK = "translator_queue"

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<TranslationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

/** Persist Pause before stopping work, so the next worker cannot silently resume it. */
class TranslationQueueReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = context.appGraph.translationManager
                val jobId = intent?.getStringExtra("jobId")
                when (intent?.getStringExtra("control") ?: "pause") {
                    "pause" -> manager.pause(jobId)
                    "resume" -> manager.resume(jobId)
                    "cancel" -> if (jobId != null) manager.cancel(jobId)
                    "cancel-model" -> if (jobId != null) context.appGraph.paddleModelManager.cancel(jobId)
                }
            } catch (error: Exception) {
                context.logcat(LogPriority.ERROR, error) { "Unable to apply translation notification action" }
            } finally {
                pending.finish()
            }
        }
    }
}
