package mihon.feature.translation.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationUsage
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset

data class VerifiedTokenPrice(
    val inputPerMillionUsd: String,
    val cachedInputPerMillionUsd: String,
    val outputPerMillionUsd: String,
    val promotionalCredit: Boolean,
    val effectiveFrom: String,
    val effectiveThrough: String?,
    val verifiedAt: String = OfficialProviderPricing.VERIFIED_AT,
    val sourceUrl: String = OfficialProviderPricing.SOURCE_URL,
)

data class TranslationCostEstimate(
    val estimatedUsd: String,
    val beforePromotionalCreditUsd: String,
    val price: VerifiedTokenPrice,
)

/** A dated list-price estimate, never a substitute for account-specific billing. */
object OfficialProviderPricing {
    const val VERIFIED_AT = "2026-09-05"
    const val SOURCE_URL = "https://cloud.google.com/gemini-enterprise-agent-platform/generative-ai/pricing"

    fun price(settings: ProviderSettings, date: LocalDate = LocalDate.now(ZoneOffset.UTC)): VerifiedTokenPrice? {
        if (OfficialProviderCapabilities.isGroqTextModel(settings)) {
            val advanced =
                runCatching {
                    TranslationWireFormat.json.parseToJsonElement(settings.advancedJson) as? JsonObject
                }.getOrNull()
                    ?: return null
            val tier = advanced["service_tier"]
            if (date < LocalDate.of(2026, 9, 12) ||
                (tier != null && (tier !is JsonPrimitive || !tier.isString || tier.content != "on_demand"))
            ) {
                return null
            }
            return VerifiedTokenPrice(
                "0.15",
                "0.075",
                "0.60",
                promotionalCredit = false,
                // The observed catalog starts here; this does not assert when the provider introduced its price.
                effectiveFrom = "2026-09-12",
                effectiveThrough = null,
                verifiedAt = "2026-09-12",
                sourceUrl = OfficialProviderCapabilities.GROQ_MODEL_DOCUMENTATION,
            )
        }
        if (settings.kind == TranslationProviderKind.OPENAI ||
            settings.model != OfficialProviderCapabilities.GEMINI_MODEL ||
            settings.provisionedThroughput || date < LocalDate.of(2026, 9, 2)
        ) {
            return null
        }
        val promotional = date < LocalDate.of(2027, 1, 1)
        val global = settings.kind == TranslationProviderKind.VERTEX_EXPRESS || settings.location == "global"
        val rates = when {
            settings.priorityPaygo && global -> if (promotional) {
                listOf(
                    "1.35",
                    "0.135",
                    "6.75",
                )
            } else {
                listOf("2.70", "0.27", "13.50")
            }
            settings.priorityPaygo -> if (promotional) {
                listOf(
                    "1.485",
                    "0.1485",
                    "7.425",
                )
            } else {
                listOf("2.97", "0.297", "14.85")
            }
            global -> if (promotional) listOf("0.75", "0.075", "3.75") else listOf("1.50", "0.15", "7.50")
            else -> if (promotional) listOf("0.825", "0.0825", "4.125") else listOf("1.65", "0.165", "8.25")
        }
        return VerifiedTokenPrice(
            rates[0],
            rates[1],
            rates[2],
            promotional,
            if (promotional) "2026-09-02" else "2027-01-01",
            if (promotional) "2026-12-31" else null,
        )
    }

    fun estimate(
        settings: ProviderSettings,
        usage: TranslationUsage,
        date: LocalDate = LocalDate.now(
            ZoneOffset.UTC,
        ),
    ): TranslationCostEstimate? {
        if (usage.trafficType?.contains("PROVISIONED", ignoreCase = true) == true) return null
        if (OfficialProviderCapabilities.isGroqTextModel(settings) && usage.trafficType != null &&
            usage.trafficType != "on_demand"
        ) {
            return null
        }
        val price = price(settings, date) ?: return null
        val input = usage.inputTokens?.takeIf { it >= 0 } ?: return null
        val output = usage.outputTokens?.takeIf { it >= 0 } ?: return null
        val cached = (usage.cachedTokens ?: 0).coerceIn(0, input)
        // OpenAI-compatible completion/output tokens already include reasoning; Vertex reports it separately.
        val reasoning = if (settings.kind ==
            TranslationProviderKind.OPENAI
        ) {
            0
        } else {
            (usage.reasoningTokens ?: 0).coerceAtLeast(0)
        }
        val total = (
            BigDecimal(input - cached) * BigDecimal(price.inputPerMillionUsd) +
                BigDecimal(cached) * BigDecimal(price.cachedInputPerMillionUsd) +
                (BigDecimal(output) + BigDecimal(reasoning)) * BigDecimal(price.outputPerMillionUsd)
            )
            .divide(BigDecimal(1_000_000), 10, RoundingMode.HALF_UP)
        return TranslationCostEstimate(
            total.stripTrailingZeros().toPlainString(),
            (if (price.promotionalCredit) total * BigDecimal(2) else total).stripTrailingZeros().toPlainString(),
            price,
        )
    }
}
