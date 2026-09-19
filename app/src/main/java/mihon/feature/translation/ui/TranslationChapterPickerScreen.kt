package mihon.feature.translation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.service.TranslationCoverage
import tachiyomi.domain.translation.service.TranslationCoverageState

private data class ChapterChoice(
    val manga: Manga,
    val chapter: Chapter,
    val coverage: TranslationCoverage,
    val job: TranslationJob?,
    val reviews: Set<QualityReviewState>,
) {
    val active get() = job?.state in chapterActionActiveStates
    val error get() = job?.state == TranslationJobState.FAILED

    // LocalSource sets dateUpload from chapterFile.lastModified(). Other fallbacks are stable.
    val newest get() = chapter.dateUpload.takeIf { it > 0 } ?: chapter.dateFetch.takeIf { it > 0 } ?: 0
}

class TranslationChapterPickerScreen(private val mangaIds: List<Long> = emptyList()) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val graph = context.appGraph
        val manager = graph.translationManager
        val jobs by manager.jobs.collectAsState(emptyList())
        val reviews by remember { graph.translationRepository.observeReviewSummaries() }.collectAsState(emptyList())
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var choices by remember { mutableStateOf(emptyList<ChapterChoice>()) }
        var selected by remember { mutableStateOf(emptySet<Long>()) }
        var submitting by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(true) }
        var sourceNames by remember { mutableStateOf(emptyMap<Long, String>()) }
        var query by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Untranslated") }
        var sort by remember { mutableStateOf("Newest first") }
        var source by remember { mutableStateOf<Long?>(null) }
        var provider by remember { mutableStateOf<String?>(null) }
        var review by remember { mutableStateOf<QualityReviewState?>(null) }
        LaunchedEffect(mangaIds, jobs, reviews) {
            try {
                choices = withContext(Dispatchers.IO) {
                    val ids = mangaIds.ifEmpty {
                        (
                            graph.getFavorites.await().map {
                                it.id
                            } + graph.translationRepository.chapterSeriesIds()
                            ).distinct()
                    }
                    ids.flatMap { id ->
                        val (manga, chapters) = manager.chapterChoices(id)
                        chapters.map { chapter ->
                            val history = jobs.filter { it.chapterId == chapter.id }.sortedByDescending { it.createdAt }
                            ChapterChoice(
                                manga,
                                chapter,
                                graph.translationRepository.chapterCoverage(chapter.id),
                                selectChapterTranslationJob(history),
                                reviews.filter { it.jobId in history.map { job -> job.id } }
                                    .map { it.state }.toSet(),
                            )
                        }
                    }
                }
                sourceNames = withContext(Dispatchers.IO) {
                    choices.map { it.manga.source }.distinct().associateWith {
                        if (it ==
                            0L
                        ) {
                            "Local source"
                        } else {
                            graph.sourceManager.getOrStub(it).name
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                snackbar.showSnackbar(error.message ?: "Cannot load chapters")
            } finally {
                loading = false
            }
        }
        val filtered = choices.filter { choice ->
            (source == null || choice.manga.source == source) &&
                (provider == null || choice.job?.settings?.provider?.model == provider) &&
                (review == null || review in choice.reviews) &&
                (choice.manga.title.contains(query, true) || choice.chapter.name.contains(query, true)) &&
                when (status) {
                    "Untranslated" -> choice.coverage.state == TranslationCoverageState.UNTRANSLATED
                    "Completed" -> choice.coverage.state == TranslationCoverageState.COMPLETED
                    "Partial" -> choice.coverage.state == TranslationCoverageState.PARTIAL
                    "Error" -> choice.error
                    "Active" -> choice.active
                    "Total unavailable" -> choice.coverage.state == TranslationCoverageState.UNKNOWN
                    else -> true
                }
        }.sortedWith(
            when (sort) {
                "Oldest first" -> compareBy<ChapterChoice> { it.newest }
                "A–Z" -> compareBy<ChapterChoice> { it.manga.title.lowercase() }.thenBy { it.chapter.name.lowercase() }
                "Z–A" -> compareByDescending<ChapterChoice> {
                    it.manga.title.lowercase()
                }.thenByDescending { it.chapter.name.lowercase() }
                "Chapter number" -> compareBy<ChapterChoice> { it.chapter.chapterNumber }
                "Progress" -> compareByDescending<ChapterChoice> {
                    it.coverage.total?.let { total ->
                        it.coverage.saved.toDouble() /
                            total
                    }
                        ?: -1.0
                }
                else -> compareByDescending<ChapterChoice> { it.newest }
            }.thenBy { it.manga.id }.thenBy { it.chapter.sourceOrder }.thenBy { it.chapter.id },
        )
        Scaffold(
            topBar = { AppBar(title = "Chapters to translate", navigateUp = navigator::pop) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("Search titles and chapters") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    singleLine = true,
                )
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(
                        "Untranslated",
                        "All",
                        "Completed",
                        "Partial",
                        "Error",
                        "Active",
                        "Total unavailable",
                    ).forEach { value ->
                        FilterChip(status == value, { status = value }, label = { Text(value) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(
                        "Newest first",
                        "Oldest first",
                        "A–Z",
                        "Z–A",
                        "Chapter number",
                        "Progress",
                    ).forEach { value ->
                        FilterChip(sort == value, { sort = value }, label = { Text(value) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(source == null, { source = null }, label = { Text("All sources") })
                    choices.map { it.manga.source }.distinct().forEach { value ->
                        FilterChip(source == value, { source = value }, label = {
                            Text(
                                if (value ==
                                    0L
                                ) {
                                    "Local source"
                                } else {
                                    sourceNames[value] ?: "Source $value"
                                },
                            )
                        })
                    }
                    FilterChip(provider == null, { provider = null }, label = { Text("All models / no provider yet") })
                    choices.mapNotNull { it.job?.settings?.provider?.model }.distinct().forEach { value ->
                        FilterChip(provider == value, { provider = value }, label = { Text(value) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(review == null, { review = null }, label = { Text("Any review state") })
                    QualityReviewState.entries.forEach { value ->
                        FilterChip(review == value, {
                            review = value
                        }, label = { Text(value.name.lowercase().replace('_', ' ')) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(enabled = !submitting, onClick = {
                        selected = filtered.map { it.chapter.id }.toSet()
                    }) { Text("Select ${filtered.size} shown") }
                    TextButton(enabled = !submitting, onClick = { selected = emptySet() }) { Text("Clear") }
                    TextButton(
                        enabled = !submitting && selected.isNotEmpty(),
                        onClick = submit@{
                            if (submitting) return@submit
                            val chosen = choices.filter { it.chapter.id in selected }.toList()
                            submitting = true
                            scope.launch {
                                try {
                                    val imports = mutableListOf<ChapterChoice>()
                                    withContext(Dispatchers.IO) {
                                        chosen.groupBy { it.manga }.forEach { (manga, rows) ->
                                            rows.forEach rowLoop@{ row ->
                                                val job =
                                                    selectChapterTranslationJob(
                                                        graph.translationRepository.jobs().filter {
                                                            it.chapterId ==
                                                                row.chapter.id
                                                        },
                                                    )
                                                if (job?.state in chapterActionActiveStates) return@rowLoop
                                                if (job?.isStructuredFiles == true ||
                                                    graph.translationPreferences.effectiveSettings(manga.id).mode ==
                                                    TranslationMode.STRUCTURED_FILES
                                                ) {
                                                    imports += row
                                                    return@rowLoop
                                                }
                                                val coverage = graph.translationRepository.chapterCoverage(
                                                    row.chapter.id,
                                                )
                                                if (coverage.state == TranslationCoverageState.COMPLETED) return@rowLoop
                                                if (job != null && coverage.saved > 0) {
                                                    manager.retry(job.id)
                                                } else {
                                                    manager.enqueue(manga, listOf(row.chapter))
                                                }
                                            }
                                        }
                                    }
                                    if (imports.isEmpty()) {
                                        navigator.replace(TranslationScreen())
                                    } else {
                                        navigator.push(
                                            TranslationStructuredImportScreen(
                                                imports.map { it.manga.id }.distinct(),
                                                imports.map { it.chapter.id }.distinct(),
                                            ),
                                        )
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    snackbar.showSnackbar(error.message ?: "Cannot queue selected chapters")
                                } finally {
                                    submitting = false
                                }
                            }
                        },
                    ) { Text(if (submitting) "Queuing selected chapters…" else "Translate / resume ${selected.size}") }
                }
                Text(
                    "Saved page identities determine translation status. Reading history is independent. " +
                        "Local chapters already indexed by Mihon are included.",
                    Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn {
                    if (loading) item { Text("Loading chapter coverage…", Modifier.padding(24.dp)) }
                    if (!loading &&
                        filtered.isEmpty()
                    ) {
                        item { Text("No chapters match these filters.", Modifier.padding(24.dp)) }
                    }
                    items(filtered, key = { it.chapter.id }) { choice ->
                        val state = when {
                            choice.active -> choice.job!!.state
                            choice.coverage.state == TranslationCoverageState.COMPLETED -> TranslationJobState.COMPLETED
                            choice.error -> TranslationJobState.FAILED
                            choice.coverage.saved > 0 -> TranslationJobState.PARTIAL
                            else -> TranslationJobState.QUEUED
                        }
                        Row(Modifier.fillMaxWidth().padding(8.dp)) {
                            Checkbox(
                                choice.chapter.id in selected,
                                enabled = !submitting,
                                onCheckedChange = { checked ->
                                    selected =
                                        if (checked) selected + choice.chapter.id else selected - choice.chapter.id
                                },
                            )
                            Column(
                                Modifier.weight(1f).clickable {
                                    navigator.push(TranslationScreen(choice.manga.id))
                                },
                            ) {
                                Text(choice.manga.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${translationStateSymbol(state)} ${choice.chapter.name}",
                                    color = translationStateColor(
                                        state,
                                        graph.translationPreferences.settings.value.queueColors,
                                    ),
                                )
                                Text(
                                    "${choice.coverage.state.label} · " +
                                        "${choice.coverage.saved}/${choice.coverage.total ?: "?"} saved pages" +
                                        if (choice.manga.source == 0L) " · local" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "AI review: ${choice.reviews.joinToString {
                                        it.name.lowercase().replace('_', ' ')
                                    }.ifEmpty { "not reviewed" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            choice.job?.let { job ->
                                TextButton(onClick = { navigator.push(TranslationLogsScreen(job.id)) }) { Text("Logs") }
                            }
                        }
                    }
                }
            }
        }
    }
}
