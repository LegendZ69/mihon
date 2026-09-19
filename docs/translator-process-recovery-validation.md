# Minified queue recovery across an actual process crash

This opt-in protocol tests normal `TranslationManager.runQueue()` recovery in two separate Android instrumentation invocations. It uses cached synthetic pages and a fixed dummy key against the loopback fixture server. It does not invoke WorkManager, reset the benchmark application's existing database, or touch `app.mihon` data. Android 11/API 30 or newer is required for exit-reason evidence.

Each run needs a new canonical UUID, a freshly started fixture-server session and matching minified `app.mihon.benchmark` / instrumentation APKs. The explicit `start` stage creates a run directory under the benchmark app's no-backup files, with separate graph/database/preferences/credential files using the existing `Fixture` / `IsolatedContext`. The `resume` stage must find the same directory, server session and committed checkpoint. A repeated start refuses an existing directory. Other acceptance tests continue using their separate random cache directories.

## What the stages establish

The server's `recovery-delay` sequence returns one successful singleton response, delays the next for 120 seconds, then succeeds immediately on later attempts. The start stage runs two images with one active image/request, checkpoints the first actual committed result, its full revision/content and both source-file hashes, then waits up to 60 seconds for an external process crash. It prints `TRANSLATION_PROCESS_READY` and atomically saves report status `awaiting_external_crash`. The enclosing test is bounded to 90 seconds and fails if no crash arrives. It does not gracefully cancel its queue at the intended barrier.

The host must verify the second dispatch in the independent ledger before crashing the reported PID. Resume requires a different process instance and a matching historical `ApplicationExitInfo` reason `REASON_CRASH` or `REASON_CRASH_NATIVE`. User force-stop, low-memory kill, self-exit and coroutine cancellation are rejected as evidence for this protocol. The run still requires the saved host crash-command receipt; a historical crash reason alone does not prove which command caused it. Android distinguishes these exit reasons and retains only a bounded history. [Exit reasons](https://developer.android.com/reference/android/app/ApplicationExitInfo), [historical exit records](https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,%20int,%20int)).

Resume verifies the original active job/batch state and exact completed result before calling the normal manager. It does not manually set the job to queued. After completion it checks that the committed result/revision is unchanged, originals still match their hashes, only the unresolved image has another batch, and exactly one additional provider transport-start event exists. The completed image is selected from actual results rather than assumed task-launch order. Its isolated dummy credential is removed before a passing final report is written; other run evidence remains available.

## Reproducible operator commands

The following commands are for the device/build owner. Record installed APK hashes and the exact device identity separately. Use the actual instrumentation component returned by `pm list instrumentation` for the installed test APK; the ordinary benchmark component is shown below. Do not install or rebuild between start and resume.

Set a real explicitly selected adb serial and a fresh UUID. The local directory is private; neither variable reuses a system environment variable.

```sh
TRANSLATION_SERIAL='explicit-device-serial'
TRANSLATION_RUN_ID='canonical-uuid-from-uuidgen-lowercased'
TRANSLATION_RUN_DIR="build/translator/validation/private/process-recovery-$TRANSLATION_RUN_ID"
mkdir -m 700 "$TRANSLATION_RUN_DIR"
adb -s "$TRANSLATION_SERIAL" reverse tcp:8765 tcp:8765
```

Run a new server in a separate host terminal, preserving its process and ledger across both stages. Do not reuse a session that has already consumed this scenario. Its port must be available or released by the owner of the prior disposable server.

```sh
python3 -B scripts/translator_fixture_server.py --port 8765 \
  --scenarios scripts/fixtures/translation-process-recovery-scenarios.json \
  --scenario recovery-delay \
  --ledger "$TRANSLATION_RUN_DIR/request-ledger.jsonl"
```

Start instrumentation in another terminal and retain its output. It is expected to terminate abnormally when the process is deliberately crashed; an ordinary passing `start` is not the desired evidence.

```sh
adb -s "$TRANSLATION_SERIAL" shell am instrument -w -r \
  -e class mihon.feature.translation.acceptance.MinifiedTranslationQueueAcceptanceTest#actualProcessCrashRetainsCommittedPages \
  -e translation.processRecovery true \
  -e translation.recoveryRunId "$TRANSLATION_RUN_ID" \
  -e translation.recoveryStage start \
  app.mihon.benchmark.test/androidx.test.runner.AndroidJUnitRunner \
  > "$TRANSLATION_RUN_DIR/start-instrumentation.txt" 2>&1
```

Once the report is `awaiting_external_crash`, the output names the run/PID and the host ledger contains two singleton dispatches, retain the pre-crash report and crash **that still-current benchmark PID** within 60 seconds. Inspect `pidof app.mihon.benchmark` before using the PID printed at the barrier. Substitute that verified PID below; never use the production package's PID.

```sh
TRANSLATION_PID='verified-benchmark-pid-from-readiness'
adb -s "$TRANSLATION_SERIAL" shell am crash "$TRANSLATION_PID" \
  > "$TRANSLATION_RUN_DIR/crash-command.txt" 2>&1
```

Invoke resume using the same UUID and server:

```sh
adb -s "$TRANSLATION_SERIAL" shell am instrument -w -r \
  -e class mihon.feature.translation.acceptance.MinifiedTranslationQueueAcceptanceTest#actualProcessCrashRetainsCommittedPages \
  -e translation.processRecovery true \
  -e translation.recoveryRunId "$TRANSLATION_RUN_ID" \
  -e translation.recoveryStage resume \
  app.mihon.benchmark.test/androidx.test.runner.AndroidJUnitRunner \
  > "$TRANSLATION_RUN_DIR/resume-instrumentation.txt" 2>&1
adb -s "$TRANSLATION_SERIAL" pull \
  "/sdcard/Android/data/app.mihon.benchmark/files/translation-acceptance/queue-process-recovery-$TRANSLATION_RUN_ID/report.json" \
  "$TRANSLATION_RUN_DIR/report.json"
```

Require a passing resume and `credential_removed=true`, the recorded crash reason/PIDs, preserved committed revision/hash, and independent wire-ledger reconciliation: three generation dispatches total, the completed image hash once and unresolved image hash twice. The abandoned delayed request may produce a disconnect/finish event later; allow it to finish or explicitly mark its finish missing rather than inventing a completed response. Preserve both reports and command logs. A new application process, a graph recreation and a new instrumentation invocation are related but distinct evidence.

This protocol does not establish WorkManager relaunch, foreground-service/notification recovery, force-stop behavior, connectivity interruption, HyperOS eviction, or translation meaning. Those remain separate cases. If an OEM omits the required exit history, record the evidence limitation/failure instead of weakening the crash assertion.
