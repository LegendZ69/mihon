# K90 runtime observations — 2026-09-06

These are bounded measurements on controlled synthetic fixtures, with the handset charging. They do not establish handset performance guarantees, a leak-free process, or passage-level translation correctness. Human meaning review remains pending.

## Installed build and physical runtime

The observed device is REDMI K90 Pro Max, model `25102RKBEC`, QTI `SM8850`, Android 16 / API 36, HyperOS `OS3.0.308.0.WPMCNXM`, security patch 2026-06-01. Its actual kernel page size is **4096 bytes**; this observation is separate from the official 16 KB emulator validation. Package `app.mihon` ran the fixed minified release APK, SHA-256 `3bcd37c58499cf1e06013dc3a0a54213e8b6b05adc3d34b6c0c9ce6f2c627462` (117,314,576 bytes).

## Small Paddle twelve-page observation

Evidence folder: `build/translator/validation/handset/20260906T024018Z-handset-fixed-release-paddle-small-twelve-cb5cd8e9/`. Derived sampled values: private `paddle-small-observed-metrics.json`.

The translation job ran approximately **10:40:22–10:40:52 SGT** and retained 9/12 completed pages. The remaining failures were provider region-ID validation failures; this was not an OCR native crash. The saved observation window ran 10:40:20–10:42:20, so about 30 seconds contained the job and the remainder included post-job activity and idle time. This is not a 120-second sustained OCR run or an isolated inference-latency measurement.

Twenty periodic snapshots, plus before/after snapshots, were taken. Five periodic memory snapshots started inside the job interval, at 10:40:25, :31, :36, :42 and :48. Their actual spacing was roughly 5.7–5.8 seconds because collection takes time. All peaks below are **sampled maxima**; a higher value between samples is possible.

| Metric | Before job | Maximum sampled during job | Final snapshot, 10:42:20 |
|---|---:|---:|---:|
| Total PSS | 216.80 MiB | **1,220.86 MiB** | 215.59 MiB |
| Total RSS | 383.12 MiB | **1,382.05 MiB** | 376.20 MiB |
| App Summary Java Heap PSS | 34.47 MiB | 158.57 MiB | 13.70 MiB |
| App Summary Native Heap PSS | 38.62 MiB | 893.32 MiB | 46.65 MiB |
| App Summary Graphics PSS | 49.71 MiB | 49.73 MiB | 36.32 MiB |

The total PSS/RSS maxima occurred at 10:40:31 (1,250,163 / 1,415,224 KiB). The detailed Native Heap table at that sample reports PSS 914,892 KiB and heap allocated 1,360,499 KiB; the App Summary Native Heap value is 914,760 KiB. These are distinct dumpsys fields, not interchangeable measurements. Maximum reported Dalvik Heap allocation was 58,703 KiB. The sixteen post-job memory snapshots, including the final snapshot, reached at most 295,628 KiB PSS / 462,340 KiB RSS. Falling PSS after completion is observed; absence of native allocation tracing prevents a leak determination.

All saved battery samples reported AC powered `true`, USB powered `false`, charging status `2`, and level 100. This is the battery service's classification while the phone was physically connected for USB testing. Battery-service temperature was 35.5–35.7°C. This run does not provide unplugged measurements.

Use only **Current temperatures from HAL**, not the separately printed cached values. During the five active samples, the maximum current CPU sensor was 72.6°C, maximum GPU sensor 57.0°C, maximum skin sensor 35.62°C, and current battery sensor 35.0–35.4°C. Before the job, maximum current CPU/GPU sensors were 40.5/38.7°C. Post-job maxima were 54.4/40.7°C, with skin at most 34.95°C and battery at most 35.5°C. The cached section repeats CPU/GPU values of 99.6/72.5°C even before the job; those stale values are excluded. Thermal service status remained `0`. This status and the snapshots do not establish absence of frequency throttling, nor is a vendor die sensor the case temperature.

## Reader trace collection and interpretation

The early action ledgers' hardcoded five-page chapter label is inaccurate: later handset UI inspection verified that the scrolling crossed from controlled chapter 001 into chapter 002. Treat the early WebGPU/classic traces and the sustained workload as **controlled-series cross-chapter scrolling**. The separate private `reader-fixture-provenance-corrections.json` hashes the original ledgers and records this correction without overwriting their raw contents.

