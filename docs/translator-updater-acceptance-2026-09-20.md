# Updater and translator acceptance — 20 September 2026

Status: **Partially tested; final acceptance remains open.** This checkpoint follows the approved updater and remaining-acceptance plan. Independent agent review is permitted and is identified as such; no human approval is claimed or required by this acceptance process.

## Source and release history

The [historical release report](translator-release-validation-2026-09-20.md) preserves the v16–v19 audit. V16 failed APK signer-output parsing before publication; v18 was deferred after the source advanced. Both are consumed, immutable, unpublished reservations. V15, v17 and v19 assets remain unchanged. Historical device evidence is not reassigned to a newer release.

Upstream `424bbc53b` was merged in `25fdc6669`, retaining the fork-only workflow guards and the upstream runner update. Normal fast-forward pushes advanced the fork mirror and translator branch. Initial updater source is `c337c43ce74ce95b9eb92cc1f81c79ccd553e8e5`, reserved as `translator-v20` by [run 35475429071](https://github.com/LegendZ69/mihon/actions/runs/35475429071). V20 was published successfully. [V21](https://github.com/LegendZ69/mihon/releases/tag/translator-v21), from `ba863df0941edb97960aed393719d6cd6512aa06`, was published by [run 35476064857](https://github.com/LegendZ69/mihon/actions/runs/35476064857). Its public APK exactly matches the locally built release. Runtime validation then found a notification lifecycle defect; these releases and their failures remain immutable evidence while the next candidate is prepared.

The old failed hosted run discarded its remote push diagnostic. Its precise server-side cause cannot be recovered from that log. New fixed stage/cause diagnostics distinguish workflow permission, protected ref, non-fast-forward, transport and other failures without publishing remote stderr. A real local Git receive-hook rejection verifies the diagnostics and secret redaction. Future automatic integration of workflow changes may require the dedicated repository-scoped `TRANSLATOR_SYNC_TOKEN` described in [release automation](translator-release-automation.md). No broad workstation OAuth credential was copied to Actions.

## Updater behavior

Numbered production releases discover published fork `translator-vN` releases, including prereleases, by increasing version code. Debug and benchmark packages do not self-update. A successful launch check is throttled for 24 hours; a failed check for one hour; manual checks bypass both. Each version is automatically announced once.

One persistent WorkManager job downloads an explicit user-selected update, retaining progress and retry/cancel state across navigation and process recreation. It verifies manifest identity, byte count, SHA-256, package, version, ABI and signing identity before exposing an APK to Android's installer. Partial bytes and metadata stay outside the updater's FileProvider directory. Download and installation require separate taps; permission denial and installer cancellation retain verified bytes. Relaunch reconciles the installed version and removes obsolete owned files.

## Current evidence

| Gate | Observed result and limit |
|---|---|
| Host Android checks | The next candidate passes 516 local tests across 68 suites, formatting and SQLDelight migrations. V21 release CI passed 497 tests across 64 reports. All 296 host validation-tool tests passed at the prior checkpoint. Native candidate validation remains required. |
| Release control | 38 tests passed, including real Git rejection classification and immutable reservation behavior. |
| K90 prompt lookup diagnosis | V21 repeatedly failed the System field lookup. Video shows the correct editor physically present while the harness scrolls past it; passive accessibility observers make the unchanged autosave/invalid-draft/Back assertions pass. A focused harness change waits for editable semantics before scrolling. It requires validation on the next frozen build; no production semantics change or historical pass is inferred. |
| Runtime identity and native checks | V21 benchmark/test installed hashes matched on K90 (4096-byte pages) and ARM64 emulator (16384). Emulator has passing evidence for all 55 selected methods, retaining an initial gesture timing failure and unchanged isolated pass. K90 passed 54/55; the prompt lookup remains open. Separate 180-second local OCR workloads passed: 770 K90 and 376 emulator iterations. K90 had overlapping personal work, so that workload is not an isolated performance comparison. |
| Independent passage/visual review | Agent reviewed 12 controlled pages and three retained real pages. Controlled semantics: 25 pass, one fail, one uncertain. Real-page meaning/order checks passed; ten historical regions failed text fit. This is historical-artifact review, not final-build renderer acceptance. |
| Controlled corrections | Derived authored corrections preserve the historical source fixtures and bind exact original hashes/revisions before replay. They address two small top-mask caps, an unstated sister possessor/age nuance, an ambiguous promise subject, and the physical Japanese +90° rotation. Actual final app save/export/Undo remains required. |
| WebGPU comparison | Prior exact calibration failures remain failures. An opt-in slop-primed test gesture is ready for hardware verification; no new performance result is claimed from host tests. |
| Spoken TalkBack | Real digital capture transcribes TalkBack system-dialog speech after installing the official Google speech engine. This verifies capture setup only; final app focus/actions/dialogs/errors/updater speech acceptance remains open. Original accessibility services were restored after preflights. |
| Live providers/accounting | No validation-paid requests or billing queries were dispatched. K90 selected Vertex service account / gemini-3.8-flash; other saved-route access is unconfirmed. UI explicitly reports no billing connection. Additional personal requests and uncertain reservations prevent new paid validation until exposure is reconciled. |
| Updater/install | Emulator performed actual v20→v21 in-app installation, with streaming cancellation, duplicate-start rejection, retry, retained download across process recreation, permission denial and installer cancellation. Installed v21 hash matched; library/chapters/theme remained and obsolete updater files were removed. K90 v15→v20 bootstrap preserved 11 stable data tables, 31 favorites, 380 jobs, 5,182 results, dedicated translator settings and one encrypted credential file. A changed common preference file needs separate interpretation. Final candidate upgrade remains required. |
| Notification recovery | V21 real Worker Pause/Resume passed; Pause→Cancel failed before Cancel dispatch because the paused notification disappeared. Android summary cancellation can remove grouped children. The candidate separates the foreground notification from the center-owned group and observes durable cancellation/removal; focused host checks pass, actual next-build lifecycle/Worker checks remain open. |
| Sanitized capture restore | V21 restored saved translations but rejected optional authenticated capture metadata: credential-header omission objects did not fit its typed string map. Two focused regression tests failed before the narrow importer repair and pass afterward, including export/import/re-export with safe header preservation. Actual next-build archive restore remains required. |

The owner made K90 available. Its personal translation queue subsequently stopped naturally: zero active chapters, no queued jobs and no translator foreground service were observed before bootstrap installation. The last dashboard observation showed 232 attempts and USD 222.953472 in outstanding reservations. These are possible exposure, not billed charges, and their overlap with historical validation holds is unconfirmed. No personal queue was paused, resumed or retried by this validation.

## Spending and evidence handling

The existing cumulative ledger remains **S$1.245150 estimated usage + S$83.696202 uncertain holds = S$84.941352 exposure**. The S$270 dispatch stop and S$300 ceiling are unchanged; the ledger arithmetic remainder is S$185.058648, but it is not dispatch permission: additional unreconciled app reservations keep new paid validation on hold. Zero recorded billing means no billing observation, not a zero invoice.

[Official Google pricing](https://cloud.google.com/gemini-enterprise-agent-platform/generative-ai/pricing) was checked on September 19 UTC (September 20 locally). Conservative standard pre-credit rates of US$1.50/US$7.50 per million input/output tokens remain the ledger basis. The [ECB reference XML](https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml) dated September 18 reports USD 1.1460/EUR and SGD 1.4651/EUR; the upward-rounded reference cross-rate is S$1.278446771379/US$. Existing reservations retain their original rates and uncertain holds.

Private logs, complete screenshots, speech and source passages remain under the owned `completion-20260920` evidence directory. Public reports contain only sanitized outcomes and identities. Initial disk relief removed five allowlisted reproducible native-copy intermediate directories after recording all 278 file hashes; actual reclaimed space was **2,498,125,824 bytes**. Historical root build evidence, archived APKs, native patches/licenses, credentials, saved data and the 16 KB emulator were preserved. Final cleanup follows artifact archival and records its own inventory and measured space.

V21 artifact SHA-256 identities (normal release / benchmark / instrumentation):

- `13cf3d9c049c4c156fa8620f864845d2b09a69b4e96285646c01a36671714285`
- `cd43d151cba5a96296b24cb80a383226e9dbb3550b69c47526e8e1323aa1cae3`
- `795c0f0510f2572115b3c71d4791ca8de09a790ab0c62fd75786e220f6fc1fcf`

All three use certificate `e6c08810c0687b9471118d71f8a7ef2614d65acb6b8b94a989fbcfa4dc61d1b9`. These hashes bind v21 evidence only; next-build results require their own identities.
