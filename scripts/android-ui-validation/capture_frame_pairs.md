# Host controller for paired frame captures

`capture_frame_pairs.py` starts exactly one framework `framePairs` instrumentation. The framework remains the sole owner of UiAutomation and reader input. The controller starts one independent 45-second Perfetto capture for every 30-second original/translated window, writes only that run's gate, and retrieves private evidence. It does not choose a page, change settings, translate content, import credentials, or start a second UI controller.

Root/device owner must first verify the installed subject and harness APK hashes; select the controlled cached page and scrolling backend; show reader controls; and verify the actual starting comparison mode, power state, automatic translation disabled, and chapter-ahead zero. Calibration accepts one pair; acceptance accepts three. On 2026-09-06 the K90 classic reader completed one canonical calibration pair and three alternating acceptance pairs with framework APK `67e851…` and minified reader APK `5e1768…`; input, PNG and trace bindings reconciled. CPU scheduling in original window 6 is partial, so pair 3 is excluded from complete CPU comparisons. The private `quality-classic-canonical-{calibration-1,three-pairs}/independent-reconciliation/` receipts preserve the limits; these are measurements, not a performance guarantee. WebGPU calibration of this revision remains pending.

```sh
python3 -B scripts/android-ui-validation/capture_frame_pairs.py \
  --serial SERIAL --backend classic --pairs 1 --initial-mode translated \
  --subject-apk-sha256 VERIFIED_SUBJECT_SHA256 \
  --harness-apk-sha256 VERIFIED_HARNESS_SHA256 \
  --acceptance --automatic-translation-disabled --chapters-ahead-zero \
  --cached-translation-visible \
  --output build/translator/validation/private/frame-classic-calibration-NEW_RUN
```

Use a new output directory every time. After reviewing calibration input, screenshot restoration, complete trace windows and loss, repeat with `--pairs 3`. The controller launches instrumentation itself: do not also run `am instrument` manually. Select WebGPU independently and use `--backend webgpu` for that run. Do not start another device UI automation or workload concurrently.

Each ready event must retain the same harness run UUID, chapter/page, declared protocol, mode order and backend. The exact gate must be `/data/user/0/app.mihon.validation.framework/files/reader-validation/UUID/frame-N.go` (or the equivalent `/data/data` path). Ordinals must be consecutive and nonces distinct valid UUIDs. The controller validates the canonical UUID, then passes it and the owned pending path as separately shell-quoted positional arguments to a fixed `printf` script. No nonce is substituted into the script body. The script uses a private umask and noclobber; staging and exact 36-byte readback each have a three-second timeout before the atomic rename. This avoids the K90 `exec-out ... tee` path, which waited for stdin EOF during retained calibration 5. No gate was armed in that failed calibration; its recorder exit and writable-close observation did complete.

Before instrumentation, the controller reads the phone's actual `perfetto --help` and `inotifyd --help`. It requires recognized usage text, acknowledged background start and stdout writable-close observation. K90 Perfetto returns exit code 1 for valid `--help`; only help exits 0 or 1 with the expected usage and all required options are accepted. Recording start still requires exit 0. Unsupported capabilities fail before the framework is started. The K90's bundled Perfetto rejected the earlier `--notify-fd` and `--no-clobber` flags during retained calibration; this controller does not send either flag.

Perfetto receives the existing `translator_perfetto_capture.configuration` frame configuration for 45 seconds. `--background-wait` (`-D`) must return a successful data-source start acknowledgment and one PID. The controller verifies that PID's start time and command refer to this exact generated trace, and confirms that its `inotifyd` observer registered `CLOSE_WRITE` for the trace inode. Only then does it stage the nonce privately and atomically rename it to the exact gate. Readiness taking ten seconds or a gate delayed twelve seconds after recording start fails before arming. The trace may include setup and teardown; offline analysis selects the measured input interval from its device clock bounds.

