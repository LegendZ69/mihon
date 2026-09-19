#!/usr/bin/env python3
"""Read-only, bounded Android translator diagnostics; requires an explicit adb target."""

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid


PROPERTIES = (
    "ro.product.manufacturer", "ro.product.brand", "ro.product.model",
    "ro.product.device", "ro.product.marketname", "ro.soc.manufacturer",
    "ro.soc.model", "ro.hardware", "ro.product.cpu.abilist",
    "ro.build.fingerprint", "ro.build.version.release", "ro.build.version.sdk",
    "ro.build.version.security_patch", "ro.build.version.incremental",
    "ro.mi.os.version.name", "ro.miui.ui.version.name",
    "ro.kernel.qemu", "ro.boot.qemu",
)


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def execute(command, timeout=10):
    started = utc()
    try:
        result = subprocess.run(command, capture_output=True, timeout=timeout, check=False)
        code, output = result.returncode, result.stdout + result.stderr
    except subprocess.TimeoutExpired as error:
        code = 124
        output = (error.stdout or b"") + (error.stderr or b"") + b"\nCapture command timed out.\n"
    except OSError as error:
        code, output = 127, str(error).encode()
    return {"command": command, "started_utc": started, "finished_utc": utc(), "exit_code": code}, output


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Exact serial from adb devices -l; no automatic target selection")
    parser.add_argument("--package", required=True, help="Installed application ID, e.g. app.mihon or app.mihon.dev")
    parser.add_argument("--scenario", required=True, help="Short run label, e.g. paddle-small-warm-01")
    parser.add_argument("--kind", choices=("handset", "emulator"), default="handset")
    parser.add_argument("--duration", type=int, default=30, help="Observation window, 0–300 seconds; 0 takes snapshots only")
    parser.add_argument("--interval", type=int, default=10, help="Memory/CPU/thermal sample spacing, 5–60 seconds")
    parser.add_argument("--output", type=Path, default=Path(tempfile.gettempdir()) / "mihon-device-validation")
    parser.add_argument("--apk", type=Path, help="Optional exact installed APK artifact to hash; does not install it")
    parser.add_argument("--adb", default="adb", help="ADB executable path")
    result = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]*", result.serial):
        parser.error("Invalid adb serial")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", result.package):
        parser.error("Invalid Android package name")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", result.scenario):
        parser.error("Scenario must contain 1–64 letters, digits, hyphens, or underscores")
    if not 0 <= result.duration <= 300 or not 5 <= result.interval <= 60:
        parser.error("Duration must be 0–300 and interval 5–60 seconds")
    if result.apk is not None and not result.apk.is_file():
        parser.error("The supplied APK does not exist")
    return result


