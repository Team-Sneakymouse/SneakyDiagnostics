# Report schema v2

The contract for the v2 report rework. Implementation tracks and the analysis tooling build
against this document; change it first when a decision changes. v2 is a clean break: every
report written by this release carries `"schemaVersion": 2` in its header. Tools read v1 and
v2 and tell them apart by that field. The layouts of `entities`, `timeline`, `scheduler`,
`spark` and `connections` reports are unchanged apart from compression.

## Compatibility policy

Compatible additions preserve `schemaVersion`; readers must tolerate unknown fields. Removing fields,
changing their types, or changing their meanings requires a new schema version. Preserve support for older
versions explicitly rather than silently interpreting their fields with new semantics.

For report-field changes, update this contract, affected readers (including `tools/sneakydiag/loader.py`),
and fixtures in `tools/tests` in the same change. Additive fields need reader changes only where the tools
consume them; document their units, meaning, and missing-data behavior here.

## Capture folder

```text
reports/<capture-id>/
  manifest.json                    capture index, rewritten after every file (never compressed)
  plugins-1.json                   collector index: one entry per provider, no measurements
  plugins-<provider>-1.json        one file per participating provider: its measurements
  timeline-1.json[.gz]  entities-<n>.json[.gz]  scheduler-1.json[.gz]  connections-1.json  spark-1.json  profile.sparkprofile
```

- `<provider>` is the lowercased plugin name with every character outside `[a-z0-9._-]`
  replaced by `_`. If two names collapse to the same slug in one capture, the later ones get
  `~2`, `~3`. The `file` field of the index is authoritative; do not derive names in tools.
- **Compression:** a JSON report whose serialized size exceeds 1 MiB is written gzip-compressed
  as `<name>.json.gz`. Smaller reports stay `.json`. Readers accept both. `.sparkprofile` is
  never recompressed. The manifest is never compressed.

## Provider selection

```text
/diagnostics plugins start [seconds] [provider ...]
/diagnostics all start [seconds] [world] [plugins=<provider>,<provider>]
```

- Provider names are matched case-insensitively and exactly against registered plugin names.
  No names selects every registered provider. An unknown or duplicate name rejects the whole
  start, as `connections` does for players. At most 32 names.
- The duration may be omitted when the first option is not an integer, as with `connections`.
- Tab completion offers registered provider names. `status` lists registered providers.
- Only selected providers are started; unselected providers are not called and are absent from
  the report. The index records the selection (`selected`: `"all"` or a list of names).

## `plugins-1.json` (collector index)

Standard header plus:

```json
{
  "selected": "all",
  "providers": [
    { "plugin": "SneakyWizards", "pluginVersion": "...", "instrumentationVersion": "...",
      "started": true, "reason": "completed", "complete": true,
      "file": "plugins-sneakywizards-1.json", "error": null }
  ],
  "incompleteProviders": ["MagicSpells"],
  "complete": true,
  "notes": "..."
}
```

- Provider `complete` is per provider (coverage). It is never combined into other providers' status.
- Collector `complete` means the coordination was normal: reason `completed` and no provider
  failed to start or stop. An incomplete provider is listed in `incompleteProviders` and does
  not by itself make the collector incomplete.
- `error` is omitted when null, and a provider that failed has no `measurements`; readers treat a missing
  `error` as null and a missing `measurements` as unavailable, never as zero cost. Index entries carry no
  sizes; the manifest has them.
- No registered providers means no plugin measurements, not zero plugin cost (keep this note).

## `plugins-<provider>-1.json`

Standard header (`collector: "plugins"`, plus `plugin`, `pluginVersion`, `instrumentationVersion`,
`reason`, `complete`, `error`) and a `measurements` object. Providers on the v1 `Provider`
interface (MagicSpells, Voice) keep their free-form `measurements` unchanged. Providers on the
shared probe emit this shape:

```json
{
  "schema": "sneakydiagnostics.probe/1",
  "observedMillis": 60012,
  "sections": [
    { "name": "tick", "calls": 1200, "totalNanos": 0, "selfNanos": 0, "meanNanos": 0, "maxNanos": 0,
      "maxAtMillis": 0, "maxAtTick": 0, "p50Nanos": 0, "p95Nanos": 0, "p99Nanos": 0,
      "histogramLog2": [0] }
  ],
  "slowest": [ { "section": "tick.watching", "nanos": 0, "atMillis": 0, "atTick": 0 } ],
  "counts": { "energy.arrived": 0 },
  "levels": { "placed": { "samples": 0, "mean": 0.0, "min": 0, "max": 0, "last": 0 } },
  "series": { "windowMillis": 1000, "sections": { "tick": { "calls": [0], "nanos": [0] } } },
  "nestingErrors": 0, "omittedKeys": 0, "maxKeys": 256
}
```

