#!/usr/bin/env python3
"""Offline, credential-safe evidence summary. Never makes network/device calls.

Input captures: {metadata, response, request?}; bodies may be objects or serialized
JSON strings. Output intentionally excludes source/translated text, arbitrary IDs,
paths, URLs, headers, prompts, errors, and unknown model names. See usage document.
"""

from __future__ import annotations

import argparse
import base64
from collections import Counter
from decimal import Decimal, InvalidOperation
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import tempfile

from PIL import Image

MAX_FILE_BYTES = 64 * 1024 * 1024
SHA256 = re.compile(r"[0-9a-f]{64}")
KNOWN_MODEL = re.compile(r"(?:gemini-[0-9]+(?:\.[0-9]+)?-(?:flash|pro|flash-lite)(?:-[0-9]{3})?|gpt-[0-9]+(?:\.[0-9]+)?(?:-mini|-nano)?|o[0-9]+(?:-mini)?)")
TOKEN_FIELDS = ("input", "visible_output", "reasoning", "output_including_reasoning", "cached_input", "provider_total")


class ReviewError(ValueError):
    """Messages are fixed strings and must never include input values."""


def digest(value: bytes | str) -> str:
    return hashlib.sha256(value.encode() if isinstance(value, str) else value).hexdigest()


def number(value, *, maximum=10**12):
    if isinstance(value, str) and re.fullmatch(r"[0-9]{1,13}", value):
        value = int(value)
    return value if type(value) is int and 0 <= value <= maximum else None


def decimal_string(value):
    if not isinstance(value, (str, int)) or isinstance(value, bool):
        return None
    if len(str(value)) > 64:
        return None
    try:
        parsed = Decimal(value)
    except InvalidOperation:
        return None
    return format(parsed, "f") if parsed.is_finite() and 0 <= parsed <= 10**12 and parsed.as_tuple().exponent >= -18 else None


def read_json(path: Path):
    if path.stat().st_size > MAX_FILE_BYTES:
        raise ReviewError("INPUT_FILE_EXCEEDS_64_MIB")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, UnicodeError):
        raise ReviewError("INVALID_INPUT_JSON") from None


def read_events(path):
    if path.stat().st_size > MAX_FILE_BYTES:
        raise ReviewError("INPUT_FILE_EXCEEDS_64_MIB")
    try:
        text = path.read_text(encoding="utf-8")
        if text.lstrip().startswith("["):
            return json.loads(text)
        return [json.loads(line) for line in text.splitlines() if line.strip()]
    except (ValueError, UnicodeError):
        raise ReviewError("INVALID_EVENT_JSON") from None


def expand_captures(paths):
    result = []
    for path in paths:
        if not path.is_dir():
            result.append(path)
        elif (path / "metadata.json").is_file():
            result.append(path)
        else:
            result.extend(sorted(p.parent for p in path.rglob("metadata.json")))
    if not result:
        raise ReviewError("NO_CAPTURE_FILES")
    return result


def read_capture(path):
    if not path.is_dir():
        return read_json(path)
    result = {"metadata": read_json(path / "metadata.json")}
    for field in ("request", "response"):
        file = path / (field + ".json")
        if file.is_file():
            if file.stat().st_size > MAX_FILE_BYTES:
                raise ReviewError("INPUT_FILE_EXCEEDS_64_MIB")
            result[field] = file.read_text(encoding="utf-8")
    return result


def object_body(value):
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except ValueError:
            return None
    return value if isinstance(value, dict) else None


def safe_model(value):
    if not isinstance(value, str):
        return None
    return value if KNOWN_MODEL.fullmatch(value) else "unlisted-model:" + digest(value)[:16]


