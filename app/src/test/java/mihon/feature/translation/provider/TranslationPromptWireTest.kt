package mihon.feature.translation.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OcrSettings
import tachiyomi.domain.translation.model.OpenAiDialect
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationPromptPair
import tachiyomi.domain.translation.model.TranslationPromptStage
import tachiyomi.domain.translation.model.TranslationPrompts
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationSettings

class TranslationPromptWireTest {
    @Test
    fun `empty custom roles omit empty provider text parts while source inputs remain present`() {
        val base = request()
        for (provider in listOf(
            ProviderSettings(),
            ProviderSettings(kind = TranslationProviderKind.OPENAI, dialect = OpenAiDialect.RESPONSES),
            ProviderSettings(kind = TranslationProviderKind.OPENAI, dialect = OpenAiDialect.CHAT_COMPLETIONS),
        )) {
            val configured = base.copy(
                settings = base.settings.copy(
                    provider = provider,
                    prompts = TranslationPrompts(translation = TranslationPromptPair("", "")),
                ),
            )
            val body = serialized(TranslationWireFormat.requestBody(configured))
            when {
                provider.kind != TranslationProviderKind.OPENAI -> assertFalse(body.containsKey("systemInstruction"))
                provider.dialect == OpenAiDialect.RESPONSES -> assertFalse(body.containsKey("instructions"))
                else -> assertEquals(
                    listOf("user"),
                    body.getValue("messages").jsonArray.map {
                        it.jsonObject.getValue("role").jsonPrimitive.content
                    },
                )
            }
            assertTrue(body.toString().contains("source words"))
            assertFalse(body.toString().contains("\"text\":\"\""))
        }
    }

    @Test
    fun `custom translation roles replace task wording while retaining OCR and response constraints`() {
        val request = request().copy(
            settings = request().settings.copy(
                prompts = TranslationPrompts(
                    translation = TranslationPromptPair("Only my system", "User {{target_language}}"),
                ),
            ),
        )
        val body = TranslationWireFormat.json.parseToJsonElement(
            Buffer().also { TranslationWireFormat.requestBody(request).writeTo(it) }.readUtf8(),
        ).jsonObject
        assertEquals(
            "Only my system",
            body.getValue("systemInstruction").jsonObject.getValue("parts")
                .jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
        )
        val user = body.getValue("contents").jsonArray.single().jsonObject.getValue("parts").jsonArray.last()
            .jsonObject.getValue("text").jsonPrimitive.content
        assertTrue(user.startsWith("User en"))
        assertTrue(user.contains("source words"))
        assertFalse(user.contains("Translate all requested pages"))
        assertFalse(body.toString().contains("inlineData"))
        assertTrue(body.getValue("generationConfig").jsonObject.containsKey("responseJsonSchema"))
    }

