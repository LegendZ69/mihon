package mihon.feature.translation.provider

import tachiyomi.domain.translation.model.OpenAiDialect
import tachiyomi.domain.translation.model.ProviderCapabilities
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.TranslationProviderKind

/** Versioned facts, never extrapolated from a model name or the size of the user's device. */
object OfficialProviderCapabilities {
    const val VERIFIED_AT = "2026-09-05"
    const val GEMINI_MODEL = "gemini-3.8-flash"
    const val GEMINI_DOCUMENTATION =
        "https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/gemini/3-8-flash"
    const val OPENAI_DOCUMENTATION = "https://developers.openai.com/api/docs/guides/images-vision"
    const val GROQ_GPT_OSS_MODEL = "openai/gpt-oss-120b"
    const val GROQ_VERIFIED_AT = "2026-09-12"
    const val GROQ_MODEL_DOCUMENTATION = "https://console.groq.com/docs/model/openai/gpt-oss-120b"

    fun isGroq(settings: ProviderSettings): Boolean = settings.kind == TranslationProviderKind.OPENAI &&
        settings.baseUrl.trimEnd('/') == "https://api.groq.com/openai/v1"

    fun isGroqTextModel(settings: ProviderSettings): Boolean = isGroq(settings) && settings.model == GROQ_GPT_OSS_MODEL

    fun imageDetails(settings: ProviderSettings): Set<String> = when {
        isGroqTextModel(settings) -> emptySet()
        isGroq(settings) -> setOf("auto", "low", "high")
        else -> setOf("auto", "low", "high", "original")
    }

    /** Groq fields currently used by this translator; other dialects retain their separate field validation. */
    fun groqAdvancedParameters(settings: ProviderSettings): Set<String> =
        if (settings.dialect == OpenAiDialect.RESPONSES) {
            setOf("temperature", "top_p", "reasoning", "service_tier")
        } else {
            setOf("temperature", "top_p", "seed", "stop", "reasoning_effort", "include_reasoning", "service_tier")
        }

    fun forSettings(settings: ProviderSettings): ProviderCapabilities {
        if (isGroqTextModel(settings)) {
            return ProviderCapabilities(
                model = settings.model,
                verifiedAt = GROQ_VERIFIED_AT,
                sourceUrl = GROQ_MODEL_DOCUMENTATION,
                maxImages = 0,
                maxInlineImageBytes = 0,
                maxInputTokens = 131_072,
                maxOutputTokens = 65_536,
                supportsVision = false,
                supportsCountTokens = false,
                maxRequestBytes = settings.maxRequestBytes,
                notes = listOf(
                    "Text input only. Choose PaddleOCR for local geometry and transcription; review is text-only.",
                    "131,072 tokens is the shared input/output context, not an independent input allowance.",
                    "Reasoning effort: low, medium (provider default), high. JSON Schema output is supported.",
                    "No documented count-tokens endpoint: exact input-token preflight is unavailable.",
                    "Responses is beta. Store, image detail, penalties, verbosity and logprobs are not sent.",
                ),
            )
        }
        if (settings.kind != TranslationProviderKind.OPENAI && settings.model == GEMINI_MODEL) {
            return ProviderCapabilities(
                model = settings.model,
                verifiedAt = VERIFIED_AT,
                sourceUrl = GEMINI_DOCUMENTATION,
                maxImages = 3000,
                maxInlineImageBytes = 7_000_000,
                maxInputTokens = 1_048_576,
                maxOutputTokens = 65_536,
                supportsCountTokens = true,
                maxRequestBytes = settings.maxRequestBytes,
                notes = listOf(
                    "Global, US and EU; Gemini 3.8 Flash is generally available.",
                    "LOW, MEDIUM (default), HIGH thinking only. Sampling settings are ignored; penalties and " +
                        "candidate count are unsupported.",
                    "Express access depends on the account. Its published model table has not yet listed 3.8 Flash.",
                    "Maximum request bytes, when configured, is a user budget, not a documented Vertex limit.",
                ),
            )
        }
        val openAi = settings.kind == TranslationProviderKind.OPENAI
        val officialOpenAi = openAi && settings.baseUrl.trimEnd('/') == "https://api.openai.com/v1"
        return ProviderCapabilities(
            model = settings.model,
            verifiedAt = if (officialOpenAi) VERIFIED_AT else "",
            sourceUrl = if (officialOpenAi) OPENAI_DOCUMENTATION else "",
            maxImages = if (officialOpenAi) 1500 else 0,
            maxInlineImageBytes = if (officialOpenAi) 512_000_000 else 0,
            maxInputTokens = settings.customInputTokenLimit ?: 0,
            maxOutputTokens = settings.customOutputTokenLimit ?: 0,
            supportsCountTokens = !openAi || (officialOpenAi && settings.dialect == OpenAiDialect.RESPONSES),
            maxRequestBytes = if (officialOpenAi) {
                minOf(settings.maxRequestBytes ?: Long.MAX_VALUE, 512_000_000)
            } else {
                settings.maxRequestBytes
            },
            notes = listOf(
                "Model context and output limits are unverified; configure custom limits to use Max.",
                "A zero capability limit means unknown, not zero capacity.",
                "Third-party OpenAI-compatible endpoints do not inherit OpenAI's limits or supported fields.",
            ),
        )
    }
}
