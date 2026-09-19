#!/usr/bin/env python3
"""Offline validation of paired input windows; never calls adb, providers or Perfetto."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile


def require(condition, message):
    if not condition:
        raise ValueError(message)


CANONICAL_POLICY = "adjacent_cached_page_then_selected_page"
INTERIOR_POLICY = "adjacent_cached_page_then_fixed_interior_drag_v1"
INTERIOR_TARGET = "repeatable_interior_anchor"
INTERIOR_DESCRIPTOR = {"protocol": "fixed_interior_drag_v1", "requested_drag_pixels": 100,
                       "direction": "up", "motion_ms": 420, "release_hold_ms": 300, "expected_page": 7}
LEGACY_POLICY = "legacy_natural_gesture_return"
CANONICAL_REPORT_FIELDS = {"restoration_target", "canonical_setup_verified", "canonical_baseline_sha256", "entry_viewport_sha256", "frame_anchor"}
CANONICAL_WINDOW_FIELDS = {"natural_after_viewport_sha256", "natural_after_elapsed_ns", "reset_after_start_elapsed_ns",
                           "reset_after_end_elapsed_ns", "canonical_reset_verified", "frame_anchor"}


def anchor_descriptor(pixels, backend):
    require(type(pixels) is int and pixels in (0, 100), "Only explicit 0 or 100 anchor pixels are supported")
    if pixels == 0:
        return None
    require(backend == "webgpu", "Interior anchor is restricted to WebGPU")
    return dict(INTERIOR_DESCRIPTOR)


def validate_anchor(observed, pixels, backend):
    expected = anchor_descriptor(pixels, backend)
    require(observed == expected, "Window anchor differs from the declared capture protocol")
    return expected


def valid_hash(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def viewport_policy(report):
    policy = report.get("viewport_reset_policy")
    if policy is None:
        require(not any(field in report for field in CANONICAL_REPORT_FIELDS) and
                not any(any(field in row for field in CANONICAL_WINDOW_FIELDS) for row in report.get("frame_windows", [])),
                "Canonical normalization metadata has no explicit policy")
        return LEGACY_POLICY
    require(policy in (CANONICAL_POLICY, INTERIOR_POLICY), "Unsupported viewport reset policy")
    interior = policy == INTERIOR_POLICY
    validate_anchor(report.get("frame_anchor"), 100 if interior else 0, report.get("backend"))
    if interior:
        require(report.get("initial_chapter") == "002 - Twelve-page corpus" and report.get("initial_page") == 7,
                "Interior anchor changed its controlled chapter or page")
    require(report.get("restoration_target") == (INTERIOR_TARGET if interior else "canonical_page_start") and report.get("canonical_setup_verified") is True,
            "Canonical setup/restoration target was not verified")
    require(valid_hash(report.get("canonical_baseline_sha256")) and valid_hash(report.get("entry_viewport_sha256")),
            "Missing canonical baseline or pre-setup entry hash")
    require(type(report.get("initial_page")) is int and report["initial_page"] > 0,
            "Canonical page identity must be a positive integer")
    if "entry_pixels_match_canonical" in report:
        require(report["entry_pixels_match_canonical"] is
                (report["entry_viewport_sha256"] == report["canonical_baseline_sha256"]),
                "Entry/canonical equality metadata disagrees with its hashes")
    return policy


def validate_report(report):
    require(report.get("plan") == "framePairs", "Not a frame-pairs report")
    require(report.get("status") == "passed_action_assertions", "Harness did not pass")
    require(report.get("frame_restore_required") is False, "Reader restoration is incomplete")
    require(report.get("comparison_pending_toggle", False) is False, "Comparison toggle completion is unknown")
    require(report.get("initial_mode") in ("original", "translated"), "Missing attested starting mode")
    require(report.get("backend") in ("classic", "webgpu"), "Missing backend")
    policy = viewport_policy(report)
    pairs = report.get("frame_pairs_requested")
    require(type(pairs) is int and 1 <= pairs <= 3, "Invalid pair count")
    require(report.get("frame_pairs_completed") == pairs, "Incomplete pairs")
    require(report.get("toggle_count", -1) >= 0 and report["toggle_count"] % 2 == 0, "Comparison parity not restored")
    windows = report.get("frame_windows", [])
    require(len(windows) == pairs * 2, "Missing or extra windows")
    hashes = {}
    previous_end = None
    output = []
    for ordinal, window in enumerate(windows, 1):
        pair = (ordinal + 1) // 2
        first = "translated" if pair % 2 else "original"
        mode = first if ordinal % 2 else ("original" if first == "translated" else "translated")
        require((window.get("ordinal"), window.get("pair"), window.get("mode")) == (ordinal, pair, mode), "Pair order changed")
        require(window.get("status") == "boundary_assertions_passed", "Window boundary assertions failed")
        require(window.get("chapter") == report.get("initial_chapter") and window.get("page") == report.get("initial_page"), "Window changed page")
        if policy in (CANONICAL_POLICY, INTERIOR_POLICY):
            require(type(window.get("page")) is int, "Window page is not a normalized integer identity")
        require(window.get("backend") == report["backend"], "Window changed backend")
        validate_anchor(window.get("frame_anchor"), 100 if policy == INTERIOR_POLICY else 0, report["backend"])
        require((window.get("duration_ms"), window.get("cadence_ms"), window.get("gesture_duration_ms"), window.get("distance_px")) == (30000, 1000, 500, 500), "Gesture protocol changed")
        start, end = window["start_elapsed_ns"], window["end_elapsed_ns"]
        require(30_000_000_000 <= end - start < 30_250_000_000, "Incomplete or overlong input window")
        require(previous_end is None or start > previous_end, "Windows overlap or run backwards")
        previous_end = end
        normalization = {}
        if policy in (CANONICAL_POLICY, INTERIOR_POLICY):
            natural = window.get("natural_after_elapsed_ns")
            reset_start, reset_end = window.get("reset_after_start_elapsed_ns"), window.get("reset_after_end_elapsed_ns")
            require(window.get("canonical_reset_verified") is True, "Canonical post-window reset was not verified")
            require(valid_hash(window.get("natural_after_viewport_sha256")), "Missing independent natural-after viewport hash")
            require(all(type(value) is int and value > 0 for value in (natural, reset_start, reset_end)) and
                    end < natural <= reset_start < reset_end,
                    "Canonical reset overlaps the timed window or natural-after observation")
            previous_end = reset_end
            normalization = {"natural_after_viewport_sha256": window["natural_after_viewport_sha256"],
                             "natural_after_elapsed_ns": natural, "reset_after_start_elapsed_ns": reset_start,
                             "reset_after_end_elapsed_ns": reset_end,
                             "restoration_target": report["restoration_target"]}
        require(abs((end - start) / 1_000_000 - (window["end_uptime_ms"] - window["start_uptime_ms"])) <= 3, "Suspension or inconsistent device clocks")
        gestures = window.get("gestures", [])
        require(len(gestures) == window.get("gesture_count_expected") == 30, "Incomplete gesture set")
        for index, gesture in enumerate(gestures):
            require(gesture.get("index") == index and gesture.get("direction") == ("up" if index % 2 == 0 else "down"), "Gesture order changed")
            target = window["start_uptime_ms"] + index * 1000
            late = gesture["start_uptime_ms"] - target
            require(gesture["scheduled_uptime_ms"] == target and gesture["start_lateness_ms"] == late and 0 <= late < 250, "Gesture missed fixed cadence")
            require(500 <= gesture["end_uptime_ms"] - gesture["start_uptime_ms"] < 750, "Gesture duration outside bounded protocol")
            require(start <= gesture["start_elapsed_ns"] < gesture["end_elapsed_ns"] <= end, "Gesture lies outside its window")
        before = window.get("before_viewport_sha256", "")
        require(re.fullmatch(r"[0-9a-f]{64}", before) is not None and window.get("after_viewport_sha256") == before, "Viewport not restored")
        require(mode not in hashes or hashes[mode] == before, "Same mode started from a different viewport")
        hashes[mode] = before
        output.append({"ordinal": ordinal, "pair": pair, "mode": mode, "backend": window["backend"],
                       "start_elapsed_ns": start, "end_elapsed_ns": end,
                       "duration_seconds": (end - start) / 1e9,
                       "max_gesture_start_lateness_ms": max(g["start_lateness_ms"] for g in gestures),
                       "viewport_reset_policy": policy, **normalization})
    require(hashes.get("original") != hashes.get("translated"), "Original/translated pixel states did not differ")
    if policy in (CANONICAL_POLICY, INTERIOR_POLICY):
        require(hashes.get(report["initial_mode"]) == report["canonical_baseline_sha256"],
                "Starting comparison mode differs from the verified canonical baseline")
    return output


def verify_screenshots(report, directory):
    from PIL import Image
    policy = viewport_policy(report)
    screenshots = {}
    hash_observations = {}
    rotation = None
    for action in report.get("actions", []):
        details = action.get("details", {})
        if policy in (CANONICAL_POLICY, INTERIOR_POLICY) and "viewport_pixel_sha256" in details:
            require(valid_hash(details["viewport_pixel_sha256"]), "Invalid observed viewport hash")
            require(action["action"] not in hash_observations, "Duplicate viewport observation")
            hash_observations[action["action"]] = details["viewport_pixel_sha256"]
        if "screenshot" not in details:
            continue
        name = details["screenshot"]
        require(re.fullmatch(r"[0-9]{2}-[A-Za-z0-9_]+\.png", name) is not None, "Unexpected screenshot filename")
        path = directory / name
        require(path.resolve().parent == directory.resolve() and path.stat().st_size <= 20 * 1024 * 1024, "Invalid screenshot path or size")
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", details["hashed_rect"])
        require(match is not None, "Invalid viewport rectangle")
        box = tuple(map(int, match.groups()))
        with Image.open(path) as source:
            require(source.width * source.height <= 50_000_000, "Screenshot exceeds decoded limit")
            require(0 <= box[0] < box[2] <= source.width and 0 <= box[1] < box[3] <= source.height, "Crop outside screenshot")
            r, g, b, a = source.convert("RGBA").crop(box).split()
            argb = Image.merge("RGBA", (a, r, g, b)).tobytes()
        digest = hashlib.sha256(argb).hexdigest()
        require(digest == details["viewport_pixel_sha256"], "Screenshot pixels disagree with report")
        require(details["chapter"] == report["initial_chapter"] and details["page"] == report["initial_page"], "Screenshot changed page")
        if policy in (CANONICAL_POLICY, INTERIOR_POLICY):
            require(type(details.get("page")) is int, "Screenshot page is not a normalized integer identity")
        if rotation is None:
            rotation = details["rotation"]
        require(details["rotation"] == rotation, "Screenshot changed orientation")
        require(action["action"] not in screenshots, "Duplicate screenshot action")
        screenshots[action["action"]] = digest
    require(len(screenshots) == 2 + 2 * len(report["frame_windows"]) + (2 if policy == INTERIOR_POLICY else 0), "Missing or extra screenshots")
    if policy == INTERIOR_POLICY:
        require(screenshots.get("anchor_setup_first_interior") == report["canonical_baseline_sha256"] and
                screenshots.get("anchor_setup_first_page_start") != report["canonical_baseline_sha256"],
                "Interior anchor lacks changed source pixels or its pinned starting image")
    require(screenshots.get("initial") == screenshots.get("frame_finally_restored"), "Initial pixels not restored")
    if policy in (CANONICAL_POLICY, INTERIOR_POLICY):
        require(screenshots.get("initial") == report["canonical_baseline_sha256"],
                "Initial/final PNGs differ from the canonical baseline")
        require(hash_observations.get("frame_entry_before_canonical_setup") == report["entry_viewport_sha256"] and
                hash_observations.get("frame_setup_repeatability") == report["canonical_baseline_sha256"],
                "Missing matching entry and repeated-setup hash observations")
    for window in report["frame_windows"]:
        label = f'frame_{window["ordinal"]}_{window["mode"]}'
        require(screenshots.get(label + "_before") == window["before_viewport_sha256"], "Missing before screenshot")
        require(screenshots.get(label + "_after") == window["after_viewport_sha256"], "Missing after screenshot")
        if policy in (CANONICAL_POLICY, INTERIOR_POLICY):
            require(hash_observations.get(label + "_natural_after") == window["natural_after_viewport_sha256"],
                    "Natural-after window hash differs from its observed checkpoint")
    return len(screenshots)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = json.loads(args.report.read_text())
    windows = validate_report(report)
    count = verify_screenshots(report, args.report.parent)
    result = {"schema": 1, "status": "paired_input_and_pixels_verified", "windows": windows,
              "screenshots_verified": count, "report_sha256": hashlib.sha256(args.report.read_bytes()).hexdigest(),
              "viewport_reset_policy": viewport_policy(report),
              "limits": ["Input timing and pixels are not frame-performance acceptance.",
                         "Initial overlay mode is host-attested, then tracked by toggle parity.",
                         "Bind each window to its actual trace clock and filter FrameTimeline rows to that interval.",
                         "Only chapter/page boundaries are observed; hidden intermediate positions are unavailable."]}
    if viewport_policy(report) in (CANONICAL_POLICY, INTERIOR_POLICY):
        result.update(restoration_target=report["restoration_target"], canonical_baseline_sha256=report["canonical_baseline_sha256"],
                      entry_viewport_sha256=report["entry_viewport_sha256"])
        if viewport_policy(report) == INTERIOR_POLICY:
            result["frame_anchor"] = report["frame_anchor"]
        result["limits"].append("Reset navigation occurs outside timed windows. Natural-after pixels are hash-only evidence; arbitrary entry-offset restoration is not claimed.")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    require(not args.output.exists(), "Refusing to replace existing evidence")
    fd, temporary = tempfile.mkstemp(prefix=".frame-pairs-", dir=args.output.parent)
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump(result, stream, indent=2)
            stream.write("\n")
        os.replace(temporary, args.output)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    print(json.dumps({"output": str(args.output), "windows": len(windows), "screenshots_verified": count}))


if __name__ == "__main__":
    main()
