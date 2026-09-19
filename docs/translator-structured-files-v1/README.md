Return a JSON object with format "mihon-structured-translations", version 1 and a pages array.
Each page needs an imageId and a regions array. Provide its original width and height.
Add imageHash only when you know the unchanged original's lowercase SHA-256 hash. A hash plus matching
dimensions enables automatic matching. Without verified content identity the user must select the
chapter and original page explicitly. Numeric imageId values are labels, never page numbers.

Each geometric region needs a stable nonblank id, sourceText, translatedText and points [{"x":0,"y":0}]
in original-image pixels. Points follow the perimeter of a convex, simple, nonzero-area polygon.
A repeated closing vertex or redundant points on an unchanged straight edge are permitted and normalized.
After normalization, the polygon must contain 3 to 32 distinct corners; at most 256 input points are accepted.
No crossed, concave, out-of-bounds or degenerate polygons are accepted. Do not invent missing geometry.
rotation is physical clockwise degrees in original coordinates, from -360 to 360; vertical writing alone
does not imply rotation. Use distinct nonnegative readingOrder values for included passages.
Classify dialogue, narration, names, meaningful signs and titles accurately; use sound_effect for SFX.
Spoken words remain dialogue even when they imitate sounds. SFX remains inspectable and is hidden only
by the reader's Ignore sound effects policy; keep the original transcription and any supplied translation.

A text-only update may contain only id and translatedText for regions already saved on the selected page.
It updates those exact IDs and preserves their geometry, source text, corrections, scores and styles.
It cannot create new regions. Do not mix text-only and geometric regions in one page.
An empty regions array is a complete blank page, subject to replacement confirmation if a result exists.

Provider output is also accepted as {"pages":[{"imageId":"...","regions":[...]}]} directly, or inside
one Gemini candidate, one OpenAI Responses output message/output_text, or one Chat Completions choice.
Provider geometry uses normalized 0–1000 box2d [y_min,x_min,y_max,x_max] and optional polygon [[x,y],...].
Provider polygon points are [x,y], unlike the box2d ordering. No markdown fences, reasoning/thought parts,
alternative candidates or unrelated output schemas are imported.
Failed, truncated or incomplete outputs are rejected.

A provider tile must include inputTransform with originalWidth, originalHeight, originalImageHash,
left, top, cropWidth, cropHeight, inputWidth and inputHeight. These describe the exact supplied crop
and its mapping into the unchanged original. Mark tiles with tileId or isTile:true. When provided, isTile
must be the JSON boolean true or false; null and other values are rejected. Unknown transforms,
rotated/resampled mappings that this contract cannot express, and multiple tiles targeting one original
cannot be inferred or merged. Import them only after producing complete original-coordinate page regions.

Native Mihon structured ZIP backups use their existing versioned manifest and checksum contract.
Import never invokes a provider, OCR, automatic review or chapter-ahead work. Provider usage found in
imported JSON is historical provenance, not application spending. Keep credentials out of imported files.
