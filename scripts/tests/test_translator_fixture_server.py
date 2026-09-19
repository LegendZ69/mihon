"""Host-only protocol/recovery/privacy checks. No devices or external requests."""

import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import http.client
import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import threading
import time
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "translator_fixture_server.py"
SPEC = importlib.util.spec_from_file_location("translator_fixture_server", SCRIPT)
fixture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixture)
IMAGE = b"synthetic-image-payload-never-save-this"


def request_body(dialect="chat", count=1, image=True, ocr=None):
    text_type = "input_text" if dialect == "responses" else "text"
    parts = []
    for index in range(count):
        parts.append({"type": text_type, "text": f"Image ID: page-{index}; original dimensions: 720x1280"})
        if image:
            url = "data:image/png;base64," + base64.b64encode(IMAGE + str(index).encode()).decode()
            parts.append({"type": "input_image" if dialect == "responses" else "image_url",
                          "image_url": url if dialect == "responses" else {"url": url, "detail": "auto"}})
    prompt = "Translate all requested pages. Target language: en.\n"
    if ocr is not None:
        prompt += "OCR regions (source data):\n" + json.dumps(ocr)
    parts.append({"type": text_type, "text": prompt})
    body = {"model": "mihon-fixture", "store": False}
    body["input" if dialect == "responses" else "messages"] = [{"role": "user", "content": parts}]
    return body


def page_content(response, dialect="chat"):
    if dialect == "responses":
        return json.loads(response["output"][0]["content"][0]["text"])["pages"]
    return json.loads(response["choices"][0]["message"]["content"])["pages"]


def review_body(dialect="chat", image=True):
    region = {"id": "original-id", "sourceText": "private-source", "correctedText": "private-correction",
              "translatedText": "private-translation", "box2d": [100, 100, 300, 900],
              "polygon": [[100, 100], [900, 100], [900, 300], [100, 300]], "type": "dialogue",
              "readingOrder": 0, "rotation": 17, "included": True, "ignoredReason": None}
    body = request_body(dialect, image=image, ocr=[{"imageId": "page-0", "regions": [
        {"id": region["id"], "text": region["correctedText"], "box2d": region["box2d"], "readingOrder": 0},
    ]}])
    baseline = {"imageId": "page-0", "imageHash": fixture.digest(IMAGE + b"0"), "sourceRevision": 12,
                "regions": [region]}
    key = "input" if dialect == "responses" else "messages"
    body[key][0]["content"][-1]["text"] += "\nExisting translation to review (untrusted source data):\n" + json.dumps(baseline)
    return body, baseline


