# Framework reader UI validation

This is a standalone Android instrumentation APK. It instruments **its own package** (`app.mihon.validation.framework`) and controls the already-running, non-debuggable `app.mihon` through Android `UiAutomation`. It does not restart Mihon or link any Mihon, AndroidX, Kotlin, JUnit, or other application library classes. The harness has no network permission and does not read Mihon's files or credentials. The separately opt-in `qualityReview` plan can ask Mihon to dispatch one selected-page AI review; it requires explicit authorization and a recorded host reservation or local-fixture reference.

The default `inspect` plan sends no input. It requires the exact **Controlled Translation Validation** series and one of these chapter titles, with the reader toolbar and page slider visible:

- `001 - Five-page modes`
- `002 - Twelve-page corpus`

It records the installed package version/certificate, current chapter/page, viewport bounds, a screenshot, and a viewport pixel hash. It stops if these selectors are unavailable; there is no screenshot-coordinate guessing.

The explicit `exercise` plan supports:

- One to ten original/translated comparison cycles; each cycle toggles twice, returning the overlay preference to its initial value.
- Optional one to ten chapter cycles between the two exact fixture titles, returning to the initial chapter and page after each cycle.
- Two-finger zoom, a short pan and return, and inverse zoom gestures within accessibility-derived reader bounds.
- Optional device rotation and restoration. A reader orientation policy that prevents actual rotation produces a failure, not a passing rotation claim.

**Comparison cycles are recorded separately from font/style changes.** The separate `styleCycles` plan changes only the existing controlled-series override's system font family and background opacity through real UI controls. It requires additional host checks described below. Input completion and screenshot hashes do not establish overlay alignment, readable text, absence of ghosting, frame deadlines, or translation meaning. Review the screenshots alongside reader traces. Exact zoom matrices are unavailable through the framework UI interface.

## Style cycles

Start from the same controlled reader/page with visible cached translation and toolbar. Before executing, the device owner must verify automatic translation is off, chapters ahead is zero, and this series already has an override. The harness refuses global `Translation defaults`, an imported font path, an unexpected app/series/chapter or missing controls. It follows reader More options → Translator queue and settings → Settings → `Settings for this series`. It does not open JSON, credentials, defaults, imports, Translate, Retry or Resume controls.

The plan first reads and journals the current font family and opacity. Actual font choices are `system`, `sans-serif`, `serif`, `monospace`, `cursive`. It uses `monospace` (or `serif` if already monospace) and opacity `0.35` (or `0.85` when the original is at most `0.60`), then restores the exact initial values. The imported font field remains read-only and must be empty. Search fields and value fields are distinguished by their label subtree even when the search query equals the field label. The harness dismisses observed IME windows before covered controls.

The current style harness journals `style_restore_required=true` **before the first potentially autosaved mutation**, including a font choice. It detects the visible autosave status, waits for 400 ms of continuously observed `Saved` within a five-second bound, and fails on `Retry save` without clicking it. Existing save errors stop initial validation; restoration may write the already journaled original values to recover from an error raised by this run. A visible legacy `Apply` control remains supported for older builds. Unknown status never silently becomes legacy mode. Failure or cancellation retains the restoration obligation until original persisted values and viewport pixels are verified and the completion journal is written. `style_pending_apply` remains the compatible name of the pending style snapshot for both persistence modes.

Each change and restoration reopens settings to verify persisted values, returns to the initial reader page, and records a screenshot/pixel hash. The alternate must change the measured viewport hash; each restoration must match its initial hash. Use a page with translated content in the measured central viewport. A mismatch fails and attempts restoration; it does not become a passing visual assessment. The harness retains the first failure and flags incomplete restoration in the final report. It never changes global defaults or clears an existing imported font. Only the two original style values are restoration targets.

Begin with one guarded round trip:

```sh
adb -s SERIAL shell am instrument -w -r \
  -e acceptance true -e plan styleCycles -e cycles 1 \
  -e automaticTranslationDisabled true -e chaptersAheadZero true -e seriesOverrideExists true \
  app.mihon.validation.framework/app.mihon.validation.framework.ReaderUiInstrumentation
```

