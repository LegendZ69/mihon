package mihon.feature.translation.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.TranslationSettings

class TranslationSettingsExportTest {
    @Test
    fun `settings exports omit private payloads while preserving configured nonsecret settings`() {
        val json = Json { encodeDefaults = true }
        val original = TranslationSettings(
            provider = ProviderSettings(
                credentialId = "private-reference-marker",
                baseUrl = "https://user-marker:password-marker@example.invalid/v1?key=query-marker#fragment-marker",
                extraHeaders = mapOf("X-Custom" to "header-marker"),
                advancedJson = """{"api_key":"nested-key-marker","temperature":0.4,""" +
                    """"thoughtSignature":"thought-marker"}""",
            ),
            targetLanguage = "ko",
            autoTranslate = true,
            chaptersAhead = 2,
            glossary = "Name = retained term",
        )
        val exported = serializeTranslationSettingsExport(original, json)
        assertAll(
            *listOf(
                "private-reference-marker",
                "user-marker",
                "password-marker",
                "query-marker",
                "fragment-marker",
                "header-marker",
                "nested-key-marker",
                "thought-marker",
            ).map { marker ->
                org.junit.jupiter.api.function.Executable {
                    assertFalse(exported.contains(marker), "Private value remained in export: $marker")
                }
            }.toTypedArray(),
        )
        val restored = json.decodeFromString<TranslationSettings>(exported)
        assertEquals("https://example.invalid/v1", restored.provider.baseUrl)
        assertEquals(emptyMap<String, String>(), restored.provider.extraHeaders)
        assertEquals(
            "0.4",
            json.parseToJsonElement(restored.provider.advancedJson).jsonObject["temperature"].toString(),
        )
        assertEquals("ko", restored.targetLanguage)
        assertEquals(true, restored.autoTranslate)
        assertEquals(2, restored.chaptersAhead)
        assertEquals(original.glossary, restored.glossary)
        assertEquals("private-reference-marker", original.provider.credentialId)
    }
}
