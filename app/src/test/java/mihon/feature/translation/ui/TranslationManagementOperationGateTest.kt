package mihon.feature.translation.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranslationManagementOperationGateTest {
    @Test
    fun `cancellation does not admit another operation before durable cleanup finishes`() = runTest {
        val gate = TranslationManagementOperationGate(this)
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val first = requireNotNull(
            gate.launch {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            },
        )
        runCurrent()
        first.cancel()
        runCurrent()
        assertTrue(cleanupStarted.isCompleted)
        assertTrue(!first.isActive && !first.isCompleted)
        var admitted: Job? = null
        try {
            admitted = gate.launch { awaitCancellation() }
            assertNull(admitted, "Durable cleanup must finish before a second management action is admitted")
        } finally {
            releaseCleanup.complete(Unit)
            admitted?.cancel()
            runCurrent()
            first.join()
            admitted?.join()
        }
    }

    @Test
    fun `an earlier cancelled operation cannot clear the handle of later work`() = runTest {
        val gate = TranslationManagementOperationGate(this)
        val releaseCleanup = CompletableDeferred<Unit>()
        val first = requireNotNull(
            gate.launch {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { releaseCleanup.await() }
                }
            },
        )
        runCurrent()
        first.cancel()
        runCurrent()
        val attempted = gate.launch { awaitCancellation() }
        releaseCleanup.complete(Unit)
        runCurrent()
        first.join()
        val next = attempted ?: requireNotNull(gate.launch { awaitCancellation() })
        runCurrent()
        try {
            assertTrue(next.isActive)
            assertSame(next, gate.running.value, "The current operation must retain its cancellation/action fence")
        } finally {
            next.cancelAndJoin()
        }
        assertNull(gate.running.value)
    }

    @Test
    fun `immediate completion and cancelled parents never leave a stale busy handle`() = runTest {
        val immediateParent = SupervisorJob()
        val cancelledParent = SupervisorJob().apply { cancel() }
        try {
            val immediate = TranslationManagementOperationGate(
                CoroutineScope(immediateParent + UnconfinedTestDispatcher(testScheduler)),
            )
            val cancelled = TranslationManagementOperationGate(
                CoroutineScope(cancelledParent + UnconfinedTestDispatcher(testScheduler)),
            )
            var calls = 0
            val completed = requireNotNull(immediate.launch { calls++ })
            val skipped = requireNotNull(cancelled.launch { calls++ })
            assertAll(
                { assertTrue(completed.isCompleted) },
                { assertTrue(skipped.isCompleted) },
                { assertNull(immediate.running.value, "Immediate completion must clear the assigned handle") },
                { assertNull(cancelled.running.value, "A cancelled parent must not retain an unstarted handle") },
                { assertEquals(1, calls, "Cancelled-scope work must never run") },
            )
        } finally {
            immediateParent.cancelAndJoin()
            cancelledParent.cancelAndJoin()
        }
    }
}
