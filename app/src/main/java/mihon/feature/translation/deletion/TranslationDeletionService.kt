package mihon.feature.translation.deletion

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mihon.feature.translation.TranslationManager
import mihon.feature.translation.provider.TranslationDiagnosticsStore
import mihon.feature.translation.transfer.OwnedTranslationExport
import mihon.feature.translation.transfer.TranslationExportStore
import tachiyomi.domain.translation.model.TranslationDeletionScope
import tachiyomi.domain.translation.model.TranslationDeletionSnapshot
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationProgressUnit
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.service.TranslationDeletionRepository
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class TranslationDeletionFile internal constructor(
    val jobId: String,
    val scope: TranslationDeletionScope,
    val label: String,
    val files: Long,
    val bytes: Long,
    internal val directory: File,
    internal val fingerprint: String,
    internal val captureId: String? = null,
)

data class TranslationDeletionPreview internal constructor(
    val scopes: Set<TranslationDeletionScope>,
    val records: List<TranslationDeletionSnapshot>,
    val files: List<TranslationDeletionFile>,
    val exports: List<OwnedTranslationExport>,
    internal val checkpoint: TranslationManagementCheckpoint,
) {
    val jobIds get() = records.map { it.jobId }.toSet()
    val logicalDatabaseBytes get() = records.sumOf { row -> scopes.sumOf { row.groups[it]?.payloadBytes ?: 0 } }
    val fileBytes get() = files.sumOf { it.bytes } + exports.sumOf { it.storageBytes }
}

data class TranslationDeletionReport(
    val complete: Boolean,
    val needsRefresh: Boolean,
    val deletedFileBytes: Long = 0,
    val deletedRecords: Long = 0,
    val message: String,
)

