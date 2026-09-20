package mihon.feature.translation

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.feature.translation.ocr.PaddleModelState
import mihon.feature.translation.ocr.PaddleModelStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.PaddleProfile
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationProgressUnit
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.TranslationStage

class TranslationNotificationSnapshotUpdatesTest {
    @Test
    fun `stopped worker cards follow durable cancellation and removal without adding unrelated jobs`() {
        val stopped = snapshot(TranslationJobState.PAUSED, saved = 1)
        val cancelled = stopped.jobs.single().copy(state = TranslationJobState.CANCELLED, message = "Cancelled")
        val unrelated = cancelled.copy(id = "unrelated", state = TranslationJobState.TRANSLATING)

        assertEquals(listOf(cancelled), stopped.withLatestJobs(listOf(unrelated, cancelled)).jobs)
        assertEquals(emptyList<TranslationJob>(), stopped.withLatestJobs(listOf(unrelated)).jobs)
    }

    @Test
    fun `durable cancellation bypasses pending progress after the worker stops`() = runTest {
        val stopped = snapshot(TranslationJobState.PAUSED, saved = 1)
        val durable = MutableStateFlow(stopped.jobs)
        val published = mutableListOf<Pair<Long, TranslationNotificationSnapshot>>()
        val collector = backgroundScope.launch {
            combine(flowOf(stopped), durable) { snapshot, jobs ->
                snapshot.withLatestJobs(jobs)
            }.notificationUpdates(clock = { testScheduler.currentTime }) { it.stateKey }.collect {
                published += testScheduler.currentTime to it
            }
        }
        runCurrent()
        advanceTimeBy(100)
        durable.value = listOf(stopped.jobs.single().copy(state = TranslationJobState.CANCELLED))
        runCurrent()
        assertEquals(listOf(0L, 100L), published.map { it.first })
        assertEquals(TranslationJobState.CANCELLED, published.last().second.jobs.single().state)
        advanceTimeBy(100)
        durable.value = emptyList()
        runCurrent()
        assertEquals(200L, published.last().first)
        assertTrue(published.last().second.jobs.isEmpty())
        collector.cancelAndJoin()
    }

    @Test
    fun `routine subprocess churn is bounded and final saved progress reaches both publishers`() = runTest {
        val acquire = operation("acquire", TranslationStage.ACQUISITION)
        val prepare = operation("prepare", TranslationStage.PREPROCESS)
        val combined = operation("page-1", TranslationStage.AI_OCR_TRANSLATION)
        val request = operation("page-1-http", TranslationStage.REQUEST)
        val validation = operation("page-1-validation", TranslationStage.VALIDATION)
        val save = operation("page-1-save", TranslationStage.SAVE).copy(unit = TranslationProgressUnit.PAGES)
        val nextCombined = operation("page-0", TranslationStage.AI_OCR_TRANSLATION)
        val nextRequest = operation("page-0-http", TranslationStage.REQUEST)
        val initial = snapshot(TranslationJobState.ACQUIRING, operations = listOf(acquire))
        val input = MutableStateFlow(initial)
        val center = MutableStateFlow(initial)
        val foreground = mutableListOf<Pair<Long, TranslationNotificationSnapshot>>()
        val cards = mutableListOf<Pair<Long, TranslationNotificationSnapshot>>()
        val worker = backgroundScope.launch {
            input.notificationUpdates(clock = { testScheduler.currentTime }) { it.stateKey }.collect {
                foreground += testScheduler.currentTime to it
                center.value = it
            }
        }
        val publisher = backgroundScope.launch {
            center.notificationUpdates(clock = { testScheduler.currentTime }) { it.stateKey }.collect {
                cards += testScheduler.currentTime to it
            }
        }
        runCurrent()
        val changes = listOf(
            snapshot(TranslationJobState.ACQUIRING, operations = listOf(acquire.complete())),
            snapshot(TranslationJobState.OCR, operations = listOf(prepare)),
            snapshot(TranslationJobState.OCR, operations = listOf(prepare.complete())),
            snapshot(operations = listOf(combined)),
            snapshot(operations = listOf(request)),
            snapshot(operations = listOf(request.complete())),
            snapshot(operations = listOf(combined.complete())),
            snapshot(operations = listOf(validation.complete())),
            snapshot(operations = listOf(save)),
            snapshot(saved = 1, operations = listOf(save.complete())),
            snapshot(saved = 1, operations = listOf(nextCombined)),
            snapshot(saved = 1, operations = listOf(nextRequest)),
        )
        val history = linkedMapOf(acquire.id to acquire)
        changes.forEach { value ->
            advanceTimeBy(10)
            value.operations.forEach { history[it.id] = it }
            val changedIds = value.operations.map { it.id }.toSet()
            input.value = value.copy(operations = value.operations + history.values.filter { it.id !in changedIds })
            runCurrent()
        }
        val final = input.value
        assertEquals(listOf(0L), foreground.map { it.first })
        assertEquals(listOf(0L), cards.map { it.first })
        advanceTimeBy(880)
        runCurrent()
        assertEquals(listOf(0L, 1_000L), foreground.map { it.first })
        assertEquals(listOf(0L, 1_000L), cards.map { it.first })
        assertEquals(final, foreground.last().second)
        assertEquals(final, cards.last().second)
        assertEquals(1, final.jobs.single().completedImages)
        assertEquals(
            TranslationStage.REQUEST,
            final.operations.first {
                it.state == TranslationOperationState.ACTIVE
            }.stage,
        )
        worker.cancelAndJoin()
        publisher.cancelAndJoin()
    }

