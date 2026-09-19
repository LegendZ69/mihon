package mihon.core.archive

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The public archive stream must behave like InputStream for native and managed decoders alike. */
@RunWith(AndroidJUnit4::class)
class ArchiveReaderInputStreamTest {
    @Test
    fun readsRespectOffsetLengthAndSentinelsWithoutSkippingSourceBytes() = withArchive { reader ->
        checkNotNull(reader.getInputStream("source.bin")).use { input ->
            val target = ByteArray(32) { 0x55 }
            assertEquals("The decoder requested exactly three bytes", 3, input.read(target, 7, 3))
            assertArrayEquals(ByteArray(7) { 0x55 }, target.copyOfRange(0, 7))
            assertArrayEquals(byteArrayOf(0, 1, 2), target.copyOfRange(7, 10))
            assertArrayEquals(ByteArray(22) { 0x55 }, target.copyOfRange(10, 32))
            assertEquals("A short read must not consume the following bytes", 3, input.read())
            assertEquals(4, input.read(target, 2, 4))
            assertArrayEquals(byteArrayOf(4, 5, 6, 7), target.copyOfRange(2, 6))
            assertArrayEquals((8 until 64).map(Int::toByte).toByteArray(), input.readBytes())
            assertEquals(-1, input.read(target, 9, 5))
        }
    }

    @Test
    fun zeroLengthAndInvalidRangesNeverConsumeTheArchiveEntry() = withArchive { reader ->
        checkNotNull(reader.getInputStream("source.bin")).use { input ->
            val target = ByteArray(8) { 0x55 }
            assertEquals(0, input.read(target, 4, 0))
            assertEquals(0, input.read(target, target.size, 0))
            assertArrayEquals(ByteArray(8) { 0x55 }, target)
            listOf(-1 to 1, 0 to -1, 7 to 2, 9 to 0).forEach { (offset, count) ->
                val error = runCatching { input.read(target, offset, count) }.exceptionOrNull()
                assertTrue(
                    "Invalid range must retain InputStream's bounds contract",
                    error is IndexOutOfBoundsException,
                )
            }
            assertArrayEquals((0 until 64).map(Int::toByte).toByteArray(), input.readBytes())
            assertEquals(0, input.read(target, target.size, 0))
            assertEquals(-1, input.read())
        }
    }

    private fun withArchive(block: (ArchiveReader) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "archive-stream-${UUID.randomUUID()}.zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("source.bin"))
                zip.write((0 until 64).map(Int::toByte).toByteArray())
                zip.closeEntry()
            }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                ArchiveReader(descriptor).use(block)
            }
        } finally {
            check(!file.exists() || file.delete()) { "Cannot remove owned archive stream fixture" }
        }
    }
}
