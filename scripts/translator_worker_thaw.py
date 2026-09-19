#!/usr/bin/env python3
"""Opt-in physical thaw recorder. Only the device owner executes this script.

observe starts the named disposable Worker test and sends Home/Sleep/Wake only.
collect is a separate target-process initialization after observation has ended.
Neither phase changes policy, permissions, network, reverse rules or queue controls.
"""

import argparse
import datetime as dt
from decimal import Decimal, InvalidOperation
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


PACKAGE = "app.mihon.benchmark"
TEST_PACKAGE = PACKAGE + ".test"
TEST_CLASS = "mihon.feature.translation.acceptance.MinifiedTranslationWorkerRecoveryAcceptanceTest#durableActualWorkerRecovery"
REPORT_PREFIX = "INSTRUMENTATION_STATUS: translation_worker_recovery_report_json="
LIMIT = 64 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def require(condition, message):
    if not condition:
        raise ValueError(message)


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def private_json(path, value):
    temporary = path.with_name(path.name + ".tmp")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, "O_NOFOLLOW", 0), 0o600)
    with os.fdopen(fd, "w") as stream:
        json.dump(value, stream, indent=2, allow_nan=False)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def read_bounded(path, limit=LIMIT):
    require(path.is_file() and not path.is_symlink(), "Expected an ordinary evidence file")
    require(path.stat().st_size <= limit, "Evidence exceeded its declared size limit")
    with path.open("rb") as stream:
        value = stream.read(limit + 1)
    require(len(value) <= limit, "Evidence grew past its declared size limit")
    return value


def reports_from_output(data):
    reports = []
    for line in data.decode("utf-8", "replace").splitlines():
        if line.startswith(REPORT_PREFIX):
            try:
                report = json.loads(line[len(REPORT_PREFIX):])
            except json.JSONDecodeError:
                continue  # A currently appended trailing status line is incomplete.
            reports.append(report)
    return reports


def generation_dispatches(data, server_run_id):
    events = []
    for line in data.splitlines(keepends=True):
        if not line.endswith(b"\n"):
            continue
        event = json.loads(line)
        request_id = event.get("request_id")
        if request_id:
            require(request_id.startswith("fixture-" + server_run_id + "-"), "Ledger belongs to another fixture session")
        if event.get("event") == "dispatch" and not event.get("count_only", False):
            require(event.get("scenario") == "worker-screen-off-thaw", "Unrelated generation used the owned fixture session")
            require(not event.get("is_review", False), "Legacy recovery job unexpectedly requested quality review")
            events.append(event)
    return events


def verify_barrier(report, dispatches, run_id):
    require(report.get("run_id") == run_id and report.get("mode") == "screen-off-thaw", "Wrong instrumentation run")
    require(report.get("status") == "awaiting_screen_off", "Missing exact screen-off barrier")
    require(report.get("package") == PACKAGE and report.get("cloud_forwarding") is False, "Invalid benchmark report")
    records = report.get("records", [])
    require(records and records[-1].get("stage") == "awaiting_screen_off", "Missing barrier record")
    record = records[-1]
    require(len(record.get("results", [])) == 1, "First page is not committed")
    events = [event for event in record.get("events", [])
              if event.get("stage") == "generateContent" and event.get("message", "").startswith("Request started")]
    require(len(events) == 2 and len(dispatches) == 2, "Expected exactly two provider and wire starts")
    require(any(work.get("state") == "RUNNING" for work in record.get("work", [])), "Actual Worker is not running")
    require(all(len(event.get("pages", [])) == 1 for event in dispatches), "Expected singleton recovery requests")
    require(dispatches[0]["pages"][0]["image_sha256"] != dispatches[1]["pages"][0]["image_sha256"], "Duplicate page at barrier")
    return record


class InvalidScreenOffInterval(ValueError):
    pass


def require_off(condition, message):
    if not condition:
        raise InvalidScreenOffInterval(message)


def elapsed_ms_from_uptime(value):
    # Linux /proc/uptime's first field includes suspend, as does Android elapsedRealtime.
    first = value.strip().split()
    require(first and re.fullmatch(r"[0-9]+\.[0-9]+", first[0]), "Missing device BOOTTIME receipt")
    try:
        return int(Decimal(first[0]) * 1000)
    except InvalidOperation as error:
        raise ValueError("Invalid device BOOTTIME receipt") from error


