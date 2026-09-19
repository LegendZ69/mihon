#!/usr/bin/env python3
"""Render controlled OCR fixtures; requires Pillow, never downloads fonts or images.

Outputs are private build artifacts. Source passages and review expectations are
separate from the image-only CBZ files that may be submitted to a provider.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
from typing import Any
import zipfile

from PIL import Image, ImageDraw, ImageFont, __version__ as pillow_version, features

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SPEC = ROOT / "scripts/fixtures/translation-corpus.json"
DEFAULT_OUTPUT = ROOT / "build/translator/validation/corpus"
FONT_CANDIDATES = {
    "ja": ["/System/Library/Fonts/ヒラギノ角ゴシック W3.ttc"],
    "zh": ["/System/Library/Fonts/Hiragino Sans GB.ttc"],
    "ko": ["/System/Library/Fonts/AppleSDGothicNeo.ttc"],
    "en": ["/System/Library/Fonts/Supplemental/Arial.ttf"],
}


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def font_codepoints(path: Path, face_index: int = 0) -> set[int]:
    """Read Unicode cmap formats 4/12, rejecting missing-glyph mappings.

    This avoids silently accepting FreeType's tofu glyph and needs no fontTools.
    Fonts with unsupported cmap formats fail closed when their characters cannot
    be proven present. Fonts are read from user-controlled local paths only.
    """
    data = path.read_bytes()
    u16 = lambda at: struct.unpack_from(">H", data, at)[0]
    u32 = lambda at: struct.unpack_from(">I", data, at)[0]
    if data[:4] == b"ttcf":
        if not 0 <= face_index < u32(8):
            raise ValueError(f"Invalid font face index {face_index}: {path}")
        start = u32(12 + 4 * face_index)
    else:
        if face_index != 0:
            raise ValueError(f"Single-face font requires index 0: {path}")
        start = 0
    cmap = None
    for i in range(u16(start + 4)):
        record = start + 12 + i * 16
        if data[record:record + 4] == b"cmap":
            cmap = u32(record + 8)
            break
    if cmap is None:
        raise ValueError(f"No character map in font: {path}")
    result: set[int] = set()
    for i in range(u16(cmap + 2)):
        record = cmap + 4 + 8 * i
        platform, encoding = u16(record), u16(record + 2)
        if platform != 0 and (platform != 3 or encoding not in (1, 10)):
            continue
        table = cmap + u32(record + 4)
        if u16(table) == 12:
            for j in range(u32(table + 12)):
                first, last, glyph = struct.unpack_from(">III", data, table + 16 + j * 12)
                if last > 0x10FFFF or first > last:
                    raise ValueError(f"Invalid format 12 character range: {path}")
                result.update(range(first + (1 if glyph == 0 else 0), last + 1))
        elif u16(table) == 4:
            segments = u16(table + 6) // 2
            ends = table + 14
            starts = ends + 2 * segments + 2
            deltas = starts + 2 * segments
            offsets = deltas + 2 * segments
            for j in range(segments):
                first, last = u16(starts + 2 * j), u16(ends + 2 * j)
                delta, offset = u16(deltas + 2 * j), u16(offsets + 2 * j)
                for point in range(first, min(last, 0xFFFE) + 1):
                    if offset == 0:
                        glyph = (point + delta) & 0xFFFF
                    else:
                        glyph = u16(offsets + 2 * j + offset + 2 * (point - first))
                        if glyph:
                            glyph = (glyph + delta) & 0xFFFF
                    if glyph:
                        result.add(point)
    return result


def validate_spec(spec: dict[str, Any]) -> None:
    if spec.get("schema_version") != 1 or len(spec.get("pages", [])) != 12:
        raise ValueError("A version 1 corpus must contain exactly twelve pages")
    seen: set[str] = set()
    for page in spec["pages"]:
        page_id = page["id"]
        if page_id in seen or Path(page_id).name != page_id or page_id in (".", ".."):
            raise ValueError(f"Invalid or duplicate page identity: {page_id}")
        seen.add(page_id)
        width, height = page["width"], page["height"]
        if width <= 0 or height <= 0 or width * height > 16_000_000:
            raise ValueError(f"Unsupported fixture dimensions: {page_id}")
        if page.get("blank", False) != (len(page["regions"]) == 0):
            raise ValueError(f"Only an explicitly blank page can have no regions: {page_id}")
        region_ids: set[str] = set()
        order = []
        for region in page["regions"]:
            if region["id"] in region_ids:
                raise ValueError(f"Duplicate region identity: {page_id}/{region['id']}")
            region_ids.add(region["id"])
            x, y, box_width, box_height = region["box"]
            if not (0 <= x < x + box_width <= width and 0 <= y < y + box_height <= height):
                raise ValueError(f"Region outside original image: {page_id}/{region['id']}")
            if not region["source_text"].strip() or region["font_size"] < 1:
                raise ValueError(f"Invalid source text or font size: {page_id}/{region['id']}")
            if region.get("layout", "horizontal") not in ("horizontal", "vertical-rtl"):
                raise ValueError("Unsupported text layout")
            if region["include"]:
                order.append(region["reading_order"])
                if not region.get("reference_translation") or not region.get("meaning_notes"):
                    raise ValueError(f"Missing meaning expectation: {page_id}/{region['id']}")
            elif region["reading_order"] is not None or not region.get("exclusion_reason"):
                raise ValueError(f"Excluded text requires a reason and null order: {page_id}/{region['id']}")
        if sorted(order) != list(range(len(order))):
            raise ValueError(f"Included reading order must be contiguous: {page_id}")
    chapter = spec["five_page_chapter"]
    if len(chapter) != 5 or len(set(chapter)) != 5 or not set(chapter) <= seen:
        raise ValueError("The mode comparison chapter must identify five distinct corpus pages")


def resolve_fonts(spec: dict[str, Any], overrides: dict[str, Path], indices: dict[str, int]) -> dict[str, Any]:
    required: dict[str, set[str]] = {}
    for page in spec["pages"]:
        for region in page["regions"]:
            required.setdefault(region["font"], set()).update(c for c in region["source_text"] if not c.isspace())
    fonts = {}
    for font_id, characters in sorted(required.items()):
        candidates = [overrides[font_id]] if font_id in overrides else [Path(p) for p in FONT_CANDIDATES.get(font_id, [])]
        path = next((p for p in candidates if p.is_file()), None)
        if path is None:
            raise ValueError(f"No local {font_id} font. Provide --font {font_id}=/absolute/font/path")
        path = path.resolve()
        face_index = indices.get(font_id, 0)
        coverage = font_codepoints(path, face_index)
        missing = sorted(c for c in characters if ord(c) not in coverage)
        if missing:
            raise ValueError(f"Font {font_id} lacks actual glyphs: {' '.join(f'U+{ord(c):04X}({c})' for c in missing)}")
        font = ImageFont.truetype(str(path), size=24, index=face_index, layout_engine=ImageFont.Layout.BASIC)
        fonts[font_id] = {
            "path": str(path), "sha256": sha256(path), "face_index": face_index,
            "family": font.getname()[0], "style": font.getname()[1],
            "glyph_validation": "Every non-whitespace source code point maps to a nonzero glyph in the selected font's Unicode cmap.",
            "verified_codepoints": [f"U+{ord(c):04X}" for c in sorted(characters)],
        }
    return fonts


def text_mask(region: dict[str, Any], font_info: dict[str, Any]) -> Image.Image:
    size = region["font_size"]
    font = ImageFont.truetype(font_info["path"], size=size, index=font_info["face_index"], layout_engine=ImageFont.Layout.BASIC)
    text = region["source_text"]
    if region.get("layout") == "vertical-rtl":
        columns = text.split("\n")
        cell = math.ceil(size * 1.2)
        mask = Image.new("L", (len(columns) * cell, max(map(len, columns)) * cell))
        draw = ImageDraw.Draw(mask)
        for i, column in enumerate(columns):
            for j, character in enumerate(column):
                left, top, right, bottom = draw.textbbox((0, 0), character, font=font)
                x = (len(columns) - i - 1) * cell + (cell - (right - left)) // 2 - left
                y = j * cell + (cell - (bottom - top)) // 2 - top
                draw.text((x, y), character, fill=255, font=font)
    else:
        draw = ImageDraw.Draw(Image.new("L", (1, 1)))
        spacing = math.ceil(size * 0.3)
        left, top, right, bottom = draw.multiline_textbbox((0, 0), text, font=font, spacing=spacing)
        mask = Image.new("L", (right - left, bottom - top))
        ImageDraw.Draw(mask).multiline_text((-left, -top), text, font=font, spacing=spacing, fill=255)
    bounds = mask.getbbox()
    if bounds is None:
        raise ValueError(f"Text rendered no visible pixels: {region['id']}")
    return mask.crop(bounds)


def render_page(page: dict[str, Any], fonts: dict[str, Any]) -> tuple[Image.Image, list[dict[str, Any]]]:
    image = Image.new("RGB", (page["width"], page["height"]), "white")
    draw = ImageDraw.Draw(image)
    result = []
    for region in page["regions"]:
        x, y, width, height = region["box"]
        draw.rounded_rectangle((x, y, x + width, y + height), radius=24, outline=(112, 112, 112), width=3)
        mask = text_mask(region, fonts[region["font"]])
        padding = 8
        padded = Image.new("L", (mask.width + 2 * padding, mask.height + 2 * padding))
        padded.paste(mask, (padding, padding))
        angle = region.get("rotation_degrees_ccw", 0)
        rotated = padded.rotate(angle, resample=Image.Resampling.BICUBIC, expand=True)
        if rotated.width + 24 > width or rotated.height + 24 > height:
            raise ValueError(f"Text does not fit {page['id']}/{region['id']}; implicit shrinking is forbidden")
        left, top = x + (width - rotated.width) // 2, y + (height - rotated.height) // 2
        image.paste((0, 0, 0), (left, top), rotated)
        radians = math.radians(angle)
        cosine, sine = math.cos(radians), math.sin(radians)
        center_x, center_y = left + rotated.width / 2, top + rotated.height / 2
        polygon = []
        for dx, dy in [(-padded.width / 2, -padded.height / 2), (padded.width / 2, -padded.height / 2), (padded.width / 2, padded.height / 2), (-padded.width / 2, padded.height / 2)]:
            polygon.append([round(center_x + cosine * dx + sine * dy, 3), round(center_y - sine * dx + cosine * dy, 3)])
        ink = rotated.getbbox()
        result.append({
            **region, "polygon": polygon,
            "bounding_box_xyxy": [min(p[0] for p in polygon), min(p[1] for p in polygon), max(p[0] for p in polygon), max(p[1] for p in polygon)],
            "text_ink_box_xyxy": [left + ink[0], top + ink[1], left + ink[2], top + ink[3]],
            "geometry_origin": "Known renderer placement, not OCR detection or a measured confidence score",
            "human_meaning_review": "pending", "detection_confidence": None, "recognition_confidence": None,
        })
    return image, result


def write_archive(path: Path, images: list[Path]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as archive:
        for i, image in enumerate(images, 1):
            entry = zipfile.ZipInfo(f"{i:03}.png", date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_STORED
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, image.read_bytes())


def generate(spec_path: Path, output: Path, overrides: dict[str, Path] | None = None, indices: dict[str, int] | None = None) -> dict[str, Any]:
    spec = json.loads(spec_path.read_text(encoding="utf-8"))
    validate_spec(spec)
    fonts = resolve_fonts(spec, overrides or {}, indices or {})
    image_dir = output / "images"
    local_series = output / "local/Controlled Translation Validation"
    image_dir.mkdir(parents=True, exist_ok=True)
    local_series.mkdir(parents=True, exist_ok=True)
    pages = []
    for page in spec["pages"]:
        image, regions = render_page(page, fonts)
        path = image_dir / f"{page['id']}.png"
        image.save(path, format="PNG", optimize=False, compress_level=9)
        pages.append({**page, "file": path.relative_to(output).as_posix(), "sha256": sha256(path), "pixel_sha256": hashlib.sha256(image.tobytes()).hexdigest(), "mode": image.mode, "regions": regions})
    by_id = {page["id"]: output / page["file"] for page in pages}
    archives = []
    for filename, ids in [("001 - Five-page modes.cbz", spec["five_page_chapter"]), ("002 - Twelve-page corpus.cbz", list(by_id))]:
        path = local_series / filename
        write_archive(path, [by_id[page_id] for page_id in ids])
        archives.append({"file": path.relative_to(output).as_posix(), "sha256": sha256(path), "page_ids": ids, "image_only": True})
    write_json(output / "source-passages.json", {"corpus_id": spec["corpus_id"], "provenance": spec["provenance"], "pages": [{"id": page["id"], "regions": [{"id": r["id"], "source_text": r["source_text"]} for r in page["regions"]]} for page in spec["pages"]]})
    manifest = {
        **spec, "source_spec_sha256": sha256(spec_path),
        "renderer": {"script_sha256": sha256(Path(__file__)), "pillow": pillow_version, "freetype": features.version_module("freetype2"), "layout_engine": "Pillow BASIC; explicit vertical columns; no platform font fallback", "png": "RGB, lossless, no text metadata; 9 compression; deterministic in the recorded renderer/font environment"},
        "fonts": fonts, "pages": pages, "archives": archives,
        "source_passages": {"file": "source-passages.json", "sha256": sha256(output / "source-passages.json")},
        "review_policy": "Reference translations and semantic notes are authored expectations, not externally verified gold labels. Human passage review is pending. Exact English string matching is not a translation-quality criterion.",
        "local_source_note": "Copy local/Controlled Translation Validation into the local folder of Mihon's selected storage location. The five-page chapter is a subset of the twelve-page chapter; avoid accidental duplicate paid tests.",
    }
    write_json(output / "manifest.json", manifest)
    verify_existing(output)
    return manifest


def verify_existing(output: Path) -> dict[str, Any]:
    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
    validate_spec(manifest)
    hashes = {}
    for page in manifest["pages"]:
        path = output / page["file"]
        if sha256(path) != page["sha256"]:
            raise ValueError(f"Image hash mismatch: {page['id']}")
        with Image.open(path) as image:
            if image.size != (page["width"], page["height"]) or image.mode != "RGB":
                raise ValueError(f"Image dimensions or mode mismatch: {page['id']}")
            if hashlib.sha256(image.tobytes()).hexdigest() != page["pixel_sha256"]:
                raise ValueError(f"Pixel hash mismatch: {page['id']}")
            if page.get("blank") and image.getextrema() != ((255, 255),) * 3:
                raise ValueError("Blank fixture contains marks")
        for region in page["regions"]:
            polygon = region["polygon"]
            if len(polygon) != 4 or any(not (0 <= x <= page["width"] and 0 <= y <= page["height"]) for x, y in polygon):
                raise ValueError(f"Invalid original-coordinate polygon: {page['id']}/{region['id']}")
            area = abs(sum(polygon[i][0] * polygon[(i + 1) % 4][1] - polygon[(i + 1) % 4][0] * polygon[i][1] for i in range(4))) / 2
            if area <= 0:
                raise ValueError("Degenerate region polygon")
        hashes[page["id"]] = page["sha256"]
    for archive in manifest["archives"]:
        path = output / archive["file"]
        if sha256(path) != archive["sha256"]:
            raise ValueError("CBZ hash mismatch")
        with zipfile.ZipFile(path) as cbz:
            names = [f"{i:03}.png" for i in range(1, len(archive["page_ids"]) + 1)]
            if cbz.namelist() != names:
                raise ValueError("CBZ must contain only correctly ordered images")
            for name, page_id in zip(names, archive["page_ids"]):
                if hashlib.sha256(cbz.read(name)).hexdigest() != hashes[page_id]:
                    raise ValueError("CBZ page differs from original-image identity")
    passages = manifest["source_passages"]
    if sha256(output / passages["file"]) != passages["sha256"]:
        raise ValueError("Original source passages changed")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, default=DEFAULT_SPEC)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--font", action="append", default=[], metavar="ID=PATH", help="Override ja, zh, ko, or en with a local font; never silently substitutes glyphs")
    parser.add_argument("--font-index", action="append", default=[], metavar="ID=N", help="Select a TTC face; default 0")
    parser.add_argument("--verify-existing", action="store_true", help="Verify artifacts without rerendering or requiring fonts")
    args = parser.parse_args()
    try:
        overrides = {key: Path(value) for key, value in (entry.split("=", 1) for entry in args.font)}
        indices = {key: int(value) for key, value in (entry.split("=", 1) for entry in args.font_index)}
        manifest = verify_existing(args.output) if args.verify_existing else generate(args.spec, args.output, overrides, indices)
    except (ValueError, OSError, KeyError, struct.error) as error:
        parser.exit(1, f"Corpus validation failed: {error}\n")
    print(json.dumps({"manifest": str((args.output / 'manifest.json').resolve()), "pages": len(manifest["pages"]), "regions": sum(len(p["regions"]) for p in manifest["pages"]), "human_meaning_review": manifest["human_meaning_review"], "archives": manifest["archives"]}, indent=2))


if __name__ == "__main__":
    main()
