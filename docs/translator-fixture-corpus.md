# Controlled translator corpus

`scripts/fixtures/translation-corpus.json` defines twelve original synthetic pages and 29 text regions. The source passages, reference translations, inclusion decisions, semantic notes, and reading order are reviewable text. They do not come from scanned manga or a selected handset chapter. Reference meanings remain **human review pending**; exact English wording is not a translation-quality assertion.

Generate the local artifacts with Python 3.11 or later and Pillow:

```sh
python3 scripts/translator_fixture_corpus.py
python3 scripts/translator_fixture_corpus.py --verify-existing
python3 -m unittest discover -s scripts/tests -p 'test_translator_fixture_corpus.py' -v
```

The default output is ignored `build/translator/validation/corpus/`. `manifest.json` records original-image dimensions, RGB pixel hashes, PNG hashes, rotated quadrilaterals, unrotated containers, source text, expectation notes, font file hashes and faces, renderer versions, and CBZ identities. `source-passages.json` independently preserves the uncorrected fixture text. Detection and recognition confidence are unavailable because these are known renderer placements, not measured OCR results.

Copy `local/Controlled Translation Validation/` into the `local` folder under Mihon's selected storage location. It contains two image-only chapters: `001 - Five-page modes.cbz` and `002 - Twelve-page corpus.cbz`. The five-page chapter reuses the first five corpus images, in the same order, for comparisons across Vertex, Max, Halving, and Custom batch size two. Do not treat these overlapping archives as independent paid-test pages. Expected answers and metadata are outside the CBZ files.

| Pages | Controlled cases |
| --- | --- |
| 1–5 | Vertical Japanese with right-to-left columns, Korean, simplified Chinese, traditional Chinese, mixed-language passages; negation, glossary names, and speaker relationships |
| 6–9 | 12/18/24-pixel lettering, 18°/−90° rotations, meaningful signs and sound effects, labeled advertisements and watermarks beside retained narration |
| 10–12 | Exactly white blank page, 3,600-pixel long strip with top-to-bottom order, and 2,400-pixel spread read from right to left |

The generator uses local Hiragino Sans W3, Hiragino Sans GB W3, Apple SD Gothic Neo Regular, and Arial Regular on the development Mac. It verifies every non-whitespace code point maps to a nonzero glyph in the selected font's Unicode cmap. Missing glyphs or fonts stop generation. No fonts are downloaded or bundled. Other hosts can provide `--font ja=/path/to/font.ttc` and equivalent `zh`, `ko`, and `en` overrides; `--font-index ja=1` selects another collection face. Different font files or renderer versions create a new artifact identity, so compare runs using the same generated PNGs and manifest.

Rendering uses Pillow's basic layout with explicit vertical columns; it does not silently select fallback fonts, shrink text, or claim support for vertical punctuation substitution. PNGs and CBZs are byte-reproducible with the recorded fonts and renderer environment. The archive timestamps and file order are fixed. Fonts are not needed to verify or use already generated artifacts.

The automated checks cover corpus membership, chapter order, source-text preservation, exclusions, bounds, all-text-ink containment in rotated polygons, blank pixels, font coverage, overflow rejection, image tampering, and repeated-render hash equality. The Japanese vertical page, traditional Chinese page, mixed-language page, and rotated page were visually inspected during creation; all four script families rendered visible glyphs rather than missing-character boxes.

These pages deliberately isolate OCR and translation cases. Their clean backgrounds and explicit incidental labels cannot establish quality on textured manga artwork, ambiguous ad placement, handwriting, or real chapter narrative. Add selected real pages as a separate corpus with their own consent, hashes, and passage reviews. Do not replace pending human review with an automated model rating.
