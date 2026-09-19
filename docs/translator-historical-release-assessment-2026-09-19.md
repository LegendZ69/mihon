# Historical APK publication assessment

Assessment date: 2026-09-19. This report records read-only inspection of retained archives and local Git evidence. No APK was rebuilt, modified, installed, tagged or published. This publication assessment was promoted from the private staging audit without adding private captures or credentials.

## Decision

**Hold all historical milestones for now: none has demonstrated complete build-time source provenance.** Seven distinct main APKs have exact matching retained hashes. Six remain potential historical candidates once the provenance gap is resolved; v7 is independently excluded because of its recorded reader crashes. Do not create replacement historical binaries or claim that present-day source reconstruction proves the originals.

## Main APK inventory

All archives below are under `build/translator/validation/r8-instrumentation-abi/`. The `release.apk` bytes matched their archived artifact metadata during the initial read-only inventory. Source ZIP entries also matched every entry of their respective `source-at-compile.json` manifests, with no missing, extra or mismatched entries. These checks establish consistency of the recorded subset, not completeness of the source inputs.

| Label and archive directory | Main APK SHA-256 | Frozen source files | Disposition |
| --- | --- | ---: | --- |
| v6 — `controls-final-candidate-v6` | `361635da64838015e721a793b25e7cafa61c8d6326e93946681a8cf22bec5f0b` | 1,122 | Hold; candidate build, missing full source attribution |
| v7 — `controls-final-v7` | `bcde60ad27c4c59fdc596585936111dcc4511136b3189e7c263ace1e74414763` | 1,123 | Exclude from publishable milestones; known reader crashes and incomplete source attribution |
| v10 — `controls-final-v10` | `55588d60b8fa33d6aa3d02643fd96cb7c0e4ca66138fdf95a7a8ababc1965826` | 1,160 | Hold; missing full source attribution |
| v11 — `native-polygon-final-v11` | `a33cb2d39eda6e6c61fb855fba4e66d3571a0303874ba266cb290238ab5b37a3` | 1,262 | Hold; missing full source attribution |
| v12 — `native-polygon-notification-final-v12` | `e55fcae7e41c6fb32fe8652d5f044c8dcbd2b82bb7a52c9f93409b5c4ac9d31d` | 1,264 | Hold; missing full source attribution |
| v13 — `native-polygon-notification-final-v13` | `feb6f8a187876487ff210fec52acb46dd592ff1adabe43437b09950449870ea2` | 1,265 | Hold; missing full source attribution |
| v14 — `native-polygon-accessibility-final-v14` | `48d5521039ceb552aad9856f992deb14574ab917e971a03cdbc8800f7ebcabe7` | 1,268 | Hold; missing full source attribution |

The v14b `native-polygon-accessibility-contract-v14b` archive has identical main and benchmark APKs to v14; only its instrumentation APK changed. It is not an additional main-app milestone.

All seven main APK manifests embed Android **versionName `0.20.4`, versionCode `29`**. The v6/v7/v10–v14 labels identify validation archives, not distinct embedded Android versions. Any eventual historical release naming must disclose this and retain the original bytes and hashes.

## Recovered Git base

The initial assessment found no base commit field in the archived source/build metadata. Follow-up inspection recovers strong evidence for the Git HEAD used by the builds:

- Every main APK's `classes2.dex` string table contains the exact application-version string `0.20.4 (3acd7eaca, 29, 2026-09-04T15:18:23Z)`.
- The local object database resolves `3acd7eaca` uniquely to **`3acd7eaca935a5091d1ca6c66113c5fa6b01447b`**, upstream commit `Update coil to v3.6.2 (#3889)`.
- The local reflog records the clone and creation of `codex/manga-translator` from that HEAD on 2026-09-05; no subsequent historical HEAD movement is recorded before this assessment.
- `app/build.gradle.kts` obtains `COMMIT_SHA` from `getLatestCommitSha()`. The implementation in `gradle/build-logic/src/main/kotlin/mihon/gradle/Commands.kt` executes `git rev-parse --short HEAD`.

This identifies the Git base. It **does not identify uncommitted build inputs**: the embedded SHA reports HEAD regardless of working-tree edits.

## Remaining source provenance gaps

The source ZIPs are scoped snapshots, not complete repository snapshots. Even v11–v14 contain only `app`, `core`, `data`, `domain`, `gradle` and a few root build files. Included build modules omitted from their manifests include:

| Omitted module or scope | Files tracked at recovered base |
| --- | ---: |
| `baseline-profile` | 6 |
| `core-metadata` | 4 |
| `i18n` | 138 |
| `icons` | 121 |
| `presentation-core` | 72 |
| `presentation-widget` | 28 |
| `source-api` | 22 |
| `source-local` | 9 |
| `telemetry` | 6 |
| **Total** | **406** |

Read-only `git diff` against the recovered base currently reports no tracked changes in those scopes; `git ls-files --others --exclude-standard` reports no untracked names there. That supports a reconstruction hypothesis, but cannot prove that those same files were unchanged during each historical build. The archived freeze receipts hash only listed files. `UP-TO-DATE` lines in build logs are not attestations of omitted source bytes.

Other omissions include root Gradle launchers and LICENSE. The v6/v7/v10 snapshots additionally omit inputs such as `app/proguard-rules.pro` and Gradle-wrapper artifacts. Later snapshots include the wrapper and app rules but still omit the modules above. Existing final native-library hashes and build logs are useful binary evidence; they do not fill the missing working-tree source record.

To qualify a milestone, locate contemporaneous evidence that binds **every omitted relevant input** to the recovered Git base or another immutable artifact. Examples would be a retained complete source snapshot, a full build-time working-tree diff plus tracked/untracked inventory, or a matching source manifest covering those modules. If that evidence cannot be found, keep the historical source provenance limitation explicit and do not promote the archive to a fully source-bound release.

## Intermediate and failed builds

- `controls-transfer-notifications-v1`, `controls-management-red-v3` and `controls-integrated-v5` retain failed-build records and source snapshots, with no APKs.
- `controls-archive-stream-red-v9` has no archived APK; its v9b successor has benchmark and instrumentation APKs only.
- v2, v4, v5b, v8 and v9b contain benchmark/instrumentation pairs, not main APKs. These remain validation evidence rather than ordinary app releases.
- A separate `controls-contrast-green-archive-red-v2` is another diagnostic benchmark/instrumentation archive, not a second main v2 release.
- Preserve the failed v7 reader evidence, along with other documented scope limitations; later passing builds do not retroactively clear them.

## Evidence and reproducible inspection

Relevant existing sources are each archive's `artifacts.json`, `release-manifest.txt`, `source-at-compile.json`, `sources.zip`, freeze receipts and build logs, plus `docs/translator-controls-validation-2026-09-12.md` for historical acceptance/failure scope.

Read-only checks used:

```sh
git rev-parse --verify '3acd7eaca^{commit}'
git reflog --all --date=iso --format='%H %gd %gs'
git diff --name-only 3acd7eaca935a5091d1ca6c66113c5fa6b01447b -- \
  baseline-profile core-metadata i18n icons presentation-core presentation-widget source-api source-local telemetry
git ls-files --others --exclude-standard -- \
  baseline-profile core-metadata i18n icons presentation-core presentation-widget source-api source-local telemetry
```

APK/ZIP verification used streaming SHA-256 plus ZIP entry hashing. The embedded version check parsed the DEX string table directly without executing the app or extracting user data. No provider credentials or private account files were inspected.