def write_private(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".capture-review-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            json.dump(value, out, ensure_ascii=False, indent=2, allow_nan=False)
            out.write("\n")
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def usage(response, dialect_hint=None):
    """Gemini candidates exclude thoughts; OpenAI output includes reasoning."""
    warnings = []
    if isinstance(response.get("usageMetadata"), dict) or dialect_hint == "vertex" and "usage" not in response:
        raw = response.get("usageMetadata", {})
        inp, visible, reasoning = (number(raw.get(k)) for k in ("promptTokenCount", "candidatesTokenCount", "thoughtsTokenCount"))
        output = visible + reasoning if visible is not None and reasoning is not None else None
        cached, total = number(raw.get("cachedContentTokenCount")), number(raw.get("totalTokenCount"))
        dialect = "vertex"
    else:
        raw = response.get("usage") if isinstance(response.get("usage"), dict) else {}
        responses = "input_tokens" in raw or "output_tokens" in raw or "output" in response
        input_key, output_key = ("input_tokens", "output_tokens") if responses else ("prompt_tokens", "completion_tokens")
        inp, output = number(raw.get(input_key)), number(raw.get(output_key))
        output_details = raw.get(output_key + "_details") or {}
        input_details = raw.get(input_key + "_details") or {}
        reasoning = number(output_details.get("reasoning_tokens")) if isinstance(output_details, dict) else None
        cached = number(input_details.get("cached_tokens")) if isinstance(input_details, dict) else None
        total = number(raw.get("total_tokens"))
        visible = output - reasoning if output is not None and reasoning is not None and output >= reasoning else None
        if output is not None and reasoning is not None and reasoning > output:
            warnings.append("REASONING_EXCEEDS_OUTPUT")
        dialect = ("openai_responses" if responses else "openai_chat") if raw else dialect_hint or "unknown"
    if cached is not None and inp is not None and cached > inp:
        warnings.append("CACHED_INPUT_EXCEEDS_INPUT")
    computed = inp + output if inp is not None and output is not None else None
    if total is not None and computed is not None and total != computed:
        warnings.append("PROVIDER_TOTAL_MISMATCH")
    return {"dialect": dialect, "input": inp, "visible_output": visible, "reasoning": reasoning,
            "output_including_reasoning": output, "cached_input": cached, "provider_total": total,
            "computed_total": computed, "warnings": warnings}


def sum_usage(rows):
    return {key: {"known_sum": sum(row[key] for row in rows if row.get(key) is not None),
                  "missing_attempts": sum(row.get(key) is None for row in rows)} for key in TOKEN_FIELDS}


def decode_pages(response):
    try:
        if "candidates" in response:
            candidates = response["candidates"]
            if not isinstance(candidates, list) or len(candidates) != 1:
                return [], "AMBIGUOUS_CANDIDATES"
            candidate = candidates[0]
            if candidate.get("finishReason") != "STOP":
                return [], "UNSUCCESSFUL_FINISH"
            text = "".join(p.get("text", "") for p in candidate["content"]["parts"] if not p.get("thought"))
        elif "choices" in response:
            if len(response["choices"]) != 1:
                return [], "AMBIGUOUS_CANDIDATES"
            choice = response["choices"][0]
            if choice.get("finish_reason") != "stop" or choice["message"].get("refusal"):
                return [], "UNSUCCESSFUL_FINISH"
            text = choice["message"]["content"]
        elif "output" in response:
            if response.get("status") != "completed":
                return [], "UNSUCCESSFUL_FINISH"
            parts = [p for item in response["output"] if item.get("type") == "message" for p in item.get("content", [])]
            if any(p.get("type") == "refusal" for p in parts):
                return [], "UNSUCCESSFUL_FINISH"
            text = "".join(p.get("text", "") for p in parts if p.get("type") == "output_text")
        else:
            return [], "NO_GENERATION_BODY"
        decoded = json.loads(text)
        if not isinstance(decoded.get("pages"), list):
            return [], "MALFORMED_GENERATION"
        if not all(isinstance(page, dict) and isinstance(page.get("imageId"), str) and isinstance(page.get("regions"), list) for page in decoded["pages"]):
            return [], "MALFORMED_GENERATION"
        return decoded["pages"], None
    except (ValueError, KeyError, TypeError, AttributeError):
        return [], "MALFORMED_GENERATION"


def configuration(snapshot):
    snapshot = snapshot if isinstance(snapshot, dict) else {}
    provider = snapshot.get("provider") if isinstance(snapshot.get("provider"), dict) else {}
    result = {"model": safe_model(provider.get("model"))}
    for key, allowed in {"kind": {"VERTEX_SERVICE_ACCOUNT", "VERTEX_EXPRESS", "OPENAI"},
                         "thinking": {"LOW", "MEDIUM", "HIGH", "MINIMAL"},
                         "location": {"global", "us", "eu"},
                         "dialect": {"RESPONSES", "CHAT_COMPLETIONS"},
                         "capacity": {"SHARED", "PRIORITY", "DEDICATED"}}.items():
        result[key] = provider.get(key) if provider.get(key) in allowed else None
    result["max_output_tokens"] = number(provider.get("maxOutputTokens"))
    for field in ("priorityPaygo", "provisionedThroughput", "imagePreparationEnabled"):
        result[field] = provider.get(field) if type(provider.get(field)) is bool else None
    result["mode"] = snapshot.get("mode") if snapshot.get("mode") in {"VERTEX", "MAX", "HALVING", "CUSTOM"} else None
    result["custom_batch_size"] = number(snapshot.get("customBatchSize"))
    ocr = snapshot.get("ocr") if isinstance(snapshot.get("ocr"), dict) else {}
    result["ocr_pipeline"] = ocr.get("pipeline") if ocr.get("pipeline") in {"AI", "PADDLE", "PADDLE_AI"} else None
    return result


def wire_settings(request, metadata):
    request = request or {}
    generation = request.get("generationConfig") if isinstance(request.get("generationConfig"), dict) else {}
    thinking = generation.get("thinkingConfig") or request.get("reasoning") or {}
    thinking = thinking if isinstance(thinking, dict) else {}
    level = thinking.get("thinkingLevel", thinking.get("effort"))
    headers = metadata.get("requestHeaders") if isinstance(metadata.get("requestHeaders"), dict) else {}
    capacity = next((value for key, value in headers.items() if key.lower() == "x-vertex-ai-llm-request-type"), None)
    priority = next((value for key, value in headers.items() if key.lower() == "x-vertex-ai-llm-request-priority"), None)
    media = generation.get("mediaResolution")
    return {"request_body_available": bool(request), "model": safe_model(request.get("model")),
            "thinking": level if level in {"LOW", "MEDIUM", "HIGH", "MINIMAL", "low", "medium", "high", "minimal", "none"} else None,
            "max_output_tokens": number(generation.get("maxOutputTokens", request.get("max_output_tokens", request.get("max_completion_tokens")))),
            "media_resolution": media if media in {"MEDIA_RESOLUTION_LOW", "MEDIA_RESOLUTION_MEDIUM", "MEDIA_RESOLUTION_HIGH", "MEDIA_RESOLUTION_ULTRA_HIGH"} else None,
            "shared_capacity_header": capacity if capacity in {"shared", "dedicated"} else None,
            "priority_header_present": priority is not None if isinstance(metadata.get("requestHeaders"), dict) else None}


def settings_differences(configured, attempt):
    wire = attempt["wire_settings"]
    differences = []
    for key in ("thinking", "max_output_tokens"):
        expected, actual = configured.get(key), wire.get(key)
        if expected is not None and actual is not None and str(expected).lower() != str(actual).lower():
            differences.append(key.upper() + "_DIFFERS")
    expected, actual = configured.get("model"), attempt.get("model")
    if expected is not None and actual is not None and expected != actual:
        differences.append("REPORTED_MODEL_DIFFERS")
    if configured.get("provisionedThroughput") is False and wire.get("shared_capacity_header") == "dedicated":
        differences.append("SHARED_CAPACITY_EXPECTATION_DIFFERS")
    if configured.get("priorityPaygo") is False and wire.get("priority_header_present") is True:
        differences.append("PRIORITY_HEADER_EXPECTATION_DIFFERS")
    return differences


def request_images(request):
    """Recognize the app's image markers without emitting prompts or payloads."""
    if not request:
        return {}
    streams = []
    for item in request.get("contents", []):
        streams.append(item.get("parts", []))
    for item in request.get("input", []) if isinstance(request.get("input"), list) else []:
        streams.append(item.get("content", []))
    for item in request.get("messages", []):
        if isinstance(item.get("content"), list):
            streams.append(item["content"])
    found = {}
    for stream in streams:
        current = None
        for part in stream:
            text = part.get("text")
            match = re.fullmatch(r"Image ID: (.{1,200}); original dimensions: ([0-9]{1,7})x([0-9]{1,7})", text) if isinstance(text, str) else None
            if match:
                current = match[1]
                found[current] = {"declared_size": [int(match[2]), int(match[3])], "bytes": None}
                continue
            inline = part.get("inlineData")
            encoded = inline.get("data") if isinstance(inline, dict) else None
            url = part.get("image_url")
            url = url.get("url") if isinstance(url, dict) else url
            if isinstance(url, str) and url.startswith("data:image/") and ";base64," in url:
                encoded = url.split(";base64,", 1)[1]
            if current and isinstance(encoded, str) and len(encoded) <= 16 * 1024 * 1024:
                try:
                    found[current]["bytes"] = base64.b64decode(encoded, validate=True)
                except ValueError:
                    found[current]["invalid_payload"] = True
    return found


def make_map(manifest, chapter):
    selected = manifest["five_page_chapter"] if chapter == "five" else [p["id"] for p in manifest["pages"]]
    by_id = {p["id"]: p for p in manifest["pages"]}
    return {"schema_version": 1, "association": "Explicit zero-based local chapter image identities; verify against the app before use",
            "images": [{"image_id": str(index), "fixture_page_id": page_id, "source_sha256": by_id[page_id]["sha256"]} for index, page_id in enumerate(selected)]}


def load_mapping(manifest, mapping, corpus_directory):
    if mapping.get("schema_version") != 1 or not isinstance(mapping.get("images"), list):
        raise ReviewError("INVALID_IMAGE_MAP")
    by_id = {p["id"]: (index, p) for index, p in enumerate(manifest["pages"], 1)}
    result = {}
    for entry in mapping["images"]:
        image_id = entry.get("image_id")
        if not isinstance(image_id, str) or not image_id or "~" in image_id or len(image_id) > 200 or image_id in result:
            raise ReviewError("INVALID_OR_DUPLICATE_IMAGE_ID")
        if entry.get("fixture_page_id") not in by_id:
            raise ReviewError("UNKNOWN_FIXTURE_PAGE")
        index, page = by_id[entry["fixture_page_id"]]
        if entry.get("source_sha256") != page["sha256"] or not SHA256.fullmatch(page["sha256"]):
            raise ReviewError("SOURCE_HASH_MAPPING_MISMATCH")
        path = (corpus_directory / page["file"]).resolve()
        if not path.is_relative_to(corpus_directory.resolve()) or digest(path.read_bytes()) != page["sha256"]:
            raise ReviewError("SOURCE_IMAGE_HASH_MISMATCH")
        with Image.open(path) as image:
            if image.size != (page["width"], page["height"]) or image.mode != "RGB" or digest(image.tobytes()) != page["pixel_sha256"]:
                raise ReviewError("SOURCE_PIXEL_IDENTITY_MISMATCH")
        result[image_id] = {"number": index, "page": page, "path": path}
    if not result:
        raise ReviewError("EMPTY_IMAGE_MAP")
    return result


def resolve_image(image_id, mapping):
    if image_id in mapping:
        item = mapping[image_id]
        return item, [0, 0, item["page"]["width"], item["page"]["height"]]
    match = re.fullmatch(r"(.+)~([0-9]+)_([0-9]+)_([0-9]+)_([0-9]+)", image_id)
    if not match or match[1] not in mapping:
        return None
    item = mapping[match[1]]
    x, y, width, height = map(int, match.groups()[1:])
    if not (width > 0 and height > 0 and x + width <= item["page"]["width"] and y + height <= item["page"]["height"]):
        return None
    return item, [x, y, width, height]


def union_area(rectangles):
    edges = sorted({r[0] for r in rectangles} | {r[2] for r in rectangles})
    area = 0
    for left, right in zip(edges, edges[1:]):
        spans = sorted((r[1], r[3]) for r in rectangles if r[0] <= left and r[2] >= right)
        end = -1
        length = 0
        for top, bottom in spans:
            length += max(0, bottom - max(top, end))
            end = max(end, bottom)
        area += (right - left) * length
    return area


def normalized_text(value):
    return " ".join(value.split()) if isinstance(value, str) else None


def iou(a, b):
    area = lambda r: max(0, r[2] - r[0]) * max(0, r[3] - r[1])
    common = area([max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])])
    return common / (area(a) + area(b) - common) if area(a) + area(b) > common else 0


