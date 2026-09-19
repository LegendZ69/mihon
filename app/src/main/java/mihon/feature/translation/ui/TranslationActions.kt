package mihon.feature.translation.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mihon.app.di.AppGraph
import mihon.app.di.appGraph
import tachiyomi.domain.translation.model.TranslationMode

private val controlActions = Mutex()

@Composable
fun chapterTranslationAction(mangaId: Long, selectedChapterIds: List<Long>): () -> Unit {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            try {
                val destination = withContext(Dispatchers.IO) {
                    translationControlDestination(context.appGraph, mangaId, selectedChapterIds)
                }
                navigator.push(destination)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Cannot add translation jobs", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** Recheck durable state on explicit clicks; completed pages never become paid resubmissions. */
internal suspend fun activateTranslationControl(
    graph: AppGraph,
    mangaId: Long,
    chapterIds: List<Long>,
): Boolean = controlActions.withLock {
    val history = graph.translationRepository.jobs().filter { it.mangaId == mangaId }
    if (chapterIds.isEmpty()) {
        if (history.any {
                it.state in mihon.feature.translation.TranslationManager.activeStates ||
                    it.state in setOf(
                        tachiyomi.domain.translation.model.TranslationJobState.QUEUED,
                        tachiyomi.domain.translation.model.TranslationJobState.WAITING,
                    )
            }
        ) {
            return@withLock true
        }
        val chapters = graph.translationManager.chapterChoices(mangaId).second
        return@withLock chapters.isNotEmpty() && chapters.all { chapter ->
            loadChapterTranslationControl(
                graph.translationRepository,
                chapter.id,
                history.filter { it.chapterId == chapter.id },
            ).complete
        }
    }
    val selected = chapterIds.distinct().map { id ->
        loadChapterTranslationControl(graph.translationRepository, id, history.filter { it.chapterId == id })
    }
    // An active selection opens its queue. Adding more work is a separate explicit picker action.
    if (selected.any { it.active }) return@withLock true
    selected.filterNot { it.complete }.forEach { control ->
        if (control.job != null) {
            graph.translationManager.retry(control.job.id)
        } else {
            graph.translationManager.enqueueSelected(mangaId, listOf(control.chapterId))
        }
    }
    true
}

/** Import routing is decided before any provider activation, including retries of older file jobs. */
internal suspend fun translationControlDestination(
    graph: AppGraph,
    mangaId: Long,
    chapterIds: List<Long>,
): Screen {
    val history = graph.translationRepository.jobs().filter { it.mangaId == mangaId }
    val controls = chapterIds.distinct().map { id ->
        loadChapterTranslationControl(graph.translationRepository, id, history.filter { it.chapterId == id })
    }
    if (controls.any { it.active }) return TranslationScreen(mangaId)
    if (controls.isNotEmpty() && controls.all { it.complete }) return TranslationScreen(mangaId)
    val importing = graph.translationPreferences.effectiveSettings(mangaId).mode == TranslationMode.STRUCTURED_FILES ||
        controls.any { !it.complete && it.job?.isStructuredFiles == true }
    if (importing) return TranslationStructuredImportScreen(listOf(mangaId), chapterIds.distinct())
    return if (activateTranslationControl(graph, mangaId, chapterIds)) {
        TranslationScreen(mangaId)
    } else {
        TranslationChapterPickerScreen(listOf(mangaId))
    }
}
