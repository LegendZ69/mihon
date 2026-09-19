# Translator controls instrumentation

These checks use the normal minified benchmark and its matching test APK. Record both APK hashes and source manifest with each run. Only the parent device-validation run performs ADB actions; no cloud inference or billing synchronization is part of these methods.

## Dashboard and billing persistence

Class: `mihon.feature.translation.accounting.TranslationDashboardPersistenceTest`.

The first two methods create an isolated SQLite database and remove only its owned directory. They cover 1,008 persisted usage records with replay deduplication, queries that cross the 256-row page boundary, model pagination, exact token/decimal totals, unknown categories, retained reservations, distinct saved pages, filtered usage/event drilldowns, immutable provider attribution, and exact scoped billing storage/deletion. This is separate from actual cloud-account access.

`compactReviewSummariesKeepLatestPerPageWithStableTiesWithoutLoadingTheBaseline` seeds review history with large raw-OCR baselines in that isolated database. It requires one compact status per job/page, job scoping, observed updates and stable ties (latest timestamp, then the lexicographically lowest review ID, matching the existing inspector helper). The query must not return the saved pre-repair payload. Record its runtime result separately from the accounting methods; a fixture or build failure is not an implementation regression result.

`compactChapterCoveragePreservesDeclaredTotalsAndCountsSavedBlankAndIgnoredPages` checks actual SQLite projections for Partial 2/5 when only two originals are cached, Complete 2/2 for saved blank and intentionally ignored SFX pages, and an unknown total when saved results lack an original-page enumeration. The stored results carry large raw-OCR documents, which must remain absent from the compact identity projection. This exercises existing coverage behavior without translating a page.

## Autosave

Class: `mihon.feature.translation.settings.TranslationAutosavePersistenceTest`.

The two ordinary methods exercise the public autosaver and actual Android `TranslationPreferences` using a `ContextWrapper` that redirects only `translator_settings` to a unique disposable name. The production global and series stores, credential vault, provider adapter, queue and WorkManager are never used. Coverage includes immediate valid selections, a coalesced text burst, invalid drafts preserving the last saved value, navigation flush, independent series overrides, reset cancelling pending edits, and recreation from committed preferences. The exact 300 ms scheduling boundary is covered by the separate virtual-clock host tests.

The optional `committedAutosaveCheckpointSurvivesASeparateTargetProcess` method supports two instrumentation invocations:

- Set `translation.settingsPhase=write` and `translation.settingsRunId=<fresh UUID>`. This commits global and series preferences to the owned store, records the writing PID, and retains the store.
- End the target process using the explicitly recorded validation condition. Distinguish instrumentation relaunch, debug kill, crash and force-stop; these are not interchangeable evidence.
- Invoke the same method with `translation.settingsPhase=collect` and the same UUID. It requires a different PID, validates both persisted values, and removes only the matching owned store.

Output lines begin `TRANSLATION_SETTINGS_CHECKPOINT` and include phase, UUID and PIDs. No setting values from the real app or credentials are printed. Without the opt-in arguments, this third method is skipped. A passing separate-process check does not establish the cause of process termination; preserve the external device evidence.

## Published notification actions

Use the existing `MinifiedTranslationWorkerAcceptanceTest.localForegroundWorkerPreservesCommittedPages` with its usual opt-in arguments and a fresh run UUID. It requires an empty benchmark translation queue, finished existing translator WorkManager work, connectivity and the host-local deterministic endpoint. It never clears user queue data to make the preflight pass.

New values for `translation.workerMode`:

- `notification-actions`: save one of two pages, stall the other local response, dispatch **Pause** from the actually posted chapter card, wait for its paused card, then dispatch the posted **Resume** action. The final page count and local request count prove that the first committed result was preserved and never resubmitted.
- `notification-cancel`: pause through the posted chapter card, dispatch **Cancel** from its paused card, and verify that its single saved page remains identical with no additional request.

