# SneakyDiagnostics

Paper 26.2+ diagnostics plugin for investigating TPS issues in custom plugins and scripts. Java 25 is required. Collection is manual. Available collectors cover entities, the tick timeline, public scheduler state, registered plugin probes, optional spark profiles, and optional Ignite scheduler instrumentation.

## Build and install

```powershell
.\gradlew.bat build
```

Copy `plugin/build/libs/SneakyDiagnostics-<version>.jar` (not the sources JAR) to the server's `plugins/` folder and restart the server. The plugin works independently of Ignite. This project does not deploy itself or change production configuration.

For scheduler execution and cancellation attribution, copy `ignite/build/libs/SneakyDiagnostics-Ignite-<version>.jar` to Ignite's `mods/` directory and restart. Keep only one companion version installed. Scheduler captures automatically include its measurements when available; status reports availability. The tested baseline is Paper 26.2 build 116 with Ignite 1.2.1. Later releases are accepted when the required hook contracts remain compatible, without an exact build-number gate. See the [companion documentation](ignite/README.md) for compatibility and runtime checks.

## Repository layout

```text
api/                      Stable provider interface (SneakyDiagnostics-API:1.0.0)
plugin/                   Paper plugin, collectors, commands, and tests
ignite/                   Optional Ignite scheduler Mixins and hook tests
build.gradle.kts          Shared version, Java toolchain, and publishing
settings.gradle.kts       Module registration
.github/workflows/        One build/publishing workflow for all artifacts
```

Root `build`, `check`, and `clean` cover all three modules. Use `:plugin:build` or `:ignite:build` for a module-specific build. The plugin and Ignite companion use the shared root version; the API has its own stable version. The Ignite bridge uses the JVM's local MBean server and JDK values; the plugin never loads companion classes. Plugin providers use the typed API described below.

## Capture

```text
/diagnostics entities start world
/diagnostics entities status
/diagnostics entities stop
/diagnostics entities resume 120
/diagnostics timeline start 60
/diagnostics scheduler start 60
/diagnostics plugins start 60
/diagnostics spark start 120
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
| `/diagnostics plugins` | `start [seconds]` | `sneakydiagnostics.plugins` |
| `/diagnostics spark` | `start [seconds]` | `sneakydiagnostics.spark` |
| `/diagnostics all` | `start [seconds] [world]` | `sneakydiagnostics.all` |

All permissions default to operators. `sneakydiagnostics.all` includes the five individual permissions. `/entitycensus <start [world] [seconds]|resume [seconds]|status|stop>` remains available with the legacy `sneaky.entitycensus` permission. Omit the world to scan all currently loaded worlds; a world filter restricts only the entity collector. Numeric single arguments to entity start are interpreted as durations; specify a duration after a numeric world name to select it explicitly.

`all start` validates every collector before starting, and shares a capture ID, timestamp, and monotonic clock across all reports. It rejects busy collectors, including pending report writes, and rolls back partial starts. Individual captures can overlap independently; starting or resuming another capture is blocked while a coordinated capture is still active. `all stop` stops all active collectors, including independently started ones. A census may finish before the requested time if it reaches the end of its traversal; timeline and scheduler collection continue for their full window.

Completion logs absolute paths under `plugins/SneakyDiagnostics/reports/<capture-id>/`. Each collector writes its own JSON file, such as `entities-1.json`, `timeline-1.json`, or `scheduler-1.json`. Stop writes partial reports. Files have the shared capture identity and offsets as well as their actual completion reason and collector-specific coverage information.

New capture IDs use UUIDv7 with the capture start's Unix millisecond timestamp, so their folder names sort chronologically to millisecond precision. IDs created within the same millisecond have random ordering. Existing UUIDv4 capture folders retain their IDs and do not sort chronologically alongside new captures; use report timestamps when comparing both formats.

An unfinished census can be continued with `/diagnostics entities resume [seconds]` after its previous report finishes writing. Resume continues the same cursor and deduplication set, writes `entities-2.json` and subsequent segments under the same capture ID, and includes pauses in observation offsets. Entities, groups, and sections in each file describe that segment; coverage counters and unique attempts are cumulative. Combine all segments when analyzing the whole census. Starting a new census discards the paused cursor. Resume state exists only in memory and is discarded on plugin disable/restart.

The first useful capture is during the sustained 11–15 TPS condition. With the instrumented MagicSpells build installed, `/diagnostics all start 60` also collects spell measurements under the same capture ID. `/diagnostics plugins status` lists registered probes. Repeat after one controlled change when player locations and activity are comparable.

## Publishing and development

The public repository is [Team-Sneakymouse/SneakyDiagnostics](https://github.com/Team-Sneakymouse/SneakyDiagnostics).
Pushes to `main` and manual runs of the `Publish Maven` workflow build and check all modules before publishing the plugin and companion artifact sets (JAR, sources JAR, and POM) to `https://maven.sneakyrp.com/releases`. Each remote publication depends on the repository's complete checks.

