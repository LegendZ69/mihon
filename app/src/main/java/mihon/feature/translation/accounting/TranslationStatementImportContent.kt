package mihon.feature.translation.accounting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
internal fun TranslationStatementImportContent(
    manager: TranslationAccountingManager,
    onImported: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    document = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream()
                            val bytes = ByteArray(8192)
                            while (true) {
                                val count = input.read(bytes)
                                if (count < 0) break
                                check(output.size() + count <= TranslationBillingStatementImporter.MAX_BYTES) {
                                    "Statement JSON exceeds 64 KiB"
                                }
                                output.write(bytes, 0, count)
                            }
                            output.toByteArray().decodeToString(throwOnInvalidSequence = true)
                        } ?: error("Statement document is unavailable")
                    }
                    message = null
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    message = "Could not read the statement. Choose a UTF-8 JSON document no larger than 64 KiB."
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Import a Groq statement", style = MaterialTheme.typography.titleMedium)
            Text(
                "Import the amount and coverage you verified in a statement or invoice. " +
                    "This local JSON format does not parse PDF or provider-specific CSV files. " +
                    "No tokens or charges are inferred.",
            )
            TextButton(enabled = !busy, onClick = {
                picker.launch(arrayOf("application/json", "text/plain"))
            }) { Text("Choose JSON document") }
            OutlinedTextField(
                document,
                {
                    if (it.toByteArray().size <=
                        TranslationBillingStatementImporter.MAX_BYTES
                    ) {
                        document = it
                    } else {
                        message = "Statement exceeds 64 KiB"
                    }
                },
                label = {
                    Text("Paste Mihon statement JSON")
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 10,
            )
            TextButton(onClick = {
                document = """
                {"version":1,"provider":"Groq","statement_id":"replace-id","scope":"replace-account-scope",
                "period_start":"YYYY-MM-DD","period_end_exclusive":"YYYY-MM-DD",
                "currency":"USD","amount":"replace-with-document-amount","invoice":true}
                """.trimIndent()
            }) { Text("Insert format template") }
            Text(
                "Use the document's currency and exact amount. Set invoice=true only for an invoice. " +
                    "The end date is exclusive. Identical imports are skipped; conflicting existing amounts are preserved.",
            )
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = document.isNotBlank() && !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            onImported(manager.importStatement(document))
                            document = ""
                        } catch (
                            error: Exception,
                        ) {
                            if (error is CancellationException) throw error
                            message =
                                error.message ?: "Statement import failed"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Import statement") }
                TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}
