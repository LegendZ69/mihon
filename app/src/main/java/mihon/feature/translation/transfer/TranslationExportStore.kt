package mihon.feature.translation.transfer

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class OwnedTranslationExport(
    val id: String,
    val jobId: String,
    val format: String,
    val createdAt: Long,
    val bytes: Long = 0,
    val sha256: String = "",
    val complete: Boolean = false,
    val pages: Int = 0,
    val active: Boolean = false,
    val storageBytes: Long = 0,
)

class TranslationExportHandle internal constructor(
    val id: String,
    val jobId: String,
    val format: String,
    val file: File,
)

/** Owns only private rendered artifacts. Copies written through SAF are never deletion targets. */
@SingleIn(AppScope::class)
class TranslationExportStore internal constructor(private val root: File) {
    @Inject constructor(context: Context) : this(File(context.noBackupFilesDir, "translation/exports"))

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private val active = mutableMapOf<String, Pair<String, Job>>()
    private val managedJobs = mutableSetOf<String>()

    suspend fun begin(jobId: String, format: String): TranslationExportHandle {
        val owner = checkNotNull(currentCoroutineContext()[Job])
        return withContext(Dispatchers.IO) {
            require(jobId.isNotBlank())
            require(format in setOf("CBZ", "PDF")) { "Unsupported rendered export format" }
            mutex.withLock {
                check(jobId !in managedJobs) {
                    "This chapter is being managed; start its export after management finishes"
                }
                val id = UUID.randomUUID().toString()
                val folder = ownedFolder(id)
                check(folder.mkdirs()) { "Cannot create private rendered export" }
                val handle = TranslationExportHandle(id, jobId, format, File(folder, "rendered.${format.lowercase()}"))
                writeMetadata(OwnedTranslationExport(id, jobId, format, System.currentTimeMillis(), active = true))
                active[id] = jobId to owner
                handle
            }
        }
    }

    suspend fun commit(handle: TranslationExportHandle, complete: Boolean, pages: Int): OwnedTranslationExport =
        withContext(Dispatchers.IO) {
            require(pages >= 0)
            mutex.withLock {
                val previous = checkHandle(handle)
                check(handle.id in active) { "Rendered export is no longer active" }
                check(handle.file.isFile) { "Rendered export file is missing" }
                val metadata = previous.copy(
                    bytes = handle.file.length(),
                    sha256 = hash(handle.file),
                    complete = complete,
                    pages = pages,
                    active = false,
                )
                writeMetadata(metadata)
                active.remove(handle.id)
                metadata.copy(storageBytes = folderBytes(handle.file.parentFile!!))
            }
        }

