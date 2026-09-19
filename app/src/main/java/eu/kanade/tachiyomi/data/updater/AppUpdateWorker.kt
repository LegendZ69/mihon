package eu.kanade.tachiyomi.data.updater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import mihon.app.di.appGraph
import java.util.UUID

class AppUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val manager = context.appGraph.appUpdateManager
    private val notifications = AppUpdateNotifications(context)

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(
        manager.state.value?.takeIf {
            it.id ==
                id.toString()
        },
    )

    override suspend fun doWork(): Result {
        val recordId = inputData.getString(AppUpdateManager.RECORD_ID) ?: return Result.failure()
        if (manager.state.value?.id != recordId) return Result.failure()
        return try {
            setForeground(getForegroundInfo())
            var lastNotificationAt = SystemClock.elapsedRealtime()
            var lastStage = manager.state.value?.stage
            manager.runDownload(recordId) { snapshot ->
                val now = SystemClock.elapsedRealtime()
                if (manager.state.value?.id == recordId &&
                    manager.state.value?.stage in AppUpdateManager.activeStages &&
                    (snapshot.stage != lastStage || now - lastNotificationAt >= 500)
                ) {
                    setForeground(foreground(snapshot))
                    lastNotificationAt = now
                    lastStage = snapshot.stage
                }
            }
            notifications.finished(manager.state.value?.takeIf { it.id == recordId })
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            notifications.finished(manager.state.value?.takeIf { it.id == recordId })
            Result.failure()
        }
    }

    private fun foreground(snapshot: AppUpdateDownload?) = ForegroundInfo(
        AppUpdateNotifications.PROGRESS_ID,
        notifications.progress(snapshot),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )
}

internal class AppUpdateNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun builder(): NotificationCompat.Builder {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = AppUpdateManager.OPEN_DOWNLOAD
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    COMPLETE_ID,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOnlyAlertOnce(true)
    }

    fun progress(state: AppUpdateDownload?): Notification = builder()
        .setContentText(
            when (state?.stage) {
                AppUpdateStage.VERIFYING -> "Verifying update…"
                AppUpdateStage.DOWNLOADING -> "Downloading update… ${state.progress}%"
                else -> "Waiting to download update…"
            },
        )
        .setProgress(100, state?.progress ?: 0, state?.stage != AppUpdateStage.DOWNLOADING)
        .setOngoing(true)
        .apply {
            state?.let {
                addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.action_cancel),
                    WorkManager.getInstance(context).createCancelPendingIntent(UUID.fromString(it.id)),
                )
            }
        }.build()

    fun finished(state: AppUpdateDownload?) {
        if (state == null) return
        val notification = builder()
            .setContentText(
                if (state.stage ==
                    AppUpdateStage.DOWNLOADED
                ) {
                    "Update ready to install"
                } else {
                    "Update download failed. Tap to retry."
                },
            )
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(COMPLETE_ID, notification)
        } catch (_: SecurityException) {
            // The same state remains available in the app if notifications are disabled.
        }
    }

    fun dismiss() = manager.cancel(COMPLETE_ID)

    companion object {
        private const val CHANNEL = "app_update_downloads_v1"
        const val PROGRESS_ID = 7201
        const val COMPLETE_ID = 7202
    }
}