    @Test
    fun `all text-only dialects preserve full replacement and count input parity`() {
        val providers = listOf(
            ProviderSettings(),
            ProviderSettings(kind = TranslationProviderKind.VERTEX_EXPRESS),
            ProviderSettings(kind = TranslationProviderKind.OPENAI, dialect = OpenAiDialect.RESPONSES),
            ProviderSettings(kind = TranslationProviderKind.OPENAI, dialect = OpenAiDialect.CHAT_COMPLETIONS),
            ProviderSettings(
                kind = TranslationProviderKind.OPENAI,
                baseUrl = "https://api.groq.com/openai/v1",
                model = "openai/gpt-oss-120b",
                dialect = OpenAiDialect.RESPONSES,
            ),
            ProviderSettings(
                kind = TranslationProviderKind.OPENAI,
                baseUrl = "https://api.groq.com/openai/v1",
                model = "openai/gpt-oss-120b",
                dialect = OpenAiDialect.CHAT_COMPLETIONS,
            ),
        )
        providers.forEach { provider ->
            val base = request()
            val custom = base.copy(
                settings = base.settings.copy(
                    provider = provider,
                    prompts = TranslationPrompts(
                        translation = TranslationPromptPair("SYSTEM ONLY", "USER ONLY"),
                        qualityReview = TranslationPromptPair("REVIEW ONLY", "REVIEW USER"),
                    ),
                ),
            )
            val translated = TranslationWireFormat.requestBody(custom)
            assertEquals("SYSTEM ONLY", translated.promptDiagnostics!!.system)
            assertTrue(translated.promptDiagnostics!!.user.startsWith("USER ONLY"))
            assertEquals(
                translated.promptDiagnostics,
                TranslationWireFormat.requestBody(custom, true).promptDiagnostics,
            )
            val translationBody = serialized(translated)
            assertFalse(translationBody.toString().contains("inlineData"))
            assertFalse(translationBody.toString().contains("image_url"))
            val page = TranslationPageResult("page", "a".repeat(64), 100, 200, custom.ocr.single().regions)
            val review = QualityReviewRequest("job", "review", custom.settings, custom.images.single(), page)
            val reviewed = QualityReviewWireFormat.requestBody(review)
            assertEquals("REVIEW ONLY", reviewed.promptDiagnostics!!.system)
            assertTrue(reviewed.promptDiagnostics!!.user.startsWith("REVIEW USER"))
            assertTrue(reviewed.promptDiagnostics!!.user.contains("Existing translation"))
            assertFalse(reviewed.promptDiagnostics!!.user.contains("Translate all requested pages"))
            assertFalse(reviewed.promptDiagnostics!!.system.contains("SYSTEM ONLY"))
            assertEquals(
                reviewed.promptDiagnostics,
                QualityReviewWireFormat.requestBody(review, true).promptDiagnostics,
            )
            val body = serialized(reviewed)
            assertFalse(body.toString().contains("inlineData"))
            assertFalse(body.toString().contains("image_url"))
            if (provider.kind == TranslationProviderKind.OPENAI) {
                if (provider.dialect == OpenAiDialect.CHAT_COMPLETIONS) {
                    assertTrue(
                        body.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonPrimitive.isString,
                    )
                } else {
                    assertEquals("REVIEW ONLY", body.getValue("instructions").jsonPrimitive.content)
                }
            }
        }
    }

    @Test
    fun `legacy settings and explicit builtin controls have identical serialized requests`() {
        val base = request()
        val legacy = Json.decodeFromString<TranslationSettings>("""{"ocr":{"pipeline":"PADDLE"}}""")
        assertEquals(
            serialized(TranslationWireFormat.requestBody(base.copy(settings = legacy))),
            serialized(TranslationWireFormat.requestBody(base)),
        )
        val custom = base.copy(
            settings = base.settings.copy(
                prompts = TranslationPrompts(
                    translation = TranslationPromptPair("", ""),
                ),
            ),
        )
        val body = TranslationWireFormat.requestBody(custom)
        assertEquals("", body.promptDiagnostics!!.system)
        assertFalse(body.promptDiagnostics!!.user.contains("Translate all requested pages"))
        assertTrue(body.promptDiagnostics!!.user.contains("source words"))
    }

    @Test
    fun `diagnostic snapshots redact secrets while retaining the full effective prompt fingerprint`() {
        val snapshot = TranslationPromptDiagnostics(
            TranslationPromptStage.TRANSLATION,
            "Secret token-value",
            "Read data:image/png;base64,AAAA and https://example.com/?key=secret",
        )
        val details = snapshot.details(listOf("token-value"))
        assertFalse(details.getValue("promptSystem").contains("token-value"))
        assertFalse(details.getValue("promptUser").contains("AAAA"))
        assertFalse(details.getValue("promptUser").contains("key=secret"))
        assertEquals(64, details.getValue("promptSha256").length)
        assertEquals(snapshot.sha256, details.getValue("promptSha256"))
        assertFalse(details.containsKey("promptSnapshotIncomplete"))
    }

    private fun serialized(body: StreamingJsonBody): JsonObject = TranslationWireFormat.json.parseToJsonElement(
        Buffer().also(body::writeTo).readUtf8(),
    ).jsonObject

    private fun request(): TranslationRequest {
        val image = TranslationImage("page", 0, "/not-read", "image/png", 100, 200, "a".repeat(64), 0)
        val region = TextRegion(
            "r",
            listOf(
                TranslationPoint(1f, 1f),
                TranslationPoint(20f, 1f),
                TranslationPoint(20f, 20f),
                TranslationPoint(1f, 20f),
            ),
            "source words",
        )
        return TranslationRequest(
            "job",
            "batch",
            TranslationSettings(ocr = OcrSettings(pipeline = OcrPipeline.PADDLE)),
            listOf(image),
            listOf(OcrPageResult(image.id, listOf(region))),
        )
    }
}
