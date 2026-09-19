#!/usr/bin/env python3
"""Offline paired-window Perfetto analysis; no adb, downloads or provider calls.

Pass an existing native trace_processor_shell binary, not a downloader wrapper.
Clock reference: https://perfetto.dev/docs/concepts/clock-sync (2026-09-06).
Frame reference: https://perfetto.dev/docs/data-sources/frametimeline (2026-09-06).
"""

import argparse
import csv
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess


SPEC = importlib.util.spec_from_file_location("paired_input_reconcile", Path(__file__).with_name("reconcile_frame_pairs.py"))
reconcile = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reconcile)
require = reconcile.require
MAX_TRACE = 512 * 1024 * 1024


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def save(path, value):
    with path.open("x") as stream:
        json.dump(value, stream, indent=2, allow_nan=False)
        stream.write("\n")
    path.chmod(0o600)


def verified_artifact(root, name, receipt, maximum=MAX_TRACE):
    require(isinstance(name, str) and not Path(name).is_absolute() and ".." not in Path(name).parts,
            "Artifact escaped its controller directory")
    path = root / name
    require(path.is_file() and not path.is_symlink() and root.resolve() in path.resolve().parents,
            "Missing or unsafe artifact")
    require(0 < path.stat().st_size <= maximum and path.stat().st_size == receipt.get("bytes"), "Artifact size changed")
    require(digest(path) == receipt.get("sha256"), "Artifact hash changed")
    return path


def verify_capture(capture, expected):
    require(capture.get("completed") is True and capture.get("recording_started_confirmed") is True and
            capture.get("gate_armed") is True and capture.get("gate_nonce") == expected["gate_nonce"] and
            capture.get("gate_file") == expected["gate_file"] and capture.get("recording_seconds") == 45,
            "Missing trace-start/gate/finish proof")
    method = capture.get("completion_method")
    if method == "background_wait_pid_and_close_write":
        require(capture.get("start_ack_exit_code") == 0 and capture.get("writer_watch_registered") is True and
                capture.get("owned_process_exited") is True and capture.get("writer_close_observed") is True and
                "record_exit_code" in capture and capture["record_exit_code"] is None and
                capture.get("record_exit_status") == "unavailable_for_background_child",
                "Missing background recorder exit and writable-close proof")
        require(type(capture.get("trace_inode")) is int and capture["trace_inode"] > 0, "Missing exact trace inode")
        for key in ("recorder_identity", "writer_identity"):
            identity = capture.get(key)
            require(isinstance(identity, dict) and
                    all(type(identity.get(field)) is int and identity[field] > 0 for field in ("pid", "start_ticks")) and
                    re.fullmatch(r"[0-9a-f]{64}", identity.get("cmdline_sha256", "")) is not None,
                    "Missing owned process identity: " + key)
        require(capture["recorder_identity"]["pid"] != capture["writer_identity"]["pid"],
                "Recorder and simultaneous writer observer cannot share a PID")
        receipt = capture.get("start_ack_stdout_hex", "")
        require(isinstance(receipt, str) and re.fullmatch(r"(?:[0-9a-f]{2})+", receipt) is not None,
                "Missing raw background start acknowledgment")
        raw = bytes.fromhex(receipt)
        require(re.fullmatch(rb"[1-9][0-9]*\n?", raw) is not None and
                int(raw) == capture["recorder_identity"]["pid"], "Start acknowledgment PID differs from owned recorder")
        return {"method": method, "recorder_exit_status": "unavailable_for_background_child"}
    require(method in (None, "notify_fd") and capture.get("record_exit_code") == 0 and
            capture.get("receipt_hex") == "00", "Missing foreground notify-fd start/exit proof")
    return {"method": "notify_fd", "recorder_exit_status": "observed_exit_code_zero"}


def json_section(name, columns, source):
    entries = ",".join("'" + column + "'," + column for column in columns.split())
    # Perfetto's CSV renderer does not escape JSON's inner quotes; hex is unambiguous.
    return f"SELECT '{name}' AS section,hex(json_group_array(json_object({entries}))) AS payload FROM ({source})"


