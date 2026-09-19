package mihon.feature.translation.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.app.di.appGraph
import mihon.feature.translation.TranslationManager
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.FilterList
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Pause
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.SelectAll
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.Share
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationLogQuery
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.service.seriesHistoryKey
import java.text.DateFormat
import java.util.Date

class TranslationScreen(private val mangaId: Long? = null, private val initialTab: Int = 0) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val graph = context.appGraph
        val manager = graph.translationManager
        val jobs by manager.jobs.collectAsState(emptyList())
        var tab by remember { mutableStateOf(initialTab) }
        var selectionUi by remember { mutableStateOf<TranslationSelectionUi?>(null) }
        val clearSelection = selectionUi?.clear
        var queueSearch by remember { mutableStateOf<String?>(null) }
        var logSearch by remember { mutableStateOf<String?>(null) }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val action: (suspend () -> Unit) -> Unit = { block ->
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { block() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    snackbar.showSnackbar(e.message ?: "Translation action failed")
                }
            }
        }
        Scaffold(
            topBar = {
                val navigateUp = {
                    val clear = clearSelection
                    if (clear != null) {
                        clear()
                    } else if (!navigator.pop()) {
                        (context as? android.app.Activity)?.finish()
                    }
                    Unit
                }
                if (selectionUi != null) {
                    AppBar(
                        title = "Translator",
                        actionModeCounter = selectionUi!!.count,
                        onCancelActionMode = { clearSelection?.invoke() },
                    )
                } else if (tab == 0 || tab == 1) {
                    SearchToolbar(
                        searchQuery = if (tab == 0) queueSearch else logSearch,
                        onChangeSearchQuery = { if (tab == 0) queueSearch = it else logSearch = it },
                        titleContent = { Text("Translator") },
                        navigateUp = navigateUp,
                        onClickCloseSearch = {
                            if (clearSelection !=
                                null
                            ) {
                                clearSelection?.invoke()
                            } else if (tab == 0) {
                                queueSearch = null
                            } else {
                                logSearch = null
                            }
                        },
                        placeholderText = if (tab ==
                            0
                        ) {
                            "Series, chapter, model or error"
                        } else {
                            "Messages, stages or request IDs"
                        },
                    )
                } else {
                    AppBar(title = "Translator", navigateUp = navigateUp)
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    listOf("Queue", "Logs", "Settings", "Dashboard", "Manage").forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
                when (tab) {
                    0 -> TranslationQueueContent(
                        jobs = jobs.filter { mangaId == null || it.mangaId == mangaId },
                        query = queueSearch,
                        manager = manager,
                        onAction = action,
                        onAdd = {
                            navigator.push(TranslationChapterPickerScreen(mangaId?.let { listOf(it) }.orEmpty()))
                        },
                        onInspect = { navigator.push(TranslationInspectorScreen(it)) },
                        onLogs = { navigator.push(TranslationLogsScreen(it)) },
                        onSelectionChanged = { selectionUi = it },
                    )
                    1 -> TranslationLogsContent(TranslationLogQuery(search = logSearch.orEmpty()), {
                        selectionUi = it
                    }, action)
                    3 -> TranslationDashboardContent()
                    4 -> TranslationManagementScreen(
                        mangaId?.let { id ->
                            jobs.filter { it.mangaId == id }.map { it.id }
                        }.orEmpty(),
                        embedded = true,
                    ).Content { selectionUi = it }
                    else -> TranslationSettingsContent(mangaId) { count, clear ->
                        selectionUi = if (count > 0) TranslationSelectionUi(count, clear) else null
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslationQueueContent(
    jobs: List<TranslationJob>,
    query: String?,
    manager: TranslationManager,
    onAction: (suspend () -> Unit) -> Unit,
    onAdd: () -> Unit,
    onInspect: (String) -> Unit,
    onLogs: (String) -> Unit,
    onSelectionChanged: (TranslationSelectionUi?) -> Unit,
) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val reviews by remember {
        context.appGraph.translationRepository.observeReviewSummaries()
    }.collectAsState(emptyList())
    val currentReviews = remember(reviews) { reviews.groupBy { it.jobId } }
    var state by remember { mutableStateOf<TranslationJobState?>(null) }
    var provider by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf<TranslationMode?>(null) }
    var newest by remember { mutableStateOf(true) }
    var selection by remember { mutableStateOf(TranslationQueueSelection()) }
    val selected = selection.jobIds
    LaunchedEffect(selected, selection.operations.keys) {
        onSelectionChanged(
            if (!selection.active) {
                null
            } else {
                TranslationSelectionUi(selection.count) {
                    selection = TranslationQueueSelection()
                }
            },
        )
    }
    DisposableEffect(Unit) { onDispose { onSelectionChanged(null) } }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var collapsedSeries by remember { mutableStateOf(setOf<String>()) }
    var language by remember { mutableStateOf("") }
    var qualityState by remember { mutableStateOf<QualityReviewState?>(null) }
    var unreviewedOnly by remember { mutableStateOf(false) }
    var showBackgroundHelp by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var menuJob by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Set<String>?>(null) }
    var deletingSubprocessLogs by remember { mutableStateOf<Set<String>?>(null) }
    var subprocessExport by remember { mutableStateOf<SelectedSubprocessDiagnostics?>(null) }
    var pendingSubprocessExport by remember { mutableStateOf<Pair<SelectedSubprocessDiagnostics, Boolean>?>(null) }
    fun finishSubprocessExport(uri: android.net.Uri?) {
        val frozen = pendingSubprocessExport
        pendingSubprocessExport = null
        if (uri != null) {
            onAction {
                val request = requireNotNull(frozen) {
                    "The export selection is unavailable. Select subprocesses again."
                }
                context.contentResolver.openOutputStream(uri)!!.use { output ->
                    exportSelectedSubprocessDiagnostics(
                        manager.repository,
                        request.first,
                        output,
                        request.second,
                        context.appGraph.translationDiagnostics::export,
                    )
                }
            }
        }
    }
    val exportSubprocessJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson"),
        ::finishSubprocessExport,
    )
    val exportSubprocessZip = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
        ::finishSubprocessExport,
    )
    var replacement by remember {
        mutableStateOf<Pair<TranslationJob, tachiyomi.domain.translation.model.TranslationSettings>?>(null)
    }
    var replacing by remember { mutableStateOf(false) }
    val preferencesRevision by manager.preferences.revision.collectAsState()
    val queueColors = remember(preferencesRevision) { manager.preferences.settings.value.queueColors }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(jobs.any { it.state in TranslationManager.activeStates }) {
        while (jobs.any { it.state in TranslationManager.activeStates }) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val filtered = jobs.filter {
        (state == null || it.state == state) && (provider.isEmpty() || it.settings.provider.kind.name == provider) &&
            (mode == null || it.settings.mode == mode) &&
            (language.isEmpty() || it.settings.targetLanguage == language) &&
            (!unreviewedOnly || currentReviews[it.id].isNullOrEmpty()) &&
            (qualityState == null || currentReviews[it.id].orEmpty().any { review -> review.state == qualityState }) &&
            (
                listOf(
                    it.mangaTitle,
                    it.chapterTitle,
                    it.settings.targetLanguage,
                    it.settings.provider.model,
                    it.message.orEmpty(),
                ) + currentReviews[it.id].orEmpty().flatMap { review ->
                    listOf(review.state.name, review.message.orEmpty()) +
                        review.findings.map { finding -> finding.description }
                }
                )
                .any { field -> field.contains(query.orEmpty(), ignoreCase = true) }
    }.let { if (newest) it.sortedByDescending { job -> job.createdAt } else it }

    Column(Modifier.fillMaxSize()) {
        val active = jobs.filter { it.state in TranslationManager.activeStates }
        Text(
            "${active.map { it.mangaId }.distinct().size} active series · ${active.size} active chapters",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        if (!selection.active) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onAdd) { Icon(MaterialSymbols.Rounded.Add, "Add chapters") }
                IconButton(onClick = {
                    showFilters = true
                }) { Icon(MaterialSymbols.Rounded.FilterList, "Filter and sort queue") }
                TranslationRowActionMenu(
                    listOf(
                        TranslationRowAction("Pause all", MaterialSymbols.Rounded.Pause) {
                            onAction { manager.pause() }
                        },
                        TranslationRowAction("Resume all", MaterialSymbols.RoundedFilled.PlayArrow) {
                            onAction {
                                val unfinished = manager.repository.jobs().filter {
                                    it.state !=
                                        TranslationJobState.COMPLETED
                                }
                                val imports = unfinished.filter { it.isStructuredFiles }
                                if (unfinished.any { !it.isStructuredFiles }) manager.resume()
                                if (imports.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        navigator.push(
                                            TranslationStructuredImportScreen(
                                                imports.map {
                                                    it.mangaId
                                                }.distinct(),
                                                imports.map { it.chapterId }.distinct(),
                                            ),
                                        )
                                    }
                                }
                            }
                        },
                        TranslationRowAction("Select matching jobs", MaterialSymbols.Rounded.SelectAll) {
                            selection = selection.selectJobs(filtered.map { it.id }.toSet())
                        },
                        TranslationRowAction("Background help", MaterialSymbols.Rounded.Info) {
                            showBackgroundHelp =
                                true
                        },
                    ),
                    "Queue actions",
                )
            }
        }
        if (showFilters) {
            AdaptiveSheet(onDismissRequest = { showFilters = false }) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(selected = state == null, onClick = {
                            state = null
                        }, label = { Text("All ${jobs.size}") })
                        TranslationJobState.entries.forEach { value ->
                            FilterChip(selected = state == value, onClick = {
                                state = if (state ==
                                    value
                                ) {
                                    null
                                } else {
                                    value
                                }
                            }, label = { Text(value.name.lowercase()) })
                        }
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        jobs.map { it.settings.provider.kind.name }.distinct().forEach { value ->
                            FilterChip(selected = provider == value, onClick = {
                                provider = if (provider ==
                                    value
                                ) {
                                    ""
                                } else {
                                    value
                                }
                            }, label = { Text(value.lowercase()) })
                        }
                        TranslationMode.entries.forEach { value ->
                            FilterChip(selected = mode == value, onClick = {
                                mode = if (mode ==
                                    value
                                ) {
                                    null
                                } else {
                                    value
                                }
                            }, label = { Text(value.name) })
                        }
                        jobs.map { it.settings.targetLanguage }.distinct().forEach { value ->
                            FilterChip(selected = language == value, onClick = {
                                language = if (language ==
                                    value
                                ) {
                                    ""
                                } else {
                                    value
                                }
                            }, label = { Text("→ $value") })
                        }
                        FilterChip(selected = newest, onClick = { newest = !newest }, label = { Text("Newest first") })
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(qualityState == null && !unreviewedOnly, {
                            qualityState = null
                            unreviewedOnly = false
                        }, label = { Text("All AI reviews") })
                        FilterChip(unreviewedOnly, {
                            unreviewedOnly = !unreviewedOnly
                            qualityState = null
                        }, label = { Text("Not reviewed") })
                        QualityReviewState.entries.forEach { value ->
                            FilterChip(qualityState == value, {
                                qualityState = if (qualityState ==
                                    value
                                ) {
                                    null
                                } else {
                                    value
                                }
                                unreviewedOnly = false
                            }, label = { Text("AI ${value.name.lowercase().replace('_', ' ')}") })
                        }
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            if (filtered.isEmpty()) {
                item {
                    Text("No matching translation jobs. Add chapters to get started.", Modifier.padding(24.dp))
                }
            }
            filtered.groupBy { it.seriesHistoryKey() }.forEach { (groupKey, chapters) ->
                item(key = groupKey) {
                    val seriesId = groupKey
                    val seriesState = when {
                        chapters.any { it.state in TranslationManager.activeStates } -> TranslationJobState.TRANSLATING
                        chapters.any { it.state == TranslationJobState.FAILED } -> TranslationJobState.FAILED
                        chapters.all { it.state == TranslationJobState.COMPLETED } -> TranslationJobState.COMPLETED
                        chapters.any { it.completedImages > 0 } -> TranslationJobState.PARTIAL
                        chapters.any { it.state == TranslationJobState.PAUSED } -> TranslationJobState.PAUSED
                        else -> TranslationJobState.QUEUED
                    }
                    Text(
                        "${if (seriesId in collapsedSeries) "▸" else "▾"} ${translationStateSymbol(
                            seriesState,
                        )} ${chapters.first().mangaTitle}" +
                            " · ${chapters.size} chapters",
                        color = translationStateColor(seriesState, queueColors),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            onLongClickLabel = "Select chapters in this series",
                            onLongClick = { selection = selection.selectJobs(selected + chapters.map { it.id }) },
                            onClick = {
                                val ids = chapters.map { it.id }.toSet()
                                if (selected.isNotEmpty()) {
                                    selection =
                                        selection.selectJobs(
                                            if (selected.containsAll(ids)) {
                                                selected - ids
                                            } else {
                                                selected +
                                                    ids
                                            },
                                        )
                                } else {
                                    collapsedSeries = collapsedSeries.toggle(seriesId)
                                }
                            },
                        ).padding(12.dp),
                    )
                }
                items(chapters.takeUnless { groupKey in collapsedSeries }.orEmpty(), key = { it.id }) { job ->
                    val actions = queueJobActions(
                        job, jobs, manager, onAction, onInspect, onLogs,
                        onRead = {
                            context.startActivity(ReaderActivity.newIntent(context, job.mangaId, job.chapterId))
                        },
                        onManage = { navigator.push(TranslationManagementScreen(listOf(job.id))) },
                        onDelete = { deleting = setOf(job.id) },
                        onReplace = { onAction { replacement = job to manager.replacementSettings(job.id) } },
                        onImport = { imported ->
                            navigator.push(
                                TranslationStructuredImportScreen(listOf(imported.mangaId), listOf(imported.chapterId)),
                            )
                        },
                    )
                    val swipeActions = mapOf(
                        TranslationGestureAction.STATE_ACTION to actions.first(),
                        TranslationGestureAction.EXPORT to actions.first { it.label == "Export options" },
                        TranslationGestureAction.DELETE to actions.first { it.label == "Delete with preview" },
                        TranslationGestureAction.LOGS to actions.first { it.label == "Related logs" },
                        TranslationGestureAction.ACTIONS to
                            TranslationRowAction("Actions", MaterialSymbols.Rounded.MoreVert) { menuJob = job.id },
                    )
                    TranslationActionRow(
                        selected = job.id in selected,
                        selectionActive = selection.active,
                        onClick = {
                            if (selected.isNotEmpty()) {
                                selection = selection.selectJobs(selected.toggle(job.id))
                            } else {
                                expanded = expanded.toggle(job.id)
                            }
                        },
                        onLongClick = { selection = selection.selectJobs(selected.toggle(job.id)) },
                        startAction = configuredTranslationSwipe(
                            TranslationGestureRow.WORK,
                            TranslationGestureDirection.START,
                            swipeActions,
                        ),
                        endAction = configuredTranslationSwipe(
                            TranslationGestureRow.WORK,
                            TranslationGestureDirection.END,
                            swipeActions,
                        ),
                    ) {
                        Column(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TranslationStatusIcon(job.state, queueColors)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        job.chapterTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = translationStateColor(job.state, queueColors),
                                    )
                                    Text(
                                        "${job.state.name.lowercase()}" +
                                            " · ${job.completedImages}/${job.imageCount.takeIf {
                                                it > 0
                                            } ?: "?"} saved pages",
                                        color =
                                        queueColors[job.state.name]?.let { Color(it.toInt()) }
                                            ?: translationStateColor(job.state),
                                    )
                                    Text(
                                        "${job.settings.mode} · ${job.settings.targetLanguage}" +
                                            " · ${job.settings.provider.model}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    val jobReviews = currentReviews[job.id].orEmpty()
                                    val reviewSummary = jobReviews.groupingBy {
                                        it.state
                                    }.eachCount().entries.joinToString { (reviewState, count) ->
                                        "$count ${reviewState.name.lowercase().replace('_', ' ')}"
                                    }
                                    Text(
                                        "AI review: ${reviewSummary.ifBlank {
                                            "not reviewed"
                                        }} · Human review pending",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (!selection.active) {
                                    androidx.compose.foundation.layout.Box {
                                        IconButton(onClick = {
                                            menuJob = job.id
                                        }) { Icon(MaterialSymbols.Rounded.MoreVert, "Actions for ${job.chapterTitle}") }
                                        TranslationActionMenu(menuJob == job.id, { menuJob = null }, actions)
                                    }
                                }
                            }
                            if (job.imageCount > 0) {
                                LinearProgressIndicator(
                                    progress = {
                                        job.completedImages.toFloat() / job.imageCount
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            job.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (job.id in expanded) {
                                val batches by produceState(
                                    initialValue = emptyList<tachiyomi.domain.translation.model.TranslationBatch>(),
                                    job.updatedAt,
                                ) {
                                    value = manager.repository.batches(job.id)
                                }
                                val results by produceState(
                                    initialValue = emptyList<TranslationPageResult>(),
                                    job.updatedAt,
                                ) {
                                    value = manager.repository.results(job.id)
                                }
                                val elapsed =
                                    (
                                        (if (job.state in TranslationManager.activeStates) now else job.updatedAt) -
                                            job.createdAt
                                        ).coerceAtLeast(0) /
                                        1000
                                Text(
                                    "Created ${DateFormat.getDateTimeInstance().format(
                                        Date(job.createdAt),
                                    )} · elapsed ${elapsed / 60}m ${elapsed % 60}s",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Limits: ${job.settings.concurrency.series} series / " +
                                        "${job.settings.concurrency.chapters} chapters per series / " +
                                        "${job.settings.concurrency.images} images per chapter",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (job.settings.mode in
                                    setOf(TranslationMode.MAX, TranslationMode.HALVING)
                                ) {
                                    Text(
                                        "Image concurrency is bypassed in this mode.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                val operations by remember(job.id) {
                                    manager.repository.observeOperations(job.id, 200)
                                }.collectAsState(emptyList())
                                LaunchedEffect(operations) {
                                    selection = selection.refreshOperations(operations)
                                }
                                if (operations.isEmpty()) {
                                    Text(
                                        "Historical subprocess timings unavailable. " +
                                            "Saved results and batch outcomes remain inspectable.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                operations.sortedBy { it.startedAt ?: it.updatedAt }.forEach { operation ->
                                    key(operation.id) {
                                        var operationMenu by remember(operation.id) { mutableStateOf(false) }
                                        val logsAction =
                                            TranslationRowAction(
                                                "Related subprocess logs",
                                                MaterialSymbols.Rounded.Info,
                                            ) {
                                                navigator.push(
                                                    TranslationLogsScreen(job.id, operationId = operation.id),
                                                )
                                            }
                                        val operationActions =
                                            listOf(
                                                logsAction,
                                                actions.first(),
                                                actions.first {
                                                    it.label ==
                                                        "Inspect OCR and results"
                                                },
                                            )
                                        val gestureActions = mapOf(
                                            TranslationGestureAction.LOGS to logsAction,
                                            TranslationGestureAction.ACTIONS to
                                                TranslationRowAction(
                                                    "Subprocess actions",
                                                    MaterialSymbols.Rounded.MoreVert,
                                                ) {
                                                    operationMenu =
                                                        true
                                                },
                                        )
                                        TranslationActionRow(
                                            selected = operation.id in selection.operations,
                                            selectionActive = selection.active,
                                            onClick = {
                                                if (selection.active) {
                                                    selection = selection.toggleOperation(operation)
                                                } else {
                                                    logsAction.onAction()
                                                }
                                            },
                                            onLongClick = { selection = selection.toggleOperation(operation) },
                                            startAction = configuredTranslationSwipe(
                                                TranslationGestureRow.SUBPROCESS,
                                                TranslationGestureDirection.START,
                                                gestureActions,
                                            ),
                                            endAction = configuredTranslationSwipe(
                                                TranslationGestureRow.SUBPROCESS,
                                                TranslationGestureDirection.END,
                                                gestureActions,
                                            ),
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                TranslationStatusIcon(operation.state.jobState(), queueColors)
                                                Column(Modifier.weight(1f)) {
                                                    Text(
                                                        operation.description(),
                                                        color = translationStateColor(
                                                            operation.state.jobState(),
                                                            queueColors,
                                                        ),
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                                    operation.message?.takeUnless { it == operation.stage.label }?.let {
                                                        Text(it, style = MaterialTheme.typography.bodySmall)
                                                    }
                                                }
                                                if (!selection.active) {
                                                    androidx.compose.foundation.layout.Box {
                                                        IconButton(onClick = {
                                                            operationMenu = true
                                                        }) {
                                                            Icon(MaterialSymbols.Rounded.MoreVert, "Subprocess actions")
                                                        }
                                                        TranslationActionMenu(operationMenu, {
                                                            operationMenu = false
                                                        }, operationActions)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (operations.size >=
                                    200
                                ) {
                                    Text("Latest 200 subprocesses shown. Open logs for full history.")
                                }
                                batches.forEach { batch ->
                                    val imageNumbers = batch.imageIds.joinToString { imageNumber(it) }
                                    val translationStage = if (job.settings.ocr.pipeline == OcrPipeline.PADDLE) {
                                        "text translation"
                                    } else {
                                        "AI OCR and translation"
                                    }
                                    Text(
                                        "${if (batch.parentId != null) "↳ " else ""}${batch.state}" +
                                            " · $translationStage" +
                                            " · images $imageNumbers · ${batch.attempts} provider attempt(s)",
                                        modifier = Modifier.clickable {
                                            if (selected.isNotEmpty()) {
                                                selection = selection.selectJobs(selected.toggle(job.id))
                                            } else if (!selection.active) {
                                                navigator.push(TranslationLogsScreen(job.id, batchId = batch.id))
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    batch.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                                results.sortedBy { it.imageId.toIntOrNull() }.forEach { page ->
                                    Text(
                                        "✓ Image ${imageNumber(page.imageId)}" +
                                            " · saved translation · ${page.regions.size} stored regions",
                                        modifier = Modifier.clickable {
                                            if (selected.isNotEmpty()) {
                                                selection = selection.selectJobs(selected.toggle(job.id))
                                            } else if (!selection.active) {
                                                navigator.push(TranslationLogsScreen(job.id, imageId = page.imageId))
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        TranslationSelectionBar(
            selected.size,
            { selection = TranslationQueueSelection() },
            listOf(
                TranslationRowAction("Pause selected", MaterialSymbols.Rounded.Pause) {
                    val ids = selected.toList()
                    onAction { ids.forEach { manager.pause(it) } }
                },
                TranslationRowAction("Resume or retry unfinished selected work", MaterialSymbols.Rounded.Refresh) {
                    val ids = selected.toSet()
                    onAction {
                        val unfinished = manager.repository.jobs().filter {
                            it.id in ids && it.state != TranslationJobState.COMPLETED
                        }
                        val imports = unfinished.filter { it.isStructuredFiles }
                        unfinished.filterNot { it in imports }.forEach {
                            if (it.state == TranslationJobState.PAUSED) {
                                manager.resume(it.id)
                            } else if (it.state !in TranslationManager.activeStates) {
                                manager.retry(it.id, force = false)
                            }
                        }
                        if (imports.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                navigator.push(
                                    TranslationStructuredImportScreen(
                                        imports.map { it.mangaId }.distinct(),
                                        imports.map { it.chapterId }.distinct(),
                                    ),
                                )
                            }
                        }
                    }
                },
                TranslationRowAction("Export selected", MaterialSymbols.Rounded.Share) {
                    navigator.push(TranslationManagementScreen(selected.toList()))
                },
                TranslationRowAction("Delete selected with preview", MaterialSymbols.Rounded.Delete) {
                    deleting =
                        selected.toSet()
                },
                TranslationRowAction("Select matching jobs", MaterialSymbols.Rounded.SelectAll) {
                    selection = selection.selectJobs(filtered.map { it.id }.toSet())
                },
                TranslationRowAction("Cancel selected", MaterialSymbols.Rounded.Close) {
                    val ids = selected.toSet()
                    onAction { ids.forEach { manager.cancel(it) } }
                },
            ),
            showCounter = false,
        )
        TranslationSelectionBar(
            selection.operations.size,
            { selection = TranslationQueueSelection() },
            listOf(
                TranslationRowAction(
                    "Open subprocess logs (select one)",
                    MaterialSymbols.Rounded.Info,
                    selection.operations.size == 1,
                ) {
                    selection.operations.values.singleOrNull()?.let {
                        navigator.push(TranslationLogsScreen(it.jobId, operationId = it.id))
                    }
                },
                TranslationRowAction("Export selected subprocess diagnostics", MaterialSymbols.Rounded.Share) {
                    subprocessExport = selection.exportSnapshot(System.currentTimeMillis())
                },
                TranslationRowAction("Delete chapter logs with preview", MaterialSymbols.Rounded.Delete) {
                    deletingSubprocessLogs = selection.operations.values.map { it.jobId }.toSet()
                },
            ),
            showCounter = false,
        )
    }
    subprocessExport?.let { frozen ->
        AlertDialog(
            onDismissRequest = { subprocessExport = null },
            title = { Text("Export ${frozen.operations.size} subprocess diagnostics") },
            text = {
                Column {
                    Text(
                        "Both formats include the selected operation snapshots and their related logs. " +
                            "The ZIP also includes available sanitized API captures. Credentials, image bodies, " +
                            "thought signatures and resumable correction inputs are omitted.",
                    )
                    TextButton(onClick = {
                        pendingSubprocessExport = frozen to false
                        subprocessExport = null
                        exportSubprocessJson.launch("mihon-subprocess-diagnostics.jsonl")
                    }) { Text("Structured diagnostics (JSONL)") }
                    TextButton(onClick = {
                        pendingSubprocessExport = frozen to true
                        subprocessExport = null
                        exportSubprocessZip.launch("mihon-subprocess-diagnostics.zip")
                    }) { Text("Diagnostics and sanitized API (ZIP)") }
                }
            },
            confirmButton = { TextButton(onClick = { subprocessExport = null }) { Text("Cancel") } },
        )
    }
    deletingSubprocessLogs?.let { ids ->
        TranslationDeletionDialog(
            context.appGraph.translationDeletionService,
            ids,
            onDismiss = { deletingSubprocessLogs = null },
            onDeleted = {
                selection = selection.withoutJobs(ids)
                deletingSubprocessLogs = null
            },
            initialScopes = setOf(tachiyomi.domain.translation.model.TranslationDeletionScope.LOGS),
            allowedScopes = setOf(tachiyomi.domain.translation.model.TranslationDeletionScope.LOGS),
            scopeDescription = "This preview deletes all logs, batch history and captures for " +
                "${ids.size} chapter histories, including subprocesses that are not selected. " +
                "Saved translations, geometry recovery checkpoints and usage accounting remain.",
        )
    }
    deleting?.let { ids ->
        TranslationDeletionDialog(
            context.appGraph.translationDeletionService,
            ids,
            onDismiss = { deleting = null },
            onDeleted = {
                selection = selection.withoutJobs(ids)
                deleting = null
            },
        )
    }
    replacement?.let { (old, settings) ->
        AlertDialog(
            onDismissRequest = { if (!replacing) replacement = null },
            title = { Text("Replace this job's configuration") },
            text = {
                Column {
                    Text("${old.mangaTitle} · ${old.chapterTitle}")
                    Text(
                        "${settings.provider.kind.name} · ${settings.provider.model}\n" +
                            "${settings.ocr.pipeline.name} · ${settings.mode.name}\n" +
                            "${settings.sourceLanguage} → ${settings.targetLanguage}\n" +
                            "Review: " +
                            if (settings.qualityReview.enabled) settings.qualityReview.coverage.name else "off",
                    )
                    Text(
                        "The new job uses these settings. The old job is paused and its saved translations, " +
                            "raw OCR, edits and review history are retained. " +
                            "Pages with matching saved content are reused; " +
                            "only unfinished pages are queued. This action can make paid requests " +
                            "for those unfinished pages.",
                    )
                    if (old.settings.targetLanguage != settings.targetLanguage) {
                        Text(
                            "Saved pages retain their original target language (${old.settings.targetLanguage}). " +
                                "Unfinished pages use ${settings.targetLanguage}, " +
                                "so this chapter may contain both languages.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (replacing) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = !replacing, onClick = {
                    replacing = true
                    onAction {
                        try {
                            val successor = manager.replaceUnfinished(old.id, settings)
                            state = null
                            expanded = expanded + successor.id
                            replacement = null
                        } finally {
                            replacing = false
                        }
                    }
                }) { Text("Create replacement") }
            },
            dismissButton = { TextButton(enabled = !replacing, onClick = { replacement = null }) { Text("Cancel") } },
        )
    }
    if (showBackgroundHelp) TranslationBackgroundHelp(onDismiss = { showBackgroundHelp = false })
}

private fun imageNumber(id: String) = (id.toIntOrNull()?.plus(1) ?: id).toString()

class TranslationLogsScreen(
    private val jobId: String? = null,
    private val operationId: String? = null,
    private val batchId: String? = null,
    private val imageId: String? = null,
    private val initialSearch: String = "",
    private val since: Long? = null,
    private val until: Long? = null,
    private val provider: String? = null,
    private val model: String? = null,
) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val context = LocalContext.current
        var selectionUi by remember { mutableStateOf<TranslationSelectionUi?>(null) }
        val clearSelection = selectionUi?.clear
        var search by remember { mutableStateOf(initialSearch.takeIf { it.isNotBlank() }) }
        Scaffold(topBar = {
            if (selectionUi != null) {
                AppBar(
                    title = "Translation logs",
                    actionModeCounter = selectionUi!!.count,
                    onCancelActionMode = { clearSelection?.invoke() },
                )
            } else {
                SearchToolbar(
                    searchQuery = search,
                    onChangeSearchQuery = { search = it },
                    titleContent = { Text("Translation logs") },
                    navigateUp = {
                        val clear = clearSelection
                        if (clear != null) clear() else navigator.pop()
                    },
                    onClickCloseSearch = {
                        val clear = clearSelection
                        if (clear != null) clear() else search = null
                    },
                    placeholderText = "Messages, stages or request IDs",
                )
            }
        }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(Modifier.padding(padding)) {
                jobId?.let { id ->
                    val job by produceState<TranslationJob?>(null, id) {
                        value = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            context.appGraph.translationRepository.jobs().firstOrNull { it.id == id }
                        }
                    }
                    job?.let { origin ->
                        TextButton(onClick = { navigator.push(TranslationScreen(origin.mangaId)) }) {
                            Text("↩ ${origin.mangaTitle} · ${origin.chapterTitle} · queue")
                        }
                    }
                }
                TranslationLogsContent(
                    TranslationLogQuery(
                        jobId,
                        operationId,
                        batchId,
                        imageId,
                        search = search.orEmpty(),
                        since = since,
                        until = until,
                        provider = provider,
                        model = model,
                    ),
                    onSelectionChanged = { selectionUi = it },
                ) { block ->
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { block() }
                        } catch (
                            e: CancellationException,
                        ) {
                            throw e
                        } catch (e: Exception) {
                            snackbar.showSnackbar(e.message ?: "Log action failed")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslationLogsContent(
    filter: TranslationLogQuery,
    onSelectionChanged: (TranslationSelectionUi?) -> Unit,
    onAction: (suspend () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val graph = context.appGraph
    val jobId = filter.jobId
    val logSettings by graph.translationPreferences.settings.collectAsState()
    var pageLimit by remember(filter) { mutableStateOf(100L) }
    var level by remember { mutableStateOf<String?>(null) }
    var errorsOnly by remember { mutableStateOf(false) }
    val effectiveQuery = filter.copy(
        level = if (errorsOnly) "WARN_OR_ERROR" else level,
        limit = pageLimit,
    )
    val events by remember(effectiveQuery) {
        graph.translationRepository.observeEventPage(effectiveQuery)
    }.collectAsState(emptyList())
    var selected by remember(filter) { mutableStateOf<Map<String, TranslationEvent>>(emptyMap()) }
    LaunchedEffect(selected) {
        onSelectionChanged(
            if (selected.isEmpty()) {
                null
            } else {
                TranslationSelectionUi(selected.size) {
                    selected =
                        emptyMap()
                }
            },
        )
    }
    DisposableEffect(Unit) { onDispose { onSelectionChanged(null) } }
    var exportSelection by remember { mutableStateOf<List<TranslationEvent>?>(null) }
    var pendingEvents by remember { mutableStateOf<List<TranslationEvent>?>(null) }
    var pendingQuery by remember { mutableStateOf<TranslationLogQuery?>(null) }
    var deletingLogs by remember { mutableStateOf<Set<String>?>(null) }
    var menuEvent by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<TranslationEvent?>(null) }
    var rawPreview by remember { mutableStateOf<String?>(null) }
    var captureIds by remember { mutableStateOf(emptyList<String>()) }
    var captureList by remember { mutableStateOf<List<mihon.feature.translation.provider.CaptureMetadata>?>(null) }
    val json = remember {
        Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
    val exportJson = remember { Json { encodeDefaults = true } }
    val exportLogs =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
            val chosen = pendingEvents
            val chosenQuery = pendingQuery
            pendingEvents = null
            pendingQuery = null
            if (uri != null) {
                onAction {
                    context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { writer ->
                        if (chosen != null) {
                            chosen.forEach {
                                writer.appendLine(
                                    mihon.feature.translation.provider.sanitizedDiagnosticRecord(
                                        exportJson.encodeToString(it),
                                    ),
                                )
                            }
                        } else {
                            val frozenQuery =
                                requireNotNull(chosenQuery) {
                                    "The export selection is unavailable. Select diagnostics again."
                                }
                            var offset = 0L
                            do {
                                val page = graph.translationRepository.eventPage(
                                    frozenQuery.copy(limit = 500, offset = offset),
                                )
                                page.forEach {
                                    writer.appendLine(
                                        mihon.feature.translation.provider.sanitizedDiagnosticRecord(
                                            exportJson.encodeToString(it),
                                        ),
                                    )
                                }
                                offset += page.size
                            } while (page.size == 500)
                        }
                    }
                }
            }
        }
    val exportRaw =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                onAction {
                    val ids = captureIds.toList()
                    check(ids.isNotEmpty()) { "The capture selection is unavailable. Select diagnostics again." }
                    context.contentResolver.openOutputStream(uri)!!.use {
                        graph.translationDiagnostics.export(ids, it)
                    }
                }
            }
        }
    suspend fun showCapture(id: String) {
        val capture = graph.translationDiagnostics.list().firstOrNull { it.id == id }
            ?: error("This capture is unavailable or has been removed by retention.")
        val incomplete = capture.completedAt == null || capture.requestTruncated ||
            capture.responseTruncated || capture.metadataTruncated
        val bodies = mutableListOf<String>()
        for (name in listOf("metadata.json", "request.json", "response.json")) {
            val content = try {
                graph.translationDiagnostics.readSanitizedBody(id, name)
            } catch (_: java.io.FileNotFoundException) {
                "Unavailable"
            }
            bodies += "$name\n$content"
        }
        rawPreview = "Capture state: ${capture.state}\n" +
            (if (incomplete) "INCOMPLETE CAPTURE\n" else "") +
            "${capture.captureNotes.joinToString("\n")}\n\n" + bodies.joinToString("\n\n")
    }
    suspend fun scopedCaptures(): List<mihon.feature.translation.provider.CaptureMetadata> {
        val since = filter.since
        val until = filter.until
        val all = graph.translationDiagnostics.list().filter {
            (jobId == null || it.jobId == jobId) &&
                (since == null || it.startedAt >= since) && (until == null || it.startedAt < until)
        }
        if (effectiveQuery.operationId == null && effectiveQuery.batchId == null && effectiveQuery.imageId == null &&
            effectiveQuery.search.isEmpty() && effectiveQuery.level == null && effectiveQuery.provider == null &&
            effectiveQuery.model == null
        ) {
            return all
        }
        val ids = mutableSetOf<String>()
        var offset = 0L
        do {
            val page = graph.translationRepository.eventPage(effectiveQuery.copy(limit = 500, offset = offset))
            ids += page.mapNotNull { it.capturePath?.let { path -> java.io.File(path).name } }
            offset += page.size
        } while (page.size == 500)
        return all.filter { it.id in ids }
    }
    val filtered = events
    Column(Modifier.fillMaxSize()) {
        if (filter.provider != null ||
            filter.model != null
        ) {
            Text(
                "${filter.provider ?: "All providers"} · ${filter.model ?: "All models"} · " +
                    "unattributed historical events excluded",
                Modifier.padding(12.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { errorsOnly = !errorsOnly }) {
                Icon(
                    MaterialSymbols.Rounded.FilterList,
                    if (errorsOnly) "Show all levels" else "Show warnings and errors",
                )
            }
            TranslationRowActionMenu(
                listOf(
                    TranslationRowAction("Export matching logs", MaterialSymbols.Rounded.Share) {
                        pendingEvents = null
                        pendingQuery = effectiveQuery
                        exportLogs.launch("mihon-translation-logs.jsonl")
                    },
                    TranslationRowAction("Export matching sanitized API", MaterialSymbols.Rounded.Share) {
                        onAction {
                            captureIds = scopedCaptures().map { it.id }
                            check(captureIds.isNotEmpty()) { "No sanitized captures to export" }
                            withContext(Dispatchers.Main) { exportRaw.launch("mihon-api-captures.zip") }
                        }
                    },
                    TranslationRowAction("Select loaded events", MaterialSymbols.Rounded.SelectAll) {
                        selected =
                            events.associateBy { it.id }
                    },
                    TranslationRowAction("Manage captures", MaterialSymbols.Rounded.Settings) {
                        onAction {
                            captureList =
                                scopedCaptures()
                        }
                    },
                    TranslationRowAction("Apply retention", MaterialSymbols.Rounded.Delete) {
                        onAction {
                            val settings = graph.translationPreferences.settings.value.logs
                            graph.translationDiagnostics.prune(settings)
                            graph.translationRepository.deleteEvents(
                                System.currentTimeMillis() - settings.retentionDays * 86_400_000L,
                            )
                        }
                    },
                ),
                "Log actions",
            )
        }
        Text(
            "${filtered.size} loaded events · Captures omit credentials, images and thought signatures.",
            Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(Modifier.weight(1f)) {
            if (events.size >= pageLimit && pageLimit < 1000) {
                item {
                    TextButton(onClick = {
                        pageLimit = (pageLimit + 100).coerceAtMost(1000)
                    }) { Text("Load more events") }
                }
            }
            if (events.size >=
                1000
            ) {
                item { Text("Showing newest 1,000 matches. Refine the search or export all matching records.") }
            }
            items(filtered, key = { it.id }) { event ->
                val actions = listOf(
                    TranslationRowAction("Details", MaterialSymbols.Rounded.Info) { detail = event },
                    TranslationRowAction("Export selected diagnostics", MaterialSymbols.Rounded.Share) {
                        exportSelection =
                            listOf(event)
                    },
                    TranslationRowAction("Delete chapter logs with preview", MaterialSymbols.Rounded.Delete) {
                        deletingLogs =
                            setOf(event.jobId)
                    },
                )
                val swipeActions = mapOf(
                    TranslationGestureAction.EXPORT to actions[1],
                    TranslationGestureAction.DELETE to actions[2],
                    TranslationGestureAction.ACTIONS to
                        TranslationRowAction("Actions", MaterialSymbols.Rounded.MoreVert) { menuEvent = event.id },
                )
                fun toggleSelection() {
                    selected =
                        if (event.id in selected) selected - event.id else selected + (event.id to event)
                }
                TranslationActionRow(
                    selected = event.id in selected,
                    selectionActive = selected.isNotEmpty(),
                    onClick = { if (selected.isEmpty()) detail = event else toggleSelection() },
                    onLongClick = { toggleSelection() },
                    startAction = configuredTranslationSwipe(
                        TranslationGestureRow.LOG,
                        TranslationGestureDirection.START,
                        swipeActions,
                    ),
                    endAction = configuredTranslationSwipe(
                        TranslationGestureRow.LOG,
                        TranslationGestureDirection.END,
                        swipeActions,
                    ),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TranslationStatusIcon(event.presentationState(), logSettings.queueColors)
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${event.stage} · ${event.level}",
                                color = translationStateColor(event.presentationState(), logSettings.queueColors),
                            )
                            Text(event.message)
                            Text(
                                DateFormat.getTimeInstance().format(Date(event.time)),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (selected.isEmpty()) {
                            androidx.compose.foundation.layout.Box {
                                IconButton(onClick = {
                                    menuEvent = event.id
                                }) { Icon(MaterialSymbols.Rounded.MoreVert, "Actions for ${event.stage}") }
                                TranslationActionMenu(menuEvent == event.id, { menuEvent = null }, actions)
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        TranslationSelectionBar(
            selected.size,
            { selected = emptyMap() },
            listOf(
                TranslationRowAction("Export selected diagnostics", MaterialSymbols.Rounded.Share) {
                    exportSelection =
                        selected.values.toList()
                },
                TranslationRowAction("Delete selected chapters' logs with preview", MaterialSymbols.Rounded.Delete) {
                    deletingLogs =
                        selected.values.map { it.jobId }.toSet()
                },
                TranslationRowAction("Select loaded events", MaterialSymbols.Rounded.SelectAll) {
                    selected =
                        selected + events.associateBy { it.id }
                },
            ),
            showCounter = false,
        )
    }
    exportSelection?.let { chosen ->
        AlertDialog(
            onDismissRequest = {
                exportSelection = null
            },
            title = { Text("Export ${chosen.size} selected diagnostics") },
            text = {
                Column {
                    Text(
                        "Choose a format, then a destination. API captures omit credentials, " +
                            "images and thought signatures.",
                    )
                    TextButton(onClick = {
                        pendingEvents = chosen
                        pendingQuery = null
                        exportSelection = null
                        exportLogs.launch("mihon-selected-logs.jsonl")
                    }) { Text("Structured logs (JSONL)") }
                    TextButton(onClick = {
                        captureIds =
                            chosen.mapNotNull { it.capturePath?.let { path -> java.io.File(path).name } }.distinct()
                        exportSelection = null
                        if (captureIds.isNotEmpty()) exportRaw.launch("mihon-selected-api-captures.zip")
                    }, enabled = chosen.any { it.capturePath != null }) { Text("Sanitized API captures (ZIP)") }
                }
            },
            confirmButton = { TextButton(onClick = { exportSelection = null }) { Text("Cancel") } },
        )
    }
    deletingLogs?.let { ids ->
        TranslationDeletionDialog(
            graph.translationDeletionService,
            ids,
            onDismiss = { deletingLogs = null },
            onDeleted = {
                selected = selected.filterValues { it.jobId !in ids }
                deletingLogs =
                    null
            },
            initialScopes = setOf(tachiyomi.domain.translation.model.TranslationDeletionScope.LOGS),
            allowedScopes = setOf(tachiyomi.domain.translation.model.TranslationDeletionScope.LOGS),
            scopeDescription = "This preview covers all logs, batch history and captures for " +
                "${ids.size} selected chapter histories, including events outside the current filter. " +
                "Saved translations and usage accounting remain.",
        )
    }
    detail?.let { event ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("${event.stage} details") },
            text = {
                SelectionContainer {
                    Text(
                        mihon.feature.translation.provider.sanitizedDiagnosticRecord(json.encodeToString(event)),
                        Modifier.verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = {
                    onAction {
                        val id = event.capturePath?.let { java.io.File(it).name }
                            ?: error("No capture for this event. Enable sanitized API capture before translating.")
                        showCapture(id)
                    }
                }) { Text("API payload") }
            },
        )
    }
    captureList?.let { captures ->
        AlertDialog(
            onDismissRequest = { captureList = null },
            title = { Text("Sanitized API captures") },
            text = {
                LazyColumn {
                    if (captures.isEmpty()) item { Text("No captures retained") }
                    items(captures, key = { it.id }) { capture ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text("${capture.operation} · ${capture.responseCode ?: "No response"}")
                            Text(
                                "${DateFormat.getDateTimeInstance().format(
                                    Date(capture.startedAt),
                                )} · ${capture.state}",
                            )
                            if (capture.requestTruncated || capture.responseTruncated || capture.metadataTruncated) {
                                Text("Incomplete capture: some bytes were omitted")
                            }
                            Text("Saved request ${capture.requestCapturedBytes} / ${capture.requestBytes} bytes")
                            Text("Saved response ${capture.responseCapturedBytes} / ${capture.responseBytes} bytes")
                            capture.error?.let { Text(it) }
                            Row {
                                TextButton(onClick = { onAction { showCapture(capture.id) } }) { Text("View") }
                                TextButton(onClick = {
                                    captureIds = listOf(capture.id)
                                    exportRaw.launch("mihon-capture-${capture.id}.zip")
                                }) { Text("Export") }
                                val navigator = LocalNavigator.currentOrThrow
                                TextButton(onClick = {
                                    navigator.push(TranslationManagementScreen(listOf(capture.jobId)))
                                }) { Text("Manage deletion…") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { captureList = null }) { Text("Close") } },
        )
    }
    rawPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { rawPreview = null },
            title = { Text("Sanitized API preview") },
            text = {
                SelectionContainer {
                    Text(
                        "Preview limited to 64 KiB per file. Exports include all retained sanitized content; " +
                            "captures are not replay archives.\n\n$preview",
                        Modifier.verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { rawPreview = null }) { Text("Close") } },
        )
    }
}
