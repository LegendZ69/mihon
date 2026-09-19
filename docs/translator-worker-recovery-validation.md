# Durable Worker recovery validation

This opt-in protocol uses `MinifiedTranslationWorkerRecoveryAcceptanceTest#durableActualWorkerRecovery` and has been compiled and executed on K90. Its separate **start**, **collect**, and **cleanup** phases use the actual application graph and WorkManager. The [dated quality/recovery report](translator-quality-recovery-validation-2026-09-06.md) records tested artifacts and individual results. The earlier completion-while-screen-off **FAIL** remains recorded in [the original Worker report](translator-worker-acceptance-validation.md#mode-screen-off); automatic completion after thaw and recovery after actual user Force stop are separate acceptance cases.

A separate [active-Resume boundary test](translator-active-resume-validation.md) reproduced the failure in a matched kept-awake run on benchmark `7e061899…` at 90,005 ms after Resume, then passed on final `f5e6eb42…` with reported completion at 1,150 ms; both used instrumentation `8e3d6c6c…`. The first saved page/revision remained unchanged, only the stalled second page was retried, and both final WorkInfos succeeded. All 43 power snapshots were Awake, and the focused fix's 26 Manager unit tests also pass. The barrier proves two app request-start events, not identical already-server-accepted transport at the Resume instant; see the linked reconciliation and wire-timing qualification. This bounded active-job result does not establish Force-stop or screen-off recovery, and earlier failures remain preserved.

## Preconditions and local endpoint

The device owner alone executes device commands. Use an installed, matching, minified `app.mihon.benchmark` / instrumentation pair with the local-fixture build flag. Record their hashes/signatures, build versions, actual runtime page size, PID/start time, boot count, power/network state and initial app-only HyperOS policy. Preserve `app.mihon`, unrelated apps, credentials and existing model packs. Do not run another workload concurrently.

The start phase requires an empty benchmark translation queue, no unfinished unique `translator_queue` work, notification permission, online state, global automatic translation off and chapters ahead zero. It refuses rather than deleting preexisting work. Use the read-only `inspectWorkerPreflightOnly` invocation in the original Worker protocol first. The disposable two-image job fixes every provider setting, uses only `mihon-fixture-only` at localhost, explicitly disables quality review, and never reads a real provider credential. Neither case incurs cloud charges. Keep global preferences unchanged; the harness hashes them before/after.

Create a fresh canonical UUID and a new private ledger/server process for each case. Never reuse a scenario sequence or reset another owner's server:

```sh
TRANSLATION_SERIAL='selected-device-serial'
TRANSLATION_MODE='screen-off-thaw'
TRANSLATION_RUN_ID='new-lowercase-canonical-uuid'
TRANSLATION_RUN_DIR="build/translator/validation/private/worker-recovery-$TRANSLATION_MODE-$TRANSLATION_RUN_ID"
mkdir -m 700 "$TRANSLATION_RUN_DIR"
adb -s "$TRANSLATION_SERIAL" reverse tcp:8765 tcp:8765
python3 -B scripts/translator_fixture_server.py --port 8765 \
  --scenarios scripts/fixtures/translation-worker-scenarios.json \
  --scenario "worker-$TRANSLATION_MODE" \
  --ledger "$TRANSLATION_RUN_DIR/request-ledger.jsonl"
```

Run the server in its own managed host process; retain its PID and `/health` receipt. It must report `fixture=true`, `cloud_forwarding=false`, the expected scenario and fresh server run ID. The first generation succeeds immediately; only the second is delayed (45 seconds for thaw, 120 seconds for Force stop). Later attempts respond immediately. Count-token requests do not advance the generation sequence. Keep this same server alive through observation and cleanup, so its ledger distinguishes cancellation, delayed socket writes and retries.

Start instrumentation in another managed host process and retain stdout/stderr:

```sh
adb -s "$TRANSLATION_SERIAL" shell am instrument -w -r \
  -e class mihon.feature.translation.acceptance.MinifiedTranslationWorkerRecoveryAcceptanceTest#durableActualWorkerRecovery \
  -e translation.workerRecovery true -e translation.workerPhase start \
  -e translation.workerMode "$TRANSLATION_MODE" -e translation.workerRunId "$TRANSLATION_RUN_ID" \
  app.mihon.benchmark.test/androidx.test.runner.AndroidJUnitRunner \
  > "$TRANSLATION_RUN_DIR/start-instrumentation.txt" 2>&1
```

The test journals to `no_backup/translation-worker-recovery/UUID/checkpoint.json` and `report.json`, with status JSON also emitted through instrumentation. Its first-page barrier requires exactly one committed result, two actual provider-start events, a RUNNING WorkInfo and notification 7101. The checkpoint retains both original file hashes, full image identities, exact first result/revision, original process identity, settings digest and preexisting WorkInfo/background-event IDs. Independently require two generation dispatches in the host ledger before interrupting. A barrier timeout is a failed preparation, not evidence of interruption acceptance.

## Screen off, then thaw

The device owner can use the reusable `scripts/translator_worker_thaw.py` recorder below instead of manually coordinating these steps. It has simulated-command/clock tests and per-run K90 evidence, including the rejected early-wake case and valid repeat below. The script requires an already running **fresh, owned** loopback fixture server with an empty private ledger, an already installed matching minified pair, and an existing reverse mapping; it creates or changes none of those. Root configures the approved app-only policy and notification permission through UI, then restores the original policy after the matrix.

Use a new, nonexistent output directory beneath an existing private parent. Supply the server's `/health` run ID and exact SHA-256 values of the approved installed APKs:

```sh
python3 -B scripts/translator_worker_thaw.py observe \
  --serial "$TRANSLATION_SERIAL" --run-id "$TRANSLATION_RUN_ID" \
  --policy-label existing-policy \
  --output "$TRANSLATION_RUN_DIR/host-observation" \
  --server-run-id "$TRANSLATION_SERVER_RUN_ID" \
  --ledger "$TRANSLATION_RUN_DIR/request-ledger.jsonl" \
  --benchmark-sha256 "$TRANSLATION_BENCHMARK_SHA256" \
  --test-sha256 "$TRANSLATION_TEST_SHA256"
```

`observe` verifies the installed APK bytes with device `sha256sum`, captures package/UID/PID, power/network/battery/thermal/foreground-service/scheduler evidence and a bounded filtered system logcat without clearing logs. It validates both the actual saved-page instrumentation barrier and independent wire dispatches before Home/Sleep. One midpoint snapshot has three-second per-command bounds so recording cannot silently extend the planned 60-second off interval. It then sends Wake only and gives the app its full 180-second completion window after observed screen-on, plus Worker-finish/teardown allowance. There is no activity launch, target instrumentation restart or queue action during that interval. The recorder does not change any policy, permission, network or reverse rule. Its static power/system sampling is part of this measured protocol and can affect idle behavior.

The host's Sleep→Wake delay alone is insufficient: on 2026-09-06 the unrestricted-policy run `73df607f-e31e-40aa-93f1-f4b1f082f8df` reported a raw instrumentation PASS after the phone woke early. Its app off/on timestamps span only **20,380 ms**, despite a host Sleep→Wake delay of **60.334 seconds**. That run is invalid for the required 60-second screen-off protocol; its raw report remains preserved. The corrected recorder starts its 60-second interval only after receiving the app's noninteractive checkpoint, rejects an app screen-on report received before planned Wake, and requires noninteractive midpoint/pre-Wake power receipts. Immediately before Wake it records `/proc/uptime`; final reconciliation requires an app off/on span of at least 60,000 ms and an app screen-on timestamp at or after that device-clock receipt. Both clocks include suspend time, and the raw receipt's precision is retained. [Linux uptime documentation](https://man7.org/linux/man-pages/man5/proc_uptime.5.html)

An early wake records overall `FAIL` with `protocol_verdict=INVALID`, independently of the raw JUnit status. Raw instrumentation and the latest report remain available. The recorder does not cancel the target to manufacture a conclusion; if it rejects while start instrumentation remains active, preserve that fact and resolve the named observation before any collection. A later passing collection cannot repair an invalid screen-off interval.

The corrected oracle passed the separately recorded unrestricted-policy repeat `e560cb5c-3203-46d2-b690-ef19feae5ebf` on benchmark `7e061899…` / instrumentation `8e3d6c6c…`. The app's noninteractive interval was **61,243 ms**; its screen-on checkpoint followed the pre-Wake device-clock receipt. At that checkpoint the job was already COMPLETED and its WorkInfo SUCCEEDED; the final job update preceded screen-on by **16,579 ms**. The reported **8 ms** is the post-wake confirmation wait, not translation completion or recovery latency. Both pages dispatched once and the exact first saved result was retained. Evidence is `build/translator/validation/private/quality-worker-thaw-unrestricted-repeat-e560cb5c-3203-46d2-b690-ef19feae5ebf/host-observation/`, including `observation.json`, `start-report.json` and `collected-report.json`. This bounded completion-while-off repeat does not relabel the earlier invalid run and predates the focused Resume fix; it does not demonstrate recovery of still-pending work after thaw or a handset-wide recovery guarantee.

The immutable pre-collection verdict and a ledger snapshot are retained in `observation.json`, `start-report.json`, `ledger-at-observation-end.jsonl`, raw instrumentation output and the phase manifest. A host interruption attempts only Wake restoration. It never cancels the Worker or kills target instrumentation to manufacture a result. If `instrumentation_left_running=true`, stop and explicitly resolve that named observation with root; **do not run collect or cleanup while the target observation is still active**. The report includes the owned host PID. A host process left running can continue appending its output, so hash that file again after it ends. SIGKILL of the host recorder cannot execute restoration; retain that limitation and restore the display through observed UI.

After `observe` has actually concluded and its outcome is preserved, run the exact same arguments with the first argument changed from `observe` to `collect`. Collection refuses an unfinished start or mismatched identity/APK hashes. It initializes the target process exactly once, writes `collected-report.json`, and does not alter the observation verdict. This collector is **not applicable to the force-stopped passive window**. The separate explicit cleanup command later in this document remains required; the host recorder deliberately does not remove the disposable job or credential.

1. At `awaiting_screen_off` plus the second ledger dispatch, record the foreground service and current policy. Within 30 seconds issue Home, then Sleep. Save exact monotonic/host timestamps and UI/system receipts. The harness records `observed_screen_off` only after PowerManager becomes noninteractive.
2. Leave the display off for **60 seconds** under the existing policy. Use bounded, read-only host/system recording; do not launch an activity, change network state or tap Resume. Capture OEM freeze/thaw and wakelock/TCP evidence when available. A server `response_sent` is not app receipt.
3. Wake the display. Do not launch Mihon, navigate to Translator or invoke any queue action during the automatic interval. The harness requires the same PID/start time, records `observed_screen_on`, and waits up to **180 seconds** for completion, followed by up to 30 seconds for its owned Worker to finish. Keep the display interactive. Record the wake receipt and state throughout the interval.
4. Automatic PASS requires two committed pages, unchanged first result and image identities, completed count two, two or three provider starts, and finished owned WorkInfo. Reconcile wire hashes: first page exactly once; only the interrupted second page may be dispatched again. The report records latency after the screen-on checkpoint. No minimum speed or guaranteed HyperOS behavior is inferred.
5. A timeout, changed PID, changed original result, extra completed-page dispatch or stuck state remains FAIL. Capture the job, WorkInfo/stop reason, newly observed background events, ledger and targeted system evidence before cleanup. Instrumentation teardown after a failure can kill its target process: any subsequent user launch/manual Resume is a **separate trigger and process case**, not same-process automatic thaw recovery. Do not relabel it as success.

The initial off/on protocol has explicit elapsed-time deadlines. A freeze can delay delivery of instrumentation status; use host Wake and process/system timestamps rather than assuming the device executed throughout that interval. This is an instrumented active Worker case, not natural idle or proof of process eviction.

The current user plan already authorizes the three app-only HyperOS policy conditions and restoration. Run each as a separate fresh case under that existing authorization. Record the exact original value and each changed value through UI, use the same duration/workload, and restore the original value in host `finally`. Do not infer a policy name from another HyperOS build or change global battery/VPN policy. Label each case by its actual observed policy and retain all outcomes, including the original FAIL.

## Actual Settings Force stop

The optional [read-only Force-stop observer](translator-force-stop-observer.md) records the three fixed host windows without launching or controlling the target. Actual Settings Force stop, launcher entry, any later manual Resume and cleanup remain separate operator actions.

Set `TRANSLATION_MODE='force-stop'` with a new UUID/server. At `awaiting_settings_force_stop` and the independently observed second dispatch:

1. Within 60 seconds open Android **App info for the verified benchmark package**, tap its actual **Force stop** control, and confirm the system dialog. Derive selectors/bounds from fresh UI evidence. Record package/title, before/after screenshots/XML, the confirmation and timestamp. Do not use `am force-stop`, task removal, crash injection, direct manager cancellation or a simulated exit as a substitute.
2. Confirm the original PID is gone, the package reports `stopped=true`, the translation foreground service is gone, and target instrumentation ended because the process was stopped. An interrupted JUnit run is the expected start outcome; a normal passing start invocation is not Force-stop evidence. `ApplicationExitInfo.REASON_USER_REQUESTED` alone is ambiguous and must be joined to these actual UI/system receipts.
3. Keep the package stopped for **30 seconds**, using only external system queries and the fixture ledger. **Do not invoke target instrumentation, collect, preflight, an app component, or an explicit intent during this passive interval.** Such a launch can change the stopped state and invalidates passive attribution. Require no new generation dispatch while stopped; the already pending response may finish later as disconnected or socket-written and must remain separately recorded.
4. Launch once through the actual benchmark launcher icon. Record the launch UI/timestamp, `stopped=false`, new PID/start time and entry activity. Open Translator through normal navigation, without tapping any Resume/Retry/Translate action. Observe for **180 seconds** and record queue state, notification/WorkManager/system state and request ledger. A page-preserving completion attributable to this launch is **automatic after user launch**, not automatic while force-stopped.
5. If launcher-only recovery does not complete, preserve that outcome and take the explicit **per-job Resume** action through the queue UI once. Retain its scoped selector/title/time. Observe a further **90 seconds**, as specified in the approved plan. Label resulting completion **manual Resume after launch**, with the previous launcher-only result retained. Do not retry a chapter, clear results or invoke a test helper to repair its state.
6. Only after the chosen observation interval and its host evidence are complete, use the collection phase below. Collection itself initializes target application state and WorkManager; any new activity after collection must be attributed separately. If collection was needed to diagnose a stalled launch before manual Resume, record that instrumentation boundary explicitly rather than claiming an uninterrupted launcher-only process.

Check [Android stopped-state behavior](https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state), [process exit reasons](https://developer.android.com/reference/android/app/ApplicationExitInfo#REASON_USER_REQUESTED) and [`ApplicationStartInfo.wasForceStopped`](https://developer.android.com/reference/android/app/ApplicationStartInfo#wasForceStopped()) when interpreting these receipts (checked 2026-09-06). API 35+ startup history is supporting evidence; availability errors remain explicit.

## Collect without queue control

Use the same UUID/mode. Select one exact host label: `post-thaw`, `post-launch`, `post-manual-resume`, or `before-cleanup`. The label is an operator attribution, not proof of the trigger.

```sh
adb -s "$TRANSLATION_SERIAL" shell am instrument -w -r \
  -e class mihon.feature.translation.acceptance.MinifiedTranslationWorkerRecoveryAcceptanceTest#durableActualWorkerRecovery \
  -e translation.workerRecovery true -e translation.workerPhase collect \
  -e translation.workerObservation post-thaw \
  -e translation.workerMode "$TRANSLATION_MODE" -e translation.workerRunId "$TRANSLATION_RUN_ID" \
  app.mihon.benchmark.test/androidx.test.runner.AndroidJUnitRunner \
  > "$TRANSLATION_RUN_DIR/collect-post-thaw.txt" 2>&1
```

Collection makes no health request, credential import, queue write, enqueue/start, resume, retry, cancellation or removal call. It checks same boot, owned-job isolation, the exact first result, original file/image hashes and unchanged settings, then appends observed jobs/results/batches/events, newly seen queue background events and WorkInfo state/attempt/stop reason. It adds historical exits for the original PID since the barrier and optional startup history. Each phase retains earlier records; collection failures are written explicitly. A passing collect invocation means those preservation checks passed, **not** that the job completed or that its completion trigger is established. Bind the relevant pre-collection host ledger interval and UI/system evidence before reporting PASS.

The minified package is not `run-as` readable. Use emitted JSON and separately archived host receipts; do not loosen app storage permissions or read private real credentials. Repeat collection only when the additional process initialization is justified and recorded.

## Explicit cleanup and restoration

Archive the pre-cleanup report and reconcile the first-page object/request hashes first. Cleanup is a distinct, intentionally mutating operation:

```sh
adb -s "$TRANSLATION_SERIAL" shell am instrument -w -r \
  -e class mihon.feature.translation.acceptance.MinifiedTranslationWorkerRecoveryAcceptanceTest#durableActualWorkerRecovery \
  -e translation.workerRecovery true -e translation.workerPhase cleanup \
  -e translation.workerMode "$TRANSLATION_MODE" -e translation.workerRunId "$TRANSLATION_RUN_ID" \
  app.mihon.benchmark.test/androidx.test.runner.AndroidJUnitRunner \
  > "$TRANSLATION_RUN_DIR/cleanup.txt" 2>&1
```

Cleanup refuses unrelated benchmark jobs, pauses/removes only the named disposable job, cancels only WorkInfo IDs absent from start preflight, removes its provider checkpoints and synthetic credential, and verifies `owned_job_removed`, `credential_removed`, `settings_unchanged` are all true. Failure preserves the first exception plus suppressed cleanup errors and leaves `cleanup_required=true`. The run's image/report evidence stays private; finished global WorkManager records are not pruned. A start rejected before a checkpoint is persisted has not imported a key or inserted a job; preserve the preflight failure without inventing cleanup assertions.

Stop only the owned local server after outstanding delayed responses are recorded. Remove only an adb reverse mapping this task created. Restore recorded network/display state, any explicitly authorized app-only policy comparison, and the benchmark launch state as applicable; do not force-stop the personal `app.mihon` package. Hash reports, request ledgers, APKs, UI receipts and system captures. Test generated text is synthetic and does not provide passage-quality evidence.

Before proposing a production fix, distinguish: process still frozen versus thawed; transport lost versus response processed; Worker RUNNING versus scheduler restart; persisted active job versus WAITING/PAUSED; request retry versus completed-page resubmission; user launch versus manual Resume versus collector initialization. A saved active job and APPEND_OR_REPLACE scheduling are hypotheses to inspect with evidence, not proof of a recovery defect by themselves.
