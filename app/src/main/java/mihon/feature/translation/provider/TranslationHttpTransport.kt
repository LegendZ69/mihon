package mihon.feature.translation.provider

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.source
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationLogSettings
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationProgressUnit
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.model.TranslationUsage
import tachiyomi.domain.translation.model.TranslationUsageRecord
import tachiyomi.domain.translation.model.accountingProvider
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class ProviderHttpResponse(
    val body: JsonObject,
    val headers: Headers,
    val code: Int,
    val capturePath: String? = null,
    val attemptId: String = UUID.randomUUID().toString(),
)

internal class TranslationHttpTransport(
    private val client: OkHttpClient,
    private val diagnostics: TranslationDiagnosticsStore,
    private val repository: TranslationRepository,
    private val temporaryDirectory: File,
) {
    suspend fun execute(
        request: Request,
        jobId: String,
        batchId: String?,
        operation: String,
        logs: TranslationLogSettings,
        attempt: Int,
        timeoutSeconds: Int,
        secrets: List<String>,
        settings: ProviderSettings? = null,
    ): ProviderHttpResponse {
        val attemptId = UUID.randomUUID().toString()
        val startedOperation = TranslationOperation(
            attemptId, jobId,
            if (operation == "countTokens") TranslationStage.TOKEN_COUNT else TranslationStage.REQUEST,
            parentId = batchId, batchId = batchId,
            reviewId = batchId.takeIf {
                operation == "qualityReview"
            },
            state = TranslationOperationState.ACTIVE,
            total = 1, unit = TranslationProgressUnit.REQUESTS, attempt = attempt,
            startedAt = System.currentTimeMillis(), message = operation,
            provider = settings?.accountingProvider(), model = settings?.model,
        )
        repository.saveOperation(startedOperation)
        var operationFailure: String? = null
        var interrupted = false
        var providerRequestId: String? = null
        if (settings != null && operation != "countTokens") {
            val caps = OfficialProviderCapabilities.forSettings(settings)
            val ceiling = if (caps.maxInputTokens > 0 && caps.maxOutputTokens > 0) {
                OfficialProviderPricing.estimate(
                    settings,
                    TranslationUsage(
                        inputTokens = caps.maxInputTokens,
                        outputTokens = (settings.maxOutputTokens ?: caps.maxOutputTokens).toLong(),
                    ),
                )
            } else {
                null
            }
            repository.saveUsage(
                TranslationUsageRecord(
                    attemptId, jobId, operationId = attemptId,
                    batchId = batchId,
                    reviewId = batchId.takeIf {
                        operation == "qualityReview"
                    },
                    provider = settings.accountingProvider(),
                    model = settings.model, time = startedOperation.startedAt!!,
                    reservedCurrency = ceiling?.let { "USD" }, reservedAmount = ceiling?.beforePromotionalCreditUsd,
                    pricingSource = ceiling?.price?.sourceUrl, pricingVerifiedAt = ceiling?.price?.verifiedAt,
                    outcomeUncertain = true,
                ),
            )
        }
        var captureSkipped: String? = null
        val capture = if (logs.enabled && logs.captureRaw) {
            try {
                diagnostics.start(jobId, batchId, operation, logs)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                captureSkipped = if (error is CaptureUnavailableException) {
                    error.message
                } else {
                    "Diagnostic storage is unavailable; sanitized capture skipped."
                }
                null
            }
        } else {
            null
        }
        val started = System.currentTimeMillis()
        var metadata = capture?.metadata?.copy(
            requestUrl = DiagnosticRedactor.message(DiagnosticRedactor.url(request.url), secrets),
            requestHeaders = DiagnosticRedactor.headers(request.headers, secrets),
            requestBytes = request.body?.contentLength() ?: 0,
            attempt = attempt,
        )
        var temporary: File? = null
        try {
            if (capture != null) {
                try {
                    diagnostics.update(capture, checkNotNull(metadata))
                } catch (_: Exception) {
                    diagnostics.recordFailure(capture, "Cannot update diagnostic metadata; sanitized capture omitted.")
                }
            }
            if (captureSkipped != null) {
                event(
                    logs,
                    TranslationEvent(
                        UUID.randomUUID().toString(),
                        jobId,
                        batchId,
                        level = "WARN",
                        stage = "CAPTURE_SKIPPED",
                        message = checkNotNull(captureSkipped),
                    ),
                )
            }
            val outgoing = if (capture != null && request.body != null) {
                val redactor = diagnostics.openSanitizedBody(capture, CaptureBody.REQUEST, secrets)
                val body = CapturingRequestBody(checkNotNull(request.body), redactor) { complete ->
                    diagnostics.bodyFinished(capture, CaptureBody.REQUEST, complete)
                }
                request.newBuilder().method(request.method, body).build()
            } else {
                request
            }
            event(
                logs,
                TranslationEvent(
                    id = UUID.randomUUID().toString(),
                    jobId = jobId,
                    batchId = batchId,
                    stage = operation,
                    operationId = attemptId,
                    message = "Request started (attempt $attempt)",
                    details = mapOf(
                        "url" to DiagnosticRedactor.message(DiagnosticRedactor.url(request.url), secrets),
                        "requestBytes" to (request.body?.contentLength() ?: 0).toString(),
                        "attempt" to attempt.toString(),
                    ) + request.tag(TranslationPromptDiagnostics::class.java)?.details(secrets).orEmpty(),
                    capturePath = capture?.directory?.absolutePath,
                ),
            )
            val response = client.newBuilder()
                .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .build().newCall(outgoing).await()
            response.use {
                val file = storage {
                    check(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs()) {
                        "Cannot create response cache."
                    }
                    File.createTempFile("translation-response-", ".json", temporaryDirectory).also { temporary = it }
                }
                metadata = metadata?.copy(
                    responseHeaders = DiagnosticRedactor.headers(it.headers, secrets),
                    responseCode = it.code,
                )
                if (capture != null) {
                    try {
                        diagnostics.update(capture, checkNotNull(metadata))
                    } catch (_: Exception) {
                        diagnostics.recordFailure(
                            capture,
                            "Cannot update diagnostic metadata; sanitized capture omitted.",
                        )
                    }
                }
                val capturedResponse = capture?.let { session ->
                    diagnostics.openSanitizedBody(session, CaptureBody.RESPONSE, secrets)
                }
                var responseComplete = false
                try {
                    StorageOutputStream(storage { file.outputStream() }).use { output ->
                        checkNotNull(it.body) { "Provider response body is missing." }.byteStream().use { input ->
                            copyBoundedProviderResponse(
                                input,
                                output,
                                capturedResponse,
                                MAX_PARSED_RESPONSE_BYTES,
                            ) { bytes ->
                                metadata = metadata?.copy(responseBytes = bytes)
                            }
                        }
                    }
                    responseComplete = true
                } finally {
                    capturedResponse?.finish(responseComplete)
                    capturedResponse?.close()
                    if (capture != null) diagnostics.bodyFinished(capture, CaptureBody.RESPONSE, responseComplete)
                }
                val requestId = (it.header("x-request-id") ?: it.header("x-goog-request-id"))
                    ?.let { id -> DiagnosticRedactor.message(id, secrets) }
                providerRequestId = requestId
                event(
                    logs,
                    TranslationEvent(
                        id = UUID.randomUUID().toString(),
                        jobId = jobId,
                        batchId = batchId,
                        level = if (it.isSuccessful) "INFO" else "ERROR",
                        stage = operation,
                        operationId = attemptId,
                        message = "HTTP ${it.code} in ${System.currentTimeMillis() - started} ms",
                        details = buildMap {
                            put("httpStatus", it.code.toString())
                            put("operationState", if (it.isSuccessful) "COMPLETED" else "FAILED")
                            put("responseBytes", file.length().toString())
                            put("durationMillis", (System.currentTimeMillis() - started).toString())
                            requestId?.let { id -> put("requestId", id) }
                            putAll(DiagnosticRedactor.headers(it.headers, secrets))
                        },
                        capturePath = capture?.directory?.absolutePath,
                    ),
                )
                if (!it.isSuccessful) {
                    val summary = file.bufferedReader().use { reader ->
                        val chars =
                            CharArray(
                                4000 + (DiagnosticRedactor.secretPatterns(secrets).maxOfOrNull(String::length) ?: 0),
                            )
                        var length = 0
                        while (length < chars.size) {
                            val read = reader.read(chars, length, chars.size - length)
                            if (read < 0) break
                            length += read
                        }
                        if (length > 0) String(chars, 0, length) else ""
                    }
                    throw ProviderHttpErrors.exception(
                        it.code,
                        it.headers,
                        DiagnosticRedactor.message(summary, secrets),
                    )
                }
                val payload = try {
                    file.source().buffer().use { source ->
                        TranslationWireFormat.json.decodeFromBufferedSource<JsonObject>(source)
                    }
                } catch (error: IOException) {
                    throw TranslationException(
                        TranslationFailureKind.STORAGE,
                        "Cannot read provider response from device storage.",
                        cause = error,
                    )
                } catch (_: Exception) {
                    throw TranslationException(
                        TranslationFailureKind.CONTENT,
                        "Provider returned invalid JSON.",
                        it.code,
                    )
                }
                val headers = Headers.Builder().apply {
                    DiagnosticRedactor.headers(it.headers, secrets).forEach { (name, value) -> add(name, value) }
                }.build()
                return ProviderHttpResponse(payload, headers, it.code, capture?.directory?.absolutePath, attemptId)
            }
        } catch (error: Exception) {
            val description = if (error is CancellationException) {
                "Cancelled"
            } else {
                DiagnosticRedactor.message(
                    error.message ?: error.javaClass.simpleName,
                    secrets,
                )
            }
            operationFailure = description
            interrupted = error is CancellationException
            metadata = metadata?.copy(error = description)
            throw error
        } finally {
            temporary?.delete()
            val completedMetadata = metadata
            if (capture != null && completedMetadata != null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        diagnostics.finish(capture, completedMetadata.copy(completedAt = System.currentTimeMillis()))
                        diagnostics.prune(logs)
                        if (capture.metadata.requestTruncated || capture.metadata.responseTruncated ||
                            capture.metadata.metadataTruncated
                        ) {
                            event(
                                logs,
                                TranslationEvent(
                                    UUID.randomUUID().toString(),
                                    jobId,
                                    batchId,
                                    level = "WARN",
                                    stage = "CAPTURE_TRUNCATED",
                                    message = "Sanitized API capture is incomplete",
                                    details = mapOf(
                                        "requestCapturedBytes" to capture.metadata.requestCapturedBytes.toString(),
                                        "responseCapturedBytes" to capture.metadata.responseCapturedBytes.toString(),
                                        "reason" to capture.metadata.captureNotes.joinToString("; "),
                                    ),
                                    capturePath = capture.directory.absolutePath,
                                ),
                            )
                        }
                    } catch (_: Exception) {
                        event(
                            logs,
                            TranslationEvent(
                                UUID.randomUUID().toString(),
                                jobId,
                                batchId,
                                level = "WARN",
                                stage = "CAPTURE_TRUNCATED",
                                message = "Diagnostic metadata could not be finalized; " +
                                    "the network result is preserved.",
                                capturePath = capture.directory.absolutePath,
                            ),
                        )
                    }
                }
            }
            withContext(NonCancellable) {
                val ended = maxOf(startedOperation.startedAt!!, System.currentTimeMillis())
                repository.saveOperation(
                    startedOperation.copy(
                        state = when {
                            interrupted -> TranslationOperationState.INTERRUPTED
                            operationFailure != null -> TranslationOperationState.FAILED
                            else -> TranslationOperationState.COMPLETED
                        },
                        completed = if (operationFailure == null) 1 else 0,
                        endedAt = ended,
                        updatedAt = ended,
                        message = operationFailure ?: operation,
                        requestId = providerRequestId,
                        captureId = capture?.metadata?.id,
                    ),
                )
            }
        }
    }

    private suspend fun event(settings: TranslationLogSettings, event: TranslationEvent) {
        if (settings.enabled) {
            try {
                repository.addEvent(event)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Optional diagnostics must not replace the provider response with a logging failure.
            }
        }
    }

    private companion object {
        const val MAX_PARSED_RESPONSE_BYTES = 64L * 1024 * 1024
    }
}

