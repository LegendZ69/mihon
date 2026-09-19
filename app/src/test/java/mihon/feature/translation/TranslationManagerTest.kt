package mihon.feature.translation

import android.content.Context
import eu.kanade.tachiyomi.util.system.NetworkState
import eu.kanade.tachiyomi.util.system.activeNetworkState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mihon.feature.translation.ocr.PaddleOcrEngine
import mihon.feature.translation.provider.TranslationProviderGateway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.model.GeometryRecoverySettings
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OcrSettings
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.ProviderCapabilities
import tachiyomi.domain.translation.model.QualityReviewCheckpoint
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.QualityReviewResponse
import tachiyomi.domain.translation.model.QualityReviewSettings
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationBatch
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationPromptPair
import tachiyomi.domain.translation.model.TranslationPrompts
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationResponse
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File

class TranslationManagerTest {
    @TempDir
    lateinit var directory: File
    private val context = mockk<Context>()
    private val provider = mockk<TranslationProviderGateway>()
    private val repository = MemoryRepository()
    private val preferences = mockk<TranslationPreferences>()
    private val preferenceValues = MutableStateFlow(TranslationSettings())
    private val ocr = mockk<PaddleOcrEngine>()
    private val imageSource = mockk<TranslationImageSource>()
    private val getChapters = mockk<GetChaptersByMangaId>()
    private lateinit var manager: TranslationManager

    @BeforeEach
    fun setup() {
        mockkStatic("eu.kanade.tachiyomi.util.system.NetworkStateTrackerKt")
        every { context.activeNetworkState() } returns NetworkState(true, true, true)
        every { context.noBackupFilesDir } returns directory
        mockkObject(TranslationWorker.Companion)
        every { TranslationWorker.start(any()) } returns Unit
        every { preferences.settings } returns preferenceValues
        every { preferences.effectiveSettings(any()) } answers { preferenceValues.value }
        coEvery { ocr.release() } returns Unit
        coEvery { imageSource.sourceLanguage(any()) } returns null
        every { provider.capabilities(any()) } returns
            ProviderCapabilities("test", "test", "test", 100, 100000, 10000, 10000)
        coEvery { provider.clearCheckpoints(any()) } returns Unit
        coEvery { provider.resumeGeometryCorrections(any()) } returns Unit
        coEvery { provider.countTokens(any()) } returns 1L
        coEvery { provider.translate(any()) } coAnswers { response(firstArg()) }
        manager = TranslationManager(context, preferences, repository, provider, imageSource, ocr, getChapters, mockk())
    }

    @AfterEach
    fun cleanup() = unmockkAll()

    @Test
    fun `structured files refuses enqueue and automatic chapter work`() = runTest {
        val config =
            TranslationSettings(mode = TranslationMode.STRUCTURED_FILES, autoTranslate = true, chaptersAhead = 5)
        preferenceValues.value = config
        val manga = Manga.create().copy(id = 1, title = "Import series")
        val chapter = Chapter.create().copy(id = 2, mangaId = 1)
        coEvery { getChapters.await(any(), any()) } returns listOf(chapter)
        assertTrue(runCatching { manager.enqueue(manga, listOf(chapter), config) }.isFailure)
        manager.onChapterOpened(manga, chapter.id)
        assertTrue(repository.jobs().isEmpty())
        verify(exactly = 0) { TranslationWorker.start(any()) }
        coVerify(exactly = 0) { provider.translate(any()) }
        coVerify(exactly = 0) { imageSource.acquire(any()) }
    }

    @Test
    fun `stale structured queue checkpoints never acquire or translate missing pages`() = runTest {
        val job = seed(2, TranslationMode.STRUCTURED_FILES)
        val saved = TranslationPageResult("0", "hash0", 100, 100, listOf(textRegion()), revision = 42)
        repository.saveResult(job.id, saved)
        manager.runQueue()
        assertEquals(listOf(saved), repository.results(job.id))
        assertEquals(TranslationJobState.PAUSED, repository.jobs().single().state)
        assertEquals("Awaiting imported pages", repository.jobs().single().message)
        coVerify(exactly = 0) { provider.translate(any()) }
        coVerify(exactly = 0) { provider.countTokens(any()) }
        coVerify(exactly = 0) { imageSource.acquire(any()) }
    }

    @Test
    fun `structured resume and retry never discard completed pages or start work`() = runTest {
        val job = seed(2, TranslationMode.STRUCTURED_FILES).copy(state = TranslationJobState.PAUSED)
        repository.saveJob(job)
        val saved = TranslationPageResult("0", "hash0", 100, 100, listOf(textRegion()), revision = 42)
        repository.saveResult(job.id, saved)
        manager.resume(job.id)
        manager.retry(job.id, force = true)
        assertEquals(listOf(saved), repository.results(job.id))
        assertEquals(TranslationJobState.PAUSED, repository.jobs().single().state)
        verify(exactly = 0) { TranslationWorker.start(any()) }
        coVerify(exactly = 0) { provider.clearCheckpoints(any()) }
        coVerify(exactly = 0) { provider.resumeGeometryCorrections(any()) }
    }

    @Test
    fun `explicit imported page review snapshots current provider and prompts across manager restart`() = runTest {
        val job = seed(2, TranslationMode.STRUCTURED_FILES).copy(state = TranslationJobState.PAUSED)
        repository.saveJob(job)
        val saved = TranslationPageResult("0", "hash0", 100, 100, listOf(textRegion()), revision = 42)
        repository.saveResult(job.id, saved)
        val chosen = TranslationSettings(
            mode = TranslationMode.STRUCTURED_FILES,
            provider = job.settings.provider.copy(model = "chosen-model", credentialId = "chosen-credential"),
            ocr = OcrSettings(pipeline = OcrPipeline.PADDLE),
            sourceLanguage = "ja",
            targetLanguage = "fr",
            prompts = TranslationPrompts(
                qualityReview = TranslationPromptPair("Pinned review system", "Pinned {{target_language}} review"),
            ),
        )
        preferenceValues.value = chosen
        manager.reviewPages(job.id, setOf("0"))
        val reserved = repository.reviews(job.id).single()
        assertEquals(chosen.copy(mode = TranslationMode.VERTEX), reserved.executionSettings)
        assertEquals(chosen.prompts.qualityReview, reserved.prompts)
        preferenceValues.value = chosen.copy(
            provider = chosen.provider.copy(model = "later-model", credentialId = "later-credential"),
            prompts = TranslationPrompts(qualityReview = TranslationPromptPair("Later system", "Later user")),
        )
        manager = TranslationManager(context, preferences, repository, provider, imageSource, ocr, getChapters, mockk())
        manager.reviewPages(job.id, setOf("0"))
        coEvery { provider.review(any()) } coAnswers {
            val request = firstArg<QualityReviewRequest>()
            assertEquals(chosen.provider, request.settings.provider)
            assertEquals(chosen.prompts.qualityReview, request.settings.prompts.qualityReview)
            assertEquals(TranslationMode.VERTEX, request.settings.mode)
            assertEquals(OcrPipeline.PADDLE, request.settings.ocr.pipeline)
            QualityReviewResponse(request.baseline, emptyList())
        }
        manager.runQueue()
        assertEquals(listOf(saved), repository.results(job.id))
        assertEquals(job.settings, repository.jobs().single().settings)
        assertEquals(TranslationJobState.PAUSED, repository.jobs().single().state)
        assertEquals(1, repository.reviews(job.id).single().attempts.size)
        coVerify(exactly = 1) { provider.review(any()) }
        coVerify(exactly = 0) { provider.translate(any()) }
        coVerify(exactly = 0) { imageSource.acquire(any()) }
    }

    @Test
    fun `region retry uses saved translation prompts after global prompts change`() = runTest {
        val seeded = seed(1)
        val job = seeded.copy(
            state = TranslationJobState.COMPLETED,
            settings = seeded.settings.copy(
                prompts = TranslationPrompts(translation = TranslationPromptPair("Original system", "Original user")),
            ),
        )
        repository.saveJob(job)
        val baseline = TranslationPageResult("0", "hash0", 100, 100, listOf(textRegion()), revision = 42)
        repository.saveResult(job.id, baseline)
        preferenceValues.value = preferenceValues.value.copy(
            prompts = TranslationPrompts(translation = TranslationPromptPair("Changed system", "Changed user")),
        )
        coEvery { provider.translate(any()) } coAnswers {
            val request = firstArg<TranslationRequest>()
            assertEquals(job.settings.prompts.translation, request.settings.prompts.translation)
            assertEquals(OcrPipeline.PADDLE, request.settings.ocr.pipeline)
            assertEquals(listOf("region"), request.ocr.single().regions.map { it.id })
            TranslationResponse(
                listOf(
                    baseline.copy(
                        regions = baseline.regions.map {
                            it.copy(translatedText = "New translation")
                        },
                    ),
                ),
            )
        }
        manager.retryRegion(job.id, "0", "region")
        coVerify(exactly = 1) { provider.translate(any()) }
        coVerify(exactly = 0) { provider.review(any()) }
        assertEquals(job.settings, repository.jobs().single().settings)
    }

