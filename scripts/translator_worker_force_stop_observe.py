#!/usr/bin/env python3
"""Private, read-only observation after an operator's actual Force stop/launch/Resume.

This script never starts instrumentation, an app component, WorkManager or a server.
It never sends input, changes policies, clears logs, stops processes or cleans jobs.
Only the device owner runs it. All device commands are allowlisted system queries.
"""

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import signal
import stat
import subprocess
import time
import urllib.request
import uuid

from translator_worker_thaw import LIMIT, NoRedirect, PACKAGE, private_json, read_bounded, reports_from_output, require, utc


DURATIONS = {"passive-stopped": 30, "after-user-launch": 180, "after-manual-resume": 90}
PREVIOUS = {"after-user-launch": "passive-stopped", "after-manual-resume": "after-user-launch"}
SCENARIO = "worker-force-stop"


def digest(value):
    return hashlib.sha256(value).hexdigest()


def private_bytes(path, value):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(value)


def ledger_dispatches(value, server_id):
    dispatches = []
    for line in value.splitlines(keepends=True):
        if not line.endswith(b"\n"):
            continue  # An append in progress is retained verbatim, never treated as a dispatch.
        event = json.loads(line)
        request_id = event.get("request_id")
        if request_id is not None:
            require(isinstance(request_id, str) and request_id.startswith("fixture-" + server_id + "-"),
                    "Ledger belongs to another fixture session")
        if event.get("event") == "dispatch":
            require(event.get("scenario") == SCENARIO, "Unrelated request used the owned server")
            require(event.get("is_review", False) is False, "Legacy recovery unexpectedly requested quality review")
            if not event.get("count_only", False):
                require(len(event.get("pages", [])) == 1, "Expected a singleton generation dispatch")
                require(re.fullmatch(r"[0-9a-f]{64}", event["pages"][0].get("image_sha256", "")) is not None,
                        "Missing exact original image hash")
                require(re.fullmatch(r"[0-9a-f]{64}", event["pages"][0].get("image_id_sha256", "")) is not None,
                        "Missing wire image identity hash")
                dispatches.append(event)
    return dispatches


def barrier_from_bytes(value, run_id):
    try:
        report = json.loads(value)
    except json.JSONDecodeError:
        candidates = reports_from_output(value)
        require(bool(candidates), "Missing durable Worker report in host evidence")
        report = candidates[-1]
    require(report.get("run_id") == run_id and report.get("mode") == "force-stop" and
            report.get("package") == PACKAGE and report.get("cloud_forwarding") is False,
            "Wrong actual Worker run")
    require(report.get("status") == "awaiting_settings_force_stop", "Start did not remain at its Force-stop barrier")
    matches = [row for row in report.get("records", []) if row.get("stage") == "awaiting_settings_force_stop"]
    require(len(matches) == 1, "Require one unambiguous committed-page barrier")
    row = matches[0]
    require(len(row.get("results", [])) == 1, "First page is not committed at barrier")
    require(isinstance(row.get("pid"), int) and row["pid"] > 0, "Missing original PID")
    require(len(row.get("jobs", [])) == 1 and row["jobs"][0].get("id") == "worker-recovery-" + run_id,
            "Barrier does not identify the owned disposable job")
    require(any(work.get("state") == "RUNNING" for work in row.get("work", [])), "Missing actual RUNNING Worker")
    starts = [event for event in row.get("events", []) if event.get("stage") == "generateContent" and
              event.get("message", "").startswith("Request started")]
    require(len(starts) == 2, "Barrier requires two actual provider starts")
    require(re.fullmatch(r"[0-9a-f]{64}", row["results"][0].get("imageHash", "")) is not None,
            "Barrier lacks the saved original image hash")
    return report, row


