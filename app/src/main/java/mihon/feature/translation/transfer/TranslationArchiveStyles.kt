package mihon.feature.translation.transfer

import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.TranslationArchiveChapter
import tachiyomi.domain.translation.model.TranslationPageResult

/** Map portable font references without changing transcription, geometry, measured scores or revisions. */
internal fun TranslationArchiveChapter.mapStyles(
    style: (OverlayStyle) -> OverlayStyle,
    fontIdentityKey: (String, String) -> String = { key, _ -> key },
): TranslationArchiveChapter {
    fun page(value: TranslationPageResult): TranslationPageResult = value.copy(
        regions = value.regions.map { it.copy(style = it.style?.let(style)) },
        rawOcr = value.rawOcr?.let { ocr ->
            ocr.copy(regions = ocr.regions.map { it.copy(style = it.style?.let(style)) })
        },
    )
    return copy(
        job = job.copy(
            settings = job.settings.copy(style = style(job.settings.style)),
            archiveMetadata = job.archiveMetadata?.let { archive ->
                archive.copy(effectiveSettings = archive.effectiveSettings?.let { it.copy(style = style(it.style)) })
            },
        ),
        effectiveSettings = effectiveSettings?.let { it.copy(style = style(it.style)) },
        results = results.map(::page),
        reviews = reviews.map { review ->
            review.copy(
                beforeResult = page(review.beforeResult),
                executionSettings = review.executionSettings?.let { it.copy(style = style(it.style)) },
                renderStyle = review.renderStyle?.let(style),
                renderEvidence = review.renderEvidence?.let { evidence ->
                    evidence.copy(
                        presentation = evidence.presentation.let { presentation ->
                            presentation.copy(
                                requestedStyle = style(presentation.requestedStyle),
                                resolvedStyle = style(presentation.resolvedStyle),
                                requestedRegionStyles = presentation.requestedRegionStyles.mapValues {
                                    it.value?.let(style)
                                },
                                resolvedRegionStyles = presentation.resolvedRegionStyles.mapValues { style(it.value) },
                                fontIdentities = presentation.fontIdentities.entries.associate { (key, identity) ->
                                    fontIdentityKey(key, identity) to
                                        identity
                                },
                            )
                        },
                    )
                },
            )
        },
    )
}