def verify_screen_off_interval(report, evidence):
    rows = report.get("records", [])
    off = [row for row in rows if row.get("stage") == "observed_screen_off"]
    on = [row for row in rows if row.get("stage") == "observed_screen_on"]
    require_off(len(off) == 1 and len(on) == 1, "Require one exact app off/on checkpoint pair")
    off, on = off[0], on[0]
    require_off(off.get("interactive") is False and on.get("interactive") is True,
                "App checkpoints do not confirm noninteractive then interactive state")
    values = [off.get("elapsed_realtime_ms"), on.get("elapsed_realtime_ms")]
    require_off(all(type(value) is int and value > 0 for value in values), "Missing app elapsedRealtime timestamps")
    duration = values[1] - values[0]
    require_off(duration >= 60_000, "App observed less than 60 seconds screen-off: " + str(duration) + " ms")
    require_off(isinstance(evidence, dict) and type(evidence.get("pre_wake_elapsed_ms")) is int and
                evidence["pre_wake_elapsed_ms"] > 0,
                "Missing device clock receipt immediately before planned Wake")
    require_off(values[1] >= evidence["pre_wake_elapsed_ms"], "App became interactive before the host's planned Wake")
    return {"app_noninteractive_interval_ms": duration,
            "app_screen_on_elapsed_ms": values[1], "host_pre_wake_device_elapsed_ms": evidence["pre_wake_elapsed_ms"],
            "minimum_off_ms": 60_000, "clock_binding": "Android elapsedRealtime / Linux BOOTTIME; receipt precision retained"}


def reconcile_completion(report, barrier, dispatches, off_evidence=None):
    require(report.get("automatic_after_screen_on") == "completed", "Automatic thaw completion was not asserted")
    require(report.get("status") == "start_observation_passed_cleanup_required", "Start did not conclude successfully")
    require(report.get("recovery_trigger") == "screen_on_only_no_launch_resume_or_queue_write", "Wrong recovery trigger")
    records = report["records"]
    complete = next(record for record in reversed(records) if record["stage"] == "automatic_completion_assertions")
    require(complete["pid"] == barrier["pid"] and
            complete["process_start_elapsed_ms"] == barrier["process_start_elapsed_ms"], "Process changed during thaw")
    require(len(complete["results"]) == 2 and barrier["results"][0] in complete["results"], "First saved result changed")
    require(len(complete["jobs"]) == 1 and complete["jobs"][0]["state"] == "COMPLETED", "Job incomplete")
    require(complete["jobs"][0]["completedImages"] == 2, "Completed count differs")
    require(len(dispatches) in (2, 3) and all(len(event["pages"]) == 1 for event in dispatches), "Unexpected request count/batching")
    hashes = [event["pages"][0]["image_sha256"] for event in dispatches]
    require(hashes[0] != hashes[1] and hashes.count(hashes[0]) == 1, "Completed page was resubmitted")
    require(all(value == hashes[1] for value in hashes[1:]), "Unexpected retry image")
    require(all(work["state"] in ("SUCCEEDED", "FAILED", "CANCELLED") for work in complete["work"]), "Worker unfinished")
    off_interval = verify_screen_off_interval(report, off_evidence)
    return {"status": "automatic_thaw_preservation_and_wire_assertions_passed", "generation_dispatches": len(dispatches),
            "first_page_dispatches": 1, "second_page_dispatches": len(dispatches) - 1,
            "recovery_elapsed_ms": report.get("recovery_elapsed_ms"), "screen_off_interval": off_interval}


