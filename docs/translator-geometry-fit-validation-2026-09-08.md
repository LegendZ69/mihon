# Translator geometry, fitting and rendered review evidence

Status: implementation complete for the current focused fixes; required visual and live acceptance remains open. Evidence reconciled on September 8, 2026 (Singapore). The installed `geometry-fit-bounds-final` / `source-overlay-fit-v3` benchmark passes 31 affected methods on both K90 and actual 16 KB, plus first/warm and three-minute preview workloads. It resolves the reproduced v2 emulator preview-repeatability failure; the original failure and measurements remain preserved below. The main reader retains its old APK for pending matched old/new comparisons. This continues the [preserved recovery and quality report](translator-quality-recovery-validation-2026-09-06.md) and does not grant overall acceptance or human passage approval.

## Approved scope

Visual review receives the unchanged original and a separately labelled rendered overlay of the same saved page. Newly scheduled visual reviews include that preview by default; existing checkpoints retain their original policy. Pure Paddle, explicit text-only review and ordinary region retry attach no images. A preview is supporting presentation evidence, never another target page or authoritative source text.

Overlay fitting may shrink below the preferred minimum to one original-image pixel, using an enabled application setting. Fixed-size mode remains explicit. Physical rotation is independent of vertical writing. Cached translations gain the improved layout locally without rewriting text, raw OCR, geometry or review revisions. Masks are not expanded to make text fit.

The preview uses the shared Canvas overlay, privately pinned original and imported-font bytes, at most 2 Mi pixels, a 4096-pixel longest dimension and no upscaling. System/fallback font byte hashes remain unavailable; descriptors record the Android build identity and that limitation. Its descriptor records source revision, image identities, resolved presentation, renderer version, scale and deterministic layout diagnostics. Interrupted transport reuses the same evidence. Repairs retain revision guards and Undo; later style changes only mark the recorded presentation outdated.

## Reproductions and current results

Private receipts are under `build/translator/validation/private/`; minified runtime logs are under `build/translator/validation/{k90,emulator16k}/geometry-fit/`. Evidence may contain copyrighted page content and is excluded from public issue attachments.

| Check | Evidence | Current result |
|---|---|---|
| Legacy renderer fragments narrow English | `overlay-fit-before/k90-red-instrumentation-oracle-v2.log` | Reproduced on K90: `KEEP DOOR CLOSED` wraps as `KE / EP / DO / OR / CL / OS / ED`. |
| Legacy preferred minimum clips translated glyphs | Same log | Reproduced: 518 of 647 nearly opaque glyph pixels disappear behind the polygon clip. This is separate from source-mask coverage. |
| New fitting, first device run (superseded oracle) | `overlay-fit-green/attempt-1/` | Historical result: seven methods passed; one failed because the opaque-pixel prerequisite did not observe small antialiased glyphs. The subsequent matched v3 runs below resolve that measurement gap; this earlier result is retained. |
| Matched antialiased glyph/word oracle v3 | `overlay-fit-{before,green}/oracle-v3/` | **PASS for the new standalone renderer on both runtimes:** old APK 0/2 passed, 2/2 failed on each; new APK 8/8 passed on each. Old clipping loses 1,465/1,789 contributing glyph pixels on K90 and 1,126/1,750 on the actual 16 KB emulator. These counts include antialiasing and do not replace the earlier opaque-pixel counts. |
| Durable render checkpoints before implementation | `quality-render-checkpoint-red/` | Four expected failures out of five tests, preserved. |
| Render request wiring before implementation | `quality-render-provider-red/` | Five expected failures out of 26 tests, preserved. |
| Checkpoints after implementation | `geometry-fit-final-host-xml/domain-TEST-*.xml` | **PASS after the cleanup fix and descriptor addition:** 11 render-checkpoint methods, 16 existing review methods and six batch-planner methods. Earlier recorded checkpoint runs had nine, then ten methods; those remain preserved. |
| Provider request wiring after implementation | `quality-render-provider-green.log`; `geometry-fit-host-xml/` | **PASS at the recorded host revision:** 27 review-provider and 21 existing translation-provider methods. Includes indexed original/preview, one candidate identity, token-count/generation parity, limits and cleanup. These are included in the host totals below, not additional unique tests. |
| Captured source-mask and +90° geometry | `emulator16k/geometry-fit/manager-red-captured-fit.log` | **PASS on the intermediate minified APK:** two authored Canvas/GPU geometry methods plus eight fitting/layout methods passed on actual 16 KB. These are authored corrections, not successful live AI repairs. Exact counts and scope are below. |
| Factory evidence: initial execution | `review-evidence-before/emulator16k-red-instrumentation.log` | Historical **FAIL:** ten methods stopped at the `SortedMap` serializer error. The remaining invalid-font method accepted that unrelated rejection; its apparent pass is not font-validation evidence. |
| Factory evidence: serialization repaired | `review-evidence-before/serialization-fixed/emulator16k-red-instrumentation.log` | Historical harness **FAIL:** ten methods stopped at a missing `OverlayTextFitter` class in the standalone package; the PNG bounds method passed. This was a packaging failure, not ten factory defects. |
| Factory evidence: complete harness before focused fixes | `review-evidence-before/serialization-fixed/renderer-complete/emulator16k-red-instrumentation.log` | **Three expected failures and eight passes** on actual 16 KB: malformed imported fonts were silently accepted, missing/corrupt pinned fonts were reused, and changed image identities were reused. |
| Factory evidence after focused fixes | `review-evidence-green/emulator16k-green-instrumentation.log` | **11/11 PASS** on actual 16 KB. Includes those three regressions, unchanged original bytes, bounded PNG, font hashes/deduplication, evidence reuse after source removal, complete identity/revision/region-style checks, duplicate preparation and cleanup. The same eleven methods subsequently passed in the intermediate APK and the `geometry-fit-final` minified APK on both K90 and actual 16 KB, within the 31-method runs below. |
| Reader-equivalent theme resolution | `emulator16k/geometry-fit/manager-red-factory-theme.log` | **2/2 PASS** with real minified resources on actual 16 KB: Light/Dark/AMOLED reader-equivalent colors and supplied system-night configuration. Together with factory methods, this run passed 13 tests. |
| Actual Manager missing-evidence cleanup | `emulator16k/geometry-fit/manager-red/instrumentation.txt` | **Expected FAIL, 1/1:** saved baseline/settings survived and the review became INCOMPLETE with zero attempts/HTTP, then the preview-cleanup assertion failed. The focused terminal cleanup fix subsequently passed on the `geometry-fit-final` minified APK on both K90 and actual 16 KB, including the preview, auxiliary-file and whole-directory assertions. This uses the actual Manager, not WorkManager or OS process-death recovery. |
| Rendered review through Manager/repository/gateway | `emulator16k/geometry-fit/manager-red-rendered-integration-correct-methods/` | **2/2 PASS** on the intermediate minified APK with four local review requests. Pinned input reuse, Undo, manual-revision protection and zero-image Paddle passed. The earlier wrong-method invocation ran zero tests and is excluded from acceptance. |

