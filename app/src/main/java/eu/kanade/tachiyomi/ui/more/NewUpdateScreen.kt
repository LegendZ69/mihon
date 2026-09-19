package eu.kanade.tachiyomi.ui.more

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.more.NewUpdateScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.rememberRequestPackageInstallsPermissionState
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.launchRequestPackageInstallsPermission
import eu.kanade.tachiyomi.util.system.openInBrowser
import kotlinx.serialization.json.Json
import tachiyomi.domain.release.model.Release

class NewUpdateScreen(private val releaseJson: String) : Screen() {
    constructor(release: Release) : this(Json.encodeToString(release))

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = assistedMetroViewModel<NewUpdateScreenModel, NewUpdateScreenModel.Factory> {
            create(releaseJson)
        }
        val state by viewModel.state.collectAsState()
        val installGranted = rememberRequestPackageInstallsPermissionState()
        LifecycleResumeEffect(viewModel) {
            viewModel.reconcile()
            onPauseOrDispose { }
        }

        NewUpdateScreen(
            versionName = viewModel.release.version,
            stage = state.stage,
            downloadProgress = { state.downloadProgress },
            changelogInfo = viewModel.release.info,
            isPrerelease = viewModel.release.isPrerelease,
            error = state.error,
            installPermissionRequired = state.stage == NewUpdateScreenModel.Stage.Downloaded && !installGranted,
            onOpenInBrowser = { context.openInBrowser(viewModel.release.releaseLink) },
            onAcceptUpdate = {
                when (state.stage) {
                    NewUpdateScreenModel.Stage.Available, NewUpdateScreenModel.Stage.Failed -> viewModel.startDownload()
                    NewUpdateScreenModel.Stage.Downloaded -> {
                        if (!context.packageManager.canRequestPackageInstalls()) {
                            try {
                                context.launchRequestPackageInstallsPermission()
                            } catch (_: Exception) {
                                viewModel.installError("Open Android Settings and allow Mihon to install unknown apps.")
                            }
                        } else {
                            viewModel.prepareInstall { file ->
                                try {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(file.getUriCompat(context), ExtensionInstaller.APK_MIME)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        },
                                    )
                                } catch (_: ActivityNotFoundException) {
                                    viewModel.installError(
                                        "No Android package installer is available. The verified update is saved.",
                                    )
                                } catch (_: SecurityException) {
                                    viewModel.installError(
                                        "Android blocked installation. Check install permissions and try again.",
                                    )
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            },
            onRejectUpdate = navigator::pop,
            onCancelDownload = viewModel::cancelDownload,
            canCancelDownload = state.canCancel,
        )
    }
}
