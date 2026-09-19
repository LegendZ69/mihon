package mihon.feature.translation.ui

import kotlinx.serialization.json.Json
import mihon.feature.translation.provider.OfficialProviderCapabilities
import mihon.feature.translation.provider.OfficialProviderPricing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationSettings
import java.time.LocalDate

class TranslationSettingsPresentationTest {
    @Test
    fun `pricing link follows the displayed model price and stays absent for unavailable pricing`() {
        val groq = OfficialProviderPricing.price(
            ProviderSettings(
                kind = TranslationProviderKind.OPENAI,
                baseUrl = "https://api.groq.com/openai/v1",
                model = "openai/gpt-oss-120b",
            ),
            LocalDate.of(2026, 9, 12),
        )
        assertEquals("https://console.groq.com/docs/model/openai/gpt-oss-120b", providerPricingLink(groq))
        assertEquals(
            OfficialProviderPricing.SOURCE_URL,
            providerPricingLink(OfficialProviderPricing.price(ProviderSettings(), LocalDate.of(2026, 9, 12))),
        )
        assertNull(providerPricingLink(null))
    }

    @Test
    fun `image limits distinguish unsupported text input from unverified or known image ceilings`() {
        val known = OfficialProviderCapabilities.forSettings(ProviderSettings())
        val textOnly = OfficialProviderCapabilities.forSettings(
            ProviderSettings(
                kind = TranslationProviderKind.OPENAI,
                baseUrl = "https://api.groq.com/openai/v1",
                model = "openai/gpt-oss-120b",
            ),
        )
        assertEquals("Images/request: unsupported (text-only model)", providerImageInputLimitLabel(textOnly))
        assertEquals("Images/request: 3000", providerImageInputLimitLabel(known))
        assertEquals("Images/request: unverified", providerImageInputLimitLabel(known.copy(maxImages = 0)))
    }

    @Test
    fun `verified text only providers hide image upload controls while vision and unverified providers retain them`() {
        val imageControls = setOf(
            "Image media resolution",
            "OpenAI image detail",
            "Tile long and large upload images",
            "Upload tile maximum edge",
            "Upload tile maximum pixels",
            "Upload tile overlap",
        )
        val groq = ProviderSettings(
            kind = TranslationProviderKind.OPENAI,
            baseUrl = "https://api.groq.com/openai/v1",
            model = "openai/gpt-oss-120b",
        )
        fun labels(provider: ProviderSettings) = translationSettingsFields(
            TranslationSettings(provider = provider),
            {},
            Json,
        ).map { it.label }.toSet()

        assertEquals(emptySet<String>(), labels(groq).intersect(imageControls))
        assertTrue(labels(groq).containsAll(listOf("OCR pipeline", "Paddle model size", "Review coverage")))
        assertEquals(imageControls, labels(ProviderSettings()).intersect(imageControls))
        assertEquals(
            imageControls,
            labels(groq.copy(baseUrl = "https://compatibility.invalid/v1")).intersect(imageControls),
        )
    }
}
