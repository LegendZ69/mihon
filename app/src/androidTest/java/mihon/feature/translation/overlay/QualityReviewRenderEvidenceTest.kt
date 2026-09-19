package mihon.feature.translation.overlay

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.color.MaterialColors
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.QualityReviewRenderEvidence
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationSettings
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Real Android renderer/filesystem only: synthetic images, test-owned font copies, and zero provider calls. */
@RunWith(AndroidJUnit4::class)
class QualityReviewRenderEvidenceTest {
    @Test
    fun sampledReviewPinsReadableColorsAndReplaysThemWithoutChangingTheBaseline() = fixture("sampled-contrast") {
        val dark = resolveReviewTheme(context, ThemeMode.DARK, AppTheme.CATPPUCCIN, false)
        val renderer = AndroidQualityReviewRenderer(context, presentationContext = { dark })
        val request = request(style = OverlayStyle(sampleBackground = true))
        val baseline = request.baseline
        val evidence = renderer.prepare(request)
        assertEquals(null, evidence.presentation.requestedStyle.textColor)
        assertEquals(
            Color.BLACK.toUInt().toLong(),
            evidence.presentation.resolvedRegionStyles.getValue("speech").textColor,
        )
        val diagnostic = evidence.presentation.layoutDiagnostics.single()
        assertFalse(
            "Resolved automatic colors must retain their original intent in diagnostics",
            diagnostic.customTextColor,
        )
        assertTrue(checkNotNull(diagnostic.contrastRatio) >= 4.5)
        val decoded = checkNotNull(BitmapFactory.decodeFile(request.image.filePath))
        val expected = try {
            decoded.copy(Bitmap.Config.ARGB_8888, true)
        } finally {
            decoded.recycle()
        }
        val replay = checkNotNull(BitmapFactory.decodeFile(request.image.filePath)).let { original ->
            try {
                original.copy(Bitmap.Config.ARGB_8888, true)
            } finally {
                original.recycle()
            }
        }
        val actual = checkNotNull(BitmapFactory.decodeFile(evidence.preview.filePath))
        try {
            TranslationOverlayDocument.create(
                dark,
                baseline,
                request.settings.style,
                evidence.presentation.sampledBackgrounds,
            ).draw(Canvas(expected))
            assertTrue("Review pixels must equal the shared reader rendering", expected.sameAs(actual))
            val resolved = baseline.copy(
                regions = baseline.regions.map {
                    it.copy(style = evidence.presentation.resolvedRegionStyles.getValue(it.id))
                },
            )
            TranslationOverlayDocument.create(
                context,
                resolved,
                evidence.presentation.resolvedStyle,
                evidence.presentation.sampledBackgrounds,
            ).draw(Canvas(replay))
            assertTrue("Pinned colors must replay independently of the current theme", replay.sameAs(actual))
        } finally {
            expected.recycle()
            replay.recycle()
            actual.recycle()
        }
        val previewBytes = File(evidence.preview.filePath).readBytes()
        val changedThemeRenderer = AndroidQualityReviewRenderer(context, presentationContext = {
            resolveReviewTheme(context, ThemeMode.LIGHT, AppTheme.CATPPUCCIN, false)
        })
        assertEquals("Retries keep the original presentation bytes", evidence, changedThemeRenderer.prepare(request))
        assertNotEquals(
            evidence.presentationFingerprint,
            changedThemeRenderer.presentationFingerprint(baseline, request.settings.style),
        )
        assertTrue(previewBytes.contentEquals(File(evidence.preview.filePath).readBytes()))
        assertEquals(baseline, request.baseline)
        assertEquals(request.image.contentHash, hash(File(request.image.filePath)))
        receipt.put("automatic_foreground", "black")
            .put("preview_sha256", evidence.preview.contentHash)
            .put("baseline_revision_preserved", baseline.revision)
            .put("pinned_colors_replay_across_themes", true)
    }

