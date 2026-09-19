"""Offline evidence validation: completion must never be inferred from duration alone."""

import datetime as dt
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "translator_unplugged_report.py"
SPEC = importlib.util.spec_from_file_location("unplugged_report", SCRIPT)
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


class UnpluggedReportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.capture = self.root / "mihon-unplugged-fixture"
        self.capture.mkdir()
        self.preflight = self.root / "preflight.json"
        self.preflight.write_text(json.dumps({
            "device_directory": "/data/local/tmp/" + self.capture.name,
            "subject": "app.mihon", "subject_apk_sha256": "a" * 64,
            "script_sha256": REPORT.PROTOCOL_SHA256,
            "automatic_translation": "off from recorded UI", "chapters_ahead": 0,
        }))
        self.put("session.txt", "\n".join([
            "WAITING_FOR_REAL_DISCONNECT", "UNPLUGGED_READER_START", "READER_GESTURES=12",
            "NATURAL_BACKGROUND_IDLE_START", "AWAITING_MANUAL_RECONNECT",
            "MANUAL_RECONNECT_OBSERVATION", "COMPLETE",
        ]) + "\n")
        self.put("boot-id.txt", "d1751c40-cebb-48f5-8a67-fced2c23ba7d")
        self.put("page-size.txt", "4096")
        self.put("fingerprint.txt", "fixture-device")
        self.put("natural-idle-start-uptime.txt", "1200")
        self.put("natural-idle-elapsed-seconds.txt", "605")
        for name, seconds in [
            ("natural-idle-start.txt", 200), ("awaiting-reconnect", 200),
            ("natural-idle-end.txt", 805), ("complete", 812),
        ]:
            self.put(name, self.stamp(seconds))
        self.put("relaunch.txt", "Starting: Intent { cmp=app.mihon/eu.kanade.tachiyomi.ui.main.MainActivity }\n")
        for label, seconds, powered in [
            ("attached", 0, True), ("unplugged-before", 10, False),
            ("reader-12", 150, False), ("unplugged-after", 190, False),
            ("reconnected-before-input", 806, True), ("resumed", 810, True),
        ]:
            self.sample(label, seconds, powered)

    def tearDown(self):
        self.temp.cleanup()

    def stamp(self, seconds):
        return (dt.datetime(2026, 9, 6, tzinfo=dt.timezone.utc) + dt.timedelta(seconds=seconds)).isoformat()

    def put(self, name, value):
        (self.capture / name).write_text(value)

    def sample(self, label, seconds, powered=False, pid=1234):
        self.put(label + "-time.txt", self.stamp(seconds))
        self.put(label + "-battery.txt", "\n".join(f"{flag} powered: {'true' if flag == 'AC' and powered else 'false'}" for flag in REPORT.POWERS) + "\nstatus: 5\nlevel: 100\ntemperature: 367\n")
        self.put(label + "-memory.txt", f"""Uptime: 50000 Realtime: {(1000 + seconds) * 1000}
** MEMINFO in pid {pid} [app.mihon] **
Pss Private Private SwapPss Rss Heap Heap Heap
  Native Heap   101 102 103 104 105 106 107 108
App Summary
 Java Heap: 201 211
 Native Heap: 202 212
 Graphics: 203 213
 TOTAL PSS: 606 TOTAL RSS: 636
""")
        self.put(label + "-thermal.txt", """Thermal Status: 0
Cached temperatures:
 Temperature{mValue=99.9, mType=0, mName=stale, mStatus=0}
Current temperatures from HAL:
 Temperature{mValue=40.2, mType=0, mName=CPU0, mStatus=0}
 Temperature{mValue=39.1, mType=1, mName=GPU0, mStatus=0}
 Temperature{mValue=NaN, mType=2, mName=battery, mStatus=0}
Current cooling devices from HAL:
""")
        self.put(label + "-idle.txt", "mState=ACTIVE mLightState=ACTIVE mScreenOn=true mCharging=true\n")
        self.put(label + "-pids.txt", str(pid))

    def analyze(self):
        return REPORT.analyze(self.capture, self.preflight)

    def test_complete_separates_power_stages_and_preserves_measurement_units(self):
        result = self.analyze()
        self.assertEqual(result["status"], "complete_bounded_observation")
        self.assertEqual(result["reader"]["unplugged_sample_count"], 3)
        self.assertTrue(result["reader"]["all_observed_reader_power_flags_false"])
        self.assertEqual(result["protocol"]["natural_idle_elapsed_seconds_recorded"], 605)
        self.assertIn("no second boot ID", result["protocol"]["same_boot_evidence"])
        row = result["snapshots"][1]
        self.assertEqual(row["memory"]["native_pss_kib"], 202)
        self.assertEqual(row["memory"]["native_allocated_kib"], 107)
        self.assertEqual(row["current_hal_max_celsius"]["cpu"], 40.2)
        self.assertIsNone(row["current_hal_max_celsius"]["battery"])
        self.assertEqual(result["snapshots"][-2]["power_state"], "external_power_observed")

    def test_missing_hal_does_not_promote_stale_temperatures(self):
        self.put("unplugged-before-thermal.txt", "Thermal Status: 0\nCached temperatures:\nTemperature{mValue=99.9, mType=0, mName=old, mStatus=0}\n")
        row = self.analyze()["snapshots"][1]
        self.assertFalse(row["current_hal_section_present"])
        self.assertIsNone(row["current_hal_max_celsius"]["cpu"])

    def remove_finish(self):
        for name in ("complete", "natural-idle-end.txt", "natural-idle-elapsed-seconds.txt", "relaunch.txt"):
            (self.capture / name).unlink()
        for label in ("reconnected-before-input", "resumed"):
            for component in REPORT.COMPONENTS:
                (self.capture / f"{label}-{component}.txt").unlink()
        self.put("session.txt", REPORT.read_text(self.capture / "session.txt").replace("MANUAL_RECONNECT_OBSERVATION\nCOMPLETE\n", ""))

    def test_awaiting_reconnect_is_not_completed(self):
        self.remove_finish()
        result = self.analyze()
        self.assertEqual(result["status"], "awaiting_reconnect")
        self.assertEqual(result["protocol"]["same_boot_evidence"], "not_established")

    def test_interrupted_finish_is_incomplete(self):
        self.remove_finish()
        self.put("session.txt", REPORT.read_text(self.capture / "session.txt") + "MANUAL_RECONNECT_OBSERVATION\n")
        self.assertEqual(self.analyze()["status"], "incomplete")

    def test_old_capture_is_never_retroactively_promoted(self):
        self.remove_finish()
        (self.capture / "awaiting-reconnect").unlink()
        p = json.loads(self.preflight.read_text())
        p["script_sha256"] = "b" * 64
        self.preflight.write_text(json.dumps(p))
        self.assertEqual(self.analyze()["status"], "incomplete")
        self.put("complete", self.stamp(900))
        self.assertEqual(self.analyze()["status"], "failed")

    def test_missing_power_flag_is_unknown_not_false(self):
        self.put("reader-12-battery.txt", "USB powered: false\n")
        result = self.analyze()
        self.assertEqual(result["status"], "failed")
        self.assertFalse(result["reader"]["all_observed_reader_power_flags_false"])

    def test_power_reconnect_during_reader_fails_unplugged_evidence(self):
        self.sample("reader-12", 150, True)
        self.assertEqual(self.analyze()["status"], "failed")

    def test_invalid_short_and_inconsistent_idle_durations_fail(self):
        for value in ("599", "-1", "NaN", "600.5", "999999"):
            with self.subTest(value=value):
                self.put("natural-idle-elapsed-seconds.txt", value)
                self.assertEqual(self.analyze()["status"], "failed")

    def test_missing_same_boot_evidence_fails_completion(self):
        (self.capture / "boot-id.txt").unlink()
        self.assertEqual(self.analyze()["status"], "failed")

    def test_marker_order_and_completion_time_are_validated(self):
        self.put("complete", self.stamp(100))
        self.assertEqual(self.analyze()["status"], "failed")
        self.put("complete", self.stamp(812))
        self.put("session.txt", REPORT.read_text(self.capture / "session.txt").replace("MANUAL_RECONNECT_OBSERVATION\nCOMPLETE", "COMPLETE\nMANUAL_RECONNECT_OBSERVATION"))
        self.assertEqual(self.analyze()["status"], "failed")

    def test_pid_change_is_observed_without_inventing_eviction(self):
        self.sample("reconnected-before-input", 806, True, pid=4321)
        self.sample("resumed", 810, True, pid=4321)
        result = self.analyze()
        self.assertEqual(result["status"], "complete_bounded_observation")
        self.assertTrue(result["process"]["pid_changed_between_snapshots"])
        self.assertEqual(result["process"]["cause_of_pid_change"], "not inferred")

    def test_missing_periodic_snapshot_and_explicit_stop_remain_failed(self):
        (self.capture / "reader-12-time.txt").unlink()
        self.assertEqual(self.analyze()["status"], "failed")
        self.remove_finish()
        self.put("session.txt", REPORT.read_text(self.capture / "session.txt") + "READER_NOT_FOREGROUND_STOPPING_RUN\n")
        self.assertEqual(self.analyze()["status"], "failed")

    def test_cli_private_output_exit_codes_hashes_and_input_protection(self):
        output = self.root / "result.json"
        args = [sys.executable, str(SCRIPT), "--directory", str(self.capture), "--preflight", str(self.preflight), "--output"]
        completed = subprocess.run(args + [str(output)], capture_output=True, text=True)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(output.stat().st_mode & 0o777, 0o600)
        result = json.loads(output.read_text())
        self.assertEqual(result["input_sha256"]["session.txt"], REPORT.sha256(self.capture / "session.txt"))
        original = self.preflight.read_bytes()
        refused = subprocess.run(args + [str(self.preflight)], capture_output=True, text=True)
        self.assertEqual(refused.returncode, 2)
        self.assertEqual(self.preflight.read_bytes(), original)
        self.remove_finish()
        awaiting = subprocess.run(args + [str(output)], capture_output=True, text=True)
        self.assertEqual(awaiting.returncode, 1)
        self.assertEqual(json.loads(output.read_text())["status"], "awaiting_reconnect")


if __name__ == "__main__":
    unittest.main()
