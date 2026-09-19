# Actual foreground Worker acceptance

The opt-in instrumentation is in `app/src/androidTest/java/mihon/feature/translation/acceptance/MinifiedTranslationWorkerAcceptanceTest.kt`. Produce a matching minified benchmark/instrumentation APK pair through the normal build. The normal minified pair passed physical K90 notification-pause and network-recovery runs on 2026-09-06. Screen-off completion failed under the phone's existing policy, with observed HyperOS process freezing; artifact-specific evidence is below. No production interface changes are needed.

The test uses the real application graph and `TranslationWorker.start()`, waits for real WorkManager RUNNING state, verifies the real notification and records WorkInfo state/attempt/stopReason. It never calls `runQueue()` directly. The existing `MinifiedTranslationQueueAcceptanceTest` and actual-crash protocol do not provide this coverage: both own manager coroutines in isolated graphs, whereas `TranslationWorker` and its notification receiver resolve the application graph.

## Isolation and preparation

- Root owns every ADB/UI action. No agent should run this protocol concurrently with reader instrumentation, APK installs or live jobs.
- Installed package must be the normal minified `app.mihon.benchmark`; local fixture build flag must be enabled. The test refuses debug/release/personal app packages.
- The benchmark translation queue must be empty and the unique `translator_queue` WorkManager chain must have no unfinished work. It does not reset any database. If preflight refuses, inspect and preserve those jobs; do not bypass or weaken it. The notification action calls `pause()` globally, which updates timestamps even on existing PAUSED jobs, hence this stronger condition.
- Keep global automatic translation off and avoid opening chapters during the test. Global translator preferences are not changed. All provider settings are fixed on the one disposable job: OpenAI-compatible Chat dialect, `http://127.0.0.1:8765/v1`, model `mihon-fixture`, synthetic key `mihon-fixture-only`, no raw capture, one image at a time, two generated pages.
- Record hashes/signature/version of the actual installed benchmark and test APKs, device identity and initial network/display state. These are bounded local tests and do not enter the paid ledger.
- Grant/verify notification permission through the app/device UI if the notification is not visible; do not silently replace the notification action with a direct manager call. The receiver is not exported, so `am broadcast` is not valid external coverage.
- Use a fresh canonical UUID, fresh private evidence directory and fresh fixture server **per mode**. Server scenario sequence numbers are scoped to its process; reusing a consumed server invalidates exact dispatch-count assertions.

Set operator variables (substitute explicit serial and selected mode):

```sh
TRANSLATION_SERIAL='selected-device-serial'
TRANSLATION_MODE='notification'
TRANSLATION_RUN_ID='new-lowercase-canonical-uuid'
TRANSLATION_RUN_DIR="build/translator/validation/private/worker-$TRANSLATION_MODE-$TRANSLATION_RUN_ID"
mkdir -m 700 "$TRANSLATION_RUN_DIR"
adb -s "$TRANSLATION_SERIAL" reverse tcp:8765 tcp:8765
```

The following opt-in preflight only reads queue counts/states, WorkManager state, network state and notification availability. It does not read credentials, import keys, create jobs, change settings, clear data or start a Worker. Inspect `translation_worker_preflight_json.ready`; a passing JUnit invocation only means inspection succeeded, not that `ready` is true. Previous pipeline acceptance uses direct provider calls and removes its unique credential; isolated queue/crash protocols store jobs in separate graphs. Those facts suggest an empty global benchmark queue, but do not replace this actual check.

```sh
adb -s "$TRANSLATION_SERIAL" shell am instrument -w -r \
  -e class mihon.feature.translation.acceptance.MinifiedTranslationWorkerAcceptanceTest#inspectWorkerPreflightOnly \
  -e translation.workerPreflight true \
  app.mihon.benchmark.test/androidx.test.runner.AndroidJUnitRunner
```

Start a new loopback-only server in its own managed host process. Do not kill another owner's server blindly; stop the previously owned disposable server by its recorded PID first.

