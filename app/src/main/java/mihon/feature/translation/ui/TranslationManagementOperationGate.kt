package mihon.feature.translation.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Management admission includes cancelled operations until their durable cleanup has completed. */
internal class TranslationManagementOperationGate(private val scope: CoroutineScope) {
    private val lock = Any()
    private val mutableRunning = MutableStateFlow<Job?>(null)
    val running: StateFlow<Job?> = mutableRunning.asStateFlow()

    fun launch(block: suspend () -> Unit): Job? {
        val operation = synchronized(lock) {
            if (mutableRunning.value != null) return null
            // Assign before start: an immediate completion or cancelled parent must never leave a stale handle.
            scope.launch(start = CoroutineStart.LAZY) { block() }.also { job ->
                mutableRunning.value = job
                job.invokeOnCompletion {
                    synchronized(lock) {
                        if (mutableRunning.value === job) mutableRunning.value = null
                    }
                }
            }
        }
        operation.start()
        return operation
    }
}
