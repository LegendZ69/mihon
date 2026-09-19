package mihon.feature.translation.ui

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/** UI gestures are application preferences, never immutable paid-work settings. */
@Inject
@SingleIn(AppScope::class)
class TranslationGesturePreferences(private val store: PreferenceStore) {
    fun assignment(row: TranslationGestureRow, direction: TranslationGestureDirection) = store.getEnum(
        "translator_gesture_${row.name.lowercase()}_${direction.name.lowercase()}",
        row.default(direction),
    )
}

enum class TranslationGestureDirection(val label: String) { START("Start"), END("End") }

enum class TranslationGestureAction(val label: String) {
    STATE_ACTION("Pause, resume, retry or open results"),
    EXPORT("Export options"),
    DELETE("Deletion preview"),
    LOGS("Related logs"),
    ACTIONS("Action menu"),
    MODEL_ACTION("Install, retry or cancel download"),
    MANAGEMENT("Model management"),
    DISABLED("Disabled"),
}

enum class TranslationGestureRow(val label: String, val allowed: Set<TranslationGestureAction>) {
    WORK(
        "Translation work",
        setOf(
            TranslationGestureAction.STATE_ACTION,
            TranslationGestureAction.EXPORT,
            TranslationGestureAction.DELETE,
            TranslationGestureAction.LOGS,
            TranslationGestureAction.ACTIONS,
            TranslationGestureAction.DISABLED,
        ),
    ),
    SAVED(
        "Saved translations",
        setOf(
            TranslationGestureAction.EXPORT,
            TranslationGestureAction.DELETE,
            TranslationGestureAction.LOGS,
            TranslationGestureAction.ACTIONS,
            TranslationGestureAction.DISABLED,
        ),
    ),
    LOG(
        "Logs and attempts",
        setOf(
            TranslationGestureAction.EXPORT,
            TranslationGestureAction.DELETE,
            TranslationGestureAction.ACTIONS,
            TranslationGestureAction.DISABLED,
        ),
    ),
    SUBPROCESS(
        "Subprocesses",
        setOf(TranslationGestureAction.LOGS, TranslationGestureAction.ACTIONS, TranslationGestureAction.DISABLED),
    ),
    MODEL(
        "Model packs",
        setOf(
            TranslationGestureAction.MODEL_ACTION,
            TranslationGestureAction.MANAGEMENT,
            TranslationGestureAction.LOGS,
            TranslationGestureAction.DISABLED,
        ),
    ),
    ;

    fun default(direction: TranslationGestureDirection): TranslationGestureAction = when (this) {
        WORK -> if (direction ==
            TranslationGestureDirection.START
        ) {
            TranslationGestureAction.STATE_ACTION
        } else {
            TranslationGestureAction.DELETE
        }
        SAVED, LOG -> if (direction ==
            TranslationGestureDirection.START
        ) {
            TranslationGestureAction.EXPORT
        } else {
            TranslationGestureAction.DELETE
        }
        SUBPROCESS -> if (direction ==
            TranslationGestureDirection.START
        ) {
            TranslationGestureAction.LOGS
        } else {
            TranslationGestureAction.ACTIONS
        }
        MODEL -> if (direction ==
            TranslationGestureDirection.START
        ) {
            TranslationGestureAction.MODEL_ACTION
        } else {
            TranslationGestureAction.MANAGEMENT
        }
    }
}
