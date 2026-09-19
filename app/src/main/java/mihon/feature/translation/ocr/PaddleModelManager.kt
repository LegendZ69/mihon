package mihon.feature.translation.ocr

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mihon.feature.translation.TranslationPreferences
import mihon.feature.translation.provider.DiagnosticRedactor
import mihon.feature.translation.provider.TranslationDiagnosticsStore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tachiyomi.domain.translation.model.PaddleProfile
import tachiyomi.domain.translation.model.TranslationLogSettings
import tachiyomi.domain.translation.model.TranslationProgressUnit
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class PaddleModelStatus { NOT_DOWNLOADED, DOWNLOADING, INSTALLED, FAILED }

data class PaddleModelState(
    val profile: PaddleProfile,
    val korean: Boolean,
    val detectorModel: String,
    val recognizerModel: String,
    val downloadBytes: Long = 0,
    val totalBytes: Long,
    val status: PaddleModelStatus = PaddleModelStatus.NOT_DOWNLOADED,
    val error: String? = null,
    val operationId: String? = null,
)

internal data class InstalledPaddleModels(
    val detector: File,
    val recognizer: File,
    val dictionary: File,
    val detectorId: String,
    val recognizerId: String,
    val revision: String,
)

/** Stores complete verified packs in app-private storage, never a partially installed model. */
@SingleIn(AppScope::class)
class PaddleModelManager(
    private val directory: File,
    client: OkHttpClient,
    private val repository: TranslationRepository? = null,
    private val diagnostics: TranslationDiagnosticsStore? = null,
    private val logSettings: () -> TranslationLogSettings = { TranslationLogSettings() },
) {
    @Inject
    constructor(
        context: Context,
        networkHelper: NetworkHelper,
        repository: TranslationRepository,
        diagnostics: TranslationDiagnosticsStore,
        preferences: TranslationPreferences,
        notifications: mihon.feature.translation.TranslationNotificationCenter,
    ) : this(
        File(context.noBackupFilesDir, "translation/paddle-models"),
        networkHelper.client,
        repository,
        diagnostics,
        { preferences.settings.value.logs },
    ) {
        notifications.observeModels(states)
    }

    constructor(context: Context, client: OkHttpClient) : this(
        File(context.noBackupFilesDir, "translation/paddle-models"),
        client,
    )

    private val client = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.MINUTES)
        .build()

    // Installing/removing and acquiring a verified pack cannot race within this manager.
    private val mutex = Mutex()
    private val selections = PaddleProfile.entries.flatMap { profile ->
        listOf(selection(profile, false), selection(profile, true))
    }
    private val mutableStates = MutableStateFlow(selections.map { it.state() })
    val states: StateFlow<List<PaddleModelState>> = mutableStates.asStateFlow()
    private var recoveredStaging = false
    private var installSequence = 0L

    @Volatile
    private var activeInstall = 0L

    @Volatile
    private var activeOperation: Pair<String, Job>? = null

    /** Cancels only the exact currently active model operation selected by a notification or queue action. */
    fun cancel(operationId: String): Boolean {
        val active = activeOperation?.takeIf { it.first == operationId } ?: return false
        active.second.cancel(CancellationException("Model operation cancelled; the next download starts at byte zero"))
        return true
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock { refreshLocked() }
    }

    suspend fun download(profile: PaddleProfile, language: String = "auto") {
        ensureModels(profile, language)
    }

    suspend fun remove(profile: PaddleProfile, language: String = "auto") = withContext(Dispatchers.IO) {
        mutex.withLock {
            val selected = selection(profile, isKorean(language))
            val folder = File(directory, selected.key)
            if (folder.exists() && !folder.deleteRecursively()) throw IOException("Could not remove OCR model pack")
            update(selected.state())
        }
    }

    internal suspend fun ensureModels(
        profile: PaddleProfile,
        language: String,
        operationContext: PaddleOperationContext? = null,
    ): InstalledPaddleModels =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                recoverStagingLocked()
                val selected = selection(profile, isKorean(language))
                val destination = File(directory, selected.key)
                val context = operationContext?.copy(logs = operationContext.logs ?: logSettings())
                    ?: PaddleOperationContext("model-pack:${UUID.randomUUID()}", logs = logSettings())
                val log = PaddleOperationLog(repository, context)
                val operation = log.begin(
                    TranslationStage.MODEL_INSTALL,
                    artifactId = selected.key,
                    total = selected.bytes,
                    unit = TranslationProgressUnit.BYTES,
                    message = "Acquire verified ${profile.name.lowercase()} OCR pack " +
                        "(${if (selected.korean) "Korean" else "multilingual"})",
                )
                activeOperation = currentCoroutineContext()[Job]?.let { operation.id to it }
                try {
                    val manifest = log.begin(
                        TranslationStage.MODEL_MANIFEST,
                        parentId = operation.id,
                        artifactId = selected.key,
                        total = 1,
                        message = "Resolve bundled official model manifest; no network request",
                    )
                    manifest.complete(
                        details = mapOf(
                            "detector" to selected.detector.id,
                            "recognizer" to selected.recognizer.id,
                            "modelRevision" to selected.key,
                            "artifactCount" to selected.models.sumOf { it.second.artifacts.size }.toString(),
                        ),
                    )
                    val cache = log.begin(
                        TranslationStage.MODEL_VERIFY,
                        parentId = operation.id,
                        artifactId = selected.key,
                        total = 1,
                        message = "Check cached pack identity and SHA-256",
                    )
                    val present = try {
                        verified(destination, selected).also {
                            cache.complete(
                                details = mapOf("verifiedCacheHit" to it.toString()),
                                message = if (it) {
                                    "Cached pack verified"
                                } else {
                                    "Pack missing or invalid; download required"
                                },
                            )
                        }
                    } catch (error: Throwable) {
                        cache.failed(error)
                        throw error
                    }
                    if (!present) install(selected, destination, log, operation)
                    operation.complete(message = "Verified OCR model pack available")
                    update(selected.state(PaddleModelStatus.INSTALLED, selected.bytes).copy(operationId = operation.id))
                    InstalledPaddleModels(
                        detector = File(destination, "det/inference.onnx"),
                        recognizer = File(destination, "rec/inference.onnx"),
                        dictionary = File(destination, "rec/inference.yml"),
                        detectorId = selected.detector.id,
                        recognizerId = selected.recognizer.id,
                        revision = selected.key,
                    )
                } catch (error: Throwable) {
                    operation.failed(error)
                    update(
                        selected.state(
                            if (error is CancellationException) {
                                PaddleModelStatus.NOT_DOWNLOADED
                            } else {
                                PaddleModelStatus.FAILED
                            },
                            error = if (error is CancellationException) {
                                null
                            } else {
                                DiagnosticRedactor.bodyText(
                                    error.message.orEmpty(),
                                )
                            },
                        ).copy(operationId = operation.id),
                    )
                    throw error
                } finally {
                    if (activeOperation?.first == operation.id) activeOperation = null
                }
            }
        }

    private suspend fun install(
        selected: Selection,
        destination: File,
        log: PaddleOperationLog,
        operation: PaddleOperationLog.Handle,
    ) {
        check(directory.isDirectory || directory.mkdirs()) { "Could not create OCR model storage" }
        if (directory.usableSpace < selected.bytes + 8L * 1024 * 1024) {
            throw IOException("Not enough free storage for ${selected.bytes} bytes of OCR models")
        }
        val staging = File(directory, ".${selected.key}-${UUID.randomUUID()}.part")
        check(staging.mkdir()) { "Could not create OCR model staging folder" }
        val installId = ++installSequence
        activeInstall = installId
        update(selected.state(PaddleModelStatus.DOWNLOADING).copy(operationId = operation.id))
        try {
            var completedBytes = 0L
            for ((subdirectory, spec) in selected.models) {
                val target = File(staging, subdirectory)
                check(target.mkdir())
                for (artifact in spec.artifacts) {
                    downloadArtifact(
                        spec,
                        artifact,
                        File(target, artifact.name),
                        log,
                        operation,
                        completedBytes,
                    ) { received ->
                        updateProgress(selected, completedBytes + received, installId)
                    }
                    completedBytes += artifact.bytes
                }
            }
            currentCoroutineContext().ensureActive()
            val commit = log.begin(
                TranslationStage.MODEL_INSTALL,
                parentId = operation.id,
                artifactId = selected.key,
                total = 1,
                message = "Atomically publish verified model pack",
            )
            // A corrupt prior version is retained until a complete replacement has been verified.
            val previous = File(directory, ".${selected.key}-${UUID.randomUUID()}.old")
            val hadPrevious = destination.exists()
            try {
                if (hadPrevious &&
                    !destination.renameTo(previous)
                ) {
                    throw IOException("Could not replace invalid OCR pack")
                }
                if (!staging.renameTo(destination)) {
                    if (hadPrevious) previous.renameTo(destination)
                    throw IOException("Could not commit OCR model pack")
                }
                commit.complete()
            } catch (error: Throwable) {
                commit.failed(error)
                throw error
            }
            if (previous.exists() && !previous.deleteRecursively()) {
                commit.event("cleanup-warning", "Replaced model pack cleanup is deferred", level = "WARN")
            }
        } catch (e: CancellationException) {
            update(selected.state())
            throw e
        } catch (e: Exception) {
            update(selected.state(PaddleModelStatus.FAILED, error = DiagnosticRedactor.bodyText(e.message.orEmpty())))
            throw e
        } finally {
            if (activeInstall == installId) activeInstall = 0L
            if (staging.exists() && !staging.deleteRecursively()) {
                withContext(NonCancellable) {
                    operation.event(
                        "staging-cleanup-warning",
                        "Interrupted model staging cleanup is deferred",
                        level = "WARN",
                    )
                }
            }
        }
    }

    private data class TransferProgress(val received: Long, val response: PaddleHttpSnapshot)
    private data class TransferReceipt(val received: Long, val sha256: String, val elapsedMillis: Long)

    private suspend fun downloadArtifact(
        spec: PaddleModelSpec,
        artifact: PaddleModelArtifact,
        destination: File,
        log: PaddleOperationLog,
        pack: PaddleOperationLog.Handle,
        packCompletedBytes: Long,
        progress: (Long) -> Unit,
    ) = coroutineScope {
        val url = "https://huggingface.co/PaddlePaddle/${spec.id}/resolve/${spec.revision}/${artifact.name}"
        val request = Request.Builder().url(url).build()
        val transfer = log.begin(
            TranslationStage.MODEL_DOWNLOAD,
            parentId = pack.id,
            artifactId = "${spec.id}/${spec.revision}/${artifact.name}",
            total = artifact.bytes,
            unit = TranslationProgressUnit.BYTES,
            message = "Download ${spec.id}/${artifact.name}",
            details = mapOf("requestUrl" to url, "method" to "GET", "range" to "none; download starts at byte zero"),
        )
        val capture = try {
            PaddleDownloadCapture.start(diagnostics, log.context, request, log.context.logs ?: logSettings())
                ?.also { transfer.attachCapture(it.id, it.directory) }
        } catch (error: Exception) {
            transfer.event("capture-unavailable", "API payload capture unavailable: ${error.message}", level = "WARN")
            null
        }
        val updates = Channel<TransferProgress>(Channel.CONFLATED)
        var response: PaddleHttpSnapshot? = null
        var received = 0L
        var reportedResponses = false
        val collector = launch {
            for (update in updates) {
                response = update.response
                received = update.received
                if (!reportedResponses) {
                    reportedResponses = true
                    update.response.responses.forEachIndexed { index, item ->
                        transfer.event(
                            "http-$index",
                            if (item.status in 300..399) {
                                "Model download redirect: HTTP ${item.status}"
                            } else {
                                "Model download response: HTTP ${item.status}"
                            },
                            mapOf(
                                "httpStatus" to item.status.toString(),
                                "url" to item.url,
                                "headers" to item.headers.toString(),
                                "redirectIndex" to index.toString(),
                            ),
                            if (item.status >= 400) "ERROR" else "INFO",
                        )
                    }
                    val requestId = update.response.responses.lastOrNull()?.headers?.entries
                        ?.firstOrNull { it.key.equals("x-request-id", true) }?.value
                    transfer.operation = transfer.operation.copy(requestId = requestId)
                }
                transfer.progress(update.received)
                pack.progress(packCompletedBytes + update.received)
            }
        }
        var receipt: TransferReceipt? = null
        var failure: Throwable? = null
        try {
            receipt =
                receiveArtifact(client.newCall(request), artifact, destination, updates, capture != null, progress)
        } catch (error: Throwable) {
            failure = error
        } finally {
            updates.close()
            withContext(NonCancellable) {
                collector.join()
                runCatching { capture?.finish(response, receipt?.received ?: received, receipt?.sha256, failure) }
                    .onFailure {
                        transfer.event("capture-failed", "API payload capture could not be finalized", level = "WARN")
                    }
                if (failure != null) {
                    transfer.failed(checkNotNull(failure), mapOf("receivedBytes" to received.toString()))
                } else {
                    val completed = checkNotNull(receipt)
                    transfer.complete(
                        completed.received,
                        details = mapOf(
                            "responseBodyBytes" to completed.received.toString(),
                            "transferMillis" to completed.elapsedMillis.toString(),
                        ),
                        message = "HTTP body received; model integrity verification follows",
                    )
                }
            }
        }
        failure?.let { throw it }
        val completed = checkNotNull(receipt)
        val verification = log.begin(
            TranslationStage.MODEL_VERIFY,
            parentId = transfer.id,
            artifactId = transfer.operation.artifactId,
            total = 1,
            unit = TranslationProgressUnit.ARTIFACTS,
            message = "Verify artifact byte count and SHA-256",
        )
        val details = mapOf(
            "expectedBytes" to artifact.bytes.toString(),
            "receivedBytes" to completed.received.toString(),
            "expectedSha256" to artifact.sha256,
            "actualSha256" to completed.sha256,
        )
        try {
            if (completed.received != artifact.bytes || completed.sha256 != artifact.sha256) {
                throw IOException("OCR model integrity check failed for ${spec.id}/${artifact.name}")
            }
            verification.complete(details = details)
        } catch (error: Throwable) {
            verification.failed(error, details)
            throw error
        }
    }

    /** Network callbacks keep blocked reads outside the cancelled coroutine, as before diagnostics existed. */
    private suspend fun receiveArtifact(
        call: Call,
        artifact: PaddleModelArtifact,
        destination: File,
        updates: Channel<TransferProgress>,
        captureErrorBody: Boolean,
        progress: (Long) -> Unit,
    ): TransferReceipt = suspendCancellableCoroutine { continuation ->
        val started = System.nanoTime()
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var received = 0L
                    var snapshot = PaddleHttpSnapshot(PaddleHttpResponse.chain(response))
                    updates.trySend(TransferProgress(0, snapshot))
                    response.use {
                        if (!it.isSuccessful) {
                            if (captureErrorBody) {
                                val prefix = it.peekBody(65537).bytes()
                                snapshot = snapshot.copy(
                                    errorBody = prefix.copyOf(minOf(prefix.size, 65536)),
                                    errorBodyComplete = prefix.size <= 65536,
                                )
                                updates.trySend(TransferProgress(0, snapshot))
                            }
                            throw IOException("OCR model download failed: HTTP ${it.code}")
                        }
                        val body = it.body
                        if (body.contentLength() > artifact.bytes) {
                            throw IOException("OCR artifact exceeds its verified size")
                        }
                        body.byteStream().use { input ->
                            destination.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    if (!continuation.isActive) {
                                        throw CancellationException(
                                            "OCR model download cancelled",
                                        )
                                    }
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    received += count
                                    if (received >
                                        artifact.bytes
                                    ) {
                                        throw IOException("OCR artifact exceeds its verified size")
                                    }
                                    digest.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                    progress(received)
                                    updates.trySend(TransferProgress(received, snapshot))
                                }
                                output.fd.sync()
                            }
                        }
                    }
                    if (continuation.isActive) {
                        continuation.resume(
                            TransferReceipt(
                                received,
                                digest.digest().hex(),
                                (System.nanoTime() - started) / 1_000_000,
                            ),
                        )
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private suspend fun refreshLocked() {
        recoverStagingLocked()
        for (selected in selections) {
            val present = verified(File(directory, selected.key), selected)
            update(
                selected.state(
                    if (present) PaddleModelStatus.INSTALLED else PaddleModelStatus.NOT_DOWNLOADED,
                    if (present) selected.bytes else 0L,
                ).copy(
                    operationId = mutableStates.value.firstOrNull {
                        it.profile == selected.profile && it.korean == selected.korean
                    }?.operationId,
                ),
            )
        }
    }

    private suspend fun recoverStagingLocked() {
        if (recoveredStaging) return
        // A process killed during download cannot run its finally block. Only clean this registry's
        // private staging names; committed model directories and unrelated files are left intact.
        for (file in directory.listFiles().orEmpty()) {
            currentCoroutineContext().ensureActive()
            if (file.name.endsWith(".part") || file.name.endsWith(".old")) {
                val selected = selections.firstOrNull { file.name.startsWith(".${it.key}-") }
                if (selected != null) {
                    val log = PaddleOperationLog(
                        repository,
                        PaddleOperationContext(
                            "model-recovery:${UUID.randomUUID()}",
                            logs = logSettings(),
                        ),
                    )
                    val cleanup = log.begin(
                        TranslationStage.MODEL_INSTALL,
                        artifactId = selected.key,
                        total = 1,
                        message = "Clear interrupted model staging; subsequent downloads start at byte zero",
                    )
                    try {
                        if (!file.deleteRecursively()) {
                            throw IOException(
                                "Could not clear interrupted OCR model download",
                            )
                        }
                        cleanup.complete(details = mapOf("entry" to file.name, "rangeResumeSupported" to "false"))
                    } catch (error: Throwable) {
                        cleanup.failed(error)
                        throw error
                    }
                }
            }
        }
        recoveredStaging = true
    }

    private suspend fun verified(folder: File, selected: Selection): Boolean {
        for ((subdirectory, model) in selected.models) {
            for (artifact in model.artifacts) {
                currentCoroutineContext().ensureActive()
                val file = File(File(folder, subdirectory), artifact.name)
                if (!file.isFile || file.length() != artifact.bytes) return false
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                if (digest.digest().hex() != artifact.sha256) return false
            }
        }
        return true
    }

    private fun update(state: PaddleModelState) = mutableStates.update { values ->
        values.map { if (it.profile == state.profile && it.korean == state.korean) state else it }
    }

    private fun updateProgress(selected: Selection, received: Long, installId: Long) = mutableStates.update { values ->
        // A cancelled HTTP callback can finish after its coroutine, including during a new download.
        if (activeInstall != installId) {
            values
        } else {
            values.map {
                if (it.profile == selected.profile && it.korean == selected.korean &&
                    it.status == PaddleModelStatus.DOWNLOADING
                ) {
                    it.copy(downloadBytes = received)
                } else {
                    it
                }
            }
        }
    }

    private fun selection(profile: PaddleProfile, korean: Boolean): Selection {
        val detector = PaddleModelRegistry.models.getValue("PP-OCRv6_${profile.name.lowercase()}_det_onnx")
        val recognizer = PaddleModelRegistry.models.getValue(
            if (korean) "korean_PP-OCRv5_mobile_rec_onnx" else "PP-OCRv6_${profile.name.lowercase()}_rec_onnx",
        )
        return Selection(profile, korean, detector, recognizer)
    }

    private data class Selection(
        val profile: PaddleProfile,
        val korean: Boolean,
        val detector: PaddleModelSpec,
        val recognizer: PaddleModelSpec,
    ) {
        val key = "${profile.name.lowercase()}-${if (korean) "ko" else "multi"}-" +
            "${detector.revision}-${recognizer.revision}"
        val models = listOf("det" to detector, "rec" to recognizer)
        val bytes = models.sumOf { (_, model) -> model.artifacts.sumOf { it.bytes } }
        fun state(
            status: PaddleModelStatus = PaddleModelStatus.NOT_DOWNLOADED,
            received: Long = 0,
            error: String? = null,
        ) = PaddleModelState(profile, korean, detector.id, recognizer.id, received, bytes, status, error)
    }

    companion object {
        internal fun isKorean(language: String): Boolean =
            language.lowercase().substringBefore('-').substringBefore('_') in setOf("ko", "kor", "korean")

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