Both use the existing `worker-notification` fixture scenario, synthetic credentials and no cloud forwarding. The notification permission/channel must already be enabled and the configured card limit must be positive; the harness does not change those policies. Record the original benchmark policy before enabling it, then restore it after evidence collection. Calling a real published PendingIntent verifies receiver/Manager/Worker behavior; it is not evidence of a physical finger tap or the notification permission dialog flow.

Existing human-tapped notification, network, screen-off and active-Resume modes remain available. Their old records and APKs are retained. The summary action assertion now matches **Pause chapters**; per-chapter controls remain **Pause**, **Resume** and **Cancel**.

Reports retain the original scope statement: actual foreground Worker under active instrumentation, not natural idle, force-stop, process death or OEM eviction. Read the report before removing only its verified owned fixture files and remaining disposable telemetry.

The v4 observations committed source image `1` first and resumed image `0`. This proves preservation of the first committed result; it does not prove source-order dispatch. Preserve each run's actual image ID, source index and content hash when reporting it.

## Worker evidence collection and cleanup

Opt-in method: `mihon.feature.translation.acceptance.MinifiedTranslationWorkerEvidenceTest#collectOrCleanupWorkerFixture`. It runs only in the normal minified benchmark. It does not call the provider or start/resume the queue.

Use `translation.workerEvidence=true`, the existing `translation.workerRunId=<UUID>`, and `translation.workerEvidencePhase=collect`. Save complete `am instrument -w -r` output. The collector accepts only the exact owned `translation-worker-acceptance/<UUID>` directory and its allowlisted `report.json`, `page-0.png` and `page-1.png`; symlinks, unexpected files, changed image identities and nonterminal reports are rejected. No fixture files are removed during collection.

The bounded ZIP includes those files, `telemetry.json` and a versioned checksum manifest. Operation, event and usage reads use exact job ID `worker-fixture-<UUID>`, pages of 100 and a maximum of 2,000 rows per category. The operation reader does not perform global process-session reconciliation. JSON is limited to 4 MiB, each file to 8 MiB, and all entries together to 16 MiB. The temporary ZIP is streamed in indexed 24 KiB chunks through instrumentation status bundles, then removed. The manifest names the actual first committed page independently of source order.

Verify the captured stream on the host, without extracting it or touching a device:

```sh
python3 scripts/translator_worker_evidence.py collected-instrumentation.log verified-worker.zip
```

The verifier requires matching collection/completion records, unique contiguous chunks, the complete ZIP hash, an exact entry allowlist and every entry's size/hash. It independently checks original-image identities, the first committed result and its preservation, and the exact owned telemetry job IDs. It writes `verified-worker.zip.receipt.json` only after successful validation and refuses to overwrite existing evidence. Its three host tests exercise valid first-committed source index 1, duplicate/missing transport chunks, and an invalid entry hash inside an otherwise correctly hashed ZIP. A receipt proves archival integrity; it does not replace the external request ledger, APK hashes or performance evidence.

Cleanup is a separate invocation with the same opt-in/run arguments, `translation.workerEvidencePhase=cleanup`, and `translation.workerEvidenceVerifiedSha256=<sha256 from a cleanup-qualified receipt>`. The app rebuilds the same archive from current files and telemetry and compares that digest before deleting anything. It additionally requires a passing report with credential/job removal and unchanged-settings flags, verifies that the exact job and credential are still absent, and checks capture remains disabled. Only that UUID's events, operations, usage and allowlisted fixture files are deleted. Shared `queue` background events, unrelated jobs/accounting, global credentials and settings are preserved. A mismatch refuses cleanup; an interrupted cleanup requires collecting and verifying the remaining fixture again, while keeping the earlier archive.

For a negative acceptance check, invoke cleanup once with a different valid-looking SHA-256 and verify the refusal before collecting again. Never use prefix, age or title matching to widen cleanup after a refusal. New collection/cleanup instrumentation requires its own recorded APK/hash/runtime result; source availability or the independent host verifier passing is not a device pass.
