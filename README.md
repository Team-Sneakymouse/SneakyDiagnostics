# SneakyDiagnostics

Paper 26.2 diagnostics plugin for investigating TPS issues in custom plugins and scripts. Java 25 is required. Collection is manual. Available collectors cover entities, the tick timeline, and public scheduler state.

## Build and install

```powershell
.\gradlew.bat build
```

Copy `plugin/build/libs/SneakyDiagnostics-<version>.jar` (not the sources JAR) to the server's `plugins/` folder and restart the server. The plugin works independently of Ignite. This project does not deploy itself or change production configuration.

The optional companion builds as `ignite/build/libs/SneakyDiagnostics-Ignite-<version>.jar`, intended for Ignite's `mods/` directory. It currently contains discovery metadata only and has no active probes, so installing it provides no additional measurements yet.

## Repository layout

```text
plugin/                   Paper plugin, collectors, commands, and existing tests
ignite/                   Optional Ignite companion, future Mixins and hook tests
build.gradle.kts          Shared version, Java toolchain, publishing, and artifact checks
settings.gradle.kts       Module registration
.github/workflows/        One build/publishing workflow for both artifacts
```

Root `build`, `check`, and `clean` cover both modules. Use `:plugin:build` or `:ignite:build` for a module-specific build. Root checks verify that the two JARs have matching versions and separate plugin/mod descriptors. No shared bridge module is introduced until the first hook defines its interface and classloader ownership.

## Capture

```text
/diagnostics entities start world
/diagnostics entities status
/diagnostics entities stop
/diagnostics entities resume 120
/diagnostics timeline start 60
/diagnostics scheduler start 60
/diagnostics all start 60 world
/diagnostics all status
/diagnostics all stop
```

`/sneakydiagnostics` is the full command name. The individual collectors accept `start`, `status`, and `stop`; entities also accepts `resume`. Durations are wall-clock seconds, from 1 to 1800. Entity captures default to 120 seconds; other captures default to 60 seconds.

| Command | Start options | Permission |
| --- | --- | --- |
| `/diagnostics entities` | `start [world] [seconds]` or `start [seconds]` | `sneakydiagnostics.entities` |
| `/diagnostics timeline` | `start [seconds]` | `sneakydiagnostics.timeline` |
| `/diagnostics scheduler` | `start [seconds]` | `sneakydiagnostics.scheduler` |
| `/diagnostics all` | `start [seconds] [world]` | `sneakydiagnostics.all` |

All permissions default to operators. `sneakydiagnostics.all` includes the three individual permissions. `/entitycensus <start [world] [seconds]|resume [seconds]|status|stop>` remains available with the legacy `sneaky.entitycensus` permission. Omit the world to scan all currently loaded worlds; a world filter restricts only the entity collector. Numeric single arguments to entity start are interpreted as durations; specify a duration after a numeric world name to select it explicitly.

`all start` validates every collector before starting, and shares a capture ID, timestamp, and monotonic clock across all reports. It rejects busy collectors, including pending report writes, and rolls back partial starts. Individual captures can overlap independently; starting or resuming another capture is blocked while a coordinated capture is still active. `all stop` stops all active collectors, including independently started ones. A census may finish before the requested time if it reaches the end of its traversal; timeline and scheduler collection continue for their full window.

Completion logs absolute paths under `plugins/SneakyDiagnostics/reports/<capture-id>/`. Each collector writes its own JSON file, such as `entities-1.json`, `timeline-1.json`, or `scheduler-1.json`. Stop writes partial reports. Files have the shared capture identity and offsets as well as their actual completion reason and collector-specific coverage information.

An unfinished census can be continued with `/diagnostics entities resume [seconds]` after its previous report finishes writing. Resume continues the same cursor and deduplication set, writes `entities-2.json` and subsequent segments under the same capture ID, and includes pauses in observation offsets. Entities, groups, and sections in each file describe that segment; coverage counters and unique attempts are cumulative. Combine all segments when analyzing the whole census. Starting a new census discards the paused cursor. Resume state exists only in memory and is discarded on plugin disable/restart.

