package com.paddle.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Supply a verified official model pack with `-e ocrModelDirectory /data/local/tmp/paddle-test`. */
@RunWith(AndroidJUnit4::class)
class ModelInferenceSmokeTest {
    @Test
    fun officialModelsDetectAndRecognizeOriginalPixelGeometry() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val directory = arguments.getString("ocrModelDirectory")
        assumeTrue("Requires an explicitly supplied, checksum-verified official model pack", directory != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(OpenCVUtils.init(context))
        val files = listOf("det.onnx", "rec.onnx", "rec.yml").map { File(directory!!, it) }
        assertTrue("The supplied model pack must contain all three files", files.all { it.isFile })
        val fixtureText = arguments.getString("ocrFixtureText") ?: "HELLO MANGA 123"
        val bitmap = Bitmap.createBitmap(960, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText(
            fixtureText,
            40f,
            170f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 72f
            },
        )
        val engine = PaddleOCR.create(
            context,
            PaddleOCRConfig(recScoreThresh = 0f),
            EngineConfig(numThreads = 2),
            files[0].absolutePath,
            files[1].absolutePath,
            files[2].absolutePath,
        )
        try {
            val result = engine.recognize(bitmap)
            val text = result.results.joinToString(" ") { it.text }.filter { !it.isWhitespace() }
            assertTrue(
                "Expected $fixtureText, received $text",
                text.contains(
                    fixtureText.filter {
                        !it.isWhitespace()
                    },
                ),
            )
            assertTrue(result.results.all { it.confidence in 0f..1f && it.confidence > 0f })
            assertTrue(result.results.all { it.box.detectionConfidence?.let { score -> score in 0f..1f } == true })
            assertTrue(result.results.all { region -> region.box.points.all { it.x in 0f..960f && it.y in 0f..320f } })
            assertTrue(result.detInputShape.isNotEmpty())
            assertTrue(result.recInputShapes.isNotEmpty())
        } finally {
            engine.release()
            bitmap.recycle()
        }
    }
}
