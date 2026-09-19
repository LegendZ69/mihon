# Translator handset validation

The requested target is a K90 Pro Max, Snapdragon Elite Gen 5, 16/512 GB, running HyperOS 3. These are user-supplied details. The [September 6 K90 report](translator-k90-validation-2026-09-06.md) now records the connected handset, live-provider results and measured reader/OCR observations. It also records actual 16 KB emulator execution. This document remains the reproduction procedure; use the dated report for current pass/fail/pending status. “Latest HyperOS” is not a reproducible build identity.

## Evidence already collected

The ARM64 `Custom_Medium_Phone` emulator (`emulator-5554`, Android 16 / API 36.1) reported a **4096-byte** kernel page size. OCR instrumentation passed eight tests across Tiny/JNI/preprocessing, Small, Medium, Korean, Chinese, and Japanese runs, using the downloaded official model artifacts. These are functional checks, not handset performance measurements.

The retained [native verification report](/tmp/mihon-ocr-native-verification.json) records SHA-256 hashes, ELF load alignment, GNU RELRO, and successful 16 KB APK ZIP alignment for the OCR test APK. Its SHA-256 is `eb01dd018294d6fc3bcae1528eaaa24baf27c20fcbb841327b5412db731ebb76`. All eight inspected ARM64/x86_64 libraries have load alignment of at least 16384 bytes and RELRO. The test APK was removed after retaining this evidence to recover build space.

