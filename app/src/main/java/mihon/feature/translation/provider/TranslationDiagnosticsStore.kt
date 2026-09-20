package mihon.feature.translation.provider

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.translation.model.TranslationLogSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@SingleIn(AppScope::class)
class TranslationDiagnosticsStore internal constructor(private val directory: File) {
    @Inject
    constructor(context: Context) : this(File(context.noBackupFilesDir, "translation/diagnostics"))

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val active = mutableSetOf<String>()
    private var accountedBytes = -1L
    private var budgetBytes = Long.MAX_VALUE

    internal fun start(
        jobId: String,
        batchId: String?,
        operation: String,
        settings: TranslationLogSettings = TranslationLogSettings(),
    ): CaptureSession = synchronized(active) {
        initializeAccounting()
        val requestedBudget = settings.maxStorageMb.coerceAtLeast(1) * 1024L * 1024L
        budgetBytes = if (active.isEmpty()) requestedBudget else minOf(budgetBytes, requestedBudget)
        pruneLocked(settings, METADATA_RESERVATION)
        if (accountedBytes + METADATA_RESERVATION > budgetBytes) {
            throw CaptureUnavailableException(
                "Capture storage budget is occupied by active captures; raw capture skipped.",
            )
        }
        val id = UUID.randomUUID().toString()
        val folder = File(directory, id)
        val session = CaptureSession(
            folder,
            CaptureMetadata(id, jobId, batchId, operation, capturePolicy = SANITIZED_CAPTURE_POLICY),
            settings,
        )
        check(folder.mkdirs()) { "Cannot create request capture." }
        active.add(id)
        accountedBytes += METADATA_RESERVATION
        try {
            updateLocked(session, session.metadata)
            session
        } catch (error: Exception) {
            active.remove(id)
            accountedBytes += folderBytes(folder) - METADATA_RESERVATION
            throw CaptureUnavailableException("Cannot write capture metadata; raw capture skipped.", error)
        }
    }

    internal fun update(session: CaptureSession, metadata: CaptureMetadata) = synchronized(active) {
        updateLocked(session, metadata)
    }

    private fun updateLocked(session: CaptureSession, metadata: CaptureMetadata) {
        var value = metadata.copy(
            requestCapturedBytes = session.requestCapturedBytes,
            responseCapturedBytes = session.responseCapturedBytes,
            requestTruncated = session.requestTruncated,
            responseTruncated = session.responseTruncated,
            requestBodyCompleted = session.requestBodyCompleted,
            responseBodyCompleted = session.responseBodyCompleted,
            captureNotes = session.notes.toList(),
            metadataTruncated = metadata.metadataTruncated || session.metadata.metadataTruncated,
            requestSanitization = session.requestSanitization,
            responseSanitization = session.responseSanitization,
        )
        var encoded = json.encodeToString(value).toByteArray()
        if (encoded.size > MAX_METADATA_BYTES) {
            // Keep a bounded manifest even when a server returns exceptionally large headers.
            value = value.copy(
                requestHeaders = emptyMap(),
                responseHeaders = emptyMap(),
                metadataTruncated = true,
                state = if (value.completedAt != null && value.error == null) "TRUNCATED" else value.state,
                requestUrl = value.requestUrl?.take(2048),
                error = value.error?.take(1024),
            )
            encoded = json.encodeToString(value).toByteArray()
        }
        check(encoded.size <= MAX_METADATA_BYTES) { "Capture metadata exceeds its reserved storage." }
        session.metadata = value
        val target = File(session.directory, "metadata.json")
        val temporary = File(session.directory, "metadata.tmp")
        try {
            temporary.writeBytes(encoded)
            check(temporary.renameTo(target)) { "Cannot save capture metadata." }
        } finally {
            temporary.delete()
        }
    }

