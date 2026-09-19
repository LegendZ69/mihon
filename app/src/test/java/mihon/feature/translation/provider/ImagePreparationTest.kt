package mihon.feature.translation.provider

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationSettings
import java.io.File

class ImagePreparationTest {
    @TempDir lateinit var directory: File

    @Test
    fun `long strips and wide pages stay within decode bounds without gaps`() {
        listOf(1000 to 40_000, 12_000 to 1500, 511 to 513, 1 to 20_000).forEach { (width, height) ->
            val tiles = UploadTileGeometry.tiles(width, height, 4000, 2_000_000, 64)
            assertTrue(
                tiles.all {
                    it.width <= 4000 && it.height <= 4000 && it.width.toLong() * it.height <= 2_000_000
                },
            )
            val xs = (0 until width step maxOf(1, width / 50)).toList() + (width - 1)
            val ys = (0 until height step maxOf(1, height / 50)).toList() + (height - 1)
            xs.forEach { x ->
                ys.forEach { y ->
                    assertTrue(
                        tiles.any {
                            x in it.left until it.right && y in it.top until it.bottom
                        },
                        "Uncovered source pixel $x,$y",
                    )
                }
            }
        }
    }

    @Test
    fun `byte-limit subdivision always progresses while retaining overlap`() {
        listOf(
            UploadRectangle(0, 0, 2, 1),
            UploadRectangle(0, 0, 1, 2),
            UploadRectangle(100, 200, 1100, 4200),
        ).forEach { original ->
            val halves = UploadTileGeometry.halves(original, 64)
            assertEquals(2, halves.size)
            assertTrue(halves.all { it.width.toLong() * it.height < original.width.toLong() * original.height })
            assertEquals(original.left, halves.minOf { it.left })
            assertEquals(original.top, halves.minOf { it.top })
            assertEquals(original.right, halves.maxOf { it.right })
            assertEquals(original.bottom, halves.maxOf { it.bottom })
        }
    }

    @Test
    fun `tile coordinates map to source and overlap duplicates merge only when page is complete`() {
        val original = source()
        val first =
            PreparedImageTile(
                original,
                original.copy(id = "first", height = 110, contentHash = "first-hash"),
                UploadRectangle(0, 0, 100, 110),
            )
        val second =
            PreparedImageTile(
                original,
                original.copy(id = "second", height = 110, contentHash = "second-hash"),
                UploadRectangle(0, 90, 100, 200),
            )
        val prepared = PreparedTranslationRequest(request(original), listOf(first, second))
        val top = result(first.image, rectangle(20f, 95f, 60f, 105f))
        val bottom = result(second.image, rectangle(20f, 5f, 60f, 15f))
        assertTrue(prepared.merge(listOf(top)).isEmpty())
        val page = prepared.merge(listOf(top, bottom)).single()
        assertEquals(original.id, page.imageId)
        assertEquals(original.contentHash, page.imageHash)
        assertEquals(200, page.height)
        assertEquals(1, page.regions.size)
        assertEquals(
            95f,
            page.regions
                .single()
                .points
                .minOf { it.y },
        )
        assertEquals(
            105f,
            page.regions
                .single()
                .points
                .maxOf { it.y },
        )
    }

    @Test
    fun `hybrid tiles preserve source OCR polygon ID and measured confidence`() {
        val original = source()
        val polygon = rectangle(10f, 90f, 90f, 120f)
        val sourceRegion =
            TextRegion(
                "ocr-region",
                polygon,
                "original OCR",
                recognitionConfidence = 0.7f,
                style = OverlayStyle(fontSize = 23f),
            )
        val request =
            request(original).copy(
                settings =
                TranslationSettings().let {
                    it.copy(ocr = it.ocr.copy(pipeline = OcrPipeline.PADDLE_AI))
                },
                ocr = listOf(OcrPageResult(original.id, listOf(sourceRegion))),
            )
        val first =
            PreparedImageTile(original, original.copy(id = "first", height = 110), UploadRectangle(0, 0, 100, 110))
        val second =
            PreparedImageTile(original, original.copy(id = "second", height = 110), UploadRectangle(0, 90, 100, 200))
        val prepared = PreparedTranslationRequest(request, listOf(first, second))
        assertTrue(
            prepared.wire.ocr
                .flatMap { it.regions }
                .flatMap { it.points }
                .all { it.x >= 0 && it.y >= 0 },
        )
        val pages =
            listOf(
                result(first.image, rectangle(10f, 90f, 90f, 110f), "ocr-region"),
                result(second.image, rectangle(10f, 0f, 90f, 30f), "ocr-region"),
            )
        val merged =
            prepared
                .merge(pages)
                .single()
                .regions
                .single()
        assertEquals("ocr-region", merged.id)
        assertEquals(polygon, merged.points)
        assertEquals(0.7f, merged.recognitionConfidence)
        assertEquals("original OCR", merged.sourceText)
        assertEquals(sourceRegion.style, merged.style)
    }

