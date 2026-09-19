package mihon.feature.translation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.rounded.ArrowDownward
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.ContentCopy
import mihon.icons.materialsymbols.rounded.EditNote
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.domain.translation.model.QualityReviewCheckpoint
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.RegionLayoutDiagnostic
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import java.util.UUID

class TranslationInspectorScreen(private val jobId: String) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val graph = LocalContext.current.appGraph
        val manager = graph.translationManager
        val preferenceRevision by graph.translationPreferences.revision.collectAsState()
        val allJobs by manager.jobs.collectAsState(emptyList())
        val reviews by remember(jobId) { graph.translationRepository.observeReviews(jobId) }.collectAsState(emptyList())
        val job = allJobs.firstOrNull { it.id == jobId }
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val clipboard = LocalClipboardManager.current
        val json = remember {
            Json {
                prettyPrint = true
                encodeDefaults = true
            }
        }
        var images by remember { mutableStateOf(emptyList<TranslationImage>()) }
        var results by remember { mutableStateOf(emptyList<TranslationPageResult>()) }
        var selectedImage by remember { mutableStateOf<String?>(null) }
        var showImagePicker by remember { mutableStateOf(false) }
        var selectedRegion by remember(selectedImage) { mutableStateOf<String?>(null) }
        var query by remember { mutableStateOf("") }
        var boxes by remember { mutableStateOf(true) }
        var source by remember { mutableStateOf(true) }
        var masks by remember { mutableStateOf(true) }
        var translations by remember { mutableStateOf(true) }
        var zoom by remember(selectedImage) { mutableStateOf(1f) }
        var draft by remember { mutableStateOf<TranslationPageResult?>(null) }
        var dirty by remember { mutableStateOf(false) }
        var edit by remember { mutableStateOf<TextRegion?>(null) }
        var split by remember { mutableStateOf<TextRegion?>(null) }
        var raw by remember { mutableStateOf<String?>(null) }
        var geometryError by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }
        var confirmDiscard by remember { mutableStateOf(false) }
        LaunchedEffect(job?.chapterId) {
            if (job != null) {
                graph.translationRepository.observeResults(job.chapterId).collect {
                    images = graph.translationRepository.images(jobId)
                    results = graph.translationRepository.results(jobId)
                    if (selectedImage == null) selectedImage = results.firstOrNull()?.imageId
                }
            }
        }
        val savedPage = results.firstOrNull { it.imageId == selectedImage }
        val image = images.firstOrNull { it.id == selectedImage }
        LaunchedEffect(savedPage, selectedImage) {
            if (!dirty || draft?.imageId != selectedImage) draft = savedPage
        }
        val page = draft?.takeIf { it.imageId == selectedImage } ?: savedPage
        val stale = dirty && page?.revision != savedPage?.revision
        val review = latestQualityReviews(reviews).firstOrNull { it.imageId == selectedImage }
        val effectiveSettings = remember(job?.mangaId, preferenceRevision) {
            job?.let { graph.translationPreferences.effectiveSettings(it.mangaId) }
        }
        val effectiveStyle = effectiveSettings?.style
        val contentPolicy = effectiveSettings?.contentPolicy ?: TranslationContentPolicy()
        var layoutDiagnostics by remember(selectedImage) { mutableStateOf(emptyList<RegionLayoutDiagnostic>()) }
        var presentationOutdated by remember(review?.id) { mutableStateOf(false) }
        LaunchedEffect(review?.renderEvidence, effectiveStyle, contentPolicy, savedPage) {
            val evidence = review?.renderEvidence
            presentationOutdated = if (evidence != null && effectiveStyle != null) {
                val beforeOverrides = review.beforeResult.regions.mapNotNull { region ->
                    region.style?.let { region.id to it }
                }.toMap()
                val currentOverrides = (savedPage ?: review.beforeResult).regions.mapNotNull { region ->
                    region.style?.let { region.id to it }
                }.toMap()
                beforeOverrides != currentOverrides || runCatching {
                    manager.reviewPresentationFingerprint(review.beforeResult, effectiveStyle, contentPolicy)
                }.getOrNull() != evidence.presentationFingerprint
            } else {
                false
            }
        }
        val canUndo = reviews.any {
            it.imageId == selectedImage && it.state == QualityReviewState.REPAIRED &&
                it.repairedRevision == savedPage?.revision
        }
        val action: (suspend () -> Unit) -> Unit = { block ->
            scope.launch {
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    snackbar.showSnackbar(error.message ?: "Inspector action failed")
                }
            }
        }
        fun change(block: () -> TranslationPageResult) {
            if (saving) return
            try {
                draft = block()
                dirty = true
                geometryError = null
            } catch (error: IllegalArgumentException) {
                geometryError = error.message ?: "Keep a convex polygon inside the image"
            }
        }
        BackHandler(enabled = dirty || saving) { confirmDiscard = true }
        Scaffold(
            topBar = {
                AppBar(title = "OCR inspector", subtitle = job?.chapterTitle, navigateUp = {
                    if (dirty || saving) confirmDiscard = true else navigator.pop()
                }, actions = {
                    page?.let { current ->
                        TranslationRowActionMenu(
                            listOf(
                                TranslationRowAction("Copy translated text", MaterialSymbols.Rounded.ContentCopy) {
                                    clipboard.setText(
                                        AnnotatedString(
                                            current.regions.filter {
                                                it.included && !contentPolicy.excludes(it)
                                            }.sortedBy { it.readingOrder }.joinToString("\n") { it.translatedText },
                                        ),
                                    )
                                },
                                TranslationRowAction("Full OCR details", MaterialSymbols.Rounded.Info) {
                                    raw =
                                        json.encodeToString(current)
                                },
                                TranslationRowAction(
                                    "Related page logs",
                                    MaterialSymbols.Rounded.Info,
                                    !dirty && !saving,
                                ) {
                                    navigator.push(TranslationLogsScreen(jobId, imageId = current.imageId))
                                },
                            ),
                            "Page actions",
                        )
                    }
                })
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                job?.mangaTitle?.let { Text(it, Modifier.padding(horizontal = 12.dp)) }
                page?.imageId?.let {
                    Text(
                        "Image ID: $it",
                        Modifier.padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val orderedPages = results.sortedBy { result -> images.firstOrNull { it.id == result.imageId }?.index }
                val pageIndex = orderedPages.indexOfFirst { it.imageId == selectedImage }
                val canSwitchPage = !dirty && !saving
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    IconButton(enabled = canSwitchPage && pageIndex > 0, onClick = {
                        selectedImage = orderedPages[pageIndex - 1].imageId
                    }) { Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, "Previous saved image") }
                    TextButton(
                        enabled = canSwitchPage && orderedPages.isNotEmpty(),
                        modifier = Modifier.weight(
                            1f,
                        ),
                        onClick = {
                            showImagePicker = true
                        },
                    ) {
                        Text(
                            "Image ${image?.index?.plus(1) ?: selectedImage ?: "?"} · ${orderedPages.size} saved pages",
                        )
                    }
                    IconButton(
                        enabled = canSwitchPage && pageIndex >= 0 && pageIndex < orderedPages.lastIndex,
                        onClick = { selectedImage = orderedPages[pageIndex + 1].imageId },
                    ) { Icon(MaterialSymbols.AutoMirroredRounded.ArrowForward, "Next saved image") }
                }
                if (showImagePicker) {
                    AdaptiveSheet(onDismissRequest = { showImagePicker = false }) {
                        LazyColumn {
                            items(orderedPages, key = { it.imageId }) { result ->
                                TextPreferenceWidget(
                                    title = "Image ${images.firstOrNull {
                                        it.id == result.imageId
                                    }?.index?.plus(1) ?: result.imageId}",
                                    subtitle = if (result.imageId ==
                                        selectedImage
                                    ) {
                                        "Selected · ${result.regions.size} stored regions"
                                    } else {
                                        "${result.regions.size} stored regions"
                                    },
                                    onPreferenceClick = {
                                        if (!dirty && !saving) selectedImage = result.imageId
                                        showImagePicker = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (page != null && image != null && job != null) {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        job.archiveMetadata?.structuredPages?.get(page.imageId)?.let { imported ->
                            item("import-provenance") {
                                Text(
                                    "Imported from ${imported.source.name} · ${imported.source.format.name}\n" +
                                        "File image ID: ${imported.sourceImageId}\n" +
                                        "SHA-256: ${imported.source.sha256}\n" +
                                        "Imported usage is historical provenance, not application spending.",
                                    Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        item("preview") {
                            TranslationGeometryPreview(
                                image = image,
                                result = page,
                                style = requireNotNull(effectiveStyle),
                                contentPolicy = contentPolicy,
                                source = source,
                                boxes = boxes,
                                masks = masks,
                                translations = translations,
                                zoom = zoom,
                                selectedRegionId = selectedRegion,
                                onZoom = { zoom = it },
                                onSelect = { selectedRegion = it },
                                onCorner = { id, corner, point ->
                                    change { TranslationRegionEdits.moveCorner(draft ?: page, id, corner, point) }
                                },
                                onLayout = { layoutDiagnostics = it },
                                modifier = Modifier.fillMaxWidth().height(320.dp),
                            )
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FilterChip(source, { source = !source }, label = { Text("Source image") })
                                FilterChip(boxes, { boxes = !boxes }, label = { Text("Boxes") })
                                FilterChip(masks, { masks = !masks }, label = { Text("Masks") })
                                FilterChip(translations, {
                                    translations = !translations
                                }, label = { Text("Translated text") })
                                TextButton(onClick = { zoom = 1f }) { Text("Fit page") }
                            }
                            Row(Modifier.padding(horizontal = 12.dp)) {
                                Text("Zoom ${"%.1f".format(zoom)}×", Modifier.padding(top = 12.dp))
                                Slider(zoom, { zoom = it }, valueRange = 1f..32f, modifier = Modifier.weight(1f))
                            }
                            Text(
                                "Select a region, then drag its corners. Pinch to zoom or drag to pan. " +
                                    "Changes stay in original-image pixels.",
                                Modifier.padding(horizontal = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            geometryError?.let {
                                Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error)
                            }
                            if (dirty) {
                                if (stale) {
                                    Text(
                                        "The saved result changed. Discard this draft and load the current " +
                                            "result before editing again.",
                                        Modifier.padding(12.dp),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                Row {
                                    TextButton(enabled = !saving && !stale, onClick = {
                                        saving = true
                                        action {
                                            try {
                                                manager.editResult(jobId, page)
                                                draft =
                                                    graph.translationRepository.results(jobId).firstOrNull {
                                                        it.imageId ==
                                                            page.imageId
                                                    }
                                                dirty = false
                                            } finally {
                                                saving = false
                                            }
                                        }
                                    }) { Text("Save manual edits") }
                                    TextButton(enabled = !saving, onClick = {
                                        draft = savedPage
                                        dirty = false
                                        geometryError =
                                            null
                                    }) { Text("Discard edits") }
                                }
                            }
                        }
                        item("quality-review") {
                            QualityReviewCard(
                                review,
                                presentationOutdated = presentationOutdated,
                                enabled = !dirty && !saving,
                                canUndo = canUndo,
                                onReview = { action { manager.reviewPages(jobId, setOf(page.imageId)) } },
                                onUndo = { action { manager.undoRepair(jobId, page.imageId) } },
                                onRegion = {
                                    selectedRegion = it
                                    query = ""
                                },
                            )
                        }
                        item("search") {
                            OutlinedTextField(query, {
                                query = it
                            }, label = {
                                Text("Search source, correction, translation, or type")
                            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                        }
                        items(
                            page.regions.filter { region ->
                                listOf(
                                    region.sourceText,
                                    region.correctedText.orEmpty(),
                                    region.translatedText,
                                    region.type,
                                ).any {
                                    it.contains(query, true)
                                }
                            }.sortedBy { it.readingOrder },
                            key = { it.id },
                        ) { region ->
                            val excludedByPolicy = contentPolicy.excludes(region)
                            Card(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable {
                                    selectedRegion =
                                        region.id
                                },
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "${if (selectedRegion == region.id) "● " else ""}" +
                                            "${region.readingOrder + 1}. ${region.type} · " +
                                            when {
                                                excludedByPolicy -> "hidden by Ignore sound effects"
                                                region.included -> "included"
                                                else -> "ignored"
                                            },
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    SelectionContainer {
                                        Text(
                                            "Raw source: ${region.sourceText.ifBlank {
                                                "unavailable for this manual region"
                                            }}",
                                        )
                                    }
                                    region.correctedText?.let { Text("Corrected: $it") }
                                    SelectionContainer {
                                        Text(region.translatedText, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Text(
                                        region.points.joinToString {
                                            "(${it.x}, ${it.y})"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "Rotation ${region.rotation}° · Detection ${score(
                                            region.detectionConfidence,
                                        )} · Recognition ${score(
                                            region.recognitionConfidence,
                                        )} · AI estimate ${score(region.aiConfidence)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    region.ignoredReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    if (excludedByPolicy) {
                                        Text(
                                            "Original SFX lettering stays visible. " +
                                                "Saved translation, OCR and geometry " +
                                                "are retained. Correct a misclassified region type, or turn off " +
                                                "Ignore sound effects to restore eligible cached overlays. " +
                                                "If needed, include the region and add or retry its translation " +
                                                "after changing the policy.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    layoutDiagnostics.firstOrNull { it.regionId == region.id }?.let { fit ->
                                        Text(
                                            "Local layout · ${"%.2f".format(fit.effectiveFontSize)} px · " +
                                                "${fit.lineCount} lines · ${fit.forcedWordBreaks} forced word breaks",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        if (fit.belowPreferredMinimum) {
                                            Text(
                                                "Shrunk below preferred minimum ${fit.preferredMinFontSize} px",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        if (fit.overflow) {
                                            Text(
                                                "Text does not fully fit. Adjust the region or style; " +
                                                    "complete text is shown above.",
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                        fit.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    }
                                    TranslationRowActionMenu(
                                        listOf(
                                            TranslationRowAction(
                                                "Edit text / coordinates",
                                                MaterialSymbols.Rounded.EditNote,
                                                !saving,
                                            ) {
                                                selectedRegion = region.id
                                                edit = region
                                            },
                                            TranslationRowAction("−90°", MaterialSymbols.Rounded.Refresh, !saving) {
                                                change { TranslationRegionEdits.rotate(page, region.id, -90) }
                                            },
                                            TranslationRowAction("+90°", MaterialSymbols.Rounded.Refresh, !saving) {
                                                change { TranslationRegionEdits.rotate(page, region.id, 90) }
                                            },
                                            TranslationRowAction(
                                                "Earlier",
                                                MaterialSymbols.Rounded.ArrowUpward,
                                                !saving,
                                            ) {
                                                change { TranslationRegionEdits.moveOrder(page, region.id, -1) }
                                            },
                                            TranslationRowAction(
                                                "Later",
                                                MaterialSymbols.Rounded.ArrowDownward,
                                                !saving,
                                            ) {
                                                change { TranslationRegionEdits.moveOrder(page, region.id, 1) }
                                            },
                                            TranslationRowAction("Split", MaterialSymbols.Rounded.EditNote, !saving) {
                                                split =
                                                    region
                                            },
                                            TranslationRowAction(
                                                "Retranslate region",
                                                MaterialSymbols.Rounded.Refresh,
                                                !dirty && !saving && !excludedByPolicy,
                                            ) {
                                                action { manager.retryRegion(jobId, page.imageId, region.id) }
                                            },
                                        ),
                                        "Actions for region ${region.readingOrder + 1}",
                                    )
                                }
                            }
                        }
                        item {
                            Text(
                                "Raw OCR and measured scores remain separate from corrections. " +
                                    "Review meaning and visual output; confidence alone does not establish accuracy.",
                                Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                } else {
                    Text("Completed image results will appear here as the chapter translates.", Modifier.padding(24.dp))
                }
            }
        }
        if (page != null) {
            edit?.let { region ->
                RegionEditor(region, page, contentPolicy, onDismiss = { edit = null }) { updated ->
                    change { TranslationRegionEdits.update(page, updated) }
                    edit = null
                }
            }
            split?.let { region ->
                RegionSplitEditor(region, onDismiss = {
                    split = null
                }) { axis, firstSource, firstTranslation, secondSource, secondTranslation ->
                    change {
                        TranslationRegionEdits.split(
                            page,
                            region.id,
                            axis,
                            firstSource,
                            firstTranslation,
                            secondSource,
                            secondTranslation,
                            "${region.id}:manual:${UUID.randomUUID()}",
                            "${region.id}:manual:${UUID.randomUUID()}",
                        )
                    }
                    split = null
                }
            }
        }
        raw?.let { text ->
            AlertDialog(
                onDismissRequest = { raw = null },
                title = { Text("OCR result and provenance") },
                text = { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState())) } },
                confirmButton = {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy JSON") }
                },
                dismissButton = { TextButton(onClick = { raw = null }) { Text("Close") } },
            )
        }
        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text("Unsaved manual edits") },
                text = { Text("Discard this page draft and leave the inspector?") },
                confirmButton = {
                    TextButton(enabled = !saving, onClick = {
                        confirmDiscard = false
                        navigator.pop()
                    }) {
                        Text("Discard and leave")
                    }
                },
                dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
            )
        }
    }
}

private fun score(value: Float?) = value?.let { "%.1f%%".format(it * 100) } ?: "unavailable"

@Composable
private fun QualityReviewCard(
    review: QualityReviewCheckpoint?,
    presentationOutdated: Boolean,
    enabled: Boolean,
    canUndo: Boolean,
    onReview: () -> Unit,
    onUndo: () -> Unit,
    onRegion: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "AI quality assessment · ${review?.state?.name?.lowercase()?.replace('_', ' ') ?: "not reviewed"}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Review meaning and visual output; an AI pass alone does not establish accuracy.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (review != null) {
                Text("Review ID: ${review.id}", style = MaterialTheme.typography.bodySmall)
                Text("Source revision: ${review.sourceRevision}", style = MaterialTheme.typography.bodySmall)
                review.renderEvidence?.let { evidence ->
                    Text(
                        "Reviewed original + rendered overlay · " +
                            "${evidence.preview.width}×${evidence.preview.height} · " +
                            "${evidence.rendererVersion}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (presentationOutdated) {
                        Text(
                            "Appearance or content policy has changed since this review. " +
                                "Its rendered snapshot is outdated; no new review was sent.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (review.state == QualityReviewState.REPAIRED) {
                    Text(
                        "A proposed revision was applied. Check its meaning and visual output; AI findings may remain.",
                    )
                }
                Text(
                    "${review.attempts.size}/${review.settings.maxTransportAttempts} transport attempts · " +
                        "one review pass · ${review.settings.coverage.name.lowercase().replace('_', ' ')}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!review.visualComplete &&
                    !review.state.pending
                ) {
                    Text(
                        "Visual coverage incomplete; inspect the original page.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                review.message?.let { Text(it) }
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    review.findings.forEach { finding ->
                        Text("${finding.code}: ${finding.description}")
                        Text(
                            "AI confidence estimate: ${score(finding.aiConfidence)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            finding.regionIds.forEach { id ->
                                TextButton(onClick = { onRegion(id) }) { Text("Region $id") }
                            }
                        }
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                val dispatching =
                    review?.state == QualityReviewState.RUNNING || review?.state == QualityReviewState.QUEUED
                TextButton(
                    enabled = enabled && !dispatching,
                    onClick = onReview,
                ) {
                    Text("Review selected page")
                }
                TextButton(
                    enabled = enabled && canUndo,
                    onClick = onUndo,
                ) { Text("Undo AI repair") }
            }
            Text(
                "Review uses the configured provider. Save or discard manual edits before requesting review or undo.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RegionEditor(
    region: TextRegion,
    page: TranslationPageResult,
    contentPolicy: TranslationContentPolicy,
    onDismiss: () -> Unit,
    onSave: (TextRegion) -> Unit,
) {
    var draft by remember(region) { mutableStateOf(region) }
    var points by remember(region) { mutableStateOf(region.points.joinToString("; ") { "${it.x},${it.y}" }) }
    var order by remember(region) { mutableStateOf(region.readingOrder.toString()) }
    var rotation by remember(region) { mutableStateOf(region.rotation.toString()) }
    var styleJson by remember(region) { mutableStateOf(region.style?.let { Json.encodeToString(it) }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit region ${region.id}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Checkbox(draft.included, { draft = draft.copy(included = it) })
                Text("Include in translation overlay")
            }
            SelectionContainer { Text("Raw source (preserved): ${region.sourceText.ifBlank { "unavailable" }}") }
            OutlinedTextField(draft.correctedText.orEmpty(), {
                draft = draft.copy(correctedText = it.ifBlank { null })
            }, label = { Text("Corrected transcription") })
            OutlinedTextField(draft.translatedText, {
                draft = draft.copy(translatedText = it)
            }, label = { Text("Translation") })
            OutlinedTextField(
                draft.type,
                { draft = draft.copy(type = it) },
                label = { Text("Region type") },
                supportingText = {
                    Text(
                        "Use sound_effect or sfx for sound effects. Signs and dialogue stay eligible; " +
                            "unknown types keep their current behavior.",
                    )
                },
            )
            if (contentPolicy.excludes(draft)) {
                Text(
                    "Ignore sound effects hides this region’s mask and translated text. Saved inclusion and region " +
                        "styles do not override that setting. Change the type only to correct its classification.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(points, {
                points = it
            }, label = {
                Text("Ordered corners: x,y; x,y; …")
            }, supportingText = { Text("Convex polygon inside ${page.width} × ${page.height} original pixels") })
            OutlinedTextField(order, { order = it }, label = { Text("Reading order (0–${page.regions.lastIndex})") })
            OutlinedTextField(rotation, { rotation = it }, label = { Text("Rotation in degrees") })
            OutlinedTextField(draft.ignoredReason.orEmpty(), {
                draft = draft.copy(ignoredReason = it.ifBlank { null })
            }, label = { Text("Ignored reason") })
            OutlinedTextField(styleJson, { styleJson = it }, label = { Text("Region style JSON (blank inherits)") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            try {
                val parsed = points.split(';').map { point ->
                    val values = point.trim().split(',')
                    require(values.size == 2) { "Each corner needs x,y" }
                    TranslationPoint(values[0].trim().toFloat(), values[1].trim().toFloat())
                }
                val updated = draft.copy(
                    points = parsed,
                    readingOrder = order.toInt(),
                    rotation = rotation.toFloat(),
                    style = styleJson.takeIf {
                        it.isNotBlank()
                    }?.let { Json.decodeFromString(it) },
                )
                TranslationRegionEdits.update(page, updated)
                onSave(updated)
            } catch (e: Exception) {
                error = e.message ?: "Check coordinates, order, rotation, and style"
            }
        }) { Text("Apply to draft") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun RegionSplitEditor(
    region: TextRegion,
    onDismiss: () -> Unit,
    onSave: (TranslationRegionEdits.SplitAxis, String, String, String, String) -> Unit,
) {
    var axis by remember { mutableStateOf(TranslationRegionEdits.SplitAxis.TOP_BOTTOM) }
    var firstSource by remember { mutableStateOf("") }
    var firstTranslation by remember { mutableStateOf("") }
    var secondSource by remember { mutableStateOf("") }
    var secondTranslation by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Split region") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Assign both passages explicitly. The original region stays excluded with its raw OCR; " +
                    "each new region has unavailable OCR scores.",
            )
            SelectionContainer {
                Text(
                    "Source reference: ${region.correctedText ?: region.sourceText}\n" +
                        "Translation reference: ${region.translatedText}",
                )
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TranslationRegionEdits.SplitAxis.entries.forEach { value ->
                    FilterChip(axis == value, {
                        axis = value
                    }, label = { Text(value.name.lowercase().replace('_', '/')) })
                }
            }
            Text(
                if (axis ==
                    TranslationRegionEdits.SplitAxis.TOP_BOTTOM
                ) {
                    "First = top; second = bottom"
                } else {
                    "First = left; second = right"
                },
            )
            OutlinedTextField(firstSource, { firstSource = it }, label = { Text("First corrected source") })
            OutlinedTextField(firstTranslation, { firstTranslation = it }, label = { Text("First translation") })
            OutlinedTextField(secondSource, { secondSource = it }, label = { Text("Second corrected source") })
            OutlinedTextField(secondTranslation, { secondTranslation = it }, label = { Text("Second translation") })
            Text(
                "After splitting, adjust corners and reading order, then save the page.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }, confirmButton = {
        TextButton(
            enabled = listOf(firstSource, firstTranslation, secondSource, secondTranslation).all {
                it.isNotBlank()
            },
            onClick = {
                onSave(axis, firstSource, firstTranslation, secondSource, secondTranslation)
            },
        ) { Text("Split into draft") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