    @Test
    fun soundEffectPolicyPinsPreviewPixelsAndFingerprintWithoutChangingSavedRegions() = fixture("content-policy") {
        val base =
            request(style = OverlayStyle(textColor = Color.WHITE.toLong(), backgroundColor = Color.BLUE.toLong()))
        val request = base.copy(
            baseline = base.baseline.copy(regions = base.baseline.regions.map { it.copy(type = "sound_effect") }),
        )
        val baseline = request.baseline
        val ignored = renderer.prepare(request)
        val original = checkNotNull(BitmapFactory.decodeFile(request.image.filePath))
        val ignoredPreview = checkNotNull(BitmapFactory.decodeFile(ignored.preview.filePath))
        try {
            assertTrue(
                "Default review preview keeps original SFX pixels without a mask or translated glyph",
                original.sameAs(ignoredPreview),
            )
        } finally {
            original.recycle()
            ignoredPreview.recycle()
        }
        assertEquals(request.settings.contentPolicy, ignored.presentation.contentPolicy)
        val legacyRequest = request.copy(
            reviewId = UUID.randomUUID().toString(),
            settings = request.settings.copy(contentPolicy = TranslationContentPolicy.Legacy),
        )
        val included = renderer.prepare(legacyRequest)
        assertEquals(TranslationContentPolicy.Legacy, included.presentation.contentPolicy)
        assertNotEquals(
            "Turning the policy off renders the already cached translation",
            ignored.preview.contentHash,
            included.preview.contentHash,
        )
        assertNotEquals(
            "Presentation identity pins the content policy",
            ignored.presentationFingerprint,
            included.presentationFingerprint,
        )
        assertEquals("No source, correction, score, geometry or result revision changes", baseline, request.baseline)
        assertEquals(request.image.contentHash, hash(File(request.image.filePath)))
        receipt.put("policy_changes_preview_and_fingerprint", true)
            .put("cached_revision_preserved", request.baseline.revision)
    }

    @Test
    fun evidenceReuseRejectsPolicyChangesAndMissingLegacyPolicyRetainsExistingBytes() = fixture("legacy-policy") {
        val base = request()
        val request = base.copy(settings = base.settings.copy(contentPolicy = TranslationContentPolicy.Legacy))
        val evidence = renderer.prepare(request)
        val manifest = File(evidenceDirectory(request), "evidence.json")
        val legacy = JSONObject(manifest.readText())
        legacy.getJSONObject("presentation").remove("contentPolicy")
        manifest.writeText(legacy.toString())
        val manifestBefore = manifest.readBytes()
        val previewBefore = File(evidence.preview.filePath).readBytes()
        val reopened = AndroidQualityReviewRenderer(context)
        assertEquals("Missing presentation policy uses legacy behavior", evidence, reopened.prepare(request))
        fails {
            reopened.prepare(request.copy(settings = request.settings.copy(contentPolicy = TranslationContentPolicy())))
        }
        assertTrue(
            "A mismatched request cannot rewrite pinned evidence",
            manifestBefore.contentEquals(manifest.readBytes()),
        )
        assertTrue(previewBefore.contentEquals(File(evidence.preview.filePath).readBytes()))
        receipt.put("legacy_presentation_policy", "include_sound_effects")
            .put("policy_mismatch_rejected_without_regeneration", true)
    }

