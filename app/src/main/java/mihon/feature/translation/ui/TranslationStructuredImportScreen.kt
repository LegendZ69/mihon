package mihon.feature.translation.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.feature.translation.transfer.StructuredImportTarget
import mihon.feature.translation.transfer.StructuredTranslationDocuments
import tachiyomi.domain.translation.model.StructuredImportDisposition
import tachiyomi.domain.translation.model.StructuredImportDocument
import tachiyomi.domain.translation.model.StructuredImportFormat
import tachiyomi.domain.translation.model.StructuredImportIssue
import tachiyomi.domain.translation.model.StructuredImportPlan
import tachiyomi.domain.translation.model.StructuredImportSource
import tachiyomi.domain.translation.model.TranslationImage
import java.io.File

private data class ImportChapterChoice(val mangaId: Long, val chapterId: Long, val title: String, val chapter: String)

/** A session retains only one parsed page graph; other documents keep bounded display metadata. */
private data class ImportFileSummary(
    val source: StructuredImportSource,
    val pageCount: Int,
    val errors: List<StructuredImportIssue>,
)

/** All preparation is local or source-image acquisition. Only the final explicit action saves translations. */
class TranslationStructuredImportScreen(
    private val mangaIds: List<Long> = emptyList(),
    private val chapterIds: List<Long> = emptyList(),
    private val initialUris: List<String> = emptyList(),
) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val graph = context.appGraph
        val service = graph.translationStructuredImportService
        val navigator = LocalNavigator.currentOrThrow
        val clipboard = LocalClipboardManager.current
        val scope = rememberCoroutineScope()
        var uriStrings by rememberSaveable { mutableStateOf(ArrayList(initialUris.distinct().take(128))) }
        var activeUri by rememberSaveable { mutableStateOf(initialUris.firstOrNull()) }
        var documents by remember { mutableStateOf<Map<String, ImportFileSummary>>(emptyMap()) }
        var activeDocument by remember { mutableStateOf<StructuredImportDocument?>(null) }
        var documentUri by remember { mutableStateOf<String?>(null) }
        var reloadFiles by remember { mutableStateOf(0) }
        var fileErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var step by remember { mutableStateOf(0) }
        var choices by remember { mutableStateOf<List<ImportChapterChoice>>(emptyList()) }
        var chosenChapter by remember { mutableStateOf<ImportChapterChoice?>(null) }
        var chooseChapter by remember { mutableStateOf(false) }
        var target by remember { mutableStateOf<StructuredImportTarget?>(null) }
        var mapping by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var choosingPage by remember { mutableStateOf<String?>(null) }
        var replaceKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
        var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
        var plan by remember { mutableStateOf<StructuredImportPlan?>(null) }
        var busy by remember { mutableStateOf(false) }
        var work by remember { mutableStateOf<Job?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var completedJobs by remember { mutableStateOf<List<String>>(emptyList()) }
        var showInstructions by remember { mutableStateOf(false) }
        var pendingDocument by rememberSaveable { mutableStateOf<String?>(null) }
        val document = activeDocument.takeIf { documentUri == activeUri }
        fun execute(block: suspend () -> Unit) {
            if (busy) return
            busy = true
            message = null
            work = scope.launch {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    message = "Cancelled. Saved pages, if any, remain available."
                    throw cancelled
                } catch (error: Exception) {
                    message = error.message ?: "Cannot prepare structured translation files"
                } finally {
                    busy = false
                    work = null
                }
            }
        }
        fun resetPrepared() {
            target?.close()
            target = null
            mapping = emptyMap()
            replaceKeys = emptySet()
            selectedKeys = emptySet()
            plan = null
            completedJobs = emptyList()
        }
        fun leave() {
            if (busy) {
                work?.cancel()
            } else if (step > 0) {
                step -= 1
            } else if (!navigator.pop()) {
                (context as? android.app.Activity)?.finish()
            }
        }
        BackHandler { leave() }
        DisposableEffect(Unit) {
            onDispose {
                val held = work
                if (held != null) {
                    held.invokeOnCompletion { target?.close() }
                } else {
                    target?.close()
                }
            }
        }
        LaunchedEffect(mangaIds, chapterIds) {
            try {
                choices = withContext(Dispatchers.IO) {
                    val ids = mangaIds.ifEmpty {
                        (graph.getFavorites.await().map { it.id } + graph.translationRepository.chapterSeriesIds())
                            .distinct()
                    }
                    ids.flatMap { id ->
                        val (manga, chapters) = graph.translationManager.chapterChoices(id)
                        chapters.filter { chapterIds.isEmpty() || it.id in chapterIds }.map {
                            ImportChapterChoice(manga.id, it.id, manga.title, it.name)
                        }
                    }
                }
                if (choices.size == 1) chosenChapter = choices.single()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = error.message ?: "Cannot load chapter choices"
            }
        }
        // Persisted SAF grants let recreation re-validate files instead of retaining stale commit decisions.
        LaunchedEffect(uriStrings.toList(), activeUri, reloadFiles) {
            suspend fun decodeFile(text: String): StructuredImportDocument = withContext(Dispatchers.IO) {
                val uri = Uri.parse(text)
                val name = context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "Imported document"
                context.contentResolver.openInputStream(uri)?.use { service.stage(it, name) }
                    ?: error("Cannot read $name. Choose the file again.")
            }
            val unread = uriStrings.filterNot { it in documents || it in fileErrors }
            val activeNeedsRead = activeUri?.takeIf { it != documentUri && it !in fileErrors }
            val order = (listOfNotNull(activeNeedsRead) + unread).distinct()
            order.forEach { text ->
                try {
                    val decoded = decodeFile(text)
                    val retainedBytes = documents.filterKeys { it != text }.values.sumOf { it.source.bytes }
                    require(decoded.source.bytes <= 512L * 1024 * 1024 - retainedBytes) {
                        "Selected documents exceed the 512 MiB staging limit. Remove a file and try again."
                    }
                    val summary = ImportFileSummary(decoded.source, decoded.pages.size, decoded.errors.take(50))
                    documents = documents + (text to summary)
                    if (text == activeUri) {
                        activeDocument = decoded
                        documentUri = text
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    fileErrors = fileErrors + (text to (error.message ?: "Cannot read selected file"))
                }
            }
        }
        val chooseFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                uris.forEach { uri ->
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
                val reselected = uris.map(Uri::toString).toSet()
                documents = documents - reselected
                fileErrors = fileErrors - reselected
                if (activeUri in reselected) {
                    resetPrepared()
                    activeDocument = null
                    documentUri = null
                }
                reloadFiles++
                val combined = (uriStrings + reselected).distinct()
                if (combined.size > 128) message = "Select at most 128 documents per import session."
                uriStrings = ArrayList(combined.take(128))
                if (activeUri == null) activeUri = uris.first().toString()
            }
        }
        val saveDocument = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            val text = pendingDocument
            pendingDocument = null
            if (uri != null && text != null) {
                execute {
                    withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                            output.write(text.toByteArray())
                        }
                    }
                    message = "Reference document saved. No translation request was made."
                }
            }
        }
        fun preview() {
            val source = document ?: return
            val selectedTarget = target ?: return
            execute {
                val next = withContext(Dispatchers.IO) {
                    service.preview(source, selectedTarget, mapping, replaceKeys)
                }
                plan = next
                selectedKeys = next.readyPages.map { it.sourceKey }.toSet()
                step = 2
            }
        }
        Scaffold(topBar = { AppBar(title = "Import structured translations", navigateUp = ::leave) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Text(
                    listOf("1 · Files", "2 · Page matching", "3 · Validation / conflicts", "4 · Imported")[step],
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(
                            "Imports do not call a translation provider. Existing translations and edits are " +
                                "kept unless you explicitly select a replacement.",
                            Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (step == 0) {
                        item {
                            Row {
                                TextButton(enabled = !busy, onClick = {
                                    chooseFiles.launch(
                                        arrayOf(
                                            "application/json",
                                            "application/zip",
                                            "text/plain",
                                            "application/octet-stream",
                                        ),
                                    )
                                }) { Text("Choose files…") }
                                TextButton(onClick = { showInstructions = true }) { Text("Format instructions") }
                            }
                        }
                        items(uriStrings, key = { it }) { uri ->
                            val value = documents[uri]
                            Column(
                                Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                    if (activeUri != uri) {
                                        resetPrepared()
                                        activeDocument = null
                                        documentUri = null
                                        activeUri = uri
                                    }
                                }.padding(16.dp),
                            ) {
                                Text(
                                    (if (activeUri == uri) "✓ " else "") +
                                        (value?.source?.name ?: "Reading document…"),
                                )
                                value?.let {
                                    Text(
                                        "${it.source.format.name} · ${it.pageCount} pages · ${it.source.bytes} bytes",
                                    )
                                    Text("SHA-256 ${it.source.sha256}", style = MaterialTheme.typography.bodySmall)
                                    it.errors.forEach { error ->
                                        Text(
                                            "${error.path}: ${error.message}",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                fileErrors[uri]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                TextButton(enabled = !busy, onClick = {
                                    uriStrings = ArrayList(uriStrings - uri)
                                    documents = documents - uri
                                    fileErrors = fileErrors - uri
                                    if (activeUri == uri) {
                                        resetPrepared()
                                        activeDocument = null
                                        documentUri = null
                                        activeUri = uriStrings.firstOrNull()
                                    }
                                }) { Text("Remove from selection") }
                            }
                        }
                    }
                    if (step == 1) {
                        item {
                            TextButton(enabled = !busy, onClick = { chooseChapter = true }) {
                                Text(
                                    chosenChapter?.let { "${it.title} · ${it.chapter}" }
                                        ?: "Choose library / local chapter…",
                                )
                            }
                            Text(
                                "Loading originals follows Mihon's source-download restrictions. " +
                                    "Hashes and dimensions can match automatically; other pages require " +
                                    "your explicit thumbnail selection.",
                                Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(enabled = !busy && chosenChapter != null, onClick = {
                                val chapter = chosenChapter ?: return@TextButton
                                execute {
                                    resetPrepared()
                                    target = service.prepareTarget(chapter.mangaId, chapter.chapterId)
                                    val source = document ?: return@execute
                                    plan = withContext(Dispatchers.IO) {
                                        service.preview(source, requireNotNull(target), emptyMap(), emptySet())
                                    }
                                }
                            }) {
                                Text(
                                    if (target == null) "Load original pages" else "Reload original pages and results",
                                )
                            }
                        }
                        items(document?.pages.orEmpty(), key = { it.key }) { page ->
                            val planned = plan?.pages?.firstOrNull { it.sourceKey == page.key }
                            val original = mapping[page.key]?.let { id -> target?.images?.firstOrNull { it.id == id } }
                                ?: planned?.image
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Text("File image ID: ${page.imageId}", style = MaterialTheme.typography.titleSmall)
                                Text("${page.coordinates.name} · ${page.regions.size} regions")
                                page.imageHash?.let {
                                    Text("Original SHA-256 $it", style = MaterialTheme.typography.bodySmall)
                                }
                                original?.let { ImportOriginalThumbnail(it) }
                                TextButton(enabled = !busy && target != null, onClick = { choosingPage = page.key }) {
                                    Text(
                                        if (original == null) "Match original page…" else "Change explicit page match…",
                                    )
                                }
                                page.issues.forEach {
                                    Text("${it.path}: ${it.message}", color = MaterialTheme.colorScheme.error)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                    if (step == 2) {
                        item {
                            Text(
                                "${selectedKeys.size} valid pages selected. Identical pages are skipped. Invalid and " +
                                    "unmatched pages stay unselected; importing a valid subset keeps " +
                                    "the rest awaiting files.",
                                Modifier.padding(16.dp),
                            )
                            if (plan?.pages?.isNotEmpty() == true &&
                                plan?.pages?.all { it.disposition == StructuredImportDisposition.IDENTICAL } == true
                            ) {
                                Text(
                                    "All matched pages are already saved. Nothing needs importing.",
                                    Modifier.padding(16.dp),
                                )
                                target?.job?.id?.let { id ->
                                    TextButton(onClick = { navigator.push(TranslationInspectorScreen(id)) }) {
                                        Text("Open existing results")
                                    }
                                }
                            }
                            TextButton(enabled = !busy, onClick = {
                                val chapter = chosenChapter ?: return@TextButton
                                execute {
                                    resetPrepared()
                                    val reloaded = service.prepareTarget(chapter.mangaId, chapter.chapterId)
                                    target = reloaded
                                    plan = withContext(Dispatchers.IO) {
                                        service.preview(requireNotNull(document), reloaded, emptyMap(), emptySet())
                                    }
                                    step = 1
                                    message = "Saved results reloaded. Confirm page matches and replacements again."
                                }
                            }) { Text("Reload saved results and matches") }
                        }
                        items(plan?.pages.orEmpty(), key = { it.sourceKey }) { page ->
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                ImportCheckbox(
                                    label = (page.image?.let { "Original page ${it.index + 1}" } ?: page.sourceKey) +
                                        " · " +
                                        page.disposition.name.lowercase().replace('_', ' '),
                                    checked = page.sourceKey in selectedKeys,
                                    enabled = !busy && page.disposition == StructuredImportDisposition.READY,
                                ) { selectedKeys = selectedKeys.toggle(page.sourceKey) }
                                page.image?.let { ImportOriginalThumbnail(it) }
                                page.issues.forEach {
                                    Text("${it.path}: ${it.message}", color = MaterialTheme.colorScheme.error)
                                }
                                if (page.normalizedRegions.isNotEmpty()) {
                                    Text(
                                        "Redundant outline points normalized: " +
                                            page.normalizedRegions.joinToString(),
                                    )
                                }
                                if (page.disposition == StructuredImportDisposition.CONFLICT ||
                                    page.sourceKey in replaceKeys
                                ) {
                                    ImportCheckbox(
                                        "Replace this page's conflicting saved result",
                                        page.sourceKey in replaceKeys,
                                        !busy,
                                    ) { checked ->
                                        replaceKeys = if (checked) {
                                            replaceKeys + page.sourceKey
                                        } else {
                                            replaceKeys - page.sourceKey
                                        }
                                        preview()
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                    if (step == 3) {
                        item {
                            Text(
                                "Import complete. Original images, Ignore SFX and current overlay styles " +
                                    "remain in use.",
                                Modifier.padding(16.dp),
                            )
                            completedJobs.forEach { id ->
                                TextButton(onClick = { navigator.push(TranslationInspectorScreen(id)) }) {
                                    Text("Inspect saved pages")
                                }
                                TextButton(onClick = { navigator.push(TranslationLogsScreen(id)) }) {
                                    Text("Related import logs")
                                }
                            }
                            TextButton(onClick = {
                                resetPrepared()
                                step = 0
                            }) { Text("Import another file or chapter") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                    if (busy) TextButton(onClick = { work?.cancel() }) { Text("Cancel operation") }
                    if (step == 0) {
                        TextButton(enabled = !busy && document != null, onClick = {
                            if (document?.source?.format == StructuredImportFormat.MIHON_ZIP) {
                                navigator.push(
                                    TranslationManagementScreen(initialImportUri = activeUri, structuredFiles = true),
                                )
                            } else {
                                step = 1
                            }
                        }) {
                            Text(
                                if (document?.source?.format == StructuredImportFormat.MIHON_ZIP) {
                                    "Inspect ZIP backup…"
                                } else {
                                    "Match pages"
                                },
                            )
                        }
                    } else if (step == 1) {
                        TextButton(enabled = !busy && target != null, onClick = ::preview) { Text("Validate matches") }
                    } else if (step == 2) {
                        TextButton(enabled = !busy && selectedKeys.isNotEmpty(), onClick = {
                            val checked = plan ?: return@TextButton
                            val checkedTarget = target ?: return@TextButton
                            val keys = selectedKeys.toSet()
                            execute {
                                val report = withContext(Dispatchers.IO) {
                                    service.commit(checked, checkedTarget, keys)
                                }
                                completedJobs = report.jobIds
                                message = "Saved ${report.importedPages} pages · " +
                                    "${report.identicalPages} identical skipped · " +
                                    "${report.preservedConflicts} local conflicts preserved\n" +
                                    report.warnings.joinToString("\n")
                                step = 3
                            }
                        }) { Text("Import ${selectedKeys.size} selected pages") }
                    }
                }
            }
        }
        if (chooseChapter) {
            ImportChapterSheet(choices, onDismiss = { chooseChapter = false }) { chosen ->
                resetPrepared()
                chosenChapter = chosen
                chooseChapter = false
            }
        }
        choosingPage?.let { pageKey ->
            AdaptiveSheet(onDismissRequest = { choosingPage = null }) {
                LazyColumn {
                    item {
                        Text(
                            "Match file image to an original page",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    item {
                        Text(
                            "File IDs do not imply chapter page numbers. " +
                                "Confirm the original thumbnail and dimensions.",
                            Modifier.padding(16.dp),
                        )
                    }
                    items(target?.images.orEmpty(), key = { it.id }) { image ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                mapping = mapping + (pageKey to image.id)
                                plan = null
                                choosingPage = null
                            }.padding(16.dp),
                        ) { ImportOriginalThumbnail(image) }
                    }
                    item {
                        TextButton(onClick = {
                            mapping = mapping - pageKey
                            plan = null
                            choosingPage = null
                        }) {
                            Text("Clear explicit match")
                        }
                    }
                }
            }
        }
        if (showInstructions) {
            AlertDialog(
                onDismissRequest = { showInstructions = false },
                title = { Text("Structured translation format") },
                text = {
                    Column {
                        Text(
                            "Portable JSON uses original-image pixel coordinates. Provider output uses normalized " +
                                "0–1000 coordinates. Tile output requires its original transform.",
                        )
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(StructuredTranslationDocuments.outputInstructions))
                        }) {
                            Text("Copy output instructions")
                        }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(StructuredTranslationDocuments.sampleJson))
                        }) {
                            Text("Copy sample JSON")
                        }
                        TextButton(onClick = {
                            pendingDocument = StructuredTranslationDocuments.sampleJson
                            saveDocument.launch("mihon-structured-translations-example.json")
                        }) { Text("Save sample JSON…") }
                        TextButton(onClick = {
                            pendingDocument = StructuredTranslationDocuments.schemaJson
                            saveDocument.launch("mihon-structured-translations-schema.json")
                        }) { Text("Save JSON schema…") }
                    }
                },
                confirmButton = { TextButton(onClick = { showInstructions = false }) { Text("Close") } },
            )
        }
    }
}

@Composable
private fun ImportOriginalThumbnail(image: TranslationImage) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(
            model = File(image.filePath),
            contentDescription = "Original page ${image.index + 1}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(64.dp, 88.dp),
        )
        Column {
            Text("Page ${image.index + 1} · ${image.width} × ${image.height}")
            Text("ID ${image.id}", style = MaterialTheme.typography.bodySmall)
            Text(image.contentHash, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ImportCheckbox(label: String, checked: Boolean, enabled: Boolean, changed: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = changed),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, null, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun ImportChapterSheet(
    chapters: List<ImportChapterChoice>,
    onDismiss: () -> Unit,
    onChoose: (ImportChapterChoice) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column {
            Text("Choose destination chapter", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                query,
                { query = it },
                label = { Text("Search series and chapters") },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            LazyColumn {
                items(
                    chapters.filter { it.title.contains(query, true) || it.chapter.contains(query, true) },
                    key = { it.chapterId },
                ) { chapter ->
                    Column(Modifier.fillMaxWidth().clickable { onChoose(chapter) }.padding(16.dp)) {
                        Text(chapter.title, style = MaterialTheme.typography.titleSmall)
                        Text(chapter.chapter)
                    }
                }
            }
        }
    }
}
