-- Offline only. Run with Perfetto v58.2 query -f against a private trace.
-- Change this exact package value for a different installed variant.
-- Durations are actual app FrameTimeline slices, not display presentation intervals.
CREATE PERFETTO TABLE translator_frame_parameters AS SELECT 'app.mihon' AS package;

SELECT 'trace_bounds' AS section, start_ts, end_ts,
       (end_ts - start_ts) / 1e9 AS retained_seconds FROM trace_bounds;

SELECT 'scheduler_coverage' AS section, count(*) AS rows,
       (max(ts + dur) - min(ts)) / 1e9 AS complete_slice_span_seconds
FROM sched WHERE dur >= 0;

SELECT 'scheduler_gaps' AS section, cpu, max(gap) / 1e6 AS largest_positive_gap_ms
FROM (SELECT cpu, max(0, ts - lag(ts + dur) OVER (PARTITION BY cpu ORDER BY ts)) AS gap
      FROM sched WHERE dur >= 0) GROUP BY cpu;

SELECT 'trace_diagnostics' AS section, name, idx, severity, value FROM stats
WHERE value > 0 AND (severity != 'info' OR name GLOB '*discard*'
                    OR name GLOB '*overrun*' OR name GLOB '*overwrite*');

CREATE PERFETTO TABLE translator_actual_frames AS
SELECT a.*, p.pid,
       CASE WHEN p.name = q.package THEN 'exact_process_name'
            ELSE 'layer_name_only_missing_process_metadata' END AS attribution
FROM actual_frame_timeline_slice a
JOIN process p USING (upid)
CROSS JOIN translator_frame_parameters q
WHERE p.name = q.package OR
      (p.name IS NULL AND instr(a.layer_name, ' - ' || q.package || '/') > 0);

SELECT 'frame_counts' AS section, upid, pid, layer_name, attribution,
       count(*) AS observed_rows,
       sum(CASE WHEN dur >= 0 THEN 1 ELSE 0 END) AS complete_rows,
       sum(CASE WHEN dur < 0 THEN 1 ELSE 0 END) AS incomplete_rows,
       count(DISTINCT surface_frame_token) AS distinct_surface_frame_tokens,
       (max(CASE WHEN dur >= 0 THEN ts + dur END) - min(ts)) / 1e9 AS observed_span_seconds
FROM translator_actual_frames GROUP BY upid, layer_name, attribution;

SELECT 'frame_durations' AS section, upid, pid, layer_name, attribution,
       count(*) AS complete_rows,
       PERCENTILE(dur / 1e6, 50) AS duration_p50_ms,
       PERCENTILE(dur / 1e6, 95) AS duration_p95_ms,
       PERCENTILE(dur / 1e6, 99) AS duration_p99_ms,
       max(dur) / 1e6 AS duration_max_ms,
       sum(CASE WHEN jank_type IS NOT NULL AND jank_type != 'None' THEN 1 ELSE 0 END) AS jank_labeled_rows,
       sum(CASE WHEN jank_type IS NULL THEN 1 ELSE 0 END) AS unknown_jank_rows,
       sum(CASE WHEN instr(jank_type, 'App Deadline Missed') > 0 THEN 1 ELSE 0 END) AS app_deadline_missed_rows,
       sum(CASE WHEN present_type = 'Dropped Frame' THEN 1 ELSE 0 END) AS dropped_rows
FROM translator_actual_frames WHERE dur >= 0 GROUP BY upid, layer_name, attribution;

SELECT 'frame_classifications' AS section, upid, layer_name, jank_type,
       jank_severity_type, present_type, on_time_finish, count(*) AS rows
FROM translator_actual_frames WHERE dur >= 0
GROUP BY upid, layer_name, jank_type, jank_severity_type, present_type, on_time_finish;

-- Unique matching expected rows prevent a many-to-many token join from inflating counts.
CREATE PERFETTO TABLE translator_expected_frames AS
SELECT upid, layer_name, surface_frame_token, count(*) AS matches,
       min(ts) AS ts, min(dur) AS dur
FROM expected_frame_timeline_slice
GROUP BY upid, layer_name, surface_frame_token;

SELECT 'expected_deadlines' AS section, a.upid, a.layer_name,
       count(*) AS complete_actual_rows,
       sum(CASE WHEN e.matches = 1 AND e.dur >= 0 THEN 1 ELSE 0 END) AS unique_expected_matches,
       sum(CASE WHEN e.matches = 1 AND e.dur >= 0 AND a.ts + a.dur > e.ts + e.dur THEN 1 ELSE 0 END)
         AS ended_after_expected_deadline_rows,
       max(CASE WHEN e.matches = 1 AND e.dur >= 0 THEN (a.ts + a.dur - e.ts - e.dur) / 1e6 END)
         AS largest_end_minus_deadline_ms
FROM translator_actual_frames a LEFT JOIN translator_expected_frames e
ON a.upid = e.upid AND a.layer_name IS e.layer_name AND a.surface_frame_token = e.surface_frame_token
WHERE a.dur >= 0 GROUP BY a.upid, a.layer_name;

SELECT 'gpu_counter_tracks' AS section, t.id, t.name, t.type, t.unit, t.gpu_id,
       count(c.id) AS samples, min(c.value) AS minimum, max(c.value) AS maximum
FROM gpu_counter_track t LEFT JOIN counter c ON c.track_id = t.id GROUP BY t.id;

SELECT 'gpu_process_memory' AS section, p.pid, p.name, t.id, t.name AS counter_name,
       t.unit, count(c.id) AS samples, min(c.value) AS minimum, max(c.value) AS maximum
FROM process_counter_track t JOIN process p USING (upid)
CROSS JOIN translator_frame_parameters q LEFT JOIN counter c ON c.track_id = t.id
WHERE p.name = q.package AND t.type = 'process_gpu_memory'
GROUP BY t.id;

-- Auxiliary GPU completion/frequency counters may be system-wide; no package attribution is implied.
SELECT 'gpu_auxiliary_counter_inventory' AS section, t.id, t.name, t.type, t.unit,
       count(c.id) AS samples, min(c.value) AS minimum, max(c.value) AS maximum
FROM counter_track t LEFT JOIN counter c ON c.track_id = t.id
WHERE lower(t.name) GLOB '*gpu*' GROUP BY t.id;

SELECT 'gpu_render_stage_inventory' AS section, count(*) AS all_gpu_slices,
       sum(CASE WHEN p.name = q.package THEN 1 ELSE 0 END) AS package_gpu_slices
FROM gpu_slice g LEFT JOIN process p USING (upid) CROSS JOIN translator_frame_parameters q;

SELECT 'clock_mapping' AS section, ts, clock_name, clock_value FROM clock_snapshot
WHERE clock_name = 'REALTIME';