The first useful capture is during the sustained 11–15 TPS condition. Run it alongside `/ms perf start 60` from the instrumented MagicSpells build, and note the capture times. Repeat the census after one controlled change when player locations and activity are comparable.

## Publishing and development

The public repository is [Team-Sneakymouse/SneakyDiagnostics](https://github.com/Team-Sneakymouse/SneakyDiagnostics).
Pushes to `main` and manual runs of the `Publish Maven` workflow build and check both modules before publishing both artifact sets (JAR, sources JAR, and POM) to `https://maven.sneakyrp.com/releases`. Each remote publication depends on the repository's complete checks.

Maven coordinates are `io.github.team-sneakymouse:SneakyDiagnostics:<version>` for the plugin and `io.github.team-sneakymouse:SneakyDiagnostics-Ignite:<version>` for the companion. The plugin's existing coordinates are preserved. Both use the same version derived from the commit date in UTC and its 12-character hash: `yyyy.MM.dd-<hash>`. A committed Git checkout is required to build.

CI follows SneakyDiscordLink's Infisical OIDC setup. The repository must have access to `INFISICAL_IDENTITY_ID` and `INFISICAL_DOMAIN` GitHub Actions secrets, and the identity must authorize this repository. Infisical project `lords-of-minecraft`, environment `prod`, path `/Maven` must provide `MAVEN_USERNAME` and `MAVEN_PASSWORD`.

For publishing from a local checkout, supply Gradle properties `sneakyrpUsername` and `sneakyrpPassword` outside the repository (for example in the user Gradle properties file), then run `publishAll`. The original `publishMavenPublicationToSneakyrpRepository` command remains an alias that publishes both artifacts. Never commit credentials. Root `publishToMavenLocal` can verify both publications without remote credentials.

`SneakyDiagnosticsPlugin` owns lifecycle and command routing. The `capture` package validates coordinated starts, shares capture identity and time, and serializes detached reports off the server thread. `entity`, `timeline`, and `scheduler` own their collection state. There is no automatic capture, NMS hook, Java agent, or reflection into scheduler internals.

The original entity fields, chunk groups, and `entities-*.json` filename pattern are preserved; capture identity, coverage, and sections are additive. Files now live in capture subdirectories; resumed files contain separate segments. Update collection/analysis scripts to discover those directories and combine segments rather than assuming one file is the entire census. Remove the old `SneakyEntityCensus` JAR when installing this plugin to avoid duplicate `/entitycensus` registrations.

## Entity census fields

- Per entity: type, non-human UUID, world, position, chunk, persistence, ticking-chunk status, visibility default, age, passenger count and vehicle UUID.
- Current tracking-player count and current tracking-human count from Paper's `getTrackedBy()`. Humans are online players without `NPC` metadata. This is a heuristic, not a universal NPC detector.
- Human counts within 32, 64 and 128 blocks horizontally. These are proximity counts, not Paper's visibility algorithm or tracking candidates.
- PDC key names, namespace hints, `NPC` metadata provider names, scoreboard tags. Namespace/provider hints do not prove which plugin created an entity.
- Displays: view-range multiplier, bounding dimensions, interpolation and teleport duration, and item/block material. Display view range is not the effective server tracking range in blocks.
- Groups by world, chunk, entity type and owner hints, sorted by tracking-human subscriptions. Ten entities tracked by three humans contribute 30 subscriptions, not three unique viewers.
- Per-section counts by world, chunk, vertical section (`floor(y / 16)`) and entity type, sorted by entity density.
- Per-world snapshot availability, loaded chunks at snapshot time, visited/completed/skipped/unvisited/partial chunks, and entity attempt/sample counts. `snapshotChunks=-1` means that world has not been reached. An unavailable world cannot produce a complete census.
- Collection overhead, start/end rolling MSPT, timestamps, skipped chunks, failed reads, and completion reason.

Player names, human UUIDs, PDC values, item metadata and text-display contents are omitted. UUIDs inside arbitrary scoreboard tags are not scrubbed. Treat the report as server diagnostic data.

## Bounds and interpretation

The scanner targets 2 ms per tick, at most 200 examined entities and eight visited chunks per tick. Each segment stops at its requested wall-clock duration, 100,000 unique entity attempts or 30,000 visited chunks; the traversal can then be resumed. A census ends permanently at 500,000 unique attempts across all segments. Paper calls are not preemptible, so 2 ms is a target rather than a hard maximum. `maxSliceNanos` exposes overruns. The one-time snapshot copy and report dispatch at completion are outside slice timing.

Only loaded chunks with entities already loaded are read. No chunks are loaded or held with tickets. Bukkit state is accessed on the server thread; report grouping and serialization happen on a separate writer thread using detached records. Each world's loaded-chunk list is obtained when that world is reached. New chunks and entities can appear during collection; this is an incremental census, not a simultaneous snapshot. Resume keeps references to the pending chunk/entity arrays and up to 500,000 deduplication UUIDs. It rechecks chunk/world availability and entity validity; it does not revisit entities arriving in chunks already processed. Per-entity observation offsets and skip/error counters expose some of that uncertainty. UUID deduplication avoids counting an entity twice if it moves between chunks or between segments.

Label lists are capped at 64 entries and each label at 128 characters, with truncation flagged. Report files are not automatically deleted. On plugin disable an active partial report is queued, and the writer is allowed to finish; an abrupt process exit can still interrupt a write.

Tracking subscriptions are not measurements of tracker CPU time, update frequency, or rejected visibility checks. This plugin identifies candidate groups for a subsequent targeted NMS probe if necessary.

Paper API reference: [entity tracking](https://jd.papermc.io/paper/26.2/org/bukkit/entity/Entity.html#getTrackedBy()).

## Tick timeline

Paper tick-end events are grouped into one-second windows using the shared capture clock. Each window reports observed tick count, mean/min/max duration, counts over 50/100 ms, and first/last server tick numbers. Empty seconds remain gaps rather than invented zero-cost ticks. Offsets describe tick-end observations; Paper's duration excludes remaining tick-end listeners and idle sleep. These are server-wide measurements, not per-plugin CPU timings. Handler collection overhead is recorded separately; report dispatch is outside that timing.

## Scheduler snapshots

At most once per wall-clock second at tick end, capture pending sync/async tasks by owning plugin and scheduler task implementation class, cancelled flags, and active async worker counts by plugin. Task implementation classes are scheduler wrappers: the runnable class is unavailable through the public interface. Worker thread names and task IDs are not written.

New/missing task IDs between complete snapshots describe observed churn. They are not exact creation, execution, or cancellation rates, and short-lived tasks between snapshots are missed. Async state can also change during a read. Diagnostics' own pending census task is included. Grouping is capped at 50,000 examined tasks, 5,000 workers, and 512 groups per snapshot; truncation and overflow counts are explicit, and truncated task-ID sets cannot produce churn comparisons. The Bukkit calls copy their full lists before these caps apply, so snapshot overhead includes potentially expensive non-preemptible list copies.

## Deeper probes

The next measurements are exact task creation/execution/cancellation attribution, spatial-query caller/radius/candidate counts, altered-block history lifecycle, and separate undo/particle timings. Bounded changes in MagicSpells and SneakyConfigurableVoice can supply logical spell/cast/lifecycle context. Exact server-internal candidate counts and deferred cancellation work need deeper instrumentation.

The repository separates the Paper plugin for capture controls/reporting from the optional Ignite companion for version-pinned Mixins. [Ignite](https://github.com/vectrix-space/ignite) transforms server classes before startup, and the sibling SneakyPatches project already targets the same Paper revision. Keep those hooks separately testable and measure their enabled/disabled overhead. The [companion module](ignite/README.md) is currently a scaffold; no hooks or plugin-to-mod bridge have been implemented, and no sibling plugins have been modified.

## Verification

`build` runs tests for capture preflight/rollback and shared identity, option validation, tick-window alignment, scheduler ownership/churn/overflow, entity extraction/section density, and paused census traversal across resumed segments and chunk/world unloads. The scanner tests reject unexpected world/chunk calls, including chunk loading. These tests exercise detached calculations and collection against controlled public-interface fixtures; they do not start a live Paper server. Verify commands, permissions, reports, and probe overhead on an isolated Paper server before production deployment.
