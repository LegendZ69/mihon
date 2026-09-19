package mihon.feature.translation.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mihon.feature.translation.overlay.TranslationOverlayDocument
import mihon.feature.translation.overlay.needsTranslationBackgroundSamples
import mihon.feature.translation.overlay.sampleTranslationBackgrounds
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import java.io.File
import java.security.MessageDigest

/** Original dimensions and geometry remain fixed while each band is decoded, painted and released. */
object AndroidTranslationPageRaster {
    @Suppress("DEPRECATION")
    suspend fun open(
        context: Context,
        image: TranslationImage,
        result: TranslationPageResult,
        style: OverlayStyle,
        contentPolicy: TranslationContentPolicy,
    ): TranslationPageRaster = withContext(Dispatchers.IO) {
        require(
            image.id == result.imageId && image.contentHash == result.imageHash && image.width == result.width &&
                image.height == result.height,
        ) { "Export translation belongs to a different original" }
        val file = File(image.filePath)
        if (!file.isFile) throw MissingTranslationOriginalException("Original file is unavailable for page ${image.id}")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != image.contentHash) {
            throw MissingTranslationOriginalException("Original content changed for page ${image.id}")
        }
        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        }.getOrDefault(ExifInterface.ORIENTATION_UNDEFINED)
        if (orientation !in setOf(ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED)) {
            throw MissingTranslationOriginalException(
                "Original EXIF transform is not supported for export: page ${image.id}",
            )
        }
        val decoder = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(file.path)
            } else {
                BitmapRegionDecoder.newInstance(file.path, false)
            }
        } catch (
            error: Exception,
        ) {
            throw MissingTranslationOriginalException("Original format cannot be decoded for page ${image.id}")
        }
            ?: throw MissingTranslationOriginalException("Original format cannot be decoded for page ${image.id}")
        try {
            if (decoder.width != image.width || decoder.height != image.height || image.width !in 1..65536) {
                throw MissingTranslationOriginalException("Original dimensions differ for page ${image.id}")
            }
            val sampled = if (needsTranslationBackgroundSamples(result, style)) {
                sampleTranslationBackgrounds({
                    file.inputStream()
                }, result, style)
            } else {
                emptyMap()
            }
            val document =
                withContext(Dispatchers.Default) {
                    TranslationOverlayDocument.create(context, result, style, sampled, contentPolicy)
                }
            object : TranslationPageRaster {
                override val width = image.width
                override val height = image.height
                override suspend fun rows(
                    top: Int,
                    count: Int,
                    consume: suspend (pixels: IntArray, rows: Int) -> Unit,
                ) {
                    require(top >= 0 && count >= 0 && top.toLong() + count <= height)
                    var sourceTop = top
                    val maximumRows = (2 * 1024 * 1024 / width).coerceAtLeast(1)
                    while (sourceTop < top + count) {
                        currentCoroutineContext().ensureActive()
                        val rows = minOf(maximumRows, top + count - sourceTop)
                        val decoded =
                            decoder.decodeRegion(
                                Rect(0, sourceTop, width, sourceTop + rows),
                                BitmapFactory.Options().apply {
                                    inPreferredConfig = Bitmap.Config.ARGB_8888
                                    inMutable = true
                                    inSampleSize = 1
                                },
                            )
                                ?: throw MissingTranslationOriginalException(
                                    "Original band could not be decoded for page ${image.id}",
                                )
                        val bitmap = if (decoded.isMutable) {
                            decoded
                        } else {
                            try {
                                checkNotNull(decoded.copy(Bitmap.Config.ARGB_8888, true))
                            } finally {
                                decoded.recycle()
                            }
                        }
                        try {
                            require(bitmap.width == width && bitmap.height == rows) {
                                "Decoder changed export band dimensions"
                            }
                            Canvas(bitmap).apply {
                                translate(0f, -sourceTop.toFloat())
                                document.draw(this)
                            }
                            val pixels = IntArray(width * rows)
                            bitmap.getPixels(pixels, 0, width, 0, 0, width, rows)
                            consume(pixels, rows)
                        } finally {
                            bitmap.recycle()
                        }
                        sourceTop += rows
                    }
                }
                override fun close() {
                    decoder.recycle()
                }
            }
        } catch (failure: Throwable) {
            decoder.recycle()
            throw failure
        }
    }
}
