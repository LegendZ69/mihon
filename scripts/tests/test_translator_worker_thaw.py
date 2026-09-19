"""Host protocol tests use simulated commands/clocks only; never execute adb."""

import copy
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "translator_worker_thaw.py"
SPEC = importlib.util.spec_from_file_location("translator_worker_thaw", SCRIPT)
thaw = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(thaw)
RUN = "11111111-2222-4333-8444-555555555555"
SERVER = "a" * 32


def dispatches(retry=False):
    return [{"event": "dispatch", "scenario": "worker-screen-off-thaw", "count_only": False,
             "request_id": f"fixture-{SERVER}-{index}", "pages": [{"image_sha256": image_hash}]}
            for index, image_hash in enumerate(["first", "second"] + (["second"] if retry else []))]


def barrier_report():
    record = {"stage": "awaiting_screen_off", "pid": 100, "process_start_elapsed_ms": 50,
              "results": [{"imageId": "first", "revision": 1, "regions": ["original geometry"]}],
              "events": [{"stage": "generateContent", "message": "Request started"}] * 2,
              "work": [{"state": "RUNNING"}], "jobs": [{"state": "TRANSLATING", "completedImages": 1}]}
    return {"run_id": RUN, "mode": "screen-off-thaw", "package": thaw.PACKAGE,
            "cloud_forwarding": False, "status": "awaiting_screen_off", "records": [record]}


def completion_report():
    report = barrier_report()
    end = copy.deepcopy(report["records"][0])
    end.update(stage="automatic_completion_assertions", results=end["results"] + [{"imageId": "second"}],
               jobs=[{"state": "COMPLETED", "completedImages": 2}], work=[{"state": "SUCCEEDED"}])
    report["records"] += [{"stage": "observed_screen_off", "elapsed_realtime_ms": 100000, "interactive": False},
                          {"stage": "observed_screen_on", "elapsed_realtime_ms": 160100, "interactive": True}, end]
    report.update(status="start_observation_passed_cleanup_required", automatic_after_screen_on="completed",
                  recovery_trigger="screen_on_only_no_launch_resume_or_queue_write", recovery_elapsed_ms=10000)
    return report


class Clock:
    now = 100.0

    def monotonic(self):
        return self.now

    def monotonic_ns(self):
        return round(self.now * 1e9)

    def sleep(self, seconds):
        self.now += seconds


class Process:
    def __init__(self, clock, finish=float("inf")):
        self.clock, self.finish, self.pid, self.returncode = clock, finish, 123, None

    def poll(self):
        if self.clock.now >= self.finish:
            self.returncode = 0
        return self.returncode

    def terminate(self):
        self.returncode = -15

    def kill(self):
        raise AssertionError("No kill needed in a passing simulated observer")

    def wait(self, timeout):
        return self.returncode


class SimulatedRecorder(thaw.Recorder):
    def __init__(self, args, clock, bad_barrier=False, interrupt=False, early_on=False):
        super().__init__(args)
        self.clock, self.commands = clock, []
        self.bad_barrier, self.interrupt = bad_barrier, interrupt
        self.early_on = early_on

    def installed(self):
        self.commands.append(("verify_installed",))

    def shell(self, *arguments, timeout=15):
        self.commands.append(arguments)
        if arguments[:3] == ("pm", "list", "packages"):
            return "package:app.mihon.benchmark uid:10691\n"
        return ""

    def capture(self, name, *arguments, required=False, timeout=15):
        self.commands.append(("capture", name))
        if name == "immediate-pre-wake-clock":
            return f"{self.clock.now:.2f} 0.00\n"
        return "mWakefulness=Asleep" if self.slept and not self.woke else "mWakefulness=Awake"

    def sample(self, label, full=False, timeout=15):
        if self.interrupt and label == "off-midpoint":
            raise KeyboardInterrupt("simulated host interruption")
        return self.capture(label, "sample")

    def start_process(self, name, remote_args):
        self.commands.append(tuple(remote_args))
        (self.output / name).write_text("OK (1 test)\n")
        return Process(self.clock, self.clock.now + 70 if name == "start-instrumentation.txt" else float("inf"))

    def latest_report(self, filename):
        if self.clock.now >= 170:
            return completion_report()
        report = barrier_report()
        if self.slept:
            report["records"].append({"stage": "observed_screen_off", "elapsed_realtime_ms": 100000, "interactive": False})
        if self.woke or (self.early_on and self.clock.now >= 120.38):
            report["status"] = "observed_screen_on"
            report["records"].append({"stage": "observed_screen_on", "elapsed_realtime_ms": 160100, "interactive": True})
        if self.bad_barrier:
            report["records"][0]["results"] = []
        return report

    def dispatches(self):
        return dispatches()