    internal fun openBody(session: CaptureSession, body: CaptureBody): OutputStream = object : OutputStream() {
        private var output: OutputStream? = null
        private val file = File(session.directory, body.fileName)
        private var closed = false

        override fun write(value: Int) = write(byteArrayOf(value.toByte()))

        override fun write(bytes: ByteArray, offset: Int, length: Int) = synchronized(active) {
            if (length == 0 || (body == CaptureBody.REQUEST && session.requestTruncated) ||
                (body == CaptureBody.RESPONSE && session.responseTruncated)
            ) {
                return@synchronized
            }
            if (closed || session.disabled || session.metadata.id !in active) {
                truncateLocked(session, body, "Capture stream closed before all body bytes were recorded.")
                return@synchronized
            }
            if (accountedBytes + length > budgetBytes) pruneLocked(session.settings, length.toLong())
            val allowed = minOf(length.toLong(), (budgetBytes - accountedBytes).coerceAtLeast(0)).toInt()
            if (allowed > 0) {
                val before = file.length()
                try {
                    val sink = output ?: file.outputStream().also { output = it }
                    sink.write(bytes, offset, allowed)
                    accountWritten(session, body, allowed.toLong())
                } catch (error: Exception) {
                    accountWritten(session, body, (file.length() - before).coerceAtLeast(0))
                    session.disabled = true
                    truncateLocked(session, body, "Diagnostic storage error; remaining raw body omitted.")
                }
            }
            if (allowed < length) {
                truncateLocked(session, body, "Capture storage budget reached; remaining raw body omitted.")
            }
        }

        override fun flush() = synchronized(active) {
            try {
                output?.flush()
            } catch (_: Exception) {
                session.disabled = true
                truncateLocked(session, body, "Diagnostic storage error while flushing the raw body.")
            }
            Unit
        }

        override fun close() = synchronized(active) {
            if (!closed) {
                closed = true
                try {
                    output?.close()
                } catch (_: Exception) {
                    session.disabled = true
                    truncateLocked(session, body, "Diagnostic storage error while closing the raw body.")
                }
            }
        }
    }

    internal fun openSanitizedBody(
        session: CaptureSession,
        body: CaptureBody,
        secrets: Collection<String> = emptyList(),
    ): CaptureOutputStream = SanitizedCaptureOutputStream(openBody(session, body), secrets) { summary ->
        synchronized(active) {
            if (body ==
                CaptureBody.REQUEST
            ) {
                session.requestSanitization = summary
            } else {
                session.responseSanitization = summary
            }
            if (!summary.complete) {
                truncateLocked(session, body, summary.reason ?: "Sanitized API capture is incomplete.")
            }
            runCatching { updateLocked(session, session.metadata) }
        }
    }

    private fun accountWritten(session: CaptureSession, body: CaptureBody, count: Long) {
        accountedBytes += count
        if (body ==
            CaptureBody.REQUEST
        ) {
            session.requestCapturedBytes += count
        } else {
            session.responseCapturedBytes += count
        }
    }

    private fun truncateLocked(session: CaptureSession, body: CaptureBody, reason: String) {
        val changed = if (body == CaptureBody.REQUEST) !session.requestTruncated else !session.responseTruncated
        if (body == CaptureBody.REQUEST) session.requestTruncated = true else session.responseTruncated = true
        if (reason !in session.notes && session.notes.size < 8) session.notes += reason
        if (changed) runCatching { updateLocked(session, session.metadata) }
    }

    internal fun bodyFinished(session: CaptureSession, body: CaptureBody, complete: Boolean) = synchronized(active) {
        if (body ==
            CaptureBody.REQUEST
        ) {
            session.requestBodyCompleted = complete
        } else {
            session.responseBodyCompleted = complete
        }
        if (!complete) truncateLocked(session, body, "Body interrupted before capture completed.")
        runCatching { updateLocked(session, session.metadata) }
        Unit
    }

    internal fun recordFailure(session: CaptureSession, reason: String) = synchronized(active) {
        session.disabled = true
        truncateLocked(session, CaptureBody.REQUEST, reason)
        truncateLocked(session, CaptureBody.RESPONSE, reason)
    }

