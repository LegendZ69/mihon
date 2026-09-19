# PaddleOCR Android integration

The `com.paddle.ocr` sources originate from PaddlePaddle/PaddleOCR,
`deploy/ppocr-android/ppocr-sdk`, commit
`2661c7c0ef5c613e8f93c6e93b2e052399f0f854` (retrieved 2026-09-05).
They retain the upstream Apache-2.0 copyright headers; see LICENSE.

Source: https://github.com/PaddlePaddle/PaddleOCR/tree/2661c7c0ef5c613e8f93c6e93b2e052399f0f854/deploy/ppocr-android/ppocr-sdk

Mihon adaptations: filesystem-backed model and dictionary loading; preserve
detector confidence; configurable tall-crop rotation; cancellation checks;
BGR recognition input matching the pinned inference.yml and PaddleX predictor;
OpenCV's official Android loader; and current official ONNX Runtime
1.29.0/OpenCV 5.0.0 Android artifacts. Model packs are outside the APK and
pinned by revision, byte length, and SHA-256 in PaddleModelRegistry.

Inference preserves the upstream DB detector, perspective crop, CTC decoding,
and model normalization. PaddleOCR's Android reference supports quadrilaterals,
not arbitrary polygons or word-level boxes. App-level tiling restores each
region to original image pixels and applies reading order independently.

Documentation: https://www.paddleocr.ai/main/en/version3.x/inference_deployment/cross_platform/android_deployment.html

ONNX Runtime: MIT; https://github.com/microsoft/onnxruntime/tree/v1.29.0
OpenCV: Apache-2.0; https://github.com/opencv/opencv/tree/5.0.0
The official OpenCV 5.0.0 AAR's C++ runtime has 4 KB ELF alignment. The
`prepareOpenCv` task verifies AAR SHA-256
`02906576c8ed6a728916853fb2f6df020af6ce6eacab527ae89ded8d973cbf6d`,
extracts its Java classes/resources/JNI, and replaces `libc++_shared.so` using
this repository's pinned Android NDK 29.0.14206865. It retains the `org.opencv`
resource namespace. No downloaded binaries are checked into the repository.

LLVM libc++ runtime license: Apache-2.0 with LLVM exceptions;
https://github.com/llvm/llvm-project/blob/main/libcxx/LICENSE.TXT

The AARs' native alignment and target-device behavior require release testing;
artifact availability does not itself establish 16 KB compatibility.
