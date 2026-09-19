#!/usr/bin/env python3
"""Capture a bounded, private Perfetto trace for one named translator flow."""

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid


MAX_TRACE_BYTES = 512 * 1024 * 1024


def configuration(package, duration, profile="frames"):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", package):
        raise ValueError("Invalid Android package")
    if type(duration) is not int or not 1 <= duration <= 60:
        raise ValueError("Trace duration must be 1–60 seconds")
    if profile not in ("frames", "native-heap"):
        raise ValueError("Unknown capture profile")
    if profile == "native-heap":
        # https://perfetto.dev/docs/data-sources/native-heap-profiler (2026-09-06)
        # Nonblocking sampling; no forced GC, process restart, or SELinux changes.
        return f'''duration_ms: {duration * 1000}
write_into_file: true
file_write_period_ms: 2000
flush_period_ms: 5000
max_file_size_bytes: {MAX_TRACE_BYTES}
buffers {{ size_kb: 32768 fill_policy: RING_BUFFER }}
data_sources {{ config {{ name: "android.heapprofd" target_buffer: 0
  heapprofd_config {{
    sampling_interval_bytes: 16384
    process_cmdline: "{package}"
    shmem_size_bytes: 8388608
    block_client: false
    continuous_dump_config {{ dump_interval_ms: 10000 }}
  }}
}} }}
data_sources {{ config {{ name: "linux.process_stats" target_buffer: 0
  process_stats_config {{ scan_all_processes_on_start: true proc_stats_poll_ms: 1000 }}
}} }}
data_sources {{ config {{ name: "android.packages_list" target_buffer: 0 }} }}
'''
    return f'''duration_ms: {duration * 1000}
write_into_file: true
file_write_period_ms: 2000
flush_period_ms: 5000
max_file_size_bytes: {MAX_TRACE_BYTES}
buffers {{ size_kb: 32768 fill_policy: RING_BUFFER }}
buffers {{ size_kb: 8192 fill_policy: RING_BUFFER }}
data_sources {{ config {{ name: "linux.ftrace" target_buffer: 0
  ftrace_config {{
    ftrace_events: "sched/sched_switch"
    ftrace_events: "sched/sched_waking"
    ftrace_events: "power/cpu_frequency"
    ftrace_events: "power/cpu_idle"
    ftrace_events: "gpu_mem/gpu_mem_total"
    ftrace_events: "power/gpu_frequency"
    atrace_categories: "gfx"
    atrace_categories: "view"
    atrace_categories: "wm"
    atrace_categories: "am"
    atrace_apps: "{package}"
  }}
}} }}
data_sources {{ config {{ name: "linux.process_stats" target_buffer: 1
  process_stats_config {{ scan_all_processes_on_start: true proc_stats_poll_ms: 1000 }}
}} }}
data_sources {{ config {{ name: "android.surfaceflinger.frametimeline" target_buffer: 1 }} }}
data_sources {{ config {{ name: "android.gpu.memory" target_buffer: 1 }} }}
data_sources {{ config {{ name: "android.packages_list" target_buffer: 1 }} }}
'''


