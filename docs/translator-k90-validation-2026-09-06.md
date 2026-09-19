# K90 translator validation — 2026-09-06

The final minified release is installed with preserved Mihon data. Its matching benchmark/test pair passed 20 core Android tests (including both rotated-text pixel regressions), two native ownership/download-interruption tests, and four cold-plus-three-warm OCR configurations on the K90. The same pair passed 23 test methods on an actual 16 KB emulator. Actual notification pause and automatic network recovery preserved completed pages, and ten offline style round trips in each reader restored the recorded viewport. **Acceptance remains in progress:** human meaning review, live Express/OpenAI access, provider geometry corrections, force-stop, post-thaw queue recovery and observed HyperOS eviction remain open. **The actual screen-off Worker test failed its completion timeout**, with HyperOS freezing the benchmark process under the recorded policy. Final-release 30-second traces and the completed unplugged reader/natural-rest observation are recorded below; neither closes the screen-off Worker failure.

Published update: [GitHub issue #1, validation results](https://github.com/LegendZ69/mihon/issues/1#issuecomment-5558255233). The issue remains open for the stated acceptance gaps.

The [AI-assisted passage review](translator-passage-review-2026-09-06.md) separates identified semantic issues from pending human review. The [mode comparison](translator-mode-comparison-2026-09-06.md) contains per-run transcription, geometry, usage and semantic caveats. The [dated device observations](translator-device-observations-2026-09-06.md) contain physical measurements and trace limits. The [capture reviewer](translator-capture-review.md), [controlled corpus](translator-fixture-corpus.md), [device procedure](translator-device-validation.md), and [September 5 baseline](translator-validation.md) provide reproducible steps and earlier evidence. Older statements that no handset or actual 16 KB runtime was available describe that earlier baseline.

## Device and artifact boundaries

| Measured property | Value |
| --- | --- |
| Manufacturer / market name | Xiaomi / REDMI K90 Pro Max |
| Model / device / SoC | `25102RKBEC` / `myron` / `SM8850` |
| Android / SDK | Android 16 / API 36 |
| HyperOS / security patch | `OS3.0.308.0.WPMCNXM` / 2026-06-01 |
| Kernel page size | **4096 bytes** |
| OS-visible memory | `MemTotal: 11286068 kB`, approximately 10.76 GiB |
| Physical application | `app.mihon` |

These properties do not independently verify the requested 16/512 SKU or establish that this is the latest HyperOS release. Standby bucket was observed as `5`; complete HyperOS background-policy/recovery acceptance remains pending.

| Application artifact | SHA-256 | Scope |
| --- | --- | --- |
| Final installed release, 117,314,580 bytes | `2d6246d0bb2c0c3af96c674b90f0e9dff90446a0f14c0951c7e56f4c476ee2be` | Normal install, device hash/certificate verified, data preserved; polygon-aware rotation fitting |
| Final installed benchmark, 117,410,492 bytes | `4e9559a474eb5620c2b743a51e78468db9614c717d817cfb76b1bd7d88e179a7` | Minified `app.mihon.benchmark`; final K90 and 16 KB acceptance below |
| Final installed instrumentation, 389,229 bytes | `61e295a873b442dcef9363f16864696f87de95ff0420dd130fd084c58791f6fc` | Exact matching test APK; on-device hash verified |
| Original delivered release | `5e4dcef1ed0e0c2dd49d5847679e9484b824dcc441af37873958299865be77d2` | Initial four AI modes and twelve-page AI provider runs |
| Fixed minified release, 117,314,576 bytes | `3bcd37c58499cf1e06013dc3a0a54213e8b6b05adc3d34b6c0c9ce6f2c627462` | Physical Small Paddle, offline reader and recorded scrolling observations |
| Minified benchmark checkpoint, 117,394,120 bytes | `a9eae61412d626af767f30bf08c51241199c9810bee728f27e8ac415ed0a4cca` | 18 core Android tests passed on K90; separate package and instrumentation keeps |
| Matching benchmark instrumentation, 328,309 bytes | `031d3b30aaba26748aece22c773946751ddc7f5f5e452d5445e607a3c2512006` | Exact pair for the checkpoint's 18-test result |

All three final APKs installed without `-t`; binary manifests omit `testOnly`, and both applications remain nondebuggable. Normal release and benchmark R8 completed in **9m26s** without task exclusions. Release excludes generated instrumentation keeps; benchmark exact keep rules and the actual APK DEX retain the required Trace, Worker and model-manager APIs. The final release also passed 16 KB ZIP alignment, which is distinct from the K90’s 4 KB runtime. Artifacts, compressed matching mappings and checks are archived under `r8-instrumentation-abi/rotation-worker-final/`.

The original pulled package certificate SHA-256 is `e6c08810c0687b9471118d71f8a7ef2614d65acb6b8b94a989fbcfa4dc61d1b9`. It identifies the local signing key, not the official Mihon certificate. Preserve each observation's installed APK hash; a host git revision is not proof of the running artifact.

The benchmark checkpoint passed **18 core Android tests in 2.563 seconds** on the 4 KB K90. Its opt-in model/pipeline tests failed before native OCR because `huggingface.co` resolved to `0.0.0.0`/`::`; a fresh-process retry reproduced this. Both Mihon builds showed DNS-over-HTTPS disabled and the phone had an active RethinkDNS VPN. The user enabled the benchmark app’s universal-policy bypass, and an app-specific trust rule for the official model host restored downloads. Four native model configurations subsequently passed; the initial DNS failures do not establish an OCR compatibility failure. A local queue graph-recreation test passed, while the modes test reported nine transport starts for five successful singleton requests; the host ledger recorded five generation dispatches. The host fixture closed HTTP/1.0 connections without declaring `Connection: close`; its corrected transport and a corrected partial-result test expectation subsequently passed the queue matrix. See the [queue acceptance report](translator-queue-acceptance-2026-09-06.md).

An intermediate release SHA-256 `3cbce9e29e3fa96008c32448dc6972543a0e3885b2c9840aed6440af20679233` was **not installed**: installation correctly failed `INSTALL_FAILED_TEST_ONLY`. AGP's injected target ABI enabled `testOnly` when the explicit override was omitted. Its binary manifest and failed-install receipt are retained; a successor build explicitly sets `android.injected.testOnly=false`. Passing signing/alignment checks on that intermediate APK does not make it the delivered final release.

An official ARM64 `sdk_gphone16k_arm64` emulator actually reported **16384 bytes**, Android 16 / API 36. Its baseline debug APK SHA-256 is `770a8604a3cc5e238c06e4221876b71cb9166e4c189b5066a2dccf2be6a99f08`; Android test APK SHA-256 is `51ebd0e5536a7388ba36d1d7c7109903c98072a0d5858a0fbe0f55da68bd7b14`. All **14 baseline Android tests passed in 23.944 seconds**. This covers only their exercised decoder/WebGPU/persistence/image-preparation paths, not later builds or every native OCR model.

The updated debug APK SHA-256 **`f283fd63e486060afd2845a5cccdb56107e5e19c0295d718937b5882deb2c64c`** and test APK **`a1b419935d57aa337fa755bccc215bade41b758d281c6e3410f6324317a0e0bb`** subsequently passed **18 Android tests in 3.286 seconds** on the same actual 16 KB runtime. The recorded suite includes updated overlay/persistence regressions and migration from database version 15. Its crash-buffer artifact is retained separately. This validates those debug paths; it does not reassign results to the physical minified release or establish every OCR model.

The **final minified pair `4e9559a4…` / `61e295a8…`** subsequently passed **23 test methods** on that official **16384-byte** emulator: 20 core tests in **7.512 s**, native ownership plus Tiny download interruption in **3.055 s**, and the OCR-profile method in **44.233 s**. That last method exercised Tiny/Small/Medium English and the explicit Small Korean recognizer, each with one cold and three warm runs. Installed APK hashes were verified; the collected crash buffer was empty. This establishes the exercised minified native paths on 16 KB, not physical K90 performance or every possible OCR input. Evidence: `emulator16k/final-minified/`.

## Acceptance matrix

| Area | Status | Observed evidence and remaining boundary |
| --- | --- | --- |
| Physical identity / package / signing | Pass for recorded artifacts | Actual properties and hashes retained; physical runtime is 4 KB. |
| Vertex service-account access | Pass for bounded sample | Requested model `gemini-3.8-flash`, global/shared; successful live results retained. |
| Express / OpenAI account access | Pending | No live account acceptance established; no model substitution. |
| Twelve-page controlled AI | Partial acceptance | 12/12 originals represented by 15 successful tiled requests; 29 expected source regions matched exactly or with whitespace-only differences. Human meaning and final overlay inspection remain pending. |
| Max / Halving / Custom | Bounded live pass | Five pages completed in each; Custom recovered a timed-out singleton without resending the first four pages. Halving whole-chapter success does not exercise recursive splitting live. |
| Small Paddle on minified handset | Functional completion; quality gaps | 9/12 initially; the focused schema fix retried only the three failed images and reached 12/12. Local Korean recognition omissions remain quality failures. |
| Small Paddle + AI / Tiny / Medium / Korean recognizer | Bounded minified pass | Hybrid completed the identical twelve pages in fifteen image requests. Tiny, Small, Medium English and the explicit Korean recognizer each passed cold + three warm native runs. Meaning/geometry acceptance remains separate. |
| Geometry / masks | Focused rotation pass; provider geometry pending | Both final rotated-glyph pixel tests pass on K90 and 16 KB. Provider warning-box clipping and sideways Japanese rotation remain separate visual/manual issues. |
| Meaning / names / negation / relationships | Human review pending | AI-assisted reviews cover controlled fixtures and three selected real Japanese pages; panel order and speaker-commitment uncertainties remain, and no human reviewer has signed off. |
| Offline cached viewing | Bounded final-release pass | Ten style round trips per reader completed with Wi-Fi/cellular off at recorded start and end and airplane mode unchanged; settings restored. No continuous network observation or provider-ledger claim. |
| Style / comparison / region-only retry | Bounded passes; wider styles pending | Final WebGPU and classic each changed system/monospace font and mask opacity ten times and restored every viewport hash. Earlier both-reader comparison/chapter cycles and two consecutive region retries retain their earlier APK attribution. Imported-font and broader style coverage remain open. |
| Retained work / retry | Bounded passes | Live retries and earlier actual minified crash/relaunch preserve completed pages. Final actual notification pause/resume and automatic network recovery also preserve the committed result and retry only the unfinished page. Force-stop and observed HyperOS eviction remain pending. |
| Model downloads / background / natural idle | Download/notification/network pass; screen-off fail | Tiny truncation/restart verifies integrity and preserves installed sources. Notification/network Worker cases pass. Actual screen-off Worker completion timed out. The separate 794-second natural-rest observation completed with the reader PID retained; active-queue recovery and process eviction remain unestablished. |
| Performance / memory | Follow-up required | WebGPU ownership fix reduced the focused native regression and follow-up retained allocation. The new three-minute run had nearly flat Native Heap PSS but about 24.7 MiB Graphics growth; residual growth and broader workloads remain limited evidence. |
| Thirty-second reader traces | Final full-window measurements; classic pacing issue | Final WebGPU: 3,470 complete frames, p95 4.281 ms, no jank/deadline labels. Final classic: 3,504 frames, p95 7.116 ms, 1,249 Buffer Stuffing/Late Present labels; one expected deadline exceeded by 0.043 ms. Separate sequential observations, not guarantees. |
| Three-minute workload | Final unplugged observation complete | Final release: 113 gestures over 181 s, eleven samples with all four power flags false; Native Heap PSS −1,376 KiB, Graphics −68 KiB, total PSS +4,397 KiB. Earlier charging/artifact observations remain separate. |
| 16 KB runtime | Final minified bounded pass | Actual 16384-byte emulator passed 23 test methods on final 4e9559/61e295, including 16 OCR cold/warm sub-runs, ownership and Tiny interruption. |
| Cold + three warm OCR/profile / ten switching cycles / unplugged | Bounded observations complete | Final four-profile K90 OCR, ten offline style round trips per reader, and final unplugged workload plus 794-second rest are recorded. Earlier incomplete idle evidence is not promoted; OEM eviction and active-queue survival are not inferred. |
| Host tooling suite | Pass for final build | **122 Python tests passed in 14.274 s** in the final build check; separate from app units and Android runtime tests. |
| App unit tests / final release | Pass | **166 unit tests** (97 app + 64 domain + 5 OCR), both SQLDelight migration tasks and Spotless passed. Normal final R8 build passed; all three exact APK hashes were verified after installation. |

## Final minified handset acceptance

The installed **4e9559a4… benchmark / 61e295a8… test pair** passed **20 core methods in 3.057 s**, including the two unchanged pixel tests that failed on the preceding 6ae/66dc pair. The red cases lost 158/3,417 opaque glyph pixels at −17° and 160/3,472 in a tilted quadrilateral; the final tests require zero lost opaque glyph pixels in their rectangular/tilted fixtures across the recorded rotations. This validates the focused autofit fix without expanding the source mask. It does not certify arbitrary imported fonts, minimum sizes or provider polygons.

The same pair passed **two native/download methods in 3.221 s**. The ownership regression again retained positive whole-process native allocation: **2,531,856 bytes** over its second 512 warmed frames, below the 4 MiB guard, with original/overlay pixels passing. The Tiny download test observed **65,536 bytes** before deliberately truncating a real loopback socket; `ProtocolException` produced FAILED state, no committed partial pack and no retained staging directory. A fresh manager then restarted all files, verified their official hashes, preserved the existing installed sources and removed only its disposable destination. Four local requests were recorded: interrupted detector, complete detector, recognizer and YAML; no HTTP Range, credentials or cloud forwarding. These are separate from real Wi-Fi interruption, UI download cancellation and process death.

The final K90 OCR-profile method passed in **4.981 s** with these measured runs:

| Profile / recognizer | Cold ms | Warm 1 / 2 / 3 ms | Largest sampled native allocated bytes |
| --- | ---: | --- | ---: |
| Tiny, PP-OCRv6 Tiny | 204 | 74 / 64 / 69 | 124,600,096 |
| Small, PP-OCRv6 Small | 234 | 127 / 111 / 116 | 257,183,824 |
| Medium, PP-OCRv6 Medium | 686 | 344 / 328 / 337 | 523,495,728 |
| Small detector, `korean_PP-OCRv5_mobile_rec_onnx` | 196 | 90 / 79 / 82 | 235,935,792 |

These are the same 960×320 synthetic English/Korean fixtures and CPU inference path, with four CPU threads and a 128 MiB decoded-image budget. Cold means new native sessions; OS page cache is not flushed. Model download/revalidation is excluded from inference timings. Plugged value **1 (AC)** was recorded. Native allocations are whole-process samples, not isolated model size or continuous peak; GPU allocation counters are unavailable in this test. Report/log: private `final-handset-ocr-profiles-report.json` and `final-handset-ocr-profiles.log`. The older profile table below remains a distinct checkpoint, not a competing final result.

The actual **notification Pause** case passed in **6.729 s**. Root tapped the scoped Pause action exposed by the HyperOS notification; the job became PAUSED with its exact first result/revision intact and its second batch INTERRUPTED. The instrumentation then invoked the same per-job resume action used by the queue UI, creating a real Worker; it did not call the queue coroutine directly. The independent ledger records three dispatches: completed page once, unfinished page twice. The cancelled delayed request later recorded disconnection. Two earlier attempts timed out at 60 seconds before a timely operator pause; their failures and successful cleanup remain preserved. They are not reclassified as passing runs or app crashes.

Actual **Wi-Fi/default-network loss and recovery** passed in **31.323 s**. The app observed offline state and the job WAITING with the first result unchanged; the same WorkManager ID moved RUNNING → ENQUEUED → SUCCEEDED, advancing from attempt 1 to 2 after connectivity returned. Recovery made no manual resume/start/state rewrite. The independent ledger again has three dispatches and only the unfinished page repeats. Wi-Fi settings were restored exactly; airplane and cellular settings were unchanged. Approximately 27.256 seconds between observed online state and completion is one scheduler observation, not a guaranteed recovery time. Both cases removed the synthetic credential/disposable job and preserved global translator settings. Raw WorkInfo stop reason `-256` is not labeled as OEM eviction. See the [Worker protocol](translator-worker-acceptance-validation.md); active instrumentation limits inference about unattended background survival.

Final release **2d6246d0…** also completed **ten WebGPU style round trips in 133.596 s** through the framework UI harness **968c4e7213b99db18ce48ccdb1ed00417b5f6b1c1a6b8406ad543ccb07bc1e47**. Each round changed system font/opacity 1.0 to monospace/opacity 0.35, then restored the originals. Twenty persisted-value readbacks and all 22 PNG viewport recomputations agree: changed pixels differ from baseline, and every restored viewport matches it. Chapter 002/page 7 was retained. Wi-Fi/cellular-off settings were recorded at start and end, and the original network settings were restored. This is endpoint evidence, not continuous connectivity monitoring or a provider request ledger. It covers this style pair, not all fonts/styles. Private `framework-style-ten-offline-reconciliation.json` contains the independent checks.

The same final release and framework harness then completed **ten classic-reader style round trips in 136.681 s** (host command 137.900463 s). All 22 PNG viewport hashes, 20 actual Apply/persisted-value readbacks and ten chapter/page/orientation restorations independently matched; each changed viewport differed and each restored viewport equaled the classic baseline. The original network settings were restored after endpoint-recorded offline operation. Private `framework-classic-style-ten-offline-reconciliation.json`, SHA-256 `15c688cd16426877960f3dab8b09ac4e113966bd82fddf8872f1bad81efef8dd`, retains the checks. WebGPU was restored through Settings afterward; the final controlled reader had no classic RecyclerView and its inspected page-7 viewport matched the prior WebGPU baseline.

The actual **screen-off Worker case failed**: after the test observed a noninteractive display, its 90-second completion wait expired (**101.515 s** total JUnit run). The report still shows one committed result and the job TRANSLATING/Worker RUNNING. The host fixture recorded the delayed second response as sent after approximately 45 seconds, which does not prove the app consumed it. Root woke the display in the host cleanup after approximately 100 seconds; the final failure snapshot is interactive. The synthetic credential/job were removed and global settings restored. This is a recorded functional acceptance failure, not a passing test merely because no crash/ANR/OOM was reported. The scoped system log records GreezeManager acknowledging the foreground service, freezing benchmark PID 18464 under the existing screen-off policy, disabling its WorkManager wake lock and destroying its TCP sockets, then thawing the same process at screen-on. This is an observed OEM process freeze, not process eviction or an app crash; it does not establish how other HyperOS policy settings would behave. Evidence: private `worker-screen-off-0bcbbd9a-de30-4b43-8e57-a57cfbe92bdc/`.

The separate final unplugged observation completed as described below. Force-stop, post-thaw provider-queue recovery and HyperOS process eviction remain unestablished.

## Final release reader traces

Both captures used release **2d6246d0…**, the same process **14474**, controlled chapter 002/page 7, restored system font/opacity 1.0, and alternating 500-pixel, 500-ms swipes with reader controls hidden. The recordings were sequential and charging at 100%, with thermal global status 0. WebGPU recorded 40 gestures and classic 38 within their trace windows; this is comparable procedure, not identical scheduling or a randomized benchmark.

| Measurement | WebGPU | Classic |
| --- | ---: | ---: |
| Retained trace seconds | 30.030285 | 30.004225 |
| Complete, uniquely attributed app frames | 3,470 | 3,504 |
| Actual frame duration p50 / p95 / p99, ms | 3.529 / 4.281 / 4.798 | 4.426 / 7.116 / 7.377 |
| Maximum actual duration, ms | 8.294 | 13.236 |
| Buffer Stuffing / Late Present frames | 0 | **1,249 (35.64%)** |
| App Deadline Missed / Dropped Frame labels | 0 / 0 | 0 / 0 |
| Actual end after unique expected deadline | 0 | 1, by **0.042668 ms** |
| Sampled process GPU memory, bytes | 291,151,872–355,364,864 | 353,275,904–373,690,368 |

The classic presentation labels comprise 293 Full and 956 Partial classifications. They must not be hidden behind the absence of App Deadline Missed labels or treated as proof of translated-text CPU failure. GPU process-memory counters are available; GPU utilization/render-stage timing remains unavailable. Native/Java/PSS snapshots and process GPU counters describe different quantities and are not added together.

Both traces retain approximately 30 seconds of continuous scheduler coverage without interior per-CPU gaps. All per-buffer loss/overwrite/discard and data-loss counters are zero. The raw diagnostics still contain 36 setup notices in each trace and legacy discarded-chunk values 6/7; those notices are retained rather than silently removed. Capture overhead, process/cache history and one observation per backend limit performance conclusions.

Private trace evidence: `traces/20260906T084143Z-final-webgpu-30s-c68d1527/` (analysis SHA-256 `498b3a0ee01b221cb35dfae08c6a4d1afa2dcabc954db03410fa4edfe7a1f7ec`) and `traces/20260906T084437Z-final-classic-30s-9e2f999f/` (analysis SHA-256 `4633f30b8258df4237676a84ca11ec7c9c3930f27f5cb2d9e75fb0c36736b9d6`). Each contains the exact trace hash, SQL, outputs, action mapping and measurement limits. Reproduce with `scripts/translator_perfetto_capture.py --duration 30 --profile frames` and the explicit package/serial, then run `scripts/translator_frame_metrics.sql` against that trace.

## Final unplugged workload and natural rest

Release **2d6246d0…** completed the corrected recorder protocol on the K90. Reader boundaries were **08:52:06–08:55:07 UTC**, with **113 completed gesture commands over 181 seconds**. All **eleven reader snapshots** record AC, USB, wireless and dock power false. The sampled power evidence and the user’s disconnect confirmation are retained; they do not prove continuous power state between snapshots.

| Snapshot memory, KiB | Unplugged before | Unplugged after | Change |
| --- | ---: | ---: | ---: |
| Total PSS | 271,310 | 275,707 | +4,397 |
| Total RSS | 432,324 | 436,800 | +4,476 |
| Java Heap PSS | 14,448 | 20,240 | +5,792 |
| Native Heap PSS | 57,420 | 56,044 | −1,376 |
| Graphics PSS | 97,084 | 97,016 | −68 |
| Native allocated | 68,396 | 67,490 | −906 |

Current HAL maxima over unplugged samples were **CPU 45.1°C, GPU 41.5°C, battery 36.9°C and skin 35.11996°C**; global thermal status was 0 at each recorded point. This is one cached-reader workload with snapshot overhead and existing process/cache history. GPU utilization/counters and frame deadlines were not collected in this unplugged run, and these snapshots do not establish retained allocations or a continuous peak.

After Home/Sleep, the recorder wrote its checkpoint and exited at **08:55:08 UTC**. The explicit finish stage at **09:08:22 UTC** recorded **794 seconds (13m14s)** of suspend-inclusive boot time; the UTC interval agrees. It passed its same-boot guard and wrote COMPLETE. An independent host read immediately before finish also matches the retained start boot ID. Every nonempty PID snapshot, including reconnected-before-input and resumed, contains **14474**.

USB reconnection preceded the final snapshots and may itself have changed idle/display state. The retained main-UID system events show a delayed-freeze action and a thaw on Activity Start; there is no explicit successful freeze/eviction event for this UID in the retained interval. The bounded log begins about 14 seconds after the first reader snapshot and contains no matching main-process fatal/ANR/OOM/kill event; that initial log gap and bounded history remain limitations. This is a completed natural-rest observation with process retention, not proof of Doze, uninterrupted screen-off, process eviction or recovery of an active provider queue.

After the recorder’s explicit wake/launch, the framework inspector confirmed controlled chapter 002/page 7. Assistant screenshot review found cached translations/masks visible; the known narrow Japanese region still wraps awkwardly. Original network settings remain Wi-Fi 2 / airplane 1 / mobile data 0, with no adb reverse rule. This cached-reader result does not change the separate Worker screen-off FAIL.

Raw evidence: `handset/mihon-unplugged-final-20260906T085125Z/` (96 files); private `final-unplugged-preflight.json`, `final-unplugged-host-interval.json`, `final-unplugged-collection-receipt.json`, `final-unplugged-reconciliation.json` (SHA-256 `fcb9db1d4bcd8b6be2699ea1bb60a3399efd7f8f65009f7fa644390184ad2b2d`) and `final-unplugged-resumed-review.json`. Independent reconciliation verified all 97 input-hash entries and the separate boot/finish receipt; private `final-unplugged-independent-addendum.json` has SHA-256 `bfbab6c91dd46fb5ddf2dd31fb45417338c0b6eeaf5cb972eee1248ca31dc312`. The [recorder/analyzer procedure](translator-unplugged-capture.md) preserves incomplete runs and distinguishes protocol completion from functional/performance acceptance.

## Exports and temporary-setting restoration

A final scoped audit parsed all eleven existing capture-export archives and found **194/194 authorization fields redacted**, with no configured private-key/token/credential-field/query-pattern findings. Archives overlap, so these are not unique requests; the audit does not rule out arbitrary unknown secrets embedded in content. The private audit is `live-export-redaction-final-audit.json`, SHA-256 `93c070fbb77645b292c4cec8be11ce611630f5f965f9e0d0fc3317319f980a07`. No vault or imported credential file was read.

Temporary benchmark notification permission was restored to denied through Android Settings; the task-created `tcp:8765` reverse was removed and its owned fixture server stopped. The temporary benchmark Hugging Face domain rule was deleted through RethinkDNS. The user’s Bypass Universal preference remains; DoH stays disabled and original Wi-Fi/airplane/cellular settings were restored. Controlled-series system font/opacity 1.0 and WebGPU were restored. Natural observation does not alter OEM background policies.

## Provider runs, quality and accounting

The controlled corpus uses original synthetic text-and-shape fixtures from `mihon-controlled-translation-v1`, with known image hashes and source polygons. Three byte-preserving page copies from the already available Japanese chapter **神様ですげェむ(1) (GANMA!), V1** were additionally translated in a separate local validation series. The originals were preserved; private provenance records SHA-256 and dimensions. Human passage evaluation remains pending.

| Run | Original pages | Successful generations / attempts | Input tokens | Visible output | Reasoning |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vertex, five pages | 5 | 5 / 5 | 8,780 | 1,764 | 8,568 |
| Max, five pages | 5 | 1 / 1 | 6,144 | 1,858 | 2,224 |
| Halving, five pages | 5 | 1 / 1 | 6,144 | 1,858 | 3,373 |
| Custom, batch size two | 5 | 3 / 4 | 7,462 known | 1,858 known | 4,338 known |
| AI Vertex, full twelve pages | 12 | 15 / 15 | 26,441 | 3,635 | 4,676 |
| Small Paddle, initial twelve pages | 12 | 12 HTTP successes / 12, 9 accepted | 10,127 | 3,998 | 7,622 |
| Small Paddle, failed-image retry | 3 | 3 / 3 | 2,777 | 219 | 1,125 |
| Small Paddle + AI, twelve pages | 12 | 15 / 15 | 28,751 | 3,803 | 5,747 |
| Selected real Japanese pages, automatic tiles | 3 | 6 / 6 | 10,653 | 2,773 | 1,913 |
| Same real pages, optional tiling off | 3 | 3 / 3 | 5,349 | 1,866 | 576 |

CountTokens calls are separate diagnostics, not translation responses or additional generation usage. Reasoning is separate from visible output; billable output estimates include both. Halving's 3,995 cached input tokens are a subset of input, conservatively estimated at ordinary input rates. Custom's 120.005-second timeout has unknown usage; its known totals cannot settle the full attempt history.

The imported snapshots for the first four modes were configured **HIGH**. Max/Halving/Custom wire requests confirm HIGH; original Vertex capture files lack the request body and cannot prove its wire value. Max did not elevate a MEDIUM setting. The twelve-page run has fifteen verified MEDIUM wire requests. These are separate settings cohorts, not a controlled reasoning-level comparison.

The [automatic mode review](translator-mode-comparison-2026-09-06.md) records transcription, role differences, image/tile identity and rectangle-ink coverage. It flags the initial warning box's 63.4106% ink coverage; traditional Chinese relationship wording with an unstated possessor/younger-sibling nuance; rotated Japanese geometry returned with zero rotation; and overlapping long-strip Korean promise translations requiring final retained-result inspection. The twelve-page run correctly returned zero regions for the blank page and excluded the advertisement/watermark while including adjacent story text. These observations do not certify meaning, masks or final reader merge behavior.

After settling both real-page runs, two region retries and the two-page hybrid check, private `hybrid-merge-two/spend-settled.json` reports **S$0.798160 estimated usage**, **S$78.461513 outstanding uncertain reservations**, and **S$79.259673 conservative exposure**, including the settled two-page hybrid merge check. Outstanding exposure includes the earlier diagnostic reservation (S$13.076919) and the whole Custom reservation (S$65.384594), retained because timeout usage is unknown. These are local estimates/reservations, not billed spend or a spending target; later reservations must be read from the private ledger.

**No billed amount is known.** Zero recorded billing means no bill entry has been supplied. Estimates use dated official shared/global prices before promotional credits, and **1 USD = 1.266907589056 SGD**, derived from ECB 2026-09-04 reference observations and verified September 6. New dispatches stop at S$270 conservative exposure, leaving headroom under the authorized S$300 ceiling. [Official pricing](https://cloud.google.com/gemini-enterprise-agent-platform/generative-ai/pricing), [ECB rate feed](https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml).

A scoped audit of five actual live-export ZIPs found **68/68 authorization headers redacted** and zero credential-shaped matches in the configured scan. This is evidence about those exported files and detector patterns, not proof that arbitrary unknown secrets can never appear in source content. Private audit: `live-export-redaction-audit.json`.

## Physical runtime observations

Small Paddle ran approximately **10:40:22–10:40:52 SGT**, inside a 120-second observation. Five periodic samples fall within active work; the rest include post-job activity and idle time. Maximum sampled PSS/RSS was **1,220.86/1,382.05 MiB**, falling to **215.59/376.20 MiB** at the final snapshot. Current HAL active maxima were CPU **72.6°C**, GPU **57.0°C**, skin **35.62°C**. Cached older sensor values are excluded. Charging was recorded throughout; this is not a 120-second sustained inference benchmark.

The three-minute WebGPU run recorded **235 gestures**, 10:51:08.940–10:54:08.943 SGT. It crossed controlled chapter boundaries; earlier five-page-only action labels are corrected separately without altering raw records. From first periodic to final memory sample, PSS increased **444.61→761.98 MiB**, Native Heap PSS **169.63→425.74 MiB**, and Graphics PSS **116.50→183.36 MiB**, while Java Heap PSS decreased. This establishes growth, with cache/resource/leak attribution unresolved. Current HAL CPU/GPU maxima were **44.0/41.1°C**. The phone was plugged in at 100%, battery status full. No fatal/ANR/OOM marker appears in the bounded PID log.

The unplugged run recorded **113 gestures**, approximately **03:07:40–03:10:41 UTC**, with all eleven sampled AC/USB/wireless/dock flags false. Native Heap PSS increased **435,408→573,616 KiB**, Graphics **318,952→344,840 KiB**, and RSS **1,034,444→1,203,400 KiB**. The same Mihon PID survived. After Home/screen-off, the old recorder's `sleep 600` remained pending when USB was reconnected around 03:22 UTC. There was no final idle checkpoint; only task-owned recorder processes were terminated. The post-reconnection state was active, which does not establish pre-reconnection Doze or eviction. Natural-idle acceptance remains incomplete. The revised [recorder protocol](translator-unplugged-capture.md) separates recording from explicit completion and refuses to retrofit that old run.

A subsequent **60-second heapprofd trace** exercised approximately 44 seconds of short reader gestures, then Back and a quiet remainder. The final screenshot shows the controlled series detail screen in the surviving app process; exact destruction timing is not asserted. The final profile reports **37,681,664 estimated live sampled bytes**, approximately **97.5% with WebGPU JNI ancestors**. Groups include overlay bind groups (~18.83 MB), render-pass encoders (~6.62 MB), command encoders/buffers, surface textures and texture views. The complete final packet repeats the last allocation state without reported heapprofd disconnect, packet loss, non-finalized profile or buffer overwrite; four empty callstacks were discarded. These are sampling estimates, not the total native heap. The evidence supports a resource-lifetime fix and an equivalent after-fix trace.

Initial 30-second reader recordings overflowed a shared 32 MiB ring buffer, retaining only **6.613 seconds WebGPU / 7.044 seconds classic**. Their percentiles remain labeled tail observations in the [dated metrics](translator-device-observations-2026-09-06.md). The collector now streams to a bounded file and protects metadata in a separate buffer.

The corrected WebGPU trace retains **29.999674 seconds**, with 3,597 complete, exactly attributed ReaderActivity frames. Actual FrameTimeline slice p50/p95/p99 is **3.378/4.210/4.752 ms**, with zero recorded jank labels or app deadline misses in that layer. Attributable GPU-memory samples range **529.375–550.836 MiB**. GPU utilization/frequency and render-stage data remain unavailable. Thirty-six setup notices/four discarded chunks remain despite no nonzero `data_loss` statistic; no blanket loss-free or all-GPU-path claim is made. The corrected classic trace retains **30.002576 seconds** with 3,561 frames and p50/p95/p99 **4.351/7.046/7.311 ms**. It records 1,247 Buffer Stuffing and five Prediction Error labels, zero App Deadline Missed labels, and one actual slice 0.308 ms beyond its expected deadline. GPU memory is **563.438–605.387 MiB**; two system GPU-frequency samples are 160/191 MHz. Forty smaller gestures stayed within verified chapter 002, so the two backends were not measured with identical navigation/cache state. Detailed artifacts and limits are in the dated observations.

## Reproduced fixes and remaining checks

Validation exposed a loading sentinel causing `NegativeArraySizeException` in reader slider state; terminal jobs retaining their start timestamp; and corrected OCR text being selected ahead of translated overlay text. The fixed release above bounds subsequent physical observations. App unit/Android pixel regressions must be tied to separately recorded build/test output.

Small Paddle's provider-ID failures led to focused schema/prompt changes preserving strict returned-ID validation. Seven focused regression tests and private capture replay were followed by a live retry of exactly the three failed pages. All three were accepted, preserving the other nine results; missing Korean raw OCR still requires the hybrid or Korean model path. The provider-clipped warning region remains a manual/quality concern. The later ownership measurements and remaining graphics growth are reported separately below.

A consecutive region-retry regression reproduced saved corrected transcription being cleared after the first retry, causing the next retry to use stale raw OCR. The focused fix preserves the saved correction and immutable original OCR while updating the translation. Its red/green evidence is retained under `/tmp/mihon-region-retry-repro`; app unit checks passed. Two consecutive physical region retries subsequently preserved the correction, original text and geometry without chapter resubmission; the temporary edit was restored.

Host fixtures provide delayed responses, partial output, throttling and transient failures without repeated provider charges. Reusable capture review checks original/tile identities, completeness, transcription/geometry and usage reconciliation. These tools support the remaining physical matrix; their tests do not replace it.

## Build and rerun checkpoints

- Final build host tooling check: **122 tests passed in 14.274 seconds**; the earlier 6.296-second checkpoint is retained separately. A subsequently added host-only unplugged analyzer passed 13 focused tests plus the five unchanged shell-protocol tests. It does not alter app sources or the tested APKs.
- Final unit/migration/format checks: **166 passed** (97 app + 64 domain + 5 OCR core), both SQLDelight migration tasks and Spotless green.
- Final normal minified release/benchmark/test build: **passed in 9m26s**, exact installed hashes above. Generated test keeps remain benchmark-only; actual DEX ABI checks pass.
- Final physical runtime: **20 core methods + 2 native/download methods + 1 OCR-profile method** passed, with 16 cold/warm OCR sub-runs. Notification and network cases passed separately.
- Final actual 16 KB runtime: **23 methods passed** on the same minified pair; prior debug 14/18-test results retain their original hashes.
- Final-release traces for both readers are measured above; the a899 sustained ownership observation remains separately attributed below. The final unplugged workload/rest observation is complete; screen-off Worker completion still failed under the observed HyperOS freeze.

## Subsequent minified acceptance checkpoints

Release **`291be8f4f90ed58fb07120efd2f0a7b33d5985c07bfe04218b34857823e3e6db`** (117,314,576 bytes) installed without `-t`; the installed APK hash was verified. Matching benchmark **`9301adfab6fb2fa0e13cdc587907fac026c3e3949eee24103bbf2df6b5cf3ff7`** and initial test **`bd12a21c9d2d3a0f5014cea7ad8936391833b2255ab96f748a41f25095e0a72a`** also installed without the override. These artifacts include the schema and consecutive-correction fixes but precede the WebGPU ownership fix.

The model-download failure was resolved with a RethinkDNS trust rule for `huggingface.co` scoped to `app.mihon.benchmark`, after the user enabled its universal-rule bypass. A temporary benchmark Google DoH trial failed and was restored to Disabled. All four model configurations then passed on checkpoint `a9eae614…` / `031d3b30…` in **30.358 s**, including downloads; inference observations below exclude download/revalidation. The 960×320 fixtures contain `HELLO MANGA 123` or Korean `안녕하세요`. Cold means new native sessions, with no OS page-cache flush. BatteryManager recorded plugged value 1 (AC); these are charging observations, not unplugged inference.

| Profile / recognizer | Cold ms | Warm 1 / 2 / 3 ms | Largest recorded native allocated bytes |
| --- | ---: | --- | ---: |
| Tiny, PP-OCRv6 Tiny | 145 | 55 / 48 / 48 | 125,318,688 |
| Small, PP-OCRv6 Small | 253 | 115 / 102 / 94 | 254,758,608 |
| Medium, PP-OCRv6 Medium | 529 | 350 / 330 / 329 | 524,192,160 |
| Small detector, `korean_PP-OCRv5_mobile_rec_onnx` | 167 | 78 / 76 / 73 | 236,613,424 |

The native allocation numbers cover the process at recorded points, not an isolated model-size estimate or continuous peak. GPU allocation counters are explicitly unavailable in this test. All three pipelines passed the real-vault/preferences/local-provider test in **0.692 s** on the same checkpoint, with synthetic translations; this does not certify meaning or performance on real manga pages. Reports are under private `benchmark-acceptance-models-ready/`.

The local queue scenario matrix subsequently passed in **4.099 s** using unchanged benchmark `9301ad…` and corrected test **`e62e998cdf83bbab9f77bc7ebc2fa5ccf99277159a51a72e083038b28880504d`**. The host fixture's missing close header caused genuine extra transport attempts, reproduced with OkHttp and fixed without weakening count assertions. The content-failure oracle now expects a failed singleton batch inside a retryable PARTIAL chapter, with zero completed images; exact failed-state filtering and partial-state filtering remain distinct. See the [queue acceptance record](translator-queue-acceptance-2026-09-06.md).

The actual process-recovery protocol passed with benchmark `9301ad…` / test `bd12a21c…`: crash PID **26179**, resume PID **26338**, `ApplicationExitInfo` reason **4**, and disposable credential removed. The independent ledger contains three dispatches: completed image once, unfinished image twice; the abandoned delayed request eventually recorded disconnection. Resume passed in **0.161 s**. The host helper initially stopped because Mihon had no PID after its APK update; the still-active barrier was verified and the intended benchmark process was crashed within its 60-second window. No main-app process was killed. Evidence is private `process-recovery-6f878880-a103-400f-a07c-9d9936fb6bc4/`; [reproduction protocol](translator-process-recovery-validation.md).

The standalone framework harness **`805f248db954cb7b69dcfef1b8f66c6e7fc40c37d1ee18d0c30baa3a0a97c6ed`** completed ten original/translated cycles, ten chapter round trips and zoom/pan against release `291be8f4…`, with automatic translation off and chapter-ahead zero verified through the UI. It restored chapter 002/page 7 and the initial viewport pixel hash. The earlier harness failed after one toggle due to menu timing; root restored that toggle before the corrected run. Reviewed screenshots show reversible masks/text, but also missing Korean OCR and a narrow unrotated Japanese translation region. No full geometry/style/ghosting acceptance is inferred. Evidence: private `framework-webgpu-menu-wait-report.json` and `.tar`, with preflight XML and restoration receipt.

The three failed Paddle images (IDs **1, 9, 10**) were retried on release `291be8f4…`, reaching **12/12 saved pages** with exactly three new generations and three token counts. All six new captures are complete with authorization redacted. Empty OCR on IDs 1 and 9 returns zero regions; ID 10 preserves its two noisy region IDs as excluded. The earlier nine images were not resubmitted. This validates the schema/retry behavior while leaving missed Korean/tiny-lettering meaning coverage open. Private `paddle-failed-three-retry-reconciliation.json` reconciles captures with the newly added events and private reservation.

## Evidence index

Paths beginning with `build/`, `scripts/` or `docs/` are relative to the repository. Other evidence paths, including `private/`, `traces/` and `handset/`, are relative to `build/translator/validation/`. Private captures may contain source passages, account state or system identities; export only reviewed, credential-free summaries.

| Evidence | Location |
| --- | --- |
| Initial identity/signing | `build/translator/validation/preflight/`; `handset/20260906T015129Z-handset-initial-handset-state-b59f1a3f/` |
| Fixed release | `build/translator/validation/apk/mihon-k90-fixed-release.apk`; private `fixed-release-install.txt` |
| Baseline and updated 16 KB runtime | `build/translator/validation/emulator16k/runtime-identity.json`, `tested-apks.json`, `instrumentation.log`, `final-debug-tested-apks.json`, `final-debug-instrumentation.log`, `final-debug-crash-buffer.txt` |
| Corpus/hash/geometry ground truth | `build/translator/validation/corpus/manifest.json`; `scripts/fixtures/translation-corpus.json` |
| Reconciled provider evidence | Private `ai-vertex-acceptance-summary.json`, `max-acceptance-summary.json`, `halving-acceptance-summary.json`, `custom-acceptance-summary.json`, `twelve-ai-acceptance-summary.json` |
| Exports and spend | Private capture/event folders, `spend-current.json`, `custom-spend-uncertain.json`, `twelve-paddle-spend-settled.json` |
| Small Paddle observations | `handset/20260906T024018Z-handset-fixed-release-paddle-small-twelve-cb5cd8e9/`; private `paddle-small-observed-metrics.json` |
| Sustained workload | `handset/20260906T025107Z-handset-webgpu-sustained-reader-180s-9ce463c8/`; private `webgpu-sustained-reader-observed-metrics.json` |
| Reader scope correction | Private `reader-fixture-provenance-corrections.json` |
| Full WebGPU trace | `traces/20260906T025756Z-webgpu-cached-scroll-streamed-30s-96092ea9/`; private `perfetto-reviewed/webgpu-streamed/` |
| Full classic trace | `traces/20260906T030002Z-classic-cached-scroll-streamed-30s-b875d6e4/`; private `perfetto-reviewed/classic-streamed/` |
| Export redaction checks | Private `live-export-redaction-audit.json` (earlier five archives); `live-export-redaction-final-audit.json` (all eleven) |
| Offline view | Private `webgpu-offline-actions.json`, `webgpu-offline-after.png` |
| Unplugged reader / incomplete idle | `handset/mihon-unplugged-20260906T0306/`; private `unplugged-reader-observed-metrics.json`, `unplugged-device-run-preflight.json` |
| Native allocation attribution | `traces/20260906T032359Z-webgpu-native-allocation-close-60s-95c4edfe/heap-analysis/`; private `webgpu-native-allocation-close-60s-actions.json`, `webgpu-native-allocation-close-60s-after.png` |
| Minified checkpoint instrumentation | `r8-instrumentation-abi/checkpoint/`; private `benchmark-core-18-instrumentation.log`, `benchmark-native-pipeline-instrumentation.log`, `benchmark-ocr-profiles-retry.log`, `benchmark-queue-instrumentation-retry.log`, `benchmark-acceptance-checkpoint/` |
| Final build/host checks and exact APKs | `build/translator/validation/r8-instrumentation-abi/rotation-worker-final/` (APK hashes, mappings, source hashes, 166-unit/122-host logs, manifests, actual DEX checks) |
| Final minified 16 KB | `emulator16k/final-minified/`, including independent `reconciliation.json` |
| Final K90 native/OCR | Private `rotation-worker-core-20-instrumentation.log`, `rotation-worker-native-and-download.log`, `final-handset-ocr-profiles-report.json` |
| Final reader frames | `traces/20260906T084143Z-final-webgpu-30s-c68d1527/frame-analysis/`; `traces/20260906T084437Z-final-classic-30s-9e2f999f/frame-analysis/` |
| Final unplugged workload/rest | `handset/mihon-unplugged-final-20260906T085125Z/`; private `final-unplugged-reconciliation.json`, `final-unplugged-collection-receipt.json` |
| Final styles per reader | Private `framework-style-ten-offline-reconciliation.json`; `framework-classic-style-ten-offline-reconciliation.json`; exact harness archive `framework-ui/style-final/` |
| Actual Worker modes | Private `worker-notification-0ab71f09-e7e0-4150-bf08-c96a958f48f8/`, `worker-network-f540e4cd-81ed-40db-bf2a-d7626cc3a0ff/`, `worker-screen-off-0bcbbd9a-de30-4b43-8e57-a57cfbe92bdc/` |
| Final handset installed hashes | Private `rotation-worker-release-installed.json`, `rotation-worker-benchmark-installed.json` |
| Final core/rotation/native/download passes | Private `rotation-worker-core-20-instrumentation.log`, `rotation-worker-native-and-download.log`, `rotation-worker-ownership-report.json`, `rotation-worker-model-interruption-report.json` |
| Final handset OCR | Private `final-handset-ocr-profiles-report.json`, `final-handset-ocr-profiles.log` |
| Final minified actual 16 KB runtime | `emulator16k/final-minified/` (runtime identity, installed hashes, three passing logs, native/model reports, empty crash buffer) |
| Notification pass and preserved earlier failures | Private `worker-notification-0ab71f09-e7e0-4150-bf08-c96a958f48f8/reconciliation.json`; earlier `worker-notification-0c343a14-b1af-48fd-9028-124d3c555ad3/` and `worker-notification-06d242fe-b90b-4c9b-9848-6b46f53cc959/` |
| Screen-off Worker failure | Private `worker-screen-off-0bcbbd9a-de30-4b43-8e57-a57cfbe92bdc/` (report, instrumentation, server ledger, action/power/service receipts) |
| Actual network recovery | Private `worker-network-f540e4cd-81ed-40db-bf2a-d7626cc3a0ff/reconciliation.json`, action/connectivity/ledger/restoration receipts |
| Final offline WebGPU styles | Private `framework-style-ten-offline-reconciliation.json`, `framework-style-ten-offline-network.json`, screenshots and `.tar`; `framework-ui/style-final/` |
| Prior host suite checkpoint | `build/translator/validation/host-tests-final-2026-09-06.log` |

All shorthand evidence directories, including `handset/`, `traces/`, `private/`, `emulator16k/`, `framework-ui/` and `r8-instrumentation-abi/`, are beneath `build/translator/validation/`. Tracking remains [LegendZ69/mihon #1](https://github.com/LegendZ69/mihon/issues/1); only completed, artifact-specific evidence should close an acceptance item.

## Classic UI checkpoint

On release `291be8f4f90ed58fb07120efd2f0a7b33d5985c07bfe04218b34857823e3e6db`, the framework harness `805f248db954cb7b69dcfef1b8f66c6e7fc40c37d1ee18d0c30baa3a0a97c6ed` completed ten comparison cycles, ten chapter round trips and zoom/pan actions on the classic RecyclerView reader. Run `3ae60653-f290-4854-8559-a84c10becc27` retained chapter 002 and page 7. The inverse pinch did not restore the exact viewport scale/position: final pixel hash differs, so only chapter/page and comparison parity are marked restored. Screenshots show reversible original/translated content, but rotated Korean text is clipped at the mask boundary. That implementation fitted the unrotated rectangle before applying rotation; a focused pixel regression reproduced both cases: 158/3,417 opaque glyph pixels clipped in the rotated rectangle and 160/3,472 in a tilted quadrilateral. Polygon-aware fitting and three geometry tests are included in final release 2d6246d0…; both unchanged physical pixel regressions passed on final benchmark 4e9559a4… / test 61e295a8…. This is separate from the provider returning zero rotation for the sideways Japanese text.

## Ownership and hybrid merge checkpoint

Release `a899cd4ea4d3c0fa8070dc4801d981289ddfa242639f041930c98a127d60e928` was installed normally without a test-only override and verified against the on-device APK, local certificate and 16 KB ZIP alignment. Benchmark `6aeaa49cdd6872baee8ebc7e504bcbf7a7571b15444de20600efe03e9243cef9` and test `b8d90ce927d91dd505caa59462bd3ccf69bd84d3d127c8ccf2a6dcdac22f9c44` came from the same normal R8 build.

The native ownership regression passed in **3.061 s**: measured whole-process native allocated growth over the second 512 warmed frames fell from **10,751,152 to 2,531,536 bytes**, below its 4 MiB ceiling. Original/overlay pixel checks passed. Both blocks still show positive growth, so this bounded pass does not establish zero retained allocation; the real-reader repeat recorded 2,025,316 estimated net sampled bytes versus the previous 37,681,664, with WebGPU JNI groups falling from 36,749,312 to 258,224 bytes. The cached content and process history differ, so the real-reader comparison is observational rather than an identical-workload experiment. The older failed report was exported without rerunning or relabeling it as current evidence.

The hybrid merge regression reproduced corrected reading order and geometry being replaced by supplied OCR values. Two intended failures went green alongside eight existing/control cases, followed by the full app94/domain64/OCR5 Gradle checks. The focused fix preserves hybrid provider order and geometry while retaining original OCR, measured confidence, region identity and user styles; text-only Paddle retains its supplied geometry.

Two physical region-only retries on the previous release `291be8f4f90ed58fb07120efd2f0a7b33d5985c07bfe04218b34857823e3e6db` preserved an edited transcription, original source text and polygon. The private capture reconciliation contains exactly two successful text-only requests, each with only image 4 / region `4:region-3`, and no image payload or chapter resubmission. The temporary punctuation edit and translation were restored. This validates consecutive correction persistence for that case.

## Sustained reader after the ownership fix

Release `a899cd4ea4d3c0fa8070dc4801d981289ddfa242639f041930c98a127d60e928` completed a fresh 180-second cached WebGPU scrolling run on the charging K90. PID **22361** remained unchanged. From first periodic sample to final sample, PSS was **239,840→265,129 KiB**, Native Heap PSS **45,736→45,772 KiB**, Java Heap PSS **12,008→12,352 KiB**, and Graphics PSS **71,792→97,040 KiB**. Thus native growth was nearly flat in this observation, while graphics growth remained **25,248 KiB**. Current HAL maxima were CPU **47.3°C**, GPU **43.4°C**, battery **35.0°C** and skin **34.1692°C**. The bounded PID log contains no fatal/ANR/OOM match; it is not a guarantee outside the window.

Evidence: `handset/20260906T074507Z-handset-webgpu-ownership-fixed-180s-d6ed557c/`, private `webgpu-ownership-fixed-observed-metrics.json`, actions and final screenshot. The corresponding 60-second allocation trace is `traces/20260906T071743Z-webgpu-ownership-fixed-close-60s-c0748387/`. Memory snapshots are not a retained-heap attribution or a complete GPU memory inventory; no frame percentiles are inferred from them.

The post-fix hybrid check translated only corpus originals `01-ja-vertical` and `11-long-strip` in four image requests. Their nine persisted regions preserve provider reading order and map to original coordinates within **0.0002 px**; raw OCR and measured confidence remain intact, both AI additions survive, and the overlapping promise appears once. All eleven captures (four generations and seven token counts) are complete HTTP 200 with redacted authorization. Usage was **7,692 input / 945 visible output / 1,844 reasoning tokens**, including 4,350 image tokens as an input subset. This is a physical merge pass for two originals, not an all-twelve or human meaning approval. Private `hybrid-merge-two/reconciliation.json` records the replay and hashes.