def window_sql(start, end, pid):
    require(all(type(value) is int and value > 0 for value in (start, end, pid)) and end > start, "Invalid window/PID")
    setup = f"""
CREATE PERFETTO TABLE q AS SELECT {start} AS ws,{end} AS we,{pid} AS target_pid;
CREATE PERFETTO TABLE target AS SELECT p.* FROM process p,q WHERE p.name='app.mihon' AND p.pid=q.target_pid
 AND (p.start_ts IS NULL OR p.start_ts<=q.ws) AND (p.end_ts IS NULL OR p.end_ts>=q.we);
CREATE PERFETTO TABLE frames AS SELECT a.* FROM actual_frame_timeline_slice a JOIN target USING(upid),q
 WHERE a.ts>=q.ws AND a.ts<q.we;
CREATE PERFETTO TABLE complete_frames AS SELECT frames.* FROM frames,q WHERE dur>=0 AND ts+dur<=q.we;
CREATE PERFETTO TABLE expected AS SELECT upid,layer_name,surface_frame_token,count(*) AS matches,min(ts) AS ts,min(dur) AS dur
 FROM expected_frame_timeline_slice JOIN target USING(upid) GROUP BY upid,layer_name,surface_frame_token;
CREATE PERFETTO TABLE running AS SELECT s.*,max(s.ts,q.ws) AS clipped_start,min(s.ts+s.dur,q.we) AS clipped_end
 FROM sched s,q WHERE s.dur>=0 AND s.ts<q.we AND s.ts+s.dur>q.ws;
CREATE PERFETTO TABLE draws AS SELECT s.id,s.name,t.utid,max(s.ts,q.ws) AS a,min(s.ts+s.dur,q.we) AS b,
 s.ts,s.dur,s.thread_dur FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t USING(utid)
 JOIN target USING(upid),q WHERE s.dur>=0 AND s.ts<q.we AND s.ts+s.dur>q.ws
 AND (s.name GLOB 'DrawFrame*' OR s.name='draw' OR s.name='drawFrame');
CREATE PERFETTO TABLE draw_prior AS SELECT *,max(b) OVER (PARTITION BY utid ORDER BY a,b ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prior_end FROM draws;
CREATE PERFETTO TABLE draw_groups AS SELECT *,sum(CASE WHEN prior_end IS NULL OR a>prior_end THEN 1 ELSE 0 END)
 OVER (PARTITION BY utid ORDER BY a,b) AS island FROM draw_prior;
CREATE PERFETTO TABLE draw_union AS SELECT utid,island,min(a) AS a,max(b) AS b FROM draw_groups GROUP BY utid,island;
"""
    sections = [
        json_section("bounds", "start_ts end_ts", "SELECT start_ts,end_ts FROM trace_bounds"),
        json_section("clock_metadata", "int_value", "SELECT int_value FROM metadata WHERE name='trace_time_clock_id'"),
        json_section("boottime", "ts clock_value clock_id", "SELECT ts,clock_value,clock_id FROM clock_snapshot WHERE clock_id=6"),
        json_section("process", "upid pid name start_ts end_ts", "SELECT upid,pid,name,start_ts,end_ts FROM target"),
        json_section("diagnostics", "name idx severity value", "SELECT name,idx,severity,value FROM stats WHERE value>0 AND (severity!='info' OR name GLOB '*discard*' OR name GLOB '*overrun*' OR name GLOB '*overwrite*' OR name GLOB '*loss*')"),
        json_section("scheduler", "cpu rows covered_ns largest_gap_ns", """SELECT cpu,count(*) AS rows,sum(clipped_end-clipped_start) AS covered_ns,
          max(max(0,clipped_start-coalesce(previous_end,clipped_start))) AS largest_gap_ns FROM
          (SELECT *,lag(clipped_end) OVER (PARTITION BY cpu ORDER BY clipped_start) AS previous_end FROM running) GROUP BY cpu"""),
        json_section("frame_counts", "layer_name started_rows complete_rows unfinished_rows right_boundary_rows distinct_tokens", """SELECT layer_name,count(*) AS started_rows,
          sum(CASE WHEN dur>=0 AND ts+dur<=q.we THEN 1 ELSE 0 END) AS complete_rows,
          sum(CASE WHEN dur<0 THEN 1 ELSE 0 END) AS unfinished_rows,
          sum(CASE WHEN dur>=0 AND ts+dur>q.we THEN 1 ELSE 0 END) AS right_boundary_rows,
          count(DISTINCT surface_frame_token) AS distinct_tokens FROM frames,q GROUP BY layer_name"""),
        json_section("left_boundary_frames", "rows", "SELECT count(*) AS rows FROM actual_frame_timeline_slice a JOIN target USING(upid),q WHERE a.ts<q.ws AND (a.dur<0 OR a.ts+a.dur>q.ws)"),
        json_section("frame_durations", "layer_name rows p50_ms p95_ms p99_ms max_ms jank_rows unknown_jank_rows app_deadline_missed_rows dropped_rows", """SELECT layer_name,count(*) AS rows,
          PERCENTILE(dur/1e6,50) AS p50_ms,PERCENTILE(dur/1e6,95) AS p95_ms,PERCENTILE(dur/1e6,99) AS p99_ms,max(dur)/1e6 AS max_ms,
          sum(CASE WHEN jank_type IS NOT NULL AND jank_type!='None' THEN 1 ELSE 0 END) AS jank_rows,
          sum(CASE WHEN jank_type IS NULL THEN 1 ELSE 0 END) AS unknown_jank_rows,
          sum(CASE WHEN instr(jank_type,'App Deadline Missed')>0 THEN 1 ELSE 0 END) AS app_deadline_missed_rows,
          sum(CASE WHEN present_type='Dropped Frame' THEN 1 ELSE 0 END) AS dropped_rows FROM complete_frames GROUP BY layer_name"""),
        json_section("classifications", "layer_name jank_type jank_severity_type present_type on_time_finish rows", """SELECT layer_name,jank_type,jank_severity_type,present_type,on_time_finish,count(*) AS rows
          FROM complete_frames GROUP BY layer_name,jank_type,jank_severity_type,present_type,on_time_finish"""),
        json_section("deadlines", "layer_name actual_rows unique_expected_matches ended_after_expected_deadline_rows max_end_minus_deadline_ms", """SELECT a.layer_name,count(*) AS actual_rows,
          sum(CASE WHEN e.matches=1 AND e.dur>=0 THEN 1 ELSE 0 END) AS unique_expected_matches,
          sum(CASE WHEN e.matches=1 AND e.dur>=0 AND a.ts+a.dur>e.ts+e.dur THEN 1 ELSE 0 END) AS ended_after_expected_deadline_rows,
          max(CASE WHEN e.matches=1 AND e.dur>=0 THEN (a.ts+a.dur-e.ts-e.dur)/1e6 END) AS max_end_minus_deadline_ms
          FROM complete_frames a LEFT JOIN expected e ON a.upid=e.upid AND a.layer_name IS e.layer_name AND a.surface_frame_token=e.surface_frame_token GROUP BY a.layer_name"""),
        json_section("thread_cpu", "tid name running_ms slices", """SELECT t.tid,t.name,sum(r.clipped_end-r.clipped_start)/1e6 AS running_ms,count(*) AS slices
          FROM running r JOIN thread t USING(utid) JOIN target USING(upid) GROUP BY t.utid ORDER BY running_ms DESC"""),
        json_section("draw_slice_wall", "rows union_wall_ms complete_wall_p50_ms complete_wall_p95_ms complete_wall_p99_ms", """SELECT count(*) AS rows,
          (SELECT sum(b-a)/1e6 FROM draw_union) AS union_wall_ms,
          PERCENTILE(CASE WHEN ts>=q.ws AND ts+dur<=q.we THEN dur/1e6 END,50) AS complete_wall_p50_ms,
          PERCENTILE(CASE WHEN ts>=q.ws AND ts+dur<=q.we THEN dur/1e6 END,95) AS complete_wall_p95_ms,
          PERCENTILE(CASE WHEN ts>=q.ws AND ts+dur<=q.we THEN dur/1e6 END,99) AS complete_wall_p99_ms FROM draws,q"""),
        json_section("draw_cpu", "intersections scheduled_cpu_ms", """SELECT count(*) AS intersections,sum(min(d.b,r.clipped_end)-max(d.a,r.clipped_start))/1e6 AS scheduled_cpu_ms
          FROM draw_union d JOIN running r ON d.utid=r.utid AND r.clipped_start<d.b AND r.clipped_end>d.a"""),
        json_section("gpu_process_memory", "track_id name unit in_window_samples min_in_window max_in_window carry_ts carry_value last_ts last_value", """SELECT t.id AS track_id,t.name,t.unit,
          (SELECT count(*) FROM counter c WHERE c.track_id=t.id AND c.ts>=q.ws AND c.ts<q.we) AS in_window_samples,
          (SELECT min(value) FROM counter c WHERE c.track_id=t.id AND c.ts>=q.ws AND c.ts<q.we) AS min_in_window,
          (SELECT max(value) FROM counter c WHERE c.track_id=t.id AND c.ts>=q.ws AND c.ts<q.we) AS max_in_window,
          (SELECT ts FROM counter c WHERE c.track_id=t.id AND c.ts<q.ws ORDER BY ts DESC LIMIT 1) AS carry_ts,
          (SELECT value FROM counter c WHERE c.track_id=t.id AND c.ts<q.ws ORDER BY ts DESC LIMIT 1) AS carry_value,
          (SELECT ts FROM counter c WHERE c.track_id=t.id AND c.ts<q.we ORDER BY ts DESC LIMIT 1) AS last_ts,
          (SELECT value FROM counter c WHERE c.track_id=t.id AND c.ts<q.we ORDER BY ts DESC LIMIT 1) AS last_value
          FROM process_counter_track t JOIN target USING(upid),q WHERE t.type='process_gpu_memory'"""),
        json_section("gpu_system_counters", "name type unit samples minimum maximum", """SELECT t.name,t.type,t.unit,count(c.id) AS samples,min(c.value) AS minimum,max(c.value) AS maximum
          FROM gpu_counter_track t,q LEFT JOIN counter c ON c.track_id=t.id AND c.ts>=q.ws AND c.ts<q.we GROUP BY t.id"""),
        json_section("gpu_render_stages", "package_complete_rows p50_ms p95_ms max_ms", "SELECT count(*) AS package_complete_rows,PERCENTILE(g.dur/1e6,50) AS p50_ms,PERCENTILE(g.dur/1e6,95) AS p95_ms,max(g.dur)/1e6 AS max_ms FROM gpu_slice g JOIN target USING(upid),q WHERE g.ts>=q.ws AND g.dur>=0 AND g.ts+g.dur<=q.we"),
        json_section("heap_samples", "heap_name records signed_sampled_bytes signed_sampled_count", """SELECT heap_name,count(*) AS records,sum(size) AS signed_sampled_bytes,sum(count) AS signed_sampled_count
          FROM heap_profile_allocation JOIN target USING(upid),q WHERE ts>=q.ws AND ts<q.we GROUP BY heap_name"""),
        json_section("allocation_counter_inventory", "name type unit samples minimum maximum", """SELECT t.name,t.type,t.unit,count(c.id) AS samples,min(c.value) AS minimum,max(c.value) AS maximum
          FROM process_counter_track t JOIN target USING(upid),q LEFT JOIN counter c ON c.track_id=t.id AND c.ts>=q.ws AND c.ts<q.we
          WHERE lower(t.name) GLOB '*alloc*' GROUP BY t.id"""),
    ]
    return setup + "\nUNION ALL\n".join(sections) + ";\n"