def inspect_coverage(processor, trace, duration, package, evidence):
    """Read retained coverage locally; never equate duration with a performance pass."""
    configuration(package, duration)  # Validate before interpolating the package into SQL.
    processor = Path(processor).resolve()
    with processor.open("rb") as stream:
        python_script = b"python" in stream.readline(200)
    command = ([sys.executable, str(processor)] if python_script else [str(processor)])
    query = f"""SELECT (end_ts-start_ts)/1e9 AS retained_seconds,
      (SELECT count(*) FROM actual_frame_timeline_slice) AS frame_timeline_rows,
      (SELECT count(*) FROM process WHERE name='{package}') AS named_package_processes,
      (SELECT count(*) FROM actual_frame_timeline_slice a
        JOIN process p USING(upid) WHERE p.name='{package}') AS package_frame_rows,
      (SELECT count(*) FROM stats WHERE severity='data_loss' AND value>0) AS data_loss_stat_count,
      (SELECT coalesce(sum(value),0) FROM stats WHERE name='ftrace_setup_errors') AS ftrace_setup_errors,
      (SELECT coalesce(sum(value),0) FROM stats WHERE name='traced_chunks_discarded') AS discarded_chunks
    FROM trace_bounds;"""
    (evidence / "coverage.sql").write_text(query + "\n")
    result = subprocess.run(command + ["query", str(trace.resolve()), query], capture_output=True, timeout=60)
    (evidence / "coverage.stdout").write_bytes(result.stdout)
    (evidence / "coverage.stderr").write_bytes(result.stderr)
    if result.returncode:
        return {"status": "analysis_failed", "offline_analysis_required": True}
    import csv
    import io
    rows = list(csv.DictReader(io.StringIO(result.stdout.decode())))
    observed = {key: float(value) if key == "retained_seconds" else int(value) for key, value in rows[0].items()}
    retained = observed["retained_seconds"]
    if not isinstance(retained, (int, float)) or retained < 0:
        raise ValueError("Invalid retained trace duration")
    # Small start/stop differences are normal. This is a truncation alarm, not acceptance.
    observed["requested_seconds"] = duration
    observed["duration_shortfall_tolerance_seconds"] = 0.5
    observed["status"] = "short_retained_window" if retained < duration - 0.5 else "duration_observed"
    observed["offline_analysis_required"] = True
    return observed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--duration", type=int, default=30)
    parser.add_argument("--profile", choices=("frames", "native-heap"), default="frames")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--trace-processor", type=Path, help="Optional local Perfetto processor for retained coverage diagnostics")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]*", args.serial):
        parser.error("Invalid adb serial")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", args.scenario):
        parser.error("Invalid scenario label")
    try:
        config = configuration(args.package, args.duration, args.profile)
    except ValueError as error:
        parser.error(str(error))
    os.umask(0o077)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    identifier = f"{stamp}-{args.scenario}-{uuid.uuid4().hex[:8]}"
    run = args.output / identifier
    run.mkdir(parents=True, exist_ok=False)
    device_path = f"/data/misc/perfetto-traces/mihon-{identifier}.pftrace"
    prefix = [args.adb, "-s", args.serial]
    manifest = {
        "format": 2, "serial": args.serial, "package": args.package,
        "scenario": args.scenario, "duration_seconds": args.duration, "profile": args.profile,
        "device_trace_path": device_path, "commands": [], "completed": False,
        "capture_status": "preflight", "device_trace_may_remain": False,
        "device_trace_cleanup": {"attempted": False, "removed": False},
        "recording_limits": {"max_file_size_bytes": MAX_TRACE_BYTES, "file_write_period_ms": 2000,
                             "flush_period_ms": 5000,
                             "buffer_sizes_kib": [32768, 8192] if args.profile == "frames" else [32768]},
        "coverage": {"status": "not_checked", "offline_analysis_required": True},
        "limitations": [
            "Trace creation alone is not a performance pass.",
            "Check actual FrameTimeline/GPU tracks and trace loss before reporting measurements.",
            "Trace contains system scheduling and package identities; retain privately.",
            "No app actions, device settings, log buffers or frame counters are changed by this script.",
            "A failed pull preserves the owned device trace for recovery; check device_trace_may_remain.",
            "Streaming has measurement overhead; a size limit may end recording early.",
            "Native heap sampling requires a profileable/debuggable subject; it is not a frame benchmark or total-memory inventory.",
        ],
    }

    def save():
        (run / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    def call(label, arguments, timeout=10, data=None):
        started = dt.datetime.now(dt.timezone.utc).isoformat()
        try:
            result = subprocess.run(prefix + arguments, input=data, capture_output=True, timeout=timeout)
            code, stdout, stderr = result.returncode, result.stdout, result.stderr
            termination = "exit"
        except subprocess.TimeoutExpired as error:
            code, stdout, stderr = 124, error.stdout or b"", error.stderr or b"Timeout"
            termination = "timeout"
        except OSError as error:
            code, stdout, stderr = 127, b"", str(error).encode()
            termination = "os_error"
        (run / f"{label}.stdout").write_bytes(stdout)
        (run / f"{label}.stderr").write_bytes(stderr)
        manifest["commands"].append({
            "label": label, "arguments": arguments, "exit_code": code,
            "termination": termination,
            "started_utc": started, "finished_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        })
        save()
        return code, stdout

    try:
        save()
        code, state = call("adb-state", ["get-state"])
        if code or state.strip() != b"device":
            manifest["capture_status"] = "target_unavailable"
            print(f"Target unavailable; evidence: {run}", file=sys.stderr)
            return 1
        code, installed = call("installed-package", ["shell", "pm", "path", args.package])
        if code or b"package:" not in installed:
            manifest["capture_status"] = "package_unavailable"
            print(f"Package unavailable; evidence: {run}", file=sys.stderr)
            return 1
        call("kernel-page-size", ["shell", "getconf", "PAGE_SIZE"])
        call("build-fingerprint", ["shell", "getprop", "ro.build.fingerprint"])
        call("available-data-sources", ["shell", "perfetto", "--query-raw"])
        (run / "config.pbtxt").write_text(config)
        manifest["capture_status"] = "recording"
        manifest["device_trace_may_remain"] = True
        save()
        print(f"START {args.scenario}: {args.duration}-second recording; {run}", flush=True)
        code, _ = call(
            "record", ["shell", "perfetto", "-o", device_path, "--txt", "-c", "-"],
            timeout=args.duration + 20, data=config.encode(),
        )
        trace = run / "trace.pftrace"
        manifest["capture_status"] = "pulling"
        pulled, _ = call("pull", ["pull", device_path, str(trace.resolve())], timeout=20)
        manifest["capture_status"] = "record_failed" if code else "pull_failed" if pulled else "trace_missing_or_empty"
        if pulled == 0 and trace.is_file() and trace.stat().st_size > 0:
            with trace.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            manifest["trace"] = {"bytes": trace.stat().st_size, "sha256": digest}
            manifest["completed"] = code == 0
            manifest["capture_status"] = "captured" if code == 0 else "record_failed_partial_trace_retained"
            manifest["device_trace_cleanup"]["attempted"] = True
            removed, _ = call("remove-owned-device-trace", ["shell", "rm", "-f", device_path])
            manifest["device_trace_cleanup"]["removed"] = removed == 0
            manifest["device_trace_may_remain"] = removed != 0
            if args.trace_processor:
                try:
                    manifest["coverage"] = inspect_coverage(
                        args.trace_processor, trace, args.duration, args.package, run,
                    )
                except (OSError, ValueError, KeyError, IndexError, subprocess.TimeoutExpired):
                    manifest["coverage"] = {"status": "analysis_failed", "offline_analysis_required": True}
        print(run, flush=True)
        return 0 if manifest["completed"] else 1
    except KeyboardInterrupt:
        manifest["completed"] = False
        manifest["capture_status"] = "interrupted"
        print(f"Capture interrupted; evidence: {run}", file=sys.stderr)
        return 130
    finally:
        manifest["finished_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        save()


if __name__ == "__main__":
    sys.exit(main())
