package mihon.feature.translation.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationSettings
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImagePreparationInstrumentedTest {
    @Test
    fun oversizedLosslessSourceIsAutomaticallyTiledWithinOfficialLimit() = runBlocking {
        withContext(Dispatchers.IO) {
            val directory = temporaryDirectory()
            try {
                val source = File(directory, "noise.png")
                val bitmap = Bitmap.createBitmap(2000, 2000, Bitmap.Config.ARGB_8888)
                try {
                    val row = IntArray(bitmap.width)
                    for (y in 0 until bitmap.height) {
                        for (x in row.indices) row[x] = noise(x + y * bitmap.width)
                        bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
                    }
                    source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    bitmap.recycle()
                }
                assertTrue("Fixture must exceed Vertex's 7 MB inline limit", source.length() > 7_000_000)
                val request = request(source, 2000, 2000)
                val prepared = ImagePreparation(
                    File(directory, "prepared"),
                ).prepare(request, OfficialProviderCapabilities.forSettings(request.settings.provider))
                assertTrue(prepared.tiles.size > 1)
                prepared.tiles.forEach { tile ->
                    assertTrue(tile.image.byteSize <= 7_000_000)
                    assertTrue(tile.image.width.toLong() * tile.image.height <= 2_000_000)
                    val decoded = BitmapFactory.decodeFile(tile.image.filePath)
                    try {
                        val x = decoded.width / 2
                        val y = decoded.height / 2
                        assertEquals(
                            noise(tile.rectangle.left + x + (tile.rectangle.top + y) * 2000),
                            decoded.getPixel(x, y),
                        )
                    } finally {
                        decoded.recycle()
                    }
                }
                assertEquals(2000, prepared.tiles.maxOf { it.rectangle.bottom })
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun uploadCopiesRemoveExifOrientationAndPreserveRawSourcePixelCoordinates() = runBlocking {
        withContext(Dispatchers.IO) {
            val directory = temporaryDirectory()
            try {
                val source = File(directory, "oriented.jpg")
                val bitmap = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.RED)
                    Canvas(bitmap).drawRect(64f, 0f, 128f, 64f, Paint().apply { color = Color.GREEN })
                    source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
                } finally {
                    bitmap.recycle()
                }
                ExifInterface(source.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                    saveAttributes()
                }
                val request = request(source, 128, 64).let {
                    it.copy(images = it.images.map { image -> image.copy(mimeType = "image/jpeg") })
                }
                val prepared = ImagePreparation(
                    File(directory, "prepared"),
                ).prepare(request, OfficialProviderCapabilities.forSettings(request.settings.provider))
                val tile = prepared.tiles.single()
                assertEquals(128, tile.image.width)
                assertEquals(64, tile.image.height)
                val original = BitmapFactory.decodeFile(source.absolutePath)
                val uploaded = BitmapFactory.decodeFile(tile.image.filePath)
                try {
                    assertEquals(original.getPixel(10, 20), uploaded.getPixel(10, 20))
                    assertEquals(original.getPixel(100, 20), uploaded.getPixel(100, 20))
                    val orientation = ExifInterface(tile.image.filePath).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_UNDEFINED,
                    )
                    assertTrue(
                        orientation in setOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL),
                    )
                    assertEquals(
                        ExifInterface.ORIENTATION_ROTATE_90,
                        ExifInterface(source.absolutePath).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_UNDEFINED,
                        ),
                    )
                } finally {
                    original.recycle()
                    uploaded.recycle()
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun temporaryDirectory() = File(
        ApplicationProvider.getApplicationContext<Context>().cacheDir,
        "image-preparation-test-${UUID.randomUUID()}",
    ).apply {
        mkdirs()
    }
    private fun request(
        source: File,
        width: Int,
        height: Int,
    ) = TranslationRequest(
        "test",
        "batch",
        TranslationSettings(),
        listOf(
            TranslationImage(
                "source",
                0,
                source.absolutePath,
                "image/png",
                width,
                height,
                UUID.randomUUID().toString(),
                source.length(),
            ),
        ),
    )
    private fun noise(seed: Int): Int {
        var value = seed * 1664525 + 1013904223
        value = (value xor (value ushr 16)) * -2048144789
        value = (value xor (value ushr 13)) * -1028477387
        return (value xor (value ushr 16)) or -0x1000000
    }
}
