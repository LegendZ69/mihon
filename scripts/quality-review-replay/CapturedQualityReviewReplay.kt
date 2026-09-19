@file:JvmName("CapturedQualityReviewReplay")

package mihon.feature.translation.provider

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Buffer
import tachiyomi.domain.translation.model.OpenAiDialect
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.QualityReviewCoverage
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.QualityReviewValidation
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** Offline captured-case protocol replay. Never constructs a client, gateway, credential, or Android context. */
fun main(args: Array<String>) {
    require(args.size == 2 && args[0] in setOf("baseline", "review"))
    val directory = File(args[1]).canonicalFile
    require(directory.isDirectory)
    val json = Json {
        encodeDefaults = true
        prettyPrint = true
    }
    val inputs = directory.listFiles()!!.filter { it.name.endsWith(".input.json") }.sortedBy { it.name }
    require(inputs.size in 1..8)
    fun read(file: File): String {
        require(file.length() in 1..16_777_216)
        return file.readText()
    }
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    for (input in inputs) {
        val descriptor = json.parseToJsonElement(read(input)).jsonObject
        val name = descriptor.getValue("id").jsonPrimitive.content
        require(name.matches(Regex("[a-z-]{1,64}")))
        val image = json.decodeFromJsonElement<TranslationImage>(descriptor.getValue("image"))
        val original = File(image.filePath)
        require(original.length() in 1..7_340_032)
        val bytes = original.readBytes()
        check(sha(bytes) == image.contentHash && bytes.size.toLong() == image.byteSize)
        val settings = TranslationSettings(
            provider = ProviderSettings(
                kind = TranslationProviderKind.OPENAI,
                model = "mihon-fixture",
                credentialId = "unused-offline",
                baseUrl = "https://unused.invalid/v1",
                dialect = OpenAiDialect.CHAT_COMPLETIONS,
                customInputTokenLimit = 1_048_576,
                customOutputTokenLimit = 65_536,
                maxOutputTokens = 8192,
            ),
        )
        val baselineFile = File(directory, "$name.baseline.json")
        if (args[0] == "baseline") {
            val pages = descriptor.getValue("pages").jsonArray
            val tiles = pages.map { page ->
                val id = page.jsonObject.getValue("imageId").jsonPrimitive.content
                val match = checkNotNull(Regex("([^~]+)~(\\d+)_(\\d+)_(\\d+)_(\\d+)").matchEntire(id))
                check(match.groupValues[1] == image.id)
                val (left, top, width, height) = match.groupValues.drop(2).map(String::toInt)
                PreparedImageTile(
                    image,
                    image.copy(id = id, width = width, height = height),
                    UploadRectangle(left, top, left + width, top + height),
                )
            }
            val request = TranslationRequest("offline", "captured", settings, listOf(image))
            val prepared = PreparedTranslationRequest(request, tiles)
            val decoded = TranslationWireFormat.decodePages(buildJsonObject { put("pages", pages) }, prepared.wire)
            val baseline = prepared.merge(decoded).single().copy(revision = 1)
            QualityReviewValidation.validatePage(baseline)
            baselineFile.writeText(json.encodeToString(baseline))
            println("BASELINE $name regions=${baseline.regions.size}")
            continue
        }
        val baseline = json.decodeFromString<TranslationPageResult>(read(baselineFile))
        val request = QualityReviewRequest("offline", name, settings, image, baseline)
        val authoredFile = File(directory, "$name.authored.json")
        val authored = json.parseToJsonElement(read(authoredFile)).jsonObject
        fun envelope(payload: JsonObject) = buildJsonObject {
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("message", buildJsonObject { put("content", payload.toString()) })
                            put("finish_reason", "stop")
                        },
                    )
                },
            )
        }
        val buffer = Buffer()
        QualityReviewWireFormat.requestBody(request).writeTo(buffer)
        require(buffer.size <= 64 * 1024 * 1024)
        val body = json.parseToJsonElement(buffer.readUtf8()).jsonObject
        fun images(body: JsonObject) = body.getValue("messages").jsonArray.last().jsonObject
            .getValue("content").jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" }
        val inline = images(
            body,
        ).single().jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
        check(sha(Base64.getDecoder().decode(inline.substringAfter(','))) == image.contentHash)
        val response = QualityReviewWireFormat.decode(envelope(authored), request, "authored-offline")
        val candidate = QualityReviewValidation.candidate(request, response)
        val originals = baseline.regions.associateBy { it.id }
        check(candidate.regions.map { it.id }.containsAll(originals.keys))
        check(
            candidate.regions.filter { it.id in originals }.all {
                val before = originals.getValue(it.id)
                it.sourceText == before.sourceText && it.detectionConfidence == before.detectionConfidence &&
                    it.recognitionConfidence == before.recognitionConfidence && it.style == before.style
            },
        )
        check(candidate.rawOcr == baseline.rawOcr)
        check(
            candidate.imageHash == baseline.imageHash && candidate.width == image.width &&
                candidate.height == image.height,
        )
        check(candidate != baseline)
        val candidateFile = File(directory, "$name.candidate.json")
        candidateFile.writeText(json.encodeToString(candidate))
        fun rejected(payload: JsonObject, scoped: QualityReviewRequest = request): Boolean = try {
            QualityReviewValidation.candidate(
                scoped,
                QualityReviewWireFormat.decode(envelope(payload), scoped, "negative"),
            )
            false
        } catch (_: TranslationException) {
            true
        }
        fun changedRegions(regions: List<JsonElement>): JsonObject {
            val page = authored.getValue("pages").jsonArray.single().jsonObject
            return JsonObject(
                authored + ("pages" to JsonArray(listOf(JsonObject(page + ("regions" to JsonArray(regions)))))),
            )
        }
        val regions = authored.getValue("pages").jsonArray.single().jsonObject.getValue("regions").jsonArray
        check(
            rejected(
                changedRegions(
                    regions.filterNot {
                        it.jsonObject.getValue("id").jsonPrimitive.content ==
                            baseline.regions.first().id
                    },
                ),
            ),
        )
        val mutated = regions.map { region ->
            if (region.jsonObject.getValue("id").jsonPrimitive.content == baseline.regions.first().id) {
                JsonObject(region.jsonObject + ("sourceText" to JsonPrimitive("MUTATED RAW SOURCE")))
            } else {
                region
            }
        }
        check(rejected(changedRegions(mutated)))
        val textRequest = request.copy(
            settings = settings.copy(
                qualityReview = settings.qualityReview.copy(coverage = QualityReviewCoverage.TEXT_ONLY),
            ),
        )
        val textBuffer = Buffer()
        QualityReviewWireFormat.requestBody(textRequest).writeTo(textBuffer)
        check(images(json.parseToJsonElement(textBuffer.readUtf8()).jsonObject).isEmpty())
        val textOnlyRejected = rejected(authored, textRequest)
        if (name != "panel-order") check(textOnlyRejected)
        val receipt = buildJsonObject {
            put("case", name)
            put("status", "passed")
            put("assessment", "AUTHORED_PROTOCOL_EXPECTATION; no provider, Android renderer, or human meaning claim")
            put("source_image_sha256", image.contentHash)
            put("input_sha256", sha(input.readBytes()))
            put("baseline_sha256", sha(baselineFile.readBytes()))
            put("authored_sha256", sha(authoredFile.readBytes()))
            put("candidate_sha256", sha(candidateFile.readBytes()))
            put("original_full_image_count", 1)
            put("full_image_bytes_unchanged", true)
            put("original_ids_retained", true)
            put("raw_source_scores_style_and_ocr_preserved", true)
            put("source_geometry_valid", true)
            put("omitted_id_rejected", true)
            put("raw_source_mutation_rejected", true)
            put("text_only_image_count", 0)
            put("authored_candidate_rejected_text_only", textOnlyRejected)
            put("before_region_count", baseline.regions.size)
            put("after_region_count", candidate.regions.size)
            put("excluded_parent_count", candidate.regions.count { !it.included })
        }
        File(directory, "$name.receipt.json").writeText(json.encodeToString(receipt))
        println(
            "PASS $name retained=${originals.size} candidate=${candidate.regions.size} textOnlyRejected=$textOnlyRejected",
        )
    }
}
