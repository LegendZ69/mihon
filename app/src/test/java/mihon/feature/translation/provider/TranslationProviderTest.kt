package mihon.feature.translation.provider

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OcrSettings
import tachiyomi.domain.translation.model.OpenAiDialect
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationLogSettings
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.VertexAuthMode
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import javax.crypto.KeyGenerator

class TranslationProviderTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `bound key import sends standard Vertex key header without OAuth or Express routing`() = runBlocking {
        val credential = CredentialParser.forProvider(
            "  bound-key-string  ",
            "Bound key",
            "bound-key",
            TranslationProviderKind.VERTEX_SERVICE_ACCOUNT,
            VertexAuthMode.SERVICE_ACCOUNT_BOUND_KEY,
        )
        val requests = mutableListOf<okhttp3.Request>()
        val provider = TranslationProviderGateway(
            { credential },
            mockk<TranslationRepository>(relaxed = true),
            TranslationDiagnosticsStore(File(directory, "bound-captures")),
            client {
                requests += it
                200 to vertexResponse()
            },
            File(directory, "bound-temporary"),
        )
        val result = provider.translate(
            request().copy(
                settings = settings().copy(
                    provider = ProviderSettings(
                        projectId = "test-project",
                        credentialId = "bound-key",
                        vertexAuthMode = VertexAuthMode.SERVICE_ACCOUNT_BOUND_KEY,
                    ),
                ),
            ),
        )
        assertEquals("Hello", result.pages.single().regions.single().translatedText)
        assertEquals(1, requests.size, "A bound key must not call the OAuth token endpoint")
        assertEquals(
            "https://aiplatform.googleapis.com/v1/projects/test-project/locations/global/" +
                "publishers/google/models/gemini-3.8-flash:generateContent",
            requests.single().url.toString(),
        )
        assertEquals("bound-key-string", requests.single().header("x-goog-api-key"))
        assertNull(requests.single().header("Authorization"))
        assertEquals("shared", requests.single().header("X-Vertex-AI-LLM-Request-Type"))
        assertNull(requests.single().header("X-Vertex-AI-LLM-Shared-Request-Type"))
    }

    @Test
    fun `default content policy excludes classified sound effects and preserves narrative text`() = runBlocking {
        val template = pagePayload().getValue("pages").jsonArray.single().jsonObject
        val region = template.getValue("regions").jsonArray.single().jsonObject
        val mixedRegions = listOf(
            Triple("warning-sign", "sign", "Keep the door closed"),
            Triple("spoken-onomatopoeia", "dialogue", "He shouted bang!"),
            Triple("door-sound", "sound_effect", "THUD"),
        ).mapIndexed { index, (id, type, translation) ->
            JsonObject(
                region + mapOf(
                    "id" to JsonPrimitive(id),
                    "type" to JsonPrimitive(type),
                    "translatedText" to JsonPrimitive(translation),
                    "readingOrder" to JsonPrimitive(index),
                ),
            )
        }
        val payload = buildJsonObject {
            put("pages", JsonArray(listOf(JsonObject(template + ("regions" to JsonArray(mixedRegions))))))
        }
        val translated = gateway(client { 200 to vertexResponse(payload) }).translate(
            request().copy(settings = TranslationSettings(provider = settings().provider)),
        ).pages.single().regions

        assertEquals(listOf("warning-sign", "spoken-onomatopoeia", "door-sound"), translated.map { it.id })
        assertTrue(translated[0].included, "Signs remain meaningful source content")
        assertTrue(translated[1].included, "Spoken onomatopoeia remains dialogue")
        assertFalse(translated[2].included, "The default must exclude explicitly classified sound effects")
        assertTrue(translated[2].ignoredReason?.isNotBlank() == true)
        assertEquals(listOf("Keep the door closed", "He shouted bang!"), translated.take(2).map { it.translatedText })
    }

    @Test
    fun `content policy instructions distinguish sound effects from spoken narrative text`() {
        val providers = listOf(
            settings().provider,
            settings().provider.copy(kind = TranslationProviderKind.OPENAI, dialect = OpenAiDialect.CHAT_COMPLETIONS),
            settings().provider.copy(kind = TranslationProviderKind.OPENAI, dialect = OpenAiDialect.RESPONSES),
        )
        for (provider in providers) {
            for (pipeline in listOf(OcrPipeline.AI, OcrPipeline.PADDLE)) {
                for (ignore in listOf(true, false)) {
                    val configured = request().copy(
                        settings = TranslationSettings(
                            provider = provider,
                            ocr = OcrSettings(pipeline = pipeline),
                            contentPolicy = TranslationContentPolicy(ignoreSoundEffects = ignore),
                        ),
                    )
                    val body = TranslationWireFormat.json.parseToJsonElement(
                        Buffer().also { TranslationWireFormat.requestBody(configured).writeTo(it) }.readUtf8(),
                    ).jsonObject
                    val instruction = when {
                        provider.kind != TranslationProviderKind.OPENAI -> body.getValue("systemInstruction")
                            .jsonObject.getValue("parts").jsonArray.single().jsonObject.getValue("text")
                        provider.dialect == OpenAiDialect.RESPONSES -> body.getValue("instructions")
                        else -> body.getValue("messages").jsonArray.first().jsonObject.getValue("content")
                    }.jsonPrimitive.content
                    assertTrue(instruction.contains("sound_effect"), "Provider output needs a canonical SFX type")
                    assertTrue(instruction.contains("spoken onomatopoeia"))
                    assertTrue(instruction.contains("dialogue, narration, names, meaningful signs and titles"))
                    if (ignore) {
                        assertTrue(instruction.contains("For sound_effect regions, set included=false"))
                        assertTrue(instruction.contains("leave translatedText empty"))
                    } else {
                        assertTrue(instruction.contains("Preserve and translate interpretable sound effects"))
                        assertFalse(instruction.contains("For sound_effect regions, set included=false"))
                    }
                }
            }
        }
    }

    @Test
    fun `empty excluded SFX preserves Paddle OCR identities and measured data`() = runBlocking {
        val original = listOf("sign", "sound_effect", "dialogue", "unknown").mapIndexed { index, kind ->
            TextRegion(
                "region-$index",
                listOf(
                    TranslationPoint(1f, 2f),
                    TranslationPoint(8f, 1f),
                    TranslationPoint(9f, 7f),
                    TranslationPoint(2f, 8f),
                ),
                "raw-$index",
                type = kind,
                readingOrder = index,
                detectionConfidence = 0.91f,
                recognitionConfidence = 0.83f,
            )
        }
        val rawOcr = OcrPageResult("page-1", original)
        val requested = request().copy(
            settings = TranslationSettings(
                provider = settings().provider,
                ocr = OcrSettings(pipeline = OcrPipeline.PADDLE),
            ),
            ocr = listOf(rawOcr),
        )
        val template = pagePayload().getValue("pages").jsonArray.single().jsonObject
        val region = template.getValue("regions").jsonArray.single().jsonObject
        val values = original.map { source ->
            JsonObject(
                region + mapOf(
                    "id" to JsonPrimitive(source.id),
                    "sourceText" to JsonPrimitive(source.sourceText),
                    "type" to JsonPrimitive(source.type),
                    "readingOrder" to JsonPrimitive(source.readingOrder),
                    "translatedText" to JsonPrimitive(if (source.type == "sound_effect") "" else "Meaningful text"),
                ),
            )
        }
        val payload = buildJsonObject {
            put("pages", JsonArray(listOf(JsonObject(template + ("regions" to JsonArray(values))))))
        }
        var sent = ""
        val page = gateway(
            client {
                sent = Buffer().also { sink -> it.body!!.writeTo(sink) }.readUtf8()
                200 to vertexResponse(payload)
            },
        ).translate(requested).pages.single()

        assertFalse(sent.contains("inlineData"))
        assertFalse(sent.contains("base64,"))
        assertEquals(original.map { it.id }, page.regions.map { it.id })
        assertEquals(rawOcr, page.rawOcr)
        page.regions.zip(original).forEach { (actual, expected) ->
            assertEquals(expected.sourceText, actual.sourceText)
            assertEquals(expected.points, actual.points)
            assertEquals(expected.detectionConfidence, actual.detectionConfidence)
            assertEquals(expected.recognitionConfidence, actual.recognitionConfidence)
        }
        assertEquals(listOf(true, false, true, true), page.regions.map { it.included })
        assertEquals("", page.regions[1].translatedText)
    }

    @Test
    fun `content policy opt out preserves SFX and never guesses unknown types`() {
        val template = pagePayload().getValue("pages").jsonArray.single().jsonObject
        val region = template.getValue("regions").jsonArray.single().jsonObject
        for (ignore in listOf(true, false)) {
            val kinds = listOf("SFX", "sound-effects", "unknown", "narration", "name", "sign", "title", "dialogue")
            val values = kinds.mapIndexed { index, kind ->
                JsonObject(
                    region + mapOf(
                        "id" to JsonPrimitive("region-$index"),
                        "type" to JsonPrimitive(kind),
                        "readingOrder" to JsonPrimitive(index),
                        "sourceText" to JsonPrimitive("BOOM"),
                    ),
                )
            }
            val payload = buildJsonObject {
                put("pages", JsonArray(listOf(JsonObject(template + ("regions" to JsonArray(values))))))
            }
            val regions = TranslationWireFormat.decodePages(
                payload,
                request().copy(settings = settings().copy(contentPolicy = TranslationContentPolicy(ignore))),
            ).single().regions
            assertEquals(kinds, regions.map { it.type })
            assertEquals(listOf(!ignore, !ignore, true, true, true, true, true, true), regions.map { it.included })
            assertTrue(regions.all { it.sourceText == "BOOM" && it.translatedText == "Hello" })
        }
    }

    @Test
    fun `content policy retains known SFX classification when provider returns unknown`() = runBlocking {
        for (knownType in listOf("unknown", "sound_effect")) {
            val original = TextRegion(
                "region-1",
                listOf(
                    TranslationPoint(40f, 10f),
                    TranslationPoint(120f, 10f),
                    TranslationPoint(120f, 50f),
                    TranslationPoint(40f, 50f),
                ),
                "こんにちは",
                type = knownType,
            )
            val ocr = OcrPageResult("page-1", listOf(original))
            val page = pagePayload().getValue("pages").jsonArray.single().jsonObject
            val proposed = JsonObject(
                page.getValue("regions").jsonArray.single().jsonObject + mapOf(
                    "type" to JsonPrimitive("unknown"),
                    "translatedText" to JsonPrimitive(if (knownType == "sound_effect") "" else "Hello"),
                ),
            )
            val response = buildJsonObject {
                put("pages", JsonArray(listOf(JsonObject(page + ("regions" to JsonArray(listOf(proposed)))))))
            }
            val translated = gateway(client { 200 to vertexResponse(response) }).translate(
                request().copy(
                    settings = settings().copy(ocr = OcrSettings(pipeline = OcrPipeline.PADDLE)),
                    ocr = listOf(ocr),
                ),
            ).pages.single()
            val region = translated.regions.single()
            assertEquals(knownType, region.type, "Ignoring known SFX must preserve its classification")
            assertEquals(knownType != "sound_effect", region.included)
            assertEquals(original.id, region.id)
            assertEquals(ocr, translated.rawOcr)
        }
    }

    @Test
    fun `local fixture transport requires benchmark flag and synthetic credentials`() {
        val settings = ProviderSettings(
            kind = TranslationProviderKind.OPENAI,
            model = "mihon-fixture",
            baseUrl = "http://127.0.0.1:8765/v1",
            dialect = OpenAiDialect.CHAT_COMPLETIONS,
            extraHeaders = mapOf("X-Mihon-Fixture-Scenario" to "partial-once"),
        )
        val normal = gateway(OkHttpClient())
        assertThrows(TranslationException::class.java) {
            normal.endpoint(settings, null, "generateContent", "mihon-fixture-only")
        }
        val fixture = TranslationProviderGateway(
            { error("Endpoint validation must not load credentials") },
            mockk<TranslationRepository>(relaxed = true),
            TranslationDiagnosticsStore(File(directory, "fixture-captures")),
            OkHttpClient(),
            File(directory, "fixture-temporary"),
            localFixturesEnabled = true,
        )
        assertEquals(
            "http://127.0.0.1:8765/v1/chat/completions",
            fixture.endpoint(settings, null, "generateContent", "mihon-fixture-only").toString(),
        )
        assertThrows(TranslationException::class.java) {
            fixture.endpoint(settings, null, "generateContent", "real-provider-key")
        }
        for (unsafe in listOf(
            settings.copy(baseUrl = "http://example.com/v1"),
            settings.copy(baseUrl = "http://localhost/v1"),
            settings.copy(baseUrl = "http://127.0.0.1:8765/v1?key=secret"),
            settings.copy(baseUrl = "http://name:secret@127.0.0.1:8765/v1"),
            settings.copy(model = "real-model"),
            settings.copy(organization = "personal-organization"),
            settings.copy(openAiProject = "personal-project"),
            settings.copy(extraHeaders = mapOf("X-Secret" to "private-key")),
        )) {
            assertThrows(TranslationException::class.java) {
                fixture.endpoint(unsafe, null, "generateContent", "mihon-fixture-only")
            }
        }
    }

    @Test
    fun `streamed JSON length includes UTF8 and base64 padding`() {
        for (size in listOf(1, 2, 3, 32_769, 65_537)) {
            val bytes = ByteArray(size) { (it % 255).toByte() }
            val image = File(directory, "image-$size").apply { writeBytes(bytes) }
            val body = StreamingJsonBody(
                buildJsonObject {
                    put("text", "日本語 \"quoted\"")
                    put("image", "image-marker")
                },
                mapOf("image-marker" to WireImage(image, "data:image/png;base64,")),
            )
            val buffer = Buffer()
            body.writeTo(buffer)
            assertEquals(body.contentLength(), buffer.size)
            val value = TranslationWireFormat.json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val encoded = value.getValue("image").jsonPrimitive.content.substringAfter("base64,")
            assertTrue(bytes.contentEquals(Base64.getDecoder().decode(encoded)))
        }
    }

    @Test
    fun `credential storage authenticates ciphertext and excludes plaintext`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val storage = EncryptedCredentialStorage(directory) { key }
        val credential = CredentialParser.apiKey("personal-secret-key", "Test", "default")
        storage.save(credential)
        assertEquals("personal-secret-key", storage.load("default").apiKey)
        assertEquals("Test", storage.list().single().label)
        val file = File(directory, "default.credential")
        assertFalse(file.readText().contains("personal-secret-key"))
        val bytes = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        assertThrows(Exception::class.java) { storage.load("default") }
        assertThrows(IllegalArgumentException::class.java) {
            storage.save(CredentialParser.apiKey("key", "Unsafe", "../escape"))
        }
        storage.remove("default")
        assertFalse(file.exists())
    }

    @Test
    fun `service account JWT is signed with RSA and correct OAuth claims`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(
            pair.private.encoded,
        )}\n-----END PRIVATE KEY-----\n"
        val document = buildJsonObject {
            put("type", "service_account")
            put("project_id", "valid-project")
            put("client_email", "translator@valid-project.iam.gserviceaccount.com")
            put("private_key", pem)
            put("private_key_id", "key-id")
            put("token_uri", "https://oauth2.googleapis.com/token")
        }
        val credential = CredentialParser.serviceAccount(document.toString(), "Test")
        val jwt = GoogleServiceAccountTokens(OkHttpClient()).assertion(credential, 1_800_000_000)
        val parts = jwt.split('.')
        val claims = TranslationWireFormat.json.parseToJsonElement(
            String(Base64.getUrlDecoder().decode(parts[1])),
        ).jsonObject
        assertEquals(1_800_003_600L, claims.getValue("exp").jsonPrimitive.long)
        assertEquals("https://oauth2.googleapis.com/token", claims.getValue("aud").jsonPrimitive.content)
        assertEquals("https://www.googleapis.com/auth/cloud-platform", claims.getValue("scope").jsonPrimitive.content)
        assertTrue(
            Signature.getInstance("SHA256withRSA").run {
                initVerify(pair.public)
                update("${parts[0]}.${parts[1]}".toByteArray())
                verify(Base64.getUrlDecoder().decode(parts[2]))
            },
        )
        val unsafe = JsonObject(document + ("token_uri" to JsonPrimitive("https://attacker.invalid/token")))
        assertThrows(IllegalArgumentException::class.java) { CredentialParser.serviceAccount(unsafe.toString(), "Bad") }
    }

    @Test
    fun `redaction catches credentials crossing stream chunks and hides unrecognized headers`() {
        val secret = "personal-secret-0123456789"
        val input = "x".repeat(32_760) + secret + "尾" + secret
        val output = ByteArrayOutputStream()
        SecretStreamRedactor.copy(ByteArrayInputStream(input.toByteArray()), output, listOf(secret))
        assertEquals(input.replace(secret, "[redacted]"), output.toString(Charsets.UTF_8.name()))
        val multiline = "private-key\nsecond-line"
        val escaped = JsonPrimitive(multiline).toString()
        val escapedOutput = ByteArrayOutputStream()
        SecretStreamRedactor.copy(ByteArrayInputStream(escaped.toByteArray()), escapedOutput, listOf(multiline))
        assertEquals("\"[redacted]\"", escapedOutput.toString(Charsets.UTF_8.name()))
        val headers = DiagnosticRedactor.headers(
            Headers.Builder().add("Authorization", "Bearer $secret").add("X-Vendor-Key", secret)
                .add("x-request-id", "request-1").build(),
        )
        assertEquals("[redacted]", headers["Authorization"])
        assertEquals("[redacted]", headers["X-Vendor-Key"])
        assertEquals("request-1", headers["x-request-id"])
        assertEquals(
            "[redacted]",
            DiagnosticRedactor.headers(Headers.headersOf("x-request-id", secret), listOf(secret))["x-request-id"],
        )
        assertFalse(DiagnosticRedactor.url("https://example.com/path?key=$secret".toHttpUrl()).contains(secret))
    }

    @Test
    fun `normalized boxes map to original pixel geometry`() {
        val response = TranslationWireFormat.decodePages(pagePayload(), request())
        val region = response.single().regions.single()
        assertEquals(
            listOf(
                TranslationPoint(40f, 10f),
                TranslationPoint(120f, 10f),
                TranslationPoint(120f, 50f),
                TranslationPoint(40f, 50f),
            ),
            region.points,
        )
        assertEquals("Hello", region.translatedText)
        assertEquals(0.8f, region.aiConfidence)
        assertNull(region.recognitionConfidence)
    }

    @Test
    fun `missing duplicate and invalid geometry responses are rejected`() {
        val request = request()
        assertContentFailure {
            TranslationWireFormat.decodePages(buildJsonObject { put("pages", JsonArray(emptyList())) }, request)
        }
        val page = pagePayload().getValue("pages").jsonArray.single()
        assertContentFailure {
            TranslationWireFormat.decodePages(buildJsonObject { put("pages", JsonArray(listOf(page, page))) }, request)
        }
        val invalid = TranslationWireFormat.json.parseToJsonElement(
            pagePayload().toString().replace("[100,200,500,600]", "[500,200,100,600]"),
        ).jsonObject
        assertContentFailure { TranslationWireFormat.decodePages(invalid, request) }
    }

    @Test
    fun `valid pages survive an omitted or invalid page for scheduler recovery`() {
        val first = request()
        val request = first.copy(images = first.images + first.images.single().copy(id = "page-2", index = 1))
        assertEquals(listOf("page-1"), TranslationWireFormat.decodePages(pagePayload(), request).map { it.imageId })
        val valid = pagePayload().getValue("pages").jsonArray.single()
        val invalid = TranslationWireFormat.json.parseToJsonElement(
            valid.toString()
                .replace("page-1", "page-2").replace("[100,200,500,600]", "[900,200,100,600]"),
        )
        val payload = buildJsonObject { put("pages", JsonArray(listOf(valid, invalid))) }
        assertEquals(listOf("page-1"), TranslationWireFormat.decodePages(payload, request).map { it.imageId })
        val duplicate = buildJsonObject { put("pages", JsonArray(listOf(valid, invalid, invalid))) }
        assertEquals(listOf("page-1"), TranslationWireFormat.decodePages(duplicate, request).map { it.imageId })
    }

    @Test
    fun `Paddle text translation preserves OCR polygons and measured scores`() {
        val points =
            listOf(
                TranslationPoint(1f, 2f),
                TranslationPoint(8f, 1f),
                TranslationPoint(9f, 7f),
                TranslationPoint(2f, 8f),
            )
        val original =
            TextRegion("region-1", points, "source OCR", detectionConfidence = 0.91f, recognitionConfidence = 0.83f)
        val request = request().copy(
            settings = settings().copy(ocr = OcrSettings(pipeline = OcrPipeline.PADDLE)),
            ocr = listOf(OcrPageResult("page-1", listOf(original))),
        )
        val body = Buffer().also { TranslationWireFormat.requestBody(request).writeTo(it) }.readUtf8()
        assertFalse(body.contains("inlineData"))
        val region = TranslationWireFormat.decodePages(pagePayload(), request).single().regions.single()
        assertEquals(points, region.points)
        assertEquals("source OCR", region.sourceText)
        assertEquals("こんにちは", region.correctedText)
        assertEquals(0.83f, region.recognitionConfidence)
    }

    @Test
    fun `Express sends selected model shared capacity and sanitized captures`() = runBlocking {
        var observed: okhttp3.Request? = null
        var sent = ""
        val client = client { outgoing ->
            observed = outgoing
            sent = Buffer().also { outgoing.body!!.writeTo(it) }.readUtf8()
            200 to vertexResponse()
        }
        val diagnostics = TranslationDiagnosticsStore(File(directory, "captures"))
        val gateway = gateway(client, diagnostics)
        val translated = gateway.translate(
            request().copy(settings = settings().copy(logs = TranslationLogSettings(captureRaw = true))),
        )
        assertEquals("Hello", translated.pages.single().regions.single().translatedText)
        assertEquals("/v1/publishers/google/models/gemini-3.8-flash:generateContent", observed!!.url.encodedPath)
        assertEquals("private-api-key", observed!!.url.queryParameter("key"))
        assertEquals("shared", observed!!.header("X-Vertex-AI-LLM-Request-Type"))
        assertNull(observed!!.header("X-Vertex-AI-LLM-Shared-Request-Type"))
        assertTrue(sent.contains("inlineData"))
        assertFalse(sent.contains("candidateCount"))
        val capture = diagnostics.list().single()
        assertFalse(capture.requestUrl!!.contains("private-api-key"))
        val captured = File(diagnostics.directoryFor(capture.id), "request.json").readText()
        assertTrue(captured.contains("_omitted"))
        assertTrue(captured.contains("inlineData"))
        assertEquals("sanitized-v1", capture.capturePolicy)
        val zip = ByteArrayOutputStream()
        diagnostics.export(listOf(capture.id), zip)
        assertTrue(zip.size() > 0)
    }

    @Test
    fun `rate limiting retries but authentication fails once`() = runBlocking {
        var calls = 0
        val retrying = gateway(
            client {
                calls++
                if (calls == 1) 429 to "{\"error\":{\"message\":\"busy\"}}" else 200 to vertexResponse()
            },
        )
        assertEquals(1, retrying.translate(request()).pages.size)
        assertEquals(2, calls)
        calls = 0
        val rejected = gateway(
            client {
                calls++
                403 to "{\"error\":{\"message\":\"denied\"}}"
            },
        )
        val error = assertThrows(TranslationException::class.java) { runBlocking { rejected.translate(request()) } }
        assertEquals(TranslationFailureKind.AUTHENTICATION, error.kind)
        assertEquals(1, calls)
    }

    @Test
    fun `service account refreshes rejected OAuth token once and never captures assertions`() = runBlocking {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val credential = CredentialParser.serviceAccount(
            buildJsonObject {
                put("type", "service_account")
                put("project_id", "valid-project")
                put("client_email", "translator@valid-project.iam.gserviceaccount.com")
                put(
                    "private_key",
                    "-----BEGIN PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(
                        pair.private.encoded,
                    )}\n-----END PRIVATE KEY-----\n",
                )
            }.toString(),
            "Service account",
            "default",
        )
        var tokens = 0
        var generations = 0
        val client = client { outgoing ->
            if (outgoing.url.host == "oauth2.googleapis.com") {
                tokens++
                assertEquals("/token", outgoing.url.encodedPath)
                200 to "{\"access_token\":\"oauth-secret-$tokens\",\"expires_in\":3600}"
            } else {
                generations++
                assertEquals(
                    "/v1/projects/valid-project/locations/global/publishers/google/models/" +
                        "gemini-3.8-flash:generateContent",
                    outgoing.url.encodedPath,
                )
                assertEquals("Bearer oauth-secret-$tokens", outgoing.header("Authorization"))
                if (generations == 1) 401 to "{\"error\":{\"message\":\"expired\"}}" else 200 to vertexResponse()
            }
        }
        val diagnostics = TranslationDiagnosticsStore(File(directory, "oauth-captures"))
        val gateway = TranslationProviderGateway(
            {
                credential
            },
            mockk<TranslationRepository>(relaxed = true),
            diagnostics,
            client,
            File(directory, "oauth-temporary"),
        )
        val request = request().copy(
            settings = settings().copy(
                provider = settings().provider.copy(kind = TranslationProviderKind.VERTEX_SERVICE_ACCOUNT),
                logs = TranslationLogSettings(captureRaw = true),
            ),
        )
        gateway.translate(request)
        gateway.translate(request)
        assertEquals(2, tokens)
        assertEquals(3, generations)
        val captures = diagnostics.list()
        assertEquals(3, captures.size)
        assertTrue(captures.none { it.requestUrl.orEmpty().contains("oauth2") })
        captures.forEach { capture ->
            assertEquals("[redacted]", capture.requestHeaders["Authorization"])
            assertFalse(File(diagnostics.directoryFor(capture.id), "metadata.json").readText().contains("oauth-secret"))
        }
    }

    @Test
    fun `Count Tokens uses same image input and user budget preflights before network`() = runBlocking {
        var path = ""
        var calls = 0
        val gateway = gateway(
            client {
                calls++
                path = it.url.encodedPath
                200 to "{\"totalTokens\":1234}"
            },
        )
        assertEquals(1234L, gateway.countTokens(request()))
        assertTrue(path.endsWith(":countTokens"))
        val oversized = request().copy(
            settings = settings().copy(provider = settings().provider.copy(maxRequestBytes = 1)),
        )
        val error = assertThrows(TranslationException::class.java) { runBlocking { gateway.translate(oversized) } }
        assertEquals(TranslationFailureKind.LIMIT, error.kind)
        assertEquals(1, calls)
    }

    @Test
    fun `OpenAI dialects have their own request and response formats`() = runBlocking {
        for (dialect in OpenAiDialect.entries) {
            var wire: JsonObject? = null
            var path = ""
            val gateway = gateway(
                client {
                    path = it.url.encodedPath
                    wire =
                        TranslationWireFormat.json.parseToJsonElement(
                            Buffer().also { sink ->
                                it.body!!.writeTo(sink)
                            }.readUtf8(),
                        ).jsonObject
                    200 to openAiResponse(dialect)
                },
            )
            val provider = settings().provider.copy(
                kind = TranslationProviderKind.OPENAI,
                model = "vision-model",
                dialect = dialect,
                extraHeaders = mapOf("X-App-Version" to "1"),
            )
            val result = gateway.translate(request().copy(settings = settings().copy(provider = provider)))
            assertEquals("Hello", result.pages.single().regions.single().translatedText)
            assertEquals(100L, result.usage.inputTokens)
            if (dialect == OpenAiDialect.RESPONSES) {
                assertEquals("/v1/responses", path)
                assertNotNull(wire!!["input"])
                assertNotNull(wire!!["text"])
                assertNull(wire!!["messages"])
            } else {
                assertEquals("/v1/chat/completions", path)
                assertNotNull(wire!!["messages"])
                assertNotNull(wire!!["response_format"])
                assertNull(wire!!["input"])
            }
            assertEquals(JsonPrimitive(false), wire!!["store"])
        }
    }

    @Test
    fun `OpenAI Responses counts structured image input before generation`() = runBlocking {
        var path = ""
        var body: JsonObject? = null
        val gateway = gateway(
            client {
                path = it.url.encodedPath
                body =
                    TranslationWireFormat.json.parseToJsonElement(
                        Buffer().also { sink ->
                            it.body!!.writeTo(sink)
                        }.readUtf8(),
                    ).jsonObject
                200 to "{\"object\":\"response.input_tokens\",\"input_tokens\":902}"
            },
        )
        val provider = settings().provider.copy(kind = TranslationProviderKind.OPENAI, model = "vision-model")
        assertEquals(902L, gateway.countTokens(request().copy(settings = settings().copy(provider = provider))))
        assertEquals("/v1/responses/input_tokens", path)
        assertNotNull(body!!["input"])
        assertNotNull(body!!["text"])
        assertNull(body!!["store"])
        assertNull(body!!["max_output_tokens"])
    }

    @Test
    fun `raw capture redacts a pasted credential without changing the sent prompt`() = runBlocking {
        var sent = ""
        val diagnostics = TranslationDiagnosticsStore(File(directory, "redacted-captures"))
        val gateway = gateway(
            client {
                sent = Buffer().also { sink -> it.body!!.writeTo(sink) }.readUtf8()
                200 to vertexResponse()
            },
            diagnostics,
        )
        gateway.translate(
            request().copy(
                settings = settings().copy(
                    instructions = "Pasted private-api-key",
                    logs = TranslationLogSettings(captureRaw = true),
                ),
            ),
        )
        assertTrue(sent.contains("private-api-key"))
        val capture = diagnostics.list().single()
        assertFalse(File(diagnostics.directoryFor(capture.id), "request.json").readText().contains("private-api-key"))
    }

    @Test
    fun `capture redaction never changes the parsed translation`() = runBlocking {
        val diagnostics = TranslationDiagnosticsStore(File(directory, "redacted-response"))
        val gateway = gateway(client { 200 to vertexResponse().replace("Hello", "private-api-key") }, diagnostics)
        val result = gateway.translate(
            request().copy(settings = settings().copy(logs = TranslationLogSettings(captureRaw = true))),
        )
        assertEquals("private-api-key", result.pages.single().regions.single().translatedText)
        val capture = diagnostics.list().single()
        assertFalse(File(diagnostics.directoryFor(capture.id), "response.json").readText().contains("private-api-key"))
    }

    @Test
    fun `unavailable diagnostic storage preserves a successful API request`() = runBlocking {
        var calls = 0
        val unavailable = File(directory, "not-a-directory").apply { writeText("occupied") }
        val gateway = gateway(
            client {
                calls++
                200 to vertexResponse()
            },
            TranslationDiagnosticsStore(unavailable),
        )
        val result = gateway.translate(
            request().copy(settings = settings().copy(logs = TranslationLogSettings(captureRaw = true))),
        )
        assertEquals("Hello", result.pages.single().regions.single().translatedText)
        assertEquals(1, calls)
    }

    @Test
    fun `unsupported Gemini settings do not produce a paid API request`() = runBlocking {
        var calls = 0
        val gateway = gateway(
            client {
                calls++
                200 to vertexResponse()
            },
        )
        val invalid = request().copy(
            settings = settings().copy(provider = settings().provider.copy(advancedJson = "{\"candidateCount\":1}")),
        )
        val error = assertThrows(TranslationException::class.java) { runBlocking { gateway.translate(invalid) } }
        assertEquals(TranslationFailureKind.CONFIGURATION, error.kind)
        assertEquals(0, calls)
    }

    @Test
    fun `vision schema probe rejects text-only completions`() = runBlocking {
        var sent = ""
        val valid = buildJsonObject {
            put(
                "candidates",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("finishReason", "STOP")
                            put(
                                "content",
                                buildJsonObject {
                                    put(
                                        "parts",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put(
                                                        "text",
                                                        "{\"pages\":[{\"imageId\":\"vision-probe\"," +
                                                            "\"detectedLanguage\":null,\"regions\":[]}]}",
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }.toString()
        val gateway = gateway(
            client { request ->
                sent = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                200 to
                    valid
            },
        )
        assertTrue(gateway.testConnection(settings().provider).contains("accepted image input"))
        assertTrue(sent.contains("inlineData"))
        assertTrue(sent.contains("responseSchema") || sent.contains("responseJsonSchema"))
        val textOnly = gateway(
            client {
                200 to
                    "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"OK\"}]}}]}"
            },
        )
        assertContentFailure { runBlocking { textOnly.testConnection(settings().provider) } }
        assertFalse(File(directory, "temporary").listFiles().orEmpty().any { it.name.startsWith("vision-probe-") })
    }

    @Test
    fun `HTTP 200 invalid translation still records usage with its exact capture`() = runBlocking {
        val events = mutableListOf<TranslationEvent>()
        val repository = mockk<TranslationRepository>(relaxed = true)
        coEvery { repository.addEvent(capture(events)) } returns Unit
        val diagnostics = TranslationDiagnosticsStore(File(directory, "usage-capture"))
        val gateway =
            TranslationProviderGateway(
                { CredentialParser.apiKey("private-api-key", "Test", "default") },
                repository,
                diagnostics,
                client {
                    200 to
                        "{\"candidates\":[],\"usageMetadata\":{\"promptTokenCount\":123,\"candidatesTokenCount\":45}}"
                },
                File(directory, "temporary"),
            )
        assertContentFailure {
            runBlocking {
                gateway.translate(
                    request().copy(settings = settings().copy(logs = TranslationLogSettings(captureRaw = true))),
                )
            }
        }
        val usage = events.single { it.stage == "USAGE" }
        assertEquals("123", usage.details["inputTokens"])
        assertEquals("45", usage.details["outputTokens"])
        assertEquals(diagnostics.directoryFor(diagnostics.list().single().id).absolutePath, usage.capturePath)
    }

    private fun settings() = TranslationSettings(
        provider = ProviderSettings(
            kind = TranslationProviderKind.VERTEX_EXPRESS,
            initialRetryMillis = 0,
            maxRetryMillis = 0,
        ),
    )

    private fun request(): TranslationRequest {
        val image = File(directory, "page.png").apply { if (!exists()) writeBytes(byteArrayOf(1, 2, 3)) }
        return TranslationRequest(
            "job",
            "batch",
            settings(),
            listOf(TranslationImage("page-1", 0, image.absolutePath, "image/png", 200, 100, "hash", image.length())),
        )
    }

    private fun gateway(
        client: OkHttpClient,
        diagnostics: TranslationDiagnosticsStore =
            TranslationDiagnosticsStore(File(directory, "captures-${System.nanoTime()}")),
    ): TranslationProviderGateway =
        TranslationProviderGateway(
            { CredentialParser.apiKey("private-api-key", "Test", "default") },
            mockk<TranslationRepository>(relaxed = true),
            diagnostics,
            client,
            File(directory, "temporary"),
        )

    private fun client(handler: (okhttp3.Request) -> Pair<Int, String>): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val (status, body) = handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Test")
                .header("x-request-id", "test-request-id").body(body.toResponseBody()).build()
        }.build()

    private fun pagePayload(): JsonObject = TranslationWireFormat.json.parseToJsonElement(
        """
            {"pages":[{"imageId":"page-1","detectedLanguage":"ja","regions":[{"id":"region-1",
            "sourceText":"こんにちは","translatedText":"Hello","box2d":[100,200,500,600],"type":"dialogue",
            "readingOrder":0,"rotation":0,"included":true,"ignoredReason":null,"aiConfidence":0.8}]}]}
        """.trimIndent(),
    ).jsonObject

    private fun vertexResponse(payload: JsonObject = pagePayload()): String = buildJsonObject {
        put(
            "candidates",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("finishReason", "STOP")
                        put(
                            "content",
                            buildJsonObject {
                                put(
                                    "parts",
                                    buildJsonArray {
                                        add(buildJsonObject { put("text", payload.toString()) })
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }.toString()

    private fun openAiResponse(dialect: OpenAiDialect): String = buildJsonObject {
        if (dialect == OpenAiDialect.RESPONSES) {
            put("status", "completed")
            put(
                "output",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "message")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "output_text")
                                            put("text", pagePayload().toString())
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            put(
                "usage",
                buildJsonObject {
                    put("input_tokens", 100)
                    put("output_tokens", 30)
                },
            )
        } else {
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("finish_reason", "stop")
                            put("message", buildJsonObject { put("content", pagePayload().toString()) })
                        },
                    )
                },
            )
            put(
                "usage",
                buildJsonObject {
                    put("prompt_tokens", 100)
                    put("completion_tokens", 30)
                },
            )
        }
    }.toString()

    private fun assertContentFailure(action: () -> Unit) {
        assertEquals(TranslationFailureKind.CONTENT, assertThrows(TranslationException::class.java, action).kind)
    }
}
