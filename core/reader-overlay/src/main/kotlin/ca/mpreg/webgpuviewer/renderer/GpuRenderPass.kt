package ca.mpreg.webgpuviewer.renderer

import androidx.webgpu.GPUCommandEncoder
import androidx.webgpu.GPURenderPassDescriptor
import androidx.webgpu.GPURenderPassEncoder
import androidx.webgpu.GPUTexture
import androidx.webgpu.GPUTextureView

/** The pass retains its attachments; release the caller's temporary color-view reference. */
internal inline fun GPUCommandEncoder.beginRenderPassWithColorView(
    texture: GPUTexture,
    descriptor: (GPUTextureView) -> GPURenderPassDescriptor,
): GPURenderPassEncoder = texture.createView().use { view ->
    beginRenderPass(descriptor(view))
}

/** Ending recording does not release the native encoder reference. */
internal fun GPURenderPassEncoder.endAndClose() = use { it.end() }