The first offline WebGPU probe has mixed idle/gesture coverage and is not a full scrolling benchmark. The next synchronized 30-second recordings also require a retention correction: their shared 32 MiB ring buffer retained only the final **6.613 seconds (WebGPU)** and **7.044 seconds (classic)**. Startup process names were missing, despite retained ReaderActivity layer identities. These files remain private supporting evidence, not full-window acceptance:

| Retained tail only | Complete layer frames | Actual slice p50 / p95 / p99 | Jank labels |
|---|---:|---:|---|
| WebGPU | 785 | 3.354 / 4.269 / 5.037 ms | 0 labeled janky |
| Classic | 835 | 4.378 / 7.096 / 7.323 ms | 320 `Buffer Stuffing` / `Late Present`; 0 `App Deadline Missed` |

All counted tail frames finished on time according to their app timing fields. A late presentation with buffer stuffing is distinct from an app deadline miss. The figures describe the explicitly named ReaderActivity layer, with provisional layer-only attribution because process metadata was overwritten. They do not prove every WebGPU render stage or overlay was individually traced, and do not support comparing backend performance across complete runs.

Tail artifacts: WebGPU SHA-256 `24fb2b3c9c624106324c5a06cee06865708e6d4517690b72af66b91f2a87f901`; classic `39c7bcbcd1118d33f315ee834f0873fc3bb730a92f173baf44309e8c2566075e`. Both showed 36 ftrace setup notices and respectively 2/3 discarded chunks. No nonzero `data_loss` statistic was reported, demonstrating why that statistic alone cannot establish full retention. Neither tail included GPU hardware counter tracks, render-stage slices, or attributable GPU memory; auxiliary `GPU completion` counters are not memory or hardware utilization.

