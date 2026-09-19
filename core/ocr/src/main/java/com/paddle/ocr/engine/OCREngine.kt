// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.engine

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.ModelConfig
import com.paddle.ocr.model.OCRError
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.model.OCRStage
import com.paddle.ocr.model.OCRStageObserver
import com.paddle.ocr.model.OCRStageState
import com.paddle.ocr.model.OCRStageUpdate
import com.paddle.ocr.postprocess.BoxSorter
import com.paddle.ocr.postprocess.QuadTextCrop
import com.paddle.ocr.util.BitmapUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class OCREngine(
    context: Context,
    private val config: PaddleOCRConfig,
    engineConfig: EngineConfig,
    detModelAsset: String = "models/det/inference.onnx",
    recModelAsset: String = "models/rec/inference.onnx",
    recConfigAsset: String = "models/rec/inference.yml",
) {
    private val ortManager = ORTSessionManager(context, engineConfig)
    private val detectionEngine: DetectionEngine
    private val recognitionEngine: RecognitionEngine
    val coldLoadTimeMs: Long get() = ortManager.coldLoadTimeMs

    init {
        val configured = try {
            ortManager.loadModels(detModelAsset, recModelAsset)
            val recModelConfig = ModelConfig.parse(context, recConfigAsset)
            recModelConfig
        } catch (t: Throwable) {
            ortManager.release()
            throw t
        }
        detectionEngine = DetectionEngine(ortManager, config)
        recognitionEngine = RecognitionEngine(ortManager, configured.characterList)
    }

    suspend fun run(bitmap: Bitmap): OCREngineResult = run(bitmap, null)

    suspend fun run(bitmap: Bitmap, observer: OCRStageObserver?): OCREngineResult {
        observer?.onStage(OCRStageUpdate(OCRStage.PREPROCESS, OCRStageState.STARTED, total = 1))
        val started = System.nanoTime()
        val srcMat = BitmapUtils.bitmapToBGRMat(bitmap)
        try {
            observer?.onStage(
                OCRStageUpdate(
                    OCRStage.PREPROCESS,
                    OCRStageState.COMPLETED,
                    1,
                    1,
                    timingsMillis = mapOf("bitmapToBgr" to (System.nanoTime() - started) / 1_000_000),
                ),
            )
        } catch (error: Throwable) {
            srcMat.release()
            throw error
        }
        return runWithOwnedMat(srcMat, observer)
    }

    suspend fun run(imageBytes: ByteArray): OCREngineResult {
        val srcMat = BitmapUtils.imdecodeBGR(imageBytes)
        if (srcMat.empty()) {
            srcMat.release()
            throw OCRError.InvalidImage()
        }
        return runWithOwnedMat(srcMat)
    }

    private suspend fun runWithOwnedMat(
        srcMat: org.opencv.core.Mat,
        observer: OCRStageObserver? = null,
    ): OCREngineResult {
        return try {
            run(srcMat, observer)
        } finally {
            srcMat.release()
        }
    }

    private suspend fun run(srcMat: org.opencv.core.Mat, observer: OCRStageObserver?): OCREngineResult {
        val totalStart = System.currentTimeMillis()
        observer?.onStage(OCRStageUpdate(OCRStage.DETECTION, OCRStageState.STARTED))
        val detResult = detectionEngine.detect(srcMat)
        val boxes = detResult.boxes
        observer?.onStage(
            OCRStageUpdate(
                OCRStage.DETECTION,
                OCRStageState.COMPLETED,
                boxes.size.toLong(),
                boxes.size.toLong(),
                timingsMillis = mapOf(
                    "detection" to detResult.timeMs,
                    "preprocess" to detResult.preprocessMs,
                    "inference" to detResult.inferenceMs,
                    "postprocess" to detResult.postprocessMs,
                ),
                details = mapOf("inputShape" to detResult.inputShape.toString()),
            ),
        )

        if (boxes.isEmpty()) {
            observer?.onStage(
                OCRStageUpdate(
                    OCRStage.RECOGNITION,
                    OCRStageState.COMPLETED,
                    0,
                    0,
                    details = mapOf("skipped" to "No regions detected; recognition was not run"),
                ),
            )
            val elapsed = System.currentTimeMillis() - totalStart
            return OCREngineResult(
                results = emptyList(),
                detectionTimeMs = detResult.timeMs,
                recognitionTimeMs = 0,
                totalTimeMs = elapsed,
                lineCount = 0,
                detPreprocessMs = detResult.preprocessMs,
                detInferenceMs = detResult.inferenceMs,
                detPostprocessMs = detResult.postprocessMs,
                detInputShape = detResult.inputShape,
                coldLoadTimeMs = ortManager.coldLoadTimeMs,
            )
        }

        // 2. Sort boxes
        val sortedBoxes = BoxSorter.sortInReadingOrder(boxes)
        observer?.onStage(
            OCRStageUpdate(
                OCRStage.RECOGNITION,
                OCRStageState.STARTED,
                total = sortedBoxes.size.toLong(),
            ),
        )

        // 3. Crop and recognize text regions
        var totalRecPreMs = 0L
        var totalRecInfMs = 0L
        var totalRecPostMs = 0L
        var totalRecMs = 0L
        val allResults = mutableListOf<OCRResult>()
        val recInputShapes = mutableListOf<List<Int>>()
        val perLineRecMs = mutableListOf<Long>()
        val batchSize = config.recBatchSize.coerceAtLeast(1)

        var i = 0
        while (i < sortedBoxes.size) {
            currentCoroutineContext().ensureActive()
            val batchCrops = mutableListOf<org.opencv.core.Mat>()
            val batchBoxIndices = mutableListOf<Int>()
            var next = i
            var cropPixels = 0L
            var widestNormalized = 0
            try {
                while (next < sortedBoxes.size && batchCrops.size < batchSize) {
                    currentCoroutineContext().ensureActive()
                    val crop = QuadTextCrop.crop(srcMat, sortedBoxes[next], config.rotateTallCrops)
                    if (crop.rows() > 0 && crop.cols() > 0) {
                        val normalizedWidth = kotlin.math.ceil(
                            48.0 * crop.cols() / crop.rows(),
                        ).toInt().coerceIn(1, 3200)
                        val nextWidest = maxOf(widestNormalized, normalizedWidth)
                        // Bound batch tensor/output memory without changing a crop's official normalization.
                        val tooLarge = 48L * nextWidest * (batchCrops.size + 1) > 48L * 3200 ||
                            cropPixels + crop.rows().toLong() * crop.cols() > 2L * 1024 * 1024
                        if (batchCrops.isNotEmpty() && tooLarge) {
                            crop.release()
                            break
                        }
                        widestNormalized = nextWidest
                        cropPixels += crop.rows().toLong() * crop.cols()
                        batchCrops.add(crop)
                        batchBoxIndices.add(next)
                    } else {
                        crop.release()
                    }
                    next++
                }
                if (batchCrops.isNotEmpty()) {
                    val batchResult = recognitionEngine.recognize(batchCrops)
                    totalRecPreMs += batchResult.preprocessMs
                    totalRecInfMs += batchResult.inferenceMs
                    totalRecPostMs += batchResult.postprocessMs
                    totalRecMs += batchResult.timeMs
                    recInputShapes.add(batchResult.inputShape)
                    if (batchSize == 1) {
                        perLineRecMs.add(batchResult.timeMs)
                    }

                    for (j in batchResult.texts.indices) {
                        val boxIdx = batchBoxIndices[j]
                        val (text, confidence) = batchResult.texts[j]
                        if (confidence >= config.recScoreThresh) {
                            allResults.add(
                                OCRResult(
                                    box = sortedBoxes[boxIdx],
                                    text = text,
                                    confidence = confidence,
                                ),
                            )
                        }
                    }
                }
            } finally {
                batchCrops.forEach { it.release() }
            }
            i = next
            observer?.onStage(
                OCRStageUpdate(
                    OCRStage.RECOGNITION,
                    OCRStageState.PROGRESS,
                    i.toLong(),
                    sortedBoxes.size.toLong(),
                    details = mapOf("recognizedRegions" to allResults.size.toString()),
                ),
            )
        }

        observer?.onStage(
            OCRStageUpdate(
                OCRStage.RECOGNITION,
                OCRStageState.COMPLETED,
                sortedBoxes.size.toLong(),
                sortedBoxes.size.toLong(),
                timingsMillis = mapOf(
                    "recognition" to totalRecMs,
                    "preprocess" to totalRecPreMs,
                    "inference" to totalRecInfMs,
                    "postprocess" to totalRecPostMs,
                ),
                details = mapOf(
                    "recognizedRegions" to allResults.size.toString(),
                    "inputShapes" to recInputShapes.toString(),
                ),
            ),
        )
        val totalElapsed = System.currentTimeMillis() - totalStart
        val pipelineOverhead = totalElapsed - detResult.timeMs - totalRecMs

        return OCREngineResult(
            results = allResults,
            detectionTimeMs = detResult.timeMs,
            recognitionTimeMs = totalRecMs,
            totalTimeMs = totalElapsed,
            lineCount = allResults.size,
            detPreprocessMs = detResult.preprocessMs,
            detInferenceMs = detResult.inferenceMs,
            detPostprocessMs = detResult.postprocessMs,
            recPreprocessMs = totalRecPreMs,
            recInferenceMs = totalRecInfMs,
            recPostprocessMs = totalRecPostMs,
            pipelineOverheadMs = pipelineOverhead,
            coldLoadTimeMs = ortManager.coldLoadTimeMs,
            detInputShape = detResult.inputShape,
            recInputShapes = recInputShapes,
            perLineRecMs = perLineRecMs,
        )
    }

    fun release() {
        ortManager.release()
    }
}
