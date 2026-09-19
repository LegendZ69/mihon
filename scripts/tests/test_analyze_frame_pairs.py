"""Offline analysis guards; synthetic table rows and private host files only."""

import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "android-ui-validation/analyze_frame_pairs.py"
SPEC = importlib.util.spec_from_file_location("analyze_frame_pairs", SCRIPT)
analysis = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(analysis)
START, END, PID = 10_000_000_000, 40_000_000_000, 1234


def raw_fixture():
    return {"clock_metadata": [{"int_value": 6}],
            "boottime": [{"ts": 9_000_000_000, "clock_value": 9_000_000_000, "clock_id": 6},
                         {"ts": 41_000_000_000, "clock_value": 41_000_000_000, "clock_id": 6}],
            "bounds": [{"start_ts": 9_000_000_000, "end_ts": 42_000_000_000}],
            "process": [{"pid": PID, "name": "app.mihon", "upid": 1}], "diagnostics": [],
            "scheduler": [{"cpu": 0, "rows": 2, "covered_ns": END-START, "largest_gap_ns": 0}],
            "frame_counts": [{"complete_rows": 20, "unfinished_rows": 0}],
            "draw_slice_wall": [{"rows": 10}], "heap_samples": [],
            "draw_cpu": [{"intersections": 10, "scheduled_cpu_ms": 20.0}],
            "gpu_render_stages": [{"package_complete_rows": 0}],
            "deadlines": [{"actual_rows": 20, "unique_expected_matches": 20}],
            "frame_durations": [{"jank_rows": 2, "app_deadline_missed_rows": 0}]}


