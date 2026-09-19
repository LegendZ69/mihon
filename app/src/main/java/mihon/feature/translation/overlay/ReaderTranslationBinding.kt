package mihon.feature.translation.overlay

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.TranslationPageResult
import java.security.MessageDigest

/** A binding owns no reader stream and only accepts results for the exact bytes currently displayed. */
fun readerTranslationDocuments(context: Context, page: ReaderPage): Flow<TranslationOverlayDocument?> {
    val graph = context.appGraph
    val chapterId = requireNotNull(page.chapter.chapter.id)
    val mangaId = requireNotNull(page.chapter.chapter.manga_id)
    var verifiedHash: String? = null
    return combine(
        graph.translationRepository.observeResults(chapterId),
        graph.translationPreferences.revision,
    ) { results, _ ->
        val settings = graph.translationPreferences.effectiveSettings(mangaId)
        Triple(results.lastOrNull { it.imageId == page.index.toString() }, settings.style, settings.contentPolicy)
    }.distinctUntilChanged().mapLatest { (result, style, contentPolicy) ->
        if (result == null || !style.enabled) return@mapLatest null
        try {
            val hash = verifiedHash ?: withContext(Dispatchers.IO) {
                val source = page.stream ?: return@withContext null
                source().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(bytes)
                        if (read < 0) break
                        digest.update(bytes, 0, read)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
            }.also { verifiedHash = it }
            if (hash == null || hash != result.imageHash) {
                context.logcat(LogPriority.WARN) {
                    "Translation overlay skipped: source hash differs for chapter=$chapterId image=${page.index}"
                }
                return@mapLatest null
            }
            val sampled = if (needsTranslationBackgroundSamples(result, style)) {
                withContext(Dispatchers.IO) {
                    page.stream?.let { sampleTranslationBackgrounds(it, result, style) }
                        ?: emptyMap()
                }
            } else {
                emptyMap()
            }
            withContext(Dispatchers.Default) {
                TranslationOverlayDocument.create(context, result, style, sampled, contentPolicy)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            context.logcat(LogPriority.ERROR, error) {
                "Unable to render translation overlay for chapter=$chapterId image=${page.index}"
            }
            null
        }
    }
}

/** Translucent masks also need source colors for contrast, without opting into sampled masks. */
internal fun needsTranslationBackgroundSamples(result: TranslationPageResult, style: OverlayStyle): Boolean =
    result.regions.any { needsTranslationBackgroundSample(it.style ?: style) }

private fun needsTranslationBackgroundSample(style: OverlayStyle): Boolean =
    (style.sampleBackground && style.backgroundColor == null) || style.backgroundOpacity < 1f ||
        style.backgroundColor?.let { Color.alpha(it.toInt()) < 255 } == true

@Suppress("DEPRECATION")
internal fun sampleTranslationBackgrounds(
    stream: () -> java.io.InputStream,
    result: TranslationPageResult,
    style: OverlayStyle,
): Map<String, Int> {
    val decoder = try {
        stream().use {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(it)
            } else {
                BitmapRegionDecoder.newInstance(it, false)
            }
        }
    } catch (error: Exception) {
        result.logcat(LogPriority.WARN, error) { "Background sampling unavailable for this source format" }
        return emptyMap()
    }
    decoder ?: return emptyMap()
    return try {
        result.regions.mapNotNull { region ->
            val effective = region.style ?: style
            if (!needsTranslationBackgroundSample(effective) || region.points.isEmpty()) {
                return@mapNotNull null
            }
            val colors = region.points.flatMap { point ->
                val x = point.x.toInt().coerceIn(0, decoder.width - 1)
                val y = point.y.toInt().coerceIn(0, decoder.height - 1)
                val rect =
                    Rect(
                        (x - 2).coerceAtLeast(0),
                        (y - 2).coerceAtLeast(0),
                        (x + 3).coerceAtMost(decoder.width),
                        (
                            y +
                                3
                            ).coerceAtMost(decoder.height),
                    )
                val sample = decoder.decodeRegion(rect, BitmapFactory.Options()) ?: return@flatMap emptyList<Int>()
                try {
                    buildList {
                        for (py in 0 until sample.height) for (px in 0 until sample.width) add(sample.getPixel(px, py))
                    }
                } finally {
                    sample.recycle()
                }
            }
            colors.groupingBy { it and 0xFFF0F0F0.toInt() }.eachCount().maxByOrNull { it.value }
                ?.let { region.id to (it.key or 0xFF000000.toInt()) }
        }.toMap()
    } finally {
        decoder.recycle()
    }
}
