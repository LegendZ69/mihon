package tachiyomi.data.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tachiyomi.domain.release.model.ReleaseApk

internal const val TRANSLATOR_REPOSITORY = "LegendZ69/mihon"
internal const val TRANSLATOR_CERTIFICATE = "e6c08810c0687b9471118d71f8a7ef2614d65acb6b8b94a989fbcfa4dc61d1b9"
private val translatorTag = Regex("translator-v([1-9][0-9]*)")
private val sha256 = Regex("[0-9a-f]{64}")

internal fun translatorNumber(tag: String): Long? = translatorTag.matchEntire(tag)?.groupValues?.get(1)
    ?.toLongOrNull()?.takeIf { it in 15..2_099_900_000L }

internal fun selectTranslatorRelease(releases: List<GithubRelease>, installedVersionCode: Long): GithubRelease? {
    val candidates = releases.filter { !it.draft && translatorNumber(it.version) != null }
    require(candidates.distinctBy { it.version }.size == candidates.size) { "Duplicate translator release tags" }
    return candidates.filter { 100_000 + checkNotNull(translatorNumber(it.version)) > installedVersionCode }
        .maxByOrNull { checkNotNull(translatorNumber(it.version)) }
}

internal fun translatorAsset(release: GithubRelease, name: String): GitHubAsset {
    val asset = requireNotNull(release.assets.singleOrNull { it.name == name }) {
        "Translator release is missing a unique $name asset"
    }
    val expected = "https://github.com/$TRANSLATOR_REPOSITORY/releases/download/${release.version}/$name"
    require(asset.downloadLink == expected) { "Translator asset URL does not match its release" }
    return asset
}

@Serializable
internal data class TranslatorReleaseManifest(
    val schema: Int,
    val repository: String,
    @SerialName("release_branch") val releaseBranch: String,
    val tag: String,
    val number: Long,
    @SerialName("version_code") val versionCode: Long,
    val apk: TranslatorApkMetadata,
    val assets: List<TranslatorAssetMetadata>,
    val prerelease: Boolean,
) {
    fun verifiedApk(release: GithubRelease, supportedAbis: List<String>): ReleaseApk {
        require(schema == 1 && repository == TRANSLATOR_REPOSITORY && releaseBranch == "codex/manga-translator") {
            "Unsupported translator release manifest"
        }
        require(!release.draft && tag == release.version && number == translatorNumber(tag)) {
            "Translator manifest does not match the published release"
        }
        require(release.releaseLink == "https://github.com/$TRANSLATOR_REPOSITORY/releases/tag/$tag") {
            "Translator release URL does not match its tag"
        }
        require(prerelease == release.prerelease && versionCode == 100_000 + number && apk.versionCode == versionCode) {
            "Translator release version metadata is inconsistent"
        }
        require(apk.applicationId == "app.mihon" && apk.variant == "release" && apk.abi == "arm64-v8a") {
            "Translator APK is not the supported production build"
        }
        require(apk.abi in supportedAbis) { "This translator update requires an ARM64 device" }
        require(apk.versionName.endsWith("-translator.$number") && apk.certificateSha256 == TRANSLATOR_CERTIFICATE) {
            "Translator APK version or signing certificate is unexpected"
        }
        val apkName = "mihon-translator-v$number-arm64-v8a.apk"
        val asset = translatorAsset(release, apkName)
        require(release.assets.count { it.name.endsWith(".apk", ignoreCase = true) } == 1) {
            "Translator release contains unexpected APK assets"
        }
        val identity = requireNotNull(assets.singleOrNull { it.name == apkName }) {
            "Translator manifest is missing a unique APK identity"
        }
        require(assets.distinctBy { it.name }.size == assets.size) { "Translator manifest contains duplicate assets" }
        require(identity.bytes > 0 && identity.bytes == asset.size && sha256.matches(identity.sha256)) {
            "Translator APK size or checksum is invalid"
        }
        require(asset.digest == null || asset.digest == "sha256:${identity.sha256}") {
            "Translator APK checksum differs from GitHub's asset digest"
        }
        return ReleaseApk(
            versionCode,
            apk.applicationId,
            apk.abi,
            identity.bytes,
            identity.sha256,
            apk.certificateSha256,
        )
    }
}

@Serializable
internal data class TranslatorApkMetadata(
    @SerialName("application_id") val applicationId: String,
    @SerialName("version_code") val versionCode: Long,
    @SerialName("version_name") val versionName: String,
    @SerialName("certificate_sha256") val certificateSha256: String,
    val abi: String,
    val variant: String,
)

@Serializable
internal data class TranslatorAssetMetadata(val name: String, val bytes: Long, val sha256: String)
