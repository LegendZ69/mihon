#!/usr/bin/env python3
"""Bind owned, agent-reviewed fixture corrections to fresh saved app revisions.

This prepares localhost review responses, never edits an app database, source
image, historical result or provider setting. The app owns apply/save/Undo.
"""

import argparse
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import zipfile


DEFAULT_FIXTURE = Path(__file__).parent / "fixtures/translation-agent-reviewed-corrections.json"
MAX_JSON_BYTES = 16 * 1024 * 1024
REGION_FIELDS = ("id", "sourceText", "correctedText", "translatedText", "type",
                 "readingOrder", "rotation", "included", "ignoredReason")
REGION_DEFAULTS = {"translatedText": "", "correctedText": None, "type": "dialogue",
                   "readingOrder": 0, "rotation": 0.0, "included": True, "ignoredReason": None}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    data = Path(path).read_bytes()
    if len(data) > MAX_JSON_BYTES:
        raise ValueError("Correction input exceeds 16 MiB")
    return json.loads(data)


def load_results(path):
    """Read only chapter results from a native export, or a result/chapter JSON."""
    path = Path(path)
    if path.suffix.lower() == ".zip":
        results = []
        with zipfile.ZipFile(path) as archive:
            for item in archive.infolist():
                if item.filename.startswith("chapters/") and item.filename.endswith(".json"):
                    if item.file_size > MAX_JSON_BYTES:
                        raise ValueError("Chapter input exceeds 16 MiB")
                    results.extend(json.loads(archive.read(item))["results"])
        return results
    value = read_json(path)
    return value["results"] if "results" in value else [value]


def same_value(expected, actual):
    """Permit only the float32 coordinate rounding used by the Android wire."""
    if isinstance(expected, bool) or isinstance(actual, bool):
        return type(expected) is type(actual) and expected == actual
    if isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
        return math.isfinite(expected) and math.isfinite(actual) and math.isclose(
            expected, actual, rel_tol=0, abs_tol=0.0002)
    if isinstance(expected, dict) and isinstance(actual, dict):
        return expected.keys() == actual.keys() and all(same_value(v, actual[k]) for k, v in expected.items())
    if isinstance(expected, list) and isinstance(actual, list):
        return len(expected) == len(actual) and all(same_value(a, b) for a, b in zip(expected, actual))
    return type(expected) is type(actual) and expected == actual


def wire_region(region, width, height):
    points = region["points"]
    if len(points) < 3 or any(
        not math.isfinite(p[axis]) or not 0 <= p[axis] <= limit
        for p in points for axis, limit in (("x", width), ("y", height))
    ):
        raise ValueError("Correction geometry is outside original coordinates")
    mapped = {key: region.get(key, REGION_DEFAULTS.get(key)) for key in REGION_FIELDS}
    mapped["box2d"] = [min(p["y"] for p in points) / height * 1000,
                       min(p["x"] for p in points) / width * 1000,
                       max(p["y"] for p in points) / height * 1000,
                       max(p["x"] for p in points) / width * 1000]
    mapped["polygon"] = [[p["x"] / width * 1000, p["y"] / height * 1000] for p in points]
    return mapped


