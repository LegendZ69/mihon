package mihon.feature.translation.notifications

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mihon.feature.translation.TranslationNotificationSnapshot
import mihon.feature.translation.TranslationNotifications
import mihon.feature.translation.ocr.PaddleModelState
import mihon.feature.translation.ocr.PaddleModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.PaddleProfile
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationProgressUnit
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.TranslationStage

@RunWith(AndroidJUnit4::class)
class TranslationNotificationPresentationTest {
    @Test
    fun foregroundNotificationCannotRemoveChapterAndModelGroupChildrenWhenCancelled() {
        val publisher = TranslationNotifications(ApplicationProvider.getApplicationContext<Context>())
        val snapshot =
            TranslationNotificationSnapshot(listOf(job("active", TranslationJobState.TRANSLATING)), emptyList(), 2)
        val foreground = publisher.foreground(snapshot)
        assertNull(foreground.group)
        assertFalse(foreground.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertTrue(publisher.cards(snapshot).values.all { it.group != null })
    }

    @Test
    fun chapterAndDownloadCardsShareTheLimitAndReportDistinctProgressUnits() {
        val publisher = TranslationNotifications(ApplicationProvider.getApplicationContext<Context>())
        val active = job("active", TranslationJobState.TRANSLATING)
        val paused = job("paused", TranslationJobState.PAUSED)
        val operation = TranslationOperation(
            "ocr",
            active.id,
            TranslationStage.RECOGNITION,
            state = TranslationOperationState.ACTIVE,
            completed = 3,
            total = 7,
            unit = TranslationProgressUnit.REGIONS,
            attempt = 2,
        )
        val model = PaddleModelState(
            PaddleProfile.SMALL,
            false,
            "official-detector",
            "official-recognizer",
            downloadBytes = 512,
            totalBytes = 1024,
            status = PaddleModelStatus.DOWNLOADING,
            operationId = "model-install",
        )
        val snapshot = TranslationNotificationSnapshot(listOf(active, paused), listOf(operation), 2, listOf(model))
        val cards = publisher.cards(snapshot)
        assertEquals(setOf(active.id, "model:model-install"), cards.keys)
        val chapter = cards.getValue(active.id)
        val text = chapter.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(chapter.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Series active"))
        assertTrue(text.contains("Chapter active"))
        assertTrue(text.contains("3/7 regions"))
        assertTrue(text.contains("attempt 2"))
        assertTrue(text.contains("1/5 saved pages"))
        assertTrue(text.contains("1 downloads"))
        assertEquals(listOf("Logs", "Pause", "Cancel"), chapter.actions.map { it.title.toString() })
        val download = cards.getValue("model:model-install")
        assertTrue(download.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("512/1024 bytes"))
        assertEquals(500, download.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(listOf("Logs", "Cancel download"), download.actions.map { it.title.toString() })
        assertTrue(publisher.cards(snapshot.copy(cardLimit = 0)).isEmpty())
        val pausedCard = publisher.cards(snapshot.copy(jobs = listOf(paused), models = emptyList())).getValue(paused.id)
        assertEquals(listOf("Logs", "Resume", "Cancel"), pausedCard.actions.map { it.title.toString() })
    }

    private fun job(id: String, state: TranslationJobState) = TranslationJob(
        id, 1, 2, "Series $id", "Chapter $id",
        TranslationSettings(), state, imageCount = 5, completedImages = 1,
    )
}