    internal fun finish(session: CaptureSession, metadata: CaptureMetadata) = synchronized(active) {
        try {
            val state = when {
                metadata.error == "Cancelled" -> "CANCELLED"
                metadata.error != null -> "FAILED"
                session.requestTruncated || session.responseTruncated || session.disabled ||
                    session.metadata.metadataTruncated -> "TRUNCATED"
                else -> "COMPLETED"
            }
            updateLocked(session, metadata.copy(state = state))
        } finally {
            if (active.remove(session.metadata.id)) {
                accountedBytes += folderBytes(session.directory) - METADATA_RESERVATION -
                    session.requestCapturedBytes - session.responseCapturedBytes
            }
        }
    }

    suspend fun list(): List<CaptureMetadata> = withContext(Dispatchers.IO) { readCaptures() }
    suspend fun directoryFor(id: String): File = withContext(Dispatchers.IO) { captureDirectory(id) }

    /** Inspection applies today's policy even to legacy on-disk captures; original files are never rewritten. */
    suspend fun readSanitizedBody(
        id: String,
        fileName: String,
        maximumCharacters: Int = 65_536,
    ): String = withContext(Dispatchers.IO) {
        require(fileName in setOf("metadata.json", "request.json", "response.json")) { "Invalid capture body." }
        val output = ByteArrayOutputStream()
        if (fileName == "metadata.json") {
            val metadata = readCaptures().firstOrNull { it.id == id } ?: error("Capture metadata is unavailable.")
            json.encodeToString(exportMetadata(metadata)).byteInputStream().use { sanitizeCopy(it, output) }
        } else {
            File(captureDirectory(id), fileName).inputStream().use { sanitizeCopy(it, output) }
        }
        val value = output.toString(Charsets.UTF_8.name())
        val maximum = maximumCharacters.coerceIn(1, 1024 * 1024)
        if (value.length > maximum) {
            value.take(maximum) + "\n[Display truncated; export includes all available sanitized content.]"
        } else {
            value
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        synchronized(active) {
            initializeAccounting()
            check(id !in active) { "A running capture cannot be deleted." }
            val folder = captureDirectory(id)
            val bytes = folderBytes(folder)
            check(folder.deleteRecursively()) { "Cannot delete capture." }
            accountedBytes = (accountedBytes - bytes).coerceAtLeast(0)
        }
    }

    /** Writes and closes a destination stream, for example an Android Storage Access Framework URI. */
    suspend fun export(ids: List<String>, destination: OutputStream) = withContext(Dispatchers.IO) {
        ZipOutputStream(destination).use { zip ->
            val metadata = readCaptures().associateBy { it.id }
            ids.distinct().forEach { id ->
                val folder = captureDirectory(id)
                check(synchronized(active) { id !in active }) { "Wait for the capture to finish before exporting." }
                val summaries = linkedMapOf<String, CaptureSanitization>()
                val missing = mutableListOf<String>()
                listOf("metadata.json", "request.json", "response.json").forEach { name ->
                    val file = File(folder, name)
                    if (name == "metadata.json" && metadata[id] != null) {
                        zip.putNextEntry(ZipEntry("$id/$name"))
                        summaries[name] = json.encodeToString(exportMetadata(metadata.getValue(id))).byteInputStream()
                            .use { sanitizeCopy(it, zip) }
                        zip.closeEntry()
                    } else if (file.isFile) {
                        zip.putNextEntry(ZipEntry("$id/$name"))
                        summaries[name] = file.inputStream().use { sanitizeCopy(it, zip) }
                        zip.closeEntry()
                    } else if (name == "metadata.json" ||
                        (name == "request.json" && (metadata[id]?.requestBytes ?: 0) > 0) ||
                        (name == "response.json" && (metadata[id]?.responseBytes ?: 0) > 0)
                    ) {
                        missing += name
                    }
                }
                zip.putNextEntry(ZipEntry("$id/export-policy.json"))
                zip.write(
                    json.encodeToString(
                        CaptureExportPolicy(
                            sourcePolicy = metadata[id]?.capturePolicy ?: "unknown",
                            bodies = summaries,
                            missingBodies = missing,
                            complete = missing.isEmpty() && summaries.values.all { it.complete },
                        ),
                    ).toByteArray(),
                )
                zip.closeEntry()
            }
        }
    }

    /** Imports historical diagnostics only; no provider work can be scheduled from a capture. */
    suspend fun importArchive(
        source: InputStream,
        jobIds: Map<String, String>,
        settings: TranslationLogSettings,
        onWarning: (String) -> Unit = {},
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val staging = File(directory.parentFile, "capture-import-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val seen = mutableSetOf<String>()
            val metadata = linkedMapOf<String, CaptureMetadata>()
            val summaries = mutableMapOf<String, CaptureSanitization>()
            var expanded = 0L
            java.util.zip.ZipInputStream(source).use { zip ->
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    require(
                        !entry.isDirectory &&
                            entry.name.matches(
                                Regex("[a-fA-F0-9-]{36}/(?:metadata|request|response|export-policy)\\.json"),
                            ),
                    ) {
                        "Unsupported capture archive entry"
                    }
                    require(seen.add(entry.name) && seen.size <= 2_000) {
                        "Duplicate or excessive capture archive entries"
                    }
                    val sourceId = entry.name.substringBefore('/')
                    val name = entry.name.substringAfter('/')
                    val folder = File(staging, sourceId).apply { mkdirs() }
                    val maximum = if (name == "metadata.json" ||
                        name == "export-policy.json"
                    ) {
                        64L * 1024
                    } else {
                        128L * 1024 * 1024
                    }
                    var bytes = 0L
                    val target = File(folder, name)
                    target.outputStream().use { output ->
                        SanitizedCaptureOutputStream(output) { summaries[entry.name] = it }.use { sanitizer ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val count = zip.read(buffer)
                                if (count < 0) break
                                bytes += count
                                expanded += count
                                require(bytes <= maximum && expanded <= 128L * 1024 * 1024) {
                                    "Capture archive exceeds expanded storage limits"
                                }
                                sanitizer.write(buffer, 0, count)
                            }
                            sanitizer.finish(true)
                        }
                    }
                    if (name == "metadata.json") {
                        require(target.length() <= MAX_METADATA_BYTES) { "Imported capture metadata is too large" }
                        val value = decodeImportedMetadata(target.readText())
                        require(value.id == sourceId) { "Capture metadata identity mismatch" }
                        metadata[sourceId] = value
                    }
                }
            }
            require(staging.listFiles().orEmpty().all { it.name in metadata }) { "Capture archive is missing metadata" }
            val mapping = linkedMapOf<String, String>()
            val prepared = mutableListOf<Pair<File, File>>()
            metadata.forEach { (sourceId, value) ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val targetJob = jobIds[value.jobId] ?: return@forEach
                val targetId = UUID.nameUUIDFromBytes(
                    "translation-import:$sourceId:$targetJob".toByteArray(),
                ).toString()
                val folder = File(staging, sourceId)
                val requestFile = File(folder, "request.json")
                val responseFile = File(folder, "response.json")
                val requestSummary = summaries["$sourceId/request.json"]
                val responseSummary = summaries["$sourceId/response.json"]
                val requestExpected = value.requestBytes > 0 || requestFile.isFile
                val responseExpected = value.responseBytes > 0 || responseFile.isFile
                val requestComplete =
                    requestFile.isFile && requestSummary?.complete == true && value.requestBodyCompleted &&
                        !value.requestTruncated &&
                        value.requestSanitization?.complete != false
                val responseComplete =
                    responseFile.isFile && responseSummary?.complete == true && value.responseBodyCompleted &&
                        !value.responseTruncated &&
                        value.responseSanitization?.complete != false
                val incomplete = (requestExpected && !requestComplete) || (responseExpected && !responseComplete)
                val safe = exportMetadata(value).copy(
                    id = targetId, jobId = targetJob,
                    batchId = value.batchId?.let { batch ->
                        if (value.jobId ==
                            targetJob
                        ) {
                            batch
                        } else {
                            UUID.nameUUIDFromBytes("translation-history:$targetJob:$batch".toByteArray()).toString()
                        }
                    },
                    state = if (incomplete) {
                        "INCOMPLETE"
                    } else if (value.completedAt ==
                        null
                    ) {
                        "INTERRUPTED"
                    } else {
                        value.state
                    },
                    requestCapturedBytes = requestFile.takeIf { it.isFile }?.length() ?: 0,
                    responseCapturedBytes = responseFile.takeIf { it.isFile }?.length() ?: 0,
                    requestBodyCompleted = requestComplete, responseBodyCompleted = responseComplete,
                    requestTruncated = requestExpected && !requestComplete,
                    responseTruncated =
                    responseExpected && !responseComplete,
                    requestSanitization = requestSummary, responseSanitization = responseSummary,
                    capturePolicy = SANITIZED_CAPTURE_POLICY,
                    captureNotes = (
                        value.captureNotes +
                            "Imported historical capture; it cannot schedule provider work."
                        ).takeLast(32),
                )
                val encoded = json.encodeToString(safe).toByteArray()
                require(encoded.size <= MAX_METADATA_BYTES) { "Imported metadata exceeds its bounded size" }
                File(folder, "metadata.json").writeBytes(encoded)
                prepared += folder to captureDirectory(targetId)
                mapping[sourceId] = targetId
            }
            synchronized(active) {
                initializeAccounting()
                val absent = prepared.filter { !it.second.exists() }
                if (absent.size !=
                    prepared.size
                ) {
                    onWarning(
                        "Existing imported capture identities were preserved; their bodies were not replaced.",
                    )
                }
                val required = absent.sumOf { folderBytes(it.first) }
                val maximum = minOf(budgetBytes, settings.maxStorageMb.coerceAtLeast(1) * 1024L * 1024L)
                check(accountedBytes + required <= maximum) {
                    "Capture import exceeds configured storage; existing history was preserved"
                }
                directory.mkdirs()
                val installed = mutableListOf<File>()
                try {
                    absent.forEach { (from, to) ->
                        check(from.renameTo(to)) { "Cannot install imported capture" }
                        installed += to
                        accountedBytes += folderBytes(to)
                    }
                } catch (failure: Throwable) {
                    installed.forEach { owned ->
                        val bytes = folderBytes(owned)
                        if (owned.deleteRecursively()) {
                            accountedBytes -=
                                bytes
                        }
                    }
                    throw failure
                }
            }
            mapping
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun exportMetadata(metadata: CaptureMetadata): CaptureMetadata = metadata.copy(
        requestUrl = metadata.requestUrl?.let { value ->
            value.toHttpUrlOrNull()?.let(DiagnosticRedactor::url) ?: "[omitted invalid URL]"
        },
        requestHeaders = DiagnosticRedactor.headers(metadata.requestHeaders),
        responseHeaders = DiagnosticRedactor.headers(metadata.responseHeaders),
        error = metadata.error?.let { DiagnosticRedactor.bodyText(it) },
        captureNotes = metadata.captureNotes.map { DiagnosticRedactor.bodyText(it) },
        exportPolicy = SANITIZED_CAPTURE_POLICY,
    )

    private fun decodeImportedMetadata(sanitized: String): CaptureMetadata {
        val fields = json.parseToJsonElement(sanitized).jsonObject.toMutableMap()
        listOf("requestHeaders", "responseHeaders").forEach { name ->
            val headers = fields[name] ?: return@forEach
            require(headers is JsonObject) { "Imported capture headers must be an object" }
            // The body sanitizer represents credential headers as omission objects, including
            // in our own exports. Restore only the typed header schema after sanitization;
            // never turn an omission marker or an unexpected structured value into visible text.
            val strings = headers.mapValues { (_, value) ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "[redacted]"
            }
            fields[name] = JsonObject(
                DiagnosticRedactor.headers(strings).mapValues { (_, value) -> JsonPrimitive(value) },
            )
        }
        return json.decodeFromJsonElement(JsonObject(fields))
    }

    private fun sanitizeCopy(input: InputStream, output: OutputStream): CaptureSanitization {
        var summary: CaptureSanitization? = null
        val sanitizer = SanitizedCaptureOutputStream(output) { summary = it }
        var complete = false
        try {
            input.copyTo(sanitizer, 8192)
            complete = true
        } finally {
            sanitizer.finish(complete)
        }
        return checkNotNull(summary)
    }

    suspend fun prune(settings: TranslationLogSettings) = withContext(Dispatchers.IO) {
        synchronized(active) {
            initializeAccounting()
            val requestedBudget = settings.maxStorageMb.coerceAtLeast(1) * 1024L * 1024L
            budgetBytes = if (active.isEmpty()) requestedBudget else minOf(budgetBytes, requestedBudget)
            pruneLocked(settings, 0)
        }
    }

    private fun initializeAccounting() {
        if (accountedBytes < 0) accountedBytes = folderBytes(directory)
    }

    private fun pruneLocked(settings: TranslationLogSettings, neededBytes: Long) {
        val cutoff = System.currentTimeMillis() - settings.retentionDays.coerceAtLeast(1) * 86_400_000L
        directory.listFiles().orEmpty().filter { it.isDirectory && it.name !in active }
            .sortedBy { it.lastModified() }.forEach { folder ->
                if (folder.lastModified() < cutoff || accountedBytes + neededBytes > budgetBytes) {
                    val bytes = folderBytes(folder)
                    if (folder.deleteRecursively()) accountedBytes = (accountedBytes - bytes).coerceAtLeast(0)
                }
            }
    }

    private fun folderBytes(folder: File): Long = folder.walkTopDown().filter(File::isFile).sumOf(File::length)

    private fun readCaptures(): List<CaptureMetadata> = directory.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.matches(Regex("[a-fA-F0-9-]{36}")) }
        .map { folder ->
            val metadata =
                runCatching {
                    val file = File(folder, "metadata.json")
                    require(file.length() <= MAX_METADATA_BYTES) { "Capture metadata exceeds its storage limit." }
                    json.decodeFromString<CaptureMetadata>(file.readText())
                }
                    .getOrNull()
                    ?: CaptureMetadata(
                        folder.name,
                        "unknown",
                        null,
                        "unknown",
                        startedAt = folder.lastModified(),
                        error = "Interrupted capture has missing or unreadable metadata.",
                    )
            when {
                metadata.completedAt == null && synchronized(active) { folder.name !in active } ->
                    metadata.copy(
                        state = "INTERRUPTED",
                        error =
                        metadata.error ?: "Capture interrupted before completion.",
                    )
                metadata.completedAt != null && metadata.state == "RUNNING" ->
                    metadata.copy(state = if (metadata.error == null) "COMPLETED" else "FAILED")
                else -> metadata
            }
        }.sortedByDescending(CaptureMetadata::startedAt)

