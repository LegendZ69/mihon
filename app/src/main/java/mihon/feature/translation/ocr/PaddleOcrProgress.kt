package mihon.feature.translation.ocr

import com.paddle.ocr.model.OCRStage
import com.paddle.ocr.model.OCRStageObserver
import com.paddle.ocr.model.OCRStageState
import com.paddle.ocr.model.OCRStageUpdate
import tachiyomi.domain.translation.model.TranslationProgressUnit
import tachiyomi.domain.translation.model.TranslationStage

/** Live native phase boundaries and measured timings, never reconstructed stage timestamps. */
internal class PaddleOcrProgress(
    private val log: PaddleOperationLog,
    private val tileIndex: Int,
    private val tileCount: Int,
) : OCRStageObserver {
    private var active: PaddleOperationLog.Handle? = null

    override suspend fun onStage(update: OCRStageUpdate) {
        val stage = when (update.stage) {
            OCRStage.PREPROCESS -> TranslationStage.PREPROCESS
            OCRStage.DETECTION -> TranslationStage.DETECTION
            OCRStage.RECOGNITION -> TranslationStage.RECOGNITION
        }
        if (update.state == OCRStageState.STARTED || active == null) {
            active = log.begin(
                stage,
                total = update.total,
                unit = if (stage == TranslationStage.PREPROCESS) {
                    TranslationProgressUnit.STEPS
                } else {
                    TranslationProgressUnit.REGIONS
                },
                message = "${stage.label} · tile ${tileIndex + 1}/$tileCount",
                details = mapOf("tileIndex" to tileIndex.toString(), "tileCount" to tileCount.toString()),
            )
        }
        val operation = checkNotNull(active)
        when (update.state) {
            OCRStageState.STARTED -> Unit
            OCRStageState.PROGRESS -> operation.progress(update.completed, update.total)
            OCRStageState.COMPLETED -> {
                operation.complete(
                    update.completed,
                    update.total,
                    details = update.details + update.timingsMillis.mapKeys { "measured.${it.key}.millis" }
                        .mapValues { it.value.toString() },
                    message = update.details["skipped"] ?: "${stage.label} finished · tile ${tileIndex + 1}/$tileCount",
                )
                active = null
            }
        }
    }

    suspend fun failed(error: Throwable) {
        active?.failed(error)
        active = null
    }
}
