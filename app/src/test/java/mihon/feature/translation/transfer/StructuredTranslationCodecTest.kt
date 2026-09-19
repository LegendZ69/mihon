package mihon.feature.translation.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.StructuredImportDisposition
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPoint

class StructuredTranslationCodecTest {
    private val codec = StructuredTranslationCodec()
    private val original = TranslationImage(
        "actual-page",
        7,
        "/private/original",
        "image/png",
        100,
        200,
        "a".repeat(64),
        123,
    )

    @Test
    fun `portable original pixels match content identity and preserve a meaningful sign`() {
        val document = codec.decode(
            """{"format":"mihon-structured-translations","version":1,"pages":[{
              "imageId":"foreign-page-8","imageHash":"${original.contentHash}","width":100,"height":200,
              "regions":[{"id":"sign","sourceText":"出口","translatedText":"Exit","type":"sign",
              "points":[{"x":10,"y":20},{"x":50,"y":20},{"x":50,"y":60},{"x":10,"y":60}]}]}]}"""
                .byteInputStream(),
            "translated.json",
        )
        val page = codec.plan(document, listOf(original), emptyList()).pages.single()
        assertEquals(StructuredImportDisposition.READY, page.disposition)
        assertEquals("actual-page", page.result!!.imageId)
        assertEquals("Exit", page.result!!.regions.single().translatedText)
        assertEquals(TranslationPoint(10f, 20f), page.result!!.regions.single().points.first())
        assertTrue(document.errors.isEmpty())
    }

