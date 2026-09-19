package mihon.feature.translation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tachiyomi.domain.translation.model.TranslationPdfPageSize
import tachiyomi.domain.translation.model.TranslationRenderedFormat

/** Shared production dialog; export work starts only through an explicit format action. */
@Composable
internal fun TranslationExportDialog(
    count: Int,
    busy: Boolean,
    logs: Boolean,
    captures: Boolean,
    format: TranslationRenderedFormat,
    paper: TranslationPdfPageSize,
    onLogsChange: (Boolean) -> Unit,
    onCapturesChange: (Boolean) -> Unit,
    onFormatChange: (TranslationRenderedFormat) -> Unit,
    onPaperChange: (TranslationPdfPageSize) -> Unit,
    onCancel: () -> Unit,
    onBackup: () -> Unit,
    onRendered: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Export $count selected histories") },
        text = {
            Column {
                Text(
                    "Structured ZIP preserves OCR, corrections, geometry and review history. " +
                        "Credentials and queued requests are excluded.",
                )
                ExportCheckbox("Include logs", logs, !busy, onLogsChange)
                ExportCheckbox("Include sanitized API payloads", captures, !busy, onCapturesChange)
                TextButton(enabled = !busy, onClick = onBackup) { Text("Structured ZIP…") }
                if (count == 1) {
                    Text("Rendered pages use current styles and the sound-effect policy.")
                    Row {
                        TranslationRenderedFormat.entries.forEach { value ->
                            FilterChip(format == value, {
                                onFormatChange(value)
                            }, enabled = !busy, label = { Text(value.name) })
                        }
                    }
                    if (format == TranslationRenderedFormat.PDF) {
                        Row {
                            TranslationPdfPageSize.entries.forEach { value ->
                                FilterChip(paper == value, {
                                    onPaperChange(value)
                                }, enabled = !busy, label = { Text(value.name) })
                            }
                        }
                    }
                    TextButton(enabled = !busy, onClick = onRendered) { Text("Export ${format.name}…") }
                } else {
                    Text("Select one chapter for CBZ/PDF export.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** One labelled, checkable action owns the full row; the checkbox supplies only its visual state. */
@Composable
private fun ExportCheckbox(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label)
    }
}
