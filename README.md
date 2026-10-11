# SneakyDiagnostics

Paper 26.2 diagnostics plugin for investigating TPS issues in custom plugins and scripts. Java 25 is required. Collection is off until an operator starts it. The initial collector is the migrated entity census; scheduler, spatial-query, and other probes are future work.

## Build and install

```powershell
.\gradlew.bat build
```

Copy `build/libs/SneakyDiagnostics-<version>.jar` (not the sources JAR) to the server's plugins folder and restart the server. This project does not deploy itself or change production configuration.

## Capture

```text
/diagnostics entities start world
/diagnostics entities status
/diagnostics entities stop
```

Omit the world argument to scan all currently loaded worlds. Permission `sneaky.diagnostics.entities` defaults to operators. `/sneakydiagnostics` is the full command name; `/entitycensus <start [world]|status|stop>` remains available with the legacy `sneaky.entitycensus` permission. A second capture is rejected until collection and report writing finish. Completion logs the absolute report path under `plugins/SneakyDiagnostics/reports/`. Stop writes a partial report.

The first useful capture is during the sustained 11–15 TPS condition. Run it alongside `/ms perf start 60` from the instrumented MagicSpells build, and note the capture times. Repeat the census after one controlled change when player locations and activity are comparable.

## Collected data

- Per entity: type, non-human UUID, world, position, chunk, persistence, ticking-chunk status, visibility default, age, passenger count and vehicle UUID.
- Current tracking-player count and current tracking-human count from Paper's `getTrackedBy()`. Humans are online players without `NPC` metadata. This is a heuristic, not a universal NPC detector.
- Human counts within 32, 64 and 128 blocks horizontally. These are proximity counts, not Paper's visibility algorithm or tracking candidates.
- PDC key names, namespace hints, `NPC` metadata provider names, scoreboard tags. Namespace/provider hints do not prove which plugin created an entity.
- Displays: view-range multiplier, bounding dimensions, interpolation and teleport duration, and item/block material. Display view range is not the effective server tracking range in blocks.
- Groups by world, chunk, entity type and owner hints, sorted by tracking-human subscriptions. Ten entities tracked by three humans contribute 30 subscriptions, not three unique viewers.
- Collection overhead, start/end rolling MSPT, timestamps, skipped chunks, failed reads, and completion reason.

Player names, human UUIDs, PDC values, item metadata and text-display contents are omitted. UUIDs inside arbitrary scoreboard tags are not scrubbed. Treat the report as server diagnostic data.

## Bounds and interpretation

The scanner targets 2 ms per tick, at most 200 examined entities and eight visited chunks per tick. It stops after 120 wall-clock seconds, 100,000 unique entity attempts or 30,000 visited chunks. Paper API calls are not preemptible, so 2 ms is a target rather than a hard maximum. `maxSliceNanos` exposes overruns. The one-time snapshot copy and report dispatch at completion are outside slice timing.

Only loaded chunks with entities already loaded are read. No chunks are loaded or held with tickets. Bukkit state is accessed on the server thread; report grouping and serialization happen on a separate writer thread using detached records. Each world's loaded-chunk list is obtained when that world is reached. New chunks and entities can appear during collection; this is an incremental census, not a simultaneous snapshot. Per-entity observation offsets and skip/error counters expose some of that uncertainty. UUID deduplication avoids counting an entity twice if it moves between chunks.

Label lists are capped at 64 entries and each label at 128 characters, with truncation flagged. Report files are not automatically deleted. On plugin disable an active partial report is queued, and the writer is allowed to finish; an abrupt process exit can still interrupt a write.

Tracking subscriptions are not measurements of tracker CPU time, update frequency, or rejected visibility checks. This plugin identifies candidate groups for a subsequent targeted NMS probe if necessary.

Paper API reference: [entity tracking](https://jd.papermc.io/paper/26.2/org/bukkit/entity/Entity.html#getTrackedBy()).
