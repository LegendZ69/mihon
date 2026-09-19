package mihon.feature.translation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationStage
import java.text.DateFormat
import java.util.Date

/** Matches dashboard operation buckets by their persisted update time, independently of usage availability. */
class TranslationOperationsScreen(
    private val since: Long,
    private val until: Long,
    private val stage: TranslationStage? = null,
    private val provider: String? = null,
    private val model: String? = null,
    private val jobId: String? = null,
    private val state: TranslationOperationState? = null,
) : Screen() {
    @Composable
    override fun Content() {
        val graph = LocalContext.current.appGraph
        val navigator = LocalNavigator.currentOrThrow
        val jobs by graph.translationManager.jobs.collectAsState(emptyList())
        val settings by graph.translationPreferences.settings.collectAsState()
        var page by remember { mutableStateOf(0L) }
        var rows by remember { mutableStateOf(emptyList<TranslationOperation>()) }
        var more by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var refresh by remember { mutableStateOf(0) }
        LaunchedEffect(page, refresh) {
            loading = true
            error = null
            try {
                val result = withContext(Dispatchers.IO) {
                    graph.translationRepository.operationPage(
                        jobId,
                        PAGE_SIZE + 1,
                        page * PAGE_SIZE,
                        stage,
                        since,
                        until,
                        provider,
                        model,
                        state,
                    )
                }
                more = result.size > PAGE_SIZE
                rows = result.take(PAGE_SIZE.toInt())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Operation records unavailable"
                rows = emptyList()
                more = false
            } finally {
                loading = false
            }
        }
        Scaffold(topBar = { AppBar(title = "Measured operations", navigateUp = navigator::pop) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Text(
                    "${stage?.label ?: "All stages"} · ${provider ?: "All providers"} · ${model ?: "All models"}",
                    Modifier.padding(12.dp),
                )
                state?.let { Text("State: ${it.name.lowercase()}", Modifier.padding(horizontal = 12.dp)) }
                Text(
                    "Updated ${DateFormat.getDateTimeInstance().format(Date(since))} – " +
                        "${DateFormat.getDateTimeInstance().format(Date(until))} (end excluded)",
                    Modifier.padding(horizontal = 12.dp),
                )
                Text(
                    "Records use the dashboard's operation update times. Usage may be unavailable; " +
                        "active records can move between time buckets as they progress.",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    TextButton(enabled = page > 0 && !loading, onClick = { page-- }) { Text("Previous") }
                    TextButton(enabled = more && !loading, onClick = { page++ }) { Text("Next") }
                    TextButton(enabled = !loading, onClick = {
                        page = 0
                        refresh++
                    }) { Text("Refresh") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!loading && error == null &&
                        rows.isEmpty()
                    ) {
                        item { Text("No matching operations.", Modifier.padding(12.dp)) }
                    }
                    items(rows, key = { it.id }) { operation ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                val job = jobs.firstOrNull { it.id == operation.jobId }
                                Text(
                                    job?.let { "${it.mangaTitle} · ${it.chapterTitle}" }
                                        ?: "Retained work ${operation.jobId}",
                                )
                                Text(
                                    "${translationStateSymbol(operation.state.jobState())} ${operation.description()}",
                                    color = translationStateColor(operation.state.jobState(), settings.queueColors),
                                )
                                Text(
                                    "${operation.provider ?: "Provider unavailable"} · " +
                                        "${operation.model ?: "Model unavailable"}",
                                )
                                operation.message?.let { Text(it) }
                                Text(
                                    "Updated ${DateFormat.getDateTimeInstance().format(Date(operation.updatedAt))}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = {
                                    navigator.push(TranslationLogsScreen(operation.jobId, operationId = operation.id))
                                }) {
                                    Text("Related logs, attempts and sanitized API")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 100L
    }
}
