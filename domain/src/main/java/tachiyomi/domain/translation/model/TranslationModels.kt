package tachiyomi.domain.translation.model

import kotlinx.serialization.Serializable

@Serializable
enum class TranslationProviderKind { VERTEX_SERVICE_ACCOUNT, VERTEX_EXPRESS, OPENAI }

@Serializable
enum class OpenAiDialect { RESPONSES, CHAT_COMPLETIONS }

@Serializable
enum class VertexAuthMode { SERVICE_ACCOUNT_JSON, SERVICE_ACCOUNT_BOUND_KEY }

@Serializable
enum class TranslationMode { VERTEX, MAX, HALVING, CUSTOM, STRUCTURED_FILES }

@Serializable
enum class OcrPipeline { AI, PADDLE, PADDLE_AI }

@Serializable
enum class PaddleProfile { TINY, SMALL, MEDIUM }

@Serializable
enum class ThinkingLevel { LOW, MEDIUM, HIGH }

@Serializable
enum class MediaResolution { DEFAULT, LOW, MEDIUM, HIGH, ULTRA_HIGH }

@Serializable
data class ProviderSettings(
    val kind: TranslationProviderKind = TranslationProviderKind.VERTEX_SERVICE_ACCOUNT,
    val credentialId: String = "default",
    val projectId: String = "",
    val location: String = "global",
    val model: String = "gemini-3.8-flash",
    val baseUrl: String = "https://api.openai.com/v1",
    val dialect: OpenAiDialect = OpenAiDialect.RESPONSES,
    val openAiImageDetail: String = "auto",
    val organization: String = "",
    val openAiProject: String = "",
    val extraHeaders: Map<String, String> = emptyMap(),
    val priorityPaygo: Boolean = false,
    val provisionedThroughput: Boolean = false,
    val thinking: ThinkingLevel = ThinkingLevel.MEDIUM,
    val mediaResolution: MediaResolution = MediaResolution.DEFAULT,
    val maxOutputTokens: Int? = null,
    val timeoutSeconds: Int = 120,
    val totalAttempts: Int = 5,
    val initialRetryMillis: Long = 1000,
    val maxRetryMillis: Long = 60000,
    val safetySettings: Map<String, String> = emptyMap(),
    val advancedJson: String = "{}",
    val maxRequestBytes: Long? = null,
    val customInputTokenLimit: Long? = null,
    val customOutputTokenLimit: Int? = null,
    val imagePreparationEnabled: Boolean = true,
    val imageTileLongEdge: Int = 4000,
    val imageTileMaxPixels: Int = 2_000_000,
    val imageTileOverlap: Int = 64,
    val vertexAuthMode: VertexAuthMode = VertexAuthMode.SERVICE_ACCOUNT_JSON,
)

@Serializable
data class OcrSettings(
    val pipeline: OcrPipeline = OcrPipeline.AI,
    val profile: PaddleProfile = PaddleProfile.SMALL,
    val language: String = "auto",
    val detectorSideLimit: Int = 64,
    val detectorLimitType: String = "min",
    val detectorMaxSide: Int = 4000,
    val detectorThreshold: Float = 0.3f,
    val boxThreshold: Float = 0.6f,
    val unclipRatio: Float = 1.5f,
    val maxCandidates: Int = 3000,
    val dilation: Boolean = false,
    val scoreMode: String = "fast",
    val recognitionThreshold: Float = 0f,
    val recognitionBatchSize: Int = 1,
    val cpuThreads: Int = 4,
    val detectOrientation: Boolean = true,
    val readingOrder: String = "auto",
    val tileOverlap: Int = 64,
)

@Serializable
data class TranslationConcurrency(
    val series: Int = 5,
    val chapters: Int = 1,
    val images: Int = 5,
    val requests: Int = 25,
    val decodedMemoryMb: Int = 128,
) {
    fun validate() {
        require(series in 1..10) { "Concurrent series must be 1–10" }
        require(chapters in 1..15) { "Concurrent chapters must be 1–15 per series" }
        require(images in 1..200) { "Concurrent images must be 1–200 per chapter" }
        require(requests in 1..200) { "Global requests must be 1–200" }
        require(decodedMemoryMb in 32..1024) { "Decoded memory budget must be 32–1024 MiB" }
    }
}

