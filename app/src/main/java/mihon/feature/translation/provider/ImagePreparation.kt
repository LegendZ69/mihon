package mihon.feature.translation.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.ProviderCapabilities
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationRequest
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

internal data class UploadRectangle(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width get() = right - left
    val height get() = bottom - top
}

internal object UploadTileGeometry {
    fun tiles(
        width: Int,
        height: Int,
        edge: Int,
        pixels: Int,
        overlap: Int,
    ): List<UploadRectangle> {
        require(width > 0 && height > 0 && edge > 0 && pixels > 0 && overlap >= 0)
        val tileWidth = minOf(width, edge, pixels)
        val tileHeight = minOf(height, edge, max(1, pixels / tileWidth))
        return buildList {
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val right = min(width, x + tileWidth)
                    val bottom = min(height, y + tileHeight)
                    add(UploadRectangle(x, y, right, bottom))
                    if (right == width) break
                    x += max(1, tileWidth - min(overlap, tileWidth / 4))
                }
                if (y + tileHeight >= height) break
                y += max(1, tileHeight - min(overlap, tileHeight / 4))
            }
        }
    }

    fun halves(
        rect: UploadRectangle,
        overlap: Int,
    ): List<UploadRectangle> {
        require(rect.width > 1 || rect.height > 1)
        return if (rect.height >= rect.width && rect.height > 1) {
            val middle = rect.top + rect.height / 2
            val margin = min(overlap, rect.height / 8)
            listOf(rect.copy(bottom = middle + margin), rect.copy(top = middle - margin))
        } else {
            val middle = rect.left + rect.width / 2
            val margin = min(overlap, rect.width / 8)
            listOf(rect.copy(right = middle + margin), rect.copy(left = middle - margin))
        }
    }
}

internal data class PreparedImageTile(
    val original: TranslationImage,
    val image: TranslationImage,
    val rectangle: UploadRectangle,
) {
    fun localX(x: Float) = (x - rectangle.left) * image.width / rectangle.width

    fun localY(y: Float) = (y - rectangle.top) * image.height / rectangle.height

    fun toOriginal(point: TranslationPoint) =
        TranslationPoint(
            rectangle.left + point.x * rectangle.width / image.width,
            rectangle.top + point.y * rectangle.height / image.height,
        )
}

