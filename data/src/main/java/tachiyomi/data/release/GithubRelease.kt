package tachiyomi.data.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contains information about the latest release from GitHub.
 */
@Serializable
data class GithubRelease(
    @SerialName("tag_name")
    val version: String,
    @SerialName("body")
    val info: String? = null,
    @SerialName("html_url")
    val releaseLink: String,
    @SerialName("assets")
    val assets: List<GitHubAsset>,
    val draft: Boolean,
    val prerelease: Boolean,
)

/**
 * Asset class containing asset name and download url.
 */
@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url")
    val downloadLink: String,
    val size: Long = 0,
    val digest: String? = null,
)
