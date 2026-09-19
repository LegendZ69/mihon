# Minified queue acceptance — 2026-09-06

The bounded mode/retry matrix **passed on the physical K90 in 4.099 seconds**, against benchmark APK SHA-256 `9301adfab6fb2fa0e13cdc587907fac026c3e3949eee24103bbf2df6b5cf3ff7` and corrected instrumentation APK `e62e998cdf83bbab9f77bc7ebc2fa5ccf99277159a51a72e083038b28880504d`. These tests use cached synthetic pages, a private graph/database, and the loopback server; they make no paid provider requests. Remaining application-wide acceptance is tracked in the [physical validation report](translator-k90-validation-2026-09-06.md).

The earlier failed invocations below used benchmark `a9eae61412d626af767f30bf08c51241199c9810bee728f27e8ac415ed0a4cca` and instrumentation `031d3b30aaba26748aece22c773946751ddc7f5f5e452d5445e607a3c2512006`; their failures remain recorded separately.

## Closed fixture connections

The first completed mode run recorded nine transport attempts for five singleton batches, while the server ledger contained exactly five successful generation dispatches. The first batch succeeded on attempt one; each later batch had a transient failure without an HTTP status and succeeded on attempt two. These were separate attempts, not duplicate database events.

The HTTP/1.0 fixture closed sockets without an explicit `Connection: close` header. An independent probe using official OkHttp JVM 5.5.0 reproduced three successes and two socket/EOF failures in five calls. Adding the close header produced five successes and five server receipts. The host server now advertises closure on health, successful, scripted-error, and authentication-error responses. The protocol regression failed before the change and all 21 fixture tests passed afterward. Exact request-count assertions were preserved.

## Results after the host fix

The next K90 invocation passed assertions for all four modes, odd halving, partial-output preservation, and 429 → 503 → 200 retry behavior before reaching a wrong expected chapter state in the test. After correcting that oracle, the complete mode/retry test passed on the newer artifact pair recorded above; the runtime production classification remained unchanged.

| Scenario | Confirmed result |
| --- | --- |
| Vertex, five pages | Five requests; five ordered singleton batches; five retained results |
| Max, five pages | One request and batch; five retained results |
| Halving, fitting chapter | One request and batch; five retained results |
| Custom, batch size two | Three requests; ordered batch sizes 2, 2, 1; five retained results |
| Odd halving with content failures | Nine requests; ancestry sizes 5, 3, 2, 1, 1, 1, 2, 1, 1; every singleton completed |
| Partial response | Three requests; batch sizes 5, 2, 2; completed first page excluded from descendants |
| Throttling and transient failure | Exactly three attempts and one response each with HTTP 429, 503, 200; one retained result |
| Singleton content failure and explicit retry | Corrected rerun passed: PARTIAL chapter, zero results, one FAILED singleton batch, then explicit retry completes with exactly two total requests |

The separate pause/coroutine-cancellation/new-graph test passed on both completed invocations. It does not establish Android process death; [the separate actual-process protocol](translator-process-recovery-validation.md) records that distinction.

## Chapter and batch failure states

`TranslationManager.runBatch` stores a singleton content failure as a **FAILED batch** and leaves that image retryable. Once the chapter's batch loop finishes, `runJob` reports **PARTIAL** whenever unresolved images remain, including zero completed images. Propagated chapter-level errors follow the separate failed/paused/waiting classification. The original test incorrectly expected the chapter to be FAILED.

The corrected assertion requires PARTIAL, zero completed images, the exact `1 images need retry` message, no results, one FAILED singleton batch for image `0`, and exactly one request. Its subsequent explicit retry must still complete the image with exactly two total requests. A failure snapshot is now saved before checking the state. Production classification is unchanged.

Read-only UI verification confirms that such a chapter appears in **All** and the separate **partial** state filter regardless of completion count. Exact state filters remain distinct: the **failed** filter selects FAILED chapters, not PARTIAL chapters. The row displays `partial · 0/1 images` and its retry message. Every row exposes **Retry failed**, and selection exposes **Retry selected**; both call `TranslationManager.retry`, which accepts PARTIAL without a completed-image threshold and requeues unresolved images. Physical UI interaction with this specific zero-completion state is not established by that source inspection.

## Evidence

- `build/translator/validation/private/benchmark-queue-instrumentation-retry.log`: initial nine-versus-five assertion failure.
- `build/translator/validation/fixture-connection-reuse/`: independent OkHttp red/green output, host regression, and cause summary.
- `build/translator/validation/private/benchmark-queue-connection-fixed.log`: later PARTIAL-versus-FAILED oracle failure and passing graph-recreation test.
- `build/translator/validation/private/benchmark-acceptance-models-ready/queue-modes-373f83a0-b96a-45a8-8cc9-432601c917a1/report.json`: persisted jobs, images, results, batches, and events for the passed preceding cases.
- `/tmp/mihon-k90-queue-fixture-close-20260906.jsonl`: independent fresh-server request ledger for the latter failed invocation.
- `build/translator/validation/private/queue-corrected-oracle-instrumentation.log`: successful 4.099-second corrected mode/retry test.
- `build/translator/validation/private/queue-corrected-oracle-requests.jsonl`: independent request ledger for the successful rerun.
- `build/translator/validation/r8-instrumentation-abi/ownership-old-app/artifacts.json`: exact unchanged benchmark/corrected test APK pair.

This establishes the bounded fixture mode/retry matrix. It does not establish live-provider meaning, source downloading, arbitrary production queue history, background policy behavior, or native memory stability. The new native ownership test in the same test APK failed JUnit discovery before native execution; that separate failure does not invalidate the completed queue test and is not a native-memory verdict.
