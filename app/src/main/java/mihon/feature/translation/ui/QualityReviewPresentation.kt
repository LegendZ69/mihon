package mihon.feature.translation.ui

import tachiyomi.domain.translation.model.QualityReviewCheckpoint

/** Historical passes cannot hide a newer incomplete assessment in the queue filter. */
internal fun latestQualityReviews(reviews: List<QualityReviewCheckpoint>): List<QualityReviewCheckpoint> =
    reviews.groupBy { it.jobId to it.imageId }.values.map { history ->
        history.maxWith(compareBy<QualityReviewCheckpoint> { it.updatedAt }.thenByDescending { it.id })
    }