def dispatch_summary(dispatches, first):
    require(len(dispatches) >= 2, "Missing two initial generation dispatches")
    hashes = [event["pages"][0]["image_sha256"] for event in dispatches]
    identities = [event["pages"][0]["image_id_sha256"] for event in dispatches]
    expected = {digest(first["imageId"].encode()),
                digest(f'{first["imageId"]}~0_0_{first["width"]}_{first["height"]}'.encode())}
    require(identities[0] in expected and identities[1] != identities[0], "Ledger does not match the saved page identity")
    require(hashes[0] != hashes[1], "Two fixture payloads are not distinct")
    require(identities.count(identities[0]) == 1 and hashes.count(hashes[0]) == 1, "Completed first page was resubmitted")
    require(all(value == hashes[1] for value in hashes[1:]), "Unexpected third image in owned job")
    require(all(value == identities[1] for value in identities[1:]), "Unexpected retry image identity")
    return {"generation_dispatches": len(hashes), "first_page_dispatches": 1,
            "second_page_dispatches": len(hashes) - 1, "first_page_original_sha256": first["imageHash"],
            "first_page_payload_sha256": hashes[0], "first_page_wire_id_sha256": identities[0],
            "second_page_payload_sha256": hashes[1], "request_ids": [event["request_id"] for event in dispatches]}


def package_stopped(value, user_id):
    # dumpsys also has standalone User N: section headings. Never cross a newline
    # or infer package installation state from their child rows.
    rows = re.findall(r"^[ \t]*User " + str(user_id) + r":[ \t]*([^\r\n]*)\r?$", value, re.MULTILINE)
    matches = [row.split() for row in rows if any(field.startswith("installed=") for field in row.split())]
    require(len(matches) == 1, "Missing unambiguous Android user package state")
    fields = matches[0]
    installed = [field for field in fields if field.startswith("installed=")]
    require(installed == ["installed=true"], "Package is not unambiguously installed for selected user")
    stopped = [field for field in fields if field.startswith("stopped=")]
    require(len(stopped) == 1 and stopped[0] in ("stopped=true", "stopped=false"),
            "Package stopped flag unavailable")
    return stopped[0] == "stopped=true"


def benchmark_processes(value, uid):
    lines = value.splitlines()
    require(bool(lines) and lines[0].split() == ["UID", "PID", "NAME"], "Unsupported process snapshot format")
    result = []
    for line in lines[1:]:
        fields = line.split(maxsplit=2)
        require(len(fields) == 3, "Incomplete process snapshot")
        process_uid, pid, name = fields
        if name == PACKAGE or name.startswith(PACKAGE + ":"):
            require(process_uid.isdigit() and int(process_uid) == uid and pid.isdigit() and int(pid) > 0,
                    "Benchmark process UID/PID does not match installed package")
            result.append({"uid": uid, "pid": int(pid), "name": name})
    return result


def read_only_command(arguments):
    exact = {
        ("am", "get-current-user"), ("pm", "path", PACKAGE), ("dumpsys", "package", PACKAGE),
        ("ps", "-A", "-o", "UID,PID,NAME"), ("cat", "/proc/sys/kernel/random/boot_id"),
        ("dumpsys", "power"), ("dumpsys", "battery"), ("dumpsys", "thermalservice"),
        ("dumpsys", "connectivity"), ("dumpsys", "deviceidle"), ("dumpsys", "notification"),
        ("dumpsys", "jobscheduler", PACKAGE), ("dumpsys", "activity", "services", PACKAGE),
        ("dumpsys", "activity", "processes", PACKAGE),
        ("settings", "get", "global", "wifi_on"), ("settings", "get", "global", "airplane_mode_on"),
        ("settings", "get", "global", "mobile_data"),
    }
    args = tuple(arguments)
    if args in exact:
        return True
    if len(args) == 2 and args[0] == "sha256sum":
        return re.fullmatch(r"/data/app/[A-Za-z0-9_./+=~-]+/base\.apk", args[1]) is not None
    if len(args) == 7 and args[:4] == ("pm", "list", "packages", "-U"):
        return args[4] == "--user" and args[5].isdigit() and args[6] == PACKAGE
    if len(args) == 2 and args[0] == "cat":
        return re.fullmatch(r"/proc/[1-9][0-9]*/stat", args[1]) is not None
    if args[:9] == ("logcat", "-b", "main", "-b", "system", "-b", "events", "-d", "-v"):
        return len(args) == 15 and args[9:13] == ("epoch", "-t", "10000", "--regex") and args[14] == "*:V"
    return False


