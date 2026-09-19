package mihon.feature.translation

import android.content.Context
import android.graphics.BitmapFactory
import de.stefan_oltmann.kim.Kim
import de.stefan_oltmann.kim.android.readMetadata
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Inject
@SingleIn(AppScope::class)
class TranslationImageSource(
    private val context: Context,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val chapterCache: ChapterCache,
    private val downloadPreferences: DownloadPreferences,
) {
    private val sourceLocks = ConcurrentHashMap<Long, Mutex>()
    private val sourcePermits = Semaphore(downloadPreferences.parallelSourceLimit.get().coerceIn(1, 10))

    suspend fun sourceLanguage(mangaId: Long): String? = getManga.await(mangaId)?.let { manga ->
        sourceManager.get(manga.source)?.lang?.trim()?.takeUnless {
            it.isBlank() || it.lowercase() in setOf("all", "other", "multi", "auto")
        }
    }

    suspend fun acquire(job: TranslationJob): List<TranslationImage> = withContext(Dispatchers.IO) {
        val manga = requireNotNull(getManga.await(job.mangaId)) { "Series no longer exists" }
        val chapter = requireNotNull(getChapter.await(job.chapterId)) { "Chapter no longer exists" }
        val source = requireNotNull(sourceManager.get(manga.source)) { "Source is not installed" }
        sourceLocks.getOrPut(manga.source) { Mutex() }.withLock {
            sourcePermits.withPermit {
                val readerChapter = ReaderChapter(chapter)
                readerChapter.ref()
                try {
                    ChapterLoader(context, downloadManager, downloadProvider, chapterCache, manga, source)
                        .loadChapter(readerChapter)
                    val pages = requireNotNull(readerChapter.pages)
                    val dir = File(context.noBackupFilesDir, "translation/images/${job.id}").apply { mkdirs() }
                    val permits = Semaphore(downloadPreferences.parallelPageLimit.get().coerceIn(1, 15))
                    coroutineScope {
                        pages.map { page ->
                            async {
                                permits.withPermit {
                                    val file = File(dir, "${page.index}.image")
                                    if (!file.exists()) {
                                        val temp = File(dir, "${page.index}.tmp")
                                        try {
                                            val local = page.stream
                                            if (local != null) {
                                                local().use { input -> temp.outputStream().use { input.copyTo(it) } }
                                            } else {
                                                require(source is HttpSource) { "Page has no image stream" }
                                                if (page.imageUrl.isNullOrBlank()) {
                                                    page.imageUrl =
                                                        source.getImageUrl(page)
                                                }
                                                val url = requireNotNull(page.imageUrl)
                                                if (chapterCache.isImageInCache(url)) {
                                                    chapterCache.getImageFile(url).copyTo(temp, overwrite = true)
                                                } else {
                                                    source.getImage(page).use { response ->
                                                        if (!response.isSuccessful) {
                                                            val kind = when (response.code) {
                                                                429 -> TranslationFailureKind.RATE_LIMIT
                                                                408, in 500..599 -> TranslationFailureKind.TRANSIENT
                                                                else -> TranslationFailureKind.CONTENT
                                                            }
                                                            throw TranslationException(
                                                                kind,
                                                                "Original image HTTP ${response.code}",
                                                            )
                                                        }
                                                        response.body.byteStream().use { input ->
                                                            temp.outputStream().use { input.copyTo(it) }
                                                        }
                                                    }
                                                }
                                            }
                                            check(temp.length() > 0) { "Empty image ${page.index + 1}" }
                                            check(temp.renameTo(file)) { "Cannot save image ${page.index + 1}" }
                                        } finally {
                                            temp.delete()
                                        }
                                    }
                                    identify(file, page.index)
                                }
                            }
                        }.awaitAll().sortedBy { it.index }
                    }
                } finally {
                    readerChapter.unref()
                }
            }
        }
    }

    private fun identify(file: File, index: Int): TranslationImage {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        // Header-only readers avoid allocating an entire tall strip merely to learn its size.
        // The native reader v10 has no bounds API: decode() would allocate all pixels.
        val metadata = if (options.outWidth <= 0 || options.outHeight <= 0) {
            runCatching { Kim.readMetadata(file) }.getOrNull()
        } else {
            null
        }
        val dimensions = if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            val size = metadata?.imageSize
            if (size == null || size.width <= 0 || size.height <= 0) {
                throw TranslationException(
                    TranslationFailureKind.CONTENT,
                    "Image ${index + 1} has no supported dimension header. Convert this image to PNG, JPEG, or WebP before translating.",
                )
            }
            size.width to size.height
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return TranslationImage(
            id = index.toString(),
            index = index,
            filePath = file.absolutePath,
            mimeType = options.outMimeType ?: metadata?.mediaFormat?.mimeType ?: "application/octet-stream",
            width = dimensions.first,
            height = dimensions.second,
            contentHash = digest.digest().joinToString("") { "%02x".format(it) },
            byteSize = file.length(),
        )
    }
}