The capture script now uses separate 32 MiB ftrace / 8 MiB metadata buffers, streaming every two seconds, five-second flush requests, a 512 MiB file cap, and documented GPU memory/frequency ftrace events. These intervals and sizes are application choices. A cap can stop recording early; actual retained coverage and track availability must be checked after each run. [Official buffer guidance](https://perfetto.dev/docs/concepts/buffers), [configuration fields](https://github.com/google/perfetto/blob/main/protos/perfetto/config/trace_config.proto), [GPU sources](https://perfetto.dev/docs/data-sources/gpu).

The corrected WebGPU collection, `20260906T025756Z-webgpu-cached-scroll-streamed-30s-96092ea9`, retains **29.999674 seconds**. Its scheduler slices span 29.966176 seconds with no positive gap between consecutive complete slices on each CPU. Its exact `app.mihon` ReaderActivity layer contains 3,597 complete frames spanning 29.974398 seconds, with actual slice p50/p95/p99 **3.378/4.210/4.752 ms**, maximum 6.277 ms, and zero labeled jank, app deadline misses, or dropped rows. All have one matching expected frame and end before the expected app deadline. This is one plugged-in, controlled-series scrolling observation, not an unplugged repeatability result.

That full trace now contains 293 attributable GPU memory samples at **529.375–550.836 MiB**. It has no GPU render-stage slices or observed GPU frequency/utilization counter, so those measurements remain unavailable. It still records 36 ftrace setup notices and four discarded chunks, while no nonzero `data_loss` statistic appears; full time-span coverage is established, but a blanket loss-free claim is not. Its SHA-256 is `ef0a5bf4829e7e8e5894de0f19dc4737341c15b92e6ebef9dadf2ce11b714d14` (154,305,654 bytes). Private CSV/diagnostics are under `private/perfetto-reviewed/webgpu-streamed/`.

The corrected classic trace, `20260906T030002Z-classic-cached-scroll-streamed-30s-b875d6e4`, contains **30.002576 seconds**, with complete scheduler slices spanning 29.961051 seconds and no positive per-CPU gap. The operator verified chapter **002 — Twelve-page corpus** before and after forty alternating 500-pixel gestures. This narrower gesture sequence differs from the earlier WebGPU cross-chapter workload. Its 3,561 complete ReaderActivity frames span 29.916511 seconds; actual slice p50/p95/p99 is **4.351/7.046/7.311 ms**, maximum 13.426 ms. There are **1,247 Buffer Stuffing** and **5 Prediction Error** labels, together 35.16% of observed frames, with zero `App Deadline Missed` labels and zero dropped rows. One frame ends 0.308 ms beyond its expected app deadline despite having no jank label; that numeric observation is reported separately from the classifier.

Classic's attributable GPU memory has thirteen samples at **563.438–605.387 MiB**. Two system GPU-frequency events record 160,000 and 191,000 kHz; these are available frequency samples, not per-app utilization or a complete time-weighted frequency profile. GPU utilization and render-stage slices remain unavailable. The trace has 36 setup notices/five discarded chunks and no nonzero `data_loss` statistic. Its SHA-256 is `123f7d4b90443857f34755803e937864e23a6b1e7ce53a33b54923f04d683749` (143,436,489 bytes). Private analysis is under `private/perfetto-reviewed/classic-streamed/`. One run of each backend, different gesture scopes and retained process/cache state do not establish a causal performance ranking.

## Three-minute sustained reader observation

The fixed release ran a cached WebGPU cross-chapter scrolling workload from **10:51:08.940–10:54:08.943 SGT**. The action ledger records 235 gestures between 10:51:08.940 and 10:54:08.263, with no new provider dispatch requested. The observation folder is `handset/20260906T025107Z-handset-webgpu-sustained-reader-180s-9ce463c8`; private derived measurements are `webgpu-sustained-reader-observed-metrics.json`.

Sixteen periodic memory samples plus before/after samples show a material growth concern. Before-workload PSS/RSS were **484.82/651.94 MiB**. After an initial reduction to PSS 444.61 MiB at 10:51:19, PSS rose in every subsequent sample to **761.98 MiB**, with final RSS **925.35 MiB**. Over that same first-periodic-to-final interval, Native Heap App Summary PSS rose **169.63→425.74 MiB**, Graphics PSS **116.50→183.36 MiB**, and Java Heap PSS fell **30.36→23.21 MiB**. These samples establish increasing retained process memory during this run; they do not distinguish a cache from a native/resource leak. A release/idle follow-up and focused allocation/resource investigation remain required before memory acceptance.

Current HAL temperature maxima across the saved snapshots were CPU 44.0°C, GPU 41.1°C and skin 33.94°C; battery HAL values ranged 34.3–34.7°C. Thermal service status remained `0`. BatteryService reported AC power throughout, level 100 and status `5` (full), with 34.7–35.2°C battery readings. The phone remained plugged in; charging a full battery and running unplugged are different conditions.

The single observed process remained present, and its bounded log contains no `FATAL EXCEPTION`, `ANR in`, `OutOfMemoryError` or `Fatal signal` marker. This supports absence of those observed markers during the bounded run, not complete crash-history or background-eviction acceptance. Cumulative gfxinfo from this workload does not yield isolated three-minute frame percentiles.

## Reproducible offline queries

`scripts/translator_frame_metrics.sql` was checked with Perfetto **v58.2-add693d8b**, RPC API 14, against both saved traces. Run:

```sh
python3 build/translator/validation/tools/trace_processor query \
  -f scripts/translator_frame_metrics.sql \
  path/to/private/trace.pftrace > path/to/private/frame-metrics.csv
```

The SQL selects the exact `app.mihon` process, with an explicitly labeled layer-name fallback only when the process name is missing. Change its first package parameter for another variant. It separates layers, excludes unfinished slices from duration percentiles, reports unknown jank separately, prevents duplicate expected-token joins, and inventories actual GPU tracks. App FrameTimeline duration is the actual app slice duration; it is not the interval between presented frames. [FrameTimeline semantics](https://perfetto.dev/docs/data-sources/frametimeline).

The optional capture argument `--trace-processor build/translator/validation/tools/trace_processor` writes coverage diagnostics to the manifest and retains SQL/stdout/stderr privately. `duration_observed` is a duration check only, with a 0.5-second truncation alarm tolerance; continuous scheduling/frame coverage, process identity, lost-data diagnostics, and action-window alignment still require offline review. Recorded action timestamps use the host clock; use trace REALTIME clock snapshots and the saved device/host timing evidence when aligning them, allowing for USB command latency. Do not subtract cumulative gfxinfo percentiles to manufacture a focused flow measurement.
