package mihon.feature.translation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import mihon.feature.translation.ocr.PaddleModelState
import mihon.feature.translation.ocr.PaddleModelStatus
import mihon.feature.translation.ui.TranslationActivity
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState

internal data class TranslationNotificationSnapshot(
    val jobs: List<TranslationJob>,
    val operations: List<TranslationOperation>,
    val cardLimit: Int,
    val models: List<PaddleModelState> = emptyList(),
) {
    // Routine stage and counter changes share the progress interval. Giving every completed subprocess
    // its own immediate key floods Android's package update budget and can drop the final saved count.
    val stateKey: String get() = jobs.sortedBy { it.id }.joinToString { job ->
        val active = job.state in setOf(
            TranslationJobState.ACQUIRING,
            TranslationJobState.OCR,
            TranslationJobState.TRANSLATING,
        )
        "${job.id}:${if (active) "ACTIVE" else job.state}:${if (active) null else job.message}"
    } + operations.filter { operation ->
        operation.state !in setOf(
            TranslationOperationState.QUEUED,
            TranslationOperationState.ACTIVE,
            TranslationOperationState.COMPLETED,
        )
    }.sortedBy { it.id }.joinToString {
        "${it.id}:${it.state}:${it.stage}:${it.attempt}:${it.message}"
    } + models.sortedWith(compareBy({ it.profile }, { it.korean }, { it.operationId })).joinToString {
        "${it.profile}:${it.korean}:${it.status}:${it.operationId}:${it.error}"
    }
    val activeModels get() = models.filter { it.status == PaddleModelStatus.DOWNLOADING }
}

