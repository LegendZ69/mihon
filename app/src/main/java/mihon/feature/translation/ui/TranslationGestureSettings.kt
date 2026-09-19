package mihon.feature.translation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import mihon.app.di.appGraph

/** Global only: settings change UI behavior without modifying saved jobs or submitting work. */
@Composable
fun TranslationGestureSettings(query: String = "") {
    val context = LocalContext.current
    val preferences = remember(context) { context.appGraph.translationGesturePreferences }
    TranslationGestureRow.entries.forEach { row ->
        TranslationGestureDirection.entries.forEach { direction ->
            val title = "${row.label} · swipe ${direction.label.lowercase()}"
            if (query.isBlank() ||
                ("$title gesture action " + row.allowed.joinToString { it.label }).contains(query, true)
            ) {
                val preference = remember(preferences, row, direction) { preferences.assignment(row, direction) }
                val current by preference.changes().collectAsState(preference.get())
                var choosing by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                TextPreferenceWidget(
                    title = title,
                    subtitle = error ?: current.label,
                    onPreferenceClick = { choosing = true },
                )
                if (choosing) {
                    AlertDialog(
                        onDismissRequest = { choosing = false },
                        title = { Text(title) },
                        text = {
                            Column {
                                Text(
                                    "Start and end mirror with the app language. " +
                                        "Swipe actions are disabled during selection.",
                                )
                                row.allowed.forEach { value ->
                                    TextButton(onClick = {
                                        runCatching { preference.set(value) }.onSuccess {
                                            choosing = false
                                            error = null
                                        }
                                            .onFailure { error = it.message ?: "Gesture could not be saved" }
                                    }) { Text(value.label) }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                runCatching { preference.delete() }.onSuccess {
                                    choosing = false
                                    error = null
                                }
                                    .onFailure { error = it.message ?: "Gesture could not be reset" }
                            }) { Text("Reset") }
                        },
                        dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
                    )
                }
            }
        }
    }
}
