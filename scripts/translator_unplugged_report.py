#!/usr/bin/env python3
"""Analyze retained device-side unplugged observations; never contacts Android.

Usage: python3 scripts/translator_unplugged_report.py --directory PULLED_RUN \
    --preflight HOST_PREFLIGHT.json --output PRIVATE_REPORT.json

Exit 0: complete bounded observation; 1: incomplete/awaiting reconnect; 2: failed.
Completion is protocol coverage, not a performance, meaning, or deep-idle pass.
"""

import argparse
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import re
import tempfile
import uuid


PROTOCOL_SHA256 = "4396dc5fe19c3b1c5653388fce728b2686b22f82d9d7b4289d4734b2bb3ec8d1"
POWERS = ("AC", "USB", "Wireless", "Dock")
COMPONENTS = ("time", "battery", "memory", "thermal", "idle", "pids")
START_MARKERS = (
    "WAITING_FOR_REAL_DISCONNECT", "UNPLUGGED_READER_START",
    "NATURAL_BACKGROUND_IDLE_START", "AWAITING_MANUAL_RECONNECT",
)
END_MARKERS = ("MANUAL_RECONNECT_OBSERVATION", "COMPLETE")
FAILURE_MARKERS = ("DISCONNECT_TIMEOUT", "READER_NOT_FOREGROUND_STOPPING_RUN")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_text(path):
    return path.read_text(errors="replace") if path.is_file() else ""


def timestamp(value):
    try:
        parsed = dt.datetime.fromisoformat(value.strip())
        return parsed if parsed.utcoffset() is not None else None
    except ValueError:
        return None


def integer(value):
    return int(value.strip()) if re.fullmatch(r"\d+", value.strip()) else None


def one(pattern, value):
    match = re.search(pattern, value, re.M)
    return match.group(1) if match else None


def snapshot(directory, label):
    paths = {key: directory / f"{label}-{key}.txt" for key in COMPONENTS}
    texts = {key: read_text(path) for key, path in paths.items()}
    battery = texts["battery"]
    flags = {}
    for name in POWERS:
        values = re.findall(rf"^\s*{name} powered:\s*(\S+)\s*$", battery, re.M)
        flags[name] = values[0] == "true" if len(values) == 1 and values[0] in ("true", "false") else None
    power_state = (
        "external_power_observed" if any(v is True for v in flags.values())
        else "all_four_flags_false" if all(v is False for v in flags.values())
        else "unavailable_or_malformed"
    )
    memory = texts["memory"]
    summary = memory.split("App Summary", 1)[1] if "App Summary" in memory else ""
    metrics = {}
    for key, pattern in {
        "pss_kib": r"TOTAL PSS:\s*(\d+)",
        "rss_kib": r"TOTAL RSS:\s*(\d+)",
        "java_pss_kib": r"^\s*Java Heap:\s*(\d+)",
        "native_pss_kib": r"^\s*Native Heap:\s*(\d+)",
        "graphics_pss_kib": r"^\s*Graphics:\s*(\d+)",
    }.items():
        value = one(pattern, summary)
        metrics[key] = int(value) if value is not None else None
    # Android meminfo's native table has Pss, private dirty/clean, swap, Rss,
    # then heap size/allocated/free. Parse only the explicitly known header.
    native = one(r"^\s*Native Heap\s+([\d\s]+)$", memory)
    columns = native.split() if native else []
    known_header = re.search(r"Pss\s+Private\s+Private\s+SwapPss\s+Rss\s+Heap\s+Heap\s+Heap", memory)
    metrics["native_allocated_kib"] = int(columns[6]) if known_header and len(columns) == 8 else None
    realtime = one(r"^Uptime:\s*\d+\s+Realtime:\s*(\d+)", memory)
    thermal = texts["thermal"]
    # Missing HAL section remains unknown; cached callbacks must never stand in.
    current = thermal.split("Current temperatures from HAL:", 1)[1] if "Current temperatures from HAL:" in thermal else ""
    current = current.split("Current cooling devices", 1)[0]
    temperatures = []
    for value, kind, name in re.findall(r"Temperature\{mValue=([^,]+), mType=(\d+), mName=([^,}]+)", current):
        try:
            number = float(value)
        except ValueError:
            continue
        if math.isfinite(number):
            temperatures.append({"celsius": number, "type": int(kind), "name": name})
    pids_text = texts["pids"].strip()
    pids = [int(v) for v in pids_text.split()] if re.fullmatch(r"\d+(?:\s+\d+)*", pids_text) else []
    return {
        "label": label, "time_utc": texts["time"].strip() or None,
        "missing_components": [key for key, path in paths.items() if not path.is_file()],
        "power_flags": flags, "power_state": power_state,
        "battery_status_raw": integer(one(r"^\s*status:\s*(\d+)", battery) or ""),
        "battery_temperature_tenths_celsius": integer(one(r"^\s*temperature:\s*(\d+)", battery) or ""),
        "pids": pids, "pid_text_valid": not pids_text or bool(pids),
        "meminfo_pid": integer(one(r"MEMINFO in pid (\d+) \[app\.mihon\]", memory) or ""),
        "meminfo_elapsed_realtime_millis": int(realtime) if realtime else None,
        "memory": metrics,
        "thermal_global_status": integer(one(r"^Thermal Status:\s*(\d+)", thermal) or ""),
        "current_hal_section_present": "Current temperatures from HAL:" in thermal,
        "current_hal_temperatures": temperatures,
        "current_hal_max_celsius": {
            name: max((r["celsius"] for r in temperatures if r["type"] == kind), default=None)
            for name, kind in (("cpu", 0), ("gpu", 1), ("battery", 2), ("skin", 3))
        },
        "idle_snapshot": {key: one(rf"(?:^|\s){key}=(\S+)", texts["idle"]) for key in ("mState", "mLightState", "mScreenOn", "mCharging")},
    }


