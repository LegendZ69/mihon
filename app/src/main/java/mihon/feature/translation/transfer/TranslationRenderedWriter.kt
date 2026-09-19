package mihon.feature.translation.transfer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import tachiyomi.domain.translation.model.TranslationPdfPageSize
import tachiyomi.domain.translation.model.TranslationRenderedFormat
import tachiyomi.domain.translation.model.TranslationTransferReport
import java.io.Closeable
import java.io.DataOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pixels are premultiplied by the platform only internally; this boundary exposes straight ARGB. */
interface TranslationPageRaster : Closeable {
    val width: Int
    val height: Int
    suspend fun rows(top: Int, count: Int, consume: suspend (pixels: IntArray, rows: Int) -> Unit)
}

data class TranslationRenderedPage(
    val id: String,
    val index: Int,
    val contentComplete: Boolean = true,
    val open: suspend () -> TranslationPageRaster,
)
class MissingTranslationOriginalException(message: String) : IOException(message)

/** Streaming CBZ/PDF boundary, consuming each original at its full resolution one bounded band at a time. */
class TranslationRenderedWriter {
    suspend fun write(
        pages: Flow<TranslationRenderedPage>,
        expectedPages: Int,
        format: TranslationRenderedFormat,
        destination: OutputStream,
        paper: TranslationPdfPageSize = TranslationPdfPageSize.A4,
        completeChapter: Boolean = true,
        progress: suspend (completed: Int, expected: Int) -> Unit = { _, _ -> },
    ): TranslationTransferReport {
        if (format ==
            TranslationRenderedFormat.PDF
        ) {
            return writePdf(pages, expectedPages, destination, paper, completeChapter, progress)
        }
        require(expectedPages >= 0)
        val output = CountedExportOutput(destination)
        val missing = mutableListOf<String>()
        val untranslated = mutableListOf<String>()
        var written = 0
        var previousIndex = -1
        ZipOutputStream(output).use { zip ->
            pages.collect { page ->
                currentCoroutineContext().ensureActive()
                require(page.index > previousIndex) { "Export pages must have unique, ascending source indices" }
                previousIndex = page.index
                if (!page.contentComplete) untranslated += page.id
                val raster = try {
                    page.open()
                } catch (
                    error: MissingTranslationOriginalException,
                ) {
                    missing += page.id
                    null
                }
                raster?.use {
                    require(it.width in 1..65536 && it.height > 0) { "Invalid export image dimensions" }
                    zip.putNextEntry(ZipEntry("${(page.index + 1).toString().padStart(6, '0')}.png"))
                    writePng(it, zip)
                    zip.closeEntry()
                    written++
                    progress(written, expectedPages)
                }
            }
            zip.putNextEntry(ZipEntry("export.json"))
            zip.write(
                Json.encodeToString(
                    mapOf(
                        "complete" to
                            (
                                completeChapter && expectedPages > 0 && written == expectedPages && missing.isEmpty() &&
                                    untranslated.isEmpty()
                                ).toString(),
                        "sourcePagesWritten" to written.toString(),
                        "sourcePagesExpected" to expectedPages.toString(),
                        "missingOriginalIds" to missing.joinToString(","),
                    ),
                ).toByteArray(),
            )
            zip.closeEntry()
        }
        return TranslationTransferReport(
            completeChapter && expectedPages > 0 && written == expectedPages && missing.isEmpty() &&
                untranslated.isEmpty(),
            written,
            expectedPages,
            output.bytes,
            missing,
            incompleteWarnings(completeChapter, untranslated),
        )
    }

