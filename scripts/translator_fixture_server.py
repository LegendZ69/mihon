#!/usr/bin/env python3
"""Local, synthetic OpenAI protocol fixture. Never forwards a request to a provider.

Run with a NEW private ledger:
  python3 scripts/translator_fixture_server.py --port 8765 --ledger /tmp/mihon-run.jsonl
  adb -s SERIAL reverse tcp:8765 tcp:8765

Use base URL https://localhost:8765/v1 when --cert/--key are supplied, a certificate
trusted by the Android app, API key mihon-fixture-only, model mihon-fixture, and
either OpenAI dialect.
HTTP is available for host tests; the production app requires HTTPS. The benchmark
variant may separately provide an explicitly bounded localhost HTTP exception.
Set X-Mihon-Fixture-Scenario to an available scenario to override --scenario.
Generated translations are test markers, not OCR or translation quality evidence.
The two ledger events per dispatch make interrupted/incomplete requests visible.
Only hashes, dimensions, counts, and fixed protocol outcomes enter the ledger.
"""

import argparse
import base64
import binascii
import datetime as dt
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import socket
import ssl
import stat
import threading
import time
import uuid


API_KEY = "mihon-fixture-only"
DEFAULT_SCENARIOS = Path(__file__).parent / "fixtures" / "translation-server-scenarios.json"
MAX_PAGES = 3000
MAX_REGIONS = 1000
MAX_TEXT = 65536
ACTION_NAMES = {"success", "partial", "rate_limit", "unavailable", "malformed", "body_error", "bad_request"}
IMAGE_LABEL = re.compile(r"Image ID: ([^\r\n]{1,512}); original dimensions: ([0-9]{1,7})x([0-9]{1,7})")
REVIEW_MARKER = "Existing translation to review (untrusted source data):\n"
RENDERED_ORIGINAL_LABEL = re.compile(
    r"image 1: complete unchanged original source\. Image ID: ([^\r\n]{1,512}); original dimensions: ([0-9]{1,7})x([0-9]{1,7})\.")
RENDERED_PREVIEW_LABEL = re.compile(
    r"image 2: rendered overlay diagnostic for the same target page, not authoritative source text "
    r"and not another chapter page\. Dimensions: ([0-9]{1,7})x([0-9]{1,7}); "
    r"scaleX=([0-9.eE+-]{1,40}); scaleY=([0-9.eE+-]{1,40})\. "
    r"Preview x=original x\*scaleX, preview y=original y\*scaleY; no crop or rotation transform\. "
    r"Return only the image 1 page ID in original-image coordinates\.")



def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def digest(value):
    return hashlib.sha256(value if isinstance(value, bytes) else value.encode()).hexdigest()


