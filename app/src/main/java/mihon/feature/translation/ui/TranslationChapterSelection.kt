package mihon.feature.translation.ui

import mihon.feature.translation.TranslationManager
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState

internal val chapterActionActiveStates = TranslationManager.activeStates +
    setOf(TranslationJobState.QUEUED, TranslationJobState.WAITING)

/** The job opened or resumed by a chapter-level action, independently of aggregate saved coverage. */
fun selectChapterTranslationJob(history: List<TranslationJob>): TranslationJob? {
    val ordered = history.sortedWith(
        compareByDescending<TranslationJob> { it.createdAt }
            .thenByDescending { it.updatedAt }.thenByDescending { it.id },
    )
    return ordered.firstOrNull { it.state in chapterActionActiveStates } ?: ordered.firstOrNull()
}
