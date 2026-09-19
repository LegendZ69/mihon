"""Controlled corpus checks; run with unittest, no device or paid API needed."""

import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from PIL import Image, ImageChops, ImageDraw

MODULE_PATH = Path(__file__).resolve().parents[1] / "translator_fixture_corpus.py"
MODULE_SPEC = importlib.util.spec_from_file_location("translator_fixture_corpus", MODULE_PATH)
corpus = importlib.util.module_from_spec(MODULE_SPEC)
MODULE_SPEC.loader.exec_module(corpus)


class CorpusSpecificationTests(unittest.TestCase):
    def setUp(self):
        self.spec = json.loads(corpus.DEFAULT_SPEC.read_text(encoding="utf-8"))

    def test_twelve_controlled_pages_cover_required_scripts_and_cases(self):
        corpus.validate_spec(self.spec)
        coverage = {tag for page in self.spec["pages"] for tag in page["coverage"]}
        for tag in ["Japanese", "Korean", "simplified Chinese", "traditional Chinese", "mixed languages", "vertical text", "tiny lettering", "rotated lettering", "meaningful signs", "sound effects", "advertisement exclusion", "watermark exclusion", "empty page", "long strip", "double-page spread"]:
            self.assertIn(tag, coverage)
        self.assertEqual(self.spec["human_meaning_review"], "pending")
        self.assertIn("synthetic", self.spec["provenance"])

    def test_rejects_geometry_outside_original_image(self):
        self.spec["pages"][0]["regions"][0]["box"][0] = -1
        with self.assertRaisesRegex(ValueError, "outside original image"):
            corpus.validate_spec(self.spec)

    def test_rejects_duplicate_or_noncontiguous_reading_order(self):
        self.spec["pages"][0]["regions"][1]["reading_order"] = 0
        with self.assertRaisesRegex(ValueError, "reading order"):
            corpus.validate_spec(self.spec)

    def test_exclusions_require_reason_and_no_story_order(self):
        self.spec["pages"][8]["regions"][1]["reading_order"] = 1
        with self.assertRaisesRegex(ValueError, "Excluded text"):
            corpus.validate_spec(self.spec)

    def test_chapter_cannot_repeat_a_page(self):
        self.spec["five_page_chapter"][1] = self.spec["five_page_chapter"][0]
        with self.assertRaisesRegex(ValueError, "five distinct"):
            corpus.validate_spec(self.spec)

    def test_blank_cannot_have_source_regions(self):
        self.spec["pages"][9]["regions"] = copy.deepcopy(self.spec["pages"][0]["regions"])
        with self.assertRaisesRegex(ValueError, "blank page"):
            corpus.validate_spec(self.spec)


class CorpusRenderingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.spec = json.loads(corpus.DEFAULT_SPEC.read_text(encoding="utf-8"))
        if not all(any(Path(path).is_file() for path in candidates) for candidates in corpus.FONT_CANDIDATES.values()):
            raise unittest.SkipTest("Rendering tests require the documented local CJK fonts; generate with explicit font overrides on other hosts")
        cls.temporary = tempfile.TemporaryDirectory()
        cls.output = Path(cls.temporary.name) / "corpus"
        cls.manifest = corpus.generate(corpus.DEFAULT_SPEC, cls.output)

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "temporary"):
            cls.temporary.cleanup()

    def test_each_recorded_glyph_exists_in_selected_font(self):
        for font in self.manifest["fonts"].values():
            codepoints = corpus.font_codepoints(Path(font["path"]), font["face_index"])
            self.assertTrue({int(p[2:], 16) for p in font["verified_codepoints"]} <= codepoints)
            self.assertNotIn(0x10FFFF, codepoints)
            self.assertEqual(corpus.sha256(Path(font["path"])), font["sha256"])

    def test_missing_glyph_fails_instead_of_rendering_tofu(self):
        spec = copy.deepcopy(self.spec)
        spec["pages"][0]["regions"][0]["source_text"] += "\U0010ffff"
        with self.assertRaisesRegex(ValueError, "lacks actual glyphs"):
            corpus.resolve_fonts(spec, {}, {})

    def test_regeneration_preserves_image_archive_and_manifest_hashes(self):
        other = Path(self.temporary.name) / "second"
        duplicate = corpus.generate(corpus.DEFAULT_SPEC, other)
        self.assertEqual(self.manifest, duplicate)
        self.assertEqual(corpus.sha256(self.output / "manifest.json"), corpus.sha256(other / "manifest.json"))

    def test_original_coordinate_polygons_enclose_all_text_ink(self):
        for page in self.manifest["pages"]:
            with Image.open(self.output / page["file"]) as image:
                for region in page["regions"]:
                    x, y, width, height = region["box"]
                    ink = image.crop((x, y, x + width, y + height)).convert("L").point(lambda value: 255 if value < 100 else 0)
                    polygon = Image.new("L", ink.size)
                    ImageDraw.Draw(polygon).polygon([(px - x, py - y) for px, py in region["polygon"]], fill=255)
                    self.assertIsNone(ImageChops.subtract(ink, polygon).getbbox(), f"Clipped geometry: {page['id']}/{region['id']}")
                    self.assertIsNotNone(ink.getbbox())

    def test_source_passages_are_separate_and_unchanged(self):
        passages = json.loads((self.output / "source-passages.json").read_text(encoding="utf-8"))
        expected = {(page["id"], region["id"]): region["source_text"] for page in self.spec["pages"] for region in page["regions"]}
        actual = {(page["id"], region["id"]): region["source_text"] for page in passages["pages"] for region in page["regions"]}
        self.assertEqual(actual, expected)
        for archive in self.manifest["archives"]:
            with zipfile.ZipFile(self.output / archive["file"]) as cbz:
                self.assertTrue(all(name.endswith(".png") for name in cbz.namelist()))

    def test_blank_has_no_ink_or_metadata_text(self):
        page = next(page for page in self.manifest["pages"] if page.get("blank"))
        with Image.open(self.output / page["file"]) as image:
            self.assertEqual(image.getextrema(), ((255, 255),) * 3)
            self.assertFalse(image.text)

    def test_text_overflow_is_rejected_without_resizing(self):
        page = copy.deepcopy(self.spec["pages"][0])
        page["regions"][0]["font_size"] = 200
        with self.assertRaisesRegex(ValueError, "implicit shrinking"):
            corpus.render_page(page, self.manifest["fonts"])

    def test_verifier_rejects_changed_image_bytes(self):
        page = self.manifest["pages"][0]
        path = self.output / page["file"]
        original = path.read_bytes()
        try:
            path.write_bytes(original + b"tamper")
            with self.assertRaisesRegex(ValueError, "Image hash mismatch"):
                corpus.verify_existing(self.output)
        finally:
            path.write_bytes(original)


if __name__ == "__main__":
    unittest.main()