After reviewing the first run and its restoration, use the same command with `-e cycles 10`. Do not run during another harness, style edit, paid translation or device trace unless that trace explicitly measures this workload. Capture provider/log request counts before and after independently; the harness has no INTERNET permission and no paid action, but cannot independently prove the subject app made no network requests.

If the process is killed, the report's `style_original`, `style_pending_apply` and `style_restore_required` fields provide the UI restoration instructions. Never start another style run until the prior restoration is resolved. A successful action result requires both persisted UI-value checks and changed/restored pixel hashes, while visual readability/geometry and passage meaning still require review.

Validated K90 WebGPU example, 2026-09-06: one guarded round trip (`b5bcab57-eb51-4bbc-91fe-562d71980e20`) followed by ten (`f7d882b0-bb42-429f-aea1-486d3053fba9`) passed on minified subject APK `2d6246d0bb2c0c3af96c674b90f0e9dff90446a0f14c0951c7e56f4c476ee2be`, page 7 of the twelve-page corpus. The ten-cycle run changed `system` / `1.0` to `monospace` / `0.35` and back, with 20 persisted-value readbacks and all 22 screenshot viewport hashes independently recomputed. Every restored viewport matched the baseline. The app report spans 133.596 seconds; the host command spans 135.553 seconds. Wi-Fi off, airplane mode on and mobile data off were recorded at both run endpoints; the initial network settings were restored afterward. These endpoint observations are not continuous connectivity monitoring. The first changed/restored screenshots were visually reviewed; passage meaning and other style combinations remain separate checks.

The **exact tested** harness APK, matching source/manifest and original build metadata are archived at `build/translator/validation/framework-ui/style-final/`; APK SHA-256 is `968c4e7213b99db18ce48ccdb1ed00417b5f6b1c1a6b8406ad543ccb07bc1e47`. It was copied without rebuilding. Run evidence and independent reconciliation are in `build/translator/validation/private/framework-style-ten-offline*`.

The **classic reader** also passed ten round trips on 2026-09-06, run `99f09b4f-e62a-4cc1-a4fb-212bab0c5f06`, using those same exact subject/harness APKs. Independent reconciliation recomputed all **22 PNG viewport hashes**, verified all **20 persisted style readbacks** in Apply/readback/screenshot order, and matched all 23 regular tar members to the extracted files. Every changed viewport differed from baseline, every restoration matched it, and every screenshot retained chapter 002/page 7 and orientation. The app report spans **136.681 seconds**; the host command spans **137.900 seconds**. Endpoint network settings were Wi-Fi off, airplane mode on and mobile data off, with the initial settings restored afterward; this does not establish continuous connectivity or a provider request count. The retained final restore intent is followed by a successful persisted-value readback and matching screenshot, with `style_restore_required=false`. The private `framework-classic-style-ten-offline-reconciliation.json` and `framework-classic-style-ten-offline-source-hashes.json` beneath `build/translator/validation/private/` contain the reproducible checks and evidence/source hashes. This is action, persistence and pixel-restoration acceptance, not human passage approval or universal font/geometry coverage.

## Paired frame windows

The canonical-reset revision of the opt-in `framePairs` plan was compiled and validated on the K90 classic reader on 2026-09-06 using framework APK `67e851…` and minified reader APK `5e1768…`. One calibration pair and three alternating acceptance pairs verified their input, canonical pixels and frame-trace bindings; original window 6 has partial CPU scheduling coverage, so pair 3 is excluded from complete CPU comparisons. Evidence is retained under `build/translator/validation/private/quality-classic-canonical-{calibration-1,three-pairs}/independent-reconciliation/`. WebGPU validation of this revision remains separate. It is **not included** in the archived `968c…` style APK above. It measures the same controlled cached page separately in translated and original modes. It accepts one calibration pair or three acceptance pairs, alternating the order **translated/original, original/translated, translated/original**. Select the scrolling reader backend before the run and leave its toolbar visible. Use a page whose central viewport visibly changes with the comparison control. Verify automatic translation is off, chapters ahead is zero, and the selected page plus its adjacent page in this controlled downloaded chapter are cached; record the actual initial comparison mode. Setup explicitly normalizes the selected page to its page start. The arbitrary entry offset is not the final restoration target. These are explicit host prerequisites.

