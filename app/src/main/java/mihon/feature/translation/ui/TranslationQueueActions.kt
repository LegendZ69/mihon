package mihon.feature.translation.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.feature.translation.TranslationManager
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.OpenInNew
import mihon.icons.materialsymbols.rounded.ArrowDownward
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.Pause
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.Share
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationMode

internal fun <T> Set<T>.toggle(id: T) = if (id in this) this - id else this + id

internal enum class TranslationWorkAction(val label: String) {
    PAUSE("Pause"),
    RESUME("Resume"),
    RETRY("Retry unfinished pages"),
    RESULTS("Open saved results"),
}

internal fun translationWorkAction(state: TranslationJobState): TranslationWorkAction = when (state) {
    TranslationJobState.COMPLETED -> TranslationWorkAction.RESULTS
    TranslationJobState.PAUSED -> TranslationWorkAction.RESUME
    TranslationJobState.PARTIAL, TranslationJobState.FAILED, TranslationJobState.CANCELLED ->
        TranslationWorkAction.RETRY
    else -> TranslationWorkAction.PAUSE
}

internal fun queueJobActions(
    job: TranslationJob,
    jobs: List<TranslationJob>,
    manager: TranslationManager,
    onAction: (suspend () -> Unit) -> Unit,
    onInspect: (String) -> Unit,
    onLogs: (String) -> Unit,
    onRead: () -> Unit,
    onManage: () -> Unit,
    onDelete: () -> Unit,
    onReplace: () -> Unit,
    onImport: (TranslationJob) -> Unit = {},
): List<TranslationRowAction> {
    val primary = translationWorkAction(job.state)
    val importing = job.isStructuredFiles &&
        job.state != TranslationJobState.COMPLETED
    val actions = mutableListOf(
        TranslationRowAction(
            if (importing) "Import remaining pages" else primary.label,
            when (primary) {
                TranslationWorkAction.PAUSE -> MaterialSymbols.Rounded.Pause
                TranslationWorkAction.RESUME -> MaterialSymbols.RoundedFilled.PlayArrow
                TranslationWorkAction.RETRY -> MaterialSymbols.Rounded.Refresh
                TranslationWorkAction.RESULTS -> MaterialSymbols.AutoMirroredRounded.OpenInNew
            },
        ) {
            onAction {
                // Resolve again after the gesture: progress may have completed while the row was dragged.
                val current = manager.repository.jobs().firstOrNull { it.id == job.id } ?: return@onAction
                if (current.isStructuredFiles &&
                    current.state != TranslationJobState.COMPLETED
                ) {
                    withContext(Dispatchers.Main) { onImport(current) }
                    return@onAction
                }
                when (translationWorkAction(current.state)) {
                    TranslationWorkAction.PAUSE -> manager.pause(current.id)
                    TranslationWorkAction.RESUME -> manager.resume(current.id)
                    TranslationWorkAction.RETRY -> manager.retry(current.id, force = false)
                    TranslationWorkAction.RESULTS -> withContext(Dispatchers.Main) { onInspect(current.id) }
                }
            }
        },
        TranslationRowAction("Read chapter", MaterialSymbols.AutoMirroredRounded.OpenInNew, onRead),
        TranslationRowAction("Inspect OCR and results", MaterialSymbols.Rounded.Info) { onInspect(job.id) },
        TranslationRowAction("Related logs", MaterialSymbols.Rounded.Info) { onLogs(job.id) },
        TranslationRowAction("Export options", MaterialSymbols.Rounded.Share, onManage),
        TranslationRowAction("Delete with preview", MaterialSymbols.Rounded.Delete, onDelete),
        TranslationRowAction("Prioritize chapter", MaterialSymbols.Rounded.ArrowUpward) {
            onAction { manager.prioritize(job.id) }
        },
    )
    for ((offset, label, icon) in listOf(
        Triple(-1, "Move up", MaterialSymbols.Rounded.ArrowUpward),
        Triple(1, "Move down", MaterialSymbols.Rounded.ArrowDownward),
    )) {
        if (jobs.indexOfFirst { it.id == job.id } + offset in
            jobs.indices
        ) {
            actions += TranslationRowAction(label, icon) {
                onAction {
                    val order = manager.repository.jobs().map { it.id }.toMutableList()
                    val index = order.indexOf(job.id)
                    val destination = index + offset
                    if (index >= 0 && destination in order.indices) {
                        order.removeAt(index)
                        order.add(destination, job.id)
                        manager.reorder(order)
                    }
                }
            }
        }
    }
    if (!importing && primary ==
        TranslationWorkAction.RETRY
    ) {
        actions +=
            TranslationRowAction("Retry unfinished with Halving", MaterialSymbols.Rounded.Refresh) {
                onAction { manager.retry(job.id, TranslationMode.HALVING, force = false) }
            }
    }
    if (job.mangaId >= 0 &&
        job.chapterId >= 0
    ) {
        actions +=
            TranslationRowAction("Replace configuration…", MaterialSymbols.Rounded.Settings, onReplace)
    }
    job.replacesJobId?.let { original ->
        actions +=
            TranslationRowAction("Preserved original review history", MaterialSymbols.Rounded.Info) {
                onInspect(original)
            }
    }
    if (primary !=
        TranslationWorkAction.RESULTS
    ) {
        actions +=
            TranslationRowAction("Cancel unfinished work", MaterialSymbols.Rounded.Close) {
                onAction { manager.cancel(job.id) }
            }
    }
    return actions
}
