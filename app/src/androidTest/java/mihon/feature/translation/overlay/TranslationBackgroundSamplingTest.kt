package mihon.feature.translation.overlay

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import mihon.core.archive.ArchiveReader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.TranslationPageResult
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Exact synthetic corpus originals and saved corners; no provider, settings, or database mutations. */
@RunWith(AndroidJUnit4::class)
class TranslationBackgroundSamplingTest {
    @Test
    fun capturedPngPagesKeepCornerColorsAndSavedResultsAcrossRepeatedSampling() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assertRepeatedSampling("asset") { index ->
            assets.open("translation/sampling/controlled-page-${(index + 1).toString().padStart(2, '0')}.png")
        }
    }

    @Test
    fun exactLocalCbzStreamsKeepCornerColorsAndSavedResultsAcrossRepeatedSampling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val manifest =
            JSONObject(assets.open("translation/sampling/provenance.json").bufferedReader().use { it.readText() })
        val bytes = assets.open("translation/sampling/${manifest.getString("reader_cbz_asset")}")
            .use { it.readBytes() }
        assertEquals(manifest.getString("reader_cbz_sha256"), sha256(bytes))
        val file = File(instrumentation.targetContext.cacheDir, "sampling-cbz-${UUID.randomUUID()}.cbz")
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                ArchiveReader(descriptor).use { reader ->
                    assertRepeatedSampling("archive_reader") { index ->
                        checkNotNull(reader.getInputStream("${(index + 1).toString().padStart(3, '0')}.png"))
                    }
                }
            }
        } finally {
            check(!file.exists() || file.delete()) { "Cannot remove owned sampling archive" }
        }
    }

    private fun assertRepeatedSampling(sourceKind: String, openSource: (Int) -> InputStream) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val json = Json { ignoreUnknownKeys = true }
        val manifest =
            JSONObject(assets.open("translation/sampling/provenance.json").bufferedReader().use { it.readText() })
        val cases = manifest.getJSONArray("cases")
        // Begin with the exact page open during the two observed K90 native reader crashes.
        val order = listOf(6) + (0 until cases.length()).filter { it != 6 }
        repeat(3) { repetition ->
            order.forEach { index ->
                val case = cases.getJSONObject(index)
                val asset = "translation/sampling/${case.getString("asset")}"
                val result = json.decodeFromString<TranslationPageResult>(
                    assets.open("$asset.json").bufferedReader().use { it.readText() },
                )
                val hash = openSource(index).use { sha256(it.readBytes()) }
                assertEquals(case.getString("image_sha256"), hash)
                assertEquals(result.imageHash, hash)
                val before = json.encodeToString(result)
                val receipt = JSONObject().put("image_id", result.imageId)
                    .put("source_kind", sourceKind)
                    .put("source_sha256", hash).put("width", result.width).put("height", result.height)
                    .put("revision", result.revision).put("repetition", repetition)
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("sampling_begin", receipt.toString())
                    },
                )
                val sampled = sampleTranslationBackgrounds(
                    { openSource(index) },
                    result,
                    OverlayStyle(backgroundOpacity = 0.85f, sampleBackground = false),
                )
                val expected = case.getJSONObject("expected_corner_colors")
                assertEquals(expected.keys().asSequence().toSet(), sampled.keys)
                expected.keys().forEach { id ->
                    assertEquals(
                        "${case.getString("asset")} $id",
                        expected.getString(id).toLong(16).toInt(),
                        sampled[id],
                    )
                }
                assertEquals("Sampling never rewrites the saved result", before, json.encodeToString(result))
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("sampling_complete", receipt.put("region_count", sampled.size).toString())
                    },
                )
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
