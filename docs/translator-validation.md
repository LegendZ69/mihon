# Translator validation — 2026-09-05

Implementation is in the working tree on `codex/manga-translator`, tracked in [issue #1](https://github.com/LegendZ69/mihon/issues/1). The [usage guide](translator.md) describes configuration and the [handset procedure](translator-device-validation.md) covers the remaining device work.

This is the retained September 5 baseline. See the [September 6 K90 validation report](translator-k90-validation-2026-09-06.md) for subsequent live-provider runs, actual 16 KB execution, measured handset evidence, regression fixes and successor APK hashes. The pending list below describes the baseline date and is superseded by that report's acceptance matrix.

## Completed checks

| Check | Result |
| --- | --- |
| Repository formatting | `spotlessCheck` passed across the repository; no lint rules suppressed. |
| JVM unit tests | 146 passed: app 77, domain 64, OCR core 5; zero failures or skips. |
| App Android instrumentation | 14 passed on Android 16 / API 36.1 ARM64, with a 4 KB kernel. |
| Persistent data | Real SQLite close/reopen, queue/settings/image/results/batch/log recovery, result ordering, cascade behavior, and v15 migration preserving existing data passed. |
| SQLDelight | Debug and release migration verification passed. |
| Provider image preparation | A noisy lossless image exceeding 7 MB was tiled within the cap with original pixel checks; EXIF removal retained original dimensions and pixel coordinates. |
| Reader rendering | Real decoder/crop JNI, original coordinates, WebGPU zoom/pan/toggle/spreads, repeated translucent revisions, Canvas/GPU opacity agreement, and transparent-edge filtering passed. |
| Local OCR | Tiny, Small, Medium, and Korean packs ran real inference. English, Chinese, and Japanese fixtures were also exercised; eight native test executions passed. |
| Release build | R8 optimization, resource shrinking, and ARM64 release packaging passed. The distribution APK installs without the adb test-only flag. |
| Release UI smoke check | The installed R8 APK completed onboarding and opened More → Translator → Settings successfully. |
| Native packaging | All 14 ARM64 libraries in the distribution APK have ELF load alignment of at least 16 KB; APK ZIP alignment and signature verification passed. The earlier full build's 14 x86_64 libraries also passed ELF load-alignment checks. |

The queue tests cover concurrency admission, shared request limits, ordering, odd halving, partial success, cancellation, stale-response fencing, region retry, duplicate enqueue, Wi-Fi waiting, and checkpoint recovery. Provider tests exercise OAuth refresh/signing, endpoint and header differences, rejected parameters, malformed/missing output, throttling, bounded image preparation, exact outgoing bytes, redaction across write boundaries, shared capture budgets, interrupted captures, and dated pricing estimates.

Two rendering defects were caught and fixed before delivery: applying alpha twice and filtering transparent edges before premultiplication. The final tests keep the original pixel assertions and add comparisons against Android Canvas. A stripped EXIF orientation is correctly accepted as absent or normal while the original JPEG's orientation tag remains intact.

## Artifact and reproduction

The installable APK is `build/translator/mihon-translator-arm64-v8a.apk` (117,314,576 bytes). Its SHA-256 is:

```text
5e4dcef1ed0e0c2dd49d5847679e9484b824dcc441af37873958299865be77d2
```

`build/translator/artifact.json` records the APK and individual native-library hashes; `build/translator/evidence/` contains the unit XML, instrumentation output, build logs, native checks, and signature result. The APK uses the repository's local signing configuration, not Mihon's official distribution certificate.

The ARM64 distribution command was:

```sh
./gradlew :app:assembleRelease \
  -Pandroid.injected.build.abi=arm64-v8a \
  -Pandroid.injected.testOnly=false \
  --no-build-cache --no-daemon --max-workers=2
```

For this ABI-targeted build, AGP writes current APKs under `app/build/intermediates/apk/`. Older files in `app/build/outputs/apk/` must not be used as the final artifact. Debug test-only APKs require `adb install -t`; the distribution APK was separately installed without `-t`.

The host ran out of storage during one cache-packaging attempt and paused the emulator. Reproducible staging files were removed, idle build processes stopped, and the emulator resumed. Release packaging and the complete final instrumentation run were then repeated successfully. No application source or test assertions were bypassed to resolve that host issue.

## Still requiring validation

- Live Vertex service-account/Express and OpenAI requests using the user's account, including actual access to the configured model and billed usage. HTTP fixtures validate the adapters but do not establish account access.
- Translation meaning and OCR quality on representative real chapters: vertical Japanese, Korean, both Chinese scripts, mixed languages, tiny lettering, rotated bubbles, watermarks, ads, and sound effects. Clean model fixtures do not establish quality on every page type.
- Physical K90 Pro Max OCR latency, Java/native/GPU memory, scrolling frame times, thermal behavior, and HyperOS background recovery.
- Execution on an actual 16 KB kernel, along with long-duration process-death/network/background interruption and memory-pressure testing on the handset. ELF/ZIP alignment checks are compatibility evidence, not a substitute for runtime execution.

The tracking issue remains open for these acceptance steps.
