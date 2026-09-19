"""Fake-adb coverage for bounded capture and truthful evidence, without a device."""

import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "translator_perfetto_capture.py"
SPEC = importlib.util.spec_from_file_location("translator_perfetto_capture", SCRIPT)
capture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(capture)
TRACE = b"fake perfetto bytes - deliberately not proof of valid frame data"


class FakeAdb:
    def __init__(self, fail=None, trace=TRACE):
        self.fail = fail or {}
        self.trace = trace
        self.calls = []

    def run(self, command, **kwargs):
        self.calls.append((command, kwargs))
        if command[:3] != ["fake-adb", "-s", "explicit-test-serial"]:
            raise AssertionError("Capture must always use its explicitly selected device")
        args = command[3:]
        if args == ["get-state"]:
            label, stdout = "state", b"device\n"
        elif args[:3] == ["shell", "pm", "path"]:
            label, stdout = "package", b"package:/data/app/test/base.apk\n"
        elif args[:2] == ["shell", "getconf"]:
            label, stdout = "page-size", b"16384\n"
        elif args[:2] == ["shell", "getprop"]:
            label, stdout = "fingerprint", b"test/fingerprint\n"
        elif args == ["shell", "perfetto", "--query-raw"]:
            label, stdout = "sources", b"fake source capabilities"
        elif args[:3] == ["shell", "perfetto", "-o"]:
            label, stdout = "record", b"Wrote test trace"
        elif args[0] == "pull":
            label, stdout = "pull", b"1 file pulled"
        elif args[:3] == ["shell", "rm", "-f"]:
            label, stdout = "cleanup", b""
        else:
            raise AssertionError("Unexpected device mutation or command: " + repr(args))
        failure = self.fail.get(label)
        if isinstance(failure, BaseException):
            raise failure
        code = failure if type(failure) is int else 0
        if isinstance(failure, bytes):
            stdout = failure
        if label == "pull" and self.trace is not None:
            Path(args[2]).write_bytes(self.trace)
        return subprocess.CompletedProcess(command, code, stdout, b"simulated failure" if code else b"")


class CaptureTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.output = Path(self.directory.name) / "runs"
        self.original_umask = os.umask(0o077)

    def tearDown(self):
        os.umask(self.original_umask)
        self.directory.cleanup()

    def run_capture(self, fake=None, extra=None):
        fake = fake or FakeAdb()
        args = [str(SCRIPT), "--serial", "explicit-test-serial", "--package", "app.mihon.benchmark",
                "--scenario", "reader-frame-fixture", "--duration", "1", "--output", str(self.output),
                "--adb", "fake-adb"] + (extra or [])
        before = set(self.output.iterdir()) if self.output.exists() else set()
        with patch("sys.argv", args), patch.object(capture.subprocess, "run", side_effect=fake.run), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = capture.main()
        run = (set(self.output.iterdir()) - before).pop()
        return code, json.loads((run / "manifest.json").read_text()), run, fake

    def test_capture_hashes_trace_and_cleans_only_its_unique_remote_path(self):
        code, manifest, run, fake = self.run_capture()
        self.assertEqual(code, 0)
        self.assertTrue(manifest["completed"])
        self.assertEqual(manifest["capture_status"], "captured")
        self.assertEqual(manifest["trace"], {"bytes": len(TRACE), "sha256": hashlib.sha256(TRACE).hexdigest()})
        self.assertFalse(manifest["device_trace_may_remain"])
        self.assertEqual(manifest["device_trace_cleanup"], {"attempted": True, "removed": True})
        remote = manifest["device_trace_path"]
        self.assertEqual(remote, "/data/misc/perfetto-traces/mihon-" + run.name + ".pftrace")
        commands = [command[3:] for command, _ in fake.calls]
        self.assertEqual([args for args in commands if args[:2] == ["shell", "rm"]], [["shell", "rm", "-f", remote]])
        self.assertIn("Trace creation alone is not a performance pass.", manifest["limitations"])
        self.assertNotIn("performance_pass", manifest)
        self.assertEqual(stat.S_IMODE((run / "trace.pftrace").stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(run.stat().st_mode), 0o700)

    def test_native_heap_capture_is_explicitly_scoped_and_nonblocking(self):
        code, manifest, run, fake = self.run_capture(extra=["--profile", "native-heap"])
        self.assertEqual(code, 0)
        self.assertEqual(manifest["profile"], "native-heap")
        sent = next(options["input"].decode() for command, options in fake.calls
                    if command[3:6] == ["shell", "perfetto", "-o"])
        self.assertIn('process_cmdline: "app.mihon.benchmark"', sent)
        self.assertIn('name: "android.heapprofd"', sent)
        self.assertIn('block_client: false', sent)
        self.assertNotIn('all: true', sent)
        self.assertNotIn('sampling_interval_bytes: 0', sent)
        self.assertNotIn('name: "android.surfaceflinger.frametimeline"', sent)

    def test_unknown_profile_is_rejected_before_capture(self):
        with self.assertRaises(ValueError):
            capture.configuration("app.mihon", 30, "everything")

    def test_repeated_labels_use_different_remote_paths_and_private_runs(self):
        _, first, _, _ = self.run_capture()
        _, second, _, _ = self.run_capture()
        self.assertNotEqual(first["device_trace_path"], second["device_trace_path"])

    def test_unavailable_device_stops_before_package_or_trace_operations(self):
        code, manifest, _, fake = self.run_capture(FakeAdb({"state": b"unauthorized\n"}))
        self.assertEqual(code, 1)
        self.assertEqual(manifest["capture_status"], "target_unavailable")
        self.assertEqual(len(fake.calls), 1)
        self.assertFalse(manifest["completed"])
        self.assertFalse(manifest["device_trace_may_remain"])

    def test_missing_package_prevents_recording_and_retains_preflight_evidence(self):
        code, manifest, run, fake = self.run_capture(FakeAdb({"package": b""}))
        self.assertEqual(code, 1)
        self.assertEqual(manifest["capture_status"], "package_unavailable")
        self.assertEqual(len(fake.calls), 2)
        self.assertTrue((run / "installed-package.stdout").is_file())
        self.assertIn("finished_utc", manifest)

    def test_record_failure_keeps_partial_trace_without_claiming_completion(self):
        code, manifest, _, _ = self.run_capture(FakeAdb({"record": 1}))
        self.assertEqual(code, 1)
        self.assertFalse(manifest["completed"])
        self.assertEqual(manifest["capture_status"], "record_failed_partial_trace_retained")
        self.assertIn("trace", manifest)
        self.assertTrue(manifest["device_trace_cleanup"]["removed"])

    def test_pull_failure_does_not_delete_the_only_remote_evidence(self):
        code, manifest, run, fake = self.run_capture(FakeAdb({"pull": 1}))
        self.assertEqual(code, 1)
        self.assertFalse(manifest["completed"])
        self.assertEqual(manifest["capture_status"], "pull_failed")
        self.assertTrue(manifest["device_trace_may_remain"])
        self.assertFalse(manifest["device_trace_cleanup"]["attempted"])
        self.assertNotIn("trace", manifest)  # A partial file exists but is not presented as verified capture.
        self.assertTrue((run / "trace.pftrace").exists())
        self.assertFalse(any(command[3:5] == ["shell", "rm"] for command, _ in fake.calls))

    def test_empty_or_missing_trace_cannot_complete_even_when_adb_reports_success(self):
        for content in (None, b""):
            with self.subTest(content=content):
                code, manifest, _, _ = self.run_capture(FakeAdb(trace=content))
                self.assertEqual(code, 1)
                self.assertEqual(manifest["capture_status"], "trace_missing_or_empty")
                self.assertFalse(manifest["completed"])
                self.assertTrue(manifest["device_trace_may_remain"])

    def test_cleanup_failure_keeps_capture_and_exposes_remaining_owned_file(self):
        code, manifest, _, _ = self.run_capture(FakeAdb({"cleanup": 1}))
        self.assertEqual(code, 0)  # Local capture succeeded; cleanup is independently unsuccessful.
        self.assertTrue(manifest["completed"])
        self.assertEqual(manifest["device_trace_cleanup"], {"attempted": True, "removed": False})
        self.assertTrue(manifest["device_trace_may_remain"])

    def test_record_timeout_preserves_partial_stdout_and_is_not_a_completed_trace(self):
        error = subprocess.TimeoutExpired("fake-adb", 21, output=b"partial output", stderr=b"partial error")
        code, manifest, run, _ = self.run_capture(FakeAdb({"record": error}))
        self.assertEqual(code, 1)
        record = next(command for command in manifest["commands"] if command["label"] == "record")
        self.assertEqual((record["exit_code"], record["termination"]), (124, "timeout"))
        self.assertEqual((run / "record.stdout").read_bytes(), b"partial output")
        self.assertEqual((run / "record.stderr").read_bytes(), b"partial error")
        self.assertFalse(manifest["completed"])

    def test_missing_adb_is_recorded_as_os_error_in_preflight(self):
        code, manifest, _, _ = self.run_capture(FakeAdb({"state": FileNotFoundError("fake-adb is absent")}))
        self.assertEqual(code, 1)
        self.assertEqual(manifest["commands"][0]["termination"], "os_error")
        self.assertEqual(manifest["commands"][0]["exit_code"], 127)

    def test_source_query_failure_does_not_invent_track_availability(self):
        code, manifest, _, _ = self.run_capture(FakeAdb({"sources": 1}))
        self.assertEqual(code, 0)
        source = next(command for command in manifest["commands"] if command["label"] == "available-data-sources")
        self.assertEqual(source["exit_code"], 1)
        self.assertNotIn("gpu_memory_available", manifest)
        self.assertNotIn("frame_timeline_available", manifest)

    def test_interruption_finalizes_manifest_and_preserves_possible_remote_trace(self):
        code, manifest, _, fake = self.run_capture(FakeAdb({"record": KeyboardInterrupt()}))
        self.assertEqual(code, 130)
        self.assertEqual(manifest["capture_status"], "interrupted")
        self.assertFalse(manifest["completed"])
        self.assertTrue(manifest["device_trace_may_remain"])
        self.assertIn("finished_utc", manifest)
        self.assertFalse(any(command[3:5] == ["shell", "rm"] for command, _ in fake.calls))

    def test_recording_duration_and_host_wait_are_bounded(self):
        _, _, run, fake = self.run_capture(extra=["--duration", "60"])
        config = (run / "config.pbtxt").read_text()
        self.assertIn("duration_ms: 60000", config)
        self.assertIn("size_kb: 32768 fill_policy: RING_BUFFER", config)
        self.assertIn("write_into_file: true", config)
        self.assertIn("file_write_period_ms: 2000", config)
        self.assertIn("flush_period_ms: 5000", config)
        self.assertIn("max_file_size_bytes: 536870912", config)
        self.assertIn("size_kb: 8192 fill_policy: RING_BUFFER", config)
        self.assertIn('name: "linux.ftrace" target_buffer: 0', config)
        self.assertIn('ftrace_events: "gpu_mem/gpu_mem_total"', config)
        self.assertIn('ftrace_events: "power/gpu_frequency"', config)
        for source in ("linux.process_stats", "android.surfaceflinger.frametimeline",
                       "android.gpu.memory", "android.packages_list"):
            self.assertIn(f'name: "{source}" target_buffer: 1', config)
        call = next(call for call in fake.calls if call[0][3:6] == ["shell", "perfetto", "-o"])
        self.assertEqual(call[1]["timeout"], 80)
        self.assertEqual(call[1]["input"].decode(), config)
        self.assertTrue(all(kwargs["timeout"] <= 80 for _, kwargs in fake.calls))

    def test_capture_success_does_not_imply_retained_coverage_was_checked(self):
        _, manifest, _, _ = self.run_capture()
        self.assertEqual(manifest["coverage"], {"status": "not_checked", "offline_analysis_required": True})
        self.assertEqual(manifest["recording_limits"]["max_file_size_bytes"], 512 * 1024 * 1024)

    def test_retained_tail_is_flagged_even_when_trace_processor_succeeds(self):
        import csv
        processor = Path(self.directory.name) / "processor"
        processor.write_text("#!/usr/bin/env python3\n")
        trace = Path(self.directory.name) / "trace.pftrace"
        trace.write_bytes(TRACE)
        for duration, expected in ((6.61, "short_retained_window"), (29.99, "duration_observed")):
            output = io.StringIO()
            writer = csv.writer(output)
            writer.writerow(["retained_seconds", "frame_timeline_rows", "named_package_processes",
                             "package_frame_rows", "data_loss_stat_count", "ftrace_setup_errors", "discarded_chunks"])
            writer.writerow([duration, 100, 0, 0, 0, 36, 3])
            result = subprocess.CompletedProcess([], 0, output.getvalue().encode(), b"")
            with patch.object(capture.subprocess, "run", return_value=result) as run:
                observed = capture.inspect_coverage(processor, trace, 30, "app.mihon", Path(self.directory.name))
            self.assertEqual(observed["status"], expected)
            self.assertTrue(observed["offline_analysis_required"])
            self.assertEqual(observed["named_package_processes"], 0)
            self.assertEqual(run.call_args.kwargs["timeout"], 60)
            self.assertEqual(run.call_args.args[0][0], capture.sys.executable)

    def test_coverage_processor_failure_cannot_claim_a_complete_window(self):
        processor = Path(self.directory.name) / "processor"
        processor.write_text("binary placeholder\n")
        with patch.object(capture.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, b"", b"bad trace")):
            observed = capture.inspect_coverage(processor, processor, 30, "app.mihon", Path(self.directory.name))
        self.assertEqual(observed["status"], "analysis_failed")
        self.assertTrue(observed["offline_analysis_required"])

    def test_invalid_args_fail_before_any_device_call_or_artifact(self):
        for extra in (["--duration", "0"], ["--duration", "61"], ["--duration", "1.5"],
                      ["--serial", "wrong;device"], ["--package", 'app.bad"\nname'],
                      ["--scenario", "../another-file"]):
            with self.subTest(extra=extra):
                fake = FakeAdb()
                with self.assertRaises(SystemExit) as raised:
                    self.run_capture(fake, extra)
                self.assertEqual(raised.exception.code, 2)
                self.assertEqual(fake.calls, [])
        self.assertFalse(self.output.exists())

    def test_configuration_rejects_noninteger_direct_call_values(self):
        for duration in (True, 0.5, 1.5, 61):
            with self.assertRaises(ValueError):
                capture.configuration("app.mihon.benchmark", duration)


if __name__ == "__main__":
    unittest.main()