    @Test
    fun `halving saves valid partial pages and only retries unresolved images`() =
        runTest {
            val job = seed(3, TranslationMode.HALVING)
            val calls = mutableListOf<List<String>>()
            coEvery { provider.translate(any()) } coAnswers {
                val request = firstArg<TranslationRequest>()
                calls += request.images.map { it.id }
                val result = response(request)
                if (request.images.size == 3) result.copy(pages = result.pages.take(1)) else result
            }
            manager.runQueue()
            assertEquals(listOf(listOf("0", "1", "2"), listOf("1"), listOf("2")), calls)
            assertEquals(3, repository.results(job.id).size)
            assertEquals(TranslationJobState.COMPLETED, repository.jobs().single().state)
        }

    @Test
    fun `completed queue job records completion time after provider work`() =
        runTest {
            seed(1)
            var providerFinishedAt = 0L
            coEvery { provider.translate(any()) } coAnswers {
                // Queue timestamps use wall time, independently of the coroutine test scheduler.
                Thread.sleep(20)
                providerFinishedAt = System.currentTimeMillis()
                response(firstArg())
            }

            manager.runQueue()

            val completed = repository.jobs().single()
            assertEquals(TranslationJobState.COMPLETED, completed.state)
            assertTrue(completed.updatedAt >= providerFinishedAt, "Elapsed time must include provider execution")
        }

    @Test
    fun `partial queue job records completion time while preserving successful pages`() =
        runTest {
            val job = seed(3, TranslationMode.MAX)
            var providerFinishedAt = 0L
            coEvery { provider.translate(any()) } coAnswers {
                Thread.sleep(20)
                providerFinishedAt = System.currentTimeMillis()
                response(firstArg()).let { it.copy(pages = it.pages.take(1)) }
            }

            manager.runQueue()

            val partial = repository.jobs().single()
            assertEquals(TranslationJobState.PARTIAL, partial.state)
            assertEquals(1, repository.results(job.id).size)
            assertTrue(partial.updatedAt >= providerFinishedAt, "Elapsed time must include incomplete provider work")
        }

    @Test
    fun `pause fences a response that finishes after cancellation`() =
        runTest {
            val job = seed(1)
            val responseGate = CompletableDeferred<Unit>()
            coEvery { provider.translate(any()) } coAnswers {
                val request = firstArg<TranslationRequest>()
                withContext(NonCancellable) { responseGate.await() }
                response(request)
            }
            val worker = launch { manager.runQueue() }
            runCurrent()
            manager.pause(job.id)
            responseGate.complete(Unit)
            advanceUntilIdle()
            worker.join()
            assertEquals(TranslationJobState.PAUSED, repository.jobs().single().state)
            assertTrue(repository.results(job.id).isEmpty())
            assertEquals("INTERRUPTED", repository.batches(job.id).single().state)
        }

    @Test
    fun `management preview joins cancelled work and resume invalidates confirmation`() =
        runTest {
            val job = seed(2)
            val saved = TranslationPageResult("0", "hash0", 100, 100, listOf(textRegion()), revision = 42)
            repository.saveResult(job.id, saved)
            val responseGate = CompletableDeferred<Unit>()
            coEvery { provider.translate(any()) } coAnswers {
                val request = firstArg<TranslationRequest>()
                withContext(NonCancellable) { responseGate.await() }
                response(request)
            }
            val worker = launch { manager.runQueue() }
            runCurrent()
            try {
                val preview = async { manager.checkpointForManagement(setOf(job.id)) }
                runCurrent()
                assertTrue(!preview.isCompleted, "Preview must wait for the cancelled transport's final checkpoint")
                responseGate.complete(Unit)
                advanceUntilIdle()
                worker.join()
                val checkpoint = preview.await()
                assertEquals(listOf(saved), repository.results(job.id))
                assertEquals(TranslationJobState.PAUSED, repository.jobs().single().state)
                assertEquals(listOf(saved), manager.withManagementCheckpoint(checkpoint) { repository.results(job.id) })
                manager.resume(job.id)
                var confirmed = false
                val error =
                    runCatching {
                        manager.withManagementCheckpoint(checkpoint) { confirmed = true }
                    }.exceptionOrNull()
                assertTrue(error is IllegalStateException)
                assertTrue(!confirmed)
                assertEquals(listOf(saved), repository.results(job.id))
                coVerify(exactly = 1) { provider.translate(any()) }
            } finally {
                responseGate.complete(Unit)
                worker.cancelAndJoin()
            }
        }

    @Test
    fun `force retry cannot accept the cancelled attempt result`() =
        runTest {
            val job = seed(1)
            val responseGate = CompletableDeferred<Unit>()
            var attempt = 0
            coEvery { provider.translate(any()) } coAnswers {
                val request = firstArg<TranslationRequest>()
                val revision = ++attempt
                if (revision == 1) withContext(NonCancellable) { responseGate.await() }
                response(request).let {
                    it.copy(pages = it.pages.map { page -> page.copy(revision = revision.toLong()) })
                }
            }
            val worker = launch { manager.runQueue() }
            runCurrent()
            val retry = launch { manager.retry(job.id, force = true) }
            runCurrent()
            responseGate.complete(Unit)
            advanceUntilIdle()
            retry.join()
            worker.join()
            manager.runQueue() // WorkManager may start a successor after the cancelled worker exits.
            coVerify(exactly = 1) { provider.clearCheckpoints(job.id) }
            assertEquals(2, attempt)
            assertEquals(2L, repository.results(job.id).single().revision)
            assertEquals(TranslationJobState.COMPLETED, repository.jobs().single().state)
        }

    @Test
    fun `fresh worker recovery closes orphaned running batch without resubmitting committed pages`() =
        runTest {
            val seeded = seed(2)
            val job = seeded.copy(state = TranslationJobState.TRANSLATING, completedImages = 1, imageCount = 2)
            repository.saveJob(job)
            val savedPage =
                TranslationPageResult(
                    "0",
                    "hash0",
                    100,
                    100,
                    listOf(textRegion().copy(correctedText = "saved correction", style = OverlayStyle())),
                    rawOcr = OcrPageResult("0", listOf(textRegion()), detectorModel = "retained-detector"),
                    revision = 77,
                )
            repository.saveResult(job.id, savedPage)
            val parent =
                TranslationBatch("split-parent", job.id, imageIds = listOf("0", "1"), state = "SPLIT", attempts = 2)
            val completed = TranslationBatch("committed-batch", job.id, parent.id, listOf("0"), "COMPLETED", 2)
            val orphaned = TranslationBatch("interrupted-batch", job.id, parent.id, listOf("1"), "RUNNING", 3)
            listOf(parent, completed, orphaned).forEach { repository.saveBatch(it) }
            val calls = mutableListOf<List<String>>()
            var orphanedStateAtDispatch: String? = null
            coEvery { provider.translate(any()) } coAnswers {
                val request = firstArg<TranslationRequest>()
                calls += request.images.map { it.id }
                orphanedStateAtDispatch = repository.batches(job.id).single { it.id == orphaned.id }.state
                response(request)
            }

            manager.runQueue()

            assertEquals(listOf(listOf("1")), calls)
            assertEquals("INTERRUPTED", orphanedStateAtDispatch, "Retire the old batch before replacement dispatch")
            assertEquals(TranslationJobState.COMPLETED, repository.jobs().single().state)
            assertEquals(2, repository.jobs().single().completedImages)
            assertEquals(job.settings, repository.jobs().single().settings)
            assertEquals(savedPage, repository.results(job.id).single { it.imageId == "0" })
            assertEquals(parent, repository.batches(job.id).single { it.id == parent.id })
            assertEquals(completed, repository.batches(job.id).single { it.id == completed.id })
            val recovered = repository.batches(job.id).single { it.id == orphaned.id }
            assertEquals(
                "INTERRUPTED",
                recovered.state,
                "Process death cannot leave a phantom running batch after completion",
            )
            assertEquals(orphaned.attempts, recovered.attempts)
            assertEquals(orphaned.parentId, recovered.parentId)
            assertEquals(orphaned.imageIds, recovered.imageIds)
        }

    @Test
    fun `fresh worker closes stale running history on an already completed job without provider work`() =
        runTest {
            val seeded = seed(1)
            val job = seeded.copy(state = TranslationJobState.COMPLETED, completedImages = 1, imageCount = 1)
            repository.saveJob(job)
            val page = TranslationPageResult("0", "hash0", 100, 100, listOf(textRegion()), revision = 93)
            repository.saveResult(job.id, page)
            val orphaned = TranslationBatch("orphaned", job.id, "old-parent", listOf("0"), "RUNNING", 4)
            val historical = TranslationBatch("failed", job.id, imageIds = listOf("0"), state = "FAILED", attempts = 2)
            repository.saveBatch(orphaned)
            repository.saveBatch(historical)

            manager.runQueue()
            val interrupted = repository.batches(job.id).single { it.id == orphaned.id }
            assertEquals("INTERRUPTED", interrupted.state)
            assertEquals(orphaned.attempts, interrupted.attempts)
            assertEquals(orphaned.parentId, interrupted.parentId)
            assertEquals(orphaned.imageIds, interrupted.imageIds)
            assertEquals(historical, repository.batches(job.id).single { it.id == historical.id })
            assertEquals(page, repository.results(job.id).single())
            assertEquals(job.settings, repository.jobs().single().settings)
            assertEquals(TranslationJobState.COMPLETED, repository.jobs().single().state)
            coVerify(exactly = 0) { provider.translate(any()) }
            coVerify(exactly = 0) { provider.review(any()) }
            coVerify(exactly = 0) { imageSource.acquire(any()) }
            manager.runQueue()
            assertEquals(interrupted, repository.batches(job.id).single { it.id == orphaned.id })
        }