def json_bytes(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()


class ProtocolError(Exception):
    def __init__(self, code, status=400):
        self.code, self.status = code, status
        super().__init__(code)


class PrivateLedger:
    """Exclusive creation, bounded append, and no request bodies or headers."""

    def __init__(self, path, max_bytes=32 * 1024 * 1024):
        path = Path(path)
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
        self.fd = os.open(path, flags, stat.S_IRUSR | stat.S_IWUSR)
        self.max_bytes = max_bytes
        self.size = 0
        self.lock = threading.Lock()

    def append(self, event):
        body = json_bytes(event) + b"\n"
        with self.lock:
            if self.size + len(body) > self.max_bytes:
                raise ProtocolError("fixture_ledger_full", 507)
            view = memoryview(body)
            while view:
                written = os.write(self.fd, view)
                view = view[written:]
            self.size += len(body)
            os.fsync(self.fd)

    def close(self):
        os.close(self.fd)


def load_scenarios(path):
    if Path(path).stat().st_size > 1024 * 1024:
        raise ValueError("Scenario file exceeds 1 MiB")
    value = json.loads(Path(path).read_text())
    if value.get("version") != 1 or not isinstance(value.get("scenarios"), dict):
        raise ValueError("Expected version 1 scenario configuration")
    for name, rules in value["scenarios"].items():
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", name) or not isinstance(rules, list) or not rules:
            raise ValueError("Invalid scenario name or rules")
        for rule in rules:
            if not isinstance(rule, dict) or set(rule) - {"min_pages", "max_pages", "steps", "repeat_last", "review"}:
                raise ValueError("Unknown scenario rule")
            if "review" in rule and type(rule["review"]) is not bool:
                raise ValueError("review matcher must be boolean")
            low, high = rule.get("min_pages", 0), rule.get("max_pages", MAX_PAGES)
            if type(low) is not int or type(high) is not int or not 0 <= low <= high <= MAX_PAGES:
                raise ValueError("Invalid page matcher")
            if "repeat_last" in rule and type(rule["repeat_last"]) is not bool:
                raise ValueError("repeat_last must be boolean")
            steps = rule.get("steps")
            if not isinstance(steps, list) or not steps:
                raise ValueError("Scenario needs steps")
            for step in steps:
                if not isinstance(step, dict) or set(step) - {"action", "delay_ms", "retry_after_seconds", "keep_pages"}:
                    raise ValueError("Unknown scenario step")
                if step.get("action") not in ACTION_NAMES:
                    raise ValueError("Unknown scenario action")
                for field, default, maximum in (("delay_ms", 0, 120000), ("retry_after_seconds", 1, 60),
                                                 ("keep_pages", 1, MAX_PAGES)):
                    number = step.get(field, default)
                    if type(number) is not int or not 0 <= number <= maximum:
                        raise ValueError("Invalid bounded scenario value")
    return value["scenarios"]


def parse_request(body, dialect):
    if not isinstance(body, dict) or not isinstance(body.get("model"), str):
        raise ProtocolError("invalid_request_shape")
    messages = body.get("input" if dialect == "responses" else "messages")
    if not isinstance(messages, list):
        raise ProtocolError("expected_message_array")
    pages, ocr, pending = [], {}, None
    seen = set()
    for message in messages:
        if not isinstance(message, dict):
            raise ProtocolError("invalid_message")
        if message.get("role") != "user":
            continue
        parts = message.get("content")
        if not isinstance(parts, list):
            raise ProtocolError("expected_content_array")
        for part in parts:
            if not isinstance(part, dict):
                raise ProtocolError("invalid_content")
            kind = part.get("type")
            if kind in ("text", "input_text"):
                value = part.get("text")
                if not isinstance(value, str):
                    raise ProtocolError("invalid_text")
                rendered_original = RENDERED_ORIGINAL_LABEL.fullmatch(value)
                match = IMAGE_LABEL.fullmatch(value) or rendered_original
                if match:
                    image_id, width, height = match.groups()
                    width, height = int(width), int(height)
                    if not width or not height or image_id in seen or len(pages) >= MAX_PAGES:
                        raise ProtocolError("invalid_image_identity")
                    pending = {"imageId": image_id, "width": width, "height": height, "image_sha256": None,
                               "image_bytes": 0}
                    if rendered_original:
                        pending["auxiliary_images"] = []
                    pages.append(pending)
                    seen.add(image_id)
                preview = RENDERED_PREVIEW_LABEL.fullmatch(value)
                if preview:
                    if (len(pages) != 1 or "auxiliary_images" not in pages[0]
                            or pages[0]["image_sha256"] is None or pages[0]["auxiliary_images"]):
                        raise ProtocolError("unpaired_rendered_preview")
                    width, height, sx, sy = preview.groups()
                    try:
                        width, height, sx, sy = int(width), int(height), float(sx), float(sy)
                    except ValueError:
                        raise ProtocolError("invalid_preview_dimensions") from None
                    if (not 0 < width <= pages[0]["width"] or not 0 < height <= pages[0]["height"]
                            or not 0 < sx <= 1 or not 0 < sy <= 1
                            or abs(sx - width / pages[0]["width"]) > 1e-9
                            or abs(sy - height / pages[0]["height"]) > 1e-9):
                        raise ProtocolError("invalid_preview_dimensions")
                    pending = {"width": width, "height": height, "scaleX": sx, "scaleY": sy,
                               "image_sha256": None, "image_bytes": 0}
                    pages[0]["auxiliary_images"].append(pending)
                marker = "OCR regions (source data):\n"
                if marker in value:
                    try:
                        source = value.rsplit(marker, 1)[1]
                        records, end = json.JSONDecoder().raw_decode(source)
                        trailing = source[end:].strip()
                        if trailing and not trailing.startswith(REVIEW_MARKER):
                            raise ValueError("Unexpected OCR suffix")
                    except (ValueError, RecursionError):
                        raise ProtocolError("invalid_ocr_json") from None
                    if not isinstance(records, list) or len(records) > MAX_PAGES:
                        raise ProtocolError("invalid_ocr_pages")
                    for record in records:
                        if not isinstance(record, dict) or record.get("imageId") not in seen:
                            raise ProtocolError("unknown_ocr_image")
                        image_id = record["imageId"]
                        if image_id in ocr:
                            raise ProtocolError("duplicate_ocr_image")
                        regions = record.get("regions")
                        if not isinstance(regions, list) or len(regions) > MAX_REGIONS:
                            raise ProtocolError("invalid_ocr_regions")
                        region_ids = set()
                        for region in regions:
                            if not isinstance(region, dict) or not isinstance(region.get("id"), str):
                                raise ProtocolError("invalid_ocr_region")
                            if not 0 < len(region["id"]) <= 512 or region["id"] in region_ids:
                                raise ProtocolError("duplicate_or_invalid_ocr_region")
                            region_ids.add(region["id"])
                            if not isinstance(region.get("text"), str) or len(region["text"]) > MAX_TEXT:
                                raise ProtocolError("invalid_ocr_text")
                            box = region.get("box2d")
                            if not isinstance(box, list) or len(box) != 4 or any(
                                type(number) not in (int, float) or not 0 <= number <= 1000 for number in box
                            ) or box[0] >= box[2] or box[1] >= box[3]:
                                raise ProtocolError("invalid_ocr_geometry")
                            order = region.get("readingOrder")
                            if type(order) is not int or order < 0:
                                raise ProtocolError("invalid_ocr_order")
                        ocr[image_id] = regions
            elif kind in ("image_url", "input_image"):
                url = part.get("image_url")
                if isinstance(url, dict):
                    url = url.get("url")
                if pending is None or pending["image_sha256"] is not None or not isinstance(url, str):
                    raise ProtocolError("unpaired_image")
                header, separator, encoded = url.partition(",")
                if not separator or not re.fullmatch(r"data:image/[a-zA-Z0-9.+-]+;base64", header):
                    raise ProtocolError("inline_images_only")
                try:
                    image = base64.b64decode(encoded, validate=True)
                except (ValueError, binascii.Error):
                    raise ProtocolError("invalid_image_base64") from None
                pending.update(image_sha256=digest(image), image_bytes=len(image))
            else:
                raise ProtocolError("unsupported_content_type")
    if not pages:
        raise ProtocolError("missing_image_identity")
    for page in pages:
        if "auxiliary_images" in page and (page["image_bytes"] <= 0
                or len(page["auxiliary_images"]) != 1 or page["auxiliary_images"][0]["image_bytes"] <= 0):
            raise ProtocolError("incomplete_rendered_review_images")
    return pages, ocr


def translated_pages(pages, ocr):
    result = []
    for page in pages:
        originals = ocr.get(page["imageId"])
        regions = originals if originals is not None else [
            {"id": "fixture-region-1", "text": "Fixture source text", "box2d": [100, 100, 300, 900], "readingOrder": 0},
        ]
        result.append({
            "imageId": page["imageId"], "detectedLanguage": None,
            "regions": [
                {"id": region["id"], "sourceText": region["text"],
                 "translatedText": "[fixture] " + region["text"] if region["text"] else "",
                 "box2d": region["box2d"], "type": "dialogue", "readingOrder": region["readingOrder"],
                 "rotation": 0, "included": True, "ignoredReason": None, "aiConfidence": None}
                for region in regions
            ],
        })
    return result


def parse_review(body, dialect, pages):
    found = []
    for message in body["input" if dialect == "responses" else "messages"]:
        if message.get("role") != "user":
            continue
        for part in message["content"]:
            text = part.get("text", "")
            if REVIEW_MARKER in text:
                try:
                    found.append(json.loads(text.rsplit(REVIEW_MARKER, 1)[1]))
                except (ValueError, RecursionError):
                    raise ProtocolError("invalid_review_baseline") from None
    if not found:
        if any("auxiliary_images" in page for page in pages):
            raise ProtocolError("rendered_images_require_review_baseline")
        return None
    if len(found) != 1 or len(pages) != 1 or not isinstance(found[0], dict):
        raise ProtocolError("review_requires_one_page")
    baseline = found[0]
    if baseline.get("imageId") != pages[0]["imageId"] or not re.fullmatch(r"[a-f0-9]{64}", str(baseline.get("imageHash", ""))):
        raise ProtocolError("invalid_review_identity")
    if pages[0]["image_sha256"] is not None and baseline["imageHash"] != pages[0]["image_sha256"]:
        raise ProtocolError("review_source_hash_mismatch")
    if type(baseline.get("sourceRevision")) is not int or baseline["sourceRevision"] < 1:
        raise ProtocolError("invalid_review_revision")
    auxiliary = pages[0].get("auxiliary_images", [])
    if auxiliary:
        preview = auxiliary[0]
        evidence = baseline.get("renderEvidence")
        if (not isinstance(evidence, dict) or evidence.get("sourceRevision") != baseline["sourceRevision"]
                or evidence.get("originalHash") != baseline["imageHash"]
                or evidence.get("previewHash") != preview["image_sha256"]
                or evidence.get("previewWidth") != preview["width"] or evidence.get("previewHeight") != preview["height"]
                or evidence.get("scaleX") != preview["scaleX"] or evidence.get("scaleY") != preview["scaleY"]):
            raise ProtocolError("rendered_review_evidence_mismatch")
    elif baseline.get("renderEvidence") is not None:
        raise ProtocolError("missing_rendered_review_images")
    regions = baseline.get("regions")
    if not isinstance(regions, list) or len(regions) > MAX_REGIONS:
        raise ProtocolError("invalid_review_regions")
    ids = set()
    for region in regions:
        if not isinstance(region, dict):
            raise ProtocolError("invalid_review_region")
        region_id = region.get("id")
        if not isinstance(region_id, str) or not 0 < len(region_id) <= 512 or region_id in ids:
            raise ProtocolError("invalid_review_region_identity")
        ids.add(region_id)
        for field in ("sourceText", "translatedText", "type"):
            if not isinstance(region.get(field), str) or len(region[field]) > MAX_TEXT:
                raise ProtocolError("invalid_review_text")
        for field in ("correctedText", "ignoredReason"):
            if region.get(field) is not None and (not isinstance(region[field], str) or len(region[field]) > MAX_TEXT):
                raise ProtocolError("invalid_review_text")
        box = region.get("box2d")
        if not isinstance(box, list) or len(box) != 4 or any(type(v) not in (int, float) or not 0 <= v <= 1000 for v in box):
            raise ProtocolError("invalid_review_geometry")
        if box[0] >= box[2] or box[1] >= box[3]:
            raise ProtocolError("invalid_review_geometry")
        polygon = region.get("polygon")
        if polygon is not None and (not isinstance(polygon, list) or not 3 <= len(polygon) <= 32 or any(
            not isinstance(p, list) or len(p) != 2 or any(type(v) not in (int, float) or not 0 <= v <= 1000 for v in p)
            for p in polygon
        )):
            raise ProtocolError("invalid_review_geometry")
        if type(region.get("rotation")) not in (int, float) or not -360 <= region["rotation"] <= 360:
            raise ProtocolError("invalid_review_rotation")
        if type(region.get("readingOrder")) is not int or region["readingOrder"] < 0 or type(region.get("included")) is not bool:
            raise ProtocolError("invalid_review_order_or_inclusion")
    return baseline


def reviewed_content(page, baseline):
    keys = {"id", "sourceText", "correctedText", "translatedText", "box2d", "polygon", "type",
            "readingOrder", "rotation", "included", "ignoredReason"}
    regions = []
    changed = []
    for original in baseline["regions"]:
        region = {key: original.get(key) for key in keys}
        if region["included"] and region["sourceText"]:
            region["translatedText"] = "Fixture reviewed " + region["id"]
            changed.append(region["id"])
        region["aiConfidence"] = None
        regions.append(region)
    findings = ([{"code": "FIXTURE_REPAIR", "description": "Synthetic deterministic translation replacement",
                  "regionIds": changed, "aiConfidence": None}] if changed else [])
    return {"pages": [{"imageId": page["imageId"], "detectedLanguage": None, "regions": regions}],
            "findings": findings, "visualComplete": page["image_bytes"] > 0}


class FixtureState:
    def __init__(self, scenarios, default_scenario, ledger):
        if default_scenario not in scenarios:
            raise ValueError("Unknown default scenario")
        self.scenarios, self.default_scenario, self.ledger = scenarios, default_scenario, ledger
        self.lock, self.counters = threading.Lock(), {}
        self.run_id, self.sequence = uuid.uuid4().hex, 0
        self.review_dispatches = {}

    def record_dispatch(self, scenario, count_only, is_review):
        if is_review and not count_only:
            with self.lock:
                self.review_dispatches[scenario] = self.review_dispatches.get(scenario, 0) + 1

    def review_barriers(self):
        with self.lock:
            return dict(self.review_dispatches)

    def allocate(self, scenario, count, count_only, is_review=False):
        if scenario not in self.scenarios:
            raise ProtocolError("unknown_fixture_scenario")
        with self.lock:
            self.sequence += 1
            request_id = f"fixture-{self.run_id}-{self.sequence:06d}"
            if count_only:
                return request_id, {"action": "count_tokens"}, None, None
            for index, rule in enumerate(self.scenarios[scenario]):
                if rule.get("review", False) != is_review:
                    continue
                if not rule.get("min_pages", 0) <= count <= rule.get("max_pages", MAX_PAGES):
                    continue
                key = (scenario, index)
                position = self.counters.get(key, 0)
                steps = rule["steps"]
                if position >= len(steps) and not rule.get("repeat_last", True):
                    continue
                self.counters[key] = position + 1
                return request_id, steps[min(position, len(steps) - 1)], index, position + 1
            return request_id, {"action": "success"}, None, None


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, port, state, max_request_bytes=64 * 1024 * 1024, max_response_bytes=8 * 1024 * 1024):
        self.state = state
        self.max_request_bytes, self.max_response_bytes = max_request_bytes, max_response_bytes
        self.slots = threading.BoundedSemaphore(8)
        super().__init__(("127.0.0.1", port), FixtureHandler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def handle_error(self, request, client_address):
        # HTTP parser errors must not print request bodies, headers, or credentials.
        pass


class FixtureHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"
    server_version = "MihonFixture/1"
    sys_version = ""

    def setup(self):
        self.request.settimeout(20)
        super().setup()

    def log_message(self, *_args):
        pass

    def send_body(self, status, body, request_id=None, headers=None):
        if len(body) > self.server.max_response_bytes:
            raise ProtocolError("fixture_response_too_large", 507)
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        # This HTTP/1.0 server closes each socket; prevent clients from pooling it for the next request.
        self.send_header("Connection", "close")
        if request_id:
            self.send_header("x-request-id", request_id)
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self.send_body(200, json_bytes({"fixture": True, "cloud_forwarding": False,
                                           "run_id": self.server.state.run_id,
                                           "review_dispatches": self.server.state.review_barriers(),
                                           "scenarios": list(self.server.state.scenarios)}))
        else:
            self.send_body(404, json_bytes({"error": {"code": "unknown_fixture_endpoint"}}))

    def do_POST(self):
        request_id, started = None, time.monotonic()
        status, outcome, event = 500, "internal_fixture_error", None
        try:
            if not hmac.compare_digest(self.headers.get("Authorization", "").encode(), ("Bearer " + API_KEY).encode()):
                raise ProtocolError("synthetic_fixture_key_required", 401)
            routes = {"/v1/chat/completions": "chat", "/v1/responses": "responses",
                      "/v1/responses/input_tokens": "responses"}
            if self.path not in routes:
                raise ProtocolError("unknown_fixture_endpoint", 404)
            if self.headers.get("Transfer-Encoding") is not None or self.headers.get("Content-Encoding") is not None:
                raise ProtocolError("unsupported_transfer_encoding")
            lengths = self.headers.get_all("Content-Length", [])
            if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,10}", lengths[0]):
                raise ProtocolError("content_length_required", 411)
            length = int(lengths[0])
            if not 0 < length <= self.server.max_request_bytes:
                raise ProtocolError("fixture_request_too_large", 413)
            data = self.rfile.read(length)
            if len(data) != length:
                raise ProtocolError("incomplete_request_body")
            try:
                body = json.loads(data)
            except (ValueError, RecursionError):
                raise ProtocolError("invalid_request_json") from None
            dialect = routes[self.path]
            pages, ocr = parse_request(body, dialect)
            review = parse_review(body, dialect, pages)
            state = self.server.state
            scenario = self.headers.get("X-Mihon-Fixture-Scenario", state.default_scenario)
            count_only = self.path.endswith("/input_tokens")
            request_id, step, rule, sequence = state.allocate(scenario, len(pages), count_only, review is not None)
            action = step["action"]
            event = {
                "event": "dispatch", "utc": utc(), "request_id": request_id, "scenario": scenario,
                "rule": rule, "matching_sequence": sequence, "action": action, "dialect": dialect,
                "count_only": count_only, "is_review": review is not None,
                "review_source_revision": review["sourceRevision"] if review else None,
                "request_bytes": length, "model_sha256": digest(body["model"]),
                "image_input_count": sum(int(page["image_bytes"] > 0) + len(page.get("auxiliary_images", [])) for page in pages),
                "auxiliary_images": [preview for page in pages for preview in page.get("auxiliary_images", [])],
                "client_request_id_sha256": digest(self.headers.get("X-Client-Request-Id", "")),
                "pages": [{"image_id_sha256": digest(page["imageId"]), "width": page["width"],
                           "height": page["height"], "image_sha256": page["image_sha256"],
                           "image_bytes": page["image_bytes"], "ocr_regions": len(ocr.get(page["imageId"], []))}
                          for page in pages],
            }
            state.ledger.append(event)
            state.record_dispatch(scenario, count_only, review is not None)
            time.sleep(step.get("delay_ms", 0) / 1000)
            headers = {}
            if action == "count_tokens":
                # Intentionally a deterministic fixture estimate, not provider tokenization.
                output = {"object": "response.input_tokens", "input_tokens": 100 + (len(pages) + sum(len(page.get("auxiliary_images", [])) for page in pages)) * 250}
                status = 200
            elif action in ("rate_limit", "unavailable", "bad_request", "body_error"):
                status = {"rate_limit": 429, "unavailable": 503, "bad_request": 400, "body_error": 200}[action]
                output = {"error": {"message": "Synthetic fixture failure", "type": "fixture_error", "code": action}}
                if action in ("rate_limit", "unavailable"):
                    headers["Retry-After"] = str(step.get("retry_after_seconds", 1))
            else:
                selected = pages[:step.get("keep_pages", max(1, (len(pages) + 1) // 2))] if action == "partial" else pages
                content = json_bytes(reviewed_content(pages[0], review) if review else {"pages": translated_pages(selected, ocr)}).decode()
                if dialect == "responses":
                    output = {"id": request_id, "object": "response", "status": "completed",
                              "output": [{"type": "message", "role": "assistant", "status": "completed",
                                          "content": [{"type": "output_text", "text": content, "annotations": []}]}],
                              "usage": {"input_tokens": 100, "output_tokens": 50}}
                else:
                    output = {"id": request_id, "object": "chat.completion", "created": 0,
                              "model": "mihon-fixture", "choices": [{"index": 0, "finish_reason": "stop",
                              "message": {"role": "assistant", "content": content}}],
                              "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}}
                status = 200
            response = b'{"intentionally_incomplete":' if action == "malformed" else json_bytes(output)
            self.send_body(status, response, request_id, headers)
            outcome = "response_sent"
        except ProtocolError as error:
            status, outcome = error.status, error.code
            try:
                self.send_body(status, json_bytes({"error": {"code": error.code}}), request_id)
            except (OSError, ProtocolError):
                outcome = "disconnected"
        except (OSError, socket.timeout):
            outcome = "disconnected"
        except Exception:
            try:
                self.send_body(500, json_bytes({"error": {"code": "internal_fixture_error"}}), request_id)
            except (OSError, ProtocolError):
                pass
        finally:
            final = {"event": "finish" if event else "rejected", "utc": utc(), "request_id": request_id,
                     "http_status": status, "outcome": outcome, "elapsed_ms": round((time.monotonic() - started) * 1000)}
            try:
                self.server.state.ledger.append(final)
            except (OSError, ProtocolError):
                pass  # An unmatched dispatch already identifies an incomplete ledger capture.


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--ledger", type=Path, required=True, help="New private JSONL file; existing files are refused")
    parser.add_argument("--scenarios", type=Path, default=DEFAULT_SCENARIOS)
    parser.add_argument("--scenario", default="success")
    parser.add_argument("--cert", type=Path, help="PEM server certificate, for HTTPS")
    parser.add_argument("--key", type=Path, help="PEM TLS private key, never logged")
    parser.add_argument("--max-request-mib", type=int, default=64)
    parser.add_argument("--max-response-mib", type=int, default=8)
    parser.add_argument("--max-ledger-mib", type=int, default=32)
    args = parser.parse_args()
    if not 0 <= args.port <= 65535 or bool(args.cert) != bool(args.key):
        parser.error("Use a valid port and supply both --cert and --key for TLS")
    if any(not 1 <= number <= 128 for number in (args.max_request_mib, args.max_response_mib, args.max_ledger_mib)):
        parser.error("Storage and wire limits must be 1–128 MiB")
    scenarios = load_scenarios(args.scenarios)
    if args.scenario not in scenarios:
        parser.error("Unknown default scenario")
    ledger = PrivateLedger(args.ledger, args.max_ledger_mib * 1024 * 1024)
    try:
        state = FixtureState(scenarios, args.scenario, ledger)
        with FixtureServer(args.port, state, args.max_request_mib * 1024 * 1024,
                           args.max_response_mib * 1024 * 1024) as server:
            if args.cert:
                context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                context.minimum_version = ssl.TLSVersion.TLSv1_2
                context.load_cert_chain(args.cert, args.key)
                server.socket = context.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
            print(json.dumps({"fixture": True, "run_id": state.run_id, "bound_host": "127.0.0.1",
                              "port": server.server_port, "tls": bool(args.cert), "scenario": args.scenario,
                              "ledger": str(args.ledger), "synthetic_api_key": API_KEY}), flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass
    finally:
        ledger.close()


if __name__ == "__main__":
    main()
