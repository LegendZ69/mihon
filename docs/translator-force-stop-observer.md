# Read-only Force-stop observation

`scripts/translator_worker_force_stop_observe.py` records the fixed windows in the [durable Worker protocol](translator-worker-recovery-validation.md#actual-settings-force-stop). It has simulated-command tests; physical execution is not implied. The device owner executes it **after** performing the corresponding actual UI action. The script never launches an activity or instrumentation, sends input, changes a policy/permission/network setting, starts a server, invokes queue controls, stops processes, or cleans up jobs. It uses an explicit allowlist of system queries.

Use the existing, owned `worker-force-stop` fixture session and the same canonical UUID throughout the three windows. Before the actual Settings Force stop, retain the exact `awaiting_settings_force_stop` report from start instrumentation: one saved page, two provider starts and a RUNNING WorkInfo. `--barrier-report` accepts either that JSON snapshot or the start output whose last emitted report is still that barrier. A start that already timed out is rejected. Preserve its original failure separately.

After the actual Settings confirmation, run:

```sh
python3 -B scripts/translator_worker_force_stop_observe.py passive-stopped \
  --serial "$TRANSLATION_SERIAL" --run-id "$TRANSLATION_RUN_ID" \
  --server-run-id "$TRANSLATION_SERVER_RUN_ID" \
  --ledger "$TRANSLATION_RUN_DIR/request-ledger.jsonl" \
  --barrier-report "$TRANSLATION_RUN_DIR/barrier-report.json" \
  --benchmark-sha256 "$TRANSLATION_BENCHMARK_SHA256" \
  --policy-label existing-policy \
  --action-utc "$TRANSLATION_ACTION_UTC" \
  --action-evidence "$TRANSLATION_ACTION_EVIDENCE" \
  --output "$TRANSLATION_RUN_DIR/passive-stopped"
```

`TRANSLATION_ACTION_UTC` is the host-recorded time of the actual preceding UI action, including timezone. `TRANSLATION_ACTION_EVIDENCE` is its private screenshot, XML or receipt. The command must begin within 60 seconds of that action; its full observation window starts after initial verification, and both times remain recorded. Supply the exact installed benchmark APK SHA-256. The output directory must not exist; its parent must already exist. Android user 0 is the default, with another explicit `--android-user` supported only if that user is foreground.

The passive window lasts at least **30 seconds** and requires `stopped=true`, no process named exactly `app.mihon.benchmark` or one of its colon-suffixed processes, and no new generation dispatch. An already-dispatched response may finish later; raw ledger events preserve that distinction. Package UID, APK bytes and boot identity are checked independently. The benchmark test package is not mistaken for the app process.

After this command concludes, launch once through the actual benchmark launcher icon. Start a new command with phase **`after-user-launch`**, new action timestamp/evidence, new output directory, and `--previous-output "$TRANSLATION_RUN_DIR/passive-stopped"`. This window lasts at least **180 seconds**. It requires `stopped=false` and an initially present main PID different from the pre-Force-stop PID. Later PID changes or absence are recorded without being described as a crash. Navigate normally if needed, but do not tap Resume, Retry or Translate during this window.

If a manual Resume is still necessary, perform that actual per-job UI action after the launcher-only observation has concluded. Run phase **`after-manual-resume`**, with another new action timestamp/evidence and output directory, and `--previous-output` pointing to the launcher observation. Its window is **90 seconds**. The helper verifies the previous observation's manifest/hash, boot/run/package identity and ledger continuity; it does not perform the Resume. Keep each action's UI evidence separate.

`observation.json` retains `OBSERVED`, `FAIL` or `PREFLIGHT_REJECTED`, sample timestamps, process lists, dispatch counts and explicit limitations. `OBSERVED` means the host protocol completed; it is **not** translation-recovery acceptance. Package/process state is sampled every five seconds when system-query latency allows. Initial/final package, process, foreground service, WorkManager scheduler, notification, power, battery, thermal, network and bounded filtered logcat evidence is retained privately. Query failures are recorded as unavailable. Commands have three-second bounds; slow queries can extend the observed duration and that elapsed time is reported. No logs are cleared, and a bounded logcat snapshot does not establish absence of all events.

The full first saved result is retained in the copied barrier, with a canonical JSON hash. Its original-file hash is kept distinct from the normalized upload-payload hash. Wire image identity links the first dispatch to that saved page; completed-page resubmission, an unrelated image/review request, ledger replacement/truncation or an incomplete trailing event fails closed. The helper cannot read the minified app's private results. After the relevant observation windows end, use the separate durable **collect** phase to verify the exact first result/revision and original files against their checkpoint. Do not initialize target instrumentation during the passive interval or between action windows whose attribution must remain uncontaminated.

Ctrl-C/SIGTERM preserves a failure and the available evidence; it performs no target restoration or cleanup. A host SIGKILL cannot write a final verdict, so an unfinished manifest remains incomplete evidence. The operator retains responsibility for the existing explicit cleanup and authorized policy restoration. Never replace a failed observation with a later successful collection.

Host-only verification:

```sh
python3 -B -m unittest discover -s scripts/tests -p test_translator_worker_force_stop_observe.py
```