def geometry(item, crop, region, expected):
    box = region.get("box2d")
    if not isinstance(box, list) or len(box) != 4 or any(type(v) not in (int, float) or not math.isfinite(v) or not 0 <= v <= 1000 for v in box) or box[0] >= box[2] or box[1] >= box[3]:
        return {"valid": False}
    x, y, width, height = crop
    pixels = [x + box[1] * width / 1000, y + box[0] * height / 1000, x + box[3] * width / 1000, y + box[2] * height / 1000]
    ink = expected["text_ink_box_xyxy"]
    observed_rotation = region.get("rotation") if type(region.get("rotation")) in (int, float) and math.isfinite(region["rotation"]) else None
    expected_rotation = -expected.get("rotation_degrees_ccw", 0)
    total = inside = 0
    with Image.open(item["path"]) as image:
        roi = image.crop(tuple(ink)).convert("L")
        for offset, shade in enumerate(roi.tobytes()):
            if shade >= 128:
                continue
            total += 1
            px, py = ink[0] + offset % roi.width + 0.5, ink[1] + offset // roi.width + 0.5
            inside += int(pixels[0] <= px < pixels[2] and pixels[1] <= py < pixels[3])
    return {"valid": True, "source_box_xyxy": [round(v, 4) for v in pixels], "source_crop_xywh": crop,
            "reference_ink_fully_inside_crop": x <= ink[0] and y <= ink[1] and x + width >= ink[2] and y + height >= ink[3],
            "padded_reference_iou": round(iou(pixels, expected["bounding_box_xyxy"]), 6),
            "dark_ink_total": total, "dark_ink_inside": inside,
            "dark_ink_coverage_percent": round(100 * inside / total, 4) if total else None,
            "rotation_reported": observed_rotation, "expected_android_canvas_rotation": expected_rotation,
            "rotation_difference_degrees": round(abs((observed_rotation - expected_rotation + 180) % 360 - 180), 4) if observed_rotation is not None else None,
            "metric_limitation": "Axis-aligned rectangle coverage; rotated output masks and translated text layout require reader inspection"}


