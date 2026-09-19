# Local quality-review acceptance

Prepared 2026-09-06. This protocol uses synthetic pages, the real minified app graph, SQL repository,
manager, provider gateway, and the loopback fixture server. It does not assess translation meaning or
contact a cloud provider. The original three gateway methods passed on K90 and the official 16 KB
emulator on the verified Resume-fix artifacts. Two later focused methods passed on both runtimes on
the final classification/orphan-history artifacts; exact scope and hashes are recorded below.

Start a fresh server with the default scenario registry and a new private ledger path:

```sh
python3 scripts/translator_fixture_server.py --port 8765 --ledger /tmp/mihon-quality-review-requests.jsonl
```

The test imports its own synthetic credential into a unique isolated graph and removes it afterward.
It requires the non-debuggable `app.mihon.benchmark` variant, the exact `mihon-fixture` model, the
benchmark loopback transport guard, and an online Android network. The server never forwards requests.
Restart the owned server before repeating the class: delayed scenarios deliberately require unused
sequences. Existing user queue, credentials, results, settings, and WorkManager jobs are not used.

With the matching benchmark app and test APK installed, the device owner can run:

```sh
adb -s "$TRANSLATOR_DEVICE_SERIAL" reverse tcp:8765 tcp:8765
adb -s "$TRANSLATOR_DEVICE_SERIAL" shell am instrument -w -r \
  -e translation.qualityReviewAcceptance true \
  -e class mihon.feature.translation.acceptance.MinifiedQualityReviewAcceptanceTest \
  app.mihon.benchmark.test/androidx.test.runner.AndroidJUnitRunner
```

The original three gateway methods check:

1. A newly translated page schedules one automatic review. The candidate commits, a second queue run
   does not dispatch another review, and undo restores the saved baseline with a new revision.
2. Duplicate result delivery and duplicate review requests preserve one pending checkpoint. Paddle
   review succeeds after the owned source image is deleted, preserves raw OCR, correction, geometry,
   rotation and measured scores, and reports incomplete visual coverage. A malformed review preserves
   the completed page and records one failed attempt without resubmitting translation.
3. After the host durably records a delayed review request, coroutine cancellation preserves its spent
   attempt. A new graph uses the second attempt, preserving raw OCR. A manual edit made while another
   response is in flight supersedes that review; its late candidate cannot overwrite the edit.

The third check is coroutine interruption and graph recreation. It is not Android process death,
force-stop, WorkManager recovery, or OEM eviction. Separate protocols cover those behaviors.

Each method emits `QUALITY_REVIEW_ACCEPTANCE_REPORT` JSON in instrumentation status bundles and writes
its report under an owned `noBackupFilesDir/quality-review-acceptance/<UUID>/` directory. Reports contain
only synthetic job/result/review/event snapshots and cleanup flags. Preserve the emitted report,
instrumentation output, exact APK hashes, and the corresponding host ledger. A failed run remains a
failure even when cleanup succeeds.

The host ledger identifies review dispatches with `is_review`, `review_source_revision`, image hashes,
and request IDs. Generation and review scenario sequences are independent; token counting consumes
neither. `/health` exposes bounded per-scenario review dispatch counts only after its dispatch event is
written, providing the interruption/edit barrier without exposing source text, region IDs or keys.
Expected generation/review counts for the original three gateway methods are 1/1, 0/1, 0/1, 0/2 and
0/1 for their five synthetic jobs respectively. The two newer methods add no HTTP provider requests. The cancelled review may finish at the host after the client closes;
retain that terminal event as evidence of an uncertain dispatched request.

Host validation on 2026-09-06: all 27 fixture protocol/privacy/recovery tests passed. The formatted
instrumentation source compiled with the cached Kotlin compiler against the current app/domain
classes. The actual production review serializer also produced a request accepted by the fixture
parser. These host checks do not establish minified runtime or handset acceptance.

```sh
python3 -m unittest scripts.tests.test_translator_fixture_server
```

## First handset run and reproduced preflight correction

The first minified K90 run failed all three methods. Its private evidence is
`build/translator/validation/private/quality-review-local-k90/instrumentation.log`. The text-only
Paddle repair and undo subchecks passed, but visual reviews did not reach the fixture server: automatic
and malformed-response checks observed `INCOMPLETE`, and the in-flight request barrier timed out.
Those failed reports did not capture the failing job checkpoint, so the source diagnosis is recorded
separately from the directly observed device results.

The exact AndroidX ExifInterface 1.4.2 implementation inserts orientation `0` for an absent tag;
its rotation API interprets that as no rotation. The previous guard accepted only `1`, incorrectly
rejecting normal untagged PNGs. Review now accepts undefined/normal orientation `0`/`1`, keeps the
original bytes unchanged, and continues rejecting rotated, mirrored and invalid orientation values.
The regression parses an actual untagged PNG using the official library. API semantics are documented
in the [AndroidX ExifInterface reference](https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface#getRotationDegrees()).
The exact official 1.4.2 sources artifact is retained privately with SHA-256
`dc69c04f03e72ad38ed43774554e6612dd9ffe7299eacaab62a38df8ac2c7767`.

The same correction window added a bounded private original snapshot shared by token counting and
generation, plus strict numeric rotation validation for review responses. The snapshot prevents a
chapter file changed between requests from being sent with the old image hash. It is removed after
success, failure or cancellation; text-only review creates no image snapshot. Four added provider
regressions brought the focused review suite to **19 passing tests**. The updated minified harness
records EXIF values when seeding an image and captures all isolated job/image/result/review/event state
before failure cleanup. The corrected original three methods subsequently passed on K90 in 7.732 s
and the actual 16 KB emulator in 5.747 s using benchmark `f5e6eb42…` / tests `8e3d6c6c…`.
The earlier failures remain part of the evidence.

## Final focused methods and artifact scope

The class now also includes `dRestartReconcilesOrphanedRunningBatchWithoutProviderDispatch` and
`eMetadataOnlyReviewDoesNotCreateARepairRevision`. Method d recreates an isolated SQL/Manager graph
and verifies interrupted orphan history, unchanged completed results and idempotence without requests.
Method e supplies explicitly authored provider-interface responses to the minified coordinator and SQL
repository: metadata-only outcomes retain the exact result/revision, findings require review, and a
material geometry candidate creates a repair revision. It does not claim gateway or live-model coverage.

On final benchmark `b1fe8855fa32ff9180dfee56eefbbd43ce261b6dcc55f582f4dd76e2bc3be998` and tests
`78472afc26459a4969b587c9a9bd136ba141d05ce452f499aec2c27bbd10488a`, these two methods passed on
K90 (0.355 s, 4096-byte pages) and the official emulator (0.700 s, actual 16384-byte pages). Both
owned credentials were removed, local ledgers contained zero dispatches, and owned servers/mappings
were removed. The original passing methods were not repeated merely for documentation. See
[the dated validation report](translator-quality-recovery-validation-2026-09-06.md) for archived
artifacts, independent reconciliations and separate actual Worker/Force-stop evidence.