```sh
python3 -B scripts/translator_fixture_server.py --port 8765 \
  --scenarios scripts/fixtures/translation-worker-scenarios.json \
  --scenario "worker-$TRANSLATION_MODE" \
  --ledger "$TRANSLATION_RUN_DIR/request-ledger.jsonl"
```

Verify its `/health` identifies `fixture=true`, `cloud_forwarding=false`, the expected scenario and new server run ID. Start this instrumentation, retaining stdout/stderr while it runs:

```sh
adb -s "$TRANSLATION_SERIAL" shell am instrument -w -r \
  -e class mihon.feature.translation.acceptance.MinifiedTranslationWorkerAcceptanceTest \
  -e translation.workerAcceptance true \
  -e translation.workerMode "$TRANSLATION_MODE" \
  -e translation.workerRunId "$TRANSLATION_RUN_ID" \
  app.mihon.benchmark.test/androidx.test.runner.AndroidJUnitRunner \
  > "$TRANSLATION_RUN_DIR/instrumentation.txt" 2>&1
```

The test emits `translation_worker_status` plus a complete JSON report through instrumentation status, so app-private 0700 file access does not require weakening storage permissions. Barriers contain actual two transport-start events and one committed result with its revision. Independently require **two generation dispatches** in the host ledger (ignore count-only rows) before injecting any interruption.

## Mode: notification

At `awaiting_notification_pause` the second image is delayed for 120 seconds, the first result is committed, WorkInfo is RUNNING, and notification 7101 has the expected title and Pause action. Within 60 seconds:

1. Capture `dumpsys activity services app.mihon.benchmark` and notification evidence with private storage. Avoid a whole-device notification dump in public output because it can contain unrelated notifications.
2. Open the notification shade using `cmd statusbar expand-notifications` or the device UI.
3. Dump the UI tree; locate **Mihon translator** and derive the notification body's bounds. Expand that row first if the action is collapsed. On the tested HyperOS 3 handset, ordinary swipe expansion did not expose Pause: an **800 ms long-press inside that notification body** opened a modal containing the title **Mihon translator** and an action with both text and content description **Pause**. In that observed modal the Pause action was indexed `action0`; do not use the index alone as its identity. Derive the long-press and subsequent tap coordinates from fresh UI bounds, verify the modal title and exact Pause label, and avoid the separate **Turn off** and **More** controls. Tap that exact action and retain before/after screenshots and XML. There may be other Pause labels; never choose the first global match blindly.

The first two local handset attempts (`0c343a14-b1af-48fd-9028-124d3c555ad3` and `06d242fe-b90b-4c9b-9848-6b46f53cc959`) timed out at the 60-second operator barrier while this HyperOS notification path was being identified. Their cleanup fields were true, but they did not establish completed-page notification acceptance. A Pause tap after the barrier timeout is not passing evidence. Subsequent attempts must start with a new run UUID and fresh server session, then perform the verified long-press and scoped Pause action promptly after both the instrumentation barrier and independent second dispatch are observed. Do not extend or bypass the assertions to turn those earlier attempts into passes.

The third handset attempt, `0ab71f09-e7e0-4150-bf08-c96a958f48f8`, passed in **6.729 seconds** on minified benchmark APK `4e9559a474eb5620c2b743a51e78468db9614c717d817cfb76b1bd7d88e179a7` and test APK `61e295a873b442dcef9363f16864696f87de95ff0420dd130fd084c58791f6fc`. The observed notification action paused the job with its first committed result intact; instrumentation then invoked the per-job resume action, and a real Worker completed the remaining image. Independent ledger reconciliation found three generation dispatches: the completed image once and the unfinished image twice. The cancelled delayed request eventually finished as disconnected after 120,008 ms. Credential removal, owned-job removal and unchanged settings all passed. Private evidence is retained under `build/translator/validation/private/worker-notification-0ab71f09-e7e0-4150-bf08-c96a958f48f8/`. This passing attempt does not change the verdicts of the two earlier timeouts or establish natural-idle/eviction behavior.

The instrumentation requires job PAUSED, an INTERRUPTED batch, the exact preserved first result, exactly two requests, and the Worker finishing. It then emits `paused_by_notification` and calls the same **per-job** `manager.resume(jobId)` used by queue UI; this schedules a new real Worker. No global Resume is invoked. Passing completion requires two results, original first-result revision/hash, three transport starts and no source changes.

