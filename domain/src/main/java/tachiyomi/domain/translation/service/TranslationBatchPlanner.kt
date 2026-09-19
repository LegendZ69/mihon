package tachiyomi.domain.translation.service

import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.model.TranslationRequest

/** Only ordered contiguous batches are used, preserving chapter reading order. */
class TranslationBatchPlanner(private val provider: TranslationProvider) {
    suspend fun plan(request: TranslationRequest): List<List<TranslationImage>> {
        val settings = request.settings
        return when (settings.mode) {
            TranslationMode.STRUCTURED_FILES -> throw TranslationException(
                TranslationFailureKind.CONFIGURATION,
                "Structured files requires importing translated files",
            )
            TranslationMode.VERTEX -> request.images.map { listOf(it) }
            TranslationMode.CUSTOM -> request.images.chunked(settings.customBatchSize)
            TranslationMode.HALVING -> listOf(request.images)
            TranslationMode.MAX -> {
                val caps = provider.capabilities(settings.provider)
                if (caps.maxInputTokens <= 0 || caps.maxOutputTokens <= 0) {
                    throw TranslationException(
                        TranslationFailureKind.CONFIGURATION,
                        "Max needs verified model limits. Set custom input/output token limits for this model.",
                    )
                }
                val result = mutableListOf<List<TranslationImage>>()
                var offset = 0
                while (offset < request.images.size) {
                    var low = 1
                    var high = minOf(
                        caps.maxImages.takeIf { it > 0 } ?: request.images.size,
                        request.images.size - offset,
                    )
                    var best = 0
                    while (low <= high) {
                        val count = (low + high) / 2
                        val images = request.images.subList(offset, offset + count)
                        if (fits(request.copy(images = images))) {
                            best = count
                            low = count + 1
                        } else {
                            high = count - 1
                        }
                    }
                    if (best == 0) {
                        throw TranslationException(
                            TranslationFailureKind.LIMIT,
                            "Image ${offset + 1} exceeds request limits",
                        )
                    }
                    result += request.images.subList(offset, offset + best).toList()
                    offset += best
                }
                result
            }
        }
    }

    suspend fun fits(request: TranslationRequest): Boolean {
        require(request.settings.mode != TranslationMode.STRUCTURED_FILES) {
            "Structured imports do not dispatch provider work"
        }
        val caps = provider.capabilities(request.settings.provider)
        if (caps.maxImages > 0 && request.images.size > caps.maxImages) return false
        return try {
            val tokens = provider.countTokens(request)
            tokens == null || caps.maxInputTokens <= 0 || tokens <= caps.maxInputTokens
        } catch (e: TranslationException) {
            if (e.kind == TranslationFailureKind.LIMIT) false else throw e
        }
    }

    companion object {
        fun <T> halves(values: List<T>): Pair<List<T>, List<T>> {
            require(values.size > 1)
            val mid = (values.size + 1) / 2
            return values.take(mid) to values.drop(mid)
        }

        fun canSplit(error: Throwable) = error is TranslationException &&
            error.kind in setOf(TranslationFailureKind.CONTENT, TranslationFailureKind.LIMIT)
    }
}