    @Test
    fun `waiting jobs resume from saved images on the next worker attempt`() =
        runTest {
            val job = seed(1)
            repository.saveJob(job.copy(state = TranslationJobState.WAITING))
            manager.runQueue()
            assertEquals(TranslationJobState.COMPLETED, repository.jobs().single().state)
            coVerify(exactly = 1) { provider.translate(any()) }
        }

    @Test
    fun `per-job resume replaces a stalled active request without resubmitting its committed first page`() =
        runTest {
            val original = seed(2)
            val job =
                original.copy(
                    settings = original.settings.copy(concurrency = original.settings.concurrency.copy(images = 1)),
                )
            repository.saveJob(job)
            val preferencesBefore = preferenceValues.value
            val calls = mutableListOf<String>()
            var stalledRequestCancelled = false
            coEvery { provider.translate(any()) } coAnswers {
                val request = firstArg<TranslationRequest>()
                val imageId = request.images.single().id
                calls += imageId
                if (imageId == "1" && calls.count { it == "1" } == 1) {
                    try {
                        awaitCancellation()
                    } finally {
                        stalledRequestCancelled = true
                    }
                }
                response(request).let { result ->
                    result.copy(pages = result.pages.map { it.copy(regions = listOf(textRegion())) })
                }
            }
            val worker = launch { manager.runQueue() }
            try {
                runCurrent()
                assertEquals(listOf("0", "1"), calls)
                val preserved = repository.results(job.id).single()
                assertEquals("0", preserved.imageId)
                assertEquals(TranslationJobState.TRANSLATING, repository.jobs().single().state)

                manager.resume(job.id)
                advanceTimeBy(2_000)
                runCurrent()

                assertTrue(stalledRequestCancelled)
                assertTrue(worker.isCompleted, "Explicit Resume must not wait for the stalled provider to finish")
                assertEquals(listOf("0", "1", "1"), calls)
                assertEquals(preserved, repository.results(job.id).single { it.imageId == "0" })
                assertEquals(2, repository.results(job.id).size)
                assertEquals(TranslationJobState.COMPLETED, repository.jobs().single().state)
                assertEquals(job.settings, repository.jobs().single().settings)
                assertEquals(preferencesBefore, preferenceValues.value)
                assertEquals(1, repository.batches(job.id).count { it.state == "INTERRUPTED" })
                coVerify(exactly = 0) { provider.clearCheckpoints(any()) }
                coVerify(exactly = 0) { provider.review(any()) }
            } finally {
                worker.cancelAndJoin()
            }
        }

    @Test
    fun `global resume preserves a healthy active request while admitting paused work`() =
        runTest {
            val job = seed(1)
            val paused = job.copy(id = "paused", chapterId = 3, state = TranslationJobState.PAUSED)
            repository.saveJob(paused)
            repository.saveImages(paused.id, repository.images(job.id))
            val responseGate = CompletableDeferred<Unit>()
            var healthyRequestCancelled = false
            coEvery { provider.translate(any()) } coAnswers {
                val request = firstArg<TranslationRequest>()
                if (request.jobId == job.id) {
                    try {
                        responseGate.await()
                    } catch (error: CancellationException) {
                        healthyRequestCancelled = true
                        throw error
                    }
                }
                response(request)
            }
            val worker = launch { manager.runQueue() }
            try {
                runCurrent()
                assertEquals(TranslationJobState.TRANSLATING, repository.jobs().first { it.id == job.id }.state)

                val healthyBatch = repository.batches(job.id).single()
                assertEquals("RUNNING", healthyBatch.state)
                manager.resume()
                runCurrent()
                assertEquals(healthyBatch, repository.batches(job.id).single())

                assertTrue(!healthyRequestCancelled)
                assertEquals(TranslationJobState.TRANSLATING, repository.jobs().first { it.id == job.id }.state)
                assertEquals(TranslationJobState.QUEUED, repository.jobs().first { it.id == paused.id }.state)
                coVerify(exactly = 1) { provider.translate(match { it.jobId == job.id }) }
                responseGate.complete(Unit)
                advanceUntilIdle()
                worker.join()
                assertTrue(!healthyRequestCancelled)
                assertTrue(repository.jobs().all { it.state == TranslationJobState.COMPLETED })
                coVerify(exactly = 1) { provider.translate(match { it.jobId == job.id }) }
                coVerify(exactly = 1) { provider.translate(match { it.jobId == paused.id }) }
            } finally {
                worker.cancelAndJoin()
            }
        }

    @Test
    fun `repeated per-job resume preserves selected review reservations and cannot obtain a third attempt`() =
        runTest {
            val job = seed(1)
            repository.saveJob(job.copy(state = TranslationJobState.COMPLETED))
            val preserved = TranslationPageResult("0", "hash0", 100, 100, listOf(textRegion()), revision = 7)
            repository.saveResult(job.id, preserved)
            coEvery { provider.review(any()) } coAnswers { awaitCancellation() }
            manager.reviewPages(job.id, setOf("0"))
            val worker = launch { manager.runQueue() }
            try {
                runCurrent()
                val first = repository.reviews(job.id).single()
                assertEquals(QualityReviewState.RUNNING, first.state)
                assertEquals(1, first.attempts.size)
                assertEquals(TranslationJobState.TRANSLATING, repository.jobs().single().state)

                manager.resume(job.id)
                advanceTimeBy(1_000)
                runCurrent()
                val second = repository.reviews(job.id).single()
                assertEquals(first.id, second.id)
                assertEquals(2, second.attempts.size)
                assertEquals(first.attempts.single(), second.attempts.first())
                assertEquals(first.beforeResult, second.beforeResult)
                assertEquals(TranslationJobState.COMPLETED, repository.jobs().single().reviewReturnState)
                assertEquals(listOf("0"), repository.jobs().single().reviewImageIds)

                manager.resume(job.id)
                advanceTimeBy(2_000)
                runCurrent()
                assertTrue(worker.isCompleted)
                val exhausted = repository.reviews(job.id).single()
                assertEquals(first.id, exhausted.id)
                assertEquals(second.attempts, exhausted.attempts)
                assertEquals(first.settings, exhausted.settings)
                assertEquals(QualityReviewState.NEEDS_REVIEW, exhausted.state)
                assertEquals(preserved, repository.results(job.id).single())
                assertEquals(TranslationJobState.COMPLETED, repository.jobs().single().state)
                assertEquals(job.settings, repository.jobs().single().settings)

                manager.resume(job.id)
                manager.runQueue()
                coVerify(exactly = 2) { provider.review(any()) }
                coVerify(exactly = 0) { provider.translate(any()) }
                coVerify(exactly = 0) { provider.clearCheckpoints(any()) }
            } finally {
                worker.cancelAndJoin()
            }
        }

    @Test
    fun `legacy wifi-only snapshot translates on connected mobile data`() =
        runTest {
            val job = seed(1)
            repository.saveJob(job.copy(settings = job.settings.copy(wifiOnly = true)))
            every { context.activeNetworkState() } returns NetworkState(true, true, false)
            manager.runQueue()
            assertEquals(TranslationJobState.COMPLETED, repository.jobs().single().state)
            assertEquals(1, repository.results(job.id).size)
            coVerify(exactly = 1) { provider.translate(any()) }
        }

    @Test
    fun `reordering preserves running work and keeps unspecified jobs afterward`() =
        runTest {
            val original = seed(1)
            repository.saveJob(original.copy(id = "second", state = TranslationJobState.TRANSLATING))
            repository.saveJob(original.copy(id = "third"))
            manager.reorder(listOf("third", "job"))
            val jobs = repository.jobs().sortedByDescending { it.priority }
            assertEquals(listOf("third", "job", "second"), jobs.map { it.id })
            assertEquals(TranslationJobState.TRANSLATING, jobs.last().state)
        }

    @Test
    fun `pause all preserves completed failed and partial outcomes`() =
        runTest {
            val original = seed(1)
            listOf(TranslationJobState.COMPLETED, TranslationJobState.PARTIAL, TranslationJobState.FAILED).forEach {
                repository.saveJob(original.copy(id = it.name, state = it))
            }
            manager.pause()
            val jobs = repository.jobs().associateBy { it.id }
            assertEquals(TranslationJobState.PAUSED, jobs.getValue("job").state)
            listOf(TranslationJobState.COMPLETED, TranslationJobState.PARTIAL, TranslationJobState.FAILED).forEach {
                assertEquals(it, jobs.getValue(it.name).state)
            }
        }

