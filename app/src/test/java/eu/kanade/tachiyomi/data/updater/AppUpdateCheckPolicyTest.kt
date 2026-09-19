package eu.kanade.tachiyomi.data.updater

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import kotlin.time.Duration.Companion.hours

class AppUpdateCheckPolicyTest {
    private var now = 1_000_000L
    private val longs = mutableMapOf<String, Preference<Long>>()
    private val strings = mutableMapOf<String, Preference<String>>()
    private val store = mockk<PreferenceStore> {
        every { getLong(any(), any()) } answers { longs.getOrPut(firstArg()) { preference(0L) } }
        every { getString(any(), any()) } answers { strings.getOrPut(firstArg()) { preference("") } }
    }
    private val policy = AppUpdateCheckPolicy(store) { now }

    @Test
    fun `automatic success checks wait a day while manual checks always run`() {
        assertTrue(policy.shouldCheck(false))
        policy.recordSuccess()
        assertFalse(policy.shouldCheck(false))
        assertTrue(policy.shouldCheck(true))
        now += 24.hours.inWholeMilliseconds - 1
        assertFalse(policy.shouldCheck(false))
        now++
        assertTrue(policy.shouldCheck(false))
    }

    @Test
    fun `failures wait one hour including a failed manual check after success`() {
        policy.recordSuccess()
        policy.recordFailure()
        assertFalse(policy.shouldCheck(false))
        assertTrue(policy.shouldCheck(true))
        now += 1.hours.inWholeMilliseconds
        assertTrue(policy.shouldCheck(false))
        policy.recordSuccess()
        assertFalse(policy.shouldCheck(false))
    }

    @Test
    fun `announcement suppression persists and manual checks still show the same version`() {
        assertTrue(policy.shouldAnnounce("fork:translator-v20", false))
        val recreated = AppUpdateCheckPolicy(store) { now }
        assertFalse(recreated.shouldAnnounce("fork:translator-v20", false))
        assertTrue(recreated.shouldAnnounce("fork:translator-v20", true))
        assertTrue(recreated.shouldAnnounce("fork:translator-v21", false))
    }

    @Test
    fun `clock moving backwards cannot suppress checking indefinitely`() {
        policy.recordSuccess()
        now--
        assertTrue(policy.shouldCheck(false))
    }

    private inline fun <reified T : Any> preference(initial: T): Preference<T> {
        var value = initial
        return mockk {
            every { get() } answers { value }
            every { set(any()) } answers { value = firstArg() }
            every { delete() } answers { value = initial }
        }
    }
}