    private fun captureDirectory(id: String): File {
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "Invalid capture ID." }
        return File(directory, id)
    }

    private companion object {
        const val MAX_METADATA_BYTES = 16 * 1024

        // Atomic metadata replacement can briefly retain both old and new manifests.
        const val METADATA_RESERVATION = MAX_METADATA_BYTES * 2L
    }
}

@Serializable
data class CaptureMetadata(
    val id: String,
    val jobId: String,
    val batchId: String?,
    val operation: String,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val requestUrl: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseCode: Int? = null,
    val requestBytes: Long = 0,
    val responseBytes: Long = 0,
    val attempt: Int = 1,
    val error: String? = null,
    val state: String = "RUNNING",
    val requestCapturedBytes: Long = 0,
    val responseCapturedBytes: Long = 0,
    val requestTruncated: Boolean = false,
    val responseTruncated: Boolean = false,
    val requestBodyCompleted: Boolean = false,
    val responseBodyCompleted: Boolean = false,
    val metadataTruncated: Boolean = false,
    val captureNotes: List<String> = emptyList(),
    val capturePolicy: String = "legacy-redacted",
    val requestSanitization: CaptureSanitization? = null,
    val responseSanitization: CaptureSanitization? = null,
    val exportPolicy: String? = null,
)

@Serializable
private data class CaptureExportPolicy(
    val policy: String = SANITIZED_CAPTURE_POLICY,
    val sourcePolicy: String,
    val bodies: Map<String, CaptureSanitization>,
    val missingBodies: List<String>,
    val complete: Boolean,
)

