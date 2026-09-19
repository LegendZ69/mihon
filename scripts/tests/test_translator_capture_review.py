import base64
import copy
import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import unittest

from PIL import Image, ImageDraw

MODULE_PATH = Path(__file__).resolve().parents[1] / "translator_capture_review.py"
SPEC = importlib.util.spec_from_file_location("translator_capture_review", MODULE_PATH)
review = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(review)


def region(source="A", order=1, box=None):
    return {"id": "region-" + source, "sourceText": source, "translatedText": "Translated " + source,
            "box2d": box or [100, 100, 300, 300], "readingOrder": order,
            "type": "dialogue", "included": True, "rotation": 0}


def capture(identifier, pages, *, inp=10, visible=3, reasoning=7, started=1):
    return {"metadata": {"id": identifier, "batchId": "batch-private", "responseCode": 200,
                         "state": "COMPLETED", "responseBodyCompleted": True,
                         "responseTruncated": False, "startedAt": started, "attempt": 0},
            "response": {"modelVersion": "gemini-3.8-flash", "usageMetadata": {
                "promptTokenCount": inp, "candidatesTokenCount": visible,
                "thoughtsTokenCount": reasoning, "totalTokenCount": inp + visible + reasoning},
                "candidates": [{"finishReason": "STOP", "content": {"parts": [{"text": json.dumps({"pages": pages})}]}}]}}