@Serializable
data class OverlayStyle(
    val enabled: Boolean = true,
    val fontPath: String? = null,
    val fontFamily: String = "system",
    val fontSize: Float = 16f,
    val fontWeight: Int = 400,
    val minFontSize: Float = 8f,
    val maxFontSize: Float = 48f,
    val autoFit: Boolean = true,
    val allowSmallerText: Boolean = true,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val textColor: Long? = null,
    val backgroundColor: Long? = null,
    val backgroundOpacity: Float = 1f,
    val outlineColor: Long? = null,
    val outlineWidth: Float = 0f,
    val padding: Float = 2f,
    val lineSpacing: Float = 1f,
    val letterSpacing: Float = 0f,
    val alignment: String = "center",
    val direction: String = "auto",
    val rotation: Float = 0f,
    val showBoxes: Boolean = false,
    val showCoordinates: Boolean = false,
    val showConfidence: Boolean = false,
    val showReadingOrder: Boolean = false,
    val showIgnored: Boolean = false,
    val sampleBackground: Boolean = false,
)

@Serializable
data class TranslationLogSettings(
    val enabled: Boolean = true,
    val captureRaw: Boolean = false,
    val retentionDays: Int = 7,
    val maxStorageMb: Int = 1024,
)

@Serializable
data class TranslationSettings(
    val provider: ProviderSettings = ProviderSettings(),
    val mode: TranslationMode = TranslationMode.VERTEX,
    val ocr: OcrSettings = OcrSettings(),
    val sourceLanguage: String = "auto",
    val targetLanguage: String = "en",
    val glossary: String = "",
    val instructions: String = "",
    val customBatchSize: Int = 1,
    val concurrency: TranslationConcurrency = TranslationConcurrency(),
    val style: OverlayStyle = OverlayStyle(),
    val logs: TranslationLogSettings = TranslationLogSettings(),
    val qualityReview: QualityReviewSettings = QualityReviewSettings(),
    val queueColors: Map<String, Long> = emptyMap(),
    val autoTranslate: Boolean = false,
    val chaptersAhead: Int = 0,
    /** Legacy JSON compatibility only; connectivity no longer requires Wi-Fi. */
    val wifiOnly: Boolean = false,
    val contentPolicy: TranslationContentPolicy = TranslationContentPolicy(),
    val notificationChapterCards: Int = 3,
    val geometryRecovery: GeometryRecoverySettings = GeometryRecoverySettings(),
    val prompts: TranslationPrompts = TranslationPrompts(),
) {
    fun validate() {
        prompts.validate()
        require(notificationChapterCards in 0..10)
        concurrency.validate()
        qualityReview.validate()
        require(style.fontWeight in 100..900) { "Font weight must be 100–900" }
        require(targetLanguage.isNotBlank()) { "Choose a target language" }
        require(customBatchSize in 1..200) { "Custom batch size must be 1–200" }
        if (mode ==
            TranslationMode.CUSTOM
        ) {
            require(customBatchSize <= concurrency.images) { "Custom batch size must fit image concurrency" }
        }
        if (mode != TranslationMode.STRUCTURED_FILES) {
            require(provider.model.isNotBlank()) { "Choose a provider model" }
            require(provider.timeoutSeconds in 10..3600)
            require(provider.totalAttempts in 1..10)
        }
        require(chaptersAhead in 0..15)
        require(logs.retentionDays in 1..365 && logs.maxStorageMb in 16..102400)
    }
}

@Serializable
data class TranslationPoint(val x: Float, val y: Float)

@Serializable
data class TextRegion(
    val id: String,
    val points: List<TranslationPoint>,
    val sourceText: String,
    val translatedText: String = "",
    val correctedText: String? = null,
    val type: String = "dialogue",
    val readingOrder: Int = 0,
    val rotation: Float = 0f,
    val included: Boolean = true,
    val ignoredReason: String? = null,
    val detectionConfidence: Float? = null,
    val recognitionConfidence: Float? = null,
    val aiConfidence: Float? = null,
    val style: OverlayStyle? = null,
)