The required source-mask assertions retain their exact scope: the captured warning's original rectangle covers **3,421/5,395** source-ink pixels; its explicitly authored correction must cover **5,395/5,395** while preserving artwork outside the polygon. The sideways Japanese fixture requires **+90° clockwise** translation and corrected original coordinates. Both requirements now have passing authored Canvas/GPU assertions on the intermediate minified 16 KB run. The same authored assertions also passed on both runtimes in the `geometry-fit-final` runs below. These checks do not establish that an AI review produced the correction.

## Host regression reconciliation

The earlier archived XML in `private/geometry-fit-host-xml/` was independently counted from both suite attributes and individual `<testcase>` elements: **168 tests across 16 suites, zero failures, errors or skips**. Domain contributes 32 methods in three suites; app contributes 136 in 13 suites. The app total includes Manager (28), review/translation providers (27/21), image preparation (10), region editing (8), capture budgeting (7), Paddle wire format (7), image transforms (6), Paddle geometry (6), pricing diagnostics (5), Paddle model management (4), wire geometry (4) and overlay frame geometry (3).

`geometry-fit-host-regressions.log` records `BUILD SUCCESSFUL in 35s`; its SHA-256 is `83a61a59356b18e67ea1fd781268bd15200291cb2de7f03167ba52c3a13556fc`. SQLDelight debug/release migration verification and the aggregate migration task were **UP-TO-DATE** in this run; no fresh migration execution is claimed. The additive `requestedRegionStyles` descriptor was added after those 168 methods ran. The factory/debug compilation subsequently passed in 21 seconds (`geometry-fit-final-factory-compile.log`).

The subsequent archived XML in `private/geometry-fit-final-host-xml/` was independently recounted: **169 tests in 16 suites, zero failures, errors or skips** (33 domain + 136 app). It includes the cleanup fix and requested region styles; the added domain method raises render-checkpoint coverage to 11. `geometry-fit-final-host-regressions.log` records `BUILD SUCCESSFUL in 37s`, SHA-256 `2fb19fb20fe288ca4fc033cd3369efd927d9fbf5db1a91ddb18cd8f8771ef3c6`. Migration tasks again report UP-TO-DATE. The separate `geometry-fit-final` Android runs below subsequently passed the Manager cleanup assertion on both runtimes; the host results alone were not treated as Android proof.

## Historical v2 artifact identity and execution scope

The installed baseline receipt, `private/geometry-fit-installed-baseline/installed.json`, records **4096-byte** pages on K90 and **16384-byte** pages on `emulator-5556`. The baseline main APK hash is `d245eb8e5bd4bb32e7cbbac33974f3dc396bad1577cb25f9bc8bb4185a596811`; the baseline benchmark hash is `b1fe8855fa32ff9180dfee56eefbbd43ce261b6dcc55f582f4dd76e2bc3be998`, with test APK `78472afc26459a4969b587c9a9bd136ba141d05ce452f499aec2c27bbd10488a`. The main installed APK has not been replaced during these standalone checks. Those baseline hashes are not hashes of the new implementation.

The following APK hashes were recalculated from the actual files and matched their artifact receipts. Paths are relative to `build/translator/validation/private/`.

| Artifact | SHA-256 | Verified execution |
|---|---|---|
| `overlay-fit-before/oracle-v3/overlay-fit-red.apk` | `69e765e8118d061b7480f6e60514c1b784f884256b506582ad28e9bb4d679b04` | Two intended failures on K90 and actual 16 KB. |
| `overlay-fit-green/oracle-v3/overlay-fit-green.apk` | `8637c674270560130b9af3b37020147d2c1ce3cf3aeec37168ee9f0a248f57ac` | Eight passes on each runtime. |
| `review-evidence-before/review-evidence-red.apk` | `60fd1007edf343b07830f048235ab4b7635f546d0c24bad9d6da0aeca1789c31` | Serialization blocker; retained. |
| `review-evidence-before/serialization-fixed/review-evidence-red.apk` | `4b6db86bc88551a3ab4bf8270d61a4a91c54fae306a9a661ef8e5aaf2b64e45b` | Missing standalone class; retained. |
| `review-evidence-before/serialization-fixed/renderer-complete/review-evidence-red.apk` | `c3546be438f50c07a7ace287fb0e4865110caa910cee69da97f2f7f617c29d06` | Three behavior failures and eight passes on actual 16 KB. |
| `review-evidence-green/review-evidence-green.apk` | `a2eb08d5da8d1ec010821ffe6778950eaa9e30a0bff33680ae85a92c68ca70df` | Eleven passes on actual 16 KB. |

These are separate test packages containing real renderer/factory classes, compiled and R8-shrunk with their bounded assertions. They do not exercise Mihon's full release dependency graph, reader navigation, native GPU ownership, cloud transport, or background Worker lifecycle. The eight fitting methods include the two matched failures plus six layout-diagnostic methods; repeating them on two runtimes is not 16 distinct methods. Factory cancellation coverage is explicitly pre-cancellation, not a claim of interruption during bitmap decoding. Every passing factory receipt records test-owned directory cleanup and zero provider dispatches.

The `build.py`, source/class hashes and instrumentation logs beside each artifact reproduce its scope. The completed factory RED and GREEN directories contain exact frozen test source copies. The earliest factory test source was subsequently formatted; its original APK/program-class hashes remain authoritative, and `review-evidence-before/test-source-preservation.txt` explicitly qualifies the later source derivative.

After installing only the corresponding isolated artifact and setting `MIHON_QA_SERIAL` to the intended device, the recorded runners are:

```sh
adb -s "$MIHON_QA_SERIAL" shell getconf PAGE_SIZE
adb -s "$MIHON_QA_SERIAL" shell am instrument -w app.mihon.validation.overlayfit/.StandaloneRunner
adb -s "$MIHON_QA_SERIAL" shell am instrument -w app.mihon.validation.reviewevidence/.StandaloneRunner
```

Use each package's matched archived APK before its runner; preserve the old/new logs separately. These commands are reproduction instructions, not evidence of additional execution. The intermediate normal minified benchmark results below supplement these standalone checks.