/** Chapter and model cards use one shared card limit and durable operation links. */
internal class TranslationNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val visible = manager.activeNotifications.filter {
        it.id == CHILD_ID && it.notification.group == GROUP
    }.mapNotNull { it.tag }.toMutableSet()

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Translation queue", NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun summary(
        snapshot: TranslationNotificationSnapshot?,
        preparing: String = "Preparing translation queue",
    ): Notification {
        val jobs = snapshot?.jobs.orEmpty()
        val active = jobs.filter { it.state in TranslationManager.activeStates }
        val models = snapshot?.activeModels.orEmpty()
        val total = jobs.sumOf { it.imageCount }
        val saved = jobs.sumOf { it.completedImages }
        val unknown = jobs.any { it.imageCount == 0 }
        val text = if (snapshot == null) {
            preparing
        } else {
            "${active.size} chapters active · ${models.size} downloads · " +
                "$saved/${if (unknown) "$total + ?" else total} saved pages · " +
                "${active.map { it.mangaId }.distinct().size} series"
        }
        val lines =
            jobs.take(8).map { job ->
                "${job.mangaTitle} · ${job.chapterTitle}: ${description(job, snapshot?.operations.orEmpty())}"
            } +
                models.map { "${it.profile.name.lowercase()} OCR pack · ${it.downloadBytes}/${it.totalBytes} bytes" }
        return base().setContentTitle("Mihon translator").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText((listOf(text) + lines).joinToString("\n")))
            .setGroup(
                GROUP,
            ).setGroupSummary(true).setOngoing(active.isNotEmpty() || models.isNotEmpty() || snapshot == null)
            .setContentIntent(open(null, false))
            .addAction(android.R.drawable.ic_media_pause, "Pause chapters", control(null, "pause"))
            .addAction(android.R.drawable.ic_menu_view, "Logs", open(null, true)).build()
    }

    internal fun cards(snapshot: TranslationNotificationSnapshot): Map<String, Notification> {
        val limit = snapshot.cardLimit.coerceIn(0, 10)
        val jobs = snapshot.jobs.filter {
            it.state in TranslationManager.activeStates ||
                it.state == TranslationJobState.PAUSED
        }
        // Active work precedes paused cards. Pack downloads share the same budget as active chapters.
        val active = jobs.filter { it.state != TranslationJobState.PAUSED }.take(limit)
        val models = snapshot.activeModels.take(limit - active.size)
        val paused = jobs.filter { it.state == TranslationJobState.PAUSED }.take(limit - active.size - models.size)
        return buildMap {
            (active + paused).forEach { job ->
                val operation = snapshot.operations.firstOrNull {
                    it.jobId == job.id &&
                        it.state == TranslationOperationState.ACTIVE
                }
                val isPaused = job.state == TranslationJobState.PAUSED
                val text = description(job, snapshot.operations)
                val expanded = listOfNotNull(
                    job.chapterTitle,
                    text,
                    job.message,
                    "${snapshot.jobs.count {
                        it.state in TranslationManager.activeStates
                    }} active chapters · ${snapshot.activeModels.size} downloads · " +
                        "${job.completedImages}/${job.imageCount.takeIf {
                            it > 0
                        } ?: "?"} saved pages",
                ).distinct().joinToString("\n")
                val builder = base().setContentTitle("${if (isPaused) "Ⅱ" else "▶"} ${job.mangaTitle}")
                    .setContentText(
                        "${job.chapterTitle} · $text",
                    ).setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
                    .setSubText(job.settings.provider.model).setGroup(GROUP).setOngoing(!isPaused)
                    .setContentIntent(open(job, false))
                    .addAction(android.R.drawable.ic_menu_view, "Logs", open(job, true))
                    .addAction(
                        if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                        if (isPaused) "Resume" else "Pause",
                        control(job.id, if (isPaused) "resume" else "pause"),
                    )
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", control(job.id, "cancel"))
                progress(builder, operation?.completed ?: 0, operation?.total, !isPaused)
                put(job.id, builder.build())
            }
            models.forEach { model ->
                val id = model.operationId ?: return@forEach
                val title = "${model.profile.name.lowercase()} OCR pack · " +
                    if (model.korean) "Korean" else "multilingual"
                val text = "Downloading · ${model.downloadBytes}/${model.totalBytes} bytes"
                val builder = base().setContentTitle("↓ $title").setContentText(text)
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            "$text\n${model.detectorModel}\n${model.recognizerModel}\n" +
                                "${active.size} active chapters · ${snapshot.activeModels.size} downloads\n" +
                                "Cancellation retains previously installed packs; " +
                                "restart downloads the unfinished artifact again.",
                        ),
                    )
                    .setGroup(GROUP).setOngoing(true).setContentIntent(open(null, true, id))
                    .addAction(android.R.drawable.ic_menu_view, "Logs", open(null, true, id))
                    .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Cancel download",
                        control(id, "cancel-model"),
                    )
                progress(builder, model.downloadBytes, model.totalBytes, true)
                put("model:$id", builder.build())
            }
        }
    }

    fun publish(snapshot: TranslationNotificationSnapshot, standaloneSummary: Boolean) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            return
        }
        val cards = cards(snapshot)
        (visible - cards.keys).forEach { manager.cancel(it, CHILD_ID) }
        cards.forEach { (tag, notification) -> manager.notify(tag, CHILD_ID, notification) }
        visible.clear()
        visible.addAll(cards.keys)
        if (standaloneSummary) {
            if (cards.isEmpty()) {
                manager.cancel(MODEL_SUMMARY_TAG, SUMMARY_ID)
            } else {
                manager.notify(MODEL_SUMMARY_TAG, SUMMARY_ID, summary(snapshot))
            }
        } else {
            manager.cancel(MODEL_SUMMARY_TAG, SUMMARY_ID)
        }
    }

    private fun progress(builder: NotificationCompat.Builder, completed: Long, total: Long?, active: Boolean) {
        if (total != null &&
            total > 0
        ) {
            builder.setProgress(1000, (completed.toDouble() / total * 1000).toInt().coerceIn(0, 1000), false)
        } else if (active) {
            builder.setProgress(0, 0, true)
        }
    }
    private fun base() = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_edit)
        .setOnlyAlertOnce(true).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
    private fun description(job: TranslationJob, operations: List<TranslationOperation>): String {
        val op = operations.firstOrNull { it.jobId == job.id && it.state == TranslationOperationState.ACTIVE }
        return op?.let {
            "${it.stage.label} · ${it.completed}/${it.total ?: "?"} ${it.unit.label}${it.attempt?.let { n ->
                " · attempt $n"
            }.orEmpty()}"
        }
            ?: job.state.name.lowercase().replace('_', ' ')
    }
    private fun open(job: TranslationJob?, logs: Boolean, operationId: String? = null): PendingIntent {
        val key = "${job?.id ?: operationId ?: "queue"}:${if (logs) "logs" else "queue"}"
        val intent = TranslationActivity.intent(context, job?.mangaId, if (logs) 1 else 0)
            .putExtra("jobId", job?.id).putExtra("operationId", operationId).setAction("translation:$key")
        return PendingIntent.getActivity(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    private fun control(jobId: String?, action: String): PendingIntent {
        val intent = Intent(
            context,
            TranslationQueueReceiver::class.java,
        ).setAction("translation:$action:${jobId ?: "queue"}")
            .putExtra("control", action).putExtra("jobId", jobId)
        return PendingIntent.getBroadcast(
            context,
            intent.action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    companion object {
        const val CHANNEL = "translator_progress"
        const val SUMMARY_ID = 7101
        private const val CHILD_ID = 7102
        private const val GROUP = "mihon.translation.operations"
        private const val MODEL_SUMMARY_TAG = "translator-download-summary"
    }
}
