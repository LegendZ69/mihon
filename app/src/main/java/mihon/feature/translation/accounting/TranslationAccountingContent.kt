package mihon.feature.translation.accounting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import mihon.app.di.appGraph
import tachiyomi.domain.translation.model.TranslationBillingConnection
import tachiyomi.domain.translation.model.TranslationBillingRange
import tachiyomi.domain.translation.model.TranslationBillingReport
import tachiyomi.domain.translation.model.TranslationBillingSource
import tachiyomi.domain.translation.model.TranslationBillingStorageStats
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Embedded in the translator dashboard; account totals remain separate from application estimates. */
@Composable
fun TranslationAccountingContent(modifier: Modifier = Modifier, onOpenLogs: (String) -> Unit = {}) {
    val context = LocalContext.current
    val manager = remember(context) { context.appGraph.translationAccountingManager }
    val connections by manager.connections.collectAsState()
    val reports by manager.reports.collectAsState()
    val failures by manager.failures.collectAsState()
    val active by manager.active.collectAsState()
    val loading by manager.loading.collectAsState()
    val storageFailure by manager.storageFailures.collectAsState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var editing by remember { mutableStateOf<TranslationBillingConnection?>(null) }
    var removing by remember { mutableStateOf<TranslationBillingConnection?>(null) }
    var importing by remember { mutableStateOf(false) }
    var deletingReport by remember { mutableStateOf<TranslationBillingReport?>(null) }
    var deletionStats by remember { mutableStateOf<TranslationBillingStorageStats?>(null) }
    var reportBytes by remember { mutableStateOf<Long?>(null) }
    var deletionError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(deletingReport) {
        deletionStats = null
        reportBytes = null
        deletionError = null
        deletingReport?.let { report ->
            try {
                reportBytes =
                    withContext(Dispatchers.IO) { BillingJson.encodeToString(report).toByteArray().size.toLong() }
                deletionStats = manager.storageStats(report.connectionId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                deletionError =
                    "Accounting storage could not be inspected; nothing was deleted"
            }
        }
    }
    var message by remember { mutableStateOf<String?>(null) }
    var syncJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val today = remember { LocalDate.now(ZoneOffset.UTC) }
    var startDate by remember { mutableStateOf(today.minusDays(29).toString()) }
    var endDate by remember { mutableStateOf(today.toString()) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cloud accounting", style = MaterialTheme.typography.titleLarge)
        Text(
            "Provider-reported account costs, imported invoices and app estimates are separate. " +
                "Cloud data can arrive late and can include usage outside Mihon.",
        )
        if (loading) {
            Text("Loading saved accounting and completing local recovery…")
            return@Column
        }
        if (storageFailure != null) {
            Text(storageFailure.orEmpty(), color = MaterialTheme.colorScheme.error)
            Button(onClick = { scope.launch { manager.recoverLocalState() } }) { Text("Retry local recovery") }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(startDate, {
                startDate = it
            }, label = { Text("From (UTC date)") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(endDate, {
                endDate = it
            }, label = { Text("Through (UTC date)") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        if (connections.isEmpty()) Text("No billing connection configured. Reported costs are unavailable.")
        connections.forEach { connection ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(connection.label, style = MaterialTheme.typography.titleMedium)
                    val sourceLabel = connection.source.name.lowercase().replace('_', ' ')
                    val refreshLabel = connection.refreshMinutes?.let { "every $it minutes" } ?: "manual sync"
                    Text("$sourceLabel · $refreshLabel")
                    failures[connection.id]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    reports[connection.id]?.let { BillingReportContent(it) }
                        ?: Text("No completed synchronization; cost and token totals are unavailable.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (connection.source != TranslationBillingSource.IMPORTED_STATEMENT) {
                            Button(enabled = active == null, onClick = {
                                syncJob = scope.launch {
                                    message = runCatching {
                                        val start = LocalDate.parse(
                                            startDate,
                                        ).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                                        val end =
                                            minOf(
                                                LocalDate.parse(
                                                    endDate,
                                                ).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                                                System.currentTimeMillis(),
                                            )
                                        manager.synchronize(connection.id, TranslationBillingRange(start, end))
                                        "Synchronization completed"
                                    }.fold({ it }, {
                                        if (it is CancellationException) {
                                            throw it
                                        } else {
                                            it.message
                                                ?: "Synchronization failed"
                                        }
                                    })
                                }
                            }) { Text(if (active == connection.id) "Synchronizing…" else "Sync now") }
                        }
                        if (connection.source != TranslationBillingSource.IMPORTED_STATEMENT) {
                            TextButton(onClick = { editing = connection }) { Text("Settings") }
                        }
                        TextButton(onClick = { onOpenLogs("billing-${connection.id}") }) { Text("Logs") }
                        TextButton(enabled = active != connection.id, onClick = {
                            removing = connection
                        }) { Text("Remove") }
                    }
                    reports[connection.id]?.let { report ->
                        TextButton(enabled = active == null, onClick = {
                            deletingReport = report
                        }) { Text("Delete accounting history") }
                    }
                }
            }
        }
        if (active != null &&
            syncJob?.isActive == true
        ) {
            OutlinedButton(onClick = { syncJob?.cancel() }) { Text("Cancel current manual sync") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                editing =
                    TranslationBillingConnection(
                        UUID.randomUUID().toString(),
                        "Google Cloud billing",
                        TranslationBillingSource.GOOGLE_BIGQUERY,
                    )
            }) { Text("Add connection") }
            TextButton(onClick = {
                uri.openUri("https://console.groq.com/settings/billing")
            }) { Text("Groq billing console") }
        }
        Text(
            "Groq ordinary-account billing synchronization is unavailable: use its console or import a statement. " +
                "Enterprise metrics are not invoice totals.",
        )
        TextButton(onClick = { importing = true }) { Text("Import Groq statement") }
        if (importing) {
            TranslationStatementImportContent(manager, onImported = {
                message = it
                importing = false
            }, onCancel = {
                importing =
                    false
            })
        }
        reports.filterKeys { id -> connections.none { it.id == id } }.forEach { (_, report) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Retained accounting history", style = MaterialTheme.typography.titleMedium)
                    BillingReportContent(report)
                    TextButton(enabled = active == null, onClick = {
                        deletingReport = report
                    }) { Text("Delete accounting history") }
                }
            }
        }
        message?.let { Text(it) }
        editing?.let { connection ->
            BillingConnectionEditor(connection, manager, onClose = { editing = null })
        }
        removing?.let { connection ->
            AlertDialog(
                onDismissRequest = { removing = null },
                title = { Text("Remove ${connection.label}?") },
                text = {
                    Text(
                        "Stop this connection's refresh and delete its separate billing credential if unused. " +
                            "Reported accounting history is retained.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                manager.removeConnection(connection.id)
                                removing = null
                            } catch (
                                error: Exception,
                            ) {
                                if (error is CancellationException) throw error
                                message =
                                    error.message
                            }
                        }
                    }) { Text("Remove connection") }
                },
                dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
            )
        }
        deletingReport?.let { report ->
            val stale = reports[report.connectionId] != report
            AlertDialog(
                onDismissRequest = {
                    if (!deleting) deletingReport = null
                },
                title = { Text("Delete accounting history?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val connectionLabel = connections.firstOrNull { it.id == report.connectionId }?.label
                            ?: report.connectionId
                        Text("Connection: $connectionLabel")
                        Text(
                            "Delete this connection's reported or imported cost history " +
                                "and its cached account usage report. Translation usage, reservations, " +
                                "source chapters, logs and credentials are preserved.",
                        )
                        deletionStats?.let { stats ->
                            Text(
                                "${stats.records ?: "Unavailable"} cost records · ${stats.payloadBytes ?: "Unavailable"} bytes of SQL payloads",
                            )
                            Text(
                                "One cached report: ${reportBytes ?: "Unavailable"} bytes; " +
                                    "${report.usage.size} account usage buckets. Shared database allocation is not included.",
                            )
                        } ?: Text("Inspecting affected accounting…")
                        deletionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text("A future explicit or scheduled synchronization may fetch account history again.")
                        if (stale) {
                            Text(
                                "Accounting changed while this preview was open. Close it and inspect the updated history.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled =
                        !deleting && !stale && active == null && deletionStats?.records != null &&
                            deletionStats?.payloadBytes != null,
                        onClick = {
                            scope.launch {
                                deleting = true
                                try {
                                    manager.deleteHistory(report.connectionId, report, checkNotNull(deletionStats))
                                    deletingReport =
                                        null
                                } catch (
                                    error: Exception,
                                ) {
                                    if (error is CancellationException) throw error
                                    deletionError =
                                        error.message ?: "Accounting deletion failed"
                                } finally {
                                    deleting = false
                                }
                            }
                        },
                    ) { Text(if (deleting) "Deleting…" else "Delete accounting history") }
                },
                dismissButton = {
                    TextButton(enabled = !deleting, onClick = { deletingReport = null }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun BillingConnectionEditor(
    initial: TranslationBillingConnection,
    manager: TranslationAccountingManager,
    onClose: () -> Unit,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var key by remember(initial.id) { mutableStateOf("") }
    var message by remember(initial.id) { mutableStateOf<String?>(null) }
    var dirty by remember(initial.id) { mutableStateOf(false) }
    var immediate by remember(initial.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    fun update(
        value: TranslationBillingConnection,
        saveImmediately: Boolean = false,
    ) {
        draft = value
        dirty = true
        immediate =
            saveImmediately
    }
    val latestDraft by rememberUpdatedState(draft)
    val latestDirty by rememberUpdatedState(dirty)
    LaunchedEffect(initial.id) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                if (latestDirty && runCatching { latestDraft.validate() }.isSuccess) {
                    manager.flushConnection(latestDraft)
                }
            }
        }
    }
    LaunchedEffect(draft, dirty, immediate) {
        if (!dirty) return@LaunchedEffect
        val error = runCatching { draft.validate() }.exceptionOrNull()
        if (error != null) {
            message = error.message
            return@LaunchedEffect
        }
        message = "Saving…"
        if (!immediate) delay(300)
        message = runCatching { manager.saveConnection(draft) }.fold({ "Saved" }, { it.message })
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Billing connection settings", style = MaterialTheme.typography.titleMedium)
            Row {
                TextButton(onClick = {
                    update(draft.copy(source = TranslationBillingSource.GOOGLE_BIGQUERY, credentialId = ""), true)
                }) { Text("Google") }
                TextButton(onClick = {
                    update(draft.copy(source = TranslationBillingSource.OPENAI_ORGANIZATION, credentialId = ""), true)
                }) { Text("OpenAI") }
            }
            OutlinedTextField(draft.label, {
                update(draft.copy(label = it))
            }, label = { Text("Label") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.projectIds.joinToString(","), {
                update(draft.copy(projectIds = it.split(',').map(String::trim).filter(String::isNotBlank)))
            }, label = {
                Text("Project filters (comma separated; blank means account scope)")
            }, modifier = Modifier.fillMaxWidth())
            if (draft.source == TranslationBillingSource.GOOGLE_BIGQUERY) {
                OutlinedTextField(draft.googleProject, {
                    update(draft.copy(googleProject = it))
                }, label = { Text("BigQuery job project") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.googleTable, {
                    update(draft.copy(googleTable = it))
                }, label = { Text("Existing export: project.dataset.table") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.googleLocation, {
                    update(draft.copy(googleLocation = it))
                }, label = { Text("Export location") }, modifier = Modifier.fillMaxWidth())
                var cap by remember(initial.id) { mutableStateOf(draft.maximumBytesBilled?.toString().orEmpty()) }
                OutlinedTextField(cap, {
                    cap = it
                    update(draft.copy(maximumBytesBilled = it.toLongOrNull()))
                }, label = { Text("Maximum billed bytes per query (required)") }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Each synchronization dry-runs a read-only query before execution. " +
                        "No resources or IAM settings are created. Query charges can apply within this cap.",
                )
                TextButton(onClick = {
                    uri.openUri("https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery")
                }) { Text("Official export setup documentation") }
            } else {
                OutlinedTextField(draft.organization, {
                    update(draft.copy(organization = it))
                }, label = { Text("OpenAI organization ID (optional)") }, modifier = Modifier.fillMaxWidth())
                Text("Use a separately imported OpenAI administration API key. Inference keys are not reused.")
            }
            Text("Automatic refresh is an application setting; manual is the default.")
            Row {
                listOf(null, 60L, 360L, 1440L).forEach { minutes ->
                    TextButton(onClick = { update(draft.copy(refreshMinutes = minutes), true) }) {
                        Text(if (minutes == null) "Manual" else "${minutes / 60}h")
                    }
                }
            }
            Text("Selected: ${draft.refreshMinutes?.let { "every $it minutes" } ?: "manual"}")
            OutlinedTextField(key, { key = it }, label = {
                Text(
                    if (draft.source ==
                        TranslationBillingSource.GOOGLE_BIGQUERY
                    ) {
                        "Paste separate service-account JSON"
                    } else {
                        "Paste separate administration API key"
                    },
                )
            }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(enabled = key.isNotBlank() && !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        draft = manager.importCredential(draft, key)
                        key = ""
                        dirty = false
                        message =
                            "Credential imported; settings saved"
                    } catch (
                        error: Exception,
                    ) {
                        if (error is CancellationException) throw error
                        message = error.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Import billing credential") }
            message?.let { Text(it) }
            TextButton(onClick = {
                scope.launch {
                    try {
                        if (dirty && runCatching { draft.validate() }.isSuccess) manager.saveConnection(draft)
                        key = ""
                        onClose()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        message = error.message ?: "Billing settings could not be saved"
                    }
                }
            }) { Text("Done") }
        }
    }
}

@Composable
private fun BillingReportContent(report: TranslationBillingReport) {
    Text("Coverage: ${Instant.ofEpochMilli(report.periodStart)} to ${Instant.ofEpochMilli(report.periodEnd)}")
    Text("Last sync: ${Instant.ofEpochMilli(report.syncedAt)}")
    if (report.snapshots.isEmpty()) Text("No reported cost rows for this period; this is not an invoice balance.")
    report.snapshots.groupBy { it.currency }.forEach { (currency, rows) ->
        val sum = rows.fold(BigDecimal.ZERO) { total, row -> total + row.amount.toBigDecimal() }
        val type = if (rows.all { it.invoice }) {
            "Imported invoice"
        } else if (rows.all {
                it.source ==
                    TranslationBillingSource.IMPORTED_STATEMENT
            }
        ) {
            "Imported statement"
        } else {
            "Provider-reported"
        }
        Text("$type $currency ${sum.toPlainString()} · ${rows.map { it.scope }.distinct().joinToString()}")
    }
    if (report.usage.isNotEmpty()) {
        fun total(value: (tachiyomi.domain.translation.model.TranslationAccountUsageBucket) -> Long?): String =
            report.usage.map(value).let { values ->
                if (values.any { it == null }) "Unavailable" else values.filterNotNull().sum().toString()
            }
        Text(
            "Account input tokens: ${total { it.inputTokens }} · output: ${total { it.outputTokens }} · " +
                "cached: ${total { it.cachedInputTokens }} · requests: ${total { it.requests }}",
        )
    }
    val bars = report.snapshots.groupBy {
        it.periodStart
    }.toSortedMap().mapValues { (_, rows) -> rows.sumOf { it.amount.toDouble() } }.values.toList()
    // Avoid displaying a graph that combines different currencies.
    if (bars.isNotEmpty() && report.snapshots.none { it.source == TranslationBillingSource.IMPORTED_STATEMENT } &&
        report.snapshots.map { it.currency }.distinct().size == 1
    ) {
        val color = MaterialTheme.colorScheme.primary
        val maximum = bars.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: 1.0
        Text("Reported daily costs; credits can be negative")
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val baseline = size.height / 2
            drawLine(color.copy(alpha = 0.4f), Offset(0f, baseline), Offset(size.width, baseline))
            val width = size.width / bars.size
            bars.forEachIndexed { index, amount ->
                val height = (kotlin.math.abs(amount) / maximum * baseline).toFloat()
                drawRect(
                    color,
                    Offset(
                        index * width + width * 0.1f,
                        if (amount >=
                            0
                        ) {
                            baseline - height
                        } else {
                            baseline
                        },
                    ),
                    Size(width * 0.8f, height),
                )
            }
        }
    }
    report.bytesProcessed?.let {
        Text("Query bytes processed: $it · billed bytes: ${report.bytesBilled ?: "Unavailable"}")
    }
    Text(report.notes)
}
