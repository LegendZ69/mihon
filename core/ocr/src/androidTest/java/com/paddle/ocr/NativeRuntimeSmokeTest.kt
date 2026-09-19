package com.paddle.ocr

import ai.onnxruntime.OrtEnvironment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paddle.ocr.preprocess.RecPreprocessor
import com.paddle.ocr.util.OpenCVUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.geometry.Geometry

/** Run on both 4 KB and 16 KB Android images; detects JNI/STL and OpenCV 5 ABI failures. */
@RunWith(AndroidJUnit4::class)
class NativeRuntimeSmokeTest {
    @Test
    fun recognizerPreservesBgrChannelsAndNormalizedZeroPadding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(OpenCVUtils.init(context))
        val wide = Mat(48, 20, CvType.CV_8UC3, Scalar(0.0, 128.0, 255.0))
        val narrow = Mat(48, 10, CvType.CV_8UC3, Scalar(0.0, 128.0, 255.0))
        try {
            val result = RecPreprocessor.preprocessBatch(listOf(wide, narrow))
            assertEquals(listOf(2L, 3L, 48L, 20L), result.shape.toList())
            assertEquals(-1f, result.tensorData[0], 0.0001f)
            assertEquals(128f / 127.5f - 1f, result.tensorData[48 * 20], 0.0001f)
            assertEquals(1f, result.tensorData[2 * 48 * 20], 0.0001f)
            assertEquals(-1f, result.tensorData[3 * 48 * 20], 0.0001f)
            assertEquals(0f, result.tensorData[3 * 48 * 20 + 10], 0.0001f)
        } finally {
            wide.release()
            narrow.release()
        }
    }

    @Test
    fun bothNativeRuntimesLoadAndGeometryExecutes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(OpenCVUtils.init(context))
        assertNotNull(OrtEnvironment.getEnvironment())
        val image = Mat.zeros(2, 2, CvType.CV_8UC1)
        try {
            image.put(0, 0, byteArrayOf(42))
            assertEquals(42.0, image.get(0, 0)[0], 0.0)
        } finally {
            image.release()
        }
        val points = MatOfPoint2f(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 5.0), Point(0.0, 5.0))
        try {
            val rectangle = Geometry.minAreaRect(points)
            assertEquals(50.0, rectangle.size.area(), 0.001)
        } finally {
            points.release()
        }
    }
}