def valid_regions(regions):
    identifiers = set()
    for region in regions:
        if not isinstance(region, dict) or not isinstance(region.get("id"), str) or not region["id"] or region["id"] in identifiers:
            return False
        identifiers.add(region["id"])
        box = region.get("box2d")
        if not isinstance(box, list) or len(box) != 4 or any(type(v) not in (int, float) or not math.isfinite(v) or not 0 <= v <= 1000 for v in box) or box[0] >= box[2] or box[1] >= box[3]:
            return False
        if not isinstance(region.get("sourceText"), str) or not isinstance(region.get("translatedText"), str) or type(region.get("included")) is not bool or number(region.get("readingOrder")) is None or not isinstance(region.get("type"), str):
            return False
        if region["included"] and region["sourceText"].strip() and not region["translatedText"].strip():
            return False
    return True


def payload_identity(payload, resolved):
    if not payload or payload.get("bytes") is None:
        return {"available": False}
    data = payload["bytes"]
    result = {"available": True, "encoded_sha256": digest(data), "pixel_match": None}
    try:
        with Image.open(io.BytesIO(data)) as actual:
            if actual.width * actual.height > 16_000_000:
                return {**result, "error": "IMAGE_PIXEL_LIMIT"}
            item, crop = resolved
            if actual.size != tuple(crop[2:]):
                return {**result, "pixel_match": False, "error": "TRANSMITTED_DIMENSIONS_DIFFER"}
            with Image.open(item["path"]) as original:
                x, y, w, h = crop
                expected = original.crop((x, y, x + w, y + h)).convert("RGB")
                result["pixel_match"] = digest(actual.convert("RGB").tobytes()) == digest(expected.tobytes())
    except (OSError, ValueError, Image.DecompressionBombError):
        result["error"] = "INVALID_TRANSMITTED_IMAGE"
    return result


