package mihon.feature.translation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mihon.feature.translation.deletion.TranslationDeletionPreview
import mihon.feature.translation.deletion.TranslationDeletionReport
import mihon.feature.translation.deletion.TranslationDeletionService
import tachiyomi.domain.translation.model.TranslationDeletionScope

@Composable
fun TranslationDeletionDialog(
    service: TranslationDeletionService,
    jobIds: Set<String>,
    onDismiss: () -> Unit,
    onDeleted: (TranslationDeletionReport) -> Unit,
    initialScopes: Set<TranslationDeletionScope> = setOf(TranslationDeletionScope.TRANSLATIONS),
    allowedScopes: Set<TranslationDeletionScope> = TranslationDeletionScope.entries.toSet(),
    scopeDescription: String? = null,
) {
    val scope = rememberCoroutineScope()
    var selected by remember(jobIds) { mutableStateOf(initialScopes.intersect(allowedScopes)) }
    var preview by remember(jobIds) { mutableStateOf<TranslationDeletionPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = {
            if (!busy ||
                preview == null
            ) {
                onDismiss()
            }
        },
        title = { Text("Delete selected translator data") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                if (preview == null) {
                    scopeDescription?.let { Text(it) }
                    Text(
                        "Preview pauses selected jobs and stops their running exports. " +
                            "Dismissing this dialog leaves them paused.",
                    )
                    TranslationDeletionScope.entries.filter { it in allowedScopes }.forEach { value ->
                        Row {
                            Checkbox(value in selected, enabled = !busy, onCheckedChange = { checked ->
                                selected = if (checked) selected + value else selected - value
                            })
                            Text(value.label)
                        }
                    }
                } else {
                    val current = checkNotNull(preview)
                    current.records.forEach { row ->
                        Text("${row.job?.mangaTitle ?: row.jobId} · ${row.job?.chapterTitle ?: "Retained history"}")
                        current.scopes.filter { it != TranslationDeletionScope.RENDERED_EXPORTS }.forEach { type ->
                            val stats = row.groups[type]
                            Text(
                                "${type.label}: ${stats?.records ?: 0} records · " +
                                    "${stats?.payloadBytes ?: 0} logical bytes",
                            )
                        }
                    }
                    Text(
                        "Private files: ${current.files.sumOf {
                            it.files
                        }} capture / checkpoint / evidence files and " +
                            "${current.exports.size} rendered exports · ${current.fileBytes} bytes",
                    )
                    Text(
                        "Database payload: ${current.logicalDatabaseBytes} logical bytes. " +
                            "SQLite may reuse space without shrinking its file.",
                    )
                    Text(
                        "Only selected job / history entries are deleted. " +
                            "Unselected saved versions of the same chapter remain available in the reader.",
                    )
                    Text(
                        "Source chapters, original cached pages, credentials and cloud billing records remain. " +
                            "Usage accounting is deleted only when selected.",
                    )
                    Text("Deletion cannot be undone. One new deletion receipt is retained in related logs.")
                }
                if (busy) CircularProgressIndicator()
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && selected.isNotEmpty(), onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val current = preview
                        if (current == null) {
                            preview = service.preview(jobIds, selected)
                        } else {
                            val report = service.confirm(current)
                            if (report.complete) {
                                onDeleted(report)
                            } else {
                                error = report.message
                                if (report.needsRefresh) preview = null
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "Cannot prepare deletion"
                        preview = null
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (preview == null) "Preview affected data" else "Delete previewed data") }
        },
        dismissButton = { TextButton(enabled = !busy || preview == null, onClick = onDismiss) { Text("Cancel") } },
    )
}