def analyze(directory, preflight_path, output=None):
    directory, preflight_path = Path(directory), Path(preflight_path)
    preflight = json.loads(preflight_path.read_text())
    if not isinstance(preflight, dict):
        raise ValueError("Preflight must be a JSON object")
    errors, missing, notes = [], [], []
    supported = preflight.get("script_sha256") == PROTOCOL_SHA256
    if not supported:
        notes.append("Preflight belongs to an older or unrecognized protocol; it cannot prove corrected finish semantics.")
    device_dir = preflight.get("device_directory", preflight.get("device_run"))
    if not device_dir or Path(device_dir).name != directory.name:
        errors.append("Preflight device-directory identity does not match the pulled directory.")
    package = preflight.get("subject", preflight.get("package"))
    if package != "app.mihon":
        errors.append("Preflight does not identify app.mihon.")
    apk = preflight.get("subject_apk_sha256", preflight.get("apk_sha256"))
    if not isinstance(apk, str) or not re.fullmatch(r"[0-9a-f]{64}", apk):
        errors.append("Preflight lacks a valid subject APK SHA-256.")
    session = read_text(directory / "session.txt").splitlines()
    gestures = [integer(line.removeprefix("READER_GESTURES=")) for line in session if line.startswith("READER_GESTURES=")]
    gesture_count = gestures[0] if len(gestures) == 1 else None
    if len(gestures) > 1 or (gestures and (gesture_count is None or gesture_count <= 0)):
        errors.append("Reader gesture count is invalid or duplicated.")
    for marker in FAILURE_MARKERS:
        if marker in session:
            errors.append(f"Device protocol recorded {marker}.")
    completed = (directory / "complete").is_file()
    awaiting = (directory / "awaiting-reconnect").is_file()
    expected_markers = START_MARKERS + END_MARKERS if completed else START_MARKERS if awaiting else ()
    positions = []
    for marker in expected_markers:
        indexes = [i for i, line in enumerate(session) if line == marker]
        if len(indexes) != 1:
            missing.append(f"Exactly one session marker {marker}")
        else:
            positions.append(indexes[0])
    if positions != sorted(positions):
        errors.append("Protocol session markers occur in the wrong order.")
    if completed and not supported:
        errors.append("A complete marker cannot promote an unrecognized/old protocol.")
    if completed and not awaiting:
        missing.append("awaiting-reconnect checkpoint before completion")
    boot_id = read_text(directory / "boot-id.txt").strip()
    try:
        uuid.UUID(boot_id)
        boot_valid = True
    except ValueError:
        boot_valid = False
    if (awaiting or completed) and not boot_valid:
        missing.append("Valid start boot ID")
    start_uptime = integer(read_text(directory / "natural-idle-start-uptime.txt"))
    elapsed = integer(read_text(directory / "natural-idle-elapsed-seconds.txt"))
    page_size = integer(read_text(directory / "page-size.txt"))
    if (awaiting or completed) and start_uptime is None:
        missing.append("Natural-idle start boot uptime")
    if completed and (elapsed is None or elapsed < 600):
        errors.append("Completed protocol requires at least 600 recorded suspend-inclusive elapsed seconds.")
    if (awaiting or completed) and page_size not in (4096, 16384, 65536):
        missing.append("Valid recorded kernel page size")
    labels = {p.name.removesuffix("-time.txt") for p in directory.glob("*-time.txt")}
    expected_labels = ["attached", "unplugged-before"]
    if gesture_count:
        expected_labels += [f"reader-{n}" for n in range(12, gesture_count + 1, 12)] + ["unplugged-after"]
    if completed:
        expected_labels += ["reconnected-before-input", "resumed"]
    if awaiting or completed:
        if not gesture_count:
            missing.append("Completed reader gesture count")
        for label in expected_labels:
            if label not in labels:
                missing.append(f"Snapshot {label}")
    def order(label):
        if label == "attached": return (0, 0)
        if label == "unplugged-before": return (1, 0)
        if re.fullmatch(r"reader-\d+", label): return (2, int(label.split("-")[1]))
        return ({"unplugged-after": 3, "reconnected-before-input": 4, "resumed": 5}.get(label, 6), 0)
    rows = [snapshot(directory, label) for label in sorted(labels, key=order)]
    by_label = {row["label"]: row for row in rows}
    dates = []
    for row in rows:
        date = timestamp(row["time_utc"] or "")
        if date is None:
            errors.append(f"Invalid timestamp for {row['label']}.")
        else:
            dates.append(date)
        if row["missing_components"]:
            missing.append(f"{row['label']} missing components: {', '.join(row['missing_components'])}")
        if not row["pid_text_valid"]:
            errors.append(f"Malformed PID snapshot {row['label']}.")
    if dates != sorted(dates):
        errors.append("Snapshot timestamps run backwards in protocol order.")
    reader = [row for row in rows if row["label"] in ("unplugged-before", "unplugged-after") or re.fullmatch(r"reader-\d+", row["label"])]
    all_unplugged = bool(reader) and all(row["power_state"] == "all_four_flags_false" for row in reader)
    if any(row["power_state"] == "external_power_observed" for row in reader):
        errors.append("At least one intended unplugged-reader sample records external power.")
    if any(row["power_state"] == "unavailable_or_malformed" for row in reader):
        missing.append("All four power flags in every unplugged-reader snapshot")
    def interval(first, last):
        a = timestamp(by_label.get(first, {}).get("time_utc") or "")
        b = timestamp(by_label.get(last, {}).get("time_utc") or "")
        return (b - a).total_seconds() if a and b else None
    reader_span = interval("unplugged-before", "unplugged-after")
    if (awaiting or completed) and (reader_span is None or reader_span < 180):
        errors.append("Reader snapshot boundaries do not establish the requested 180-second interval.")
    marker_dates = {}
    for name in ("natural-idle-start.txt", "natural-idle-end.txt", "awaiting-reconnect", "complete"):
        raw = read_text(directory / name).strip()
        marker_dates[name] = raw or None
        required = name in ("natural-idle-start.txt", "awaiting-reconnect") if awaiting and not completed else completed
        if required and timestamp(raw) is None:
            missing.append(f"Valid timestamp in {name}")
    start = timestamp(marker_dates["natural-idle-start.txt"] or "")
    end = timestamp(marker_dates["natural-idle-end.txt"] or "")
    wall_elapsed = (end - start).total_seconds() if start and end else None
    if wall_elapsed is not None and wall_elapsed < 0:
        errors.append("Natural-idle wall-clock timestamps run backwards.")
    timeline = [
        timestamp(by_label.get("unplugged-after", {}).get("time_utc") or ""), start,
        timestamp(marker_dates["awaiting-reconnect"] or ""), end,
        timestamp(by_label.get("reconnected-before-input", {}).get("time_utc") or ""),
        timestamp(by_label.get("resumed", {}).get("time_utc") or ""),
        timestamp(marker_dates["complete"] or ""),
    ]
    observed_timeline = [value for value in timeline if value is not None]
    if observed_timeline != sorted(observed_timeline):
        errors.append("Completion/checkpoint timestamps contradict snapshot ordering.")
    reconnect_uptime = by_label.get("reconnected-before-input", {}).get("meminfo_elapsed_realtime_millis")
    if completed and reconnect_uptime is not None and start_uptime is not None and elapsed is not None:
        if reconnect_uptime // 1000 < start_uptime + elapsed:
            errors.append("Recorded idle duration exceeds the reconnect snapshot's boot elapsed time.")
    if elapsed is not None and wall_elapsed is not None and abs(elapsed - wall_elapsed) > 5:
        notes.append("Idle wall-clock and boot elapsed intervals differ by more than five seconds; use the recorded boot interval and investigate clock changes.")
    relaunch = read_text(directory / "relaunch.txt")
    if completed and not relaunch.strip():
        missing.append("Recorded explicit relaunch result")
    if re.search(r"(?:^|\n)(?:Error|Exception)|unable to resolve", relaunch, re.I):
        errors.append("Explicit relaunch reports an error.")
    if completed and not by_label.get("resumed", {}).get("pids"):
        missing.append("Observed app PID after explicit relaunch")
    finishing = (directory / ".finishing").exists() or ("MANUAL_RECONNECT_OBSERVATION" in session and not completed)
    if finishing:
        missing.append("Finish stage has not been fully closed")
    status = "failed" if errors or (completed and missing) else "complete_bounded_observation" if completed else "awaiting_reconnect" if awaiting and supported and not missing and not finishing else "incomplete"
    pids = sorted({pid for row in rows for pid in row["pids"]})
    first, last = by_label.get("unplugged-before", {}), by_label.get("unplugged-after", {})
    delta = {}
    for key in first.get("memory", {}):
        a, b = first["memory"][key], last.get("memory", {}).get(key)
        delta[key] = {"first": a, "last": b, "delta": b - a if a is not None and b is not None else None}
    output = Path(output).resolve() if output else None
    return {
        "schema": 1, "status": status, "errors": errors, "missing_evidence": missing, "notes": notes,
        "absent_completion_artifacts": [name for name in (
            "awaiting-reconnect", "boot-id.txt", "natural-idle-start-uptime.txt",
            "natural-idle-end.txt", "natural-idle-elapsed-seconds.txt", "complete",
            "reconnected-before-input-time.txt", "resumed-time.txt", "relaunch.txt",
        ) if not (directory / name).is_file()],
        "run_directory": str(directory.resolve()), "package": package, "subject_apk_sha256_attested_by_preflight": apk,
        "device_fingerprint_recorded": read_text(directory / "fingerprint.txt").strip() or None,
        "kernel_page_size_recorded": page_size,
        "preflight": {key: preflight[key] for key in ("backend", "chapter", "initial_page", "automatic_translation", "autoTranslate", "chapters_ahead", "chaptersAhead", "network") if key in preflight},
        "protocol": {"preflight_script_sha256": preflight.get("script_sha256"), "supported_finish_protocol": supported, "complete_file_present": completed, "awaiting_reconnect_file_present": awaiting, "session_markers": [line for line in session if line in START_MARKERS + END_MARKERS + FAILURE_MARKERS or line.startswith("READER_GESTURES=")], "marker_timestamps": marker_dates, "start_boot_id": boot_id or None, "same_boot_evidence": "verified_protocol_finish_guard_succeeded; no second boot ID retained" if supported and completed and boot_valid and not errors and not missing else "not_established", "natural_idle_start_uptime_seconds": start_uptime, "natural_idle_elapsed_seconds_recorded": elapsed, "natural_idle_wall_clock_seconds": wall_elapsed},
        "reader": {"gesture_count_recorded": gesture_count, "snapshot_boundary_seconds": reader_span, "unplugged_sample_count": len(reader), "all_observed_reader_power_flags_false": all_unplugged, "memory_first_to_last": delta},
        "process": {"observed_pids": pids, "one_pid_across_nonempty_snapshots": len(pids) == 1, "pid_changed_between_snapshots": len(pids) > 1, "cause_of_pid_change": "not inferred", "reconnected_before_input_pids": by_label.get("reconnected-before-input", {}).get("pids"), "resumed_pids": by_label.get("resumed", {}).get("pids")},
        "snapshots": rows,
        "limits": [
            "This report analyzes retained files only. It performs no ADB, app input, provider call or device setting change.",
            "Complete means recorded protocol stages are present and consistent; it is not functional, performance, deep-idle or meaning acceptance.",
            "Four false power flags establish only sampled states. They do not prove uninterrupted external-power disconnection between samples or throughout the unsampled resting interval.",
            "The same-boot conclusion relies on the identified script's finish guard. Only the initial boot ID was retained; no independent end-boot comparison is invented.",
            "At least 600 recorded suspend-inclusive boot seconds establishes the resting interval. It does not prove Doze, deep idle, process eviction or continuous screen-off.",
            "USB reconnection may wake the phone before reconnected-before-input is sampled. That snapshot precedes explicit wake/relaunch, not the reconnection itself.",
            "Resumed is measured after explicit wake and MainActivity launch. An observed PID change does not identify eviction, crash or process-death cause; unchanged PID does not prove absence of interruption.",
            "Memory values are KiB snapshots, not retained allocation attribution or GPU totals. Only the current HAL temperature section is parsed; unavailable readings remain null.",
            "Reader duration uses snapshot UTC boundaries and includes snapshot overhead; gestures are the device script's completed-command count, not verified rendered frames.",
            "No frame rate, latency percentile, billed cost, crash-free guarantee or continuous thermal/power behavior is inferred. Preflight assertions are host attestations, not independently queried app settings.",
        ],
        "input_sha256": {str(preflight_path.resolve()): sha256(preflight_path), **{path.name: sha256(path) for path in sorted(directory.iterdir()) if path.is_file() and not path.is_symlink() and path.resolve() != output}},
    }


def write_private(path, report):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor, temp = tempfile.mkstemp(prefix=".unplugged-report-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w") as stream:
            json.dump(report, stream, indent=2, ensure_ascii=False, allow_nan=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--preflight", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.resolve().is_relative_to(args.directory.resolve()) or args.output.resolve() == args.preflight.resolve():
        parser.error("Output must be outside the input capture directory and must not replace the preflight")
    try:
        report = analyze(args.directory, args.preflight, args.output)
    except (OSError, ValueError, TypeError) as error:
        report = {"schema": 1, "status": "failed", "errors": [str(error)], "limits": ["Input could not be reconciled; no acceptance claim."]}
    write_private(args.output, report)
    print(json.dumps({"status": report["status"], "output": str(args.output), "sha256": sha256(args.output)}))
    return 0 if report["status"] == "complete_bounded_observation" else 2 if report["status"] == "failed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