The `geometry-fit-final` normal minified build completed successfully in **11m 9s**, with exit code 0 and the recorded release, benchmark and benchmarkAndroidTest assembly tasks. It used the benchmark test build type, arm64 ABI targeting, `testOnly=false`, `--no-build-cache` and plain console output; all three R8 tasks executed normally. The archive verified all **28 frozen source hashes** before and after copying. The build-command SHA-256 is `04d2379c328a591c79a9aa30d3454331f9a9b1ec1ae9744f2f6f0d5de0c0e9a8`; the completed build-log SHA-256 is `480aab40e2237d7b6c0538bd0ce9f492e3495d1ddd0f2acbb5f33c379352769b`.

The final archive is `build/translator/validation/r8-instrumentation-abi/geometry-fit-final/`. Its completion manifest, `artifacts.json`, has SHA-256 `07322a82074e48f4d1c555a1c2ebcc9c4099e7c6e3d27f25be86856f0561fa92` and indexes 35 copied artifacts, including generated instrumentation keep rules, R8 mappings, AGP output metadata and host verification reports.

| Final artifact | SHA-256 | Bytes |
|---|---|---:|
| `release.apk` (`app.mihon`) | `ef1713d9f2e11fc1179e283ed82d140b467a1c5d6d71f3a9c2392a9567c9bcb2` | 117,396,532 |
| `benchmark.apk` (`app.mihon.benchmark`) | `aba9dcd393b2838eff6017e7a8b0101a9a47d8d108b90eb9eba5d80af9f16301` | 117,508,824 |
| `benchmarkAndroidTest.apk` (`app.mihon.benchmark.test`) | `e85d84db5a834615fda3506e72c03409e78600a7f69b4fdebcc1dba338d51c15` | 511,317 |

Release and benchmark APKs and mappings are fresh outputs of the final build. The test R8 task also executed, but Gradle correctly reported test packaging **UP-TO-DATE** because its APK bytes were unchanged from the verified intermediate archive. The archive initially rejected that older timestamp and retained the failed staging evidence. A narrow provenance check then verified the exact test APK and retained `mapping.txt` hash against the immutable intermediate archive, together with all seven frozen instrumentation source hashes and identical generated keep rules. The final manifest records this as `identical_incremental_test_provenance`; no timestamp was edited, no R8 task was skipped and no replacement build was invented. The final test APK's unchanged hash does not make the intermediate app's runtime results final-app results.

All three final APK signatures match certificate SHA-256 `e6c08810c0687b9471118d71f8a7ef2614d65acb6b8b94a989fbcfa4dc61d1b9`. Manifest inspection verifies non-debuggable and `testOnly=false` on each; release and benchmark permit shell profiling, and the test APK targets the benchmark with AndroidJUnitRunner. Both main variants contain 14 uncompressed arm64 native libraries, with ELF LOAD alignment at least 16384 and congruent offsets/addresses. All three APKs pass `zipalign -c -P 16 -v 4`. **These are static alignment checks, not actual 16 KB runtime acceptance.** The separate installed-package and 31-method runtime receipts below establish the bounded K90/16384-byte Android results. Archive verification alone does not establish those passes or resolve the separate emulator preview-repeatability failure.

The host archive helper's eleven focused tests pass, including source mutation, overwrite rejection, changed test APK/keep/mapping rejection and normal incremental packaging provenance. Its snapshots and verification reports remain with the archives. The helper performed no ADB, Gradle, provider or runtime action.

## Intermediate minified Android results

The archived `r8-instrumentation-abi/geometry-fit-manager-red/` build was installed normally, without the test-only flag, on `sdk_gphone16k_arm64`, Android 16/API 36, with observed page size **16384**. Host APK bytes, archive hashes and device-installed hashes independently agree:

| Package | SHA-256 | Scope |
|---|---|---|
| `app.mihon.benchmark` | `f0652cb364cc8eae4587c1971ea791abb06b4b13f51f98d24046f862e7102bba` | Normal minified, non-debuggable, profileable intermediate app; precedes the terminal missing-evidence cleanup fix. |
| `app.mihon.benchmark.test` | `e85d84db5a834615fda3506e72c03409e78600a7f69b4fdebcc1dba338d51c15` | Matching non-debuggable instrumentation APK. |

`emulator16k/geometry-fit/manager-red-installed/installed-artifacts.json` binds the installed packages to the runtime. The independent detailed receipt is `private/geometry-fit-manager-red-reconciliation.json` (SHA-256 `5a3e79b2d20e8f0dab2f22769ff7b5af1a3f60cab0c48ebe0ad5d48b1ea1c490`). It records exact method names, log hashes, host XML counts, capture receipts and local request ledger comparisons.

The three passing groups contain **25 distinct methods**: 13 factory/theme, ten captured geometry/fitting, and two rendered-review integration methods. The separate missing-pinned-original method failed at the expected cleanup assertion. The wrong-method integration attempt reported `OK (0 tests)` and zero requests; its successful shell exit is not validation. These intermediate successes cannot be relabelled as tests of the final APK.

The warning replay measured **3,421/5,395** covered source-ink pixels with the deficient input rectangle and **5,395/5,395** with the authored polygon in both actual Canvas and GPU readback. It preserved 1,899,000 inspected pixels outside the mask and 1,271 opaque translated-glyph pixels. The Japanese replay covered **2,388/2,388** source-ink pixels, matched an independently authored **+90° clockwise** Canvas reference within one-pixel rounding tolerance, and lost zero of 831 reference glyph pixels behind the clip. Canvas/GPU output preserved 1,901,119 inspected outside-mask pixels and 819 opaque translated-glyph pixels; both cases restored the original comparison. The 831 reference and 819 opaque-output counts use different color thresholds and are not an omission estimate. These checks cover one selected source-ink region and pixels outside its mask bounds; they do not assess all artwork inside the mask or human passage meaning.

The two rendered-review methods used the actual isolated Manager, repository and gateway with a local synthetic Chat Completions endpoint. The ledger independently records **four review requests and zero chapter-generation requests**: three visual requests containing exactly two image inputs, and one pure Paddle request containing none. Every response has the single original page ID. The recreation case made two visual attempts with identical serialized request-body SHA-256 `63fbe4cffbb18adb7e838bf9b2ec71583ffa4ed881048f20c522fff9aa9ac1a2`; saved evidence descriptors and raw OCR remained unchanged, and Undo restored the baseline apart from its revision without another request. The other visual candidate was superseded by a concurrent manual revision. These are coroutine interruption and graph-recreation checks, not Android process death, Force stop or WorkManager recovery.

There are four unique capture receipts; one was inspected twice across the recreation checkpoints. Passing device assertions decoded the complete, untruncated request/response captures, checked original/preview hashes, descriptor revision/fingerprint, indexed labels, one response target and absence of the known synthetic credential in request headers. The host independently reconciled the receipts against the ledger and checked retained logs/ledgers for that synthetic credential and private-key PEM markers. At that stage only device capture receipts were retained on the host. The raw files remained in the isolated fixture roots; their subsequent explicit collection and independent raw inspection are documented below. This result does not establish credential redaction for live-provider exports.

