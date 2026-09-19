package mihon.feature.translation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import tachiyomi.domain.translation.model.TranslationUsageRecord
import java.text.DateFormat
import java.util.Date

class TranslationUsageScreen(
    private val since: Long,
    private val until: Long,
    private val provider: String? = null,
    private val model: String? = null,
) : Screen() {
    @Composable
    override fun Content() {
        val repository = LocalContext.current.appGraph.translationRepository
        val navigator = LocalNavigator.currentOrThrow
        var page by remember { mutableStateOf(0L) }
        var rows by remember { mutableStateOf(emptyList<TranslationUsageRecord>()) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var refresh by remember { mutableStateOf(0) }
        LaunchedEffect(page, refresh) {
            loading = true
            error = null
            try {
                rows =
                    withContext(Dispatchers.IO) { repository.usagePage(since, until, provider, model, 100, page * 100) }
            } catch (
                cancelled: CancellationException,
            ) {
                throw cancelled
            } catch (
                failure: Exception,
            ) {
                error = failure.message ?: "Usage records unavailable"
            } finally {
                loading = false
            }
        }
        Scaffold(topBar = { AppBar(title = "Request usage and prices", navigateUp = navigator::pop) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Text(
                    "${provider ?: "All providers"} · ${model ?: "All models"}\n" +
                        "${DateFormat.getDateTimeInstance().format(Date(since))} – " +
                        DateFormat.getDateTimeInstance().format(Date(until)),
                    Modifier.padding(12.dp),
                )
                Text(
                    "Local estimates and uncertain reservations are separate from cloud costs and invoices.",
                    Modifier.padding(horizontal = 12.dp),
                )
                Row {
                    TextButton(enabled = page > 0 && !loading, onClick = { page-- }) { Text("Previous") }
                    TextButton(enabled = rows.size == 100 && !loading, onClick = { page++ }) { Text("Next") }
                    TextButton(enabled = !loading, onClick = { refresh++ }) { Text("Refresh") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!loading &&
                        rows.isEmpty()
                    ) {
                        item { Text("No matching generation attempts.", Modifier.padding(12.dp)) }
                    }
                    items(rows, key = { it.id }) { row ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${row.provider} / ${row.model}", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${DateFormat.getDateTimeInstance().format(Date(row.time))} · " +
                                        if (row.reviewId == null) "Translation / generation" else "AI review",
                                )
                                Text(
                                    "Input ${row.usage.inputTokens ?: "Unavailable"} · " +
                                        "output ${row.usage.outputTokens ?: "Unavailable"} · " +
                                        "cached ${row.usage.cachedTokens ?: "Unavailable"} · " +
                                        "reasoning ${row.usage.reasoningTokens ?: "Unavailable"}",
                                )
                                Text("Estimated USD ${row.estimatedUsd ?: "Unavailable"}")
                                if (row.outcomeUncertain) {
                                    Text(
                                        "⚠ Uncertain result · reserved ${row.reservedAmount ?: "Unavailable"} " +
                                            "${row.reservedCurrency ?: "currency unavailable"}",
                                    )
                                }
                                SelectionContainer {
                                    Text(
                                        "Attempt ${row.id}\nRequest ${row.requestId ?: "Unavailable"}\n" +
                                            "Price verified ${row.pricingVerifiedAt ?: "Unavailable"}\n" +
                                            "${row.pricingSource ?: "Pricing source unavailable"}\n" +
                                            "Applied exchange rate ${row.exchangeRate ?: "Unavailable"}\n" +
                                            "${row.exchangeRateSource ?: "Exchange-rate source unavailable"}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                TextButton(onClick = {
                                    navigator.push(
                                        TranslationLogsScreen(
                                            row.jobId,
                                            operationId = row.operationId,
                                            batchId = row.batchId.takeIf {
                                                row.operationId ==
                                                    null
                                            },
                                        ),
                                    )
                                }) { Text("Attempt, related logs and sanitized API") }
                            }
                        }
                    }
                }
            }
        }
    }
}
