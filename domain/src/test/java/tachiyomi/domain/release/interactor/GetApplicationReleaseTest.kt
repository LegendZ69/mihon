package tachiyomi.domain.release.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseApk
import tachiyomi.domain.release.service.ReleaseService

class GetApplicationReleaseTest {

    @Test
    fun `release versions compare the first different component and pad missing components`() = runTest {
        for ((current, latest, expected) in listOf(
            Triple("2.0.0", "v1.9.9", false),
            Triple("1.9.9", "v2.0.0", true),
            Triple("1.2.3", "v1.2", false),
            Triple("1.2", "v1.2.1", true),
            Triple("1.2", "v1.2.0", false),
            Triple("1.2.0", "unexpected", false),
        )) {
            coEvery { releaseService.latest(any()) } returns Release(latest, "", "", "")
            val result = getApplicationRelease.await(
                GetApplicationRelease.Arguments(false, false, 0, current, "test"),
            )
            (result is GetApplicationRelease.Result.NewUpdate) shouldBe expected
        }
    }

    @Test
    fun `translator versions compare numeric APK identity independently of upstream version`() = runTest {
        for ((code, expected) in listOf(100_014L to false, 100_015L to false, 100_019L to true)) {
            coEvery { releaseService.latest(any()) } returns Release(
                "translator-v${code - 100_000}",
                "",
                "",
                "",
                ReleaseApk(code, "app.mihon", "arm64-v8a", 10, "a".repeat(64), "b".repeat(64)),
                isPrerelease = true,
            )
            val result = getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    false,
                    false,
                    0,
                    "99.0.0-translator.15",
                    "test",
                    isTranslator = true,
                    installedVersionCode = 100_015,
                ),
            )
            (result is GetApplicationRelease.Result.NewUpdate) shouldBe expected
        }
    }

    @Test
    fun `malformed nightly revision is ignored without crashing`() = runTest {
        coEvery { releaseService.latest(any()) } returns Release("r-invalid", "", "", "")
        getApplicationRelease.await(GetApplicationRelease.Arguments(false, true, 100, "", "test")) shouldBe
            GetApplicationRelease.Result.NoNewUpdate
    }

    private lateinit var getApplicationRelease: GetApplicationRelease
    private lateinit var releaseService: ReleaseService

    @BeforeEach
    fun beforeEach() {
        releaseService = mockk()

        getApplicationRelease = GetApplicationRelease(releaseService)
    }

    @Test
    fun `When has update but is nightly expect new update`() = runTest {
        val release = Release(
            "r2000",
            "info",
            "http://example.com/release_link",
            "http://example.com/release_link.apk",
        )

        coEvery { releaseService.latest(any()) } returns release

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isNightly = true,
                commitCount = 1000,
                versionName = "",
                repository = "test",
            ),
        )

        (result as GetApplicationRelease.Result.NewUpdate).release shouldBe GetApplicationRelease.Result.NewUpdate(
            release,
        ).release
    }

    @Test
    fun `When has update expect new update`() = runTest {
        val release = Release(
            "v2.0.0",
            "info",
            "http://example.com/release_link",
            "http://example.com/release_link.apk",
        )

        coEvery { releaseService.latest(any()) } returns release

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isNightly = false,
                commitCount = 0,
                versionName = "v1.0.0",
                repository = "test",
            ),
        )

        (result as GetApplicationRelease.Result.NewUpdate).release shouldBe GetApplicationRelease.Result.NewUpdate(
            release,
        ).release
    }

    @Test
    fun `When has no update expect no new update`() = runTest {
        val release = Release(
            "v1.0.0",
            "info",
            "http://example.com/release_link",
            "http://example.com/release_link.apk",
        )

        coEvery { releaseService.latest(any()) } returns release

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isNightly = false,
                commitCount = 0,
                versionName = "v2.0.0",
                repository = "test",
            ),
        )

        result shouldBe GetApplicationRelease.Result.NoNewUpdate
    }
}