For the failed cleanup method, before/after snapshots independently agree on the complete saved result and job settings. The review is INCOMPLETE, has no transport attempts, and the ledger is empty. Failure occurs specifically at `Terminal preflight must clean the preview`; the later auxiliary-file and whole-directory assertions were not reached. Fixture teardown removed its synthetic credential. All three local-server runs report their owned server stopped and matching before/after reverse mappings, including the zero-test run.


## Historical v2 minified Android results

The `aba9dcd393b2838eff6017e7a8b0101a9a47d8d108b90eb9eba5d80af9f16301` benchmark and `e85d84db5a834615fda3506e72c03409e78600a7f69b4fdebcc1dba338d51c15` test APKs passed **31 distinct affected methods on each runtime**. These are the same 31 methods repeated on two runtimes, not 62 unique tests. Installed hashes match the final archive, with K90 reporting 4096-byte pages and the official Android 16/API 36 emulator reporting **16384-byte** pages.

| Group | K90 | Actual 16 KB emulator | Scope |
|---|---:|---:|---|
| Factory and reader-theme evidence | 13/13 PASS | 13/13 PASS | Eleven factory methods plus two real-resource theme methods. |
| Captured geometry, text fitting and layout diagnostics | 10/10 PASS | 10/10 PASS | Two authored Canvas/GPU cases, two fitting regressions and six diagnostic methods. |
| Actual isolated Manager/repository/gateway | 8/8 PASS | 8/8 PASS | Automatic review, raw OCR, interruption/manual revision, orphan recovery, metadata-only classification, rendered evidence reuse, Undo, pure Paddle and missing-original cleanup. |

Independent receipts are `{k90,emulator16k}/geometry-fit/final-regressions/independent-reconciliation.json`, relative to `build/translator/validation/`. Their SHA-256 values are `a8ac5de0f34a61a2abc39389cb66e438208355bff1e13df346060fc6b4f0e0e6` (K90) and `1f82ee1fe72072809610efe91390125337360c5ecf77f956914cfdd8d42d2593` (emulator). Both record no errors or missing methods within this matrix. They bind exact method names, logs, installed packages, runtime identities, local request ledgers and result/capture receipts.

The former Manager cleanup failure is **GREEN on both final-stage runtimes**: with only the pinned original removed, the existing raw chapter file and complete saved result/settings remain unchanged; the review finishes INCOMPLETE with no transport attempt or provider dispatch. All preview, auxiliary-file and whole evidence-directory removal assertions were reached. This is actual Manager startup/queue execution, not Android process death, force-stop or WorkManager acceptance. The earlier intermediate RED remains retained.

Each eight-method integration run dispatched **11 synthetic local requests**: five legacy visual reviews, three rendered visual reviews, two text-only reviews and one intentionally seeded original generation. All 11 have matching terminal ledger entries. Thus the complete eight-method suite is not a zero-generation test; the selected review-only/cleanup/retry assertions retain their own narrower request scopes. The rendered recreation checkpoint reuses the same original/preview descriptor and serialized body, Undo restores the baseline apart from its revision, and a concurrent manual revision supersedes a late candidate. Pure Paddle attaches no images. Source geometry/raw OCR/revisions remain covered by the named device assertions and saved checkpoint receipts.

At this historical pre-collector stage, the owned synthetic servers stopped, reverse mappings were restored and synthetic credentials were removed. **Deletion of every isolated fixture/job/report directory was not established by these integration receipts.** This differs from the rendered preview's specific terminal evidence-directory cleanup, which passed. The tests decoded raw app-private capture bodies; the initial host reconciliation covered hashes/identities/ledgers without those raw bytes. The later independent emulator ZIP exports and allowlisted cleanup are recorded below; K90 raw collection and fixture-root cleanup remain pending. Neither these synthetic receipts nor their credential-marker scans establish live-provider export redaction, model correction or human meaning approval.

## Bounded source-overlay-fit-v2 preview performance on K90

The final minified benchmark `aba9dcd393b2838eff6017e7a8b0101a9a47d8d108b90eb9eba5d80af9f16301` and test APK `e85d84db5a834615fda3506e72c03409e78600a7f69b4fdebcc1dba338d51c15` completed both rendering-only performance methods on K90, Android 16/API 36, with actual **4096-byte** pages. Installed-package receipts match the archived bytes. This measures the **new preview feature**; the old `d245`/`b1fe` build has no corresponding preview renderer or test and supplies no old-preview comparison.

Each invocation generated one authored 1400×1497 source with 20 multilingual/rotated regions, then sequentially prepared, validated and cleaned independent review-evidence directories. Its 2,095,800 pixels are close to the 2,097,152-pixel preview ceiling; a single ARGB buffer is 8,383,200 bytes, which is not the renderer's total memory. Source generation is excluded from preparation timing. No explicit GC, graphics/font cache flushing, provider call, credential access, queue job or reader input occurs in this test.

| Case | Completed preparations | Preparation measurements | Elapsed workload |
|---|---:|---|---:|
| First plus three warm preparations | 4 | First: **195.903 ms**; warm: **226.420 / 225.295 / 229.899 ms** | The host instrumentation invocation was 1.920 s; this includes setup and observations. |
| Sequential sustained workload | 508 | p50 **225.239 ms**, p95 **233.758 ms**, mean **255.076 ms**, maximum **1,714.842 ms** | **180.254 device-elapsed seconds** |

The first preparation is the first call of that invocation, **not a cache-flushed cold-start measurement**. Percentiles use nearest rank, `ceil(p×N)`. Sustained timing contains a significant 1.715-second outlier; it is retained without an unproven GC, storage or renderer-cause attribution. Preparation total was 129.578 seconds; the remaining sustained time includes validation, cleanup, three memory observations per iteration and evidence writes. The method's total wall throughput is therefore not an isolated rendering-throughput benchmark.

App memory observations occur before preparation, after preparation and after cleanup. They provide **sampled boundaries**, not instantaneous peaks or an enforced whole-process memory bound:

| App observation | First + three warm | Sustained |
|---|---:|---:|
| Maximum Java-used bytes across iteration boundaries | 29,526,392 | 35,007,320 |
| Maximum native-allocated bytes across iteration boundaries | 62,940,800 | 114,861,952 |
| Maximum process PSS across iteration boundaries (KiB) | 105,608 | 158,532 |
| Final owned cleanup minus first iteration: Java-used bytes | +3,802,720 | −22,679,456 |
| Final owned cleanup minus first iteration: native-allocated bytes | +35,853,328 | −7,927,968 |
| Final owned cleanup minus first iteration: process PSS (KiB) | +42,283 | +5,009 |

