# On-device PaddleOCR

This module adapts PaddlePaddle's Android reference SDK to the current official
ONNX Runtime and OpenCV Android artifacts. Source revision and licenses are in
NOTICE.md. The app's `PaddleModelRegistry` pins official detector, recognizer, and
recognizer dictionary files by repository revision, length, and SHA-256.

`PaddleModelManager` exposes `states`, `refresh()`, `download(profile, language)`,
and `remove(profile, language)`. Downloads commit only after every artifact has
passed integrity validation. Korean selects the official PP-OCRv5 Korean
recognizer; other languages use the selected PP-OCRv6 Tiny, Small, or Medium
recognizer. `auto` uses the multilingual recognizer and does not run a separate
script classifier. A caller should resolve source language metadata first.

`PaddleOcrEngine.recognize(image, settings, decodedMemoryMb)` serializes local
inference, decodes bounded overlapping tiles, and restores quadrilaterals to
original image pixels. The memory value bounds decoded and preprocessing images;
model weights, native inference activations, and runtime memory are additional.
Detection and recognition confidence are preserved independently. Recognition
threshold exclusions remain present in diagnostics. Orientation means perspective
rectification and optional tall-crop rotation, without a trained page classifier.

The native detector respects official limit type/side settings. Configurations
that would exceed the working pixel bound fail explicitly; lower the detector
side limit or increase the image memory budget. Effective tensor shapes and
working limits are included in the raw OCR JSON.

## Validation

Run core unit tests with `./gradlew :core:ocr:testDebugUnitTest` and app geometry
and model-integrity tests with
`./gradlew :app:testDebugUnitTest --tests 'mihon.feature.translation.ocr.*'`.

Build instrumentation with `./gradlew :core:ocr:assembleDebugAndroidTest`, install
the resulting APK, and run:

```sh
adb -s SERIAL shell am instrument -w org.opencv.test/androidx.test.runner.AndroidJUnitRunner
```

This always tests native loading, OpenCV geometry, and recognition channel order
and padding. The end-to-end model test is skipped unless an explicit verified
model pack is supplied. Copy the selected registry's detector to `det.onnx`, its
recognizer to `rec.onnx`, and its matching YAML to `rec.yml` in an accessible
folder, then run:

```sh
adb -s SERIAL shell am instrument -w \
  -e ocrModelDirectory /data/local/tmp/paddle-test \
  org.opencv.test/androidx.test.runner.AndroidJUnitRunner
```

The test renders a synthetic text fixture, executes actual detector/recognizer
models, and checks text, confidence, and original-pixel coordinates. The optional
`ocrFixtureText` instrumentation argument changes its expected text.

Use both 4 KB and 16 KB Android targets. Verify the kernel with
`adb -s SERIAL shell getconf PAGE_SIZE`, the final APK with
`zipalign -c -P 16 -v 4 APK`, and every ARM64/x86_64 native library's ELF LOAD
alignment. The OpenCV AAR's bundled C++ runtime is replaced at build time with
the repository-pinned NDK runtime; inspect the final app APK after other native
dependencies are merged.
