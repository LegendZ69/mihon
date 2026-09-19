package mihon.feature.translation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.feature.translation.TranslationManager
import mihon.feature.translation.accounting.TranslationAccountingContent
import mihon.feature.translation.ocr.PaddleModelStatus
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.FilterList
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.domain.translation.model.TranslationDashboardQuery
import tachiyomi.domain.translation.model.TranslationDashboardReport
import tachiyomi.domain.translation.model.TranslationDashboardUsage
import tachiyomi.domain.translation.model.TranslationLatencySummary
import tachiyomi.domain.translation.model.TranslationMeasuredTotal
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.model.accountingProvider
import java.text.DateFormat
import java.util.Date

@Composable
internal fun TranslationDashboardContent() {
    val context = LocalContext.current
    val graph = context.appGraph
    val navigator = LocalNavigator.currentOrThrow
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val jobs by graph.translationManager.jobs.collectAsState(emptyList())
    // The bounded recent list drives active cards only. Aggregate totals scan all matching durable rows.
    val operations by remember {
        graph.translationRepository.observeOperations(limit = 1000)
    }.collectAsState(emptyList())
    val models by graph.paddleModelManager.states.collectAsState()
    val settings by graph.translationPreferences.settings.collectAsState()
    var windowHours by remember { mutableStateOf(24L) }
    var provider by remember { mutableStateOf<String?>(null) }
    var model by remember { mutableStateOf<String?>(null) }
    var breakdownOffset by remember { mutableStateOf(0L) }
    var refresh by remember { mutableStateOf(0) }
    var showFilters by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<TranslationDashboardReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var storage by remember { mutableStateOf<Long?>(null) }
    val allActive = jobs.filter { it.state in TranslationManager.activeStates }
    val active = allActive.filter {
        (provider == null || it.settings.provider.accountingProvider() == provider) &&
            (model == null || it.settings.provider.model == model)
    }
    val running by rememberUpdatedState(
        allActive.isNotEmpty() || models.any {
            it.status == PaddleModelStatus.DOWNLOADING
        },
    )
    LaunchedEffect(lifecycle, windowHours, provider, model, breakdownOffset, refresh) {
        report = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    val query = TranslationDashboardQuery(
                        (now - windowHours * 3_600_000).coerceAtLeast(0),
                        now + 1,
                        provider = provider,
                        model = model,
                        breakdownOffset = breakdownOffset,
                    )
                    val measured = withContext(Dispatchers.IO) { graph.translationDashboardRepository.aggregate(query) }
                    report = measured
                    error = null
                    storage = withContext(Dispatchers.IO) {
                        val roots =
                            listOf(
                                java.io.File(context.noBackupFilesDir, "translation"),
                                java.io.File(context.filesDir, "translation"),
                            )
                        roots.filter {
                            it.exists()
                        }.sumOf { root -> root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error =
                        failure.message ?: "Dashboard data unavailable"
                }
                delay(if (running) 5_000 else 30_000)
            }
        }
    }
    fun logs(since: Long? = report?.query?.since, until: Long? = report?.query?.until, search: String = "") {
        navigator.push(
            TranslationLogsScreen(
                initialSearch = search,
                since = since,
                until = until,
                provider = provider,
                model = model,
            ),
        )
    }
    fun operations(
        stage: TranslationStage,
        since: Long? = null,
        until: Long? = null,
        state: TranslationOperationState? = null,
    ) {
        val query = report?.query ?: return
        navigator.push(
            TranslationOperationsScreen(
                since ?: query.since,
                until ?: query.until,
                stage,
                provider,
                model,
                state = state,
            ),
        )
    }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text("Live translator dashboard", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = {
                showFilters = true
            }) { Icon(MaterialSymbols.Rounded.FilterList, "Dashboard filters") }
            TranslationRowActionMenu(
                listOf(
                    TranslationRowAction("Refresh measurements", MaterialSymbols.Rounded.Refresh) { refresh++ },
                    TranslationRowAction("Backups and storage", MaterialSymbols.Rounded.Folder) {
                        navigator.push(TranslationManagementScreen())
                    },
                    TranslationRowAction("Underlying logs", MaterialSymbols.Rounded.Info, report != null) { logs() },
                ),
                "Dashboard actions",
            )
        }
        Text(
            report?.let { "Measured ${DateFormat.getTimeInstance().format(Date(it.generatedAt))}" }
                ?: "Loading persisted measurements…",
            style = MaterialTheme.typography.bodySmall,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            "Past ${windowHours}h · ${provider ?: "All providers"} · ${model ?: "All models"}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (showFilters) {
            AdaptiveSheet(onDismissRequest = { showFilters = false }) {
                Column(Modifier.padding(vertical = 12.dp)) {
                    ListPreferenceWidget(
                        value = windowHours,
                        title = "Time window",
                        subtitle = "Past ${windowHours}h",
                        icon = null,
                        entries = mapOf(
                            1L to "Past hour",
                            24L to "Past 24 hours",
                            168L to "Past 7 days",
                            720L to "Past 30 days",
                        ),
                        onValueChange = {
                            windowHours = it
                            breakdownOffset = 0
                        },
                    )
                    TextPreferenceWidget(
                        title = "Provider and model",
                        subtitle = "${provider ?: "All providers"} · ${model ?: "All models"}. " +
                            "Select a provider/model from its breakdown menu.",
                    )
                    TextPreferenceWidget(title = "Clear provider and model filters", onPreferenceClick = {
                        provider = null
                        model = null
                        breakdownOffset = 0
                    })
                }
            }
        }
        Text(
            "${active.map {
                it.mangaId
            }.distinct().size} active series · ${active.size} active chapters matching provider/model filters",
        )
        Text("${models.count { it.status == PaddleModelStatus.DOWNLOADING }} model downloads across all providers")
        active.forEach { job ->
            val operation = operations.firstOrNull {
                it.jobId == job.id && it.state == TranslationOperationState.ACTIVE
            }
            Card(Modifier.fillMaxWidth().clickable { navigator.push(TranslationScreen(job.mangaId)) }) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TranslationStatusIcon(job.state, settings.queueColors)
                        Text(
                            "${job.mangaTitle} · ${job.chapterTitle}",
                            Modifier.weight(1f),
                            color = translationStateColor(job.state, settings.queueColors),
                        )
                        TranslationRowActionMenu(
                            listOf(
                                TranslationRowAction("Open queue", MaterialSymbols.Rounded.Folder) {
                                    navigator.push(TranslationScreen(job.mangaId))
                                },
                                TranslationRowAction("Related logs", MaterialSymbols.Rounded.Info) {
                                    navigator.push(TranslationLogsScreen(job.id, operationId = operation?.id))
                                },
                            ),
                            "Actions for ${job.chapterTitle}",
                        )
                    }
                    Text(
                        operation?.description()
                            ?: "${job.state.name.lowercase()} · ${job.completedImages}/${job.imageCount.takeIf {
                                it > 0
                            } ?: "?"} saved pages",
                    )
                    (operation?.message ?: job.message)?.let { Text(it) }
                }
            }
        }
        report?.let { value ->
            Text("Measured operations", style = MaterialTheme.typography.titleMedium)
            Text(
                "${value.successfulRequests} successful requests · ${value.failedRequests} failed requests · " +
                    "${value.retryAttempts} retry attempts",
                Modifier.clickable {
                    operations(TranslationStage.REQUEST)
                },
            )
            Text(
                "${value.pageSaves} save operations · ${value.uniqueSavedPages} distinct saved pages · " +
                    "${value.partialOutputs} partial outputs",
                Modifier.clickable {
                    operations(TranslationStage.SAVE, state = TranslationOperationState.COMPLETED)
                },
            )
            Text(
                "HTTP success, valid output, saved pages and pending review are separate outcomes. " +
                    "${value.savesWithoutPageIdentity} saves lack a page identity; " +
                    "${value.operationsWithoutProvider} operations lack provider attribution.",
            )
            Text(
                "Mean request latency: ${value.latency.meanMillis?.let {
                    "${it.toLong()} ms"
                } ?: "Unavailable"} · ${value.latency.observations} measured · " +
                    "${value.latency.unavailable} unavailable",
            )
            Text(
                "Approximate latency intervals: p50 ${value.latency.intervalLabel(
                    0.5,
                )} · p95 ${value.latency.intervalLabel(0.95)}",
            )
            Text(
                "Requests by update time — tap a column for its operations",
                style = MaterialTheme.typography.titleSmall,
            )
            DashboardChart(value.bins.map { it.requests }) { index ->
                value.bins[index].let {
                    navigator.push(
                        TranslationOperationsScreen(it.since, it.until, TranslationStage.REQUEST, provider, model),
                    )
                }
            }
            Text("Page saves over time", style = MaterialTheme.typography.titleSmall)
            DashboardChart(
                value.bins.map {
                    it.pageSaves
                },
            ) { index ->
                value.bins[index].let {
                    operations(TranslationStage.SAVE, it.since, it.until, TranslationOperationState.COMPLETED)
                }
            }
            Text(
                "Each chart spans ${DateFormat.getDateTimeInstance().format(
                    Date(value.query.since),
                )} to ${DateFormat.getDateTimeInstance().format(Date(value.query.until))}.",
                style = MaterialTheme.typography.bodySmall,
            )
            value.stages.forEach { stage ->
                Text(
                    "${stage.stage.label}: ${stage.states.values.sum()} operations · " +
                        stage.states.entries.joinToString {
                            "${it.value} ${it.key.name.lowercase()}"
                        } + "\n" +
                        "${stage.durations.sumMillis ?: "Unavailable"} measured ms · " +
                        "${stage.durations.observations} timed · " + stage.completedUnits.entries.joinToString {
                            "${it.value} ${it.key.label}"
                        },
                    Modifier.clickable { operations(stage.stage) },
                )
            }
            Text("Application usage and estimates", style = MaterialTheme.typography.titleMedium)
            Text("${value.usage.attempts} distinct generation attempts · ${value.usage.uncertain} uncertain outcomes")
            UsageSummary(value.usage)
            TextButton(onClick = {
                navigator.push(TranslationUsageScreen(value.query.since, value.query.until, provider, model))
            }) { Text("Usage, applied prices and reservations") }
            Text(
                "Reservations are possible exposure, not billed charges. " +
                    "Missing usage or prices remain unavailable. Cloud costs and invoices are shown separately below.",
            )
            Text("Provider / model breakdown", style = MaterialTheme.typography.titleMedium)
            value.breakdowns.forEach { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "${row.key.provider} / ${row.key.model} · ${row.usage.attempts} attempts",
                                Modifier.weight(1f),
                            )
                            TranslationRowActionMenu(
                                listOf(
                                    TranslationRowAction("Filter dashboard", MaterialSymbols.Rounded.FilterList) {
                                        provider = row.key.provider
                                        model = row.key.model
                                        breakdownOffset = 0
                                    },
                                    TranslationRowAction("Request records", MaterialSymbols.Rounded.Info) {
                                        navigator.push(
                                            TranslationUsageScreen(
                                                value.query.since,
                                                value.query.until,
                                                row.key.provider,
                                                row.key.model,
                                            ),
                                        )
                                    },
                                ),
                                "Actions for ${row.key.provider} / ${row.key.model}",
                            )
                        }
                        UsageSummary(row.usage)
                    }
                }
            }
            Row {
                TextButton(enabled = breakdownOffset > 0, onClick = {
                    breakdownOffset =
                        (breakdownOffset - 25).coerceAtLeast(0)
                }) { Text("Previous models") }
                TextButton(enabled = value.moreBreakdowns, onClick = { breakdownOffset += 25 }) { Text("More models") }
            }
        }
        Text(
            "Translator private storage: ${storage?.let {
                "$it bytes"
            } ?: "Unavailable"} (logical file sizes; external exports and SQLite free pages are separate)",
        )
        models.filter {
            it.status == PaddleModelStatus.DOWNLOADING || it.status == PaddleModelStatus.FAILED
        }.forEach { pack ->
            Text(
                "${if (pack.status == PaddleModelStatus.FAILED) "✕" else "↓"} " +
                    "${pack.profile.name.lowercase()} pack · " +
                    "${pack.downloadBytes}/${pack.totalBytes} bytes · ${pack.status.name.lowercase()}",
            )
            pack.error?.let { Text(it) }
            pack.operationId?.let { id ->
                TextButton(onClick = {
                    navigator.push(TranslationLogsScreen(operationId = id))
                }) { Text("Download subprocesses and API logs") }
            }
        }
        TranslationAccountingContent(onOpenLogs = { navigator.push(TranslationLogsScreen(it)) })
    }
}