def main():
    args = arguments()
    adb = shutil.which(args.adb)
    if adb is None:
        raise SystemExit("ADB executable not found")
    prefix = [adb, "-s", args.serial]
    state, output = execute(prefix + ["get-state"])
    if state["exit_code"] != 0 or output.strip() != b"device":
        raise SystemExit("Selected adb target is unavailable or unauthorized: " + output.decode(errors="replace"))
    properties = {}
    preflight = []
    for name in PROPERTIES:
        command, value = execute(prefix + ["shell", "getprop", name])
        properties[name] = value.decode(errors="replace").strip() if command["exit_code"] == 0 else None
        preflight.append(command)
    emulator = (
        args.serial.startswith("emulator-")
        or properties.get("ro.kernel.qemu") == "1"
        or properties.get("ro.boot.qemu") == "1"
        or properties.get("ro.hardware") in ("ranchu", "goldfish")
        or (properties.get("ro.product.model") or "").startswith("sdk_gphone")
    )
    if args.kind == "handset" and emulator:
        raise SystemExit("Refusing to label an emulator capture as a handset benchmark. Use --kind emulator for diagnostics.")
    if args.kind == "emulator" and not emulator:
        raise SystemExit("Target is not identified as an emulator; inspect its identity and use --kind handset if appropriate.")
    installed, package_path = execute(prefix + ["shell", "pm", "path", args.package])
    if installed["exit_code"] != 0 or b"package:" not in package_path:
        raise SystemExit("Selected package is not installed on the explicitly selected target")

    os.umask(0o077)
    run_name = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run = args.output / f"{run_name}-{args.kind}-{args.scenario}-{uuid.uuid4().hex[:8]}"
    run.mkdir(parents=True, exist_ok=False)
    manifest = {
        "capture_format": 1, "kind": args.kind, "scenario": args.scenario,
        "serial": args.serial, "package": args.package, "properties": properties,
        "requested_window_seconds": args.duration, "sample_interval_seconds": args.interval,
        "started_utc": utc(), "commands": preflight + [state, installed],
        "read_only_device_commands": True, "completed": False,
        "limitations": [
            "Snapshots are not a CPU sampling profile, native allocation trace, or GPU memory inventory.",
            "gfxinfo counters/history are not reset and can include frames outside this observation window.",
            "PID-scoped, bounded logs can omit short-lived processes or overwritten buffer entries.",
            "Unavailable or denied commands remain recorded; missing data is not a zero measurement.",
        ],
    }
    if args.apk:
        digest = hashlib.sha256()
        with args.apk.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        manifest["apk"] = {"path": str(args.apk.resolve()), "sha256": digest.hexdigest(), "bytes": args.apk.stat().st_size}
    repo = Path(__file__).resolve().parent.parent
    revision, revision_bytes = execute(["git", "-C", str(repo), "rev-parse", "HEAD"])
    manifest["host_repository_revision"] = revision_bytes.decode(errors="replace").strip() if revision["exit_code"] == 0 else None
    manifest["host_revision_is_not_installed_apk_proof"] = True
    seen_pids = set()

    def save_manifest():
        (run / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    def capture(name, *shell_arguments):
        metadata, body = execute(prefix + ["shell", *shell_arguments])
        metadata["artifact"] = name + ".txt"
        manifest["commands"].append(metadata)
        (run / metadata["artifact"]).write_bytes(body)
        return body.decode(errors="replace")

    def process_ids(label):
        value = capture(label + "-pids", "pidof", args.package)
        pids = [part for part in value.split() if part.isdigit()][:20]
        seen_pids.update(pids)
        return pids

    def sample(label):
        capture(label + "-device-time", "date", "+%s")
        pids = process_ids(label)
        capture(label + "-memory", "dumpsys", "-t", "8", "meminfo", args.package)
        capture(label + "-thermal", "dumpsys", "-t", "8", "thermalservice")
        capture(label + "-battery", "dumpsys", "-t", "8", "battery")
        if pids:
            capture(label + "-cpu", "top", "-b", "-n", "1", "-p", ",".join(pids))
        save_manifest()

    def boundary(label):
        sample(label)
        capture(label + "-frames", "dumpsys", "-t", "8", "gfxinfo", args.package, "framestats")
        capture(label + "-services", "dumpsys", "-t", "8", "activity", "services", args.package)
        capture(label + "-standby", "am", "get-standby-bucket", args.package)
        capture(label + "-deep-idle", "dumpsys", "-t", "8", "deviceidle", "get", "deep")
        capture(label + "-light-idle", "dumpsys", "-t", "8", "deviceidle", "get", "light")

    print(f"Capturing {args.kind} diagnostics in {run}", flush=True)
    save_manifest()
    try:
        manifest["kernel_page_size"] = capture("kernel-page-size", "getconf", "PAGE_SIZE").strip()
        capture("kernel", "uname", "-a")
        capture("system-memory", "cat", "/proc/meminfo")
        capture("package", "dumpsys", "-t", "8", "package", args.package)
        capture("display", "dumpsys", "-t", "8", "display")
        for scope, name in (("system", "peak_refresh_rate"), ("system", "min_refresh_rate"),
                            ("system", "screen_brightness"), ("global", "low_power")):
            capture("setting-" + name, "settings", "get", scope, name)
        boundary("before")
        manifest["flow_start_utc"] = utc()
        print(f"START the named flow now; observation window is {args.duration} seconds.", flush=True)
        deadline = time.monotonic() + args.duration
        index = 0
        while time.monotonic() < deadline:
            time.sleep(min(args.interval, max(0, deadline - time.monotonic())))
            if time.monotonic() >= deadline:
                break
            index += 1
            sample(f"sample-{index:03d}")
        manifest["flow_end_utc"] = utc()
        boundary("after")
        for pid in sorted(seen_pids, key=int):
            capture("logcat-pid-" + pid, "logcat", "-d", "-v", "epoch", "--pid", pid,
                    "-b", "main", "-b", "system", "-b", "crash", "-t", "2000")
        manifest["completed"] = True
    except KeyboardInterrupt:
        manifest["interrupted"] = True
        print("Capture interrupted; collected artifacts are retained.", file=sys.stderr)
    finally:
        manifest["finished_utc"] = utc()
        manifest["observed_pids"] = sorted(seen_pids, key=int)
        save_manifest()
        print(run, flush=True)
    return 0 if manifest["completed"] else 130


if __name__ == "__main__":
    sys.exit(main())
