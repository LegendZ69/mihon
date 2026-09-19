package mihon.feature.translation.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.TranslationPageResult

/** The real inspector View draws into its host's Canvas, which may extend beyond its viewport. */
@RunWith(AndroidJUnit4::class)
class TranslationGeometryPreviewViewportTest {
    @Test
    fun zoomedInspectorPreviewLeavesPixelsOutsideItsViewportUntouched() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val outsideColor = Color.rgb(179, 33, 101)
        val sourceColor = Color.rgb(39, 122, 83)
        val source = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888).apply { eraseColor(sourceColor) }
        val parent = Bitmap.createBitmap(1400, 1360, Bitmap.Config.ARGB_8888).apply { eraseColor(outsideColor) }
        val originX = 100
        val originY = 200
        val viewportWidth = 1200
        val viewportHeight = 960
        val pixels = IntArray(parent.width * parent.height)
        try {
            instrumentation.runOnMainSync {
                val preview = GeometryPreviewView(instrumentation.targetContext)
                try {
                    preview.layout(0, 0, viewportWidth, viewportHeight)
                    preview.bind(
                        bitmap = source,
                        document = null,
                        result = TranslationPageResult("viewport-fixture", "synthetic", 1200, 1600, emptyList()),
                        source = true,
                        boxes = false,
                        masks = false,
                        translations = false,
                        zoom = 2.5f,
                        selected = null,
                        onZoom = {},
                        onSelect = {},
                        onCorner = { _, _, _ -> },
                    )
                    val canvas = Canvas(parent)
                    // AndroidView's host can leave a wider clip, as in the captured inspector UI.
                    // Deliberately avoid a test-owned viewport clip: the preview must contain its drawing.
                    val saved = canvas.save()
                    canvas.translate(originX.toFloat(), originY.toFloat())
                    preview.draw(canvas)
                    canvas.restoreToCount(saved)
                    parent.getPixels(pixels, 0, parent.width, 0, 0, parent.width, parent.height)
                } finally {
                    preview.clear()
                }
            }
            var changedOutside = 0
            var sourceInside = 0
            for (y in 0 until parent.height) {
                for (x in 0 until parent.width) {
                    val inside = x in originX until originX + viewportWidth &&
                        y in originY until originY + viewportHeight
                    val color = pixels[y * parent.width + x]
                    if (inside && color == sourceColor) sourceInside++
                    if (!inside && color != outsideColor) changedOutside++
                }
            }
            val report = JSONObject()
                .put("case", "inspector-viewport-2.5x")
                .put("zoom", 2.5)
                .put("source_width", source.width).put("source_height", source.height)
                .put("viewport_left", originX).put("viewport_top", originY)
                .put("viewport_width", viewportWidth).put("viewport_height", viewportHeight)
                .put("parent_width", parent.width).put("parent_height", parent.height)
                .put("outside_pixels_changed", changedOutside)
                .put("source_pixels_inside", sourceInside)
                .put("provider_dispatches", 0)
            instrumentation.sendStatus(
                2,
                Bundle().apply { putString("inspector_viewport_report_json", report.toString()) },
            )
            assertEquals(
                "The actual preview must render the original page inside its viewport",
                1200 * 960,
                sourceInside,
            )
            assertEquals("Zoomed source drawing must not overwrite adjacent inspector controls", 0, changedOutside)
        } finally {
            parent.recycle()
            source.recycle()
        }
    }
}
