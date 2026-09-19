package mihon.feature.translation

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import mihon.feature.translation.ocr.PaddleModelState

/** One process-owned publisher keeps chapter and model progress under the same update/card budgets. */
@Inject
@SingleIn(AppScope::class)
class TranslationNotificationCenter(context: Context, preferences: TranslationPreferences) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val publisher = TranslationNotifications(context)
    private val chapters = MutableStateFlow<TranslationNotificationSnapshot?>(null)
    private val workerRunning = MutableStateFlow(false)
    private val models = MutableStateFlow<List<PaddleModelState>>(emptyList())
    private var modelObserver: Job? = null

    private val publishing = scope.launch {
        val snapshots = combine(chapters, models, preferences.settings, workerRunning) {
                chapter,
                packs,
                settings,
                running,
            ->
            (chapter ?: TranslationNotificationSnapshot(emptyList(), emptyList(), settings.notificationChapterCards))
                .copy(cardLimit = settings.notificationChapterCards, models = packs) to !running
        }
        snapshots.notificationUpdates { (snapshot, standalone) ->
            Triple(snapshot.stateKey, standalone, snapshot.cardLimit)
        }.collect { (snapshot, standalone) ->
            // Permission/channel policy may change between checking and notifying.
            try {
                publisher.publish(snapshot, standalone)
            } catch (_: SecurityException) { }
        }
    }

    @Synchronized
    fun observeModels(states: StateFlow<List<PaddleModelState>>) {
        modelObserver?.cancel()
        modelObserver = scope.launch { states.collect { models.value = it } }
    }
    internal fun chapterProgress(
        snapshot: TranslationNotificationSnapshot,
    ) {
        workerRunning.value = true
        chapters.value =
            snapshot
    }
    internal fun chapterWorkerStopped(
        snapshot: TranslationNotificationSnapshot,
    ) {
        chapters.value = snapshot
        workerRunning.value =
            false
    }
    internal fun withModels(snapshot: TranslationNotificationSnapshot) = snapshot.copy(models = models.value)
}
