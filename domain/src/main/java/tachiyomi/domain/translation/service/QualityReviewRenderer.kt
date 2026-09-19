package tachiyomi.domain.translation.service

import tachiyomi.domain.translation.model.QualityReviewRenderEvidence
import tachiyomi.domain.translation.model.QualityReviewRequest

/** Android owns rendering/files; the coordinator owns durable reuse and terminal cleanup. */
interface QualityReviewRenderer {
    suspend fun prepare(request: QualityReviewRequest): QualityReviewRenderEvidence
    suspend fun cleanup(evidence: QualityReviewRenderEvidence)
}