Maven coordinates are `io.github.team-sneakymouse:SneakyDiagnostics:<version>` for the plugin and `io.github.team-sneakymouse:SneakyDiagnostics-Ignite:<version>` for the companion. The plugin's existing coordinates are preserved. The plugin and companion use the same version derived from the commit date in UTC and its 12-character hash: `yyyy.MM.dd-<hash>`. A committed Git checkout is required to build. The provider library is `io.github.team-sneakymouse:SneakyDiagnostics-API:1.0.0`; it has an independent stable interface version and is bundled inside the Paper plugin. Release it once per API version through the manual `Publish Diagnostics API` workflow or `publishApi`; it is excluded from ordinary `publishAll` runs. Publish the first API release before building the MagicSpells integration in CI.

Each deployable plugin/companion Maven POM includes a custom `sneakyrp.deployment.type` property for deployment automation:

| Artifact | Property value | Installation directory |
| --- | --- | --- |
| `SneakyDiagnostics` | `paper-plugin` | `plugins/` |
| `SneakyDiagnostics-Ignite` | `ignite-mod` | Ignite's `mods/` |

Read the version's `.pom` alongside its main JAR and select installation handling by this property. For example, the companion declares `<properties><sneakyrp.deployment.type>ignite-mod</sneakyrp.deployment.type></properties>`. The API library has no deployment property and must not be installed as a plugin. This is a custom deployment convention, not Maven's packaging type; both artifacts remain JARs. The property is in the POM, not repository-level `maven-metadata.xml` or Gradle's `.module` file. Existing releases lack it; deployment systems can retain their legacy behavior for missing values and should reject unknown explicit values.

CI follows SneakyDiscordLink's Infisical OIDC setup. The repository must have access to `INFISICAL_IDENTITY_ID` and `INFISICAL_DOMAIN` GitHub Actions secrets, and the identity must authorize this repository. Infisical project `lords-of-minecraft`, environment `prod`, path `/Maven` must provide `MAVEN_USERNAME` and `MAVEN_PASSWORD`.

For publishing from a local checkout, supply Gradle properties `sneakyrpUsername` and `sneakyrpPassword` outside the repository (for example in the user Gradle properties file), then run `publishAll`. The original `publishMavenPublicationToSneakyrpRepository` command remains an alias that publishes the plugin and companion. Never commit credentials. Root `publishToMavenLocal` can verify all three publications without remote credentials.

`SneakyDiagnosticsPlugin` owns lifecycle and command routing. The `capture` package validates coordinated starts, shares capture identity and time, and serializes detached reports off the server thread. `entity`, `timeline`, `scheduler`, and `integration` own their collection state. The Paper plugin uses public APIs; the optional Ignite companion instruments scheduler internals. There is no automatic capture.

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

## spark profiles

