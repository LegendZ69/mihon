"""Guarded synthetic review patches; no device, credential, or provider calls."""

import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from PIL import Image

from scripts import translator_reviewed_corrections as corrections


ROOT = Path(__file__).resolve().parents[2]


class ReviewedCorrectionTests(unittest.TestCase):
    def setUp(self):
        self.fixture = corrections.read_json(corrections.DEFAULT_FIXTURE)
        self.results = [json.loads((ROOT / p["historical_asset"]).read_text()) for p in self.fixture["pages"]]

    def request(self, bundle, index=0):
        bound = bundle["pages"][index]
        return ({"width": bound["width"], "height": bound["height"], "image_sha256": bound["image_hash"]},
                {"imageId": bound["image_id"], "imageHash": bound["image_hash"],
                 "sourceRevision": bound["source_revision"], "regions": copy.deepcopy(bound["baseline_regions"])})

    def test_historical_identity_is_intact_and_source_png_hashes_match(self):
        for page, baseline in zip(self.fixture["pages"], self.results):
            asset = ROOT / page["historical_asset"]
            self.assertEqual(hashlib.sha256(asset.read_bytes()).hexdigest(), page["historical_asset_sha256"])
            self.assertEqual(hashlib.sha256(asset.with_suffix(".png").read_bytes()).hexdigest(), page["image_hash"])
            self.assertEqual(baseline["revision"], page["historical_asset_revision"])

    def test_binds_new_export_revision_without_rewriting_source_or_unaffected_regions(self):
        before = copy.deepcopy(self.results)
        for result in self.results:
            result["revision"] += 1000
        bundle = corrections.bind(self.fixture, self.results)
        for index, bound in enumerate(bundle["pages"]):
            self.assertEqual(bound["source_revision"], before[index]["revision"] + 1000)
            page, baseline = self.request(bundle, index)
            response = corrections.reviewed_response(bundle, page, baseline)
            self.assertTrue(response["visualComplete"])
            for old, new in zip(bound["baseline_regions"], response["pages"][0]["regions"]):
                for field in ("id", "sourceText", "correctedText", "readingOrder", "included"):
                    self.assertEqual(old[field], new[field])
                if old["id"] not in bound["changed_ids"]:
                    self.assertEqual(old, new)
        self.assertEqual([r["regions"] for r in before], [r["regions"] for r in self.results])

    def test_rejects_changed_source_identity_ambiguous_page_or_changed_patch_target(self):
        for defect in ("wrong-hash", "duplicate", "changed-text", "changed-geometry", "changed-source"):
            with self.subTest(defect=defect):
                results = copy.deepcopy(self.results)
                if defect == "wrong-hash": results[0]["imageHash"] = "0" * 64
                elif defect == "duplicate": results.append(copy.deepcopy(results[0]))
                elif defect == "changed-text": results[0]["regions"][1]["translatedText"] = "Already edited"
                elif defect == "changed-geometry": results[1]["regions"][0]["points"][0]["y"] += 1
                else: results[0]["regions"][1]["sourceText"] += "changed"
                with self.assertRaises(ValueError): corrections.bind(self.fixture, results)

    def test_response_refuses_stale_revision_different_bytes_or_any_intervening_region_edit(self):
        bundle = corrections.bind(self.fixture, self.results)
        for defect in ("revision", "bytes", "dimensions", "untargeted-text", "region-order", "geometry"):
            with self.subTest(defect=defect):
                page, baseline = self.request(bundle, 1)
                if defect == "revision": baseline["sourceRevision"] += 1
                elif defect == "bytes": page["image_sha256"] = "0" * 64
                elif defect == "dimensions": page["height"] += 1
                elif defect == "untargeted-text": baseline["regions"][1]["translatedText"] += "edited"
                elif defect == "region-order": baseline["regions"].reverse()
                else: baseline["regions"][1]["polygon"][0][0] += 0.01
                with self.assertRaises(ValueError): corrections.reviewed_response(bundle, page, baseline)

    def test_only_android_float32_rounding_is_tolerated(self):
        bundle = corrections.bind(self.fixture, self.results)
        page, baseline = self.request(bundle)
        baseline["regions"][0]["polygon"][0][0] += 0.00004
        corrections.reviewed_response(bundle, page, baseline)
        baseline["regions"][0]["polygon"][0][0] += 0.01
        with self.assertRaises(ValueError): corrections.reviewed_response(bundle, page, baseline)

    def test_mask_expansions_cover_reproduced_caps_without_touching_warning_or_other_edges(self):
        page = self.fixture["pages"][1]
        with Image.open((ROOT / page["historical_asset"]).with_suffix(".png")) as original:
            for change, point in zip(page["changes"], [(761, 211), (645, 930)]):
                old = change["expected"]["points"]
                new = change["set"]["points"]
                self.assertEqual(original.getpixel(point)[:3], (0, 0, 0))
                self.assertLess(point[1], min(p["y"] for p in old))
                self.assertGreaterEqual(point[1], min(p["y"] for p in new))
                self.assertAlmostEqual(old[0]["y"] - new[0]["y"], 3.8)
                self.assertEqual([p["x"] for p in old], [p["x"] for p in new])
                self.assertEqual(old[2:], new[2:])
        bundle = corrections.bind(self.fixture, self.results)
        bound = bundle["pages"][1]
        self.assertEqual(bound["baseline_regions"][-1], bound["proposed_regions"][-1])

    def test_rotation_matches_authored_physical_baseline_and_meaning_remains_ambiguous(self):
        corpus = json.loads((ROOT / "scripts/fixtures/translation-corpus.json").read_text())
        authored = corpus["pages"][6]["regions"][1]
        self.assertEqual(-authored["rotation_degrees_ccw"], 90)
        bundle = corrections.bind(self.fixture, self.results)
        self.assertEqual(bundle["pages"][2]["proposed_regions"][1]["rotation"], 90)
        sister = bundle["pages"][0]["proposed_regions"][1]["translatedText"]
        self.assertIn("the younger sister", sister)
        self.assertNotIn("your sister", sister)
        self.assertNotIn("my sister", sister)
        self.assertEqual(bundle["pages"][3]["proposed_regions"][3]["translatedText"], "It's a promise.")

    def test_native_export_reader_only_loads_chapter_results(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "export.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("chapters/000000.json", json.dumps({"results": self.results}))
                archive.writestr("diagnostics/captures/opaque.json", "not JSON and deliberately not parsed")
            self.assertEqual(corrections.load_results(path), self.results)


if __name__ == "__main__":
    unittest.main()
