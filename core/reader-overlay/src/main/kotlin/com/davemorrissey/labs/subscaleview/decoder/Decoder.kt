package com.davemorrissey.labs.subscaleview.decoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import ca.mpreg.imagedecoder.ImageDecoder
import ca.mpreg.webgpuviewer.ReaderPixelBuffer
import ca.mpreg.webgpuviewer.readerSourceOrientation
import ca.mpreg.webgpuviewer.restoreReaderSourceFrame
import com.davemorrissey.labs.subscaleview.CropBorders
import com.davemorrissey.labs.subscaleview.provider.InputProvider
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.sqrt

// Border detection runs on a copy shrunk to about this many pixels (16 MB of RGBA).
private const val BORDER_PIXELS = 4_000_000.0

class Decoder(
    private val cropBorders: Boolean,
) : ImageRegionDecoder {

    // Kept open so each tile decodes only its own region; ImageDecoder serialises the calls.
    @Volatile
    private var decoder: ImageDecoder? = null

    // Raw-coordinate RGBA8 copy of an Exif-oriented image. The decoder applies Exif orientation, but
    // translation geometry is in the file's raw pixel coordinates, so such an image is shown unrotated.
    @Volatile
    private var rawPixels: ReaderPixelBuffer? = null

    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    // The crop rect SSIV lays tiles out against, in full-image coordinates.
    private var cropLeft: Int = 0
    private var cropTop: Int = 0
    private var cropWidth: Int = 0
    private var cropHeight: Int = 0

    val sourceImageWidth: Int get() = imageWidth
    val sourceImageHeight: Int get() = imageHeight
    val sourceCrop: Rect get() = Rect(cropLeft, cropTop, cropLeft + cropWidth, cropTop + cropHeight)

    /**
     * Opens the image and returns its size, or the size of its content without borders when
     * cropBorders is set, found on a shrunk copy of the whole image.
     */
    override fun init(context: Context, provider: InputProvider): Point {
        // Else the old decoder's native memory waits for the GC.
        decoder?.close()
        decoder = null
        rawPixels = null
        val dec = provider.openStream().use { inputStream ->
            checkNotNull(inputStream) { "InputProvider returned null stream" }
            ImageDecoder.open(inputStream)
        }

        if (dec.readerSourceOrientation() != 1) {
            return dec.use(::initRaw)
        }

        imageWidth = dec.width
        imageHeight = dec.height
        cropLeft = 0
        cropTop = 0
        cropWidth = dec.width
        cropHeight = dec.height

        // Border detection reads 8-bit RGBA, so HDR (half-float) images are left uncropped
        if (cropBorders && !dec.isHdr) {
            try {
                // The full page could be hundreds of MB on the Java heap.
                val pixels = dec.width.toDouble() * dec.height
                val sample = ceil(sqrt(pixels / BORDER_PIXELS)).toInt().coerceAtLeast(1)
                val (borders, sw, sh) = dec.decode(null, sample).use { frame ->
                    Triple(
                        CropBorders.findCropBorders(frame.toByteArray(), frame.width, frame.height),
                        frame.width,
                        frame.height,
                    )
                }
                val l = borders.getOrElse(0) { 0 }
                val t = borders.getOrElse(1) { 0 }
                val w = borders.getOrElse(2) { 0 }
                val h = borders.getOrElse(3) { 0 }
                val ok = borders.size >= 4
                if (ok && w > 0 && h > 0 && l >= 0 && t >= 0 && l + w <= sw && t + h <= sh) {
                    // A sampled pixel wider each way, so gaps between samples never cut content.
                    val slack = if (sample > 1) 1 else 0
                    val left = ((l - slack).coerceAtLeast(0).toLong() * sample).toInt()
                    val top = ((t - slack).coerceAtLeast(0).toLong() * sample).toInt()
                    val right = ((l + w + slack).toLong() * sample).coerceAtMost(dec.width.toLong()).toInt()
                    val bottom = ((t + h + slack).toLong() * sample).coerceAtMost(dec.height.toLong()).toInt()
                    if (right > left && bottom > top) {
                        cropLeft = left
                        cropTop = top
                        cropWidth = right - left
                        cropHeight = bottom - top
                    }
                }
            } catch (e: Throwable) {
                dec.close()
                throw e
            }
        }

        decoder = dec
        return Point(cropWidth, cropHeight)
    }

    /** Decodes the whole first frame once and keeps its raw-coordinate RGBA8 pixels for [decodeRegion]. */
    private fun initRaw(dec: ImageDecoder): Point {
        val raw = dec.decodeNext().use { frame -> restoreReaderSourceFrame(dec, frame, sdr8 = true) }
        imageWidth = raw.width
        imageHeight = raw.height
        cropLeft = 0
        cropTop = 0
        cropWidth = raw.width
        cropHeight = raw.height

        if (cropBorders) {
            val buffer = raw.pixels.duplicate()
            val copy = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val borders = CropBorders.findCropBorders(copy, raw.width, raw.height)
            val l = borders.getOrElse(0) { 0 }
            val t = borders.getOrElse(1) { 0 }
            val w = borders.getOrElse(2) { 0 }
            val h = borders.getOrElse(3) { 0 }
            if (borders.size >= 4 && w > 0 && h > 0 && l >= 0 && t >= 0 && l + w <= raw.width && t + h <= raw.height) {
                cropLeft = l
                cropTop = t
                cropWidth = w
                cropHeight = h
            }
        }

        rawPixels = raw
        return Point(cropWidth, cropHeight)
    }

    /**
     * Decodes [sRect], given in the cropped coordinates SSIV knows about, shrunk by
     * [sampleSize] as it decodes: only the shrunk tile is ever held.
     */
    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        val region = Rect(
            (sRect.left + cropLeft).coerceAtLeast(cropLeft),
            (sRect.top + cropTop).coerceAtLeast(cropTop),
            (sRect.right + cropLeft).coerceAtMost(cropLeft + cropWidth),
            (sRect.bottom + cropTop).coerceAtMost(cropTop + cropHeight),
        )
        require(!region.isEmpty) { "region $sRect is outside the ${cropWidth}x$cropHeight image" }

        rawPixels?.let { return it.regionBitmap(region, sampleSize.coerceAtLeast(1)) }

        val dec = checkNotNull(decoder) { "Decoder not initialized or recycled" }
        return dec.decodeToBitmap(region, sampleSize.coerceAtLeast(1))
    }

    override fun isReady(): Boolean = decoder != null || rawPixels != null

    override fun recycle() {
        decoder?.close()
        decoder = null
        rawPixels = null
    }
}

/** Nearest-neighbour samples of RGBA8 [region], one source pixel in [sampleSize] each way. */
private fun ReaderPixelBuffer.regionBitmap(region: Rect, sampleSize: Int): Bitmap {
    val source = pixels.duplicate().order(ByteOrder.nativeOrder())
    val outWidth = (region.width() + sampleSize - 1) / sampleSize
    val outHeight = (region.height() + sampleSize - 1) / sampleSize
    val out = IntArray(outWidth * outHeight)
    for (outY in 0 until outHeight) {
        val sy = region.top + outY * sampleSize
        if (sy >= height) break
        for (outX in 0 until outWidth) {
            val sx = region.left + outX * sampleSize
            if (sx >= width) continue
            val i = (sy * width + sx) * 4
            val r = source.get(i).toInt() and 0xFF
            val g = source.get(i + 1).toInt() and 0xFF
            val b = source.get(i + 2).toInt() and 0xFF
            val a = source.get(i + 3).toInt() and 0xFF
            out[outY * outWidth + outX] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return Bitmap.createBitmap(out, outWidth, outHeight, Bitmap.Config.ARGB_8888)
}
