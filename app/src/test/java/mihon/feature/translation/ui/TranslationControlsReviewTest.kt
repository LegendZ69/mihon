package mihon.feature.translation.ui

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.QualityReviewSummary
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.TranslationCoverage
import tachiyomi.domain.translation.service.TranslationCoverageState
import tachiyomi.domain.translation.service.TranslationRepository

class TranslationControlsReviewTest {
    @Test
    fun `visible chapter controls update when only review state changes`() = runBlocking {
        verifyReviewUpdate(listOf(2))
    }

    @Test
    fun `cached series controls update when only review state changes`() = runBlocking {
        verifyReviewUpdate(null)
    }

    private suspend fun verifyReviewUpdate(chapterIds: List<Long>?) = kotlinx.coroutines.coroutineScope {
        val repository = mockk<TranslationRepository>()
        val job = TranslationJob(
            "saved",
            1,
            2,
            "Series",
            "Chapter",
            TranslationSettings(),
            state = TranslationJobState.COMPLETED,
        )
        val review = QualityReviewSummary("review", job.id, "page", QualityReviewState.RUNNING, 10)
        val unrelated = review.copy(id = "other", jobId = "unrelated")
        val reviews = MutableStateFlow(listOf(review, unrelated))
        val coverage = MutableStateFlow(TranslationCoverage(TranslationCoverageState.COMPLETED, 1, 1))
        every { repository.observeJobs() } returns flowOf(listOf(job))
        every { repository.observeChapterCoverage(2) } returns coverage
        every { repository.observeReviewSummaries() } returns reviews
        coEvery { repository.chapterCoverage(2) } answers { coverage.value }
        coEvery { repository.reviewSummaries(job.id) } answers { reviews.value }
        val observed = Channel<TranslationControlSummary>(Channel.UNLIMITED)
        val collector = launch {
            observeTranslationControl(repository, listOf(1), chapterIds).collect { observed.send(it) }
        }
        try {
            val initial = withTimeout(5_000) { observed.receive() }.chapters.single()
            assertEquals(1, initial.pendingReviews)
            assertEquals(0, initial.incompleteReviews)
            reviews.value = listOf(review.copy(state = QualityReviewState.NEEDS_REVIEW, updatedAt = 20), unrelated)
            val updated = withTimeoutOrNull(2_000) { observed.receive() }
            assertNotNull(updated, "A review-only update must invalidate the visible control")
            assertEquals(0, updated!!.chapters.single().pendingReviews)
            assertEquals(1, updated.chapters.single().incompleteReviews)
            if (chapterIds != null) {
                coverage.value = TranslationCoverage(TranslationCoverageState.PARTIAL, 1, 2)
                val changedCoverage = withTimeout(2_000) { observed.receive() }
                assertEquals(2, changedCoverage.chapters.single().coverage.total)
            }
        } finally {
            collector.cancelAndJoin()
            observed.close()
        }
    }
}
