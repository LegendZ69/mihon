package mihon.feature.translation.ocr

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import mihon.feature.translation.provider.DiagnosticRedactor
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationLogSettings
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationProgressUnit
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.service.TranslationRepository
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** Associates local work with the exact queue operation without changing the OCR result contract. */
data class PaddleOperationContext(
    val jobId: String,
    val parentId: String? = null,
    val imageId: String? = null,
    val logs: TranslationLogSettings? = null,
)

internal class PaddleOperationLog(
    private val repository: TranslationRepository?,
    val context: PaddleOperationContext,
) {
    suspend fun begin(
        stage: TranslationStage,
        parentId: String? = context.parentId,
        artifactId: String? = null,
        total: Long? = null,
        unit: TranslationProgressUnit = TranslationProgressUnit.STEPS,
        message: String? = null,
        details: Map<String, String> = emptyMap(),
    ): Handle {
        val now = System.currentTimeMillis()
        val operation = TranslationOperation(
            id = UUID.randomUUID().toString(),
            jobId = context.jobId,
            stage = stage,
            parentId = parentId,
            artifactId = artifactId,
            imageId = context.imageId,
            state = TranslationOperationState.ACTIVE,
            total = total,
            unit = unit,
            startedAt = now,
            updatedAt = now,
            message = message?.let { DiagnosticRedactor.bodyText(it) },
        )
        repository?.saveOperation(operation)
        return Handle(operation).also { it.event("start", message ?: stage.label, details) }
    }

    inner class Handle internal constructor(var operation: TranslationOperation) {
        val id get() = operation.id
        private var progressNanos = 0L
        private var captureDirectory: String? = null

        suspend fun progress(completed: Long, total: Long? = operation.total, message: String? = null) {
            val now = System.nanoTime()
            if (progressNanos != 0L && now - progressNanos < 1_000_000_000L) return
            progressNanos = now
            operation = operation.copy(
                completed = completed,
                total = total?.coerceAtLeast(completed),
                updatedAt = System.currentTimeMillis(),
                message = message?.let { DiagnosticRedactor.bodyText(it) } ?: operation.message,
            )
            repository?.saveOperation(operation)
        }

        suspend fun complete(
            completed: Long = operation.total ?: operation.completed,
            total: Long? = operation.total,
            details: Map<String, String> = emptyMap(),
            message: String? = null,
        ) {
            finish(TranslationOperationState.COMPLETED, completed, total, details, message)
        }

        suspend fun failed(error: Throwable, details: Map<String, String> = emptyMap()) = withContext(NonCancellable) {
            finish(
                if (error is CancellationException) {
                    TranslationOperationState.INTERRUPTED
                } else {
                    TranslationOperationState.FAILED
                },
                operation.completed,
                operation.total,
                details,
                error.message ?: error.javaClass.simpleName,
            )
        }

        suspend fun attachCapture(captureId: String, directory: String) {
            captureDirectory = directory
            operation = operation.copy(captureId = captureId, updatedAt = System.currentTimeMillis())
            repository?.saveOperation(operation)
        }

        suspend fun event(
            key: String,
            message: String,
            details: Map<String, String> = emptyMap(),
            level: String = "INFO",
        ) {
            if (context.logs?.enabled == false) return
            repository?.addEvent(
                TranslationEvent(
                    id = "$id:$key",
                    jobId = context.jobId,
                    imageId = context.imageId,
                    operationId = id,
                    operationState = operation.state,
                    stage = operation.stage.name,
                    time = operation.updatedAt,
                    level = level,
                    message = DiagnosticRedactor.bodyText(message).take(4000),
                    details = buildMap {
                        put("state", operation.state.name)
                        put("completed", operation.completed.toString())
                        operation.total?.let { put("total", it.toString()) }
                        put("unit", operation.unit.label)
                        operation.parentId?.let { put("parentOperationId", it) }
                        operation.artifactId?.let { put("artifactId", it) }
                        operation.captureId?.let { put("captureId", it) }
                        details.forEach { (key, value) ->
                            put(DiagnosticRedactor.bodyText(key), DiagnosticRedactor.bodyText(value).take(8192))
                        }
                    },
                    capturePath = captureDirectory,
                ),
            )
        }

        private suspend fun finish(
            state: TranslationOperationState,
            completed: Long,
            total: Long?,
            details: Map<String, String>,
            message: String?,
        ) {
            val end = maxOf(System.currentTimeMillis(), operation.startedAt ?: 0L)
            operation = operation.copy(
                state = state,
                completed = completed,
                total = total?.coerceAtLeast(completed),
                updatedAt = end,
                endedAt = end,
                message = message?.let { DiagnosticRedactor.bodyText(it) } ?: operation.message,
            )
            repository?.saveOperation(operation)
            event(
                "finish",
                operation.message ?: "${operation.stage.label}: ${state.name.lowercase()}",
                details + mapOf("elapsedMillis" to (end - (operation.startedAt ?: end)).toString()),
                when (state) {
                    TranslationOperationState.FAILED -> "ERROR"
                    TranslationOperationState.INTERRUPTED -> "WARN"
                    else -> "INFO"
                },
            )
        }
    }
}