private inline fun <T> storage(block: () -> T): T = try {
    block()
} catch (error: IOException) {
    throw TranslationException(
        TranslationFailureKind.STORAGE,
        "Cannot write translation data. Check available device storage.",
        cause = error,
    )
} catch (error: IllegalStateException) {
    throw TranslationException(TranslationFailureKind.STORAGE, "Cannot access translation storage.", cause = error)
}

private class StorageOutputStream(private val delegate: OutputStream) : OutputStream() {
    override fun write(value: Int) = storage { delegate.write(value) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) = storage { delegate.write(bytes, offset, length) }
    override fun flush() = storage { delegate.flush() }
    override fun close() = storage { delegate.close() }
}

internal object ProviderHttpErrors {
    fun exception(status: Int, headers: Headers, body: String): TranslationException {
        val parsed = runCatching { TranslationWireFormat.json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = parsed?.get("error") as? JsonObject
        val message = (error?.get("message")?.jsonPrimitive?.contentOrNull ?: "Provider request failed").take(1000)
        val kind = when {
            status == 401 || status == 403 -> TranslationFailureKind.AUTHENTICATION
            status == 429 -> TranslationFailureKind.RATE_LIMIT
            status == 408 || status in 500..599 -> TranslationFailureKind.TRANSIENT
            status == 413 -> TranslationFailureKind.LIMIT
            status == 400 &&
                Regex(
                    "(?i)(token.{0,30}(limit|maximum|exceed)|context.{0,20}(length|limit)|request.{0," +
                        "30}(large|size)|image.{0,30}(limit|maximum|exceed))",
                ).containsMatchIn(message) -> TranslationFailureKind.LIMIT
            else -> TranslationFailureKind.CONFIGURATION
        }
        return TranslationException(kind, "$message (HTTP $status)", status, retryAfterMillis(headers["Retry-After"]))
    }

    fun retryAfterMillis(value: String?, now: Long = System.currentTimeMillis()): Long? {
        if (value == null) return null
        value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { return (it * 1000).toLong() }
        return runCatching {
            (SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)!!.time - now).coerceAtLeast(0)
        }.getOrNull()
    }
}

/** The original response spool is bounded even if the server omits Content-Length. */
internal fun copyBoundedProviderResponse(
    input: InputStream,
    output: OutputStream,
    capture: OutputStream?,
    maximumBytes: Long,
    progress: (Long) -> Unit = {},
): Long {
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maximumBytes - total + 1).toInt())
        if (count < 0) return total
        if (count == 0) continue
        val allowed = minOf(count.toLong(), maximumBytes - total).toInt()
        if (allowed > 0) {
            output.write(buffer, 0, allowed)
            capture?.write(buffer, 0, allowed)
        }
        total += count
        progress(total)
        if (total > maximumBytes) {
            throw TranslationException(
                TranslationFailureKind.LIMIT,
                "Provider response exceeds the 64 MiB parsing budget.",
            )
        }
    }
}
