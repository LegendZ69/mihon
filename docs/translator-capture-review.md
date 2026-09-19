# Offline capture evidence review

`scripts/translator_capture_review.py` summarizes controlled-fixture translation captures without contacting providers, devices, or billing services. It supports native capture directories containing `metadata.json`, `request.json`, and `response.json`, plus combined `{ "metadata": ..., "request": ..., "response": ... }` files. Request bodies are optional. JSON bodies can be parsed objects or serialized strings; event exports can be JSON arrays or JSONL.

The JSON output contains allowlisted settings, counts, geometry, and cryptographic identities. It excludes source/translated passages, prompts, headers, URLs, filenames, account identifiers, raw errors, and arbitrary model names. Unrecognized models are represented by a digest. Output files are atomically written with mode `0600`; keep both inputs and outputs under ignored private build storage. Do not pass credential files as settings snapshots.

## Run a review

First create an explicit mapping from local chapter image IDs to the generated fixture manifest. `five` uses the mode-comparison chapter; `all` uses all twelve pages. Verify the zero-based IDs against the chapter being tested, or edit the mapping's `image_id` values to match the queue. Mapping hashes must match the original corpus files.

```sh
python3 -B scripts/translator_capture_review.py \
  --corpus build/translator/validation/corpus/manifest.json \
  --write-image-map build/translator/validation/private/review-image-map.json \
  --chapter five

python3 -B scripts/translator_capture_review.py \
  --corpus build/translator/validation/corpus/manifest.json \
  --image-map build/translator/validation/private/review-image-map.json \
  --captures build/translator/validation/private/max-captures \
  --settings build/translator/validation/private/controlled-validation-max.json \
  --events build/translator/validation/private/max-events.jsonl \
  --output build/translator/validation/private/max-acceptance-summary.json
```

`--captures` also accepts multiple individual combined files. An export directory is searched for capture subdirectories with `metadata.json`; unrelated files are ignored. Identical duplicate envelopes with the same capture identity are counted once. Conflicting envelopes with the same identity are flagged and conservatively counted separately. Known token-count operations are reported separately from generation attempts, so Max's preflight requests do not become malformed translations or paid generation counts. Input files are limited to 64 MiB each; oversized inputs stop with a fixed error instead of silently truncating evidence.

## What is verified

- **Original identity:** the image map, encoded image SHA256, dimensions, RGB mode, and decoded pixel hash must agree with the corpus. Returned IDs are matched exactly; recognized `base~x_y_width_height` tile IDs are validated against original-image bounds. Unknown IDs remain unresolved.
- **Transmitted identity:** when actual inline/data-URL request bytes are present, their decoded dimensions and RGB pixels are compared with the corresponding source crop. Re-encoded PNG bytes may have a different encoded hash while retaining identical pixels. Without request bytes, transmitted identity remains explicitly unverified.
- **Completeness:** duplicate IDs, malformed regions, unsuccessful finish reasons, non-success HTTP responses, and truncated response captures cannot complete a page. Successful child crops from Halving/retries retain their coverage; an incomplete crop union does not establish full-page completion. The newest successful observation of each exact image/tile identity is used for page diagnostics, while every unique attempt still contributes usage. This does not independently prove the application's queue or merge state.
- **Text and order:** exact source matches are reported separately from whitespace-only matches. Whitespace normalization collapses runs to one space, preserving word boundaries. Repeated reference passages are left for manual association rather than matching one detection to multiple regions. Relative reading order is checked within each returned tile; cross-tile order is not inferred. Raw translation text is not exported or automatically certified.
- **Geometry:** the normalized `[y_min, x_min, y_max, x_max]` rectangle is mapped through the tile origin to original pixels. The reviewer measures coverage of known dark source lettering pixels and padded-box intersection-over-union. An observation whose crop itself cuts the reference passage is labeled. These rectangle metrics do not validate rotated overlay masks, text shaping, or reader appearance. Coverage below 99% is a follow-up heuristic, not a product acceptance threshold.
- **Settings:** configured values and observed wire values remain separate. Only comparable observed values are checked for differences. Missing request bodies leave thinking, output limits, and resolution unknown; the tool does not invent provider defaults. Capacity header values and Priority-header presence are recorded without retaining headers.

## Usage and spend reconciliation

Vertex `candidatesTokenCount` is visible output; `thoughtsTokenCount` is reasoning. Their sum is output including reasoning. OpenAI Responses `output_tokens` and Chat Completions `completion_tokens` already include reasoning, so visible output is derived by subtraction when reasoning details are available. Cached input is a subset of input. Missing values remain unknown, with a separate count of missing attempts. Provider totals are checked for internal consistency, including unsuccessful generations when usage is available.

Optional `--events` compares unique `USAGE` event totals against capture totals without also counting `TRANSLATED` events. This is aggregate consistency, not proof of one-to-one event association. The application's provider-output field is interpreted according to the capture dialect.

Optional `--spend` reads the existing ledger's `status` JSON export. Whole-session estimated usage, outstanding reservations, billed amounts, and ceiling status remain distinct. To reconcile a particular run, pass `--spend-selection` pointing to a private JSON array of the reservation IDs that cover **exactly** those captures. Without this explicit binding, token reconciliation remains `NOT_BOUND_TO_RESERVATIONS`. A match requires settled selected reservations and matching input, visible-output, reasoning, and attempt totals. The reviewer does not settle reservations or authorize spending.

Compare generated reports with `--compare report-one.json report-two.json --output comparison.json`. This checks whether the reference image identities agree and lists transcription counts, whitespace distinctions, geometry coverage, role/order observations, token totals, and configured versus observed thinking. It does not expose raw captures or make a causal claim that one mode improves quality from a single run.

```sh
python3 -B -m unittest discover -s scripts/tests -p 'test_translator_capture_review.py' -v
```

Tests cover duplicate/conflicting captures, batch failure followed by successful halves, missing crops, stale-file ordering, malformed geometry, unsuccessful/truncated responses, original hash tampering, transmitted pixel identity, blank-page hallucinations, whitespace distinctions, secret-bearing inputs, native exports, JSONL events, Vertex/OpenAI token accounting, reservation binding, and private output permissions. Human meaning review and reader validation remain pending regardless of automated results.