def summarize(capture_paths, corpus_path, image_map, *, settings=None, events=None, spend=None, spend_request_ids=None):
    manifest = read_json(corpus_path)
    mapping = load_mapping(manifest, image_map, corpus_path.parent)
    attempts, non_generation, observations, seen, duplicate_files, conflicts = [], [], {}, {}, 0, 0
    configured = configuration(settings)
    capture_paths = expand_captures(capture_paths)
    for path in capture_paths:
        capture = read_capture(path)
        if not isinstance(capture, dict):
            raise ReviewError("INVALID_CAPTURE_ENVELOPE")
        metadata = capture.get("metadata") if isinstance(capture.get("metadata"), dict) else {}
        response = object_body(capture.get("response"))
        request = object_body(capture.get("request"))
        identity = metadata.get("id")
        canonical = digest(json.dumps(capture, sort_keys=True, separators=(",", ":")))
        identity = identity if isinstance(identity, str) and identity else canonical
        if identity in seen:
            if seen[identity] == canonical:
                duplicate_files += 1
                continue
            conflicts += 1
        seen[identity] = canonical
        if metadata.get("operation") in {"countTokens", "input_tokens", "countInputTokens"}:
            non_generation.append({"capture_key": digest(identity), "operation": "token_count",
                                   "http_status": number(metadata.get("responseCode"))})
            continue
        pages, error = decode_pages(response or {})
        http = number(metadata.get("responseCode"))
        if http is not None and not 200 <= http < 300:
            error, pages = "HTTP_FAILURE", []
        if metadata.get("responseTruncated") is True or metadata.get("responseBodyCompleted") is False:
            error, pages = "INCOMPLETE_RESPONSE_CAPTURE", []
        timestamp = number(metadata.get("startedAt"), maximum=10**15)
        completed_at = number(metadata.get("completedAt"), maximum=10**15)
        order = timestamp if timestamp is not None else len(attempts)
        payloads = request_images(request)
        actual_ids = [page["imageId"] for page in pages]
        repeated_ids = {key for key, value in Counter(actual_ids).items() if value > 1}
        dialect_hint = "vertex" if metadata.get("operation") == "generateContent" or request and "contents" in request else None
        attempt = {"capture_key": digest(identity), "capture_content_sha256": canonical,
                   "batch_key": digest(str(metadata["batchId"])) if metadata.get("batchId") is not None else None,
                   "attempt": number(metadata.get("attempt")), "http_status": http,
                   "started_epoch_millis": timestamp,
                   "duration_millis": completed_at - timestamp if timestamp is not None and completed_at is not None and completed_at >= timestamp else None,
                   "capture_complete": metadata.get("responseBodyCompleted") is True and metadata.get("responseTruncated") is False,
                   "capture_state": metadata.get("state") if metadata.get("state") in {"COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED", "TRUNCATED", "RUNNING"} else None,
                   "error": error, "model": safe_model((response or {}).get("modelVersion", (response or {}).get("model"))),
                   "wire_settings": wire_settings(request, metadata), "usage": usage(response or {}, dialect_hint),
                   "response_pages": len(pages), "duplicate_page_ids": len(repeated_ids),
                   "request_image_count": len(payloads) if request is not None else None,
                   "requested_fixture_pages": [resolved[0]["number"] for image_id in payloads if (resolved := resolve_image(image_id, mapping))],
                   "missing_requested_page_ids": len(set(payloads) - set(actual_ids)) if request is not None else None,
                   "unexpected_page_ids": len(set(actual_ids) - set(payloads)) if payloads else None,
                   "unmapped_pages": 0, "invalid_page_schemas": 0, "image_evidence": []}
        attempt["configured_effective_differences"] = settings_differences(configured, attempt)
        for page in pages:
            resolved = resolve_image(page["imageId"], mapping)
            if not resolved:
                attempt["unmapped_pages"] += 1
                continue
            if not valid_regions(page["regions"]):
                attempt["invalid_page_schemas"] += 1
                continue
            item, crop = resolved
            evidence = payload_identity(payloads.get(page["imageId"]), resolved)
            attempt["image_evidence"].append({"fixture_page_number": item["number"], "image_key": digest(page["imageId"]), **evidence})
            if page["imageId"] in repeated_ids or evidence.get("pixel_match") is False:
                continue
            if payloads and page["imageId"] not in payloads:
                continue
            previous = observations.get(page["imageId"])
            if previous is None or order >= previous["order"]:
                observations[page["imageId"]] = {"item": item, "crop": crop, "regions": page["regions"], "order": order, "capture_key": digest(identity)}
        attempts.append(attempt)
    page_reports = []
    for item in mapping.values():
        page = item["page"]
        observed = [o for o in observations.values() if o["item"]["number"] == item["number"]]
        rects = [[o["crop"][0], o["crop"][1], o["crop"][0] + o["crop"][2], o["crop"][1] + o["crop"][3]] for o in observed]
        source_area = page["width"] * page["height"]
        region_reports, unknown, nonexact = [], 0, 0
        expected_texts = {r["source_text"] for r in page["regions"]}
        normalized_expected_texts = {normalized_text(value) for value in expected_texts}
        text_counts = Counter(normalized_text(r["source_text"]) for r in page["regions"])
        for observation in observed:
            nonexact += sum(not isinstance(r, dict) or r.get("sourceText") not in expected_texts for r in observation["regions"])
            unknown += sum(not isinstance(r, dict) or normalized_text(r.get("sourceText")) not in normalized_expected_texts for r in observation["regions"])
        for index, expected in enumerate(page["regions"], 1):
            normalized_source = normalized_text(expected["source_text"])
            matched = [(o, r) for o in observed for r in o["regions"] if text_counts[normalized_source] == 1 and isinstance(r, dict) and normalized_text(r.get("sourceText")) == normalized_source]
            checks = [{"capture_key": o["capture_key"], "included_matches": r.get("included") is expected["include"],
                       "ignored_reason_present": isinstance(r.get("ignoredReason"), str) and bool(r["ignoredReason"].strip()) if not expected["include"] else None,
                       "transcription_match": "exact" if r["sourceText"] == expected["source_text"] else "whitespace_only",
                       "role_matches": r.get("type") == expected["role"], "reading_order": number(r.get("readingOrder")),
                       "translation_present": isinstance(r.get("translatedText"), str) and bool(r["translatedText"].strip()),
                       "geometry": geometry(item, o["crop"], r, expected)} for o, r in matched]
            region_reports.append({"region_number": index, "exact_source_match_observations": sum(r["sourceText"] == expected["source_text"] for _, r in matched),
                                   "whitespace_only_match_observations": sum(r["sourceText"] != expected["source_text"] for _, r in matched),
                                   "repeated_reference_text_needs_manual_association": text_counts[normalized_source] > 1,
                                   "human_meaning_review": "pending", "observations": checks})
        # Orders are comparable inside each response/tile, not across independently numbered tiles.
        order_checks = []
        for observation in observed:
            indices = []
            for r in observation["regions"]:
                if not isinstance(r, dict):
                    continue
                matches = [e for e in page["regions"] if normalized_text(e["source_text"]) == normalized_text(r.get("sourceText")) and e["include"]]
                if len(matches) == 1:
                    indices.append((number(r.get("readingOrder")), matches[0]["reading_order"]))
            if len(indices) > 1:
                valid = all(a is not None for a, _ in indices) and len({a for a, _ in indices}) == len(indices)
                order_checks.append(valid and [b for _, b in sorted(indices)] == sorted(b for _, b in indices))
        page_reports.append({"fixture_page_number": item["number"], "source_sha256": page["sha256"],
                             "source_pixel_sha256": page["pixel_sha256"], "source_dimensions": [page["width"], page["height"]],
                             "observed_crop_coverage_percent": round(100 * union_area(rects) / source_area, 4),
                             "full_image_response_coverage": union_area(rects) == source_area,
                             "expected_regions": len(page["regions"]), "exact_matched_regions": sum(r["exact_source_match_observations"] > 0 for r in region_reports),
                             "matched_regions_with_whitespace_normalization": sum(bool(r["observations"]) for r in region_reports),
                             "unexpected_or_nonexact_regions": nonexact, "unexpected_or_nonmatching_regions": unknown,
                             "blank_hallucination": not page["regions"] and unknown > 0,
                             "relative_order_checks": order_checks, "regions": region_reports})
    totals = sum_usage([a["usage"] for a in attempts])
    event_report = summarize_events(events, attempts, totals) if events is not None else {"available": False}
    spend_report = summarize_spend(spend, spend_request_ids, totals, len(attempts)) if spend is not None else {"available": False}
    issues = []
    if conflicts:
        issues.append("CONFLICTING_CAPTURE_IDENTITIES")
    if any(a["configured_effective_differences"] for a in attempts):
        issues.append("CONFIGURED_EFFECTIVE_SETTINGS_DIFFER")
    if any(a["error"] or a["duplicate_page_ids"] or a["unmapped_pages"] or a["invalid_page_schemas"] for a in attempts):
        issues.append("CAPTURE_OR_PAGE_COMPLETENESS_FAILURE")
    if any(not a["capture_complete"] for a in attempts):
        issues.append("CAPTURE_COMPLETENESS_UNKNOWN_OR_INCOMPLETE")
    if any(a["usage"]["warnings"] for a in attempts) or any(totals[key]["missing_attempts"] for key in ("input", "visible_output", "reasoning")):
        issues.append("USAGE_RECONCILIATION_FOLLOWUP")
    if any(not evidence["available"] for a in attempts for evidence in a["image_evidence"]):
        issues.append("TRANSMITTED_PIXEL_IDENTITY_UNVERIFIED")
    if any(evidence.get("pixel_match") is False or evidence.get("error") for a in attempts for evidence in a["image_evidence"]):
        issues.append("TRANSMITTED_IMAGE_MISMATCH")
    if any(not p["full_image_response_coverage"] or p["matched_regions_with_whitespace_normalization"] != p["expected_regions"] or p["unexpected_or_nonmatching_regions"] for p in page_reports):
        issues.append("CORPUS_COVERAGE_OR_TRANSCRIPTION_FOLLOWUP")
    if any(r["whitespace_only_match_observations"] for p in page_reports for r in p["regions"]):
        issues.append("TRANSCRIPTION_WHITESPACE_ONLY_DIFFERENCES")
    if any(o["geometry"].get("dark_ink_coverage_percent", 0) < 99 for p in page_reports for r in p["regions"] for o in r["observations"]):
        issues.append("REGION_INK_COVERAGE_BELOW_99_PERCENT")
    if any((o["geometry"].get("rotation_difference_degrees") or 0) > 1 for p in page_reports for r in p["regions"] for o in r["observations"]):
        issues.append("REGION_ROTATION_DIFFERS_FROM_FIXTURE")
    return {"schema_version": 1, "assessment": "automatic_controlled_fixture_evidence", "human_meaning_review": "pending",
            "sanitization": "Allowlisted values and cryptographic identities only; no raw text, arbitrary IDs, paths, headers, URLs, or errors",
            "methodology": {"transcription": "Exact strings first; collapsed whitespace equivalence is separately labeled and does not remove word boundaries or certify meaning", "ink": "Dark pixels below grayscale128 within known fixture ink bounds; pixel-center containment in decoded source-coordinate rectangle", "order": "Relative ranks checked within each returned tile; cross-tile order is not inferred", "identity": "Explicit image map validates original encoded and pixel hashes. Transmitted pixel identity is verified only when actual request image bytes are available", "completeness": "Union of accepted returned crops plus expected-region presence with exact or explicitly labeled whitespace-only text; does not certify app queue completion", "usage": "All unique generation captures count, including failed attempts. Vertex candidates plus thoughts; OpenAI output already includes reasoning. Cached input is a subset"},
            "capture_files": len(capture_paths), "unique_attempts": len(attempts), "duplicate_capture_files_ignored": duplicate_files,
            "non_generation_captures": non_generation,
            "conflicting_capture_identities": conflicts, "configured_snapshot": configured,
            "usage_totals": totals, "attempts": attempts, "pages": page_reports, "events": event_report, "spend": spend_report,
            "followup_codes": issues, "acceptance": "Evidence only; human meaning and reader overlay acceptance remain pending"}


