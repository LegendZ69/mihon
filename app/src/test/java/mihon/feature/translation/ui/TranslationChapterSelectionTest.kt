package mihon.feature.translation.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationSettings

class TranslationChapterSelectionTest {
    @Test
    fun `chapter actions retain older active work instead of resuming a newer inactive history`() {
        val active = job("active", TranslationJobState.TRANSLATING, 10)
        val newerFailed = job("failed", TranslationJobState.FAILED, 20)
        assertEquals("active", selectChapterTranslationJob(listOf(newerFailed, active))?.id)
        assertEquals(
            "queued",
            selectChapterTranslationJob(
                listOf(newerFailed, active.copy(id = "queued", state = TranslationJobState.QUEUED)),
            )?.id,
        )
        assertEquals(
            "waiting",
            selectChapterTranslationJob(
                listOf(newerFailed, active.copy(id = "waiting", state = TranslationJobState.WAITING)),
            )?.id,
        )
    }

    @Test
    fun `inactive history selection is newest and empty history stays absent`() {
        assertNull(selectChapterTranslationJob(emptyList()))
        val older = job("older", TranslationJobState.PARTIAL, 10)
        val newest = job("newest", TranslationJobState.PAUSED, 20)
        assertEquals("newest", selectChapterTranslationJob(listOf(newest, older))?.id)
    }

    private fun job(id: String, state: TranslationJobState, createdAt: Long) =
        TranslationJob(id, 1, 2, "Series", "Chapter", TranslationSettings(), state = state, createdAt = createdAt)
}
