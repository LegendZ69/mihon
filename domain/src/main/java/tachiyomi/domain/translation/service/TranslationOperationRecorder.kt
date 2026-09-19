package tachiyomi.domain.translation.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState

class TranslationOperationRecorder(private val repository: TranslationRepository) {
    suspend fun <T> run(
        operation: TranslationOperation,
        completedUnits: ((T) -> Long)? = null,
        block: suspend () -> T,
    ): T {
        val started = System.currentTimeMillis()
        val active = operation.copy(state = TranslationOperationState.ACTIVE, startedAt = started, updatedAt = started)
        repository.saveOperation(active)
        event(active, "started")
        try {
            val result = block()
            val end = maxOf(started, System.currentTimeMillis())
            val measured = completedUnits?.invoke(result)?.also { require(it >= 0) }
            val completed = active.copy(
                state = TranslationOperationState.COMPLETED,
                completed = measured ?: operation.total ?: operation.completed,
                total = measured ?: operation.total,
                endedAt = end,
                updatedAt = end,
            )
            repository.saveOperation(completed)
            event(completed, "completed")
            return result
        } catch (error: Exception) {
            withContext(NonCancellable) {
                val end = maxOf(started, System.currentTimeMillis())
                val failed = active.copy(
                    state = if (error is CancellationException) {
                        TranslationOperationState.INTERRUPTED
                    } else {
                        TranslationOperationState.FAILED
                    },
                    endedAt = end,
                    updatedAt = end,
                    message = if (error is CancellationException) {
                        "Interrupted; saved pages remain available"
                    } else {
                        error.message ?: error.javaClass.simpleName
                    },
                )
                repository.saveOperation(failed)
                event(failed, "finished")
            }
            throw error
        }
    }

    private suspend fun event(operation: TranslationOperation, transition: String) {
        repository.addEvent(
            TranslationEvent(
                id = "${operation.id}:$transition", jobId = operation.jobId,
                batchId = operation.batchId, imageId = operation.imageId, operationId = operation.id,
                time = operation.updatedAt, stage = operation.stage.name, operationState = operation.state,
                level = when (operation.state) {
                    TranslationOperationState.FAILED -> "ERROR"
                    TranslationOperationState.INTERRUPTED -> "WARN"
                    else -> "INFO"
                },
                message = "${operation.stage.label}: ${operation.state.name.lowercase()}" +
                    (operation.message?.let { " · $it" } ?: ""),
                details = buildMap {
                    put("completed", operation.completed.toString())
                    operation.total?.let { put("total", it.toString()) }
                    put("unit", operation.unit.label)
                    operation.parentId?.let { put("parentOperationId", it) }
                    operation.reviewId?.let { put("reviewId", it) }
                    operation.endedAt?.let { end ->
                        operation.startedAt?.let { put("durationMillis", (end - it).toString()) }
                    }
                },
            ),
        )
    }
}
