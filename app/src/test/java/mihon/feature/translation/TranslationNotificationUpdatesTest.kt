package mihon.feature.translation

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranslationNotificationUpdatesTest {
    private data class Progress(val state: String, val saved: Int)

    @Test
    fun `last saved progress survives a sampling boundary after an immediate state change`() = runTest {
        val input = MutableStateFlow(Progress("acquiring", 0))
        val published = mutableListOf<Pair<Long, Progress>>()
        val collector = backgroundScope.launch {
            input.notificationUpdates(clock = { testScheduler.currentTime }) { it.state }.collect {
                published += testScheduler.currentTime to it
            }
        }
        runCurrent()
        advanceTimeBy(100)
        input.value = Progress("translating", 0)
        runCurrent()
        assertEquals(listOf(0L, 100L), published.map { it.first })
        advanceTimeBy(100)
        input.value = Progress("translating", 1)
        runCurrent()
        advanceTimeBy(899)
        runCurrent()
        assertEquals(0, published.last().second.saved)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1_100L to Progress("translating", 1), published.last())
        advanceTimeBy(3_900)
        runCurrent()
        assertEquals(3, published.size)
        collector.cancelAndJoin()
    }

    @Test
    fun `newer pending progress replaces older counts without exceeding the interval`() = runTest {
        val input = MutableStateFlow(Progress("translating", 0))
        val published = mutableListOf<Pair<Long, Int>>()
        val collector = backgroundScope.launch {
            input.notificationUpdates(clock = { testScheduler.currentTime }) { it.state }.collect {
                published += testScheduler.currentTime to it.saved
            }
        }
        runCurrent()
        for (count in 1..9) {
            advanceTimeBy(100)
            input.value = Progress("translating", count)
            runCurrent()
        }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(0L to 0, 1_000L to 9), published)
        collector.cancelAndJoin()
    }

    @Test
    fun `paused state replaces pending active progress immediately`() = runTest {
        val input = MutableStateFlow(Progress("translating", 0))
        val published = mutableListOf<Progress>()
        val collector = backgroundScope.launch {
            input.notificationUpdates(clock = { testScheduler.currentTime }) { it.state }.toList(published)
        }
        runCurrent()
        advanceTimeBy(100)
        input.value = Progress("translating", 1)
        runCurrent()
        advanceTimeBy(100)
        input.value = Progress("paused", 1)
        runCurrent()
        assertEquals(listOf(Progress("translating", 0), Progress("paused", 1)), published)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, published.size)
        collector.cancelAndJoin()
    }

    @Test
    fun `finite upstream delivers its final pending value and suppresses duplicates`() = runTest {
        val result = async {
            flow {
                emit(Progress("translating", 0))
                delay(100)
                emit(Progress("translating", 1))
                emit(Progress("translating", 1))
            }.notificationUpdates(clock = { testScheduler.currentTime }) { it.state }.toList()
        }
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(Progress("translating", 0), Progress("translating", 1)), result.await())
    }

    @Test
    fun `cancellation discards pending progress`() = runTest {
        val input = MutableStateFlow(Progress("translating", 0))
        val published = mutableListOf<Progress>()
        val collector = backgroundScope.launch {
            input.notificationUpdates(clock = { testScheduler.currentTime }) { it.state }.toList(published)
        }
        runCurrent()
        input.value = Progress("translating", 1)
        runCurrent()
        collector.cancelAndJoin()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(Progress("translating", 0)), published)
    }

    @Test
    fun `worker and notification center in sequence retain final page progress`() = runTest {
        val input = MutableStateFlow(Progress("acquiring", 0))
        val published = mutableListOf<Pair<Long, Progress>>()
        val collector = backgroundScope.launch {
            input.notificationUpdates(clock = { testScheduler.currentTime }) { it.state }
                .notificationUpdates(clock = { testScheduler.currentTime }) { it.state }.collect {
                    published += testScheduler.currentTime to it
                }
        }
        runCurrent()
        advanceTimeBy(100)
        input.value = Progress("translating", 0)
        runCurrent()
        advanceTimeBy(100)
        input.value = Progress("translating", 1)
        runCurrent()
        advanceTimeBy(900)
        runCurrent()
        assertEquals(1_100L to Progress("translating", 1), published.last())
        collector.cancelAndJoin()
    }

    @Test
    fun `new snapshots do not cancel an in-flight foreground publication`() = runTest {
        val input = MutableStateFlow(Progress("acquiring", 0))
        val finished = mutableListOf<Progress>()
        var publicationCancelled = false
        val collector = backgroundScope.launch {
            input.notificationUpdates(clock = { testScheduler.currentTime }) { it.state }.collect {
                try {
                    delay(600)
                    finished += it
                } finally {
                    if (it !in finished) publicationCancelled = true
                }
            }
        }
        runCurrent()
        advanceTimeBy(100)
        input.value = Progress("translating", 0)
        runCurrent()
        advanceTimeBy(100)
        input.value = Progress("paused", 1)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(publicationCancelled)
        assertEquals(listOf(Progress("acquiring", 0), Progress("paused", 1)), finished)
        assertTrue(collector.isActive)
        collector.cancelAndJoin()
    }

    @Test
    fun `progress interval starts after a slow publication completes`() = runTest {
        val input = MutableStateFlow(Progress("translating", 0))
        val completedPublications = mutableListOf<Pair<Long, Int>>()
        val collector = backgroundScope.launch {
            input.notificationUpdates(clock = { testScheduler.currentTime }) { it.state }.collect {
                if (it.saved == 0) delay(1_500)
                completedPublications += testScheduler.currentTime to it.saved
            }
        }
        runCurrent()
        advanceTimeBy(100)
        input.value = Progress("translating", 1)
        runCurrent()
        advanceTimeBy(1_400)
        runCurrent()
        assertEquals(listOf(1_500L to 0), completedPublications)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(1_500L to 0, 2_500L to 1), completedPublications)
        collector.cancelAndJoin()
    }
}