```sh
adb -s SERIAL shell am instrument -w -r \
  -e acceptance true -e plan framePairs -e pairs 1 \
  -e initialMode translated -e backend classic \
  -e automaticTranslationDisabled true -e chaptersAheadZero true -e cachedTranslationVisible true \
  app.mihon.validation.framework/app.mihon.validation.framework.ReaderUiInstrumentation
```

Run `-e pairs 3` only after reviewing the calibration input, same-page boundaries and restoration. A WebGPU run requires a separate calibration with `-e backend webgpu` after selecting that scrolling backend; this revision has no completed WebGPU frame-pair acceptance. The harness requires a RecyclerView for classic and its absence for WebGPU; this is scoped to the existing scrolling layouts. It does not select a backend or change global defaults.

Use the [host controller](capture_frame_pairs.md) to launch the framework, wait for each `frame_window_ready_json`, start its independent 45-second trace, verify actual recording readiness and the writer watch, and arm only its exact owned gate. The controller validates run UUID, nonce, mode order and page, writes the canonical UUID as a quoted positional argument to a fixed bounded script, checks its exact bytes, and atomically renames the private pending file. The earlier `exec-out ... tee` method stalled on the K90 because stdin EOF was not forwarded; do not use it. The harness consumes and removes each one-use gate. Wait for both recorder exit and the trace's CLOSE_WRITE before collection or the next window. Do not run another UI owner or arm multiple gates in advance.

The window starts one second after its gate. It sends thirty 500-pixel vertical gestures at one-second cadence, alternating direction, with 500 milliseconds per gesture including an endpoint hold. It measures 30 seconds on the device's monotonic clocks and rejects cadence lateness of 250 milliseconds or more, screen-off, changed viewport, visible controls or failed injection. Screenshots, report writes and setup occur outside the measured interval. Setup records the entry viewport hash, then performs an observed adjacent-page → selected-page jump twice and requires identical canonical pixels before any trace gate. Real classic page navigation calls `scrollToPositionWithOffset(position, 0)`; WebGPU continuous navigation resets its scroll offset. The harness checks the exact controlled chapter and bounded integer page identity after each jump, allowing only the slider's tiny floating-point rounding noise. It never crosses a chapter boundary for normalization.

After each timed window, the harness reveals controls and records the natural postgesture hash separately. Equal and opposite gestures are not assumed lossless: calibration 6 completed thirty gestures but drifted from `8a3a…` to `b297…` while still reporting page 7. The new harness resets outside the timed bounds, then captures the canonical after screenshot and requires it to equal that mode's before screenshot. Original and translated canonical pixels must differ. Entry/natural-after hashes are hash-only observations, without additional exported PNGs; the canonical initial, per-window before/after and final PNGs remain independently verifiable. Intermediate hidden scroll positions are not independently observable. Mode naming rests on the recorded initial mode plus comparison-toggle parity, so a host attestation mistake invalidates attribution.

Export the run using the evidence command below. Independently reconcile the extracted report and PNG pixels:

```sh
python3 -B scripts/android-ui-validation/reconcile_frame_pairs.py \
  private/frame-run/reader-validation/UUID/report.json \
  --output private/frame-run-reconciliation.json
```

The reconciliation verifies timing, gesture counts, order, page identity, ARGB viewport hashes and restoration. It emits `start_elapsed_ns` / `end_elapsed_ns` bounds for **each** window. Bind them to the matching Perfetto clock before filtering FrameTimeline slices; do not report whole-capture setup/waiting time as a 30-second sample. Record app-deadline misses, overall jank categories and unavailable GPU counters separately. Passing input assertions do not establish smoothness or absence of visual defects. Keep the same workload, charging/power state and thermal context across the paired windows; record them independently.

The final journal restores comparison parity, selected chapter/page and rotation to the explicitly declared canonical page-start baseline. It does not claim restoration of an arbitrary entry offset. The retained pre-fix calibration failure is not relabeled as a pass. A pending toggle is an explicit unknown state and prevents blind restoration. Do not launch another plan until any `comparison_pending_toggle` or `frame_restore_required` flag is resolved through observed UI. The plan clicks no paid action, but absence of paid provider calls still needs independent offline or ledger evidence.