    @Test
    fun `mixed cancellation preserves completed history and cancels only unfinished work`() = runTest {
        val partial = seed(2).copy(
            state = TranslationJobState.PARTIAL,
            imageCount = 2,
            completedImages = 1,
            geometryRecoveryId = "partial-pass",
        )
        val complete = partial.copy(
            id = "finished",
            chapterId = 3,
            state = TranslationJobState.COMPLETED,
            imageCount = 1,
            geometryRecoveryId = "completed-pass",
        )
        repository.saveJob(partial)
        repository.saveJob(complete)
        val saved = TranslationPageResult("0", "hash0", 100, 100, listOf(textRegion()), revision = 42)
        repository.saveResult(partial.id, saved)
        repository.saveResult(complete.id, saved)
        repository.createReview(complete.id, "0", QualityReviewSettings())
        val reviews = repository.reviews(complete.id)

        listOf(complete.id, partial.id).forEach { manager.cancel(it) }

        val current = repository.jobs().associateBy { it.id }
        assertEquals(complete, current.getValue(complete.id))
        assertEquals(TranslationJobState.CANCELLED, current.getValue(partial.id).state)
        assertEquals(partial.geometryRecoveryId, current.getValue(partial.id).geometryRecoveryId)
        assertEquals(1, current.getValue(partial.id).completedImages)
        assertEquals(listOf(saved), repository.results(complete.id))
        assertEquals(listOf(saved), repository.results(partial.id))
        assertEquals(reviews, repository.reviews(complete.id))
        verify(exactly = 0) { TranslationWorker.start(any()) }
        coVerify(exactly = 0) { provider.clearCheckpoints(any()) }
        coVerify(exactly = 0) { provider.translate(any()) }
    }

