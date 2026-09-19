package mihon.feature.translation.ui

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.app.di.AppGraph
import mihon.feature.translation.TranslationManager
import mihon.feature.translation.TranslationPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.TranslationCoverage
import tachiyomi.domain.translation.service.TranslationCoverageState
import tachiyomi.domain.translation.service.TranslationRepository

class TranslationChapterGestureTest {
    @Test
    fun `native chapter swipe keys defaults and legacy serialized assignments stay unchanged`() {
        val stored = mutableMapOf<String, String>()
        val store = object : PreferenceStore by InMemoryPreferenceStore() {
            override fun <T> getObjectFromString(
                key: String,
                defaultValue: T,
                serializer: (T) -> String,
                deserializer: (String) -> T,
            ): Preference<T> = object : Preference<T> by InMemoryPreferenceStore.InMemoryPreference(
                key,
                stored[key]?.let(deserializer),
                defaultValue,
            ) {
                override fun set(value: T) {
                    stored[key] = serializer(value)
                }
            }
        }
        val defaults = LibraryPreferences(store)
        assertEquals("pref_chapter_swipe_end_action", defaults.swipeToStartAction.key())
        assertEquals("pref_chapter_swipe_start_action", defaults.swipeToEndAction.key())
        assertEquals(LibraryPreferences.ChapterSwipeAction.ToggleBookmark, defaults.swipeToStartAction.get())
        assertEquals(LibraryPreferences.ChapterSwipeAction.ToggleRead, defaults.swipeToEndAction.get())
        for (name in listOf("ToggleRead", "ToggleBookmark", "Download", "Disabled")) {
            stored[defaults.swipeToStartAction.key()] = name
            stored[defaults.swipeToEndAction.key()] = name
            val restarted = LibraryPreferences(store)
            assertEquals(name, restarted.swipeToStartAction.get().name)
            assertEquals(name, restarted.swipeToEndAction.get().name)
        }
        defaults.swipeToStartAction.set(LibraryPreferences.ChapterSwipeAction.Translate)
        defaults.swipeToEndAction.set(LibraryPreferences.ChapterSwipeAction.TranslationQueue)
        val restarted = LibraryPreferences(store)
        assertEquals(LibraryPreferences.ChapterSwipeAction.Translate, restarted.swipeToStartAction.get())
        assertEquals(LibraryPreferences.ChapterSwipeAction.TranslationQueue, restarted.swipeToEndAction.get())
    }

    @Test
    fun `mixed history gestures retry only unfinished pages without forcing saved chapters`() = runBlocking {
        val complete = job("complete", 2, TranslationJobState.FAILED)
        val partial = job("partial", 3, TranslationJobState.PARTIAL)
        val (graph, repository, manager) = setup(listOf(complete, partial))
        coEvery { repository.chapterCoverage(2) } returns TranslationCoverage(TranslationCoverageState.COMPLETED, 8, 8)
        coEvery { repository.chapterCoverage(3) } returns TranslationCoverage(TranslationCoverageState.PARTIAL, 1, 8)
        coEvery { manager.retry(partial.id, null, false) } returns Unit

        assertTrue(activateTranslationControl(graph, 1, listOf(2, 3, 3)))

        coVerify(exactly = 1) { manager.retry(partial.id, null, false) }
        coVerify(exactly = 0) { manager.retry(complete.id, any(), any()) }
        coVerify(exactly = 0) { manager.retry(any(), any(), true) }
        coVerify(exactly = 0) { manager.enqueueSelected(any(), any()) }
    }

    @Test
    fun `active mixed selection opens queue without starting another chapter`() = runBlocking {
        val active = job("active", 2, TranslationJobState.TRANSLATING)
        val (graph, repository, manager) = setup(listOf(active))
        coEvery { repository.chapterCoverage(2) } returns TranslationCoverage(TranslationCoverageState.PARTIAL, 1, 8)
        coEvery { repository.chapterCoverage(3) } returns
            TranslationCoverage(TranslationCoverageState.UNTRANSLATED, 0, 8)

        assertTrue(activateTranslationControl(graph, 1, listOf(2, 3)))

        coVerify(exactly = 0) { manager.retry(any(), any(), any()) }
        coVerify(exactly = 0) { manager.enqueueSelected(any(), any()) }
    }