`/diagnostics all start [seconds]` attempts a spark execution profile for the same capture window. `/diagnostics spark start [seconds]` captures only spark. Both default to 60 seconds. Reports contain `spark-1.json` and, on successful export, `profile.sparkprofile` in the shared capture folder. Open the binary file by dragging it into [spark's viewer](https://spark.lucko.me/). Nothing is uploaded automatically.

Diagnostics cancels any running spark profiler and starts a dedicated server-thread execution sampler at a 4 ms interval with no slow-tick threshold. Like the other collectors, its deadline is checked at tick end; a stalled tick can extend collection. Stop, rollback, and plugin disable finalize owned sessions; finalization and export run on the report writer thread. Waiting for that writer can also extend sampling; `exportAfterMillis` and `snapshotAfterMillis` expose the actual export/finalization offsets. Pending exports block a new spark capture. Administrators can still stop or replace the owned sampler through spark; diagnostics detects a changed session and marks its report partial.

Existing profilers are cancelled without exporting or restoring them, including background, allocation, lock, slow-tick-only, and server-thread-excluding profiles. Each diagnostics capture uses a fresh execution sampler rather than retained history. Missing, disabled, or incompatible spark produces a skip report while the other collectors continue.

Exports are labelled `coverage: dedicated_session`, with `owned: true`. `complete` describes normal diagnostics completion and successful export; tick-end checks and asynchronous finalization can extend the actual sampling window. Consult the profile's own sampling windows and the recorded export offsets for exact coverage. World filters apply only to the entity collector.

spark's public API exposes metrics but no profiling lifecycle/export interface. The optional adapter discovers the public Bukkit service where available, otherwise the standalone plugin or Paper's bundled module, and calls spark implementation methods directly without command dispatch. Reflection is isolated to this adapter; no spark classes or dependencies are bundled into diagnostics. Compatibility is checked by method contracts before cancelling a running profiler; failures are reported as skips.

The export/ownership proof can run against a local bundled spark JAR without starting Minecraft:

```powershell
.\gradlew.bat :plugin:verifySpark -PsparkJar="C:/path/to/spark-paper.jar"
```

## Scheduler snapshots

At most once per wall-clock second at tick end, capture pending sync/async tasks by owning plugin and scheduler task implementation class, cancelled flags, and active async worker counts by plugin. Task implementation classes are scheduler wrappers: the runnable class is unavailable through the public interface. Worker thread names and task IDs are not written.

New/missing task IDs between complete snapshots describe observed churn. They are not exact creation, execution, or cancellation rates, and short-lived tasks between snapshots are missed. Async state can also change during a read. Diagnostics' own pending census task is included. Grouping is capped at 50,000 examined tasks, 5,000 workers, and 512 groups per snapshot; truncation and overflow counts are explicit, and truncated task-ID sets cannot produce churn comparisons. The Bukkit calls copy their full lists before these caps apply, so snapshot overhead includes potentially expensive non-preemptible list copies.

## Plugin measurements

The instrumented MagicSpells build (based on `b462388fcc10`, published baseline `2026.10.16-b462388fcc10`) registers a provider through the typed SneakyDiagnostics API. `/diagnostics plugins start 60` collects its existing spell, subspell, passive, event, scheduled-task, reagent and inventory-scan timings. `/diagnostics all start 60` includes the same measurements with the entities, timeline and scheduler captures.

Reports use `plugins-1.json` in the shared capture folder. Each provider reports its build and instrumentation version, completion reason, coverage and measurements. MagicSpells includes calls, inclusive/self/max nanoseconds, inventory slots and item-match counts. Timings are server-thread wall time; async execution is excluded. Inclusive rows overlap one another and scheduler probes. Instrumentation `magicspells-execution-2` preserves independent global, operation and loaded-spell aggregates when detail overflows. Detail capacity is calculated from the provider report allowance after reserving aggregate space; `limits.maxDetailKeys` reports the effective budget. Rank spells by `spellTotals.selfNanos` and inspect `totalsComplete`, `spellTotalsComplete`, `operationTotalsComplete` and `detailComplete` separately. Oversized spell exports retain combined omitted totals instead of losing the entire provider report.

Instrumentation `magicspells-execution-3` adds canonical `spellKey` with configured spelling and a bounded `taskLifecycle` section. Boundary inventories include tasks registered before capture, group pending work by spell/source/kind/delay/interval, and report age/creation ranges. The largest 512 groups are ranked before export truncation. Counters distinguish helper registrations, repeating executions, one-shot completion and cancellation requests/removals. Check `inventoryComplete`, `lifecycleComplete`, unknown metadata/owners, overflow and actual observation offsets independently of timing coverage. Next-run ticks are explicitly unavailable through the public scheduler. Direct Bukkit work has unknown historical metadata; external removals are reconciled without inferring cancellation or execution counts. `allPluginLifecycleComplete` is false: direct tasks can appear and disappear between observations. An end snapshot after the event deadline explicitly leaves an uncovered interval. Registry metadata is capped at 65,536 entries, removed on supported termination and cleared on reload/disable; external cancellation metadata is reconciled at capture boundaries. See [provider details](docs/plugin-providers.md#magicspells-scheduler-capture).

Install both rebuilt plugin JARs for this integration. The baseline MagicSpells release does not expose the new provider. The instrumented build removes `/ms perf` and its standalone reports; capture control and report writing belong to SneakyDiagnostics. MagicSpells still runs without SneakyDiagnostics, with measurements disabled. No API JAR needs to be installed separately. Close/reload during capture preserves the provider's partial results and marks it incomplete.

See [the provider guide](docs/plugin-providers.md) for the reusable interface, build dependency, lifecycle contract and classloader verification.

The instrumented SneakyConfigurableVoice build registers a `voice-routing-1` provider with the same commands. Its four fixed groups cover normal, whisper, shout and broadcast. Measurements include microphone-event volume, handler/dispatch/query wall time, submission-to-start delay, effective ranges, player-query candidates/matches, recipient/send counts and unstarted/in-flight work at the capture boundary. Collection is thread-safe, bounded and omits player identities and audio payloads. Dispatches scheduled before capture are counted without fabricating queue delay. Timings overlap and send calls returning normally do not prove network delivery. See the voice checkout's README for detailed field meanings and a live smoke checklist.

## Deeper probes

With the companion installed, `/diagnostics scheduler start` and `/diagnostics all start` also capture task creation, sync/async runnable and consumer execution, callable execution, individual/plugin cancellation requests, repeated individual cancellation requests, and deferred cancellation scans. The scheduler report's `ignite` section groups these by owner, body class, sync flag, event, and cancellation caller. It includes started/completed/throwing counts, total/max duration, observation offsets, overflow counters, and unfinished scopes at stop. Without the companion, public snapshots remain available.

Probe state is bounded to 2,048 groups and 100,000 entries in each task-identity/cancellation map, with labels capped at 192 characters. Hooks bypass aggregation outside captures; captures expire on their monotonic deadline even when no tick arrives. Execution durations are inclusive wall time, not CPU time. Cancellation requests do not count removed tasks, and request/deferred/execution scopes can overlap. Creation timing measures probe bookkeeping. Unknown targets can predate capture; a caller frame is evidence of a call site, not proven plugin ownership. Read report notes before comparing totals.

The instrumented MagicSpells integration supplies its existing spell/event/task measurements. Dedicated MagicSpells spatial-query caller/radius/candidate counts, altered-block history lifecycle and separate undo/particle timings remain future work. [Ignite](https://github.com/vectrix-space/ignite) supplies class transformation; compatibility contracts and a runtime proof belong in the [companion module](ignite/README.md).

## Verification

`build` runs tests for capture preflight/rollback and shared identity, option validation, tick-window alignment, scheduler ownership/churn/overflow, entity extraction/section density, and paused census traversal across resumed segments and chunk/world unloads. The scanner tests reject unexpected world/chunk calls, including chunk loading. Companion tests cover hook contracts, bounded aggregation, cancellation attribution, capture expiration, and detached reports; bridge tests exercise JDK-only communication and protocol mismatch.

The opt-in `:ignite:verifyRuntime` check uses real Ignite transformations against Paper scheduler classes, preserving return values/exceptions and verifying a separate plugin classloader. It starts no Minecraft server. Its warm-loop timing is a synthetic smoke measurement, not a server TPS benchmark. Verify commands, permissions, reports, and probe overhead on an isolated Paper server before production deployment.
