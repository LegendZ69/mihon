package mihon.feature.translation.provider

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.InputStream
import java.io.OutputStream

/** Mirrors only bytes emitted by the original request body; no additional chapter-sized spool is created. */
internal class CapturingRequestBody(
    private val original: RequestBody,
    private val capture: CaptureOutputStream,
    private val finished: (Boolean) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = original.contentType()
    override fun contentLength(): Long = original.contentLength()
    override fun isOneShot(): Boolean = original.isOneShot()
    override fun isDuplex(): Boolean = original.isDuplex()

    override fun writeTo(sink: BufferedSink) {
        var complete = false
        try {
            val tee = object : ForwardingSink(sink) {
                override fun write(source: Buffer, byteCount: Long) {
                    val snapshot = Buffer()
                    source.copyTo(snapshot, 0, byteCount)
                    super.write(source, byteCount)
                    while (snapshot.size > 0) {
                        capture.write(snapshot.readByteArray(minOf(snapshot.size, 8192)))
                    }
                }
            }.buffer()
            original.writeTo(tee)
            tee.flush()
            complete = true
        } finally {
            capture.finish(complete)
            capture.close()
            finished(complete)
        }
    }
}

/** Holds a possible secret prefix until its following bytes arrive, including at a capture-budget boundary. */
internal class SecretRedactingOutputStream(
    private val output: OutputStream,
    secrets: List<String>,
) : CaptureOutputStream() {
    private val patterns = DiagnosticRedactor.secretPatterns(secrets).map { it.toByteArray(Charsets.UTF_8) }
        .sortedByDescending { it.size }
    private val keep = (patterns.firstOrNull()?.size ?: 1) - 1
    private var pending = ByteArray(0)
    private var finished = false

    override fun write(value: Int) = write(byteArrayOf(value.toByte()))

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "Capture redactor is already finished." }
        if (patterns.isEmpty()) {
            output.write(bytes, offset, length)
            return
        }
        // A caller can supply a large array; bound the redaction window independently of its size.
        var cursor = offset
        while (cursor < offset + length) {
            val count = minOf(8192, offset + length - cursor)
            val combined = ByteArray(pending.size + count)
            pending.copyInto(combined)
            bytes.copyInto(combined, pending.size, cursor, cursor + count)
            pending = combined
            emit(complete = false)
            cursor += count
        }
    }

    private fun emit(complete: Boolean) {
        val until = if (complete) pending.size else (pending.size - keep).coerceAtLeast(0)
        var cursor = 0
        var literalStart = 0
        while (cursor < until) {
            val match = patterns.firstOrNull { pattern ->
                cursor + pattern.size <= pending.size && pattern.indices.all { pending[cursor + it] == pattern[it] }
            }
            if (match == null) {
                cursor++
            } else {
                output.write(pending, literalStart, cursor - literalStart)
                output.write(REDACTED)
                cursor += match.size
                literalStart = cursor
            }
        }
        output.write(pending, literalStart, cursor - literalStart)
        pending = pending.copyOfRange(cursor, pending.size)
    }

    override fun finish(complete: Boolean) {
        if (finished) return
        if (complete) emit(complete = true)
        // On an interrupted body, a buffered prefix might be the start of a credential; omit it.
        pending.fill(0)
        pending = ByteArray(0)
        finished = true
        output.flush()
    }

    override fun flush() = output.flush()
    override fun close() {
        finish(complete = true)
        output.close()
    }

    private companion object {
        val REDACTED = "[redacted]".toByteArray(Charsets.UTF_8)
    }
}

internal object SecretStreamRedactor {
    fun copy(input: InputStream, output: OutputStream, secrets: List<String>) {
        val redactor = SecretRedactingOutputStream(output, secrets)
        var complete = false
        try {
            input.copyTo(redactor, 8192)
            complete = true
        } finally {
            redactor.finish(complete)
        }
    }
}