internal enum class CaptureBody(val fileName: String) { REQUEST("request.json"), RESPONSE("response.json") }

internal class CaptureUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class CaptureSession(
    val directory: File,
    var metadata: CaptureMetadata,
    val settings: TranslationLogSettings,
) {
    val requestFile get() = File(directory, "request.json")
    val responseFile get() = File(directory, "response.json")
    var requestCapturedBytes = 0L
    var responseCapturedBytes = 0L
    var requestTruncated = false
    var responseTruncated = false
    var requestBodyCompleted = false
    var responseBodyCompleted = false
    var disabled = false
    var requestSanitization: CaptureSanitization? = null
    var responseSanitization: CaptureSanitization? = null
    val notes = mutableListOf<String>()
}

internal object DiagnosticRedactor {
    private const val REDACTED = "[redacted]"
    private val visibleHeaders = setOf(
        "content-type", "content-length", "accept", "user-agent", "date", "retry-after",
        "x-request-id", "x-client-request-id", "x-goog-request-id", "x-cloud-trace-context",
        "x-vertex-ai-llm-request-type", "x-vertex-ai-llm-shared-request-type",
        "openai-processing-ms", "openai-version",
    )

    fun headers(
        headers: Headers,
        secrets: Collection<String> = emptyList(),
    ): Map<String, String> = headers.names().associateWith { name ->
        val lower = name.lowercase()
        if (lower in visibleHeaders || lower.startsWith("x-ratelimit-")) {
            message(headers.values(name).joinToString(", "), secrets)
        } else {
            REDACTED
        }
    }

