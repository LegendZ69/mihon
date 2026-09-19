package mihon.feature.translation.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FilterOutputStream
import java.util.Base64

internal data class WireImage(val file: File, val prefix: String = "")

/** Writes base64 in bounded chunks; never constructs a base64 chapter in the JVM heap. */
internal class StreamingJsonBody(
    private val value: JsonElement,
    private val images: Map<String, WireImage> = emptyMap(),
    val promptDiagnostics: TranslationPromptDiagnostics? = null,
) : RequestBody() {
    override fun contentType() = "application/json; charset=utf-8".toMediaType()

    override fun contentLength(): Long = length(value)

    override fun writeTo(sink: BufferedSink) = write(value, sink)

    private fun length(element: JsonElement): Long = when (element) {
        is JsonObject -> 2L + element.entries.sumOf { (key, value) ->
            JsonPrimitive(key).toString().toByteArray(Charsets.UTF_8).size + 1L + length(value)
        } + (element.size - 1).coerceAtLeast(0)
        is JsonArray -> 2L + element.sumOf(::length) + (element.size - 1).coerceAtLeast(0)
        is JsonPrimitive -> images[element.content]?.takeIf { element.isString }?.let {
            2L + it.prefix.toByteArray(Charsets.UTF_8).size + ((it.file.length() + 2) / 3) * 4
        } ?: element.toString().toByteArray(Charsets.UTF_8).size.toLong()
    }

    private fun write(element: JsonElement, sink: BufferedSink) {
        when (element) {
            is JsonObject -> {
                sink.writeByte('{'.code)
                element.entries.forEachIndexed { index, (key, value) ->
                    if (index > 0) sink.writeByte(','.code)
                    sink.writeUtf8(JsonPrimitive(key).toString())
                    sink.writeByte(':'.code)
                    write(value, sink)
                }
                sink.writeByte('}'.code)
            }
            is JsonArray -> {
                sink.writeByte('['.code)
                element.forEachIndexed { index, value ->
                    if (index > 0) sink.writeByte(','.code)
                    write(value, sink)
                }
                sink.writeByte(']'.code)
            }
            is JsonPrimitive -> {
                val image = images[element.content]?.takeIf { element.isString }
                if (image == null) {
                    sink.writeUtf8(element.toString())
                } else {
                    sink.writeByte('"'.code)
                    sink.writeUtf8(image.prefix)
                    val output = object : FilterOutputStream(sink.outputStream()) {
                        override fun close() = flush()
                        override fun write(
                            bytes: ByteArray,
                            offset: Int,
                            length: Int,
                        ) = out.write(bytes, offset, length)
                    }
                    Base64.getEncoder().wrap(output).use { encoder ->
                        image.file.inputStream().use { input -> input.copyTo(encoder, 32 * 1024) }
                    }
                    sink.writeByte('"'.code)
                }
            }
        }
    }
}
