package ca.mpreg.webgpuviewer.renderer

import androidx.webgpu.GPUCommandEncoder
import androidx.webgpu.GPUColor
import androidx.webgpu.GPURenderPassColorAttachment
import androidx.webgpu.GPURenderPassDescriptor
import androidx.webgpu.GPURenderPassEncoder
import androidx.webgpu.GPUTexture
import androidx.webgpu.LoadOp
import androidx.webgpu.StoreOp

/** Small independently rasterized regions, positioned in the original image's pixel space. */
class ImageOverlay(
    val revision: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val layers: List<Layer>,
) {
    data class Layer(
        val image: Image,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )

    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(layers.all { it.width > 0 && it.height > 0 })
    }

    private var released = false

    /** For an overlay which was prepared but never handed to ImageSingle.replaceOverlay. */
    suspend fun dispose() = WebGpuRenderer.onDispatcher { release() }

    internal fun release() {
        if (released) return
        released = true
        layers.forEach { it.image.cleanup() }
    }

    internal fun draw(
        pass: GPURenderPassEncoder,
        dst: GPUTexture,
        original: Image,
        x: Float,
        y: Float,
        scale: Float,
    ) {
        if (released) return
        val rect = original.placement(dst, x, y, scale)
        val originX = rect[0] * dst.width
        val originY = rect[1] * dst.height
        val pixelScaleX = (rect[2] - rect[0]) * dst.width / sourceWidth
        val pixelScaleY = (rect[3] - rect[1]) * dst.height / sourceHeight
        layers.forEach { layer ->
            val left = originX + layer.left * pixelScaleX
            val top = originY + layer.top * pixelScaleY
            val w = layer.width * pixelScaleX
            val h = layer.height * pixelScaleY
            if (left >= dst.width || top >= dst.height || left + w <= 0f || top + h <= 0f) return@forEach
            val imageScale = w / layer.image.width
            val (placeX, placeY) = solveImagePlacement(
                left + w / 2f, top + h / 2f, imageScale, layer.image,
                dst.width.toFloat(), dst.height.toFloat(),
            )
            RenderPage.renderOverlay(pass, layer.image, dst, placeX, placeY, imageScale)
        }
    }
}

internal inline fun overlayPass(
    encoder: GPUCommandEncoder,
    dst: GPUTexture,
    draw: (GPURenderPassEncoder) -> Unit,
) {
    val pass = encoder.beginRenderPassWithColorView(dst) { view ->
        GPURenderPassDescriptor(
            colorAttachments = arrayOf(
                GPURenderPassColorAttachment(
                    view = view, loadOp = LoadOp.Load, storeOp = StoreOp.Store,
                    clearValue = GPUColor(0.0, 0.0, 0.0, 0.0),
                ),
            ),
        )
    }
    try {
        draw(pass)
    } finally {
        pass.endAndClose()
    }
}