    fun headers(
        headers: Map<String, String>,
        secrets: Collection<String> = emptyList(),
    ): Map<String, String> = headers.entries.associate { (name, value) ->
        val lower = name.lowercase()
        bodyText(name, secrets) to if (lower in visibleHeaders || lower.startsWith("x-ratelimit-")) {
            message(value, secrets)
        } else {
            REDACTED
        }
    }

    fun url(url: HttpUrl): String = url.newBuilder().apply {
        username("")
        password("")
        url.queryParameterNames.forEach { name -> setQueryParameter(name, REDACTED) }
    }.build().toString()

    fun secretPatterns(values: Collection<String>): List<String> = values.filter(String::isNotEmpty)
        .flatMap { listOf(it, JsonPrimitive(it).toString().removeSurrounding("\"")) }
        .distinct().sortedByDescending(String::length)

    fun message(message: String, secrets: Collection<String>): String = bodyText(message, secrets).take(4000)

    fun bodyText(value: String, secrets: Collection<String> = emptyList()): String = secretPatterns(secrets)
        .fold(value) { text, secret -> text.replace(secret, REDACTED) }
        .replace(Regex("(?i)(bearer\\s+)[^\\s,;]+"), "$1[redacted]")
        .replace(Regex("(?i)data:[^\\s\"'<>]*"), "[omitted embedded data]")
        .replace(Regex("https?://[^\\s\"'<>\\\\]+")) { match ->
            match.value.toHttpUrlOrNull()?.let(::url) ?: "[omitted unparseable URL]"
        }
        .replace(Regex("[A-Za-z0-9+/=_-]{512,}"), "[omitted opaque data]")
}
