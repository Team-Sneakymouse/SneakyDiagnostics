# Plugin diagnostics providers

SneakyDiagnostics publishes `io.github.team-sneakymouse:SneakyDiagnostics-API:<version>`.
The API contains one interface, `com.teamsneakymouse.sneakydiagnostics.api.Diagnostics`,
with nested capture, provider, result and registration types. It has no Bukkit dependency.
The SneakyDiagnostics plugin bundles the API and registers its implementation with Bukkit's
`ServicesManager`. Provider plugins compile against the API and must **not shade or relocate it**.
The API, plugin and Ignite companion share the commit-derived version
(`yyyy.MM.dd-<hash>`) and are published together by the `Publish Maven` workflow
or `./gradlew publishAll`. Replace `<version>` below with a published Diagnostics
version. Existing consumers pinned to the previously published API `1.0.0` can
continue using that release.

## Build and registration

```groovy
repositories {
    maven { url = uri("https://maven.sneakyrp.com/releases") }
}
dependencies {
    compileOnly("io.github.team-sneakymouse:SneakyDiagnostics-API:<version>")
}
```

For local development before remote publication, run `./gradlew :api:publishToMavenLocal`
in SneakyDiagnostics and add a `mavenLocal` repository restricted to this module in the provider.
The API JAR is a library; install only the SneakyDiagnostics plugin JAR on the server.

Add `softdepend: [SneakyDiagnostics]` to the provider's `plugin.yml`. Keep the API-dependent
adapter in a separate class and load it only when SneakyDiagnostics is enabled. A hard
`depend` is also supported when diagnostics is required for that plugin's operation.

After the provider plugin has finished loading, register on the server thread:

```java
Diagnostics diagnostics = plugin.getServer().getServicesManager().load(Diagnostics.class);
Diagnostics.Registration registration = diagnostics.register(
    plugin.getName(), plugin.getPluginMeta().getVersion(), "my-measurements-1",
    new Diagnostics.Provider() {
        @Override public void start(Diagnostics.Capture capture) {
            recorder.start(capture.startedNanos(), capture.seconds());
        }
        @Override public Diagnostics.Result stop(String reason) {
            return new Diagnostics.Result(recorder.stopAndDetach(), true);
        }
    });
```

`recorder` above is the plugin's bounded measurement implementation. Close `registration`
on the server thread **before** reload or disable clears measured state. Re-register after
reload has completed. Close is idempotent; an old handle cannot remove a newer registration.
MagicSpells and SneakyConfigurableVoice provide concrete examples. MagicSpells records
server-thread scopes; Voice uses a fixed-size, thread-safe recorder for overlapping
microphone handlers and scheduled delivery.

## Capture contract

- SneakyDiagnostics calls `start` and `stop` on the server thread. Registration, deregistration
  and service shutdown also require that thread. Async measurements need a thread-safe recorder
  that detaches its result on the server thread.
- Use the supplied capture ID and `startedNanos + seconds * 1_000_000_000L` as the deadline.
  Reject new measurements at that deadline even when no server tick arrives. Do not retain
  a capture session in work that outlives the session; stable logical ownership labels may persist.
- Providers registered at the start participate in `/diagnostics plugins start` and
  `/diagnostics all start`. New registrations join the next capture. World filters affect
  only the entity collector.
- Do not schedule a stop or write a report. SneakyDiagnostics finishes at tick end, on manual
  stop, or on disable. A failed start rolls back all attempted providers, including the one
  whose start threw. Therefore `stop` must tolerate a partially failed start.
- Deregistration during a capture immediately stops that provider with reason
  `provider_unregistered`. Its partial result stays in the capture; other providers continue.
- A provider exception becomes an explicit report error. Stop failures do not prevent other
  providers from stopping. A start failure rejects and rolls back the coordinated capture.
- Results must contain detached JSON values: string-keyed maps, lists, finite primitive numbers,
  strings, booleans, and null. Do not return Bukkit objects, enums, custom records or mutable live
  state. The service copies nested collections before returning control and rejects invalid values.
- Per provider, the detached report allows at most 250,000 values, 16 nesting levels, 192-character
  map keys and 1,024-character strings. Keep instrumentation itself bounded as well; these report
  limits do not bound a provider's work before it returns.
- `Result.complete` indicates coverage: set it false for overflow, dropped scopes, unavailable
  measurements or unfinished work. The collector additionally marks early stops and errors incomplete.
- Timings must state whether they measure inclusive wall time, self time, or CPU time, and document
  overlaps. Plugin measurements can overlap Ignite scheduler measurements and must not be summed blindly.
- Report plugin and instrumentation versions separately. Change the instrumentation version when
  the measurement schema or meaning changes. Avoid player identifiers and payload contents.

## Reports and verification

