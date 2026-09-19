# Translator v15: upstream synchronization and release validation

Validation checkpoint: 20 September 2026. **Published as Partially tested; one K90 native UI failure remains unresolved.** This report preserves the decoder and populated-queue regressions, their fixes, and later test-only harness timing failures. Both reader smoke checks passed on `943464a5b`; source `b2fe5ccbf` has a completed minified build, an emulator 39/39 pass and a retained K90 38/39 result. Earlier failures remain evidence. Japanese AI orientation, final WebGPU performance measurements, spoken TalkBack and human passage approval remain open. No paid provider calls were made in this phase; existing uncertain reservations and the S$270 dispatch stop/S$300 cumulative ceiling remain unchanged.

## Source and release identity

| Checkpoint | Commit / outcome |
| --- | --- |
| Integrated translator, structured imports and prompt controls committed | `1b65dd456275c8ca469fed5275500a8282f75e1e` |
| First upstream integration | Merge `bf9b1cfe97c944537bd8d53a2c4d42fe3a153df1`, incorporating upstream `5848ffb4c484506d8123ef6fa9ecbbda166a330a` |
| Release automation | `2209c40240ccf35774a7712855f2767c25bfa813` |
| Decoder-coordinate fix and explicit device-receipt scope | `d49ac4e705e014a2c088f1439830eabaae5ad15d` |
| German locale integration; completed device checkpoint | Merge `89d7490f0364a23e37b3bd5b25a3f76c6f08837d`, incorporating upstream `385d076d4369535c2839193cd29e2b32741989b1` |
| Latest upstream integration checkpoint | Merge `04b5b8ac7424d542ac8531d6716d716ad7c64868`, incorporating AGP 9.4.1 update `504ec2afaea49cf8bb8dab03164f2feca8ffb3b6`; candidate build cancelled before delivery |
| Populated-queue/settings gesture preference fix | `e4f8dd8e30e69dd4b8eadd7d2157d3e06f9bbd0f`; manual normal-app reader-to-queue navigation passed |
| Native dialog-harness refinement | `943464a5bfa91b6d15ce8789897495448014b915`; normal reader checks completed, emulator transition-timing failure retained |
| Final native window-transition harness refinement | `b2fe5ccbf755bda06905412e1105763b14caf63b`; test-only centralized focused-root waiting, reconciled build completed |
| `main` mirror at this checkpoint | Exactly upstream `504ec2afaea49cf8bb8dab03164f2feca8ffb3b6` |
| Reserved and pushed annotated tag | `translator-v15`, bound to source `b2fe5ccbf755bda06905412e1105763b14caf63b` and upstream `504ec2afaea49cf8bb8dab03164f2feca8ffb3b6`; v15 is published without changing this source |
| Reconciled normal APK SHA-256 on `b2fe5ccbf` | `7c595c9eaf4967cbb2d873bcfbbc78139022d26cf05ad24fd535e73acb07fe4b`; installed hash verified |
| Reconciled benchmark APK SHA-256 on `b2fe5ccbf` | `ec06c329e07ad7fce5f83c059c667076cae718bfce9cc760287904658fd3bb49` |
| Reconciled instrumentation APK SHA-256 on `b2fe5ccbf` | `3e9808e4c541b262f4cee04103949cb4385f180d119f87bb3d2a8b63b8c2a457` |
| Packaged full-source archive SHA-256 | `75b320ac061f2afd74d01d2f0583bb9a36bc559dc1b6a8c2d338e4d254617fbf`; 1,881 tracked files |
| Packaged manifest SHA-256 | `71c302a5e2886eb8bc9c9e89100cd6201b0579f6281bb0fc1c258a57c561ff28` |
| Packaged sanitized validation SHA-256 | `048504ec8acd2613a63c5987d30dabccc975f5d07aa4c739341e9164298237c2` |
| Published `SHA256SUMS` SHA-256 | `732eeb7cdd75870bdfdaa2dc022d9e058856a72b26b88ba6124a86816d8431bf` |
| Published release | [Translator v15](https://github.com/LegendZ69/mihon/releases/tag/translator-v15) — **Partially tested**, with the retained failed device aggregate |
| Independent public-download asset verification | **PASS — all five assets** exactly match their original bundle hashes and GitHub digests; receipt `v15-public-download-verification.json` |

The published normal artifact is ARM64 `app.mihon`, versionCode **100015**, versionName **`0.20.4-translator.15`**. The preserved signing certificate SHA-256 is `e6c08810c0687b9471118d71f8a7ef2614d65acb6b8b94a989fbcfa4dc61d1b9`. The reconciled normal APK installed hash and preserved signing identity are verified. All five independently downloaded public assets match the original bundle and GitHub digests. The verified public manifest records passed 16 KB ZIP alignment. ZIP alignment alone does not establish 16 KB runtime acceptance.

## Preserved baseline and completed candidates

The original v14 main APK is retained unchanged: `48d5521039ceb552aad9856f992deb14574ab917e971a03cdbc8800f7ebcabe7`. The first normal candidate built from the release-automation checkpoint in **8m 37s** and produced APK `302b1bc9cf207f12421be5a8fa734ee40418e6b12e74292d6b25baa2dc314010`. Its normal package, ARM64 ABI, versionCode 100015 and preserved certificate were verified. It upgraded the main installation without clearing data; the controlled chapter still displayed **12/12 saved translations**. This is UI-observed preservation, not a byte-for-byte database audit.

On controlled chapter 002, page 7, the recorded initial viewport and every original/translated comparison restoration matched across v14 and this candidate: SHA-256 `148749fd79541cfbbc9662ae68f0ef018562bb25376a490bbc8a368afd1d702a`. Each build passed three comparison cycles and the pinch/pan action smoke test. The harness restored comparison parity, chapter/page and requested rotation. Gesture-return viewport pixels differed from the initial viewport; no complete pixel restoration after gestures, frame-pacing improvement or memory/thermal guarantee is claimed.

**Reader-backend scope correction:** a later direct inspection of Main Settings → Advanced
found the high-quality renderer **off**. The baseline smoke recordings did not independently
identify their active backend. Earlier normal-app comparison passes in this phase must not
be credited as WebGPU coverage. Separate explicit classic and WebGPU smoke runs subsequently
passed on source `943464a5b`; their scope is recorded below. The high-quality renderer is now
restored **on** and the current backend is WebGPU, with page 7 restored. This correction does
not replace older reports that recorded their own backend settings and artifact scope.

A subsequent build after the decoder fix was deliberately cancelled with exit 130 to incorporate the newer upstream locale commit. It was not installed. Its interrupted build is not a final artifact or a successful build result.

The later normal minified build at `89d7490f0` completed successfully and is retained as
`translator-v15-latest`. Its main APK SHA-256 is
`63e2e72d10689edc497f3056824932836e20d99a3a0328e1b2f7d2c42b8bab02`.
The normal main-app smoke passed three comparison cycles with the same recorded baseline
viewport; **12/12 saved translations** remained visible. Both companion native matrices
passed all 38 methods without skips, including the two added EXIF regressions.

Before publication, upstream advanced again to AGP 9.4.1. That update was merged and pushed
as `04b5b8ac7`, and the `main` mirror advanced to `504ec2af`. Its build archive,
`translator-v15-agp941`, records cancellation with exit 130 after another main-app failure
was reproduced, so the focused fix can be included before publication. It was not delivered.
The completed `89d7490f0` results retain their own bounded APK scope.

The production correction at `e4f8dd8e3` then built in **7m 11s**. Its normal APK is
`502b6eb8f72f96e583725eb17ea1c7c63799458d8246a9d5ffdad8c1f1a4140b`.
Manual main-app navigation from the reader into the populated Translator queue passed;
**12/12 saved translations**, **one repaired review** and **one Needs review result** remained
visible. These are observed UI preservation checks.

The emulator's 39-method run on `e4f8dd8e3` passed 38 methods and retained one test-only
dialog interaction failure: Android returned false for `ACTION_CLICK`. That same method
passed in isolation on the unchanged APKs in **8.484 s**. The harness now waits for the focused
dialog, reacquires fresh nodes and bounds retries of rejected clicks. This refinement is
source `943464a5b`, with no additional production behavior change. Its minified delivery build
completed. The normal installed APK SHA-256 is
`972f31f739f318396a25d8131115de04af35f336954496a910e6657cb9854b02`.
The K90 companion matrix passed **39/39** methods. The emulator passed **38/39** and retained
a second test-only timing failure: the accessibility root was temporarily null while dismissing
a dialog and returning to Queue. Those results remain in `publication-native/`.

Source `b2fe5ccbf` centralizes bounded waiting for the focused accessibility root across window
transitions. This is a test-only refinement; no additional production behavior change is
claimed. All **478 host tests**, formatting and SQLDelight migrations passed again on this
source. Archive `translator-v15-reconciled` completed with the normal, benchmark and test
hashes recorded above. The normal installed hash was verified. The actual 16384-byte emulator
passed **39/39** native methods with no skips. K90, running with a 4096-byte page size, passed
**38/39** with no skips; its prompt-editor method could not locate the visible control
“System task wording” after bounded accessibility scrolling. An isolated run of that same
method on the exact unchanged APKs failed again. A third diagnostic run retained the same
failure; `prompt-diagnostic/03.png` shows both “System Custom replacement” and the “System
task wording” editor visibly present while the harness lookup misses the control. The reason
for that accessibility/lookup discrepancy has not been proved, and no production root cause
or successful K90 rerun is claimed. No application crash was observed in these bounded runs.
Raw terminal summaries and status codes are retained under `reconciled-native-*` and the
isolated/diagnostic receipts. Final K90 prompt-autosave verification remains open; the emulator
method passed within its complete 39/39 matrix. No further source or build changes are planned
for this prerelease; the retained device aggregate is **failed**, with a **Partially tested**
release label.

The reconciled normal APK also passed three WebGPU original/translated comparison cycles
in `main-reconciled-webgpu-smoke.txt`, repeating the WebGPU viewport hashes below. Classic
smoke remains evidence from normal source `943464a5b`; `b2fe5ccbf` changes only the test
harness, but the older APK's classic run is not relabeled as a direct run on the reconciled APK.

### Explicit classic and WebGPU smoke on `943464a5b`

Each backend completed three original/translated comparison cycles plus physical pinch/pan
smoke actions on the normal APK above. The initial and comparison-restored viewport hashes
repeated across all three cycles:

| Recorded backend | Initial / comparison-restored viewport SHA-256 | Original/opposite viewport SHA-256 |
| --- | --- | --- |
| Classic | `148749fd79541cfbbc9662ae68f0ef018562bb25376a490bbc8a368afd1d702a` | `ddb0b04e845ad87ca85c4a90216322170eab6a5f0a6e8d802909b319b06a0cbf` |
| WebGPU | `dbc49be35371bf987dd9305c051426b68fac38b79fba1da202bd6b083644f23d` | `ddb0b04e845ad87ca85c4a90216322170eab6a5f0a6e8d802909b319b06a0cbf` |

Both runs retained gesture-return pixel drift; restoring comparison parity and chapter/page
is not complete viewport-pixel restoration after gestures. These are bounded functional
checks, with **no new reader pacing or thermal measurements**. Receipts are
`main-publication-classic-smoke` and `main-publication-webgpu-smoke`. Renderer restoration is
visually recorded in `main-renderer-restored-on.png`; the earlier off state remains in
`main-restored-renderer.png`.

## Validation outcomes and retained failures

| Check | Result / exact scope |
| --- | --- |
| Completed host run on `89d7490f0` | **PASS** in 1m 14s: 332 app, 138 domain, 3 core/common and 5 OCR tests; formatting and SQLDelight migration checks passed |
| Release-control Python suite at frozen v15 source | **PASS — 19 methods**, using temporary Git repositories and synthetic artifacts; no remote writes or provider requests |
| Subsequent publisher-fix regression suite | **PASS — 25 methods**, including six additional regressions; tooling commit `d1fa97a73` is separate from frozen v15 source |
| First candidate native matrix, K90 | **34/36 passed; 2 failed**, actual 4096-byte runtime |
| First candidate native matrix, official emulator | **34/36 passed; 2 failed**, actual 16384-byte runtime |
| Correctly prepared SFX fixture rerun | **PASS on both runtimes**, using the unchanged first-candidate APKs |
| Decoder-fixed normal build on `89d7490f0` | **PASS** — archive `translator-v15-latest`, normal APK `63e2e72d…` as identified above |
| Decoder-fixed K90 native matrix on `89d7490f0` | **PASS — 38/38, zero skips**, actual 4096-byte runtime; all methods target the companion |
| Decoder-fixed emulator native matrix on `89d7490f0` | **PASS — 38/38, zero skips**, actual 16384-byte runtime; all methods target the companion |
| Normal main-app smoke on `89d7490f0` | **PASS** — three comparison cycles, matching baseline viewport and 12/12 saved translations retained; bounded UI scope |
| Populated Translator queue/settings navigation on source `89d7490f0` main APK | **FAIL — reproduced application crash**, outside the preceding 38-method matrix and reader comparison smoke |
| AGP 9.4.1 candidate build on `04b5b8ac7` | **CANCELLED — exit 130**, archive `translator-v15-agp941`; stopped to include the dependency fix, not delivered |
| Production gesture-preference correction on `e4f8dd8e3` | **PASS** — normal build 7m 11s; manual reader-to-populated-queue navigation and 12/12 saved/review-state UI preservation |
| Emulator native matrix on `e4f8dd8e3` | **38/39 passed; one retained test-only dialog `ACTION_CLICK` rejection** |
| Isolated rerun of that dialog method | **PASS — 8.484 s**, unchanged target/instrumentation APKs |
| Host checks on harness source `943464a5b` | **PASS — 478 total:** 332 app, 138 domain, 3 core/common and 5 OCR; formatting and SQLDelight migrations passed |
| Normal minified build on `943464a5b` | **PASS** — archive `translator-v15-delivery`, installed normal APK `972f31f7…` identified above |
| K90 companion matrix on `943464a5b` | **PASS — 39/39**; retained with that exact target/instrumentation pair |
| Emulator companion matrix on `943464a5b` | **38/39 passed; retained test-only null accessibility-root transition failure** |
| Host checks on final harness source `b2fe5ccbf` | **PASS — all 478 tests**, formatting and SQLDelight migrations |
| Reconciled normal minified build on `b2fe5ccbf` | **PASS** — archive `translator-v15-reconciled`; exact APK identities above |
| Reconciled normal installed hash | **PASS** — matches `7c595c9e…`; independent public downloads also match the original bundle |
| Reconciled K90 companion matrix on `b2fe5ccbf` | **38/39 passed; one retained prompt-editor control lookup failure**, zero skips; actual 4096-byte runtime |
| Exact-APK isolated and diagnostic K90 prompt reruns | **FAIL / FAIL** — same lookup assertion; diagnostic editor visibly present, no application crash observed, root cause unproved |
| Reconciled official emulator companion matrix on `b2fe5ccbf` | **PASS — 39/39, zero skips**, actual 16384-byte runtime |
| Recorded classic and WebGPU normal-app smoke on `943464a5b` | **PASS — three comparison cycles per backend plus pinch/pan**, precise pixel-restoration limits above; high-quality renderer restored on |
| Reconciled `b2fe5ccbf` normal-app WebGPU smoke | **PASS — three comparison cycles**, same WebGPU viewport hashes; classic comparison retains its `943464a5b` artifact scope |

The initial 36-method matrices passed all 15 SQLite methods and both native UI methods, along with the retained HDR, spread, captured-geometry and WebGPU ownership checks. No new application crash was observed during those specific initial native runs. Their two failures have different causes:

1. **Actual decoder regression:** decoder 14 applied EXIF orientation where the overlay contract required original source coordinates; one assertion observed width 96 instead of 128. The fix adapts decoded data back to raw source coordinates for all eight EXIF orientations, retains HDR/gain-map handling and closes owned decoder resources. Three host tests pass. Both added native methods subsequently passed on K90 and the actual 16 KB runtime in the `89d7490f0` matrices and again in the final `b2fe5ccbf` matrices; the final K90 failure is the separate prompt-editor method.
2. **Fixture preparation error:** the initial harness omitted `08-signs-and-sfx.png`. The corrected setup passed the SFX method on both unchanged APKs. This resolves that setup failure without treating it as a production code fix or deleting the original failed run.

A **separate later main-app crash** was reproduced on source `89d7490f0`, normal APK
`63e2e72d10689edc497f3056824932836e20d99a3a0328e1b2f7d2c42b8bab02`, while navigating
from the reader into the populated Translator queue/settings. `PreferenceStore` was still
requested through `MetroInjektRegistrar`, which did not expose that binding. The preceding
38 companion methods did not exercise this populated-queue path; their passes remain valid
only for their recorded methods, and the reader comparison smoke does not clear this crash.

The audit of fork-changed Kotlin found two affected Injekt calls. The correction in
`e4f8dd8e3` moves those lookups to `context.appGraph` and adds a native navigation regression.
Manual navigation on its normal main APK passed as recorded above. Retained failure evidence
includes `restore-series-queue.xml` and `queue-injekt-crash.txt`; the later dialog-click
rejection and isolated passing rerun are retained separately. Neither test setup problem
is substituted for this production crash or for the earlier EXIF defect. Final full-matrix
confirmation now uses the central window-transition waits on `b2fe5ccbf`. Its emulator
39/39 pass and K90 38/39 result are recorded separately from the `943464a5b` K90 39/39 pass
and emulator 38/39 result. The earlier null-root timing failure is not a recurrence of the
`PreferenceStore` application crash. The new K90 failure is in
`TranslationStructuredImportNativeTest.nativePromptEditorsSaveValidDraftAndRetainInvalidDraftWithoutProviderWork`:
“Visible native control after bounded accessibility scrolling: System task wording.” The
exact-APK isolated and diagnostic reruns both retained that failure. Visible editor evidence
does not establish the lookup's root cause or prove autosave behavior passed. No application
crash or production root cause was established. The failed method remains counted, and final
K90 prompt-autosave acceptance remains **open**; emulator success does not replace it.

Native methods execute against the **minified `app.mihon.benchmark` companion**, with a separately hashed instrumentation APK. The normal `app.mihon` smoke tests use an external UI harness. Final receipts must identify the actual tested package and both APK hashes per run; benchmark results must not be presented as direct instrumentation of the normal main APK. The published normal APK hash remains a separate top-level identity.

Captured authored geometry and native ownership regressions demonstrate their stated fixture boundaries. They do not repair the saved live Japanese AI result, supply human passage approval or replace the outstanding matched WebGPU performance measurements.

## Automation and publication controls

The protected `translator-release` environment is restricted to `codex/manga-translator` and configured with the preserved signing identity. V15 is published and all five public assets have been independently verified. `TRANSLATOR_RELEASE_AUTOMATION_ENABLED` is now **true**. Two actual manual workflow runs demonstrated serialized, unchanged-source no-ops as recorded below.

The workflow requests a five-minute poll and also handles translator-branch pushes/manual dispatches. It serializes synchronization and release work; GitHub scheduling is best effort. `main` advances only by fast-forward. Translator changes are merged normally; conflicts preserve history and produce deduplicated issue #1 records. No AI merge or provider dispatch occurs in CI. The inherited website-update workflow also now has a repository guard, added in `e4f8dd8e3`, that prevents this fork from dispatching updates to the upstream website.

Annotated version reservations bind the exact source and upstream before building. Unchanged inputs and documentation-only follow-up commits reuse the latest reservation; a source rollback receives a new version. Explicit retry restores the original preserved Actions bundle when available and resumes publication without rebuilding or replacing assets. Missing original bytes with existing draft assets stop recovery safely. Source/upstream advancement is checked again before publishing.

The annotated `translator-v15` reservation was pushed and its exact five-asset bundle packaged. The initial publication attempt created an empty draft, then failed when the release-by-tag endpoint returned HTTP 404. A focused publisher fix resumed that same empty draft and uploaded all five original bundled byte streams. [V15 is now published](https://github.com/LegendZ69/mihon/releases/tag/translator-v15), with no APK rebuild, source change, retagging or asset substitution. Its source remains `b2fe5ccbf755bda06905412e1105763b14caf63b`; all five independently downloaded public assets exactly match the preserved bundle and GitHub digests. The initial failure remains recorded.

The publisher correction is committed and pushed separately as `d1fa97a73ae92521873572f5b5550c92df166cd7`. Six focused regression methods bring the release-control suite from **19 at frozen v15 source** to **25 passing methods** on that correction. It changes release tooling, not application behavior, and is absent from the published v15 source archive. Its push is expected to allocate a separate v16 CI build under the existing source-change rules; that subsequent build/publication remains pending at this checkpoint. V15 is not rebuilt, retagged or replaced.

Before that tooling push, concurrent manual runs [35459679141](https://github.com/LegendZ69/mihon/actions/runs/35459679141) and [35459680627](https://github.com/LegendZ69/mihon/actions/runs/35459680627) both completed successfully with their build jobs **skipped**. The second requested `retry_reserved=true` and was observed pending while the first ran. Their synchronization jobs ran sequentially at **17:57:53–17:58:03 UTC** and **17:58:07–17:58:19 UTC**, respectively. Neither run allocated a v16 tag or overwrote release assets. These are observed production workflow no-ops and concurrency behavior, separate from unit-test coverage.

Public assets comprise the normal ARM64 APK, full tracked-source `git archive`, SHA-256 checksums, manifest and sanitized aggregate validation. Credentials and private raw logs/API captures are excluded. Release titles distinguish **Untested** from **Partially tested**; neither label implies overall acceptance. This v15 prerelease is **Partially tested**, with the final device aggregate explicitly **failed** because the K90 prompt method remains unresolved. Its publication does not mark the build validated or close that acceptance gap.

| Delivery check | Outcome |
| --- | --- |
| v15 annotated reservation and local bundle | **PASS** — tag pushed; source/upstream bound; five assets packaged, including the 1,881-file source archive |
| Remote asset reconciliation | **PASS — all five downloaded assets** match original bytes and GitHub digests |
| Final K90 prompt-autosave verification | **OPEN / FAILED CHECK** — retain 38/39 matrix and both failed exact-APK reruns; no production root cause established |
| GitHub release publication | **PUBLISHED** — [v15](https://github.com/LegendZ69/mihon/releases/tag/translator-v15); the same empty draft was resumed with the original bundle after the retained HTTP 404 failure |
| Automation enabled after publication | **PASS — true**, after published v15 asset verification |
| Observed unchanged-source no-op and serialization | **PASS** — actual runs `35459679141` and `35459680627`, both successful with build skipped; sequential timings above, no new tag or asset overwrite |
| Later tooling release | **PENDING** — pushed `d1fa97a73` is expected to trigger a separate v16 CI build; v15 retains its frozen source and artifacts |
| Current settings checks | **UI-verified:** controlled-series chapter-ahead 5, automatic translation off, Ignore SFX on; benchmark Battery saver (recommended), autostart off and notifications off |
| Main high-quality renderer restoration | **PASS — restored on**, explicit WebGPU smoke completed; current backend WebGPU, reader page 7 restored |
| Final post-validation settings check and owned fixture cleanup | **PASS within recorded scope** — settings/signing/hash restoration verified; 13 owned run archives verified before exact UUID directories were removed; both owned external fixture roots absent; reverse mappings empty; owned emulator stopped; main app foreground restored |
| Issue #1 update | **PENDING** |

## Reproduction and evidence scope

Host and release-control checks:

```sh
./gradlew spotlessCheck testDebugUnitTest verifySqlDelightMigration -PtranslatorReleaseNumber=15
python3 -m unittest discover -s scripts/tests -p test_translator_release.py -v
```

Normal minified artifacts, including the companion used for native acceptance:

```sh
./gradlew :app:assembleRelease :app:assembleBenchmark :app:assembleBenchmarkAndroidTest \
  -PtranslatorReleaseNumber=15 -Ptranslation.testBuildType=benchmark \
  -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false
```

The native matrix comprises `TranslationArchivePersistenceTest`, `TranslationStructuredImportNativeTest`, `TranslationOverlayRenderingTest`, `TranslationSoundEffectOverlayTest`, `TranslationCapturedGeometryTest` and `MinifiedWebGpuOwnershipAcceptanceTest`. Run only after verifying installed hashes, observed runtime page size, complete geometry/SFX fixtures, focused benchmark foreground and recorded device conditions. Archive results before cleaning owned fixtures.

Cleanup verified all 13 framework-owned run tar archives before removing their exact disposable UUID directories. Both owned external fixture roots were verified absent, reverse mappings were empty, and the owned `emulator-5556` process (PID 74352) was stopped and its exit verified. The main app was restored to the foreground, with current signing/hash and temporary-setting restoration checks verified. No additional paid provider calls were made. This cleanup does not delete unrelated source chapters, saved translations, credentials or preserved validation archives.

Disk-space housekeeping replaced identical private APK copies with APFS clones of their archived bytes. All bytes and modification times were preserved, and no evidence was deleted; the receipt is `identical-apk-clones.json`.

Current settings receipts include `main-delivery-ahead.xml`, `main-delivery-automatic.xml`, `main-delivery-sfx.xml` and `benchmark-policy/power-ui.xml`. Private raw receipts remain under `build/translator/validation/private/release-sync-20260919/`; only sanitized aggregates and artifact identities belong in public releases. The [release automation guide](translator-release-automation.md) records exact packaging and recovery commands. Earlier [structured/prompt validation](translator-structured-prompts-validation-2026-09-19.md) and [controls validation](translator-controls-validation-2026-09-12.md) retain their own artifact scope.

The [historical publication assessment](translator-historical-release-assessment-2026-09-19.md) remains unchanged: all historical milestones are held for incomplete full-source provenance, v7 is additionally excluded for recorded reader crashes, and v14b duplicates v14's main/benchmark bytes. No historical APK was rebuilt, retagged or promoted by this phase.
