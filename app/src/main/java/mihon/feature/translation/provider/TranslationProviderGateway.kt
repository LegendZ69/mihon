package mihon.feature.translation.provider

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import tachiyomi.domain.translation.model.GeometryCorrectionAttempt
import tachiyomi.domain.translation.model.GeometryCorrectionCheckpoint
import tachiyomi.domain.translation.model.GeometryCorrectionState
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OpenAiDialect
import tachiyomi.domain.translation.model.ProviderCapabilities
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.QualityReviewRenderEvidence
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.QualityReviewResponse
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationInputTransform
import tachiyomi.domain.translation.model.TranslationLogSettings
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationResponse
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.model.TranslationUsage
import tachiyomi.domain.translation.model.VertexAuthMode
import tachiyomi.domain.translation.service.GeometryCorrectionCoordinator
import tachiyomi.domain.translation.service.QualityReviewValidation
import tachiyomi.domain.translation.service.TranslationProvider
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

@SingleIn(AppScope::class)
class TranslationProviderGateway internal constructor(
    private val credentials: suspend (String) -> StoredTranslationCredential,
    private val repository: TranslationRepository,
    diagnostics: TranslationDiagnosticsStore,
    client: OkHttpClient,
    private val temporaryDirectory: File,
    private val preparation: ImagePreparation? = null,
    private val localFixturesEnabled: Boolean = false,
    private val originalOrientation: (File) -> Int = { file ->
        ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    },
) : TranslationProvider {
    @Inject
    constructor(
        context: Context,
        vault: TranslationCredentialVault,
        repository: TranslationRepository,
        diagnostics: TranslationDiagnosticsStore,
        network: NetworkHelper,
    ) : this(
        vault::load,
        repository,
        diagnostics,
        isolatedClient(network.client),
        File(context.cacheDir, "translation/http"),
        ImagePreparation(File(context.cacheDir, "translation/prepared")),
        BuildConfig.TRANSLATION_LOCAL_FIXTURES_ENABLED,
    )

    private val http = TranslationHttpTransport(client, diagnostics, repository, temporaryDirectory)
    private val oauth = GoogleServiceAccountTokens(client)
    private val tokenCounts = ConcurrentHashMap<String, Long>()

    override fun capabilities(settings: ProviderSettings): ProviderCapabilities =
        OfficialProviderCapabilities.forSettings(settings)

    override suspend fun review(request: QualityReviewRequest): QualityReviewResponse = withContext(Dispatchers.IO) {
        validateInputModality(request.settings.provider, QualityReviewWireFormat.visual(request))
        val image = request.image
        val baseline = request.baseline
        QualityReviewValidation.validatePage(baseline)
        if (baseline.imageId != image.id || baseline.imageHash != image.contentHash ||
            baseline.width != image.width || baseline.height != image.height
        ) {
            throw TranslationException(
                TranslationFailureKind.CONTENT,
                "Review baseline does not match the original image.",
            )
        }
        val evidence = QualityReviewWireFormat.renderEvidence(request)
        evidence?.let { validateRenderEvidence(request, it) }
        val effective = if (evidence == null) request else request.copy(image = evidence.original)
        val wire = QualityReviewWireFormat.wireRequest(effective)
        val inputs = listOf(effective.image) + listOfNotNull(evidence?.preview)
        val snapshots = mutableListOf<File>()
        try {
            val stable = try {
                // Review never enters ImagePreparation: full bytes or an explicitly text-only request.
                validateRequest(wire, inputs)
                if (QualityReviewWireFormat.visual(request)) {
                    val original = snapshotReviewImage(effective.image, request.settings.provider, "original")
                        .also { snapshots += it }
                    verifyReviewOrientation(original, "original")
                    val stableOriginal = effective.image.copy(filePath = original.path)
                    val stableEvidence = evidence?.let {
                        val preview = snapshotReviewImage(it.preview, request.settings.provider, "preview")
                            .also { file -> snapshots += file }
                        verifyReviewOrientation(preview, "rendered preview")
                        it.copy(original = stableOriginal, preview = it.preview.copy(filePath = preview.path))
                    }
                    effective.copy(image = stableOriginal, renderEvidence = stableEvidence)
                } else {
                    // Pure Paddle and explicit text-only review must never inspect supplied image evidence.
                    effective.copy(renderEvidence = null)
                }
            } catch (error: TranslationException) {
                if (evidence != null &&
                    error.kind in setOf(TranslationFailureKind.CONTENT, TranslationFailureKind.STORAGE)
                ) {
                    throw TranslationException(
                        TranslationFailureKind.LIMIT,
                        "Required visual review evidence is unavailable or changed; saved translation retained. " +
                            error.message,
                        cause = error,
                    )
                }
                throw error
            }
            reviewStableOriginal(stable)
        } finally {
            snapshots.forEach { it.delete() }
        }
    }

    private fun validateRenderEvidence(request: QualityReviewRequest, evidence: QualityReviewRenderEvidence) {
        val original = evidence.original
        val expected = request.image
        val preview = evidence.preview
        fun requireEvidence(condition: Boolean, message: String) {
            if (!condition) throw TranslationException(TranslationFailureKind.LIMIT, message)
        }
        requireEvidence(
            evidence.sourceRevision == request.baseline.revision &&
                original.copy(filePath = expected.filePath) == expected,
            "Rendered review evidence does not match the saved source revision and image identity.",
        )
        requireEvidence(
            evidence.rendererVersion.isNotBlank() && evidence.presentationFingerprint.matches(Regex("[a-f0-9]{64}")),
            "Rendered review evidence is missing its presentation identity.",
        )
        requireEvidence(
            preview.id != original.id && preview.width in 1..4096 && preview.height in 1..4096 &&
                preview.width.toLong() * preview.height <= 2L * 1024 * 1024 &&
                preview.width <= original.width && preview.height <= original.height && preview.mimeType == "image/png",
            "Rendered review evidence must be a bounded PNG of the complete page without upscaling.",
        )
        requireEvidence(
            evidence.scaleX.isFinite() && evidence.scaleY.isFinite() &&
                evidence.scaleX > 0 && evidence.scaleY > 0 &&
                abs(evidence.scaleX - preview.width.toDouble() / original.width) <= 1e-9 &&
                abs(evidence.scaleY - preview.height.toDouble() / original.height) <= 1e-9,
            "Rendered review evidence has an invalid original-to-preview transform.",
        )
    }

    private fun verifyReviewOrientation(snapshot: File, label: String) {
        val orientation = try {
            originalOrientation(snapshot)
        } catch (error: IOException) {
            throw TranslationException(
                TranslationFailureKind.LIMIT,
                "Cannot verify $label orientation for full-page review; the existing translation is preserved.",
                cause = error,
            )
        }
        // AndroidX inserts UNDEFINED for untagged images and treats it as no rotation/flip.
        if (orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED) {
            throw TranslationException(
                TranslationFailureKind.LIMIT,
                "Full-page review cannot use an EXIF-rotated or mirrored $label without normalization; " +
                    "the existing translation is preserved.",
            )
        }
    }

    /** Counting and generation reopen the same owned image snapshots, never mutable chapter or preview files. */
    private suspend fun reviewStableOriginal(request: QualityReviewRequest): QualityReviewResponse {
        val image = request.image
        val baseline = request.baseline
        val wire = QualityReviewWireFormat.wireRequest(request)
        val body = QualityReviewWireFormat.requestBody(request)
        validateRequestBytes(body, request.settings.provider)
        val caps = capabilities(request.settings.provider)
        val settings = request.settings.provider
        val inputLimit = listOfNotNull(
            caps.maxInputTokens.takeIf {
                it > 0
            },
            settings.customInputTokenLimit,
        ).minOrNull()
        val outputLimit = listOfNotNull(
            caps.maxOutputTokens.takeIf {
                it > 0
            },
            settings.customOutputTokenLimit,
        ).minOrNull()
        val configuredOutput = settings.maxOutputTokens
        if ((inputLimit != null && inputLimit <= 0) || (outputLimit != null && outputLimit <= 0) ||
            (outputLimit != null && configuredOutput != null && configuredOutput > outputLimit)
        ) {
            throw TranslationException(
                TranslationFailureKind.CONFIGURATION,
                "Review token limits conflict with the effective provider settings.",
            )
        }
        if (caps.supportsCountTokens) {
            val countBody = QualityReviewWireFormat.requestBody(request, countOnly = true)
            validateRequestBytes(countBody, request.settings.provider)
            val counted = execute(
                request.settings.provider,
                countBody,
                "countTokens",
                request.jobId,
                request.reviewId,
                request.settings.logs,
                maxAttempts = 1,
            )
            val field = if (request.settings.provider.kind ==
                TranslationProviderKind.OPENAI
            ) {
                "input_tokens"
            } else {
                "totalTokens"
            }
            val count = counted.body[field]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }
                ?: throw TranslationException(
                    TranslationFailureKind.CONTENT,
                    "Review countTokens did not return a valid count.",
                )
            if (inputLimit != null && count > inputLimit) {
                throw TranslationException(
                    TranslationFailureKind.LIMIT,
                    "Full-page review exceeds the input token limit; no generation was sent.",
                )
            }
        }
        val response = execute(
            request.settings.provider,
            body,
            "qualityReview",
            request.jobId,
            request.reviewId,
            request.settings.logs,
            maxAttempts = 1,
        )
        // Keep usage/capture accounting even when candidate validation fails.
        recordUsage(response, wire, request.reviewId)
        val reviewed = QualityReviewWireFormat.decode(
            response.body,
            request,
            response.headers["x-request-id"] ?: response.headers["x-goog-request-id"],
        )
        if (request.settings.logs.enabled) {
            repository.addEvent(
                TranslationEvent(
                    UUID.randomUUID().toString(),
                    request.jobId,
                    request.reviewId,
                    stage = "QUALITY_REVIEW_RESPONSE",
                    message = "Received one review candidate; application and acceptance are pending",
                    details = mapOf(
                        "imageId" to image.id,
                        "imageHash" to image.contentHash,
                        "sourceRevision" to baseline.revision.toString(),
                        "visualComplete" to reviewed.visualComplete.toString(),
                        "findings" to reviewed.findings.size.toString(),
                        "model" to request.settings.provider.model,
                        "provider" to request.settings.provider.kind.name,
                        "countTokensSupported" to caps.supportsCountTokens.toString(),
                    ) + QualityReviewWireFormat.renderEvidence(request)?.let {
                        mapOf(
                            "previewHash" to it.preview.contentHash,
                            "previewDimensions" to "${it.preview.width}x${it.preview.height}",
                            "previewScaleX" to it.scaleX.toString(),
                            "previewScaleY" to it.scaleY.toString(),
                            "rendererVersion" to it.rendererVersion,
                            "presentationFingerprint" to it.presentationFingerprint,
                        )
                    }.orEmpty(),
                    capturePath = response.capturePath,
                ),
            )
        }
        return reviewed
    }

    private suspend fun snapshotReviewImage(image: TranslationImage, settings: ProviderSettings, label: String): File {
        val source = File(image.filePath)
        val maximum = listOfNotNull(
            image.byteSize.takeIf { it > 0 },
            capabilities(settings).maxInlineImageBytes.takeIf { it > 0 },
            capabilities(settings).maxRequestBytes,
        ).minOrNull() ?: 0L
        if (image.byteSize <= 0 || source.length() != image.byteSize) {
            throw TranslationException(TranslationFailureKind.CONTENT, "Review $label size changed; reload the page.")
        }
        if (image.byteSize > maximum) {
            throw TranslationException(
                TranslationFailureKind.LIMIT,
                "Full $label exceeds the review snapshot byte budget.",
            )
        }
        val snapshot = try {
            if (!temporaryDirectory.isDirectory && !temporaryDirectory.mkdirs()) {
                throw IOException("Cannot create review snapshot directory")
            }
            File.createTempFile("review-$label-", ".source", temporaryDirectory)
        } catch (error: IOException) {
            throw TranslationException(
                TranslationFailureKind.STORAGE,
                "Cannot snapshot the $label for review.",
                cause = error,
            )
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            source.inputStream().use { input ->
                snapshot.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > maximum) {
                            throw TranslationException(
                                TranslationFailureKind.CONTENT,
                                "Review $label grew during snapshot.",
                            )
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (copied != image.byteSize ||
                digest.digest().joinToString("") { "%02x".format(it) } != image.contentHash
            ) {
                throw TranslationException(
                    TranslationFailureKind.CONTENT,
                    "Review $label bytes changed; reload the original page.",
                )
            }
            return snapshot
        } catch (error: Exception) {
            snapshot.delete()
            if (error is CancellationException || error is TranslationException) throw error
            throw TranslationException(
                TranslationFailureKind.STORAGE,
                "Cannot snapshot the $label for review.",
                cause = error,
            )
        }
    }

    suspend fun clearCheckpoints(jobId: String) = withContext(Dispatchers.IO) {
        preparation?.clearCheckpoints(jobId)
        Unit
    }

    override suspend fun testConnection(settings: ProviderSettings): String = withContext(Dispatchers.IO) {
        validateSettings(settings)
        if (!capabilities(settings).supportsVision) return@withContext testTextConnection(settings)
        val probe = try {
            check(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs()) {
                "Cannot create connection-test image."
            }
            File.createTempFile("vision-probe-", ".png", temporaryDirectory).apply {
                writeBytes(
                    java.util.Base64.getDecoder().decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAXklEQVR4nO3PMQ0AMAzAsPInvYLYYVWKESTzjh" +
                            "sd8KsBrQGtAa0BrQGtAa0BrQGtAa0BrQGtAa0BrQGtAa0BrQGtAa0BrQGtAa0BrQGtAa0BrQGtAa0BrQGtAa0B" +
                            "bQHKU9LC7/CP1AAAAABJRU5ErkJggg==",
                    ),
                )
            }
        } catch (error: Exception) {
            throw TranslationException(
                TranslationFailureKind.STORAGE,
                "Cannot create connection-test image.",
                cause = error,
            )
        }
        try {
            val image = tachiyomi.domain.translation.model.TranslationImage(
                "vision-probe",
                0,
                probe.absolutePath,
                "image/png",
                64,
                64,
                "connection-probe",
                probe.length(),
            )
            val request = TranslationRequest(
                "connection-test",
                UUID.randomUUID().toString(),
                tachiyomi.domain.translation.model.TranslationSettings(
                    provider = settings,
                    logs = TranslationLogSettings(captureRaw = false),
                ),
                listOf(image),
                context =
                "Connection diagnostic: examine the attached image and return its page in the required schema. " +
                    "A blank image must have an empty regions array.",
            )
            validateRequest(request)
            val body = TranslationWireFormat.requestBody(request)
            validateRequestBytes(body, settings)
            val response =
                execute(
                    settings,
                    body,
                    "testConnectionVisionSchema",
                    request.jobId,
                    request.batchId,
                    request.settings.logs,
                )
            val decoded = TranslationWireFormat.decode(response.body, request, response.headers["x-request-id"])
            recordUsage(response, request)
            if (decoded.pages.singleOrNull()?.imageId != image.id ||
                decoded.pages.single().regions.any { it.included }
            ) {
                throw TranslationException(
                    TranslationFailureKind.CONTENT,
                    "Connected, but the blank-image structured response was invalid.",
                )
            }
            val providerName = settings.kind.name.lowercase().replace('_', ' ')
            "${settings.model} accepted image input and returned a valid structured page using $providerName. " +
                "Translation quality and larger request limits are not measured by this small probe."
        } finally {
            probe.delete()
        }
    }

    private suspend fun testTextConnection(settings: ProviderSettings): String {
        val image = TranslationImage("text-probe", 0, "", "image/png", 1, 1, "text-connection-probe", 0)
        val configured = tachiyomi.domain.translation.model.TranslationSettings(provider = settings)
        val request = TranslationRequest(
            "connection-test",
            UUID.randomUUID().toString(),
            configured.copy(ocr = configured.ocr.copy(pipeline = OcrPipeline.PADDLE)),
            listOf(image),
            listOf(tachiyomi.domain.translation.model.OcrPageResult(image.id, emptyList())),
            "Text-only connection diagnostic. The supplied OCR has no passages. " +
                "Return the page ID with an empty regions array.",
        )
        validateRequest(request)
        val body = TranslationWireFormat.requestBody(request)
        validateRequestBytes(body, settings)
        val response =
            execute(settings, body, "testConnectionTextSchema", request.jobId, request.batchId, request.settings.logs)
        recordUsage(response, request)
        val decoded = TranslationWireFormat.decode(response.body, request, response.headers["x-request-id"])
        if (decoded.pages.singleOrNull()?.imageId != image.id || decoded.pages.single().regions.isNotEmpty()) {
            throw TranslationException(
                TranslationFailureKind.CONTENT,
                "Connected, but the text-only structured response was invalid.",
            )
        }
        return "${settings.model} accepted text input and returned a valid structured page. " +
            "Use PaddleOCR for translation and text-only review. Image input and translation quality were not tested."
    }

    override suspend fun countTokens(request: TranslationRequest): Long? = withContext(Dispatchers.IO) {
        require(request.settings.mode != tachiyomi.domain.translation.model.TranslationMode.STRUCTURED_FILES) {
            "Structured imports do not dispatch provider requests"
        }
        requireGeometryNotPaused(request)
        validateInputModality(request.settings.provider, request.settings.ocr.pipeline != OcrPipeline.PADDLE)
        if (preparation == null ||
            request.settings.ocr.pipeline == OcrPipeline.PADDLE
        ) {
            return@withContext countTokensWire(request)
        }
        validateSettings(request.settings.provider)
        val prepared = preparation.prepare(request, capabilities(request.settings.provider))
        if (request.settings.mode == TranslationMode.HALVING) return@withContext countTokensWire(prepared.wire)
        val counts = physicalGroups(prepared.wire).mapNotNull { countTokensWire(it) }
        counts.maxOrNull()
    }

    private suspend fun countTokensWire(request: TranslationRequest): Long? {
        requireGeometryNotPaused(request)
        validateRequest(request)
        val generation = TranslationWireFormat.requestBody(request)
        validateRequestBytes(generation, request.settings.provider)
        if (!capabilities(request.settings.provider).supportsCountTokens) return null
        val identity = MessageDigest.getInstance("SHA-256").digest(
            (
                TranslationWireFormat.json.encodeToString(request.settings) + "\n" +
                    TranslationWireFormat.json.encodeToString(request.images) + "\n" +
                    TranslationWireFormat.json.encodeToString(request.ocr) + "\n" + request.context
                ).toByteArray(),
        ).joinToString("") { "%02x".format(it) }
        tokenCounts[identity]?.let { return it }
        val response = execute(
            request.settings.provider,
            TranslationWireFormat.requestBody(request, countOnly = true),
            "countTokens",
            request.jobId,
            request.batchId,
            request.settings.logs,
        )
        val field = if (request.settings.provider.kind ==
            TranslationProviderKind.OPENAI
        ) {
            "input_tokens"
        } else {
            "totalTokens"
        }
        val count = response.body[field]?.jsonPrimitive?.longOrNull
            ?: throw TranslationException(TranslationFailureKind.CONTENT, "Count Tokens did not return a token count.")
        if (tokenCounts.size >= 512) tokenCounts.keys.firstOrNull()?.let(tokenCounts::remove)
        tokenCounts[identity] = count
        return count
    }

    override suspend fun translate(request: TranslationRequest): TranslationResponse = withContext(Dispatchers.IO) {
        require(request.settings.mode != tachiyomi.domain.translation.model.TranslationMode.STRUCTURED_FILES) {
            "Structured imports do not dispatch provider requests"
        }
        requireGeometryNotPaused(request)
        // Capture before preparation, token counting and generation so edits during HTTP invalidate candidates.
        val sourceRevisions = if (geometryRecoveryEnabled(request)) {
            request.images.map { request.inputTransforms[it.id]?.original?.id ?: it.id }.distinct()
                .associateWith { repository.resultRevision(request.jobId, it) }
        } else {
            emptyMap()
        }
        validateInputModality(request.settings.provider, request.settings.ocr.pipeline != OcrPipeline.PADDLE)
        if (preparation == null ||
            request.settings.ocr.pipeline == OcrPipeline.PADDLE
        ) {
            return@withContext translateWire(request, sourceRevisions)
        }
        validateSettings(request.settings.provider)
        val prepared = preparation.prepare(request, capabilities(request.settings.provider))
        val results = prepared.tiles.mapNotNull { tile -> preparation.cached(prepared.wire, tile.image) }
            .associateByTo(mutableMapOf()) { it.imageId }
        preparationEvent(
            request,
            "PREPARED",
            "${request.images.size} source image(s) → ${prepared.tiles.size} PNG tile(s); ${results.size} " +
                "checkpoint(s) reused",
            prepared.warnings +
                prepared.tiles.map {
                    "${it.image.id}: origin=${it.rectangle.left},${it.rectangle.top}; " +
                        "source=${it.rectangle.width}x${it.rectangle.height}; " +
                        "upload=${it.image.width}x${it.image.height}; bytes=${it.image.byteSize}"
                },
        )
        val responses = mutableListOf<TranslationResponse>()
        var firstFailure: TranslationException? = null
        var terminalFailure: TranslationException? = null
        suspend fun executeGroup(group: TranslationRequest, path: String) {
            val pending = group.images.filter { it.id !in results }
            if (pending.isEmpty() || terminalFailure != null) return
            val actual = group.copy(
                images = pending,
                ocr = group.ocr.filter { page ->
                    pending.any {
                        it.id ==
                            page.imageId
                    }
                },
            )
            preparationEvent(
                request,
                "TILE_REQUEST",
                "Prepared tile request $path (${pending.size} images)",
                pending.map {
                    it.id
                },
            )
            try {
                val input = countTokensWire(actual)
                val limit = capabilities(actual.settings.provider).maxInputTokens
                if (input != null && limit > 0 &&
                    input > limit
                ) {
                    throw TranslationException(
                        TranslationFailureKind.LIMIT,
                        "Prepared tile request exceeds the model input token limit.",
                    )
                }
                // Commit each accepted tile before waiting for correction of another input.
                val response = translateWire(actual, sourceRevisions) { page ->
                    val image = actual.images.first { it.id == page.imageId }
                    preparation.checkpoint(prepared.wire, image, page)
                    results[page.imageId] = page
                }
                responses += response
                response.deferredFailure?.let { throw it }
                if (response.pages.size !=
                    pending.size
                ) {
                    throw TranslationException(
                        TranslationFailureKind.CONTENT,
                        "Prepared request returned incomplete tile coverage.",
                    )
                }
            } catch (error: TranslationException) {
                val unresolved = pending.filter { it.id !in results }
                val canSplit = error.kind in setOf(TranslationFailureKind.CONTENT, TranslationFailureKind.LIMIT) &&
                    unresolved.size > 1 &&
                    (request.settings.mode != TranslationMode.HALVING || request.images.size == 1)
                if (canSplit) {
                    val half = (unresolved.size + 1) / 2
                    preparationEvent(
                        request,
                        "TILE_SPLIT",
                        "$path failed: ${error.message}; split remaining tiles",
                        unresolved.map {
                            it.id
                        },
                    )
                    executeGroup(actual.copy(images = unresolved.take(half)), "$path.1")
                    executeGroup(actual.copy(images = unresolved.drop(half)), "$path.2")
                } else {
                    if (firstFailure == null) firstFailure = error
                    if (error.kind !in
                        setOf(TranslationFailureKind.CONTENT, TranslationFailureKind.LIMIT)
                    ) {
                        terminalFailure = error
                    }
                }
            }
        }
        val remaining = prepared.wire.copy(images = prepared.wire.images.filter { it.id !in results })
        if (remaining.images.isNotEmpty()) {
            val groups = if (request.settings.mode ==
                TranslationMode.HALVING
            ) {
                listOf(remaining)
            } else {
                physicalGroups(remaining)
            }
            groups.forEachIndexed { index, group -> executeGroup(group, "${request.batchId}.tile-${index + 1}") }
        }
        val pages = prepared.merge(results.values)
        if (pages.isEmpty()) {
            throw terminalFailure ?: firstFailure
                ?: TranslationException(TranslationFailureKind.CONTENT, "No source page has complete tile coverage.")
        }
        fun total(
            value: (TranslationUsage) -> Long?,
        ): Long? = responses.mapNotNull { value(it.usage) }.takeIf { it.isNotEmpty() }?.sum()
        TranslationResponse(
            pages,
            TranslationUsage(
                total {
                    it.inputTokens
                },
                total {
                    it.outputTokens
                },
                total { it.cachedTokens },
                total { it.reasoningTokens },
                responses.lastOrNull()?.usage?.trafficType,
            ),
            responses.lastOrNull()?.requestId,
            if (pages.size == request.images.size) "STOP" else "PARTIAL",
            deferredFailure = terminalFailure,
        )
    }

    private suspend fun physicalGroups(request: TranslationRequest): List<TranslationRequest> {
        if (request.settings.mode ==
            TranslationMode.VERTEX
        ) {
            return request.images.map { request.copy(images = listOf(it)) }
        }
        if (request.settings.mode ==
            TranslationMode.CUSTOM
        ) {
            return request.images.chunked(request.settings.customBatchSize).map { request.copy(images = it) }
        }
        if (request.settings.mode == TranslationMode.HALVING) return listOf(request)
        val caps = capabilities(request.settings.provider)
        val groups = mutableListOf<TranslationRequest>()
        var offset = 0
        while (offset < request.images.size) {
            var low = 1
            var high = minOf(caps.maxImages.takeIf { it > 0 } ?: request.images.size, request.images.size - offset)
            var best = 0
            while (low <= high) {
                val size = (low + high) / 2
                val candidate = request.copy(images = request.images.subList(offset, offset + size))
                val fits = try {
                    val count = countTokensWire(candidate)
                    count == null || caps.maxInputTokens <= 0 || count <= caps.maxInputTokens
                } catch (error: TranslationException) {
                    if (error.kind == TranslationFailureKind.LIMIT) false else throw error
                }
                if (fits) {
                    best = size
                    low = size + 1
                } else {
                    high = size - 1
                }
            }
            if (best ==
                0
            ) {
                throw TranslationException(
                    TranslationFailureKind.LIMIT,
                    "A prepared tile and prompt exceed the request budget.",
                )
            }
            groups += request.copy(images = request.images.subList(offset, offset + best))
            offset += best
        }
        return groups
    }

    private suspend fun preparationEvent(
        request: TranslationRequest,
        stage: String,
        message: String,
        details: List<String>,
    ) {
        if (request.settings.logs.enabled) {
            repository.addEvent(
                TranslationEvent(
                    UUID.randomUUID().toString(),
                    request.jobId,
                    request.batchId,
                    stage = stage,
                    message = message,
                    details = details.mapIndexed { index, value ->
                        index.toString() to value
                    }.toMap(),
                ),
            )
        }
    }

    private val geometryCorrections = GeometryCorrectionCoordinator(repository, ::correctGeometry)

    /** Explicit Resume reopens authentication pauses while keeping every consumed attempt. */
    suspend fun resumeGeometryCorrections(jobIds: Set<String>) {
        for (jobId in jobIds) {
            // Collect before mutating: state changes also reorder operation timestamps.
            val paused = mutableListOf<GeometryCorrectionCheckpoint>()
            var offset = 0L
            do {
                val page = repository.operationPage(
                    jobId,
                    limit = 100,
                    offset = offset,
                    stage = TranslationStage.GEOMETRY_CORRECTION,
                )
                for (operation in page) {
                    val checkpoint = operation.geometryCorrection ?: continue
                    if (checkpoint.state == GeometryCorrectionState.PAUSED) paused += checkpoint
                }
                offset += page.size
            } while (page.size == 100)
            paused.forEach { geometryCorrections.resumePaused(it) }
        }
    }

    private fun geometryCorrectionId(request: TranslationRequest, image: TranslationImage): String {
        val transform = request.inputTransforms[image.id] ?: TranslationInputTransform(image)
        transform.validate(image)
        val identity = listOf(
            request.geometryRecoveryId, image.id, image.contentHash, image.width, image.height,
            transform.original.id, transform.original.contentHash, transform.original.width, transform.original.height,
            transform.left, transform.top, transform.cropWidth, transform.cropHeight,
            transform.inputWidth, transform.inputHeight,
        )
            .joinToString("|")
        return "geometry:${request.jobId}:" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun geometryRecoveryEnabled(request: TranslationRequest): Boolean =
        request.settings.geometryRecovery.enabled && request.geometryRecoveryId != null &&
            request.settings.ocr.pipeline != OcrPipeline.PADDLE

    private suspend fun requireGeometryNotPaused(request: TranslationRequest) {
        if (!geometryRecoveryEnabled(request)) return
        var offset = 0L
        do {
            val page = repository.operationPage(
                request.jobId,
                limit = 100,
                offset = offset,
                stage = TranslationStage.GEOMETRY_CORRECTION,
                state = TranslationOperationState.PAUSED,
            )
            for (operation in page) {
                val checkpoint = operation.geometryCorrection ?: continue
                if (checkpoint.policyId != request.geometryRecoveryId ||
                    checkpoint.state != GeometryCorrectionState.PAUSED
                ) {
                    continue
                }
                val kind = checkpoint.attempts.lastOrNull()?.failureKind
                if (kind in setOf(TranslationFailureKind.AUTHENTICATION, TranslationFailureKind.CONFIGURATION)) {
                    val action = if (checkpoint.attempts.size < checkpoint.maxTransportAttempts) {
                        "Resume after correcting the configuration"
                    } else {
                        "Correct the configuration, then explicitly retry unfinished work to start a new pass"
                    }
                    throw TranslationException(
                        requireNotNull(kind),
                        "Geometry correction is paused for image ${checkpoint.transform.original.id}: " +
                            "${checkpoint.message ?: "check provider credentials and settings"}. " +
                            "$action; saved pages are preserved.",
                    )
                }
            }
            offset += page.size
        } while (page.size == 100)
    }

    private suspend fun translateWire(
        request: TranslationRequest,
        sourceRevisions: Map<String, Long?>,
        onPageAccepted: suspend (TranslationPageResult) -> Unit = {},
    ): TranslationResponse {
        if (!geometryRecoveryEnabled(request)) {
            return translateWireOnce(request).also { response ->
                response.pages.forEach { onPageAccepted(it) }
            }
        }
        requireGeometryNotPaused(request)
        validateRequest(request)
        val checkpoints = linkedMapOf<String, GeometryCorrectionCheckpoint>()
        request.images.forEach { image ->
            repository.geometryCorrection(geometryCorrectionId(request, image))?.let { checkpoints[image.id] = it }
        }
        val pending = request.images.filter { it.id !in checkpoints }
        val pages = mutableListOf<TranslationPageResult>()
        var usage = TranslationUsage()
        var requestId: String? = null
        var failure: TranslationException? = null
        if (pending.isNotEmpty()) {
            val actual = request.copy(
                images = pending,
                ocr = request.ocr.filter { ocr ->
                    pending.any {
                        it.id ==
                            ocr.imageId
                    }
                },
            )
            val body = TranslationWireFormat.requestBody(actual)
            validateRequestBytes(body, actual.settings.provider)
            val response =
                execute(
                    actual.settings.provider,
                    body,
                    "generateContent",
                    actual.jobId,
                    actual.batchId,
                    actual.settings.logs,
                )
            recordUsage(response, actual)
            val decoded = TranslationWireFormat.decodeGeneration(
                response.body,
                actual.settings.provider,
                response.headers["x-request-id"] ?: response.headers["x-goog-request-id"],
            )
            usage = decoded.usage
            requestId = decoded.requestId
            val report = TranslationGeometryDecoding.decode(decoded.payload, actual)
            report.pages.forEach { page ->
                onPageAccepted(page)
                pages += page
            }
            for (normalization in report.normalizations) {
                repository.addEvent(
                    TranslationEvent(
                        UUID.randomUUID().toString(),
                        request.jobId,
                        batchId = request.batchId,
                        imageId = normalization.imageId,
                        stage = "GEOMETRY_NORMALIZED",
                        message = "Removed redundant vertices from region ${normalization.regionId}; outline unchanged",
                        details = mapOf(
                            "regionId" to normalization.regionId,
                            "outcome" to TranslationWireFormat.json.encodeToString(normalization.outcome),
                        ),
                        operationId = request.batchId,
                    ),
                )
            }
            // Persist every rejected candidate before making any correction request.
            for (rejected in report.geometry) {
                currentCoroutineContext().ensureActive()
                val transform = request.inputTransforms[rejected.image.id] ?: TranslationInputTransform(rejected.image)
                val checkpoint = GeometryCorrectionCheckpoint(
                    id = geometryCorrectionId(request, rejected.image), jobId = request.jobId,
                    policyId = requireNotNull(request.geometryRecoveryId), image = rejected.image,
                    transform = transform, settings = request.settings,
                    ocr = request.ocr.filter { it.imageId == rejected.image.id }, context = request.context,
                    candidateJson = rejected.candidate.toString(), issues = rejected.issues, batchId = request.batchId,
                    sourceRevision = sourceRevisions[transform.original.id],
                )
                val persisted = repository.createGeometryCorrection(checkpoint)
                if (persisted != null) {
                    checkpoints[rejected.image.id] = persisted
                } else {
                    failure = TranslationException(
                        TranslationFailureKind.GEOMETRY,
                        "Geometry candidate became stale; saved results are preserved",
                    )
                }
            }
            val otherFailures = report.rejected.filterKeys { id -> report.geometry.none { it.image.id == id } }
            if (otherFailures.isNotEmpty()) {
                failure = TranslationException(
                    TranslationFailureKind.CONTENT,
                    otherFailures.entries.joinToString("; ") { "${it.key}: ${it.value}" },
                )
            }
            if (request.settings.logs.enabled) {
                repository.addEvent(
                    TranslationEvent(
                        UUID.randomUUID().toString(), request.jobId,
                        batchId = request.batchId, stage = "TRANSLATED",
                        level = if (report.rejected.isEmpty()) "INFO" else "WARN",
                        message = "Validated ${report.pages.size}/${pending.size} initial pages; " +
                            "${report.geometry.size} need geometry correction",
                        details =
                        mapOf(
                            "imageIds" to pending.joinToString { it.id },
                            "model" to request.settings.provider.model,
                            "unresolvedImageIds" to report.rejected.keys.joinToString(),
                        ) +
                            report.rejected.mapKeys { "rejectedPage:${it.key}" },
                        capturePath = response.capturePath, operationId = request.batchId,
                    ),
                )
            }
        }
        for ((imageId, checkpoint) in checkpoints) {
            currentCoroutineContext().ensureActive()
            val current = geometryCorrections.run(checkpoint)
            if (current?.state == GeometryCorrectionState.COMPLETED && current.result != null) {
                val result = requireNotNull(current.result)
                val expected = request.images.first { it.id == imageId }
                QualityReviewValidation.validatePage(result)
                if (result.imageId != expected.id || result.imageHash != expected.contentHash ||
                    result.width != expected.width || result.height != expected.height
                ) {
                    failure =
                        TranslationException(
                            TranslationFailureKind.GEOMETRY,
                            "Saved correction does not match the requested image",
                        )
                } else {
                    onPageAccepted(result)
                    pages += result
                }
            } else {
                val lastKind = current?.attempts?.lastOrNull()?.failureKind
                val kind = if (current?.state == GeometryCorrectionState.PAUSED &&
                    lastKind in setOf(TranslationFailureKind.AUTHENTICATION, TranslationFailureKind.CONFIGURATION)
                ) {
                    requireNotNull(lastKind)
                } else {
                    TranslationFailureKind.GEOMETRY
                }
                failure = TranslationException(
                    kind,
                    "Image $imageId needs geometry correction: ${current?.message ?: "source result or job changed"}",
                )
                if (kind == TranslationFailureKind.AUTHENTICATION || kind == TranslationFailureKind.CONFIGURATION) break
            }
        }
        if (pages.isEmpty()) {
            throw failure
                ?: TranslationException(TranslationFailureKind.CONTENT, "Response omitted all requested pages")
        }
        // Per-request accounting contains correction usage; this aggregate is unknown when a correction was involved.
        if (checkpoints.isNotEmpty()) usage = TranslationUsage()
        return TranslationResponse(
            pages.sortedBy { page -> request.images.first { it.id == page.imageId }.index },
            usage,
            requestId,
            if (failure == null) "STOP" else "PARTIAL",
            deferredFailure = failure,
        )
    }

    private suspend fun correctGeometry(
        checkpoint: GeometryCorrectionCheckpoint,
        attempt: GeometryCorrectionAttempt,
    ): TranslationPageResult {
        val initial = GeometryCorrectionWireFormat.request(checkpoint)
        validateRequest(initial)
        checkpoint.transform.validate(checkpoint.image)
        // Counting and generation use the same immutable input bytes and recorded transform.
        val snapshot = snapshotReviewImage(checkpoint.image, checkpoint.settings.provider, "geometry")
        try {
            val stable = checkpoint.copy(image = checkpoint.image.copy(filePath = snapshot.path))
            val wire = GeometryCorrectionWireFormat.request(stable)
            val body = GeometryCorrectionWireFormat.body(stable)
            validateRequestBytes(body, stable.settings.provider)
            val caps = capabilities(stable.settings.provider)
            val inputLimit = listOfNotNull(
                caps.maxInputTokens.takeIf {
                    it > 0
                },
                stable.settings.provider.customInputTokenLimit,
            ).minOrNull()
            if (caps.supportsCountTokens) {
                val countBody = GeometryCorrectionWireFormat.body(stable, countOnly = true)
                validateRequestBytes(countBody, stable.settings.provider)
                val counted = execute(
                    stable.settings.provider,
                    countBody,
                    "countTokens",
                    stable.jobId,
                    stable.id,
                    stable.settings.logs,
                    maxAttempts = 1,
                    attemptOffset = attempt.number - 1,
                )
                val key = if (stable.settings.provider.kind ==
                    TranslationProviderKind.OPENAI
                ) {
                    "input_tokens"
                } else {
                    "totalTokens"
                }
                val tokens = counted.body[key]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }
                    ?: throw TranslationException(
                        TranslationFailureKind.GEOMETRY,
                        "Geometry preflight returned no valid token count",
                    )
                if (inputLimit != null && tokens > inputLimit) {
                    throw TranslationException(
                        TranslationFailureKind.LIMIT,
                        "Geometry correction exceeds the input token limit; generation was not dispatched",
                    )
                }
            }
            val response = execute(
                stable.settings.provider,
                body,
                "geometryCorrection",
                stable.jobId,
                stable.id,
                stable.settings.logs,
                maxAttempts = 1,
                attemptOffset = attempt.number - 1,
            )
            recordUsage(response, wire)
            val decoded = TranslationWireFormat.decodeGeneration(
                response.body,
                stable.settings.provider,
                response.headers["x-request-id"] ?: response.headers["x-goog-request-id"],
            )
            repository.addEvent(
                TranslationEvent(
                    UUID.randomUUID().toString(), stable.jobId, batchId = stable.id,
                    imageId = stable.transform.original.id, stage = "GEOMETRY_CORRECTION_RESPONSE",
                    message = "Geometry correction response received; identity and shape validation pending",
                    details = mapOf(
                        "attempt" to attempt.number.toString(),
                        "inputImageId" to stable.image.id,
                        "inputHash" to stable.image.contentHash,
                        "originalHash" to stable.transform.original.contentHash,
                        "model" to stable.settings.provider.model,
                    ),
                    capturePath = response.capturePath, operationId = stable.id,
                ),
            )
            return GeometryCorrectionWireFormat.decode(decoded.payload, stable)
        } finally {
            snapshot.delete()
        }
    }

    private suspend fun translateWireOnce(request: TranslationRequest): TranslationResponse {
        validateRequest(request)
        val body = TranslationWireFormat.requestBody(request)
        validateRequestBytes(body, request.settings.provider)
        val response = execute(
            request.settings.provider,
            body,
            "generateContent",
            request.jobId,
            request.batchId,
            request.settings.logs,
        )
        recordUsage(response, request)
        val rejectedPages = mutableMapOf<String, String>()
        val translated = TranslationWireFormat.decode(
            response.body,
            request,
            response.headers["x-request-id"] ?: response.headers["x-goog-request-id"],
        ) { id, reason -> rejectedPages[id ?: "unknown"] = reason }
        if (request.settings.logs.enabled) {
            val unresolved = request.images.map { it.id }.toSet() - translated.pages.map { it.imageId }.toSet()
            repository.addEvent(
                TranslationEvent(
                    id = UUID.randomUUID().toString(),
                    jobId = request.jobId,
                    batchId = request.batchId,
                    level = if (unresolved.isEmpty()) "INFO" else "WARN",
                    stage = "TRANSLATED",
                    message =
                    "Translated ${translated.pages.size}/${request.images.size} image(s)" +
                        if (unresolved.isEmpty()) "" else "; missing or invalid pages need another request",
                    details = buildMap {
                        translated.requestId?.let { put("requestId", it) }
                        translated.finishReason?.let { put("finishReason", it) }
                        translated.usage.inputTokens?.let { put("inputTokens", it.toString()) }
                        translated.usage.outputTokens?.let { put("outputTokens", it.toString()) }
                        translated.usage.reasoningTokens?.let { put("reasoningTokens", it.toString()) }
                        translated.usage.cachedTokens?.let { put("cachedTokens", it.toString()) }
                        translated.usage.trafficType?.let { put("trafficType", it) }
                        put("provider", request.settings.provider.kind.name)
                        put("model", request.settings.provider.model)
                        put("imageIds", request.images.joinToString { it.id })
                        if (unresolved.isNotEmpty()) put("unresolvedImageIds", unresolved.joinToString())
                        rejectedPages.forEach { (id, reason) -> put("rejectedPage:$id", reason) }
                    },
                    capturePath = response.capturePath,
                ),
            )
        }
        return translated
    }

    private suspend fun recordUsage(
        response: ProviderHttpResponse,
        request: TranslationRequest,
        reviewId: String? = null,
    ) {
        val usage = TranslationWireFormat.decodeUsage(response.body, request.settings.provider)
        val estimate = OfficialProviderPricing.estimate(request.settings.provider, usage)
        val previous = repository.usage(response.attemptId)
        val uncertain = usage.inputTokens == null || usage.outputTokens == null
        repository.saveUsage(
            tachiyomi.domain.translation.model.TranslationUsageRecord(
                id = response.attemptId, jobId = request.jobId, operationId = response.attemptId,
                batchId = request.batchId,
                reviewId = reviewId ?: previous?.reviewId,
                requestId = response.headers["x-request-id"] ?: response.headers["x-goog-request-id"],
                provider = if (OfficialProviderCapabilities.isGroq(request.settings.provider)) {
                    "GROQ"
                } else {
                    request.settings.provider.kind.name
                },
                model = request.settings.provider.model,
                time = previous?.time ?: System.currentTimeMillis(),
                usage = usage,
                outcomeUncertain = uncertain,
                reservedCurrency = if (uncertain) previous?.reservedCurrency else null,
                reservedAmount = if (uncertain) previous?.reservedAmount else null,
                estimatedUsd = estimate?.estimatedUsd,
                pricingSource = estimate?.price?.sourceUrl ?: if (uncertain) previous?.pricingSource else null,
                pricingVerifiedAt = estimate?.price?.verifiedAt ?: if (uncertain) previous?.pricingVerifiedAt else null,
                exchangeRate = if (uncertain) previous?.exchangeRate else null,
                exchangeRateSource = if (uncertain) previous?.exchangeRateSource else null,
            ),
        )
        if (request.settings.logs.enabled) {
            repository.addEvent(
                TranslationEvent(
                    id = UUID.randomUUID().toString(),
                    jobId = request.jobId,
                    batchId = request.batchId,
                    stage = "USAGE",
                    operationId = response.attemptId,
                    message = estimate?.let { "Estimated USD ${it.estimatedUsd} for this response" }
                        ?: "Provider token usage; verified cost estimate unavailable",
                    details = buildMap {
                        usage.inputTokens?.let { put("inputTokens", it.toString()) }
                        usage.outputTokens?.let { put("outputTokens", it.toString()) }
                        usage.cachedTokens?.let { put("cachedTokens", it.toString()) }
                        usage.reasoningTokens?.let { put("reasoningTokens", it.toString()) }
                        usage.trafficType?.let { put("trafficType", it) }
                        put("model", request.settings.provider.model)
                        put("httpStatus", response.code.toString())
                        estimate?.let {
                            put("estimatedUsd", it.estimatedUsd)
                            put("beforePromotionalCreditUsd", it.beforePromotionalCreditUsd)
                            put("pricingVerifiedAt", it.price.verifiedAt)
                            put("pricingSource", it.price.sourceUrl)
                            put("priceEffectiveFrom", it.price.effectiveFrom)
                            it.price.effectiveThrough?.let { end -> put("priceEffectiveThrough", end) }
                            put(
                                "costNote",
                                if (it.price.promotionalCredit) {
                                    "Estimate includes 50% promotional credits back; excludes account " +
                                        "discounts, other credits and tax. HTTP 200 responses can incur charges " +
                                        "even when translation validation fails."
                                } else {
                                    "List-price estimate excludes account discounts, credits and tax. HTTP 200 " +
                                        "responses can incur charges even when translation validation fails."
                                },
                            )
                        }
                    },
                    capturePath = response.capturePath,
                ),
            )
        }
    }

    private suspend fun execute(
        settings: ProviderSettings,
        body: RequestBody,
        operation: String,
        jobId: String,
        batchId: String?,
        logs: TranslationLogSettings,
        maxAttempts: Int = settings.totalAttempts,
        attemptOffset: Int = 0,
    ): ProviderHttpResponse {
        var refreshed = false
        for (attempt in 1..maxAttempts) {
            try {
                val credential = try {
                    credentials(settings.credentialId)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    throw TranslationException(
                        TranslationFailureKind.AUTHENTICATION,
                        "Credential is missing or cannot be decrypted. Import it again in settings.",
                    )
                }
                val boundKey = settings.kind == TranslationProviderKind.VERTEX_SERVICE_ACCOUNT &&
                    settings.vertexAuthMode == VertexAuthMode.SERVICE_ACCOUNT_BOUND_KEY
                val token = when {
                    boundKey -> credential.apiKey.takeIf { credential.kind == CredentialKind.SERVICE_ACCOUNT_BOUND_KEY }
                        ?: throw TranslationException(
                            TranslationFailureKind.AUTHENTICATION,
                            "Import a service-account-bound authorization key " +
                                "for the selected Vertex authentication mode.",
                        )
                    settings.kind == TranslationProviderKind.VERTEX_SERVICE_ACCOUNT -> oauth.accessToken(credential)
                    else ->
                        credential.apiKey
                            ?: throw TranslationException(
                                TranslationFailureKind.AUTHENTICATION,
                                "Import an API key for this provider.",
                            )
                }
                val request = Request.Builder()
                    .tag(TranslationPromptDiagnostics::class.java, (body as? StreamingJsonBody)?.promptDiagnostics)
                    .url(endpoint(settings, credential.projectId, operation, token))
                    .post(if (maxAttempts == 1) body.singleTransportAttempt() else body)
                    .header("Accept", "application/json")
                    .header("X-Client-Request-Id", UUID.randomUUID().toString())
                    .apply {
                        settings.extraHeaders.forEach { (key, value) -> header(key, value) }
                        if (boundKey) {
                            header("x-goog-api-key", token)
                        } else if (settings.kind !=
                            TranslationProviderKind.VERTEX_EXPRESS
                        ) {
                            header("Authorization", "Bearer $token")
                        }
                        if (settings.kind == TranslationProviderKind.OPENAI) {
                            if (settings.organization.isNotBlank()) header("OpenAI-Organization", settings.organization)
                            if (settings.openAiProject.isNotBlank()) header("OpenAI-Project", settings.openAiProject)
                        } else {
                            header(
                                "X-Vertex-AI-LLM-Request-Type",
                                if (settings.provisionedThroughput) "dedicated" else "shared",
                            )
                            if (settings.priorityPaygo) header("X-Vertex-AI-LLM-Shared-Request-Type", "priority")
                        }
                    }
                    .build()
                return http.execute(
                    request,
                    jobId,
                    batchId,
                    operation,
                    logs,
                    attempt + attemptOffset,
                    settings.timeoutSeconds,
                    listOfNotNull(token, credential.apiKey, credential.privateKey),
                    settings = settings,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failure = when (error) {
                    is TranslationException -> error
                    is IOException -> TranslationException(
                        TranslationFailureKind.TRANSIENT,
                        "Network request failed. Check your connection.",
                        cause = error,
                    )
                    else -> TranslationException(
                        TranslationFailureKind.CONFIGURATION,
                        "Unable to build or execute provider request.",
                        cause = error,
                    )
                }
                val refresh =
                    failure.httpStatus == 401 && settings.kind == TranslationProviderKind.VERTEX_SERVICE_ACCOUNT &&
                        settings.vertexAuthMode == VertexAuthMode.SERVICE_ACCOUNT_JSON &&
                        !refreshed
                if (refresh) {
                    refreshed = true
                    oauth.invalidate(settings.credentialId)
                }
                val retryable =
                    refresh ||
                        failure.kind in setOf(TranslationFailureKind.RATE_LIMIT, TranslationFailureKind.TRANSIENT)
                if (!retryable || attempt == maxAttempts) throw failure
                val backoff = retryDelay(settings, attempt, failure.retryAfterMillis)
                if (logs.enabled) {
                    repository.addEvent(
                        TranslationEvent(
                            id = UUID.randomUUID().toString(),
                            jobId = jobId,
                            batchId = batchId,
                            level = "WARN",
                            stage = "RETRY",
                            message = "${failure.kind}: retry ${attempt + 1}/$maxAttempts in $backoff ms",
                            details = mapOf(
                                "delayMillis" to backoff.toString(),
                                "httpStatus" to failure.httpStatus.toString(),
                            ),
                        ),
                    )
                }
                delay(backoff)
            }
        }
        error("Validated attempt count must be positive")
    }

    private fun validateRequest(
        request: TranslationRequest,
        inputImages: List<TranslationImage> = request.images,
    ) {
        validateSettings(request.settings.provider)
        if (request.images.isEmpty()) {
            throw TranslationException(
                TranslationFailureKind.CONFIGURATION,
                "Select images to translate.",
            )
        }
        if (request.images.distinctBy { it.id }.size != request.images.size) {
            throw TranslationException(TranslationFailureKind.CONFIGURATION, "Each image must have a unique ID.")
        }
        val caps = capabilities(request.settings.provider)
        val vision = request.settings.ocr.pipeline != OcrPipeline.PADDLE
        validateInputModality(request.settings.provider, vision)
        if (vision && request.settings.provider.kind == TranslationProviderKind.OPENAI &&
            request.settings.provider.openAiImageDetail !in
            OfficialProviderCapabilities.imageDetails(request.settings.provider)
        ) {
            throw TranslationException(
                TranslationFailureKind.CONFIGURATION,
                "Image detail is unsupported by this provider.",
            )
        }
        if (vision && caps.maxImages > 0 && inputImages.size > caps.maxImages) {
            throw TranslationException(
                TranslationFailureKind.LIMIT,
                "This model supports at most ${caps.maxImages} images per request.",
            )
        }
        inputImages.forEach { image ->
            if (image.width <= 0 ||
                image.height <= 0
            ) {
                throw TranslationException(TranslationFailureKind.CONTENT, "Image dimensions are invalid.")
            }
            if (vision) {
                val file = File(image.filePath)
                if (!file.isFile ||
                    file.length() == 0L
                ) {
                    throw TranslationException(TranslationFailureKind.STORAGE, "Source image is missing or empty.")
                }
                if (caps.maxInlineImageBytes > 0 && file.length() > caps.maxInlineImageBytes) {
                    throw TranslationException(
                        TranslationFailureKind.LIMIT,
                        "An image exceeds the model's ${caps.maxInlineImageBytes} byte inline limit. Resize or " +
                            "tile it before translation.",
                    )
                }
                val mimeTypes = if (request.settings.provider.kind == TranslationProviderKind.OPENAI) {
                    setOf("image/png", "image/jpeg", "image/webp", "image/gif")
                } else {
                    setOf("image/png", "image/jpeg", "image/webp", "image/heic", "image/heif")
                }
                if (image.mimeType !in
                    mimeTypes
                ) {
                    throw TranslationException(
                        TranslationFailureKind.CONTENT,
                        "Unsupported image format: ${image.mimeType}",
                    )
                }
            }
        }
        if (request.settings.ocr.pipeline != OcrPipeline.AI &&
            request.images.any { image -> request.ocr.none { it.imageId == image.id } }
        ) {
            throw TranslationException(
                TranslationFailureKind.CONTENT,
                "The OCR pipeline has not produced all requested pages.",
            )
        }
    }

    private fun validateRequestBytes(body: RequestBody, settings: ProviderSettings) {
        val limit = capabilities(settings).maxRequestBytes ?: return
        if (body.contentLength() > limit) {
            throw TranslationException(
                TranslationFailureKind.LIMIT,
                "Serialized request exceeds the $limit byte request budget.",
            )
        }
    }

    private fun validateCredentialScope(settings: ProviderSettings) {
        if (settings.credentialId.startsWith("billing-")) {
            throw TranslationException(
                TranslationFailureKind.CONFIGURATION,
                "Billing administration credentials cannot be used for inference. " +
                    "Choose a separate translation credential.",
            )
        }
    }

    private fun validateInputModality(settings: ProviderSettings, visual: Boolean) {
        validateCredentialScope(settings)
        if (visual && !capabilities(settings).supportsVision) {
            throw TranslationException(
                TranslationFailureKind.CONFIGURATION,
                "${settings.model} accepts text only. Choose PaddleOCR for translation and text-only review. " +
                    "Existing queued settings and saved translations are unchanged.",
            )
        }
    }

    private fun validateSettings(settings: ProviderSettings) {
        validateCredentialScope(settings)
        fun requireSetting(condition: Boolean, message: String) {
            if (!condition) throw TranslationException(TranslationFailureKind.CONFIGURATION, message)
        }
        requireSetting(settings.model.isNotBlank(), "Choose a provider model.")
        requireSetting(settings.credentialId.matches(Regex("[A-Za-z0-9_-]{1,80}")), "Choose a credential.")
        requireSetting(settings.timeoutSeconds in 10..3600, "Request timeout must be 10–3600 seconds.")
        requireSetting(settings.totalAttempts in 1..10, "Total attempts must be 1–10.")
        requireSetting(
            settings.initialRetryMillis >= 0 && settings.maxRetryMillis in settings.initialRetryMillis..3_600_000,
            "Invalid retry delays.",
        )
        requireSetting((settings.maxRequestBytes ?: 1L) > 0, "Request byte budget must be positive.")
        requireSetting(
            settings.openAiImageDetail in setOf("auto", "low", "high", "original"),
            "Image detail must be auto, low, high or original.",
        )
        requireSetting(
            settings.imageTileLongEdge in 256..16384 && settings.imageTileMaxPixels in 65536..16000000 &&
                settings.imageTileOverlap in 0..1024,
            "Invalid upload tile limits.",
        )
        settings.maxOutputTokens?.let {
            val ceiling = capabilities(settings).maxOutputTokens
            requireSetting(
                it > 0 && (ceiling == 0 || it <= ceiling),
                "Output token limit exceeds the selected model's capability.",
            )
        }
        val protected =
            setOf(
                "authorization",
                "proxy-authorization",
                "cookie",
                "host",
                "content-length",
                "content-type",
                "x-goog-api-key",
                "x-vertex-ai-llm-request-type",
                "x-vertex-ai-llm-shared-request-type",
            )
        requireSetting(
            settings.extraHeaders.keys.none {
                it.lowercase() in protected
            },
            "Use the dedicated credential and capacity settings instead of overriding reserved headers.",
        )
        if (settings.kind != TranslationProviderKind.OPENAI) {
            requireSetting(settings.model.matches(Regex("[A-Za-z0-9_.:@-]+")), "Vertex model ID is invalid.")
            if (settings.kind == TranslationProviderKind.VERTEX_SERVICE_ACCOUNT) {
                requireSetting(settings.location.matches(Regex("[a-z0-9-]+")), "Vertex location is invalid.")
                if (settings.model == OfficialProviderCapabilities.GEMINI_MODEL) {
                    requireSetting(
                        settings.location in setOf("global", "us", "eu"),
                        "Gemini 3.8 Flash supports global, us and eu locations.",
                    )
                }
                requireSetting(
                    !settings.priorityPaygo || settings.location == "global",
                    "Priority PayGo requires global location.",
                )
            }
            requireSetting(
                !(settings.priorityPaygo && settings.provisionedThroughput),
                "Choose Priority PayGo or dedicated Provisioned Throughput.",
            )
            requireSetting(
                settings.kind != TranslationProviderKind.VERTEX_EXPRESS || !settings.provisionedThroughput,
                "Use standard Vertex project authentication for dedicated throughput.",
            )
        }
    }

    internal fun endpoint(
        settings: ProviderSettings,
        credentialProject: String?,
        operation: String,
        apiKey: String,
    ): HttpUrl {
        if (settings.kind == TranslationProviderKind.OPENAI) {
            val base = settings.baseUrl.trimEnd('/').toHttpUrlOrNull()
                ?: throw TranslationException(TranslationFailureKind.CONFIGURATION, "Enter a valid provider base URL.")
            val localFixture = localFixturesEnabled && base.scheme == "http" && base.host == "127.0.0.1" &&
                apiKey == "mihon-fixture-only" && settings.model == "mihon-fixture" &&
                settings.organization.isBlank() && settings.openAiProject.isBlank() &&
                settings.extraHeaders.keys.all { it == "X-Mihon-Fixture-Scenario" }
            if ((!base.isHttps && !localFixture) || base.username.isNotBlank() ||
                base.password.isNotBlank() || base.query != null ||
                base.fragment != null
            ) {
                throw TranslationException(
                    TranslationFailureKind.CONFIGURATION,
                    "Provider base URL must use HTTPS without embedded credentials, query or fragment.",
                )
            }
            return base.newBuilder().addPathSegment(
                if (settings.dialect ==
                    OpenAiDialect.RESPONSES
                ) {
                    "responses"
                } else {
                    "chat"
                },
            )
                .apply {
                    if (settings.dialect == OpenAiDialect.CHAT_COMPLETIONS) {
                        addPathSegment("completions")
                    } else if (operation == "countTokens") {
                        addPathSegment("input_tokens")
                    }
                }.build()
        }
        val method = if (operation == "countTokens") "countTokens" else "generateContent"
        if (settings.kind == TranslationProviderKind.VERTEX_EXPRESS) {
            return HttpUrl.Builder().scheme("https").host("aiplatform.googleapis.com")
                .addPathSegments("v1/publishers/google/models").addPathSegment("${settings.model}:$method")
                .addQueryParameter("key", apiKey).build()
        }
        val project = settings.projectId.ifBlank { credentialProject.orEmpty() }
        if (!project.matches(Regex("[a-z][a-z0-9-]{4,61}[a-z0-9]|[0-9]+"))) {
            throw TranslationException(TranslationFailureKind.CONFIGURATION, "Set a valid Google Cloud project ID.")
        }
        return HttpUrl.Builder().scheme("https")
            .host(
                if (settings.location ==
                    "global"
                ) {
                    "aiplatform.googleapis.com"
                } else {
                    "${settings.location}-aiplatform.googleapis.com"
                },
            )
            .addPathSegment("v1").addPathSegment("projects").addPathSegment(project)
            .addPathSegment("locations").addPathSegment(settings.location).addPathSegments("publishers/google/models")
            .addPathSegment("${settings.model}:$method").build()
    }

    private companion object {
        /** Stops OkHttp follow-ups such as 503/Retry-After:0 from silently consuming a second paid attempt. */
        fun RequestBody.singleTransportAttempt(): RequestBody = object : RequestBody() {
            override fun contentType() = this@singleTransportAttempt.contentType()
            override fun contentLength() = this@singleTransportAttempt.contentLength()
            override fun writeTo(sink: BufferedSink) = this@singleTransportAttempt.writeTo(sink)
            override fun isOneShot() = true
        }

        fun isolatedClient(base: OkHttpClient): OkHttpClient = base.newBuilder().apply {
            // DoH/TLS configuration is retained; reader cookies, challenge handlers and HTTP logging are not.
            interceptors().clear()
            networkInterceptors().clear()
        }
            .cache(null)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()

        fun retryDelay(settings: ProviderSettings, attempt: Int, retryAfter: Long?): Long {
            val exponential = (settings.initialRetryMillis * 2.0.pow(attempt - 1)).toLong()
                .coerceAtMost(settings.maxRetryMillis)
            val jitter = if (exponential > 0) Random.nextLong(exponential / 2 + 1) else 0
            return maxOf((exponential + jitter).coerceAtMost(settings.maxRetryMillis), retryAfter ?: 0)
        }
    }
}