    @Test
    fun `pause and chapter completion bypass pending routine progress`() = runTest {
        val initial = snapshot(operations = listOf(operation("page-1-http", TranslationStage.REQUEST)))
        val input = MutableStateFlow(initial)
        val published = mutableListOf<Pair<Long, TranslationNotificationSnapshot>>()
        val collector = backgroundScope.launch {
            input.notificationUpdates(clock = { testScheduler.currentTime }) { it.stateKey }.collect {
                published += testScheduler.currentTime to it
            }
        }
        runCurrent()
        advanceTimeBy(100)
        input.value = snapshot(saved = 1, operations = listOf(operation("page-0-http", TranslationStage.REQUEST)))
        runCurrent()
        advanceTimeBy(100)
        val paused = snapshot(TranslationJobState.PAUSED, saved = 1)
        input.value = paused
        runCurrent()
        assertEquals(listOf(0L to initial, 200L to paused), published)
        advanceTimeBy(100)
        val completed = snapshot(TranslationJobState.COMPLETED, saved = 2)
        input.value = completed
        runCurrent()
        assertEquals(300L to completed, published.last())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, published.size)
        collector.cancelAndJoin()
    }

    @Test
    fun `retry and failed request remain immediate while the chapter is active`() = runTest {
        val request = operation("page-1-http", TranslationStage.REQUEST)
        val initial = snapshot(operations = listOf(request))
        val input = MutableStateFlow(initial)
        val published = mutableListOf<Pair<Long, TranslationNotificationSnapshot>>()
        val collector = backgroundScope.launch {
            input.notificationUpdates(clock = { testScheduler.currentTime }) { it.stateKey }.collect {
                published += testScheduler.currentTime to it
            }
        }
        runCurrent()
        advanceTimeBy(100)
        val retry =
            snapshot(operations = listOf(request.copy(state = TranslationOperationState.RETRY, message = "HTTP 429")))
        input.value = retry
        runCurrent()
        assertEquals(100L to retry, published.last())
        advanceTimeBy(100)
        val failed =
            snapshot(operations = listOf(request.copy(state = TranslationOperationState.FAILED, message = "HTTP 401")))
        input.value = failed
        runCurrent()
        assertEquals(listOf(0L to initial, 100L to retry, 200L to failed), published)
        collector.cancelAndJoin()
    }

    @Test
    fun `reordering existing jobs alerts and model packs does not create an immediate update`() {
        val first = snapshot().jobs.single()
        val alert = operation("request-alert", TranslationStage.REQUEST).copy(state = TranslationOperationState.FAILED)
        val model = PaddleModelState(
            profile = PaddleProfile.SMALL,
            korean = false,
            detectorModel = "detector",
            recognizerModel = "recognizer",
            totalBytes = 100,
            status = PaddleModelStatus.DOWNLOADING,
            operationId = "small-download",
        )
        val original = snapshot().copy(
            jobs = listOf(first, first.copy(id = "other")),
            operations = listOf(alert, alert.copy(id = "other-alert", jobId = "other")),
            models = listOf(model, model.copy(profile = PaddleProfile.TINY, operationId = "tiny-download")),
        )
        val reordered = original.copy(
            jobs = original.jobs.reversed(),
            operations = original.operations.reversed(),
            models = original.models.reversed(),
        )
        assertEquals(original.stateKey, reordered.stateKey)
    }

    @Test
    fun `new retry attempt and recovery clearing remain immediate`() = runTest {
        val request = operation("request", TranslationStage.REQUEST).copy(
            state = TranslationOperationState.RETRY,
            attempt = 1,
            message = "HTTP 429",
        )
        val initial = snapshot(operations = listOf(request))
        val input = MutableStateFlow(initial)
        val published = mutableListOf<Pair<Long, TranslationNotificationSnapshot>>()
        val collector = backgroundScope.launch {
            input.notificationUpdates(clock = { testScheduler.currentTime }) { it.stateKey }.collect {
                published += testScheduler.currentTime to it
            }
        }
        runCurrent()
        advanceTimeBy(100)
        val nextAttempt = snapshot(operations = listOf(request.copy(attempt = 2)))
        input.value = nextAttempt
        runCurrent()
        assertEquals(100L to nextAttempt, published.last())
        advanceTimeBy(100)
        val recovered =
            snapshot(operations = listOf(request.copy(state = TranslationOperationState.ACTIVE, attempt = 2)))
        input.value = recovered
        runCurrent()
        assertEquals(listOf(0L to initial, 100L to nextAttempt, 200L to recovered), published)
        collector.cancelAndJoin()
    }

    private fun snapshot(
        state: TranslationJobState = TranslationJobState.TRANSLATING,
        saved: Int = 0,
        operations: List<TranslationOperation> = emptyList(),
    ) = TranslationNotificationSnapshot(
        jobs = listOf(
            TranslationJob(
                "chapter", 1, 1, "Series", "Chapter", TranslationSettings(),
                state = state,
                imageCount = 2,
                completedImages = saved,
                message = state.name.lowercase(),
                createdAt = 0,
                updatedAt = 0,
            ),
        ),
        operations = operations,
        cardLimit = 3,
    )

    private fun operation(id: String, stage: TranslationStage) = TranslationOperation(
        id = id,
        jobId = "chapter",
        stage = stage,
        state = TranslationOperationState.ACTIVE,
        total = 1,
        unit = TranslationProgressUnit.REQUESTS,
        updatedAt = 0,
    )

    private fun TranslationOperation.complete() = copy(state = TranslationOperationState.COMPLETED, completed = 1)
}
