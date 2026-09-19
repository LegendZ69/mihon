package tachiyomi.domain.translation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.chapterHistoryKey
import tachiyomi.domain.translation.service.seriesHistoryKey

class TranslationHistoryIdentityTest {
    @Test
    fun selectingAnUnlinkedChapterCannotSelectAnotherArchiveWithTheSameSentinels() {
        val selected = job("archive-a", -1, -1)
        val histories = listOf(selected, job("archive-b", -1, -1), job("library", 1, 2))
        assertEquals(
            listOf("archive-a"),
            histories.filter {
                it.chapterHistoryKey() == selected.chapterHistoryKey()
            }.map { it.id },
        )
        assertEquals(3, histories.groupBy { it.seriesHistoryKey() }.size)
    }

    @Test
    fun realChapterHistoryAndSeriesStillGroupAcrossReplacementJobs() {
        val histories = listOf(job("old", 1, 2), job("new", 1, 2), job("another-chapter", 1, 3))
        assertEquals(2, histories.groupBy { it.chapterHistoryKey() }.size)
        assertEquals(1, histories.groupBy { it.seriesHistoryKey() }.size)
    }

    private fun job(id: String, manga: Long, chapter: Long) =
        TranslationJob(id, manga, chapter, "Series", "Chapter", TranslationSettings())
}
