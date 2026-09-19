"""Exercise the real device-shell protocol with fake Android commands, without adb."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "translator_unplugged_capture.sh"


class UnpluggedProtocolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.run_dir = self.root / "mihon-unplugged-fixture"
        # Only relocate the Android-only evidence path; execute the protocol unchanged.
        self.script = self.root / "capture.sh"
        self.script.write_text(SCRIPT.read_text().replace("/data/local/tmp/", str(self.root) + "/"))
        self.events = self.root / "events"
        self.env = {**os.environ, "PATH": str(self.bin) + ":" + os.environ["PATH"],
                    "TASK_EVENTS": str(self.events), "TASK_BOOT": "same-boot", "TASK_UPTIME": "1000",
                    "TASK_DATE_COUNTER": str(self.root / "date-counter"), "TASK_READER": "present"}
        self.command("cat", 'case "$1" in /proc/sys/kernel/random/boot_id) echo "$TASK_BOOT";; *) /bin/cat "$@";; esac')
        self.command("awk", 'if [ "$2" = /proc/uptime ]; then echo "$TASK_UPTIME"; else /usr/bin/awk "$@"; fi')
        self.command("getprop", "echo test-fingerprint")
        self.command("getconf", "echo 4096")
        self.command("pidof", "echo 1234")
        self.command("sleep", 'echo "sleep $*" >> "$TASK_EVENTS"')
        self.command("date", 'if [ "$1" = +%s ]; then n=$(cat "$TASK_DATE_COUNTER" 2>/dev/null || echo 0); echo $((n + 1)) > "$TASK_DATE_COUNTER"; echo $((1000 + n * n * 100)); else echo 2026-09-06T00:00:00Z; fi')
        self.command("dumpsys", 'echo "dumpsys $*" >> "$TASK_EVENTS"; case "$*" in *battery*) echo "USB powered: false";; *activity*) if [ "$TASK_READER" = present ]; then echo "topResumedActivity= app.mihon/eu.kanade.tachiyomi.ui.reader.ReaderActivity"; fi;; *) echo fixture;; esac')
        for name in ("input", "am", "screencap"):
            self.command(name, f'echo "{name} $*" >> "$TASK_EVENTS"')

    def command(self, name, body):
        path = self.bin / name
        path.write_text("#!/bin/sh\n" + body + "\n")
        path.chmod(0o700)

    def invoke(self, stage="start", **overrides):
        return subprocess.run(["/bin/sh", str(self.script), str(self.run_dir), stage],
                              env={**self.env, **overrides}, capture_output=True, text=True, timeout=5)

    def tearDown(self):
        self.temp.cleanup()

    def test_start_exits_at_idle_checkpoint_without_sleeping_or_relaunching(self):
        self.assertEqual(self.invoke().returncode, 0)
        self.assertTrue((self.run_dir / "awaiting-reconnect").exists())
        self.assertFalse((self.run_dir / "complete").exists())
        events = self.events.read_text()
        self.assertIn("input keyevent 223", events)
        self.assertNotIn("sleep 600", events)
        self.assertNotIn("am start", events)
        self.assertEqual(self.invoke().returncode, 1)  # Existing evidence is never replaced.

    def test_finish_measures_suspend_inclusive_elapsed_and_snapshots_before_input(self):
        self.assertEqual(self.invoke().returncode, 0)
        self.events.write_text("")
        self.assertEqual(self.invoke("finish", TASK_UPTIME="1605").returncode, 0)
        self.assertEqual((self.run_dir / "natural-idle-elapsed-seconds.txt").read_text(), "605\n")
        self.assertTrue((self.run_dir / "complete").exists())
        events = self.events.read_text()
        self.assertLess(events.index("dumpsys -t 8 deviceidle"), events.index("input keyevent 224"))
        self.assertEqual(self.invoke("finish", TASK_UPTIME="1700").returncode, 1)

    def test_early_reconnect_or_reboot_cannot_be_marked_complete(self):
        self.assertEqual(self.invoke().returncode, 0)
        self.events.write_text("")
        self.assertEqual(self.invoke("finish", TASK_UPTIME="1599").returncode, 1)
        self.assertEqual(self.invoke("finish", TASK_UPTIME="1605", TASK_BOOT="new-boot").returncode, 1)
        self.assertFalse((self.run_dir / "complete").exists())
        self.assertEqual(self.events.read_text(), "")

    def test_old_incomplete_run_cannot_be_retroactively_finalized(self):
        self.run_dir.mkdir()
        (self.run_dir / "session.txt").write_text("NATURAL_BACKGROUND_IDLE_START\n")
        self.assertEqual(self.invoke("finish", TASK_UPTIME="1605").returncode, 1)
        self.assertFalse((self.run_dir / "complete").exists())

    def test_losing_reader_foreground_stops_all_remaining_input(self):
        self.assertEqual(self.invoke(TASK_READER="absent").returncode, 1)
        self.assertNotIn("input ", self.events.read_text())
        self.assertFalse((self.run_dir / "awaiting-reconnect").exists())


if __name__ == "__main__":
    unittest.main()
