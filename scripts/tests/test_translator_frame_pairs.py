"""Offline oracle tests; no Android, credentials, native build or external requests."""

import copy
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "android-ui-validation/reconcile_frame_pairs.py"
SPEC = importlib.util.spec_from_file_location("reconcile_frame_pairs", SCRIPT)
frames = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(frames)


def report_fixture(pairs=3, canonical=False):
    report = {"plan": "framePairs", "status": "passed_action_assertions", "frame_restore_required": False,
              "initial_mode": "translated", "backend": "classic", "frame_pairs_requested": pairs,
              "frame_pairs_completed": pairs, "toggle_count": 4, "initial_chapter": "002 - Twelve-page corpus",
              "initial_page": 7.0, "frame_windows": []}
    for ordinal in range(1, pairs * 2 + 1):
        pair = (ordinal + 1) // 2
        first = "translated" if pair % 2 else "original"
        mode = first if ordinal % 2 else ("original" if first == "translated" else "translated")
        start = 100000 + ordinal * 50000
        pixel = ("a" if mode == "translated" else "b") * 64
        gestures = [{"index": index, "direction": "up" if index % 2 == 0 else "down",
                     "scheduled_uptime_ms": start + index * 1000, "start_uptime_ms": start + index * 1000,
                     "start_elapsed_ns": (start + index * 1000) * 1000000,
                     "end_uptime_ms": start + index * 1000 + 500,
                     "end_elapsed_ns": (start + index * 1000 + 500) * 1000000, "start_lateness_ms": 0}
                    for index in range(30)]
        report["frame_windows"].append({"ordinal": ordinal, "pair": pair, "mode": mode,
            "status": "boundary_assertions_passed", "chapter": report["initial_chapter"], "page": 7.0,
            "backend": "classic", "duration_ms": 30000, "cadence_ms": 1000, "gesture_duration_ms": 500,
            "distance_px": 500, "gesture_count_expected": 30, "start_uptime_ms": start,
            "end_uptime_ms": start + 30000, "start_elapsed_ns": start * 1000000,
            "end_elapsed_ns": (start + 30000) * 1000000, "gestures": gestures,
            "before_viewport_sha256": pixel, "after_viewport_sha256": pixel})
    if canonical:
        report.update(viewport_reset_policy=frames.CANONICAL_POLICY, restoration_target="canonical_page_start",
                      canonical_setup_verified=True, canonical_baseline_sha256="a" * 64,
                      entry_viewport_sha256="e" * 64, initial_page=7)
        for window in report["frame_windows"]:
            end = window["end_elapsed_ns"]
            window.update(page=7, natural_after_viewport_sha256="c" * 64,
                          natural_after_elapsed_ns=end + 1_000_000, reset_after_start_elapsed_ns=end + 2_000_000,
                          reset_after_end_elapsed_ns=end + 4_000_000_000, canonical_reset_verified=True)
    return report