    private suspend fun writePdf(
        pages: Flow<TranslationRenderedPage>,
        expectedPages: Int,
        destination: OutputStream,
        paper: TranslationPdfPageSize,
        completeChapter: Boolean,
        progress: suspend (Int, Int) -> Unit,
    ): TranslationTransferReport {
        require(expectedPages >= 0)
        val output = CountedExportOutput(destination)
        val missing = mutableListOf<String>()
        val untranslated = mutableListOf<String>()
        var written = 0
        var previousIndex = -1
        val pdf = StreamingTranslationPdf(output, paper)
        output.use {
            pages.collect { page ->
                currentCoroutineContext().ensureActive()
                require(page.index > previousIndex) { "Export pages must have unique, ascending source indices" }
                previousIndex = page.index
                if (!page.contentComplete) untranslated += page.id
                val raster = try {
                    page.open()
                } catch (
                    error: MissingTranslationOriginalException,
                ) {
                    missing += page.id
                    null
                }
                raster?.use {
                    require(it.width in 1..65536 && it.height > 0) { "Invalid export image dimensions" }
                    pdf.page(it)
                    written++
                    progress(written, expectedPages)
                }
            }
            pdf.finish()
        }
        return TranslationTransferReport(
            completeChapter && expectedPages > 0 && written == expectedPages && missing.isEmpty() &&
                untranslated.isEmpty(),
            written,
            expectedPages,
            output.bytes,
            missing,
            incompleteWarnings(completeChapter, untranslated) +
                "PDF contains ${pdf.sheets} sheets from $written source pages",
        )
    }

    private fun incompleteWarnings(completeChapter: Boolean, untranslated: List<String>): List<String> = buildList {
        if (!completeChapter) add("Chapter page total is unavailable; completeness cannot be established.")
        if (untranslated.isNotEmpty()) {
            add(
                "Originals retained without saved translation for pages: ${untranslated.joinToString()}",
            )
        }
    }

    /** PNG 3: one zlib datastream split into bounded consecutive IDAT chunks, with unfiltered RGBA rows. */
    private suspend fun writePng(raster: TranslationPageRaster, destination: OutputStream) {
        val output = DataOutputStream(destination)
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteBuffer.allocate(13).putInt(raster.width).putInt(raster.height)
            .put(8).put(6).put(0).put(0).put(0).array()
        chunk(output, "IHDR", header, header.size)
        val idat = object : OutputStream() {
            val buffer = ByteArray(8192)
            var used = 0
            override fun write(value: Int) {
                buffer[used++] = value.toByte()
                if (used == buffer.size) flush()
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                var cursor = offset
                var remaining = length
                while (remaining > 0) {
                    val count = minOf(remaining, buffer.size - used)
                    bytes.copyInto(buffer, used, cursor, cursor + count)
                    cursor += count
                    remaining -= count
                    used += count
                    if (used == buffer.size) flush()
                }
            }
            override fun flush() {
                if (used > 0) {
                    chunk(output, "IDAT", buffer, used)
                    used = 0
                }
            }
        }
        val deflater = Deflater()
        try {
            val compressed = DeflaterOutputStream(idat, deflater, 8192)
            val row = ByteArray(raster.width * 4 + 1)
            var rowsWritten = 0
            raster.rows(0, raster.height) { pixels, rows ->
                currentCoroutineContext().ensureActive()
                require(
                    rows > 0 && rows <= raster.height - rowsWritten &&
                        pixels.size.toLong() == raster.width.toLong() * rows &&
                        pixels.size <= 2 * 1024 * 1024,
                ) { "Invalid or oversized raster band" }
                repeat(rows) { y ->
                    currentCoroutineContext().ensureActive()
                    row[0] = 0
                    for (x in 0 until raster.width) {
                        val value = pixels[y * raster.width + x]
                        val offset = x * 4 + 1
                        row[offset] = (value ushr 16).toByte()
                        row[offset + 1] = (value ushr 8).toByte()
                        row[offset + 2] = value.toByte()
                        row[offset + 3] = (value ushr 24).toByte()
                    }
                    compressed.write(row)
                }
                rowsWritten += rows
            }
            require(rowsWritten == raster.height) { "Raster omitted source rows" }
            compressed.finish()
            idat.flush()
            chunk(output, "IEND", byteArrayOf(), 0)
        } finally {
            deflater.end()
        }
    }

    private fun chunk(output: DataOutputStream, name: String, bytes: ByteArray, count: Int) {
        val type = name.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(type)
            update(bytes, 0, count)
        }
        output.writeInt(count)
        output.write(type)
        output.write(bytes, 0, count)
        output.writeInt(crc.value.toInt())
    }
}

internal class CountedExportOutput(output: OutputStream) : FilterOutputStream(output) {
    var bytes = 0L
        private set
    override fun write(value: Int) {
        out.write(value)
        bytes++
    }
    override fun write(value: ByteArray, offset: Int, length: Int) {
        out.write(value, offset, length)
        bytes += length
    }
}
