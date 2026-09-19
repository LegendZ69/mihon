package mihon.feature.translation.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.SystemClock
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRRunResult
import com.paddle.ocr.util.OpenCVUtils
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrSettings
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.service.OcrEngine
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** One native session pair at a time bounds OCR memory independently of cloud request concurrency. */
@SingleIn(AppScope::class)
class PaddleOcrEngine private constructor(
    context: Context,
    private val modelManager: PaddleModelManager,
    private val operationLogFactory: (PaddleOperationContext) -> PaddleOperationLog,
) : OcrEngine {
    @Inject
    constructor(context: Context, modelManager: PaddleModelManager, repository: TranslationRepository) :
        this(context, modelManager, { PaddleOperationLog(repository, it) })

    constructor(context: Context, modelManager: PaddleModelManager) :
        this(context, modelManager, { PaddleOperationLog(null, it) })

    private val context = context.applicationContext
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }
    private var engine: PaddleOCR? = null
    private var engineSettings: OcrSettings? = null
    private var models: InstalledPaddleModels? = null
    private var engineMemoryMb: Int? = null

    override suspend fun recognize(image: TranslationImage, settings: OcrSettings): OcrPageResult =
        recognize(image, settings, 128)

    suspend fun recognize(image: TranslationImage, settings: OcrSettings, decodedMemoryMb: Int): OcrPageResult =
        recognize(image, settings, decodedMemoryMb, null)

    suspend fun recognize(
        image: TranslationImage,
        settings: OcrSettings,
        decodedMemoryMb: Int,
        operationContext: PaddleOperationContext?,
    ): OcrPageResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val operationLog = operationContext?.let(operationLogFactory)
                try {
                    validate(settings)
                    require(decodedMemoryMb in 32..1024) { "OCR decoded memory budget must be 32–1024 MiB" }
                    val tilePixelBudget = minOf(
                        PaddleOcrGeometry.MAX_TILE_PIXELS.toLong(),
                        decodedMemoryMb * 1024L * 1024L / 64L,
                    ).toInt()
                    require(image.width > 0 && image.height > 0) { "OCR requires original image dimensions" }
                    require(File(image.filePath).isFile) { "OCR source image is missing" }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(image.filePath, bounds)
                    require(bounds.outWidth == image.width && bounds.outHeight == image.height) {
                        "OCR source dimensions changed; reacquire the source image"
                    }
                    val started = SystemClock.elapsedRealtime()
                    val previousEngine = engine
                    val acquireOperation = operationLog?.begin(
                        TranslationStage.PREPROCESS,
                        total = 1,
                        message = "Acquire OCR engine and verified model pack",
                    )
                    val instance = try {
                        acquire(
                            settings,
                            decodedMemoryMb,
                            tilePixelBudget,
                            operationContext?.copy(parentId = acquireOperation?.id ?: operationContext.parentId),
                        ).also {
                            acquireOperation?.complete(
                                details = mapOf(
                                    "engineReused" to (it === previousEngine).toString(),
                                    "measured.coldLoad.millis" to
                                        (if (it === previousEngine) 0 else it.coldLoadTimeMs).toString(),
                                ),
                            )
                        }
                    } catch (error: Throwable) {
                        acquireOperation?.failed(error)
                        throw error
                    }
                    val activeModels = checkNotNull(models)
                    val tiles = PaddleOcrGeometry.tiles(
                        image.width,
                        image.height,
                        settings.tileOverlap,
                        tilePixelBudget,
                    )
                    val rawRegions = mutableListOf<TextRegion>()
                    val stageTimes = mutableMapOf<String, Long>()
                    val shapeDetails = mutableListOf<String>()
                    var decoder: BitmapRegionDecoder? = null
                    try {
                        @Suppress("DEPRECATION")
                        decoder = BitmapRegionDecoder.newInstance(image.filePath, false)
                    } catch (e: IOException) {
                        if (tiles.size > 1) throw IOException("This image format cannot be safely tiled for OCR", e)
                    }
                    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    try {
                        if (decoder != null) {
                            require(decoder.width == image.width && decoder.height == image.height) {
                                "OCR source dimensions changed; reacquire the source image"
                            }
                        }
                        for ((tileIndex, tile) in tiles.withIndex()) {
                            currentCoroutineContext().ensureActive()
                            val decode = operationLog?.begin(
                                TranslationStage.PREPROCESS,
                                total = 1,
                                message = "Decode original tile ${tileIndex + 1}/${tiles.size}",
                            )
                            val decodeStart = SystemClock.elapsedRealtime()
                            val bitmap = try {
                                checkNotNull(
                                    decoder?.decodeRegion(Rect(tile.left, tile.top, tile.right, tile.bottom), options)
                                        ?: if (tiles.size ==
                                            1
                                        ) {
                                            BitmapFactory.decodeFile(image.filePath, options)
                                        } else {
                                            null
                                        },
                                ) { "Unable to decode OCR image region" }
                            } catch (error: Throwable) {
                                decode?.failed(error)
                                throw error
                            }
                            stageTimes.addTime("decode", SystemClock.elapsedRealtime() - decodeStart)
                            try {
                                decode?.complete(
                                    details = mapOf(
                                        "measured.decode.millis" to
                                            (SystemClock.elapsedRealtime() - decodeStart).toString(),
                                        "sourceWindow" to "${tile.left},${tile.top},${tile.right},${tile.bottom}",
                                        "decodedWidth" to bitmap.width.toString(),
                                        "decodedHeight" to bitmap.height.toString(),
                                    ),
                                )
                                require(bitmap.width.toLong() * bitmap.height <= tilePixelBudget) {
                                    "Decoded OCR image exceeds the working memory limit"
                                }
                                val observer = operationLog?.let { PaddleOcrProgress(it, tileIndex, tiles.size) }
                                val result = try {
                                    instance.recognize(bitmap, observer)
                                } catch (error: Throwable) {
                                    observer?.failed(error)
                                    throw error
                                }
                                stageTimes.add(result)
                                shapeDetails +=
                                    "tile=$tileIndex window=${tile.left},${tile.top},${tile.right},${tile.bottom}" +
                                    " det=${result.detInputShape} rec=${result.recInputShapes}" +
                                    " perLineMs=${result.perLineRecMs}"
                                for ((index, region) in result.results.withIndex()) {
                                    if (region.text.isBlank()) continue
                                    val points = PaddleOcrGeometry.restore(
                                        region.box.points.map { TranslationPoint(it.x, it.y) },
                                        tile,
                                        bitmap.width,
                                        bitmap.height,
                                    )
                                    rawRegions += TextRegion(
                                        id = "${image.id}:$tileIndex:$index",
                                        points = points,
                                        sourceText = region.text,
                                        rotation = PaddleOcrGeometry.rotation(points),
                                        detectionConfidence = region.box.detectionConfidence,
                                        recognitionConfidence = region.confidence,
                                        included = region.confidence >= settings.recognitionThreshold,
                                        ignoredReason = if (region.confidence <
                                            settings.recognitionThreshold
                                        ) {
                                            "Below OCR recognition threshold"
                                        } else {
                                            null
                                        },
                                    )
                                    require(rawRegions.size <= 100000) { "OCR page exceeds the region count limit" }
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    } finally {
                        decoder?.recycle()
                    }
                    stageTimes["total"] = SystemClock.elapsedRealtime() - started
                    stageTimes["cold_load"] = if (instance === previousEngine) 0L else instance.coldLoadTimeMs
                    val ordered = PaddleOcrGeometry.order(
                        PaddleOcrGeometry.deduplicate(rawRegions),
                        settings.readingOrder,
                        settings.language,
                    )
                    OcrPageResult(
                        imageId = image.id,
                        regions = ordered,
                        detectorModel = activeModels.detectorId,
                        recognizerModel = activeModels.recognizerId,
                        timingsMillis = stageTimes,
                        rawJson = buildJsonObject {
                            put("engine", "onnxruntime-android:1.29.0")
                            put("opencv", "5.0.0")
                            put("modelRevision", activeModels.revision)
                            put("coordinateSpace", "original_image_pixels")
                            put("imageWidth", image.width)
                            put("imageHeight", image.height)
                            put("settings", json.parseToJsonElement(json.encodeToString(settings)))
                            put("rawRegions", json.parseToJsonElement(json.encodeToString(rawRegions)))
                            put(
                                "tiles",
                                buildJsonArray {
                                    shapeDetails.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                                },
                            )
                            put("orientation", "perspective_rectification_and_optional_tall_crop_rotation")
                            put("localWorkers", 1)
                            put("decodedMemoryBudgetMb", decodedMemoryMb)
                            put("maxDecodedTilePixels", tilePixelBudget)
                            put("maxDetectorWorkingPixels", tilePixelBudget * 2)
                            put("maxRecognitionBatchNormalizedPixels", 48 * 3200)
                        }.toString(),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TranslationException) {
                    throw e
                } catch (e: IllegalArgumentException) {
                    throw TranslationException(
                        TranslationFailureKind.CONFIGURATION,
                        e.message ?: "Invalid OCR configuration",
                        cause = e,
                    )
                } catch (e: IOException) {
                    throw TranslationException(
                        TranslationFailureKind.STORAGE,
                        e.message ?: "OCR model or image I/O failed",
                        cause = e,
                    )
                } catch (e: UnsatisfiedLinkError) {
                    throw TranslationException(
                        TranslationFailureKind.CONFIGURATION,
                        "PaddleOCR native runtime could not load on this device: ${e.message}",
                        cause = e,
                    )
                } catch (e: Exception) {
                    throw TranslationException(
                        TranslationFailureKind.CONTENT,
                        e.message ?: "PaddleOCR inference failed",
                        cause = e,
                    )
                }
            }
        }

    private suspend fun acquire(
        settings: OcrSettings,
        decodedMemoryMb: Int,
        tilePixelBudget: Int,
        operationContext: PaddleOperationContext?,
    ): PaddleOCR {
        if (engineSettings == settings && engineMemoryMb == decodedMemoryMb) engine?.let { return it }
        releaseLocked()
        if (!OpenCVUtils.init(context)) {
            throw TranslationException(
                TranslationFailureKind.CONFIGURATION,
                "OpenCV could not initialize on this device",
            )
        }
        val installed = modelManager.ensureModels(settings.profile, settings.language, operationContext)
        val instance = PaddleOCR.create(
            context = context,
            config = PaddleOCRConfig(
                detLimitSideLen = settings.detectorSideLimit,
                detLimitType = settings.detectorLimitType,
                detMaxSideLimit = settings.detectorMaxSide,
                detThresh = settings.detectorThreshold,
                detBoxThresh = settings.boxThreshold,
                detUnclipRatio = settings.unclipRatio,
                detMaxCandidates = settings.maxCandidates,
                detUseDilation = settings.dilation,
                detScoreMode = settings.scoreMode,
                // Retain unfiltered OCR for diagnostics; exclusion is represented in the result instead.
                recScoreThresh = 0f,
                recBatchSize = settings.recognitionBatchSize,
                rotateTallCrops = settings.detectOrientation,
                maxDetectorPixels = tilePixelBudget * 2,
            ),
            engineConfig = EngineConfig(numThreads = settings.cpuThreads),
            detModelAssetPath = installed.detector.absolutePath,
            recModelAssetPath = installed.recognizer.absolutePath,
            recConfigAssetPath = installed.dictionary.absolutePath,
        )
        models = installed
        engineSettings = settings
        engineMemoryMb = decodedMemoryMb
        engine = instance
        return instance
    }

    override suspend fun release() {
        mutex.withLock { releaseLocked() }
    }

    private suspend fun releaseLocked() = withContext(NonCancellable + Dispatchers.IO) {
        try {
            engine?.release()
        } finally {
            engine = null
            engineSettings = null
            engineMemoryMb = null
            models = null
        }
    }

    private fun validate(settings: OcrSettings) {
        require(settings.detectorSideLimit > 0 && settings.detectorMaxSide >= 32)
        require(settings.detectorLimitType in setOf("min", "max", "resize_long"))
        require(settings.detectorThreshold in 0f..1f && settings.boxThreshold in 0f..1f)
        require(settings.recognitionThreshold in 0f..1f)
        require(settings.unclipRatio.isFinite() && settings.unclipRatio > 0)
        require(settings.maxCandidates in 1..100000)
        require(settings.recognitionBatchSize in 1..200)
        require(settings.cpuThreads in 1..64)
        require(settings.scoreMode in setOf("fast", "slow"))
        require(settings.tileOverlap in 0..256)
    }

    private fun MutableMap<String, Long>.addTime(stage: String, millis: Long) {
        this[stage] = (this[stage] ?: 0L) + millis
    }

    private fun MutableMap<String, Long>.add(result: OCRRunResult) {
        addTime("detection", result.detectionTimeMs)
        addTime("recognition", result.recognitionTimeMs)
        addTime("det_preprocess", result.detPreprocessMs)
        addTime("det_inference", result.detInferenceMs)
        addTime("det_postprocess", result.detPostprocessMs)
        addTime("rec_preprocess", result.recPreprocessMs)
        addTime("rec_inference", result.recInferenceMs)
        addTime("rec_postprocess", result.recPostprocessMs)
        addTime("pipeline_overhead", result.pipelineOverheadMs)
    }
}