class PairedWindowsTests(unittest.TestCase):
    def test_primed_protocol_preserves_guards_and_refuses_mixed_input_schedules(self):
        report = self.interior_report()
        report.update(viewport_reset_policy=frames.PRIMED_POLICY,
                      frame_anchor=frames.anchor_descriptor(100, "webgpu", True))
        for window in report["frame_windows"]:
            window["frame_anchor"] = frames.anchor_descriptor(100, "webgpu", True)
        self.assertEqual(len(frames.validate_report(report)), 2)
        report["frame_windows"][0]["frame_anchor"] = frames.anchor_descriptor(100, "webgpu")
        with self.assertRaises(ValueError):
            frames.validate_report(report)
        report["frame_windows"][0]["frame_anchor"] = frames.anchor_descriptor(100, "webgpu", True)
        report["frame_windows"][0]["after_viewport_sha256"] = "f" * 64
        with self.assertRaises(ValueError):
            frames.validate_report(report)
        with self.assertRaises(ValueError):
            frames.anchor_descriptor(0, "webgpu", True)

    def interior_report(self):
        report = report_fixture(1, canonical=True)
        report.update(backend="webgpu", viewport_reset_policy=frames.INTERIOR_POLICY,
                      restoration_target=frames.INTERIOR_TARGET, frame_anchor=frames.anchor_descriptor(100, "webgpu"))
        for window in report["frame_windows"]:
            window.update(backend="webgpu", frame_anchor=frames.anchor_descriptor(100, "webgpu"))
        return report

    def test_interior_protocol_is_explicit_and_keeps_timed_windows(self):
        windows = frames.validate_report(self.interior_report())
        self.assertEqual([row["duration_seconds"] for row in windows], [30.0, 30.0])
        self.assertEqual({row["restoration_target"] for row in windows}, {frames.INTERIOR_TARGET})
        self.assertEqual({row["viewport_reset_policy"] for row in windows}, {frames.INTERIOR_POLICY})

    def test_interior_cannot_mix_offsets_targets_backends_or_window_policies(self):
        mutations = [lambda r: r.pop("frame_anchor"),
                     lambda r: r["frame_anchor"].update(requested_drag_pixels=101),
                     lambda r: r.update(restoration_target="canonical_page_start"),
                     lambda r: r.update(backend="classic"),
                     lambda r: r.update(initial_page=8),
                     lambda r: r["frame_windows"][0].pop("frame_anchor"),
                     lambda r: r["frame_windows"][1]["frame_anchor"].update(release_hold_ms=0)]
        for mutation in mutations:
            report = self.interior_report()
            mutation(report)
            with self.assertRaises(ValueError):
                frames.validate_report(report)

    def test_interior_does_not_override_first_boundary_failure_or_lost_repeatability(self):
        for mutation in (lambda r: r.update(status="failed"),
                         lambda r: r["frame_windows"][0].update(status="failed"),
                         lambda r: r["frame_windows"][0].update(page=6),
                         lambda r: r["frame_windows"][0].update(after_viewport_sha256="f" * 64)):
            report = self.interior_report()
            mutation(report)
            with self.assertRaises(ValueError):
                frames.validate_report(report)

    def test_page_start_cannot_silently_accept_interior_metadata(self):
        report = report_fixture(1, canonical=True)
        report["frame_anchor"] = frames.anchor_descriptor(100, "webgpu")
        with self.assertRaises(ValueError):
            frames.validate_report(report)
        with self.assertRaises(ValueError):
            frames.validate_anchor(None, 100, "webgpu")
        with self.assertRaises(ValueError):
            frames.anchor_descriptor(100, "classic")
        self.assertIsNone(frames.validate_anchor(None, 0, "classic"))

    def test_three_alternating_pairs_keep_six_separate_windows(self):
        result = frames.validate_report(report_fixture())
        self.assertEqual([x["mode"] for x in result], ["translated", "original", "original", "translated", "translated", "original"])
        self.assertEqual([x["duration_seconds"] for x in result], [30.0] * 6)

    def test_one_calibration_pair_is_valid(self):
        self.assertEqual(len(frames.validate_report(report_fixture(1))), 2)

    def test_canonical_reset_accepts_natural_drift_and_keeps_legacy_explicitly_separate(self):
        legacy = frames.validate_report(report_fixture(1))
        canonical = frames.validate_report(report_fixture(canonical=True))
        self.assertEqual({row["viewport_reset_policy"] for row in legacy}, {frames.LEGACY_POLICY})
        self.assertEqual({row["viewport_reset_policy"] for row in canonical}, {frames.CANONICAL_POLICY})
        self.assertEqual(canonical[0]["natural_after_viewport_sha256"], "c" * 64)
        self.assertEqual(canonical[0]["restoration_target"], "canonical_page_start")

    def test_missing_or_tampered_canonical_setup_cannot_fall_back_to_legacy(self):
        for mutate in (lambda r: r.pop("viewport_reset_policy"), lambda r: r.update(canonical_setup_verified=False),
                       lambda r: r.update(restoration_target="arbitrary_entry_offset"),
                       lambda r: r.update(canonical_baseline_sha256="d" * 64),
                       lambda r: r.pop("entry_viewport_sha256"), lambda r: r.update(initial_page=7.0),
                       lambda r: r.update(viewport_reset_policy="unverified")):
            report = report_fixture(canonical=True)
            mutate(report)
            with self.assertRaises(ValueError):
                frames.validate_report(report)

    def test_canonical_reset_timestamps_cannot_overlap_either_timed_window(self):
        for mutate in (lambda r: r["frame_windows"][0].pop("natural_after_viewport_sha256"),
                       lambda r: r["frame_windows"][0].update(canonical_reset_verified=False),
                       lambda r: r["frame_windows"][0].update(natural_after_elapsed_ns=r["frame_windows"][0]["end_elapsed_ns"]),
                       lambda r: r["frame_windows"][0].update(reset_after_start_elapsed_ns=r["frame_windows"][0]["end_elapsed_ns"] - 1),
                       lambda r: r["frame_windows"][0].update(reset_after_end_elapsed_ns=r["frame_windows"][1]["start_elapsed_ns"] + 1),
                       lambda r: r["frame_windows"][0].update(page=7.0)):
            report = report_fixture(canonical=True)
            mutate(report)
            with self.assertRaises(ValueError):
                frames.validate_report(report)

    def test_incomplete_cadence_cannot_pass_as_a_thirty_second_workload(self):
        for mutation in [lambda w: w["gestures"].pop(), lambda w: w["gestures"][2].update(start_lateness_ms=300),
                         lambda w: w.update(end_elapsed_ns=w["end_elapsed_ns"] - 1_000_000_000)]:
            report = report_fixture()
            mutation(report["frame_windows"][0])
            with self.assertRaises(ValueError):
                frames.validate_report(report)

    def test_wrong_order_page_or_pixel_restoration_cannot_pass(self):
        for field, value in [("mode", "original"), ("page", 8.0), ("after_viewport_sha256", "c" * 64)]:
            report = report_fixture()
            report["frame_windows"][0][field] = value
            with self.assertRaises(ValueError):
                frames.validate_report(report)

    def test_pending_toggle_and_failed_restoration_remain_failures(self):
        for field, value in [("comparison_pending_toggle", True), ("frame_restore_required", True), ("toggle_count", 3)]:
            report = report_fixture()
            report[field] = value
            with self.assertRaises(ValueError):
                frames.validate_report(report)

    def test_suspend_inclusive_and_uptime_divergence_is_rejected(self):
        report = copy.deepcopy(report_fixture())
        report["frame_windows"][0]["end_uptime_ms"] -= 100
        with self.assertRaises(ValueError):
            frames.validate_report(report)

    def test_png_argb_hashes_are_independently_verified_and_tampering_is_rejected(self):
        from PIL import Image

        report = report_fixture(1, canonical=True)
        report["actions"] = []
        colors = {"translated": (12, 34, 56, 255), "original": (90, 80, 70, 255)}
        hashes = {mode: hashlib.sha256(bytes((color[3], *color[:3])) * 4).hexdigest()
                  for mode, color in colors.items()}
        report["canonical_baseline_sha256"] = hashes["translated"]
        report["actions"] += [
            {"action": "frame_entry_before_canonical_setup", "details": {"viewport_pixel_sha256": report["entry_viewport_sha256"]}},
            {"action": "frame_setup_repeatability", "details": {"viewport_pixel_sha256": report["canonical_baseline_sha256"]}},
        ]
        labels = [("initial", "translated")]
        for window in report["frame_windows"]:
            window["before_viewport_sha256"] = window["after_viewport_sha256"] = hashes[window["mode"]]
            report["actions"].append({"action": f'frame_{window["ordinal"]}_{window["mode"]}_natural_after',
                                      "details": {"viewport_pixel_sha256": window["natural_after_viewport_sha256"]}})
            for suffix in ("before", "after"):
                labels.append((f'frame_{window["ordinal"]}_{window["mode"]}_{suffix}', window["mode"]))
        labels.append(("frame_finally_restored", "translated"))
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for index, (label, mode) in enumerate(labels):
                name = f"{index:02d}-{label}.png"
                Image.new("RGBA", (4, 4), colors[mode]).save(directory / name)
                report["actions"].append({"action": label, "details": {
                    "screenshot": name, "hashed_rect": "[1,1][3,3]", "viewport_pixel_sha256": hashes[mode],
                    "chapter": report["initial_chapter"], "page": report["initial_page"], "rotation": 0}})
            frames.validate_report(report)
            self.assertEqual(frames.verify_screenshots(report, directory), 6)
            wrong_baseline = copy.deepcopy(report)
            wrong_baseline["canonical_baseline_sha256"] = "d" * 64
            with self.assertRaisesRegex(ValueError, "canonical baseline"):
                frames.verify_screenshots(wrong_baseline, directory)
            wrong_page = copy.deepcopy(report)
            next(action for action in wrong_page["actions"] if action["action"] == "initial")["details"]["page"] = 7.0
            with self.assertRaisesRegex(ValueError, "normalized integer"):
                frames.verify_screenshots(wrong_page, directory)
            for label in ("frame_entry_before_canonical_setup", "frame_setup_repeatability", "frame_1_translated_natural_after"):
                changed = copy.deepcopy(report)
                next(action for action in changed["actions"] if action["action"] == label)["details"]["viewport_pixel_sha256"] = "f" * 64
                with self.assertRaises(ValueError):
                    frames.verify_screenshots(changed, directory)
            altered = directory / next(action for action in report["actions"] if action["action"] == "frame_1_translated_before")["details"]["screenshot"]
            with Image.open(altered) as original:
                modified = original.copy()
            modified.putpixel((2, 2), (0, 0, 0, 255))
            modified.save(altered)
            with self.assertRaisesRegex(ValueError, "pixels disagree"):
                frames.verify_screenshots(report, directory)

    def test_screenshot_path_cannot_escape_the_extracted_run(self):
        report = report_fixture(1)
        report["actions"] = [{"action": "initial", "details": {"screenshot": "../00-initial.png"}}]
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "filename"):
                frames.verify_screenshots(report, Path(temporary))


if __name__ == "__main__":
    unittest.main()
