# Structured imports and prompt controls — 19 September 2026

This continuation starts from the verified v14 build documented in [the controls validation report](translator-controls-validation-2026-09-12.md). It adds local structured-file imports and stage-specific prompt replacement. Prior Japanese physical-orientation, unmatched WebGPU measurements, spoken TalkBack and human passage approval remain open. No result in this report resolves those earlier gaps.

## Implemented boundaries

Structured files is available globally and per series. Portable JSON and Gemini, OpenAI Responses and Chat Completions envelopes are decoded locally; Mihon ZIP archives use the existing archive restoration path. The import UI separates files, explicit matching, validation/conflicts and committing selected valid pages. Hash/dimension matching is automatic; IDs without hashes require an explicit original-page selection. Provider geometry uses normalized coordinates and portable JSON uses original pixels. Recorded tile transforms are required. Text-only imports update explicitly matched existing region IDs while preserving their geometry and OCR.

The queue, manager, batching and provider boundaries reject file-mode translation dispatch. Imports do not call OCR, translate, review or chapter-ahead processing. Partial imports remain **Awaiting imported pages**. Explicit provider review is a separate action and snapshots current validated provider/review settings. Imported usage is historical provenance, not a new usage record or billed request.

Prompt settings expose Built-in or Custom replacement independently for system and user roles in translation, quality review and geometry correction. Template variables are literal and nonrecursive; unknown/malformed variables prevent autosave while retaining the editable draft. Valid edits use the existing 300 ms autosaver. Runtime source data and code-enforced output/identity/modality constraints remain separate from task wording. New jobs and review checkpoints pin settings; ordinary retries retain their translation prompt settings. Explicit geometry recovery retries adopt only current geometry prompt settings.

Request diagnostics record effective prompt text (sanitized and bounded), stage and a full-prompt SHA-256. Long diagnostic text is explicitly marked incomplete. Image bodies, thought signatures and credentials remain excluded. Structured archives preserve prompt settings/import provenance while removing credential references, headers and executable work.

No SQL schema migration is added; model changes are backward-compatible JSON fields.

## Documentation checked

