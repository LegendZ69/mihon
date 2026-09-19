package mihon.feature.translation.ui

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler

@Composable
internal fun TranslationBackgroundHelp(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val restricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
    val optimized = !context.getSystemService(
        PowerManager::class.java,
    ).isIgnoringBatteryOptimizations(context.packageName)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Background translation") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Background restricted: $restricted\nBattery optimization active: $optimized")
                Text(
                    "Completed pages are saved. If Android interrupts the queue, return to Translator and press Resume. Check the job's waiting reason and related background log events first.",
                )
                Text(
                    "Android may limit long-running work even with an ongoing notification. Connectivity and Mihon’s separate source-download restrictions also affect when jobs can run.",
                )
                Text(
                    "On HyperOS, check this app's background activity and Background autostart settings if work repeatedly stops. Menu names vary by device and ROM; the phone's settings remain under your control.",
                )
                TextButton(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }) { Text("Open Mihon app info") }
                TextButton(onClick = {
                    links.openUri(
                        "https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running",
                    )
                }) {
                    Text("Android background-work documentation")
                }
                TextButton(onClick = {
                    links.openUri("https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1624")
                }) {
                    Text("Official HyperOS autostart guidance")
                }
                Text("Guidance checked 2026-09-05. K90 Pro Max behavior must be verified on the actual handset.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
