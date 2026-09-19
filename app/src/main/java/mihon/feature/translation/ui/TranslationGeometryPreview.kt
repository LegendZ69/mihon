package mihon.feature.translation.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.feature.translation.overlay.TranslationOverlayDocument
import mihon.feature.translation.overlay.needsTranslationBackgroundSamples
import mihon.feature.translation.overlay.sampleTranslationBackgrounds
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.RegionLayoutDiagnostic
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import java.io.File
import kotlin.math.hypot

/** Inspector-only editor: reader renderers and original-image storage remain unchanged. */
@Composable
internal fun TranslationGeometryPreview(
    image: TranslationImage,
    result: TranslationPageResult,
    style: OverlayStyle,
    contentPolicy: TranslationContentPolicy = TranslationContentPolicy(),
    source: Boolean,
    boxes: Boolean,
    masks: Boolean,
    translations: Boolean,
    zoom: Float,
    selectedRegionId: String?,
    onZoom: (Float) -> Unit,
    onSelect: (String) -> Unit,
    onCorner: (String, Int, TranslationPoint) -> Unit,
    onLayout: (List<RegionLayoutDiagnostic>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preview by produceState<GeometryBitmap?>(null, image.filePath, image.contentHash) {
        value = null
        var decoded: Bitmap? = null
        try {
            decoded = withContext(Dispatchers.IO) {
                var sample = 1
                while ((image.width.toLong() / sample).coerceAtLeast(1) *
                    (image.height.toLong() / sample).coerceAtLeast(1) >
                    2L * 1024 * 1024 ||
                    image.width / sample > 4096 || image.height / sample > 4096
                ) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    image.filePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                ).also { decoded = it } ?: error("Image format cannot be previewed by the platform decoder")
            }
            value = GeometryBitmap(decoded)
        } catch (error: CancellationException) {
            decoded?.recycle()
            throw error
        } catch (error: Exception) {
            decoded?.recycle()
            value = GeometryBitmap(error = error.message ?: "Image preview unavailable")
        }
    }
    val document by produceState<TranslationOverlayDocument?>(
        null,
        image.contentHash,
        result,
        style,
        contentPolicy,
    ) {
        value = null
        if (result.imageHash == image.contentHash) {
            value = withContext(Dispatchers.Default) {
                fun visible(chosen: OverlayStyle) = chosen.copy(
                    enabled = true,
                    showBoxes = false,
                    showCoordinates = false,
                    showConfidence = false,
                    showReadingOrder = false,
                )
                val sampled = if (needsTranslationBackgroundSamples(result, style)) {
                    withContext(Dispatchers.IO) {
                        sampleTranslationBackgrounds({ File(image.filePath).inputStream() }, result, style)
                    }
                } else {
                    emptyMap()
                }
                val copy = result.copy(regions = result.regions.map { it.copy(style = visible(it.style ?: style)) })
                TranslationOverlayDocument.create(context, copy, visible(style), sampled, contentPolicy)
            }
        }
    }
    LaunchedEffect(document) { onLayout(document?.layoutDiagnostics.orEmpty()) }
    val bitmap = preview?.bitmap
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            preview == null -> CircularProgressIndicator()
            bitmap == null -> Text(preview?.error.orEmpty())
            else -> AndroidView(
                factory = { GeometryPreviewView(it) },
                update = {
                    it.bind(
                        bitmap, document, result, source, boxes, masks, translations, zoom,
                        selectedRegionId, onZoom, onSelect, onCorner,
                    )
                },
                onReset = null,
                onRelease = { it.clear() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private data class GeometryBitmap(val bitmap: Bitmap? = null, val error: String? = null)

internal class GeometryPreviewView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bitmap: Bitmap? = null
    private var document: TranslationOverlayDocument? = null
    private var result: TranslationPageResult? = null
    private var source = true
    private var boxes = true
    private var masks = true
    private var translations = true
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var selected: String? = null
    private var onZoom: (Float) -> Unit = {}
    private var onSelect: (String) -> Unit = {}
    private var onCorner: (String, Int, TranslationPoint) -> Unit = { _, _, _ -> }
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    private var corner = -1
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val detector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, 32f)
                corner = -1
                dragged = true
                constrainPan()
                onZoom(zoom)
                invalidate()
                return true
            }
        },
    )

    init {
        isClickable = true
        contentDescription =
            "Original-coordinate region editor. Pinch to zoom, drag to pan, or drag a selected corner. " +
            "Coordinate fields provide an alternative."
    }

    fun bind(
        bitmap: Bitmap,
        document: TranslationOverlayDocument?,
        result: TranslationPageResult,
        source: Boolean,
        boxes: Boolean,
        masks: Boolean,
        translations: Boolean,
        zoom: Float,
        selected: String?,
        onZoom: (Float) -> Unit,
        onSelect: (String) -> Unit,
        onCorner: (String, Int, TranslationPoint) -> Unit,
    ) {
        if (this.result?.imageId != result.imageId || this.result?.imageHash != result.imageHash) {
            panX = 0f
            panY = 0f
        }
        this.bitmap = bitmap
        this.document = document
        this.result = result
        this.source = source
        this.boxes = boxes
        this.masks = masks
        this.translations = translations
        this.zoom = zoom.coerceIn(1f, 32f)
        this.selected = selected
        this.onZoom = onZoom
        this.onSelect = onSelect
        this.onCorner = onCorner
        constrainPan()
        invalidate()
    }

    fun clear() {
        bitmap = null
        document = null
        result = null
        onZoom = {}
        onSelect = {}
        onCorner = { _, _, _ -> }
    }

    private fun scale(): Float {
        val page = result ?: return 1f
        return minOf(width.toFloat() / page.width, height.toFloat() / page.height).coerceAtLeast(0.0001f) * zoom
    }

    private fun left() = (width - (result?.width ?: 1) * scale()) / 2f + panX
    private fun top() = (height - (result?.height ?: 1) * scale()) / 2f + panY

    private fun constrainPan() {
        val page = result ?: return
        val limitX = ((page.width * scale() - width) / 2f).coerceAtLeast(0f)
        val limitY = ((page.height * scale() - height) / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-limitX, limitX)
        panY = panY.coerceIn(-limitY, limitY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val page = result ?: return
        canvas.save()
        // AndroidView can supply a larger host clip. Contain every draw before applying page transforms.
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawColor(Color.DKGRAY)
        canvas.translate(left(), top())
        canvas.scale(scale(), scale())
        canvas.clipRect(0f, 0f, page.width.toFloat(), page.height.toFloat())
        canvas.drawColor(Color.WHITE)
        if (source) {
            bitmap?.takeUnless { it.isRecycled }?.let {
                canvas.drawBitmap(it, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), paint)
            }
        }
        document?.draw(canvas, drawMasks = masks, drawTranslations = translations)
        if (boxes) {
            page.regions.forEach { region ->
                if (region.points.isEmpty()) return@forEach
                val path = Path().apply {
                    moveTo(region.points.first().x, region.points.first().y)
                    region.points.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                line.color = if (region.id == selected) {
                    Color.MAGENTA
                } else if (region.included) {
                    Color.BLUE
                } else {
                    Color.GRAY
                }
                line.strokeWidth = 2f * resources.displayMetrics.density / scale()
                canvas.drawPath(path, line)
                if (region.id == selected) {
                    region.points.forEach { point ->
                        handle.color = Color.MAGENTA
                        canvas.drawCircle(point.x, point.y, 7f * resources.displayMetrics.density / scale(), handle)
                    }
                }
            }
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val page = result ?: return false
        parent?.requestDisallowInterceptTouchEvent(true)
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragged = false
                val points = page.regions.firstOrNull { it.id == selected }?.points.orEmpty()
                corner = if (boxes) {
                    points.indices.minByOrNull { index ->
                        val point = points[index]
                        hypot(event.x - left() - point.x * scale(), event.y - top() - point.y * scale())
                    }?.takeIf { index ->
                        val point = points[index]
                        hypot(event.x - left() - point.x * scale(), event.y - top() - point.y * scale()) <=
                            24f * resources.displayMetrics.density
                    } ?: -1
                } else {
                    -1
                }
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !detector.isInProgress) {
                if (hypot(event.x - downX, event.y - downY) > touchSlop) dragged = true
                if (dragged) {
                    if (corner >= 0 && selected != null) {
                        onCorner(
                            selected!!,
                            corner,
                            TranslationPoint(
                                (event.x - left()) / scale(),
                                (event.y - top()) / scale(),
                            ),
                        )
                    } else {
                        panX += event.x - lastX
                        panY += event.y - lastY
                        constrainPan()
                        invalidate()
                    }
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged) {
                    performClick()
                    val x = (event.x - left()) / scale()
                    val y = (event.y - top()) / scale()
                    page.regions.filter { region ->
                        region.points.isNotEmpty() && x in region.points.minOf { it.x }..region.points.maxOf { it.x } &&
                            y in region.points.minOf { it.y }..region.points.maxOf { it.y }
                    }.minByOrNull { region ->
                        (region.points.maxOf { it.x } - region.points.minOf { it.x }) *
                            (region.points.maxOf { it.y } - region.points.minOf { it.y })
                    }?.let { onSelect(it.id) }
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                corner = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                corner = -1
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