    @Test
    fun `tile checkpoints survive smaller retry groups and invalidate when instructions change`() {
        val first = source()
        val second = first.copy(id = "second", contentHash = "another")
        val request =
            request(
                first,
            ).copy(
                images = listOf(first, second),
                ocr = listOf(OcrPageResult(first.id, emptyList()), OcrPageResult(second.id, emptyList())),
            )
        val storage = ImagePreparation(directory)
        val page = result(first, rectangle(0f, 0f, 10f, 10f))
        storage.checkpoint(request, first, page)
        val retry = request.copy(batchId = "new-batch", images = listOf(first), ocr = request.ocr.take(1))
        assertEquals(page, storage.cached(retry, first))
        assertNull(storage.cached(retry.copy(settings = retry.settings.copy(instructions = "New terminology")), first))
        assertFalse(storage.checkpointFile(request, first).name.contains("page"))
    }

    @Test
    fun `forced retranslation clears only its job tile checkpoints`() =
        runBlocking {
            val image = source()
            val storage = ImagePreparation(directory)
            val request = request(image)
            val other = request.copy(jobId = "other")
            val page = result(image, rectangle(0f, 0f, 10f, 10f))
            storage.checkpoint(request, image, page)
            storage.checkpoint(other, image, page)
            storage.clearCheckpoints(request.jobId)
            assertNull(storage.cached(request, image))
            assertEquals(page, storage.cached(other, image))
        }

    @Test
    fun `AI-added region ending in an OCR ID is not matched to supplied geometry`() {
        val image = source()
        val region = TextRegion("region", rectangle(0f, 0f, 10f, 10f), "OCR source")
        val prepared =
            PreparedTranslationRequest(
                request(image).copy(ocr = listOf(OcrPageResult(image.id, listOf(region)))),
                listOf(PreparedImageTile(image, image, UploadRectangle(0, 0, 100, 200))),
            )
        val newPoints = rectangle(60f, 100f, 80f, 130f)
        val page = prepared.merge(listOf(result(image, newPoints, "new:region"))).single()
        assertEquals(newPoints, page.regions.single().points)
        assertEquals("source", page.regions.single().sourceText)
    }

    @Test
    fun `hybrid merge retains corrected vertical passage order from supplied OCR IDs`() {
        val original = source()
        val sourceTexts = listOf("まだ敵が外にいる", "扉を開けないで", "ユナ", "ここで待つ", "わかった")
        val raw =
            sourceTexts.mapIndexed { index, text ->
                TextRegion("ocr-$index", rectangle(10f * index, 10f, 10f * index + 5, 60f), text, readingOrder = index)
            }
        val request =
            request(original).copy(
                settings = TranslationSettings().let { it.copy(ocr = it.ocr.copy(pipeline = OcrPipeline.PADDLE_AI)) },
                ocr = listOf(OcrPageResult(original.id, raw)),
            )
        val correctedOrder = listOf(2, 1, 0, 4, 3)
        val response =
            TranslationPageResult(
                original.id,
                original.contentHash,
                original.width,
                original.height,
                correctedOrder.mapIndexed { order, rawIndex ->
                    raw[rawIndex].copy(translatedText = "translated-$rawIndex", readingOrder = order)
                },
            )
        val prepared =
            PreparedTranslationRequest(
                request,
                listOf(PreparedImageTile(original, original, UploadRectangle(0, 0, 100, 200))),
            )
        val merged = prepared.merge(listOf(response)).single()
        assertEquals(
            correctedOrder.map { raw[it].id },
            merged.regions.sortedBy { it.readingOrder }.map { it.id },
            "The captured hybrid response corrects local OCR's left-to-right Japanese order",
        )
        assertEquals(raw, merged.rawOcr!!.regions)
    }