def bind(fixture, results):
    if fixture.get("schema_version") != 1 or fixture.get("kind") != "authored-controlled-agent-corrections":
        raise ValueError("Expected an owned reviewed correction fixture")
    bound = []
    for page in fixture["pages"]:
        matches = [r for r in results if r.get("imageHash") == page["image_hash"]]
        if len(matches) != 1:
            raise ValueError("Each correction needs one unambiguous saved original")
        baseline = copy.deepcopy(matches[0])
        # Kotlin serialization omits model defaults in the original sampling assets.
        baseline["regions"] = [{**REGION_DEFAULTS, **r} for r in baseline["regions"]]
        if (baseline["width"], baseline["height"]) != (page["width"], page["height"]):
            raise ValueError("Original dimensions changed")
        if type(baseline["revision"]) is not int or baseline["revision"] < 1:
            raise ValueError("Saved revision is required")
        revised = copy.deepcopy(baseline)
        regions = {r["id"]: r for r in revised["regions"]}
        if len(regions) != len(revised["regions"]):
            raise ValueError("Duplicate saved region identity")
        for change in page["changes"]:
            region = regions.get(change["region_id"])
            if region is None or any(not same_value(expected, region.get(field))
                                     for field, expected in change["expected"].items()):
                raise ValueError("Reviewed correction precondition no longer matches")
            if set(change["set"]) - {"translatedText", "type", "points", "rotation"}:
                raise ValueError("Correction attempts to change an unreviewed field")
            region.update(copy.deepcopy(change["set"]))
        width, height = baseline["width"], baseline["height"]
        bound.append({
            "image_id": baseline["imageId"], "image_hash": baseline["imageHash"],
            "width": width, "height": height, "source_revision": baseline["revision"],
            "baseline_regions": [wire_region(r, width, height) for r in baseline["regions"]],
            "proposed_regions": [wire_region(r, width, height) for r in revised["regions"]],
            "changed_ids": [c["region_id"] for c in page["changes"]],
            "detected_language": baseline.get("detectedLanguage"),
        })
    return {"schema_version": 1, "kind": "bound-agent-reviewed-corrections",
            "human_approval_claimed": False, "cloud_forwarding": False, "pages": bound}


def load_bound(path):
    value = read_json(path)
    if value.get("schema_version") != 1 or value.get("kind") != "bound-agent-reviewed-corrections":
        raise ValueError("Expected a bound correction bundle")
    if not isinstance(value.get("pages"), list) or not 1 <= len(value["pages"]) <= 12:
        raise ValueError("Bound correction bundle needs 1–12 pages")
    return value


def reviewed_response(bundle, page, baseline):
    matches = [r for r in bundle["pages"] if r["image_id"] == baseline["imageId"]
               and r["image_hash"] == baseline["imageHash"]]
    if len(matches) != 1:
        raise ValueError("Correction request has no unique bound original")
    selected = matches[0]
    if ((page["width"], page["height"]) != (selected["width"], selected["height"])
            or baseline["sourceRevision"] != selected["source_revision"]
            or page["image_sha256"] != selected["image_hash"]):
        raise ValueError("Correction source, dimensions or saved revision changed")
    if not same_value(selected["baseline_regions"], baseline["regions"]):
        raise ValueError("Correction baseline contents changed")
    return {"pages": [{"imageId": baseline["imageId"],
                       "detectedLanguage": selected["detected_language"],
                       "regions": copy.deepcopy(selected["proposed_regions"])}],
            "findings": [{"code": "AUTHORED_AGENT_CORRECTION",
                          "description": "Owned synthetic passage/geometry correction; agent-authored fixture, not model or human approval",
                          "regionIds": selected["changed_ids"], "aiConfidence": None}],
            # The protocol uses this to acknowledge complete source coverage before
            # applying a visual repair. Exact reviewed original bytes were checked
            # above; this is not approval of a fresh renderer or its output pixels.
            "visualComplete": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    parser.add_argument("--baseline", type=Path, required=True, help="Fresh native export ZIP or chapter JSON")
    parser.add_argument("--output", type=Path, required=True, help="New private response bundle; refuses overwrite")
    args = parser.parse_args()
    bundle = bind(read_json(args.fixture), load_results(args.baseline))
    bundle["fixture_sha256"] = digest(args.fixture.read_bytes())
    bundle["baseline_artifact_sha256"] = digest(args.baseline.read_bytes())
    args.output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(bundle, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")
    print(json.dumps({"pages": len(bundle["pages"]), "output_sha256": digest(args.output.read_bytes()),
                      "historical_sources_modified": False, "runtime_applied": False}))


if __name__ == "__main__":
    main()