internal class PreparedTranslationRequest(
    val original: TranslationRequest,
    val tiles: List<PreparedImageTile>,
    val warnings: List<String> = emptyList(),
) {
    val wire: TranslationRequest =
        original.copy(
            images = tiles.map { it.image },
            inputTransforms = tiles.associate { tile ->
                tile.image.id to tachiyomi.domain.translation.model.TranslationInputTransform(
                    tile.original,
                    tile.rectangle.left,
                    tile.rectangle.top,
                    tile.rectangle.width,
                    tile.rectangle.height,
                    tile.image.width,
                    tile.image.height,
                )
            },
            ocr =
            tiles.mapNotNull { tile ->
                original.ocr.firstOrNull { it.imageId == tile.original.id }?.let { page ->
                    page.copy(
                        imageId = tile.image.id,
                        regions =
                        page.regions
                            .filter { region ->
                                region.points.isNotEmpty() && region.points.maxOf {
                                    it.x
                                } > tile.rectangle.left &&
                                    region.points.minOf { it.x } < tile.rectangle.right &&
                                    region.points.maxOf { it.y } > tile.rectangle.top &&
                                    region.points.minOf { it.y } < tile.rectangle.bottom
                            }.map { region ->
                                region.copy(
                                    points =
                                    region.points.map { point ->
                                        TranslationPoint(
                                            tile.localX(point.x).coerceIn(
                                                0f,
                                                tile.image.width.toFloat(),
                                            ),
                                            tile.localY(point.y).coerceIn(
                                                0f,
                                                tile.image.height.toFloat(),
                                            ),
                                        )
                                    },
                                )
                            },
                    )
                }
            },
        )

    fun merge(pages: Collection<TranslationPageResult>): List<TranslationPageResult> {
        val byId = pages.associateBy { it.imageId }
        val hybrid = original.settings.ocr.pipeline == OcrPipeline.PADDLE_AI
        return original.images.mapNotNull { image ->
            val imageTiles = tiles.filter { it.original.id == image.id }
            if (imageTiles.any { it.image.id !in byId }) return@mapNotNull null
            val suppliedByQualifiedId = mutableMapOf<String, TextRegion>()
            val sourceOcr = original.ocr.firstOrNull { it.imageId == image.id }
            val sourceById = sourceOcr?.regions.orEmpty().associateBy { it.id }
            val candidates =
                imageTiles.flatMap { tile ->
                    byId.getValue(tile.image.id).regions.sortedBy { it.readingOrder }.map { region ->
                        sourceById[region.id]?.let { suppliedByQualifiedId["${tile.image.id}:${region.id}"] = it }
                        region.copy(
                            id = "${tile.image.id}:${region.id}",
                            points = region.points.map(tile::toOriginal),
                        )
                    }
                }
            val deduplicated = mutableListOf<TextRegion>()
            candidates.sortedByDescending { area(it) }.forEach { candidate ->
                val duplicate =
                    deduplicated.any { existing ->
                        val sameOcrRegion =
                            suppliedByQualifiedId[existing.id]?.id?.let {
                                it == suppliedByQualifiedId[candidate.id]?.id
                            } ==
                                true
                        val sameText =
                            normalized(existing.sourceText) == normalized(candidate.sourceText) ||
                                normalized(existing.translatedText).let {
                                    it.isNotEmpty() &&
                                        it == normalized(candidate.translatedText)
                                }
                        sameOcrRegion || (sameText && overlap(existing, candidate) >= 0.5f)
                    }
                if (!duplicate) deduplicated += candidate
            }
            val order = candidates.withIndex().associate { it.value.id to it.index }
            val regions =
                deduplicated.sortedBy { order[it.id] }.mapIndexed { index, region ->
                    val supplied = suppliedByQualifiedId[region.id]
                    region.copy(
                        id = supplied?.id ?: "${image.id}:region-$index",
                        readingOrder = if (hybrid) index else supplied?.readingOrder ?: index,
                        sourceText = supplied?.sourceText ?: region.sourceText,
                        points = if (hybrid) region.points else supplied?.points ?: region.points,
                        detectionConfidence = supplied?.detectionConfidence ?: region.detectionConfidence,
                        recognitionConfidence = supplied?.recognitionConfidence ?: region.recognitionConfidence,
                        style = supplied?.style ?: region.style,
                    )
                }
            TranslationPageResult(
                image.id,
                image.contentHash,
                image.width,
                image.height,
                regions,
                sourceOcr,
                imageTiles.firstNotNullOfOrNull { byId.getValue(it.image.id).detectedLanguage },
            )
        }
    }

    private fun normalized(text: String) = text.filterNot(Char::isWhitespace).lowercase()

    private fun area(region: TextRegion): Float =
        if (region.points.isEmpty()) {
            0f
        } else {
            (region.points.maxOf { it.x } - region.points.minOf { it.x }) *
                (region.points.maxOf { it.y } - region.points.minOf { it.y })
        }

    private fun overlap(
        first: TextRegion,
        second: TextRegion,
    ): Float {
        if (first.points.isEmpty() || second.points.isEmpty()) return 0f
        val width =
            min(first.points.maxOf { it.x }, second.points.maxOf { it.x }) -
                max(first.points.minOf { it.x }, second.points.minOf { it.x })
        val height =
            min(first.points.maxOf { it.y }, second.points.maxOf { it.y }) -
                max(first.points.minOf { it.y }, second.points.minOf { it.y })
        return max(0f, width) * max(0f, height) / min(area(first), area(second)).coerceAtLeast(1f)
    }
}