def summarize_events(events, attempts, totals):
    if not isinstance(events, list):
        raise ReviewError("INVALID_EVENT_ARRAY")
    seen, rows = set(), []
    for event in events:
        if not isinstance(event, dict) or event.get("stage") != "USAGE":
            continue
        identity = event.get("id")
        identity = digest(str(identity)) if identity is not None else digest(json.dumps(event, sort_keys=True))
        if identity in seen:
            continue
        seen.add(identity)
        details = event.get("details") if isinstance(event.get("details"), dict) else {}
        rows.append({"input": number(details.get("inputTokens")), "visible_output": number(details.get("outputTokens")), "reasoning": number(details.get("reasoningTokens"))})
    # App outputTokens is dialect dependent; only compare its field to raw provider output.
    raw_outputs = sum(a["usage"]["visible_output"] if a["usage"]["dialect"] == "vertex" else a["usage"]["output_including_reasoning"] for a in attempts if (a["usage"]["visible_output"] if a["usage"]["dialect"] == "vertex" else a["usage"]["output_including_reasoning"]) is not None)
    sums = {key: sum(r[key] for r in rows if r[key] is not None) for key in ("input", "visible_output", "reasoning")}
    complete = all(all(r[key] is not None for key in r) for r in rows) and len(rows) == len(attempts) and all(totals[key]["missing_attempts"] == 0 for key in ("input", "visible_output", "reasoning"))
    known_matches = sums["input"] == totals["input"]["known_sum"] and sums["visible_output"] == raw_outputs and sums["reasoning"] == totals["reasoning"]["known_sum"]
    return {"available": True, "unique_usage_events": len(rows), "capture_attempt_count_matches": len(rows) == len(attempts),
            "event_known_totals": {"input": sums["input"], "provider_output_field": sums["visible_output"], "reasoning": sums["reasoning"]},
            "aggregate_comparison_complete": complete,
            "known_aggregate_matches": known_matches,
            "aggregate_matches": complete and known_matches,
            "limitation": "Aggregate consistency only; event IDs are deduplicated but this does not prove per-attempt association"}


