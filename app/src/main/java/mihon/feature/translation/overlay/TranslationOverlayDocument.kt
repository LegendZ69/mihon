package mihon.feature.translation.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.ColorUtils
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.renderer.ImageOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.RegionLayoutDiagnostic
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationPageResult
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** One immutable layout shared by Canvas and GPU viewers. Fonts/layout are never recomputed in onDraw. */
class TranslationOverlayDocument private constructor(
    val result: TranslationPageResult,
    private val regions: List<RegionLayout>,
    val contentPolicy: TranslationContentPolicy,
) {
    val layoutDiagnostics: List<RegionLayoutDiagnostic> = regions.mapNotNull { it.diagnostic }

    private class RegionLayout(
        val bounds: RectF,
        val polygon: Path,
        val style: OverlayStyle,
        val layout: StaticLayout?,
        val textFrame: OverlayTextFrame?,
        val textPaint: TextPaint,
        val backgroundPaint: Paint,
        val debugPaint: Paint,
        val debugText: String,
        val rotation: Float,
        val included: Boolean,
        val diagnostic: RegionLayoutDiagnostic?,
        val offsetX: Float,
        val offsetY: Float,
    )

    fun draw(canvas: Canvas, drawMasks: Boolean = true, drawTranslations: Boolean = true) {
        val clip = Rect()
        canvas.getClipBounds(clip)
        regions.forEach { region ->
            if (!RectF.intersects(region.bounds, RectF(clip))) return@forEach
            drawRegion(canvas, region, drawMasks, drawTranslations)
        }
    }

    private fun drawRegion(
        canvas: Canvas,
        region: RegionLayout,
        drawMasks: Boolean = true,
        drawTranslations: Boolean = true,
    ) {
        val style = region.style
        val bounds = region.bounds
        if (region.included && region.layout != null) {
            canvas.save()
            canvas.clipPath(region.polygon)
            if (drawMasks) canvas.drawPath(region.polygon, region.backgroundPaint)
            if (drawTranslations) {
                val frame = region.textFrame
                canvas.rotate(region.rotation, frame?.centerX ?: bounds.centerX(), frame?.centerY ?: bounds.centerY())
                val layout = region.layout
                val x = (frame?.left ?: bounds.left) + region.offsetX
                val y = (frame?.top ?: bounds.top) + region.offsetY
                canvas.translate(x, y)
                if (style.outlineWidth > 0f) {
                    val color = region.textPaint.color
                    region.textPaint.style = Paint.Style.STROKE
                    region.textPaint.strokeWidth = style.outlineWidth
                    region.textPaint.color = (style.outlineColor ?: 0xFFFFFFFF).toInt()
                    layout.draw(canvas)
                    region.textPaint.style = Paint.Style.FILL
                    region.textPaint.color = color
                }
                layout.draw(canvas)
            }
            canvas.restore()
        }
        if (style.showBoxes) canvas.drawPath(region.polygon, region.debugPaint)
        if (region.debugText.isNotEmpty()) {
            canvas.save()
            val labelPaint =
                TextPaint(region.debugPaint).apply {
                    this.style = Paint.Style.FILL
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                }
            val label =
                StaticLayout.Builder
                    .obtain(
                        region.debugText,
                        0,
                        region.debugText.length,
                        labelPaint,
                        max(1, bounds.width().toInt()),
                    ).setIncludePad(false)
                    .build()
            canvas.translate(bounds.left, bounds.top)
            label.draw(canvas)
            canvas.restore()
        }
    }

    /** Rasterizes only text regions in bounded 1024px tiles, never a full page/long strip bitmap. */
    suspend fun gpuOverlay(): ImageOverlay {
        val layers = mutableListOf<ImageOverlay.Layer>()
        try {
            return withContext(Dispatchers.Default) {
                regions.forEach { region ->
                    val left = max(0, region.bounds.left.toInt() - 2)
                    val top = max(0, region.bounds.top.toInt() - 2)
                    val right = min(result.width, ceil(region.bounds.right).toInt() + 2)
                    val bottom = min(result.height, ceil(region.bounds.bottom).toInt() + 2)
                    for (y in top until bottom step TILE_SIZE) {
                        for (x in left until right step TILE_SIZE) {
                            coroutineContext.ensureActive()
                            val width = min(TILE_SIZE, right - x)
                            val height = min(TILE_SIZE, bottom - y)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                Canvas(bitmap).apply {
                                    translate(-x.toFloat(), -y.toFloat())
                                    drawRegion(this, region)
                                }
                                // getPixels returns straight ARGB. The overlay shader premultiplies
                                // these RGBA samples before filtering and source-over blending.
                                val pixels = IntArray(width * height)
                                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                                val rgba = ByteBuffer.allocateDirect(width * height * 4)
                                pixels.forEach { pixel ->
                                    rgba
                                        .put((pixel shr 16).toByte())
                                        .put((pixel shr 8).toByte())
                                        .put(pixel.toByte())
                                        .put((pixel ushr 24).toByte())
                                }
                                rgba.flip()
                                // Finish ownership transfer for this bounded tile even if a new style
                                // cancels the document while Dawn is uploading its texture.
                                val image =
                                    withContext(kotlinx.coroutines.NonCancellable) {
                                        Image(
                                            rgba,
                                            width,
                                            height,
                                            createMipMaps = false,
                                            backgroundColor = Color.TRANSPARENT,
                                        )
                                    }
                                layers +=
                                    ImageOverlay.Layer(
                                        image,
                                        x.toFloat(),
                                        y.toFloat(),
                                        width.toFloat(),
                                        height.toFloat(),
                                    )
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
                ImageOverlay(result.revision, result.width, result.height, layers)
            }
        } catch (error: Throwable) {
            withContext(kotlinx.coroutines.NonCancellable) {
                ImageOverlay(result.revision, result.width, result.height, layers).dispose()
            }
            throw error
        }
    }

    companion object {
        const val RENDERER_VERSION = "source-overlay-fit-v5"
        private const val TILE_SIZE = 1024

        fun create(
            context: Context,
            result: TranslationPageResult,
            style: OverlayStyle,
            sampledBackgrounds: Map<String, Int> = emptyMap(),
            contentPolicy: TranslationContentPolicy = TranslationContentPolicy(),
        ): TranslationOverlayDocument {
            val systemColor =
                com.google.android.material.color.MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorOnSurface,
                    Color.BLACK,
                )
            val background =
                com.google.android.material.color.MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorSurface,
                    Color.WHITE,
                )
            val regions =
                result.regions.sortedBy { it.readingOrder }.mapNotNull { region ->
                    val chosen = region.style ?: style
                    val excludedByPolicy = contentPolicy.excludes(region)
                    if (!chosen.enabled || region.points.size < 3 ||
                        (excludedByPolicy && !chosen.showBoxes) ||
                        (!excludedByPolicy && !region.included && !chosen.showIgnored)
                    ) {
                        return@mapNotNull null
                    }
                    buildRegion(
                        region,
                        chosen,
                        resolveColors(chosen, systemColor, background, sampledBackgrounds[region.id]),
                        excludedByPolicy,
                    )
                }
            return TranslationOverlayDocument(result, regions, contentPolicy)
        }

        /** Fill contrast against a representative backdrop, not a guarantee over varying source artwork. */
        internal data class ResolvedColors(
            val text: Int,
            val background: Int,
            val mask: Int,
            val compositedBackground: Int,
            val contrastRatio: Double,
            val estimated: Boolean,
            val sourceSampleAvailable: Boolean,
            val customText: Boolean,
        ) {
            fun applyTo(style: OverlayStyle): OverlayStyle = style.copy(
                textColor = text.toUInt().toLong(),
                backgroundColor = background.toUInt().toLong(),
            )
        }

        internal fun resolveColors(
            style: OverlayStyle,
            systemText: Int,
            systemBackground: Int,
            sampledBackground: Int? = null,
        ): ResolvedColors {
            // A contrast-only source sample must never replace an explicitly selected mask.
            val background = style.backgroundColor?.toInt()
                ?: sampledBackground?.takeIf { style.sampleBackground }
                ?: systemBackground
            val opacity = style.backgroundOpacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
            val mask = ColorUtils.setAlphaComponent(background, (Color.alpha(background) * opacity).toInt())
            val themeBackdrop = ColorUtils.setAlphaComponent(systemBackground, 255)
            val backdrop = sampledBackground?.let { ColorUtils.compositeColors(it, themeBackdrop) } ?: themeBackdrop
            val composited = ColorUtils.compositeColors(mask, backdrop)
            val text = style.textColor?.toInt() ?: if (sampledBackground != null || style.backgroundColor != null) {
                if (ColorUtils.calculateContrast(Color.BLACK, composited) >=
                    ColorUtils.calculateContrast(Color.WHITE, composited)
                ) {
                    Color.BLACK
                } else {
                    Color.WHITE
                }
            } else {
                // Preserve Mihon's theme foreground when the user has not changed the backdrop.
                systemText
            }
            return ResolvedColors(
                text = text,
                background = background,
                mask = mask,
                compositedBackground = composited,
                contrastRatio = ColorUtils.calculateContrast(text, composited),
                estimated = Color.alpha(mask) < 255,
                sourceSampleAvailable = sampledBackground != null,
                customText = style.textColor != null,
            )
        }

        private fun buildRegion(
            region: TextRegion,
            style: OverlayStyle,
            colors: ResolvedColors,
            excludedByPolicy: Boolean,
        ): RegionLayout? {
            if (region.points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
            val bounds =
                RectF(
                    region.points.minOf {
                        it.x
                    },
                    region.points.minOf { it.y },
                    region.points.maxOf { it.x },
                    region.points.maxOf { it.y },
                )
            if (bounds.width() < 1 || bounds.height() < 1) return null
            val path =
                Path().apply {
                    moveTo(region.points[0].x, region.points[0].y)
                    region.points.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
            val fontWeight = max(if (style.bold) 700 else 100, style.fontWeight.coerceIn(100, 900))
            val fontStyle =
                when {
                    fontWeight >= 600 && style.italic -> Typeface.BOLD_ITALIC
                    fontWeight >= 600 -> Typeface.BOLD
                    style.italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
            val font =
                style.fontPath?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
                    ?: Typeface.create(style.fontFamily.takeUnless { it == "system" }, fontStyle)
            val paint =
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            Typeface.create(font, fontWeight, style.italic)
                        } else {
                            Typeface.create(font, fontStyle)
                        }
                    color = colors.text
                    letterSpacing = style.letterSpacing
                }
            val included = region.included && !excludedByPolicy
            val text = region.translatedText
            val rotation = region.rotation + style.rotation
            val frame = OverlayTextFrame.fit(
                region.points,
                rotation,
                style.padding.coerceAtLeast(0f) + style.outlineWidth.coerceAtLeast(0f) / 2,
            )
            val width = frame?.width ?: max(1f, bounds.width() - style.padding.coerceAtLeast(0f) * 2)
            val height = frame?.height ?: max(1f, bounds.height() - style.padding.coerceAtLeast(0f) * 2)
            val fitted = if (!included || text.isBlank()) {
                null
            } else {
                OverlayTextFitter.fit(text, paint, style, width, height, usableFrame = frame != null)
            }
            val minSize = style.minFontSize.takeIf { it.isFinite() }?.coerceIn(1f, 256f) ?: 8f
            val diagnostic = fitted?.let {
                RegionLayoutDiagnostic(
                    regionId = region.id,
                    effectiveFontSize = it.fontSize,
                    preferredMinFontSize = minSize,
                    belowPreferredMinimum = it.fontSize < minSize,
                    overflow = it.overflow,
                    forcedWordBreaks = it.forcedWordBreaks,
                    message = buildList {
                        if (it.overflow) {
                            add(
                                "Complete text does not fit; enlarge or correct the region, or adjust its style",
                            )
                        }
                        if (it.fontSize <
                            minSize
                        ) {
                            add("Text shrank below the preferred minimum to preserve the complete passage")
                        }
                        if (it.forcedWordBreaks >
                            0
                        ) {
                            add("Some words require internal line breaks at the allowed font sizes")
                        }
                        if (colors.customText && colors.contrastRatio < 4.5) {
                            add(
                                "Custom text color has low contrast against the overlay background; " +
                                    "choose Automatic text color or a contrasting color",
                            )
                        }
                        if (colors.estimated) {
                            add(
                                if (colors.sourceSampleAvailable) {
                                    "Translucent-mask contrast is estimated from source corner samples; " +
                                        "artwork may vary"
                                } else {
                                    "Source background is unavailable; translucent-mask contrast uses a theme estimate"
                                },
                            )
                        }
                    }.takeIf { it.isNotEmpty() }?.joinToString(". "),
                    lineCount = it.layout.lineCount,
                    frameWidth = width,
                    frameHeight = height,
                    resolvedTextColor = colors.text.toUInt().toLong(),
                    compositedBackgroundColor = colors.compositedBackground.toUInt().toLong(),
                    contrastRatio = colors.contrastRatio,
                    contrastBackgroundEstimated = colors.estimated,
                    customTextColor = colors.customText,
                )
            }
            // Policy-excluded regions may expose diagnostic outlines, never text or lettering masks.
            val debug = if (excludedByPolicy) {
                ""
            } else {
                buildList {
                    if (style.showReadingOrder) add("#${region.readingOrder}")
                    if (style.showCoordinates) {
                        add(
                            "${bounds.left.toInt()},${bounds.top.toInt()} – " +
                                "${bounds.right.toInt()},${bounds.bottom.toInt()}",
                        )
                    }
                    if (style.showConfidence) {
                        region.detectionConfidence?.let { add("D ${(it * 100).toInt()}%") }
                        region.recognitionConfidence?.let { add("R ${(it * 100).toInt()}%") }
                        region.aiConfidence?.let { add("AI ${(it * 100).toInt()}%") }
                    }
                    if (!region.included) add(region.ignoredReason ?: "Ignored")
                }.joinToString(" · ")
            }
            return RegionLayout(
                bounds,
                path,
                style,
                fitted?.layout,
                frame,
                paint,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colors.mask
                },
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (included) Color.rgb(30, 150, 255) else Color.rgb(245, 145, 30)
                    this.style = Paint.Style.STROKE
                    strokeWidth = 1.5f
                },
                debug,
                rotation,
                included,
                diagnostic,
                fitted?.offsetX ?: 0f,
                fitted?.offsetY ?: 0f,
            )
        }
    }
}