def decode_sections(stdout):
    rows = list(csv.DictReader(io.StringIO(stdout)))
    require(rows and set(rows[0]) == {"section", "payload"}, "Unexpected trace processor result format")
    result = {}
    for row in rows:
        require(row["section"] not in result, "Duplicate result section")
        result[row["section"]] = json.loads(bytes.fromhex(row["payload"]))
    return result


def assess(raw, start, end, pid):
    require(raw["clock_metadata"] == [{"int_value": 6}], "Unsupported trace clock; require explicit BOOTTIME metadata")
    clocks = raw["boottime"]
    require(len(clocks) >= 2 and all(row["ts"] == row["clock_value"] for row in clocks), "Missing or nonidentity BOOTTIME snapshots")
    require(min(row["clock_value"] for row in clocks) <= start and max(row["clock_value"] for row in clocks) >= end,
            "Clock snapshots do not bracket the complete gesture window")
    require(len(raw["bounds"]) == 1 and raw["bounds"][0]["start_ts"] <= start and raw["bounds"][0]["end_ts"] >= end,
            "Trace does not retain the entire gesture window")
    require(len(raw["process"]) == 1 and raw["process"][0]["pid"] == pid and raw["process"][0]["name"] == "app.mihon",
            "Exact subject PID/name/lifetime attribution is unavailable or ambiguous")
    blocking = [row for row in raw["diagnostics"] if row["severity"] == "data_loss" or
                (row["value"] > 0 and ("overwrite" in row["name"] or "packet_loss" in row["name"] or "overrun_delta" in row["name"]))]
    scheduler = raw["scheduler"]
    scheduler_complete = bool(scheduler) and all(abs(row["covered_ns"] - (end-start)) <= 1000 and row["largest_gap_ns"] == 0 for row in scheduler)
    frames = raw["frame_counts"]
    observed = bool(frames) and sum(row["complete_rows"] for row in frames) > 0
    incomplete = sum(row["unfinished_rows"] for row in frames)
    comparable = observed and not blocking and incomplete == 0
    return {"status": "measurements_with_diagnostics" if comparable and raw["diagnostics"] else "measured_window" if comparable else "incomplete_frame_evidence",
            "frame_window_eligible_for_comparison": comparable, "performance_pass": None,
            "clock_binding": {"source": "Android elapsedRealtimeNanos / BOOTTIME", "trace_clock_id": 6,
                              "mapping": "identity verified by bracketing clock snapshots", "start_ts": start, "end_ts": end},
            "blocking_loss_diagnostics": blocking, "scheduler_complete_on_observed_cpus": scheduler_complete,
            "expected_deadlines_complete": bool(raw["deadlines"]) and all(row["actual_rows"] == row["unique_expected_matches"] for row in raw["deadlines"]),
            "cpu_limit": "Scheduled CPU summed across observed threads; missing/offline CPU intervals require review. No CPU utilization inference from wall slices.",
            "draw_cpu_available": bool(raw["draw_slice_wall"] and raw["draw_slice_wall"][0]["rows"] and raw["draw_cpu"][0]["intersections"]),
            "draw_cpu_eligible_for_comparison": bool(raw["draw_slice_wall"] and raw["draw_slice_wall"][0]["rows"] and scheduler_complete and not blocking),
            "draw_cpu_coverage": "complete_on_observed_cpus" if scheduler_complete else "observed_partial_scheduling_only",
            "draw_scope": "Union of observed DrawFrame*, draw, drawFrame slices per thread; excludes uninstrumented native drawing and does not double-count nested intervals.",
            "allocation_sampling_available": bool(raw["heap_samples"]),
            "allocation_rate_bytes_per_second": None,
            "allocation_limit": "Signed profiler samples are not exact total allocations; allocation-named gauges are inventory only. Frames configuration does not enable heapprofd.",
            "gpu_render_stage_timing_available": bool(raw["gpu_render_stages"][0]["package_complete_rows"]),
            "gpu_hardware_utilization": "unavailable or not identified; inspect recorded system counter identities, never infer from frequency",
            "raw": raw}


