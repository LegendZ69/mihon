# Controlled translation review — 2026-09-06

Automatic assessment of the first live five-page **AI OCR / Vertex single-image preset** run found exact source transcription for all 12 expected regions and correct relative reading order on all five pages. Geometry requires follow-up: the mixed-language page's English warning box cuts through the top of its lettering. **Human passage-level meaning review remains pending.**

This assessment uses the first five pages of `mihon-controlled-translation-v1`: vertical Japanese, Korean, simplified Chinese, traditional Chinese, and mixed languages. These are original synthetic text-and-shape fixtures, not real manga chapters. All five captured responses identify `gemini-3.8-flash`, returned HTTP 200, and have completed, untruncated response captures. The assessment made no additional provider requests.

| Fixture page | Exact source transcriptions | Relative order | Lowest region ink coverage | Observations |
| --- | --- | --- | --- | --- |
| Vertical Japanese | 2/2 | Matches | 100% | Top-to-bottom columns and right-to-left reading order retained |
| Korean | 2/2 | Matches | 100% | Warning, waiting speaker, and person going ahead retained on automatic reading |
| Simplified Chinese | 2/2 | Matches | 100% | Three minutes, medicine, recipient, and prohibition retained; narration classified as dialogue |
| Traditional Chinese | 2/2 | Matches | 100% | Conditional retained; younger-sister nuance and possessor need review |
| Mixed languages | 4/4 | Matches | 63.4106% | English warning box clips lettering; Chinese exit sign classified as dialogue |

All 12 passages were included. Provider reading-order values start at 1 while fixture annotations start at 0; their relative ranks agree, so this offset is not a reading-order failure. Exact source comparison includes Unicode characters and line breaks, without normalization. Exact English string matching was not used as a quality criterion.

## Geometry findings

Provider `box2d` values were decoded as `[y_min, x_min, y_max, x_max]` normalized to 0–1000, matching the application parser. These first five fixtures use axis-aligned source text regions. For each region, the assessment counts source pixels with grayscale below 128 within its known renderer ink bounds, then checks whether each pixel center is inside the provider's rectangle. This measures coverage of original lettering, not translated text layout or final overlay appearance.

The mixed-page `KEEP DOOR CLOSED` result specifies `[818, 293, 834, 707]`, corresponding to original-pixel bounds `[351.6, 1308.8, 848.4, 1334.4]`. The actual lettering bounds are `[354, 1296, 845, 1333]`. Its top boundary misses 12.8 pixels of text height, and the box covers only **63.4106%** of dark lettering pixels. Applying a source mask only inside this region can leave visible original text. Reader inspection and correction are required before claiming accurate masking for this case.

The mixed-page Japanese and Chinese regions cover 99.2019% and 98.3634% of their dark lettering pixels. All other tested regions cover 100%. Across the sample, aggregate dark-ink coverage is 97.3953%; this aggregate must not hide the English warning failure. Intersection-over-union against the fixture's padded region boxes ranges from 0.473301 to 0.948719. Padded-box IoU also reflects intentional fixture padding, so a low score alone is not a rendering failure.

### Code trace and disposition

