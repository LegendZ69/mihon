package tachiyomi.domain.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.TranslationPromptPair
import tachiyomi.domain.translation.model.TranslationPromptTemplates
import tachiyomi.domain.translation.model.TranslationPrompts
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.TranslationSettingsAutosaver

class TranslationPromptTemplatesTest {
    @Test
    fun `substitution preserves literal values and never expands inserted template syntax`() {
        val settings = TranslationSettings(glossary = "Name means {{target_language}}", targetLanguage = "English")
        TranslationPromptTemplates.render("{{glossary}} / {{target_language}}", settings, "") shouldBe
            "Name means {{target_language}} / English"
    }

    @Test
    fun `unknown or incomplete variables are editable errors`() {
        listOf("{{api_key}}", "{{target_language", "target_language}}", "{{ target_language }}").forEach {
            shouldThrow<IllegalArgumentException> { TranslationPromptTemplates.validate(it) }
        }
        TranslationPromptTemplates.validate("JSON object: {\"pages\": []}")
    }

    @Test
    fun `legacy settings retain builtins while empty replacements survive a round trip`() {
        Json.decodeFromString<TranslationSettings>("{}").prompts shouldBe TranslationPrompts()
        val custom = TranslationSettings(prompts = TranslationPrompts(translation = TranslationPromptPair("", "")))
        Json.decodeFromString<TranslationSettings>(Json.encodeToString(custom)).prompts.translation shouldBe
            TranslationPromptPair("", "")
    }

    @Test
    fun `invalid prompt drafts cannot replace saved values and navigation flushes valid edits`() = runTest {
        val writes = mutableListOf<TranslationSettings>()
        val saver = TranslationSettingsAutosaver(this) { writes += it }
        val valid =
            TranslationSettings(prompts = TranslationPrompts(translation = TranslationPromptPair("Saved", null)))
        saver.update(valid)
        advanceTimeBy(300)
        runCurrent()
        writes.single().prompts.translation.system shouldBe "Saved"
        saver.update(valid.copy(prompts = TranslationPrompts(translation = TranslationPromptPair("{{secret}}", null))))
        saver.flush()
        writes.size shouldBe 1
        (saver.state.value.error != null) shouldBe true
        saver.update(valid.copy(prompts = TranslationPrompts(translation = TranslationPromptPair("Next", null))))
        saver.flush()
        writes.last().prompts.translation.system shouldBe "Next"
    }
}