def analyze_trace(processor, trace, start, end, pid, directory):
    directory.mkdir(mode=0o700)
    query = directory / "window.sql"
    query.write_text(window_sql(start, end, pid))
    query.chmod(0o600)
    result = subprocess.run([str(processor), "query", "-f", str(query), str(trace)], capture_output=True, timeout=120)
    require(len(result.stdout) + len(result.stderr) <= 16 * 1024 * 1024, "Trace analysis output exceeded bound")
    for name, value in (("query.csv", result.stdout), ("query.stderr", result.stderr)):
        (directory / name).write_bytes(value)
        (directory / name).chmod(0o600)
    require(result.returncode == 0, "Trace processor rejected the retained query; diagnostics preserved")
    raw = decode_sections(result.stdout.decode())
    save(directory / "raw.json", raw)
    return assess(raw, start, end, pid)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--trace-processor", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    processor = args.trace_processor.resolve()
    require(processor.is_file() and os.access(processor, os.X_OK), "Use an existing executable native trace processor")
    with processor.open("rb") as stream:
        require(stream.read(2) != b"#!", "Downloader/script wrappers are not permitted by the offline analyzer")
    manifest = json.loads(args.manifest.read_text())
    root = args.manifest.resolve().parent
    require(manifest.get("completed") is True and manifest.get("subject_package") == "app.mihon", "Controller did not complete the expected subject run")
    pid = manifest.get("subject_pid")
    require(type(pid) is int and pid > 0, "Missing exact host-observed subject PID")
    artifacts = manifest["artifacts"]
    def command_receipt(label):
        commands = [command for command in manifest["commands"] if command["label"] == label]
        require(len(commands) == 1 and commands[0].get("exit_code") == 0, "Missing unique successful " + label + " receipt")
        name = commands[0]["stem"] + ".stdout"
        return verified_artifact(root, name, artifacts[name], 1024*1024).read_text().strip()
    require(command_receipt("subject-pid") == str(pid), "PID receipt differs from manifest attribution")
    boot_id = command_receipt("boot-id")
    require(re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", boot_id) is not None, "Malformed host boot ID receipt")
    report_path = verified_artifact(root, "framework-report.json", artifacts["framework-report.json"], 8*1024*1024)
    require(digest(report_path) == manifest["framework_report_sha256"], "Framework report hash differs")
    report = json.loads(report_path.read_text())
    input_windows = reconcile.validate_report(report)
    for action in report.get("actions", []):
        name = action.get("details", {}).get("screenshot")
        if name:
            verified_artifact(root, name, artifacts[name], 20*1024*1024)
    screenshot_count = reconcile.verify_screenshots(report, root)
    require(len(manifest["windows"]) == len(input_windows), "Controller window count differs")
    args.output.mkdir(mode=0o700, parents=False, exist_ok=False)
    result = {"schema": 1, "status": "analyzing", "package": "app.mihon", "pid": pid,
              "host_observed_boot_id": boot_id,
              "host_attested_apk_hashes": manifest.get("host_attested_apk_hashes"),
              "manifest_sha256": digest(args.manifest), "framework_report_sha256": digest(report_path),
              "processor_sha256": digest(processor), "script_sha256": digest(Path(__file__)),
              "screenshots_verified": screenshot_count, "windows": [],
              "limits": ["Measured values are not handset guarantees or a performance pass.",
                         "Actual FrameTimeline durations are not presentation intervals, refresh rate or touch latency.",
                         "No App Deadline Missed rows does not imply no jank; all classification labels are retained.",
                         "Duration percentiles use complete slices wholly inside each window; boundary and unfinished rows are separate.",
                         "GPU memory counters differ from Graphics PSS; carry-forward values are dated change events.",
                         "CPU draw attribution excludes uninstrumented WebGPU work. No allocation pressure is inferred from memory deltas.",
                         "Thermal/power context remains in the controller receipts and must be compared before attributing mode differences."]}
    try:
        for expected, window in zip(report["frame_windows"], manifest["windows"]):
            require(window["complete"] == expected, "Controller completion differs from final framework report")
            require(all(window[key] == expected[key] for key in ("ordinal", "pair", "mode", "backend")), "Window attribution differs")
            trace = verified_artifact(root, window["trace"]["path"], window["trace"])
            require(window["trace"]["sha256"] == artifacts[window["trace"]["path"]]["sha256"], "Trace artifact receipt differs")
            capture = json.loads(verified_artifact(root, window["capture"], artifacts[window["capture"]], 1024*1024).read_text())
            capture_proof = verify_capture(capture, expected)
            measured = analyze_trace(processor, trace, expected["start_elapsed_ns"], expected["end_elapsed_ns"], pid,
                                     args.output / f'window-{expected["ordinal"]}')
            measured.update({key: expected[key] for key in ("ordinal", "pair", "mode", "backend", "page", "chapter")})
            measured["trace_sha256"] = window["trace"]["sha256"]
            measured["capture_completion"] = capture_proof
            result["windows"].append(measured)
        result["status"] = "paired_windows_measured" if all(window["frame_window_eligible_for_comparison"] for window in result["windows"]) else "paired_evidence_incomplete"
    except Exception as error:
        result.update(status="analysis_failed", failure_class=type(error).__name__, failure=str(error))
        raise
    finally:
        save(args.output / "analysis.json", result)
    print(json.dumps({"status": result["status"], "windows": len(result["windows"]), "analysis": str(args.output / "analysis.json")}))


if __name__ == "__main__":
    main()
