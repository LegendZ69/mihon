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

package com.paddle.ocr.preprocess

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil

data class RecPreprocessResult(
    val tensorData: FloatArray,
    val shape: LongArray,
)

object RecPreprocessor {
    private const val FIXED_HEIGHT = 48
    private const val MAX_IMG_W = 3200

    fun preprocessBatch(crops: List<Mat>): RecPreprocessResult {
        require(crops.isNotEmpty())
        require(crops.all { it.rows() > 0 && it.cols() > 0 })
        val widths = crops.map { ceil(FIXED_HEIGHT.toDouble() * it.cols() / it.rows()).toInt().coerceIn(1, MAX_IMG_W) }
        val maxW = widths.max()
        val channelSize = FIXED_HEIGHT * maxW
        // Padding is zero in normalized space, as in the official recognizer preprocessing.
        val tensorData = FloatArray(crops.size * 3 * channelSize)
        crops.forEachIndexed { batch, crop ->
            val width = widths[batch]
            val resized = Mat()
            val normalized = Mat()
            val channels = mutableListOf<Mat>()
            try {
                // Official pinned inference.yml configurations use DecodeImage img_mode=BGR.
                Imgproc.resize(
                    crop,
                    resized,
                    Size(width.toDouble(), FIXED_HEIGHT.toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_LINEAR,
                )
                resized.convertTo(normalized, CvType.CV_32F)
                Core.divide(normalized, org.opencv.core.Scalar(127.5, 127.5, 127.5), normalized)
                Core.subtract(normalized, org.opencv.core.Scalar(1.0, 1.0, 1.0), normalized)
                Core.split(normalized, channels)
                for (channel in 0..2) {
                    val values = FloatArray(FIXED_HEIGHT * width)
                    channels[channel].get(0, 0, values)
                    for (row in 0 until FIXED_HEIGHT) {
                        System.arraycopy(
                            values,
                            row * width,
                            tensorData,
                            (batch * 3 + channel) * channelSize + row * maxW,
                            width,
                        )
                    }
                }
            } finally {
                channels.forEach { it.release() }
                normalized.release()
                resized.release()
            }
        }
        return RecPreprocessResult(
            tensorData,
            longArrayOf(crops.size.toLong(), 3, FIXED_HEIGHT.toLong(), maxW.toLong()),
        )
    }
}
