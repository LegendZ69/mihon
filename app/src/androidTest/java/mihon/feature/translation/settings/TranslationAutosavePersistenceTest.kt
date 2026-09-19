package mihon.feature.translation.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.feature.translation.TranslationPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.service.TranslationSettingsAutosaver
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** The production preference implementation writes only a uniquely named disposable store. */
@RunWith(AndroidJUnit4::class)
class TranslationAutosavePersistenceTest {
    @Test
    fun validChangesSaveAndInvalidDraftCannotReplaceTheLastCommittedSettings() = runBlocking<Unit> {
        fixture { context ->
            val preferences = TranslationPreferences(context)
            val writes = AtomicInteger()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            try {
                val saver =
                    TranslationSettingsAutosaver(scope) { value ->
                        withContext(Dispatchers.IO) {
                            preferences.update(value)
                            writes.incrementAndGet()
                        }
                    }
                withContext(Dispatchers.Main) {
                    saver.update(preferences.settings.value.copy(notificationChapterCards = 2), immediate = true)
                }
                saved(saver)
                assertEquals(2, TranslationPreferences(context).settings.value.notificationChapterCards)
                withContext(Dispatchers.Main) {
                    saver.update(preferences.settings.value.copy(targetLanguage = "ja"))
                    saver.update(preferences.settings.value.copy(targetLanguage = "ko"))
                }
                saved(saver)
                assertEquals(2, writes.get())
                assertEquals("ko", TranslationPreferences(context).settings.value.targetLanguage)
                withContext(Dispatchers.Main) {
                    saver.update(preferences.settings.value.copy(targetLanguage = ""))
                    saver.flush()
                }
                assertNotNull(saver.state.value.error)
                assertEquals(2, writes.get())
                assertEquals("ko", TranslationPreferences(context).settings.value.targetLanguage)
                assertEquals(
                    ProviderSettings().credentialId,
                    TranslationPreferences(context).settings.value.provider.credentialId,
                )
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun navigationFlushAndSeriesResetKeepScopesSeparateAcrossPreferenceRecreation() = runBlocking<Unit> {
        fixture { context ->
            val preferences = TranslationPreferences(context)
            withContext(Dispatchers.IO) { preferences.update(preferences.settings.value.copy(targetLanguage = "fr")) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            try {
                fun saver(
                    id: Long,
                ) = TranslationSettingsAutosaver(scope) { value ->
                    withContext(Dispatchers.IO) { preferences.update(value, id) }
                }
                val first = saver(41)
                val second = saver(42)
                withContext(Dispatchers.Main) {
                    first.update(preferences.settings.value.copy(targetLanguage = "ja"))
                    second.update(preferences.settings.value.copy(targetLanguage = "zh-TW"))
                    first.flush()
                    second.flush()
                }
                val recreated = TranslationPreferences(context)
                assertEquals("fr", recreated.settings.value.targetLanguage)
                assertEquals("ja", recreated.effectiveSettings(41).targetLanguage)
                assertEquals("zh-TW", recreated.effectiveSettings(42).targetLanguage)
                withContext(Dispatchers.Main) {
                    first.update(preferences.effectiveSettings(41).copy(targetLanguage = "ko"))
                    first.reset { withContext(Dispatchers.IO) { preferences.reset(41) } }
                }
                delay(400)
                val afterReset = TranslationPreferences(context)
                assertFalse(afterReset.hasSeriesOverride(41))
                assertEquals("fr", afterReset.effectiveSettings(41).targetLanguage)
                assertEquals("zh-TW", afterReset.effectiveSettings(42).targetLanguage)
                assertTrue(afterReset.hasSeriesOverride(42))
            } finally {
                scope.cancel()
            }
        }
    }

    /** Run write and collect in separate target processes using the same UUID; parent records the actual kill/relaunch trigger. */
    @Test
    fun committedAutosaveCheckpointSurvivesASeparateTargetProcess() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("translation.settingsPhase")
        assumeTrue(phase == "write" || phase == "collect")
        val runId = requireNotNull(arguments.getString("translation.settingsRunId"))
        require(UUID.fromString(runId).toString() == runId)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.mihon.benchmark", base.packageName)
        val context = IsolatedContext(base, runId)
        val preferences = TranslationPreferences(context)
        val marker = context.getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
        if (phase == "write") {
            check(marker.all.isEmpty()) { "Use a fresh acceptance UUID; existing evidence is retained" }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            try {
                val global =
                    TranslationSettingsAutosaver(scope) { value ->
                        withContext(Dispatchers.IO) { preferences.update(value) }
                    }
                val series =
                    TranslationSettingsAutosaver(scope) { value ->
                        withContext(Dispatchers.IO) { preferences.update(value, 42) }
                    }
                withContext(Dispatchers.Main) {
                    global.update(preferences.settings.value.copy(targetLanguage = "ja"))
                    global.flush()
                    series.update(preferences.settings.value.copy(targetLanguage = "zh-TW"))
                    series.flush()
                }
                withContext(Dispatchers.IO) {
                    check(marker.edit().putInt("acceptance_writer_pid", Process.myPid()).commit())
                }
                println(
                    "TRANSLATION_SETTINGS_CHECKPOINT phase=write run=$runId pid=${Process.myPid()} global=ja series42=zh-TW",
                )
            } finally {
                scope.cancel()
            }
        } else {
            val writer = marker.getInt("acceptance_writer_pid", -1)
            assertTrue("A completed write phase is required", writer > 0)
            assertNotEquals(
                "Collect must use a different target process; this does not by itself identify how the old process ended",
                writer,
                Process.myPid(),
            )
            assertEquals("ja", preferences.settings.value.targetLanguage)
            assertEquals("zh-TW", preferences.effectiveSettings(42).targetLanguage)
            assertEquals(ProviderSettings().credentialId, preferences.settings.value.provider.credentialId)
            assertEquals(ProviderSettings().credentialId, preferences.effectiveSettings(42).provider.credentialId)
            withContext(Dispatchers.IO) { assertTrue(base.deleteSharedPreferences(context.storeName)) }
            println(
                "TRANSLATION_SETTINGS_CHECKPOINT phase=collect run=$runId writerPid=$writer readerPid=${Process.myPid()} preserved=true ownedStoreRemoved=true",
            )
        }
    }

    private suspend fun saved(saver: TranslationSettingsAutosaver) = withTimeout(5_000) {
        saver.state.first { !it.saving }.also { assertEquals(null, it.error) }
    }

    private suspend fun fixture(block: suspend (IsolatedContext) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = IsolatedContext(base, UUID.randomUUID().toString())
        try {
            block(context)
        } finally {
            withContext(Dispatchers.IO) { assertTrue(base.deleteSharedPreferences(context.storeName)) }
        }
    }

    private class IsolatedContext(base: Context, runId: String) : ContextWrapper(base) {
        val storeName = "translation-autosave-acceptance-$runId"
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            require(name == "translator_settings")
            return baseContext.getSharedPreferences(storeName, mode)
        }
    }
}
