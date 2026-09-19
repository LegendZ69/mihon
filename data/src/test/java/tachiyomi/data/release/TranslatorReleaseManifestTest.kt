package tachiyomi.data.release

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TranslatorReleaseManifestTest {
    private val hash = "a".repeat(64)
    private val name = "mihon-translator-v19-arm64-v8a.apk"
    private val url = "https://github.com/LegendZ69/mihon/releases/download/translator-v19/"
    private val release = GithubRelease(
        version = "translator-v19",
        info = null,
        releaseLink = "https://github.com/LegendZ69/mihon/releases/tag/translator-v19",
        assets = listOf(
            GitHubAsset("manifest.json", "${url}manifest.json", 100),
            GitHubAsset(name, "$url$name", 200, "sha256:$hash"),
            GitHubAsset("source.zip", "${url}source.zip", 400),
            GitHubAsset("SHA256SUMS", "${url}SHA256SUMS", 500),
        ),
        draft = false,
        prerelease = true,
    )
    private val manifest = TranslatorReleaseManifest(
        1, TRANSLATOR_REPOSITORY, "codex/manga-translator", "translator-v19", 19, 100_019,
        TranslatorApkMetadata(
            "app.mihon",
            100_019,
            "0.20.4-translator.19",
            TRANSLATOR_CERTIFICATE,
            "arm64-v8a",
            "release",
        ),
        listOf(TranslatorAssetMetadata(name, 200, hash)),
        true,
    )

    @Test
    fun `published prereleases sort by number rather than time or lexicographic tag`() {
        val releases = listOf(
            release.copy(version = "translator-v99"),
            release.copy(version = "translator-v100"),
            release.copy(version = "translator-v101", draft = true),
            release.copy(version = "v2000.0.0"),
            release.copy(version = "translator-v999999999999999999999999"),
            release.copy(version = "translator-v0200"),
        )
        assertEquals("translator-v100", selectTranslatorRelease(releases, 100_015)?.version)
        assertNull(selectTranslatorRelease(releases, 100_100))
        assertNull(selectTranslatorRelease(emptyList(), 100_015))
        assertThrows(IllegalArgumentException::class.java) {
            selectTranslatorRelease(listOf(release, release), 100_015)
        }
    }

    @Test
    fun `valid immutable manifest yields exact expected APK identity`() {
        val apk = manifest.verifiedApk(release, listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals(100_019L, apk.versionCode)
        assertEquals("app.mihon", apk.packageName)
        assertEquals(200L, apk.sizeBytes)
        assertEquals(hash, apk.sha256)
        assertEquals(TRANSLATOR_CERTIFICATE, apk.certificateSha256)
    }

    @Test
    fun `mismatched metadata and unsupported devices fail closed`() {
        val invalid = listOf(
            manifest.copy(schema = 2),
            manifest.copy(repository = "mihonapp/mihon"),
            manifest.copy(tag = "translator-v20"),
            manifest.copy(number = 20),
            manifest.copy(versionCode = 100_020),
            manifest.copy(prerelease = false),
            manifest.copy(apk = manifest.apk.copy(applicationId = "app.mihon.benchmark")),
            manifest.copy(apk = manifest.apk.copy(versionCode = 100_020)),
            manifest.copy(apk = manifest.apk.copy(versionName = "0.20.4-translator.20")),
            manifest.copy(apk = manifest.apk.copy(certificateSha256 = "b".repeat(64))),
            manifest.copy(apk = manifest.apk.copy(variant = "benchmark")),
            manifest.copy(apk = manifest.apk.copy(abi = "x86_64")),
            manifest.copy(assets = listOf(TranslatorAssetMetadata(name, 201, hash))),
            manifest.copy(assets = listOf(TranslatorAssetMetadata(name, 200, "bad"))),
            manifest.copy(assets = manifest.assets + manifest.assets),
        )
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { value.verifiedApk(release, listOf("arm64-v8a")) }
        }
        assertThrows(IllegalArgumentException::class.java) { manifest.verifiedApk(release, listOf("x86_64")) }
    }

    @Test
    fun `asset origin duplicate missing digest and extra APK checks prevent an arbitrary download`() {
        val apk = release.assets[1]
        val invalid = listOf(
            release.copy(assets = release.assets + apk),
            release.copy(assets = release.assets.filterNot { it.name == name }),
            release.copy(assets = release.assets + GitHubAsset("other.apk", "${url}other.apk", 200)),
            release.copy(assets = listOf(apk.copy(downloadLink = "https://example.com/$name"))),
            release.copy(assets = listOf(apk.copy(digest = "sha256:${"b".repeat(64)}"))),
            release.copy(releaseLink = "https://example.com/release"),
            release.copy(draft = true),
        )
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { manifest.verifiedApk(value, listOf("arm64-v8a")) }
        }
        assertEquals(
            hash,
            manifest.verifiedApk(release.copy(assets = listOf(apk.copy(digest = null))), listOf("arm64-v8a")).sha256,
        )
        assertThrows(IllegalArgumentException::class.java) {
            translatorAsset(release.copy(assets = release.assets + release.assets[0]), "manifest.json")
        }
    }

    @Test
    fun `GitHub null release notes and unrelated JSON fields are accepted`() {
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<GithubRelease>(
            """{"tag_name":"translator-v19","body":null,"html_url":"link","assets":[],"draft":false,"prerelease":true,"id":1}""",
        )
        assertNull(parsed.info)
        assertEquals(true, parsed.prerelease)
    }

    @Test
    fun `published schema one wire names decode without requiring validation-only fields`() {
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<TranslatorReleaseManifest>(
            """
            {
              "schema": 1, "repository": "LegendZ69/mihon", "release_branch": "codex/manga-translator",
              "tag": "translator-v19", "number": 19, "version_code": 100019, "prerelease": true,
              "source_sha": "unused source identity", "overall_acceptance": "pending",
              "apk": {
                "application_id": "app.mihon", "version_code": 100019,
                "version_name": "0.20.4-translator.19", "certificate_sha256": "$TRANSLATOR_CERTIFICATE",
                "abi": "arm64-v8a", "variant": "release"
              },
              "assets": [{"name": "$name", "bytes": 200, "sha256": "$hash"}]
            }
            """.trimIndent(),
        )
        assertEquals(
            manifest.verifiedApk(release, listOf("arm64-v8a")),
            parsed.verifiedApk(release, listOf("arm64-v8a")),
        )
    }

    @Test
    fun `upstream selection ignores non APK assets and supports secondary ABIs`() {
        val upstream = release.copy(
            assets = listOf(
                GitHubAsset("mihon-arm64-v8a.apk", "arm64"),
                GitHubAsset("mihon-x86_64.apk", "x64"),
                GitHubAsset("mihon-x86.apk", "x86"),
                GitHubAsset("mihon-foss.apk", "foss"),
                GitHubAsset("source.zip", "source"),
                GitHubAsset("SHA256SUMS", "checksums"),
            ),
        )
        assertEquals("x86", upstreamDownloadLink(upstream, false, listOf("missing", "x86")))
        assertEquals("x64", upstreamDownloadLink(upstream, false, listOf("x86_64", "x86")))
        assertEquals("foss", upstreamDownloadLink(upstream, true, listOf("arm64-v8a")))
        assertNull(upstreamDownloadLink(upstream, false, listOf("unsupported")))
        assertEquals(
            "universal",
            upstreamDownloadLink(
                upstream.copy(
                    assets =
                    upstream.assets + GitHubAsset("mihon.apk", "universal"),
                ),
                false,
                emptyList(),
            ),
        )
    }
}