    @Test
    fun `hybrid merge restores corrected geometry and adds regions without losing raw OCR metadata`() {
        val original = source()
        val raw =
            TextRegion(
                "ocr-region",
                rectangle(10f, 120f, 70f, 150f),
                ".",
                readingOrder = 0,
                detectionConfidence = 0.8f,
                recognitionConfidence = 0.3f,
                style = OverlayStyle(fontSize = 23f),
            )
        val settings = TranslationSettings().let { it.copy(ocr = it.ocr.copy(pipeline = OcrPipeline.PADDLE_AI)) }
        val prepared =
            PreparedTranslationRequest(
                request(original).copy(settings = settings, ocr = listOf(OcrPageResult(original.id, listOf(raw)))),
                listOf(
                    PreparedImageTile(
                        original,
                        original.copy(id = "top", height = 100),
                        UploadRectangle(0, 0, 100, 100),
                    ),
                    PreparedImageTile(
                        original,
                        original.copy(id = "bottom", width = 50, height = 50),
                        UploadRectangle(0, 100, 100, 200),
                    ),
                ),
            )
        val top =
            TranslationPageResult(
                "top",
                "source-hash",
                100,
                100,
                listOf(
                    TextRegion(
                        "added",
                        rectangle(20f, 10f, 60f, 30f),
                        "유나, 먼저 올라가!",
                        "Yuna, go up first!",
                        readingOrder = 1,
                    ),
                ),
            )
        val corrected =
            raw.copy(
                points = rectangle(4f, 8f, 40f, 28f),
                correctedText = "하지만 널 두고 갈 수는 없어.",
                translatedText = "But I can't leave you behind.",
                readingOrder = 1,
                aiConfidence = 0.95f,
                rotation = -17f,
            )
        val bottom = TranslationPageResult("bottom", "source-hash", 50, 50, listOf(corrected))
        val merged = prepared.merge(listOf(bottom, top)).single()
        assertEquals(
            listOf("Yuna, go up first!", "But I can't leave you behind."),
            merged.regions
                .sortedBy {
                    it.readingOrder
                }.map { it.translatedText },
        )
        val kept = merged.regions.single { it.id == raw.id }
        assertEquals(rectangle(8f, 116f, 80f, 156f), kept.points)
        assertEquals(listOf(0, 1), merged.regions.map { it.readingOrder })
        assertEquals(raw.sourceText, kept.sourceText)
        assertEquals(corrected.correctedText, kept.correctedText)
        assertEquals(raw.detectionConfidence, kept.detectionConfidence)
        assertEquals(raw.recognitionConfidence, kept.recognitionConfidence)
        assertEquals(raw.style, kept.style)
        assertEquals(0.95f, kept.aiConfidence)
        assertEquals(-17f, kept.rotation)
        assertEquals(raw, merged.rawOcr!!.regions.single())
    }

    @Test
    fun `Paddle text-only merge keeps the original polygon and stable OCR identity`() {
        val original = source()
        val raw = TextRegion("ocr-region", rectangle(10f, 10f, 70f, 40f), "original", readingOrder = 4)
        val settings = TranslationSettings().let { it.copy(ocr = it.ocr.copy(pipeline = OcrPipeline.PADDLE)) }
        val prepared =
            PreparedTranslationRequest(
                request(original).copy(settings = settings, ocr = listOf(OcrPageResult(original.id, listOf(raw)))),
                listOf(PreparedImageTile(original, original, UploadRectangle(0, 0, 100, 200))),
            )
        val response = result(original, rectangle(15f, 15f, 60f, 30f), raw.id)
        val merged = prepared.merge(listOf(response)).single()
        assertEquals(raw.id, merged.regions.single().id)
        assertEquals(raw.points, merged.regions.single().points)
        assertEquals(raw.sourceText, merged.regions.single().sourceText)
        assertEquals(raw.readingOrder, merged.regions.single().readingOrder)
        assertEquals(raw, merged.rawOcr!!.regions.single())
    }

    private fun source() = TranslationImage("page", 0, "/unused/source.png", "image/png", 100, 200, "source-hash", 10)

    private fun request(image: TranslationImage) = TranslationRequest(
        "job",
        "batch",
        TranslationSettings(),
        listOf(image),
    )

    private fun rectangle(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = listOf(
        TranslationPoint(left, top),
        TranslationPoint(right, top),
        TranslationPoint(right, bottom),
        TranslationPoint(left, bottom),
    )

    private fun result(
        image: TranslationImage,
        points: List<TranslationPoint>,
        id: String = "region",
    ) = TranslationPageResult(
        image.id,
        image.contentHash,
        image.width,
        image.height,
        listOf(TextRegion(id, points, "source", "Translated")),
    )
}