    @Test
    fun `provider envelopes ignore thought parts and agree on normalized geometry`() {
        val payload = providerPage()
        val quoted = kotlinx.serialization.json.JsonPrimitive(payload).toString()
        val envelopes = listOf(
            payload,
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[
                {"thought":true,"text":"not JSON"},{"text":$quoted}]}}],
                "usageMetadata":{"promptTokenCount":12,"thoughtSignature":"secret","inlineData":"secret"}}""",
            """{"status":"completed","output":[{"type":"reasoning","summary":[{"text":"not JSON"}]},
                {"type":"message","status":"completed","content":[{"type":"output_text","text":$quoted}]}]}""",
            """{"status":"completed","output_text":$quoted}""",
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":$quoted}}]}""",
        )
        envelopes.forEach { envelope ->
            val document = codec.decode(envelope.byteInputStream(), "response.json")
            assertTrue(document.errors.isEmpty(), document.errors.toString())
            val plan = codec.plan(
                document,
                listOf(original),
                emptyList(),
                mapOf(document.pages.single().key to original.id),
            )
            val region = plan.readyPages.single().result!!.regions.single()
            assertEquals(
                listOf(
                    TranslationPoint(
                        10f,
                        20f,
                    ),
                    TranslationPoint(
                        50f,
                        20f,
                    ),
                    TranslationPoint(
                        50f,
                        60f,
                    ),
                    TranslationPoint(
                        10f,
                        60f,
                    ),
                ),
                region.points,
            )
            assertEquals("THUD", region.translatedText)
            assertEquals("sound_effect", region.type)
            assertTrue(region.included, "Presentation policy must not rewrite imported classifications or text")
        }
        val document = codec.decode(envelopes[1].byteInputStream(), "gemini.json")
        assertEquals("""{"promptTokenCount":12}""", document.externalUsageJson)
    }

    @Test
    fun `numeric provider IDs require an explicit page selection even when index exists`() {
        val document = codec.decode(providerPage().byteInputStream(), "response.json")
        val pending = codec.plan(document, listOf(original), emptyList()).pages.single()
        assertEquals(StructuredImportDisposition.UNMAPPED, pending.disposition)
        val matched = codec.plan(
            document,
            listOf(original),
            emptyList(),
            mapOf(document.pages.single().key to original.id),
        )
        assertEquals(StructuredImportDisposition.READY, matched.pages.single().disposition)
    }

    @Test
    fun `text-only imports update only translated text and retain unmentioned regions`() {
        val source = tachiyomi.domain.translation.model.TextRegion(
            "r",
            listOf(
                TranslationPoint(
                    1f,
                    1f,
                ),
                TranslationPoint(
                    5f,
                    1f,
                ),
                TranslationPoint(
                    5f,
                    5f,
                ),
                TranslationPoint(
                    1f,
                    5f,
                ),
            ),
            "raw source", "before", correctedText = "manual correction", type = "dialogue", rotation = 90f,
            detectionConfidence = 0.8f, recognitionConfidence = 0.9f,
            style = tachiyomi.domain.translation.model.OverlayStyle(fontSize = 22f),
        )
        val untouched = source.copy(id = "unmentioned", readingOrder = 1)
        val raw = tachiyomi.domain.translation.model.OcrPageResult(
            original.id,
            listOf(
                source,
                untouched,
            ),
            rawJson = "raw-OCR",
        )
        val baseline = tachiyomi.domain.translation.model.TranslationPageResult(
            original.id,
            original.contentHash,
            100,
            200,
            listOf(
                source,
                untouched,
            ),
            raw,
            "ja",
            77,
        )
        val document = codec.decode(
            """{"pages":[{"imageId":"external","regions":[{"id":"r","translatedText":"after",
                "sourceText":"do not replace raw","rotation":0,"type":"sound_effect"}]}]}""".byteInputStream(),
            "text.json",
        )
        val mapping = mapOf(document.pages.single().key to original.id)
        val conflict = codec.plan(document, listOf(original), listOf(baseline), mapping).pages.single()
        assertEquals(StructuredImportDisposition.CONFLICT, conflict.disposition)
        val accepted = codec.plan(
            document,
            listOf(original),
            listOf(baseline),
            mapping,
            setOf(document.pages.single().key),
        ).readyPages.single()
        assertEquals(77L, accepted.expectedRevision)
        assertEquals(baseline.copy(regions = listOf(source.copy(translatedText = "after"), untouched)), accepted.result)
        val again = codec.plan(document, listOf(original), listOf(accepted.result!!), mapping).pages.single()
        assertEquals(StructuredImportDisposition.IDENTICAL, again.disposition)
        assertEquals(
            StructuredImportDisposition.INVALID,
            codec.plan(
                document,
                listOf(original),
                emptyList(),
                mapping,
            ).pages.single().disposition,
        )
    }

    @Test
    fun `redundant vertices normalize without accepting concavity or using a supplied rectangle`() {
        val closed = "[[100,100],[300,100],[500,100],[500,100],[500,300],[100,300],[100,100]]"
        val valid = codec.decode(providerPage(closed).byteInputStream(), "closed.json")
        val normalized = codec.plan(
            valid,
            listOf(original),
            emptyList(),
            mapOf(valid.pages.single().key to original.id),
        ).readyPages.single()
        assertEquals(setOf("sfx"), normalized.normalizedRegions)
        assertEquals(4, normalized.result!!.regions.single().points.size)
        assertEquals(TranslationPoint(50f, 20f), normalized.result!!.regions.single().points[1])
        val invalidBox = codec.decode(
            providerPage(closed).replace(
                "[100,100,300,500]",
                "[100,100,100,500]",
            ).byteInputStream(),
            "invalid-box.json",
        )
        assertTrue(invalidBox.pages.single().issues.single().path.endsWith("box2d"))
        listOf(
            "[[100,100],[300,500],[100,500],[300,100]]",
            "[[100,100],[100,500],[200,250],[300,500],[300,100]]",
            "[[100,100],[100,100],[100,100]]",
            "[[100,-1],[100,500],[300,500],[300,100]]",
            "[[100,\"100\"],[100,500],[300,500],[300,100]]",
        ).forEach { polygon ->
            val document = codec.decode(providerPage(polygon).byteInputStream(), "invalid.json")
            val result = codec.plan(
                document,
                listOf(original),
                emptyList(),
                mapOf(document.pages.single().key to original.id),
            ).pages.single()
            assertEquals(StructuredImportDisposition.INVALID, result.disposition, polygon)
            assertTrue(result.issues.isNotEmpty())
        }
    }

    @Test
    fun `recorded tile transforms map to the correct original and cannot silently discard rotation`() {
        val transform = """{"originalWidth":100,"originalHeight":200,"originalImageHash":"${original.contentHash}",
            "left":20,"top":40,"cropWidth":40,"cropHeight":80,"inputWidth":20,"inputHeight":40}"""
        val input = providerPage().replace(
            "\"imageId\":\"7\"",
            "\"imageId\":\"tile-1\",\"tileId\":\"tile-1\",\"inputTransform\":$transform",
        )
        val document = codec.decode(input.byteInputStream(), "tile.json")
        val result = codec.plan(document, listOf(original), emptyList()).readyPages.single().result!!
        assertEquals(TranslationPoint(24f, 48f), result.regions.single().points.first())
        val missing = codec.decode(
            providerPage().replace(
                "\"imageId\":\"7\"",
                "\"imageId\":\"tile-1\",\"isTile\":true",
            ).byteInputStream(),
            "tile.json",
        )
        assertTrue(missing.pages.single().issues.single().path.endsWith("inputTransform"))
        val rotated = codec.decode(
            input.replace(
                "\"left\":20",
                "\"rotation\":90,\"left\":20",
            ).byteInputStream(),
            "tile.json",
        )
        assertTrue(rotated.pages.single().issues.isNotEmpty())
        val wrong = codec.decode(input.replace("\"cropWidth\":40", "\"cropWidth\":200").byteInputStream(), "tile.json")
        assertEquals(
            StructuredImportDisposition.INVALID,
            codec.plan(
                wrong,
                listOf(original),
                emptyList(),
            ).pages.single().disposition,
        )
    }

    @Test
    fun `tile markers must be booleans even when a valid transform is supplied`() {
        val transform = """{"originalWidth":100,"originalHeight":200,"originalImageHash":"${original.contentHash}",
            "left":20,"top":40,"cropWidth":40,"cropHeight":80,"inputWidth":20,"inputHeight":40}"""
        fun document(marker: String?, includeTransform: Boolean) = codec.decode(
            providerPage().replace(
                "\"imageId\":\"7\"",
                "\"imageId\":\"7\"" +
                    (marker?.let { ",\"isTile\":$it" } ?: "") +
                    (if (includeTransform) ",\"inputTransform\":$transform" else ""),
            ).byteInputStream(),
            "tile-marker.json",
        )
        for (marker in listOf("\"true\"", "\"false\"", "1", "0", "null", "{}", "[]")) {
            for (includeTransform in listOf(false, true)) {
                val decoded = document(marker, includeTransform)
                val page = codec.plan(
                    decoded,
                    listOf(original),
                    emptyList(),
                    mapOf(decoded.pages.single().key to original.id),
                ).pages.single()
                assertEquals(StructuredImportDisposition.INVALID, page.disposition, marker)
                assertEquals("$.pages[0].isTile", page.issues.single().path, marker)
            }
        }
        for (marker in listOf(null, "false", "true")) {
            for (includeTransform in listOf(false, true)) {
                val decoded = document(marker, includeTransform)
                val page = codec.plan(
                    decoded,
                    listOf(original),
                    emptyList(),
                    mapOf(decoded.pages.single().key to original.id),
                ).pages.single()
                if (marker == "true" && !includeTransform) {
                    assertEquals(StructuredImportDisposition.INVALID, page.disposition)
                    assertEquals("$.pages[0].inputTransform", page.issues.single().path)
                } else {
                    assertEquals(StructuredImportDisposition.READY, page.disposition, "$marker/$includeTransform")
                }
            }
        }
    }

    @Test
    fun `one invalid page does not remove a separately mapped valid page`() {
        val first = providerPage().removePrefix("{\"pages\":[").removeSuffix("]}")
        val input = """{"pages":[$first,{"imageId":"other","regions":[
            {"id":"bad","translatedText":"x","polygon":[[0,0],[0,0],[0,0]]}]}]}"""
        val document = codec.decode(input.byteInputStream(), "partial.json")
        val second = original.copy(id = "second", contentHash = "b".repeat(64))
        val plan = codec.plan(
            document,
            listOf(
                original,
                second,
            ),
            emptyList(),
            mapOf(
                document.pages[0].key to original.id,
                document.pages[1].key to second.id,
            ),
        )
        assertEquals(
            listOf(
                StructuredImportDisposition.READY,
                StructuredImportDisposition.INVALID,
            ),
            plan.pages.map { it.disposition },
        )
        assertEquals(1, plan.readyPages.size)
        val sameTarget = codec.plan(
            document,
            listOf(original),
            emptyList(),
            document.pages.associate { it.key to original.id },
        )
        assertTrue(sameTarget.pages.all { it.disposition == StructuredImportDisposition.INVALID })
    }

    @Test
    fun `duplicate identities and mismatched content cannot be forced through explicit mapping`() {
        val first = providerPage().removePrefix("{\"pages\":[").removeSuffix("]}")
        val repeated = codec.decode("{\"pages\":[$first,$first]}".byteInputStream(), "duplicate.json")
        assertTrue(repeated.pages.all { it.issues.any { issue -> issue.message == "Duplicate page identity" } })
        val regionDuplicate = codec.decode(
            providerPage().replace(
                "\"id\":\"sfx\"",
                "\"id\":\"\"",
            ).byteInputStream(),
            "blank.json",
        )
        assertTrue(regionDuplicate.pages.single().issues.single().path.endsWith(".id"))
        val hashMismatch = codec.decode(
            providerPage().replace(
                "\"imageId\":\"7\"",
                "\"imageId\":\"7\",\"imageHash\":\"${"b".repeat(64)}\"",
            ).byteInputStream(),
            "wrong.json",
        )
        assertEquals(
            StructuredImportDisposition.INVALID,
            codec.plan(
                hashMismatch,
                listOf(original),
                emptyList(),
                mapOf(hashMismatch.pages.single().key to original.id),
            ).pages.single().disposition,
        )
    }

    @Test
    fun `incomplete envelopes and unsupported schemas are field-specific errors`() {
        val quoted = kotlinx.serialization.json.JsonPrimitive(providerPage()).toString()
        listOf(
            """{"status":"incomplete","output_text":$quoted}""",
            """{"choices":[{"finish_reason":"length","message":{"content":$quoted}}]}""",
            """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":$quoted}]}}]}""",
            """{"format":"something-else","version":1,"pages":[]}""",
            """{"format":"mihon-structured-translations","version":2,"pages":[]}""",
            """{"bubbles":[]}""",
            """{"pages": [""",
        ).forEach { text ->
            val document = codec.decode(text.byteInputStream(), "invalid.json")
            assertTrue(document.errors.isNotEmpty(), text)
            assertTrue(document.errors.all { it.path.startsWith("$") })
            assertTrue(document.pages.isEmpty())
        }
    }

    @Test
    fun `bounded input and cancellation cannot retain partial decoded work`() {
        val huge = object : java.io.InputStream() {
            var remaining = StructuredTranslationCodec.RECORD_LIMIT + 1
            override fun read(): Int = if (remaining-- > 0) ' '.code else -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                bytes.fill(' '.code.toByte(), offset, offset + count)
                remaining -= count
                return count
            }
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            codec.decode(
                huge,
                "oversize.json",
            )
        }
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CancellationException::class.java) {
            codec.decode(
                providerPage().byteInputStream(),
                "cancelled.json",
            ) { throw java.util.concurrent.CancellationException() }
        }
    }

    @Test
    fun `invalid encoding and excessive nesting are rejected without parsing unbounded structures`() {
        val malformed = byteArrayOf(0x7b, 0x22, 0xc3.toByte(), 0x28, 0x22, 0x3a, 0x30, 0x7d)
        assertEquals(
            "File is not valid UTF-8 JSON",
            codec.decode(
                malformed.inputStream(),
                "bad-encoding.json",
            ).errors.single().message,
        )
        val deep = "{\"pages\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}"
        assertTrue(codec.decode(deep.byteInputStream(), "deep.json").errors.single().message.contains("depth"))
        val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + providerPage().toByteArray()
        assertTrue(codec.decode(bom.inputStream(), "bom.json").errors.isEmpty())
    }

    @Test
    fun `native zip is identified for existing verified archive handling and docs are valid JSON`() {
        val zip = java.io.ByteArrayOutputStream().also { output ->
            java.util.zip.ZipOutputStream(output).use {
                it.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                it.write("{}".toByteArray())
                it.closeEntry()
            }
        }.toByteArray()
        val document = codec.decode(zip.inputStream(), "backup.zip")
        assertEquals(tachiyomi.domain.translation.model.StructuredImportFormat.MIHON_ZIP, document.source.format)
        assertEquals(zip.size.toLong(), document.source.bytes)
        assertTrue(document.pages.isEmpty())
        val sample = codec.decode(StructuredTranslationDocuments.sampleJson.byteInputStream(), "sample.json")
        assertTrue(sample.errors.isEmpty())
        assertTrue(sample.pages.single().issues.isEmpty())
        val sampleImage = original.copy(width = 1200, height = 1600)
        assertEquals(
            1,
            codec.plan(
                sample,
                listOf(sampleImage),
                emptyList(),
                mapOf(sample.pages.single().key to sampleImage.id),
            ).readyPages.size,
        )
        kotlinx.serialization.json.Json.parseToJsonElement(StructuredTranslationDocuments.schemaJson)
    }

    private fun providerPage(polygon: String = "null"): String =
        """{"pages":[{"imageId":"7","regions":[{"id":"sfx","sourceText":"쿵","translatedText":"THUD",""" +
            """"type":"sound_effect","included":true,"box2d":[100,100,300,500],"polygon":$polygon}]}]}"""
}
