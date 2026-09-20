package mihon.feature.translation.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import mihon.feature.translation.TranslationNotificationSnapshot
import mihon.feature.translation.TranslationNotifications
import mihon.feature.translation.ocr.PaddleModelState
import mihon.feature.translation.ocr.PaddleModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.PaddleProfile
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationSettings
import java.util.UUID

/** Opt-in, idle benchmark package only: real Android notification publication, with no queued/provider work. */
@RunWith(AndroidJUnit4::class)
class TranslationNotificationLifecycleTest {
    @Test
    fun pausedChapterAndModelCardsSurviveForegroundRemovalAndRestart() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("translation.notificationLifecycleAcceptance") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("app.mihon.benchmark", context.packageName)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(
            "Enable notifications explicitly before this opt-in test",
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
        assertTrue(
            "Run only with an idle notification surface; do not replace existing chapter/model cards",
            manager.activeNotifications.none { it.id in setOf(7101, 7102) },
        )
        val publisher = TranslationNotifications(context)
        assertTrue(
            "Translation progress channel must be enabled",
            manager.getNotificationChannel(TranslationNotifications.CHANNEL).importance !=
                NotificationManager.IMPORTANCE_NONE,
        )
        val id = "notification-lifecycle-${UUID.randomUUID()}"
        val job = TranslationJob(
            id, 1, 2, "Notification lifecycle validation", "Owned chapter card",
            TranslationSettings(), TranslationJobState.TRANSLATING, imageCount = 2, completedImages = 1,
        )
        val model = PaddleModelState(
            PaddleProfile.SMALL,
            false,
            "owned-detector",
            "owned-recognizer",
            downloadBytes = 512,
            totalBytes = 1024,
            status = PaddleModelStatus.DOWNLOADING,
            operationId = id,
        )
        val active = TranslationNotificationSnapshot(listOf(job), emptyList(), 2, listOf(model))
        val paused = active.copy(jobs = listOf(job.copy(state = TranslationJobState.PAUSED)))
        val modelTag = "model:$id"
        var lastPublication = 0L
        fun publish(snapshot: TranslationNotificationSnapshot) {
            // This direct publisher fixture respects the production progress interval instead of
            // flooding Android's package update quota. The tested cancellation has no delay.
            val remaining = lastPublication + 1_000 - SystemClock.uptimeMillis()
            if (remaining > 0) SystemClock.sleep(remaining)
            publisher.publish(snapshot)
            lastPublication = SystemClock.uptimeMillis()
        }
        fun cardActions() = manager.activeNotifications.singleOrNull { it.id == 7102 && it.tag == id }
            ?.notification?.actions?.map { it.title.toString() }
        fun hasModelAndSummary(): Boolean {
            val posted = manager.activeNotifications
            return posted.any { it.id == 7102 && it.tag == modelTag } &&
                posted.any {
                    it.id == 7101 && it.tag != null &&
                        it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
                }
        }
        fun hasForeground() = manager.activeNotifications.any { it.id == 7101 && it.tag == null }
        try {
            manager.notify(7101, publisher.foreground(active))
            publish(active)
            await("Active chapter, model and center-owned group summary are published") {
                hasForeground() && hasModelAndSummary() && cardActions() == listOf("Logs", "Pause", "Cancel")
            }
            publish(paused)
            await("Paused action card is published before foreground removal") {
                cardActions() == listOf("Logs", "Resume", "Cancel") && hasModelAndSummary()
            }
            // WorkManager owns/removes only the untagged foreground ID. On Android, removing a
            // group summary would also remove its children, reproducing the original failure.
            manager.cancel(7101)
            await("Paused Resume/Cancel and model cards survive foreground removal") {
                !hasForeground() && hasModelAndSummary() && cardActions() == listOf("Logs", "Resume", "Cancel")
            }
            manager.notify(7101, publisher.foreground(active))
            publish(active)
            await("Restart keeps the same center-owned group and its cards") {
                hasForeground() && hasModelAndSummary() && cardActions() == listOf("Logs", "Pause", "Cancel")
            }
            manager.cancel(7101)
            await("Foreground removal may also precede the final paused snapshot") { !hasForeground() }
            publish(paused)
            await("Paused cards publish after foreground removal") {
                hasModelAndSummary() && cardActions() == listOf("Logs", "Resume", "Cancel")
            }
            publish(paused.withLatestJobs(listOf(job.copy(state = TranslationJobState.CANCELLED))))
            await("Cancel dismisses only the chapter card and retains the model download") {
                cardActions() == null && hasModelAndSummary()
            }
        } finally {
            publisher.publish(TranslationNotificationSnapshot(emptyList(), emptyList(), 2))
            manager.cancel(7101)
            await("Remove only this test's notifications") {
                manager.activeNotifications.none { it.id in setOf(7101, 7102) }
            }
        }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue(message, predicate())
    }
}