The existing serialization remains aligned with [Google's inference request reference](https://docs.cloud.google.com/gemini-enterprise-agent-platform/reference/models/inference) and [OpenAI's text-generation guidance](https://developers.openai.com/api/docs/guides/text?api-mode=responses), retrieved 19 September 2026. Vertex uses systemInstruction, Responses uses instructions/input, and Chat Completions uses system/user messages. Groq/Paddle remains text-only. This work does not change model names, provider defaults or pricing.

## Validation record

Evidence directory: `build/translator/validation/private/structured-prompts-20260919/` (private, excluded from publication).

Initial integration builds exposed missing imports, a cross-module Kotlin smart cast and a positional argument affected by the restore overload. These were compilation failures, not behavior-level test-first failures. All outputs are retained; no unexecuted RED is claimed.

Host, persistence and final minified device results will be recorded below after execution. The K90 was read as 4096-byte runtime and the official test emulator as 16384 bytes; alignment checks alone are not runtime acceptance.

No paid provider validation has been dispatched for this addition. Existing uncertain reservations and the S$270 dispatch stop/S$300 ceiling remain in force. No cloud credential import or change is needed for local structured imports.

## Host and build checks

The final host run passes **319 translator app tests and 138 domain tests**, with zero failures, errors or skips. Formatting and SQLDelight migration verification pass with the repository's normal rules. Evidence: `host-final-5.log`; JUnit XML receipts are retained privately. Coverage includes provider envelopes, malformed UTF-8/deep JSON, mapping, geometry, raw OCR preservation, conflicts, cancellation around commit, import-only queue guards, immutable prompts, literal variables, request serialization, sanitized exports and archive round trips.

Independent review found an existing job with an unknown page count would retain zero after original acquisition during import. Its verified original count now determines completion; a real SQLite regression is included in the device matrix. The first minified candidate (`structured-prompts-v15`) was deliberately interrupted with exit 130 before delivery to include this correction. It was never installed.

Downloadable contract: [example JSON](translator-structured-files-v1/example.json), [JSON schema](translator-structured-files-v1/schema.json), and [output instructions](translator-structured-files-v1/README.md). These same documents are available from the native import screen.

## Retained provisional device failures

The normal minified `structured-prompts-v15b` candidate ran 16 methods on each runtime. Twelve SQLite methods and the imported Canvas/GPU overlay method passed; three methods failed. A real Android ICU incompatibility rejected the prompt template regex because its closing braces were not escaped. Desktop JVM tests accepted the old pattern. Both closing braces are now escaped; the native review-checkpoint and prompt-editor methods exercise the regression.

The other two failures occurred before feature interaction. K90 HyperOS denied the test package’s activity launch (`MIUILOG Permission Denied`, result 102). The emulator resumed TranslationActivity behind a **System UI isn’t responding** dialog. No production crash was recorded for these failures. The strict focused-window guard was retained. After foregrounding the benchmark through its normal launcher, the K90 structured-file native flow passed on the same provisional APK; the final matrix will use that setup. The emulator obstruction was captured and dismissed using its observed **Wait** control. These outcomes remain in the private provisional and isolation records; they are not counted as final acceptance.

## Source identity preservation

A further independently identified defect could orphan a saved page when reacquisition returned changed originals and only an unchanged page was selected for import. Job reuse now requires the complete ordered hash/dimension identity set, or a genuinely empty unacquired job. Changed content, ordering or chapter length creates a separate deterministic import record. Transactional restoration rechecks the full identity set before writing image metadata. Missing private files with unchanged identities can refresh their paths while retaining saved image IDs, results and corrections.

Seven additional host tests pass for this boundary. Two SQLite methods exercise rejected partial imports with changed unselected originals and allowed same-identity path refresh, including reopen checks. They join the final native matrix.

## Reproduction

Host checks (JDK 21, repository SDK):

```sh
./gradlew spotlessApply spotlessCheck :app:testDebugUnitTest --tests 'mihon.feature.translation.*' :domain:testDebugUnitTest :data:verifySqlDelightMigration --console=plain --max-workers=4
```

Normal minified build (no reduced optimization rules):

```sh
./gradlew spotlessCheck :data:verifySqlDelightMigration :app:assembleRelease :app:assembleBenchmark :app:assembleBenchmarkAndroidTest -Ptranslation.testBuildType=benchmark -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false --no-build-cache --console=plain --max-workers=4
```

Native matrix after verifying installed APK hashes, waking/dismissing the nonsecure keyguard, ensuring no system dialog, and foregrounding the benchmark launcher:

```sh
adb -s SERIAL shell am start -W -n app.mihon.benchmark/eu.kanade.tachiyomi.ui.main.MainActivity
adb -s SERIAL shell am instrument -w -r -e translation.acceptance true -e class mihon.feature.translation.transfer.TranslationArchivePersistenceTest,mihon.feature.translation.ui.TranslationStructuredImportNativeTest,mihon.feature.translation.overlay.TranslationOverlayRenderingTest#importedGeometryAndSfxPolicyRenderWithoutChangingImportedContent app.mihon.benchmark.test/androidx.test.runner.AndroidJUnitRunner
```

The tests use disposable SQLite databases, one owned sample file and a nonexistent series preference scope, with explicit cleanup. They do not invoke live providers. The native UI tests cover import parsing/matching guards/cancellation and prompt editing/autosave/validation/navigation flush; SQLite tests cover commit/conflict/duplicate/restart behavior, and Canvas/GPU tests cover imported geometry plus SFX exclusion. These distinct checks are not presented as a human reading assessment or a full reader performance measurement.

A final malformed-input regression reproduced `isTile:"true"` being accepted without its required transform (expected `INVALID`, actual `READY`). Present tile markers now require a JSON boolean; malformed strings, numbers, null, objects and arrays fail at the `isTile` field even when a transform is provided. The input instructions also state the existing 3–32 distinct-corner limit after outline-preserving normalization. `structured-prompts-v15c` was interrupted with exit 130 before installation to include this correction; its partial build evidence is retained.
