# Offline captured quality-review replay

`CapturedQualityReviewReplay.kt` is a host JVM harness in the provider package. It calls the actual
`TranslationWireFormat`, `PreparedTranslationRequest`, `QualityReviewWireFormat`, and shared domain
validation. It does not construct an HTTP client, provider gateway, credentials, or Android context.

Compile it alongside those production sources and the translation domain model sources, using the
repository's Kotlin compiler, serialization compiler plugin, and existing app dependency classpath.
This avoids replacing production parsing or coordinate conversion with a second implementation.
The provider helpers are internal, so a separate compilation also needs the app classes as a Kotlin
friend path. No APK or Gradle configuration change is required.

The resulting entry point accepts two phases and an explicit private directory:

```sh
java -cp "$TRANSLATOR_REPLAY_CLASSPATH" \
  mihon.feature.translation.provider.CapturedQualityReviewReplay baseline "$TRANSLATOR_REPLAY_DIRECTORY"
java -cp "$TRANSLATOR_REPLAY_CLASSPATH" \
  mihon.feature.translation.provider.CapturedQualityReviewReplay review "$TRANSLATOR_REPLAY_DIRECTORY"
```

Each `<case>.input.json` contains an `id`, a serialized `TranslationImage` referring to an existing
original file, and `pages` from recorded structured provider output. Page IDs must identify retained
crop rectangles in the application's `originalId~left_top_width_height` form. Keep original and capture
hashes with that descriptor. The baseline phase runs the actual decoder and tile merge and writes a
reconstructed baseline; it does not claim to export a historical database revision.

Create `<case>.authored.json` separately with a review `pages`, `findings`, and `visualComplete` envelope.
Clearly label the candidate and its rationale as an authored protocol expectation. Keep copyrighted
images, captured passages, requests, and candidates in app-private/host-private evidence, outside Git.
Do not put credentials into replay inputs. Cases are bounded to eight per run, 16 MiB per JSON input,
7 MiB per original file, and 64 MiB per serialized review request; these are harness bounds, not a
replacement for gateway/provider preflight.

The review phase verifies unchanged full-image bytes in the actual request, candidate identities,
convex original-coordinate geometry, raw source and measured-score/style preservation, and rejection
of omitted IDs or raw-source mutation. It also serializes a text-only request and verifies that it has
no image parts. Geometry/identity repair candidates must be rejected in text-only mode; the named
`panel-order` case permits an order-only candidate. Outputs include the parsed candidate and a receipt
with content hashes. The harness overwrites only derived files for these explicitly supplied cases.

On 2026-09-06, five recorded problem cases passed this local replay: clipped synthetic warning,
sideways Japanese, a rectangle crossing artwork, crop fragments, and panel order. All original IDs
were retained, including excluded split/fragment parents. Coordinate round trips differed from the
authored points by at most 0.0001 pixel. An independent source-ink calculation improved the **authored**
warning mask from 3,421/5,395 to 5,395/5,395 known dark pixels. These results verify protocol behavior;
they do not establish live model repair, complete artwork preservation, reader pixels, or human
passage-level meaning approval.

The private run directory is
`build/translator/validation/private/quality-review-authored-replay/`. It retains input/capture/source
hashes, authored rationales, per-case receipts, compile/run logs, the exact local preparation commands,
and an independent reconciliation. No additional provider requests were made.
