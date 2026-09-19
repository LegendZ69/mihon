package mihon.feature.translation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState

internal fun TranslationOperationState.jobState(): TranslationJobState = when (this) {
    TranslationOperationState.COMPLETED -> TranslationJobState.COMPLETED
    TranslationOperationState.FAILED -> TranslationJobState.FAILED
    TranslationOperationState.PARTIAL -> TranslationJobState.PARTIAL
    TranslationOperationState.RETRY, TranslationOperationState.WAITING -> TranslationJobState.WAITING
    TranslationOperationState.PAUSED, TranslationOperationState.INTERRUPTED -> TranslationJobState.PAUSED
    TranslationOperationState.CANCELLED -> TranslationJobState.CANCELLED
    TranslationOperationState.QUEUED -> TranslationJobState.QUEUED
    TranslationOperationState.ACTIVE -> TranslationJobState.TRANSLATING
}

internal fun translationStateSymbol(state: TranslationJobState): String = when (state) {
    TranslationJobState.COMPLETED -> "✓"
    TranslationJobState.FAILED -> "✕"
    TranslationJobState.PARTIAL -> "⚠"
    TranslationJobState.WAITING -> "↻"
    TranslationJobState.PAUSED -> "Ⅱ"
    TranslationJobState.CANCELLED -> "⊘"
    TranslationJobState.QUEUED -> "◷"
    else -> "▶"
}

@Composable
internal fun translationStateColor(state: TranslationJobState, overrides: Map<String, Long> = emptyMap()): Color =
    overrides[state.name]?.let { Color(it.toInt()) } ?: when (state) {
        TranslationJobState.COMPLETED ->
            if (MaterialTheme.colorScheme.surface.luminance() <
                0.5f
            ) {
                Color(0xFF62CF82)
            } else {
                Color(0xFF1C783B)
            }
        TranslationJobState.WAITING, TranslationJobState.PARTIAL ->
            if (MaterialTheme.colorScheme.surface.luminance() <
                0.5f
            ) {
                Color(0xFFFFAE53)
            } else {
                Color(0xFFA45C05)
            }
        TranslationJobState.FAILED -> MaterialTheme.colorScheme.error
        TranslationJobState.QUEUED, TranslationJobState.PAUSED, TranslationJobState.CANCELLED ->
            if (MaterialTheme.colorScheme.surface.luminance() <
                0.5f
            ) {
                Color(0xFFAAAAAA)
            } else {
                Color(0xFF6B6B6B)
            }
        else -> MaterialTheme.colorScheme.onSurface
    }

internal fun TranslationOperation.progressLabel(): String = "$completed/${total?.toString() ?: "?"} ${unit.label}"
internal fun TranslationOperation.description(): String = buildString {
    append(stage.label)
    append(" · ").append(state.name.lowercase().replace('_', ' '))
    append(" · ").append(progressLabel())
    attempt?.let { append(" · attempt ").append(it) }
    imageId?.let { append(" · page ").append(it.toIntOrNull()?.plus(1) ?: it) }
    val start = startedAt
    val end = endedAt
    if (start != null && end != null) append(" · ").append(end - start).append(" ms")
}

internal fun tachiyomi.domain.translation.model.TranslationEvent.presentationState(): TranslationJobState {
    val measured = operationState ?: details["operationState"]?.let { value ->
        TranslationOperationState.entries.firstOrNull { it.name == value }
    }
    if (measured != null) return measured.jobState()
    return when {
        level == "ERROR" -> TranslationJobState.FAILED
        level == "WARN" -> TranslationJobState.WAITING
        details["httpStatus"]?.toIntOrNull() in 200..299 -> TranslationJobState.COMPLETED
        else -> TranslationJobState.QUEUED
    }
}