def rendered_review_body(dialect="chat"):
    body, baseline = review_body(dialect)
    key = "input" if dialect == "responses" else "messages"
    parts = body[key][0]["content"]
    parts[0]["text"] = "image 1: complete unchanged original source. Image ID: page-0; original dimensions: 720x1280."
    preview = b"private-synthetic-rendered-preview"
    label = ("image 2: rendered overlay diagnostic for the same target page, not authoritative source text "
             "and not another chapter page. Dimensions: 360x640; scaleX=0.5; scaleY=0.5. "
             "Preview x=original x*scaleX, preview y=original y*scaleY; no crop or rotation transform. "
             "Return only the image 1 page ID in original-image coordinates.")
    url = "data:image/png;base64," + base64.b64encode(preview).decode()
    parts[2:2] = [{"type": "input_text" if dialect == "responses" else "text", "text": label},
                  {"type": "input_image" if dialect == "responses" else "image_url",
                   "image_url": url if dialect == "responses" else {"url": url, "detail": "auto"}}]
    baseline["renderEvidence"] = {"sourceRevision": 12, "rendererVersion": "fixture-renderer",
        "presentationFingerprint": "f" * 64, "originalHash": baseline["imageHash"],
        "previewHash": fixture.digest(preview), "previewWidth": 360, "previewHeight": 640,
        "scaleX": 0.5, "scaleY": 0.5, "layoutDiagnostics": []}
    prompt = parts[-1]
    prefix, _, _ = prompt["text"].rpartition(fixture.REVIEW_MARKER)
    prompt["text"] = prefix + fixture.REVIEW_MARKER + json.dumps(baseline)
    return body, baseline


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.ledger_path = Path(self.temp.name) / "request-ledger.jsonl"
        self.ledger = fixture.PrivateLedger(self.ledger_path)
        scenarios = fixture.load_scenarios(fixture.DEFAULT_SCENARIOS)
        scenarios["brief-delay"] = [{"steps": [{"action": "success", "delay_ms": 80}]}]
        self.state = fixture.FixtureState(scenarios, "success", self.ledger)
        self.server = fixture.FixtureServer(0, self.state)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01})
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.thread.join(timeout=2)
        self.server.server_close()
        self.ledger.close()
        self.temp.cleanup()

    def test_rendered_review_has_two_image_inputs_but_one_target_and_response_page(self):
        for dialect in ("chat", "responses"):
            body, baseline = rendered_review_body(dialect)
            status, _, response = self.post(body, path="/v1/responses" if dialect == "responses" else "/v1/chat/completions")
            self.assertEqual(status, 200, response)
            pages = page_content(json.loads(response), dialect)
            self.assertEqual([page["imageId"] for page in pages], ["page-0"])
            self.assertEqual(pages[0]["regions"][0]["sourceText"], baseline["regions"][0]["sourceText"])
        rows = [row for row in self.events(finishes=2) if row["event"] == "dispatch"]
        self.assertEqual(len(rows), 2)
        for row in rows:
            self.assertEqual(len(row["pages"]), 1)
            self.assertEqual(row["image_input_count"], 2)
            self.assertEqual(row["auxiliary_images"][0]["image_sha256"], fixture.digest(b"private-synthetic-rendered-preview"))
            self.assertEqual(row["auxiliary_images"][0]["width"], 360)
        ledger = self.ledger_path.read_text()
        for private in ("private-source", "private-synthetic-rendered-preview", "private-translation", fixture.API_KEY):
            self.assertNotIn(private, ledger)

    def test_rendered_review_token_fixture_counts_auxiliary_input_without_consuming_review_attempt(self):
        body, _ = rendered_review_body("responses")
        status, _, response = self.post(body, path="/v1/responses/input_tokens")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(response)["input_tokens"], 600)
        self.assertEqual(self.state.review_barriers(), {})
        status, _, response = self.post(body, path="/v1/responses")
        self.assertEqual(status, 200)
        self.assertEqual(len(page_content(json.loads(response), "responses")), 1)
        rows = [row for row in self.events(finishes=2) if row["event"] == "dispatch"]
        self.assertEqual([row["image_input_count"] for row in rows], [2, 2])
        self.assertEqual([row["count_only"] for row in rows], [True, False])
        self.assertEqual(self.state.review_barriers(), {"success": 1})

    def test_rendered_review_rejects_missing_or_mismatched_preview_before_dispatch(self):
        for defect in ("missing-payload", "wrong-hash", "wrong-scale", "missing-baseline"):
            body, baseline = rendered_review_body()
            parts = body["messages"][0]["content"]
            if defect == "missing-payload":
                del parts[3]
            elif defect == "missing-baseline":
                parts[-1]["text"] = "Translate the original"
            else:
                if defect == "wrong-hash": baseline["renderEvidence"]["previewHash"] = "0" * 64
                else: baseline["renderEvidence"]["scaleX"] = 0.75
                prefix, _, _ = parts[-1]["text"].rpartition(fixture.REVIEW_MARKER)
                parts[-1]["text"] = prefix + fixture.REVIEW_MARKER + json.dumps(baseline)
            self.assertEqual(self.post(body)[0], 400, defect)
        self.assertFalse(any(row["event"] == "dispatch" for row in self.events()))

    def test_review_preserves_baseline_and_keeps_private_text_out_of_ledger(self):
        body, baseline = review_body()
        status, _, response = self.post(body)
        self.assertEqual(status, 200)
        content = json.loads(json.loads(response)["choices"][0]["message"]["content"])
        region = content["pages"][0]["regions"][0]
        self.assertEqual(region["sourceText"], baseline["regions"][0]["sourceText"])
        self.assertEqual(region["polygon"], baseline["regions"][0]["polygon"])
        self.assertEqual(region["rotation"], 17)
        self.assertEqual(region["translatedText"], "Fixture reviewed original-id")
        self.assertTrue(content["visualComplete"])
        self.assertTrue(content["findings"])
        events = self.events(finishes=1)
        self.assertTrue(next(row for row in events if row["event"] == "dispatch")["is_review"])
        ledger = self.ledger_path.read_text()
        for secret in ("private-source", "private-correction", "private-translation", fixture.API_KEY):
            self.assertNotIn(secret, ledger)

    def test_text_only_responses_review_never_claims_visual_coverage(self):
        body, baseline = review_body("responses", image=False)
        status, _, response = self.post(body, path="/v1/responses")
        self.assertEqual(status, 200)
        content = json.loads(json.loads(response)["output"][0]["content"][0]["text"])
        self.assertFalse(content["visualComplete"])
        self.assertEqual(content["pages"][0]["regions"][0]["correctedText"], "private-correction")
        self.assertEqual(content["pages"][0]["regions"][0]["box2d"], baseline["regions"][0]["box2d"])

    def test_review_baseline_validation_rejects_wrong_source_and_duplicate_identity(self):
        for field, value in (("imageHash", "0" * 64), ("sourceRevision", -1), ("imageId", "unknown")):
            body, _ = review_body()
            prompt = body["messages"][0]["content"][-1]
            prefix, _, raw = prompt["text"].rpartition(fixture.REVIEW_MARKER)
            baseline = json.loads(raw)
            baseline[field] = value
            prompt["text"] = prefix + fixture.REVIEW_MARKER + json.dumps(baseline)
            self.assertEqual(self.post(body)[0], 400, field)

    def test_review_failures_do_not_consume_the_generation_sequence(self):
        self.state.scenarios["mixed-review"] = [
            {"steps": [{"action": "rate_limit"}, {"action": "success"}]},
            {"review": True, "steps": [{"action": "malformed"}, {"action": "success"}]},
        ]
        body, _ = review_body()
        self.assertEqual(self.post(body, scenario="mixed-review")[2], b'{"intentionally_incomplete":')
        self.assertEqual(self.post(scenario="mixed-review")[0], 429)
        self.assertEqual(self.post(body, scenario="mixed-review")[0], 200)
        self.assertEqual(self.post(scenario="mixed-review")[0], 200)
        rows = [row for row in self.events(finishes=4) if row["event"] == "dispatch"]
        self.assertEqual([(row["is_review"], row["matching_sequence"]) for row in rows],
                         [(True, 1), (False, 1), (True, 2), (False, 2)])

    def test_review_health_barrier_observes_durable_dispatch_before_delayed_response(self):
        self.state.scenarios["review-barrier"] = [{"review": True, "steps": [{"action": "success", "delay_ms": 300}]}]
        body, _ = review_body("responses")
        self.assertEqual(self.post(body, scenario="review-barrier", path="/v1/responses/input_tokens")[0], 200)
        self.assertEqual(self.state.review_barriers(), {})
        with ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(self.post, body, "review-barrier", "/v1/responses")
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
                connection.request("GET", "/health")
                response = connection.getresponse()
                health = json.loads(response.read())
                connection.close()
                if health["review_dispatches"].get("review-barrier") == 1:
                    break
            self.assertEqual(health["review_dispatches"], {"review-barrier": 1})
            self.assertFalse(future.done(), "barrier must precede the delayed response")
            rows = self.events()
            self.assertEqual(sum(row["event"] == "dispatch" and not row["count_only"] for row in rows), 1)
            self.assertEqual(future.result()[0], 200)
        self.assertNotIn("private-source", json.dumps(health))
        self.assertNotIn("original-id", json.dumps(health))

    def post(self, body=None, scenario="success", path="/v1/chat/completions", headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        data = fixture.json_bytes(request_body() if body is None else body)
        outgoing = {"Authorization": "Bearer " + fixture.API_KEY, "Content-Type": "application/json",
                    "X-Mihon-Fixture-Scenario": scenario}
        outgoing.update(headers or {})
        connection.request("POST", path, data, outgoing)
        response = connection.getresponse()
        result = (response.status, dict(response.getheaders()), response.read())
        connection.close()
        return result

    def events(self, finishes=0):
        # Response delivery precedes the fsync of its completion record.
        deadline = time.monotonic() + 2
        while True:
            rows = [json.loads(line) for line in self.ledger_path.read_text().splitlines()]
            if sum(row["event"] == "finish" for row in rows) >= finishes or time.monotonic() >= deadline:
                return rows
            time.sleep(0.005)

    def test_process_recovery_scenario_commits_then_blocks_only_one_attempt(self):
        scenarios = fixture.load_scenarios(SCRIPT.parent / "fixtures" / "translation-process-recovery-scenarios.json")
        state = fixture.FixtureState(scenarios, "recovery-delay", self.ledger)
        # Token diagnostics must not consume the generation sequence before the crash barrier.
        self.assertEqual(state.allocate("recovery-delay", 1, True)[1]["action"], "count_tokens")
        steps = [state.allocate("recovery-delay", 1, False)[1] for _ in range(4)]
        self.assertEqual([step["action"] for step in steps], ["success"] * 4)
        self.assertEqual([step.get("delay_ms", 0) for step in steps], [0, 120000, 0, 0])

    def test_worker_recovery_only_delays_the_interrupted_generation(self):
        scenarios = fixture.load_scenarios(SCRIPT.parent / "fixtures" / "translation-worker-scenarios.json")
        for scenario, delay_ms in [("worker-screen-off-thaw", 45000), ("worker-force-stop", 120000),
                                   ("worker-active-resume", 120000)]:
            with self.subTest(scenario=scenario):
                state = fixture.FixtureState(scenarios, scenario, self.ledger)
                self.assertEqual(state.allocate(scenario, 1, True)[1]["action"], "count_tokens")
                first = state.allocate(scenario, 1, False)[1]
                self.assertEqual(first.get("delay_ms", 0), 0)
                self.assertEqual(state.allocate(scenario, 1, True)[1]["action"], "count_tokens")
                rest = [state.allocate(scenario, 1, False)[1] for _ in range(3)]
                self.assertEqual([first["action"], *[step["action"] for step in rest]], ["success"] * 4)
                self.assertEqual([step.get("delay_ms", 0) for step in rest], [delay_ms, 0, 0])

    def test_closed_sockets_are_advertised_to_pooling_clients(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        try:
            connection.request("GET", "/health", headers={"Connection": "keep-alive"})
            response = connection.getresponse()
            response.read()
            self.assertEqual(response.status, 200)
            self.assertEqual(response.getheader("Connection"), "close")
        finally:
            connection.close()
        # Success, scripted failures, and parser/auth errors must all prevent closed-socket reuse.
        for scenario, headers, expected in [
            ("success", {}, 200),
            ("retry-429-503", {}, 429),
            ("success", {"Authorization": "Bearer invalid-fixture-token"}, 401),
        ]:
            with self.subTest(status=expected):
                status, received, _ = self.post(scenario=scenario, headers=headers)
                self.assertEqual(status, expected)
                self.assertEqual(received.get("Connection"), "close")

    def test_chat_image_response_has_original_ids_and_normalized_schema(self):
        status, headers, raw = self.post(request_body(count=2))
        self.assertEqual(status, 200)
        pages = page_content(json.loads(raw))
        self.assertEqual([page["imageId"] for page in pages], ["page-0", "page-1"])
        region = pages[0]["regions"][0]
        self.assertEqual(region["box2d"], [100, 100, 300, 900])
        self.assertIsNone(region["aiConfidence"])
        self.assertEqual(set(region), {"id", "sourceText", "translatedText", "box2d", "type", "readingOrder",
                                       "rotation", "included", "ignoredReason", "aiConfidence"})
        dispatch = self.events(finishes=1)[0]
        self.assertEqual(dispatch["request_id"], headers["x-request-id"])
        self.assertEqual(dispatch["pages"][0]["image_sha256"], hashlib.sha256(IMAGE + b"0").hexdigest())

    def test_responses_images_and_token_count_do_not_consume_failure_sequence(self):
        body = request_body("responses", count=2)
        count_status, _, count_raw = self.post(body, "retry-429-503", "/v1/responses/input_tokens")
        self.assertEqual(count_status, 200)
        self.assertEqual(json.loads(count_raw)["input_tokens"], 600)
        codes = [self.post(body, "retry-429-503", "/v1/responses")[0] for _ in range(3)]
        self.assertEqual(codes, [429, 503, 200])
        _, _, raw = self.post(body, path="/v1/responses")
        response = json.loads(raw)
        self.assertEqual(response["status"], "completed")
        self.assertEqual(len(page_content(response, "responses")), 2)

    def test_text_only_preserves_ocr_region_ids_order_and_geometry(self):
        ocr = [{"imageId": "page-0", "regions": [
            {"id": "ocr-7", "text": "行かない！", "box2d": [50, 60, 700, 250], "readingOrder": 3},
            {"id": "ocr-8", "text": "진짜?", "box2d": [60, 500, 200, 950], "readingOrder": 4},
        ]}]
        for dialect, path in (("chat", "/v1/chat/completions"), ("responses", "/v1/responses")):
            status, _, raw = self.post(request_body(dialect, image=False, ocr=ocr), path=path)
            self.assertEqual(status, 200)
            regions = page_content(json.loads(raw), dialect)[0]["regions"]
            self.assertEqual([region["id"] for region in regions], ["ocr-7", "ocr-8"])
            self.assertEqual(regions[0]["sourceText"], "行かない！")
            self.assertEqual(regions[0]["box2d"], ocr[0]["regions"][0]["box2d"])
            self.assertEqual(regions[0]["readingOrder"], 3)

    def test_hybrid_images_use_existing_ocr_and_empty_page_stays_empty(self):
        body = request_body(count=2, ocr=[{"imageId": "page-0", "regions": []}])
        status, _, raw = self.post(body)
        self.assertEqual(status, 200)
        pages = page_content(json.loads(raw))
        self.assertEqual(pages[0]["regions"], [])
        self.assertEqual(len(pages[1]["regions"]), 1)

    def test_partial_response_once_preserves_successful_page_identity(self):
        body = request_body(count=5)
        _, _, first = self.post(body, "partial-once")
        _, _, second = self.post(body, "partial-once")
        self.assertEqual([page["imageId"] for page in page_content(json.loads(first))], ["page-0"])
        self.assertEqual(len(page_content(json.loads(second))), 5)

    def test_halving_matches_batch_size_independently_from_singleton_sequence(self):
        for count in (5, 3, 2):
            code, _, raw = self.post(request_body(count=count), "halving-content")
            self.assertEqual(code, 200)
            self.assertEqual(json.loads(raw)["error"]["code"], "body_error")
        code, _, raw = self.post(request_body(count=1), "halving-content")
        self.assertEqual(code, 200)
        self.assertEqual(len(page_content(json.loads(raw))), 1)

    def test_rate_limit_retry_after_and_recovery_order(self):
        results = [self.post(scenario="retry-429-503") for _ in range(4)]
        self.assertEqual([result[0] for result in results], [429, 503, 200, 200])
        self.assertEqual(results[0][1]["Retry-After"], "1")
        rows = [row for row in self.events(finishes=4) if row["event"] == "dispatch"]
        self.assertEqual([row["matching_sequence"] for row in rows], [1, 2, 3, 4])

    def test_malformed_and_body_error_recover_separately(self):
        code, _, raw = self.post(scenario="malformed-once")
        self.assertEqual(code, 200)
        with self.assertRaises(json.JSONDecodeError):
            json.loads(raw)
        self.assertEqual(len(page_content(json.loads(self.post(scenario="malformed-once")[2]))), 1)
        self.assertIn("error", json.loads(self.post(scenario="body-error-once")[2]))
        self.assertIn("choices", json.loads(self.post(scenario="body-error-once")[2]))

    def test_delay_is_recorded_as_elapsed_and_dispatch_precedes_finish(self):
        self.post(scenario="brief-delay")
        rows = self.events(finishes=1)
        self.assertEqual([row["event"] for row in rows], ["dispatch", "finish"])
        self.assertGreaterEqual(rows[1]["elapsed_ms"], 70)
        self.assertEqual(rows[1]["outcome"], "response_sent")

    def test_ledger_redacts_all_freeform_values_and_payloads(self):
        secret = "DO-NOT-RECORD-THIS-SECRET"
        body = request_body(ocr=[{"imageId": "page-0", "regions": [
            {"id": "secret-region-identity", "text": secret, "box2d": [1, 2, 10, 20], "readingOrder": 0},
        ]}])
        body["model"] = "private-model-identity"
        status, _, _ = self.post(body, headers={"X-Client-Request-Id": "private-client-request-identity",
                                               "X-Unrelated-Secret": "private-custom-header"})
        self.assertEqual(status, 200)
        self.events(finishes=1)
        text = self.ledger_path.read_text()
        for excluded in (secret, "private-model-identity", "secret-region-identity", "private-client-request-identity",
                         "private-custom-header", "page-0", fixture.API_KEY, base64.b64encode(IMAGE).decode(),
                         "Translate all requested"):
            self.assertNotIn(excluded, text)
        self.assertEqual(stat.S_IMODE(self.ledger_path.stat().st_mode), 0o600)

    def test_non_synthetic_credentials_are_refused_and_never_logged(self):
        code, _, raw = self.post(headers={"Authorization": "Bearer real-secret-must-never-work"})
        self.assertEqual(code, 401)
        self.assertEqual(json.loads(raw)["error"]["code"], "synthetic_fixture_key_required")
        self.assertNotIn("real-secret", self.ledger_path.read_text())

    def test_external_image_urls_are_not_fetched(self):
        body = request_body()
        body["messages"][0]["content"][1]["image_url"]["url"] = "https://example.invalid/private-image"
        code, _, raw = self.post(body)
        self.assertEqual(code, 400)
        self.assertEqual(json.loads(raw)["error"]["code"], "inline_images_only")

    def test_request_response_and_ledger_limits_fail_closed(self):
        self.server.max_request_bytes = 10
        self.assertEqual(self.post()[0], 413)
        self.server.max_request_bytes = 1024 * 1024
        self.server.max_response_bytes = 100
        self.assertEqual(self.post()[0], 507)
        self.server.max_response_bytes = 1024 * 1024
        self.ledger.max_bytes = self.ledger.size
        self.assertEqual(self.post()[0], 507)

    def test_unknown_route_and_scenario_are_rejected_without_echo(self):
        code, _, raw = self.post(path="/private-secret?key=do-not-record")
        self.assertEqual(code, 404)
        self.assertNotIn(b"private-secret", raw)
        self.assertEqual(self.post(scenario="not-a-scenario")[0], 400)
        self.assertNotIn("do-not-record", self.ledger_path.read_text())

    def test_duplicate_image_and_invalid_geometry_are_rejected(self):
        body = request_body(count=2)
        body["messages"][0]["content"][2]["text"] = body["messages"][0]["content"][0]["text"]
        self.assertEqual(self.post(body)[0], 400)
        body = request_body(ocr=[{"imageId": "page-0", "regions": [
            {"id": "r", "text": "source", "box2d": [100, 100, 20, 20], "readingOrder": 0},
        ]}])
        self.assertEqual(self.post(body)[0], 400)

    def test_concurrent_dispatches_have_unique_ids_and_serial_rule_positions(self):
        with ThreadPoolExecutor(max_workers=4) as pool:
            replies = list(pool.map(lambda _: self.post(), range(12)))
        self.assertEqual([reply[0] for reply in replies], [200] * 12)
        request_ids = [reply[1]["x-request-id"] for reply in replies]
        self.assertEqual(len(set(request_ids)), 12)
        rows = [row for row in self.events(finishes=12) if row["event"] == "dispatch"]
        self.assertEqual(sorted(row["matching_sequence"] for row in rows), list(range(1, 13)))
        self.assertEqual(self.server.server_address[0], "127.0.0.1")


class ConfigurationTests(unittest.TestCase):
    def test_existing_ledger_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ledger.jsonl"
            path.write_text("keep me")
            with self.assertRaises(FileExistsError):
                fixture.PrivateLedger(path)
            self.assertEqual(path.read_text(), "keep me")

    def test_unknown_steps_and_unbounded_delays_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "scenarios.json"
            for step in ({"action": "forward_to_cloud"}, {"action": "success", "delay_ms": 999999},
                         {"action": "success", "echo_headers": True}):
                path.write_text(json.dumps({"version": 1, "scenarios": {"test": [{"steps": [step]}]}}))
                with self.assertRaises(ValueError):
                    fixture.load_scenarios(path)

    def test_exhausted_nonrepeating_rule_falls_through_without_consuming_others(self):
        state = fixture.FixtureState({"test": [
            {"min_pages": 2, "steps": [{"action": "partial"}], "repeat_last": False},
            {"steps": [{"action": "success"}]},
        ]}, "test", None)
        self.assertEqual(state.allocate("test", 5, False)[1]["action"], "partial")
        self.assertEqual(state.allocate("test", 5, False)[1]["action"], "success")


if __name__ == "__main__":
    unittest.main()
