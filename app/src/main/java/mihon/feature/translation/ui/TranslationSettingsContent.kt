package mihon.feature.translation.ui

import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import mihon.app.di.appGraph
import mihon.feature.translation.provider.CredentialInfo
import mihon.feature.translation.provider.OfficialProviderPricing
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OpenAiDialect
import tachiyomi.domain.translation.model.QualityReviewCoverage
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.VertexAuthMode
import tachiyomi.domain.translation.service.TranslationSettingsAutosaver
import java.io.File
import java.util.UUID

@Composable
fun TranslationSettingsContent(
    mangaId: Long? = null,
    onModelSelectionChanged: ((Int, () -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val graph = remember(context) { context.appGraph }
    val preferences = graph.translationPreferences
    val provider = graph.translationProvider
    val vault = graph.translationCredentialVault
    val models = graph.paddleModelManager
    val modelStates by models.states.collectAsState()
    val revision by preferences.revision.collectAsState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var draft by remember(mangaId) {
        mutableStateOf(mangaId?.let(preferences::effectiveSettings) ?: preferences.settings.value)
    }
    val saveScope = remember(mangaId) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val autosaver = remember(mangaId) {
        TranslationSettingsAutosaver(saveScope) { value ->
            withContext(Dispatchers.IO) { preferences.update(value, mangaId) }
        }
    }
    val saveState by autosaver.state.collectAsState()
    var lastSubmitted by remember(mangaId) { mutableStateOf(draft) }
    var immediateEdit = false
    DisposableEffect(autosaver) {
        onDispose {
            saveScope.launch {
                try {
                    autosaver.flush()
                } finally {
                    saveScope.cancel()
                }
            }
        }
    }
    LaunchedEffect(draft) {
        if (draft != lastSubmitted) {
            lastSubmitted = draft
            autosaver.update(draft)
        }
    }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf("Provider", "Translation")) }
    var errors by remember { mutableStateOf(emptySet<String>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var credentials by remember { mutableStateOf(emptyList<CredentialInfo>()) }
    var credentialText by remember { mutableStateOf("") }
    var showCredential by remember { mutableStateOf(false) }
    var showJson by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }

    fun report(error: Throwable) {
        if (error is CancellationException) throw error
        message = error.message ?: "The operation could not be completed."
    }

    fun importCredential(text: String) {
        val selected = draft.provider
        scope.launch {
            busy = true
            try {
                require(!selected.credentialId.startsWith("billing-")) {
                    "Billing administration credentials are managed only in Cloud accounting."
                }
                vault.import(selected.credentialId, text, selected.kind, vertexAuthMode = selected.vertexAuthMode)
                credentialText = ""
                showCredential = false
                credentials = vault.list().filterNot { it.reference.startsWith("billing-") }
                message = "Credential saved in protected device storage."
            } catch (error: Exception) {
                report(error)
            } finally {
                busy = false
            }
        }
    }

    val credentialPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val bytes = input.readBytesBounded(128 * 1024)
                            bytes.toString(Charsets.UTF_8)
                        } ?: error("Cannot open credential document.")
                    }
                    importCredential(text)
                } catch (error: Exception) {
                    report(error)
                }
            }
        }
    }
    val settingsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) {
                        val text = context.contentResolver.openInputStream(uri)?.use {
                            it.readBytesBounded(2 * 1024 * 1024).toString(Charsets.UTF_8)
                        } ?: error("Cannot open settings document.")
                        preferences.json.decodeFromString<TranslationSettings>(text).also { it.validate() }
                    }
                    draft = imported
                    errors = emptySet()
                    message =
                        "Settings loaded for review. Changes save automatically. Credentials remain in protected storage."
                } catch (error: Exception) {
                    report(error)
                }
            }
        }
    }
    val settingsExporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        val serialized = serializeTranslationSettingsExport(draft, preferences.json)
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                                it.write(serialized)
                            } ?: error("Cannot write settings document.")
                        }
                        message = "Settings exported. Credential values and custom header values are excluded."
                    } catch (error: Exception) {
                        report(error)
                    }
                }
            }
        }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val font = withContext(Dispatchers.IO) {
                        val folder = File(context.filesDir, "translation/fonts").apply { mkdirs() }
                        val file = File(folder, "${UUID.randomUUID()}.font")
                        try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                file.outputStream().use { it.write(input.readBytesBounded(32 * 1024 * 1024)) }
                            } ?: error("Cannot open font.")
                            Typeface.createFromFile(file)
                            file
                        } catch (error: Exception) {
                            file.delete()
                            throw error
                        }
                    }
                    draft = draft.copy(style = draft.style.copy(fontPath = font.absolutePath))
                    message = "Font imported. Saving appearance."
                } catch (error: Exception) {
                    report(error)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { credentials = vault.list().filterNot { it.reference.startsWith("billing-") } }.onFailure(::report)
        runCatching { models.refresh() }.onFailure(::report)
    }
    val saved =
        remember(revision, mangaId) { mangaId?.let(preferences::effectiveSettings) ?: preferences.settings.value }
    LaunchedEffect(saved) {
        // A child settings screen can update this scope while its parent remains in the back stack.
        if (draft == lastSubmitted && !saveState.saving && saveState.error == null && draft != saved) {
            draft = saved
            lastSubmitted = saved
        }
    }
    val fields = translationSettingsFields(draft, {
        draft = it
        lastSubmitted = it
        autosaver.update(it, immediate = immediateEdit)
    }, preferences.json)
    val groups = fields.groupBy { it.group }
    val caps = provider.capabilities(draft.provider)

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item("scope") {
            Text(
                if (mangaId == null) "Translation defaults" else "Settings for this series",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                if (mangaId ==
                    null
                ) {
                    "New translation jobs use these settings."
                } else {
                    "Changes save automatically as a series override. Reset returns to global defaults."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item("search") {
            OutlinedTextField(query, {
                query = it
            }, Modifier.fillMaxWidth(), label = { Text("Search settings") }, singleLine = true)
        }
        if (query.isBlank() ||
            "prompts system user translation quality review geometry correction".contains(query, true)
        ) {
            item("prompts") {
                TextPreferenceWidget(
                    title = "Prompts",
                    subtitle = "System and user wording for translation, quality review and geometry correction",
                    onPreferenceClick = {
                        scope.launch {
                            autosaver.flush()
                            navigator.push(TranslationPromptsScreen(mangaId))
                        }
                    },
                )
            }
        }
        item("save") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        saveState.error != null -> saveState.error!!
                        saveState.saving || draft != saved -> "Saving…"
                        else -> "Saved"
                    },
                    color = if (saveState.error !=
                        null
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (saveState.error != null) {
                    TextButton(onClick = { saveScope.launch { autosaver.flush() } }) { Text("Retry save") }
                }
                TextButton(onClick = {
                    saveScope.launch {
                        autosaver.reset { withContext(Dispatchers.IO) { preferences.reset(mangaId) } }
                        val reset = mangaId?.let(preferences::effectiveSettings) ?: preferences.settings.value
                        lastSubmitted = reset
                        draft = reset
                        errors = emptySet()
                    }
                }) { Text("Reset") }
            }
            if (errors.isNotEmpty()) Text("Correct: ${errors.joinToString()}", color = MaterialTheme.colorScheme.error)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (query.isBlank() || "credential key service account authentication".contains(query, true)) {
            item("credentials") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Credentials", style = MaterialTheme.typography.titleMedium)
                    val selected = credentials.firstOrNull { it.reference == draft.provider.credentialId }
                    Text(
                        selected?.let {
                            "${it.label} · ${it.kind.name.lowercase().replace(
                                '_',
                                ' ',
                            )}${it.accountEmail?.let { email ->
                                "\n$email"
                            }.orEmpty()}"
                        }
                            ?: "No credential imported for ${draft.provider.credentialId}.",
                    )
                    OutlinedTextField(
                        credentialText,
                        { credentialText = it },
                        Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                if (draft.provider.kind == TranslationProviderKind.VERTEX_SERVICE_ACCOUNT &&
                                    draft.provider.vertexAuthMode == VertexAuthMode.SERVICE_ACCOUNT_JSON
                                ) {
                                    "Paste service-account JSON"
                                } else {
                                    "Paste API key"
                                },
                            )
                        },
                        visualTransformation = if (showCredential) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        maxLines = 4,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            showCredential = !showCredential
                        }) { Text(if (showCredential) "Hide" else "Show") }
                        OutlinedButton(enabled = !busy && credentialText.isNotBlank(), onClick = {
                            importCredential(credentialText)
                        }) { Text("Import pasted") }
                        OutlinedButton(enabled = !busy, onClick = {
                            credentialPicker.launch(
                                arrayOf("application/json", "text/plain", "application/octet-stream"),
                            )
                        }) { Text("Import file") }
                        TextButton(
                            enabled = !busy && selected != null,
                            onClick = {
                                scope.launch {
                                    try {
                                        require(!draft.provider.credentialId.startsWith("billing-")) {
                                            "Manage billing credentials in Cloud accounting."
                                        }
                                        vault.delete(draft.provider.credentialId)
                                        credentials = vault.list().filterNot { it.reference.startsWith("billing-") }
                                        message =
                                            "Credential deleted."
                                    } catch (error: Exception) {
                                        report(error)
                                    }
                                }
                            },
                        ) { Text("Delete") }
                    }
                    OutlinedButton(
                        enabled = !busy && selected != null,
                        onClick = {
                            val settings = draft.provider
                            scope.launch {
                                busy = true
                                try {
                                    message = provider.testConnection(settings)
                                } catch (
                                    error: Exception,
                                ) {
                                    report(error)
                                } finally {
                                    busy =
                                        false
                                }
                            }
                        },
                    ) { Text(if (busy) "Working…" else "Test connection") }
                }
            }
        }
        if (query.isBlank() || "groq paddle gpt oss compatible".contains(query, true)) {
            item("groq-preset") {
                OutlinedButton(onClick = {
                    draft = draft.copy(
                        provider = draft.provider.copy(
                            kind = TranslationProviderKind.OPENAI,
                            baseUrl = "https://api.groq.com/openai/v1",
                            model = "openai/gpt-oss-120b",
                            dialect = OpenAiDialect.CHAT_COMPLETIONS,
                            priorityPaygo = false,
                            provisionedThroughput = false,
                            advancedJson = "{}",
                        ),
                        ocr = draft.ocr.copy(pipeline = OcrPipeline.PADDLE),
                        qualityReview = draft.qualityReview.copy(coverage = QualityReviewCoverage.TEXT_ONLY),
                    )
                    lastSubmitted = draft
                    autosaver.update(draft, immediate = true)
                }) { Text("Use PaddleOCR → Groq GPT-OSS 120B") }
                Text(
                    "Text-only translation and review. Existing queued jobs keep their saved settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (mangaId == null && (
                query.isBlank() ||
                    (
                        "gesture swipe action " + TranslationGestureRow.entries.joinToString { row ->
                            row.label + " " + row.allowed.joinToString { it.label }
                        }
                        ).contains(query, ignoreCase = true)
                )
        ) {
            item("translation-gestures") {
                HorizontalDivider()
                Text("Swipe actions", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Global app controls. Long-press selects rows; swipes are disabled during selection.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TranslationGestureSettings(query)
            }
        }
        groups.forEach { (name, group) ->
            val matches = group.filter {
                query.isBlank() ||
                    "${it.group} ${it.label} ${it.hint}".contains(query, ignoreCase = true)
            }
            if (matches.isNotEmpty()) {
                item("group-$name") {
                    HorizontalDivider()
                    TextButton(onClick = { expanded = if (name in expanded) expanded - name else expanded + name }) {
                        Text(
                            "${if (name in expanded || query.isNotBlank()) "▾" else "▸"} $name",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                if (name in expanded || query.isNotBlank()) {
                    matches.forEach { field ->
                        item("field-${field.group}-${field.label}") {
                            TranslationSettingControl(
                                field.copy(update = { value ->
                                    immediateEdit = field.kind != SettingsFieldKind.TEXT
                                    field.update(value)
                                }),
                            ) { invalid ->
                                errors = if (invalid) errors + field.label else errors - field.label
                            }
                        }
                    }
                }
            }
        }
        if (query.isBlank() || "model paddle download pack offline ocr".contains(query, true)) {
            item("packs") {
                HorizontalDivider()
                Text("PaddleOCR model packs", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Official model artifacts are downloaded once and verified before use. Korean uses its " +
                        "separate recognition pack.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item("model-pack-actions") {
                TranslationModelPacks(modelStates, ::report, onModelSelectionChanged)
            }
        }
        if (query.isBlank() || "font style import".contains(query, true)) {
            item("font-import") {
                OutlinedButton(onClick = {
                    fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/octet-stream"))
                }) { Text("Import overlay font") }
                TextButton(onClick = {
                    draft =
                        draft.copy(style = draft.style.copy(fontPath = null, fontFamily = "system"))
                }) { Text("Use system font") }
            }
        }
        item("capabilities") {
            HorizontalDivider()
            Text("Effective provider limits", style = MaterialTheme.typography.titleMedium)
            Text(
                "${caps.model}\n${providerImageInputLimitLabel(caps)}" +
                    "\nInput tokens: ${caps.maxInputTokens.takeIf { it > 0 } ?: "unverified"}" +
                    "\nOutput tokens: ${caps.maxOutputTokens.takeIf { it > 0 } ?: "unverified"}" +
                    "\nRequest byte budget: ${caps.maxRequestBytes ?: "none configured"}" +
                    "\nVerified: ${caps.verifiedAt.ifBlank { "custom provider" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            caps.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (caps.sourceUrl.isNotBlank()) {
                TextButton(onClick = {
                    uriHandler.openUri(caps.sourceUrl)
                }) { Text("Official model documentation") }
            }
            TextButton(onClick = {
                uriHandler.openUri(
                    "https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/guides/gemini-3-8-flash",
                )
            }) { Text("Gemini 3.8 parameter rules") }
            TextButton(onClick = {
                uriHandler.openUri("https://www.paddleocr.ai/latest/en/version3.x/pipeline_usage/OCR.html")
            }) { Text("PaddleOCR documentation") }
            val price = OfficialProviderPricing.price(draft.provider)
            if (price != null) {
                val effectiveThrough = price.effectiveThrough?.let { " through $it" }.orEmpty()
                Text(
                    "Estimated USD / million tokens: input ${price.inputPerMillionUsd}, " +
                        "cached input ${price.cachedInputPerMillionUsd}, " +
                        "output + reasoning ${price.outputPerMillionUsd}. " +
                        "Verified ${price.verifiedAt}; effective ${price.effectiveFrom}$effectiveThrough.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (price.promotionalCredit) {
                        "Rates include 50% promotional credits back. Account discounts, other credits and tax " +
                            "are excluded. Actual response usage and estimates appear in logs."
                    } else {
                        "List prices exclude account discounts, credits and tax. Actual response usage and " +
                            "estimates appear in logs."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "Cost estimate unavailable for this model or capacity setting. Token usage remains " +
                        "recorded when provided.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            providerPricingLink(price)?.let { target ->
                TextButton(onClick = {
                    uriHandler.openUri(target)
                }) { Text("Official pricing and promotional terms") }
            }
        }
        item("import-export") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    settingsPicker.launch(arrayOf("application/json", "text/plain"))
                }) { Text("Import settings") }
                OutlinedButton(onClick = {
                    settingsExporter.launch("mihon-translation-settings.json")
                }) { Text("Export settings") }
                TextButton(onClick = {
                    jsonText =
                        preferences.json.encodeToString(
                            draft.copy(provider = draft.provider.copy(extraHeaders = emptyMap())),
                        )
                    showJson =
                        true
                }) { Text("Edit settings JSON") }
            }
        }
    }
    if (showJson) {
        AlertDialog(
            onDismissRequest = { showJson = false },
            title = { Text("Settings JSON") },
            text = {
                OutlinedTextField(jsonText, {
                    jsonText = it
                }, Modifier.fillMaxWidth(), maxLines = 18, label = { Text("Configuration without credentials") })
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        try {
                            draft =
                                preferences.json.decodeFromString<TranslationSettings>(jsonText).also { it.validate() }
                            errors = emptySet()
                            showJson = false
                            message = "JSON loaded. Saving valid settings."
                        } catch (error: Exception) {
                            report(error)
                        }
                    },
                ) { Text("Load") }
            },
            dismissButton = { TextButton(onClick = { showJson = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TranslationSettingControl(field: TranslationSettingsField, onInvalid: (Boolean) -> Unit) {
    when (field.kind) {
        SettingsFieldKind.TOGGLE -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(field.label)
                if (field.hint.isNotBlank()) Text(field.hint, style = MaterialTheme.typography.bodySmall)
            }
            Switch(field.value.toBoolean(), { field.update(it.toString()) }, enabled = field.enabled)
        }
        SettingsFieldKind.CHOICE -> {
            var opened by remember { mutableStateOf(false) }
            Column {
                Text(field.label)
                OutlinedButton(onClick = {
                    opened = true
                }, enabled = field.enabled) { Text(field.value.lowercase().replace('_', ' ')) }
                DropdownMenu(expanded = opened, onDismissRequest = { opened = false }) {
                    field.choices.forEach { choice ->
                        DropdownMenuItem(text = { Text(choice.lowercase().replace('_', ' ')) }, onClick = {
                            field.update(choice)
                            opened =
                                false
                        })
                    }
                }
                if (field.hint.isNotBlank()) Text(field.hint, style = MaterialTheme.typography.bodySmall)
            }
        }
        SettingsFieldKind.TEXT -> {
            var text by rememberSaveable(field.label, field.value) { mutableStateOf(field.value) }
            var error by rememberSaveable(field.label, field.value) { mutableStateOf<String?>(null) }
            OutlinedTextField(
                text,
                {
                    text = it
                    try {
                        field.update(it)
                        error = null
                        onInvalid(false)
                    } catch (_: Exception) {
                        error = field.validationHint
                        onInvalid(true)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(field.label) },
                supportingText = { Text(error ?: field.hint) },
                isError = error != null,
                enabled = field.enabled,
                singleLine = !field.multiline,
                maxLines = if (field.multiline) 6 else 1,
            )
        }
    }
}

private fun java.io.InputStream.readBytesBounded(maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val size = read(buffer)
        if (size < 0) break
        require(output.size().toLong() + size <= maxBytes) { "The selected file is too large." }
        output.write(buffer, 0, size)
    }
    return output.toByteArray()
}