Collection requires both the original recorder PID's exit and the exact trace's writable-close event. A PID with a different start time means the original process exited; permission failures are never interpreted as exit. A close event for another path, watcher loss/overflow, incomplete writer registration, or either missing completion signal prevents pull and the next gate. The background child's exit status is explicitly unavailable: a successful `-D` acknowledgment is not relabeled as its eventual exit code. A completed controller record still requires offline verification of trace bounds, loss and packet content.

The controller only terminates its own verified `inotifyd` observer, checking its PID/start time and exact command before signaling. It does not terminate Perfetto or Mihon. It retains remote trace evidence after failed recording/pull, and never deletes an unrelated path. It lets a missing-gate timeout reach the framework's restoration path instead of force-stopping Mihon. A host interruption can leave restoration or observer cleanup uncertain; inspect the capture record, final framework report and UI before another run.

Private output includes instrumentation text, command receipts, discrete power/thermal snapshots, the validated main PID, per-window ready/complete metadata, configs, trace hashes, the final framework report and referenced screenshots. The manifest distinguishes host-attested APK hashes from actual runtime process receipts. It includes all retained artifact hashes. It records `captured_windows_pending_offline_frame_and_pixel_analysis` only when the input report and completed events agree; that status is not a visual or performance pass.

Run the separate pixel/input oracle:

```sh
python3 -B scripts/android-ui-validation/reconcile_frame_pairs.py \
  build/translator/validation/private/RUN/framework-report.json \
  --output build/translator/validation/private/RUN-pixels.json
```

`analyze_frame_pairs.py` supplies separate per-window Perfetto analysis. Verify BOOTTIME/elapsed clock mapping, complete window coverage, exact process attribution, trace loss and jank classifications. Report draw scheduling/CPU evidence separately from FrameTimeline durations. Unsupported GPU timing and allocation counters must stay unavailable. Discrete power snapshots cannot establish continuous power state, and none of these observations approves translation meaning.

Offline simulated tests (no adb or device execution):

```sh
python3 -B -m unittest discover -s scripts/tests -p test_translator_frame_capture.py -v
```

Official behavior checked 2026-09-06: [`--background-wait` start acknowledgment](https://perfetto.dev/docs/reference/perfetto-cli), [waiting for background trace `CLOSE_WRITE`](https://perfetto.dev/docs/learning-more/tracing-in-background), and [Android Toybox inotifyd stdout events/line buffering](https://android.googlesource.com/platform/external/toybox/+/refs/heads/main/toys/other/inotifyd.c). The on-phone help receipts remain the compatibility authority for each run.

Classic calibration initially stopped before its first gate because the one-tap toolbar hide did not finish before the old generic idle check. The harness now observes the exact reader's title and slider disappearance for at most five elapsed seconds after one tap. It journals the observed delay or timeout and never retaps. Classic single-tap confirmation and the reader bars' 200ms exit animation can outlast the old settle's minimum wait; the bounded host regression models that race. Physical calibration 3 observed the controls disappear after 354ms, confirming that the earlier 350ms minimum could return too early. It then stopped before any frame gesture because of the unsupported Perfetto flags. Both failed runs remain retained. The later canonical classic runs above verified the background-wait start acknowledgment, owned recorder/writer identities, nonce gate and writable-close capture path.

Host regression for this transition (actual pure Java helper, no device):

```sh
python3 -B -m unittest discover -s scripts/tests -p test_translator_reader_controls.py -v
```

For the separately declared interior WebGPU protocol, add `--frame-anchor-pixels 100`. The controller validates `webgpu` before connecting, forwards `frameAnchorPixels=100`, and requires the exact same anchor descriptor in every ready/completed window and final report. Chapter 002 and reader page 7 remain mandatory in the framework. Omitting the flag preserves the prior page-start command and evidence format. The interior protocol retains two additional setup PNGs, records a distinct restoration target, and preserves all thirty-second timing and boundary guards. Both app builds must use this same explicit protocol; old page-start captures remain a separate comparison. See README for the one-shot gesture and repeatability conditions. This option never adapts an offset or retries a failed measurement.