Host regression for the actual frame reset helper (no Android or device calls):

```sh
python3 -B -m unittest discover -s scripts/tests -p test_translator_frame_viewport.py -v
```

The helper regression covers the old same-page optimization retaining an offset, actual adjacent/return command order, repeat resets, slider rounding, chapter endpoints, an unobserved jump, invalid pages, a singleton chapter and return from another page. It cannot establish renderer scroll-position behavior; the dated classic physical evidence above provides that separate check, while WebGPU calibration remains pending.

Host regression for inspector labels and style persistence/restoration guards, without device actions:

```sh
python3 -B -m unittest discover -s scripts/tests -p test_translator_reader_settings.py -v
```

## Selected-page AI review

The `qualityReview` plan was compiled and exercised twice on K90 on 2026-09-06 with the unchanged framework APK `67e851d5c8ae2acfa505262b4c1cc82bc8f96d20f0d4ea1576f24949835820c0`, archived with its build metadata at `build/translator/validation/framework-ui/canonical-frame-reset/`. The host-attested subject was release `5e1768a023f87b4b99adbf0968f5f44626ed35accf68a6748d2bfa14515a4a2d`; the framework records package version/certificate, not the installed APK hash. Later application builds require their own artifact attribution. These runs establish bounded UI dispatch and observation, with the unsuccessful visual outcomes retained below. The plan is separate from the reader plans and is **not included** in the archived `968c…` style APK. It starts from an already-open **OCR inspector** for the exact **Controlled Translation Validation** series. The host must supply the exact visible chapter title, page label (`Image N`), and `Image ID`. Keep the quality card visible, including its state, Review ID (when present), and enabled **Review selected page** button. Save or discard manual drafts first. The harness does not navigate to a page or scroll to find the card.

Verify automatic translation is disabled and chapters ahead is zero. Reserve the worst-case cost of the selected page's review settings, including its permitted transport attempts, **before** any live invocation. Pass the reservation UUID through `dispatchReference`; never pass a credential. The host ledger, its S$270 dispatch stop, and S$300 total ceiling remain authoritative for this test session. The harness can validate the reference format but cannot read that ledger, enforce provider billing, or count actual API attempts.

```sh
adb -s SERIAL shell am instrument -w -r \
  -e acceptance true -e plan qualityReview \
  -e automaticTranslationDisabled true -e chaptersAheadZero true \
  -e allowProviderDispatch true -e dispatchKind reserved_live \
  -e dispatchReference RESERVED_LEDGER_UUID \
  -e chapterTitle '002 - Twelve-page corpus' -e pageLabel 'Image 7' \
  -e imageId EXACT_IMAGE_ID -e reviewTimeoutSeconds 180 \
  app.mihon.validation.framework/app.mihon.validation.framework.ReaderUiInstrumentation
```

For an already-configured deterministic endpoint, use `dispatchKind=local_fixture` and `dispatchReference=fixture:RUN_ID`. This is a host attestation that the subject's endpoint is local; the harness does not open provider settings or verify the URL. The explicit `allowProviderDispatch=true` remains required. Root/device owner supplies and checks these arguments; the plan never imports credentials or modifies provider settings.

The plan refuses an existing queued, running or paused checkpoint, clicks **Review selected page exactly once**, and requires a newly observed Review ID before accepting a terminal state. It never clicks Translate, Retry, Resume, Undo, another image, or a chapter action. An unfinished UI observation times out after 10–900 seconds (default 180), retains its uncertain-outcome marker, and sends no retry. Preserve the reservation and inspect request logs before another invocation. A crash between journaling click intent and recording its result also remains uncertain.

The current selector accepts exactly `Image N` or the native `Image N · X saved pages` form with a positive saved count. It rejects a different page, an arbitrary suffix, or ambiguous matching labels. The exact series title, chapter title, `Image ID`, inspector title and visible quality-card guards remain required; the displayed count is never treated as an image identity.

