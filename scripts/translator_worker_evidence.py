#!/usr/bin/env python3
"""Verify a bounded Worker-fixture instrumentation ZIP before issuing a cleanup receipt.

No ADB, network, extraction or device deletion. The separate cleanup instrumentation
must receive the SHA-256 from a successful receipt and revalidate its current files.
"""

import argparse
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import uuid
import zipfile

MAX_ARCHIVE = 16 * 1024 * 1024 + 64 * 1024
MAX_LOG = 32 * 1024 * 1024
EXPECTED_FILES = {"report.json", "page-0.png", "page-1.png", "telemetry.json"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(value):
    return hashlib.sha256(value).hexdigest()


def verify(log):
    require(len(log.encode()) <= MAX_LOG, "Instrumentation log exceeds the bounded verifier")
    require("FAILURES!!!" not in log and "INSTRUMENTATION_FAILED" not in log and "INSTRUMENTATION_ABORTED" not in log,
            "Instrumentation failure cannot qualify cleanup")
    headers, completions, chunks, chunk_runs = [], [], {}, set()
    current = {}

    def consume():
        if "translation_worker_evidence_json" in current:
            value = json.loads(current["translation_worker_evidence_json"])
            if value.get("phase") == "collect":
                headers.append(value)
            elif value.get("phase") == "collect_complete":
                completions.append(value)
        if "translation_worker_evidence_chunk" in current:
            index = int(current["translation_worker_evidence_chunk_index"])
            require(0 <= index < 1024 and index not in chunks, "Missing or duplicate evidence chunk")
            raw = base64.b64decode(current["translation_worker_evidence_chunk"], validate=True)
            require(0 < len(raw) <= 24 * 1024, "Unexpected evidence chunk size")
            chunks[index] = raw
            chunk_runs.add(current["translation_worker_evidence_run"])
        current.clear()

    for line in log.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            consume()
        elif line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if separator and key.startswith("translation_worker_evidence_"):
                require(key not in current, "Duplicate status field")
                current[key] = value
    require(not current and len(headers) == len(completions) == 1, "Incomplete or ambiguous evidence stream")
    header, completion = headers[0], completions[0]
    run = header["run_id"]
    require(str(uuid.UUID(run)) == run and chunk_runs == {run}, "Evidence run identities differ")
    require(completion["run_id"] == run and completion["sha256"] == header["sha256"] and completion["bytes"] == header["bytes"],
            "Evidence completion differs from its header")
    require(type(header["chunks"]) is int and 0 < header["chunks"] < 1024, "Invalid chunk total")
    require(set(chunks) == set(range(header["chunks"])), "Missing or duplicate evidence chunk")
    archive = b"".join(chunks[index] for index in range(header["chunks"]))
    require(0 < len(archive) <= MAX_ARCHIVE and len(archive) == header["bytes"], "Archive byte count differs")
    require(re.fullmatch(r"[0-9a-f]{64}", header["sha256"]) and sha256(archive) == header["sha256"], "Archive hash differs")
    with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
        names = zipped.namelist()
        require(len(names) == 5 and set(names) == EXPECTED_FILES | {"manifest.json"}, "Unexpected archive entries")
        manifest_info = zipped.getinfo("manifest.json")
        require(manifest_info.file_size <= 64 * 1024, "Manifest exceeds the bounded verifier")
        manifest = json.loads(zipped.read(manifest_info))
        require(manifest == header["manifest"] and manifest["schema"] == 1 and manifest["run_id"] == run and
                manifest["package"] == "app.mihon.benchmark", "Manifest identity differs")
        descriptions = manifest["files"]
        require(len(descriptions) == 4 and {item["name"] for item in descriptions} == EXPECTED_FILES, "Manifest entries differ")
        bodies = {}
        total = 0
        for item in descriptions:
            info = zipped.getinfo(item["name"])
            require(type(item["bytes"]) is int and 0 < item["bytes"] <= 8 * 1024 * 1024 and info.file_size == item["bytes"], "Entry size differs")
            total += info.file_size
            require(total <= 16 * 1024 * 1024, "Archive expanded size exceeds the bounded verifier")
            with zipped.open(info) as stream:
                body = stream.read(info.file_size + 1)
            require(len(body) == info.file_size and sha256(body) == item["sha256"], "Entry hash differs")
            bodies[item["name"]] = body
    report = json.loads(bodies["report.json"])
    require(report["schema"] == 1 and report["run_id"] == run and report["package"] == "app.mihon.benchmark" and report["cloud_forwarding"] is False,
            "Worker report identity differs")
    images = report["images"]
    require(len(images) == 2 and {item["index"] for item in images} == {0, 1}, "Original image list differs")
    for image in images:
        body = bodies[f"page-{image['index']}.png"]
        require(body.startswith(b"\x89PNG\r\n\x1a\n") and sha256(body) == image["contentHash"] and len(body) == image["byteSize"], "Original image identity differs")
    result_sets = [record["results"] for record in report["records"]]
    first = next((values[0] for values in result_sets if len(values) == 1), None)
    require(first is not None, "First committed result is unavailable")
    image = next((value for value in images if value["id"] == first["imageId"]), None)
    require(image is not None and image["contentHash"] == first["imageHash"], "First committed original differs")
    preserved = {"image_id": first["imageId"], "source_index": image["index"], "image_sha256": image["contentHash"], "result_revision": first.get("revision", 1)}
    require(preserved == manifest["first_committed_page"], "First committed presentation metadata differs")
    if report["status"] == "passed":
        require(next((item for item in result_sets[-1] if item["imageId"] == first["imageId"]), None) == first, "First committed result changed")
    telemetry = json.loads(bodies["telemetry.json"])
    job_id = "worker-fixture-" + run
    require(telemetry["schema"] == 1 and telemetry["synthetic"] is True and telemetry["job_id"] == job_id, "Telemetry identity differs")
    counts = {}
    for category in ("operations", "events", "usage"):
        values = telemetry[category]
        require(len(values) <= 2000 and len({item["id"] for item in values}) == len(values), "Telemetry is oversized or duplicated")
        require(all(item["jobId"] == job_id for item in values), "Unrelated telemetry is present")
        if category == "events":
            require(all(item.get("capturePath") is None for item in values), "Unexpected API capture on a no-capture fixture")
        counts[category] = len(values)
    qualified = (report["status"] == "passed" and "failure_class" not in report and
                 all(report.get(name) is True for name in ("credential_removed", "owned_job_removed", "settings_unchanged")))
    return archive, {"schema": 1, "run_id": run, "sha256": sha256(archive), "bytes": len(archive),
                     "entry_checksums_verified": True, "cleanup_qualified": qualified,
                     "first_committed_page": preserved, "telemetry_counts": counts,
                     "scope": "First committed result; not a guarantee of source-order dispatch. No device deletion performed."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("instrumentation_log", type=Path)
    parser.add_argument("output_zip", type=Path)
    arguments = parser.parse_args()
    require(arguments.instrumentation_log.stat().st_size <= MAX_LOG, "Instrumentation log exceeds the bounded verifier")
    archive, receipt = verify(arguments.instrumentation_log.read_text())
    receipt_path = arguments.output_zip.with_suffix(arguments.output_zip.suffix + ".receipt.json")
    require(not arguments.output_zip.exists() and not receipt_path.exists(), "Output already exists; preserved without replacement")
    with arguments.output_zip.open("xb") as output:
        output.write(archive)
    with receipt_path.open("x") as output:
        json.dump(receipt, output, indent=2)
        output.write("\n")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
