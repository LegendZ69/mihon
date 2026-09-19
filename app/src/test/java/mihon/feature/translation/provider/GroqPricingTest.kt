package mihon.feature.translation.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationUsage
import java.time.LocalDate

class GroqPricingTest {
    @Test
    fun `provider-reported nonstandard Groq tier cannot inherit the on-demand price`() {
        val settings =
            ProviderSettings(
                kind = TranslationProviderKind.OPENAI,
                baseUrl = "https://api.groq.com/openai/v1",
                model = "openai/gpt-oss-120b",
            )
        assertNull(
            OfficialProviderPricing.estimate(
                settings,
                TranslationUsage(inputTokens = 100, outputTokens = 20, trafficType = "performance"),
                LocalDate.of(2026, 9, 12),
            ),
        )
    }

    @Test
    fun `Groq dated on-demand estimate bills cached input separately and includes reasoning in output only once`() {
        val settings =
            ProviderSettings(
                kind = TranslationProviderKind.OPENAI,
                baseUrl = "https://api.groq.com/openai/v1",
                model = "openai/gpt-oss-120b",
            )
        val usage =
            TranslationUsage(
                inputTokens = 1_000_000,
                outputTokens = 1_000_000,
                cachedTokens = 200_000,
                reasoningTokens = 250_000,
            )
        val date = LocalDate.of(2026, 9, 12)
        val estimate = OfficialProviderPricing.estimate(settings, usage, date)
        assertNotNull(estimate)
        assertEquals("0.735", estimate!!.estimatedUsd)
        assertEquals("0.735", estimate.beforePromotionalCreditUsd)
        assertEquals("2026-09-12", estimate.price.verifiedAt)
        assertEquals("https://console.groq.com/docs/model/openai/gpt-oss-120b", estimate.price.sourceUrl)
        assertFalse(estimate.price.promotionalCredit)
        assertEquals(
            estimate,
            OfficialProviderPricing.estimate(
                settings.copy(advancedJson = """{"service_tier":"on_demand"}"""),
                usage,
                date,
            ),
        )
        assertNull(OfficialProviderPricing.estimate(settings, TranslationUsage(), date))
        assertNull(OfficialProviderPricing.price(settings.copy(model = "unknown"), date))
        assertNull(OfficialProviderPricing.price(settings, date.minusDays(1)))
        for (tier in listOf("auto", "performance", "flex")) {
            assertNull(
                OfficialProviderPricing.price(settings.copy(advancedJson = """{"service_tier":"$tier"}"""), date),
            )
        }
    }
}