`plugins-1.json` is written under the normal capture folder as an index. Its `providers` array
identifies each participating plugin/build/instrumentation version, start status, completion
reason, per-provider coverage (`complete`), any error, and the `file` holding its measurements.
`incompleteProviders` lists providers with partial coverage or an error; they do not by
themselves make the collector `complete` false, which means only that the capture ended as
`completed` and no provider failed to start or stop. `selected` is `"all"` or the chosen names.
Each provider's detached measurements are in `plugins-<provider>-1.json` (lowercased name with
characters outside `[a-z0-9._-]` replaced by `_`; later collisions get `~2`, `~3`; use the index
`file` field). That file repeats the standard header with the provider's own `plugin`,
`pluginVersion`, `instrumentationVersion`, `reason`, `complete` and `error`, and a free-form
`measurements` object exactly as the provider returned it (omitted if the provider failed).
No registered providers means no plugin measurements, not zero plugin cost.

Providers participate by default. `/diagnostics plugins start [seconds] [provider ...]` and
`/diagnostics all start [seconds] [world] [plugins=<a>,<b>]` start only the named providers
(case-insensitive exact match, at most 32; unknown or duplicate names reject the start).
`/diagnostics plugins status` lists registered providers. See `docs/report-schema-v2.md`.

Run the consumer/provider classloader check after building the instrumented MagicSpells plugin:

```powershell
.\gradlew.bat :plugin:verifyMagicSpells -PmagicSpellsJar=D:/Projects/MagicSpells-Workspace/MagicSpells/core/build/libs/MagicSpells-<version>.jar
```

This exercises the actual rebuilt adapter and recorder through the service, verifies shared API
class identity, checks that MagicSpells did not shade the API, and preserves a partial result
when the provider unregisters. It starts no Minecraft server. An isolated Paper smoke test
is still needed for plugin loading, commands, real spell activity and overhead measurements.

For the rebuilt SneakyConfigurableVoice JAR, use the corresponding check:

```powershell
.\gradlew.bat :plugin:verifyVoice -PvoiceJar=D:/Projects/SneakyConfigurableVoice/build/libs/SneakyConfigurableVoice-<version>.jar
```

It exercises the actual voice recorder/provider through the same service and checks the
detached report against the consumer's limits, including partial results on unregister.

## MagicSpells scheduler capture

`magicspells-execution-3` uses the existing protocol and optional adapter. Case-folded
`spellKey` uses `Locale.ROOT`; `spell` retains configured internal spelling. Its
`taskLifecycle` report reserves export space ahead of optional timing detail.

- `start`/`end`: observed pending counts, async counts, in-flight callbacks, unknown
  metadata/attribution, omitted task counts, observation offsets and the largest 512
  pending groups. Group rows contain spell/source/kind/delay/period, pending count,
  oldest/youngest ages and creation offsets. Negative creation offsets predate capture.
- `totals`/`groups`: helper registrations, callback starts/completions/failures,
  repeating executions, one-shot completions, cancellation requests, tracked removals
  and cleanup terminations. Counts are independent of timing-scope counts and exclude
  waiting time. Lifecycle groups retain the first 512 keys; omitted events are counted.
- `unknownMetadataTasks` covers direct Bukkit/library work, registrations before
  instrumentation and overflow. Historical owner/origin/kind/creation/period are unknown.
  `nextRunTick` is null: the public scheduler exposes no reliable next-run timestamp.
- `unobservedRemovals` means tracked metadata disappeared from the scheduler without
  a supported terminal event. It is not an execution or cancellation count. Start-boundary
  stale cleanup is separately `preCaptureUnobservedRemovals`.
- `coverage` is `magicspells_sync_wrappers_only`; `allPluginLifecycleComplete` is false.
  Direct work can live entirely between snapshots. `inventoryComplete` and
  `lifecycleComplete` state the narrower coverage independently. Event collection stops
  at the shared deadline; an end snapshot after it sets `endObservedAfterDeadline` and
  marks lifecycle coverage incomplete. In-flight work and label/group/registry overflow
  also require attention. An apparently old repeating task alone does not establish a leak.

At most 65,536 entries retain bounded registration metadata outside capture. Completion
and helper cancellation remove it immediately; reload/disable cancel scheduler work and
clear it. External cancellation metadata remains bounded until boundary reconciliation
or unload. Overflow counters are conservative since load/cleanup. No task payload,
player identity, spell/Bukkit object or stack trace is retained. The public scheduler
allocates a complete task list twice per capture; the adapter streams its relevant rows.

The `verifyMagicSpells` command above also checks 36,000 pre-capture tasks and saturated
timing detail through the actual consumer guard, including provider unregistration and
metadata cleanup. It does not replace an isolated Paper behavior/overhead smoke test or
an authorized production capture during representative pending-task growth.
