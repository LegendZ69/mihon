package mihon.feature.translation.ui

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import mihon.feature.translation.provider.sanitizedDiagnosticRecord
import tachiyomi.domain.translation.model.TranslationLogQuery
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Chapter actions and subprocess diagnostics have distinct selection scopes. */
internal data class TranslationQueueSelection(
    val jobIds: Set<String> = emptySet(),
    val operations: Map<String, TranslationOperation> = emptyMap(),
) {
    val active get() = jobIds.isNotEmpty() || operations.isNotEmpty()
    val count get() = jobIds.size + operations.size

    fun selectJobs(ids: Set<String>) = TranslationQueueSelection(jobIds = ids)

    fun toggleOperation(operation: TranslationOperation) = TranslationQueueSelection(
        operations = if (operation.id in
            operations
        ) {
            operations - operation.id
        } else {
            operations + (operation.id to operation)
        },
    )

    fun refreshOperations(latest: List<TranslationOperation>) = copy(
        operations = operations + latest.filter { it.id in operations }.associateBy { it.id },
    )

    fun withoutJobs(ids: Set<String>) = copy(
        jobIds = jobIds - ids,
        operations = operations.filterValues { it.jobId !in ids },
    )

    fun exportSnapshot(now: Long) = SelectedSubprocessDiagnostics(operations.values.toList(), now)
}

internal data class SelectedSubprocessDiagnostics(val operations: List<TranslationOperation>, val capturedAt: Long)

/** Frozen IDs and a time boundary prevent new progress events from shifting the paginated export. */
internal suspend fun exportSelectedSubprocessDiagnostics(
    repository: TranslationRepository,
    selection: SelectedSubprocessDiagnostics,
    destination: OutputStream,
    includeCaptures: Boolean,
    exportCaptures: suspend (List<String>, OutputStream) -> Unit,
) {
    require(selection.operations.isNotEmpty()) { "Select subprocess diagnostics to export" }
    val json = Json { encodeDefaults = true }
    val chosen = selection.operations.distinctBy { it.id }
    suspend fun writeRecords(output: OutputStream): Set<String> {
        val captures = linkedSetOf<String>()
        val events = hashSetOf<String>()
        output.bufferedWriter().use { writer ->
            fun record(type: String, value: kotlinx.serialization.json.JsonElement) {
                val envelope = buildJsonObject {
                    put("recordType", type)
                    put("record", value)
                }
                writer.appendLine(sanitizedDiagnosticRecord(envelope.toString()))
            }
            record(
                "export",
                buildJsonObject {
                    put("version", 1)
                    put("capturedAt", selection.capturedAt)
                    put("scope", "Selected operation snapshots and their related events through capturedAt")
                    put("operationIds", json.encodeToJsonElement(chosen.map { it.id }))
                    put("capturePolicy", "sanitized-v1")
                },
            )
            for (operation in chosen) {
                currentCoroutineContext().ensureActive()
                record("operation", json.encodeToJsonElement(operation))
                var offset = 0L
                do {
                    currentCoroutineContext().ensureActive()
                    val page = repository.eventPage(
                        TranslationLogQuery(
                            jobId = operation.jobId,
                            operationId = operation.id,
                            until = selection.capturedAt + 1,
                            limit = 500,
                            offset = offset,
                        ),
                    )
                    for (event in page) {
                        currentCoroutineContext().ensureActive()
                        if (!events.add(event.id)) continue
                        record("event", json.encodeToJsonElement(event))
                        event.capturePath?.let { captures += java.io.File(it).name }
                    }
                    offset += page.size
                } while (page.size == 500)
            }
        }
        return captures
    }
    if (!includeCaptures) {
        writeRecords(destination)
        return
    }
    ZipOutputStream(destination).use { archive ->
        archive.putNextEntry(ZipEntry("operations-and-logs.jsonl"))
        val captures = writeRecords(NonClosingDiagnosticsStream(archive))
        archive.closeEntry()
        if (captures.isNotEmpty()) {
            archive.putNextEntry(ZipEntry("sanitized-api-captures.zip"))
            exportCaptures(captures.toList(), NonClosingDiagnosticsStream(archive))
            archive.closeEntry()
        }
    }
}

private class NonClosingDiagnosticsStream(output: OutputStream) : FilterOutputStream(output) {
    override fun write(buffer: ByteArray, offset: Int, length: Int) = out.write(buffer, offset, length)

    override fun close() = flush()
}
