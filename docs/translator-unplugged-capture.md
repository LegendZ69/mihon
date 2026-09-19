# Unplugged observation without wireless ADB

`scripts/translator_unplugged_capture.sh` runs on the phone after USB removal. It is a bounded validation helper for the K90's 1200 × 2608 display and the installed `app.mihon` package. It records real BatteryService power state; it never simulates unplugging, changes battery policies, or forces Doze.

Before starting, open the controlled validation series in its reader, choose a middle page, hide reader controls, and verify automatic translation is off. The helper moves through cached content with alternating short swipes. It is not suitable for arbitrary applications, display sizes, or an unattended provider queue. Its foreground check stops gestures if `ReaderActivity` is no longer resumed; the host must verify the selected series and settings separately.

Use a unique run directory. The script refuses to overwrite an existing directory and applies `umask 077`. With the explicit handset serial:

```sh
adb -s SERIAL push scripts/translator_unplugged_capture.sh /data/local/tmp/mihon-unplugged-capture.sh
adb -s SERIAL shell 'nohup setsid sh /data/local/tmp/mihon-unplugged-capture.sh /data/local/tmp/mihon-unplugged-UNIQUE > /data/local/tmp/mihon-unplugged-launch.txt 2>&1 < /dev/null &'
adb -s SERIAL shell cat /data/local/tmp/mihon-unplugged-UNIQUE/session.txt
```

After `WAITING_FOR_REAL_DISCONNECT`, remove external power within five minutes and leave the phone resting untouched. The helper waits until all reported AC, USB, wireless and dock power inputs are false. It then observes three minutes of reader gestures with sampled memory/thermal state. That stage includes measurement overhead and is distinct from a Perfetto frame trace.

The next stage sends Home, turns off the display, writes `AWAITING_MANUAL_RECONNECT`, and exits. There is no sleeping recorder, periodic inspection, alarm, or wake lock during the resting interval. Leave the phone untouched for at least ten minutes after that stage (approximately fourteen minutes after unplugging). The host records the intended interval and any observed disturbances.

After reconnecting, invoke `finish` explicitly. It requires the same boot identity and at least 600 seconds since the idle checkpoint according to `/proc/uptime`, which includes time spent suspended. Wall-clock timestamps are retained separately. It refuses old incomplete runs lacking this protocol's checkpoint, early completion, a reboot, concurrent completion, or a second completed run. [Linux uptime documentation](https://man7.org/linux/man-pages/man5/proc_uptime.5.html), verified 2026-09-06.

This is a **natural background/screen-off observation**, not proof that the device entered deep idle or that HyperOS evicted the process. Reconnecting USB can itself wake the phone and change its idle state before the finalizer runs. The finalizer labels that snapshot `reconnected-before-input`, then explicitly wakes the display, launches Mihon and records the resumed state. Do not describe that snapshot as the state immediately before reconnection. User movement, power reconnection, notifications or other activity must be reported. A preserved PID supports process retention; it does not prove recovery of an active provider queue.

Reconnect USB after approximately fourteen minutes, finalize, then collect the task-owned directory:

```sh
adb -s SERIAL shell sh /data/local/tmp/mihon-unplugged-capture.sh /data/local/tmp/mihon-unplugged-UNIQUE finish
adb -s SERIAL pull /data/local/tmp/mihon-unplugged-UNIQUE /PRIVATE/EVIDENCE/
```

Require the final `COMPLETE` marker, timestamps, real unplugged BatteryService snapshots, the selected content/settings preflight, and the exact installed APK hash. A missing marker or interrupted shell remains incomplete evidence. Preserve the script's hash with the run. Read-only captures are mixed with explicit input actions; do not describe this helper as wholly read-only. The helper changes no app preferences but leaves Mihon foreground and the display awake after completion.

Wireless ADB is an alternative only when reachable and explicitly scoped to the task. If it is temporarily enabled, restore its original state after testing. A network failure is not evidence of an application recovery failure.

The earlier 2026-09-06 `mihon-unplugged-20260906T0306` run used a single `sleep 600`. It captured 113 reader gestures over approximately three minutes while all sampled external-power flags were false, but its sleep remained pending after reconnection. Only the task-owned recorder/sleep processes were terminated; Mihon's process survived. Its missing final checkpoint remains an incomplete natural-idle result and cannot be upgraded by this new finalizer. Device-suspend timing is a plausible explanation for the delayed sleep, not an independently established cause from that run.

## Host reconciliation

Run the host-only analyzer after pulling the directory without renaming its device basename:

```sh
python3 scripts/translator_unplugged_report.py \
  --directory build/translator/validation/handset/mihon-unplugged-UNIQUE \
  --preflight /PRIVATE/EVIDENCE/preflight.json \
  --output /PRIVATE/EVIDENCE/unplugged-report.json
```

The preflight identifies the device directory, subject package/APK hash, script hash, selected content and verified settings. Output must be outside the capture directory. Exit 0 means a complete, consistent bounded observation; 1 means awaiting/incomplete and 2 means failed. None means a performance, meaning, Doze or eviction pass. The report separates attached, sampled-unplugged, reconnected-before-input and resumed states, preserves unavailable measurements, and hashes input evidence. Thirteen analyzer tests and five device-shell protocol tests pass; the earlier incomplete recording still returns incomplete.
