package mihon.feature.translation.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Diagnostic policy, deliberately separate from the bytes sent to or parsed from the provider. */
internal const val SANITIZED_CAPTURE_POLICY = "sanitized-v1"

@Serializable
data class CaptureSanitization(
    val policy: String = SANITIZED_CAPTURE_POLICY,
    val complete: Boolean,
    val sourceBytes: Long,
    val sourceSha256: String,
    val omittedValues: Int,
    val reason: String? = null,
)

internal abstract class CaptureOutputStream : OutputStream() {
    abstract fun finish(complete: Boolean)
}

/**
 * Tokenizes strings as bytes arrive and drops binary/sensitive values without retaining their contents.
 * Only bounded, fully validated, sanitized JSON reaches [output]. There is no raw diagnostic spool.
 * An invalid, oversized or interrupted input emits a safe incomplete manifest instead of a raw prefix.
 */
internal class SanitizedCaptureOutputStream(
    private val output: OutputStream,
    private val secrets: Collection<String> = emptyList(),
    private val completed: (CaptureSanitization) -> Unit = {},
) : CaptureOutputStream() {
    private data class Frame(val objectValue: Boolean, var key: String? = null, var expectsKey: Boolean = objectValue)

    private val json = Json { isLenient = false }
    private val frames = ArrayDeque<Frame>()
    private val retained = ByteArrayOutputStream()
    private val sourceDigest = MessageDigest.getInstance("SHA-256")
    private var sourceBytes = 0L
    private var failure: String? = null
    private var finished = false
    private var omittedValues = 0
    private var string: ByteArrayOutputStream? = null
    private var stringDigest: MessageDigest? = null
    private var stringBytes = 0L
    private var stringIsKey = false
    private var stringOmission: String? = null
    private var escaped = false
    private var unicodeRemaining = 0
    private var utf8Remaining = 0
    private var utf8CodePoint = 0
    private var utf8Minimum = 0

    override fun write(value: Int) = write(byteArrayOf(value.toByte()))

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (finished) return // A transport retry must never fail because optional diagnostics were already closed.
        sourceBytes += length
        sourceDigest.update(bytes, offset, length)
        if (sourceBytes > MAX_SOURCE_BYTES) fail("Input exceeded the diagnostic processing limit.")
        if (failure != null) return
        try {
            for (index in offset until offset + length) {
                val value = bytes[index].toInt() and 255
                validateUtf8(value)
                if (stringDigest != null) {
                    stringByte(value)
                } else {
                    when (value.toChar()) {
                        '"' -> startString()
                        '{', '[' -> {
                            check(frames.size < MAX_DEPTH) { "JSON nesting exceeds the diagnostic limit." }
                            append(value)
                            frames.addLast(Frame(value == '{'.code))
                        }
                        '}', ']' -> {
                            check(frames.isNotEmpty()) { "Invalid JSON container." }
                            val frame = frames.removeLast()
                            check(frame.objectValue == (value == '}'.code)) { "Mismatched JSON container." }
                            append(value)
                        }
                        ',' -> {
                            frames.lastOrNull()?.takeIf { it.objectValue }?.apply {
                                key = null
                                expectsKey = true
                            }
                            append(value)
                        }
                        else -> append(value)
                    }
                }
            }
        } catch (_: Exception) {
            fail("Malformed or oversized JSON body; raw content was omitted.")
        }
    }

    private fun startString() {
        stringIsKey = frames.lastOrNull()?.let { it.objectValue && it.expectsKey } == true
        stringOmission = if (stringIsKey) null else omissionReason(frames.lastOrNull()?.key)
        string = if (stringOmission == null) ByteArrayOutputStream() else null
        stringDigest = MessageDigest.getInstance("SHA-256")
        stringBytes = 0
        escaped = false
        unicodeRemaining = 0
        recordStringByte('"'.code)
    }

    private fun recordStringByte(value: Int) {
        stringBytes++
        stringDigest!!.update(value.toByte())
        string?.write(value)
        check(string == null || string!!.size() <= if (stringIsKey) MAX_KEY_BYTES else MAX_STRING_BYTES) {
            "Retained JSON string exceeded the diagnostic limit."
        }
    }

    private fun stringByte(value: Int) {
        recordStringByte(value)
        if (unicodeRemaining > 0) {
            check(value.toChar() in "0123456789abcdefABCDEF") { "Invalid JSON Unicode escape." }
            unicodeRemaining--
            return
        }
        if (escaped) {
            check(value.toChar() in "\"\\/bfnrtu") { "Invalid JSON escape." }
            escaped = false
            if (value == 'u'.code) unicodeRemaining = 4
            return
        }
        check(value >= 32) { "Invalid control character in JSON string." }
        if (value == '\\'.code) {
            escaped = true
            return
        }
        if (value == '"'.code) {
            finishString()
        } else if (!stringIsKey && stringOmission == null && stringBytes == 6L &&
            string!!.toString(Charsets.UTF_8.name()).equals("\"data:", ignoreCase = true)
        ) {
            stringOmission = "embedded_data"
            string?.reset()
            string = null
        }
    }

    private fun finishString() {
        val omission = stringOmission
        if (omission != null) {
            omittedValues++
            append(
                buildJsonObject {
                    put("_omitted", true)
                    put("reason", omission)
                    put("encodedBytes", stringBytes)
                    if (omission != "credential") {
                        put("sha256", stringDigest!!.digest().toHex())
                        put("hashEncoding", "json-string-utf8")
                    }
                }.toString(),
            )
        } else {
            val text = json.parseToJsonElement(string!!.toString(Charsets.UTF_8.name())).jsonPrimitive.content
            if (stringIsKey) {
                frames.last().apply {
                    key = text
                    expectsKey = false
                }
                append(JsonPrimitive(safeText(text)).toString())
            } else {
                append(JsonPrimitive(safeText(text)).toString())
            }
        }
        string = null
        stringDigest = null
    }

    private fun validateUtf8(value: Int) {
        if (utf8Remaining > 0) {
            check(value in 0x80..0xbf) { "Invalid UTF-8 continuation." }
            utf8CodePoint = (utf8CodePoint shl 6) or (value and 0x3f)
            if (--utf8Remaining == 0) {
                check(
                    utf8CodePoint >= utf8Minimum && utf8CodePoint <= 0x10ffff &&
                        utf8CodePoint !in 0xd800..0xdfff,
                ) { "Invalid UTF-8 code point." }
            }
        } else if (value >= 0x80) {
            when (value) {
                in 0xc2..0xdf -> {
                    utf8Remaining = 1
                    utf8CodePoint = value and 0x1f
                    utf8Minimum = 0x80
                }
                in 0xe0..0xef -> {
                    utf8Remaining = 2
                    utf8CodePoint = value and 0x0f
                    utf8Minimum = 0x800
                }
                in 0xf0..0xf4 -> {
                    utf8Remaining = 3
                    utf8CodePoint = value and 7
                    utf8Minimum = 0x10000
                }
                else -> error("Invalid UTF-8 leading byte.")
            }
        }
    }

    private fun append(value: Int) {
        check(retained.size() < MAX_RETAINED_BYTES) { "Sanitized JSON exceeds the diagnostic limit." }
        retained.write(value)
    }

    private fun append(value: String) {
        val bytes = value.toByteArray()
        check(retained.size() + bytes.size <= MAX_RETAINED_BYTES) { "Sanitized JSON exceeds the diagnostic limit." }
        retained.write(bytes)
    }

    private fun safeText(value: String): String = DiagnosticRedactor.bodyText(value, secrets)

    private fun sanitizeNested(value: JsonElement, depth: Int = 0): JsonElement {
        check(depth < MAX_DEPTH) { "Nested JSON exceeds the diagnostic limit." }
        return when (value) {
            is JsonObject -> JsonObject(
                value.entries.associate { (key, item) ->
                    val reason = omissionReason(key)
                    val sanitized = when {
                        // Accept only a complete canonical metadata shape, never a provider's arbitrary _omitted flag.
                        reason != null && canonicalOmission(item) -> item
                        reason != null && shouldOmitValue(key, item) -> omitValue(item, reason)
                        else -> sanitizeNested(item, depth + 1)
                    }
                    safeText(key) to sanitized
                },
            )
            is JsonArray -> JsonArray(value.map { sanitizeNested(it, depth + 1) })
            is JsonPrimitive -> if (value.isString) {
                val text = value.content
                val candidate = text.trimStart(' ', '\n', '\r', '\t')
                val nested = if (candidate.startsWith('{') || candidate.startsWith('[')) {
                    runCatching { json.parseToJsonElement(candidate) }.getOrNull()
                } else {
                    null
                }
                JsonPrimitive(if (nested != null) sanitizeNested(nested, depth + 1).toString() else safeText(text))
            } else {
                value
            }
        }
    }

    private fun shouldOmitValue(key: String, item: JsonElement): Boolean {
        val normalized = key.lowercase().filter(Char::isLetterOrDigit)
        // This existing checkpoint name is also a task-prompt stage. Only the exact non-executable
        // prompt pair shape is configuration; extra checkpoint fields remain omitted in full.
        if (normalized == "geometrycorrection") {
            return item !is JsonObject || item.keys.any { it !in setOf("system", "user") } ||
                item.values.any { it !is JsonNull && (it !is JsonPrimitive || !it.isString) }
        }
        // Usage/list APIs also have a generic data array. Only its scalar binary representation is opaque.
        if (normalized != "data") return true
        if (item is JsonObject) return false
        if (item is JsonArray) {
            return item.isNotEmpty() && item.all {
                val primitive = it as? JsonPrimitive
                primitive != null && !primitive.isString &&
                    primitive.longOrNull?.let { count -> count in 0L..255L } == true
            }
        }
        return true
    }

    private fun omitValue(value: JsonElement, reason: String): JsonObject {
        omittedValues++
        val encoded = value.toString().toByteArray()
        return buildJsonObject {
            put("_omitted", true)
            put("reason", reason)
            put("encodedBytes", encoded.size)
            if (reason != "credential") {
                put("sha256", MessageDigest.getInstance("SHA-256").digest(encoded).toHex())
                put("hashEncoding", if (value is JsonPrimitive && value.isString) "json-string-utf8" else "json-utf8")
            }
        }
    }

    private fun canonicalOmission(value: JsonElement): Boolean {
        val item = value as? JsonObject ?: return false
        val marker = item["_omitted"] as? JsonPrimitive ?: return false
        if (marker.isString || marker.content != "true") return false
        val reason = (item["reason"] as? JsonPrimitive)?.content ?: return false
        if (reason !in
            setOf("credential", "image_or_binary_data", "embedded_data", "thought_signature", "resumable_work")
        ) {
            return false
        }
        val size = item["encodedBytes"] as? JsonPrimitive ?: return false
        if (size.isString || (size.longOrNull ?: -1) < 0) return false
        if (reason == "credential") return item.keys == setOf("_omitted", "reason", "encodedBytes")
        return item.keys == setOf("_omitted", "reason", "encodedBytes", "sha256", "hashEncoding") &&
            (item["sha256"] as? JsonPrimitive)?.content?.matches(Regex("[0-9a-f]{64}")) == true &&
            (item["hashEncoding"] as? JsonPrimitive)?.content in setOf("json-string-utf8", "json-utf8")
    }

    private fun fail(reason: String) {
        if (failure == null) failure = reason
        retained.reset()
        string?.reset()
        string = null
    }

    override fun finish(complete: Boolean) {
        if (finished) return
        finished = true
        if (!complete) fail("Body interrupted before sanitized capture completed.")
        if (stringDigest != null || frames.isNotEmpty() || utf8Remaining != 0) {
            fail("Incomplete JSON body; raw content was omitted.")
        }
        var encoded: ByteArray? = null
        if (failure == null) {
            try {
                val parsed = json.parseToJsonElement(retained.toString(Charsets.UTF_8.name()))
                check(parsed is JsonObject || parsed is JsonArray) { "Provider body is not structured JSON." }
                encoded = sanitizeNested(parsed).toString().toByteArray()
                check(encoded.size <= MAX_RETAINED_BYTES) { "Sanitized JSON exceeds the diagnostic limit." }
            } catch (_: Exception) {
                fail("Malformed or oversized JSON body; raw content was omitted.")
                encoded = null
            }
        }
        val summary = CaptureSanitization(
            complete = failure == null,
            sourceBytes = sourceBytes,
            sourceSha256 = sourceDigest.digest().toHex(),
            omittedValues = omittedValues,
            reason = failure,
        )
        try {
            output.write(
                encoded ?: buildJsonObject {
                    put(
                        "_capture",
                        buildJsonObject {
                            put("policy", SANITIZED_CAPTURE_POLICY)
                            put("incomplete", true)
                            put("reason", failure)
                            put("sourceBytes", sourceBytes)
                        },
                    )
                }.toString().toByteArray(),
            )
            output.flush()
        } finally {
            retained.reset()
            completed(summary)
        }
    }

    override fun flush() = Unit // Never expose an unvalidated prefix to disk or an export destination.
    override fun close() {
        finish(complete = false)
        output.close()
    }

    private companion object {
        const val MAX_RETAINED_BYTES = 2 * 1024 * 1024
        const val MAX_STRING_BYTES = 256 * 1024
        const val MAX_KEY_BYTES = 1024
        const val MAX_SOURCE_BYTES = 1024L * 1024 * 1024
        const val MAX_DEPTH = 64

        fun omissionReason(key: String?): String? = when (key?.lowercase()?.filter(Char::isLetterOrDigit)) {
            "thoughtsignature", "thoughtsignatures", "encryptedcontent" -> "thought_signature"
            "geometrycorrection" -> "resumable_work"
            "data", "image", "b64json", "base64", "filedata", "imagebytes", "binary", "modelbytes" ->
                "image_or_binary_data"
            "authorization", "apikey", "privatekey", "secret", "accesstoken", "refreshtoken", "idtoken",
            "assertion", "credential", "credentials", "password", "token", "clientsecret", "secretkey",
            "xapikey", "xgoogapikey",
            -> "credential"
            else -> null
        }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

/** The same omission policy also protects legacy event previews and JSONL exports. */
internal fun sanitizedDiagnosticRecord(serialized: String): String {
    val output = ByteArrayOutputStream()
    SanitizedCaptureOutputStream(output).use { sanitizer ->
        sanitizer.write(serialized.toByteArray())
        sanitizer.finish(true)
    }
    return Json.parseToJsonElement(output.toString(Charsets.UTF_8.name())).toString()
}
