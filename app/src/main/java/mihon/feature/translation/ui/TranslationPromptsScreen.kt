package mihon.feature.translation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.app.di.appGraph
import mihon.feature.translation.provider.TranslationPromptPresentation
import tachiyomi.domain.translation.model.TranslationPromptPair
import tachiyomi.domain.translation.model.TranslationPromptStage
import tachiyomi.domain.translation.model.TranslationPromptTemplates
import tachiyomi.domain.translation.model.TranslationPrompts
import tachiyomi.domain.translation.service.TranslationSettingsAutosaver

/** Prompt editing never instantiates a provider request or schedules translation work. */
class TranslationPromptsScreen(private val mangaId: Long? = null) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val preferences = LocalContext.current.appGraph.translationPreferences
        val clipboard = LocalClipboardManager.current
        fun settings() = mangaId?.let(preferences::effectiveSettings) ?: preferences.settings.value
        var serialized by rememberSaveable(mangaId) { mutableStateOf(Json.encodeToString(settings().prompts)) }
        var stage by rememberSaveable { mutableStateOf(TranslationPromptStage.TRANSLATION) }
        var query by rememberSaveable { mutableStateOf("") }
        var preview by remember { mutableStateOf(false) }
        val draft = remember(serialized) { Json.decodeFromString<TranslationPrompts>(serialized) }
        val saveScope = remember(mangaId) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
        val saver = remember(mangaId) {
            TranslationSettingsAutosaver(saveScope) { value ->
                withContext(Dispatchers.IO) {
                    preferences.update(settings().copy(prompts = value.prompts), mangaId)
                }
            }
        }
        val saveState by saver.state.collectAsState()
        LaunchedEffect(Unit) {
            // Restore a valid pending edit after recreation, without creating an override merely by opening.
            if (draft != settings().prompts) saver.update(settings().copy(prompts = draft))
        }
        DisposableEffect(saver) {
            onDispose {
                saveScope.launch {
                    try {
                        saver.flush()
                    } finally {
                        saveScope.cancel()
                    }
                }
            }
        }
        fun update(pair: TranslationPromptPair, immediate: Boolean = false) {
            val next = draft.withStage(stage, pair)
            serialized = Json.encodeToString(next)
            saver.update(settings().copy(prompts = next), immediate)
        }
        val pair = draft.stage(stage)
        val builtIn = TranslationPromptPresentation.preview(settings().copy(prompts = TranslationPrompts()), stage)
        Scaffold(topBar = { AppBar(title = "Prompts", navigateUp = navigator::pop) }) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(
                        if (mangaId == null) "Global prompt defaults" else "Prompts for this series",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Full replacement of task wording. Required source inputs and output validation " +
                            "remain separate. " +
                            "Changes affect newly scheduled work; editing and previewing make no API calls.",
                        Modifier.padding(horizontal = 16.dp),
                    )
                }
                item {
                    OutlinedTextField(
                        query,
                        { query = it },
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        label = { Text("Search prompts") },
                        singleLine = true,
                    )
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TranslationPromptStage.entries.filter { label(it).contains(query, true) }.forEach {
                            FilterChip(selected = stage == it, onClick = { stage = it }, label = { Text(label(it)) })
                        }
                    }
                    Text(
                        saveState.error ?: if (saveState.saving) "Saving…" else "Saved",
                        Modifier.padding(horizontal = 16.dp),
                        color = if (saveState.error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (saveState.error != null) {
                        TextButton(onClick = { saveScope.launch { saver.flush() } }) {
                            Text("Retry saving valid changes")
                        }
                    }
                }
                item("system") {
                    PromptRoleEditor("System", pair.system, builtIn.system, clipboard::setText) { value, immediate ->
                        update(pair.copy(system = value), immediate)
                    }
                }
                item("user") {
                    PromptRoleEditor("User", pair.user, builtIn.userTask, clipboard::setText) { value, immediate ->
                        update(pair.copy(user = value), immediate)
                    }
                }
                item {
                    TextPreferenceWidget(
                        title = "Effective request preview",
                        subtitle = "Resolved task wording and separate runtime inputs",
                        onPreferenceClick = { preview = true },
                    )
                    SelectionContainer {
                        Text(
                            "Literal variables: " + TranslationPromptTemplates.variables.joinToString { "{{$it}}" } +
                                "\nInserted values are never expanded again. Do not enter credentials in prompts.",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (preview) {
            val resolved = runCatching {
                TranslationPromptPresentation.preview(settings().copy(prompts = draft), stage)
            }
            AlertDialog(
                onDismissRequest = { preview = false },
                title = { Text("Effective ${label(stage).lowercase()} prompt") },
                text = {
                    LazyColumn {
                        resolved.fold(onSuccess = { value ->
                            item { SelectionContainer { Text("System\n${value.system}") } }
                            item { SelectionContainer { Text("User task\n${value.userTask}") } }
                            item { Text("Runtime inputs supplied when work starts\n${value.runtimeInputs}") }
                            item {
                                Text(
                                    "No page or chapter context is selected in this settings preview. " +
                                        "Request logs contain the dispatched snapshot and SHA-256.",
                                )
                            }
                        }, onFailure = { error ->
                            item { Text(error.message ?: "Invalid prompt", color = MaterialTheme.colorScheme.error) }
                        })
                    }
                },
                confirmButton = { TextButton(onClick = { preview = false }) { Text("Close") } },
            )
        }
    }

    private fun label(stage: TranslationPromptStage) = when (stage) {
        TranslationPromptStage.TRANSLATION -> "Translation"
        TranslationPromptStage.QUALITY_REVIEW -> "Quality review"
        TranslationPromptStage.GEOMETRY_CORRECTION -> "Geometry correction"
    }
}

@Composable
private fun PromptRoleEditor(
    role: String,
    value: String?,
    builtIn: String,
    copy: (AnnotatedString) -> Unit,
    update: (String?, Boolean) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$role prompt", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(value == null, { update(null, true) }, label = { Text("Built-in") })
            FilterChip(
                value != null,
                { if (value == null) update(builtIn, true) },
                label = { Text("Custom replacement") },
            )
        }
        if (value != null) {
            val error = runCatching { TranslationPromptTemplates.validate(value) }.exceptionOrNull()?.message
            OutlinedTextField(
                value,
                { update(it, false) },
                Modifier.fillMaxWidth(),
                label = { Text("$role task wording") },
                minLines = 5,
                maxLines = 15,
                isError = error != null,
                supportingText = {
                    Text(error ?: "Replaces the built-in $role wording completely. Empty text is allowed.")
                },
            )
        } else {
            Text("The app supplies its built-in $role task wording.", style = MaterialTheme.typography.bodyMedium)
        }
        FlowRow {
            TextButton(onClick = { copy(AnnotatedString(builtIn)) }) { Text("Copy built-in") }
            TextButton(enabled = value != null, onClick = { update(null, true) }) { Text("Reset to built-in") }
        }
    }
}
