package ca.mpreg.webgpuviewer

import android.util.Half
import ca.mpreg.imagedecoder.ImageDecoder
import ca.mpreg.webgpuviewer.renderer.GainmapInput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Pixels in the original file's coordinate system, before any reader crop or rotation. */
data class ReaderPixelBuffer(val pixels: ByteBuffer, val width: Int, val height: Int)

/**
 * Decoder 18 applies Exif orientation to every frame, region and gainmap. Translation identities,
 * upload copies and saved geometry use raw source pixels instead. Undo that permutation here,
 * before either reader crops or uploads, without resampling or re-encoding. The unchanged case
 * shares [pixels] and allocates no pixel copy.
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

/** The Exif orientation the decoder applies to everything it hands out; 1 when absent or invalid. */
fun ImageDecoder.readerSourceOrientation(): Int =
    getTag("Orientation")?.trim()?.toIntOrNull()?.takeIf { it in 1..8 } ?: 1

/**
 * [frame]'s pixels in raw source coordinates. An unoriented frame's buffer is shared and valid only
 * until the frame closes; an oriented one is copied. [sdr8] clamps half-float HDR output to the RGBA8
 * the classic reader's tiles use, which always copies.
 */
fun restoreReaderSourceFrame(
    decoder: ImageDecoder,
    frame: ImageDecoder.Frame,
    sdr8: Boolean = false,
): ReaderPixelBuffer {
    val bytesPerPixel = if (decoder.isHdr) 8 else 4
    val restored = restoreReaderSourcePixels(
        frame.image,
        frame.width,
        frame.height,
        bytesPerPixel,
        decoder.readerSourceOrientation(),
    )
    if (!sdr8 || !decoder.isHdr) return restored
    val halves = restored.pixels.duplicate().order(ByteOrder.nativeOrder())
    val count = Math.multiplyExact(Math.multiplyExact(restored.width, restored.height), 4)
    val sdr = ByteBuffer.allocateDirect(count).order(ByteOrder.nativeOrder())
    repeat(count) {
        val sample = Half.toFloat(halves.short)
        sdr.put((sample.coerceIn(0f, 1f).takeUnless { it.isNaN() }?.times(255f)?.roundToInt() ?: 0).toByte())
    }
    sdr.flip()
    return ReaderPixelBuffer(sdr, restored.width, restored.height)
}

/** [gainmap] for the viewer, in the same raw source coordinates as [restoreReaderSourceFrame]. */
fun readerSourceGainmap(gainmap: ImageDecoder.Gainmap, orientation: Int): GainmapInput {
    val pixels = restoreReaderSourcePixels(gainmap.pixels, gainmap.width, gainmap.height, gainmap.channels, orientation)
    return GainmapInput(
        pixels = pixels.pixels,
        width = pixels.width,
        height = pixels.height,
        channels = gainmap.channels,
        gamma = gainmap.gamma,
        minContentBoost = gainmap.minContentBoost,
        maxContentBoost = gainmap.maxContentBoost,
        offsetSdr = gainmap.offsetSdr,
        offsetHdr = gainmap.offsetHdr,
    )
}