def summarize_spend(spend, selected_ids, totals, attempt_count):
    if not isinstance(spend, dict):
        raise ReviewError("INVALID_SPEND_SUMMARY")
    keys = ("hard_ceiling_sgd", "dispatch_ceiling_sgd", "estimated_usage_sgd", "outstanding_reservations_sgd", "recorded_billing_sgd", "conservative_exposure_sgd", "available_to_reserve_sgd")
    result = {"available": True, "amounts": {key: decimal_string(spend.get(key)) for key in keys},
              "new_requests_allowed": spend.get("new_requests_allowed") if type(spend.get("new_requests_allowed")) is bool else None,
              "scope": "Whole-session money totals; selected reservations only for token comparison", "billed_amounts_are_separate": True}
    if not selected_ids:
        return {**result, "token_reconciliation": "NOT_BOUND_TO_RESERVATIONS"}
    requests = spend.get("requests") if isinstance(spend.get("requests"), dict) else {}
    if len(selected_ids) != len(set(selected_ids)) or any(key not in requests for key in selected_ids):
        return {**result, "token_reconciliation": "INVALID_RESERVATION_SELECTION"}
    usages = [requests[key].get("usage", {}) for key in selected_ids]
    expected = {"input_tokens": "input", "output_tokens": "visible_output", "reasoning_tokens": "reasoning"}
    complete = all(requests[key].get("state") == "settled" for key in selected_ids)
    comparisons = {}
    for ledger_key, token_key in expected.items():
        values = [number(u.get(ledger_key)) for u in usages]
        valid = all(v is not None for v in values) and totals[token_key]["missing_attempts"] == 0
        complete &= valid
        comparisons[token_key] = {"ledger_known_sum": sum(v for v in values if v is not None), "capture_known_sum": totals[token_key]["known_sum"], "matches": valid and sum(values) == totals[token_key]["known_sum"]}
    count_values = [number(u.get("attempts")) for u in usages]
    attempt_match = all(v is not None for v in count_values) and sum(count_values) == attempt_count
    return {**result, "selected_reservations": len(selected_ids), "token_reconciliation": "MATCH" if complete and attempt_match and all(v["matches"] for v in comparisons.values()) else "FOLLOWUP_REQUIRED", "tokens": comparisons, "attempt_count_matches": attempt_match}