class UsageTests(unittest.TestCase):
    def test_vertex_adds_visible_and_reasoning_once(self):
        value = review.usage(capture("a", [])["response"])
        self.assertEqual((value["visible_output"], value["reasoning"], value["output_including_reasoning"]), (3, 7, 10))
        self.assertEqual(value["computed_total"], 20)
        self.assertFalse(value["warnings"])

    def test_openai_responses_output_already_includes_reasoning(self):
        value = review.usage({"output": [], "usage": {"input_tokens": 10, "output_tokens": 10,
            "input_tokens_details": {"cached_tokens": 4}, "output_tokens_details": {"reasoning_tokens": 7}, "total_tokens": 20}})
        self.assertEqual((value["visible_output"], value["reasoning"], value["output_including_reasoning"]), (3, 7, 10))
        self.assertEqual(value["computed_total"], 20)
        self.assertEqual(value["cached_input"], 4)

    def test_openai_chat_output_already_includes_reasoning(self):
        value = review.usage({"usage": {"prompt_tokens": 10, "completion_tokens": 10,
            "completion_tokens_details": {"reasoning_tokens": 7}, "total_tokens": 20}})
        self.assertEqual(value["visible_output"], 3)
        self.assertEqual(value["output_including_reasoning"], 10)

    def test_missing_reasoning_stays_unknown(self):
        value = review.usage({"usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 3, "totalTokenCount": 20}})
        self.assertIsNone(value["reasoning"])
        self.assertIsNone(value["output_including_reasoning"])

    def test_inconsistent_provider_totals_and_cached_subset_are_flagged(self):
        response = capture("a", [])["response"]
        response["usageMetadata"].update(totalTokenCount=200, cachedContentTokenCount=11)
        self.assertEqual(set(review.usage(response)["warnings"]), {"PROVIDER_TOTAL_MISMATCH", "CACHED_INPUT_EXCEEDS_INPUT"})


class CaptureReviewTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        image = Image.new("RGB", (100, 100), "white")
        draw = ImageDraw.Draw(image)
        draw.rectangle((10, 10, 29, 29), fill="black")
        draw.rectangle((10, 60, 29, 79), fill="black")
        image.save(self.root / "page.png")
        def expected(source, box, order):
            return {"id": source, "source_text": source, "include": True, "role": "dialogue",
                    "reading_order": order, "text_ink_box_xyxy": box,
                    "bounding_box_xyxy": [box[0] - 2, box[1] - 2, box[2] + 2, box[3] + 2]}
        page = {"id": "controlled-page", "file": "page.png", "sha256": review.digest((self.root / "page.png").read_bytes()),
                "pixel_sha256": review.digest(image.tobytes()), "width": 100, "height": 100,
                "regions": [expected("A", [10, 10, 30, 30], 0), expected("B", [10, 60, 30, 80], 1)]}
        self.manifest = {"pages": [page], "five_page_chapter": [page["id"]]}
        self.corpus = self.root / "manifest.json"
        self.corpus.write_text(json.dumps(self.manifest))
        self.mapping = review.make_map(self.manifest, "all")
        self.full_page = {"imageId": "0~0_0_100_100", "regions": [region(), region("B", 2, [600, 100, 800, 300])]}

    def tearDown(self):
        self.temporary.cleanup()

    def run_review(self, captures, **kwargs):
        files = []
        for i, value in enumerate(captures):
            path = self.root / f"capture-{i}.json"
            path.write_text(json.dumps(value))
            files.append(path)
        return review.summarize(files, self.corpus, self.mapping, **kwargs)

    def test_complete_page_identity_transcription_order_and_geometry(self):
        report = self.run_review([capture("a", [self.full_page])])
        page = report["pages"][0]
        self.assertTrue(page["full_image_response_coverage"])
        self.assertEqual(page["exact_matched_regions"], 2)
        self.assertEqual(page["relative_order_checks"], [True])
        self.assertEqual(page["regions"][0]["observations"][0]["geometry"]["dark_ink_coverage_percent"], 100)
        self.assertIn("TRANSMITTED_PIXEL_IDENTITY_UNVERIFIED", report["followup_codes"])

    def test_native_capture_directory_and_jsonl_events_are_supported(self):
        value = capture("native", [self.full_page])
        directory = self.root / "native-captures" / "opaque-directory"
        directory.mkdir(parents=True)
        for name in ("metadata", "response"):
            (directory / (name + ".json")).write_text(json.dumps(value[name]))
        report = review.summarize([directory.parent], self.corpus, self.mapping)
        self.assertEqual(report["unique_attempts"], 1)
        self.assertEqual(report["pages"][0]["exact_matched_regions"], 2)
        path = self.root / "events.jsonl"
        path.write_text('{"stage":"USAGE"}\n{"stage":"TRANSLATED"}\n')
        self.assertEqual(len(review.read_events(path)), 2)

    def test_duplicate_files_do_not_double_count_paid_attempts(self):
        value = capture("same-local-capture-id", [self.full_page])
        report = self.run_review([value, copy.deepcopy(value)])
        self.assertEqual(report["unique_attempts"], 1)
        self.assertEqual(report["duplicate_capture_files_ignored"], 1)
        self.assertEqual(report["usage_totals"]["reasoning"]["known_sum"], 7)

    def test_token_count_capture_is_not_a_generation_attempt(self):
        counted = {"metadata": {"id": "count", "operation": "countTokens", "responseCode": 200}, "response": {"totalTokens": 10}}
        report = self.run_review([counted, capture("a", [self.full_page])])
        self.assertEqual(report["unique_attempts"], 1)
        self.assertEqual(len(report["non_generation_captures"]), 1)
        self.assertEqual(report["usage_totals"]["reasoning"]["known_sum"], 7)

    def test_repeated_reference_text_is_not_associated_twice(self):
        self.manifest["pages"][0]["regions"][1]["source_text"] = "A"
        self.corpus.write_text(json.dumps(self.manifest))
        report = self.run_review([capture("a", [self.full_page])])
        self.assertEqual(report["pages"][0]["exact_matched_regions"], 0)
        self.assertTrue(report["pages"][0]["regions"][0]["repeated_reference_text_needs_manual_association"])

    def test_whitespace_only_transcription_is_labeled_not_an_exact_match(self):
        self.manifest["pages"][0]["regions"][0]["source_text"] = "A\nC"
        self.corpus.write_text(json.dumps(self.manifest))
        page = copy.deepcopy(self.full_page)
        page["regions"][0]["sourceText"] = "A C"
        report = self.run_review([capture("a", [page])])
        self.assertEqual(report["pages"][0]["exact_matched_regions"], 1)
        self.assertEqual(report["pages"][0]["matched_regions_with_whitespace_normalization"], 2)
        self.assertIn("TRANSCRIPTION_WHITESPACE_ONLY_DIFFERENCES", report["followup_codes"])
        self.assertNotIn("CORPUS_COVERAGE_OR_TRANSCRIPTION_FOLLOWUP", report["followup_codes"])

    def test_conflicting_capture_identity_is_explicit_and_conservative(self):
        first = capture("same", [self.full_page])
        second = capture("same", [self.full_page], visible=4)
        report = self.run_review([first, second])
        self.assertEqual(report["conflicting_capture_identities"], 1)
        self.assertIn("CONFLICTING_CAPTURE_IDENTITIES", report["followup_codes"])

    def test_failed_batch_usage_is_counted_before_successful_halves(self):
        failed = capture("parent", [], started=1)
        failed["response"]["candidates"][0]["finishReason"] = "MAX_TOKENS"
        top = {"imageId": "0~0_0_100_50", "regions": [region(box=[200, 100, 600, 300])]}
        bottom = {"imageId": "0~0_50_100_50", "regions": [region("B", 1, [200, 100, 600, 300])]}
        report = self.run_review([failed, capture("half-a", [top], started=2), capture("half-b", [bottom], started=3)])
        self.assertEqual(report["unique_attempts"], 3)
        self.assertEqual(report["usage_totals"]["reasoning"]["known_sum"], 21)
        self.assertTrue(report["pages"][0]["full_image_response_coverage"])
        self.assertEqual(report["pages"][0]["exact_matched_regions"], 2)

    def test_single_half_is_not_full_page_acceptance(self):
        top = {"imageId": "0~0_0_100_50", "regions": [region(box=[200, 100, 600, 300])]}
        report = self.run_review([capture("a", [top])])
        self.assertFalse(report["pages"][0]["full_image_response_coverage"])
        self.assertEqual(report["pages"][0]["observed_crop_coverage_percent"], 50)

    def test_out_of_order_capture_files_choose_latest_successful_tile(self):
        earlier = copy.deepcopy(self.full_page)
        earlier["regions"][0]["sourceText"] = "wrong"
        report = self.run_review([capture("new", [self.full_page], started=1788000000003), capture("old", [earlier], started=1788000000002)])
        self.assertEqual(report["pages"][0]["exact_matched_regions"], 2)

    def test_real_epoch_millisecond_duration_is_preserved(self):
        value = capture("a", [self.full_page], started=1788661820886)
        value["metadata"]["completedAt"] = 1788661940891
        report = self.run_review([value])
        self.assertEqual(report["attempts"][0]["started_epoch_millis"], 1788661820886)
        self.assertEqual(report["attempts"][0]["duration_millis"], 120005)

    def test_duplicate_page_ids_are_rejected(self):
        report = self.run_review([capture("a", [self.full_page, copy.deepcopy(self.full_page)])])
        self.assertEqual(report["attempts"][0]["duplicate_page_ids"], 1)
        self.assertFalse(report["pages"][0]["full_image_response_coverage"])

    def test_truncated_response_cannot_complete_page(self):
        value = capture("a", [self.full_page])
        value["metadata"]["responseTruncated"] = True
        report = self.run_review([value])
        self.assertEqual(report["attempts"][0]["error"], "INCOMPLETE_RESPONSE_CAPTURE")
        self.assertFalse(report["pages"][0]["full_image_response_coverage"])
        self.assertEqual(report["usage_totals"]["input"]["known_sum"], 10)

    def test_source_hash_mapping_and_file_tampering_fail_closed(self):
        self.mapping["images"][0]["source_sha256"] = "0" * 64
        with self.assertRaisesRegex(review.ReviewError, "SOURCE_HASH_MAPPING_MISMATCH"):
            self.run_review([capture("a", [self.full_page])])
        self.mapping = review.make_map(self.manifest, "all")
        with (self.root / "page.png").open("ab") as file:
            file.write(b"changed")
        with self.assertRaisesRegex(review.ReviewError, "SOURCE_IMAGE_HASH_MISMATCH"):
            self.run_review([capture("a", [self.full_page])])

    def test_clipped_box_reports_measured_ink_loss(self):
        page = copy.deepcopy(self.full_page)
        page["regions"][0]["box2d"] = [200, 100, 300, 300]
        report = self.run_review([capture("a", [page])])
        metric = report["pages"][0]["regions"][0]["observations"][0]["geometry"]
        self.assertEqual(metric["dark_ink_coverage_percent"], 50)
        self.assertIn("REGION_INK_COVERAGE_BELOW_99_PERCENT", report["followup_codes"])

    def test_malformed_geometry_rejects_page(self):
        page = copy.deepcopy(self.full_page)
        page["regions"][0]["box2d"] = [100, 100, 50, 300]
        report = self.run_review([capture("a", [page])])
        self.assertEqual(report["attempts"][0]["invalid_page_schemas"], 1)
        self.assertFalse(report["pages"][0]["full_image_response_coverage"])

    def test_rotation_uses_android_clockwise_sign_and_detects_missing_quarter_turn(self):
        self.manifest["pages"][0]["regions"][0]["rotation_degrees_ccw"] = -90
        self.corpus.write_text(json.dumps(self.manifest))
        report = self.run_review([capture("a", [self.full_page])])
        geometry = report["pages"][0]["regions"][0]["observations"][0]["geometry"]
        self.assertEqual(geometry["expected_android_canvas_rotation"], 90)
        self.assertEqual(geometry["rotation_difference_degrees"], 90)
        self.assertIn("REGION_ROTATION_DIFFERS_FROM_FIXTURE", report["followup_codes"])

    def test_blank_page_with_unexpected_text_is_not_success(self):
        self.manifest["pages"][0]["regions"] = []
        self.corpus.write_text(json.dumps(self.manifest))
        report = self.run_review([capture("a", [self.full_page])])
        self.assertTrue(report["pages"][0]["blank_hallucination"])

    def test_actual_request_pixels_are_verified_without_exporting_payload(self):
        value = capture("a", [self.full_page])
        encoded = base64.b64encode((self.root / "page.png").read_bytes()).decode()
        value["request"] = {"contents": [{"parts": [{"text": "Image ID: 0~0_0_100_100; original dimensions: 100x100"}, {"inlineData": {"data": encoded}}]}], "generationConfig": {"thinkingConfig": {"thinkingLevel": "MEDIUM"}}}
        report = self.run_review([value])
        self.assertTrue(report["attempts"][0]["image_evidence"][0]["pixel_match"])
        self.assertNotIn(encoded, json.dumps(report))
        self.assertEqual(report["attempts"][0]["wire_settings"]["thinking"], "MEDIUM")

    def test_different_uploaded_pixels_reject_fixture_association(self):
        other = self.root / "other.png"
        Image.new("RGB", (100, 100), "red").save(other)
        value = capture("a", [self.full_page])
        value["request"] = {"contents": [{"parts": [{"text": "Image ID: 0~0_0_100_100; original dimensions: 100x100"}, {"inlineData": {"data": base64.b64encode(other.read_bytes()).decode()}}]}]}
        report = self.run_review([value])
        self.assertFalse(report["attempts"][0]["image_evidence"][0]["pixel_match"])
        self.assertFalse(report["pages"][0]["full_image_response_coverage"])
        self.assertIn("TRANSMITTED_IMAGE_MISMATCH", report["followup_codes"])

    def test_configured_and_observed_thinking_are_compared_only_when_available(self):
        value = capture("a", [self.full_page])
        settings = {"provider": {"thinking": "MEDIUM"}}
        unknown = self.run_review([value], settings=settings)
        self.assertFalse(unknown["attempts"][0]["configured_effective_differences"])
        value["request"] = {"generationConfig": {"thinkingConfig": {"thinkingLevel": "HIGH"}}}
        observed = self.run_review([value], settings=settings)
        self.assertEqual(observed["attempts"][0]["configured_effective_differences"], ["THINKING_DIFFERS"])
        self.assertIn("CONFIGURED_EFFECTIVE_SETTINGS_DIFFER", observed["followup_codes"])

    def test_response_page_missing_from_actual_request_is_not_accepted(self):
        value = capture("a", [self.full_page])
        value["request"] = {"contents": [{"parts": [{"text": "Image ID: unexpected~0_0_100_100; original dimensions: 100x100"}]}]}
        report = self.run_review([value])
        self.assertFalse(report["pages"][0]["full_image_response_coverage"])
        self.assertEqual(report["attempts"][0]["missing_requested_page_ids"], 1)

    def test_secrets_in_every_freeform_surface_are_absent_from_report(self):
        secret = "private-secret-do-not-export"
        page = copy.deepcopy(self.full_page)
        page["regions"][0]["translatedText"] = secret
        value = capture(secret, [page])
        value["metadata"].update(requestUrl="https://host/?key=" + secret, requestHeaders={"Authorization": secret}, error=secret)
        value["response"]["modelVersion"] = secret
        settings = {"provider": {"model": secret, "projectId": secret, "credentialId": secret, "extraHeaders": {"key": secret}}, "instructions": secret}
        events = [{"id": secret, "stage": "USAGE", "message": secret, "details": {"inputTokens": "10", "outputTokens": "3", "reasoningTokens": "7", "token": secret}}]
        report = self.run_review([value], settings=settings, events=events)
        serialized = json.dumps(report)
        self.assertNotIn(secret, serialized)
        self.assertNotIn("Authorization", serialized)
        self.assertTrue(report["events"]["aggregate_matches"])

    def test_spend_is_not_reconciled_without_explicit_reservation_selection(self):
        spend = {"estimated_usage_sgd": "0.01", "recorded_billing_sgd": "0", "requests": {"opaque": {"state": "settled", "usage": {"input_tokens": 10, "output_tokens": 3, "reasoning_tokens": 7, "attempts": 1}}}}
        unbound = self.run_review([capture("a", [self.full_page])], spend=spend)
        self.assertEqual(unbound["spend"]["token_reconciliation"], "NOT_BOUND_TO_RESERVATIONS")
        bound = self.run_review([capture("a", [self.full_page])], spend=spend, spend_request_ids=["opaque"])
        self.assertEqual(bound["spend"]["token_reconciliation"], "MATCH")
        spend["requests"]["opaque"]["usage"]["reasoning_tokens"] = 8
        mismatch = self.run_review([capture("a", [self.full_page])], spend=spend, spend_request_ids=["opaque"])
        self.assertEqual(mismatch["spend"]["token_reconciliation"], "FOLLOWUP_REQUIRED")

    def test_written_reports_are_private(self):
        path = self.root / "report.json"
        review.write_private(path, {"safe": True})
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_comparison_reconciles_source_identities_and_marks_changed_corpus(self):
        first = self.run_review([capture("a", [self.full_page])])
        second = self.run_review([capture("b", [self.full_page])])
        compared = review.compare_reports([first, second])
        self.assertTrue(compared["same_reference_image_identities"])
        self.assertEqual(compared["runs"][0]["exact_matched_regions"], 2)
        second["pages"][0]["source_sha256"] = "0" * 64
        self.assertFalse(review.compare_reports([first, second])["same_reference_image_identities"])


if __name__ == "__main__":
    unittest.main()
