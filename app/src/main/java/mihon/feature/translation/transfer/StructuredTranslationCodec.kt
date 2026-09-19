package mihon.feature.translation.transfer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import tachiyomi.domain.translation.model.StructuredImportCoordinates
import tachiyomi.domain.translation.model.StructuredImportDisposition
import tachiyomi.domain.translation.model.StructuredImportDocument
import tachiyomi.domain.translation.model.StructuredImportFormat
import tachiyomi.domain.translation.model.StructuredImportIssue
import tachiyomi.domain.translation.model.StructuredImportPage
import tachiyomi.domain.translation.model.StructuredImportPlan
import tachiyomi.domain.translation.model.StructuredImportPlannedPage
import tachiyomi.domain.translation.model.StructuredImportRegion
import tachiyomi.domain.translation.model.StructuredImportSource
import tachiyomi.domain.translation.model.StructuredImportTransform
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.service.QualityReviewValidation
import tachiyomi.domain.translation.service.TranslationGeometry
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Untrusted file boundary. Decoding/planning has no repository, network, OCR or provider dependencies. */
class StructuredTranslationCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(source: InputStream, sourceName: String, checkActive: () -> Unit = {}): StructuredImportDocument {
        val digest = MessageDigest.getInstance("SHA-256")
        val output = ByteArrayOutputStream()
        var count = 0L
        val header = ByteArray(4)
        var headerCount = 0
        var zip = false
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            checkActive()
            val read = source.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            count += read
            digest.update(buffer, 0, read)
            if (headerCount < 4) {
                val copied = minOf(read, 4 - headerCount)
                buffer.copyInto(header, headerCount, 0, copied)
                headerCount += copied
                zip = headerCount == 4 && header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() &&
                    (header[2].toInt() to header[3].toInt()) in setOf(3 to 4, 5 to 6, 7 to 8)
            }
            require(count <= if (zip) TOTAL_LIMIT else RECORD_LIMIT) {
                "Structured file exceeds its bounded size limit"
            }
            if (!zip) output.write(buffer, 0, read)
        }
        checkActive()
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val name = sourceName.substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() }.take(200).ifBlank { "Imported file" }
        val descriptor = StructuredImportSource(name, hash, count, StructuredImportFormat.PROVIDER_PAGES)
        if (zip) {
            return StructuredImportDocument(
                descriptor.copy(format = StructuredImportFormat.MIHON_ZIP),
                emptyList(),
            )
        }
        return try {
            val decodedText = try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                fail("$", "File is not valid UTF-8 JSON")
            }
            validateNesting(decodedText)
            val value = json.parseToJsonElement(decodedText.removePrefix("\uFEFF")) as? JsonObject
                ?: fail("$", "Expected a JSON object")
            val envelope = unwrap(value)
            val pageValues = envelope.payload["pages"] as? JsonArray ?: fail("$.pages", "Expected a pages array")
            requireField(pageValues.size <= MAX_PAGES, "$.pages", "At most $MAX_PAGES pages may be imported per file")
            val pages = pageValues.mapIndexed { index, element ->
                checkActive()
                val path = "$.pages[$index]"
                val key = "$hash:$index"
                try {
                    page(element as? JsonObject ?: fail(path, "Expected a page object"), key, path, envelope.portable)
                } catch (failure: InvalidField) {
                    StructuredImportPage(
                        key,
                        (element as? JsonObject)?.stringOrNull("imageId") ?: "",
                        coordinates = if (envelope.portable) {
                            StructuredImportCoordinates.ORIGINAL_PIXELS
                        } else {
                            StructuredImportCoordinates.NORMALIZED_1000
                        },
                        regions = emptyList(),
                        issues = listOf(failure.issue),
                    )
                }
            }
            val repeated = pages.groupBy { it.imageId }.filterValues { it.size > 1 }.keys
            StructuredImportDocument(
                descriptor.copy(format = envelope.format),
                pages.mapIndexed { index, page ->
                    if (page.imageId in repeated) {
                        page.copy(
                            issues = page.issues + StructuredImportIssue(
                                "$.pages[$index].imageId",
                                "Duplicate page identity",
                            ),
                        )
                    } else {
                        page
                    }
                },
                externalUsageJson = safeUsage(value),
            )
        } catch (failure: InvalidField) {
            StructuredImportDocument(descriptor, emptyList(), listOf(failure.issue))
        } catch (_: kotlinx.serialization.SerializationException) {
            StructuredImportDocument(
                descriptor,
                emptyList(),
                listOf(
                    StructuredImportIssue(
                        "$",
                        "Malformed or truncated JSON",
                    ),
                ),
            )
        } catch (_: IllegalArgumentException) {
            StructuredImportDocument(
                descriptor,
                emptyList(),
                listOf(
                    StructuredImportIssue(
                        "$",
                        "Malformed or unsupported JSON document",
                    ),
                ),
            )
        }
    }

    fun plan(
        document: StructuredImportDocument,
        originals: List<TranslationImage>,
        existingResults: List<TranslationPageResult>,
        mapping: Map<String, String> = emptyMap(),
        replacePageKeys: Set<String> = emptySet(),
    ): StructuredImportPlan {
        val planned = document.pages.map { page ->
            if (page.issues.isNotEmpty() || document.errors.isNotEmpty()) {
                return@map StructuredImportPlannedPage(
                    page.key,
                    null,
                    null,
                    null,
                    StructuredImportDisposition.INVALID,
                    page.issues + document.errors,
                )
            }
            val explicit = mapping[page.key]
            val candidates = if (explicit != null) {
                originals.filter { it.id == explicit }
            } else {
                originals.filter {
                    page.imageHash != null && page.width != null && page.height != null &&
                        it.contentHash == page.imageHash && it.width == page.width && it.height == page.height
                }
            }
            val image = candidates.singleOrNull()
                ?: return@map StructuredImportPlannedPage(
                    page.key,
                    null,
                    null,
                    null,
                    StructuredImportDisposition.UNMAPPED,
                    listOf(
                        StructuredImportIssue(
                            page.key,
                            "Choose the original page explicitly; numeric image IDs are not page numbers",
                        ),
                    ),
                )
            val oldMatches = existingResults.filter { it.imageId == image.id && it.imageHash == image.contentHash }
            val baseline = oldMatches.singleOrNull()
            try {
                requireField(oldMatches.size <= 1, page.key, "Multiple saved results have this original identity")
                requireField(image.width > 0 && image.height > 0, page.key, "Original dimensions are invalid")
                requireField(
                    page.imageHash == null || image.contentHash == page.imageHash,
                    page.key,
                    "Original content hash does not match",
                )
                requireField(page.width == null || page.width == image.width, page.key, "Original width does not match")
                requireField(
                    page.height == null || page.height == image.height,
                    page.key,
                    "Original height does not match",
                )
                validateTransform(page, image)
                val normalized = mutableSetOf<String>()
                val sourceById = baseline?.regions.orEmpty().associateBy { it.id }
                val textOnly = page.regions.any { it.points == null }
                requireField(
                    !textOnly || page.regions.all { it.points == null },
                    page.key,
                    "Do not mix text-only and geometric regions on one page",
                )
                val imported = page.regions.map { region ->
                    val path = "${page.key}.regions[${region.id}]"
                    val before = sourceById[region.id]
                    if (textOnly) {
                        requireField(
                            before != null,
                            path,
                            "Text-only import requires a matching saved region ID and geometry",
                        )
                        before!!.copy(translatedText = region.translatedText)
                    } else {
                        val sourceGeometry = TranslationGeometry.normalizeAndValidate(
                            region.points!!,
                            if (page.coordinates == StructuredImportCoordinates.NORMALIZED_1000) 1000 else image.width,
                            if (page.coordinates == StructuredImportCoordinates.NORMALIZED_1000) 1000 else image.height,
                        )
                        requireField(
                            sourceGeometry.accepted,
                            "$path.points",
                            sourceGeometry.issues.joinToString { it.reason },
                        )
                        if (sourceGeometry.changes.isNotEmpty()) normalized += region.id
                        val points = sourceGeometry.points.map { point -> originalPoint(point, page, image) }
                        val geometry = TranslationGeometry.normalizeAndValidate(points, image.width, image.height)
                        requireField(geometry.accepted, "$path.points", geometry.issues.joinToString { it.reason })
                        if (geometry.changes.isNotEmpty()) normalized += region.id
                        TextRegion(
                            id = region.id, points = geometry.points,
                            sourceText = before?.sourceText ?: region.sourceText
                                ?: fail("$path.sourceText", "Geometric regions require sourceText"),
                            translatedText = region.translatedText,
                            correctedText = before?.correctedText ?: region.correctedText,
                            type = region.type, readingOrder = region.readingOrder, rotation = region.rotation,
                            included = region.included, ignoredReason = region.ignoredReason,
                            aiConfidence = region.aiConfidence,
                            detectionConfidence = before?.detectionConfidence,
                            recognitionConfidence = before?.recognitionConfidence,
                            style = before?.style,
                        )
                    }
                }
                val regions = if (textOnly) {
                    val updates = imported.associateBy { it.id }
                    baseline!!.regions.map { updates[it.id] ?: it }
                } else {
                    imported
                }
                val result = TranslationPageResult(
                    image.id,
                    image.contentHash,
                    image.width,
                    image.height,
                    regions,
                    baseline?.rawOcr,
                    if (textOnly) baseline?.detectedLanguage else page.detectedLanguage,
                    baseline?.revision ?: 1,
                )
                QualityReviewValidation.validatePage(result)
                val identical = baseline == result
                StructuredImportPlannedPage(
                    page.key,
                    image,
                    result,
                    baseline?.revision,
                    when {
                        identical -> StructuredImportDisposition.IDENTICAL
                        baseline != null && page.key !in replacePageKeys -> StructuredImportDisposition.CONFLICT
                        else -> StructuredImportDisposition.READY
                    },
                    normalizedRegions = normalized,
                )
            } catch (failure: InvalidField) {
                StructuredImportPlannedPage(
                    page.key,
                    image,
                    null,
                    baseline?.revision,
                    StructuredImportDisposition.INVALID,
                    listOf(failure.issue),
                )
            } catch (failure: tachiyomi.domain.translation.model.TranslationException) {
                StructuredImportPlannedPage(
                    page.key,
                    image,
                    null,
                    baseline?.revision,
                    StructuredImportDisposition.INVALID,
                    listOf(StructuredImportIssue(page.key, failure.message ?: "Invalid translated page")),
                )
            }
        }
        val duplicateTargets = planned.filter { it.image != null }
            .groupBy { it.image!!.id }.filterValues { it.size > 1 }.keys
        return StructuredImportPlan(
            document,
            planned.map {
                if (it.image?.id in duplicateTargets) {
                    it.copy(
                        result = null,
                        disposition = StructuredImportDisposition.INVALID,
                        issues = it.issues + StructuredImportIssue(
                            it.sourceKey,
                            "Multiple imported pages target the same original; import them separately",
                        ),
                    )
                } else {
                    it
                }
            },
        )
    }

    private fun page(value: JsonObject, key: String, path: String, portable: Boolean): StructuredImportPage {
        val id = value.requiredString(
            "imageId",
            path,
        ).also {
            requireField(
                it.isNotBlank(),
                "$path.imageId",
                "Image ID is blank",
            )
        }
        val isTile = value["isTile"]?.let { element ->
            (element as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: fail("$path.isTile", "Expected a boolean")
        } ?: false
        val transform = value["inputTransform"]?.takeUnless { it == JsonNull }?.let { element ->
            requireField(!portable, "$path.inputTransform", "Portable geometry is already in original-image pixels")
            val t = element as? JsonObject ?: fail("$path.inputTransform", "Expected a recorded transform object")
            val p = "$path.inputTransform"
            requireField(
                t.keys.all {
                    it in setOf(
                        "originalWidth",
                        "originalHeight",
                        "originalImageHash",
                        "left",
                        "top",
                        "cropWidth",
                        "cropHeight",
                        "inputWidth",
                        "inputHeight",
                    )
                },
                p,
                "Unsupported transform fields; only a recorded unrotated crop and scale are supported",
            )
            StructuredImportTransform(
                t.requiredInt("originalWidth", p), t.requiredInt("originalHeight", p),
                t.requiredString("originalImageHash", p), t.requiredInt("left", p), t.requiredInt("top", p),
                t.requiredInt("cropWidth", p), t.requiredInt("cropHeight", p),
                t.requiredInt("inputWidth", p), t.requiredInt("inputHeight", p),
            )
        }
        requireField(
            transform != null || (
                value["tileId"] == null && value["tile"] == null && !isTile
                ),
            "$path.inputTransform",
            "Tile output requires its recorded inputTransform",
        )
        val hash = value.optionalString("imageHash", path) ?: transform?.originalImageHash
        requireField(
            hash == null || hash.matches(Regex("[0-9a-f]{64}")),
            "$path.imageHash",
            "Expected a lowercase SHA-256 hash",
        )
        val width = value.optionalInt("width", path) ?: transform?.originalWidth
        val height = value.optionalInt("height", path) ?: transform?.originalHeight
        requireField(width == null || width > 0, "$path.width", "Width must be positive")
        requireField(height == null || height > 0, "$path.height", "Height must be positive")
        if (portable) {
            requireField(
                value["coordinateSpace"] == null || value.stringOrNull("coordinateSpace") == "original_pixels",
                "$path.coordinateSpace",
                "Portable coordinates must be original_pixels",
            )
        } else {
            requireField(
                value["coordinateSpace"] == null || value.stringOrNull("coordinateSpace") == "normalized_1000",
                "$path.coordinateSpace",
                "Provider coordinates must be normalized_1000",
            )
        }
        val values = value["regions"] as? JsonArray ?: fail("$path.regions", "Expected a regions array")
        requireField(values.size <= MAX_REGIONS, "$path.regions", "Too many regions")
        val ids = mutableSetOf<String>()
        val regions = values.mapIndexed { index, element ->
            val regionPath = "$path.regions[$index]"
            val r = element as? JsonObject ?: fail(regionPath, "Expected a region object")
            val regionId = r.requiredString("id", regionPath)
            requireField(
                regionId.isNotBlank() && ids.add(regionId),
                "$regionPath.id",
                "Region IDs must be nonblank and unique",
            )
            val points = if (portable) {
                portablePoints(
                    r["points"],
                    "$regionPath.points",
                )
            } else {
                providerPoints(
                    r,
                    regionPath,
                )
            }
            val order = r.optionalInt("readingOrder", regionPath) ?: index
            val rotation = r.optionalFloat("rotation", regionPath) ?: 0f
            val confidence = r.optionalFloat("aiConfidence", regionPath)
            requireField(order >= 0, "$regionPath.readingOrder", "Reading order must be nonnegative")
            requireField(
                rotation in -360f..360f,
                "$regionPath.rotation",
                "Rotation must be a clockwise angle from -360 to 360",
            )
            requireField(
                confidence == null || confidence in 0f..1f,
                "$regionPath.aiConfidence",
                "AI confidence must be within 0–1",
            )
            StructuredImportRegion(
                regionId, r.requiredString("translatedText", regionPath), points,
                r.optionalString("sourceText", regionPath), r.optionalString("correctedText", regionPath),
                r.optionalString("type", regionPath) ?: "dialogue", order, rotation,
                r.optionalBoolean("included", regionPath) ?: true,
                r.optionalString("ignoredReason", regionPath), confidence,
            )
        }
        return StructuredImportPage(
            key, id, hash, width, height,
            if (portable) StructuredImportCoordinates.ORIGINAL_PIXELS else StructuredImportCoordinates.NORMALIZED_1000,
            regions, value.optionalString("detectedLanguage", path), transform,
        )
    }

    private fun portablePoints(value: JsonElement?, path: String): List<TranslationPoint>? {
        if (value == null || value == JsonNull) return null
        val values = value as? JsonArray ?: fail(path, "Expected an array of {x,y} points")
        requireField(values.size <= TranslationGeometry.MAX_INPUT_VERTICES, path, "Too many polygon vertices")
        return values.mapIndexed { index, element ->
            val point = element as? JsonObject ?: fail("$path[$index]", "Expected an {x,y} point")
            TranslationPoint(point.requiredFloat("x", "$path[$index]"), point.requiredFloat("y", "$path[$index]"))
        }
    }

    private fun providerPoints(region: JsonObject, path: String): List<TranslationPoint>? {
        val box = region["box2d"]?.takeUnless { it == JsonNull }
        val rectangle = box?.let {
            val values = it as? JsonArray ?: fail("$path.box2d", "Expected [y_min,x_min,y_max,x_max]")
            requireField(values.size == 4, "$path.box2d", "Expected exactly four bounding coordinates")
            val b = values.mapIndexed { index, item -> normalized(item, "$path.box2d[$index]") }
            requireField(b[0] < b[2] && b[1] < b[3], "$path.box2d", "Bounding rectangle must have positive area")
            listOf(
                TranslationPoint(
                    b[1],
                    b[0],
                ),
                TranslationPoint(
                    b[3],
                    b[0],
                ),
                TranslationPoint(
                    b[3],
                    b[2],
                ),
                TranslationPoint(
                    b[1],
                    b[2],
                ),
            )
        }
        val polygon = region["polygon"]?.takeUnless { it == JsonNull } ?: return rectangle
        val points = polygon as? JsonArray ?: fail("$path.polygon", "Expected normalized [x,y] vertices")
        requireField(
            points.size <= TranslationGeometry.MAX_INPUT_VERTICES,
            "$path.polygon",
            "Too many polygon vertices",
        )
        return points.mapIndexed { index, item ->
            val pair = item as? JsonArray ?: fail("$path.polygon[$index]", "Expected an [x,y] pair")
            requireField(pair.size == 2, "$path.polygon[$index]", "Expected exactly two coordinates [x,y]")
            TranslationPoint(
                normalized(
                    pair[0],
                    "$path.polygon[$index][0]",
                ),
                normalized(
                    pair[1],
                    "$path.polygon[$index][1]",
                ),
            )
        }
    }

    private fun originalPoint(
        point: TranslationPoint,
        page: StructuredImportPage,
        image: TranslationImage,
    ): TranslationPoint {
        if (page.coordinates == StructuredImportCoordinates.ORIGINAL_PIXELS) return point
        val t = page.transform
        return if (t == null) {
            TranslationPoint(point.x * image.width / 1000f, point.y * image.height / 1000f)
        } else {
            TranslationPoint(t.left + point.x * t.cropWidth / 1000f, t.top + point.y * t.cropHeight / 1000f)
        }
    }

    private fun validateTransform(page: StructuredImportPage, image: TranslationImage) {
        val t = page.transform ?: return
        requireField(
            t.originalImageHash == image.contentHash && t.originalWidth == image.width &&
                t.originalHeight == image.height,
            "${page.key}.inputTransform",
            "Recorded transform belongs to a different original",
        )
        requireField(
            t.inputWidth > 0 && t.inputHeight > 0 && t.cropWidth > 0 && t.cropHeight > 0 && t.left >= 0 && t.top >= 0 &&
                t.left.toLong() + t.cropWidth <= image.width && t.top.toLong() + t.cropHeight <= image.height,
            "${page.key}.inputTransform",
            "Recorded crop or input dimensions are invalid",
        )
    }

    private data class Envelope(
        val payload: JsonObject,
        val format: StructuredImportFormat,
        val portable: Boolean = false,
    )

    private fun unwrap(value: JsonObject): Envelope {
        if ("format" in value || "version" in value) {
            requireField(
                value.stringOrNull("format") == "mihon-structured-translations",
                "$.format",
                "Unsupported portable format; use mihon-structured-translations",
            )
            requireField(value["version"] == JsonPrimitive(1), "$.version", "Supported portable version is 1")
            return Envelope(value, StructuredImportFormat.PORTABLE_V1, true)
        }
        if ("pages" in value) return Envelope(value, StructuredImportFormat.PROVIDER_PAGES)
        fun payload(text: String, path: String): JsonObject = try {
            validateNesting(text)
            json.parseToJsonElement(text) as? JsonObject ?: fail(path, "Output must contain a Mihon pages object")
        } catch (_: kotlinx.serialization.SerializationException) {
            fail(path, "Provider output is malformed or truncated JSON")
        }
        val candidates = value["candidates"] as? JsonArray
        if (candidates != null) {
            requireField(candidates.size == 1, "$.candidates", "Select exactly one provider candidate")
            val candidate = candidates.single() as? JsonObject ?: fail("$.candidates[0]", "Invalid candidate")
            val finish = candidate.stringOrNull("finishReason")
            requireField(
                finish == null || finish == "STOP",
                "$.candidates[0].finishReason",
                "Provider output did not complete successfully",
            )
            val content = candidate["content"] as? JsonObject ?: fail(
                "$.candidates[0].content",
                "Missing output content",
            )
            val parts = content["parts"] as? JsonArray ?: fail("$.candidates[0].content.parts", "Missing output parts")
            parts.forEachIndexed { index, part ->
                val objectPart = part as? JsonObject ?: fail(
                    "$.candidates[0].content.parts[$index]",
                    "Expected a part object",
                )
                objectPart.optionalBoolean("thought", "$.candidates[0].content.parts[$index]")
            }
            val text = parts.mapNotNull { it as? JsonObject }.filter { it["thought"] != JsonPrimitive(true) }
                .mapNotNull { it.stringOrNull("text") }.joinToString("")
            return Envelope(payload(text, "$.candidates[0].content.parts"), StructuredImportFormat.GEMINI)
        }
        if ("output" in value || "output_text" in value) {
            requireField(
                value.stringOrNull("status") in setOf(
                    null,
                    "completed",
                ),
                "$.status",
                "Responses output did not complete successfully",
            )
            val output = value["output"] as? JsonArray
            val messages = output.orEmpty().mapNotNull { it as? JsonObject }
                .filter { it.stringOrNull("type") == "message" }
            requireField(messages.size <= 1, "$.output", "Select exactly one response output message")
            val message = messages.singleOrNull()
            requireField(
                message?.stringOrNull("status") in setOf(
                    null,
                    "completed",
                ),
                "$.output.message.status",
                "Output message is incomplete",
            )
            val parts = message?.get("content") as? JsonArray
            val text = if (message != null) {
                parts.orEmpty().mapNotNull { it as? JsonObject }.filter { it.stringOrNull("type") == "output_text" }
                    .mapNotNull { it.stringOrNull("text") }.joinToString("")
            } else {
                value.stringOrNull("output_text") ?: fail("$.output", "Missing output text")
            }
            return Envelope(payload(text, "$.output"), StructuredImportFormat.OPENAI_RESPONSES)
        }
        val choices = value["choices"] as? JsonArray
        if (choices != null) {
            requireField(choices.size == 1, "$.choices", "Select exactly one completion choice")
            val choice = choices.single() as? JsonObject ?: fail("$.choices[0]", "Invalid completion choice")
            requireField(
                choice.stringOrNull("finish_reason") in setOf(
                    null,
                    "stop",
                ),
                "$.choices[0].finish_reason",
                "Chat completion is incomplete",
            )
            val message = choice["message"] as? JsonObject ?: fail("$.choices[0].message", "Missing output message")
            val text = message.stringOrNull("content") ?: fail(
                "$.choices[0].message.content",
                "Expected a string containing Mihon pages JSON",
            )
            return Envelope(payload(text, "$.choices[0].message.content"), StructuredImportFormat.CHAT_COMPLETIONS)
        }
        fail("$", "Unsupported schema: expected a portable document, pages object or supported provider response")
    }

    private fun validateNesting(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { char ->
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    '{', '[' -> {
                        depth++
                        requireField(depth <= 64, "$", "JSON nesting exceeds the supported depth of 64")
                    }
                    '}', ']' -> depth--
                }
            }
        }
    }

    private fun safeUsage(value: JsonObject): String? {
        val usage = (value["usageMetadata"] ?: value["usage"]) as? JsonObject ?: return null
        // Do not retain arbitrary provider objects, secrets, signatures or image bodies as provenance.
        val safe = usage.filter { (key, number) ->
            key in setOf(
                "promptTokenCount",
                "candidatesTokenCount",
                "totalTokenCount",
                "cachedContentTokenCount",
                "thoughtsTokenCount",
                "input_tokens",
                "output_tokens",
                "total_tokens",
                "prompt_tokens",
                "completion_tokens",
            ) &&
                number is JsonPrimitive && !number.isString && number.content.toLongOrNull()?.let { it >= 0 } == true
        }
        return JsonObject(safe).takeIf { it.isNotEmpty() }?.toString()
    }

    private fun normalized(value: JsonElement, path: String): Float {
        val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.floatOrNull
        requireField(
            number != null && number.isFinite() && number in 0f..1000f,
            path,
            "Expected a finite normalized coordinate within 0–1000",
        )
        return number!!
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.requiredString(
        key: String,
        path: String,
    ): String = stringOrNull(key) ?: fail(
        "$path.$key",
        "Expected a string",
    )
    private fun JsonObject.optionalString(key: String, path: String): String? =
        if (get(key) == null || get(key) == JsonNull) null else requiredString(key, path)
    private fun JsonObject.requiredInt(key: String, path: String): Int =
        (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: fail("$path.$key", "Expected an integer")
    private fun JsonObject.optionalInt(key: String, path: String): Int? =
        if (get(key) == null || get(key) == JsonNull) null else requiredInt(key, path)
    private fun JsonObject.requiredFloat(key: String, path: String): Float {
        val value = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.floatOrNull
        requireField(value != null && value.isFinite(), "$path.$key", "Expected a finite numeric coordinate or value")
        return value!!
    }
    private fun JsonObject.optionalFloat(key: String, path: String): Float? =
        if (get(key) == null || get(key) == JsonNull) null else requiredFloat(key, path)
    private fun JsonObject.optionalBoolean(key: String, path: String): Boolean? =
        if (get(key) == null || get(key) == JsonNull) {
            null
        } else {
            (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: fail("$path.$key", "Expected a boolean")
        }
    private fun requireField(condition: Boolean, path: String, message: String) {
        if (!condition) fail(path, message)
    }
    private fun fail(path: String, message: String): Nothing = throw InvalidField(StructuredImportIssue(path, message))
    private class InvalidField(val issue: StructuredImportIssue) : IllegalArgumentException(issue.message)

    companion object {
        const val RECORD_LIMIT = 16L * 1024 * 1024
        const val TOTAL_LIMIT = 512L * 1024 * 1024
        const val MAX_PAGES = 10000
        const val MAX_REGIONS = 10000
    }
}
