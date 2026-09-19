package mihon.feature.translation.ocr

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.feature.translation.provider.CaptureBody
import mihon.feature.translation.provider.CaptureSession
import mihon.feature.translation.provider.DiagnosticRedactor
import mihon.feature.translation.provider.TranslationDiagnosticsStore
import okhttp3.Request
import okhttp3.Response
import tachiyomi.domain.translation.model.TranslationLogSettings

/** Response metadata only. Model bytes never enter the diagnostic pipeline. */
internal data class PaddleHttpSnapshot(
    val responses: List<PaddleHttpResponse>,
    val errorBody: ByteArray? = null,
    val errorBodyComplete: Boolean = true,
)

internal data class PaddleHttpResponse(
    val url: String,
    val status: Int,
    val headers: Map<String, String>,
) {
    companion object {
        fun chain(response: Response): List<PaddleHttpResponse> = generateSequence(response) { it.priorResponse }
            .take(32).toList().asReversed().map {
                PaddleHttpResponse(
                    DiagnosticRedactor.url(it.request.url),
                    it.code,
                    DiagnosticRedactor.headers(it.headers),
                )
            }
    }
}

internal class PaddleDownloadCapture private constructor(
    private val store: TranslationDiagnosticsStore,
    private val session: CaptureSession,
) {
    val id get() = session.metadata.id
    val directory get() = session.directory.absolutePath

    fun finish(
        response: PaddleHttpSnapshot?,
        received: Long,
        sha256: String?,
        error: Throwable?,
    ) {
        try {
            val final = response?.responses?.lastOrNull()
            val metadata = session.metadata.copy(
                completedAt = System.currentTimeMillis(),
                responseCode = final?.status,
                responseHeaders = final?.headers.orEmpty(),
                responseBytes = response?.errorBody?.size?.toLong() ?: received,
                error = error?.let {
                    if (it is kotlin.coroutines.cancellation.CancellationException) {
                        "Cancelled"
                    } else {
                        DiagnosticRedactor.bodyText(it.message ?: it.javaClass.simpleName)
                    }
                },
            )
            store.update(session, metadata)
            val body = response?.errorBody
            if (body != null && !response.errorBodyComplete) {
                session.notes +=
                    "Error responseBytes counts only the bounded observed prefix; total body size is unavailable."
            }
            if (body == null) {
                session.notes += "Artifact bytes are omitted before sanitization; sanitizer hashes describe the " +
                    "omission manifest. The artifact SHA-256 is recorded separately."
            }
            val complete = if (body != null) response.errorBodyComplete else error == null
            val encoded = body ?: buildJsonObject {
                put(
                    "_artifactContent",
                    buildJsonObject {
                        put("omitted", true)
                        put("reason", "Model artifact contents remain in model storage, outside API captures")
                        put("receivedBytes", received)
                        sha256?.let { put("sha256", it) }
                        put("transferComplete", complete)
                    },
                )
            }.toString().toByteArray()
            store.openSanitizedBody(session, CaptureBody.RESPONSE).use { stream ->
                stream.write(encoded)
                stream.finish(complete)
            }
            store.bodyFinished(session, CaptureBody.RESPONSE, complete)
        } catch (_: Exception) {
            store.recordFailure(session, "Model-download capture could not be completed")
        } finally {
            store.finish(session, session.metadata)
        }
    }

    companion object {
        fun start(
            store: TranslationDiagnosticsStore?,
            context: PaddleOperationContext,
            request: Request,
            settings: TranslationLogSettings,
        ): PaddleDownloadCapture? {
            if (store == null || !settings.captureRaw) return null
            val session = store.start(context.jobId, null, "modelArtifact", settings)
            try {
                session.notes += "GET has no request body; request.json contains descriptive metadata only."
                store.update(
                    session,
                    session.metadata.copy(
                        requestUrl = DiagnosticRedactor.url(request.url),
                        requestHeaders = DiagnosticRedactor.headers(request.headers),
                    ),
                )
                val encoded = "{\"_requestBody\":\"GET request has no body\"}".toByteArray()
                store.openSanitizedBody(session, CaptureBody.REQUEST).use { stream ->
                    stream.write(encoded)
                    stream.finish(true)
                }
                store.bodyFinished(session, CaptureBody.REQUEST, true)
                return PaddleDownloadCapture(store, session)
            } catch (error: Exception) {
                store.recordFailure(session, "Model-download capture could not be prepared")
                store.finish(
                    session,
                    session.metadata.copy(
                        completedAt = System.currentTimeMillis(),
                        error = "Model-download capture preparation failed",
                    ),
                )
                throw error
            }
        }
    }
}