**Actual 16 KB runtime execution is recorded in the September 6 report**, including 18 updated app Android tests. Static alignment checks and a successful 4 KB JNI load alone do not prove operation on a 16 KB kernel. Check each final artifact and remaining native model paths independently. Android documents both binary/APK alignment and a running-device page-size check; `adb -s SERIAL shell getconf PAGE_SIZE` must report `16384` for that runtime claim. Do not infer it from the phone model or Android version. [Android 16 KB validation](https://developer.android.com/guide/practices/page-sizes)

Local evidence is ephemeral: copy needed files into the chosen private report folder before `/tmp` cleanup. Individual logs are `/tmp/mihon-ocr-{4kb,korean,small,medium,chinese,japanese}-instrumentation.log`. The first reports three passing tests; each remaining log reports one.

## Capture a bounded run

Use the exact installed APK and a release/profileable build for performance comparisons. Application IDs are `app.mihon` (release), `app.mihon.benchmark` (benchmark), `app.mihon.foss`, `app.mihon.debug` (nightly), and `app.mihon.dev` (debug). Debug and minified release results are not interchangeable. Installation and credentials are operator setup steps; this capture does not perform them.

List devices, then explicitly supply the selected serial:

```sh
adb devices -l
python3 scripts/translator_device_capture.py \
  --serial ACTUAL_HANDSET_SERIAL \
  --package app.mihon \
  --scenario paddle-small-warm-01 \
  --kind handset --duration 30 --interval 10 \
  --apk /absolute/path/to/installed.apk \
  --output /absolute/path/to/private-validation
```

Start the named flow only when the script prints `START`. Every run gets a unique folder with its manifest, device/build identity, kernel page size, package metadata, CPU snapshots, memory, thermal/battery snapshots, frame statistics, service/idle state, and bounded PID-scoped logs. APK hashing identifies the supplied local file; confirm that it is the file installed on the handset. The recorded host Git revision alone does not prove the installed build.

The script requires a serial, rejects recognizable emulators for `--kind handset`, and uses device read operations only. It does not launch or force-stop the app, clear app data/log buffers/frame counters, change settings, force idle, or install anything. Emulator diagnostic captures require `--kind emulator`. Commands have timeouts and retain failures. The observation window is at most 300 seconds; preflight, before/after snapshots, and an in-flight sample add bounded collection time. Read the actual timestamps. Snapshots introduce overhead; denied or absent counters mean unavailable, not zero.

Keep raw logs private and inspect them before sharing: chapter text, URLs, and device identifiers may be present even when authorization headers are redacted.

For a full reader trace, use the bounded streaming [Perfetto capture and analysis procedure](translator-device-observations-2026-09-06.md). The [unplugged device-side helper](translator-unplugged-capture.md) supports a separately labeled observation when wireless ADB is unavailable.

## Reproduction matrix

Use a fixed chapter fixture and record its image hashes, dimensions, language, page count, model pack/version, OCR settings, concurrency/memory budget, translation/style revision, reader backend, and raw-logging setting. Record battery percentage, charging state, ambient conditions, brightness, refresh-rate policy, network, and HyperOS battery/autostart policy. First-load and warm-cache runs must be labeled separately. The durations and repetitions below are test-protocol choices, not official platform limits or promised performance targets.

| Scenario | Focused operation | Evidence and expected behavior |
| --- | --- | --- |
| Idle baseline | Open the reader with the fixed chapter, wait for loading, then capture 30 seconds | Baseline PSS/native/graphics, CPU, thermal state |
| OCR profiles | Run Tiny, Small, Medium separately on the same images; include explicit Korean; repeat each warm run three times | Load versus inference time, CPU/native memory, original-pixel polygons and confidence retained |
| Reader frames | With cached translations, scroll/pan/zoom the same pages for 30 seconds in each supported reader backend | Frame deadlines, CPU/GPU tracks; correct overlays under crop, split, spread, and rotation |
| Style and lifetime | Change styles, navigate out/back, and switch chapters ten times, then idle | No geometry drift or stale revisions; memory returns toward a stable range after expected caches |
| Sustained load | Repeat the fixed OCR workload for 180 seconds, recording completed images and thermal samples | Throughput over time, thermal status, CPU frequency; separate charging and unplugged runs |
| Background recovery | Queue a bounded chapter, use Home, turn the screen off, return; separately interrupt/reconnect the network | Visible queue/notification state, pause/resume, retained completed results, retry classification |
| Process/session recovery | Separately relaunch normally, or manually reboot after recording queue state | Persisted jobs/results and recovery events; completed pages remain cached and are not automatically resubmitted |

Annotate manual actions with times inside the captured window. Never combine multiple renderer/profile/policy changes into one comparison. Let the device return to a comparable thermal state between runs. There is no fabricated target frame rate or translation-time guarantee.

## CPU, memory, GPU, and frame interpretation

`top` shows snapshots, not function attribution. For a CPU-heavy flow, use a profileable build and the official Simpleperf application workflow; save one bounded 30-second capture under a unique filename, then report that exact file. Preserve available mapping files and unstripped native symbols. Report sample count and self/inclusive costs; absent symbols or samples do not establish absent work. CPU samples do not measure network waits or suspended coroutines. [Simpleperf application profiling](https://android.googlesource.com/platform/system/extras/+/master/simpleperf/doc/android_application_profiling.md)

`dumpsys meminfo` separates Java/native/graphics categories and PSS. Compare repeated stable states, accounting for retained OCR sessions/model weights. A growing snapshot alone does not prove a leak. Use a separate bounded Perfetto `heapprofd` capture for native allocation stacks on a permitted debug/profileable build; inspect profiler health and loss before interpreting unreleased allocations. This is an operator-triggered recording, outside the read-only snapshot script. [Android memory diagnostics](https://developer.android.com/tools/dumpsys), [native heap profiling](https://perfetto.dev/docs/data-sources/native-heap-profiler)

For reader jank, record a 30-second Perfetto trace with scheduler/frequency and **FrameTimeline** sources enabled. Ensure the required tracks actually exist before reporting presented-frame p50/p95/p99 or missed deadlines. The script preserves cumulative `gfxinfo` snapshots; do not subtract percentile summaries or attribute the whole history to one run. A custom rendering surface may not be fully represented by HWUI frame statistics. Use the observed display deadlines, including variable refresh rate. [Perfetto Android recording](https://perfetto.dev/docs/quickstart/android-tracing), [Android rendering diagnostics](https://developer.android.com/topic/performance/vitals/render)

GPU memory/counters depend on driver and data-source support. Inspect the actual handset's available Perfetto GPU tracks and identify which process/counter each measurement covers. `meminfo` Graphics is not a complete GPU allocation inventory, and missing GPU tracks are not a zero-memory result. Record support gaps explicitly. [Perfetto GPU data sources](https://perfetto.dev/docs/data-sources/gpu)

## Thermal and HyperOS background checks

Retain raw thermal status alongside throughput and frequency changes. Battery temperature is not SoC temperature; device thermal severity and sensor availability vary. Avoid invented temperature cutoffs or disabled throttling. USB charging changes heat and natural idle conditions; record it, or explicitly configure wireless ADB as a separate operator setup. [Android thermal guidance](https://developer.android.com/games/optimize/adpf/thermal)

Begin with the user's existing HyperOS battery/autostart policy. Record the actual setting names and values from this handset; paths from another Xiaomi model are not evidence. If the operator changes a policy, label the subsequent run separately. Use the app's background-help entry to locate relevant system settings. HyperOS documents autostart controls, while Android Doze/App Standby and long-running WorkManager quotas can defer background work. Immediate continuation under every policy is not guaranteed. [HyperOS autostart documentation](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1624), [Android Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby), [long-running WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

Observe foreground notification Pause, queued/running/waiting states, network retries, and recovery logs. Distinguish manual force-stop from an OS process death; force-stop changes Android scheduling behavior and is not performed by this script. A 30-second screen-off run does not prove natural Doze recovery. Longer natural-idle/reboot checks require operator action and separate before/after captures, with exact elapsed time and charging conditions recorded.

## Result record

For each matrix row, record **pending / pass / fail / unsupported**, exact device/build, run count, fixture/settings, action times, artifact paths, and limits of the measurement. Include errors as evidence. Report observed peak/stable memory, sampled CPU attribution, actual frame counts/percentiles, thermal status, and queue recovery outcomes only when captured. Functional acceptance is no crash/ANR/OOM, no duplicated completed results, preserved overlay geometry, and explainable recoverable queue states. Performance acceptance thresholds should be agreed from physical-device measurements; emulator timings do not supply them.
