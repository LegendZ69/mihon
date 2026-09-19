# Translator fork synchronization and releases

This workflow is restricted to `LegendZ69/mihon`, with `codex/manga-translator` as the
release/default branch and `main` as a fast-forward-only mirror of `mihonapp/mihon:main`.
It performs no translation-provider or device calls. Artifacts are normal minified ARM64
`app.mihon` releases; automated prereleases do not claim device, visual or human passage acceptance.

Current acceptance uses agent visual and passage review; a separate human signoff is not
required. Reports must identify the reviewer and observed scope. Automated host builds do
not perform that review and must never label agent-reviewed output as human-approved.

## Enablement and preserved signing identity

Keep repository variable `TRANSLATOR_RELEASE_AUTOMATION_ENABLED=false` while preparing
and publishing the initial v15 locally. Configure the `translator-release` GitHub environment
before enabling it. Restrict deployment branches to `codex/manga-translator`; use the
repository's desired reviewer protection. Required environment secrets are:

| Secret | Existing Gradle environment input |
|---|---|
| `SIGNING_KEY` | `storeFileBase64` (the existing keystore, base64 encoded) |
| `KEY_STORE_PASSWORD` | `storePassword` |
| `ALIAS` | `keyAlias` |
| `KEY_PASSWORD` | `keyPassword` |

Set GitHub environment variable `SIGNING_CERT_SHA256` to
`e6c08810c0687b9471118d71f8a7ef2614d65acb6b8b94a989fbcfa4dc61d1b9`.
This is the preserved installation certificate, not a newly generated CI key. A missing
secret or mismatched certificate stops before Gradle; no replacement debug certificate is used.
Do not put signing material in source, release files, CLI arguments or workflow logs.

The token needs contents-write for branch/tag/release operations and issues-write in the
sync job for a deduplicated issue #1 failure record. Signing secrets are available only in
the protected build job. Existing branch/tag rules must allow these intended bot operations;
the workflow does not bypass or change repository protections. Enable GitHub immutable
releases if server-enforced asset/tag immutability is desired; the script itself never replaces
published assets or moves reservation tags.

The synchronization checkout accepts an optional repository secret `TRANSLATOR_SYNC_TOKEN`
for Git pushes that include `.github/workflows/` changes. Use a dedicated credential scoped
to this repository with **Contents: write** and **Workflows: write**; a classic PAT for this
public fork instead needs `public_repo` and `workflow` scopes (`repo` for private repositories).
Do not copy a personal workstation OAuth session into
Actions. The default `GITHUB_TOKEN` remains the fallback for ordinary source changes and
continues to handle issue/release APIs. The dedicated token is used only by the synchronization
checkout, never by the signing/build job. A missing, expired or insufficient credential stops
the relevant push and reports a fixed diagnostic; it does not alter branch protection.

A dedicated token can trigger another push workflow. The existing concurrency group serializes
it; after the current run reserves/publishes the source, the later unchanged-source run is a
no-op. The synchronization/build dependency remains explicit and never relies on that event.

After v15 is published and verified, set `TRANSLATOR_RELEASE_AUTOMATION_ENABLED=true`.
All schedule, branch-push and manual-dispatch runs honor this gate. The workflow must be
present on the default branch. In a fork, GitHub Actions and scheduled runs must also be enabled.

## Synchronization, allocation and no-op behavior

The schedule requests a poll every five minutes. GitHub may delay or drop scheduled runs
under load, and may disable scheduled workflows after repository inactivity; it is not an
exact five-minute service guarantee. Pushes to the translator branch and manual dispatches
also run the same workflow. The complete sync/build/publish run is serialized without
cancelling an active build. A newer pending run may replace an older pending run; each run
fetches current refs, so it does not rely on processing every push separately.

`main` advances only when it is an ancestor of current upstream. Divergence fails without
rewriting history. Upstream changes merge normally into the translator branch. Conflicts
abort the merge and preserve existing changes. Push rejections, mirror divergence and confirmed
merge conflicts have separate sanitized diagnostics. Logs and issue #1 comments identify the
failed stage and a fixed cause code without exposing remote stderr, credentials or URLs. One
comment is retained per source/upstream/stage/cause combination; repeated polling deduplicates
it. A local agent resolves content conflicts; CI does not use AI to resolve source code.

Check the reported cause before acting: `workflow_permission` requires an appropriately scoped
sync credential, `protected_ref` requires review of the existing repository rule,
`non_fast_forward` requires fetching/reconciling current refs, and `transport` or `unknown`
requires checking remote state before retrying. Earlier successful steps can already have
advanced a branch. An uncertain reservation push must be reconciled against the remote tag;
never reuse its number for different source inputs. Failure to post the issue comment does
not hide the original synchronization error.