    @Test
    fun completeOriginalAndSharedCanvasPixelsArePinnedWithoutChangingTheBaseline() = fixture("immutable") {
        val request = request()
        val baseline = request.baseline
        val originalBytes = File(request.image.filePath).readBytes()
        val evidence = renderer.prepare(request)
        assertTrue(originalBytes.contentEquals(File(evidence.original.filePath).readBytes()))
        assertTrue(originalBytes.contentEquals(File(request.image.filePath).readBytes()))
        assertEquals(baseline, request.baseline)
        assertEquals(request.image.copy(filePath = evidence.original.filePath), evidence.original)
        assertEquals(baseline.revision, evidence.sourceRevision)
        assertEquals(AndroidQualityReviewRenderer.VERSION, evidence.rendererVersion)
        assertEquals(1.0, evidence.scaleX, 0.0)
        assertEquals(1.0, evidence.scaleY, 0.0)
        assertTrue(evidence.presentationFingerprint.matches(Regex("[a-f0-9]{64}")))
        assertEquals(evidence.preview.contentHash, hash(File(evidence.preview.filePath)))
        assertEquals(evidence.preview.byteSize, File(evidence.preview.filePath).length())
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size))
        val expected = try {
            decoded.copy(Bitmap.Config.ARGB_8888, true)
        } finally {
            decoded.recycle()
        }
        val actual = checkNotNull(BitmapFactory.decodeFile(evidence.preview.filePath))
        try {
            val resolved = baseline.copy(
                regions = baseline.regions.map {
                    it.copy(style = evidence.presentation.resolvedRegionStyles.getValue(it.id))
                },
            )
            TranslationOverlayDocument.create(
                context,
                resolved,
                evidence.presentation.resolvedStyle,
                evidence.presentation.sampledBackgrounds,
            ).draw(Canvas(expected))
            assertTrue("The preview must use the shared Canvas overlay", expected.sameAs(actual))
            assertNotEquals(
                "Translation must visibly change the generated page",
                hash(File(request.image.filePath)),
                evidence.preview.contentHash,
            )
        } finally {
            expected.recycle()
            actual.recycle()
        }
        receipt.put("original_sha256", evidence.original.contentHash)
            .put("preview_sha256", evidence.preview.contentHash)
            .put("baseline_revision", baseline.revision)
    }

    @Test
    fun previewPngStaysWithinPixelDimensionAndNoUpscalingBounds() = fixture("bounds") {
        for ((width, height) in listOf(31 to 23, 2100 to 1100, 5001 to 32)) {
            val request = request(width, height, regions = false)
            val evidence = renderer.prepare(request)
            val png = File(evidence.preview.filePath)
            assertTrue(
                png.inputStream().use {
                    it.readNBytes(8)
                }.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)),
            )
            val bitmap = checkNotNull(BitmapFactory.decodeFile(png.path))
            try {
                assertEquals(bitmap.width, evidence.preview.width)
                assertEquals(bitmap.height, evidence.preview.height)
                assertTrue(bitmap.width in 1..minOf(width, 4096))
                assertTrue(bitmap.height in 1..minOf(height, 4096))
                assertTrue(bitmap.width.toLong() * bitmap.height <= 2L * 1024 * 1024)
                if (width == 31) {
                    assertEquals(width, bitmap.width)
                    assertEquals(height, bitmap.height)
                }
                assertEquals(bitmap.width.toDouble() / width, evidence.scaleX, 0.0)
                assertEquals(bitmap.height.toDouble() / height, evidence.scaleY, 0.0)
                assertEquals(hash(File(request.image.filePath)), evidence.original.contentHash)
            } finally {
                bitmap.recycle()
            }
            renderer.cleanup(evidence)
        }
    }

    @Test
    fun persistedEvidenceIsReusedWhenTheChapterFileIsReplacedOrMissing() = fixture("reuse") {
        val request = request()
        val first = renderer.prepare(request)
        val original = File(first.original.filePath).readBytes()
        val preview = File(first.preview.filePath).readBytes()
        File(request.image.filePath).writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(first, renderer.prepare(request))
        assertTrue(File(request.image.filePath).delete())
        val reopened = AndroidQualityReviewRenderer(context)
        assertEquals(first, reopened.prepare(request))
        assertTrue(original.contentEquals(File(first.original.filePath).readBytes()))
        assertTrue(preview.contentEquals(File(first.preview.filePath).readBytes()))
        assertFalse(File(first.preview.filePath).parentFile!!.listFiles().orEmpty().any { it.extension == "part" })
        receipt.put("manifest_reused_after_source_removal", true)
    }

    @Test
    fun resolvedPresentationRetainsStyleThemeAndSystemFontIdentities() = fixture("styles") {
        val style = OverlayStyle(
            fontFamily = "serif",
            fontWeight = 600,
            italic = true,
            showBoxes = true,
            showCoordinates = true,
            showConfidence = true,
            showReadingOrder = true,
            showIgnored = true,
        )
        val base = request(style = style)
        val regionStyle =
            OverlayStyle(textColor = Color.RED.toUInt().toLong(), backgroundColor = Color.YELLOW.toUInt().toLong())
        val request = base.copy(
            baseline = base.baseline.copy(
                regions =
                base.baseline.regions + base.baseline.regions.single().copy(
                    id = "override",
                    style = regionStyle,
                    readingOrder = 1,
                ),
            ),
        )
        val evidence = renderer.prepare(request)
        val presentation = evidence.presentation
        assertEquals(style, presentation.requestedStyle)
        val expectedText = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurface,
            Color.BLACK,
        )
        val expectedBackground = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurface,
            Color.WHITE,
        )
        assertEquals(expectedText.toUInt().toLong(), presentation.resolvedStyle.textColor)
        assertEquals(expectedBackground.toUInt().toLong(), presentation.resolvedStyle.backgroundColor)
        assertEquals(regionStyle.textColor, presentation.resolvedRegionStyles.getValue("override").textColor)
        assertEquals(
            regionStyle.backgroundColor,
            presentation.resolvedRegionStyles.getValue("override").backgroundColor,
        )
        (listOf(presentation.resolvedStyle) + presentation.resolvedRegionStyles.values).forEach {
            assertFalse(
                it.showBoxes || it.showCoordinates || it.showConfidence || it.showReadingOrder || it.showIgnored,
            )
        }
        assertTrue(
            presentation.fontIdentities.values.all {
                it.contains(Build.FINGERPRINT) && it.endsWith("file-hash-unavailable")
            },
        )
        assertEquals(evidence.presentationFingerprint, renderer.presentationFingerprint(request.baseline, style))
        assertNotEquals(
            evidence.presentationFingerprint,
            renderer.presentationFingerprint(request.baseline, style.copy(fontWeight = 900)),
        )
        assertNotEquals(
            evidence.presentationFingerprint,
            renderer.presentationFingerprint(request.baseline, style.copy(textColor = Color.BLUE.toLong())),
        )
        receipt.put("resolved_text_color", presentation.resolvedStyle.textColor)
            .put("resolved_background_color", presentation.resolvedStyle.backgroundColor)
            .put("system_font_hash_available", false)
    }

    @Test
    fun importedFontsAreHashedDeduplicatedAndRemainReusableWithoutTheirSource() = fixture("fonts") {
        val firstFont = importedFont("font-a.ttf")
        val secondFont = File(root, "font-b.ttf").also { firstFont.copyTo(it) }
        val fontHash = hash(firstFont)
        val base = request(style = OverlayStyle(fontPath = firstFont.path))
        val request = base.copy(
            baseline = base.baseline.copy(
                regions = base.baseline.regions.map {
                    it.copy(style = OverlayStyle(fontPath = secondFont.path))
                },
            ),
        )
        val evidence = renderer.prepare(request)
        val presentation = evidence.presentation
        val pinned = File(checkNotNull(presentation.resolvedStyle.fontPath))
        assertEquals(fontHash, hash(pinned))
        assertEquals(pinned.path, presentation.resolvedRegionStyles.getValue("speech").fontPath)
        assertEquals("sha256:$fontHash", presentation.fontIdentities[firstFont.path])
        assertEquals("sha256:$fontHash", presentation.fontIdentities[secondFont.path])
        assertEquals(1, pinned.parentFile!!.listFiles().orEmpty().count { it.name.startsWith("font-") })
        assertTrue(firstFont.delete())
        assertTrue(secondFont.delete())
        assertEquals(evidence, AndroidQualityReviewRenderer(context).prepare(request))
        assertEquals(fontHash, hash(pinned))
        assertNotEquals(
            evidence.presentationFingerprint,
            renderer.presentationFingerprint(request.baseline, request.settings.style),
        )
        renderer.cleanup(evidence)
        assertFalse(pinned.exists())
        assertTrue(File(request.image.filePath).isFile)
        receipt.put("imported_font_sha256", fontHash).put("one_copy_for_duplicate_font_bytes", true)
    }

    @Test
    fun malformedMissingAndOversizedImportedFontsNeverProduceFallbackEvidence() = fixture("invalid-fonts") {
        val invalid = File(root, "invalid.ttf").apply { writeText("This is not a font") }
        val missing = File(root, "missing.ttf")
        val oversized = File(root, "oversized.ttf")
        RandomAccessFile(oversized, "rw").use { it.setLength(32L * 1024 * 1024 + 1) }
        for (font in listOf(invalid, missing, oversized)) {
            val request = request(style = OverlayStyle(fontPath = font.path))
            val originalHash = hash(File(request.image.filePath))
            val failure = fails { renderer.prepare(request) }
            assertTrue(
                "Expected an imported-font rejection, not an unrelated preparation failure: ${failure.message}",
                failure.message.orEmpty().contains("font", ignoreCase = true),
            )
            assertFalse("Failed preparation must not retain a partial folder", evidenceDirectory(request).exists())
            assertEquals(originalHash, hash(File(request.image.filePath)))
        }
    }

    @Test
    fun changedOrMissingPinnedFontCannotBeSilentlyReused() = fixture("pinned-font") {
        for (remove in listOf(false, true)) {
            val font = importedFont("source-$remove.ttf")
            val request = request(style = OverlayStyle(fontPath = font.path))
            val evidence = renderer.prepare(request)
            val pinned = File(checkNotNull(evidence.presentation.resolvedStyle.fontPath))
            if (remove) assertTrue(pinned.delete()) else pinned.writeBytes(byteArrayOf(1, 2, 3))
            fails { AndroidQualityReviewRenderer(context).prepare(request) }
            assertTrue("The imported source font remains untouched", font.isFile)
            assertEquals(evidence.preview.contentHash, hash(File(evidence.preview.filePath)))
            renderer.cleanup(evidence)
        }
    }

    @Test
    fun missingOrCorruptPinnedImagesAndManifestNeverTriggerSilentRegeneration() = fixture("corrupt-evidence") {
        for (corruption in listOf("original", "preview", "manifest")) {
            val request = request()
            val evidence = renderer.prepare(request)
            val changed = when (corruption) {
                "original" -> File(evidence.original.filePath).also { assertTrue(it.delete()) }
                "preview" -> File(evidence.preview.filePath).apply { writeBytes(byteArrayOf(6, 5, 4)) }
                else -> File(evidenceDirectory(request), "evidence.json").apply { writeText("{incomplete") }
            }
            val changedBytes = changed.takeIf { it.exists() }?.readBytes()
            fails { AndroidQualityReviewRenderer(context).prepare(request) }
            if (changedBytes ==
                null
            ) {
                assertFalse(changed.exists())
            } else {
                assertTrue(changedBytes.contentEquals(changed.readBytes()))
            }
            assertEquals(request.image.contentHash, hash(File(request.image.filePath)))
            renderer.cleanup(evidence)
        }
    }

    @Test
    fun reusedManifestMustMatchTheCompleteImageIdentityAndRevision() = fixture("identity") {
        val request = request()
        val evidence = renderer.prepare(request)
        val mismatches = listOf(
            request.copy(image = request.image.copy(id = "another-page")),
            request.copy(image = request.image.copy(index = request.image.index + 1)),
            request.copy(image = request.image.copy(width = request.image.width + 1)),
            request.copy(image = request.image.copy(height = request.image.height + 1)),
            request.copy(image = request.image.copy(mimeType = "image/jpeg")),
            request.copy(image = request.image.copy(byteSize = request.image.byteSize + 1)),
            request.copy(baseline = request.baseline.copy(revision = request.baseline.revision + 1)),
            request.copy(settings = request.settings.copy(style = request.settings.style.copy(fontWeight = 700))),
            request.copy(
                baseline = request.baseline.copy(
                    regions = request.baseline.regions.map { it.copy(style = OverlayStyle(fontWeight = 700)) },
                ),
            ),
        )
        for (changed in mismatches) fails { renderer.prepare(changed) }
        assertEquals(evidence, renderer.prepare(request))
        assertEquals(request.image.contentHash, hash(File(request.image.filePath)))
    }

    @Test
    fun duplicatePreparationCreatesOneCheckpointAndCleanupPreservesUnrelatedFiles() = fixture("duplicate") {
        val request = request()
        val results = coroutineScope {
            listOf(async { renderer.prepare(request) }, async { renderer.prepare(request) }).awaitAll()
        }
        assertEquals(results.first(), results.last())
        val unrelated = File(root, "keep.txt").apply { writeText("Unrelated fixture content") }
        val siblingRequest = this.request(regions = false)
        val sibling = renderer.prepare(siblingRequest)
        val siblingHash = hash(File(sibling.preview.filePath))
        val evidence = results.first()
        renderer.cleanup(evidence)
        renderer.cleanup(evidence)
        assertFalse(evidenceDirectory(request).exists())
        assertEquals("Unrelated fixture content", unrelated.readText())
        assertEquals(siblingHash, hash(File(sibling.preview.filePath)))
        val forged = evidence.copy(preview = evidence.preview.copy(filePath = unrelated.path))
        fails { renderer.cleanup(forged) }
        assertEquals("Unrelated fixture content", unrelated.readText())
        assertEquals(request.image.contentHash, hash(File(request.image.filePath)))
        renderer.cleanup(sibling)
    }

    @Test
    fun failedAndPreCancelledPreparationLeavesNoPartialEvidence() = fixture("interruption") {
        val request = request()
        val sourceHash = hash(File(request.image.filePath))
        fails { renderer.prepare(request.copy(image = request.image.copy(contentHash = "0".repeat(64)))) }
        assertFalse(evidenceDirectory(request).exists())
        val cancelled = Job().apply { cancel() }
        val error = fails { withContext(cancelled) { renderer.prepare(request) } }
        assertTrue(error is CancellationException)
        assertFalse(evidenceDirectory(request).exists())
        assertEquals(sourceHash, hash(File(request.image.filePath)))
        val evidence = renderer.prepare(request)
        renderer.cleanup(evidence)
        receipt.put("cancellation_scope", "Cancelled before preparation; no claim of mid-decode interruption")
    }

    private fun fixture(name: String, block: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        val fixture = Fixture(name)
        try {
            fixture.block()
            fixture.receipt.put("status", "passed")
        } finally {
            fixture.root.deleteRecursively()
            fixture.receipt.put("owned_directory_removed", !fixture.root.exists())
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply {
                    putString("quality_render_evidence_report_json", fixture.receipt.toString())
                },
            )
        }
    }

    private class Fixture(name: String) {
        private val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "review-evidence-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context: Context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { check(isDirectory || mkdirs()) }
        }
        val renderer = AndroidQualityReviewRenderer(context)
        val receipt = JSONObject().put("case", name).put("status", "incomplete")
            .put("provider_dispatches", 0).put("assessment", "DETERMINISTIC_ANDROID_RENDER_EVIDENCE")

        fun evidenceDirectory(request: QualityReviewRequest) = File(
            context.filesDir,
            "translation/review-evidence/${request.reviewId}",
        )

        fun request(
            width: Int = 320,
            height: Int = 160,
            style: OverlayStyle = OverlayStyle(),
            regions: Boolean = true,
        ): QualityReviewRequest {
            val file = File(root, "source-${UUID.randomUUID()}.png")
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.rgb(224, 232, 240))
                if (regions) {
                    Canvas(bitmap).drawText(
                        "Original",
                        30f,
                        65f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.BLACK
                            textSize = 22f
                        },
                    )
                }
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            val image = TranslationImage("page", 0, file.path, "image/png", width, height, hash(file), file.length())
            val passages = if (regions) {
                listOf(
                    TextRegion(
                        "speech",
                        listOf(
                            TranslationPoint(20f, 20f),
                            TranslationPoint(300f, 20f),
                            TranslationPoint(300f, 100f),
                            TranslationPoint(20f, 100f),
                        ),
                        sourceText = "Raw source",
                        translatedText = "Keep the door closed",
                        correctedText = "Corrected source",
                        detectionConfidence = 0.91f,
                        recognitionConfidence = 0.87f,
                    ),
                )
            } else {
                emptyList()
            }
            return QualityReviewRequest(
                "synthetic-render-evidence",
                UUID.randomUUID().toString(),
                TranslationSettings(style = style),
                image,
                TranslationPageResult(
                    image.id,
                    image.contentHash,
                    width,
                    height,
                    passages,
                    rawOcr = OcrPageResult(image.id, passages, rawJson = "{\"synthetic\":true}"),
                    revision = 42,
                ),
            )
        }

        fun importedFont(name: String): File {
            val systemFont = File("/system/fonts").listFiles().orEmpty().filter {
                it.isFile && it.extension.lowercase() in setOf("ttf", "otf") && it.length() in 1..2_000_000
            }.sortedBy { it.name }.firstOrNull {
                runCatching { Typeface.createFromFile(it) }.isSuccess
            }
            checkNotNull(systemFont) { "No readable Android system font is available for the imported-font fixture" }
            return File(root, name).also { systemFont.copyTo(it) }
        }
    }

    private companion object {
        suspend fun fails(block: suspend () -> Unit): Exception {
            try {
                block()
            } catch (error: Exception) {
                return error
            }
            throw AssertionError("Expected the renderer to reject unavailable or inconsistent evidence")
        }

        fun hash(file: File): String = file.inputStream().use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(65536)
            while (true) {
                val count = stream.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