/** Immutable source image; provider preprocessing must map all coordinates back to this image. */
@Serializable
data class TranslationImage(
    val id: String,
    val index: Int,
    val filePath: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val contentHash: String,
    val byteSize: Long,
)

@Serializable
data class OcrPageResult(
    val imageId: String,
    val regions: List<TextRegion>,
    val detectorModel: String = "",
    val recognizerModel: String = "",
    val timingsMillis: Map<String, Long> = emptyMap(),
    val rawJson: String? = null,
)

@Serializable
data class TranslationPageResult(
    val imageId: String,
    val imageHash: String,
    val width: Int,
    val height: Int,
    val regions: List<TextRegion>,
    val rawOcr: OcrPageResult? = null,
    val detectedLanguage: String? = null,
    val revision: Long = 1,
)

@Serializable
data class TranslationUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cachedTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val trafficType: String? = null,
)

data class TranslationRequest(
    val jobId: String,
    val batchId: String,
    val settings: TranslationSettings,
    val images: List<TranslationImage>,
    val ocr: List<OcrPageResult> = emptyList(),
    val context: String = "",
    val geometryRecoveryId: String? = null,
    val inputTransforms: Map<String, TranslationInputTransform> = emptyMap(),
)

data class TranslationResponse(
    val pages: List<TranslationPageResult>,
    val usage: TranslationUsage = TranslationUsage(),
    val requestId: String? = null,
    val finishReason: String? = null,
    /** Completed pages must be saved before an authentication or geometry failure pauses remaining work. */
    val deferredFailure: TranslationException? = null,
)

@Serializable
enum class TranslationJobState {
    QUEUED,
    ACQUIRING,
    OCR,
    TRANSLATING,
    PAUSED,
    WAITING,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED,
}

@Serializable
data class TranslationJob(
    val id: String,
    val mangaId: Long,
    val chapterId: Long,
    val mangaTitle: String,
    val chapterTitle: String,
    val settings: TranslationSettings,
    val state: TranslationJobState = TranslationJobState.QUEUED,
    val imageCount: Int = 0,
    val completedImages: Int = 0,
    val priority: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val message: String? = null,
    /** Explicit page review must not submit unfinished chapter images. */
    val reviewReturnState: TranslationJobState? = null,
    val reviewImageIds: List<String>? = null,
    val archiveMetadata: TranslationArchiveProvenance? = null,
    /** Explicit replacement keeps source review/Undo and accounting history on this prior job. */
    val replacesJobId: String? = null,
    /** Stable across resumes; a deliberate retry may start a new bounded correction pass. */
    val geometryRecoveryId: String? = null,
) {
    val isStructuredFiles: Boolean get() = settings.mode == TranslationMode.STRUCTURED_FILES ||
        archiveMetadata?.structuredFiles == true
}

@Serializable
data class TranslationEvent(
    val id: String,
    val jobId: String,
    val batchId: String? = null,
    val imageId: String? = null,
    val time: Long = System.currentTimeMillis(),
    val level: String = "INFO",
    val stage: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
    val capturePath: String? = null,
    val operationId: String? = null,
    val operationState: TranslationOperationState? = null,
)

@Serializable
data class TranslationBatch(
    val id: String,
    val jobId: String,
    val parentId: String? = null,
    val imageIds: List<String>,
    val state: String = "QUEUED",
    val attempts: Int = 0,
    val message: String? = null,
)

enum class TranslationFailureKind {
    AUTHENTICATION,
    CONFIGURATION,
    RATE_LIMIT,
    TRANSIENT,
    CONTENT,
    GEOMETRY,
    LIMIT,
    CANCELLED,
    STORAGE,
}

class TranslationException(
    val kind: TranslationFailureKind,
    message: String,
    val httpStatus: Int? = null,
    val retryAfterMillis: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

data class ProviderCapabilities(
    val model: String,
    val verifiedAt: String,
    val sourceUrl: String,
    val maxImages: Int,
    val maxInlineImageBytes: Long,
    val maxInputTokens: Long,
    val maxOutputTokens: Int,
    val supportsVision: Boolean = true,
    val supportsCountTokens: Boolean = false,
    val maxRequestBytes: Long? = null,
    val notes: List<String> = emptyList(),
)
