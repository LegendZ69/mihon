package mihon.feature.translation.ui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mihon.feature.translation.provider.OfficialProviderCapabilities
import mihon.feature.translation.provider.VerifiedTokenPrice
import tachiyomi.domain.translation.model.MediaResolution
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OpenAiDialect
import tachiyomi.domain.translation.model.PaddleProfile
import tachiyomi.domain.translation.model.ProviderCapabilities
import tachiyomi.domain.translation.model.QualityReviewCoverage
import tachiyomi.domain.translation.model.ThinkingLevel
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.VertexAuthMode
import java.util.Locale

internal enum class SettingsFieldKind { TEXT, CHOICE, TOGGLE }

internal fun providerPricingLink(price: VerifiedTokenPrice?): String? = price?.sourceUrl?.takeIf(String::isNotBlank)

internal fun providerImageInputLimitLabel(capabilities: ProviderCapabilities): String =
    if (!capabilities.supportsVision) {
        "Images/request: unsupported (text-only model)"
    } else {
        "Images/request: ${capabilities.maxImages.takeIf { it > 0 } ?: "unverified"}"
    }

internal data class TranslationSettingsField(
    val group: String,
    val label: String,
    val value: String,
    val hint: String = "",
    val kind: SettingsFieldKind = SettingsFieldKind.TEXT,
    val choices: List<String> = emptyList(),
    val enabled: Boolean = true,
    val multiline: Boolean = false,
    val validationHint: String = "Enter a valid value.",
    val update: (String) -> Unit,
)

