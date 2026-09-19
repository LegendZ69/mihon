"""All commands and clock waits are simulated. These tests never execute adb."""

import copy
import datetime as dt
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location("force_observer", SCRIPTS / "translator_worker_force_stop_observe.py")
observer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(observer)
RUN = "11111111-2222-4333-8444-555555555555"
SERVER = "a" * 32
FIRST, SECOND = "b" * 64, "c" * 64
BOOT = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"


def barrier():
    row = {"stage": "awaiting_settings_force_stop", "pid": 123, "process_start_elapsed_ms": 12345,
           "results": [{"imageId": "first", "imageHash": FIRST, "width": 960, "height": 320,
                        "revision": 19, "regions": ["saved"]}],
           "jobs": [{"id": "worker-recovery-" + RUN, "state": "TRANSLATING"}],
           "work": [{"id": "work", "state": "RUNNING"}],
           "events": [{"stage": "generateContent", "message": "Request started"}] * 2}
    return {"run_id": RUN, "mode": "force-stop", "package": observer.PACKAGE,
            "cloud_forwarding": False, "status": "awaiting_settings_force_stop", "records": [row]}


def dispatches(hashes=(FIRST, SECOND)):
    return [{"event": "dispatch", "request_id": f"fixture-{SERVER}-{index}", "scenario": "worker-force-stop",
             "count_only": False, "is_review": False, "pages": [{"image_sha256": image_hash,
                "image_id_sha256": observer.digest(("first~0_0_960_320" if image_hash == FIRST else "second~0_0_960_320").encode())}]}
            for index, image_hash in enumerate(hashes)]


def ledger(events):
    return b"".join(json.dumps(event).encode() + b"\n" for event in events)


class Clock:
    def __init__(self):
        self.now = 100.0

    def monotonic(self):
        return self.now

    def monotonic_ns(self):
        return round(self.now * 1e9)

    def sleep(self, duration):
        self.now += duration


class Device:
    def __init__(self, args, clock, stopped=None, pid=None, fail_after=None, interrupt=False):
        self.args, self.clock = args, clock
        self.stopped = args.phase == "passive-stopped" if stopped is None else stopped
        self.pid = (None if self.stopped else 456) if pid is None else pid
        self.commands = []
        self.fail_after, self.interrupt = fail_after, interrupt

    def query(self, label, arguments, required=True, timeout=3):
        assert observer.read_only_command(arguments), arguments
        self.commands.append((label, tuple(arguments)))
        if self.interrupt and label.startswith("during-"):
            raise KeyboardInterrupt("simulated host interruption")
        if arguments == ["dumpsys", "package", observer.PACKAGE]:
            stopped = self.stopped if self.fail_after is None or self.clock.now < self.fail_after else not self.stopped
            return f"Packages:\n  User 0: installed=true hidden=false stopped={str(stopped).lower()} enabled=0\n"
        if arguments == ["ps", "-A", "-o", "UID,PID,NAME"]:
            return "UID PID NAME\n1000 9 system_server\n" + (
                f"10691 {self.pid} {observer.PACKAGE}\n" if self.pid is not None else "")
        if arguments[:2] == ["pm", "path"]:
            return "package:/data/app/~~test/app.mihon.benchmark-test/base.apk\n"
        if arguments[0] == "sha256sum":
            return self.args.benchmark_sha256 + "  " + arguments[1] + "\n"
        if arguments[:3] == ["pm", "list", "packages"]:
            return "package:app.mihon.benchmark uid:10691\npackage:app.mihon.benchmark.test uid:10692\n"
        if arguments == ["am", "get-current-user"]:
            return "0\n"
        if arguments == ["cat", "/proc/sys/kernel/random/boot_id"]:
            return BOOT + "\n"
        return "sample\n"