Independent server ledger: first image hash once, second hash twice, three generation dispatches total. The delayed cancelled request may emit a disconnect later; preserve its delayed finish or mark it outstanding rather than inventing success. This proves the actual notification action and WorkManager continuation; it does not prove OS process death or user force-stop.

## Mode: network

Record the actual starting Wi-Fi, cellular, airplane and VPN policies before this run. On this handset the known starting configuration was Wi-Fi on and airplane mode already on. Reverify rather than assuming it. The host must restore the exact recorded Wi-Fi state in a `finally`/shell trap even when instrumentation fails.

At `awaiting_network_loss` and the independent second ledger dispatch, disable **only Wi-Fi** using `svc wifi disable` when doing so removes the validated network under the recorded policy. Do not change airplane mode, cellular data or the user's VPN preferences. If another validated network remains, this injection cannot establish the intended case; restore Wi-Fi and report the limitation.

Wait for `awaiting_network_restore`, which asserts:

- the app's actual network state became offline;
- the job became WAITING or QUEUED;
- no owned WorkInfo remains RUNNING;
- only the first result is present and exactly two provider requests started.

Record device connectivity and job-scheduler evidence, then restore Wi-Fi using `svc wifi enable`. The test waits for a validated online network and emits `network_restored`. It makes **no** resume, start or queue-state write after restoration. WorkManager must resume on its own within the bounded 180-second completion wait, retaining the first page and sending only the second again. WorkInfo stop reasons and `TranslationWorker` background-stop events are retained as observed numeric evidence, not hardcoded OEM assumptions.

ADB reverse alone is **not** a network-loss test: removing it changes local endpoint reachability while the Android network can remain online. Conversely, adb reverse can keep a loopback socket alive while Wi-Fi is off; cancellation must come from actual network constraints, not merely an HTTP error. Do not replace this case with stopping the fixture server or direct coroutine cancellation.

If the job becomes PARTIAL/FAILED, gets stuck, or resubmits the completed page, preserve the failed report as a reproduced behavior. Do not manually resume it to turn that case green. Cleanup removes only the disposable test job/credential.

The K90 run `f540e4cd-81ed-40db-bf2a-d7626cc3a0ff` passed in **31.323 seconds** on the same minified `4e9559a4…` / `61e295a8…` pair identified above, at an actual 4 KB runtime page size. Disabling Wi-Fi produced an app-observed offline state and a device connectivity record of no active default network. The job changed from TRANSLATING to WAITING with the message “Waiting for the required network”; its first completed result, geometry, text and revision remained identical across all four checkpoints. After Wi-Fi restoration, the same WorkManager request advanced from attempt 1 to attempt 2 and SUCCEEDED without a manual resume, new start call or state rewrite. Independent report/ledger checks found exactly three generation dispatches, with the completed image sent once and only the unfinished image sent again. The delayed abandoned request eventually finished as disconnected after 120,009 ms. Initial Wi-Fi/airplane/mobile-data settings were restored, and all three owned-job/credential/settings cleanup assertions passed.

Private evidence and reconciliation are under `build/translator/validation/private/worker-network-f540e4cd-81ed-40db-bf2a-d7626cc3a0ff/`. Completion was observed 27.256 seconds after the online checkpoint in this run; that timing is a measurement of this scheduler state, not a guaranteed reconnect latency. WorkInfo stop reason remained `-256`, and no background-stop event was recorded, so this result is not labeled OS eviction. It establishes actual network-state handling and automatic WorkManager retry against the local fixture while the screen and instrumentation remain active; paid-provider transport, screen-off, process death and natural-idle behavior require separate evidence.

## Mode: screen-off

At `awaiting_screen_off` the first page is saved and the second response is delayed 45 seconds. Within 30 seconds issue Home and Sleep (`input keyevent KEYCODE_HOME`, then `input keyevent KEYCODE_SLEEP`) and retain the command receipts. The test emits `observed_screen_off` only after the real PowerManager reports the display noninteractive. It requires the display to remain off throughout completion and the normal Worker to complete both pages with exactly two requests.