/** Typed descriptors keep the searchable form and the persisted settings model in the same vocabulary. */
internal fun translationSettingsFields(
    settings: TranslationSettings,
    update: (TranslationSettings) -> Unit,
    json: Json,
): List<TranslationSettingsField> = buildList {
    var group = "Provider"
    fun text(
        label: String,
        value: String,
        hint: String = "",
        enabled: Boolean = true,
        multiline: Boolean = false,
        change: (String) -> Unit,
    ) {
        add(
            TranslationSettingsField(
                group,
                label,
                value,
                hint,
                enabled = enabled,
                multiline = multiline,
                update = change,
            ),
        )
    }
    fun toggle(label: String, value: Boolean, hint: String = "", enabled: Boolean = true, change: (Boolean) -> Unit) {
        add(
            TranslationSettingsField(
                group,
                label,
                value.toString(),
                hint,
                SettingsFieldKind.TOGGLE,
                enabled = enabled,
                update = { change(it.toBooleanStrict()) },
            ),
        )
    }
    fun choice(
        label: String,
        value: String,
        options: List<String>,
        hint: String = "",
        enabled: Boolean = true,
        change: (String) -> Unit,
    ) {
        add(
            TranslationSettingsField(
                group,
                label,
                value,
                hint,
                SettingsFieldKind.CHOICE,
                options,
                enabled,
                update = change,
            ),
        )
    }
    fun integer(
        label: String,
        value: Number?,
        range: LongRange,
        hint: String = "",
        enabled: Boolean = true,
        optional: Boolean = false,
        change: (Long?) -> Unit,
    ) {
        val optionalHint = if (optional) ", or leave blank for the provider default" else ""
        add(
            TranslationSettingsField(
                group,
                label,
                value?.toString().orEmpty(),
                hint,
                enabled = enabled,
                validationHint = "Enter ${range.first}–${range.last}$optionalHint.",
                update = {
                    val parsed = if (optional &&
                        it.isBlank()
                    ) {
                        null
                    } else {
                        it.toLong().also { number -> require(number in range) }
                    }
                    change(parsed)
                },
            ),
        )
    }
    fun decimal(
        label: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        hint: String = "",
        change: (Float) -> Unit,
    ) {
        add(
            TranslationSettingsField(
                group,
                label,
                value.toString(),
                hint,
                validationHint = "Enter ${range.start}–${range.endInclusive}.",
                update = { change(it.toFloat().also { number -> require(number.isFinite() && number in range) }) },
            ),
        )
    }
    fun color(label: String, value: Long?, change: (Long?) -> Unit) {
        add(
            TranslationSettingsField(
                group,
                label,
                value?.let { String.format(Locale.US, "#%08X", it) }.orEmpty(),
                "Blank uses the system/theme default. Enter #RRGGBB or #AARRGGBB.",
                validationHint = "Use #RRGGBB, #AARRGGBB, or leave blank.",
                update = {
                    val hex = it.trim().removePrefix("#")
                    val parsed = if (hex.isEmpty()) {
                        null
                    } else {
                        require(hex.length == 6 || hex.length == 8)
                        hex.toLong(16).let { number -> if (hex.length == 6) number or 0xFF000000L else number }
                    }
                    change(parsed)
                },
            ),
        )
    }
    val provider = settings.provider
    val vertex = provider.kind != TranslationProviderKind.OPENAI
    val gemini38 = vertex && provider.model == OfficialProviderCapabilities.GEMINI_MODEL
    choice("Provider", provider.kind.name, TranslationProviderKind.entries.map { it.name }) {
        val kind = TranslationProviderKind.valueOf(it)
        update(
            settings.copy(
                provider = provider.copy(
                    kind = kind,
                    priorityPaygo = provider.priorityPaygo && kind != TranslationProviderKind.OPENAI,
                    provisionedThroughput =
                    provider.provisionedThroughput && kind == TranslationProviderKind.VERTEX_SERVICE_ACCOUNT,
                ),
            ),
        )
    }
    if (provider.kind == TranslationProviderKind.VERTEX_SERVICE_ACCOUNT) {
        choice(
            "Vertex authentication",
            provider.vertexAuthMode.name,
            VertexAuthMode.entries.map { it.name },
            "JSON uses renewable OAuth tokens; a service-account-bound API key uses x-goog-api-key " +
                "and needs a project ID.",
        ) {
            update(settings.copy(provider = provider.copy(vertexAuthMode = VertexAuthMode.valueOf(it))))
        }
    }
    text(
        "Credential ID",
        provider.credentialId,
        "Select the storage slot to import, use or delete. Secrets are kept separately.",
    ) {
        update(settings.copy(provider = provider.copy(credentialId = it)))
    }
    text("Model ID", provider.model, "The exact model ID is sent; unsupported models are never silently replaced.") {
        update(settings.copy(provider = provider.copy(model = it)))
    }
    text(
        "Google Cloud project",
        provider.projectId,
        "Blank uses the imported service account's project.",
        enabled =
        provider.kind == TranslationProviderKind.VERTEX_SERVICE_ACCOUNT,
    ) {
        update(settings.copy(provider = provider.copy(projectId = it)))
    }
    text(
        "Vertex location",
        provider.location,
        "Gemini 3.8 supports global, us and eu. Other models may support regional IDs. Express uses its global " +
            "endpoint without a location segment.",
        enabled =
        vertex && provider.kind != TranslationProviderKind.VERTEX_EXPRESS,
    ) {
        update(settings.copy(provider = provider.copy(location = it)))
    }
    text(
        "OpenAI compatible base URL",
        provider.baseUrl,
        "HTTPS API base URL, normally ending in /v1.",
        enabled = !vertex,
    ) {
        update(settings.copy(provider = provider.copy(baseUrl = it)))
    }
    choice(
        "OpenAI API dialect",
        provider.dialect.name,
        OpenAiDialect.entries.map {
            it.name
        },
        "Responses is recommended for OpenAI; choose Chat Completions for endpoints using that contract.",
        enabled = !vertex,
    ) {
        update(settings.copy(provider = provider.copy(dialect = OpenAiDialect.valueOf(it))))
    }
    text("OpenAI organization", provider.organization, enabled = !vertex) {
        update(settings.copy(provider = provider.copy(organization = it)))
    }
    text("OpenAI project", provider.openAiProject, enabled = !vertex) {
        update(settings.copy(provider = provider.copy(openAiProject = it)))
    }
    text(
        "Extra HTTP headers (JSON)",
        json.encodeToString(provider.extraHeaders),
        "Non-secret custom headers only. Authentication and capacity headers have dedicated controls.",
        multiline = true,
    ) {
        update(settings.copy(provider = provider.copy(extraHeaders = json.decodeFromString(it))))
    }
    toggle(
        "Priority PayGo",
        provider.priorityPaygo,
        "Off uses Standard PayGo. Priority requires global and is billed at its own rate.",
        enabled =
        vertex && !provider.provisionedThroughput,
    ) {
        update(settings.copy(provider = provider.copy(priorityPaygo = it)))
    }
    toggle(
        "Provisioned Throughput",
        provider.provisionedThroughput,
        "Off explicitly bypasses purchased throughput. On requests dedicated capacity only.",
        enabled =
        provider.kind == TranslationProviderKind.VERTEX_SERVICE_ACCOUNT && !provider.priorityPaygo,
    ) {
        update(settings.copy(provider = provider.copy(provisionedThroughput = it)))
    }

    group = "Translation"
    choice(
        "Translation mode",
        settings.mode.displayLabel,
        TranslationMode.entries.map { it.displayLabel },
        "Vertex: one image for text detection. Max: most images fitting documented limits. Halving: whole " +
            "chapter, then recursively smaller batches on content/size failure. Structured files imports " +
            "saved translations without OCR, credentials or provider requests.",
    ) {
        update(settings.copy(mode = TranslationMode.entries.single { mode -> mode.displayLabel == it }))
    }
    text("Source language", settings.sourceLanguage, "auto detects the source; use a language code to override.") {
        update(settings.copy(sourceLanguage = it))
    }
    text("Target language", settings.targetLanguage, "en translates into English.") {
        update(settings.copy(targetLanguage = it))
    }
    toggle(
        "Ignore sound effects",
        settings.contentPolicy.ignoreSoundEffects,
        "Application default: on. Leaves original SFX lettering visible and excludes typed sound effects " +
            "from new translation work. Existing cached text is preserved. Applies globally or to this series; " +
            "turn off to restore eligible cached overlays without retranslating.",
    ) { update(settings.copy(contentPolicy = settings.contentPolicy.copy(ignoreSoundEffects = it))) }
    text(
        "Glossary",
        settings.glossary,
        "Names, honorifics and preferred translations. One entry per line works well.",
        multiline = true,
    ) {
        update(settings.copy(glossary = it))
    }
    text(
        "Translation instructions",
        settings.instructions,
        "Additional style, tone and terminology instructions; source images remain untrusted content.",
        multiline = true,
    ) {
        update(settings.copy(instructions = it))
    }
    integer(
        "Custom images per request",
        settings.customBatchSize,
        1L..settings.concurrency.images.toLong(),
        "Applies to Custom mode.",
        enabled =
        settings.mode == TranslationMode.CUSTOM,
    ) { update(settings.copy(customBatchSize = it!!.toInt())) }
    toggle(
        "Translate automatically",
        settings.autoTranslate,
        "Start translation when a chapter is opened. Inactive in Structured files mode.",
        enabled = settings.mode != TranslationMode.STRUCTURED_FILES,
    ) {
        update(settings.copy(autoTranslate = it))
    }
    integer(
        "Chapters ahead",
        settings.chaptersAhead,
        0L..15L,
        "Prefetch translation for the next chapters; 0 disables it. Inactive in Structured files mode.",
        enabled = settings.mode != TranslationMode.STRUCTURED_FILES,
    ) {
        update(settings.copy(chaptersAhead = it!!.toInt()))
    }

    group = "Notifications"
    integer(
        "Active chapter notification cards",
        settings.notificationChapterCards,
        0L..10L,
        "Application default: 3. Global setting; grouped summary remains. " +
            "Disabled Android permissions/channels are respected.",
    ) {
        update(settings.copy(notificationChapterCards = it!!.toInt()))
    }

    group = "Geometry recovery"
    toggle(
        "Correct rejected region geometry",
        settings.geometryRecovery.enabled,
        "Application default: on for new jobs and explicit retries. One geometry-only correction pass, " +
            "at most two transport attempts capped by the provider retry limit. Preserves saved pages and text. " +
            "Pure Paddle keeps its measured OCR geometry. Existing queued jobs retain their prior policy.",
    ) { update(settings.copy(geometryRecovery = settings.geometryRecovery.copy(enabled = it))) }

    group = "Quality review"
    val review = settings.qualityReview
    toggle(
        "Review translation quality automatically",
        review.enabled,
        "Application default: on. Uses one provider review pass after translation; " +
            "Review meaning and visual output; confidence alone does not establish accuracy.",
    ) { update(settings.copy(qualityReview = review.copy(enabled = it))) }
    choice(
        "Review coverage",
        review.coverage.name,
        QualityReviewCoverage.entries.map { it.name },
        "Application default: full page when available. Text-only review cannot establish " +
            "visual completeness or reading order from the original image.",
    ) { update(settings.copy(qualityReview = review.copy(coverage = QualityReviewCoverage.valueOf(it)))) }
    toggle(
        "Include rendered overlay in visual review",
        review.includeRenderedPreview,
        "Application default: on for newly scheduled visual reviews. Sends the unchanged original plus a labelled " +
            "bounded preview of its translation. Adds image input cost; text-only and Paddle reviews send no images. " +
            "Existing pending reviews keep their captured input policy.",
    ) { update(settings.copy(qualityReview = review.copy(includeRenderedPreview = it))) }
    integer(
        "Review transport attempts",
        review.maxTransportAttempts,
        1L..2L,
        "Application default: 2. One review pass may use at most this many transport attempts, " +
            "including an uncertain interrupted request.",
    ) { update(settings.copy(qualityReview = review.copy(maxTransportAttempts = it!!.toInt()))) }
    text(
        "Review passes",
        "1",
        "Fixed application policy, not an official provider default. No recursive review/repair loop.",
        enabled = false,
    ) {}

    group = "Generation"
    choice(
        "Thinking level",
        provider.thinking.name,
        ThinkingLevel.entries.map {
            it.name
        },
        "Gemini 3.8 defaults to Medium; Minimal is unsupported. Max batching does not raise thinking.",
        enabled = vertex,
    ) {
        update(settings.copy(provider = provider.copy(thinking = ThinkingLevel.valueOf(it))))
    }
    if (OfficialProviderCapabilities.forSettings(provider).supportsVision) {
        choice(
            "Image media resolution",
            provider.mediaResolution.name,
            MediaResolution.entries.map {
                it.name
            },
            "Gemini 3: Low 280, Medium 560, High 1120, Ultra high 2240 tokens/image. Ultra high is set per image.",
            enabled = vertex,
        ) {
            update(settings.copy(provider = provider.copy(mediaResolution = MediaResolution.valueOf(it))))
        }
        choice(
            "OpenAI image detail",
            provider.openAiImageDetail,
            if (OfficialProviderCapabilities.isGroq(
                    provider,
                )
            ) {
                listOf("auto", "low", "high")
            } else {
                listOf("auto", "low", "high", "original")
            },
            "Original is recommended for OCR when the selected model supports it. Auto follows the model default; " +
                "sizing and token costs vary by model.",
            enabled = !vertex && OfficialProviderCapabilities.forSettings(provider).supportsVision,
        ) {
            update(settings.copy(provider = provider.copy(openAiImageDetail = it)))
        }
        toggle(
            "Tile long and large upload images",
            provider.imagePreparationEnabled,
            "App policy: lossless PNG tiles preserve readable text. Source pixels and overlays stay unchanged. " +
                "Required size/format/EXIF normalization still applies when off.",
        ) {
            update(settings.copy(provider = provider.copy(imagePreparationEnabled = it)))
        }
        integer(
            "Upload tile maximum edge",
            provider.imageTileLongEdge,
            256L..16384L,
            "App default: 4000 pixels. One prepared tile per Vertex-mode request.",
        ) {
            update(settings.copy(provider = provider.copy(imageTileLongEdge = it!!.toInt())))
        }
        integer(
            "Upload tile maximum pixels",
            provider.imageTileMaxPixels,
            65536L..16000000L,
            "App default: 2 megapixels, further bounded by decoded-memory budget.",
        ) {
            update(settings.copy(provider = provider.copy(imageTileMaxPixels = it!!.toInt())))
        }
        integer(
            "Upload tile overlap",
            provider.imageTileOverlap,
            0L..1024L,
            "Repeated border regions are merged in original image coordinates.",
        ) {
            update(settings.copy(provider = provider.copy(imageTileOverlap = it!!.toInt())))
        }
    }
    integer(
        "Maximum output tokens",
        provider.maxOutputTokens,
        1L..if (gemini38) 65_536L else Int.MAX_VALUE.toLong(),
        "Blank leaves the model default; Gemini 3.8 allows up to 65,536.",
        optional = true,
    ) {
        update(settings.copy(provider = provider.copy(maxOutputTokens = it?.toInt())))
    }
    text(
        "Safety settings (JSON)",
        json.encodeToString(provider.safetySettings),
        "Map supported Google harm category names to threshold names. Blank object uses provider defaults.",
        enabled = vertex,
        multiline = true,
    ) {
        update(settings.copy(provider = provider.copy(safetySettings = json.decodeFromString(it))))
    }
    text(
        "Advanced generation settings (JSON)",
        provider.advancedJson,
        "Vertex: seed, stopSequences, responseLogprobs, logprobs; model support varies. OpenAI: supported " +
            "sampling/reasoning/service tier controls.",
        multiline = true,
    ) {
        json.parseToJsonElement(it).jsonObject
        update(settings.copy(provider = provider.copy(advancedJson = it)))
    }
    if (gemini38) {
        text(
            "Temperature / Top P / Top K",
            "Ignored by Gemini 3.8",
            "Kept out of requests according to the model guide.",
            enabled = false,
        ) {
        }
        text(
            "Candidate count / presence / frequency penalties",
            "Unsupported by Gemini 3.8",
            "Sending these fields produces an API error.",
            enabled = false,
        ) {
        }
    }
    integer("Request timeout (seconds)", provider.timeoutSeconds, 10L..3600L) {
        update(settings.copy(provider = provider.copy(timeoutSeconds = it!!.toInt())))
    }
    integer(
        "Total request attempts",
        provider.totalAttempts,
        1L..10L,
        "Includes the original request; 1 disables retry. Only transient failures retry.",
    ) {
        update(settings.copy(provider = provider.copy(totalAttempts = it!!.toInt())))
    }
    integer(
        "Initial retry delay (ms)",
        provider.initialRetryMillis,
        0L..3_600_000L,
        "Official default: 1000 ms, exponential backoff with jitter.",
    ) {
        update(settings.copy(provider = provider.copy(initialRetryMillis = it!!)))
    }
    integer(
        "Maximum retry delay (ms)",
        provider.maxRetryMillis,
        0L..3_600_000L,
        "Official default: 60000 ms. A server Retry-After may require waiting longer.",
    ) {
        update(settings.copy(provider = provider.copy(maxRetryMillis = it!!)))
    }
    integer(
        "Optional request byte budget",
        provider.maxRequestBytes,
        1L..Long.MAX_VALUE,
        "An app budget, not a claimed Vertex limit. Blank adds no app request-size ceiling.",
        optional = true,
    ) {
        update(settings.copy(provider = provider.copy(maxRequestBytes = it)))
    }
    integer(
        "Custom model input token limit",
        provider.customInputTokenLimit,
        1L..Long.MAX_VALUE,
        "Required for Max when the model's limits are unverified. Known official model limits take precedence.",
        optional = true,
    ) {
        update(settings.copy(provider = provider.copy(customInputTokenLimit = it)))
    }
    integer(
        "Custom model output token limit",
        provider.customOutputTokenLimit,
        1L..Int.MAX_VALUE.toLong(),
        "Required for Max when the model's limits are unverified.",
        optional = true,
    ) {
        update(settings.copy(provider = provider.copy(customOutputTokenLimit = it?.toInt())))
    }

    group = "Concurrency"
    integer("Concurrent series", settings.concurrency.series, 1L..10L) {
        update(settings.copy(concurrency = settings.concurrency.copy(series = it!!.toInt())))
    }
    integer("Concurrent chapters per series", settings.concurrency.chapters, 1L..15L) {
        update(settings.copy(concurrency = settings.concurrency.copy(chapters = it!!.toInt())))
    }
    integer(
        "Concurrent images per chapter",
        settings.concurrency.images,
        1L..200L,
        "Ignored in Halving mode; request and decoded-memory budgets still apply.",
    ) {
        update(
            settings.copy(
                concurrency = settings.concurrency.copy(images = it!!.toInt()),
                customBatchSize = minOf(settings.customBatchSize, it.toInt()),
            ),
        )
    }
    integer(
        "Global request limit",
        settings.concurrency.requests,
        1L..200L,
        "Caps all active requests across series and chapters.",
    ) {
        update(settings.copy(concurrency = settings.concurrency.copy(requests = it!!.toInt())))
    }
    integer(
        "Decoded image memory (MiB)",
        settings.concurrency.decodedMemoryMb,
        32L..1024L,
        "Bounds local image decoding; request image data streams from disk.",
    ) {
        update(settings.copy(concurrency = settings.concurrency.copy(decodedMemoryMb = it!!.toInt())))
    }

    group = "OCR"
    val ocr = settings.ocr
    choice(
        "OCR pipeline",
        ocr.pipeline.name,
        OcrPipeline.entries.map {
            it.name
        },
        "AI reads and translates images. Paddle reads locally before text translation. Paddle + AI also checks " +
            "the images.",
    ) {
        update(settings.copy(ocr = ocr.copy(pipeline = OcrPipeline.valueOf(it))))
    }
    choice(
        "Paddle model size",
        ocr.profile.name,
        PaddleProfile.entries.map {
            it.name
        },
    ) { update(settings.copy(ocr = ocr.copy(profile = PaddleProfile.valueOf(it)))) }
    text(
        "Paddle language",
        ocr.language,
        "An explicit code overrides the source language for OCR. Auto uses available source language metadata, " +
            "otherwise the multilingual pack; choose ko for Korean when metadata is unavailable.",
    ) {
        update(settings.copy(ocr = ocr.copy(language = it)))
    }
    integer("Detector side limit", ocr.detectorSideLimit, 16L..8192L) {
        update(settings.copy(ocr = ocr.copy(detectorSideLimit = it!!.toInt())))
    }
    choice("Detector limit type", ocr.detectorLimitType, listOf("min", "max", "resize_long")) {
        update(settings.copy(ocr = ocr.copy(detectorLimitType = it)))
    }
    integer("Detector maximum side", ocr.detectorMaxSide, 32L..16384L) {
        update(settings.copy(ocr = ocr.copy(detectorMaxSide = it!!.toInt())))
    }
    decimal("Detector threshold", ocr.detectorThreshold, 0f..1f) {
        update(settings.copy(ocr = ocr.copy(detectorThreshold = it)))
    }
    decimal("Box threshold", ocr.boxThreshold, 0f..1f) { update(settings.copy(ocr = ocr.copy(boxThreshold = it))) }
    decimal("Unclip ratio", ocr.unclipRatio, 0.01f..10f, "Expansion around detected text regions.") {
        update(settings.copy(ocr = ocr.copy(unclipRatio = it)))
    }
    integer("Maximum detector candidates", ocr.maxCandidates, 1L..100000L) {
        update(settings.copy(ocr = ocr.copy(maxCandidates = it!!.toInt())))
    }
    toggle("Detector dilation", ocr.dilation) { update(settings.copy(ocr = ocr.copy(dilation = it))) }
    choice("Detector score mode", ocr.scoreMode, listOf("fast", "slow")) {
        update(settings.copy(ocr = ocr.copy(scoreMode = it)))
    }
    decimal(
        "Recognition confidence threshold",
        ocr.recognitionThreshold,
        0f..1f,
        "Measured OCR score; separate from AI self-assessed confidence.",
    ) {
        update(settings.copy(ocr = ocr.copy(recognitionThreshold = it)))
    }
    integer(
        "Recognition batch size",
        ocr.recognitionBatchSize,
        1L..200L,
        "Maximum text crops per batch; working-memory limits can reduce the effective batch.",
    ) {
        update(settings.copy(ocr = ocr.copy(recognitionBatchSize = it!!.toInt())))
    }
    integer("OCR CPU threads", ocr.cpuThreads, 1L..32L) {
        update(settings.copy(ocr = ocr.copy(cpuThreads = it!!.toInt())))
    }
    toggle(
        "Rotate tall text crops",
        ocr.detectOrientation,
        "Perspective rectification always applies. This rotates tall crops for recognition; it does not run a " +
            "page-orientation classifier.",
    ) {
        update(settings.copy(ocr = ocr.copy(detectOrientation = it)))
    }
    choice("Reading order", ocr.readingOrder, listOf("auto", "ltr", "rtl", "vertical")) {
        update(settings.copy(ocr = ocr.copy(readingOrder = it)))
    }
    integer(
        "Tile overlap (pixels)",
        ocr.tileOverlap,
        0L..256L,
        "Overlapping long-page tiles preserve text across image boundaries.",
    ) {
        update(settings.copy(ocr = ocr.copy(tileOverlap = it!!.toInt())))
    }

    group = "Overlay style"
    val style = settings.style
    toggle("Show translated overlay", style.enabled) { update(settings.copy(style = style.copy(enabled = it))) }
    choice(
        "Font family",
        style.fontFamily,
        listOf("system", "sans-serif", "serif", "monospace", "cursive"),
        "An imported font takes precedence. Use system font clears the imported selection.",
    ) {
        update(settings.copy(style = style.copy(fontFamily = it)))
    }
    text(
        "Imported font path",
        style.fontPath.orEmpty(),
        "Import a font below, or clear to use the selected system family.",
    ) {
        update(settings.copy(style = style.copy(fontPath = it.ifBlank { null })))
    }
    decimal("Text size", style.fontSize, 1f..200f) { update(settings.copy(style = style.copy(fontSize = it))) }
    integer("Font weight", style.fontWeight, 100L..900L) {
        update(settings.copy(style = style.copy(fontWeight = it!!.toInt())))
    }
    decimal("Minimum auto-fit size", style.minFontSize, 1f..200f) {
        update(settings.copy(style = style.copy(minFontSize = it)))
    }
    decimal("Maximum auto-fit size", style.maxFontSize, 1f..300f) {
        update(settings.copy(style = style.copy(maxFontSize = it)))
    }
    toggle("Auto-fit text to region", style.autoFit) { update(settings.copy(style = style.copy(autoFit = it))) }
    toggle(
        "Allow smaller text when needed",
        style.allowSmallerText,
        "Application default: on. Auto-fit may go below the preferred minimum, down to 1 original-image pixel. " +
            "Applies to cached overlays without retranslating; fixed-size mode does not shrink. " +
            "The inspector reports the actual size and any remaining overflow.",
    ) { update(settings.copy(style = style.copy(allowSmallerText = it))) }
    toggle("Bold", style.bold) { update(settings.copy(style = style.copy(bold = it))) }
    toggle("Italic", style.italic) { update(settings.copy(style = style.copy(italic = it))) }
    color("Text color", style.textColor) { update(settings.copy(style = style.copy(textColor = it))) }
    color("Background color", style.backgroundColor) { update(settings.copy(style = style.copy(backgroundColor = it))) }
    decimal("Background opacity", style.backgroundOpacity, 0f..1f) {
        update(settings.copy(style = style.copy(backgroundOpacity = it)))
    }
    toggle("Sample background from page", style.sampleBackground) {
        update(settings.copy(style = style.copy(sampleBackground = it)))
    }
    color("Outline color", style.outlineColor) { update(settings.copy(style = style.copy(outlineColor = it))) }
    decimal("Outline width", style.outlineWidth, 0f..20f) {
        update(settings.copy(style = style.copy(outlineWidth = it)))
    }
    decimal("Region padding", style.padding, 0f..100f) { update(settings.copy(style = style.copy(padding = it))) }
    decimal("Line spacing", style.lineSpacing, 0.5f..4f) { update(settings.copy(style = style.copy(lineSpacing = it))) }
    decimal("Letter spacing", style.letterSpacing, -0.5f..2f) {
        update(settings.copy(style = style.copy(letterSpacing = it)))
    }
    choice("Text alignment", style.alignment, listOf("start", "center", "end")) {
        update(settings.copy(style = style.copy(alignment = it)))
    }
    choice("Text direction", style.direction, listOf("auto", "ltr", "rtl")) {
        update(settings.copy(style = style.copy(direction = it)))
    }
    decimal("Text rotation", style.rotation, -360f..360f) { update(settings.copy(style = style.copy(rotation = it))) }

    group = "OCR inspection"
    toggle("Show bounding boxes", style.showBoxes) { update(settings.copy(style = style.copy(showBoxes = it))) }
    toggle("Show XY coordinates", style.showCoordinates) {
        update(settings.copy(style = style.copy(showCoordinates = it)))
    }
    toggle(
        "Show confidence scores",
        style.showConfidence,
        "OCR detector/recognizer values and AI self-assessment remain separate.",
    ) {
        update(settings.copy(style = style.copy(showConfidence = it)))
    }
    toggle("Show reading order", style.showReadingOrder) {
        update(settings.copy(style = style.copy(showReadingOrder = it)))
    }
    toggle("Show ignored regions", style.showIgnored) { update(settings.copy(style = style.copy(showIgnored = it))) }

    group = "Logs and storage"
    toggle("Detailed translation logs", settings.logs.enabled) {
        update(settings.copy(logs = settings.logs.copy(enabled = it)))
    }
    toggle(
        "API payload capture — sanitized",
        settings.logs.captureRaw,
        "Stores API structure and translated text. Images, thought signatures and secrets are omitted; " +
            "this is not a replay archive.",
    ) {
        update(settings.copy(logs = settings.logs.copy(captureRaw = it)))
    }
    integer("Log retention (days)", settings.logs.retentionDays, 1L..365L) {
        update(settings.copy(logs = settings.logs.copy(retentionDays = it!!.toInt())))
    }
    integer(
        "Sanitized capture storage (MiB)",
        settings.logs.maxStorageMb,
        16L..102400L,
        "Oldest completed captures are pruned to this budget.",
    ) {
        update(settings.copy(logs = settings.logs.copy(maxStorageMb = it!!.toInt())))
    }
    group = "Queue colors"
    TranslationJobState.entries.forEach { state ->
        color("${state.name.lowercase().replace('_', ' ')} color", settings.queueColors[state.name]) { value ->
            update(
                settings.copy(
                    queueColors = if (value ==
                        null
                    ) {
                        settings.queueColors - state.name
                    } else {
                        settings.queueColors + (state.name to value)
                    },
                ),
            )
        }
    }
}

internal val TranslationMode.displayLabel: String
    get() = if (this == TranslationMode.STRUCTURED_FILES) "Structured files" else name
