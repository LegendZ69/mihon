package mihon.feature.translation.overlay

import android.graphics.RectF
import android.icu.text.BreakIterator
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import tachiyomi.domain.translation.model.OverlayStyle
import kotlin.math.max
import kotlin.math.min

/** Fits the actual shaped layout. It neither edits the text nor expands the source mask. */
internal object OverlayTextFitter {
    data class Fit(
        val layout: StaticLayout,
        val fontSize: Float,
        val offsetX: Float,
        val offsetY: Float,
        val overflow: Boolean,
        val forcedWordBreaks: Int,
    )

    fun fit(
        text: String,
        paint: TextPaint,
        style: OverlayStyle,
        availableWidth: Float,
        availableHeight: Float,
        usableFrame: Boolean,
    ): Fit {
        val width = max(1, availableWidth.toInt())
        val minSize = style.minFontSize.finiteOr(8f).coerceIn(1f, 256f)
        val maxSize = style.maxFontSize.finiteOr(48f).coerceIn(minSize, 512f)
        val breaks = BreakIterator.getLineInstance(paint.textLocale).apply { setText(text) }

        fun candidate(size: Float, allowReflow: Boolean = true): Candidate {
            paint.textSize = size
            val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(
                    when (style.alignment) {
                        "left", "start" -> Layout.Alignment.ALIGN_NORMAL
                        "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> Layout.Alignment.ALIGN_CENTER
                    },
                )
                .setTextDirection(
                    when (style.direction) {
                        "rtl" -> TextDirectionHeuristics.RTL
                        "ltr" -> TextDirectionHeuristics.LTR
                        else -> TextDirectionHeuristics.FIRSTSTRONG_LTR
                    },
                )
                .setLineSpacing(0f, style.lineSpacing.finiteOr(1f).coerceIn(0.5f, 4f))
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setIncludePad(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setUseLineSpacingFromFallbacks(true)
            if (Build.VERSION.SDK_INT >= 35) builder.setUseBoundsForWidth(true)
            val layout = builder.build()
            val glyphBounds = OverlayGlyphBounds.measure(layout)
            val bounds = RectF(
                glyphBounds.left,
                min(0f, glyphBounds.top),
                glyphBounds.right,
                max(layout.height.toFloat(), glyphBounds.bottom),
            )
            val complete = layout.lineCount > 0 && layout.getLineEnd(layout.lineCount - 1) == text.length &&
                (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 }
            val advancesFit = (0 until layout.lineCount).all { layout.getLineWidth(it) <= availableWidth + 0.01f }
            val fits = usableFrame && complete && advancesFit && bounds.width() <= availableWidth + 0.01f &&
                bounds.height() <= availableHeight + 0.01f
            val forced = (0 until (layout.lineCount - 1)).count { line ->
                val end = layout.getLineEnd(line)
                end < text.length && !breaks.isBoundary(end)
            }
            // On API 36, identical input produced an over-wide first bounds-aware layout.
            // After measuring its glyphs, rebuild once at the same size before shrinking.
            // The replacement must still satisfy every fit check; reflow cannot repeat.
            if (allowReflow && Build.VERSION.SDK_INT >= 35 && usableFrame && complete &&
                (!advancesFit || bounds.width() > availableWidth + 0.01f)
            ) {
                return candidate(size, allowReflow = false)
            }
            return Candidate(layout, size, bounds, fits, forced)
        }

        fun largest(lowLimit: Float, highLimit: Float, allowForcedBreaks: Boolean): Candidate? {
            fun Candidate.eligible() = fits && (allowForcedBreaks || forcedWordBreaks == 0)
            var best = candidate(lowLimit).takeIf { it.eligible() } ?: return null
            val largest = candidate(highLimit)
            if (largest.eligible()) return largest
            var low = lowLimit
            var high = highLimit
            repeat(12) {
                val middle = (low + high) / 2
                val current = candidate(middle)
                if (current.eligible()) {
                    best = current
                    low = middle
                } else {
                    high = middle
                }
            }
            return best
        }

        val floor = if (style.allowSmallerText) 1f else minSize
        val chosen = if (style.autoFit) {
            largest(minSize, maxSize, allowForcedBreaks = false)
                ?: (if (floor < minSize) largest(floor, minSize, allowForcedBreaks = false) else null)
                ?: largest(floor, maxSize, allowForcedBreaks = true)
                ?: candidate(floor)
        } else {
            candidate(style.fontSize.finiteOr(16f).coerceIn(1f, 512f))
        }
        // StaticLayout retains this paint; restore the winning candidate after the search probes.
        paint.textSize = chosen.size
        val leftLimit = -chosen.bounds.left
        val rightLimit = availableWidth - chosen.bounds.right
        val x = if (leftLimit <= rightLimit) 0f.coerceIn(leftLimit, rightLimit) else leftLimit
        val y = ((availableHeight - chosen.bounds.height()) / 2).coerceAtLeast(0f) - chosen.bounds.top
        return Fit(chosen.layout, chosen.size, x, y, !chosen.fits, chosen.forcedWordBreaks)
    }

    private data class Candidate(
        val layout: StaticLayout,
        val size: Float,
        val bounds: RectF,
        val fits: Boolean,
        val forcedWordBreaks: Int,
    )

    private fun Float.finiteOr(fallback: Float) = takeIf { it.isFinite() } ?: fallback
}
