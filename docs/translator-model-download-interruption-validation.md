# Minified model-download interruption

`MinifiedPaddleDownloadInterruptionAcceptanceTest` exercises the actual `PaddleModelManager` with a disposable model directory and real loopback HTTP sockets. It requires the official Tiny pack already installed in `app.mihon.benchmark`; these existing files are read and hash-checked only. No installed pack, settings, credential or real translation job is removed or changed. A strict URL allowlist rewrites the pinned artifact requests to the device's local server, with redirects and automatic transport retries disabled. No cloud request or DNS lookup for the official host is dispatched.

After the manager reports at least 65,536 downloaded bytes, the test closes the first detector response's socket while its advertised Content-Length is still incomplete. It requires an IOException, FAILED state, no published pack, and removal of interrupted staging files. A newly constructed manager then downloads the three complete pinned files into the same disposable directory. It must reach INSTALLED, match every registry size/hash, leave the original installed model hashes unchanged, and preserve an unrelated marker during installation.

The auth-free request ledger must contain detector, detector, recognizer, dictionary, with no Range or Authorization headers. This is a **full-file restart**, not byte-range resume. Cleanup closes the local server and removes only the owned disposable model directory; a private report remains under `noBackupFilesDir/translation-model-interruption/<run UUID>/report.json` and is emitted through instrumentation status for collection without relaxing file permissions.

Run only through the device owner, after installing and recording the matching normal minified benchmark/instrumentation APK hashes. No host fixture server or adb reverse is required:

```sh
adb -s 'explicit-device-serial' shell am instrument -w -r \
  -e class mihon.feature.translation.acceptance.MinifiedPaddleDownloadInterruptionAcceptanceTest \
  -e translation.modelInterruption true \
  app.mihon.benchmark.test/androidx.test.runner.AndroidJUnitRunner
```

Retain the instrumentation output's `translation_model_interruption_report_json` / `TRANSLATION_MODEL_INTERRUPTION_REPORT_JSON`, installed APK identity, device runtime page size and artifact hashes. PASS requires the assertions and `owned_model_directory_removed=true`. This establishes actual minified download-manager handling of interrupted HTTP content and verified restart. UI cancellation, Wi-Fi interruption, process death and OEM eviction are separate cases.

On **2026-09-06**, this test passed on both the **4096-byte K90** and the official **16384-byte emulator**, using the exact installed minified benchmark `4e9559a474eb5620c2b743a51e78468db9614c717d817cfb76b1bd7d88e179a7` and instrumentation APK `61e295a873b442dcef9363f16864696f87de95ff0420dd130fd084c58791f6fc`. Each report records the observed 65,536-byte cut, unpublished partial pack, removed staging files, complete verified restart, unchanged source hashes, and successful owned-directory cleanup. The combined ownership/download suite passed in **3.221 s on the K90** and **3.055 s on the emulator**; these are two-method suite times, not isolated download timings. Evidence is `build/translator/validation/private/rotation-worker-model-interruption-report.json`, `private/rotation-worker-native-and-download.log`, and `emulator16k/final-minified/{model-interruption-report.json,native-and-download.log}` beneath the same validation root. The normal R8 build and actual DEX checks passed; [the dated K90 report](translator-k90-validation-2026-09-06.md) retains the broader acceptance limits.