@Inject
@SingleIn(AppScope::class)
class TranslationDeletionService(
    private val context: Context,
    private val manager: TranslationManager,
    private val repository: TranslationRepository,
    private val storage: TranslationDeletionRepository,
    private val diagnostics: TranslationDiagnosticsStore,
    private val exportStore: TranslationExportStore,
) {
    suspend fun preview(jobIds: Set<String>, scopes: Set<TranslationDeletionScope>): TranslationDeletionPreview =
        withContext(Dispatchers.IO) {
            require(jobIds.isNotEmpty() && scopes.isNotEmpty()) { "Select chapters and at least one deletion scope" }
            exportStore.stop(jobIds)
            val checkpoint = manager.checkpointForManagement(jobIds)
            check(checkpoint.jobIds == jobIds) { "Selected work was not checkpointed; refresh the preview" }
            val records = manager.withManagementCheckpoint(checkpoint) { storage.deletionSnapshots(jobIds) }
            val files = ownedFiles(jobIds, scopes)
            val exports = if (TranslationDeletionScope.RENDERED_EXPORTS in
                scopes
            ) {
                exportStore.list(jobIds)
            } else {
                emptyList()
            }
            check(exports.none { it.active }) { "A selected export is still running; refresh after it stops" }
            TranslationDeletionPreview(scopes, records, files, exports, checkpoint)
        }

    suspend fun confirm(preview: TranslationDeletionPreview): TranslationDeletionReport = withContext(Dispatchers.IO) {
        var deletedBytes = 0L
        var changed = false
        val started = System.currentTimeMillis()
        try {
            manager.withManagementCheckpoint(preview.checkpoint) {
                exportStore.withManagement(preview.jobIds) {
                    val current = storage.deletionSnapshots(preview.jobIds)
                    val recordsChanged = current.zip(preview.records).any { (now, before) ->
                        now.jobId != before.jobId || now.jobFingerprint != before.jobFingerprint ||
                            preview.scopes.any { now.groups[it] != before.groups[it] }
                    }
                    val files = ownedFiles(preview.jobIds, preview.scopes)
                    val allExports = exportStore.list(preview.jobIds)
                    if (allExports.any { it.active }) {
                        return@withManagement TranslationDeletionReport(
                            false,
                            true,
                            message = "A selected export started after the preview. " +
                                "Refresh and checkpoint it before deletion.",
                        )
                    }
                    val exports = if (TranslationDeletionScope.RENDERED_EXPORTS in
                        preview.scopes
                    ) {
                        allExports
                    } else {
                        emptyList()
                    }
                    if (recordsChanged || current.size != preview.records.size || files != preview.files ||
                        exports != preview.exports
                    ) {
                        return@withManagement TranslationDeletionReport(
                            false,
                            true,
                            message = "Selected records or files changed. " +
                                "Refresh the preview before deleting anything.",
                        )
                    }
                    for (file in files) {
                        currentCoroutineContext().ensureActive()
                        if (file.captureId != null) {
                            diagnostics.delete(file.captureId)
                        } else {
                            check(file.directory.deleteRecursively()) { "Selected private-file cleanup is incomplete" }
                        }
                        changed = true
                        deletedBytes += file.bytes
                    }
                    if (exports.isNotEmpty()) {
                        deletedBytes += exportStore.delete(exports.map { it.id }.toSet())
                        changed = true
                    }
                    check(storage.deleteRecords(preview.records, preview.scopes)) {
                        "Database records changed while deleting private files. " +
                            "Remaining records were preserved; refresh the preview."
                    }
                    val count = preview.records.sumOf { row -> preview.scopes.sumOf { row.groups[it]?.records ?: 0 } }
                    val report = TranslationDeletionReport(
                        true,
                        false,
                        deletedBytes,
                        count,
                        buildString {
                            append(
                                "Selected data deleted. Source chapters, original cached pages and credentials remain. ",
                            )
                            if (TranslationDeletionScope.ACCOUNTING in preview.scopes) {
                                append("Cloud billing records remain separate.")
                            } else {
                                append("Usage and cost / reservation history were retained.")
                            }
                            if (TranslationDeletionScope.LOGS in preview.scopes &&
                                TranslationDeletionScope.TRANSLATIONS !in preview.scopes
                            ) {
                                append(" Geometry recovery checkpoints were retained with translation data.")
                            }
                        },
                    )
                    changed = true
                    try {
                        receipt(preview, report, started)
                        report
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        report.copy(
                            message =
                            report.message + " Deletion completed, but its log receipt could not be saved.",
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val report = TranslationDeletionReport(
                false,
                true,
                deletedBytes,
                message = if (changed) {
                    "Some selected private files were deleted. Remaining data was preserved; refresh the preview."
                } else {
                    "Work or storage changed; no deletion was confirmed. " +
                        "Refresh the preview. ${error.message.orEmpty()}"
                },
            )
            if (changed) receipt(preview, report, started)
            report
        }
    }

    private suspend fun receipt(preview: TranslationDeletionPreview, report: TranslationDeletionReport, started: Long) {
        val finished = System.currentTimeMillis()
        for (job in preview.records) {
            val id = UUID.randomUUID().toString()
            repository.saveOperation(
                TranslationOperation(
                    id, job.jobId, TranslationStage.DELETE,
                    state = if (report.complete) {
                        TranslationOperationState.COMPLETED
                    } else {
                        TranslationOperationState.PARTIAL
                    },
                    completed = if (report.complete) preview.scopes.size.toLong() else 0,
                    total = preview.scopes.size.toLong(), unit = TranslationProgressUnit.STEPS,
                    startedAt = started, endedAt = finished, updatedAt = finished, message = report.message,
                ),
            )
            repository.addEvent(
                TranslationEvent(
                    "$id:receipt",
                    job.jobId,
                    operationId = id,
                    operationState = if (report.complete) {
                        TranslationOperationState.COMPLETED
                    } else {
                        TranslationOperationState.PARTIAL
                    },
                    level = if (report.complete) "INFO" else "WARN",
                    stage = "DELETE",
                    message = report.message,
                    details = mapOf(
                        "scopes" to preview.scopes.joinToString { it.name },
                        "deletedFilesBytesAcrossSelection" to report.deletedFileBytes.toString(),
                        "receipt" to "This new management receipt is retained after clearing earlier logs",
                    ),
                ),
            )
        }
    }

    private suspend fun ownedFiles(
        jobIds: Set<String>,
        scopes: Set<TranslationDeletionScope>,
    ): List<TranslationDeletionFile> {
        val files = mutableListOf<TranslationDeletionFile>()
        if (TranslationDeletionScope.LOGS in scopes) {
            val root = File(context.noBackupFilesDir, "translation/diagnostics")
            diagnostics.list().filter { it.jobId in jobIds }.forEach { capture ->
                check(capture.state != "RUNNING") { "A selected API capture is still active" }
                require(
                    runCatching {
                        UUID.fromString(capture.id).toString() == capture.id
                    }.getOrDefault(false),
                ) { "Invalid capture ownership ID" }
                inspect(
                    root,
                    capture.id,
                    capture.jobId,
                    TranslationDeletionScope.LOGS,
                    "Sanitized API capture",
                    capture.id,
                )?.let(files::add)
            }
        }
        if (TranslationDeletionScope.TRANSLATIONS in scopes) {
            for (id in jobIds.sorted()) {
                inspect(
                    File(context.cacheDir, "translation/prepared"),
                    "results-${hashText(id)}",
                    id,
                    TranslationDeletionScope.TRANSLATIONS,
                    "Translated tile checkpoints",
                )?.let(files::add)
                repository.reviews(id).forEach { review ->
                    require(review.id.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "Invalid private review evidence ID" }
                    inspect(
                        File(context.filesDir, "translation/review-evidence"),
                        review.id,
                        id,
                        TranslationDeletionScope.TRANSLATIONS,
                        "Private review evidence",
                    )?.let(files::add)
                }
            }
        }
        return files.sortedWith(compareBy<TranslationDeletionFile> { it.jobId }.thenBy { it.directory.path })
    }

    private suspend fun inspect(
        root: File,
        name: String,
        jobId: String,
        scope: TranslationDeletionScope,
        label: String,
        captureId: String? = null,
    ): TranslationDeletionFile? {
        val folder = File(root, name)
        if (!folder.exists()) return null
        require(folder.isDirectory && folder.canonicalFile == File(root.canonicalFile, name)) {
            "Unowned private storage folder"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var files = 0L
        var bytes = 0L
        suspend fun visit(directory: File) {
            for (file in directory.listFiles().orEmpty().sortedBy { it.name }) {
                currentCoroutineContext().ensureActive()
                require(file.canonicalFile == File(directory.canonicalFile, file.name)) {
                    "Unowned linked storage entry"
                }
                if (file.isDirectory) {
                    visit(file)
                    continue
                }
                require(file.isFile) { "Unsupported private storage entry" }
                val relative = file.relativeTo(folder).path
                digest.update(relative.toByteArray())
                digest.update(0.toByte())
                digest.update(file.length().toString().toByteArray())
                digest.update(0.toByte())
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                files++
                bytes += file.length()
            }
        }
        visit(folder)
        return TranslationDeletionFile(
            jobId,
            scope,
            label,
            files,
            bytes,
            folder,
            digest.digest().joinToString("") { "%02x".format(it) },
            captureId,
        )
    }

    private fun hashText(value: String) = MessageDigest.getInstance(
        "SHA-256",
    ).digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
    }
}
