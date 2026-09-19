package mihon.feature.translation.overlay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.TranslationPoint
import java.awt.geom.Path2D
import kotlin.math.cos
import kotlin.math.sin

class OverlayTextFrameTest {
    private val rectangle =
        listOf(
            TranslationPoint(0f, 0f),
            TranslationPoint(295f, 0f),
            TranslationPoint(295f, 136f),
            TranslationPoint(0f, 136f),
        )

    @Test
    fun `cardinal rotations preserve the available padded dimensions`() {
        val horizontal = requireNotNull(OverlayTextFrame.fit(rectangle, 0f, 2f))
        assertEquals(291f, horizontal.width)
        assertEquals(132f, horizontal.height)
        val vertical = requireNotNull(OverlayTextFrame.fit(rectangle, 90f, 2f))
        assertEquals(132f, vertical.width)
        assertEquals(291f, vertical.height)
    }

    @Test
    fun `every rotated frame corner lies inside the actual convex source polygon`() {
        val tilted =
            rectangle.map { point ->
                val angle = Math.toRadians(-17.0)
                TranslationPoint(
                    (point.x * cos(angle) - point.y * sin(angle)).toFloat(),
                    (point.x * sin(angle) + point.y * cos(angle)).toFloat(),
                )
            }
        val trapezoid =
            listOf(
                TranslationPoint(20f, 10f),
                TranslationPoint(280f, 0f),
                TranslationPoint(310f, 160f),
                TranslationPoint(0f, 130f),
            )
        for (polygon in listOf(rectangle, tilted, trapezoid, trapezoid.reversed())) {
            val path =
                Path2D.Double().apply {
                    moveTo(polygon[0].x.toDouble(), polygon[0].y.toDouble())
                    polygon.drop(1).forEach { lineTo(it.x.toDouble(), it.y.toDouble()) }
                    closePath()
                }
            for (degrees in -360..360 step 5) {
                val frame = OverlayTextFrame.fit(polygon, degrees.toFloat(), 2f)
                assertNotNull(frame, "A nondegenerate convex fixture must admit a text frame")
                frame!!
                val angle = Math.toRadians(degrees.toDouble())
                for (x in listOf(-frame.width / 2, frame.width / 2)) {
                    for (y in listOf(-frame.height / 2, frame.height / 2)) {
                        val actualX = frame.centerX + x * cos(angle) - y * sin(angle)
                        val actualY = frame.centerY + x * sin(angle) + y * cos(angle)
                        assertTrue(
                            path.contains(actualX, actualY),
                            "Rotated corner $actualX,$actualY outside polygon at $degrees degrees",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `invalid or fully consumed source geometry has no fitting frame`() {
        assertNull(OverlayTextFrame.fit(rectangle, Float.NaN, 2f))
        assertNull(OverlayTextFrame.fit(rectangle, 0f, 1000f))
        assertNull(OverlayTextFrame.fit(listOf(TranslationPoint(0f, 0f), TranslationPoint(1f, 1f)), 0f, 2f))
    }
}