class Health:
    url = "http://127.0.0.1:8765/health"

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def read(self, size):
        return json.dumps({"fixture": True, "cloud_forwarding": False, "run_id": SERVER,
                           "scenarios": ["worker-screen-off-thaw"]}).encode()


class ThawProtocolTests(unittest.TestCase):
    def test_barrier_requires_saved_page_actual_worker_and_two_wire_dispatches(self):
        thaw.verify_barrier(barrier_report(), dispatches(), RUN)
        for field, value in [("results", []), ("work", [{"state": "ENQUEUED"}]), ("events", [])]:
            report = barrier_report()
            report["records"][0][field] = value
            with self.assertRaises(ValueError):
                thaw.verify_barrier(report, dispatches(), RUN)
        with self.assertRaises(ValueError):
            thaw.verify_barrier(barrier_report(), dispatches(True), RUN)

    def test_completion_rejects_changed_page_process_or_duplicate_completed_dispatch(self):
        for mutation in (lambda r: r["records"][-1]["results"][0].update(revision=2),
                         lambda r: r["records"][-1].update(pid=101),
                         lambda r: r.update(recovery_trigger="manual_resume")):
            report = completion_report()
            mutation(report)
            with self.assertRaises(ValueError):
                thaw.reconcile_completion(report, barrier_report()["records"][0], dispatches())
        wrong = dispatches(True)
        wrong[-1]["pages"][0]["image_sha256"] = "first"
        with self.assertRaises(ValueError):
            thaw.reconcile_completion(completion_report(), barrier_report()["records"][0], wrong)

    def test_ledger_rejects_other_sessions_review_and_unrelated_jobs(self):
        for change in ({"request_id": "fixture-wrong-1"}, {"scenario": "other"}, {"is_review": True}):
            event = dispatches()[0]
            event.update(change)
            with self.assertRaises(ValueError):
                thaw.generation_dispatches(json.dumps(event).encode() + b"\n", SERVER)
        encoded = b"".join(json.dumps(event).encode() + b"\n" for event in dispatches())
        self.assertEqual(len(thaw.generation_dispatches(encoded + b'{"incomplete":', SERVER)), 2)

    def simulate(self, directory, bad_barrier=False, interrupt=False, early_on=False):
        ledger = directory / "ledger.jsonl"
        ledger.touch(mode=0o600)
        args = SimpleNamespace(phase="observe", adb="NEVER_EXECUTE_ADB", serial="selected-device-serial",
            run_id=RUN, policy_label="existing-policy", output=directory / "new-run", server_run_id=SERVER,
            ledger=ledger, benchmark_sha256="a" * 64, test_sha256="b" * 64)
        clock = Clock()
        recorder = SimulatedRecorder(args, clock, bad_barrier, interrupt, early_on)
        def run(command, **kwargs):
            self.assertEqual(command[-2:], ["reverse", "--list"])
            return SimpleNamespace(stdout=b"device tcp:8765 tcp:8765\n")
        with patch.object(thaw.time, "monotonic", clock.monotonic), patch.object(thaw.time, "monotonic_ns", clock.monotonic_ns), \
                patch.object(thaw.time, "sleep", clock.sleep), patch.object(thaw.subprocess, "run", run), \
                patch.object(thaw.urllib.request, "build_opener", return_value=SimpleNamespace(open=lambda *a, **kw: Health())), \
                patch("builtins.print"):
            try:
                recorder.observe()
            except (ValueError, KeyboardInterrupt):
                if not (bad_barrier or interrupt or early_on):
                    raise
        return recorder, json.loads((args.output / "observation.json").read_text())

    def test_observe_only_sends_home_sleep_wake_and_preserves_verdict_before_collection(self):
        with tempfile.TemporaryDirectory() as temporary:
            recorder, result = self.simulate(Path(temporary))
            self.assertEqual([command for command in recorder.commands if command[0] == "input"],
                             [("input", "keyevent", "KEYCODE_HOME"), ("input", "keyevent", "KEYCODE_SLEEP"),
                              ("input", "keyevent", "KEYCODE_WAKEUP")])
            self.assertEqual(result["verdict"], "PASS")
            self.assertTrue(result["start_concluded"] and result["cleanup_required"])
            self.assertFalse(result["instrumentation_left_running"])
            self.assertEqual(sum(command[0] == "am" for command in recorder.commands), 1)
            self.assertTrue(all("resume" not in str(command).lower() for command in recorder.commands))
            wake = next(event for event in recorder.events if event["action"] == "wake_only")
            self.assertGreaterEqual(wake["off_seconds"], 60)
            self.assertLess(wake["off_seconds"], 60.2)
            self.assertEqual(result["reconciliation"]["screen_off_interval"]["app_noninteractive_interval_ms"], 60100)

    def test_known_20380ms_early_wake_rejects_raw_instrumentation_pass(self):
        report = completion_report()
        report["records"][1]["elapsed_realtime_ms"] = 2716071304
        report["records"][2]["elapsed_realtime_ms"] = 2716091684
        with self.assertRaisesRegex(thaw.InvalidScreenOffInterval, "20380 ms"):
            thaw.verify_screen_off_interval(report, None)
        self.assertEqual(report["status"], "start_observation_passed_cleanup_required")

    def test_sixty_second_app_interval_still_rejects_on_before_planned_wake(self):
        report = completion_report()
        with self.assertRaisesRegex(thaw.InvalidScreenOffInterval, "before the host"):
            thaw.verify_screen_off_interval(report, {"pre_wake_elapsed_ms": 165000})
        with self.assertRaisesRegex(thaw.InvalidScreenOffInterval, "Missing device clock"):
            thaw.verify_screen_off_interval(report, None)
        self.assertEqual(thaw.elapsed_ms_from_uptime("2716131.00 1234.50\n"), 2716131000)

    def test_early_app_screen_on_keeps_overall_invalid_and_never_collects(self):
        with tempfile.TemporaryDirectory() as temporary:
            recorder, result = self.simulate(Path(temporary), early_on=True)
            self.assertEqual(result["verdict"], "FAIL")
            self.assertEqual(result["protocol_verdict"], "INVALID")
            self.assertTrue(result["instrumentation_left_running"])
            self.assertEqual(sum(command[0] == "am" for command in recorder.commands), 1)
            self.assertTrue((recorder.output / "start-report.json").is_file())

    def test_invalid_barrier_sends_no_input_and_leaves_target_untouched(self):
        with tempfile.TemporaryDirectory() as temporary:
            recorder, result = self.simulate(Path(temporary), bad_barrier=True)
            self.assertFalse(any(command[0] == "input" for command in recorder.commands))
            self.assertEqual(result["verdict"], "FAIL")
            self.assertTrue(result["instrumentation_left_running"])

    def test_host_interruption_wakes_display_without_cancelling_work_or_collecting(self):
        with tempfile.TemporaryDirectory() as temporary:
            recorder, result = self.simulate(Path(temporary), interrupt=True)
            self.assertEqual([command[-1] for command in recorder.commands if command[0] == "input"],
                             ["KEYCODE_HOME", "KEYCODE_SLEEP", "KEYCODE_WAKEUP"])
            self.assertEqual(result["verdict"], "FAIL")
            self.assertTrue(result["instrumentation_left_running"] and result["cleanup_required"])

    def test_collection_refuses_to_initialize_target_before_start_concludes(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            args = SimpleNamespace(phase="collect", adb="NEVER_EXECUTE_ADB", serial="device", run_id=RUN,
                                   policy_label="existing-policy", server_run_id=SERVER, output=directory)
            (directory / "observation.json").write_text(json.dumps({"serial": "device", "run_id": RUN,
                "policy_label_host_attested": "existing-policy", "server_run_id": SERVER,
                "start_concluded": False, "verdict": "FAIL"}))
            with patch.object(thaw.subprocess, "run", side_effect=AssertionError("ADB must not execute")), \
                    patch.object(thaw.subprocess, "Popen", side_effect=AssertionError("ADB must not execute")):
                with self.assertRaisesRegex(ValueError, "disturb recovery"):
                    thaw.Recorder(args).collect()

    def test_installed_hash_mismatch_rejects_before_instrumentation_or_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = SimpleNamespace(phase="observe", adb="NEVER_EXECUTE_ADB", serial="device", output=Path(temporary),
                                   benchmark_sha256="a" * 64, test_sha256="b" * 64)
            recorder = thaw.Recorder(args)
            calls = []
            def shell(*arguments, **kwargs):
                calls.append(arguments)
                if arguments[:2] == ("pm", "path"):
                    return "package:/data/app/~~valid=/pkg-123=/base.apk\n"
                if arguments[0] == "sha256sum":
                    return "c" * 64 + "  /data/app/valid/pkg-123/base.apk\n"
                raise AssertionError("Unexpected command")
            with patch.object(recorder, "shell", shell), \
                    patch.object(thaw.subprocess, "Popen", side_effect=AssertionError("ADB must not execute")):
                with self.assertRaisesRegex(ValueError, "hash differs"):
                    recorder.installed()
            self.assertEqual([arguments[0] for arguments in calls], ["pm", "sha256sum"])


if __name__ == "__main__":
    unittest.main()
