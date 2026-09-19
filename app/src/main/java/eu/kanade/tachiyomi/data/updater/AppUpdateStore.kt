package eu.kanade.tachiyomi.data.updater

import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.release.model.Release
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

@Serializable
data class AppUpdateDownload(
    val id: String,
    val release: Release,
    val stage: AppUpdateStage,
    val progress: Int = 0,
    val error: String? = null,
)

@Serializable
enum class AppUpdateStage { QUEUED, DOWNLOADING, VERIFYING, DOWNLOADED, FAILED }

/** Single-process, app-scoped owner. Metadata is kept outside the FileProvider directory. */
internal class AppUpdateStore(
    private val directory: File,
    private val persistence: AppUpdateRecordPersistence =
        AtomicAppUpdateRecordPersistence(File(directory, "state.json")),
) {
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<AppUpdateDownload?> = mutableState

    private fun read(): AppUpdateDownload? = runCatching {
        persistence.read()?.let { bytes ->
            Json.decodeFromString<AppUpdateDownload>(bytes.decodeToString())
                .also { require(UUID.fromString(it.id).toString() == it.id) }
        }
    }.getOrNull()

    /** A failed commit never publishes DOWNLOADED or hides a record whose deletion failed. */
    @Synchronized
    fun save(value: AppUpdateDownload?): Boolean {
        try {
            if (value == null) {
                persistence.delete()
            } else {
                persistence.write(Json.encodeToString(value).encodeToByteArray())
            }
        } catch (_: Exception) {
            // WorkInfo observers and worker error handlers must remain usable when the disk is full.
            // Keep the previously committed record on disk and expose a retryable failure in memory.
            mutableState.value = (value ?: mutableState.value)?.copy(
                stage = AppUpdateStage.FAILED,
                error = "Could not save update state. Free some storage and retry.",
            )
            return false
        }
        mutableState.value = value
        return true
    }

    @Synchronized
    fun update(
        id: String,
        persist: Boolean = true,
        onlyWhileActive: Boolean = false,
        transform: (AppUpdateDownload) -> AppUpdateDownload,
    ): Boolean {
        val current = mutableState.value?.takeIf { it.id == id } ?: return false
        if (onlyWhileActive && current.stage !in AppUpdateManager.activeStages) return false
        val next = transform(current)
        // Readiness always requires a durable record, even if a caller accidentally requests a transient update.
        if (persist || next.stage == AppUpdateStage.DOWNLOADED) return save(next)
        mutableState.value = next
        return true
    }

    fun apk(id: String) = File(directory, "apks/${UUID.fromString(id)}.apk")
    fun partial(id: String) = File(directory, "staging/${UUID.fromString(id)}.part")

    fun removeFiles(id: String) {
        apk(id).delete()
        partial(id).delete()
    }

    fun removeOtherFiles(keepId: String?) {
        val retained = keepId?.let { setOf(apk(it), partial(it)) }.orEmpty()
        listOf("apks", "staging").forEach { child ->
            File(directory, child).listFiles()?.filter { it !in retained }?.forEach { it.delete() }
        }
    }
}

/** Implementations must retain the prior committed bytes if writing fails. */
internal interface AppUpdateRecordPersistence {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun delete()
}

private class AtomicAppUpdateRecordPersistence(private val file: File) : AppUpdateRecordPersistence {
    private val record = AtomicFile(file)

    override fun read(): ByteArray? = try {
        record.openRead().use { it.readBytes() }
    } catch (error: FileNotFoundException) {
        if (file.exists()) throw error
        null
    }

    override fun write(bytes: ByteArray) {
        val directory = checkNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Update state storage is unavailable" }
        val output = record.startWrite()
        try {
            output.write(bytes)
            // AtomicFile logs some synchronization failures instead of throwing; make them observable here.
            output.fd.sync()
            record.finishWrite(output)
        } catch (error: Exception) {
            record.failWrite(output)
            throw error
        }
        check(record.readFully().contentEquals(bytes)) { "Update state could not be committed" }
    }

    override fun delete() {
        record.delete()
        check(!file.exists() && read() == null) { "Update state could not be removed" }
    }
}
