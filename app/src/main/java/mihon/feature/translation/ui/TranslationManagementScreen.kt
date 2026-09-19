package mihon.feature.translation.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.app.di.appGraph
import mihon.feature.translation.transfer.StagedTranslationArchive
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.FormatListBulleted
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Palette
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.SelectAll
import mihon.icons.materialsymbols.rounded.SwapCalls
import tachiyomi.domain.translation.model.TranslationArchiveConflictPolicy
import tachiyomi.domain.translation.model.TranslationArchiveLink
import tachiyomi.domain.translation.model.TranslationBackupOptions
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationPdfPageSize
import tachiyomi.domain.translation.model.TranslationRenderedFormat
import tachiyomi.domain.translation.service.chapterHistoryKey
import tachiyomi.presentation.core.components.material.Scaffold

/** Management uses saved chapter identities; opening it never submits translation work. */
class TranslationManagementScreen(
    private val initialJobIds: List<String> = emptyList(),
    private val embedded: Boolean = false,
    private val initialImportUri: String? = null,
    private val structuredFiles: Boolean = false,
) : Screen() {
    @Composable
    override fun Content() {
        Content(onSelectionChanged = {})
    }

    @Composable
    internal fun Content(onSelectionChanged: (TranslationSelectionUi?) -> Unit) {
        val context = LocalContext.current
        val graph = context.appGraph
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val jobs by graph.translationManager.jobs.collectAsState(emptyList())
        val uiSettings by graph.translationPreferences.settings.collectAsState()
        var selected by remember { mutableStateOf(initialJobIds.toSet()) }
        LaunchedEffect(selected) {
            onSelectionChanged(
                selected.takeIf { it.isNotEmpty() }?.let {
                    TranslationSelectionUi(it.size) { selected = emptySet() }
                },
            )
        }
        DisposableEffect(Unit) { onDispose { onSelectionChanged(null) } }
        var search by rememberSaveable { mutableStateOf<String?>(null) }
        var expandedIds by remember { mutableStateOf(emptySet<String>()) }
        var exportIds by remember { mutableStateOf<List<String>?>(null) }
        var logs by remember { mutableStateOf(false) }
        var captures by remember { mutableStateOf(false) }
        var paper by remember { mutableStateOf(TranslationPdfPageSize.A4) }
        var format by remember { mutableStateOf(TranslationRenderedFormat.CBZ) }
        var progress by remember { mutableStateOf<String?>(null) }
        var outcome by remember { mutableStateOf<String?>(null) }
        val operations = remember(scope) { TranslationManagementOperationGate(scope) }
        val running by operations.running.collectAsState()
        var cancelling by remember { mutableStateOf(false) }
        var archive by remember { mutableStateOf<StagedTranslationArchive?>(null) }
        var replace by remember { mutableStateOf(false) }
        var target by remember { mutableStateOf<ArchiveTarget?>(null) }
        var choosingTarget by remember { mutableStateOf(false) }
        var deletionIds by remember { mutableStateOf<Set<String>?>(null) }
        var archivedAppearance by remember { mutableStateOf<TranslationJob?>(null) }
        var pendingBackup by rememberSaveable { mutableStateOf<String?>(null) }
        var pendingRendered by rememberSaveable { mutableStateOf<String?>(null) }
        var savedLink by remember { mutableStateOf<TranslationJob?>(null) }
        var savedTarget by remember { mutableStateOf<ArchiveTarget?>(null) }
        val busy = running != null || cancelling || pendingBackup != null || pendingRendered != null
        fun execute(block: suspend () -> Unit) {
            if (cancelling) return
            operations.launch {
                outcome = null
                try {
                    withContext(Dispatchers.IO) { block() }
                } catch (error: CancellationException) {
                    outcome = error.message?.takeIf { it.startsWith("Translations were restored;") }
                        ?: "Operation cancelled. Completed steps, if any, remain saved."
                    throw error
                } catch (
                    error: Exception,
                ) {
                    outcome = error.message ?: "Management operation failed"
                } finally {
                    progress = null
                }
            }
        }
        fun cancelOperation(afterTermination: suspend () -> Unit = {}) {
            if (cancelling) return
            val heldOperation = operations.running.value
            cancelling = true
            scope.launch {
                try {
                    withContext(NonCancellable) {
                        heldOperation?.cancelAndJoin()
                        afterTermination()
                    }
                } finally {
                    cancelling = false
                }
            }
        }
        val backup =
            rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                val savedRequest = pendingBackup
                pendingBackup = null
                if (uri != null) {
                    execute {
                        val request = savedRequest?.let { Json.decodeFromString<PendingBackupRequest>(it) }
                            ?: error("The backup selection is unavailable. Select the histories and destination again.")
                        val manifest = context.contentResolver.openOutputStream(uri)?.use { output ->
                            graph.translationTransferService.backup(
                                request.jobIds.toSet(),
                                output,
                                TranslationBackupOptions(request.logs, request.captures),
                            ) {
                                progress = "${it.stage.label} · ${it.completed}/${it.total ?: "?"} ${it.unit.label}"
                            }
                        } ?: error("Cannot open backup destination")
                        outcome = "Backup saved: ${manifest.chapters} chapters · ${manifest.pages} pages. " +
                            "Credentials and queued requests excluded." +
                            manifest.warnings.joinToString("\n", prefix = "\n")
                    }
                }
            }
        val rendered =
            rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/octet-stream"),
            ) { uri ->
                val savedRequest = pendingRendered
                pendingRendered = null
                if (uri != null) {
                    execute {
                        val request = savedRequest?.let { Json.decodeFromString<PendingRenderedRequest>(it) }
                            ?: error("The export selection is unavailable. Select the history and destination again.")
                        val report = context.contentResolver.openOutputStream(uri)?.use { output ->
                            graph.translationTransferService.exportChapter(
                                request.jobId,
                                TranslationRenderedFormat.valueOf(request.format),
                                output,
                                TranslationPdfPageSize.valueOf(request.paper),
                            ) {
                                progress = "${it.stage.label} · ${it.completed}/${it.total ?: "?"} ${it.unit.label}"
                            }
                        } ?: error("Cannot open export destination")
                        outcome = "${if (report.complete) "Completed" else "Incomplete export"}: " +
                            "${report.pagesWritten}/${report.pagesExpected} pages · ${report.bytesWritten} bytes" +
                            (
                                if (report.missingOriginals.isNotEmpty()) {
                                    "\nMissing originals: ${report.missingOriginals.joinToString()}"
                                } else {
                                    ""
                                }
                                ) +
                            report.warnings.joinToString("\n", prefix = "\n")
                    }
                }
            }
        fun inspectBackup(uri: android.net.Uri) {
            execute {
                archive?.close()
                archive = null
                archive = context.contentResolver.openInputStream(uri)?.use {
                    graph.translationTransferService.inspectBackup(it)
                } ?: error("Cannot read backup")
                replace = false
                target = null
            }
        }
        val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) inspectBackup(uri)
        }
        LaunchedEffect(initialImportUri) {
            initialImportUri?.let { inspectBackup(android.net.Uri.parse(it)) }
        }
        DisposableEffect(Unit) {
            onDispose {
                val heldOperation = operations.running.value
                if (heldOperation != null) {
                    // Inspection can assign its staged result during cancellation; read the latest value on completion.
                    heldOperation.invokeOnCompletion { archive?.close() }
                } else {
                    archive?.close()
                }
            }
        }
        val matches = jobs.filter {
            it.mangaTitle.contains(search.orEmpty(), true) || it.chapterTitle.contains(search.orEmpty(), true)
        }.sortedByDescending { it.createdAt }
        fun toggleSelection(id: String) {
            if (!busy) selected = if (id in selected) selected - id else selected + id
        }
        BackHandler(selected.isNotEmpty()) { selected = emptySet() }
        Scaffold(
            contentWindowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            topBar = { scrollBehavior ->
                if (embedded) {
                    // The Translator tab owns its app bar; standalone management keeps normal navigation below.
                } else if (selected.isNotEmpty()) {
                    AppBar(
                        title = "Translations and backups",
                        navigateUp = { selected = emptySet() },
                        actionModeCounter = selected.size,
                        scrollBehavior = scrollBehavior,
                        onCancelActionMode = { selected = emptySet() },
                        actionModeActions = {
                            TranslationRowActionMenu(
                                if (busy) {
                                    emptyList()
                                } else {
                                    listOf(
                                        TranslationRowAction("Select matches", MaterialSymbols.Rounded.SelectAll) {
                                            selected = matches.map { it.id }.toSet()
                                        },
                                    )
                                },
                            )
                        },
                    )
                } else {
                    SearchToolbar(
                        titleContent = { AppBarTitle("Translations and backups") },
                        searchQuery = search,
                        onChangeSearchQuery = { search = it },
                        navigateUp = navigator::pop,
                        placeholderText = "Search saved series and chapters",
                        scrollBehavior = scrollBehavior,
                        actions = {
                            TranslationRowActionMenu(
                                if (busy) {
                                    emptyList()
                                } else {
                                    listOf(
                                        TranslationRowAction(
                                            "Import structured files",
                                            MaterialSymbols.Rounded.Folder,
                                        ) {
                                            navigator.push(TranslationStructuredImportScreen())
                                        },
                                        TranslationRowAction("Import ZIP", MaterialSymbols.Rounded.Folder) {
                                            restore.launch(arrayOf("application/zip", "application/octet-stream"))
                                        },
                                        TranslationRowAction("Select matches", MaterialSymbols.Rounded.SelectAll) {
                                            selected = matches.map { it.id }.toSet()
                                        },
                                    )
                                },
                            )
                        },
                    )
                }
            },
            bottomBar = {
                TranslationSelectionBar(
                    selected.size,
                    { selected = emptySet() },
                    if (busy) {
                        emptyList()
                    } else {
                        listOf(
                            TranslationRowAction("Export selected", MaterialSymbols.Rounded.Download) {
                                exportIds = selected.toList()
                            },
                            TranslationRowAction("Delete selected…", MaterialSymbols.Rounded.Delete) {
                                deletionIds = selected.toSet()
                            },
                        )
                    },
                    showCounter = false,
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (embedded) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { search = if (search == null) "" else null }) {
                            Icon(MaterialSymbols.Rounded.Search, "Search saved translations")
                        }
                        TranslationRowActionMenu(
                            if (busy) {
                                emptyList()
                            } else {
                                listOf(
                                    TranslationRowAction("Import structured files", MaterialSymbols.Rounded.Folder) {
                                        navigator.push(TranslationStructuredImportScreen())
                                    },
                                    TranslationRowAction("Import ZIP", MaterialSymbols.Rounded.Folder) {
                                        restore.launch(arrayOf("application/zip", "application/octet-stream"))
                                    },
                                    TranslationRowAction("Select matches", MaterialSymbols.Rounded.SelectAll) {
                                        selected = matches.map { it.id }.toSet()
                                    },
                                )
                            },
                        )
                    }
                    search?.let { value ->
                        OutlinedTextField(
                            value,
                            { search = it },
                            label = { Text("Search saved series and chapters") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                }
                progress?.let {
                    Text(it, Modifier.padding(16.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (running != null || cancelling) {
                    TextButton(enabled = !cancelling, onClick = { cancelOperation() }) {
                        Text(if (cancelling) "Cancelling…" else "Cancel operation")
                    }
                }
                outcome?.let { Text(it, Modifier.padding(16.dp)) }
                LazyColumn(Modifier.weight(1f)) {
                    if (matches.isEmpty()) {
                        item { Text("No matching translation history", Modifier.padding(16.dp)) }
                    }
                    items(matches, key = { it.id }) { job ->
                        val rowActions = buildMap {
                            put(
                                TranslationGestureAction.EXPORT,
                                TranslationRowAction("Export options", MaterialSymbols.Rounded.Download) {
                                    exportIds = listOf(job.id)
                                },
                            )
                            put(
                                TranslationGestureAction.DELETE,
                                TranslationRowAction("Translation deletion preview", MaterialSymbols.Rounded.Delete) {
                                    deletionIds = setOf(job.id)
                                },
                            )
                            put(
                                TranslationGestureAction.LOGS,
                                TranslationRowAction(
                                    "Related logs",
                                    MaterialSymbols.AutoMirroredRounded.FormatListBulleted,
                                ) {
                                    navigator.push(TranslationLogsScreen(job.id))
                                },
                            )
                            put(
                                TranslationGestureAction.ACTIONS,
                                TranslationRowAction("Chapter details and actions", MaterialSymbols.Rounded.MoreVert) {
                                    expandedIds = expandedIds + job.id
                                },
                            )
                        }
                        TranslationActionRow(
                            selected = job.id in selected,
                            selectionActive = selected.isNotEmpty() || busy,
                            onClick = {
                                if (selected.isNotEmpty()) {
                                    toggleSelection(job.id)
                                } else {
                                    expandedIds =
                                        if (job.id in expandedIds) expandedIds - job.id else expandedIds + job.id
                                }
                            },
                            onLongClick = { toggleSelection(job.id) },
                            startAction = configuredTranslationSwipe(
                                TranslationGestureRow.SAVED,
                                TranslationGestureDirection.START,
                                rowActions,
                            ),
                            endAction = configuredTranslationSwipe(
                                TranslationGestureRow.SAVED,
                                TranslationGestureDirection.END,
                                rowActions,
                            ),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(job.mangaTitle, style = MaterialTheme.typography.bodyLarge)
                                    Text(job.chapterTitle, style = MaterialTheme.typography.bodyMedium)
                                    if (job.isStructuredFiles) {
                                        Text(
                                            if (job.state == TranslationJobState.COMPLETED) {
                                                "Imported translations"
                                            } else {
                                                "Awaiting imported pages"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Row {
                                        TranslationStatusIcon(job.state, uiSettings.queueColors)
                                        Text(
                                            "${job.state.name.lowercase()} · " +
                                                "${job.completedImages}/${job.imageCount.takeIf {
                                                    it > 0
                                                } ?: "?"} saved pages",
                                            color = translationStateColor(job.state, uiSettings.queueColors),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                if (selected.isEmpty()) {
                                    val actions = if (busy) {
                                        emptyList()
                                    } else {
                                        buildList {
                                            addAll(
                                                rowActions.filterKeys {
                                                    it != TranslationGestureAction.ACTIONS
                                                }.values,
                                            )
                                            add(
                                                TranslationRowAction(
                                                    "Select all history for this chapter",
                                                    MaterialSymbols.Rounded.SelectAll,
                                                ) {
                                                    selected =
                                                        selected +
                                                        jobs.filter {
                                                            it.chapterHistoryKey() == job.chapterHistoryKey()
                                                        }.map { it.id }
                                                },
                                            )
                                            if (job.mangaId < 0 || job.chapterId < 0) {
                                                add(
                                                    TranslationRowAction(
                                                        "Relink saved archive…",
                                                        MaterialSymbols.Rounded.SwapCalls,
                                                    ) {
                                                        savedLink = job
                                                        savedTarget = null
                                                        choosingTarget = true
                                                    },
                                                )
                                            }
                                            if (job.mangaId >= 0 && job.archiveMetadata?.effectiveSettings != null) {
                                                add(
                                                    TranslationRowAction(
                                                        "Apply archived presentation…",
                                                        MaterialSymbols.Rounded.Palette,
                                                    ) {
                                                        archivedAppearance = job
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    TranslationRowActionMenu(actions, "Actions for ${job.chapterTitle}")
                                }
                            }
                            if (job.id in expandedIds) {
                                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                                    Text(
                                        "${job.settings.provider.model} · ${job.settings.targetLanguage} · " +
                                            java.text.DateFormat.getDateTimeInstance().format(
                                                java.util.Date(job.createdAt),
                                            ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (job.mangaId < 0 || job.chapterId < 0) {
                                        Text(
                                            "Unlinked archive: relink originals before rendered export.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        exportIds?.let { ids ->
            TranslationExportDialog(
                count = ids.size,
                busy = busy,
                logs = logs,
                captures = captures,
                format = format,
                paper = paper,
                onLogsChange = { logs = it },
                onCapturesChange = { captures = it },
                onFormatChange = { format = it },
                onPaperChange = { paper = it },
                onCancel = { exportIds = null },
                onBackup = {
                    pendingBackup = Json.encodeToString(PendingBackupRequest(ids, logs, captures))
                    exportIds = null
                    try {
                        backup.launch("mihon-translations.zip")
                    } catch (error: Exception) {
                        pendingBackup = null
                        outcome = error.message
                    }
                },
                onRendered = {
                    pendingRendered = Json.encodeToString(PendingRenderedRequest(ids.single(), format.name, paper.name))
                    exportIds = null
                    try {
                        rendered.launch("mihon-chapter.${format.name.lowercase()}")
                    } catch (error: Exception) {
                        pendingRendered = null
                        outcome = error.message
                    }
                },
            )
        }
        archive?.let { staged ->
            AlertDialog(
                onDismissRequest = {
                    if (!busy) {
                        staged.close()
                        archive = null
                    }
                },
                title = { Text("Restore verified backup") },
                text = {
                    Column {
                        Text(
                            "${staged.manifest.chapters} chapters · ${staged.manifest.pages} pages\n" +
                                "Identical records are skipped. Unmatched pages remain available for relinking. " +
                                "Restore never starts translation requests.",
                        )
                        Row {
                            Checkbox(replace, { replace = it }, enabled = !busy)
                            Text("Replace conflicting local edits (explicit choice)")
                        }
                        if (staged.manifest.chapters == 1) {
                            Text(
                                target?.let { "Link to ${it.title} · ${it.chapter}" }
                                    ?: "Keep unmatched pages in an unlinked archive",
                            )
                            TextButton(enabled = !busy, onClick = {
                                choosingTarget = true
                            }) { Text("Choose library or local chapter…") }
                            if (target !=
                                null
                            ) {
                                TextButton(enabled = !busy, onClick = { target = null }) { Text("Keep unlinked") }
                            }
                            Text(
                                "Relinking may acquire original source pages under Mihon's download rules. " +
                                    "It verifies their hashes and dimensions and never calls a translation provider.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        execute {
                            val links = target?.let { chosen ->
                                mapOf(
                                    staged.chapterSummaries.single().sourceJobId to
                                        TranslationArchiveLink(chosen.mangaId, chosen.chapterId),
                                )
                            } ?: emptyMap()
                            val report = graph.translationTransferService.restore(
                                staged,
                                if (replace) {
                                    TranslationArchiveConflictPolicy.REPLACE
                                } else {
                                    TranslationArchiveConflictPolicy.KEEP_LOCAL
                                },
                                links,
                                structuredFiles = structuredFiles,
                            ) {
                                progress = "${it.stage.label} · ${it.completed}/${it.total ?: "?"} ${it.unit.label}"
                            }
                            outcome =
                                "Restored ${report.importedPages} pages · " +
                                "${report.identicalPages} identical skipped · " +
                                "${report.preservedConflicts} local conflicts preserved · " +
                                "${report.unlinkedJobs} unlinked chapters\n${report.warnings.joinToString("\n")}"
                            staged.close()
                            archive = null
                        }
                    }) { Text(if (replace) "Replace and restore" else "Restore, preserving edits") }
                },
                dismissButton = {
                    TextButton(enabled = !cancelling, onClick = {
                        cancelOperation {
                            withContext(Dispatchers.IO) { staged.close() }
                            if (archive === staged) archive = null
                        }
                    }) {
                        Text(
                            if (cancelling) {
                                "Cancelling…"
                            } else if (running != null) {
                                "Cancel operation"
                            } else {
                                "Cancel"
                            },
                        )
                    }
                },
            )
        }
        if (choosingTarget) {
            ArchiveTargetDialog(onDismiss = {
                choosingTarget = false
                savedLink = null
            }, onSelected = {
                if (savedLink != null) savedTarget = it else target = it
                choosingTarget = false
            })
        }
        savedLink?.let { source ->
            savedTarget?.let { destination ->
                AlertDialog(
                    onDismissRequest = {
                        if (!busy) {
                            savedLink = null
                            savedTarget = null
                        }
                    },
                    title = { Text("Relink saved archive") },
                    text = {
                        Text(
                            "Link ${source.mangaTitle} · ${source.chapterTitle} to " +
                                "${destination.title} · ${destination.chapter}? Original pages may be acquired under " +
                                "Mihon's download rules and must match saved hashes and dimensions. " +
                                "Local edits are preserved. The original archive remains available. " +
                                "This does not call a translation provider.",
                        )
                    },
                    confirmButton = {
                        TextButton(enabled = !busy, onClick = {
                            execute {
                                val report = graph.translationTransferService.relinkSaved(
                                    source.id,
                                    destination.mangaId,
                                    destination.chapterId,
                                ) {
                                    progress = "${it.stage.label} · ${it.completed}/${it.total ?: "?"} ${it.unit.label}"
                                }
                                selected = report.jobIds.toSet()
                                outcome =
                                    "Relinked ${report.linkedJobs} chapters. Original archive retained.\n" +
                                    report.warnings.joinToString("\n")
                                savedLink = null
                                savedTarget = null
                            }
                        }) { Text("Verify and relink") }
                    },
                    dismissButton = {
                        TextButton(enabled = !cancelling, onClick = {
                            cancelOperation {
                                if (savedLink?.id == source.id && savedTarget == destination) {
                                    savedLink = null
                                    savedTarget = null
                                }
                            }
                        }) {
                            Text(
                                if (cancelling) {
                                    "Cancelling…"
                                } else if (running !=
                                    null
                                ) {
                                    "Cancel operation"
                                } else {
                                    "Cancel"
                                },
                            )
                        }
                    },
                )
            }
        }
        archivedAppearance?.let { job ->
            AlertDialog(
                onDismissRequest = {
                    if (!busy) archivedAppearance = null
                },
                title = { Text("Apply archived presentation") },
                text = {
                    Text(
                        "Apply the archived overlay style and glossary to ${job.mangaTitle}? " +
                            "This updates the series override and cached presentation. " +
                            "Credentials, provider, language, " +
                            "Ignore sound effects, automation and saved translations remain unchanged.",
                    )
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        execute {
                            val archived = requireNotNull(job.archiveMetadata?.effectiveSettings)
                            val current = graph.translationPreferences.effectiveSettings(job.mangaId)
                            graph.translationPreferences.update(
                                current.copy(style = archived.style, glossary = archived.glossary),
                                job.mangaId,
                            )
                            archivedAppearance = null
                            outcome = "Archived style and glossary applied to this series."
                        }
                    }) { Text("Apply style and glossary") }
                },
                dismissButton = {
                    TextButton(enabled = !busy, onClick = { archivedAppearance = null }) { Text("Cancel") }
                },
            )
        }
        deletionIds?.let { ids ->
            TranslationDeletionDialog(
                graph.translationDeletionService,
                ids,
                onDismiss = { deletionIds = null },
                onDeleted = {
                    deletionIds = null
                    outcome = "Selected deletion completed."
                },
            )
        }
    }
}

private data class ArchiveTarget(
    val mangaId: Long,
    val chapterId: Long,
    val title: String,
    val chapter: String,
    val date: Long,
)

/** Choosing a target only reads indexed chapters; source acquisition starts after explicit Restore. */
@Composable
private fun ArchiveTargetDialog(onDismiss: () -> Unit, onSelected: (ArchiveTarget) -> Unit) {
    val graph = LocalContext.current.appGraph
    var query by remember { mutableStateOf("") }
    var choices by remember { mutableStateOf<List<ArchiveTarget>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            choices = withContext(Dispatchers.IO) {
                (
                    graph.getFavorites.await().map {
                        it.id
                    } + graph.translationRepository.chapterSeriesIds()
                    ).distinct().flatMap { id ->
                    val (manga, chapters) = graph.translationManager.chapterChoices(id)
                    chapters.map { chapter ->
                        ArchiveTarget(
                            manga.id,
                            chapter.id,
                            manga.title,
                            chapter.name + if (manga.source == 0L) " · Local source" else "",
                            chapter.dateUpload.takeIf { it > 0 } ?: chapter.dateFetch,
                        )
                    }
                }.sortedWith(
                    compareByDescending<ArchiveTarget> {
                        it.date
                    }.thenBy { it.title }.thenByDescending { it.chapterId },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Cannot load indexed chapters"
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Link to a chapter") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("Search series and chapters") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (choices == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(
                    choices.orEmpty().filter {
                        it.title.contains(query, true) || it.chapter.contains(query, true)
                    },
                    key = { it.chapterId },
                ) { choice ->
                    TextButton(onClick = { onSelected(choice) }) { Text("${choice.title}\n${choice.chapter}") }
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Serializable
private data class PendingBackupRequest(val jobIds: List<String>, val logs: Boolean, val captures: Boolean)

@Serializable
private data class PendingRenderedRequest(val jobId: String, val format: String, val paper: String)
