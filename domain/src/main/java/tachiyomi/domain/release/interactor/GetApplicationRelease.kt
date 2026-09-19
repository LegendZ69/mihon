package tachiyomi.domain.release.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

@Inject
class GetApplicationRelease(
    private val service: ReleaseService,
) {
    suspend fun await(arguments: Arguments): Result {
        val release = service.latest(arguments) ?: return Result.NoNewUpdate

        // Check if latest version is different from current version
        val isNewVersion = if (arguments.isTranslator) {
            val apk = requireNotNull(release.apk) { "Translator release is missing verified APK metadata" }
            apk.versionCode > arguments.installedVersionCode
        } else {
            isNewVersion(arguments.isNightly, arguments.commitCount, arguments.versionName, release.version)
        }
        return when {
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    private fun isNewVersion(
        isNightly: Boolean,
        commitCount: Int,
        versionName: String,
        versionTag: String,
    ): Boolean {
        return if (isNightly) {
            val revision = versionTag.removePrefix("r").toLongOrNull() ?: return false
            revision > commitCount
        } else {
            val newSemVer = versionParts(versionTag) ?: return false
            val oldSemVer = versionParts(versionName) ?: return false
            for (index in 0 until maxOf(newSemVer.size, oldSemVer.size)) {
                val comparison = (newSemVer.getOrElse(index) { 0L }).compareTo(oldSemVer.getOrElse(index) { 0L })
                if (comparison != 0) return comparison > 0
            }
            false
        }
    }

    private fun versionParts(value: String): List<Long>? {
        val numeric = value.removePrefix("v").substringBefore('-').substringBefore('+')
        if (!numeric.matches(Regex("[0-9]+(?:\\.[0-9]+)*"))) return null
        return numeric.split('.').map { it.toLongOrNull() ?: return null }
    }

    data class Arguments(
        val isFoss: Boolean,
        val isNightly: Boolean,
        val commitCount: Int,
        val versionName: String,
        val repository: String,
        val forceCheck: Boolean = false,
        val isTranslator: Boolean = false,
        val installedVersionCode: Long = 0,
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }
}