class AnalyzeFramePairsTests(unittest.TestCase):
    def capture(self, background=False):
        value = {"completed": True, "recording_started_confirmed": True, "gate_armed": True,
                 "gate_nonce": "nonce", "gate_file": "gate", "recording_seconds": 45,
                 "record_exit_code": 0, "receipt_hex": "00"}
        if background:
            value.update(completion_method="background_wait_pid_and_close_write", start_ack_exit_code=0,
                         start_ack_stdout_hex=b"123\n".hex(), writer_watch_registered=True, trace_inode=12345,
                         owned_process_exited=True, writer_close_observed=True, record_exit_code=None,
                         record_exit_status="unavailable_for_background_child",
                         recorder_identity={"pid": 123, "start_ticks": 1000, "cmdline_sha256": "a" * 64},
                         writer_identity={"pid": 456, "start_ticks": 1100, "cmdline_sha256": "b" * 64})
            del value["receipt_hex"]
        return value

    def test_background_wait_capture_preserves_unavailable_exit_code(self):
        capture = self.capture(background=True)
        result = analysis.verify_capture(capture, capture)
        self.assertEqual("background_wait_pid_and_close_write", result["method"])
        self.assertEqual("unavailable_for_background_child", result["recorder_exit_status"])
        self.assertIsNone(capture["record_exit_code"])

    def test_background_capture_requires_both_exit_and_close_and_exact_start_identity(self):
        mutations = [lambda c: c.update(owned_process_exited=False), lambda c: c.update(writer_close_observed=False),
                     lambda c: c.update(writer_watch_registered=False), lambda c: c.update(trace_inode=True),
                     lambda c: c.update(record_exit_code=0), lambda c: c.update(record_exit_status="success"),
                     lambda c: c.update(start_ack_exit_code=1), lambda c: c.update(start_ack_stdout_hex=b"789\n".hex()),
                     lambda c: c["recorder_identity"].update(start_ticks=0),
                     lambda c: c["writer_identity"].update(cmdline_sha256="missing"),
                     lambda c: c["writer_identity"].update(pid=123)]
        for change in mutations:
            capture = self.capture(background=True)
            change(capture)
            with self.subTest(capture=capture), self.assertRaises(ValueError):
                analysis.verify_capture(capture, capture)

    def test_legacy_notify_capture_is_separate_and_unknown_methods_fail_closed(self):
        capture = self.capture()
        self.assertEqual("observed_exit_code_zero", analysis.verify_capture(capture, capture)["recorder_exit_status"])
        for change in (lambda c: c.update(receipt_hex=""), lambda c: c.update(record_exit_code=None),
                       lambda c: c.update(completion_method="unverified"), lambda c: c.update(gate_armed=False)):
            invalid = self.capture()
            change(invalid)
            with self.assertRaises(ValueError):
                analysis.verify_capture(invalid, capture)

    def test_no_app_deadline_misses_are_never_promoted_to_no_jank(self):
        measured = analysis.assess(raw_fixture(), START, END, PID)
        self.assertTrue(measured["frame_window_eligible_for_comparison"])
        self.assertIsNone(measured["performance_pass"])
        self.assertEqual(measured["raw"]["frame_durations"][0]["jank_rows"], 2)

    def test_missing_wrong_or_unbracketed_clock_mapping_rejects_measurements(self):
        mutations = [lambda r: r.update(clock_metadata=[]),
                     lambda r: r["clock_metadata"][0].update(int_value=3),
                     lambda r: r["boottime"][0].update(ts=9_000_000_001),
                     lambda r: r["boottime"][1].update(ts=39_000_000_000, clock_value=39_000_000_000)]
        for mutate in mutations:
            raw = raw_fixture()
            mutate(raw)
            with self.assertRaises(ValueError):
                analysis.assess(raw, START, END, PID)

    def test_truncated_trace_and_ambiguous_or_wrong_process_fail_closed(self):
        for mutate in [lambda r: r["bounds"][0].update(end_ts=END-1),
                       lambda r: r["process"].append(copy.deepcopy(r["process"][0])),
                       lambda r: r["process"][0].update(pid=999),
                       lambda r: r["process"][0].update(name=None)]:
            raw = raw_fixture()
            mutate(raw)
            with self.assertRaises(ValueError):
                analysis.assess(raw, START, END, PID)

    def test_loss_and_unfinished_frames_are_retained_as_incomplete_evidence(self):
        for mutate in [lambda r: r["diagnostics"].append({"name": "ftrace_cpu_overrun_delta", "severity": "info", "value": 1}),
                       lambda r: r["frame_counts"][0].update(unfinished_rows=1)]:
            raw = raw_fixture()
            mutate(raw)
            result = analysis.assess(raw, START, END, PID)
            self.assertFalse(result["frame_window_eligible_for_comparison"])
            self.assertEqual(result["status"], "incomplete_frame_evidence")

    def test_unavailable_gpu_and_allocations_and_scheduler_gaps_remain_explicit(self):
        raw = raw_fixture()
        raw["scheduler"][0]["covered_ns"] -= 2_000_000
        measured = analysis.assess(raw, START, END, PID)
        self.assertTrue(measured["draw_cpu_available"])
        self.assertFalse(measured["draw_cpu_eligible_for_comparison"])
        self.assertEqual(measured["draw_cpu_coverage"], "observed_partial_scheduling_only")
        self.assertFalse(measured["allocation_sampling_available"])
        self.assertIsNone(measured["allocation_rate_bytes_per_second"])
        self.assertFalse(measured["gpu_render_stage_timing_available"])

    def test_perfetto_hex_json_transport_preserves_quotes_and_labels(self):
        data = [{"name": 'quoted "DrawFrame", label', "value": None}]
        encoded = json.dumps(data).encode().hex()
        decoded = analysis.decode_sections('"section","payload"\n"example","' + encoded + '"\n')
        self.assertEqual(decoded, {"example": data})
        with self.assertRaises(ValueError):
            analysis.decode_sections('"section","payload"\n"a","5b5d"\n"a","5b5d"\n')

    def test_artifact_hash_and_path_guards_prevent_cross_run_reads(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "trace.pftrace"
            path.write_bytes(b"fixture")
            receipt = {"bytes": 7, "sha256": analysis.digest(path)}
            self.assertEqual(analysis.verified_artifact(root, "trace.pftrace", receipt), path)
            for name, changed in [("../trace.pftrace", receipt), ("trace.pftrace", {**receipt, "sha256": "0"*64})]:
                with self.assertRaises(ValueError):
                    analysis.verified_artifact(root, name, changed)

    def test_query_uses_half_open_window_exact_identity_and_unique_deadline_matches(self):
        query = analysis.window_sql(START, END, PID)
        self.assertIn("p.name='app.mihon' AND p.pid=q.target_pid", query)
        self.assertIn("a.ts>=q.ws AND a.ts<q.we", query)
        self.assertIn("e.matches=1", query)
        self.assertIn("draw_union", query)
        self.assertIn("r.utid", query)
        with self.assertRaises(ValueError):
            analysis.window_sql("1; bad query", END, PID)


if __name__ == "__main__":
    unittest.main()