Record foreground-service/job-scheduler state without waking the screen. Wait for passing instrumentation before Wake, returning to the benchmark app, and verifying cached results through the report. Restore the initial interactive state when it differs. This measures a short active foreground-worker screen-off run under instrumentation. Instrumentation affects process importance; **it cannot validate natural idle, app standby/Doze eligibility, HyperOS eviction or unattended long-term background survival.** Keep those results separately pending.

The K90 run `0bcbbd9a-de30-4b43-8e57-a57cfbe92bdc` **failed** on the same minified `4e9559a4…` / `61e295a8…` pair under the unchanged device policy. The test observed the noninteractive display, but its 90-second completion assertion timed out. The host's separate 100-second wait then woke the phone during failure cleanup; JUnit reported **101.515 seconds**. A frozen process cannot promptly deliver its own timeout, so distinguish this elapsed duration and the host's Wake receipt from active execution time. The first result's complete stored object, including geometry, text, hash and revision, was identical at all three checkpoints. The second batch remained RUNNING and no second result was committed. Credential removal, owned-job removal and unchanged settings all passed.

Private evidence is under `build/translator/validation/private/worker-screen-off-0bcbbd9a-de30-4b43-8e57-a57cfbe92bdc/`. The captured foreground service was active with notification 7101. In `post-logcat.txt`, lines 7466–7480 show HyperOS acknowledging that foreground service, freezing the benchmark UID for `screen off`, disabling its WorkManager foreground wakelock with reason `greeze`, and destroying that UID's live TCP sockets. Lines 13147–13178 show the same PID thawing when the screen turns on. The pre-wake power capture independently reports the WorkManager foreground wakelock as DISABLED. It reports neither Android light/deep device-idle mode nor low-power standby as active, so those states must not be substituted for the observed OEM freeze.

The local server recorded exactly two generation dispatches and marked the delayed second response sent after **45,006 ms**. That host socket write does not prove receipt or processing by the frozen app. The app recorded no background-stop event, and WorkInfo still reported RUNNING with stop reason `-256`; neither value disproves the separately observed freeze. The later process kill is explicitly instrumentation teardown (`finished inst`), not evidence of screen-off eviction or an app crash. The timeout cleanup cancelled the disposable work immediately after thaw, so **post-thaw automatic recovery remains unmeasured** in this run. Preserve this failure without repeating the same conditions or changing OEM policy to relabel it a pass.

The existing Translator **Background help** already explains saved completed pages, returning to Translator and using Resume, inspecting waiting reasons and background logs, and checking HyperOS background activity/autostart through App info. Those are recovery actions, not a claim that the app can detect or prevent this OEM freeze; no production change or device-policy change was made for this finding.

The separate [durable Worker recovery protocol](translator-worker-recovery-validation.md) adds start/collect/cleanup phases for automatic completion after screen-on and actual Settings Force stop. Its new cases await compilation/execution and do not change this recorded failure.

## Cleanup and evidence acceptance

Normal success/failure cleanup pauses only the owned job, cancels only WorkInfo IDs created after preflight, removes only that job and its provider checkpoints, removes only its synthetic credential, and verifies global translator settings are unchanged. It retains the run's generated images and private report as evidence. Report fields `credential_removed`, `owned_job_removed`, `settings_unchanged` must all be true. Existing finished WorkManager records are not pruned. A process kill would bypass this finally block and is not part of these modes; preserve evidence and explicitly clean the named disposable job/credential on a separate recovery invocation if that occurs.

Reconcile report and ledger by original image content hashes; snapshot event counts alone are not wire evidence. Hash all run artifacts. Record FAIL when assertions fail, PENDING when required UI/network permission/barriers cannot be established, and PASS only with observed host action receipts and independent matching dispatches. No passage-level meaning assessment is performed: output is synthetic fixture text.

After collection remove only the task-created adb reverse rule if it did not preexist, stop only the owned fixture server and restore network/display state. Do not clear logcat, reset app data, prune global WorkManager state, modify production credentials or touch `app.mihon`.
