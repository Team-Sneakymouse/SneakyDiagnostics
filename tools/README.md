# Analysis tools

Python scripts that read SneakyDiagnostics capture folders (`plugins/SneakyDiagnostics/reports/<capture-id>/`) and
print what the maintainers otherwise work out by hand: rankings, window correlation, spark attribution and
before/after comparisons. Standard library only, no install step. Run them with [uv](https://docs.astral.sh/uv/);
use `-I` so a capture folder (untrusted data) can never shadow a module. Run from the repository checkout,
passing the capture path as an argument rather than changing into a capture directory. Keep analysis-tool
runtime dependencies standard-library only (pytest is a test dependency):

```powershell
uv run python -I tools/analyze_capture.py <capture-dir> [--json out.json] [--raw]
uv run python -I tools/analyze_spark.py   <capture-dir>/profile.sparkprofile [--prefix com.example.plugin.] [--match Text]
uv run python -I tools/diff_captures.py   <before-dir> <after-dir> [--json out.json]
uv run --with pytest pytest tools/tests
```

Captures are treated as untrusted: they are only parsed as JSON (or a bounded protobuf wire decode), gzip output is
size-limited (default 512 MiB per JSON document; override with `--max-bytes` or `SNEAKYDIAG_MAX_BYTES`, and
`SNEAKYDIAG_MAX_PROFILE_BYTES` for profiles, default 1 GiB), and `NaN`/`Infinity` are rejected. Nothing is executed or written next to the capture.

## Formats

| | v1 | v2 (`docs/report-schema-v2.md`) |
| --- | --- | --- |
| Marker | `schemaVersion: 1`, no `manifest.json` | `manifest.json`, `schemaVersion: 2` |
| Provider data | embedded in `plugins-1.json` `providers[].measurements` | `plugins-1.json` index plus `plugins-<provider>-1.json` files (the index `file` field is authoritative) |
| Compression | none | any JSON report may be `.json.gz`; read transparently |
| Verification | none (files shown as `unverified`) | manifest `bytes`, `uncompressedBytes` and `sha256` are checked; mismatches, missing and unlisted files become warnings |

Both load into one model (`tools/sneakydiag/loader.py`), so the analyses do not care. A problem with one file never aborts the run:
the file is skipped, a warning is printed, and everything that depended on it is reported as unavailable, not as zero.

## `analyze_capture.py`

Human-readable summary on stdout, the same data as JSON with `--json`. Sections:

- **Inventory**: files with verification status, collectors with completion reason and coverage flags (truncation, overflow, dropped, unfinished),
  collectors that were not captured, the provider selection, and each incomplete provider with the cause taken from its own fields.
- **Timeline**: tick count, observed ticks/s, tick-weighted mean/max, share of ticks over 50/100 ms, empty seconds. Empty seconds are gaps, not free ticks.
- **Providers**: for `sneakydiagnostics.probe/1` providers the top sections by `selfNanos` (exclusive, additive) and by `totalNanos`
  (inclusive, overlapping), `p95/p99` (histogram bucket upper bounds, over by less than 2x), `slowest` calls with capture offset and tick,
  counts, levels, and `entities.owned.*`. MagicSpells (free-form) gets the spell ranking plus the mixed-case recovery: detail rows are grouped by
  lower-cased spell name, but only when the detail is complete and sums exactly to the global self total; otherwise the tool says why it refuses.
  Other free-form providers (Voice) list their scalar fields and every `{totalNanos, samples}` timer found, with queue/delay timers labelled as waiting time.
- **Scheduler**: pending-task range and per-plugin pending, snapshot churn, truncation/overflow, snapshot collection overhead, and Ignite groups by owner x event x
  thread kind (inclusive wall time).
- **Entity census**: segment-combined counts by type and owner hint, tracked-subscription groups, per-world coverage (unreached worlds are unknown, not zero).
- **Window correlation**: the worst timeline windows (max tick at or above `--slow-ms`, default 100; `--top-windows`, default 10), and for each the provider
  sections active in that window (from `series`, with a multiple of that section's median window), the scheduler snapshots that fell in it with the largest
  pending changes and the snapshot's own cost. For probes with count/level series it also shows what the provider counted in that second
  and its sampled levels against their median (a level with no sample in that second is left out, not shown as zero).
  It also lists sections that are busier in slow windows than in other windows. A provider
  with no series gets an explicit "cannot be attributed" statement. MagicSpells' operation series (execution-4) is shown per
  window as outermost-scope time and self time by operation. **Slow calls** place individual timed calls in the window: Ignite's
  slow scheduler and listener calls, each probe's `slowest` and per-section maxima, and MagicSpells' slow operations. A call is
  placed by its server tick when the timeline has current-tick ranges (`firstCurrentTick..lastCurrentTick`, the same counter as
  the records; its tick numbers are never used, they fall behind after an empty-server pause), otherwise by its end offset, and
  the count placed by offset is shown. Ranges are validated endpoints searched by bisection, never expanded; inverted, partial or
  overlapping ranges disable tick matching. Per window it shows the server-thread time covered by the union of these intervals
  (nested and overlapping calls are not counted twice) inside the measured tick duration, and separately the time in
  `ServerTickEndEvent` listeners (and calls nested in them), which Paper runs after measuring the tick, so it is not part of the
  percentage. With Ignite's `measuredTicks` for every tick of the window the covered share is an exact intersection with the
  intervals Paper measured as tick duration (tick-end work is excluded even when its listener left no record); otherwise
  only retained tick-end listener records can be removed and the share is labelled an UPPER BOUND, per window and per tick.
  Present-but-invalid current-tick endpoints (booleans, strings, partial) disable tick matching; only null or missing
  endpoints count as absent. Coverage above the tick time is flagged, not clamped. Uncovered tick time is unattributed, not idle. MagicSpells
  scopes that ended after the deadline (`series.late`) are shown in the post-deadline window only when they all ended in it.
  Ignite groups remain aggregates; a companion without slow-call records is reported as such.
- **Coverage audit**: enabled plugins (manifest) against registered providers, what each provider measures and its limits
  (omitted keys, series left out, evicted slow records, unobserved counters), and the plugins without a provider ranked by
  Ignite-measured scheduler time and slow listener calls. No provider means unmeasured, not zero cost; Ignite columns are
  inclusive, overlap provider sections and cover only scheduler tasks and slow listener calls. Absence of a provider is
  established only by a captured plugins index: without it (a scheduler-only capture) every plugin's provider status is
  unknown, none is reported missing, and Ignite-measured owners are listed under unknown status instead. Slow-call lists
  name why they are incomplete: evicted shorter calls, or listener calls still running on other threads at stop (counted,
  durations unknown); calls enclosing the stop itself are shown but are not a gap.
- **Leads**: hypotheses derived from the numbers, kept apart from the observations above them.

Rules the output follows: inclusive timings are never summed, units are in the column headers, missing or incomplete data is reported as a gap,
and a measured contributor is not presented as the proven cause of lost TPS. `--raw` removes the row caps (`--top`, default 15).

## `analyze_spark.py`

Reads a `.sparkprofile` and, if present, the sibling `spark-1.json`:

- **Ownership/coverage**: owned or shared profile, and for each analyzed window how many seconds overlap the diagnostics capture window. A shared
  profile can be mostly history from before the capture; the tool says so rather than attributing it to the capture.
- **Windows**: the last retained window by default (`--windows N` for more, `--all` for one aggregate), with spark's players/entities/chunks/MSPT.
- **Accounting**: inclusive and self sampled milliseconds per method on the `--thread` (default `Server thread`). A method or prefix is counted once per stack at
  its outermost frame, so recursion is not double counted; self time is additive.
- **`--prefix com.example.plugin.`** (repeatable or comma separated) reports the share of server-thread time under that code (outermost-frame inclusive time,
  total self time, top self methods, and a union across prefixes). A prefix that matches nothing is warned about.
- **`--match Text`** prints the call path to the outermost frame whose `class.method` contains the text, plus the heaviest callee chain below it.

The protobuf wire format is decoded by `tools/sneakydiag/sparkwire.py` (about 300 lines, only the fields used). This avoids generated `*_pb2` modules, which
refuse to import on a protobuf runtime older than the protoc that produced them, and avoids a dependency. Field numbers follow
[spark's schema](https://github.com/lucko/spark/tree/master/spark-common/src/main/proto/spark); unknown fields are skipped. The decoder was cross-checked against the
official runtime on real profiles. If spark renumbers fields, update the `F_*` constants.

## `diff_captures.py`

For the controlled-change workflow: capture, change one thing, capture again under comparable load, then compare.
Tick statistics, probe sections matched by provider + section name (per-observed-second rates, with added/removed flagged), MagicSpells spells and operations,
Ignite groups, levels, counts and scheduler pending. The top of the output is a loud block when:

- players, entities or chunks differ by more than `--tolerance` (default 10%), or workload is unknown for either capture (manifest first, else the last spark window, labelled approximate),
- a capture, collector or provider is incomplete or its file is missing/mismatched,
- the captures are the same, provider sets or instrumentation versions differ, or schema versions differ.

A delta is evidence that two captures differ; it attributes the difference to your change only when workload and coverage match. Repeat the baseline to see run-to-run noise.

## Tests

`tools/tests` builds small synthetic v1 and v2 captures and spark profiles in a temporary directory (no real data is stored in the repository). To also smoke-test against a
real capture on your machine, set `SNEAKYDIAG_RAW=<capture-dir>`; that test is skipped otherwise.
