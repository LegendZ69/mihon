package ca.mpreg.webgpuviewer

import ca.mpreg.imagedecoder.ImageDecoder
import ca.mpreg.webgpuviewer.renderer.GainmapInput
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pixels in the original file's coordinate system, before any reader crop or rotation. */
data class ReaderPixelBuffer(val pixels: ByteBuffer, val width: Int, val height: Int)

/**
 * Decoder 14 unconditionally applies EXIF in nativeDecode/apply_orientation, including gainmaps.
 * Translation identities, upload copies and saved geometry use raw source pixels instead. Undo
 * that permutation here, before either reader crops or uploads, without resampling or re-encoding.
 * The unchanged case shares the decoder's Java-owned direct buffer and allocates no pixel copy.
 */
fun restoreReaderSourcePixels(
    pixels: ByteBuffer,
    width: Int,
    height: Int,
    bytesPerPixel: Int,
    orientation: Int,
): ReaderPixelBuffer {
    require(width > 0 && height > 0 && bytesPerPixel in 1..8) { "Invalid reader pixel dimensions" }
    val pixelCount = width.toLong() * height
    require(pixelCount <= Int.MAX_VALUE / bytesPerPixel && pixelCount <= pixels.remaining() / bytesPerPixel) {
        "Invalid reader pixel buffer"
    }
    val byteCount = (pixelCount * bytesPerPixel).toInt()
    if (orientation !in 2..8) return ReaderPixelBuffer(pixels, width, height)

    val sourceWidth = if (orientation >= 5) height else width
    val sourceHeight = if (orientation >= 5) width else height
    val source = pixels.slice().order(ByteOrder.nativeOrder())
    val restored = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
    for (y in 0 until sourceHeight) {
        for (x in 0 until sourceWidth) {
            val orientedX = when (orientation) {
                2, 3 -> sourceWidth - 1 - x
                5, 8 -> y
                6, 7 -> sourceHeight - 1 - y
                else -> x
            }
            val orientedY = when (orientation) {
                3, 4 -> sourceHeight - 1 - y
                5, 6 -> x
                7, 8 -> sourceWidth - 1 - x
                else -> y
            }
            val offset = (orientedY * width + orientedX) * bytesPerPixel
            when (bytesPerPixel) {
                8 -> restored.putLong(source.getLong(offset))
                4 -> restored.putInt(source.getInt(offset))
                else -> repeat(bytesPerPixel) { restored.put(source.get(offset + it)) }
            }
        }
    }
    restored.flip()
    return ReaderPixelBuffer(restored, sourceWidth, sourceHeight)
}

data class ReaderDecodedFrame(
    val image: ByteBuffer,
    val width: Int,
    val height: Int,
    val duration: Int,
    val isHdr: Boolean,
    val hdrHeadroom: Float,
    val gainmap: GainmapInput?,
)

/** Only full, untrimmed frames: crop geometry is calculated after restoring source coordinates. */
fun ImageDecoder.decodeNextReaderFrame(): ReaderDecodedFrame {
    val orientation = getTag("Orientation")?.trim()?.toIntOrNull() ?: 1
    val decoded = decodeNext(crop = false, getTrim = false)
    val original = restoreReaderSourcePixels(
        decoded.image,
        decoded.width,
        decoded.height,
        decoded.pixelFormat.bytesPerPixel,
        orientation,
    )
    val gainmap = decoded.gainmap?.let {
        val originalMap = restoreReaderSourcePixels(it.pixels, it.width, it.height, it.channels, orientation)
        GainmapInput(
            pixels = originalMap.pixels,
            width = originalMap.width,
            height = originalMap.height,
            channels = it.channels,
            gamma = it.gamma,
            minContentBoost = it.minContentBoost,
            maxContentBoost = it.maxContentBoost,
            offsetSdr = it.offsetSdr,
            offsetHdr = it.offsetHdr,
        )
    }
    return ReaderDecodedFrame(
        original.pixels,
        original.width,
        original.height,
        decoded.duration,
        decoded.isHdr,
        decoded.hdrHeadroom,
        gainmap,
    )
}
