# Updater and translator acceptance — 20 September 2026

Status: **Partially tested; final acceptance remains open.** This checkpoint follows the approved updater and remaining-acceptance plan. Independent agent review is permitted and is identified as such; no human approval is claimed or required by this acceptance process.

## Source and release history

The [historical release report](translator-release-validation-2026-09-20.md) preserves the v16–v19 audit. V16 failed APK signer-output parsing before publication; v18 was deferred after the source advanced. Both are consumed, immutable, unpublished reservations. V15, v17 and v19 assets remain unchanged. Historical device evidence is not reassigned to a newer release.

Upstream `424bbc53b` was merged in `25fdc6669`, retaining the fork-only workflow guards and the upstream runner update. Normal fast-forward pushes advanced the fork mirror and translator branch. Initial updater source is `c337c43ce74ce95b9eb92cc1f81c79ccd553e8e5`, reserved as `translator-v20` by [run 35475429071](https://github.com/LegendZ69/mihon/actions/runs/35475429071). That run's synchronization and reservation passed; build/publication and later final-build results are recorded below when verified.

The old failed hosted run discarded its remote push diagnostic. Its precise server-side cause cannot be recovered from that log. New fixed stage/cause diagnostics distinguish workflow permission, protected ref, non-fast-forward, transport and other failures without publishing remote stderr. A real local Git receive-hook rejection verifies the diagnostics and secret redaction. Future automatic integration of workflow changes may require the dedicated repository-scoped `TRANSLATOR_SYNC_TOKEN` described in [release automation](translator-release-automation.md). No broad workstation OAuth credential was copied to Actions.

## Updater behavior

Numbered production releases discover published fork `translator-vN` releases, including prereleases, by increasing version code. Debug and benchmark packages do not self-update. A successful launch check is throttled for 24 hours; a failed check for one hour; manual checks bypass both. Each version is automatically announced once.

One persistent WorkManager job downloads an explicit user-selected update, retaining progress and retry/cancel state across navigation and process recreation. It verifies manifest identity, byte count, SHA-256, package, version, ABI and signing identity before exposing an APK to Android's installer. Partial bytes and metadata stay outside the updater's FileProvider directory. Download and installation require separate taps; permission denial and installer cancellation retain verified bytes. Relaunch reconciles the installed version and removes obsolete owned files.

## Current evidence

| Gate | Observed result and limit |
|---|---|
| Host Android checks | Final candidate: 512 tests across 68 app/domain/data/core suites passed with zero failures or skips; global formatting, SQLDelight migration verification, and benchmark AndroidTest Kotlin compilation passed. |
| Release control | 38 tests passed, including real Git rejection classification and immutable reservation behavior. |
| K90 prompt lookup diagnosis | Unchanged installed v15 passed the focused prompt editor test after explicitly foregrounding the benchmark. Earlier lookup failure is retained; it did not justify a production semantics change. Final-build repeat remains required. |
| Runtime identity | K90 reports 4096-byte pages. The preserved ARM64 emulator reports 16384-byte pages. Runtime identity alone is not a matrix pass. |
| Independent passage/visual review | Agent reviewed 12 controlled pages and three retained real pages. Controlled semantics: 25 pass, one fail, one uncertain. Real-page meaning/order checks passed; ten historical regions failed text fit. This is historical-artifact review, not final-build renderer acceptance. |
| Controlled corrections | Derived authored corrections preserve the historical source fixtures and bind exact original hashes/revisions before replay. They address two small top-mask caps, an unstated sister possessor/age nuance, an ambiguous promise subject, and the physical Japanese +90° rotation. Actual final app save/export/Undo remains required. |
| WebGPU comparison | Prior exact calibration failures remain failures. An opt-in slop-primed test gesture is ready for hardware verification; no new performance result is claimed from host tests. |
| Spoken TalkBack | Digital capture and local transcription tools are installed and host-smoke-tested. Host TTS and semantic trees do not pass actual TalkBack speech acceptance. |
| Live providers/accounting | No new paid provider requests or billing queries have been dispatched. Existing K90 route/account access must be inspected and all three required routes validated; missing access blocks that route. |
| Final native/build/install gates | Pending final frozen-source APKs, runtime matrix and actual bootstrap-to-final in-app upgrade. |

The owner subsequently made K90 available. Its current UI shows five active translation chapters and additional queued work. App replacement and isolated performance runs wait for the owner's queue-handling choice. These independently initiated requests are outside this validation dispatch ledger; the ledger amount is not a current account-wide spending measurement.

## Spending and evidence handling

The existing cumulative ledger remains **S$1.245150 estimated usage + S$83.696202 uncertain holds = S$84.941352 exposure**. The S$270 dispatch stop and S$300 ceiling are unchanged; S$185.058648 remains reservable. Zero recorded billing means no billing observation, not a zero invoice.

[Official Google pricing](https://cloud.google.com/gemini-enterprise-agent-platform/generative-ai/pricing) was checked on September 19 UTC (September 20 locally). Conservative standard pre-credit rates of US$1.50/US$7.50 per million input/output tokens remain the ledger basis. The [ECB reference XML](https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml) dated September 18 reports USD 1.1460/EUR and SGD 1.4651/EUR; the upward-rounded reference cross-rate is S$1.278446771379/US$. Existing reservations retain their original rates and uncertain holds.

Private logs, complete screenshots, speech and source passages remain under the owned `completion-20260920` evidence directory. Public reports contain only sanitized outcomes and identities. Initial disk relief removed five allowlisted reproducible native-copy intermediate directories after recording all 278 file hashes; actual reclaimed space was **2,498,125,824 bytes**. Historical root build evidence, archived APKs, native patches/licenses, credentials, saved data and the 16 KB emulator were preserved. Final cleanup follows artifact archival and records its own inventory and measured space.
