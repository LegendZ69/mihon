package tachiyomi.domain.translation.service

import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult

enum class TranslationCoverageState(val label: String) {
    UNTRANSLATED("Untranslated"),
    PARTIAL("Partial"),
    COMPLETED("Completed"),
    UNKNOWN("Total unavailable"),
}

data class TranslationCoverageIdentity(val imageId: String, val hash: String, val width: Int, val height: Int)

data class TranslationCoverage(val state: TranslationCoverageState, val saved: Int, val total: Int?) {
    companion object {
        fun calculate(
            images: List<TranslationImage>,
            results: List<TranslationPageResult>,
            knownTotal: Int? = null,
        ): TranslationCoverage =
            identities(
                images.map { TranslationCoverageIdentity(it.id, it.contentHash, it.width, it.height) },
                results.map { TranslationCoverageIdentity(it.imageId, it.imageHash, it.width, it.height) },
                knownTotal,
            )

        fun identities(
            images: List<TranslationCoverageIdentity>,
            results: List<TranslationCoverageIdentity>,
            knownTotal: Int? = null,
        ): TranslationCoverage {
            if (images.isEmpty()) {
                val saved = results.distinctBy { Triple(it.hash, it.width, it.height) }.size
                return TranslationCoverage(
                    if (saved ==
                        0
                    ) {
                        TranslationCoverageState.UNTRANSLATED
                    } else {
                        TranslationCoverageState.UNKNOWN
                    },
                    saved,
                    null,
                )
            }
            val originals = images.distinctBy { it.imageId }
            val saved = originals.count { image ->
                results.any { it.hash == image.hash && it.width == image.width && it.height == image.height }
            }
            val total = maxOf(originals.size, knownTotal?.takeIf { it > 0 } ?: 0)
            return TranslationCoverage(
                when (saved) {
                    total -> TranslationCoverageState.COMPLETED
                    0 -> TranslationCoverageState.UNTRANSLATED
                    else -> TranslationCoverageState.PARTIAL
                },
                saved,
                total,
            )
        }
    }
}
