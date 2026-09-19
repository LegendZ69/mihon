package eu.kanade.tachiyomi.data.updater

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseApk
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** The final filename is never visible until both the bytes and install identity are verified. */
internal suspend fun copyVerifiedApk(
    source: InputStream,
    partial: File,
    destination: File,
    expected: ReleaseApk?,
    verifyIdentity: (File) -> Unit,
    progress: suspend (Int) -> Unit,
) {
    require(partial.parentFile == destination.parentFile && partial != destination)
    check(!destination.exists()) { "An update file already exists" }
    val limit = expected?.sizeBytes ?: MAX_UPDATE_BYTES
    require(limit in 1..MAX_UPDATE_BYTES) { "Invalid update size" }
    try {
        check(partial.parentFile!!.isDirectory || partial.parentFile!!.mkdirs()) { "Update storage is unavailable" }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var previousProgress = -1
        partial.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count == -1) break
                if (count == 0) continue
                total += count
                if (total > limit) throw IOException("Update exceeds its expected size")
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                val percent = if (expected != null) (total * 100 / limit).toInt() else 0
                if (percent != previousProgress) {
                    progress(percent)
                    previousProgress = percent
                }
            }
            output.fd.sync()
        }
        currentCoroutineContext().ensureActive()
        if (total == 0L || (expected != null && total != expected.sizeBytes)) {
            throw IOException("Update download is incomplete")
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (expected != null && !hash.equals(expected.sha256, ignoreCase = true)) {
            throw IOException("Update checksum does not match the release")
        }
        verifyIdentity(partial)
        currentCoroutineContext().ensureActive()
        check(partial.renameTo(destination)) { "Could not save the verified update" }
    } catch (error: Throwable) {
        partial.delete()
        throw error
    }
}

internal data class UpdateApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val certificates: Set<String>,
    val abis: Set<String>,
)

internal fun verifyUpdateIdentity(
    archive: UpdateApkIdentity,
    installed: UpdateApkIdentity,
    expected: ReleaseApk?,
) {
    require(archive.packageName == installed.packageName) { "Update is for a different application" }
    require(archive.versionCode > installed.versionCode) { "This update is already installed or older" }
    require(archive.certificates.size == 1 && archive.certificates == installed.certificates) {
        "Update signing certificate does not match this installation"
    }
    if (expected != null) {
        require(archive.packageName == expected.packageName && archive.versionCode == expected.versionCode) {
            "Update package or version does not match the release"
        }
        require(archive.certificates == setOf(expected.certificateSha256.lowercase())) {
            "Update signing certificate does not match the release"
        }
        require(expected.abi in archive.abis) { "Update does not contain the required architecture" }
    }
}

internal const val MAX_UPDATE_BYTES = 512L * 1024 * 1024

/** Release notes can be edited after publication without changing the downloaded artifact. */
fun Release.isSameUpdate(other: Release): Boolean =
    version == other.version && downloadLink == other.downloadLink && apk == other.apk
