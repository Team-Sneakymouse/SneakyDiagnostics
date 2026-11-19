# Report schema v2

The contract for the v2 report rework. Implementation tracks and the analysis tooling build
against this document; change it first when a decision changes. v2 is a clean break: every
report written by this release carries `"schemaVersion": 2` in its header. Tools read v1 and
v2 and tell them apart by that field. The layouts of `entities`, `timeline`, `scheduler`,
`spark` and `connections` reports are unchanged apart from compression and the compatible additions
listed under [Tick identity](#tick-identity), [Scheduler: Ignite slow calls](#scheduler-ignite-slow-calls),
[Scheduler: Ignite listener totals](#scheduler-ignite-listener-totals), [Timeline: spawns](#timeline-spawns) and
[Entities: census budget](#entities-census-budget). [Tick phases](#tick-phases-phases-1json) (`phases-1.json`) is a new
collector file, also a compatible addition, and so is [`packets-1.json`](#packets-1json-tracker-sends) (tracker sends
and the [packet sections](#packets-1json-packet-sections)). Captures without either file did not run that collector;
readers say "not recorded".

## Tick identity

Two server counters exist and they are **not interchangeable**:

- the **tick number** (`ServerTickEndEvent.getTickNumber()`, Paper's `tickCount`): the timeline's `firstTick` and
  `lastTick`;
- the **current tick** (`Bukkit.getCurrentTick()`, `MinecraftServer.currentTick`): shared-probe `atTick`/`maxAtTick`,
  Ignite record `tick`, MagicSpells slow-operation `tick`, and the timeline's `firstCurrentTick`/`lastCurrentTick`
  (compatible addition, 2026-11: the current tick read at the same tick ends).

Paper increments the current tick on every main-loop iteration. While the server pauses because it is empty, the loop
still runs scheduled tasks but returns before the tick number advances or `ServerTickEndEvent` fires, so the current
tick runs ahead by one per paused iteration, permanently. Match records to timeline windows only by
`firstCurrentTick..lastCurrentTick`; timelines written before these fields existed cannot be tick-matched (readers fall
back to end offsets and say so). Missing fields are unknown, never zero.

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
  timeline-1.json[.gz]  entities-<n>.json[.gz]  scheduler-1.json[.gz]  phases-1.json[.gz]  packets-1.json[.gz]
  connections-1.json  spark-1.json  profile.sparkprofile
```

- `<provider>` is the lowercased plugin name with every character outside `[a-z0-9._-]`
  replaced by `_`. If two names collapse to the same slug in one capture, the later ones get
  `~2`, `~3`. The `file` field of the index is authoritative and names the file as stored
  (`.json.gz` when compressed); do not derive names in tools.
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
- The index is written after the provider files, so each `file` is the stored name. A provider file
  that failed to write keeps its planned `.json` name; readers report it missing.
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
  "series": { "windowMillis": 1000, "sections": { "tick": { "calls": [0], "nanos": [0] } },
              "counts": { "energy.arrived": { "windows": [0, 7], "n": [3, 1] } },
              "levels": { "placed": { "from": [0, 5], "to": [3, 9], "values": [4, 6] } },
              "omitted": { "counts": [], "levels": [] } },
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
  `atTick` is the current tick (`Bukkit.getCurrentTick()`, see [Tick identity](#tick-identity)), not the timeline's
  tick number. `slowest` holds the 10 longest calls across all sections.
- **Series:** windows are `[i*windowMillis, (i+1)*windowMillis)` of capture offset, aligned with the
  timeline report's one-second windows. Only the sections with the largest `totalNanos` are
  included: up to 64 (16 before 2026-11), fewer when their series would exceed 150,000 values
  (`floor(150000 / (2 x windows + 3))`, 41 sections at 1,800 windows); readers accept any number; `calls[i]` and `nanos[i]` are for window `i` (a call belongs to the window in which it ends;
  `nanos` is inclusive wall time, like `totalNanos`). Windows with no calls are `0`.
- **Count and level series** (same windows; added later in `probe/1`, so older reports have no `series.counts`,
  `series.levels` or `series.omitted`, and readers treat their absence as "no series", not as zero):
  - `counts.<key>` lists only the windows with a non-zero count: `windows[i]` is a window index and `n[i]` what was
    counted in it. A window not listed counted 0 for that key.
  - `levels.<key>` lists runs of consecutive sampled windows with the same value: windows `from[i]` to `to[i]`
    (inclusive) each had `values[i]` as their last sample. `sampleDue()` is due once per window, so a window in no run
    had **no sample** (the plugin skipped the key, or the server didn't tick in that second): unknown, not zero.
  - Budget: the count and level series together use at most 50,000 values (2 per listed count window, 3 per
    level run). Keys are taken cheapest first, so rare markers (`hunt.started`) always fit and only the busiest
    keys are left out; their names are in `omitted.counts` / `omitted.levels`. Every key in `counts` / `levels`
    is in exactly one of the series map and the omitted list. Leaving a key out does not make the provider
    incomplete.
- **Bounds:** at most 256 distinct names per map; extra names add to `omittedKeys` and make the
  provider incomplete. Labels are fixed strings (kinds, spells, machine types), never player
  names, ids or text. Values must satisfy the existing 250,000-value / 16-level report limit.
- **Entity ownership (standard levels):** providers that own entities report `entities.owned`
  (total) and `entities.owned.<bukkit entity type, lowercase>` as levels. Per-type counts only:
  no per-chunk or per-entity data. The entity census reports types in upper case; fold case to join them.
- When no capture is running a probe does no work: no timing, no allocation, no string building.

## Scheduler: Ignite slow calls

Compatible additions to `scheduler-1.json`'s `ignite` map (bridge protocol stays 1). Companions built before
2026-11 omit all three; readers report them as "not recorded", never as zero.

```json
"slowExecutions": { "thresholdNanos": 1000000, "capacity": 512, "observed": 3, "retained": 3, "evicted": 0,
                    "complete": true, "records": [
  { "event": "execution", "owner": "SneakyKnives", "taskClass": "...", "sync": true,
    "startOffsetMicros": 52310412, "endOffsetMicros": 52638897, "nanos": 328485000,
    "thread": "server", "tick": 246999, "throwing": false, "endedAfterDeadline": false } ] },
"slowListeners": { "...same fields...": 0, "observedInvocations": 1820000, "runningAtStop": 1,
                   "runningAroundStop": 1, "records": [
  { "owner": "SneakyItems", "listenerClass": "...", "eventClass": "org.bukkit.event.inventory.InventoryOpenEvent",
    "priority": "LOWEST", "asyncEvent": false, "startOffsetMicros": 0, "endOffsetMicros": 0, "nanos": 0,
    "thread": "server", "tick": 0, "throwing": false, "endedAfterDeadline": false } ] },
"runningAtStop": { "count": 0, "capacity": 32, "records": [
  { "event": "execution", "owner": "...", "taskClass": "...", "sync": false, "startOffsetMicros": 0,
    "runningNanosAtStop": 0 } ] },
"measuredTicks": { "tick": [246999], "startOffsetMicros": [52310000], "endOffsetMicros": [52361000],
                   "capacity": 2400, "dropped": 0 }
```

- Offsets are microseconds from the shared capture origin; `nanos` is inclusive wall time. Records are ordered by
  start. Scheduler records may carry `caller` (cancellation and deferred-scan events).
- `thread` is `server`, `other` or `unknown`; `tick` (`Bukkit.getCurrentTick()`, matched by the timeline's
  `firstCurrentTick..lastCurrentTick`, never its tick numbers) is present only on the server thread. A missing `tick`
  is unknown, not tick 0.
- Listener `runningAtStop` counts calls begun before the stop that had not ended (it can include calls that ended
  while the stop ran); `runningAroundStop` are those on the stopping thread's own stack (normally the SneakyDiagnostics
  tick-end listener that stops the capture). Calls running on other threads may have been slow, so they make
  `complete` false; enclosing ones do not. A slow call either appears in the list (or `evicted`) or is counted as
  running: completion, the running set and the record are updated atomically with respect to the export.
- Timings of `ServerTickEndEvent` listeners fall outside the timeline's tick duration, which Paper measures before
  dispatching that event; readers report them apart from tick-time percentages.
- `measuredTicks` (parallel lists, one entry per server tick, current-tick identity): the interval Paper measured as
  that tick's duration. Paper reads the duration immediately before dispatching `ServerTickEndEvent`, so the interval
  ends where the tick's first tick-end listener call began and starts `getTickDuration()` earlier (accurate to the
  event's own dispatch, microseconds). It is recorded for every tick, whatever the listeners' durations; tick-end
  listeners, the capture stop and empty-server pauses lie outside it. At most `capacity` (capture seconds x 40)
  entries; `dropped` counts the rest. Ticks without a server tick identity are not recorded. Readers intersect
  server-thread calls with these intervals for an exact covered share; for a window whose intervals are not all
  present (older companion, dropped, capture-boundary tick) they can only remove retained tick-end listener records
  and must label the share an upper bound.
- Each list keeps the `capacity` longest calls of at least `thresholdNanos`. `observed` counts every call over the
  threshold, `evicted = observed - retained`, `complete` is `observed == retained`. Eviction does not affect
  `igniteComplete`. `capacity` is `max(512, 64 x capture seconds)`, at most 16,384 (companions before 2026-11: always
  512); `limits.slowExecutions` / `limits.slowListeners` carry the same value.
- Listener records time one listener's executor call, including nested events; scheduler records include listeners
  their task triggers. Records overlap each other and provider sections: sum only their union per thread.

## Scheduler: Ignite listener totals

Compatible additions to `scheduler-1.json`'s `ignite` map (2026-11, bridge protocol stays 1). Older companions omit
them; readers report "not recorded", never zero.

```json
"listenerTotals": { "keys": 812, "maxKeys": 4096, "overflow": { "calls": 0, "totalNanos": 0 },
                    "nestingOverflow": 0, "maxDepth": 64, "complete": true, "rows": [
  { "owner": "SneakyItems", "listenerClass": "...", "eventClass": "org.bukkit.event.inventory.InventoryOpenEvent",
    "priority": "LOWEST", "asyncEvent": false, "calls": 5120, "totalNanos": 0, "selfNanos": 0, "maxNanos": 0,
    "otherThreadCalls": 0, "otherThreadNanos": 0, "histogramLog2": [0] } ],
  "series": { "windowMillis": 1000, "maxOwners": 256, "omittedOwners": 0,
              "owners": { "SneakyItems": { "totalNanos": [0], "selfNanos": [0] } } } },
"groups": [ { "...existing fields...": 0, "histogramLog2": [0] } ]
```

- **Rows:** one per owner plugin x listener class x event class (the class of the event instance) x priority x async
  flag, for **every** listener call made while recording, ordered by `selfNanos` descending. `calls`, `totalNanos`
  (inclusive wall time, nested events included), `maxNanos` and `histogramLog2` cover all threads. `selfNanos` is
  server-thread wall time minus the nested listener calls that ran inside the call (a depth stack; scheduler work or
  plugin code not reached through an event is not subtracted), so self times add up across rows. Calls on other
  threads have no self time and are also counted in `otherThreadCalls` / `otherThreadNanos`.
- `histogramLog2` uses the shared probe's buckets (41 buckets, `floor(log2(nanos))`, see the probe/1 Percentiles bullet).
- **Series:** `owners.<plugin>.totalNanos[i]` / `selfNanos[i]` are that owner's server-thread listener time in window
  `i` (`[i*1000, (i+1)*1000)` ms of capture offset, by call end, aligned with the timeline). Owners with no time are
  left out. Windows run to the stop (calls ending after the deadline count in the last window).
- **Bounds:** at most 4,096 keys; calls of further keys are counted only in `overflow`. At most 256 owners have a
  series (`omittedOwners` counts the rest; their rows are kept). Nesting deeper than 64 listener calls is counted in
  `nestingOverflow` (the parent's self time then includes the deeper call). `complete` is false when any of the three
  is non-zero, and then `igniteComplete` is false too. Labels are capped at 192 characters.
- **Accounting:** `slowListeners.observedInvocations = sum(rows.calls) + overflow.calls + slowListeners.runningAtStop`,
  exact when the stop runs on the server thread and no other-thread call races it.
- `ServerTickEndEvent` listeners are included; their time lies outside the timeline's tick durations.
- **Groups** (all events) gain `histogramLog2`: completed scopes by the same buckets, so distributions do not depend
  on the capped slow-call list. Older groups have none (unknown).

## Timeline: spawns

Compatible addition to `timeline-1.json` (2026-11, public Paper API, no companion needed). Older timelines omit it;
readers report "not recorded".

```json
"spawns": { "windowMillis": 1000, "observed": 1840, "keys": 12, "maxKeys": 512, "overflow": 0,
            "droppedOffThread": 0, "attributedReasons": ["CUSTOM", "DEFAULT"], "complete": true, "rows": [
  { "reason": "CUSTOM", "entityType": "ZOMBIE", "plugin": "SneakyShrine", "total": 300, "cancelled": 2,
    "windows": [0, 4], "n": [12, 3] } ] },
"spawnCollectionNanos": 400000
```

- Every `EntitySpawnEvent` (including `CreatureSpawnEvent`) seen at MONITOR priority while the timeline records, keyed
  by spawn reason (`CreatureSpawnEvent.getSpawnReason()`, else `Entity.getEntitySpawnReason()`), entity type and, for
  the `attributedReasons`, the spawning plugin. `total` counts spawns no listener cancelled; `cancelled` the others.
- `windows[i]` / `n[i]`: the one-second windows (capture offset, like the timeline's) with non-cancelled spawns and how
  many; windows not listed had none. Cancelled spawns have no windows.
- `plugin` (CUSTOM and DEFAULT spawns only) is the first class on the spawning stack loaded by a plugin class loader
  other than SneakyDiagnostics', `<unknown>` when none: a hint, not proof (a library or callback can stand between).
- **Bounds:** at most 512 keys; further spawns count only in `observed` and `overflow`. Events off the server thread
  are counted in `droppedOffThread` and not classified. `complete` is false when either is non-zero. Labels are capped at
  128 characters. Rows are ordered by `total` descending.
- `spawnCollectionNanos`: the listener's own server-thread time, outside `totalCollectionNanos`.

## Entities: census budget

Compatible additions to `entities-<n>.json` (2026-11):

- `budgetMillisPerSecond` (50): slice time the census may use per wall second of its segment, spread over the ticks
  expected in the rest of that second (from the last tick interval, never assumed shorter than 50 ms). A single slice
  targets at most 10 ms; Paper calls are not preemptible, so `maxSliceNanos` can exceed it.
- `budgetExhaustedTicks`: ticks on which the second's budget was already spent, so no slice ran.
- `visitOrder` (`"players-first"`): within each world, loaded chunks are visited nearest to a human first (horizontal
  distance in chunks to the nearest one at the time the world is reached); chunks in a world without humans keep their
  order. Worlds keep their order.
- `limits.targetSliceMillis`, `limits.entitiesPerTick` and `limits.chunksPerTick` keep their meaning as per-slice
  targets and caps; they now report the values at the largest slice (10 ms, 1,000 entities, 40 chunks: caps scale
  with the slice target at 100 entities and 4 chunks per millisecond). `limits.budgetMillisPerSecond` repeats the budget.
- Census files without these fields used a fixed 2 ms per tick and world order.

## Ignite `ServerProbe` MBean (protocol 1)

The companion's second local MBean, `com.teamsneakymouse.sneakydiagnostics:type=ServerProbe`, controls the
server-internal probes behind `phases-1.json` and `packets-1.json`. It is separate from the scheduler bridge
(`type=SchedulerProbe`, protocol 1, unchanged). Only JDK values cross it:

| member | meaning |
|---|---|
| `ProtocolVersion` | `1` |
| `Status` | every probe's state, `"; "`-separated, in registry order: `phases available, idle; tracker recording <captureId>; packets recording <captureId>` |
| `start(String probe, String captureId, long startedNanos, int seconds)` | starts that probe; server thread |
| `stop(String probe, String captureId)` → `Map` | stops that probe and returns its result; server thread |

- **Probes by name:** `phases` (tick phases), `tracker` (entity-tracker sends) and `packets` (packet classes, causes,
  tracker owners, inbound; 2026-11). Each probe owns its own capture, so several probes can record the same capture ID
  at once (`/diagnostics all` starts all three; the `packets` collector starts `tracker` and `packets`) and each can
  run alone. A second `start` of the same probe fails until it is stopped; `stop` checks the capture ID per probe. An
  unknown name fails with `IllegalArgumentException` ("Unknown server probe '<name>'; known: phases, tracker,
  packets"); JMX wraps it in a `RuntimeMBeanException`.
- **Results:** every result map holds `captureId`. `phases` returns the `phases-1.json` fields at top level (with
  `schema: "sneakydiagnostics.phases/1"`). `tracker` returns `{captureId, tracker}`: its `packets-1.json` section under
  its name. `packets` returns `{captureId, outbound, causes, trackerCauses, inbound}`, or `{captureId, error}` when it
  was stopped off the server thread (counts discarded).
- **Availability:** a plugin bridge treats the probe as available when the MBean is registered, `ProtocolVersion` is 1
  and `Status` lists the probe's name. A probe added later (as `packets` was) joins the registry without a protocol
  change; a bridge sees an older companion as "without the <name> probe".
- **Protocol stays 1:** the named start/stop replaced the single-probe signature from the tick-phases and tracker
  branches before anything using this MBean was released or deployed, so no reader or companion with the older shape
  exists.

## Tick phases: `phases-1.json`

New collector file (2026-11, compatible addition; `/diagnostics phases start [seconds]`, included in `all`). It needs
the Ignite companion's [`ServerProbe` MBean](#ignite-serverprobe-mbean-protocol-1) (probe `phases`). Captures without the file did not run the collector: readers
report tick phases as "not recorded", never as zero. Standard header plus:

```json
{ "igniteAvailability": "Ignite phases available, idle", "available": true, "schema": "sneakydiagnostics.phases/1",
  "phasesComplete": true, "errors": 0, "complete": true,
  "ticks": { "tick": [246999], "startOffsetMicros": [52310000], "measuredMicros": [51000],
             "count": 1, "capacity": 2400, "dropped": 0 },
  "phases": { "tick-start-tasks": [1200], "scheduler": [9000], "diagnostics": [2100], "connections": [4000],
              "level.world.entities": [21000], "level.world.chunks": [2500], "level.world.tracker": [6000],
              "level.world.block-entities": [300], "level.world.block-events": [10],
              "level.world.spawning.state": [1500], "level.world.other": [800] },
  "other": [2590],
  "outside": { "tick-end": [700], "diagnostics": [350] },
  "totals": { "scheduler": { "totalNanos": 9000400, "calls": 1, "ticksWithTime": 1, "maxTickMicros": 9000,
                             "insideMeasuredTick": true },
              "outside.tick-end": { "...": 0 } },
  "measured": { "measuredTicks": 1, "unmeasuredTicks": 0, "measuredNanos": 51000000, "otherMicros": 2590,
                "negativeOtherTicks": 0 },
  "worlds": ["world"], "omittedWorlds": [], "omittedWorldCalls": 0, "omittedSeries": [],
  "spawning": { "world": { "stateCalls": 1, "spawnChunkCalls": 0, "spawnChunkCallsThatSpawned": 0,
                           "naturalSpawnAttempts": 0, "customSpawnerCalls": 0 } },
  "depthOverflow": 0, "nestingErrors": 0, "spanningTicks": 0, "lastTickPartial": false,
  "limits": { "ticksPerSecond": 40, "worlds": 64, "depth": 32, "values": 8000000 } }
```

- **Rows:** one per server tick, keyed by the current tick (`Bukkit.getCurrentTick()`, matched by the timeline's
  `firstCurrentTick..lastCurrentTick`, see [Tick identity](#tick-identity)). Recording starts with the first tick after
  the start and opens no row after the deadline. `startOffsetMicros` is the row's start (capture offset). Every list in
  `phases`, `outside` and `other` is aligned with `ticks.tick`.
- **Phase values** are exclusive server-thread wall time in whole microseconds (floor): a nested phase is subtracted
  from its parent, so the phases of a tick add up. Phases: `tick-start-tasks` (inbound packets and queued tasks at tick
  start), `scheduler` (Bukkit main-thread heartbeat), `connections` (player connection ticks, which include player
  entity ticks), `diagnostics` (SneakyDiagnostics' own listener and scheduler-task calls: census slices, scheduler
  snapshots, timeline sampling), and per world `level.<world>.<kind>` with kinds `entities`, `chunks` (minus tracker and
  spawning), `tracker` (Moonrise entity tracker), `block-entities`, `block-events`, `spawning.state`
  (`NaturalSpawner.createState`, mob-cap bookkeeping), `spawning.spawn` (`NaturalSpawner.spawnForChunk`, actual spawn
  attempts), `spawning.custom` (phantoms, patrols, cats, wandering trader, sieges) and `other` (the rest of that world's
  tick). Hook sites are listed in the companion documentation. World names may contain dots; split names with
  `worlds`. A phase absent from `phases`/`outside` had no time in any tick (the hooks are structural, so absence is
  zero, not missing data); a phase present has a value for every row (0 where it did not run).
- **Measured tick and `other`:** `measuredMicros[i]` is Paper's tick duration (`ServerTickEndEvent.getTickDuration()`,
  read at the dispatch; the same interval as the scheduler's `measuredTicks`), `null` where Paper measured none
  (empty-server pause, or the stop interrupted the tick: then `lastTickPartial` is true). `other[i] =
  measuredMicros[i] - sum(phases[*][i])`, exactly, and `null` where the measured duration is unknown: readers must
  label `other` unknown there, not zero. `other` is the unhooked rest of the tick (tick bookkeeping, players list,
  chunk sending, console commands, autosave, Paper/Folia schedulers). It can only be negative through clock skew;
  `measured.negativeOtherTicks` counts such ticks.
- **`outside`:** time after the measured interval: `tick-end` (the `ServerTickEndEvent` dispatch, its listeners) and
  `diagnostics` (SneakyDiagnostics work during that dispatch or between ticks). It is not part of the tick duration.
- **`totals`:** per phase, over every committed tick including dropped ones: `totalNanos` (exact), `calls` (wrapped
  calls), `ticksWithTime`, `maxTickMicros`; keys of outside phases carry the prefix `outside.`. `measured` sums the
  measured ticks (`measuredNanos`, `otherMicros`) and counts ticks without a measured duration.
- **`spawning`:** per world: `stateCalls` (`createState`), `spawnChunkCalls` (`spawnForChunk`),
  `spawnChunkCallsThatSpawned` (calls that tried to add at least one entity), `naturalSpawnAttempts` (entities
  `NaturalSpawner.spawnCategoryForPosition` tried to add, before spawn events can cancel them) and `customSpawnerCalls`.
  Natural spawning is disabled on LoM, so readers report any spawning time as a finding and say which part ran.
- **Bounds:** at most `capacity` (capture seconds x 40) rows; `dropped` counts the rest. At most 64 worlds: later
  worlds' time stays in the enclosing phase, their names are in `omittedWorlds` and their calls in
  `omittedWorldCalls`. Nesting deeper than 32 phases is counted in `depthOverflow` (the parent keeps the time).
  At most 8,000,000 stored values (columns x capacity): later phases keep their totals but have no per-tick values and
  are listed in `omittedSeries`. `nestingErrors` (mismatched frames) and `spanningTicks` (a frame open across a tick
  boundary) should be 0. Any of these makes `phasesComplete` false.
- **Without the companion** (or with an older one): `available` is false, `unavailableReason` says why, `complete` is
  false and no phase fields are present. `igniteError` is set when the probe failed at stop. `complete` is true only
  for a `completed` capture with `phasesComplete` and no `errors`.

## `packets-1.json` (tracker sends)

Collector `packets` (`/diagnostics packets start [seconds]`, included in `all`, permission
`sneakydiagnostics.packets`). It holds the entity tracker's send time (this section, probe `tracker`) and the
[packet sections](#packets-1json-packet-sections) (probe `packets`), both measured by the Ignite companion's
[`ServerProbe` MBean](#ignite-serverprobe-mbean-protocol-1) under the report's capture ID. Readers report a missing
section as "not recorded" (unmeasured), never zero.

Standard header plus `startedAt`, `igniteAvailability` (the tracker probe's availability), `packetsAvailability`
(2026-11, the packets probe's), `errors`, `notes`, optionally `igniteError` (tracker stop failed) and `packetsError`
(2026-11, packets stop failed), and:

```json
"tracker": {
  "observedMillis": 60000, "calls": 1200000, "totalNanos": 0, "metadataCalls": 0, "metadataNanos": 0,
  "entities": 37000, "offThreadCalls": 0, "lateCalls": 0, "labelErrors": 0, "maxGroups": 512,
  "overflowEntities": 0, "complete": true,
  "groups": [ { "entityType": "ITEM_DISPLAY", "owner": "pdc:sneakygrounditems", "entities": 10468, "calls": 0,
                "totalNanos": 0, "metadataCalls": 0, "metadataNanos": 0, "maxNanos": 0, "histogramLog2": [0] } ],
  "series": { "windowMillis": 1000, "calls": [0], "nanos": [0],
              "groups": [ { "entityType": "ITEM_DISPLAY", "owner": "pdc:sneakygrounditems", "nanos": [0] } ] }
}
```

- **What is timed:** server-thread wall time inside `ServerEntity.sendChanges`, the tracker's per-tick send for one
  tracked entity (movement, rotation, passengers, velocity, item-frame map updates and changed metadata).
  `metadataNanos` is the nested `sendDirtyEntityData` part and is included in `totalNanos`. The player checks
  (`TrackedEntity.updatePlayer`) and the tracker's loop over entities are **not** included. Each send's time includes
  the probe's own two clock reads.
- **Groups:** one per Bukkit entity type (upper case, like the census) and owner hint, resolved once per entity per
  capture and cached. Owner hint: `(player)` for a connected player (exactly `ServerPlayer`), `class:<name>` for a
  non-Minecraft entity class (plugin NPCs such as Citizens), otherwise the entity's sorted PDC namespaces
  (`pdc:a,pdc:b`, as in the census), else `(none)`. Hints are candidates, not proven ownership; a PDC change after the
  first send is not reflected. `entities` counts distinct tracker entries seen; `groups` is sorted by `totalNanos`.
- **Bounds:** at most 512 groups; entities beyond that go to the `(overflow)` group and are counted in
  `overflowEntities`. Labels are capped at 192 characters. A label that cannot be read becomes `(unknown)` and counts
  in `labelErrors`.
- **Coverage:** `complete` (in `tracker`) is false when `overflowEntities`, `labelErrors` or `offThreadCalls` is
  non-zero. `offThreadCalls` are sends seen on another thread: counted (approximately), not timed. `lateCalls` ended
  after the requested deadline and are left out without affecting `complete`. The report's own `complete` also needs
  reason `completed`, no `igniteError` or `packetsError`, and (2026-11) all four packet sections present and complete:
  a report from a companion with only the tracker probe is incomplete because its packets are unmeasured.
- **Histogram and series:** `histogramLog2` uses the shared-probe buckets. Series windows are
  `[i*1000, (i+1)*1000)` ms of capture offset by a send's **end**, aligned with the timeline; `calls`/`nanos` cover
  all groups, `series.groups` the 16 groups with the largest `totalNanos` (a group with no sends in any window is left
  out). Windows with no sends are 0.
- **No `tracker` key** (companion missing, older than this probe, or failed): tracker send time is unmeasured, not
  zero, and the report is incomplete.

## `packets-1.json` (packet sections)

Compatible addition (2026-11), written by the same collector from the companion's `packets` probe: `outbound`,
`causes`, `trackerCauses` and `inbound`. Counts are per packet class, cause or entity type and owner only: no player
identities, no payloads, no bytes on the wire. Hook sites are listed in the
[companion documentation](../ignite/README.md#packets).

```json
"outbound": {
  "observedMillis": 60000, "sends": 2400000, "serverThreadSends": 2390000, "otherThreadSends": 10000,
  "serverThreadNanos": 0, "classNanos": 0, "nestedSends": 0, "otherThreadNanos": 0, "bundles": 4000,
  "bundledPackets": 12000, "lateSends": 0, "maxClasses": 512, "classOverflow": false, "complete": true,
  "classes": [ { "packetClass": "net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket", "sends": 0,
                 "serverThreadSends": 0, "otherThreadSends": 0, "totalNanos": 0, "maxNanos": 0,
                 "otherThreadNanos": 0, "bundled": 0, "histogramLog2": [0] } ],
  "series": { "windowMillis": 1000, "sends": [0], "nanos": [0],
              "classes": [ { "packetClass": "...", "sends": [0], "nanos": [0] } ] } },
"causes": {
  "maxDepth": 32, "depthOverflow": 0, "nestingErrors": 0, "complete": true,
  "rows": [ { "cause": "tracker.metadata", "packets": 0, "broadcasts": 0, "totalNanos": 0, "maxNanos": 0 },
            { "cause": "other", "packets": 0, "totalNanos": 0, "maxNanos": 0 } ],
  "series": { "windowMillis": 1000, "causes": [ { "cause": "tracker.metadata", "packets": [0], "nanos": [0] } ] } },
"trackerCauses": {
  "entities": 0, "maxGroups": 512, "overflowEntities": 0, "labelErrors": 0, "spawnLookups": 0, "spawnsAttributed": 0,
  "spawnLookupNanos": 0, "passThroughFrames": 0, "complete": true,
  "groups": [ { "entityType": "ITEM_DISPLAY", "owner": "plugin:SneakyWartime", "entities": 0, "packets": 0,
                "broadcasts": 0, "totalNanos": 0,
                "causes": { "tracker.update": { "packets": 0, "broadcasts": 0, "totalNanos": 0 } } } ] },
"inbound": {
  "observedMillis": 60000, "packets": 0, "totalNanos": 0, "betweenTicksPackets": 0, "betweenTicksNanos": 0,
  "offThreadPackets": 0, "latePackets": 0, "classOverflow": false, "complete": true,
  "classes": [ { "packetClass": "net.minecraft.network.protocol.game.ServerboundUseItemPacket", "packets": 0,
                 "totalNanos": 0, "maxNanos": 0, "betweenTicksPackets": 0, "betweenTicksNanos": 0,
                 "histogramLog2": [0] } ],
  "series": { "windowMillis": 1000, "packets": [0], "nanos": [0],
              "classes": [ { "packetClass": "...", "packets": [0], "nanos": [0] } ] } }
```

- **Outbound** counts every `Connection.send` call (all overloads reach the wrapped one): `sends` =
  `serverThreadSends` + `otherThreadSends`. `totalNanos` per class is **inclusive server-thread** wall time inside
  `send`: ProtocolLib's proxy, the Netty hand-off or queueing happen below it, so this is the cost profilers attribute
  to sending. Each value includes about one of the probe's clock reads. `serverThreadNanos` sums the outermost sends
  only, so it is non-overlapping; `classNanos` sums the per-class times and exceeds it only when a send happens inside
  another send (`nestedSends`, e.g. a packet listener sending). Other threads are timed apart (`otherThreadNanos`,
  per class `otherThreadNanos`). A bundle is one send of its own class; its sub-packets add to their class's `bundled`
  (and `bundledPackets`), not to `sends`. Paper's queued "extra packets" and the login hand-off are sent below or around
  `send` and are not separate sends. Classes are full Mojang class names, sorted by `totalNanos`.
- **Causes** attribute each server-thread send to the innermost wrapped call site that was running: `tracker.update`
  (`ServerEntity.sendChanges` outside its metadata part: movement, rotation, passengers, velocity, map updates),
  `tracker.metadata` (`sendDirtyEntityData`), `tracker.spawn` (`addPairing`), `tracker.despawn` (`removePairing`),
  `entity.event` (equipment changes, swings, entity status and damage broadcasts), `broadcast` (`PlayerList`
  broadcasts: positional sounds and level events, `broadcastAll`, system and chat messages), `particles`, `chunks`
  (chunk send and unload), else `other`. Every cause has a row. `broadcasts` counts calls of the cause (re-entering the
  cause already on top is not counted again), so fan-out = `packets` / `broadcasts`; `other` has no `broadcasts`. A
  cause can have calls and no packets (nobody was watching). `totalNanos` is inclusive send time as in `outbound`;
  cause rows sum to `serverThreadSends` and `classNanos` (other-thread sends have no cause). `depthOverflow` (stack deeper than 32) and `nestingErrors`
  make `complete` false.
- **Tracker causes** split the four tracker causes by entity type and owner: `owner` is `plugin:<name>` for an entity
  whose spawning plugin is known (the first Paper plugin-classloader frame on the stack at `ServerLevel.addFreshEntity`,
  skipping plugin classes that implement `LevelCallback`, recorded only while a packets capture runs and kept on the
  entity), else the tracker section's owner hint (`(player)`, `class:<name>`, PDC namespaces, `(none)`). It is a
  hint, not proven ownership. `entities` counts distinct tracker entries seen; `causes` holds only causes with calls or
  packets. `spawnLookups`/`spawnsAttributed`/`spawnLookupNanos` describe the stack walks (their time is server-thread
  time inside `addFreshEntity`, not in any packet number), `passThroughFrames` the skipped frames. Bounds as in the
  tracker section (512 groups, `overflowEntities`, `labelErrors`; either makes `complete` false).
- **Inbound** counts packets handled on the server thread by `PacketProcessor$ListenerAndPacket.handle`, with
  inclusive time (plugin events fired by the packet and packets sent in response are inside it). `betweenTicks*` is the
  part handled from `MinecraftServer.pollTask` while the server waited for the next tick; the rest ran in
  `runAllTasksAtTickStart` (the `tick-start-tasks` phase). `offThreadPackets` (handled on another thread, counted
  approximately, not timed) makes `complete` false.
- **Series:** windows `[i*1000, (i+1)*1000)` ms of capture offset by the call's **end**, aligned with the timeline;
  `outbound.series` has totals and the 32 classes with the largest `totalNanos` (classes without server-thread sends
  are left out), `causes.series` every cause, `inbound.series` every inbound class. Windows with no calls are 0.
- **Bounds:** at most 512 packet classes per server run (the slot table is global); further classes go to an
  `(overflow)` row and set `classOverflow`, which makes that section incomplete. Labels are capped at 192 characters.
  Calls ending after the deadline are counted in `lateSends`/`latePackets` and left out.
- **Relation to other sections:** outbound and inbound times are inclusive and overlap every tick phase; never add them
  to phases. Sends attributed to `tracker.update` + `tracker.metadata` are part of the tracker section's `totalNanos`
  (`sendChanges`, inclusive), which is part of the exclusive `level.<world>.tracker` phase; `tracker.spawn`/`despawn`
  sends during the tracker tick are in that phase but not in the tracker section.
- **No packet sections** (companion missing, older than the packets probe, or failed: then `packetsError`): packet
  counts are unmeasured, not zero.

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
- Writers before 2026-11 wrote the index first and listed a compressed provider file under its
  uncompressed name (`plugins-magicspells-1.json` for `plugins-magicspells-1.json.gz`). The analysis
  tools fall back to the `.json.gz` file in that case, with a warning.
- In the manifest, the index and provider files all have `collector: "plugins"` (tell them apart by name:
  `plugins-1.json` is the index), and `spark-1.json` and `profile.sparkprofile` are listed with
  `collector: "spark"`.
