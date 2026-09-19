package eu.kanade.tachiyomi.data.updater

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isFossBuildType
import eu.kanade.tachiyomi.util.system.isNightlyBuildType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.interactor.GetApplicationRelease

@Inject
@SingleIn(AppScope::class)
class AppUpdateChecker(
    private val getApplicationRelease: GetApplicationRelease,
    preferenceStore: PreferenceStore,
) {
    private val policy = AppUpdateCheckPolicy(preferenceStore)
    private val mutex = Mutex()

    suspend fun checkForUpdate(forceCheck: Boolean = false): GetApplicationRelease.Result = withIOContext {
        mutex.withLock {
            if (!policy.shouldCheck(forceCheck)) return@withLock GetApplicationRelease.Result.NoNewUpdate
            try {
                val result = getApplicationRelease.await(
                    GetApplicationRelease.Arguments(
                        isFoss = isFossBuildType,
                        isNightly = isNightlyBuildType,
                        commitCount = BuildConfig.COMMIT_COUNT.toInt(),
                        versionName = BuildConfig.VERSION_NAME,
                        repository = GITHUB_REPO,
                        forceCheck = forceCheck,
                        isTranslator = BuildConfig.TRANSLATOR_RELEASE_NUMBER > 0,
                        installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    ),
                )
                policy.recordSuccess()
                if (result is GetApplicationRelease.Result.NewUpdate &&
                    !policy.shouldAnnounce("$GITHUB_REPO:${result.release.version}", forceCheck)
                ) {
                    GetApplicationRelease.Result.NoNewUpdate
                } else {
                    result
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                policy.recordFailure()
                throw error
            }
        }
    }
}

val GITHUB_REPO: String by lazy {
    when {
        BuildConfig.TRANSLATOR_RELEASE_NUMBER > 0 -> "LegendZ69/mihon"
        isNightlyBuildType -> "mihonapp/mihon-preview"
        else -> "mihonapp/mihon"
    }
}

val RELEASE_TAG: String by lazy {
    when {
        BuildConfig.TRANSLATOR_RELEASE_NUMBER > 0 -> "translator-v${BuildConfig.TRANSLATOR_RELEASE_NUMBER}"
        isNightlyBuildType -> "r${BuildConfig.COMMIT_COUNT}"
        else -> "v${BuildConfig.VERSION_NAME}"
    }
}

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
