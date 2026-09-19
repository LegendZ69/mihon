package mihon.feature.translation.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.feature.translation.provider.OfficialProviderCapabilities
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.QualityReviewPresentation
import tachiyomi.domain.translation.model.QualityReviewRenderEvidence
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.service.QualityReviewRenderer
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** One bounded mutable preview bitmap. Original bytes and fonts are privately pinned before dispatch. */
class AndroidQualityReviewRenderer(
    private val context: Context,
    private val presentationContext: () -> Context = { context },
) : QualityReviewRenderer {
    private val root = File(context.filesDir, "translation/review-evidence")
    private val permit = Semaphore(1)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override suspend fun prepare(request: QualityReviewRequest): QualityReviewRenderEvidence = permit.withPermit {
        withContext(Dispatchers.IO) {
            require(request.reviewId.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "Invalid review identity" }
            require(
                request.baseline.imageId == request.image.id &&
                    request.baseline.imageHash == request.image.contentHash &&
                    request.baseline.width == request.image.width && request.baseline.height == request.image.height,
            ) { "Review baseline does not match the complete original image" }
            val directory = File(root, request.reviewId)
            val manifest = File(directory, "evidence.json")
            if (manifest.isFile) {
                val prior = json.decodeFromString<QualityReviewRenderEvidence>(manifest.readText())
                require(
                    prior.sourceRevision == request.baseline.revision &&
                        prior.original.copy(filePath = request.image.filePath) == request.image &&
                        prior.presentation.requestedStyle == request.settings.style &&
                        prior.presentation.contentPolicy == request.settings.contentPolicy &&
                        prior.presentation.requestedRegionStyles ==
                        request.baseline.regions.associate { it.id to it.style },
                ) { "Prepared review belongs to a different presentation" }
                require(
                    File(prior.original.filePath).canonicalFile == File(directory, "original.bin").canonicalFile &&
                        File(prior.preview.filePath).canonicalFile == File(directory, "preview.png").canonicalFile,
                ) { "Prepared review files do not belong to this checkpoint" }
                verifyFile(File(prior.original.filePath), prior.original.contentHash, prior.original.byteSize)
                verifyFile(File(prior.preview.filePath), prior.preview.contentHash, prior.preview.byteSize)
                verifyPinnedFonts(directory, prior.presentation)
                return@withContext prior
            }
            directory.deleteRecursively()
            check(directory.mkdirs()) { "Cannot create private review evidence" }
            try {
                val limit = OfficialProviderCapabilities.forSettings(request.settings.provider).maxInlineImageBytes
                val original = File(directory, "original.bin")
                copyVerified(
                    File(request.image.filePath),
                    original,
                    request.image.contentHash,
                    request.image.byteSize,
                    limit,
                )
                val orientation = ExifInterface(original).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED,
                )
                if (orientation !in setOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL)) {
                    incomplete("Rendered review cannot normalize an EXIF-rotated original")
                }
                val baseStyle = request.settings.style
                val themedContext = presentationContext()
                val textColor = MaterialColors.getColor(
                    themedContext,
                    com.google.android.material.R.attr.colorOnSurface,
                    Color.BLACK,
                )
                val background = MaterialColors.getColor(
                    themedContext,
                    com.google.android.material.R.attr.colorSurface,
                    Color.WHITE,
                )
                val fonts = linkedMapOf<String, String>()
                val fontCopies = mutableMapOf<String, String>()
                fun pinned(style: OverlayStyle): OverlayStyle {
                    val font = style.fontPath?.let { path ->
                        fontCopies.getOrPut(path) {
                            val source = File(path)
                            require(source.isFile && source.length() in 1..MAX_FONT_BYTES) {
                                "Imported font unavailable for review"
                            }
                            val hash = digest(source)
                            val target = File(directory, "font-$hash")
                            if (!target.exists()) source.copyTo(target, overwrite = false)
                            verifyFile(target, hash, source.length())
                            verifyLoadableFont(target)
                            fonts[path] = "sha256:$hash"
                            target.path
                        }
                    }
                    if (font == null) fonts[fontKey(style)] = systemFontIdentity(style)
                    return style.copy(
                        fontPath = font,
                        showBoxes = false,
                        showCoordinates = false,
                        showConfidence = false,
                        showReadingOrder = false,
                        showIgnored = false,
                    )
                }
                // Keep automatic color intent while drawing. Resolving null text into a theme color
                // here would make the shared renderer treat it as an explicit custom foreground.
                val pinnedStyle = pinned(baseStyle)
                val pinnedRegions = request.baseline.regions.associate { it.id to pinned(it.style ?: baseStyle) }
                val sampled = if (needsTranslationBackgroundSamples(request.baseline, baseStyle)) {
                    sampleTranslationBackgrounds({ original.inputStream() }, request.baseline, baseStyle)
                } else {
                    emptyMap()
                }
                val renderedResult = request.baseline.copy(
                    regions = request.baseline.regions.map {
                        it.copy(style = pinnedRegions.getValue(it.id))
                    },
                )
                val document = withContext(Dispatchers.Default) {
                    TranslationOverlayDocument.create(
                        themedContext,
                        renderedResult,
                        pinnedStyle,
                        sampled,
                        request.settings.contentPolicy,
                    )
                }
                val resolvedStyle = TranslationOverlayDocument.resolveColors(pinnedStyle, textColor, background)
                    .applyTo(pinnedStyle)
                val resolvedRegions = pinnedRegions.mapValues { (id, style) ->
                    TranslationOverlayDocument.resolveColors(style, textColor, background, sampled[id]).applyTo(style)
                }
                val fingerprint = fingerprint(
                    request.baseline,
                    baseStyle,
                    textColor,
                    background,
                    fonts,
                    request.settings.contentPolicy,
                )
                val preview = File(directory, "preview.png")
                val bitmap = decode(original, request.image.width, request.image.height)
                val width = bitmap.width
                val height = bitmap.height
                try {
                    coroutineContext.ensureActive()
                    Canvas(bitmap).apply {
                        scale(width.toFloat() / request.image.width, height.toFloat() / request.image.height)
                        document.draw(this)
                    }
                    preview.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    bitmap.recycle()
                }
                if (limit > 0 &&
                    preview.length() > limit
                ) {
                    incomplete("Rendered preview exceeds the provider image limit")
                }
                val evidence = QualityReviewRenderEvidence(
                    original = request.image.copy(filePath = original.path),
                    preview = request.image.copy(
                        id = "${request.image.id}-rendered-preview",
                        filePath = preview.path,
                        mimeType = "image/png",
                        width = width,
                        height = height,
                        contentHash = digest(preview),
                        byteSize = preview.length(),
                    ),
                    sourceRevision = request.baseline.revision,
                    rendererVersion = VERSION,
                    presentationFingerprint = fingerprint,
                    scaleX = width.toDouble() / request.image.width,
                    scaleY = height.toDouble() / request.image.height,
                    presentation = QualityReviewPresentation(
                        baseStyle,
                        resolvedStyle,
                        resolvedRegions,
                        fonts,
                        sampled,
                        document.layoutDiagnostics,
                        requestedRegionStyles = request.baseline.regions.associate { it.id to it.style },
                        contentPolicy = request.settings.contentPolicy,
                    ),
                )
                val temporary = File(directory, "evidence.part")
                temporary.writeText(json.encodeToString(evidence))
                check(temporary.renameTo(manifest)) { "Cannot checkpoint render evidence" }
                evidence
            } catch (error: CancellationException) {
                directory.deleteRecursively()
                throw error
            } catch (error: Exception) {
                directory.deleteRecursively()
                if (error is TranslationException) throw error
                incomplete(error.message ?: "Rendered preview unavailable")
            }
        }
    }

    override suspend fun cleanup(evidence: QualityReviewRenderEvidence) = withContext(Dispatchers.IO) {
        val directory = File(evidence.preview.filePath).canonicalFile.parentFile ?: return@withContext
        require(directory.parentFile == root.canonicalFile) { "Refusing unrelated evidence cleanup" }
        check(!directory.exists() || directory.deleteRecursively()) { "Evidence cleanup incomplete" }
    }

    /** Called only while the Manager owns queue execution; pending snapshots survive interruption. */
    suspend fun cleanupExcept(pendingReviewIds: Set<String>) = permit.withPermit {
        withContext(Dispatchers.IO) {
            root.listFiles().orEmpty().filter { it.name !in pendingReviewIds }.forEach { directory ->
                if (directory.isDirectory && directory.name.matches(Regex("[A-Za-z0-9-]{1,100}")) &&
                    directory.canonicalFile.parentFile == root.canonicalFile
                ) {
                    check(directory.deleteRecursively()) { "Review evidence cleanup deferred" }
                }
            }
        }
    }

    /** Used by inspection only; a changed presentation never schedules paid work. */
    suspend fun presentationFingerprint(
        result: TranslationPageResult,
        style: OverlayStyle,
        contentPolicy: TranslationContentPolicy = TranslationContentPolicy(),
    ): String = withContext(Dispatchers.IO) {
        val themedContext = presentationContext()
        val fonts = linkedMapOf<String, String>()
        (listOf(style) + result.regions.mapNotNull { it.style }).forEach { chosen ->
            val path = chosen.fontPath
            if (path != null) {
                fonts[path] = File(path).takeIf { it.isFile }?.let { "sha256:${digest(it)}" } ?: "unavailable"
            } else {
                fonts[fontKey(chosen)] = systemFontIdentity(chosen)
            }
        }
        fingerprint(
            result,
            style,
            MaterialColors.getColor(themedContext, com.google.android.material.R.attr.colorOnSurface, Color.BLACK),
            MaterialColors.getColor(themedContext, com.google.android.material.R.attr.colorSurface, Color.WHITE),
            fonts,
            contentPolicy,
        )
    }

    private fun fingerprint(
        result: TranslationPageResult,
        style: OverlayStyle,
        text: Int,
        background: Int,
        fonts: Map<String, String>,
        contentPolicy: TranslationContentPolicy,
    ): String {
        val regionStyles: Map<String, OverlayStyle?> = result.regions.associate { it.id to it.style }.toSortedMap()
        val sortedFonts: Map<String, String> = fonts.toSortedMap()
        return digest(
            (
                VERSION + "\n" + json.encodeToString(style) + "\n" + json.encodeToString(regionStyles) + "\n" +
                    "$text/$background\n" + json.encodeToString(sortedFonts) + "\n" +
                    json.encodeToString(contentPolicy)
                ).toByteArray(),
        )
    }

    private fun decode(file: File, width: Int, height: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth != width ||
            bounds.outHeight != height
        ) {
            incomplete("Original image dimensions cannot be verified")
        }
        var sample = 1
        while (((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample) > MAX_PIXELS ||
            (width.toLong() + sample - 1) / sample > 4096 || (height.toLong() + sample - 1) / sample > 4096
        ) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            },
        ) ?: incomplete("Original format cannot be rendered by the platform decoder")
        if (!bitmap.isMutable || bitmap.width.toLong() * bitmap.height > MAX_PIXELS ||
            bitmap.width > 4096 || bitmap.height > 4096 || bitmap.width > width || bitmap.height > height
        ) {
            bitmap.recycle()
            incomplete("Platform decoder exceeded the bounded preview dimensions")
        }
        return bitmap
    }

    private suspend fun copyVerified(
        source: File,
        target: File,
        expectedHash: String,
        expectedBytes: Long,
        limit: Long,
    ) {
        if (limit > 0 && expectedBytes > limit) incomplete("Complete original exceeds the provider image limit")
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        source.inputStream().use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    if (count > expectedBytes ||
                        (limit > 0 && count > limit)
                    ) {
                        incomplete("Original changed before review")
                    }
                    hash.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        if (count != expectedBytes || hex(hash.digest()) != expectedHash) incomplete("Original changed before review")
    }

    private fun verifyFile(file: File, hash: String, bytes: Long) {
        if (!file.isFile || file.length() != bytes ||
            digest(file) != hash
        ) {
            incomplete("Pinned review evidence changed or is unavailable")
        }
    }

    private fun verifyPinnedFonts(directory: File, presentation: QualityReviewPresentation) {
        (listOf(presentation.resolvedStyle) + presentation.resolvedRegionStyles.values)
            .mapNotNull { it.fontPath }.distinct().forEach { path ->
                val file = File(path).canonicalFile
                val hash = file.name.removePrefix("font-")
                require(
                    file.parentFile == directory.canonicalFile && hash.matches(Regex("[a-f0-9]{64}")) &&
                        "sha256:$hash" in presentation.fontIdentities.values,
                ) { "Pinned font identity does not belong to this review" }
                if (!file.isFile || file.length() !in 1..MAX_FONT_BYTES || digest(file) != hash) {
                    incomplete("Pinned review font changed or is unavailable")
                }
                verifyLoadableFont(file)
            }
    }

    private fun verifyLoadableFont(file: File) {
        // API 26+: null fallback prevents invalid imported bytes from silently selecting a system font.
        // https://developer.android.com/reference/android/graphics/Typeface.Builder#setFallback(java.lang.String)
        if (runCatching { Typeface.Builder(file).setFallback(null).build() }.getOrNull() == null) {
            incomplete("Imported font cannot be loaded for this rendered review")
        }
    }

    private fun digest(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        hex(hash.digest())
    }
    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun fontKey(
        style: OverlayStyle,
    ) = "system:${style.fontFamily}:${style.fontWeight}:${style.bold}:${style.italic}"
    private fun systemFontIdentity(
        style: OverlayStyle,
    ) = "${fontKey(style)};build=${Build.FINGERPRINT};file-hash-unavailable"
    private fun incomplete(message: String): Nothing = throw TranslationException(TranslationFailureKind.LIMIT, message)

    companion object {
        const val VERSION = TranslationOverlayDocument.RENDERER_VERSION
        private const val MAX_PIXELS = 2L * 1024 * 1024
        private const val MAX_FONT_BYTES = 32L * 1024 * 1024
    }
}
