package ca.mpreg.webgpuviewer.renderer

import androidx.webgpu.ErrorType
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPURequestCallback
import androidx.webgpu.InternalException
import androidx.webgpu.OutOfMemoryException
import androidx.webgpu.ValidationException
import androidx.webgpu.WebGpuRuntimeException
import ca.mpreg.webgpuviewer.renderer.ErrorScopes.pump
import kotlinx.coroutines.asExecutor
import java.util.concurrent.atomic.AtomicInteger

/** Error scopes popped without suspending. Their answers need events processed: see [pump]. */
internal object ErrorScopes {
    private val pending = AtomicInteger()

    /** Pops [device]'s innermost scope; [onError] gets the error type, on the render thread, if it caught one. */
    fun pop(device: GPUDevice, onError: (type: Int) -> Unit) {
        pending.incrementAndGet()
        device.popErrorScope(
            WebGpuRenderer.dispatcher.asExecutor(),
            object : GPURequestCallback<Int> {
                override fun onResult(result: Int) {
                    pending.decrementAndGet()
                    if (result != ErrorType.NoError) onError(result)
                }

                // Caught errors arrive here, not in onResult; anything else is a lost device failing
                // every pending pop.
                override fun onError(exception: Exception) {
                    pending.decrementAndGet()
                    if (exception is WebGpuRuntimeException) onError(errorType(exception))
                }
            })
    }

    private fun errorType(e: WebGpuRuntimeException): Int = when (e) {
        is ValidationException -> ErrorType.Validation
        is OutOfMemoryException -> ErrorType.OutOfMemory
        is InternalException -> ErrorType.Internal
        else -> ErrorType.Unknown
    }

    /** Lets pending pops answer. Once a frame, on the render thread. */
    fun pump() {
        if (pending.get() > 0 && WebGpuRenderer.isAvailable) WebGpuRenderer.instance.processEvents()
    }
}
