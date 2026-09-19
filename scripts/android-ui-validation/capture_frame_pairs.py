#!/usr/bin/env python3
"""Host controller for guarded framePairs; each gate follows a real Perfetto start receipt.

This command DOES run adb when explicitly invoked. Unit tests replace all device/process calls.
Only the framework instrumentation owns UiAutomation; the controller handles trace files/gates.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import queue
import re
import shlex
import subprocess
import threading
import time
import uuid

HARNESS = "app.mihon.validation.framework"
SUBJECT = "app.mihon"
UUID_PATTERN = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
GATE_PATTERN = re.compile(r"(?:/data/user/0|/data/data)/" + re.escape(HARNESS) +
                          r"/files/reader-validation/(?P<run>" + UUID_PATTERN + r")/frame-(?P<ordinal>[1-6])\.go")
MAX_OUTPUT = 8 * 1024 * 1024
MAX_TRACE = 512 * 1024 * 1024


def require(value, message):
    if not value:
        raise ValueError(message)


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


capture = load_module("translator_perfetto_config", Path(__file__).resolve().parents[1] / "translator_perfetto_capture.py")
reconcile = load_module("translator_paired_input", Path(__file__).with_name("reconcile_frame_pairs.py"))


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def save(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    temporary.chmod(0o600)
    os.replace(temporary, path)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_ready(value, ordinal, backend, run_id=None, previous=None):
    require(isinstance(value, dict), "Invalid ready event")
    require(type(value.get("ordinal")) is int and value["ordinal"] == ordinal, "Out-of-order or duplicate ready event")
    match = GATE_PATTERN.fullmatch(value.get("gate_file", ""))
    require(match is not None and int(match["ordinal"]) == ordinal, "Gate is outside the owned harness run")
    require(run_id is None or match["run"] == run_id, "Harness run changed")
    require(re.fullmatch(UUID_PATTERN, value.get("gate_nonce", "")) is not None, "Invalid gate nonce")
    pair = (ordinal + 1) // 2
    first = "translated" if pair % 2 else "original"
    mode = first if ordinal % 2 else ("original" if first == "translated" else "translated")
    require((value.get("pair"), value.get("mode"), value.get("backend")) == (pair, mode, backend), "Pair identity changed")
    require(value.get("status") == "awaiting_host_trace", "Window is not awaiting its trace")
    require(value.get("chapter") in ("001 - Five-page modes", "002 - Twelve-page corpus"), "Unexpected chapter")
    page = value.get("page")
    require(type(page) in (int, float) and page >= 0 and page < 100000, "Invalid page")
    require((value.get("duration_ms"), value.get("gesture_count_expected"), value.get("cadence_ms"),
             value.get("gesture_duration_ms"), value.get("distance_px")) == (30000, 30, 1000, 500, 500), "Window protocol changed")
    require(re.fullmatch(r"[0-9a-f]{64}", value.get("before_viewport_sha256", "")) is not None, "Missing viewport hash")
    if previous is not None:
        require((value["chapter"], page) == (previous["chapter"], previous["page"]), "Selected page changed")
    return match["run"]


def parse_event(line):
    for kind in ("ready", "complete"):
        prefix = f"INSTRUMENTATION_STATUS: frame_window_{kind}_json="
        if line.startswith(prefix):
            return kind, json.loads(line[len(prefix):])
    return None


class Device:
    def __init__(self, adb, serial, root, manifest):
        self.prefix = [adb, "-s", serial]
        self.root = root
        self.manifest = manifest

    def call(self, label, args, *, data=None, timeout=15, check=True):
        index = len(self.manifest["commands"])
        stem = f"command-{index:03d}-{label}"
        entry = {"label": label, "arguments": args, "started_utc": utc(), "stem": stem}
        self.manifest["commands"].append(entry)
        save(self.root / "manifest.json", self.manifest)
        try:
            result = subprocess.run(self.prefix + args, input=data, capture_output=True, timeout=timeout)
            stdout, stderr, code = result.stdout, result.stderr, result.returncode
        except subprocess.TimeoutExpired as error:
            stdout, stderr, code = error.stdout or b"", error.stderr or b"", 124
        require(len(stdout) + len(stderr) <= MAX_OUTPUT, "Device command output exceeded the private evidence bound")
        (self.root / (stem + ".stdout")).write_bytes(stdout)
        (self.root / (stem + ".stderr")).write_bytes(stderr)
        entry.update(exit_code=code, finished_utc=utc())
        save(self.root / "manifest.json", self.manifest)
        if check:
            require(code == 0, f"Device command failed: {label} ({code}); evidence retained")
        return code, stdout


def validate_capabilities(perfetto_help, inotify_help, perfetto_exit=0, inotify_exit=0):
    """Help may use exit 1; only actual usage plus the required options permits a run."""
    require(perfetto_exit in (0, 1) and perfetto_help.lstrip().splitlines()[:1] == [b"Usage: perfetto"],
            "Perfetto did not return recognized help usage; no instrumentation started")
    require(all(re.search(rb"(?m)^\s+" + flag + rb"(?:\s|$)", perfetto_help)
                for flag in (b"--background-wait", b"--txt", b"--config")),
            "Perfetto lacks required --background-wait/--txt/--config; no instrumentation started")
    require(inotify_exit in (0, 1) and re.search(rb"(?m)^usage: inotifyd PROG FILE", inotify_help) and
            b'PROG is "-"' in inotify_help and b"closed (writable)" in inotify_help,
            "inotifyd lacks recognized usage/stdout writable-close observation; no instrumentation started")


def gate_stage_command(ready):
    gate, nonce = ready.get("gate_file", ""), ready.get("gate_nonce", "")
    require(GATE_PATTERN.fullmatch(gate) is not None, "Nonce destination is outside the owned harness run")
    require(re.fullmatch(UUID_PATTERN, nonce) is not None, "Nonce is not a canonical UUID")
    # The script is fixed. The validated values are shell-quoted positional arguments, not script text.
    script = 'umask 077; set -C; printf \'%s\' "$1" > "$2"'
    return shlex.join(["run-as", HARNESS, "sh", "-c", script, "mihon-frame-gate", nonce, gate + ".pending"])


def process_stat(raw, pid):
    text = raw.decode("ascii").strip()
    if text == "MIHON_PROCESS_GONE":
        return None
    match = re.fullmatch(r"([1-9][0-9]*) \(.*\) (.+)", text)
    require(match is not None and int(match[1]) == pid, "Invalid owned-process stat receipt")
    fields = match[2].split()
    require(len(fields) >= 20 and fields[19].isdigit(), "Missing owned-process start time")
    return {"pid": pid, "state": fields[0], "start_ticks": int(fields[19])}


def command_owns(raw, executable, argument):
    args = raw.rstrip(b"\0").split(b"\0")
    # Toybox inotifyd splits FILE:MASK in its argv storage, so procfs can expose FILE and MASK separately.
    expected = {argument.encode()}
    if executable == "inotifyd":
        expected.add((argument + ":w").encode())
    return bool(args and args[0].split(b"/")[-1] == executable.encode() and expected.intersection(args[1:]))


def close_event(raw, pid, remote):
    require(len(raw) <= 65536, "Writer observation exceeded its evidence bound")
    lines = raw.decode("utf-8").splitlines()
    require(lines and lines[0] == str(pid), "Writer observer PID receipt changed")
    if not raw.endswith(b"\n"):
        return False  # An adb/file read can observe an event between writes; wait for its complete line.
    closed = False
    for line in lines[1:]:
        fields = line.split("\t")
        require(len(fields) == 2 and fields[1] == remote, "Writer event is not for the exact owned trace")
        require(fields[0] == "w", "Writer observation overflowed or lost its exact writable-close watch")
        closed = True
    return closed


class WindowCapture:
    """A -D acknowledgment starts the trace; owned PID exit AND CLOSE_WRITE permit collection."""
    def __init__(self, device, ordinal):
        self.device = device
        self.ordinal = ordinal
        self.directory = device.root / f"window-{ordinal}"
        self.directory.mkdir(mode=0o700)
        self.remote = f"/data/misc/perfetto-traces/mihon-pair-{uuid.uuid4().hex}-{ordinal}.pftrace"
        self.process = None  # Host adb observer process, never the background Perfetto child.
        self.record = {"ordinal": ordinal, "directory": self.directory.name, "device_trace_path": self.remote,
                       "completed": False, "gate_armed": False, "trace_may_remain": False,
                       "recording_seconds": 45, "completion_method": "background_wait_pid_and_close_write",
                       "record_exit_code": None, "record_exit_status": "unavailable_for_background_child"}
        self.started = None
        self.streams = []
        self.recorder = None
        self.watcher = None
        self.finish_attempted = False

    def persist(self):
        save(self.directory / "capture.json", self.record)

    def stat(self, pid, purpose):
        # A failed read is not an exit. Distinguish an absent PID from denied/failed procfs access.
        command = (f"if [ ! -d /proc/{pid} ]; then printf 'MIHON_PROCESS_GONE\\n'; "
                   f"else cat /proc/{pid}/stat || {{ [ ! -d /proc/{pid} ] && printf 'MIHON_PROCESS_GONE\\n'; }}; fi")
        _, raw = self.device.call(f"{purpose}-{self.ordinal}", ["shell", command], timeout=5)
        return process_stat(raw, pid)

    def identify(self, pid, executable, argument, purpose):
        identity = self.stat(pid, purpose + "-stat")
        require(identity is not None and identity["state"] != "Z", f"{purpose} exited before ownership verification")
        _, command = self.device.call(f"{purpose}-cmdline-{self.ordinal}", ["exec-out", "cat", f"/proc/{pid}/cmdline"], timeout=5)
        require(command_owns(command, executable, argument), f"{purpose} PID does not own this exact trace")
        identity["cmdline_sha256"] = hashlib.sha256(command).hexdigest()
        return identity

    def alive(self, identity, purpose):
        current = self.stat(identity["pid"], purpose)
        return current is not None and current["start_ticks"] == identity["start_ticks"] and current["state"] != "Z"

    def start(self):
        config = capture.configuration(SUBJECT, 45, "frames")
        (self.directory / "config.pbtxt").write_text(config)
        self.device.call(f"trace-absent-{self.ordinal}", ["shell", "test", "!", "-e", self.remote])
        self.record.update(config_sha256=digest(self.directory / "config.pbtxt"), started_utc=utc(), trace_may_remain=True)
        self.persist()
        self.started = time.monotonic()
        code, raw = self.device.call(f"record-start-{self.ordinal}",
                                    ["shell", "perfetto", "--background-wait", "--txt", "-c", "-", "-o", self.remote],
                                    data=config.encode(), timeout=35, check=False)
        self.record.update(start_ack_exit_code=code, start_ack_stdout_hex=raw.hex(), receipt_utc=utc(),
                           receipt_after_host_start_seconds=time.monotonic() - self.started)
        self.persist()
        require(code == 0, "Perfetto background-wait start acknowledgment failed; gate stays unarmed")
        require(re.fullmatch(rb"[1-9][0-9]*\n?", raw) is not None, "Invalid background Perfetto PID receipt")
        self.recorder = self.identify(int(raw), "perfetto", self.remote, "recorder")
        self.record.update(recorder_identity=self.recorder, recording_started_confirmed=True)
        require(time.monotonic() - self.started < 10, "No timely start receipt; gate stays unarmed")
        # -D returns only after tracing starts. Register a CLOSE_WRITE watch before the gate,
        # then verify the watch's inode through this exact shell-owned observer's fdinfo.
        _, raw_inode = self.device.call(f"trace-inode-{self.ordinal}", ["shell", "stat", "-c", "%i", self.remote], timeout=5)
        require(re.fullmatch(rb"[1-9][0-9]*\n?", raw_inode) is not None, "Invalid owned trace inode")
        inode = int(raw_inode)
        out = (self.directory / "writer.stdout").open("wb")
        err = (self.directory / "writer.stderr").open("wb")
        self.streams = [out, err]
        # $$ is the task-owned shell PID; exec retains it. All path tokens are host-generated.
        command = "printf '%s\\n' \"$$\"; exec " + shlex.join(["inotifyd", "-", self.remote + ":w"])
        self.record["writer_command"] = self.device.prefix + ["shell", command]
        self.process = subprocess.Popen(self.record["writer_command"], stdout=out, stderr=err)
        while time.monotonic() - self.started < 10:
            require(self.process.poll() is None, "Writer observer exited before registering its watch")
            raw = (self.directory / "writer.stdout").read_bytes()
            if raw.endswith(b"\n"):
                first = raw.splitlines()[0]
                require(re.fullmatch(rb"[1-9][0-9]*", first) is not None, "Invalid writer observer PID receipt")
                pid = int(first)
                # The shell's PID receipt can arrive immediately before exec; retry only that transition.
                _, cmdline = self.device.call(f"writer-ready-cmdline-{self.ordinal}", ["exec-out", "cat", f"/proc/{pid}/cmdline"], timeout=5)
                if command_owns(cmdline, "inotifyd", self.remote):
                    self.watcher = self.identify(pid, "inotifyd", self.remote, "writer")
                    self.record["writer_identity"] = self.watcher
                    command = f"for task_fd in /proc/{pid}/fdinfo/*; do cat \"$task_fd\" || exit 1; done"
                    _, fdinfo = self.device.call(f"writer-watch-{self.ordinal}", ["shell", command], timeout=5)
                    watches = re.findall(rb"inotify wd:[0-9a-f]+ ino:([0-9a-f]+).*?mask:([0-9a-f]+)", fdinfo)
                    if any(int(number, 16) == inode and int(mask, 16) & 8 for number, mask in watches):
                        self.record.update(writer_watch_registered=True, trace_inode=inode,
                                           writer_watch_registered_utc=utc())
                        self.persist()
                        return
            time.sleep(0.1)
        raise ValueError("No timely owned writable-close watch; gate stays unarmed")

    def arm(self, ready):
        require(self.record.get("recording_started_confirmed") is True, "Cannot arm before actual Perfetto readiness")
        require(self.record.get("writer_watch_registered") is True, "Cannot arm without a registered exact writer watch")
        require(self.alive(self.recorder, "recorder-before-gate"), "Perfetto ended before gate dispatch")
        require(self.process.poll() is None and self.alive(self.watcher, "writer-before-gate"), "Writer observer ended before gate dispatch")
        gate = ready["gate_file"]
        pending = gate + ".pending"
        self.record.update(gate_file=gate, gate_nonce=ready["gate_nonce"], gate_pending=True)
        self.persist()
        self.device.call(f"gate-absent-{self.ordinal}", ["shell", "run-as", HARNESS, "test", "!", "-e", gate])
        self.device.call(f"gate-stage-{self.ordinal}", ["shell", gate_stage_command(ready)], timeout=3)
        _, staged = self.device.call(f"gate-verify-{self.ordinal}",
                                     ["exec-out", "run-as", HARNESS, "cat", pending], timeout=3)
        require(staged == ready["gate_nonce"].encode(), "Staged gate nonce differs; refusing to arm")
        self.record.update(gate_nonce_verified=True, gate_staging_method="fixed_printf_positional_arguments")
        require(time.monotonic() - self.started < 12, "Too little trace time remains; gate stays unarmed")
        self.device.call(f"gate-arm-{self.ordinal}", ["shell", "run-as", HARNESS, "mv", pending, gate])
        self.record.update(gate_armed=True, gate_pending=False, gate_armed_utc=utc(),
                           gate_after_host_start_seconds=time.monotonic() - self.started)
        self.persist()

    def stop_watcher(self):
        if self.watcher is not None and self.process is not None and self.process.poll() is None:
            # Only signal our still-matching PID/start time/command; never a reused PID.
            if self.alive(self.watcher, "writer-cleanup-stat"):
                current = self.identify(self.watcher["pid"], "inotifyd", self.remote, "writer-cleanup")
                require(current["start_ticks"] == self.watcher["start_ticks"], "Writer PID was reused during cleanup")
                self.device.call(f"writer-stop-{self.ordinal}", ["shell", "kill", "-TERM", str(self.watcher["pid"])])
                self.process.wait(timeout=5)
                self.record["writer_cleanup"] = "owned_observer_stopped"
            else:
                self.record["writer_cleanup"] = "owned_observer_already_exited_or_pid_reused"
        elif self.process is not None and self.process.poll() is None:
            self.record["writer_cleanup"] = "ownership_unverified_observer_may_remain"
        for stream in self.streams:
            stream.close()

    def finish(self):
        if self.finish_attempted:
            return
        self.finish_attempted = True
        if self.recorder is None:
            self.record["cleanup"] = "No verified recorder PID; owned remote trace may remain"
            self.persist()
            return
        try:
            require(self.record.get("writer_watch_registered") is True, "Unverified writer watch; trace retained without pulling")
            while time.monotonic() < self.started + 70:
                if not self.record.get("owned_process_exited") and not self.alive(self.recorder, "recorder-completion"):
                    self.record.update(owned_process_exited=True, record_exit_observed_utc=utc(),
                                       host_start_to_exit_observation_seconds=time.monotonic() - self.started)
                if not self.record.get("writer_close_observed"):
                    raw = (self.directory / "writer.stdout").read_bytes()
                    if close_event(raw, self.watcher["pid"], self.remote):
                        self.record.update(writer_close_observed=True, writer_close_observed_utc=utc(),
                                           writer_close_after_host_start_seconds=time.monotonic() - self.started)
                    else:
                        require(self.process.poll() is None, "Writer observer exited without an exact CLOSE_WRITE receipt")
                if self.record.get("owned_process_exited") and self.record.get("writer_close_observed"):
                    break
                time.sleep(0.5)
            else:
                self.record["record_timeout"] = True
                raise ValueError("Perfetto recording timeout: owned PID exit and writer CLOSE_WRITE were not both observed")
            trace = self.directory / "trace.pftrace"
            self.device.call(f"trace-pull-{self.ordinal}", ["pull", self.remote, str(trace.resolve())], timeout=25)
            require(trace.is_file() and 0 < trace.stat().st_size <= MAX_TRACE, "Missing, empty or oversized trace")
            trace.chmod(0o600)
            self.record["trace"] = {"path": str(trace.relative_to(self.device.root)), "sha256": digest(trace), "bytes": trace.stat().st_size}
            self.record["completed"] = True
            removed, _ = self.device.call(f"trace-cleanup-{self.ordinal}", ["shell", "rm", "-f", self.remote], check=False)
            self.record["trace_may_remain"] = removed != 0
        finally:
            try:
                self.stop_watcher()
            finally:
                self.persist()


def read_instrumentation(process, path, events):
    try:
        size = 0
        with path.open("wb") as stream:
            while line := process.stdout.readline(MAX_OUTPUT + 1):
                size += len(line)
                require(size <= MAX_OUTPUT, "Instrumentation output exceeded its private evidence bound")
                stream.write(line)
                stream.flush()
                event = parse_event(line.decode("utf-8", errors="strict").rstrip("\r\n"))
                if event:
                    events.put(event)
        events.put(("eof", None))
    except Exception as error:
        events.put(("error", str(error)))


def next_event(events, kind, timeout=100):
    try:
        observed, value = events.get(timeout=timeout)
    except queue.Empty as error:
        raise ValueError(f"Timed out waiting for the next {kind} event") from error
    require(observed == kind, f"Expected {kind}; received {observed}: {value if observed == 'error' else 'inspect instrumentation log'}")
    return value


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", required=True, type=Path, help="New private directory; never overwrites an existing run")
    parser.add_argument("--backend", required=True, choices=("classic", "webgpu"))
    parser.add_argument("--frame-anchor-pixels", type=int, choices=(0, 100), default=0,
                        help="Opt-in fixed interior drag for controlled WebGPU chapter 002/page 7; 0 preserves page-start protocol")
    parser.add_argument("--prime-frame-anchor", action="store_true",
                        help="Experimental 96-pixel touch-slop prime before the explicit 100-pixel anchor body")
    parser.add_argument("--pairs", required=True, type=int, choices=(1, 3))
    parser.add_argument("--initial-mode", required=True, choices=("original", "translated"))
    parser.add_argument("--subject-apk-sha256", required=True)
    parser.add_argument("--harness-apk-sha256", required=True)
    for flag in ("acceptance", "automatic-translation-disabled", "chapters-ahead-zero", "cached-translation-visible"):
        parser.add_argument("--" + flag, required=True, action="store_true")
    args = parser.parse_args(argv)
    anchor = reconcile.anchor_descriptor(args.frame_anchor_pixels, args.backend, args.prime_frame_anchor)
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]*", args.serial) is not None, "Invalid explicit serial")
    for value in (args.subject_apk_sha256, args.harness_apk_sha256):
        require(re.fullmatch(r"[0-9a-f]{64}", value) is not None, "Host-verified APK hash is required")
    os.umask(0o077)
    args.output.mkdir(parents=True, mode=0o700, exist_ok=False)
    manifest = {"schema": 1, "status": "started", "started_utc": utc(), "subject_package": SUBJECT,
                "harness_package": HARNESS, "backend": args.backend, "pairs": args.pairs,
                "initial_mode": args.initial_mode, "serial": args.serial,
                "host_attested_apk_hashes": {"subject": args.subject_apk_sha256, "harness": args.harness_apk_sha256},
                "commands": [], "windows": [], "completed": False,
                "limits": ["Host attestation does not replace installed-package hash verification.",
                           "Framework is the only UiAutomation owner; controller never injects UI events.",
                           "Input assertions and trace files are not frame-performance or visual acceptance.",
                           "Power snapshots are discrete observations; GPU/allocation counters require offline inspection.",
                           "On failure no next gate is armed; preserve the harness restoration journal before reuse."]}
    if anchor is not None:
        manifest["frame_anchor"] = anchor
    save(args.output / "manifest.json", manifest)
    device = Device(args.adb, args.serial, args.output, manifest)
    instrumentation = None
    recording = None
    run_id = None
    events = queue.Queue()
    try:
        _, state = device.call("target", ["get-state"])
        require(state.strip() == b"device", "Target is not ready")
        device.call("boot-id", ["exec-out", "cat", "/proc/sys/kernel/random/boot_id"])
        _, subject_pid = device.call("subject-pid", ["shell", "pidof", SUBJECT])
        require(re.fullmatch(rb"[1-9][0-9]*", subject_pid.strip()) is not None, "Require one exact main-app PID")
        manifest["subject_pid"] = int(subject_pid.strip())
        device.call("power-before", ["shell", "dumpsys", "battery"])
        device.call("thermal-before", ["shell", "dumpsys", "thermalservice"])
        perfetto_exit, perfetto_help = device.call("perfetto-capabilities", ["shell", "perfetto --help 2>&1"], check=False)
        inotify_exit, inotify_help = device.call("inotify-capabilities", ["shell", "inotifyd --help 2>&1"], check=False)
        validate_capabilities(perfetto_help, inotify_help, perfetto_exit, inotify_exit)
        manifest["capture_capabilities"] = {"method": "background_wait_pid_and_close_write", "validated_before_instrumentation": True}
        command = device.prefix + ["shell", "am", "instrument", "-w", "-r",
            "-e", "acceptance", "true", "-e", "plan", "framePairs", "-e", "pairs", str(args.pairs),
            "-e", "initialMode", args.initial_mode, "-e", "backend", args.backend,
            "-e", "automaticTranslationDisabled", "true", "-e", "chaptersAheadZero", "true",
            "-e", "cachedTranslationVisible", "true", f"{HARNESS}/{HARNESS}.ReaderUiInstrumentation"]
        if anchor is not None:
            command[-1:-1] = ["-e", "frameAnchorPixels", str(args.frame_anchor_pixels)]
        if args.prime_frame_anchor:
            command[-1:-1] = ["-e", "frameAnchorPrimed", "true"]
        manifest["instrumentation_command"] = command
        save(args.output / "manifest.json", manifest)
        instrumentation = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        thread = threading.Thread(target=read_instrumentation, args=(instrumentation, args.output / "instrumentation.log", events), daemon=True)
        thread.start()
        previous = None
        seen_nonces = set()
        for ordinal in range(1, args.pairs * 2 + 1):
            ready = next_event(events, "ready")
            run_id = validate_ready(ready, ordinal, args.backend, run_id, previous)
            reconcile.validate_anchor(ready.get("frame_anchor"), args.frame_anchor_pixels, args.backend, args.prime_frame_anchor)
            require(ready["gate_nonce"] not in seen_nonces, "Gate nonce was reused")
            seen_nonces.add(ready["gate_nonce"])
            manifest["harness_run_id"] = run_id
            window = {"ordinal": ordinal, "pair": ready["pair"], "mode": ready["mode"], "backend": args.backend, "ready": ready}
            manifest["windows"].append(window)
            save(args.output / "manifest.json", manifest)
            print(json.dumps({"window": ordinal, "mode": ready["mode"], "phase": "starting_trace"}), flush=True)
            device.call(f"power-window-{ordinal}", ["shell", "dumpsys", "battery"])
            device.call(f"thermal-window-{ordinal}", ["shell", "dumpsys", "thermalservice"])
            recording = WindowCapture(device, ordinal)
            recording.start()
            recording.arm(ready)
            complete = next_event(events, "complete", timeout=70)
            reconcile.validate_anchor(complete.get("frame_anchor"), args.frame_anchor_pixels, args.backend, args.prime_frame_anchor)
            require(complete.get("ordinal") == ordinal and complete.get("gate_nonce") == ready["gate_nonce"] and
                    complete.get("gate_file") == ready["gate_file"] and complete.get("status") == "boundary_assertions_passed",
                    "Unexpected or failed window completion")
            window["complete"] = complete
            recording.finish()
            window["trace"] = recording.record["trace"]
            window["capture"] = f"window-{ordinal}/capture.json"
            recording = None
            save(args.output / "manifest.json", manifest)
            print(json.dumps({"window": ordinal, "phase": "trace_closed_and_hashed", "trace": window["trace"]}), flush=True)
            previous = ready
        require(next_event(events, "eof", timeout=60) is None, "Instrumentation did not finish")
        require(instrumentation.wait(timeout=10) == 0, "adb instrumentation process failed")
        thread.join(timeout=5)
        _, report_bytes = device.call("framework-report", ["exec-out", "run-as", HARNESS, "cat", f"files/reader-validation/{run_id}/report.json"])
        report = json.loads(report_bytes)
        windows = reconcile.validate_report(report)
        require(len(windows) == len(manifest["windows"]), "Final report window count changed")
        for observed, recorded in zip(report["frame_windows"], manifest["windows"]):
            require(observed == recorded["complete"], "Final report differs from the completed status event")
        (args.output / "framework-report.json").write_bytes(report_bytes)
        manifest["framework_report_sha256"] = digest(args.output / "framework-report.json")
        screenshots = [action.get("details", {}).get("screenshot") for action in report.get("actions", [])
                       if "screenshot" in action.get("details", {})]
        require(len(screenshots) == 2 + 2 * len(windows) + (2 if anchor is not None else 0) and len(set(screenshots)) == len(screenshots),
                "Missing or duplicate screenshot references")
        screenshot_bytes = 0
        for name in screenshots:
            require(isinstance(name, str) and re.fullmatch(r"[0-9]{2}-[A-Za-z0-9_]+\.png", name) is not None,
                    "Screenshot reference escaped the owned report")
            _, raw = device.call("framework-screenshot", ["exec-out", "run-as", HARNESS, "cat",
                                 f"files/reader-validation/{run_id}/{name}"])
            require(raw, "Empty screenshot")
            screenshot_bytes += len(raw)
            require(screenshot_bytes <= 128 * 1024 * 1024, "Screenshot storage limit exceeded")
            (args.output / name).write_bytes(raw)
        manifest["screenshots_pulled"] = len(screenshots)
        manifest["input_windows"] = windows
        manifest.update(completed=True, status="captured_windows_pending_offline_frame_and_pixel_analysis")
    except (Exception, KeyboardInterrupt) as error:
        manifest.update(status="failed", failure=f"{type(error).__name__}: {error}", completed=False)
    finally:
        if recording is not None:
            try:
                recording.finish()
            except Exception as error:
                manifest["capture_cleanup_error"] = str(error)
        if instrumentation is not None and instrumentation.poll() is None:
            # Let the missing-gate timeout reach the framework finally/restoration path; do not force-stop Mihon.
            try:
                instrumentation.wait(timeout=150)
            except subprocess.TimeoutExpired:
                manifest["harness_still_running"] = True
                manifest["restoration_requires_explicit_inspection"] = True
        if run_id is not None and not (args.output / "framework-report.json").exists():
            try:
                _, raw = device.call("failed-framework-report", ["exec-out", "run-as", HARNESS, "cat", f"files/reader-validation/{run_id}/report.json"])
                (args.output / "framework-report.json").write_bytes(raw)
                manifest["framework_report_sha256"] = digest(args.output / "framework-report.json")
            except Exception as error:
                manifest["report_recovery_error"] = str(error)
        try:
            device.call("power-after", ["shell", "dumpsys", "battery"])
            device.call("thermal-after", ["shell", "dumpsys", "thermalservice"])
        except Exception as error:
            manifest["post_snapshot_error"] = str(error)
        manifest["finished_utc"] = utc()
        manifest["artifacts"] = {str(p.relative_to(args.output)): {"sha256": digest(p), "bytes": p.stat().st_size}
                                 for p in args.output.rglob("*") if p.is_file() and p.name != "manifest.json"}
        save(args.output / "manifest.json", manifest)
    print(json.dumps({"output": str(args.output.resolve()), "completed": manifest["completed"], "status": manifest["status"]}), flush=True)
    return 0 if manifest["completed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