The historical scheduled run [35469317788](https://github.com/LegendZ69/mihon/actions/runs/35469317788)
stopped at `git push failed (exit 1)` while upstream introduced workflow updates. Its old
script discarded the server diagnostic; its generic issue comment is not proof of a content
conflict or a permission rejection. The later read-only inspection found no repository
rulesets and unprotected main/translator branches. The new local regression reproduces an
explicit workflow-permission rejection through a real Git receive hook and verifies the
stage/cause, credential redaction and unchanged remote ref. This establishes diagnostic
behavior, not the unrecoverable cause of the historical hosted run.

Versions begin at 15. The next reservation is one greater than every existing translator
reservation, including failed releases. `translator-vN` is an annotated tag whose JSON
annotation records `schema`, `number`, `source_sha` and `upstream_sha`. The tag points to the
exact compile/source-archive commit. `-PtranslatorReleaseNumber=N` supplies versionCode
`100000 + N` and versionName `<upstream version>-translator.N`.
Numbered production `release` builds also enable the fork's in-app updater automatically.
The existing `-PtranslatorReleaseNumber=N` argument is sufficient; no extra CI flag is
needed. Benchmark, debug, nightly and FOSS variants retain their separate updater policy.

Reserved numbers and tags are never reused for different inputs. An unchanged published
source is a no-op. Fork-only changes limited to `docs/**` or Markdown files also reuse the
latest reservation when upstream is unchanged; this lets a final validation report commit
follow publication without allocating another version. All other source, script, build and
workflow changes trigger a new reservation. A new upstream SHA triggers a new build even
when upstream changed documentation only. Explicit retries use the original reserved source. A rollback after a newer reservation receives a
new version even if its file tree matches an older published release.

Source and upstream heads are checked immediately before reservation and again before
publication. A substantive advance defers publication to a later synchronized run. Equivalent
fork documentation changes remain allowed. Assets, manifests and tags continue identifying
the original compile commit; no artifact is described as built from the later documentation commit.

The build job is an explicit dependency in the same workflow. It does not expect the
`GITHUB_TOKEN` branch/tag push to trigger another workflow. This avoids GitHub's normal
suppression of most events generated by that token.

## Local initial release and recovery

Run from a clean tracked checkout containing the final source and release tooling. The sync
command is restricted to disposable GitHub Actions checkouts; local publication uses these
separate commands. Keep automation disabled while reserving the first version.

```sh
python3 scripts/translator_release.py reserve --source HEAD --upstream <verified-upstream-sha> --number 15
# Review the reservation, then explicitly push this immutable tag:
git push origin refs/tags/translator-v15:refs/tags/translator-v15
./gradlew assembleRelease -PtranslatorReleaseNumber=15 -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false
python3 scripts/translator_release.py package \
  --tag translator-v15 \
  --apk app/build/intermediates/apk/release/app-arm64-v8a-release.apk \
  --output build/translator/public-release-v15 \
  --formatting passed --migrations passed --unit-status passed \
  --unit-results app/build/test-results/testDebugUnitTest \
  --unit-results domain/build/test-results/testDebugUnitTest
python3 scripts/translator_release.py publish \
  --tag translator-v15 --directory build/translator/public-release-v15
```

Replace the example check statuses with the observed outcomes. Defaults are `not_run`;
missing JUnit reports remain unavailable, not zero tests. `package` verifies the real APK
using `apksigner`, `aapt` and `zipalign -c -P 16 -v 4`; set `ANDROID_HOME` or pass `--apksigner`, `--aapt` and `--zipalign` paths.
The package name, version code/name, ARM64-only native ABI and exact certificate must match.
Certificate inspection uses verbose `apksigner` output with exactly one reported signer. It accepts
the observed Android Build Tools 37 scheme labels and legacy ordinal/range labels; all APK
certificate digests must match the preserved certificate. Source-stamp digests never establish
the application signing identity. Unknown formats fail closed.
The 16 KB ZIP alignment check is separate from native ELF/runtime acceptance.
Local signing still uses the existing local keystore configuration. `package` refuses a
modified tracked checkout or a checkout different from its reserved commit.

For a local release with observed focused device tests, optionally add
`--device-results <private-receipt.json>`. The receipt must contain exactly `schema: 1`,
`source_sha`, `apk_sha256` and `runs`. Each run contains only `kind` (`k90` or `emulator16k`),
`page_size_bytes` (4096 or 16384), `status` (`passed`, `failed`, `not_run`), numeric `tests`,
`failures`, `errors`, `skipped`, `recorded_at` in `YYYY-MM-DDTHH:MM:SSZ` format, and these
required APK identities:

- `tested_package`: exactly `app.mihon` or `app.mihon.benchmark`.
- `tested_apk_sha256`: the actual installed target APK's lowercase SHA-256 hash.
- `instrumentation_apk_sha256`: the instrumentation APK's lowercase SHA-256 hash.

The top-level source/hash must match the reservation and released normal `app.mihon` APK.
A run with `tested_package: app.mihon` must also have that same tested-APK hash. A run targeting
`app.mihon.benchmark` records the matching minified benchmark companion's own hash; its
methods provide companion coverage. Release notes and the manifest identify this scope
explicitly, alongside the instrumentation hash, without attributing companion methods to
the main package. `emulator16k` requires an observed 16384-byte runtime. Unknown fields,
missing identities and free-form logs are rejected. Overall acceptance remains pending;
the receipt does not perform agent visual or passage review.

If building fails, the reservation remains and normal polling does not repeatedly rebuild
it. Dispatch with `retry_reserved=true` to retry an unpublished reservation. A published
release always remains a no-op. On an explicit retry, the workflow first looks for the original preserved Actions bundle. If one
exists, it validates the artifact digest, trusted workflow run, source identity and all checksums,
then resumes publication without signing, rebuilding or repackaging. Matching uploaded assets
are skipped; only missing assets are uploaded. The earliest bundle is authoritative, including
when an expired bundle makes recovery unavailable. A draft with assets but no preserved bundle
fails closed. For a locally preserved bundle, `publish` resumes the same exact bytes directly.
GitHub's release-by-tag lookup may return 404 for an existing draft. Release inspection then
checks authenticated, paginated release listings for the exact tag; malformed/duplicate identities
or failed lookups stop publication rather than assuming absence. This recovery was exercised on
the initial v15 draft and on the v17 Actions retry without replacing either APK or source
bundle. Creating a new draft uses the authoritative REST creation response directly; it
validates the returned release ID, exact tag, draft/prerelease flags and asset list before
uploading. It does not depend on immediately discovering that new draft through a separate
tag/list lookup. A failed or ambiguous creation is not blindly repeated; a later explicit
retry follows the existing draft-discovery path.

CI preserves the original bundle before publication for 90 days. An expired/missing original
bundle with prior assets requires a new reviewed source/version, not moving the old tag.

## Artifacts and validation boundaries

Each release includes:

- `mihon-translator-vN-arm64-v8a.apk`.
- `mihon-translator-vN-source.zip`, from `git archive` of the exact reserved source, with all
  tracked files; untracked/ignored local files and credentials are not added.
- `manifest.json`, recording source/upstream, version, certificate, ABI, asset hashes/sizes
  and the workflow run URL where available.
- `validation.json`, aggregate formatting, migration and supplied JUnit counts, explicitly
  marking device/live-provider/human validation not run by this workflow.
- `SHA256SUMS`, covering the APK, source ZIP, manifest and validation summary.

Raw failure messages, test names, logs, API captures, environment values and signing material
are excluded from the generated validation summary. Historical handset evidence remains in
its existing reports with its original artifact scope. CI runs formatting, host unit tests,
SQLDelight migration verification and the normal minified release build. Those checks must
pass for automatic publication; successful host checks do not imply handset acceptance.

The local release-control tests use temporary Git repositories and synthetic artifacts:

```sh
python3 -m unittest discover -s scripts/tests -p test_translator_release.py -v
```

They cover version reservation/retry, source binding, fast-forward-only mirrors, preserved
merge conflicts, docs-only deduplication, APK metadata/certificate/ABI validation, secret-free
aggregate reports, source-archive ownership, artifact checksums and immutable upload retries.
They make no remote writes, Gradle calls, device calls or provider requests.

## Official behavior references

Verified 2026-09-20:

- [Scheduled events and default-branch restrictions](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule).
- [Events generated by GITHUB_TOKEN](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow).
- [Workflow concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency).
- [Deployment environments](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).
- [Immutable releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases#immutable-releases).
- [Additional workflow authentication permissions](https://docs.github.com/en/actions/tutorials/authenticate-with-github_token#granting-additional-permissions).
- [Workflow scope](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps#available-scopes).
- [Fine-grained permissions for Git references](https://docs.github.com/en/rest/git/refs#update-a-reference).
