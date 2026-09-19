#!/usr/bin/env python3
"""Replay geometry checkpoint deletion against the production SQLDelight statements."""
import argparse
import json
import re
import sqlite3
from pathlib import Path


def statement(source, name):
    return re.search(rf"(?m)^{name}:\n(.*?);", source, flags=re.S).group(1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source = (Path(__file__).resolve().parents[1] / "data/src/main/sqldelight/tachiyomi/data/translation.sq").read_text()
    connection = sqlite3.connect(":memory:")
    connection.executescript(source.split("\njobs:\n", 1)[0])
    connection.execute("INSERT INTO translation_jobs VALUES (?, ?, ?, ?, ?, ?)",
                       ("job", 1, 1, 0, 1, json.dumps({"geometryRecoveryId": "current"})))
    protected = {"queued", "running", "paused", "interrupted", "completed", "failed", "old_completed"}
    for identity in sorted(protected | {"ordinary"}):
        payload = {"id": identity, "jobId": "job", "stage": "GEOMETRY_CORRECTION"}
        if identity != "ordinary":
            payload["geometryCorrection"] = {
                "id": identity, "policyId": "old" if identity.startswith("old_") else "current",
                "state": identity.removeprefix("old_").upper(),
                "candidateJson": "retained raw candidate", "attempts": [{"number": 1}, {"number": 2}],
            }
        connection.execute("INSERT INTO translation_operations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                           (identity, "job", None, None, "page", "GEOMETRY_CORRECTION", identity.upper(), 1,
                            json.dumps(payload)))
    before = dict(connection.execute("SELECT id, payload FROM translation_operations"))
    connection.execute(statement(source, "deleteOperations"), {"jobId": "job"})
    after = dict(connection.execute("SELECT id, payload FROM translation_operations"))
    checks = {"retained_all_checkpoint_states": set(after) == protected,
              "checkpoint_bytes_unchanged": all(after.get(key) == before[key] for key in protected),
              "ordinary_log_deleted": "ordinary" not in after}
    report = {"checks": checks, "remaining_ids": sorted(after), "passed": all(checks.values())}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    connection.close()
    print(json.dumps(report))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
