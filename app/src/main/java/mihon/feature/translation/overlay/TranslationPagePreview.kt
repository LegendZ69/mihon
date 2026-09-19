package mihon.feature.translation.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import java.io.File

/** Inspector preview uses the same source-space layout as every reader, without a reader DB model. */
@Composable
fun TranslationPagePreview(
    image: TranslationImage,
    result: TranslationPageResult,
    style: OverlayStyle,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preview by produceState<Preview?>(null, image.filePath, image.contentHash) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                Preview(bitmap = decodePreview(image))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Preview(error = error.message ?: "Unable to preview this image")
            }
        }
    }
    val document by produceState<TranslationOverlayDocument?>(null, result, style) {
        value =
            if (!style.enabled || result.imageHash != image.contentHash) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    val backgrounds = if (needsTranslationBackgroundSamples(result, style)) {
                        withContext(Dispatchers.IO) {
                            sampleTranslationBackgrounds({ File(image.filePath).inputStream() }, result, style)
                        }
                    } else {
                        emptyMap()
                    }
                    TranslationOverlayDocument.create(context, result, style, backgrounds)
                }
            }
    }
    val bitmap = preview?.bitmap
    DisposableEffect(bitmap) {
        onDispose { bitmap?.recycle() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            preview == null -> CircularProgressIndicator()
            bitmap == null -> Text(preview?.error.orEmpty())
            else -> AndroidView(
                factory = { PreviewView(it) },
                update = { it.bind(bitmap, document, image.width, image.height) },
                onReset = null,
                onRelease = { it.clear() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private data class Preview(val bitmap: Bitmap? = null, val error: String? = null)

/** At most 2 Mi pixels (8 MiB ARGB), including extremely tall strips. */
private fun decodePreview(image: TranslationImage): Bitmap {
    val maxPixels = 2L * 1024 * 1024
    var sample = 1
    while ((image.width.toLong() / sample).coerceAtLeast(1) * (image.height.toLong() / sample).coerceAtLeast(1) >
        maxPixels ||
        image.width / sample > 4096 || image.height / sample > 4096
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    BitmapFactory.decodeFile(image.filePath, options)?.let { return it }
    // The platform decoder applies the target size during decode. The reader's custom Coil
    // decoder first allocates a full image, so it is intentionally not used for this preview.
    return android.graphics.ImageDecoder.decodeBitmap(
        android.graphics.ImageDecoder.createSource(File(image.filePath)),
    ) {
            decoder,
            info,
            _,
        ->
        val scale = minOf(
            1.0,
            4096.0 / maxOf(info.size.width, info.size.height),
            kotlin.math.sqrt(maxPixels.toDouble() / (info.size.width.toLong() * info.size.height)),
        )
        decoder.setTargetSize(
            (info.size.width * scale).toInt().coerceAtLeast(1),
            (info.size.height * scale).toInt().coerceAtLeast(1),
        )
        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
    }
}

private class PreviewView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    private var document: TranslationOverlayDocument? = null
    private var sourceWidth = 1
    private var sourceHeight = 1

    fun bind(bitmap: Bitmap, document: TranslationOverlayDocument?, width: Int, height: Int) {
        this.bitmap = bitmap
        this.document = document
        sourceWidth = width.coerceAtLeast(1)
        sourceHeight = height.coerceAtLeast(1)
        invalidate()
    }

    fun clear() {
        bitmap = null
        document = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap?.takeUnless { it.isRecycled } ?: return
        val scale = minOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val left = (width - sourceWidth * scale) / 2f
        val top = (height - sourceHeight * scale) / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat())
        canvas.drawBitmap(image, null, RectF(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat()), paint)
        document?.draw(canvas)
        canvas.restore()
    }
}
