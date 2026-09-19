package tachiyomi.domain.translation

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.TranslationSettingsAutosaver

class TranslationSettingsAutosaverTest {
    @Test
    fun `typing saves the latest valid settings after 300 milliseconds without Apply`() = runTest {
        val writes = mutableListOf<TranslationSettings>()
        val saver = TranslationSettingsAutosaver(this) { writes += it }
        saver.update(TranslationSettings(targetLanguage = "j"))
        advanceTimeBy(200)
        saver.update(TranslationSettings(targetLanguage = "ja"))
        advanceTimeBy(299)
        runCurrent()
        writes.size shouldBe 0
        advanceTimeBy(1)
        runCurrent()
        writes.map { it.targetLanguage } shouldBe listOf("ja")
        saver.state.value.saving shouldBe false
    }

    @Test
    fun `toggle persists immediately and invalid input preserves saved settings`() = runTest {
        val writes = mutableListOf<TranslationSettings>()
        val saver = TranslationSettingsAutosaver(this) { writes += it }
        saver.update(TranslationSettings(autoTranslate = true), immediate = true)
        runCurrent()
        writes.single().autoTranslate shouldBe true
        saver.update(TranslationSettings(targetLanguage = ""))
        advanceTimeBy(400)
        runCurrent()
        writes.size shouldBe 1
        (saver.state.value.error != null) shouldBe true
    }

    @Test
    fun `navigation flushes pending text and reset cancels pending series override`() = runTest {
        val writes = mutableListOf<String>()
        val saver = TranslationSettingsAutosaver(this) { writes += it.targetLanguage }
        saver.update(TranslationSettings(targetLanguage = "zh"))
        saver.flush()
        writes shouldBe listOf("zh")
        saver.update(TranslationSettings(targetLanguage = "ko"))
        saver.reset { writes += "reset" }
        advanceTimeBy(500)
        runCurrent()
        writes shouldBe listOf("zh", "reset")
    }

    @Test
    fun `storage failure is visible and a later flush can save the same draft`() = runTest {
        var unavailable = true
        val writes = mutableListOf<String>()
        val saver = TranslationSettingsAutosaver(this) {
            check(!unavailable) { "Storage unavailable" }
            writes += it.targetLanguage
        }
        saver.update(TranslationSettings(targetLanguage = "ja"), immediate = true)
        runCurrent()
        saver.state.value.error shouldBe "Storage unavailable"
        writes.size shouldBe 0
        unavailable = false
        saver.flush()
        writes shouldBe listOf("ja")
        saver.state.value.error shouldBe null
    }
}
