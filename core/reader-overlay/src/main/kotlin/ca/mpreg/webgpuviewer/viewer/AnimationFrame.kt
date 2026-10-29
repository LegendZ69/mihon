package ca.mpreg.webgpuviewer.viewer

import java.io.Closeable
import java.nio.ByteBuffer

/** One decoded animation frame, as [ImagePage.ImageSingle.animate] asks for it. */
class AnimationFrame(
    /** The whole canvas, as [ca.mpreg.webgpuviewer.renderer.Image] takes it. */
    val pixels: ByteBuffer,
    /** Display time in ms. */
    val duration: Int,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() = onClose()
}