    @Test
    fun `cancel interrupts a region request without relabelling its completed chapter`() = runTest {
        val complete = seed(1).copy(state = TranslationJobState.COMPLETED, imageCount = 1, completedImages = 1)
        repository.saveJob(complete)
        val region = textRegion()
        val saved = TranslationPageResult("0", "hash0", 100, 100, listOf(region), revision = 42)
        repository.saveResult(complete.id, saved)
        var cancelled = false
        coEvery { provider.translate(any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val retry = launch { manager.retryRegion(complete.id, "0", region.id) }
        runCurrent()

        manager.cancel(complete.id)
        advanceUntilIdle()

        assertTrue(retry.isCancelled)
        assertTrue(cancelled)
        assertEquals(TranslationJobState.COMPLETED, repository.jobs().single().state)
        assertEquals(listOf(saved), repository.results(complete.id))
        coVerify(exactly = 1) { provider.translate(any()) }
        coVerify(exactly = 0) { provider.clearCheckpoints(any()) }
    }

    @Test
    fun `pause aborts selected region request while preserving completed chapter`() =
        runTest {
            val job = seed(1)
            repository.saveJob(job.copy(state = TranslationJobState.COMPLETED))
            val region =
                TextRegion(
                    "region",
                    listOf(
                        TranslationPoint(1f, 1f),
                        TranslationPoint(90f, 1f),
                        TranslationPoint(90f, 90f),
                        TranslationPoint(1f, 90f),
                    ),
                    "original",
                    "saved",
                )
            repository.saveResult(job.id, TranslationPageResult("0", "hash0", 100, 100, listOf(region)))
            var cancelled = false
            coEvery { provider.translate(any()) } coAnswers {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            val retry = launch { manager.retryRegion(job.id, "0", "region") }
            runCurrent()
            manager.pause(job.id)
            advanceUntilIdle()
            assertTrue(retry.isCancelled)
            assertTrue(cancelled)
            assertEquals(TranslationJobState.COMPLETED, repository.jobs().single().state)
            assertEquals(
                "saved",
                repository
                    .results(job.id)
                    .single()
                    .regions
                    .single()
                    .translatedText,
            )
        }

    @Test
    fun `ignored sound effect retry preserves manual edits without changing the job or sending a request`() =
        runTest {
            val seeded = seed(1)
            val job =
                seeded.copy(
                    state = TranslationJobState.COMPLETED,
                    settings = seeded.settings.copy(contentPolicy = TranslationContentPolicy.Legacy),
                )
            repository.saveJob(job)
            val raw = textRegion().copy(
                type = "sound_effect",
                detectionConfidence = 0.8f,
                recognitionConfidence = 0.7f,
            )
            val page =
                TranslationPageResult(
                    "0",
                    "hash0",
                    100,
                    100,
                    listOf(
                        raw.copy(
                            correctedText = "manual source",
                            translatedText = "manual translation",
                            rotation = 90f,
                        ),
                    ),
                    rawOcr = OcrPageResult("0", listOf(raw)),
                    revision = 25,
                )
            repository.saveResult(job.id, page)
            coEvery { provider.translate(any()) } returns
                TranslationResponse(
                    listOf(page.copy(regions = page.regions.map { it.copy(translatedText = "unwanted replacement") })),
                )

            val error = runCatching { manager.retryRegion(job.id, "0", raw.id) }.exceptionOrNull()

            assertEquals(page, repository.results(job.id).single())
            assertEquals(job, repository.jobs().single())
            assertTrue(error?.message?.contains("sound effects", ignoreCase = true) == true)
            coVerify(exactly = 0) { provider.translate(any()) }
            coVerify(exactly = 0) { provider.review(any()) }
        }

    @Test
    fun `auto ahead follows reader order and reuses results across queue settings changes`() =
        runTest {
            val manga = Manga.create().copy(id = 1, title = "Series")
            val chapters = listOf(1L, 2L, 3L, 4L).map { Chapter.create().copy(id = it, mangaId = 1, sourceOrder = it) }
            coEvery { getChapters.await(1, true) } returns chapters
            preferenceValues.value = TranslationSettings(autoTranslate = true, chaptersAhead = 1)
            manager.onChapterOpened(manga, 2, listOf(2, 4, 1))
            assertEquals(listOf(2L, 4L), repository.jobs().map { it.chapterId })
            preferenceValues.value =
                preferenceValues.value.copy(chaptersAhead = 2, queueColors = mapOf("QUEUED" to 123L))
            manager.onChapterOpened(manga, 2, listOf(2, 4, 1))
            preferenceValues.value =
                preferenceValues.value.copy(
                    wifiOnly = false,
                    style = OverlayStyle(bold = true),
                    concurrency = preferenceValues.value.concurrency.copy(requests = 2),
                )
            manager.onChapterOpened(manga, 2, listOf(2, 4, 1))
            assertEquals(listOf(2L, 4L, 1L), repository.jobs().map { it.chapterId })
            verify(exactly = 2) { TranslationWorker.start(context) }
        }

    @Test
    fun `ignoring sound effects reuses a cached chapter without resubmission or result edits`() =
        runTest {
            val manga = Manga.create().copy(id = 1, title = "Series")
            val chapter = Chapter.create().copy(id = 2, mangaId = 1)
            val before =
                seed(1).let {
                    it.copy(
                        state = TranslationJobState.COMPLETED,
                        settings = it.settings.copy(contentPolicy = TranslationContentPolicy.Legacy),
                    )
                }
            repository.saveJob(before)
            val saved =
                TranslationPageResult(
                    "0",
                    "hash0",
                    100,
                    100,
                    listOf(textRegion().copy(type = "sound_effect")),
                    revision = 33,
                )
            repository.saveResult(before.id, saved)

            manager.enqueue(manga, listOf(chapter), before.settings.copy(contentPolicy = TranslationContentPolicy()))

            assertEquals(listOf(before), repository.jobs())
            assertEquals(listOf(saved), repository.results(before.id))
            verify(exactly = 0) { TranslationWorker.start(context) }
            coVerify(exactly = 0) { provider.translate(any()) }
        }

    @Test
    fun `explicit OCR language wins over automatic source language`() =
        runTest {
            val job = seed(1)
            repository.saveJob(
                job.copy(
                    settings = job.settings.copy(ocr = OcrSettings(pipeline = OcrPipeline.PADDLE, language = "ko")),
                ),
            )
            coEvery { ocr.recognize(any(), any(), any(), any()) } coAnswers
                { OcrPageResult(firstArg<TranslationImage>().id, emptyList()) }
            manager.runQueue()
            coVerify(exactly = 1) {
                ocr.recognize(
                    any(),
                    match { it.language == "ko" },
                    any(),
                    match {
                        it != null && it.jobId == job.id && it.imageId == "0" && !it.parentId.isNullOrBlank() &&
                            it.logs == job.settings.logs
                    },
                )
            }
        }

    @Test
    fun `single tall original can reach gateway bounded tile fallback in halving mode`() =
        runTest {
            seed(1, TranslationMode.HALVING)
            coEvery { provider.countTokens(any()) } returns 100_000L
            manager.runQueue()
            coVerify(exactly = 1) { provider.translate(any()) }
            assertEquals(TranslationJobState.COMPLETED, repository.jobs().single().state)
        }

    @Test
    fun `consecutive region retries preserve corrected transcription raw OCR and edited region metadata`() =
        runTest {
            val job = seed(1)
            repository.saveJob(job.copy(state = TranslationJobState.COMPLETED))
            val raw = textRegion().copy(detectionConfidence = 0.8f, recognitionConfidence = 0.7f)
            val region =
                raw.copy(
                    correctedText = "user-corrected transcription",
                    readingOrder = 3,
                    rotation = 11f,
                    aiConfidence = 0.6f,
                    style = OverlayStyle(bold = true, rotation = 17f),
                )
            val other = textRegion().copy(id = "other", translatedText = "untouched translation")
            val page =
                TranslationPageResult(
                    "0",
                    "hash0",
                    100,
                    100,
                    listOf(region, other),
                    rawOcr = OcrPageResult("0", listOf(raw)),
                )
            repository.saveResult(job.id, page)
            val requests = mutableListOf<TranslationRequest>()
            coEvery { provider.translate(any()) } coAnswers {
                val request = firstArg<TranslationRequest>()
                requests += request
                val translated =
                    region.copy(
                        sourceText = "provider transcription",
                        correctedText = "provider correction",
                        translatedText = if (requests.size == 1) "first translation" else "second translation",
                        points =
                        listOf(
                            TranslationPoint(2f, 2f),
                            TranslationPoint(80f, 2f),
                            TranslationPoint(80f, 80f),
                            TranslationPoint(2f, 80f),
                        ),
                        readingOrder = 0,
                        rotation = 0f,
                        detectionConfidence = null,
                        recognitionConfidence = null,
                        aiConfidence = if (requests.size == 1) 0.9f else null,
                        style = null,
                    )
                TranslationResponse(listOf(page.copy(regions = listOf(translated), rawOcr = null)))
            }

            repeat(2) { manager.retryRegion(job.id, "0", region.id) }

            assertEquals(
                listOf(region.correctedText, region.correctedText),
                requests.map {
                    it.ocr
                        .single()
                        .regions
                        .single()
                        .correctedText
                },
            )
            requests.forEach { request ->
                assertEquals(OcrPipeline.PADDLE, request.settings.ocr.pipeline)
                assertEquals(listOf("0"), request.images.map { it.id })
                val submitted =
                    request.ocr
                        .single()
                        .regions
                        .single()
                assertEquals(region.sourceText, submitted.sourceText)
                assertEquals(region.points, submitted.points)
                assertEquals(region.style, submitted.style)
            }
            val saved = repository.results(job.id).single()
            assertEquals(
                page.copy(
                    regions = listOf(region.copy(translatedText = "second translation", aiConfidence = 0.9f), other),
                    revision = saved.revision,
                ),
                saved,
            )
            assertEquals(TranslationJobState.COMPLETED, repository.jobs().single().state)
            coVerify(exactly = 2) { provider.translate(any()) }
            coVerify(exactly = 0) { ocr.recognize(any(), any(), any()) }
            coVerify(exactly = 0) { ocr.recognize(any(), any(), any(), any()) }
        }

    @Test
    fun `undo repair fences a delayed region retry and preserves every restored region`() =
        runTest {
            val job = seed(1)
            repository.saveJob(job.copy(state = TranslationJobState.COMPLETED))
            val first = textRegion().copy(translatedText = "Original first")
            val second = textRegion().copy(id = "other", translatedText = "Original second", readingOrder = 1)
            val baseline = TranslationPageResult("0", "hash0", 100, 100, listOf(first, second), revision = 10)
            repository.saveResult(job.id, baseline)
            val review = requireNotNull(repository.createReview(job.id, "0", QualityReviewSettings()))
            val repaired =
                baseline.copy(
                    regions = listOf(
                        first.copy(translatedText = "AI first"),
                        second.copy(translatedText = "AI second"),
                    ),
                    revision = 11,
                )
            assertTrue(
                repository.completeReview(
                    review.copy(state = QualityReviewState.REPAIRED, repairedRevision = 11),
                    repaired,
                ),
            )
            val responseGate = CompletableDeferred<Unit>()
            coEvery { provider.translate(any()) } coAnswers {
                val request = firstArg<TranslationRequest>()
                assertEquals(OcrPipeline.PADDLE, request.settings.ocr.pipeline)
                assertEquals(
                    listOf(first.id),
                    request.ocr
                        .single()
                        .regions
                        .map { it.id },
                )
                withContext(NonCancellable) { responseGate.await() }
                TranslationResponse(listOf(repaired.copy(regions = listOf(first.copy(translatedText = "Late retry")))))
            }
            val retry = launch { manager.retryRegion(job.id, "0", first.id) }
            runCurrent()
            coVerify(exactly = 1) { provider.translate(any()) }
            manager.undoRepair(job.id, "0")
            val undone = repository.results(job.id).single()
            assertEquals(baseline.copy(revision = undone.revision), undone)
            responseGate.complete(Unit)
            advanceUntilIdle()
            retry.join()
            assertTrue(retry.isCancelled)
            assertEquals(undone, repository.results(job.id).single())
            assertEquals(QualityReviewState.UNDONE, repository.reviews(job.id).single().state)
            assertEquals(TranslationJobState.COMPLETED, repository.jobs().single().state)
            coVerify(exactly = 0) { provider.review(any()) }
        }

    @Test
    fun `region retry rejects a newer repository revision without a manager generation change`() =
        runTest {
            val job = seed(1)
            repository.saveJob(job.copy(state = TranslationJobState.COMPLETED))
            val region = textRegion()
            val baseline = TranslationPageResult("0", "hash0", 100, 100, listOf(region), revision = 10)
            repository.saveResult(job.id, baseline)
            val responseGate = CompletableDeferred<Unit>()
            coEvery { provider.translate(any()) } coAnswers {
                responseGate.await()
                TranslationResponse(
                    listOf(baseline.copy(regions = listOf(region.copy(translatedText = "Stale retry")))),
                )
            }
            val retry = launch { manager.retryRegion(job.id, "0", region.id) }
            runCurrent()
            val changed = baseline.copy(regions = listOf(region.copy(translatedText = "Newer edit")), revision = 11)
            repository.saveResult(job.id, changed)
            responseGate.complete(Unit)
            advanceUntilIdle()
            retry.join()
            assertTrue(retry.isCancelled)
            assertEquals(changed, repository.results(job.id).single())
            coVerify(exactly = 1) { provider.translate(any()) }
        }

    @Test
    fun `manual retry and new worker share one global request cap`() =
        runTest {
            preferenceValues.value =
                preferenceValues.value.copy(concurrency = preferenceValues.value.concurrency.copy(requests = 1))
            val job = seed(1)
            repository.saveJob(job.copy(state = TranslationJobState.COMPLETED))
            val region = textRegion()
            repository.saveResult(job.id, TranslationPageResult("0", "hash0", 100, 100, listOf(region)))
            repository.saveJob(job.copy(id = "queued", chapterId = 3))
            repository.saveImages("queued", repository.images(job.id))
            coEvery { provider.translate(any()) } coAnswers {
                val request = firstArg<TranslationRequest>()
                if (request.jobId == job.id) awaitCancellation() else response(request)
            }
            val manual = launch { manager.retryRegion(job.id, "0", region.id) }
            runCurrent()
            val worker = launch { manager.runQueue() }
            runCurrent()
            coVerify(exactly = 0) { provider.countTokens(any()) }
            manager.pause(job.id)
            advanceUntilIdle()
            manual.join()
            worker.join()
            assertTrue(manual.isCancelled)
            assertEquals(TranslationJobState.COMPLETED, repository.jobs().first { it.id == "queued" }.state)
        }

    @Test
    fun `queued jobs explain whether series or chapter capacity is occupied`() =
        runTest {
            preferenceValues.value =
                preferenceValues.value.copy(
                    concurrency = preferenceValues.value.concurrency.copy(series = 1, chapters = 1),
                )
            val job = seed(1)
            repository.saveJob(job.copy(id = "same-series", chapterId = 3))
            repository.saveJob(job.copy(id = "next-series", mangaId = 2, chapterId = 4))
            coEvery { provider.translate(any()) } coAnswers { awaitCancellation() }
            val worker = launch { manager.runQueue() }
            runCurrent()
            val queued = repository.jobs().associateBy { it.id }
            assertEquals("Waiting for a chapter slot", queued.getValue("same-series").message)
            assertEquals("Waiting for a series slot", queued.getValue("next-series").message)
            manager.pause()
            advanceUntilIdle()
            worker.join()
        }

    @Test
    fun `queue startup prunes detailed events using global retention`() =
        runTest {
            val now = System.currentTimeMillis()
            repository.addEvent(
                TranslationEvent("old", "job", time = now - 14L * 24 * 60 * 60 * 1000, stage = "test", message = "old"),
            )
            repository.addEvent(TranslationEvent("recent", "job", time = now, stage = "test", message = "recent"))
            manager.runQueue()
            assertEquals(listOf("recent"), repository.observeEvents(null).first().map { it.id })
        }

    @Test
    fun `automatic OCR language uses installed source metadata when available`() =
        runTest {
            val job = seed(1)
            repository.saveJob(job.copy(settings = job.settings.copy(ocr = OcrSettings(pipeline = OcrPipeline.PADDLE))))
            coEvery { imageSource.sourceLanguage(1) } returns "ko"
            coEvery { ocr.recognize(any(), any(), any(), any()) } coAnswers
                { OcrPageResult(firstArg<TranslationImage>().id, emptyList()) }
            manager.runQueue()
            coVerify(exactly = 1) {
                ocr.recognize(
                    any(),
                    match { it.language == "ko" },
                    any(),
                    match {
                        it != null && it.jobId == job.id && it.imageId == "0" && !it.parentId.isNullOrBlank() &&
                            it.logs == job.settings.logs
                    },
                )
            }
        }

    @Test
    fun `new pages save before bounded review and cached pages do not get retrospective review`() =
        runTest {
            val job = seed(2)
            repository.saveJob(job.copy(settings = job.settings.copy(qualityReview = QualityReviewSettings())))
            val cached = TranslationPageResult("0", "hash0", 100, 100, emptyList())
            repository.saveResult(job.id, cached)
            coEvery { provider.review(any()) } coAnswers {
                val request = firstArg<QualityReviewRequest>()
                assertEquals("1", request.image.id)
                assertEquals(request.baseline, repository.results(job.id).first { it.imageId == "1" })
                QualityReviewResponse(request.baseline, emptyList())
            }
            manager.runQueue()
            assertEquals(cached, repository.results(job.id).first { it.imageId == "0" })
            assertEquals(QualityReviewState.PASSED, repository.reviews(job.id).single().state)
            coVerify(exactly = 1) { provider.translate(any()) }
            coVerify(exactly = 1) { provider.review(any()) }
        }

    @Test
    fun `selected review context follows image order and preserves page boundaries`() =
        runTest {
            val job = seed(3)
            repository.saveJob(job.copy(state = TranslationJobState.COMPLETED))
            for (index in listOf(2, 1, 0)) {
                repository.saveResult(
                    job.id,
                    TranslationPageResult(
                        index.toString(),
                        "hash$index",
                        100,
                        100,
                        listOf(textRegion().copy(sourceText = "Source $index", translatedText = "Translation $index")),
                    ),
                )
            }
            coEvery { provider.review(any()) } coAnswers {
                val request = firstArg<QualityReviewRequest>()
                assertEquals("1", request.image.id)
                assertEquals(
                    "Page 1 (image ID: 0)\nSource 0 → Translation 0\n\nPage 3 (image ID: 2)\nSource 2 → Translation 2",
                    request.context,
                )
                QualityReviewResponse(request.baseline, emptyList())
            }
            manager.reviewPages(job.id, setOf("1"))
            manager.runQueue()
            coVerify(exactly = 1) { provider.review(any()) }
            coVerify(exactly = 0) { provider.translate(any()) }
            assertEquals(TranslationJobState.COMPLETED, repository.jobs().single().state)
        }

    @Test
    fun `selected old page review pins current policy and excludes SFX from context`() =
        runTest {
            val seeded = seed(2)
            val job =
                seeded.copy(
                    state = TranslationJobState.COMPLETED,
                    settings = seeded.settings.copy(contentPolicy = TranslationContentPolicy.Legacy),
                )
            repository.saveJob(job)
            val contextPage =
                TranslationPageResult(
                    "0",
                    "hash0",
                    100,
                    100,
                    listOf(
                        textRegion().copy(id = "sign", type = "sign", sourceText = "立入禁止", translatedText = "No entry"),
                        textRegion().copy(
                            id = "sfx",
                            type = "sound_effect",
                            sourceText = "쿵!",
                            translatedText = "THUD!",
                            readingOrder = 1,
                        ),
                    ),
                )
            val selectedPage = TranslationPageResult("1", "hash1", 100, 100, listOf(textRegion()))
            repository.saveResult(job.id, contextPage)
            repository.saveResult(job.id, selectedPage)
            var observedRequest: QualityReviewRequest? = null
            coEvery { provider.review(any()) } coAnswers {
                firstArg<QualityReviewRequest>().also { observedRequest = it }.let {
                    QualityReviewResponse(it.baseline, emptyList())
                }
            }

            manager.reviewPages(job.id, setOf("1"))
            assertEquals(TranslationContentPolicy(), repository.reviews(job.id).single().contentPolicy)
            preferenceValues.value = preferenceValues.value.copy(contentPolicy = TranslationContentPolicy.Legacy)
            manager.reviewPages(job.id, setOf("1"))
            manager.runQueue()

            assertEquals(TranslationContentPolicy(), observedRequest?.settings?.contentPolicy)
            assertEquals("Page 1 (image ID: 0)\n立入禁止 → No entry", observedRequest?.context)
            assertEquals(listOf(contextPage, selectedPage), repository.results(job.id))
            assertEquals(job.settings, repository.jobs().single().settings)
            coVerify(exactly = 1) { provider.review(any()) }
            coVerify(exactly = 0) { provider.translate(any()) }
        }

    @Test
    fun `explicit selected review of partial chapter never translates unfinished pages`() =
        runTest {
            val job = seed(2)
            repository.saveJob(job.copy(state = TranslationJobState.PARTIAL))
            repository.saveResult(job.id, TranslationPageResult("0", "hash0", 100, 100, emptyList()))
            coEvery { provider.review(any()) } coAnswers {
                QualityReviewResponse(firstArg<QualityReviewRequest>().baseline, emptyList())
            }
            manager.reviewPages(job.id, setOf("0"))
            manager.reviewPages(job.id, setOf("0"))
            manager.runQueue()
            assertEquals(TranslationJobState.PARTIAL, repository.jobs().single().state)
            assertEquals(1, repository.results(job.id).size)
            coVerify(exactly = 0) { provider.translate(any()) }
            coVerify(exactly = 1) { provider.review(any()) }
        }

    private fun textRegion() =
        TextRegion(
            "region",
            listOf(
                TranslationPoint(1f, 1f),
                TranslationPoint(90f, 1f),
                TranslationPoint(90f, 90f),
                TranslationPoint(1f, 90f),
            ),
            "original",
            "saved",
        )

    @Test
    fun `explicit replacement uses displayed settings and only processes unfinished pages once`() =
        runTest {
            val original = seed(2)
            val saved =
                TranslationPageResult(
                    "0",
                    "hash0",
                    100,
                    100,
                    listOf(textRegion().copy(correctedText = "manual correction")),
                    rawOcr = OcrPageResult("0", listOf(textRegion())),
                    revision = 42,
                )
            repository.saveResult(original.id, saved)
            val oldReview = repository.createReview(original.id, "0", QualityReviewSettings())
            val chosen =
                original.settings.copy(
                    provider =
                    original.settings.provider.copy(
                        kind = tachiyomi.domain.translation.model.TranslationProviderKind.OPENAI,
                        baseUrl = "https://api.groq.com/openai/v1",
                        model = "openai/gpt-oss-120b",
                    ),
                    ocr = original.settings.ocr.copy(pipeline = OcrPipeline.PADDLE),
                )
            preferenceValues.value = chosen
            assertEquals(chosen, manager.replacementSettings(original.id))
            preferenceValues.value = chosen.copy(targetLanguage = "fr")
            every { provider.capabilities(any()) } returns
                ProviderCapabilities(
                    "text",
                    "test",
                    "test",
                    0,
                    0,
                    10000,
                    10000,
                    supportsVision = false,
                )
            coEvery { ocr.recognize(any(), any(), any(), any()) } coAnswers {
                OcrPageResult(firstArg<TranslationImage>().id, listOf(textRegion()))
            }

            val replacement = manager.replaceUnfinished(original.id, chosen)

            assertTrue(replacement.id != original.id, "Replacement must have a new immutable job identity")
            assertEquals(chosen, replacement.settings)
            assertEquals(original.id, replacement.replacesJobId)
            assertEquals(original.settings, repository.jobs().first { it.id == original.id }.settings)
            assertEquals(TranslationJobState.PAUSED, repository.jobs().first { it.id == original.id }.state)
            assertEquals(listOf(saved), repository.results(replacement.id))
            assertEquals(listOf(oldReview), repository.reviews(original.id))
            assertTrue(repository.reviews(replacement.id).isEmpty())
            assertEquals(replacement.id, manager.replaceUnfinished(original.id, chosen).id)
            coVerify(exactly = 0) { provider.translate(any()) }

            manager.runQueue()

            assertEquals(2, repository.jobs().size)
            assertEquals(saved, repository.results(replacement.id).first { it.imageId == "0" })
            assertEquals(listOf(saved), repository.results(original.id))
            coVerify(exactly = 1) {
                provider.translate(
                    match {
                        it.jobId == replacement.id &&
                            it.images.map { image -> image.id } == listOf("1") &&
                            it.ocr.map { ocr -> ocr.imageId } == listOf("1") && it.settings == chosen
                    },
                )
            }
            coVerify(exactly = 0) { provider.clearCheckpoints(any()) }
            coVerify(exactly = 0) { provider.review(any()) }
        }

    @Test
    fun `replacement rejects incompatible image pipeline before stopping the source job`() =
        runTest {
            val original = seed(1)
            every { provider.capabilities(any()) } returns
                ProviderCapabilities(
                    "text",
                    "test",
                    "test",
                    0,
                    0,
                    10000,
                    10000,
                    supportsVision = false,
                )

            val error = runCatching { manager.replaceUnfinished(original.id, original.settings) }.exceptionOrNull()

            assertTrue(
                error is IllegalArgumentException,
                "A text-only replacement must require pure Paddle before queueing",
            )
            assertEquals(listOf(original), repository.jobs())
            coVerify(exactly = 0) { provider.translate(any()) }
        }

    @Test
    fun `unlinked historical lineage cannot claim a live job replacement`() =
        runTest {
            val original = seed(2)
            val chosen = original.settings.copy(targetLanguage = "fr")
            val unlinked =
                original.copy(
                    id = "archived-history",
                    mangaId = -1,
                    chapterId = -1,
                    state = TranslationJobState.PAUSED,
                    settings = chosen,
                    replacesJobId = original.id,
                )
            repository.saveJob(unlinked)

            val replacement = manager.replaceUnfinished(original.id, chosen)

            assertTrue(
                replacement.id != unlinked.id && replacement.id != original.id,
                "Historical archive lineage must not stand in for a live replacement job",
            )
            assertEquals(original.mangaId, replacement.mangaId)
            assertEquals(original.chapterId, replacement.chapterId)
            assertEquals(unlinked, repository.jobs().first { it.id == unlinked.id })
            coVerify(exactly = 0) { provider.translate(any()) }
        }

    @Test
    fun `concurrent replacement confirmations wait for cancelled work and create one successor`() =
        runTest {
            val original = seed(2)
            val saved = TranslationPageResult("0", "hash0", 100, 100, listOf(textRegion()), revision = 42)
            repository.saveResult(original.id, saved)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { provider.translate(any()) } coAnswers {
                val request = firstArg<TranslationRequest>()
                if (request.jobId == original.id) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
                response(request)
            }
            val worker = launch { manager.runQueue() }
            entered.await()
            val settings = original.settings.copy(targetLanguage = "fr")
            val first = async { manager.replaceUnfinished(original.id, settings) }
            val second = async { manager.replaceUnfinished(original.id, settings) }
            try {
                runCurrent()
                assertTrue(
                    !first.isCompleted && !second.isCompleted,
                    "Neither confirmation may create work before the cancelled generation checkpoints",
                )
            } finally {
                release.complete(Unit)
            }
            val replacement = first.await()
            assertEquals(replacement.id, second.await().id)
            worker.join()
            manager.runQueue()

            assertEquals(2, repository.jobs().size)
            assertEquals(listOf(saved), repository.results(original.id))
            assertEquals(saved, repository.results(replacement.id).first { it.imageId == "0" })
            coVerify(exactly = 1) { provider.translate(match { it.jobId == original.id }) }
            coVerify(exactly = 1) { provider.translate(match { it.jobId == replacement.id }) }
        }

    @Test
    fun `incomplete original cache reacquires full chapter without resubmitting saved pages`() =
        runTest {
            val original = seed(3)
            val originals = repository.images(original.id)
            repository.saveJob(original.copy(imageCount = 3))
            repository.saveImages(original.id, originals.take(2))
            val saved = TranslationPageResult("0", "hash0", 100, 100, listOf(textRegion()), revision = 42)
            repository.saveResult(original.id, saved)
            coEvery { imageSource.acquire(any()) } returns originals

            manager.runQueue()

            coVerify(exactly = 1) { imageSource.acquire(match { it.id == original.id }) }
            assertEquals(3, repository.results(original.id).size)
            assertEquals(saved, repository.results(original.id).first { it.imageId == "0" })
            coVerify(exactly = 0) { provider.translate(match { request -> request.images.any { it.id == "0" } }) }
        }

    @Test
    fun `explicit retry snapshots only current geometry policy and preserves committed page history`() =
        runTest {
            val seeded = seed(2)
            val legacy =
                seeded.copy(
                    state = TranslationJobState.FAILED,
                    imageCount = 2,
                    completedImages = 1,
                    geometryRecoveryId = "exhausted-pass",
                    settings =
                    seeded.settings.copy(
                        provider = seeded.settings.provider.copy(
                            model = "legacy-model",
                            credentialId = "legacy-credential",
                        ),
                        ocr = seeded.settings.ocr.copy(cpuThreads = 2, language = "ja"),
                        sourceLanguage = "ja",
                        glossary = "Aki = Aki",
                        prompts = TranslationPrompts(
                            translation = TranslationPromptPair("Saved translation system", "Saved translation user"),
                            qualityReview = TranslationPromptPair("Saved review system", "Saved review user"),
                            geometryCorrection = TranslationPromptPair("Old correction system", "Old correction user"),
                        ),
                        geometryRecovery = GeometryRecoverySettings(enabled = false),
                    ),
                )
            repository.saveJob(legacy)
            val saved =
                TranslationPageResult(
                    "0",
                    "hash0",
                    100,
                    100,
                    listOf(
                        textRegion().copy(correctedText = "Manual correction", style = OverlayStyle(fontSize = 19f)),
                    ),
                    rawOcr = OcrPageResult("0", listOf(textRegion()), detectorModel = "retained-detector"),
                    revision = 77,
                )
            repository.saveResult(legacy.id, saved)
            preferenceValues.value =
                TranslationSettings(
                    provider = legacy.settings.provider.copy(
                        model = "new-global-model",
                        credentialId = "new-global-credential",
                    ),
                    ocr = OcrSettings(pipeline = OcrPipeline.PADDLE),
                    targetLanguage = "fr",
                    prompts = TranslationPrompts(
                        translation = TranslationPromptPair("New translation system", "New translation user"),
                        qualityReview = TranslationPromptPair("New review system", "New review user"),
                        geometryCorrection = TranslationPromptPair("New correction system", "New correction user"),
                    ),
                    geometryRecovery = GeometryRecoverySettings(enabled = true),
                )

            manager.retry(legacy.id)

            val retried = repository.jobs().single()
            assertEquals(TranslationJobState.QUEUED, retried.state)
            assertTrue(!retried.geometryRecoveryId.isNullOrBlank())
            assertNotEquals(legacy.geometryRecoveryId, retried.geometryRecoveryId)
            assertEquals(
                legacy.settings.copy(
                    geometryRecovery = preferenceValues.value.geometryRecovery,
                    prompts = legacy.settings.prompts.copy(
                        geometryCorrection = preferenceValues.value.prompts.geometryCorrection,
                    ),
                ),
                retried.settings,
            )
            assertEquals(listOf(saved), repository.results(legacy.id))
            manager.runQueue()
            assertEquals(TranslationJobState.COMPLETED, repository.jobs().single().state)
            assertEquals(retried.geometryRecoveryId, repository.jobs().single().geometryRecoveryId)
            assertEquals(saved, repository.results(legacy.id).single { it.imageId == "0" })
            coVerify(exactly = 1) {
                provider.translate(
                    match { request ->
                        request.images.map { it.id } == listOf("1") &&
                            request.geometryRecoveryId == retried.geometryRecoveryId
                    },
                )
            }
            coVerify(exactly = 0) { provider.translate(match { it.images.any { image -> image.id == "0" } }) }
            coVerify(exactly = 0) { provider.clearCheckpoints(any()) }
        }

    @Test
    fun `duplicate retry while queued or active keeps the same bounded geometry pass`() =
        runTest {
            val legacy = seed(2).copy(state = TranslationJobState.FAILED, geometryRecoveryId = "previous-pass")
            repository.saveJob(legacy)
            val saved = TranslationPageResult("0", "hash0", 100, 100, listOf(textRegion()), revision = 42)
            repository.saveResult(legacy.id, saved)
            manager.retry(legacy.id)
            val retried = repository.jobs().single()

            listOf(
                TranslationJobState.QUEUED,
                TranslationJobState.ACQUIRING,
                TranslationJobState.OCR,
                TranslationJobState.TRANSLATING,
                TranslationJobState.WAITING,
            ).forEach { state ->
                val before = retried.copy(state = state)
                repository.saveJob(before)
                manager.retry(legacy.id, mode = TranslationMode.MAX, captureDiagnostics = true)
                assertEquals(before, repository.jobs().single(), "Duplicate retry must not replace $state work")
                assertEquals(listOf(saved), repository.results(legacy.id))
            }
            verify(exactly = 1) { TranslationWorker.start(context) }
            coVerify(exactly = 0) { provider.clearCheckpoints(any()) }
            coVerify(exactly = 0) { provider.translate(any()) }
        }

    @Test
    fun `ordinary retry of completed work leaves the saved job and geometry pass untouched`() =
        runTest {
            val complete =
                seed(1).copy(
                    state = TranslationJobState.COMPLETED,
                    imageCount = 1,
                    completedImages = 1,
                    geometryRecoveryId = "completed-pass",
                )
            repository.saveJob(complete)
            val saved = TranslationPageResult("0", "hash0", 100, 100, listOf(textRegion()), revision = 42)
            repository.saveResult(complete.id, saved)

            manager.retry(complete.id)

            assertEquals(complete, repository.jobs().single())
            assertEquals(listOf(saved), repository.results(complete.id))
            verify(exactly = 0) { TranslationWorker.start(any()) }
            coVerify(exactly = 0) { provider.clearCheckpoints(any()) }
            coVerify(exactly = 0) { provider.translate(any()) }
        }

    @Test
    fun `explicit resume reopens correction checkpoint before starting worker without replacing its pass`() =
        runTest {
            val paused =
                seed(2).copy(
                    state = TranslationJobState.PAUSED,
                    imageCount = 2,
                    completedImages = 1,
                    geometryRecoveryId = "paused-pass-with-reserved-attempt",
                )
            repository.saveJob(paused)
            val saved = TranslationPageResult("0", "hash0", 100, 100, listOf(textRegion()), revision = 42)
            repository.saveResult(paused.id, saved)
            val calls = mutableListOf<String>()
            coEvery { provider.resumeGeometryCorrections(setOf(paused.id)) } coAnswers {
                assertEquals(paused, repository.jobs().single())
                calls += "resume checkpoint"
            }
            every { TranslationWorker.start(context) } answers { calls += "start worker" }

            manager.resume(paused.id)

            assertEquals(listOf("resume checkpoint", "start worker"), calls)
            val resumed = repository.jobs().single()
            assertEquals(TranslationJobState.QUEUED, resumed.state)
            assertEquals(paused.geometryRecoveryId, resumed.geometryRecoveryId)
            assertEquals(paused.settings, resumed.settings)
            assertEquals(listOf(saved), repository.results(paused.id))
            coVerify(exactly = 1) { provider.resumeGeometryCorrections(setOf(paused.id)) }
            coVerify(exactly = 0) { provider.clearCheckpoints(any()) }
        }

    private suspend fun seed(
        count: Int,
        mode: TranslationMode = TranslationMode.VERTEX,
    ): TranslationJob {
        val job =
            TranslationJob(
                "job",
                1,
                2,
                "Series",
                "Chapter",
                TranslationSettings(
                    mode = mode,
                    qualityReview =
                    tachiyomi.domain.translation.model
                        .QualityReviewSettings(enabled = false),
                ),
            )
        repository.saveJob(job)
        repository.saveImages(
            job.id,
            (0 until count).map {
                val file = File(directory, "$it.image").apply { writeText("fixture $it") }
                TranslationImage(it.toString(), it, file.path, "image/png", 100, 100, "hash$it", file.length())
            },
        )
        return job
    }

    private fun response(request: TranslationRequest) =
        TranslationResponse(
            request.images.map {
                TranslationPageResult(it.id, it.contentHash, it.width, it.height, emptyList())
            },
        )
}

private class MemoryRepository : TranslationRepository {
    private val jobValues = MutableStateFlow<List<TranslationJob>>(emptyList())
    private val resultValues = MutableStateFlow<Map<String, List<TranslationPageResult>>>(emptyMap())
    private val imageValues = mutableMapOf<String, List<TranslationImage>>()
    private val batchValues = mutableMapOf<String, TranslationBatch>()
    private val events = MutableStateFlow<List<TranslationEvent>>(emptyList())

    override fun observeJobs() = jobValues

    override fun observeEvents(jobId: String?) =
        events.map { values ->
            values.filter {
                jobId == null ||
                    it.jobId == jobId
            }
        }

    override fun observeResults(chapterId: Long) =
        resultValues.map { values ->
            jobValues.value.filter { it.chapterId == chapterId }.flatMap { values[it.id].orEmpty() }
        }

    override suspend fun jobs() = jobValues.value

    override suspend fun saveJob(job: TranslationJob) {
        jobValues.value =
            jobValues.value.filterNot { it.id == job.id } + job
    }

    override suspend fun replaceUnfinishedJob(
        source: TranslationJob,
        replacement: TranslationJob,
    ): TranslationJob? {
        if (jobs().firstOrNull { it.id == source.id } != source) return null
        val images = images(source.id)
        val saved =
            results(source.id).filter { page ->
                images.any {
                    it.id == page.imageId && it.contentHash == page.imageHash && it.width == page.width &&
                        it.height == page.height
                }
            }
        val total = maxOf(source.imageCount, images.size)
        val created =
            replacement.copy(
                imageCount = total,
                completedImages = saved.size,
                state = if (total > 0 &&
                    saved.size == total
                ) {
                    TranslationJobState.COMPLETED
                } else {
                    TranslationJobState.QUEUED
                },
            )
        saveImages(created.id, images)
        saved.forEach { saveResult(created.id, it) }
        saveJob(created)
        return created
    }

    override suspend fun removeJob(id: String) {
        jobValues.value = jobValues.value.filterNot { it.id == id }
    }

    override suspend fun images(jobId: String) = imageValues[jobId].orEmpty()

    override suspend fun saveImages(
        jobId: String,
        images: List<TranslationImage>,
    ) {
        imageValues[jobId] = images
    }

    override suspend fun results(jobId: String) = resultValues.value[jobId].orEmpty()

    override suspend fun saveResult(
        jobId: String,
        result: TranslationPageResult,
    ) {
        resultValues.value =
            resultValues.value + (jobId to (results(jobId).filterNot { it.imageId == result.imageId } + result))
    }

    private val reviewValues = mutableMapOf<String, QualityReviewCheckpoint>()

    override suspend fun reviews(jobId: String?) = reviewValues.values.filter { jobId == null || it.jobId == jobId }

    override suspend fun saveNewResult(
        jobId: String,
        result: TranslationPageResult,
        review: QualityReviewSettings,
    ) {
        if (results(jobId).any { it.imageId == result.imageId }) return
        saveResult(jobId, result)
        if (review.enabled) createReview(jobId, result.imageId, review)
    }

    override suspend fun createReview(
        jobId: String,
        imageId: String,
        settings: QualityReviewSettings,
    ): QualityReviewCheckpoint? {
        if (reviews(jobId).any { it.imageId == imageId && it.state.pending }) return null
        val result = results(jobId).first { it.imageId == imageId }
        return QualityReviewCheckpoint(
            "review-$imageId",
            jobId,
            imageId,
            result.revision,
            result,
            settings,
            contentPolicy = jobs().first { it.id == jobId }.settings.contentPolicy,
        ).also {
            reviewValues[it.id] = it
        }
    }

    override suspend fun saveReview(review: QualityReviewCheckpoint): Boolean {
        if (results(review.jobId).first { it.imageId == review.imageId }.revision != review.sourceRevision) return false
        reviewValues[review.id] = review
        return true
    }

    override suspend fun reserveReviewAttempt(review: QualityReviewCheckpoint): Boolean {
        val stored = reviewValues.getValue(review.id)
        return review.attempts.size == stored.attempts.size + 1 && saveReview(review)
    }

    override suspend fun completeReview(
        review: QualityReviewCheckpoint,
        candidate: TranslationPageResult?,
    ): Boolean {
        if (!saveReview(review)) return false
        if (candidate != null) saveResult(review.jobId, candidate)
        return true
    }

    override suspend fun undoRepair(
        jobId: String,
        imageId: String,
    ): Boolean {
        val current = results(jobId).firstOrNull { it.imageId == imageId } ?: return false
        val review =
            reviews(jobId).firstOrNull {
                it.imageId == imageId && it.state == QualityReviewState.REPAIRED &&
                    it.repairedRevision == current.revision
            } ?: return false
        saveResult(jobId, review.beforeResult.copy(revision = current.revision + 1))
        reviewValues[review.id] = review.copy(state = QualityReviewState.UNDONE)
        return true
    }

    override suspend fun scheduleReviews(
        job: TranslationJob,
        imageIds: Set<String>,
        settings: QualityReviewSettings,
        contentPolicy: TranslationContentPolicy,
        prompts: tachiyomi.domain.translation.model.TranslationPromptPair,
        executionSettings: TranslationSettings?,
    ): Boolean {
        imageIds.forEach { imageId ->
            createReview(job.id, imageId, settings)?.let { created ->
                reviewValues[created.id] =
                    created.copy(
                        contentPolicy = contentPolicy,
                        prompts = prompts,
                        executionSettings = executionSettings,
                    )
            }
        }
        saveJob(job)
        return true
    }

    override suspend fun batches(jobId: String) = batchValues.values.filter { it.jobId == jobId }

    override suspend fun saveBatch(batch: TranslationBatch) {
        batchValues[batch.id] = batch
    }

    override suspend fun addEvent(event: TranslationEvent) {
        events.value += event
    }

    override suspend fun deleteEvents(before: Long) {
        events.value = events.value.filter { it.time >= before }
    }

    override suspend fun deleteResults(jobId: String) {
        resultValues.value = resultValues.value - jobId
    }
}
