package mihon.feature.translation.transfer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID

/** Private content-addressed fonts; archive references never become arbitrary destination paths. */
internal class TranslationArchiveFontStore(private val directory: File) {
    suspend fun restore(archive: StagedTranslationArchive): Map<String, String> {
        val coroutine = currentCoroutineContext()
        val installed = mutableListOf<File>()
        try {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create private imported-font storage" }
            return archive.manifest.entries.filter { it.kind == "font" }.associate { entry ->
                coroutine.ensureActive()
                require(entry.name == "fonts/${entry.sha256}.font" && entry.bytes in 4..32L * 1024 * 1024) {
                    "Invalid portable font identity"
                }
                val source = File(archive.directory, entry.name)
                require(
                    source.length() == entry.bytes &&
                        TranslationBackupCodec.sha256(source) { coroutine.ensureActive() } == entry.sha256 &&
                        TranslationBackupCodec.validFont(source),
                ) { "Portable font verification failed" }
                val target = File(directory, "${entry.sha256}.font")
                if (!target.exists()) {
                    val temporary = File(directory, "${UUID.randomUUID()}.part")
                    try {
                        source.inputStream().use { input ->
                            temporary.outputStream().use { output ->
                                val bytes = ByteArray(8192)
                                while (true) {
                                    coroutine.ensureActive()
                                    val count = input.read(bytes)
                                    if (count < 0) break
                                    output.write(bytes, 0, count)
                                }
                            }
                        }
                        require(
                            temporary.length() == entry.bytes &&
                                TranslationBackupCodec.sha256(temporary) {
                                    coroutine.ensureActive()
                                } == entry.sha256,
                        ) { "Portable font changed during import" }
                        check(temporary.renameTo(target)) { "Cannot install portable font" }
                        installed += target
                    } finally {
                        temporary.delete()
                    }
                }
                require(
                    target.length() == entry.bytes &&
                        TranslationBackupCodec.sha256(target) {
                            coroutine.ensureActive()
                        } == entry.sha256,
                ) { "Existing font content differs; it was preserved" }
                entry.name to target.absolutePath
            }
        } catch (failure: Throwable) {
            installed.forEach { it.delete() }
            throw failure
        }
    }
}