def compare_reports(reports):
    runs, identities = [], []
    for index, report in enumerate(reports, 1):
        if report.get("schema_version") != 1 or report.get("assessment") != "automatic_controlled_fixture_evidence":
            raise ReviewError("NOT_A_CAPTURE_REVIEW_REPORT")
        pages, attempts = report["pages"], report["attempts"]
        identities.append(sorted((p["source_sha256"], p["source_pixel_sha256"]) for p in pages))
        observations = [o for page in pages for region in page["regions"] for o in region["observations"]]
        geometry_rows = [o["geometry"] for o in observations if o["geometry"].get("valid")]
        coverage = [g["dark_ink_coverage_percent"] for g in geometry_rows if type(g.get("dark_ink_coverage_percent")) in (int, float) and math.isfinite(g["dark_ink_coverage_percent"]) and 0 <= g["dark_ink_coverage_percent"] <= 100]
        configured = report.get("configured_snapshot") or {}
        runs.append({"run_number": index,
                     "configured_mode": configured.get("mode") if configured.get("mode") in {"VERTEX", "MAX", "HALVING", "CUSTOM"} else None,
                     "configured_thinking": configured.get("thinking") if configured.get("thinking") in {"LOW", "MEDIUM", "HIGH"} else None,
                     "observed_wire_thinking": sorted({a["wire_settings"]["thinking"] for a in attempts if a["wire_settings"].get("thinking") in {"LOW", "MEDIUM", "HIGH", "low", "medium", "high"}}),
                     "generation_attempts": number(report.get("unique_attempts")), "token_count_requests": len(report.get("non_generation_captures", [])),
                     "pages": len(pages), "fully_covered_pages": sum(p.get("full_image_response_coverage") is True for p in pages),
                     "expected_regions": sum(number(p.get("expected_regions")) or 0 for p in pages),
                     "exact_matched_regions": sum(number(p.get("exact_matched_regions")) or 0 for p in pages),
                     "matched_with_whitespace_normalization": sum(number(p.get("matched_regions_with_whitespace_normalization")) or 0 for p in pages),
                     "minimum_dark_ink_coverage_percent": min(coverage) if coverage else None,
                     "geometry_observations": len(geometry_rows), "role_mismatch_observations": sum(o.get("role_matches") is False for o in observations),
                     "failed_relative_order_checks": sum(check is False for p in pages for check in p.get("relative_order_checks", [])),
                     "transmitted_pixel_matches": sum(e.get("pixel_match") is True for a in attempts for e in a.get("image_evidence", [])),
                     "usage_totals": {key: {"known_sum": number(report["usage_totals"][key].get("known_sum")), "missing_attempts": number(report["usage_totals"][key].get("missing_attempts"))} for key in TOKEN_FIELDS}})
    return {"schema_version": 1, "assessment": "automatic_mode_evidence_comparison", "human_meaning_review": "pending",
            "same_reference_image_identities": bool(identities) and all(value == identities[0] for value in identities),
            "runs": runs, "limitation": "Descriptive observations only. Different configured settings, stochastic responses, incomplete wire evidence, or one run per mode prevent causal quality/performance claims. Raw passages and reader overlay appearance are not assessed."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path)
    parser.add_argument("--compare", type=Path, nargs="+", help="Compare sanitized review reports without reopening raw captures")
    parser.add_argument("--image-map", type=Path)
    parser.add_argument("--write-image-map", type=Path)
    parser.add_argument("--chapter", choices=("five", "all"), default="five")
    parser.add_argument("--captures", type=Path, nargs="+")
    parser.add_argument("--settings", type=Path)
    parser.add_argument("--events", type=Path)
    parser.add_argument("--spend", type=Path)
    parser.add_argument("--spend-selection", type=Path, help="JSON array of reservation IDs covering exactly these captures")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        if args.compare:
            if not args.output:
                raise ReviewError("OUTPUT_REQUIRED")
            write_private(args.output, compare_reports([read_json(path) for path in args.compare]))
        elif args.write_image_map:
            if not args.corpus:
                raise ReviewError("CORPUS_REQUIRED")
            write_private(args.write_image_map, make_map(read_json(args.corpus), args.chapter))
        else:
            if not args.corpus or not args.image_map or not args.captures or not args.output:
                raise ReviewError("IMAGE_MAP_CAPTURES_AND_OUTPUT_REQUIRED")
            optional = lambda path: read_json(path) if path else None
            report = summarize(args.captures, args.corpus, read_json(args.image_map), settings=optional(args.settings), events=read_events(args.events) if args.events else None, spend=optional(args.spend), spend_request_ids=optional(args.spend_selection))
            write_private(args.output, report)
        print(json.dumps({"output_written": True, "human_meaning_review": "pending"}))
    except ReviewError as error:
        parser.exit(1, "Review failed: " + str(error) + "\n")
    except (OSError, ValueError, TypeError, KeyError, AttributeError):
        parser.exit(1, "Review failed: INPUT_OR_OUTPUT_ERROR\n")


if __name__ == "__main__":
    main()
