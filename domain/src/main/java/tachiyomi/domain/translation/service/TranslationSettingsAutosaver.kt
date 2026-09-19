package tachiyomi.domain.translation.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.domain.translation.model.TranslationSettings

data class TranslationSettingsSaveState(val saving: Boolean = false, val error: String? = null)

/** Own one instance per editable settings scope; credentials and inspector drafts do not enter here. */
class TranslationSettingsAutosaver(
    private val scope: CoroutineScope,
    private val persist: suspend (TranslationSettings) -> Unit,
) {
    private val mutableState = MutableStateFlow(TranslationSettingsSaveState())
    val state = mutableState.asStateFlow()
    private var pending: TranslationSettings? = null
    private var scheduled: Job? = null
    private val writes = Mutex()
    private var revision = 0L

    fun update(value: TranslationSettings, immediate: Boolean = false) {
        scheduled?.cancel()
        revision++
        try {
            value.validate()
        } catch (error: IllegalArgumentException) {
            pending = null
            mutableState.value = TranslationSettingsSaveState(error = error.message ?: "Invalid settings")
            return
        }
        pending = value
        mutableState.value = TranslationSettingsSaveState(saving = true)
        scheduled = scope.launch {
            if (!immediate) delay(300)
            persistLatest()
        }
    }

    suspend fun flush() {
        scheduled?.cancel()
        persistLatest()
    }

    /** Reset must follow any in-flight disk commit, and must not recreate a removed series override. */
    suspend fun reset(action: suspend () -> Unit) {
        scheduled?.cancel()
        revision++
        pending = null
        writes.withLock {
            try {
                withContext(NonCancellable) { action() }
                mutableState.value = TranslationSettingsSaveState()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value =
                    TranslationSettingsSaveState(error = error.message ?: "Settings could not be reset")
            }
        }
    }

    private suspend fun persistLatest() = writes.withLock {
        val value = pending ?: return@withLock
        val savingRevision = revision
        try {
            withContext(NonCancellable) { persist(value) }
            if (revision == savingRevision) {
                pending = null
                mutableState.value = TranslationSettingsSaveState()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (revision == savingRevision) {
                mutableState.value =
                    TranslationSettingsSaveState(error = error.message ?: "Settings could not be saved")
            }
        }
    }
}
