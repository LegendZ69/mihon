#!/system/bin/sh
# Device-side observation survives USB removal. Run only with the controlled reader open.
# Usage: sh translator_unplugged_capture.sh /data/local/tmp/mihon-unplugged-UNIQUE [start|finish]
# No cloud calls, app settings, battery simulation, forced idle, or process kills.
set -eu
task_dir="${1:-}"
task_stage="${2:-start}"
case "$task_stage" in start|finish) ;; *) echo 'Expected start or finish' >&2; exit 2 ;; esac
case "$task_dir" in
  /data/local/tmp/mihon-unplugged-*) ;;
  *) echo 'Expected a unique /data/local/tmp/mihon-unplugged-* directory' >&2; exit 2 ;;
esac
case "$task_dir" in *[!A-Za-z0-9_./-]*|*..*) exit 2 ;; esac
umask 077
package=app.mihon
stamp() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
boot_seconds() { awk '{print int($1)}' /proc/uptime; }
snapshot() {
  label="$1"
  stamp >"$task_dir/$label-time.txt"
  dumpsys -t 8 battery >"$task_dir/$label-battery.txt"
  dumpsys -t 8 meminfo "$package" >"$task_dir/$label-memory.txt"
  dumpsys -t 8 thermalservice >"$task_dir/$label-thermal.txt"
  dumpsys -t 8 deviceidle >"$task_dir/$label-idle.txt"
  pidof "$package" >"$task_dir/$label-pids.txt" || true
}
if [ "$task_stage" = finish ]; then
  # Never retrofit a passing end onto an older/incomplete protocol or a different boot.
  [ -f "$task_dir/awaiting-reconnect" ] || { echo 'No awaiting-reconnect checkpoint' >&2; exit 1; }
  [ ! -f "$task_dir/complete" ] || { echo 'Run already completed' >&2; exit 1; }
  [ "$(cat "$task_dir/boot-id.txt")" = "$(cat /proc/sys/kernel/random/boot_id)" ] || {
    echo 'Device rebooted during observation; retain incomplete evidence' >&2; exit 1;
  }
  idle_start=$(cat "$task_dir/natural-idle-start-uptime.txt")
  case "$idle_start" in ''|*[!0-9]*) echo 'Invalid idle checkpoint' >&2; exit 1 ;; esac
  idle_elapsed=$(( $(boot_seconds) - idle_start ))
  [ "$idle_elapsed" -ge 600 ] || { echo 'Resting interval is shorter than 600 seconds' >&2; exit 1; }
  mkdir "$task_dir/.finishing" || exit 1
  trap 'rmdir "$task_dir/.finishing"' EXIT
  [ ! -f "$task_dir/complete" ] || exit 1
  exec >>"$task_dir/session.txt" 2>&1
  echo MANUAL_RECONNECT_OBSERVATION
  stamp >"$task_dir/natural-idle-end.txt"
  echo "$idle_elapsed" >"$task_dir/natural-idle-elapsed-seconds.txt"
  # USB reconnection itself may already wake the device and change its idle state.
  # Capture that state before explicitly waking/launching; do not infer pre-reconnect Doze.
  snapshot reconnected-before-input
  input keyevent 224
  am start -n app.mihon/eu.kanade.tachiyomi.ui.main.MainActivity >"$task_dir/relaunch.txt"
  sleep 3
  snapshot resumed
  stamp >"$task_dir/complete"
  echo COMPLETE
  stamp
  exit 0
fi
mkdir "$task_dir"
exec >"$task_dir/session.txt" 2>&1
echo WAITING_FOR_REAL_DISCONNECT
stamp
cat /proc/sys/kernel/random/boot_id >"$task_dir/boot-id.txt"
getprop ro.build.fingerprint >"$task_dir/fingerprint.txt"
getconf PAGE_SIZE >"$task_dir/page-size.txt"
snapshot attached
attempt=0
while dumpsys -t 8 battery | grep -E '(AC|USB|Wireless|Dock) powered: true' >/dev/null; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 60 ]; then echo DISCONNECT_TIMEOUT; exit 1; fi
  sleep 5
done
echo UNPLUGGED_READER_START
snapshot unplugged-before
# The host must verify the selected controlled series and automatic translation OFF.
# Stop input if the reader leaves the foreground; never launch unrelated content.
start=$(date +%s)
iteration=0
while [ $(( $(date +%s) - start )) -lt 180 ]; do
  if ! dumpsys -t 8 activity activities | grep -E '(topResumedActivity=|ResumedActivity:).*app.mihon/.*ReaderActivity' >/dev/null; then
    echo READER_NOT_FOREGROUND_STOPPING_RUN
    exit 1
  fi
  if [ $((iteration % 2)) -eq 0 ]; then
    input swipe 600 1600 600 1100 500
  else
    input swipe 600 1100 600 1600 500
  fi
  iteration=$((iteration + 1))
  if [ $((iteration % 12)) -eq 0 ]; then snapshot "reader-$iteration"; fi
  sleep 1
done
echo "READER_GESTURES=$iteration"
snapshot unplugged-after
screencap -p "$task_dir/unplugged-reader-after.png"
if ! dumpsys -t 8 activity activities | grep -E '(topResumedActivity=|ResumedActivity:).*app.mihon/.*ReaderActivity' >/dev/null; then
  echo READER_NOT_FOREGROUND_STOPPING_RUN
  exit 1
fi
echo NATURAL_BACKGROUND_IDLE_START
input keyevent 3
input keyevent 223
stamp >"$task_dir/natural-idle-start.txt"
boot_seconds >"$task_dir/natural-idle-start-uptime.txt"
stamp >"$task_dir/awaiting-reconnect"
echo AWAITING_MANUAL_RECONNECT
# Exit instead of leaving a sleep timer whose progress can stop during suspend.
# The host reconnects after the resting interval and invokes finish explicitly.