`completed_review_ui_observation` means a new checkpoint reached a visible terminal state. `review_needs_attention` remains true for paused, incomplete, needs-review, superseded and undone states. Passed or repaired states are explicitly AI assessments with `human_approval=false`; a repaired revision can retain findings and does not approve its meaning. Screenshots capture only visible findings. Reconcile the app's full review logs, actual request/usage events, result revision and selected image identity independently. `review_control_clicks=1` is a UI count, **not** a provider request count. No chapter translation or region retry is part of this plan, and the resulting selected-page review remains in the app for inspection or a separately authorized undo.

### Retained K90 selected-page observations

Each controlled run recorded exactly one **Review selected page** click, a new Review ID, zero chapter-action clicks, `completed_review_ui_observation`, and `human_approval=false`. Independent app exports establish one token-count preflight and one generation review per run, both first-attempt HTTP 200, with the exact saved baseline revision and the unchanged full original image. Those provider counts come from the logs and raw captures; the framework itself reports `provider_dispatch_count=null`.

| Controlled target | Observed review | Retained outcome |
|---|---|---|
| Chapter 001, Image 5, image ID `4` | `d673a7f2-d68e-4f35-862f-fdec1c0ddd5a` | Confidence and detected-language metadata changed; text and geometry did not. The clipped warning was not repaired. Separate manual UI Undo restored the complete baseline except its new monotonic revision. |
| Chapter 002, Image 7, image ID `6` | `52656e89-5d78-49d5-901e-264f6591d357` | Confidence alone changed; Japanese rotation and geometry did not. The known sideways-text issue was not repaired. Undo was not exercised. |

Both older app checkpoints displayed `repaired` with no findings and `visualComplete=true`. Their labels and higher AI confidence are not visual acceptance, calibrated probabilities or human meaning approval. The later metadata-classification fix does not retroactively turn these model outcomes into successful repairs. The first Undo was performed separately through the app UI; this harness still never invokes Undo.

Private evidence lives beneath `build/translator/validation/private/quality-live-review-clipped-warning/` and `quality-live-review-sideways-japanese/`, including `framework-evidence/reader-validation/<UUID>/report.json`, the two screenshots per run, actual before/after exports, raw request/response captures, and `independent-reconciliation/`. Framework run IDs are `06e650f0-3910-4b5d-a77b-249827c099bb` and `4b5723c9-ed32-432a-8eb6-fa8baea77d8e`, respectively. The first directory also retains the separate Undo export. Full images, source passages and raw payloads stay private. The [quality and recovery report](../../docs/translator-quality-recovery-validation-2026-09-06.md) records the detailed request/persistence checks and subsequent application changes.

Three additional selected real pages were reviewed manually through their actual inspector controls. They are outside the controlled framework runs: the exact-series/chapter guard was not relaxed, and their results must not be attributed to this harness. No new framework compilation or APK is claimed by this documentation update.

## Build without Gradle or a device

```sh
python3 scripts/android-ui-validation/build.py
```

The helper uses local JDK 21, Android platform 37.0, build-tools 36.1.0, and the standard local Android debug keystore. Paths can be overridden with `--sdk`, `--java-home`, `--platform`, and `--build-tools`. It never executes `adb`. It writes an APK and `build.json` containing source/APK hashes and signature verification to `build/translator/validation/framework-ui/`.

The harness is debuggable solely so its private evidence can be exported with `run-as`; the subject Mihon APK remains non-debuggable. Self instrumentation does not require the harness certificate to match Mihon's certificate.

## Review before any device execution

1. Confirm the installed subject APK and certificate against the validation run's recorded artifacts.
2. For reader plans, open the controlled series and one of the exact chapters above, show the toolbar, and select a cached page with visible translated content. For `qualityReview`, open its exact controlled OCR inspector and selected quality card instead; verify the supplied chapter title, `Image N` label and image ID before the authorized click.
3. Ensure no other user or automation will control the phone during the run.
4. Before `exercise`, verify **Translate automatically is off for this series** and chapter-ahead processing is zero. Recheck this after settings imports. The harness records this as a **host-verified prerequisite**, not an automated assertion. Overlay setting changes, rotation, or opening a chapter can otherwise invoke automatic translation. The reader plans never click Translate, Retry, Resume, credentials, or provider settings. The separate `qualityReview` plan requires the additional dispatch authorization above.
5. Before enabling chapter cycles, verify that the controlled series contains exactly the two supplied local fixture chapters, both cached and accessible through the reader's Next/Previous chapter controls.

