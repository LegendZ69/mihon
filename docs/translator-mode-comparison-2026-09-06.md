# Controlled live-mode evidence — 2026-09-06

All four modes returned the five controlled chapter pages. Custom recovered from a real 120-second singleton timeout without resending the four already completed pages. Its timed-out request has no returned usage, so the ledger correctly retains the reservation as uncertain. These are automatic observations; **human meaning review and final reader overlay acceptance remain pending**.

## Five-page mode comparison

The original saved handset settings already specified **HIGH** thinking. The copied Vertex, Max, Halving, and Custom configurations retain HIGH. Raw Max/Halving/Custom request bodies confirm HIGH; the original Vertex extracts contain response and metadata only, so its wire setting cannot be independently verified from those extracts. Max fills its otherwise unspecified output limit with 65,536; it does not raise the configured thinking level. The intended MEDIUM setting was used in the separate twelve-page run below.

| Mode | Generation attempts | Captured token-count requests | Exact transcriptions | Minimum source-ink coverage | Role mismatches |
| --- | --- | --- | --- | --- | --- |
| Vertex | 5 | Not included in the retained extracts | 12/12 | 63.4106% | 2 |
| Max | 1 | 4 | 10/12; remaining 2 differ only in whitespace | 100% | 3 |
| Halving | 1 | 1 | 12/12 | 98.8753% | 1 |
| Custom, batch size 2 | 4, including one timeout | 3 | 12/12 from successful responses | 98.8753% | 3 |

Relative reading order matches in all five pages for each mode. Max substitutes spaces for the Japanese vertical passage's line breaks; the character content is unchanged. All expected meaningful passages are included. All five transmitted image payloads in each successful Max/Halving/Custom run match the original fixture pixels. The original Vertex response extracts do not contain payloads for this independent check.

Source-ink coverage measures dark original lettering inside returned rectangles. The original Vertex run's English warning box clips its upper lettering. Halving and Custom slightly clip the first vertical Japanese region. These measurements do not prove how a particular overlay revision looks after text layout, rotation, masks, and styles. The single-run results do not establish that one mode produces better quality.

| Mode | Input tokens | Visible output | Reasoning | Output including reasoning | Usage completeness |
| --- | ---: | ---: | ---: | ---: | --- |
| Vertex | 8,780 | 1,764 | 8,568 | 10,332 | Complete for five captured generations |
| Max | 6,144 | 1,858 | 2,224 | 4,082 | Complete |
| Halving | 6,144 | 1,858 | 3,373 | 5,231 | Complete; 3,995 input tokens identified as cached |
| Custom | 7,462 known | 1,858 known | 4,338 known | 6,196 known | One timeout's usage unavailable |

Cached tokens are part of input, not additional input. Token-count requests are separate from generation attempts. Successful-run usage agrees with the selected settled ledger reservations and unique `USAGE` log events. Custom's known response usage agrees with its three usage events, but this is not complete accounting: its S$65.384594 reservation remains uncertain. Estimates, reservations, and confirmed billing remain separate; the review does not settle or release funds.

Halving succeeded with its initial whole-chapter batch. This live run therefore does **not** exercise recursive failure splitting; that behavior needs separate deterministic failure coverage.

## Custom recovery evidence

Two initial requests translated pages 3–4 and pages 1–2 in 9.137 and 12.928 seconds respectively. The first request for page 5 lasted 120.005 seconds and produced no completed response capture. Retry 2 began 1.429 seconds after that failure and returned page 5 in 10.947 seconds. The captured request identities show only page 5 was retried; the first four pages were not sent again. All final source-page crop coverage is present. This establishes bounded provider-request recovery, not process-death or background persistence acceptance.

Traditional Chinese sister wording varies across the modes: the original Vertex and Custom responses choose “your sister,” Max chooses “my little sister,” and Halving uses “little sister” without a possessor. The source explicitly denotes a younger sister but does not state whose sister. The omitted younger relationship and selected possessor require contextual human review; disagreement with the authored reference possessive alone is not a proven mistranslation. Narration/sign role differences also remain visible in the detailed reports.

## Twelve-page AI run with MEDIUM thinking

All 15 generation requests explicitly send **MEDIUM** thinking and identify `gemini-3.8-flash`; 15 token-count requests are recorded separately. The 12 original pages become 15 upload tiles, including the long strip and spread. All 15 transmitted payloads match their original source crops, and the returned crop unions cover all 12 images.

- All 29 annotated regions are found: 27 exact transcriptions and two Japanese whitespace-only differences. There are no unmatched additional regions. The blank fixture returns zero regions.
- The advertisement and watermark are classified separately, have `included=false` with ignored reasons, and the neighboring story narration remains included. Their raw translations are retained in the response, which does not imply they should be overlaid.
- The 12-, 18-, and 24-pixel source passages are transcribed correctly on these clean controlled fixtures. The signs and Korean impact sound effect remain included. This does not establish performance on tiny lettering over real artwork.
- Minimum measured rectangle ink coverage is 99.4921%, on a long-strip passage; all other observed rectangles cover their reference dark ink completely. Seven role classifications differ from the fixture annotations.
- The Korean passage rendered 18° counterclockwise is reported as −18°, consistent with Android Canvas rotation. The Japanese passage rendered 90° clockwise is reported as 0° instead of the corresponding +90°. Its rectangle encloses the text, but orientation/layout needs reader inspection before acceptance.
- The long strip's final promise passage appears in two overlapping tile responses, translated differently as “I promise” and “It's a promise.” Both are automatically associated with the same source passage. The app's overlap-deduplication and retained translation must be checked in the final persisted result; raw capture coverage alone does not establish the merged result's wording or order.

Usage is **26,441 input + 3,635 visible output + 4,676 reasoning = 34,752 total tokens**, matching all 15 usage events and the selected settled reservation. Relative order is checked within each tile; cross-tile/spread order and passage-level meaning are not independently certified by this report.

Private reproducible evidence is recorded in `*-acceptance-summary.json` and `four-mode-comparison.json` under `build/translator/validation/private/`. The reusable [capture reviewer](translator-capture-review.md) verifies hashes, separates token accounting, and emits sanitized summaries without account identifiers, credentials, headers, or source passages. Raw captures remain private; the original synthetic source/reference passages remain in the reviewable fixture specification. Original fixture provenance and human-review limitations remain unchanged.
