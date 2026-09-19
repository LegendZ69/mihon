package com.paddle.ocr.model

/** Optional observer at actual pipeline boundaries; it does not modify native output or confidence scores. */
fun interface OCRStageObserver {
    suspend fun onStage(update: OCRStageUpdate)
}

enum class OCRStage { PREPROCESS, DETECTION, RECOGNITION }
enum class OCRStageState { STARTED, PROGRESS, COMPLETED }

data class OCRStageUpdate(
    val stage: OCRStage,
    val state: OCRStageState,
    val completed: Long = 0,
    val total: Long? = null,
    val timingsMillis: Map<String, Long> = emptyMap(),
    val details: Map<String, String> = emptyMap(),
)
