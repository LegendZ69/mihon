"""Fake subprocess/device tests for capture ordering, bounded gates and private evidence."""
import contextlib
import copy
import importlib.util
import io
import json
import os
import shlex
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "android-ui-validation/capture_frame_pairs.py"
spec = importlib.util.spec_from_file_location("frame_capture", SCRIPT)
controller = importlib.util.module_from_spec(spec)
spec.loader.exec_module(controller)
spec = importlib.util.spec_from_file_location("frame_oracle_fixture", Path(__file__).with_name("test_translator_frame_pairs.py"))
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
RUN = "10000000-0000-0000-0000-000000000000"
TRACE = b"simulated trace, not a valid performance measurement"


def make_report(pairs=1):
    report = fixtures.report_fixture(pairs)
    report["actions"] = [{"details": {"screenshot": f"{i:02d}-frame_test.png"}} for i in range(2 + 4 * pairs)]
    for window in report["frame_windows"]:
        ordinal = window["ordinal"]
        window["gate_file"] = f"/data/user/0/{controller.HARNESS}/files/reader-validation/{RUN}/frame-{ordinal}.go"
        window["gate_nonce"] = f"20000000-0000-0000-0000-{ordinal:012d}"
    return report


def ready_of(window):
    result = copy.deepcopy(window)
    result["status"] = "awaiting_host_trace"
    return result


PERFETTO_HELP = (Path(__file__).parent / "fixtures/perfetto-k90-help.txt").read_bytes()
INOTIFY_HELP = b'usage: inotifyd PROG FILE[:MASK] ...\nPROG is "-" events are sent to stdout. w closed (writable)'


def stat_receipt(pid, start=100, state="S"):
    # state is field 3; starttime is field 22, 19 fields later.
    return f"{pid} (task with spaces) {state} ".encode() + b"0 " * 18 + f"{start} 0\n".encode()


class Process:
    def __init__(self, fake, kind, output=b""):
        self.fake, self.kind, self.returncode = fake, kind, None
        self.stdin, self.stdout = io.BytesIO(), io.BytesIO(output)

    def poll(self):
        return self.returncode

    def wait(self, timeout=None):
        self.returncode = 0
        self.fake.calls.append(("wait", self.kind))
        return self.returncode