class Device:
    def __init__(self, args):
        self.args = args
        self.receipts = []

    def query(self, label, arguments, required=True, timeout=3):
        require(read_only_command(arguments), "Device command is not an allowlisted system query")
        record = {"label": label, "arguments": list(arguments), "started_utc": utc(),
                  "started_host_monotonic_ns": time.monotonic_ns()}
        try:
            result = subprocess.run([self.args.adb, "-s", self.args.serial, "shell", shlex.join(arguments)],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, check=False)
            require(len(result.stdout) + len(result.stderr) <= LIMIT, "System capture exceeded size bound")
            private_bytes(self.args.output / (label + ".stdout.txt"), result.stdout)
            private_bytes(self.args.output / (label + ".stderr.txt"), result.stderr)
            record["exit_code"] = result.returncode
            if required:
                require(result.returncode == 0, "Required system query failed: " + label)
            return result.stdout.decode("utf-8", "replace") if result.returncode == 0 else None
        except Exception as error:
            record.update(error_class=type(error).__name__, unavailable=True)
            if required:
                raise
            return None
        finally:
            record.update(finished_utc=utc(), finished_host_monotonic_ns=time.monotonic_ns())
            self.receipts.append(record)
            private_json(self.args.output / "commands.json", self.receipts)


class Observer:
    def __init__(self, args, device=None):
        self.args, self.output = args, args.output
        self.device = device or Device(args)
        self.record = {"schema": 1, "phase": args.phase, "run_id": args.run_id,
            "server_run_id": args.server_run_id, "serial": args.serial, "package": PACKAGE,
            "benchmark_sha256": args.benchmark_sha256, "policy_label_host_attested": args.policy_label,
            "android_user": args.android_user, "user_action_utc_host_attested": args.action_utc,
            "window_seconds_required": DURATIONS[args.phase], "started_utc": utc(), "samples": [],
            "verdict": "PREFLIGHT_PENDING", "target_initialized": False, "app_or_queue_control_performed": False,
            "cleanup_performed": False, "durable_result_read_during_window": False,
            "limits": ["User action is separately evidenced and host-attested; queries cannot establish which UI was tapped.",
                       "No private app database is read. Exact first-result preservation awaits separate post-window collection.",
                       "Process/stopped state is sampled, not continuous; logcat is a bounded ring-buffer snapshot.",
                       "A server response_sent is not app receipt or completed translation.",
                       "Host/system sampling can affect background behavior. No app policy is changed."]}
        self.before_ledger = None
        self.ledger_stat = None

    def query(self, label, *args, **kwargs):
        return self.device.query(label, list(args), **kwargs)

    def save(self):
        private_json(self.output / "observation.json", self.record)

    def installed(self, label):
        paths = self.query(label + "-apk-path", "pm", "path", PACKAGE).strip().splitlines()
        require(len(paths) == 1 and paths[0].startswith("package:"), "Require exactly one installed benchmark APK")
        path = paths[0][8:]
        checksum = self.query(label + "-apk-hash", "sha256sum", path).strip().split()
        require(checksum and checksum[0] == self.args.benchmark_sha256, "Installed benchmark APK differs")
        return {"path": path, "sha256": checksum[0]}

    def ledger(self, label):
        state = self.args.ledger.lstat()
        require(stat.S_ISREG(state.st_mode) and not state.st_mode & 0o077, "Ledger must be a private ordinary file")
        if self.ledger_stat is not None:
            require((state.st_dev, state.st_ino) == self.ledger_stat, "Owned ledger was replaced")
        self.ledger_stat = (state.st_dev, state.st_ino)
        value = read_bounded(self.args.ledger, 32 * 1024 * 1024)
        if self.before_ledger is not None:
            require(value.startswith(self.before_ledger), "Owned ledger was truncated or rewritten")
        private_bytes(self.output / (label + "-ledger.jsonl"), value)
        require(not value or value.endswith(b"\n"), "Ledger snapshot ends in an incomplete event; counts are unavailable")
        summary = dispatch_summary(ledger_dispatches(value, self.args.server_run_id), self.first_result)
        return value, summary

    def state(self, label, initial=False):
        package = self.query(label + "-package", "dumpsys", "package", PACKAGE)
        processes = benchmark_processes(self.query(label + "-processes", "ps", "-A", "-o", "UID,PID,NAME"),
                                        self.record["package_uid"])
        stopped = package_stopped(package, self.args.android_user)
        sample = {"label": label, "observed_utc": utc(), "observed_host_monotonic_ns": time.monotonic_ns(),
                  "stopped": stopped, "processes": processes}
        self.record["samples"].append(sample)
        self.save()  # Retain the unexpected state before raising.
        require(stopped == (self.args.phase == "passive-stopped"), "Package stopped flag violates the selected phase")
        if stopped:
            require(not processes, "A benchmark process exists during the force-stopped interval")
        elif initial:
            main = [row for row in processes if row["name"] == PACKAGE]
            require(len(main) == 1 and main[0]["pid"] != self.record["barrier_pid"],
                    "Expected a new benchmark main PID after the actual user launch")
        for process in processes:
            self.query(label + "-pid-" + str(process["pid"]), "cat", "/proc/" + str(process["pid"]) + "/stat",
                       required=False)
        return sample

    def systems(self, label):
        for name, command in (
            ("services", ("dumpsys", "activity", "services", PACKAGE)),
            ("activity-processes", ("dumpsys", "activity", "processes", PACKAGE)),
            ("jobscheduler", ("dumpsys", "jobscheduler", PACKAGE)),
            ("notification", ("dumpsys", "notification")), ("power", ("dumpsys", "power")),
            ("battery", ("dumpsys", "battery")), ("thermal", ("dumpsys", "thermalservice")),
            ("connectivity", ("dumpsys", "connectivity")), ("deviceidle", ("dumpsys", "deviceidle")),
            ("wifi", ("settings", "get", "global", "wifi_on")),
            ("airplane", ("settings", "get", "global", "airplane_mode_on")),
            ("mobile-data", ("settings", "get", "global", "mobile_data")),
        ):
            self.query(label + "-" + name, *command, required=False)
        expression = r"app\.mihon\.benchmark|translator_queue|SystemForeground|WorkManager|WM-|Greeze|" + str(self.record["package_uid"])
        self.query(label + "-logcat", "logcat", "-b", "main", "-b", "system", "-b", "events", "-d", "-v",
                   "epoch", "-t", "10000", "--regex", expression, "*:V", required=False)

    def previous(self):
        expected = PREVIOUS.get(self.args.phase)
        if expected is None:
            require(self.args.previous_output is None, "Passive interval must begin a new observation chain")
            return
        require(self.args.previous_output is not None, "Missing preceding host-only observation")
        folder = self.args.previous_output
        value = read_bounded(folder / "observation.json")
        manifest = json.loads(read_bounded(folder / "manifest.json"))
        require(manifest["files"]["observation.json"]["sha256"] == digest(value), "Previous observation hash differs")
        previous = json.loads(value)
        require(previous.get("phase") == expected and previous.get("verdict") == "OBSERVED" and
                previous.get("window_completed") is True, "Previous window did not complete its protocol")
        for key in ("run_id", "server_run_id", "serial", "package", "benchmark_sha256", "policy_label_host_attested",
                    "android_user", "first_result_canonical_sha256"):
            require(previous.get(key) == self.record.get(key), "Previous observation identity differs: " + key)
        require(self.args.action_time >= dt.datetime.fromisoformat(previous["finished_utc"]),
                "User action predates completion of the previous observation")
        prior_ledger = read_bounded(folder / "after-ledger.jsonl")
        require(manifest["files"]["after-ledger.jsonl"]["sha256"] == digest(prior_ledger), "Previous ledger hash differs")
        require(self.before_ledger.startswith(prior_ledger), "Current ledger does not extend the preceding window")
        self.record["previous_observation"] = {"path": str(folder.resolve()), "sha256": digest(value)}
        self.record["previous_boot_id"] = previous.get("boot_id")

    def health(self):
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open("http://127.0.0.1:8765/health", timeout=5) as response:
            require(response.url == "http://127.0.0.1:8765/health", "Fixture health redirected")
            value = response.read(65537)
        require(len(value) <= 65536, "Fixture health too large")
        health = json.loads(value)
        private_json(self.output / "fixture-health.json", health)
        require(health.get("fixture") is True and health.get("cloud_forwarding") is False and
                health.get("run_id") == self.args.server_run_id and SCENARIO in health.get("scenarios", []),
                "Wrong owned loopback fixture session")

    def manifest(self):
        files = {}
        for path in sorted(self.output.iterdir()):
            if path.name != "manifest.json" and path.is_file() and not path.is_symlink():
                value = read_bounded(path)
                files[path.name] = {"bytes": len(value), "sha256": digest(value)}
        private_json(self.output / "manifest.json", {"schema": 1, "created_utc": utc(), "files": files,
            "observer_sha256": digest(Path(__file__).read_bytes()),
            "shared_helpers_sha256": digest(Path(__file__).with_name("translator_worker_thaw.py").read_bytes()),
            "private_evidence": True})

    def run(self):
        self.output.mkdir(mode=0o700, parents=False, exist_ok=False)
        self.save()
        try:
            action_age = (dt.datetime.now(dt.timezone.utc) - self.args.action_time).total_seconds()
            require(0 <= action_age <= 60, "Action must have occurred within the preceding 60 seconds")
            private_bytes(self.output / "user-action-evidence", read_bounded(self.args.action_evidence))
            value = read_bounded(self.args.barrier_report)
            private_bytes(self.output / "barrier-input.txt", value)
            report, barrier = barrier_from_bytes(value, self.args.run_id)
            private_json(self.output / "barrier-report.json", report)
            first = barrier["results"][0]
            self.first_result = first
            canonical = json.dumps(first, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
            self.record.update(first_result_canonical_sha256=digest(canonical), first_page_image_sha256=first["imageHash"],
                               barrier_pid=barrier["pid"], barrier_process_start_elapsed_ms=barrier.get("process_start_elapsed_ms"))
            self.before_ledger, summary = self.ledger("before")
            self.record["before_dispatches"] = summary
            if self.args.phase == "passive-stopped":
                require(summary["generation_dispatches"] == 2, "Force stop was not performed at the two-request barrier")
            self.previous()
            self.health()
            require(self.query("current-user", "am", "get-current-user").strip() == str(self.args.android_user),
                    "Selected Android user is not foreground")
            self.record["installed_before"] = self.installed("before")
            packages = self.query("package-uid", "pm", "list", "packages", "-U", "--user", str(self.args.android_user), PACKAGE)
            uids = re.findall(r"^package:" + re.escape(PACKAGE) + r" uid:([0-9]+)$", packages, re.MULTILINE)
            require(len(uids) == 1, "Missing exact benchmark UID")
            self.record["package_uid"] = int(uids[0])
            boot = self.query("before-boot-id", "cat", "/proc/sys/kernel/random/boot_id").strip()
            require(str(uuid.UUID(boot)) == boot, "Missing canonical boot identity")
            if "previous_boot_id" in self.record:
                require(self.record["previous_boot_id"] == boot, "Device boot differs from preceding observation")
            self.record["boot_id"] = boot
            self.state("before", initial=True)
            self.record.update(verdict="OBSERVING", window_started_utc=utc(),
                               window_started_host_monotonic_ns=time.monotonic_ns())
            start = time.monotonic()
            duration = DURATIONS[self.args.phase]
            self.save()
            print(json.dumps({"action": "observation_window_started", "phase": self.args.phase,
                              "seconds": duration, "app_untouched": True}), flush=True)
            self.systems("before")
            next_sample = start + 5
            while time.monotonic() < start + duration:
                if time.monotonic() >= next_sample:
                    self.state("during-" + str(len(self.record["samples"])))
                    next_sample = time.monotonic() + 5
                time.sleep(min(0.25, max(0, start + duration - time.monotonic())))
            self.record.update(window_completed=True, window_elapsed_seconds=time.monotonic() - start,
                               window_ended_utc=utc(), window_ended_host_monotonic_ns=time.monotonic_ns())
            self.state("after")
            _, after = self.ledger("after")
            self.record["after_dispatches"] = after
            self.record["new_generation_dispatches"] = after["generation_dispatches"] - summary["generation_dispatches"]
            if self.args.phase == "passive-stopped":
                require(self.record["new_generation_dispatches"] == 0, "A new request dispatched during passive force stop")
            self.record["installed_after"] = self.installed("after")
            require(self.query("after-boot-id", "cat", "/proc/sys/kernel/random/boot_id").strip() == boot,
                    "Device rebooted during observation")
            self.systems("after")
            self.record.update(verdict="OBSERVED", recovery_acceptance="pending separate durable collection and UI attribution")
        except BaseException as error:
            self.record.update(verdict="FAIL" if self.record["verdict"] == "OBSERVING" or
                               self.record.get("window_completed") else "PREFLIGHT_REJECTED",
                               error_class=type(error).__name__, error=str(error))
            raise
        finally:
            # No target-side cleanup, restoration, launch or intervention, including after Ctrl-C.
            if self.record["verdict"] != "OBSERVED" and "package_uid" in self.record:
                try:
                    self.systems("failure")
                except Exception as error:
                    self.record["failure_system_capture_error"] = type(error).__name__
            if self.ledger_stat is not None and not (self.output / "after-ledger.jsonl").exists():
                try:
                    _, summary = self.ledger("after")
                    self.record["after_dispatches"] = summary
                except Exception as error:
                    self.record["final_ledger_error"] = type(error).__name__ + ": " + str(error)
            self.record.update(finished_utc=utc(), operator_cleanup_required=True)
            self.save()
            self.manifest()
            print(json.dumps({"verdict": self.record["verdict"], "output": str(self.output),
                              "app_untouched": True}), flush=True)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=DURATIONS)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--server-run-id", required=True)
    parser.add_argument("--ledger", type=Path, required=True)
    parser.add_argument("--barrier-report", type=Path, required=True)
    parser.add_argument("--benchmark-sha256", required=True)
    parser.add_argument("--policy-label", required=True)
    parser.add_argument("--android-user", type=int, default=0)
    parser.add_argument("--action-utc", required=True, help="Actual preceding UI action time, timezone required")
    parser.add_argument("--action-evidence", type=Path, required=True, help="Private host screenshot/XML/receipt of actual UI action")
    parser.add_argument("--previous-output", type=Path)
    parser.add_argument("--output", type=Path, required=True, help="New directory under an existing private parent")
    args = parser.parse_args(argv)
    require(re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", args.serial) is not None, "Invalid explicit serial")
    require(str(uuid.UUID(args.run_id)) == args.run_id, "Expected canonical run UUID")
    require(re.fullmatch(r"[0-9a-f]{32}", args.server_run_id) is not None, "Expected exact fixture server run ID")
    require(re.fullmatch(r"[0-9a-f]{64}", args.benchmark_sha256) is not None, "Expected exact APK SHA-256")
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", args.policy_label) is not None, "Invalid policy label")
    require(args.android_user >= 0, "Invalid Android user")
    args.action_time = dt.datetime.fromisoformat(args.action_utc)
    require(args.action_time.tzinfo is not None, "Action timestamp must include timezone")
    return args


def main():
    def interrupted(signum, _frame):
        raise KeyboardInterrupt("Host signal " + str(signum))
    signal.signal(signal.SIGTERM, interrupted)
    Observer(parse_args()).run()


if __name__ == "__main__":
    main()
