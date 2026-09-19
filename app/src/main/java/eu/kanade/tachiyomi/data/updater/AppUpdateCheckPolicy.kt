package eu.kanade.tachiyomi.data.updater

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import kotlin.time.Duration.Companion.hours

internal class AppUpdateCheckPolicy(
    preferenceStore: PreferenceStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastSuccess = preferenceStore.getLong(Preference.appStateKey("app_update_last_success"))
    private val lastFailure = preferenceStore.getLong(Preference.appStateKey("app_update_last_failure"))
    private val announced = preferenceStore.getString(Preference.appStateKey("app_update_announced_release"))

    fun shouldCheck(force: Boolean): Boolean {
        if (force) return true
        val now = clock()

        // A clock correction must not prevent future checks indefinitely.
        fun recent(timestamp: Long, window: Long) = timestamp > 0 && now >= timestamp && now - timestamp < window
        return !recent(lastSuccess.get(), 24.hours.inWholeMilliseconds) &&
            !recent(lastFailure.get(), 1.hours.inWholeMilliseconds)
    }

    fun recordSuccess() {
        lastSuccess.set(clock())
        lastFailure.delete()
    }

    fun recordFailure() {
        // A failed manual check should retry after one hour, even after an earlier successful check.
        lastSuccess.delete()
        lastFailure.set(clock())
    }

    fun shouldAnnounce(identity: String, force: Boolean): Boolean {
        if (!force && announced.get() == identity) return false
        announced.set(identity)
        return true
    }
}