class ForceStopObserverTests(unittest.TestCase):
    def args(self, folder, phase="passive-stopped", output="observe"):
        source = folder / "ledger.jsonl"
        if not source.exists():
            source.write_bytes(ledger(dispatches()))
            source.chmod(0o600)
        saved = folder / "barrier.json"
        saved.write_text(json.dumps(barrier()))
        action = folder / "action.xml"
        action.write_text("<hierarchy />")
        now = dt.datetime.now(dt.timezone.utc)
        return SimpleNamespace(phase=phase, adb="NEVER_EXECUTE", serial="simulated", run_id=RUN,
            server_run_id=SERVER, ledger=source, barrier_report=saved, benchmark_sha256="d" * 64,
            policy_label="recorded-existing", android_user=0, action_time=now, action_utc=now.isoformat(),
            action_evidence=action, previous_output=None, output=folder / output)

    def run_fake(self, args, device=None, before_systems=None):
        clock = device.clock if device else Clock()
        device = device or Device(args, clock)
        run = observer.Observer(args, device)
        original_systems = run.systems
        if before_systems:
            def systems(label):
                if label == "before":
                    before_systems()
                original_systems(label)
            run.systems = systems
        with patch.object(observer.time, "monotonic", clock.monotonic), \
             patch.object(observer.time, "monotonic_ns", clock.monotonic_ns), \
             patch.object(observer.time, "sleep", clock.sleep), \
             patch.object(observer.Observer, "health"), \
             patch.object(observer.subprocess, "run", side_effect=AssertionError("No subprocess permitted")):
            run.run()
        return run, device, clock

    def test_mutating_commands_are_not_allowlisted(self):
        for command in (["am", "force-stop", observer.PACKAGE], ["am", "start", "-n", "app/Activity"],
                        ["am", "instrument", "-w", "test"], ["cmd", "jobscheduler", "run", observer.PACKAGE, "1"],
                        ["input", "keyevent", "KEYCODE_HOME"], ["logcat", "-c"], ["kill", "123"],
                        ["settings", "put", "global", "wifi_on", "1"], ["pm", "grant", observer.PACKAGE, "permission"]):
            self.assertFalse(observer.read_only_command(command), command)
        self.assertTrue(observer.read_only_command(["dumpsys", "package", observer.PACKAGE]))
        self.assertFalse(observer.read_only_command(["sha256sum", "/data/app/test/$(echo bad)/base.apk"]))

    def test_barrier_and_ledger_reject_wrong_scope_or_resubmitted_saved_page(self):
        observer.barrier_from_bytes(json.dumps(barrier()).encode(), RUN)
        for change in (lambda r: r.update(status="failed"), lambda r: r["records"][0].update(results=[]),
                       lambda r: r["records"][0].update(work=[{"state": "ENQUEUED"}])):
            wrong = barrier()
            change(wrong)
            with self.assertRaises(ValueError):
                observer.barrier_from_bytes(json.dumps(wrong).encode(), RUN)
        with self.assertRaises(ValueError):
            observer.dispatch_summary(dispatches((FIRST, SECOND, FIRST)), barrier()["records"][0]["results"][0])
        with self.assertRaises(ValueError):
            observer.ledger_dispatches(ledger(dispatches()), "e" * 32)
        wrong = dispatches()
        wrong[1]["is_review"] = True
        with self.assertRaises(ValueError):
            observer.ledger_dispatches(ledger(wrong), SERVER)

    def test_wire_payload_hash_is_distinct_from_original_file_hash(self):
        first = barrier()["records"][0]["results"][0]
        first["imageHash"] = "e" * 64  # Upload PNG normalization can change bytes without changing image identity.
        result = observer.dispatch_summary(dispatches(), first)
        self.assertEqual("e" * 64, result["first_page_original_sha256"])
        self.assertEqual(FIRST, result["first_page_payload_sha256"])

    def test_exact_package_user_uid_and_process_names(self):
        self.assertTrue(observer.package_stopped(" User 0: installed=true stopped=true\n User 10: installed=true stopped=false", 0))
        with self.assertRaises(ValueError):
            observer.package_stopped(" User 10: installed=true stopped=true", 0)
        self.assertEqual([], observer.benchmark_processes("UID PID NAME\n10692 99 app.mihon.benchmark.test\n", 10691))
        with self.assertRaises(ValueError):
            observer.benchmark_processes("UID PID NAME\n10692 99 app.mihon.benchmark:worker\n", 10691)

    def test_process_names_with_spaces_do_not_reject_an_unrelated_kernel_thread(self):
        # Minimal non-private row from the K90 snapshot that rejected the first preflight.
        kernel = "    0  1115 [irq/331-q6v5 wdog]\n"
        self.assertEqual([], observer.benchmark_processes("  UID   PID NAME\n" + kernel, 10691))
        processes = observer.benchmark_processes(
            "UID PID NAME\n" + kernel + "10691 456 app.mihon.benchmark\n"
            "10691 789 app.mihon.benchmark:worker\n10692 990 app.mihon.benchmark.test\n", 10691)
        self.assertEqual([
            {"uid": 10691, "pid": 456, "name": "app.mihon.benchmark"},
            {"uid": 10691, "pid": 789, "name": "app.mihon.benchmark:worker"},
        ], processes)

    def test_process_selection_matches_the_whole_name_not_an_unrelated_prefix(self):
        snapshot = ("UID PID NAME\n10691 11 app.mihon.benchmark.test\n"
                    "10691 12 app.mihon.benchmark-other\n10691 13 [app.mihon.benchmark worker]\n"
                    "10691 14 app.mihon\n10691 15 app.mihon.benchmark worker\n")
        self.assertEqual([], observer.benchmark_processes(snapshot, 10691))

    def test_target_process_uid_and_pid_must_be_numeric_and_exact(self):
        for name in ("app.mihon.benchmark", "app.mihon.benchmark:worker"):
            for uid, pid in (("10692", "456"), ("u0_a691", "456"), ("-10691", "456"),
                             ("10691", "0"), ("10691", "-1"), ("10691", "unknown"),
                             ("10691", "4.5")):
                with self.subTest(name=name, uid=uid, pid=pid), self.assertRaisesRegex(ValueError, "UID/PID"):
                    observer.benchmark_processes(f"UID PID NAME\n{uid} {pid} {name}\n", 10691)

    def test_incomplete_process_rows_and_unsupported_headers_fail_closed(self):
        for snapshot in ("", "PID UID NAME\n", "UID PID CMD\n", "UID PID NAME EXTRA\n",
                         "UID PID NAME\n10691\n", "UID PID NAME\n10691 456\n",
                         "UID PID NAME\n   \n", "UID PID NAME\n0 1115\n"):
            with self.subTest(snapshot=snapshot), self.assertRaises(ValueError):
                observer.benchmark_processes(snapshot, 10691)

    def test_package_state_ignores_a_later_user_subsection_with_no_inline_install_state(self):
        snapshot = ("    User 0: ceDataInode=1362268 installed=true hidden=false stopped=true enabled=0\n"
                    "      installReason=0\n"
                    "    User 0:\n"
                    "      [android,com.android.settings]:\n")
        self.assertTrue(observer.package_stopped(snapshot, 0))
        self.assertFalse(observer.package_stopped(snapshot.replace("stopped=true", "stopped=false"), 0))

    def test_package_state_never_reads_install_flags_from_the_next_line(self):
        for snapshot in ("    User 0:\n      installed=true stopped=true\n",
                         "    User 0:\n      installed=true stopped=false\n",
                         "    User 10: installed=true stopped=true\n    User 0:\n"):
            with self.subTest(snapshot=snapshot), self.assertRaises(ValueError):
                observer.package_stopped(snapshot, 0)

    def test_package_state_requires_one_well_formed_installed_status_line(self):
        valid = "    User 0: installed=true stopped=true\n"
        for snapshot in (valid + valid, valid + valid.replace("stopped=true", "stopped=false"),
                         valid.replace("installed=true", "installed=false"),
                         valid.replace("installed=true", "installed=true installed=false"),
                         valid.replace("installed=true", "installed=unknown"),
                         valid.replace("stopped=true", "stopped=true stopped=false"),
                         valid.replace("stopped=true", "stopped=true-invalid"),
                         valid.replace("stopped=true", "stopped=unknown")):
            with self.subTest(snapshot=snapshot), self.assertRaises(ValueError):
                observer.package_stopped(snapshot, 0)

    def test_passive_full_30_seconds_preserves_read_only_receipts(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory))
            run, device, clock = self.run_fake(args)
            self.assertEqual(130, clock.now)
            self.assertEqual("OBSERVED", run.record["verdict"])
            self.assertEqual(0, run.record["new_generation_dispatches"])
            self.assertTrue(all(row["stopped"] and not row["processes"] for row in run.record["samples"]))
            self.assertTrue((args.output / "manifest.json").exists())
            self.assertTrue(any(command[1][0] == "logcat" for command in device.commands))
            self.assertFalse(run.record["durable_result_read_during_window"])

    def test_wrong_initial_stopped_flag_rejected_and_failure_retained(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory))
            with self.assertRaises(ValueError):
                self.run_fake(args, Device(args, Clock(), stopped=False))
            result = json.loads((args.output / "observation.json").read_text())
            self.assertEqual("PREFLIGHT_REJECTED", result["verdict"])
            self.assertFalse(result["samples"][0]["stopped"])
            self.assertTrue((args.output / "after-ledger.jsonl").exists())

    def test_new_generation_during_passive_interval_is_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory))
            with self.assertRaises(ValueError):
                self.run_fake(args, before_systems=lambda: args.ledger.write_bytes(ledger(dispatches((FIRST, SECOND, SECOND)))))
            result = json.loads((args.output / "observation.json").read_text())
            self.assertEqual("FAIL", result["verdict"])
            self.assertEqual(1, result["new_generation_dispatches"])

    def test_midwindow_state_violation_is_retained_without_control(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory))
            device = Device(args, Clock(), fail_after=105)
            with self.assertRaises(ValueError):
                self.run_fake(args, device)
            result = json.loads((args.output / "observation.json").read_text())
            self.assertEqual("FAIL", result["verdict"])
            self.assertFalse(result["samples"][-1]["stopped"])
            self.assertFalse(result.get("window_completed", False))

    def test_interrupt_keeps_partial_evidence_and_performs_no_restoration(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory))
            device = Device(args, Clock(), interrupt=True)
            with self.assertRaises(KeyboardInterrupt):
                self.run_fake(args, device)
            result = json.loads((args.output / "observation.json").read_text())
            self.assertEqual("FAIL", result["verdict"])
            self.assertEqual("KeyboardInterrupt", result["error_class"])
            self.assertTrue(all(observer.read_only_command(command) for _, command in device.commands))

    def test_complete_chain_has_full_180_and_90_windows_without_implicit_resume(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stopped = self.args(root)
            self.run_fake(stopped)
            launched = self.args(root, "after-user-launch", "launch")
            launched.previous_output = stopped.output
            launch_run, _, launch_clock = self.run_fake(launched)
            self.assertEqual(280, launch_clock.now)
            self.assertEqual(0, launch_run.record["new_generation_dispatches"])
            resumed = self.args(root, "after-manual-resume", "resume")
            resumed.previous_output = launched.output
            resume_run, _, resume_clock = self.run_fake(resumed,
                before_systems=lambda: resumed.ledger.write_bytes(ledger(dispatches((FIRST, SECOND, SECOND)))))
            self.assertEqual(190, resume_clock.now)
            self.assertEqual(1, resume_run.record["new_generation_dispatches"])
            self.assertEqual("OBSERVED", resume_run.record["verdict"])
            self.assertIn("pending", resume_run.record["recovery_acceptance"])

    def test_active_phase_requires_verified_previous_window_and_new_pid(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = self.args(root, "after-user-launch")
            with self.assertRaises(ValueError):
                self.run_fake(args)
            stopped = self.args(root, output="stopped")
            self.run_fake(stopped)
            launched = self.args(root, "after-user-launch", "launch")
            launched.previous_output = stopped.output
            with self.assertRaises(ValueError):
                self.run_fake(launched, Device(launched, Clock(), pid=123))
            self.assertEqual("PREFLIGHT_REJECTED", json.loads((launched.output / "observation.json").read_text())["verdict"])

    def test_ledger_replacement_and_preexisting_output_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory))
            def replace():
                replacement = args.ledger.with_name("replacement")
                replacement.write_bytes(args.ledger.read_bytes())
                replacement.chmod(0o600)
                replacement.replace(args.ledger)
            with self.assertRaises(ValueError):
                self.run_fake(args, before_systems=replace)
            original = (args.output / "observation.json").read_bytes()
            with self.assertRaises(FileExistsError):
                self.run_fake(args)
            self.assertEqual(original, (args.output / "observation.json").read_bytes())

    def test_parser_requires_timezone_and_fixed_phase_durations(self):
        self.assertEqual({"passive-stopped": 30, "after-user-launch": 180, "after-manual-resume": 90}, observer.DURATIONS)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = self.args(root)
            values = [args.phase, "--serial", args.serial, "--run-id", RUN, "--server-run-id", SERVER,
                      "--ledger", str(args.ledger), "--barrier-report", str(args.barrier_report),
                      "--benchmark-sha256", args.benchmark_sha256, "--policy-label", args.policy_label,
                      "--action-utc", "2026-09-06T12:00:00", "--action-evidence", str(args.action_evidence),
                      "--output", str(args.output)]
            with self.assertRaises(ValueError):
                observer.parse_args(values)


if __name__ == "__main__":
    unittest.main()
