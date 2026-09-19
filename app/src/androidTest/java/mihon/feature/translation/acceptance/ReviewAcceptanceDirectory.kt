package mihon.feature.translation.acceptance

import java.io.File

/** Concurrent graph initializers may create the same isolated directory after the first check. */
internal fun ensureReviewAcceptanceDirectory(directory: File): File = directory.apply {
    check(isDirectory || mkdirs() || isDirectory) { "Cannot create isolated acceptance directory: $path" }
}
