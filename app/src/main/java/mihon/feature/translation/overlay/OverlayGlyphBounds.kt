package mihon.feature.translation.overlay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.text.MeasuredText
import android.graphics.text.PositionedGlyphs
import android.graphics.text.TextRunShaper
import android.os.Build
import android.text.StaticLayout
import androidx.annotation.RequiresApi

/** Uses shaped glyph bounds, including fallback fonts, without allocating a page bitmap. */
internal object OverlayGlyphBounds {
    fun measure(layout: StaticLayout): RectF {
        if (Build.VERSION.SDK_INT >= 35) return layout.computeDrawingBoundingBox()
        return BoundsCanvas().also { layout.draw(it) }.bounds
    }

    private class BoundsCanvas : Canvas() {
        val bounds = RectF()

        override fun getClipBounds(bounds: Rect): Boolean {
            bounds.set(-1_000_000, -1_000_000, 1_000_000, 1_000_000)
            return true
        }

        @RequiresApi(29)
        override fun drawTextRun(
            text: MeasuredText,
            start: Int,
            end: Int,
            contextStart: Int,
            contextEnd: Int,
            x: Float,
            y: Float,
            isRtl: Boolean,
            paint: Paint,
        ) {
            val measured = Rect()
            text.getBounds(start, end, measured)
            bounds.union(RectF(measured).apply { offset(x, y) })
        }

        override fun drawTextRun(
            text: CharArray,
            index: Int,
            count: Int,
            contextIndex: Int,
            contextCount: Int,
            x: Float,
            y: Float,
            isRtl: Boolean,
            paint: Paint,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                include(
                    TextRunShaper.shapeTextRun(text, index, count, contextIndex, contextCount, x, y, isRtl, paint),
                    paint,
                )
            } else {
                legacy(String(text, index, count), x, y, isRtl, paint)
            }
        }

        override fun drawTextRun(
            text: CharSequence,
            start: Int,
            end: Int,
            contextStart: Int,
            contextEnd: Int,
            x: Float,
            y: Float,
            isRtl: Boolean,
            paint: Paint,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                include(
                    TextRunShaper.shapeTextRun(
                        text,
                        start,
                        end - start,
                        contextStart,
                        contextEnd - contextStart,
                        x,
                        y,
                        isRtl,
                        paint,
                    ),
                    paint,
                )
            } else {
                legacy(text.subSequence(start, end).toString(), x, y, isRtl, paint)
            }
        }

        override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) {
            drawTextRun(text, index, count, index, count, x, y, false, paint)
        }

        override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            drawTextRun(text, start, end, start, end, x, y, false, paint)
        }

        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            drawTextRun(text as CharSequence, start, end, start, end, x, y, false, paint)
        }

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            drawText(text, 0, text.length, x, y, paint)
        }

        @RequiresApi(31)
        private fun include(glyphs: PositionedGlyphs, paint: Paint) {
            val glyphBounds = RectF()
            for (index in 0 until glyphs.glyphCount()) {
                glyphs.getFont(index).getGlyphBounds(glyphs.getGlyphId(index), paint, glyphBounds)
                glyphBounds.offset(glyphs.getGlyphX(index), glyphs.getGlyphY(index))
                bounds.union(glyphBounds)
            }
        }

        private fun legacy(text: String, x: Float, y: Float, isRtl: Boolean, paint: Paint) {
            // API 26–28 has no public positioned-glyph API. Keep both painted paths
            // and conservative font metrics; never substitute Latin-only metrics for glyph ink.
            val path = Path()
            paint.getTextPath(text, 0, text.length, 0f, 0f, path)
            val ink = RectF()
            path.computeBounds(ink, true)
            val measured = Rect()
            paint.getTextBounds(text, 0, text.length, measured)
            ink.union(RectF(measured))
            val metrics = paint.fontMetrics
            val advance = paint.measureText(text)
            ink.union(0f, metrics.top, advance, metrics.bottom)
            if (isRtl) {
                // Older drawTextRun exposes a directional run, while getTextPath
                // has no direction argument. Include its reflected bearings too.
                val reflected = RectF(-ink.right + advance, ink.top, -ink.left + advance, ink.bottom)
                ink.union(reflected)
            }
            ink.offset(x, y)
            bounds.union(ink)
        }
    }
}