/** PNG upload copies contain raw source pixels and no EXIF orientation metadata. */
internal class ImagePreparation(
    private val directory: File,
) {
    private val mutex = Mutex()
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    suspend fun prepare(
        request: TranslationRequest,
        capabilities: ProviderCapabilities,
    ): PreparedTranslationRequest =
        mutex.withLock {
            if (request.settings.ocr.pipeline == OcrPipeline.PADDLE) {
                return@withLock PreparedTranslationRequest(
                    request,
                    request.images.map {
                        PreparedImageTile(it, it, UploadRectangle(0, 0, it.width, it.height))
                    },
                )
            }
            val settings = request.settings.provider
            val budget =
                min(
                    settings.imageTileMaxPixels.toLong(),
                    request.settings.concurrency.decodedMemoryMb * 1024L * 1024 / 16,
                ).toInt()
            val edge = if (settings.imagePreparationEnabled) settings.imageTileLongEdge else Int.MAX_VALUE
            val policyPixels =
                if (settings.imagePreparationEnabled) {
                    budget
                } else {
                    (request.settings.concurrency.decodedMemoryMb * 1024L * 1024 / 16)
                        .coerceAtMost(
                            Int.MAX_VALUE.toLong(),
                        ).toInt()
                }
            val warnings = mutableListOf<String>()
            val result = mutableListOf<PreparedImageTile>()
            try {
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create prepared image cache." }
                for (source in request.images) {
                    currentCoroutineContext().ensureActive()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(source.filePath, bounds)
                    if (bounds.outWidth != source.width || bounds.outHeight != source.height) {
                        throw TranslationException(
                            TranslationFailureKind.CONTENT,
                            "Source dimensions changed or the image cannot be decoded.",
                        )
                    }
                    @Suppress("DEPRECATION")
                    val decoder =
                        try {
                            BitmapRegionDecoder.newInstance(source.filePath, false)
                        } catch (
                            _: IOException,
                        ) {
                            null
                        }
                    var fallback: Bitmap? = null
                    try {
                        if (decoder == null) {
                            var sample = 1
                            while ((source.width.toLong() / sample) * (source.height.toLong() / sample) >
                                policyPixels
                            ) {
                                sample *= 2
                            }
                            fallback = BitmapFactory.decodeFile(
                                source.filePath,
                                BitmapFactory.Options().apply {
                                    inSampleSize = sample
                                    inPreferredConfig = Bitmap.Config.ARGB_8888
                                },
                            )
                                ?: throw TranslationException(
                                    TranslationFailureKind.CONTENT,
                                    "Image format cannot be decoded for upload.",
                                )
                            if (sample >
                                1
                            ) {
                                warnings +=
                                    "${source.id}: decoder lacks region support; upload sampled by $sample to fit " +
                                    "device memory."
                            }
                        }

                        suspend fun prepareRectangle(rect: UploadRectangle) {
                            currentCoroutineContext().ensureActive()
                            val identity =
                                digest(
                                    "png-v1:${source.contentHash}:${rect.left}:${rect.top}:" +
                                        "${rect.right}:${rect.bottom}:$policyPixels:${decoder == null}",
                                )
                            val target = File(directory, "$identity.png")
                            var width = rect.width
                            var height = rect.height
                            if (!target.isFile || target.length() <= 0) {
                                val bitmap =
                                    if (decoder != null) {
                                        decoder.decodeRegion(
                                            Rect(rect.left, rect.top, rect.right, rect.bottom),
                                            BitmapFactory.Options().apply {
                                                inPreferredConfig =
                                                    Bitmap.Config.ARGB_8888
                                            },
                                        )
                                    } else {
                                        val original = checkNotNull(fallback)
                                        val left = rect.left.toLong() * original.width / source.width
                                        val top = rect.top.toLong() * original.height / source.height
                                        val right =
                                            max(
                                                left + 1,
                                                rect.right.toLong() * original.width / source.width,
                                            ).coerceAtMost(original.width.toLong())
                                        val bottom =
                                            max(
                                                top + 1,
                                                rect.bottom.toLong() * original.height / source.height,
                                            ).coerceAtMost(original.height.toLong())
                                        Bitmap.createBitmap(
                                            original,
                                            left.toInt().coerceAtMost(original.width - 1),
                                            top.toInt().coerceAtMost(original.height - 1),
                                            (right - left).toInt(),
                                            (bottom - top).toInt(),
                                        )
                                    }
                                        ?: throw TranslationException(
                                            TranslationFailureKind.CONTENT,
                                            "Cannot decode an upload tile.",
                                        )
                                val temporary = File(directory, "$identity.tmp")
                                try {
                                    width = bitmap.width
                                    height = bitmap.height
                                    temporary.outputStream().buffered().use {
                                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                                    }
                                    check(temporary.renameTo(target)) { "Cannot save upload tile." }
                                } finally {
                                    temporary.delete()
                                    if (bitmap !== fallback) bitmap.recycle()
                                }
                            } else {
                                val tileBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(target.absolutePath, tileBounds)
                                width = tileBounds.outWidth
                                height = tileBounds.outHeight
                                if (width <= 0 || height <= 0) {
                                    target.delete()
                                    prepareRectangle(rect)
                                    return
                                }
                            }
                            val tile =
                                TranslationImage(
                                    "${source.id}~${rect.left}_${rect.top}_${rect.width}_${rect.height}",
                                    result.size,
                                    target.absolutePath,
                                    "image/png",
                                    width,
                                    height,
                                    digest(target),
                                    target.length(),
                                )
                            val mapped = PreparedImageTile(source, tile, rect)
                            val exceedsRequest =
                                capabilities.maxRequestBytes?.let { limit ->
                                    val wire =
                                        PreparedTranslationRequest(
                                            request.copy(images = listOf(source)),
                                            listOf(mapped),
                                        ).wire
                                    val bytes = TranslationWireFormat.requestBody(wire).contentLength()
                                    val imageBytes = ((target.length() + 2) / 3) * 4
                                    if (bytes - imageBytes >= limit) {
                                        throw TranslationException(
                                            TranslationFailureKind.LIMIT,
                                            "The prompt and schema exceed the configured request byte budget before " +
                                                "image data.",
                                        )
                                    }
                                    bytes > limit
                                } ?: false
                            val exceedsInline =
                                capabilities.maxInlineImageBytes > 0 &&
                                    target.length() > capabilities.maxInlineImageBytes
                            if (exceedsInline ||
                                exceedsRequest
                            ) {
                                target.delete()
                                if (rect.width <= 1 &&
                                    rect.height <= 1
                                ) {
                                    throw TranslationException(
                                        TranslationFailureKind.LIMIT,
                                        "Provider inline image limit is smaller than a PNG pixel.",
                                    )
                                }
                                UploadTileGeometry.halves(rect, settings.imageTileOverlap).forEach {
                                    prepareRectangle(it)
                                }
                            } else {
                                result += mapped
                            }
                        }
                        UploadTileGeometry
                            .tiles(source.width, source.height, edge, policyPixels, settings.imageTileOverlap)
                            .forEach { prepareRectangle(it) }
                    } finally {
                        decoder?.recycle()
                        fallback?.recycle()
                    }
                }
                PreparedTranslationRequest(request, result, warnings)
            } catch (error: IOException) {
                throw TranslationException(
                    TranslationFailureKind.STORAGE,
                    "Cannot prepare upload images. Check device storage.",
                    cause = error,
                )
            } catch (error: IllegalStateException) {
                throw TranslationException(
                    TranslationFailureKind.STORAGE,
                    "Cannot save prepared image data.",
                    cause = error,
                )
            }
        }

    fun checkpointFile(
        request: TranslationRequest,
        image: TranslationImage,
    ): File = checkpointFile(request, image, json.encodeToString(request.settings))

    private fun checkpointFile(request: TranslationRequest, image: TranslationImage, settingsJson: String): File {
        val key =
            digest(
                settingsJson + request.context +
                    json.encodeToString(request.ocr.filter { it.imageId == image.id }) + image.contentHash + image.id,
            )
        return File(File(directory, "results-${digest(request.jobId)}"), "$key.json")
    }

    suspend fun clearCheckpoints(jobId: String) =
        mutex.withLock {
            val folder = File(directory, "results-${digest(jobId)}")
            if (folder.exists() &&
                !folder.deleteRecursively()
            ) {
                throw TranslationException(TranslationFailureKind.STORAGE, "Cannot remove translated tile checkpoints.")
            }
        }

    fun cached(
        request: TranslationRequest,
        image: TranslationImage,
    ): TranslationPageResult? =
        runCatching {
            fun read(file: File, expectedHash: String): TranslationPageResult? = runCatching {
                if (!file.isFile || file.length() > 16L * 1024 * 1024) return@runCatching null
                json.decodeFromString<TranslationPageResult>(file.readText()).takeIf {
                    it.imageId == image.id && it.imageHash == expectedHash && it.width == image.width &&
                        it.height == image.height
                }
            }.getOrNull()

            read(checkpointFile(request, image), image.contentHash)?.let { return@runCatching it }

            // v10 identified PNG copies by their preparation recipe and had no geometryRecovery setting.
            // Read only the bounded legacy keys for this exact owned tile; preserve the old cache verbatim.
            val source = File(image.filePath)
            val recipe = source.nameWithoutExtension
            if (source.parentFile?.canonicalFile != directory.canonicalFile || source.extension != "png" ||
                !recipe.matches(Regex("[a-f0-9]{64}")) || !source.isFile || source.length() != image.byteSize
            ) {
                return@runCatching null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            if (bounds.outWidth != image.width || bounds.outHeight != image.height ||
                digest(source) != image.contentHash
            ) {
                return@runCatching null
            }

            val settings = json.encodeToJsonElement(request.settings).jsonObject
            val policies = listOf(settings.toString(), JsonObject(settings - "geometryRecovery").toString()).distinct()
            for (policy in policies) {
                for (hash in listOf(image.contentHash, recipe).distinct()) {
                    val legacy = read(checkpointFile(request, image.copy(contentHash = hash), policy), hash) ?: continue
                    val restored = legacy.copy(imageHash = image.contentHash)
                    // Storage pressure must not turn a validated completed tile into another paid request.
                    runCatching { checkpoint(request, image, restored) }
                    return@runCatching restored
                }
            }
            null
        }.getOrNull()

    fun checkpoint(
        request: TranslationRequest,
        image: TranslationImage,
        page: TranslationPageResult,
    ) {
        val target = checkpointFile(request, image)
        val temporary = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            temporary.writeText(json.encodeToString(page))
            check(temporary.renameTo(target))
        } catch (error: IOException) {
            throw TranslationException(
                TranslationFailureKind.STORAGE,
                "Cannot save translated tile checkpoint.",
                cause = error,
            )
        } catch (error: IllegalStateException) {
            throw TranslationException(
                TranslationFailureKind.STORAGE,
                "Cannot create translated tile checkpoint.",
                cause = error,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun digest(value: String) =
        MessageDigest
            .getInstance(
                "SHA-256",
            ).digest(value.toByteArray())
            .joinToString("") {
                "%02x".format(it)
            }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
