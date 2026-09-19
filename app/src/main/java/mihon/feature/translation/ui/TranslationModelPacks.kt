package mihon.feature.translation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.feature.translation.ocr.PaddleModelState
import mihon.feature.translation.ocr.PaddleModelStatus
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.FormatListBulleted
import mihon.icons.materialsymbols.rounded.Cancel
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Settings
import tachiyomi.domain.translation.model.TranslationJobState

private val PaddleModelState.selectionId: String get() = "${profile.name}:$korean"
private val PaddleModelState.language: String get() = if (korean) "ko" else "auto"
private val PaddleModelState.title: String
    get() = "${profile.name.lowercase().replaceFirstChar(
        Char::uppercase,
    )} · ${if (korean) "Korean" else "Multilingual"}"

/** Pack gestures always recheck the manager's live state before starting or cancelling an operation. */
@Composable
fun TranslationModelPacks(
    states: List<PaddleModelState>,
    onError: (Throwable) -> Unit,
    onSelectionChanged: ((Int, () -> Unit) -> Unit)? = null,
) {
    val graph = LocalContext.current.appGraph
    val manager = graph.paddleModelManager
    val uiSettings by graph.translationPreferences.settings.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(selected) { onSelectionChanged?.invoke(selected.size) { selected = emptySet() } }
    DisposableEffect(Unit) { onDispose { onSelectionChanged?.invoke(0) {} } }
    var managementIds by remember { mutableStateOf<Set<String>?>(null) }
    var removalIds by remember { mutableStateOf<Set<String>?>(null) }
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }
    var removing by remember { mutableStateOf(false) }
    var pendingInstalls by remember { mutableStateOf(emptySet<String>()) }
    BackHandler(selected.isNotEmpty()) { selected = emptySet() }

    fun perform(ids: Set<String>) {
        if (removing) return
        // Cancellation is immediate; queued installation work must not delay it behind a suspended download.
        val current = manager.states.value.filter { it.selectionId in ids }
        current.filter { it.status == PaddleModelStatus.DOWNLOADING }.forEach { state ->
            state.operationId?.let(manager::cancel)
        }
        current.filter { it.status == PaddleModelStatus.NOT_DOWNLOADED || it.status == PaddleModelStatus.FAILED }
            .filterNot { it.selectionId in pendingInstalls }
            .forEach { state ->
                pendingInstalls = pendingInstalls + state.selectionId
                scope.launch {
                    try {
                        manager.download(state.profile, state.language)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        onError(failure)
                    } finally {
                        pendingInstalls = pendingInstalls - state.selectionId
                    }
                }
            }
        if (current.size == 1 && current.single().status == PaddleModelStatus.INSTALLED) managementIds = ids
    }
    Column {
        TranslationSelectionBar(
            selected.size,
            { selected = emptySet() },
            listOf(
                TranslationRowAction("Install, retry or cancel selected packs", MaterialSymbols.Rounded.Download) {
                    perform(selected.toSet())
                },
                TranslationRowAction("Manage selected packs", MaterialSymbols.Rounded.Settings) {
                    managementIds = selected.toSet()
                },
            ),
            showCounter = onSelectionChanged == null,
        )
        states.forEach { state ->
            val id = state.selectionId
            val primary = TranslationRowAction(
                when (state.status) {
                    PaddleModelStatus.DOWNLOADING -> "Cancel download"
                    PaddleModelStatus.FAILED -> "Retry download"
                    PaddleModelStatus.NOT_DOWNLOADED -> "Install model pack"
                    PaddleModelStatus.INSTALLED -> "Manage installed model pack"
                },
                if (state.status ==
                    PaddleModelStatus.DOWNLOADING
                ) {
                    MaterialSymbols.Rounded.Cancel
                } else {
                    MaterialSymbols.Rounded.Download
                },
            ) { perform(setOf(id)) }
            val actions = buildMap {
                put(TranslationGestureAction.MODEL_ACTION, primary)
                put(
                    TranslationGestureAction.MANAGEMENT,
                    TranslationRowAction("Model management", MaterialSymbols.Rounded.Settings) {
                        managementIds = setOf(id)
                    },
                )
                state.operationId?.let { operation ->
                    put(
                        TranslationGestureAction.LOGS,
                        TranslationRowAction(
                            "Model download logs",
                            MaterialSymbols.AutoMirroredRounded.FormatListBulleted,
                        ) {
                            navigator.push(TranslationLogsScreen(operationId = operation))
                        },
                    )
                }
            }
            TranslationActionRow(
                selected = id in selected,
                selectionActive = selected.isNotEmpty() || removing,
                onClick = {
                    if (selected.isNotEmpty()) {
                        selected = if (id in selected) selected - id else selected + id
                    } else {
                        expandedIds = if (id in expandedIds) expandedIds - id else expandedIds + id
                    }
                },
                onLongClick = { selected = if (id in selected) selected - id else selected + id },
                startAction = configuredTranslationSwipe(
                    TranslationGestureRow.MODEL,
                    TranslationGestureDirection.START,
                    actions,
                ),
                endAction = configuredTranslationSwipe(
                    TranslationGestureRow.MODEL,
                    TranslationGestureDirection.END,
                    actions,
                ),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(state.title, style = MaterialTheme.typography.bodyLarge)
                        val status = when (state.status) {
                            PaddleModelStatus.INSTALLED -> "Installed"
                            PaddleModelStatus.DOWNLOADING -> "Downloading"
                            PaddleModelStatus.FAILED -> "Download failed"
                            PaddleModelStatus.NOT_DOWNLOADED -> "Not installed"
                        }
                        val statusState = when (state.status) {
                            PaddleModelStatus.INSTALLED -> TranslationJobState.COMPLETED
                            PaddleModelStatus.FAILED -> TranslationJobState.FAILED
                            PaddleModelStatus.DOWNLOADING -> TranslationJobState.ACQUIRING
                            PaddleModelStatus.NOT_DOWNLOADED -> TranslationJobState.QUEUED
                        }
                        Row {
                            TranslationStatusIcon(statusState, uiSettings.queueColors)
                            Text(
                                "$status · ${state.downloadBytes / 1024 / 1024}/${state.totalBytes / 1024 / 1024} MiB",
                                color = translationStateColor(statusState, uiSettings.queueColors),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        state.error?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (selected.isEmpty()) {
                        TranslationRowActionMenu(
                            actions.values.toList(),
                            "Actions for ${state.title}",
                        )
                    }
                }
                if (state.status == PaddleModelStatus.DOWNLOADING) {
                    if (state.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { (state.downloadBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (id in expandedIds) {
                    Text(
                        "${state.detectorModel}\n${state.recognizerModel}",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    managementIds?.let { ids ->
        val current = states.filter { it.selectionId in ids }
        AlertDialog(
            onDismissRequest = { managementIds = null },
            title = { Text("Model management") },
            text = {
                Column {
                    current.forEach { state ->
                        Text(state.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${state.detectorModel}\n${state.recognizerModel}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        state.operationId?.let { operation ->
                            TextButton(onClick = {
                                managementIds = null
                                navigator.push(TranslationLogsScreen(operationId = operation))
                            }) { Text("Related download logs") }
                        }
                    }
                    Text(
                        "Removing a model pack preserves translations and OCR results. " +
                            "It must be downloaded again before local OCR can use it.",
                    )
                }
            },
            confirmButton = {
                if (current.any { it.status == PaddleModelStatus.INSTALLED }) {
                    TextButton(onClick = {
                        removalIds =
                            current.filter { it.status == PaddleModelStatus.INSTALLED }.map { it.selectionId }.toSet()
                        managementIds = null
                    }) { Text("Remove installed packs…") }
                } else {
                    TextButton(onClick = {
                        perform(ids)
                        managementIds = null
                    }) { Text("Install, retry or cancel") }
                }
            },
            dismissButton = { TextButton(onClick = { managementIds = null }) { Text("Close") } },
        )
    }
    removalIds?.let { ids ->
        val current = states.filter { it.selectionId in ids && it.status == PaddleModelStatus.INSTALLED }
        AlertDialog(
            onDismissRequest = { if (!removing) removalIds = null },
            title = { Text("Remove installed model packs?") },
            text = {
                Text(
                    current.joinToString("\n") { "${it.title}: ${it.totalBytes} artifact bytes" } +
                        "\nOnly these owned model directories are removed. Saved translations and source pages remain.",
                )
            },
            confirmButton = {
                TextButton(enabled = !removing && current.isNotEmpty(), onClick = {
                    removing = true
                    scope.launch {
                        try {
                            current.forEach { manager.remove(it.profile, it.language) }
                            removalIds = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            onError(failure)
                        } finally {
                            removing = false
                        }
                    }
                }) { Text(if (removing) "Removing…" else "Remove") }
            },
            dismissButton = { TextButton(enabled = !removing, onClick = { removalIds = null }) { Text("Cancel") } },
        )
    }
}