    @Test
    fun `gesture deduplicates chapter identities before enqueue`() = runBlocking {
        val (graph, repository, manager) = setup(emptyList())
        coEvery { repository.chapterCoverage(2) } returns
            TranslationCoverage(TranslationCoverageState.UNTRANSLATED, 0, 8)
        coEvery { manager.enqueueSelected(1, listOf(2)) } returns Unit

        assertTrue(activateTranslationControl(graph, 1, listOf(2, 2, 2)))

        coVerify(exactly = 1) { manager.enqueueSelected(1, listOf(2)) }
    }

    @Test
    fun `later gesture rechecks durable completed coverage before retry`() = runBlocking {
        val partial = job("partial", 2, TranslationJobState.PARTIAL)
        val (graph, repository, manager) = setup(listOf(partial))
        var coverage = TranslationCoverage(TranslationCoverageState.PARTIAL, 1, 2)
        coEvery { repository.chapterCoverage(2) } answers { coverage }
        coEvery { manager.retry(partial.id, null, false) } answers {
            coverage = TranslationCoverage(TranslationCoverageState.COMPLETED, 2, 2)
        }

        assertTrue(activateTranslationControl(graph, 1, listOf(2)))
        assertTrue(activateTranslationControl(graph, 1, listOf(2)))

        coVerify(exactly = 1) { manager.retry(partial.id, null, false) }
        coVerify(exactly = 0) { manager.enqueueSelected(any(), any()) }
    }

    @Test
    fun `structured mode chapter action opens import without queue or paid retry`() = runBlocking {
        val (graph, repository, manager) = setup(emptyList())
        val preferences = mockk<TranslationPreferences>()
        every { graph.translationPreferences } returns preferences
        every { preferences.effectiveSettings(1) } returns TranslationSettings(mode = TranslationMode.STRUCTURED_FILES)
        coEvery { repository.chapterCoverage(2) } returns
            TranslationCoverage(TranslationCoverageState.UNTRANSLATED, 0, null)

        assertTrue(translationControlDestination(graph, 1, listOf(2)) is TranslationStructuredImportScreen)
        coVerify(exactly = 0) { manager.retry(any(), any(), any()) }
        coVerify(exactly = 0) { manager.enqueueSelected(any(), any()) }
    }

    @Test
    fun `old file job continues import even after current settings select a provider mode`() = runBlocking {
        val previous = job("imported", 2, TranslationJobState.PARTIAL).copy(
            settings = TranslationSettings(mode = TranslationMode.STRUCTURED_FILES),
        )
        val (graph, repository, manager) = setup(listOf(previous))
        val preferences = mockk<TranslationPreferences>()
        every { graph.translationPreferences } returns preferences
        every { preferences.effectiveSettings(1) } returns TranslationSettings()
        coEvery { repository.chapterCoverage(2) } returns TranslationCoverage(TranslationCoverageState.PARTIAL, 1, 8)

        assertTrue(translationControlDestination(graph, 1, listOf(2)) is TranslationStructuredImportScreen)
        coVerify(exactly = 0) { manager.retry(any(), any(), any()) }
        coVerify(exactly = 0) { manager.enqueueSelected(any(), any()) }
    }

    @Test
    fun `completed imported chapter opens saved results without requiring credentials`() = runBlocking {
        val previous = job("imported", 2, TranslationJobState.COMPLETED).copy(
            settings = TranslationSettings(mode = TranslationMode.STRUCTURED_FILES),
        )
        val (graph, repository, manager) = setup(listOf(previous))
        coEvery { repository.chapterCoverage(2) } returns TranslationCoverage(TranslationCoverageState.COMPLETED, 8, 8)

        assertTrue(translationControlDestination(graph, 1, listOf(2)) is TranslationScreen)
        coVerify(exactly = 0) { manager.retry(any(), any(), any()) }
        coVerify(exactly = 0) { manager.enqueueSelected(any(), any()) }
    }

    private fun job(id: String, chapter: Long, state: TranslationJobState) = TranslationJob(
        id,
        1,
        chapter,
        "Series",
        "Chapter $chapter",
        TranslationSettings(),
        state = state,
    )

    private fun setup(history: List<TranslationJob>): Triple<AppGraph, TranslationRepository, TranslationManager> {
        val graph = mockk<AppGraph>()
        val repository = mockk<TranslationRepository>()
        val manager = mockk<TranslationManager>()
        every { graph.translationRepository } returns repository
        every { graph.translationManager } returns manager
        coEvery { repository.jobs() } returns history
        coEvery { repository.reviewSummaries(any()) } returns emptyList()
        return Triple(graph, repository, manager)
    }
}