Root agent/device owner must review and run these commands. Replace `SERIAL` with the explicit handset serial. Do not run them during another workload or natural-idle measurement.

```sh
adb -s SERIAL install -r build/translator/validation/framework-ui/mihon-framework-ui-validation.apk
adb -s SERIAL shell am instrument -w -r \
  -e acceptance true -e plan inspect \
  app.mihon.validation.framework/app.mihon.validation.framework.ReaderUiInstrumentation
```

After reviewing the inspect result and verifying the prerequisites, the bounded ten-cycle run is:

```sh
adb -s SERIAL shell am instrument -w -r \
  -e acceptance true -e plan exercise -e automaticTranslationDisabled true \
  -e cycles 10 -e chapterCycles true -e gestures true -e rotation false \
  app.mihon.validation.framework/app.mihon.validation.framework.ReaderUiInstrumentation
```

Rotation is a separate opt-in (`-e rotation true`). Keep it separate from traces where rotation is not the measured variable. Use `-e gestures false` to isolate comparison/chapter switching. The harness runs against the current reader backend and never changes backend settings; run separately after the device owner selects each backend.

## Evidence and restoration

Each run writes `files/reader-validation/UUID/report.json` and screenshots in the harness's private storage. Directories use mode 0700 and files 0600; backups are disabled. The report is checkpointed after every action. Screenshots are restricted to the verified fixture reader or, for `qualityReview`, its explicitly selected inspector, with limits of 48 images and 128 MiB per run. No complete accessibility tree or arbitrary on-screen text is exported.

Instrumentation output returns the exact report path. Export only that run, for example:

```sh
adb -s SERIAL exec-out run-as app.mihon.validation.framework \
  tar -C files -cf - reader-validation/UUID > private/framework-ui-UUID.tar
```

`passed_action_assertions` means the guarded controls and restoration checks completed. `failed` includes explicit failure/restoration details; neither state establishes human visual or meaning acceptance. A killed harness cannot guarantee restoration: use the last journaled `toggle_count`, screenshots, and initial chapter/page to recover through the UI. Do not run a second exercise until the first run's restoration is understood.

The inspect plan makes no changes. Exercise restoration uses comparison-toggle parity, the initial chapter/page, and initial rotation policy. No reader preferences are written directly. A missing control or unexpected package/title stops the test; incomplete restoration remains visible in the report.

Official interfaces checked on 2026-09-06: [Instrumentation](https://developer.android.com/reference/android/app/Instrumentation), [UiAutomation](https://developer.android.com/reference/android/app/UiAutomation), [AAPT2](https://developer.android.com/tools/aapt2), [d8](https://developer.android.com/tools/d8), and [apksigner](https://developer.android.com/tools/apksigner).

### Explicit interior anchor for matched WebGPU comparison

The optional `frameAnchorPixels=100` argument selects `adjacent_cached_page_then_fixed_interior_drag_v1`, restricted to **002 - Twelve-page corpus, reader page 7**. Omitting it or passing `0` preserves the page-start protocol above. Every setup, window reset and final restoration performs the same adjacent-page reset followed by one upward 100-physical-pixel finger drag: 420 ms eased motion and 300 ms stationary hold before release. Touch slop means requested finger travel is not a measured 100-pixel document displacement. The harness requires changed page-start pixels, stable page 7/viewport, two identical setup baselines and identical per-mode start/restoration hashes. Setup retains two extra PNGs, so use action labels rather than fixed numeric filenames. A swallowed, page-changing or unrepeatable gesture fails before measurement; no offset retry or adaptation occurs.

The anchor places measurement away from the exact page-start boundary implicated by the retained 14-pixel natural drift failure. It does not alter the thirty timed gestures, boundary guard or failed-run semantics. A comparison requires the same explicit anchor, framework, viewport and power policy for both app builds. Do not compare an interior run against an old page-start run or relabel an earlier failure. The declared restoration target is `repeatable_interior_anchor`, not an arbitrary entry offset. Hardware repeatability and matched frame acceptance still require actual device evidence.