- **Timing:** server-thread wall time. `totalNanos` is inclusive (nested sections overlap).
  `selfNanos` excludes time in nested sections and sums correctly across a nesting tree; it is
  exact only for strictly nested sections, and `nestingErrors` counts mismatched ends.
- **Percentiles:** `histogramLog2[k]` counts calls with `floor(log2(nanos)) == k` (k from 0 to 40;
  calls of 0 or 1 ns land in bucket 0, longer ones in the last bucket). `p50/p95/p99Nanos` are the upper bound of the bucket
  containing that rank, so they overestimate by less than 2x.
- **When:** `maxAtMillis` and `atMillis` are capture offsets (the shared monotonic origin) of the call's
  **end**, like the series;
  `atTick` is the server tick number. `slowest` holds the 10 longest calls across all sections.
- **Series:** windows are `[i*windowMillis, (i+1)*windowMillis)` of capture offset, aligned with the
  timeline report's one-second windows. Only the 16 sections with the largest `totalNanos` are
  included; `calls[i]` and `nanos[i]` are for window `i` (a call belongs to the window in which it ends;
  `nanos` is inclusive wall time, like `totalNanos`). Windows with no calls are `0`.
- **Bounds:** at most 256 distinct names per map; extra names add to `omittedKeys` and make the
  provider incomplete. Labels are fixed strings (kinds, spells, machine types), never player
  names, ids or text. Values must satisfy the existing 250,000-value / 16-level report limit.
- **Entity ownership (standard levels):** providers that own entities report `entities.owned`
  (total) and `entities.owned.<bukkit entity type, lowercase>` as levels. Per-type counts only:
  no per-chunk or per-entity data. The entity census reports types in upper case; fold case to join them.
- When no capture is running a probe does no work: no timing, no allocation, no string building.

## `manifest.json`

```json
{
  "schemaVersion": 2,
  "captureId": "...", "captureStartedAt": "...", "requestedDurationSeconds": 60, "updatedAt": "...",
  "server": { "version": "...", "javaVersion": "...", "diagnosticsVersion": "...",
              "plugins": [ { "name": "MagicSpells", "version": "..." } ] },
  "workload": { "atStart": { "players": 34, "worlds": [ { "name": "world", "loadedChunks": 5414, "entities": 22356 } ] },
                "atEnd": null },
  "files": [ { "name": "timeline-1.json.gz", "collector": "timeline", "segment": 1, "bytes": 0,
               "uncompressedBytes": 0, "sha256": "...", "complete": true, "reason": "completed",
               "writtenAt": "..." } ]
}
```

- Rewritten (temp file, then atomic move) on the writer thread after every report file, so it is
  valid at any moment, including after a partial capture.
- `workload` holds counts only: no player names, UUIDs or locations. `atEnd` is sampled when the
  last active collector of the capture finishes and is `null` if that never happens.
- `sha256` is of the file as stored (compressed if compressed).

## Report management

```text
/diagnostics reports list
/diagnostics reports prune keep <n> [confirm]
```

- Permission `sneakydiagnostics.reports` (default op). `list` shows each capture's id, age, file
  count and size, newest first.
- `prune` without `confirm` only lists what would be deleted. With `confirm` it deletes all but the
  newest `n` capture folders (by capture start), never one that is active or has a pending write.
  There is no automatic deletion.

## Compatibility

- v1 `Diagnostics.Provider` registrations keep working unchanged through an adapter and are written
  to a per-provider file with their measurements as provided.
- Existing captures (v1 `plugins-1.json` with embedded providers) stay readable by the analysis tools.
- `complete` changed meaning: in v1 the collector's `complete` was false when any provider was incomplete;
  in v2 it only describes coordination, and coverage is per provider (`incompleteProviders`).
- In the manifest, the index and provider files all have `collector: "plugins"` (tell them apart by name:
  `plugins-1.json` is the index), and `spark-1.json` and `profile.sparkprofile` are listed with
  `collector: "spark"`.