These invocations have separate PIDs (21415 and 22701). The first four preparations retained additional allocated memory at their final observation; the three-minute run ended with lower Java/native readings than its first boundary, while PSS remained 5,009 KiB higher. File cleanup and falling boundary readings do not prove absence of leaks, transient peaks or future memory pressure. Java/native heap counters and PSS account for different things and must not be summed as independent physical memory.

The independent host observer completed 60-second and 240-second windows. The cold/warm instrumentation finished between five-second samples: **zero matching process-memory samples** were captured, so its host PSS maximum is unavailable. The sustained observer captured **32 matching-PID memory samples**, with sampled PSS maximum **100,021 KiB**, RSS maximum 227,340 KiB, Java PSS maximum 12,540 KiB and native PSS maximum 44,108 KiB. The reported Graphics PSS category was zero in those available snapshots; GPU memory inventory, GPU timing/utilization and allocation-rate counters remain unavailable. Missing before/after process samples prevent an independent host before/after memory delta.

All 13 cold/warm and 44 sustained power snapshots report AC=true and USB/Wireless/Dock=false. These are charged, discrete-sample measurements, not proof of uninterrupted external power. Current HAL maxima over the **whole observer windows**, including setup/idle, were CPU/GPU/battery/skin **51.5/44.2/36.2/35.65728°C** for cold/warm and **58.7/48.0/36.7/37.0724°C** for sustained. Cached historical temperatures are excluded; these maxima are not attributed precisely to a particular preparation. Root's host invocation bounds fit within the nominal observer windows, and each app's elapsed bounds fit the independent `/proc/uptime` bracket within its 10 ms printed resolution. No exact host-sample-to-iteration clock conversion is claimed.

All **512** completed K90 preparations preserve the same source hash, preview hash, dimensions, revision 42 and 20 diagnostic regions; each records evidence cleanup, and both final reports record complete test-owned directory removal. The source and preview hashes agree across the two K90 invocations. Runtime diagnostics report zero overflow/below-minimum regions for this authored fixture. These assertions do not constitute a human visual/meaning review or a cached-reader pacing test. The generated source and previews were removed by test cleanup: the host independently validates report/JSONL consistency and checksums, while image-byte hash validation remains the preserved on-device assertion.

Evidence is under `build/translator/validation/k90/geometry-fit/final-preview-{cold-warm,sustained}/`. Cold/warm report/JSONL SHA-256: `a244451f12d1fd22ef0f09ad39dae9e44db2702debd54984929769c3f0d90c02` / `6a4f12ec59b56f79b7e4bdf29048fd44a61eb1027561806a006b97f8e0fe3341`. Sustained report/JSONL: `9cce2ae9f3f6563745f54e0988650923b4fe123a756ede9eca8ecfc05c61e354` / `06bddf56d8fe27ecfa5525f52c4d585b563e08d2fe6104b84aa2a91dcb8ce33f`. The archived independent combined summary is `private/geometry-fit-preview-performance-analysis-v1/k90-combined-v1/summary.json`, relative to `build/translator/validation/`, SHA-256 `861e9f3987f47466283279671bace28697e0721f6d15d0a58bb1a086a46aed0d`; it indexes raw observer, action, installed-package and workload artifacts. Eight host regression checks include complete captured evidence plus deliberately failed status, truncation, changed preview hashes, short duration, missing cleanup, malformed fields and incorrect installed identities.

The separate v2 **16384-byte emulator cold/warm run remains recorded as FAILED**, not partially accepted: on its first warm attempt the repeated preview SHA-256 changed from `2e80f3030421f871db990a0d40e5dd257511d06a564dd81bbc99bc1b763fc06a` to `19e470634d63e6ae32983ac00d996388cc6c40e663936f9f1776144f338c1b92`, triggering `ComparisonFailure`. Only the first preparation completed all validation; the second completed preparation before failing hash comparison. Its final report records owned cleanup. Neither the two recorded preparations nor earlier factory/geometry tests are a four-preparation or sustained emulator performance pass. The retained failed report SHA-256 is `67938d509fe77d535f5df92c9f33547dfb08992cbbc47d596fe4110331395d92`; the independently verified v3 results below do not rewrite this failed run.

The preceding measurements belong to `source-overlay-fit-v2` and the immutable `geometry-fit-final` APKs. The focused v3 source change below does not supersede that retained failure or transfer these measurements to its successor build.

## Cold-layout diagnosis and bounded reflow

The separate diagnostic APK reproduced the minified emulator failure with the same source bytes. Independent comparison found 15,364 changed pixels, all inside passage-3, and no changes outside the masks. Its first layout chose 41.992676 px; later layouts chose 42 px. Locales, styles, fonts and presentation fingerprints were unchanged. A traced first 42 px candidate had line ends `[13,27,30]` and a 305.60938 px line in a 298 px frame. Later identical candidates used `[13,25,30]` and fit. The exact framework cache mechanism remains unproven.

| Isolated experiment | Actual 16 KB result | Decision |
|---|---|---|
| SIMPLE break strategy | Reproduced the original first/warm hashes | Rejected |
| Independent paint per candidate | Reproduced the original first/warm hashes | Rejected |
| Disable bounds-aware breaking | Four matching previews, but the unchanged clipping suite lost 28/2,459 glyph pixels | Rejected; never applied |
| One same-size reflow after horizontal invalidity | Four previews exactly match the original valid warm PNG, `19e470634d63e6ae32983ac00d996388cc6c40e663936f9f1776144f338c1b92`; all 20 presentation diagnostics match | Applied after eight fitting methods passed on each runtime |

The accepted change keeps bounds-aware breaking, complete-text checks, Unicode boundaries and shaped-glyph clipping checks. On API 35+, a complete candidate that is horizontally invalid may be laid out once more at the same size. The replacement must satisfy every existing predicate; the retry cannot recurse again. It neither changes masks nor consumes a provider attempt. The renderer version advances to `source-overlay-fit-v3` so local layouts and recorded presentation fingerprints distinguish the change.