class Recorder:
    def __init__(self, args):
        self.args, self.output = args, args.output
        self.adb = [args.adb, "-s", args.serial]
        self.events = []
        self.instrumentation = None
        self.logcat = None
        self.slept = False
        self.woke = False
        self.observe_record = {}

    def event(self, action, **details):
        self.events.append({"action": action, "utc": utc(), "host_monotonic_ns": time.monotonic_ns(), **details})
        private_json(self.output / (self.args.phase + "-actions.json"), self.events)
        print(json.dumps({"action": action, **details}), flush=True)

    def manifest(self):
        files = {}
        for path in sorted(self.output.iterdir()):
            if path.is_file() and not path.is_symlink() and not path.name.endswith("-manifest.json"):
                value = read_bounded(path)
                files[path.name] = {"bytes": len(value), "sha256": hashlib.sha256(value).hexdigest()}
        private_json(self.output / (self.args.phase + "-manifest.json"), {
            "created_utc": utc(), "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "files": files, "limit": "A running instrumentation file can grow after this snapshot; retain its final hash separately."})

    def shell(self, *arguments, timeout=15):
        # One carefully quoted remote command; report contents never become shell syntax.
        return subprocess.run(self.adb + ["shell", shlex.join(map(str, arguments))], check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout).stdout.decode("utf-8", "replace")

    def capture(self, name, *arguments, required=False, timeout=15):
        path = self.output / (name + ".txt")
        try:
            value = self.shell(*arguments, timeout=timeout)
            require(len(value.encode()) <= LIMIT, "System capture exceeded size bound")
            path.write_text(value)
            os.chmod(path, 0o600)
            return value
        except Exception as error:
            self.event("capture_unavailable", name=name, error_class=type(error).__name__)
            if required:
                raise
            return ""

    def installed(self):
        receipts = []
        for package, expected in ((PACKAGE, self.args.benchmark_sha256), (TEST_PACKAGE, self.args.test_sha256)):
            paths = self.shell("pm", "path", package).strip().splitlines()
            require(len(paths) == 1 and paths[0].startswith("package:"), "Require one installed APK per verified package")
            path = paths[0][8:]
            require(re.fullmatch(r"/data/app/[A-Za-z0-9_./+=~-]+/base\.apk", path) is not None, "Unexpected installed APK path")
            result = self.shell("sha256sum", path).strip().split()
            require(result and result[0] == expected, "Installed " + package + " APK hash differs from approved artifact")
            receipts.append({"package": package, "path": path, "sha256": result[0]})
            self.capture(self.args.phase + "-package-" + package, "dumpsys", "package", package, required=True)
        private_json(self.output / (self.args.phase + "-installed-apks.json"), receipts)

    def sample(self, label, full=False, timeout=15):
        self.capture(label + "-pid", "pidof", PACKAGE, timeout=timeout)
        power = self.capture(label + "-power", "dumpsys", "power", required=True, timeout=timeout)
        self.capture(label + "-services", "dumpsys", "activity", "services", PACKAGE, timeout=timeout)
        self.capture(label + "-battery", "dumpsys", "battery", timeout=timeout)
        self.capture(label + "-thermal", "dumpsys", "thermalservice", timeout=timeout)
        if full:
            self.capture(label + "-connectivity", "dumpsys", "connectivity")
            self.capture(label + "-jobscheduler", "dumpsys", "jobscheduler", PACKAGE)
            self.capture(label + "-deviceidle", "dumpsys", "deviceidle")
            self.capture(label + "-wifi", "settings", "get", "global", "wifi_on")
            self.capture(label + "-airplane", "settings", "get", "global", "airplane_mode_on")
            self.capture(label + "-mobile-data", "settings", "get", "global", "mobile_data")
        return power

    def start_process(self, name, remote_args):
        path = self.output / name
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as output:
            process = subprocess.Popen(self.adb + ["shell", shlex.join(remote_args)], stdout=output,
                                       stderr=subprocess.STDOUT, start_new_session=True)
        self.event("owned_host_process_started", purpose=name, pid=process.pid)
        return process

    def instrumentation_args(self, phase):
        arguments = ["am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS,
                     "-e", "translation.workerRecovery", "true", "-e", "translation.workerPhase", phase,
                     "-e", "translation.workerMode", "screen-off-thaw", "-e", "translation.workerRunId", self.args.run_id]
        if phase == "collect":
            arguments += ["-e", "translation.workerObservation", "post-thaw"]
        return arguments + [TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"]

    def latest_report(self, filename):
        reports = reports_from_output(read_bounded(self.output / filename))
        return reports[-1] if reports else None

    def dispatches(self):
        return generation_dispatches(read_bounded(self.args.ledger, 32 * 1024 * 1024), self.args.server_run_id)

    def wait(self, ready, deadline, label):
        while time.monotonic() < deadline:
            if ready():
                return
            time.sleep(0.1)
        raise TimeoutError(label)

    def observe(self):
        self.output.mkdir(mode=0o700, parents=False, exist_ok=False)
        self.observe_record = {"schema": 1, "phase": "observe", "serial": self.args.serial, "run_id": self.args.run_id,
            "policy_label_host_attested": self.args.policy_label, "server_run_id": self.args.server_run_id,
            "benchmark_sha256": self.args.benchmark_sha256, "test_sha256": self.args.test_sha256,
            "ledger": str(self.args.ledger.resolve()), "started_utc": utc(), "start_concluded": False,
            "cleanup_required": False, "app_policy_mutated": False, "queue_resume_called": False,
            "limits": ["Instrumented active Worker; not natural idle or unattended process-eviction acceptance.",
                       "Host samples can affect sleep behavior; policy label requires separate UI evidence.",
                       "Collection is separate and initializes the target process."]}
        private_json(self.output / "observation.json", self.observe_record)
        try:
            self.installed()
            require(stat.S_ISREG(self.args.ledger.lstat().st_mode) and not self.args.ledger.is_symlink(), "Unsafe ledger file")
            require(not self.args.ledger.stat().st_mode & 0o077, "Fixture ledger must be private")
            require(read_bounded(self.args.ledger) == b"", "Require a fresh unconsumed owned fixture session")
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
            with opener.open("http://127.0.0.1:8765/health", timeout=5) as response:
                require(response.url == "http://127.0.0.1:8765/health", "Fixture health redirected")
                health = json.loads(response.read(65537))
            require(health.get("fixture") is True and health.get("cloud_forwarding") is False and
                    health.get("run_id") == self.args.server_run_id and
                    "worker-screen-off-thaw" in health.get("scenarios", []), "Fixture session does not match")
            private_json(self.output / "health.json", health)
            reverse = subprocess.run(self.adb + ["reverse", "--list"], check=True, capture_output=True, timeout=10).stdout.decode()
            require(any(line.split()[-2:] == ["tcp:8765", "tcp:8765"] for line in reverse.splitlines()), "Existing reverse mapping missing")
            require("mWakefulness=Awake" in self.sample("before", full=True), "Start with an awake display")
            uid_line = self.shell("pm", "list", "packages", "-U", PACKAGE)
            uid_match = re.search(r"^package:app\.mihon\.benchmark uid:(\d+)\s*$", uid_line, re.MULTILINE)
            require(uid_match is not None, "Cannot identify benchmark UID")
            uid = int(uid_match.group(1))
            self.observe_record["benchmark_uid"] = uid
            expression = r"app\.mihon\.benchmark|TranslationWorker|Greeze|greeze|Frozen|unfreeze|\b" + str(uid) + r"\b"
            self.logcat = self.start_process("targeted-logcat.txt", ["logcat", "-b", "main", "-b", "system", "-b", "crash",
                                                                           "-v", "epoch", "-T", "1", "--regex", expression])
            self.instrumentation = self.start_process("start-instrumentation.txt", self.instrumentation_args("start"))
            self.observe_record.update(cleanup_required=True, instrumentation_host_pid=self.instrumentation.pid)
            private_json(self.output / "observation.json", self.observe_record)
            def ready():
                report = self.latest_report("start-instrumentation.txt")
                if report and report.get("status") == "awaiting_screen_off":
                    verify_barrier(report, self.dispatches(), self.args.run_id)
                    private_json(self.output / "barrier-report.json", report)
                    return True
                require(self.instrumentation.poll() is None, "Start ended before its committed-page barrier")
                return False
            self.wait(ready, time.monotonic() + 90, "No valid saved-page barrier within 90 seconds")
            self.event("verified_first_page_and_two_dispatch_barrier")
            self.shell("input", "keyevent", "KEYCODE_HOME")
            self.event("home")
            self.shell("input", "keyevent", "KEYCODE_SLEEP")
            self.slept = True
            slept_at = time.monotonic()
            self.event("sleep", planned_off_seconds=60)
            power = self.capture("immediate-off-power", "dumpsys", "power", required=True)
            require_off("mWakefulness=Asleep" in power or "mWakefulness=Dozing" in power, "Display did not enter a noninteractive state")
            def still_off():
                report = self.latest_report("start-instrumentation.txt")
                require_off(not report or not any(row.get("stage") == "observed_screen_on" for row in report.get("records", [])),
                            "App screen-on checkpoint arrived before planned host Wake")
                return report
            def off_checkpoint_received():
                report = still_off()
                return report and any(row.get("stage") == "observed_screen_off" and row.get("interactive") is False
                                      for row in report.get("records", []))
            self.wait(off_checkpoint_received, time.monotonic() + 30, "No app noninteractive checkpoint before off interval")
            # Starting only after this receipt guarantees the app's measured off interval can reach a full 60 s.
            off_anchor = time.monotonic()
            self.event("app_screen_off_checkpoint_received", planned_off_seconds_after_receipt=60)
            def off_until(deadline):
                still_off()
                return time.monotonic() >= deadline
            # Collect one bounded midpoint snapshot; its overhead is recorded and never extends the 60-second target.
            self.wait(lambda: off_until(off_anchor + 30), off_anchor + 31, "Off midpoint deadline")
            power = self.sample("off-midpoint", full=False, timeout=3)
            require_off("mWakefulness=Asleep" in power or "mWakefulness=Dozing" in power, "Display woke before midpoint")
            self.wait(lambda: off_until(off_anchor + 60), off_anchor + 61, "Off duration deadline")
            power = self.capture("immediate-pre-wake-power", "dumpsys", "power", required=True, timeout=3)
            require_off("mWakefulness=Asleep" in power or "mWakefulness=Dozing" in power, "Display woke before planned Wake")
            clock_receipt = self.capture("immediate-pre-wake-clock", "cat", "/proc/uptime", required=True, timeout=3)
            off_evidence = {"pre_wake_elapsed_ms": elapsed_ms_from_uptime(clock_receipt),
                            "pre_wake_clock_raw": clock_receipt.strip()}
            self.observe_record["screen_off_evidence"] = off_evidence
            private_json(self.output / "observation.json", self.observe_record)
            still_off()
            self.shell("input", "keyevent", "KEYCODE_WAKEUP")
            self.woke = True
            self.event("wake_only", off_seconds=time.monotonic() - slept_at)
            power = self.capture("immediate-wake-power", "dumpsys", "power", required=True)
            require("mWakefulness=Awake" in power, "Wake did not make display awake")
            wake_observed = time.monotonic()
            self.observe_record["wake_observed_host_monotonic_ns"] = time.monotonic_ns()
            private_json(self.output / "observation.json", self.observe_record)
            deadline, hard_deadline = wake_observed + 245, wake_observed + 360
            next_sample, app_wake_seen = wake_observed + 30, False
            while self.instrumentation.poll() is None:
                now = time.monotonic()
                report = self.latest_report("start-instrumentation.txt")
                if report and not app_wake_seen and any(record.get("stage") == "observed_screen_on" for record in report.get("records", [])):
                    app_wake_seen = True
                    deadline = max(deadline, now + 215)
                    self.event("app_screen_on_checkpoint_received", automatic_wait_seconds=180, worker_finish_allowance_seconds=30)
                if now >= min(deadline, hard_deadline):
                    raise TimeoutError("Automatic observation ended; target instrumentation remains untouched for explicit operator handling")
                require((self.output / "start-instrumentation.txt").stat().st_size <= LIMIT, "Instrumentation output exceeded bound")
                if self.logcat and (self.output / "targeted-logcat.txt").stat().st_size > LIMIT:
                    self.stop_logcat()
                    self.event("logcat_truncated_at_storage_bound")
                if now >= next_sample:
                    self.sample("after-wake-" + str(round(now - wake_observed)))
                    next_sample = time.monotonic() + 30
                time.sleep(0.1)
            self.observe_record["start_concluded"] = True
            self.observe_record["instrumentation_exit_code"] = self.instrumentation.returncode
            self.sample("after-start-concluded", full=True)
            report = self.latest_report("start-instrumentation.txt")
            require(report is not None, "Missing final start report")
            private_json(self.output / "start-report.json", report)
            barrier = json.loads((self.output / "barrier-report.json").read_text())["records"][-1]
            result = reconcile_completion(report, barrier, self.dispatches(), off_evidence)
            require("OK (1 test)" in read_bounded(self.output / "start-instrumentation.txt").decode("utf-8", "replace"), "JUnit did not pass")
            self.observe_record.update(verdict="PASS", reconciliation=result)
        except BaseException as error:
            if self.instrumentation:
                self.observe_record["start_concluded"] = self.instrumentation.poll() is not None
            self.observe_record.update(verdict="FAIL" if self.instrumentation else "PREFLIGHT_REJECTED",
                                       error_class=type(error).__name__, error=str(error))
            if isinstance(error, InvalidScreenOffInterval):
                self.observe_record["protocol_verdict"] = "INVALID"
            self.event("observation_failed_preserved", error_class=type(error).__name__)
            raise
        finally:
            if self.slept and not self.woke:
                try:
                    self.shell("input", "keyevent", "KEYCODE_WAKEUP")
                    self.event("wake_in_interrupted_host_restoration")
                except Exception as error:
                    self.observe_record["display_restoration_error"] = type(error).__name__
            self.stop_logcat()
            if self.instrumentation and (self.output / "start-instrumentation.txt").is_file():
                try:
                    report = self.latest_report("start-instrumentation.txt")
                    if report is not None:
                        private_json(self.output / "start-report.json", report)
                        self.observe_record["raw_instrumentation_report_status"] = report.get("status")
                except Exception as error:
                    self.observe_record["final_report_snapshot_error"] = type(error).__name__
            self.observe_record["finished_utc"] = utc()
            self.observe_record["instrumentation_left_running"] = bool(self.instrumentation and self.instrumentation.poll() is None)
            if self.args.ledger.is_file():
                try:
                    value = read_bounded(self.args.ledger, 32 * 1024 * 1024)
                    path = self.output / "ledger-at-observation-end.jsonl"
                    path.write_bytes(value)
                    os.chmod(path, 0o600)
                    self.observe_record["ledger_snapshot_sha256"] = hashlib.sha256(value).hexdigest()
                except Exception as error:
                    self.observe_record["ledger_snapshot_error"] = type(error).__name__
            private_json(self.output / "observation.json", self.observe_record)
            self.event("observation_closed", verdict=self.observe_record.get("verdict", "FAIL"),
                       start_concluded=self.observe_record.get("start_concluded", False), cleanup_required=self.observe_record["cleanup_required"])
            self.manifest()

    def stop_logcat(self):
        if self.logcat and self.logcat.poll() is None:
            self.logcat.terminate()
            try:
                self.logcat.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.logcat.kill()
                self.logcat.wait(timeout=5)
        self.logcat = None

    def collect(self):
        require(self.output.is_dir() and not self.output.is_symlink(), "Observation output directory missing")
        original = json.loads(read_bounded(self.output / "observation.json"))
        require(original.get("serial") == self.args.serial and original.get("run_id") == self.args.run_id and
                original.get("server_run_id") == self.args.server_run_id and
                original.get("policy_label_host_attested") == self.args.policy_label, "Collection identity differs")
        require(original.get("start_concluded") is True and original.get("verdict") in ("PASS", "FAIL"), "Observation not concluded; collection would disturb recovery")
        require(original.get("benchmark_sha256") == self.args.benchmark_sha256 and
                original.get("test_sha256") == self.args.test_sha256, "Collection artifact changed")
        require(not (self.output / "collect-instrumentation.txt").exists(), "A collection is already recorded; do not overwrite it")
        self.installed()
        self.event("collect_initializes_target_process_no_queue_controls", preserved_observation_verdict=original["verdict"])
        process = self.start_process("collect-instrumentation.txt", self.instrumentation_args("collect"))
        try:
            process.wait(timeout=90)
            report = self.latest_report("collect-instrumentation.txt")
            require(report is not None, "Collection returned no report")
            private_json(self.output / "collected-report.json", report)
            require(report.get("status") == "collected_read_only_post-thaw" and report.get("collector_controls_queue") is False,
                    "Collection preservation checks did not pass")
            require("OK (1 test)" in read_bounded(self.output / "collect-instrumentation.txt").decode("utf-8", "replace"), "Collection JUnit failed")
            self.event("collection_preservation_checks_passed", observation_verdict_unchanged=original["verdict"])
        except BaseException as error:
            self.event("collection_failed", error_class=type(error).__name__, host_pid=process.pid,
                       left_running=process.poll() is None)
            raise
        finally:
            self.manifest()


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("observe", "collect"))
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--policy-label", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--server-run-id", required=True)
    parser.add_argument("--ledger", type=Path, required=True)
    parser.add_argument("--benchmark-sha256", required=True)
    parser.add_argument("--test-sha256", required=True)
    args = parser.parse_args(argv)
    require(re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", args.serial) is not None, "Invalid explicit serial")
    require(str(uuid.UUID(args.run_id)) == args.run_id, "Run ID must be a canonical UUID")
    require(re.fullmatch(r"[0-9a-f]{32}", args.server_run_id) is not None, "Invalid fixture server session ID")
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", args.policy_label) is not None, "Use a short descriptive policy label")
    require(all(re.fullmatch(r"[0-9a-f]{64}", value) for value in (args.benchmark_sha256, args.test_sha256)), "Expected exact lowercase APK SHA-256 values")
    return args


def main():
    args = parse_args()
    def interrupted(signum, _frame):
        raise KeyboardInterrupt("Host signal " + str(signum))
    signal.signal(signal.SIGTERM, interrupted)
    recorder = Recorder(args)
    getattr(recorder, args.phase)()


if __name__ == "__main__":
    main()
