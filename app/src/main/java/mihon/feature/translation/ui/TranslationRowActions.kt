package mihon.feature.translation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.DropdownMenu
import me.saket.swipe.SwipeAction
import me.saket.swipe.SwipeableActionsBox
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Cancel
import mihon.icons.materialsymbols.rounded.Done
import mihon.icons.materialsymbols.rounded.Error
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Pause
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Schedule
import mihon.icons.materialsymbols.rounded.Warning
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.presentation.core.util.selectedBackground

class TranslationRowAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean,
    val onAction: () -> Unit,
) {
    constructor(label: String, icon: ImageVector, onAction: () -> Unit) : this(label, icon, true, onAction)
}

internal class TranslationSelectionUi(val count: Int, val clear: () -> Unit)

/** Uses the same logical directions and completed-swipe threshold as Mihon chapter rows. */
@Composable
fun TranslationActionRow(
    selected: Boolean,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    startAction: TranslationRowAction?,
    endAction: TranslationRowAction?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val background = MaterialTheme.colorScheme.primaryContainer
    val swipeEnabled by rememberUpdatedState(!selectionActive)
    val haptic = LocalHapticFeedback.current
    fun swipe(action: TranslationRowAction?) = action?.takeIf { it.enabled && !selectionActive }?.let {
        SwipeAction(
            icon = { Icon(it.icon, it.label, Modifier.padding(16.dp), tint = contentColorFor(background)) },
            background = background,
            onSwipe = { if (swipeEnabled) it.onAction() },
        )
    }
    SwipeableActionsBox(
        modifier = Modifier.clipToBounds(),
        startActions = listOfNotNull(swipe(startAction)),
        endActions = listOfNotNull(swipe(endAction)),
        swipeThreshold = 56.dp,
        backgroundUntilSwipeThreshold = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            modifier.fillMaxWidth().selectedBackground(selected)
                .combinedClickable(
                    onClick = onClick,
                    role = Role.Button,
                    onLongClickLabel = if (selected) "Deselect" else "Select",
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                )
                .semantics {
                    this.selected = selected
                    customActions = if (selectionActive) {
                        emptyList()
                    } else {
                        listOfNotNull(startAction, endAction).filter { it.enabled }
                            .distinctBy { it.label }.map { action ->
                                CustomAccessibilityAction(action.label) {
                                    action.onAction()
                                    true
                                }
                            }
                    }
                },
        ) { content() }
    }
}

@Composable
fun TranslationRowActionMenu(actions: List<TranslationRowAction>, label: String = "Actions") {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(MaterialSymbols.Rounded.MoreVert, label) }
        TranslationActionMenu(expanded, { expanded = false }, actions)
    }
}

@Composable
fun TranslationActionMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<TranslationRowAction>) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                enabled = action.enabled,
                leadingIcon = { Icon(action.icon, null) },
                onClick = {
                    onDismiss()
                    action.onAction()
                },
            )
        }
    }
}

@Composable
fun TranslationSelectionBar(
    count: Int,
    onClear: () -> Unit,
    actions: List<TranslationRowAction>,
    showCounter: Boolean = true,
) {
    if (count == 0) return
    BackHandler(onBack = onClear)
    Surface(
        shape = MaterialTheme.shapes.large.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            if (showCounter) AppBar(title = null, actionModeCounter = count, onCancelActionMode = onClear)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
                actions.take(4).forEach { action ->
                    IconButton(onClick = action.onAction, enabled = action.enabled, modifier = Modifier.weight(1f)) {
                        Icon(action.icon, action.label)
                    }
                }
                if (actions.size > 4) TranslationRowActionMenu(actions.drop(4), "More selection actions")
            }
        }
    }
}

@Composable
fun configuredTranslationSwipe(
    row: TranslationGestureRow,
    direction: TranslationGestureDirection,
    actions: Map<TranslationGestureAction, TranslationRowAction>,
): TranslationRowAction? {
    val context = LocalContext.current
    val preferences = remember(context) { context.appGraph.translationGesturePreferences }
    val preference = remember(preferences, row, direction) { preferences.assignment(row, direction) }
    val assignment by preference.changes().collectAsState(preference.get())
    return actions[assignment.takeIf { it in row.allowed } ?: row.default(direction)]
}

@Composable
fun TranslationStatusIcon(
    state: tachiyomi.domain.translation.model.TranslationJobState,
    colors: Map<String, Long> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = when (state) {
            tachiyomi.domain.translation.model.TranslationJobState.COMPLETED -> MaterialSymbols.Rounded.Done
            tachiyomi.domain.translation.model.TranslationJobState.FAILED -> MaterialSymbols.Rounded.Error
            tachiyomi.domain.translation.model.TranslationJobState.PARTIAL -> MaterialSymbols.Rounded.Warning
            tachiyomi.domain.translation.model.TranslationJobState.WAITING -> MaterialSymbols.Rounded.Refresh
            tachiyomi.domain.translation.model.TranslationJobState.PAUSED -> MaterialSymbols.Rounded.Pause
            tachiyomi.domain.translation.model.TranslationJobState.CANCELLED -> MaterialSymbols.Rounded.Cancel
            tachiyomi.domain.translation.model.TranslationJobState.QUEUED -> MaterialSymbols.Rounded.Schedule
            else -> MaterialSymbols.RoundedFilled.PlayArrow
        },
        contentDescription = state.name.lowercase().replace('_', ' '),
        tint = translationStateColor(state, colors),
        modifier = modifier,
    )
}
