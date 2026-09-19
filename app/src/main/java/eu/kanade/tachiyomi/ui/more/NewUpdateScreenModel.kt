package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.updater.AppUpdateManager
import eu.kanade.tachiyomi.data.updater.AppUpdateStage
import eu.kanade.tachiyomi.data.updater.isSameUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import tachiyomi.domain.release.model.Release
import java.io.File

@AssistedInject
class NewUpdateScreenModel(
    @Assisted releaseJson: String,
    private val manager: AppUpdateManager,
) : ViewModel() {
    val release = Json.decodeFromString<Release>(releaseJson)
    private val error = MutableStateFlow<String?>(null)
    private val preparingInstall = MutableStateFlow(false)
    private val installed = MutableStateFlow(release.apk?.let { it.versionCode <= BuildConfig.VERSION_CODE } == true)
    val state = combine(manager.state, error, preparingInstall, installed) {
            download,
            localError,
            preparing,
            isInstalled,
        ->
        val current = download?.takeIf { it.release.isSameUpdate(release) }
        State(
            stage = appUpdateScreenStage(current?.stage, preparing, isInstalled),
            downloadProgress = current?.progress ?: 0,
            error = localError ?: current?.error,
            canCancel = !isInstalled && current?.stage in AppUpdateManager.activeStages,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        State(stage = if (installed.value) Stage.Installed else Stage.Available),
    )

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(releaseJson: String): NewUpdateScreenModel
    }

    fun startDownload() = perform { manager.download(release) }
    fun cancelDownload() = perform { manager.cancelDownload() }
    fun reconcile() = perform {
        // Establish the installed state before clearing metadata, so a restored screen never offers a redownload.
        installed.value = manager.isInstalled(release)
        manager.reconcileInstalledVersion()
    }

    fun prepareInstall(onReady: (File) -> Unit) = perform {
        if (preparingInstall.value) return@perform
        preparingInstall.value = true
        try {
            onReady(manager.verifiedApk(release))
        } finally {
            preparingInstall.value = false
        }
    }

    fun installError(message: String) {
        error.value = message
    }

    private fun perform(action: suspend () -> Unit) {
        viewModelScope.launch {
            error.value = null
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error.value = exception.message ?: "Could not complete the update action. Please try again."
            }
        }
    }

    @Immutable
    data class State(
        val downloadProgress: Int = 0,
        val stage: Stage = Stage.Available,
        val error: String? = null,
        val canCancel: Boolean = false,
    )

    enum class Stage { Available, Queued, Downloading, Verifying, Downloaded, Failed, Installed }
}

internal fun appUpdateScreenStage(
    download: AppUpdateStage?,
    preparing: Boolean,
    installed: Boolean,
): NewUpdateScreenModel.Stage = when {
    installed -> NewUpdateScreenModel.Stage.Installed
    preparing -> NewUpdateScreenModel.Stage.Verifying
    else -> when (download) {
        AppUpdateStage.QUEUED -> NewUpdateScreenModel.Stage.Queued
        AppUpdateStage.DOWNLOADING -> NewUpdateScreenModel.Stage.Downloading
        AppUpdateStage.VERIFYING -> NewUpdateScreenModel.Stage.Verifying
        AppUpdateStage.DOWNLOADED -> NewUpdateScreenModel.Stage.Downloaded
        AppUpdateStage.FAILED -> NewUpdateScreenModel.Stage.Failed
        null -> NewUpdateScreenModel.Stage.Available
    }
}
