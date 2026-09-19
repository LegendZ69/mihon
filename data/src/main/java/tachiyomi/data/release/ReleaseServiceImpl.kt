package tachiyomi.data.release

import android.os.Build
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReleaseServiceImpl(
    private val networkService: NetworkHelper,
    private val json: Json,
) : ReleaseService {

    override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release? {
        if (arguments.isTranslator) return latestTranslator(arguments)
        val release = with(json) {
            networkService.client
                .newCall(request("https://api.github.com/repos/${arguments.repository}/releases/latest", arguments))
                .awaitSuccess()
                .parseAs<GithubRelease>()
        }

        val downloadLink = upstreamDownloadLink(release, arguments.isFoss, Build.SUPPORTED_ABIS.toList()) ?: return null

        return Release(
            version = release.version,
            info = changelog(release),
            releaseLink = release.releaseLink,
            downloadLink = downloadLink,
            isPrerelease = release.prerelease,
        )
    }

    private suspend fun latestTranslator(arguments: GetApplicationRelease.Arguments): Release? {
        require(arguments.repository == TRANSLATOR_REPOSITORY) { "Unexpected translator update repository" }
        val releases = mutableListOf<GithubRelease>()
        var page = 1
        while (true) {
            // Public listings include prereleases. Reservation tags and unpublished drafts are not updates.
            require(page <= 100) { "Too many release pages; open the releases page to check this update" }
            val batch = with(json) {
                networkService.client.newCall(
                    request(
                        "https://api.github.com/repos/$TRANSLATOR_REPOSITORY/releases?per_page=100&page=$page",
                        arguments,
                    ),
                ).awaitSuccess().parseAs<List<GithubRelease>>()
            }
            releases.addAll(batch)
            if (batch.size < 100) break
            page++
        }
        val release = selectTranslatorRelease(releases, arguments.installedVersionCode) ?: return null
        val manifestAsset = translatorAsset(release, "manifest.json")
        val manifest = with(json) {
            networkService.client.newCall(request(manifestAsset.downloadLink, arguments))
                .awaitSuccess().parseAs<TranslatorReleaseManifest>()
        }
        val apk = manifest.verifiedApk(release, Build.SUPPORTED_ABIS.toList())
        val asset = translatorAsset(release, "mihon-translator-v${manifest.number}-arm64-v8a.apk")
        return Release(
            release.version,
            changelog(release),
            release.releaseLink,
            asset.downloadLink,
            apk,
            release.prerelease,
        )
    }

    private fun request(url: String, arguments: GetApplicationRelease.Arguments) = if (arguments.forceCheck) {
        GET(url, cache = CacheControl.FORCE_NETWORK)
    } else {
        GET(url)
    }

    private fun changelog(release: GithubRelease) = release.info.orEmpty().substringBeforeLast("<!-->")
        .replace(gitHubUsernameMentionRegex) { mention ->
            "[${mention.value}](https://github.com/${mention.value.substring(1)})"
        }

    companion object {
        /**
         * Regular expression that matches a mention to a valid GitHub username, like it's
         * done in GitHub Flavored Markdown. It follows these constraints:
         *
         * - Alphanumeric with single hyphens (no consecutive hyphens)
         * - Cannot begin or end with a hyphen
         * - Max length of 39 characters
         *
         * Reference: https://stackoverflow.com/a/30281147
         */
        private val gitHubUsernameMentionRegex = """\B@([a-z0-9](?:-(?=[a-z0-9])|[a-z0-9]){0,38}(?<=[a-z0-9]))"""
            .toRegex(RegexOption.IGNORE_CASE)
    }
}

internal fun upstreamDownloadLink(release: GithubRelease, isFoss: Boolean, supportedAbis: List<String>): String? {
    val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    if (isFoss) return apks.singleOrNull { "-foss" in it.name }?.downloadLink
    val production = apks.filterNot { "-foss" in it.name }
    val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    fun abi(asset: GitHubAsset) = abis.firstOrNull { "-$it" in asset.name }
    for (supported in supportedAbis) {
        production.singleOrNull { abi(it) == supported }?.let { return it.downloadLink }
    }
    return production.singleOrNull { abi(it) == null }?.downloadLink
}