The captured English rectangle already excludes the top of the lettering. `TranslationWireFormat.decodePages` maps its y coordinates with `height / 1000` and x coordinates with `width / 1000`, consistent with [Google's bounding-box examples](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/bounding-box-detection) (checked 2026-09-06). Tile restoration separately maps local pixels through the retained crop rectangle. Neither operation expands or contracts the supplied rectangle. The two-million-pixel preparation policy accommodates this 1200 × 1600 fixture as one full-size tile; verifying the actual transmitted image hash remains the capture-association limitation stated below.

`TranslationOverlayDocument` clips both its source mask and text to the stored polygon. Canvas readers, the inspector preview, and WebGPU region textures share that document. Text padding is an inset for translated layout, and the two-pixel GPU texture allocation margin does not enlarge the clipped mask. Consequently, changing font size, padding, or texture bounds cannot recover the missing source lettering. No coordinate conversion or mask expansion change is justified by this case.

Keep this result flagged for manual correction. For this authored fixture, the measured source ink supports moving the rectangle's top from 1308.8 to 1296 original-image pixels while retaining the other edges; verify original/translated comparison after saving. This is a fixture-specific correction, not a rule to expand regions on artwork.

Google documents limited spatial precision and recommends single-image text prompts, sufficient image resolution, and examples. The tested preset already uses one image. A future controlled prompt comparison may replace “tight text-region boxes” with “Enclose every visible stroke of the source passage, including the tops and bottoms of all lines; exclude surrounding artwork and balloon borders.” This wording is an application hypothesis, not an official guarantee or a tested improvement. Keep the current result and compare per-region ink coverage before adopting it. [Image understanding guidance](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/capabilities/image-understanding)

## Meaning and classification follow-up

- Traditional Chinese `如果我沒回來，就帶妹妹離開這裡。` was translated as “If I don't come back, take your sister and leave this place.” The conditional and departure are retained. `妹妹` explicitly denotes a younger sister, but the source leaves the possessor unstated. The output omits the younger relationship and chooses “your.” Flag these for contextual human review; the possessor difference is not, by itself, proof of a mistranslation against the authored reference.
- Korean and traditional Chinese warnings use plural “enemies” in their English results. Their source enemy nouns do not establish a firm singular/plural contrast in this context, so this is not counted as a demonstrated meaning error.
- The simplified Chinese narration and mixed Chinese directional sign are classified as dialogue. These differ from fixture annotations; the narration's plain rounded container gives limited visual evidence for that distinction. The sign's meaning and left direction remain present in the translation.
- Automatic reading found no obvious reversal of the prohibitions, quantities, names, or waiting/going-ahead relationships elsewhere in this sample. This observation is not human certification. Returned AI confidence values are uncalibrated model estimates, separate from measured OCR confidence.

The private report `build/translator/validation/private/ai-vertex-quality-review.json` records source and response passages, image/capture hashes, per-region pixel counts, decoded boxes, role differences, and review notes. It omits account identifiers and request headers. Response image indexes were associated with the first five corpus images; these extracted response captures do not independently prove the hashes of the transmitted image bodies.

The remaining seven controlled pages, real chapter artwork/context, human meaning review, PaddleOCR pipelines, other modes/providers, and actual reader overlay pixel comparisons are outside this initial five-page assessment. In particular, that run provides no evidence for tiny or rotated lettering, advertisement/watermark exclusion, or blank-page hallucination behavior. Later recorded samples are assessed separately below.

## Later twelve-page hybrid assessment

The Small PaddleOCR + AI run has 15 complete, untruncated HTTP-200 generation captures using `gemini-3.8-flash`, MEDIUM thinking and shared capacity without a Priority header. All 15 transmitted tile pixel identities match the original corpus/crop mapping. Usage totals reconcile against the event export, but usage/capture completeness is separate from passage or reader acceptance. This review issued no requests and does not represent human approval.

The automatic whole-region matcher finds 20 of 29 authored groups. An additional **AI-assisted line association** accounts for the other nine: Japanese vertical columns, Chinese lines, incidental labels and the spread's first passage are returned as separate regions. Comparing exact full authored strings or their exact individual lines within the same known page accounts for all 27 meaningful passages and both excluded groups. This association method is explicitly different from whitespace normalization or an exact whole-region match; it does not silently change the existing automatic summary.

There are 40 captured region observations, including one overlapping-strip duplicate. For each of the 29 authored groups, the union of its associated **returned, axis-aligned rectangles** covers 100% of the dark source pixels inside the known ink bounds. This measures captured source-mask extent before app merging. It does not measure rotated clipping, translated glyph layout, artwork preservation, final persisted geometry or what either reader painted. The two excluded groups contribute measured source ink but should not be rendered.

| Case | Captured geometry/order evidence | Remaining limit |
| --- | --- | --- |
| Mixed English warning | Hybrid source bounds `[348.0,1286.4,846.0,1340.8]` enclose all measured lettering, unlike the initial five-page AI box with 63.4106% ink coverage. The separate twelve-page AI run also reached 100% on this warning. | One improved sample does not validate a general prompting or OCR-pipeline advantage. |
| Tiny text and recovered Korean | All three tiny passages and all 11 Korean passages appear; associated rectangles cover the known dark ink. Local Small OCR had omitted or corrupted the Korean source passages. | These are clean controlled fixtures. AI confidence is an uncalibrated estimate; measured OCR scores remain separate. |
| Rotated Korean | Provider reports −17° against the fixture's −18° Android Canvas angle. Axis-aligned source ink coverage is 100%. | A one-degree angle difference and full rectangle coverage do not prove that rotated text fits inside the clipped mask. |
| Sideways Japanese | Provider reports 0° against the fixture's +90° Android Canvas angle. | This remains a 90° rotation failure in the captured result; the earlier AI run also missed it. |
| Long strip | All four passages appear; both overlapping observations of the final promise now agree on translation. | The previous app implementation restored stale OCR order. The new two-page runtime check below verifies corrected merging; it does not rewrite this older twelve-page result. |
| Incidental text / blank | Four incidental lines are excluded with reasons and empty translations; the blank page has zero regions. | Synthetic labels are explicit and cannot establish exclusion quality on ambiguous real artwork. |

### Reproduced hybrid merge loss

`PreparedTranslationRequest.merge` restored supplied OCR polygons and `readingOrder` even for `PADDLE_AI`. The actual captured Japanese response orders the five columns correctly, while original local OCR orders them left-to-right. Restoring OCR order therefore discards the correction. Mixing new AI regions with supplied OCR IDs can also create duplicate final order values. A separate crop-and-resize regression shows corrected original-coordinate bounds `[8,116,80,156]` replaced with old OCR bounds `[10,120,70,150]`.

The focused fix retains corrected hybrid geometry after coordinate restoration and numbers the merged provider sequence contiguously. It preserves raw OCR independently, raw transcription, measured scores, stable IDs, user style overrides, corrected transcription and rotation. Pure Paddle text translation still preserves source polygons and IDs. The existing largest-area duplicate selection and tile traversal policy are unchanged. Two new tests fail for the intended symptoms on the old code; all ten image-preparation tests pass after the fix, including a Paddle-only control. The complete app suite then passes 94 tests, with domain/OCR tests, migrations and formatting also passing. The bounded two-original minified runtime check below now verifies the corrected merge. Other inputs remain separate acceptance work; old persisted results are not automatically replaced.

The private line-association report is `ai-assisted-hybrid-passage-review-2026-09-06.json`, SHA-256 `51acd9df21d4fbbdff94e6fd7cf4e07794f74a992aabcfcc62b5d09561d37575`. The unchanged automatic summary is `twelve-hybrid-acceptance-summary.json`, SHA-256 `6452ec91d750e6cfa043333af748f1544f7d6902fd0789f05b4825139780fc86`. Meaning findings, including the unresolved younger-sister nuance, are in the [AI-assisted passage review](translator-passage-review-2026-09-06.md).

## Physical two-page hybrid merge verification

**Bounded merge PASS:** minified release `a899cd4ea4d3c0fa8070dc4801d981289ddfa242639f041930c98a127d60e928` completed the byte-preserving Japanese page 01 and Korean long-strip page 11 copies in controlled chapter 003. Small PaddleOCR + AI used MEDIUM thinking, global/shared Vertex, automatic translation off and chapter-ahead zero. All four generation captures and seven token-count captures are complete HTTP 200; generations finish with STOP on their first attempts. All four transmitted images exactly match the original crop pixels.

The app's **Full OCR details** inspector supplied the actual persisted JSON for both originals. This is persisted-result evidence from the physical minified app, beyond replaying captured responses through the merger. It does not establish what every reader or font paints.

| Check | Physical persisted result |
| --- | --- |
| Japanese corrected order | Five stable OCR IDs occur as `0:0:2`, `0:0:1`, `0:0:0`, `0:0:4`, `0:0:3`: name, prohibition, enemy, acknowledgement, waiting. Final reading orders are contiguous 0–4; the local left-to-right order is retained separately in raw OCR. |
| Long-strip ordering and additions | Four final regions occur in the authored passage sequence with contiguous orders 0–3. Both AI-added passages survive with unavailable measured detection/recognition scores kept null. |
| Geometry restoration | Every retained region matches one provider rectangle mapped through its original crop within **0.0002 source pixels**. Japanese provider boxes match the prior run exactly; the strip has small provider geometry differences. |
| Raw OCR and confidence | All seven supplied OCR IDs retain original source strings and measured scores. Raw polygons, source text, rotation and scores remain intact in the separate raw OCR record. The two noisy Korean strings remain `.` and `0.`, while `correctedText` stores their recovered Korean passages. |
| Overlap selection | The final promise appears in two request tiles but persists once, using the larger middle-tile provider rectangle. Ten captured region observations become nine persisted regions across the two originals. |

The retained promise is **“I promise.”**; its bottom-overlap alternative is “It's a promise.” This is a new provider sample, different from the earlier twelve-page hybrid run's matching alternatives. The existing largest-area policy selects geometry without resolving speaker context. Its correct execution is a merge pass, while the first-person commitment remains an AI-assisted semantic concern requiring human review.

The private reconciliation is `build/translator/validation/private/hybrid-merge-two/reconciliation.json`, SHA-256 `a960bd73a924e2021107eb2a97cfd75359e7668b388dcffb8b916f9aebeb33bb`; it includes original/crop checks, persisted JSON hashes, field comparisons, complete capture metadata and reproducible steps. Capture ZIP SHA-256 is `e14997637ae948598857fd4ef8f5c95df505cbda5e96cfa4df278f1b120425b9`. Usage reconciles to 7,692 input tokens, including a known 4,350 image-token subset, 945 visible output tokens and 1,844 reasoning tokens across four generations. Authorization is redacted in all eleven captured requests; the bounded credential-pattern scan found no matches.

This verifies merging for **two originals**, not a rerun of all twelve pages, arbitrary panel ordering, style overrides or final overlay glyph fitting. Previously stored jobs are unchanged. Human meaning approval, the provider's sideways-Japanese rotation error, real-page fragment/segmentation findings and wider reader coverage remain pending.

## Selected real-page geometry and panel order

Three privately selected 1362 × 1920 JPEGs were visually assessed alongside their tiled and full-input AI responses. The [passage review](translator-passage-review-2026-09-06.md#three-selected-real-pages-tiled-versus-full-input) records source/capture hashes and bounded meaning findings. Full copyrighted passages and source images remain private. There are no authored ink masks or human region annotations for these pages, so no numerical ink-coverage or IoU result is asserted.

For original `00007`, the 64-pixel overlap between upload crops starting at y=0 and y=1404 cuts through a bottom bubble. The upper crop ends at y=1468; its partial text and the lower crop's fragment produce extra translated regions. Their differing text prevents the current overlap deduplicator from treating them as a single repeated passage. Full-image input removes these fragments, although a small source-transcription typo remains. This comparison isolates an observed crop-boundary failure; it does not validate every full-image request or every chapter.

For original `00008`, the full-input response merges a long reply across separated text areas into normalized box `[58,95,437,557]`, or original bounds approximately `[129.39,111.36,758.63,839.04]`. Visual inspection shows character artwork between those text areas. Filling the stored rectangle can obscure that intervening artwork even if every source character is enclosed. This is a provider segmentation/geometry finding, requiring actual overlay inspection; no coordinate-conversion defect was inferred from it.

For original `00009`, both tiled and full-input responses place the student-ID recognition after the return demand/reaction in the adjacent left panel. The visibly tall right panel establishes the opposite order. Tiled traversal can impose an additional top-to-bottom ordering constraint, but the incorrect complete-image response proves that cropping alone does not explain this example. The hybrid merge correction preserves provider order; it cannot repair a provider order that is already wrong. Panel-aware ordering, fragment handling, translated-text fit and artwork-safe region editing remain acceptance follow-ups rather than inferred passes.

## Reproduced rotated-text clipping

The classic-reader screenshot from the controlled UI exercise visibly cuts through glyphs in the translated Korean warning. The screenshot SHA-256 is `96209749a4b2746a7f6ce6fed870e1e2830c526e47df3057d5fe07afe33f434d`. This is an app text-layout defect, separate from the provider's missing 90° Japanese rotation and from source-ink coverage measurements.

The renderer chose font size using the unrotated outer width and height, then rotated the layout after clipping to the original source polygon. A layout fitting those outer dimensions can extend outside the mask after rotation; a tilted polygon can also cut text that fits its axis-aligned outer bounds.

Two Android pixel regressions reproduced the problem on the unchanged minified benchmark `6ae…` with test APK `66dcf2…`: **158 of 3,417 opaque glyph pixels** were lost at −17°, and **160 of 3,472** were lost when horizontal text was placed inside a tilted source quadrilateral. Both tests failed for the intended assertion in 0.216 seconds. The oracle draws the same real document twice, once on normal Canvas and once on a Canvas subclass bypassing only `clipPath`; it compares foreground glyph retention, not a hardcoded font rasterization or calculated font size. The private log is `rotation-old-app-regression.log`, SHA-256 `c18c97a4364f4ba2dad0c36bb3e9324b73e27bbe0fce532ac4ec1e22cace1e6e`.

The focused correction computes a centered layout frame constrained by the actual polygon edges and the combined region/style rotation, then runs the existing font-size search inside it. The source mask remains fixed. Raw geometry/results and manual font sizing are unchanged. Three independent host tests pass, including 580 polygon/angle combinations checked against `java.awt.geom.Path2D` containment, cardinal dimensions and invalid geometry.

**Final minified regression PASS, 2026-09-06:** both unchanged Android pixel tests passed on benchmark `4e9559a474eb5620c2b743a51e78468db9614c717d817cfb76b1bd7d88e179a7` with test APK `61e295a873b442dcef9363f16864696f87de95ff0420dd130fd084c58791f6fc`. They are included in the 20-method core suite that passed in **3.057 s on the 4096-byte K90** and **7.512 s on the actual 16384-byte official emulator**; these times cover the complete core suite, not only the two rotation tests. The pixel oracle requires zero lost opaque glyph pixels for its axis-aligned and tilted polygons, cardinal/non-cardinal angles and combined style rotation. Exact installed hashes and logs are retained in private `rotation-worker-benchmark-installed.json`, `rotation-worker-core-20-instrumentation.log`, and `emulator16k/final-minified/` beneath `build/translator/validation/`. The earlier red result remains unchanged.

**Assistant visual review, separate from human approval:** the final classic-reader screenshot `build/translator/validation/private/final-classic-30s-after.png`, SHA-256 `947961fa4809d6bb643fcfb8e6f7abb169df807f2be9988891fe9b58b500fd15`, shows the complete rotated English translation of the Korean warning inside its fixed black mask. This viewport was recorded on installed release `2d6246d0bb2c0c3af96c674b90f0e9dff90446a0f14c0951c7e56f4c476ee2be`. The visible clipping from the earlier screenshot is absent in this bounded observation. The adjacent narrow Japanese result remains a separate provider-rotation/readability follow-up. This visual assessment does not provide human passage-level meaning approval or replace the automated pixel regression.

This fix retains the configured minimum font size. Text that cannot fit at that minimum, invalid/consumed geometry, arbitrary imported-font overhang and broader style combinations still require overflow/manual-correction checks. Passing the bounded regression must not be presented as proof that every possible region or font can display every passage without clipping.
