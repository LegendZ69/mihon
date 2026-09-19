package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import eu.kanade.presentation.manga.components.MarkdownRender
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.more.NewUpdateScreenModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.OpenInNew
import mihon.icons.materialsymbols.rounded.NewReleases
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.InfoScreen

@Composable
fun NewUpdateScreen(
    versionName: String,
    changelogInfo: String,
    stage: NewUpdateScreenModel.Stage,
    downloadProgress: () -> Int,
    onOpenInBrowser: () -> Unit,
    onAcceptUpdate: () -> Unit,
    onRejectUpdate: () -> Unit,
    isPrerelease: Boolean = false,
    error: String? = null,
    installPermissionRequired: Boolean = false,
    canCancelDownload: Boolean = false,
    onCancelDownload: () -> Unit = {},
) {
    InfoScreen(
        icon = MaterialSymbols.Rounded.NewReleases,
        headingText = stringResource(
            if (stage == NewUpdateScreenModel.Stage.Installed) {
                MR.strings.ext_installed
            } else {
                MR.strings.update_check_notification_update_available
            },
        ),
        subtitleText = versionName,
        acceptText = when (stage) {
            NewUpdateScreenModel.Stage.Available -> stringResource(MR.strings.update_check_confirm)
            NewUpdateScreenModel.Stage.Queued -> stringResource(MR.strings.update_download_queued)
            NewUpdateScreenModel.Stage.Verifying -> stringResource(MR.strings.update_download_verifying)
            NewUpdateScreenModel.Stage.Downloading -> stringResource(
                MR.strings.downloading_with_progress,
                downloadProgress(),
            )
            NewUpdateScreenModel.Stage.Downloaded -> stringResource(
                if (installPermissionRequired) MR.strings.update_install_permission else MR.strings.action_install,
            )
            NewUpdateScreenModel.Stage.Failed -> stringResource(MR.strings.action_retry)
            NewUpdateScreenModel.Stage.Installed -> stringResource(MR.strings.action_close)
        },
        onAcceptClick = if (stage == NewUpdateScreenModel.Stage.Installed) onRejectUpdate else onAcceptUpdate,
        canAccept =
        stage in
            setOf(
                NewUpdateScreenModel.Stage.Available,
                NewUpdateScreenModel.Stage.Failed,
                NewUpdateScreenModel.Stage.Downloaded,
                NewUpdateScreenModel.Stage.Installed,
            ),
        rejectText = if (stage ==
            NewUpdateScreenModel.Stage.Installed
        ) {
            null
        } else {
            stringResource(MR.strings.action_not_now)
        },
        onRejectClick = onRejectUpdate,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.padding.large),
        ) {
            if (isPrerelease) {
                Text(
                    text = stringResource(MR.strings.update_prerelease_notice),
                    modifier = Modifier.padding(bottom = MaterialTheme.padding.small),
                )
            }
            if (error != null) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = MaterialTheme.padding.small),
                )
            }
            if (canCancelDownload) {
                TextButton(onClick = onCancelDownload) { Text(stringResource(MR.strings.action_cancel)) }
            }
            MarkdownRender(
                content = changelogInfo,
                flavour = remember { GFMFlavourDescriptor() },
            )

            TextButton(
                onClick = onOpenInBrowser,
                modifier = Modifier.padding(top = MaterialTheme.padding.small),
            ) {
                Text(text = stringResource(MR.strings.update_check_open))
                Spacer(modifier = Modifier.width(MaterialTheme.padding.extraSmall))
                Icon(imageVector = MaterialSymbols.AutoMirroredRounded.OpenInNew, contentDescription = null)
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun NewUpdateScreenPreview() {
    TachiyomiPreviewTheme {
        NewUpdateScreen(
            versionName = "v0.99.9",
            changelogInfo = """
                ## Yay
                Foobar

                ### More info
                - Hello
                - World
            """.trimIndent(),
            stage = NewUpdateScreenModel.Stage.Available,
            downloadProgress = { 0 },
            onOpenInBrowser = {},
            onAcceptUpdate = {},
            onRejectUpdate = {},
        )
    }
}
