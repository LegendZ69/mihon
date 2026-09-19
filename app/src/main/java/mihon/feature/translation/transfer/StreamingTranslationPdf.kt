package mihon.feature.translation.transfer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.domain.translation.model.TranslationPdfPageSize
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.floor

/** PDF 1.7 raster streams with indirect lengths; no full chapter bitmap/PDF page list is retained. */
internal class StreamingTranslationPdf(
    private val output: CountedExportOutput,
    private val paper: TranslationPdfPageSize,
) {
    private val offsets = mutableListOf(0L)
    private val catalog = reserve()
    private val pages = reserve()
    private val pageIds = mutableListOf<Int>()
    val sheets get() = pageIds.size

    init {
        ascii("%PDF-1.7\n%")
        output.write(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte()))
        ascii("\n")
        objectValue(catalog, "<< /Type /Catalog /Pages $pages 0 R >>")
    }

    suspend fun page(raster: TranslationPageRaster) {
        // Ordinary pages fit proportionally. Strips over twice the paper aspect split at exact source rows.
        val strip = raster.height.toDouble() / raster.width > 2.0 * paper.heightPoints / paper.widthPoints
        val maximumRows = if (strip) {
            floor(
                raster.width.toDouble() * paper.heightPoints / paper.widthPoints,
            ).toInt().coerceAtLeast(1)
        } else {
            raster.height
        }
        var top = 0
        while (top < raster.height) {
            currentCoroutineContext().ensureActive()
            require(pageIds.size < 100_000) { "PDF exceeds the application sheet limit" }
            val height = minOf(maximumRows, raster.height - top)
            val page = reserve()
            val content = reserve()
            val image = reserve()
            val length = reserve()
            pageIds += page
            val scale = minOf(paper.widthPoints.toDouble() / raster.width, paper.heightPoints.toDouble() / height)
            val widthPoints = raster.width * scale
            val heightPoints = height * scale
            val left = (paper.widthPoints - widthPoints) / 2.0
            val bottom = paper.heightPoints - heightPoints
            objectValue(
                page,
                "<< /Type /Page /Parent $pages 0 R /MediaBox [0 0 " +
                    "${paper.widthPoints} ${paper.heightPoints}] /Resources << " +
                    "/XObject << /Im0 $image 0 R >> >> /Contents $content 0 R >>",
            )
            val drawing = "q\n${decimal(
                widthPoints,
            )} 0 0 ${decimal(heightPoints)} ${decimal(left)} ${decimal(bottom)} cm\n/Im0 Do\nQ\n"
            objectValue(
                content,
                "<< /Length ${drawing.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${drawing}endstream",
            )
            beginObject(image)
            ascii(
                "<< /Type /XObject /Subtype /Image /Width ${raster.width} /Height $height /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length $length 0 R >>\nstream\n",
            )
            val start = output.bytes
            val deflater = Deflater()
            try {
                val compressed = DeflaterOutputStream(output, deflater, 8192)
                val row = ByteArray(raster.width * 3)
                var received = 0
                raster.rows(top, height) { pixels, rows ->
                    currentCoroutineContext().ensureActive()
                    require(
                        rows > 0 && rows <= height - received && pixels.size.toLong() == raster.width.toLong() * rows &&
                            pixels.size <= 2 * 1024 * 1024,
                    ) { "Invalid or oversized PDF raster band" }
                    repeat(rows) { y ->
                        currentCoroutineContext().ensureActive()
                        for (x in 0 until raster.width) {
                            val pixel = pixels[y * raster.width + x]
                            val alpha = pixel ushr 24
                            val offset = x * 3
                            row[offset] = flatten(pixel ushr 16 and 255, alpha)
                            row[offset + 1] = flatten(pixel ushr 8 and 255, alpha)
                            row[offset + 2] = flatten(pixel and 255, alpha)
                        }
                        compressed.write(row)
                    }
                    received += rows
                }
                require(received == height) { "PDF raster omitted source rows" }
                compressed.finish()
            } finally {
                deflater.end()
            }
            val streamLength = output.bytes - start
            ascii("\nendstream\nendobj\n")
            objectValue(length, streamLength.toString())
            top += height
        }
    }

    fun finish() {
        objectValue(
            pages,
            "<< /Type /Pages /Count ${pageIds.size} /Kids [${pageIds.joinToString(" ") {
                "$it 0 R"
            }}] >>",
        )
        val start = output.bytes
        ascii("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        offsets.drop(1).forEach { offset ->
            require(offset in 1 until 10_000_000_000L) { "PDF cross-reference offset exceeds format limit" }
            ascii("${offset.toString().padStart(10, '0')} 00000 n \n")
        }
        ascii("trailer\n<< /Size ${offsets.size} /Root $catalog 0 R >>\nstartxref\n$start\n%%EOF\n")
        output.flush()
    }

    private fun flatten(channel: Int, alpha: Int) = ((channel * alpha + 255 * (255 - alpha) + 127) / 255).toByte()
    private fun decimal(value: Double) = String.format(Locale.US, "%.4f", value)
    private fun reserve(): Int {
        offsets += 0L
        return offsets.lastIndex
    }
    private fun beginObject(id: Int) {
        offsets[id] = output.bytes
        ascii("$id 0 obj\n")
    }
    private fun objectValue(id: Int, value: String) {
        beginObject(id)
        ascii("$value\nendobj\n")
    }
    private fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
}
