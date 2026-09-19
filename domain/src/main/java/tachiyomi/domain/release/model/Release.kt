package tachiyomi.domain.release.model

import kotlinx.serialization.Serializable

/**
 * Contains information about the latest release.
 */
@Serializable
data class Release(
    val version: String,
    val info: String,
    val releaseLink: String,
    val downloadLink: String,
    val apk: ReleaseApk? = null,
    val isPrerelease: Boolean = false,
)

@Serializable
data class ReleaseApk(
    val versionCode: Long,
    val packageName: String,
    val abi: String,
    val sizeBytes: Long,
    val sha256: String,
    val certificateSha256: String,
)