@Composable
private fun UsageSummary(usage: TranslationDashboardUsage) {
    Text(
        "Input ${usage.input.label()} · Output ${usage.output.label()} · " +
            "Cached ${usage.cached.label()} · Reasoning ${usage.reasoning.label()}",
    )
    Text("Estimated USD ${usage.estimatedUsd.label()}")
    usage.reservations.forEach { (currency, amount) -> Text("Outstanding $currency reservations: ${amount.label()}") }
    if (usage.unknownReservationCurrency >
        0
    ) {
        Text("${usage.unknownReservationCurrency} reservations have unavailable currency")
    }
}
private fun TranslationMeasuredTotal.label() =
    value?.let { "$it${if (unavailable > 0) " ($unavailable unavailable)" else ""}" }
        ?: "Unavailable ($unavailable records)"
private fun TranslationLatencySummary.intervalLabel(percentile: Double) =
    percentileInterval(percentile)?.let { (low, high) -> "$low–${high?.toString() ?: "∞"} ms" } ?: "Unavailable"

@Composable
private fun DashboardChart(values: List<Long>, onBin: (Int) -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val onSelected by rememberUpdatedState(onBin)
    Canvas(
        Modifier.fillMaxWidth().height(100.dp).pointerInput(values) {
            detectTapGestures { point ->
                if (values.isNotEmpty() &&
                    size.width > 0
                ) {
                    onSelected((point.x / size.width * values.size).toInt().coerceIn(values.indices))
                }
            }
        },
    ) {
        val maximum = values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val width = size.width / values.size.coerceAtLeast(1)
        values.forEachIndexed { index, value ->
            val height = (value.toDouble() / maximum * size.height).toFloat()
            drawRect(color, Offset(index * width, size.height - height), Size((width - 2).coerceAtLeast(1f), height))
        }
    }
    Text("${values.sum()} measured records", style = MaterialTheme.typography.bodySmall)
}