Android documents bounds-aware line breaking and drawing bounds from API 35; the one-reflow policy is an application workaround supported by this reproduction, not an Android-documented default. References verified September 7 UTC: [StaticLayout.Builder](https://developer.android.com/reference/android/text/StaticLayout.Builder#setUseBoundsForWidth(boolean)), [Layout drawing bounds](https://developer.android.com/reference/android/text/Layout#computeDrawingBoundingBox()).

The accepted standalone regression APK SHA-256 is `12ab687b9395c88c2a99788b485dd7930d24b509792e02db77af006f934ee06a`. K90 and actual 16 KB each passed all eight unchanged fitting methods. The emulator controller initially expected raw bundle fields from a non-raw invocation; that host receipt remains preserved, with the eight exact source method names independently reconciled in `bounded-reflow-regression/independent-method-reconciliation.json`. This was a controller-format error, not a device test failure. Native Canvas/GPU and normal minified successor checks remain separate.

Runtime artifacts are under `{emulator16k,k90}/geometry-fit/`, including every rejected experiment. Small source/build/analysis recipes and hashes are archived in `private/preview-layout-stability-2026-09-08/`. Formatting, migration checks and the 169-test host suite passed after the focused source change (`geometry-fit-bounds-host-*`); the normal successor build and affected runtime results follow.

## Final v3 artifacts and affected regressions

The normal `geometry-fit-bounds-final` build completed successfully in **9m 56s**, with fresh release, benchmark, test APKs and R8 mappings. The archive verified all 28 frozen source hashes before and after copying; the incremental reuse exception was not needed. The completion manifest at `build/translator/validation/r8-instrumentation-abi/geometry-fit-bounds-final/artifacts.json` has SHA-256 `512113f480792f83b0027efa6969bbff7736cd58475f69cbdb5fdeb87cf3de95`.

| Final v3 artifact | SHA-256 | Bytes |
|---|---|---:|
| `release.apk` | `5bcedfc3bd744c289d3f0e1a52d86f860d56eb201623c415b73b1f4ee22ce6a3` | 117,396,532 |
| `benchmark.apk` | `c769c3c00378d7b31a02bb22437d84ad16873afa7c6960946e9ace3bdef7fb58` | 117,508,824 |
| `benchmarkAndroidTest.apk` | `c91c54947f9f8791d114bf2e07afe1c1c7ea506dc4a32f6b1789395043f5609a` | 511,317 |

All signatures match certificate `e6c08810c0687b9471118d71f8a7ef2614d65acb6b8b94a989fbcfa4dc61d1b9`; the APKs are non-debuggable, `testOnly=false`, and both main variants permit shell profiling. Both main variants' 14 native libraries pass static ELF and ZIP 16 KB alignment checks. These checks are supplemented by execution on the official emulator's actual **16384-byte** runtime, not used as a substitute for it.

The benchmark and test APK hashes were independently read back from both installations. Each device passed **31 distinct affected methods**, with no skips: 13 factory/theme, ten captured Canvas/GPU/fitting methods, and eight actual isolated Manager/repository/gateway methods. This is the same 31-method matrix executed on two runtimes. Each integration run has exactly 11 local synthetic dispatches: one initial generation, five legacy one-image reviews, three original-plus-preview reviews and two text-only reviews. The missing-evidence cleanup, stable identities, raw OCR preservation, revision races, evidence reuse and Undo assertions all passed. These runs do not establish live model correction or WorkManager/OS recovery.

Receipts are under `{k90,emulator16k}/geometry-fit/bounds-final-{installed,regressions}/`. A second independent parser verified exact raw JUnit start/end pairs, all 28 source hashes, installed APK hashes and exit-history deltas in `private/preview-layout-stability-2026-09-08/bounds-final-independent-check/`. During the 31-method windows, each runtime added three `USER_REQUESTED/FORCE_STOP` entries explicitly attributed to finished instrumentation. No new crash, ANR or low-memory exit, or fatal/OOM marker appeared in those selected logs; this is not global crash-free acceptance.

The final source revision also passed **169 host tests in 16 suites**, zero failures/errors/skips, archived under `private/geometry-fit-bounds-host-xml/`; its Gradle run completed in 45 seconds. Formatting passed. SQLDelight migration verification remained UP-TO-DATE; no new migration or fresh migration execution is claimed.

Rebuild command, with JDK 21 and the configured Android SDK:

```sh
./gradlew :app:assembleRelease :app:assembleBenchmark :app:assembleBenchmarkAndroidTest \
  -Ptranslation.testBuildType=benchmark -Pandroid.injected.build.abi=arm64-v8a \
  -Pandroid.injected.testOnly=false --no-build-cache --console=plain
```

The main installed `app.mihon` remains SHA-256 `d245eb8e5bd4bb32e7cbbac33974f3dc396bad1577cb25f9bc8bb4185a596811`, verified again after the final workloads. The v3 release is built and archived, but its reader UI is not yet installed or visually accepted. This preserves the old-reader baseline for the pending matched comparison.

Exact install/test/preview/collection recipes and their hashes are frozen under `private/geometry-fit-root-recipes-final/`. Its README describes restoring the recorded host paths and using a fresh output directory per run. Representative device commands after the matching installation are:

```sh
python3 -B /tmp/mihon-geometry-fit-device-regressions.py SERIAL NEW_TEST_DIR
python3 -B /tmp/mihon-geometry-fit-preview-performance.py SERIAL cold-warm NEW_COLD_DIR geometry-fit-bounds-final
python3 -B /tmp/mihon-geometry-fit-preview-performance.py SERIAL sustained NEW_SUSTAINED_DIR geometry-fit-bounds-final
```

These are reproduction instructions for the recorded synthetic workloads, not evidence of additional execution or permission to dispatch a live review without reserving its cost.

## Final v3 preview performance — bounded local acceptance

The normal minified `geometry-fit-bounds-final` benchmark (`c769c3c00378d7b31a02bb22437d84ad16873afa7c6960946e9ace3bdef7fb58`) and Android test APK (`c91c54947f9f8791d114bf2e07afe1c1c7ea506dc4a32f6b1789395043f5609a`) completed the first-prepare plus three-warm workload and the sequential three-minute workload on both K90 (actual 4,096-byte pages) and API 36 emulator (actual 16,384-byte pages). The report version is `source-overlay-fit-v3`. Installed-artifact receipts match the immutable archive; the analyzer explicitly rejects a different stage/version. These are measurements of the newly added preview feature. The old d245/b1fe application does not provide that feature and cannot supply an old-preview baseline.

The independent reconciliation reads all four reports and JSONL files, verifies their retained hashes and exact equality with the last streamed instrumentation JSON, and confirms 505 K90 plus 1,084 emulator preparations (1,589 distinct review IDs). Every recorded preparation validated its source and preview hash, 1,400×1,497 dimensions, revision 42, all 20 diagnostic regions, zero overflow/below-minimum flags, and owned-evidence cleanup. Each workload reports zero provider dispatches, credential accesses and queue jobs. This does not test live-provider repair or translation meaning.

| Runtime | First prepare (ms) | Warm 1 / 2 / 3 (ms) | Sustained iterations / elapsed | Sustained mean / p50 / p95 / maximum (ms) |
|---|---:|---|---|---|
| K90 / 4 KB | 244.744 | 228.404 / 226.698 / 222.614 | 501 / 180.118 s | 255.665 / 224.960 / 231.688 / **2,519.146** |
| Emulator / 16 KB | 180.061 | 143.981 / 144.225 / 141.030 | 1,080 / 180.197 s | 137.899 / 136.660 / 143.257 / 373.272 |

Percentiles use nearest rank. First prepare is the first renderer invocation in that test process; graphics, font and OS caches were not flushed. Source generation is excluded from prepare timings. The sustained elapsed duration also includes validation, cleanup, memory snapshots and instrumentation overhead; it is not the sum of prepare durations alone. The K90 maximum is retained as an observed outlier; these data do not attribute its cause or support a no-stall guarantee.

| Runtime / workload | Iteration-boundary sampled maxima: Java used / native allocated (bytes), PSS (KiB) | Final owned cleanup minus first iteration's before snapshot: Java / native (bytes), PSS (KiB) |
|---|---|---|
| K90 / first + three warm | 29,687,336 / 62,931,776 / 104,946 | +3,802,720 / +35,853,376 / +41,780 |
| K90 / sustained | 35,066,768 / 114,867,600 / 160,385 | −20,327,240 / **+18,004,608** / **+29,289** |
| Emulator / first + three warm | 15,766,432 / 54,175,680 / 119,952 | +4,802,368 / +37,609,888 / +44,132 |
| Emulator / sustained | 22,213,072 / 115,612,944 / 191,644 | −6,818,368 / −6,624,080 / +22,308 |

Those maxima cover the three snapshots per iteration, with source-generation and final owned-cleanup snapshots retained separately. They are sampled process counters, not peaks or total allocated bytes. Successful file cleanup does not assert that native caches or all process resources were released. In particular, the positive K90 sustained native/PSS delta remains visible; neither a leak nor leak-free behavior is established by this bounded run. The 2,097,152-pixel preview ceiling bounds output dimensions, not total process memory.

The independent host observer has 32 K90 and 33 emulator sustained memory samples whose PID equals the workload PID. Their sampled PSS maxima are 99,920 and 98,306 KiB, respectively, from a different sparse sampling schedule than the app snapshots. Both cold/warm runs have **zero attributable host memory samples**. The emulator's single package sample belonged to predecessor PID 6859, so it is expressly excluded from workload PID 7137; its raw receipt is retained. Missing before/after process samples prevent a host endpoint delta. These host values must not replace or be combined into the app boundary maxima.

All available K90 power snapshots (13 cold/warm, 45 sustained) show AC=true and USB/Wireless/Dock=false. They describe the sampled charging condition, not continuous power throughout every operation. K90 current-HAL maxima over the complete 60/240-second observer windows were CPU 52.7/57.7 °C, GPU 44.6/47.7 °C, battery 36.0/36.6 °C and skin 35.99472/36.88392 °C. The emulator's 13/46 virtual power snapshots also report AC=true; its virtual battery/skin readings are 30.2/30.1 °C and CPU/GPU temperatures are unavailable. Emulator readings do not measure host physical thermals. The broader observer windows and coarse clock brackets do not establish per-iteration thermal attribution or absence of throttling.

No FrameTimeline, reader draw-CPU, allocation profiler or GPU-memory/counter evidence is produced by this workload. A zero `Graphics` PSS category is not proof of zero GPU usage. Reader backend performance, real-page overlay appearance and human meaning review remain separate acceptance work.

The separate post-workload exit reconciliation identifies all four workload PID exits as reason 10/subreason 21/status 0, explicitly attributed to finished instrumentation. The bounded exit-history differences contain no new crash, ANR or low-memory record, and the selected logs contain no fatal/OOM marker. The emulator cold workload's exit was already present in the chosen baseline snapshot; a later collector PID is classified separately. Sparse observers missed both cold workload PIDs, so they provide no attributable memory or logcat coverage for those short runs. Receipt: `private/preview-layout-stability-2026-09-08/bounds-final-independent-check/performance-receipt.json`, SHA-256 `43187a0aabe1001ef09a1f4f64917e378312e9bb2d75fa670794673a38524ec9`. This bounded check does not establish global crash-free acceptance.

**Deterministic image identity.** K90's 505 records all retain source SHA-256 `32f4b0283161dffb8285211e99304f88ab367a9b2c192bac231ec6bde9373b6d` (46,539 bytes) and preview `7b583ad9b5f37b52ea1dc9f27307e21178625ad242f4d36931f33ba54dc668b5` (501,102 bytes). The emulator's 1,084 records retain source `9446dec4ccdb0597403d9e029acb434ff3e88d948776b6e6ed9ff1f5a6777bd2` (45,576 bytes) and preview `19e470634d63e6ae32983ac00d996388cc6c40e663936f9f1776144f338c1b92` (469,784 bytes). Cross-platform image equality is not required because generated Android font/raster pixels differ. Original/preview files were deliberately removed after successful validation: these are reconciled runtime hash assertions, not independent host rehashes of image bytes that no longer exist.

**Historical v2 scope.** The workload source is unchanged between the v2 and v3 archives (`65d722e1d24652da2f144d067e921c46c925ddf9ae83516613600bcb6eb11ea4`), and the K90 source/preview hashes match exactly. Its v2 sustained history was 508 preparations / 180.254 s, p50 225.239 ms, p95 233.758 ms and maximum 1,714.842 ms. These separate sequential runs are descriptive history, not randomized A/B or causal speedup evidence. The v2 emulator's first/warm hash mismatch remains a failed retained run; its partial timings are not a completed performance baseline. V3's 1,084 consistent emulator previews establish the bounded post-fix hash regression result without erasing that failure.

The private durable evidence is `build/translator/validation/private/geometry-fit-preview-performance-analysis-v3/`. It includes both complete summaries, earlier cold-only summaries, the exact analyzer/parser and test sources/results, and `v3-independent-record-reconciliation.json` (SHA-256 `04f9819c6ea6ddc14de9400e1b4d824e03ba51aabb7d804781a8aaaa638b6778`). The independent receipt rehashes 612 retained external artifacts and excludes the predecessor process. Its archive manifest records all copied files and source hashes. The analyzer's ten existing host tests passed. The analysis itself performed no additional Android or provider actions.

## Independent synthetic capture export and cleanup

The signature-matched collector exported only 11 explicitly allowlisted emulator fixture UUIDs: eight final cases and three intermediate cases, including the historical cleanup failure. The host ZIP SHA-256 is `2136f210cdffdfb4ad3fcfeb8fd39cd664792f20611d04f95a08044b2569eeb2`. Independent inspection verified all 58 selected files plus the manifest, file hashes/lengths/CRC, source reports and 15 ledger-correlated requests. All 15 captured Authorization headers were redacted, with no known synthetic key, private-key marker or credential-shaped field leak.

Review captures contain six two-image requests, five legacy single-image requests and three text-only Paddle requests; one initial translation request is separate. Three replay pairs retain identical request bytes. The deliberately malformed 28-byte response was captured completely; the cancelled request has no response body. Their invalid-content and incomplete-capture states remain distinct. This independent inspection covers synthetic captures, not the pending live-provider export.

After that inspection, explicit cleanup verified the unchanged target APK, ZIP hash and file inventory, then removed only those 11 UUID roots and the collection's device ZIP/receipt. The verified host ZIP and historical failures remain preserved. Evidence: `emulator16k/geometry-fit/final-review-collection/{independent-zip-inspection.json,cleanup.json}`.

The final v3 collection is separate: **eight UUIDs**, 40 selected files plus the manifest, ZIP SHA-256 `2330b3d47ba735664ba328671aa59a5db9bb9111c06aee6fe0877aa52df9897a`. Independent raw inspection passed all hashes, lengths and CRC, with exact final-v3 report and ledger agreement: 11 requests comprise three original-plus-preview reviews, five legacy visual reviews, two zero-image Paddle reviews and one generation. Both replay pairs have identical request bytes; all 11 Authorization headers are redacted with no known synthetic key/PEM/credential-shaped leak. The malformed 28-byte response is fully captured, while the cancelled request has no response body; both remain preserved with those distinct states. This remains synthetic coverage, not live-provider export acceptance.

The v3 inspection receipt SHA-256 is `92c8b77b568c7bf41d3c0a8e7574c0e8f9bf7be4514b6d4d6cedbb5f2989630e`. Only after that PASS and a fresh host ZIP rehash, root invoked explicit cleanup with the same APK, UUIDs and ZIP hash. All eight roots and that collection's device export/private receipt were removed; the host ZIP remains. Evidence is `emulator16k/geometry-fit/bounds-final-review-collection/{independent-zip-inspection.json,cleanup.json}`. K90 collection and its sixteen v2/v3 fixture-directory cleanups remain pending after the installation refusal below; their synthetic credentials were already removed by the tests.

After verifying installed APK hashes, cleanup uninstalled the one owned standalone fitting package from K90 and four owned diagnostic/collector packages from the emulator. The benchmark, its test APK, main Mihon and the existing K90 reader harness remain. Seven external preview-report directories and two captured-geometry directories were removed only after every file matched its retained host SHA-256 and each directory contained exactly the expected files. This removed ten known files on K90 and eight on the emulator, preserving all host evidence. Receipts: `{k90,emulator16k}/geometry-fit/bounds-final-owned-cleanup/`. No user credentials, translations or app database were cleared.

Both devices had empty forward/reverse mapping lists after cleanup. The owned 16 KB emulator process and all four final performance observers were verified stopped; the local review runners had already stopped their owned endpoint servers. Receipt: `private/geometry-fit-final-session-cleanup.json`. No benchmark background/reader preference was changed by these final synthetic workloads. The post-workload K90 snapshot records a secure locked, sleeping display and notification UID mode `ignore` (the separate package mode reads `allow`); it is not a fresh UI inspection of the battery/autostart/reader settings. Final UI verification of the previously restored settings remains pending after unlock.


## Handset UI and live-validation status

The attempted K90 collector installation while the handset was locked returned `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`; the exact log is `k90/geometry-fit/final-review-collection/install.log`. This is a retained installation refusal, not a successful collector execution or proof of a deliberate cancellation by the user. No lock or permission policy was bypassed. The failed collector attempt does not provide saved-page UI/baseline evidence.

Selected-page inspector/reader validation, two controlled live reviews, independent before/after geometry and pixel assessment, live capture redaction, and old/new cached-page reader comparisons remain pending for this implementation. Old/new comparison applies to reader rendering in both backends; there is no old preview-memory implementation to benchmark. No new reservation has been created or paid request dispatched in this implementation phase, and the cumulative ledger values below remain unchanged.

## Completion still required

- Compare old/new rendering on identical cached pages in both readers, including matched classic traces. Completed v3 preview workloads on both runtimes do not replace reader presentation, frame-deadline or allocation evidence.
- Complete handset UI/collector setup after normal unlock, preserve actual saved live baselines, reserve costs before two selected controlled-page reviews, inspect geometry and pixels independently of model confidence, and inspect credential-redacted captures. Expand only for missing coverage.
- Verify the saved-page inspector actions and local cached-layout invalidation in the installed reader; retain pending human passage approval. Authored corrected geometry is not proof that a selected live review repairs the saved deficient mask or rotation.
- Finish scoped K90 fixture cleanup and final settings verification after unlock. Keep all prior failures, v2/v3 artifact identities and before/after evidence distinct. Any further source change requires a new verified build and affected reruns.

No new paid requests have been dispatched in this implementation phase. The existing cumulative ledger remains authoritative, including uncertain holds and the S$270 dispatch stop/S$300 ceiling. The dated pricing/SGD refresh below is complete; verify that its date remains applicable before dispatch. Human passage approval remains pending.

## Pricing refresh

The conservative reservation basis remains US$1.50 per million input tokens and US$7.50 per million output tokens, including reasoning. Google's advertised US$0.75/US$3.75 introductory pricing is implemented through 50% credits; those credits do not reduce reservations. [Official pricing](https://cloud.google.com/gemini-enterprise-agent-platform/generative-ai/pricing)

The September 7 ECB reference observations are USD 1.1622 and SGD 1.4716 per EUR, giving SGD/USD **1.266219239374**, rounded upward to 12 decimals. This is a reference estimate, not billed Cloud SGD pricing. [ECB reference rates](https://www.ecb.europa.eu/stats/policy_and_exchange_rates/euro_reference_exchange_rates/html/index.en.html)

Refresh receipt: `private/geometry-fit-ledger-refresh-receipt.json`. Verification uses the host's actual UTC date, September 7 (September 8 in Singapore); an initial local-date/UTC-date validation refusal left the ledger unchanged. Every existing request/reservation was compared before and after and remains identical. Exposure remains S$79.489861 (estimated usage S$1.028348 plus uncertain holds S$78.461513); billing has not been supplied. No new reservation or paid request was created by the refresh.

## Issue tracker update

The final v3 results and remaining device/visual checks were posted to [issue #1](https://github.com/LegendZ69/mihon/issues/1#issuecomment-5574507611). The posted body was read back and verified. The issue remains open; this report does not close overall acceptance.