    suspend fun abandon(handle: TranslationExportHandle) = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (handle.id !in active) return@withLock
            checkHandle(handle)
            val folder = ownedFolder(handle.id)
            check(folder.deleteRecursively()) { "Incomplete rendered export cleanup failed" }
            active.remove(handle.id)
        }
    }

    suspend fun list(jobIds: Set<String>? = null): List<OwnedTranslationExport> = withContext(Dispatchers.IO) {
        mutex.withLock {
            root.listFiles().orEmpty().mapNotNull { folder ->
                runCatching { readMetadata(folder.name) }.getOrNull()?.takeIf { jobIds == null || it.jobId in jobIds }
                    ?.let { metadata ->
                        metadata.copy(
                            active = metadata.id in active,
                            bytes = payload(metadata).takeIf { it.isFile }?.length() ?: 0,
                            storageBytes = folderBytes(folder),
                        )
                    }
            }.sortedByDescending { it.createdAt }
        }
    }

    /** Stop only jobs registered by begin, then await their finally/abandon cleanup. */
    suspend fun stop(jobIds: Set<String>) {
        val self = currentCoroutineContext()[Job]
        val work = mutex.withLock { active.values.filter { it.first in jobIds }.map { it.second }.distinct() }
        check(self !in work) { "A running export cannot delete itself" }
        work.forEach { it.cancel(CancellationException("Paused for rendered export management")) }
        work.forEach { it.join() }
    }

    /** Fence selected jobs while a confirmed management action changes their source records. */
    suspend fun <T> withManagement(jobIds: Set<String>, block: suspend () -> T): T {
        require(jobIds.isNotEmpty()) { "Select chapters to manage" }
        var acquired = false
        try {
            mutex.withLock {
                check(jobIds.none { it in managedJobs }) { "Selected chapters are already being managed" }
                check(
                    active.values.none {
                        it.first in jobIds
                    },
                ) { "A selected export is running; refresh and stop it before management" }
                managedJobs.addAll(jobIds)
                acquired = true
            }
            return block()
        } finally {
            if (acquired) {
                withContext(NonCancellable) {
                    mutex.withLock { managedJobs.removeAll(jobIds) }
                }
            }
        }
    }

    suspend fun delete(ids: Set<String>): Long = withContext(Dispatchers.IO) {
        mutex.withLock {
            val owned = ids.map { id ->
                check(id !in active) { "Stop the running export before deleting it" }
                readMetadata(id).also { metadata ->
                    val allowed = setOf("metadata.json", "metadata.part", payload(metadata).name)
                    check(
                        ownedFolder(id).listFiles().orEmpty().all {
                            it.name in allowed
                        },
                    ) { "Unrecognized export storage entry" }
                }.let { ownedFolder(id) to folderBytes(ownedFolder(id)) }
            }
            var deleted = 0L
            owned.forEach { (folder, bytes) ->
                currentCoroutineContext().ensureActive()
                check(folder.deleteRecursively()) { "Rendered export deletion is incomplete" }
                deleted += bytes
            }
            deleted
        }
    }

    private fun checkHandle(handle: TranslationExportHandle): OwnedTranslationExport {
        val metadata = readMetadata(handle.id)
        require(
            metadata.jobId == handle.jobId && metadata.format == handle.format &&
                payload(metadata).canonicalFile == handle.file.canonicalFile,
        ) { "Unowned rendered export handle" }
        return metadata
    }

    private fun payload(metadata: OwnedTranslationExport) = File(
        ownedFolder(metadata.id),
        "rendered.${metadata.format.lowercase()}",
    )

    private fun readMetadata(id: String): OwnedTranslationExport {
        val folder = ownedFolder(id)
        val file = File(folder, "metadata.json")
        require(file.isFile && file.canonicalFile.parentFile == folder.canonicalFile && file.length() in 1..16_384) {
            "Rendered export ownership metadata is missing"
        }
        val metadata = json.decodeFromString<OwnedTranslationExport>(file.readText())
        require(metadata.id == id && metadata.jobId.isNotBlank() && metadata.format in setOf("CBZ", "PDF")) {
            "Invalid rendered export ownership"
        }
        require(payload(metadata).canonicalFile.parentFile == folder.canonicalFile) { "Unowned rendered export path" }
        return metadata
    }

    private fun writeMetadata(metadata: OwnedTranslationExport) {
        val folder = ownedFolder(metadata.id)
        val temporary = File(folder, "metadata.part")
        temporary.writeText(json.encodeToString(metadata))
        check(temporary.renameTo(File(folder, "metadata.json"))) { "Cannot save rendered export ownership" }
    }

    private fun ownedFolder(id: String): File {
        require(
            runCatching {
                UUID.fromString(id).toString() == id
            }.getOrDefault(false),
        ) { "Invalid rendered export ID" }
        return File(root, id).also {
            require(it.canonicalFile.parentFile == root.canonicalFile) { "Unowned rendered export folder" }
        }
    }

    private fun folderBytes(folder: File): Long = folder.listFiles().orEmpty().sumOf { file ->
        require(file.isFile && file.canonicalFile == File(folder.canonicalFile, file.name)) {
            "Unowned export storage entry"
        }
        file.length()
    }

    private suspend fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