class FakeDevice:
    def __init__(self, report=None, receipt=b"4444\n", pull_failure=False, capture_exit=0, timeout=False,
                 no_close=False, wrong_close=False, no_watch=False, bad_identity=False, no_perfetto=False,
                 no_inotify=False, reuse_recorder=False, watcher_reuse=False, denied_stat=False, help_exit=0, help_output=None, eof_stall=False, stage_timeout=False, bad_staged_nonce=False):
        self.report = report or make_report()
        self.receipt, self.pull_failure, self.capture_exit, self.timeout = receipt, pull_failure, capture_exit, timeout
        self.no_close, self.wrong_close, self.no_watch, self.bad_identity = no_close, wrong_close, no_watch, bad_identity
        self.no_perfetto, self.no_inotify = no_perfetto, no_inotify
        self.help_exit, self.help_output = help_exit, help_output
        self.eof_stall, self.stage_timeout, self.bad_staged_nonce = eof_stall, stage_timeout, bad_staged_nonce
        self.pending_nonce = None
        self.reuse_recorder, self.watcher_reuse, self.denied_stat = reuse_recorder, watcher_reuse, denied_stat
        self.calls, self.processes = [], []
        self.corrupt_ready = None
        self.corrupt_final = False
        self.clock = 0
        self.armed = False
        self.remote = None
        self.writer_out = None
        self.finished = False

    def sleep(self, seconds):
        self.clock += max(1, seconds) if self.no_watch else seconds

    def popen(self, command, **kwargs):
        assert command[:3] == ["fake-adb", "-s", "explicit-device"]
        if command[3:7] == ["shell", "am", "instrument", "-w"]:
            self.calls.append(("instrumentation",))
            lines = []
            for window in self.report["frame_windows"]:
                ready = ready_of(window)
                if self.corrupt_ready:
                    ready = self.corrupt_ready(ready)
                lines += [f"INSTRUMENTATION_STATUS: frame_window_ready_json={json.dumps(ready)}\n",
                          f"INSTRUMENTATION_STATUS: frame_window_complete_json={json.dumps(window)}\n"]
            process = Process(self, "instrumentation", "".join(lines).encode())
        elif len(command) == 5 and command[3] == "shell" and "exec inotifyd - " in command[4]:
            self.writer_out = kwargs["stdout"]
            self.writer_out.write(b"5555\n")
            self.writer_out.flush()
            process = Process(self, "writer")
        else:
            raise AssertionError("Unexpected subprocess: " + repr(command))
        self.processes.append(process)
        return process

    def run(self, command, **kwargs):
        assert command[:3] == ["fake-adb", "-s", "explicit-device"]
        args = command[3:]
        self.calls.append(("call", args))
        code, out = 0, b""
        if args == ["get-state"]:
            out = b"device\n"
        elif args == ["exec-out", "cat", "/proc/sys/kernel/random/boot_id"]:
            out = RUN.encode()
        elif args[:2] == ["shell", "pidof"]:
            out = b"1234\n"
        elif args[:2] == ["shell", "dumpsys"]:
            out = b"test-only discrete device snapshot\n"
        elif args == ["shell", "perfetto --help 2>&1"]:
            code = self.help_exit
            out = b"old unsupported options only" if self.no_perfetto else PERFETTO_HELP
            if self.help_output is not None:
                out = self.help_output
        elif args == ["shell", "inotifyd --help 2>&1"]:
            out = b"unsupported" if self.no_inotify else INOTIFY_HELP
        elif args[:2] == ["shell", "test"]:
            assert args[2:4] == ["!", "-e"]
        elif args[:3] == ["shell", "perfetto", "--background-wait"]:
            self.calls.append(("start",))
            self.remote = args[-1]
            self.armed = False
            self.finished = False
            self.record_start = self.clock
            code, out = self.capture_exit, self.receipt
            assert "duration_ms: 45000" in kwargs["input"].decode()
        elif args[:4] == ["shell", "stat", "-c", "%i"]:
            out = b"123456\n"
        elif args[0] == "shell" and args[1].startswith("if [ ! -d /proc/"):
            pid = int(args[1].split("/proc/")[1].split(" ")[0])
            if pid == 4444:
                if self.armed:
                    # Completion receipt arrives from the fake framework immediately; advance to recording end.
                    self.clock = max(self.clock, self.record_start + 45)
                    if not self.finished:
                        self.finished = True
                        if not self.no_close:
                            name = self.remote + ".wrong" if self.wrong_close else self.remote
                            self.writer_out.write(f"w\t{name}\n".encode())
                            self.writer_out.flush()
                    if self.denied_stat:
                        code = 1
                    elif self.timeout:
                        out = stat_receipt(pid)
                    elif self.reuse_recorder:
                        out = stat_receipt(pid, start=999)
                    else:
                        out = b"MIHON_PROCESS_GONE\n"
                    if not code and not self.timeout:
                        self.calls.append(("recorder_gone",))
                else:
                    out = stat_receipt(pid)
            elif pid == 5555:
                out = stat_receipt(pid, start=999 if self.finished and self.watcher_reuse else 100)
            else:
                raise AssertionError(args)
        elif args[:2] == ["exec-out", "cat"] and args[2].endswith("/cmdline"):
            if args[2] == "/proc/4444/cmdline":
                path = "/not/owned" if self.bad_identity else self.remote
                out = b"perfetto\0--background-wait\0-o\0" + path.encode() + b"\0"
            elif args[2] == "/proc/5555/cmdline":
                # Actual Toybox splits FILE:MASK in argv in place.
                out = b"inotifyd\0-\0" + self.remote.encode() + b"\0w\0"
            else:
                raise AssertionError(args)
        elif args[0] == "shell" and args[1].startswith("for task_fd in /proc/5555/fdinfo/"):
            out = b"pos: 0\nflags: 0\n" if self.no_watch else b"inotify wd:1 ino:1e240 sdev:fd00000 mask:8 ignored_mask:0\n"
        elif args[:3] == ["shell", "kill", "-TERM"]:
            assert args[3] == "5555" and not self.watcher_reuse
        elif len(args) == 2 and args[0] == "shell" and args[1].startswith("run-as " + controller.HARNESS + " sh -c "):
            tokens = shlex.split(args[1])
            assert tokens[:4] == ["run-as", controller.HARNESS, "sh", "-c"]
            assert tokens[5] == "mihon-frame-gate"
            assert kwargs.get("input") is None and kwargs["timeout"] <= 3
            if self.stage_timeout:
                raise subprocess.TimeoutExpired(command, kwargs["timeout"])
            self.pending_nonce = tokens[6].encode()
        elif args[:3] == ["shell", "run-as", controller.HARNESS] and args[3] in ("test", "chmod", "mv"):
            if args[3] == "mv":
                self.armed = True
        elif args[:4] == ["exec-out", "run-as", controller.HARNESS, "tee"]:
            if self.eof_stall:
                raise subprocess.TimeoutExpired(command, kwargs["timeout"])
            assert kwargs["input"].decode().startswith("20000000-")
            out = kwargs["input"]
        elif args[0] == "pull":
            code = int(self.pull_failure)
            Path(args[2]).write_bytes(TRACE[:10] if code else TRACE)
        elif args[:3] == ["shell", "rm", "-f"]:
            assert args[3:] == [self.remote]
        elif args[:4] == ["exec-out", "run-as", controller.HARNESS, "cat"]:
            if args[4].endswith(".go.pending"):
                assert args[4].startswith(f"/data/user/0/{controller.HARNESS}/files/reader-validation/{RUN}/")
                assert kwargs.get("input") is None and kwargs["timeout"] <= 3
                out = b"wrong nonce" if self.bad_staged_nonce else self.pending_nonce
                return subprocess.CompletedProcess(command, 0, out, b"")
            assert args[4].startswith(f"files/reader-validation/{RUN}/")
            if args[4].endswith("report.json"):
                report = copy.deepcopy(self.report)
                if self.corrupt_final:
                    report["frame_windows"][0]["gate_nonce"] = "tampered"
                out = json.dumps(report).encode()
            elif args[4].endswith(".png"):
                out = b"simulated png bytes; offline pixel verification required"
            else:
                raise AssertionError(args)
        else:
            raise AssertionError("Unexpected device mutation: " + repr(args))
        return subprocess.CompletedProcess(command, code, out, b"simulated failure" if code else b"")


class ControllerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "capture"
        self.mask = os.umask(0o077)

    def tearDown(self):
        os.umask(self.mask)
        self.temp.cleanup()

    def run_controller(self, fake, anchor_pixels=0):
        args = ["--serial", "explicit-device", "--adb", "fake-adb", "--output", str(self.root),
                "--backend", fake.report["backend"], "--pairs", str(fake.report["frame_pairs_requested"]),
                "--initial-mode", "translated", "--subject-apk-sha256", "a" * 64,
                "--harness-apk-sha256", "b" * 64, "--acceptance", "--automatic-translation-disabled",
                "--chapters-ahead-zero", "--cached-translation-visible"]
        if anchor_pixels:
            args += ["--frame-anchor-pixels", str(anchor_pixels)]
        with patch.object(controller.subprocess, "Popen", side_effect=fake.popen), \
                patch.object(controller.subprocess, "run", side_effect=fake.run), \
                patch.object(controller.time, "monotonic", side_effect=lambda: fake.clock), \
                patch.object(controller.time, "sleep", side_effect=fake.sleep), contextlib.redirect_stdout(io.StringIO()):
            code = controller.main(args)
        return code, json.loads((self.root / "manifest.json").read_text())

    def test_explicit_interior_descriptor_binds_gate_command_and_final_windows(self):
        report = fixtures.PairedWindowsTests().interior_report()
        report["actions"] = [{"details": {"screenshot": f"{i:02d}-frame_test.png"}} for i in range(8)]
        for window in report["frame_windows"]:
            ordinal = window["ordinal"]
            window["gate_file"] = f"/data/user/0/{controller.HARNESS}/files/reader-validation/{RUN}/frame-{ordinal}.go"
            window["gate_nonce"] = f"20000000-0000-0000-0000-{ordinal:012d}"
        code, manifest = self.run_controller(FakeDevice(report), 100)
        self.assertEqual(code, 0)
        self.assertEqual(manifest["frame_anchor"], controller.reconcile.INTERIOR_DESCRIPTOR)
        self.assertIn("frameAnchorPixels", manifest["instrumentation_command"])
        self.assertEqual(manifest["screenshots_pulled"], 8)

    def test_interior_ready_cannot_arm_page_start_capture(self):
        report = make_report()
        report["backend"] = "webgpu"
        for window in report["frame_windows"]:
            window["backend"] = "webgpu"
        fake = FakeDevice(report)
        code, manifest = self.run_controller(fake, 100)
        self.assertEqual(code, 1)
        self.assertIn("anchor differs", manifest["failure"])
        self.assertEqual(manifest["windows"], [])

    def test_ready_requires_exact_owned_path_nonce_run_order_and_protocol(self):
        ready = ready_of(make_report()["frame_windows"][0])
        self.assertEqual(controller.validate_ready(ready, 1, "classic"), RUN)
        changes = {"gate_file": "/data/user/0/app.mihon/files/credentials", "gate_nonce": "nonce;rm -rf /",
                   "ordinal": 2, "backend": "webgpu", "mode": "original", "distance_px": 1, "page": float("nan")}
        for key, value in changes.items():
            with self.subTest(key=key), self.assertRaises(ValueError):
                controller.validate_ready(dict(ready, **{key: value}), 1, "classic")
        with self.assertRaises(ValueError):
            controller.validate_ready(ready, 1, "classic", "30000000-0000-0000-0000-000000000000")

    def test_one_pair_closes_pulls_hashes_before_next_gate_and_archives_private_evidence(self):
        fake = FakeDevice()
        code, report = self.run_controller(fake)
        self.assertEqual(code, 0)
        self.assertTrue(report["completed"])
        self.assertIn("pending_offline", report["status"])
        self.assertEqual(report["screenshots_pulled"], 6)
        starts = [i for i, call in enumerate(fake.calls) if call[0] == "start"]
        arms = [i for i, call in enumerate(fake.calls) if call[0] == "call" and call[1][3:4] == ["mv"]]
        waits = [i for i, call in enumerate(fake.calls) if call == ("recorder_gone",)]
        pulls = [i for i, call in enumerate(fake.calls) if call[0] == "call" and call[1][0] == "pull"]
        self.assertTrue(starts[0] < arms[0] < waits[0] < pulls[0] < starts[1] < arms[1])
        for window in report["windows"]:
            self.assertEqual(window["trace"]["sha256"], controller.hashlib.sha256(TRACE).hexdigest())
        for path in self.root.rglob("*"):
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o700 if path.is_dir() else 0o600)
        self.assertFalse(any("input" in str(c) or "force-stop" in str(c) for c in fake.calls))

    def test_three_pairs_are_six_separate_captures_with_alternating_modes(self):
        code, report = self.run_controller(FakeDevice(make_report(3)))
        self.assertEqual(code, 0)
        self.assertEqual([w["mode"] for w in report["windows"]], ["translated", "original", "original", "translated", "translated", "original"])
        self.assertEqual(len({w["trace"]["path"] for w in report["windows"]}), 6)

    def test_capability_failure_stops_before_instrumentation_or_recording(self):
        for option in ("no_perfetto", "no_inotify"):
            with self.subTest(option=option):
                self.root = Path(self.temp.name) / option
                fake = FakeDevice(**{option: True})
                code, manifest = self.run_controller(fake)
                self.assertEqual(code, 1)
                self.assertIn("no instrumentation started", manifest["failure"])
                self.assertFalse(any(c[0] in ("start", "instrumentation") for c in fake.calls))

    def test_actual_k90_help_exit_one_with_valid_usage_allows_capture(self):
        # Actual captured K90 --help output; it exits 1 even for this supported request.
        fake = FakeDevice(help_exit=1)
        code, manifest = self.run_controller(fake)
        self.assertEqual(code, 0)
        help_receipt = next(c for c in manifest["commands"] if c["label"] == "perfetto-capabilities")
        self.assertEqual(help_receipt["exit_code"], 1)
        self.assertTrue(manifest["capture_capabilities"]["validated_before_instrumentation"])

    def test_help_error_codes_or_nonusage_fail_before_instrumentation(self):
        cases = [(2, PERFETTO_HELP), (124, PERFETTO_HELP), (1, b"permission denied"),
                 (1, b"perfetto: unrecognized option --help\n" + PERFETTO_HELP),
                 (1, b"--background-wait --txt --config")]
        for index, (exit_code, output) in enumerate(cases):
            with self.subTest(exit_code=exit_code, output=output[:40]):
                self.root = Path(self.temp.name) / ("help-error-" + str(index))
                fake = FakeDevice(help_exit=exit_code, help_output=output)
                code, manifest = self.run_controller(fake)
                self.assertEqual(code, 1)
                self.assertFalse(any(c[0] in ("start", "instrumentation") for c in fake.calls))

    def test_gate_staging_does_not_wait_for_forwarded_stdin_eof(self):
        fake = FakeDevice(eof_stall=True)
        code, manifest = self.run_controller(fake)
        self.assertEqual(code, 0)
        record = json.loads((self.root / "window-1/capture.json").read_text())
        self.assertTrue(record["gate_nonce_verified"])
        self.assertEqual(record["gate_staging_method"], "fixed_printf_positional_arguments")
        self.assertFalse(any(c[0] == "call" and c[1][:4] == ["exec-out", "run-as", controller.HARNESS, "tee"]
                             for c in fake.calls))

    def test_gate_write_timeout_leaves_gate_unarmed_and_retains_evidence(self):
        fake = FakeDevice(stage_timeout=True)
        code, manifest = self.run_controller(fake)
        self.assertEqual(code, 1)
        self.assertIn("gate-stage-1 (124)", manifest["failure"])
        self.assertFalse(any(c[0] == "call" and c[1][3:4] == ["mv"] for c in fake.calls))
        capture = json.loads((self.root / "window-1/capture.json").read_text())
        self.assertFalse(capture["gate_armed"])
        self.assertTrue(capture["gate_pending"])

    def test_gate_readback_must_match_exact_nonce_before_arm(self):
        fake = FakeDevice(bad_staged_nonce=True)
        code, manifest = self.run_controller(fake)
        self.assertEqual(code, 1)
        self.assertIn("nonce differs", manifest["failure"])
        self.assertFalse(any(c[0] == "call" and c[1][3:4] == ["mv"] for c in fake.calls))

    def test_fixed_stage_script_finishes_with_open_stdin_and_never_overwrites(self):
        ready = ready_of(make_report()["frame_windows"][0])
        tokens = shlex.split(controller.gate_stage_command(ready))
        self.assertEqual(tokens[6:], [ready["gate_nonce"], ready["gate_file"] + ".pending"])
        self.assertNotIn(ready["gate_nonce"], tokens[4])
        path = Path(self.temp.name) / "nonce.pending"
        process = subprocess.Popen(["/bin/sh", "-c", tokens[4], tokens[5], tokens[6], str(path)],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            self.assertEqual(process.wait(timeout=2), 0)  # stdin stays open throughout the wait.
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=2)
            process.stdin.close()
            process.stdout.close()
            process.stderr.close()
        self.assertEqual(path.read_bytes(), ready["gate_nonce"].encode())
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        result = subprocess.run(["/bin/sh", "-c", tokens[4], tokens[5], "different", str(path)], capture_output=True, timeout=2)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(path.read_bytes(), ready["gate_nonce"].encode())
        for value in (dict(ready, gate_nonce="$(touch anything)"), dict(ready, gate_file="/tmp/not-owned")):
            with self.assertRaises(ValueError):
                controller.gate_stage_command(value)

    def test_start_pid_must_own_this_trace_before_any_gate(self):
        fake = FakeDevice(bad_identity=True)
        code, manifest = self.run_controller(fake)
        self.assertEqual(code, 1)
        self.assertIn("does not own", manifest["failure"])
        self.assertFalse(any(c[0] == "call" and c[1][3:4] == ["mv"] for c in fake.calls))
        self.assertFalse(any(c[0] == "call" and c[1][0] == "pull" for c in fake.calls))

    def test_inode_watch_registration_required_before_gate(self):
        fake = FakeDevice(no_watch=True)
        code, manifest = self.run_controller(fake)
        self.assertEqual(code, 1)
        self.assertIn("writable-close watch", manifest["failure"])
        self.assertFalse(any(c[0] == "call" and c[1][3:4] == ["mv"] for c in fake.calls))
        self.assertFalse(any(c[0] == "call" and c[1][0] == "pull" for c in fake.calls))

    def test_exited_pid_without_close_receipt_never_pulls(self):
        fake = FakeDevice(no_close=True)
        code, manifest = self.run_controller(fake)
        self.assertEqual(code, 1)
        record = json.loads((self.root / "window-1/capture.json").read_text())
        self.assertTrue(record["owned_process_exited"])
        self.assertFalse(record.get("writer_close_observed", False))
        self.assertFalse(any(c[0] == "call" and c[1][0] == "pull" for c in fake.calls))
        self.assertEqual(len([c for c in fake.calls if c[0] == "start"]), 1)

    def test_close_receipt_for_other_trace_never_pulls(self):
        fake = FakeDevice(wrong_close=True)
        code, manifest = self.run_controller(fake)
        self.assertEqual(code, 1)
        self.assertIn("exact owned trace", manifest["failure"])
        self.assertFalse(any(c[0] == "call" and c[1][0] == "pull" for c in fake.calls))

    def test_denied_procfs_read_is_not_an_exit(self):
        fake = FakeDevice(denied_stat=True)
        code, manifest = self.run_controller(fake)
        self.assertEqual(code, 1)
        record = json.loads((self.root / "window-1/capture.json").read_text())
        self.assertFalse(record.get("owned_process_exited", False))
        self.assertFalse(any(c[0] == "call" and c[1][0] == "pull" for c in fake.calls))

    def test_reused_recorder_pid_means_original_exited_but_still_requires_writer_close(self):
        fake = FakeDevice(reuse_recorder=True)
        code, manifest = self.run_controller(fake)
        self.assertEqual(code, 0)
        record = json.loads((self.root / "window-1/capture.json").read_text())
        self.assertTrue(record["owned_process_exited"])
        self.assertTrue(record["writer_close_observed"])
        self.assertIsNone(record["record_exit_code"])
        self.assertEqual(record["record_exit_status"], "unavailable_for_background_child")
        self.assertEqual(record["start_ack_exit_code"], 0)
        self.assertFalse(any(c[0] == "call" and c[1][:3] == ["shell", "kill", "-TERM"] and c[1][3] == "4444" for c in fake.calls))

    def test_observer_pid_reuse_prevents_killing_new_process(self):
        fake = FakeDevice(watcher_reuse=True)
        code, manifest = self.run_controller(fake)
        self.assertEqual(code, 0)
        self.assertFalse(any(c[0] == "call" and c[1][:3] == ["shell", "kill", "-TERM"] for c in fake.calls))
        record = json.loads((self.root / "window-1/capture.json").read_text())
        self.assertIn("pid_reused", record["writer_cleanup"])

    def test_proc_stat_parses_spaces_and_rejects_wrong_or_missing_identity(self):
        self.assertEqual(controller.process_stat(stat_receipt(44, start=778), 44),
                         {"pid": 44, "start_ticks": 778, "state": "S"})
        self.assertIsNone(controller.process_stat(b"MIHON_PROCESS_GONE\n", 44))
        for raw in (stat_receipt(45), b"44 (task) S 0", b"permission denied"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                controller.process_stat(raw, 44)

    def test_inotify_receipt_rejects_overflow_unwatchable_and_wrong_pid(self):
        remote = "/owned/trace"
        self.assertTrue(controller.close_event(b"55\nw\t/owned/trace\n", 55, remote))
        self.assertFalse(controller.close_event(b"55\n", 55, remote))
        self.assertFalse(controller.close_event(b"55\nw\t/owned/tr", 55, remote))
        for raw in (b"56\nw\t/owned/trace\n", b"55\no\t/owned/trace\n", b"55\nx\t/owned/trace\n"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                controller.close_event(raw, 55, remote)

    def test_failed_start_receipt_never_arms_a_gate(self):
        fake = FakeDevice(receipt=b"not a pid\n")
        code, report = self.run_controller(fake)
        self.assertEqual(code, 1)
        self.assertFalse(report["completed"])
        self.assertFalse(any(c[0] == "call" and c[1][3:4] == ["mv"] for c in fake.calls))
        self.assertEqual(len([c for c in fake.calls if c[0] == "start"]), 1)

    def test_unowned_ready_stops_before_any_trace_or_gate(self):
        fake = FakeDevice()
        fake.corrupt_ready = lambda w: dict(w, gate_file="/sdcard/not-owned.go")
        code, report = self.run_controller(fake)
        self.assertEqual(code, 1)
        self.assertFalse(any(c[0] == "start" for c in fake.calls))
        self.assertNotIn("harness_run_id", report)

    def test_trace_pull_failure_preserves_remote_trace_and_prevents_second_capture(self):
        fake = FakeDevice(pull_failure=True)
        code, report = self.run_controller(fake)
        self.assertEqual(code, 1)
        capture = json.loads((self.root / "window-1/capture.json").read_text())
        self.assertTrue(capture["trace_may_remain"])
        self.assertFalse(capture["completed"])
        self.assertFalse(any(c[0] == "call" and c[1][:2] == ["shell", "rm"] for c in fake.calls))
        self.assertEqual(len([c for c in fake.calls if c[0] == "start"]), 1)

    def test_failed_recording_never_claims_complete_or_arms_next_window(self):
        fake = FakeDevice(capture_exit=1)
        code, report = self.run_controller(fake)
        self.assertEqual(code, 1)
        self.assertFalse(report["completed"])
        self.assertEqual(len([c for c in fake.calls if c[0] == "start"]), 1)

    def test_final_report_must_match_status_receipts(self):
        fake = FakeDevice()
        fake.corrupt_final = True
        code, report = self.run_controller(fake)
        self.assertEqual(code, 1)
        self.assertIn("differs", report["failure"])

    def test_screenshot_path_cannot_read_other_app_data(self):
        fake = FakeDevice()
        fake.report["actions"][0]["details"]["screenshot"] = "../../credentials.json"
        code, report = self.run_controller(fake)
        self.assertEqual(code, 1)
        self.assertIn("escaped", report["failure"])
        self.assertFalse(any("credentials.json" in str(c) for c in fake.calls))

    def test_reused_nonce_prevents_second_capture(self):
        report = make_report()
        report["frame_windows"][1]["gate_nonce"] = report["frame_windows"][0]["gate_nonce"]
        fake = FakeDevice(report)
        code, manifest = self.run_controller(fake)
        self.assertEqual(code, 1)
        self.assertIn("nonce was reused", manifest["failure"])
        self.assertEqual(len([c for c in fake.calls if c[0] == "start"]), 1)

    def test_record_timeout_preserves_unknown_remote_state_without_next_gate(self):
        fake = FakeDevice(timeout=True)
        code, manifest = self.run_controller(fake)
        self.assertEqual(code, 1)
        self.assertIn("recording timeout", manifest["failure"])
        capture = json.loads((self.root / "window-1/capture.json").read_text())
        self.assertTrue(capture["record_timeout"])
        self.assertTrue(capture["writer_close_observed"])
        self.assertFalse(capture.get("owned_process_exited", False))
        self.assertTrue(capture["trace_may_remain"])
        self.assertFalse(any(c[0] == "call" and c[1][0] == "pull" for c in fake.calls))

    def test_late_gate_is_not_armed_even_after_staging_nonce(self):
        self.root.mkdir()
        device = controller.Device("fake-adb", "explicit-device", self.root, {"commands": []})
        capture = controller.WindowCapture(device, 1)
        fake = FakeDevice()
        capture.process = Process(fake, "writer")
        capture.recorder = {"pid": 4444, "start_ticks": 100}
        capture.watcher = {"pid": 5555, "start_ticks": 100}
        capture.record["writer_watch_registered"] = True
        capture.started = 0
        capture.record["recording_started_confirmed"] = True
        with patch.object(controller.subprocess, "run", side_effect=fake.run), \
                patch.object(controller.time, "monotonic", return_value=13), self.assertRaises(ValueError):
            capture.arm(ready_of(make_report()["frame_windows"][0]))
        self.assertFalse(any(c[0] == "call" and c[1][3:4] == ["mv"] for c in fake.calls))
        self.assertFalse(capture.record["gate_armed"])

    def test_parser_ignores_arbitrary_log_text_and_rejects_malformed_event(self):
        self.assertIsNone(controller.parse_event("random frame_window_ready_json={}"))
        with self.assertRaises(json.JSONDecodeError):
            controller.parse_event("INSTRUMENTATION_STATUS: frame_window_ready_json=bad")

    def test_cannot_arm_without_a_receipt(self):
        self.root.mkdir()
        device = controller.Device("fake-adb", "explicit-device", self.root, {"commands": []})
        capture = controller.WindowCapture(device, 1)
        with self.assertRaises(ValueError):
            capture.arm(ready_of(make_report()["frame_windows"][0]))


if __name__ == "__main__":
    unittest.main()
