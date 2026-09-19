package mihon.feature.translation.ui

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mihon.feature.translation.TranslationManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.TranslationRepository

class TranslationQueueGestureTest {
    @Test
    fun `a row that completes during its swipe opens saved results without resubmission`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val repository = mockk<TranslationRepository>()
            val manager = mockk<TranslationManager>()
            every { manager.repository } returns repository
            val shown = job(TranslationJobState.FAILED)
            coEvery { repository.jobs() } returns listOf(shown.copy(state = TranslationJobState.COMPLETED))
            val opened = mutableListOf<String>()
            val actions = queueJobActions(
                shown, listOf(shown), manager, { block -> launch { block() } },
                opened::add, {}, {}, {}, {}, {},
            )
            actions.first().onAction()
            runCurrent()
            assertEquals(listOf("chapter-job"), opened)
            coVerify(exactly = 0) { manager.retry(any(), any(), any()) }
            coVerify(exactly = 0) { manager.resume(any()) }
            coVerify(exactly = 0) { manager.pause(any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `unfinished retry never clears completed pages and paused work resumes`() = runTest {
        val repository = mockk<TranslationRepository>()
        val manager = mockk<TranslationManager>()
        every { manager.repository } returns repository
        coEvery { manager.retry(any(), any(), any()) } returns Unit
        coEvery { manager.resume(any()) } returns Unit
        val shown = job(TranslationJobState.PARTIAL)
        var current = shown
        coEvery { repository.jobs() } answers { listOf(current) }
        val actions = queueJobActions(
            shown, listOf(shown), manager, { block -> launch { block() } },
            {}, {}, {}, {}, {}, {},
        )
        actions.first().onAction()
        runCurrent()
        coVerify(exactly = 1) { manager.retry("chapter-job", null, false) }
        current = shown.copy(state = TranslationJobState.PAUSED)
        actions.first().onAction()
        runCurrent()
        coVerify(exactly = 1) { manager.resume("chapter-job") }
        coVerify(exactly = 1) { manager.retry(any(), any(), any()) }
    }

    @Test
    fun `structured file retry opens import and cannot dispatch provider work`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val repository = mockk<TranslationRepository>()
            val manager = mockk<TranslationManager>()
            every { manager.repository } returns repository
            val shown = job(TranslationJobState.PARTIAL).copy(
                settings = TranslationSettings(mode = TranslationMode.STRUCTURED_FILES),
            )
            coEvery { repository.jobs() } returns listOf(shown)
            val imports = mutableListOf<String>()
            val actions = queueJobActions(
                shown, listOf(shown), manager, { block -> launch { block() } },
                {}, {}, {}, {}, {}, {}, onImport = { imports += it.id },
            )
            assertEquals("Import remaining pages", actions.first().label)
            actions.first().onAction()
            runCurrent()
            assertEquals(listOf("chapter-job"), imports)
            assertEquals(false, actions.any { it.label.contains("Halving") })
            coVerify(exactly = 0) { manager.retry(any(), any(), any()) }
            coVerify(exactly = 0) { manager.resume(any()) }
            coVerify(exactly = 0) { manager.pause(any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun job(state: TranslationJobState) = TranslationJob(
        "chapter-job", 1, 2, "Series", "Chapter", TranslationSettings(), state = state,
        imageCount = 8, completedImages = 5,
    )
}
