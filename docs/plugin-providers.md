# Plugin diagnostics: measuring your plugin

SneakyDiagnostics can record what your plugin costs while a capture runs (`/diagnostics plugins start`, or as
part of `/diagnostics all start`). You add a small shim class and a few `probe.start()/end()` calls. Without a
capture, or without SneakyDiagnostics installed, they do nothing. The service keeps all timing, aggregation and
reporting; do **not** write your own. (Plugins whose measurements cannot be expressed this way are the exception:
see [Choosing: shared probe or custom provider](#choosing-shared-probe-or-custom-provider).)

The API is published as `io.github.team-sneakymouse:SneakyDiagnostics-API:<version>`, `<version>` being the
commit-derived `yyyy.MM.dd-<hash>` of a SneakyDiagnostics release that contains `Diagnostics.probe(...)`. The
API, plugin and Ignite companion share that version and are published together (`Publish Maven` workflow or
`./gradlew publishAll`). Never shade or relocate the API; the SneakyDiagnostics plugin JAR carries it.

## 1. Quick start

**Build.** Compile against the API only; it is not bundled. Keep the existing repositories block.

```kotlin
repositories {
    maven("https://maven.sneakyrp.com/releases")
}
dependencies {
    compileOnly("io.github.team-sneakymouse:SneakyDiagnostics-API:<version>")
}
```

For local development before publication run `./gradlew :api:publishToMavenLocal` in SneakyDiagnostics and
add `mavenLocal()` to your repositories. `plugin.yml` must name it as a soft dependency (a hard `depend` is
only for plugins that cannot run without it):

```yaml
softdepend: [SneakyDiagnostics]
```

**The shim.** Save this as `integration/Probe.java` in your plugin: put your own `package` line first and copy the
rest unchanged. It is the only class that touches the API, it contains no timing or aggregation logic, and
the file is identical in every plugin.

```java
import com.teamsneakymouse.sneakydiagnostics.api.Diagnostics;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;

/**
 * This plugin's measurements for SneakyDiagnostics (optional: without it every method below does nothing).
 * Keep API types out of every method signature (Bukkit reflects over them when registering events).
 */
public final class Probe implements Listener {
    private static final String SERVICE = "com.teamsneakymouse.sneakydiagnostics.api.Diagnostics";
    private final Plugin plugin;
    private final String instrumentation;
    private Diagnostics.Probe probe; // null unless SneakyDiagnostics is enabled; the only member naming an API type

    public Probe(Plugin plugin, String instrumentation) {
        this.plugin = plugin;
        this.instrumentation = instrumentation;
    }

    /** Call at the end of onEnable (server thread). Attaches now if SneakyDiagnostics is enabled, and again whenever it (re)appears. */
    public void hook() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (plugin.getServer().getPluginManager().isPluginEnabled("SneakyDiagnostics")) attach();
    }

    /** Call first thing in onDisable (server thread). */
    public void unhook() { detach(); }

    public long start() { var p = probe; return p == null ? 0 : p.start(); }
    public void end(String section, long token) { var p = probe; if (p != null) p.end(section, token); }
    public void count(String key) { count(key, 1); }
    public void count(String key, long n) { var p = probe; if (p != null) p.count(key, n); }
    public boolean sampleDue() { var p = probe; return p != null && p.sampleDue(); }
    public void level(String key, long value) { var p = probe; if (p != null) p.level(key, value); }
    public boolean on() { var p = probe; return p != null && p.on(); }

    @EventHandler public void onServiceUp(ServiceRegisterEvent event) {
        if (SERVICE.equals(event.getProvider().getService().getName())) attach();
    }

    @EventHandler public void onServiceDown(ServiceUnregisterEvent event) {
        if (SERVICE.equals(event.getProvider().getService().getName())) detach();
    }

    private void attach() {
        detach();
        try {
            Diagnostics diagnostics = plugin.getServer().getServicesManager().load(Diagnostics.class);
            if (diagnostics != null) probe = diagnostics.probe(plugin.getName(), plugin.getPluginMeta().getVersion(), instrumentation);
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Couldn't register with SneakyDiagnostics (" + e + "); its captures won't include " + plugin.getName() + ".");
        }
    }

    private void detach() {
        var p = probe;
        probe = null;
        if (p == null) return;
        try { p.close(); } catch (RuntimeException e) { plugin.getLogger().warning("Couldn't leave SneakyDiagnostics: " + e); }
    }
}
```

How it stays safe when SneakyDiagnostics is missing, and why it looks like this, is explained under
[Why the shim looks like this](#why-the-shim-looks-like-this). Do not add other methods to it, and never put an
API type (`Diagnostics`, `Diagnostics.Probe`, ...) in a method signature, field outside this class, or lambda.

**Wire it up.** Create the probe once, call `hook()` at the end of `onEnable` and `unhook()` first thing in
`onDisable`. The instrumentation version names what your sections mean; see [Rules](#2-rules).

```java
private final Probe probe = new Probe(this, "myplugin-ticks-2");

@Override public void onEnable() {
    // ... existing setup ...
    probe.hook();
}

@Override public void onDisable() {
    probe.unhook();
    // ... existing teardown ...
}
```

**Sections, counters and levels.**

- `long t = probe.start(); ...; probe.end("name", t);` times a piece of server-thread work (wall time).
- `probe.count("name")` or `probe.count("name", n)`: something happened.
- `if (probe.sampleDue()) { probe.level("name", value); ... }`: how much of something there is now, sampled
  about once a second during a capture.

A tick loop with nested sections, a counter, and levels including the entity ownership levels:

```java
private void tickAll() {
    long tick = probe.start();
    long ts = probe.start();
    deliver();
    probe.end("tick.deliveries", ts);
    ts = probe.start();
    for (Machine machine : machines) {
        long tm = probe.start();
        machine.tick();
        if (tm != 0) probe.end("machine." + machine.type(), tm);
    }
    probe.end("tick.machines", ts);
    probe.count("machines.ticked", machines.size());
    if (probe.sampleDue()) {
        probe.level("machines", machines.size());
        probe.level("entities.owned", owned.size());
        Map<EntityType, Integer> byType = new EnumMap<>(EntityType.class);
        for (Entity entity : owned) byType.merge(entity.getType(), 1, Integer::sum);
        for (var entry : byType.entrySet()) {
            probe.level("entities.owned." + entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue());
        }
    }
    probe.end("tick", tick);
}
```

An event handler (`try/finally` so an early return or exception still ends the section):

```java
@EventHandler
public void onEntitiesLoad(EntitiesLoadEvent event) {
    long t = probe.start();
    try {
        for (Entity entity : event.getEntities()) register(entity);
    } finally {
        probe.end("event.entities-load", t);
    }
}
```

These snippets and the shim are compiled by the SneakyDiagnostics tests
(`plugin/src/test/java/.../integration/reference/`), so they build as written.

## 2. Rules

1. **Fixed labels only.** Names are constants or come from a small closed set (machine type, spell key, event
   name). At most **256 distinct names per kind** (sections, counts, levels), each at most 192 characters;
   more are dropped, counted in `omittedKeys`, and make your report incomplete. Never put player names, UUIDs,
   locations, item text or other free text into a name.
2. **Guard string building.** When no capture runs `start()` returns 0 and `end/count/level` return at once, but
   `"machine." + type` would still allocate. Build a name only after the token check
   (`if (t != 0) probe.end("machine." + type, t);`), and build level names inside `if (probe.sampleDue())`.
   A counter with a built name goes in `if (probe.on())`. Constant names need no guard. With SneakyDiagnostics
   installed, `on()` and `sampleDue()` are one interface call each even when idle: cheap, but call them once per
   tick or loop, not once per entity.
3. **Nest sections strictly (LIFO).** End an inner section before its outer one, and never overlap two sections
   that are not nested. `selfNanos` (time not spent in nested sections) is exact only then; `nestingErrors` in
   the report counts violations and must be 0. Do not nest a section inside one with the same name. **Give every
   section its own token variable**: reusing one (`long ts = t0; ... probe.end("a", ts); ... probe.end("tick", t0);`
   with `ts` and `t0` holding the same token) makes the later `end` an unknown token and the outer section is lost.
4. **`try/finally` around early returns.** Every `start()` must reach its `end()` on every path, including
   `return`, `continue`, `break` and exceptions. When unsure, wrap the work in `try { ... } finally { probe.end(...); }`.
   For a tick method with many sequential sections, put the whole body in one `try/finally` that ends the outer
   `tick` section: if an inner section throws, the outer `end` closes the frames left open (each counted in
   `nestingErrors`) instead of leaking them.
5. **Server thread only.** The probe is not thread safe. Never call it from async tasks, other threads or
   packet/voice threads; count such work on the server thread when it is delivered.
6. **Naming conventions.**

   | Kind | Name | Example |
   |---|---|---|
   | whole tick of the plugin | `tick` | `tick` |
   | a part of the tick | `tick.<part>` | `tick.deliveries` |
   | an event handler | `event.<name>` | `event.entities-load` |
   | work per type | `<kind>.<type>` | `machine.Crusher`, `spell.fireball` |
   | counter | dotted, past tense | `energy.arrived` |
   | level | the thing counted | `placed`, `deliveries.pending` |

   Lowercase with `-` between words, `.` between parts.
7. **Entity ownership levels.** If your plugin owns entities (displays, armor stands, NPCs, mobs it spawns),
   report them as levels, inside `sampleDue()`: `entities.owned` (total) and
   `entities.owned.<bukkit entity type, lowercase>` (for example `entities.owned.item_display`). Counts per
   type only: no per-chunk or per-entity data. See `report-schema-v2.md`. Count only what the plugin already keeps
   in memory. A plugin that stores entity UUIDs rather than references may resolve them with `Bukkit.getEntity`
   inside `sampleDue()` (this counts only loaded entities); never scan worlds or load chunks. Leave out entities
   you cannot count cheaply and say so in your README.
8. **Instrumentation version.** `<plugin>-ticks-<n>` (lowercase plugin name). Increase `n` whenever what a report
   means changes: a name is added, removed or renamed, a section is moved to another place in the nesting, a
   level starts counting something else. Never reuse a number for a different meaning. A new plugin release with
   unchanged instrumentation does not bump it (the plugin version is reported separately). Tools compare
   captures only within one instrumentation version.
9. **Keep it cheap.** One section per meaningful piece (a tick, a phase, a handler, a kind of work), not per
   entity or per block.
10. **One `sampleDue()` site per plugin.** The recorder hands each second's sampling slot to whichever call asks
    first, so two scheduled tasks that both call `sampleDue()` make each other skip levels. Sample every level
    from a single place (for example one `sampleLevels()` method called from the main tick) and let other tasks
    contribute only sections and counts.

## 3. Migrating from a copied Probe

For plugins that carry `integration/Probe.java` and `integration/DiagnosticsBridge.java`
(SneakyWizards, SneakyRacials, SneakyCCTV, SneakySwitches, SneakyAppliances, SneakyPowerplant, SneakyCasino,
SneakyHeist). Call sites such as `probe.start()`, `probe.end("x", t)`, `probe.count("x")`, `probe.sampleDue()`
and `probe.level("x", n)` keep working unchanged, because the shim has the same method names.

1. **Bump the API dependency** in `build.gradle.kts`: set the `compileOnly("io.github.team-sneakymouse:SneakyDiagnostics-API:...")`
   version to the `<version>` the integrator gives you, and update any comment there that still names
   `DiagnosticsBridge`. Keep `softdepend: [SneakyDiagnostics]` in `plugin.yml`.
2. **Delete** `integration/Probe.java` and `integration/DiagnosticsBridge.java`.
3. **Add the shim** from [Quick start](#1-quick-start) as `integration/Probe.java` (same class name and package as
   the deleted file, so existing imports still resolve; only the `package` line differs between plugins). Match
   the repository's line endings (the reference file is CRLF; with `* text=auto` git normalizes either way).
4. **Replace the hook code in the main class.** Remove the `AutoCloseable diagnostics` field, `hookDiagnostics()` and
   `unhookDiagnostics()`. Change the probe field to `new Probe(this, "<plugin>-ticks-2")` (the old one was
   `new Probe()`), call `probe.hook()` where `hookDiagnostics()` was called (end of `onEnable`) and
   `probe.unhook()` where `unhookDiagnostics()` was (first thing in `onDisable`). Keep the `probe()` getter if other
   classes use it.
5. **Keep the call sites** where the method names match. Then check each one:
   - every `start()` has its `end()` on all paths and sections nest strictly (Rules 3 and 4); the old probe did
     not require this;
   - section names are still fixed labels and fewer than 256 per kind (Rule 1).
6. **Guard string building.** Find every name that is built, for example with
   `grep -nE 'probe(\(\))?\.(end|count|level)\(.*\+' -r src` (the optional `()` also matches
   `plugin.probe().end(...)`), and guard it as in Rule 2:

   ```diff
   -probe.end("placed." + p.getClass().getSimpleName(), ts);
   +if (ts != 0) probe.end("placed." + p.getClass().getSimpleName(), ts);
   ```

   Also move any aggregation that only feeds `level(...)` (maps, `TreeMap`s, loops over all things) inside
   `if (probe.sampleDue())`; the old code already did this for the sampling block, keep it that way.
7. **Add the entity-owned levels** if the plugin owns entities (Rule 7), inside the existing `sampleDue()` block.
8. **Bump the instrumentation version** from `<plugin>-ticks-1` to `<plugin>-ticks-2` (the report now has self
   time, percentiles, the slowest calls and per-second series).
9. **Shrink the README "Diagnostics" section** to three lines pointing here, plus the plugin's own section and
   level names:

   ```markdown
   ## Diagnostics
   Measured by [SneakyDiagnostics](https://github.com/Team-Sneakymouse/SneakyDiagnostics) when it is installed (optional): `/diagnostics plugins start 30 <Plugin>`.
   Setup and rules: [plugin-providers.md](https://github.com/Team-Sneakymouse/SneakyDiagnostics/blob/main/docs/plugin-providers.md).
   Sections: `tick`, `tick.<part>`, ...; counts: `...`; levels: `...`, `entities.owned`, `entities.owned.<type>`.
   ```

10. **Remove tests of the old `Probe`** (anything calling `begin`, `finish`, `complete` or reading its maps). The shim has
    no logic to test; the service tests cover the recorder.
11. Run the [verification checklist](#4-verification-checklist).

## 4. Verification checklist

- [ ] `./gradlew build` passes; the plugin JAR contains no `com/teamsneakymouse/sneakydiagnostics/api` classes.
- [ ] `grep -nE 'probe(\(\))?\.(end|count|level)\(.*\+' -r src` shows only guarded calls (Rule 2); no `Diagnostics` type
      is mentioned anywhere except `integration/Probe.java`.
- [ ] With SneakyDiagnostics installed, start the server: no warnings about SneakyDiagnostics in the log, and
      `/diagnostics plugins status` lists your plugin.
- [ ] `/diagnostics plugins start 30 <Plugin>` while exercising the plugin, then look in the capture folder: a
      `plugins-<slug>-1.json` exists (`<slug>` is the lowercased plugin name; read the `file` field of
      `plugins-1.json`). In it `measurements.schema` is `sneakydiagnostics.probe/1`, `complete` is `true`,
      `measurements.omittedKeys` is 0 and `measurements.nestingErrors` is 0. Your `tick` section is present.
- [ ] `/diagnostics plugins status` shows no capture running afterwards, and with no capture nothing is measured or
      allocated: the code review above is the check (constant names and guarded built names only).
- [ ] Reload only SneakyDiagnostics (plugin manager, or disable and enable it): `/diagnostics plugins status`
      lists your plugin again without restarting it.
- [ ] Remove SneakyDiagnostics from `plugins/` and start the server: your plugin enables and works, with no
      `NoClassDefFoundError` and no warning.

## Why the shim looks like this

The consumer must run when SneakyDiagnostics (and so the API classes) is absent. The JVM links lazily: a
missing class fails only when an instruction that names it executes (or when reflection reads a signature that
names it). The shim therefore:

- names API types only as the type of one private field and inside method bodies. The field stays `null` until
  `attach()` runs, which happens only when `isPluginEnabled("SneakyDiagnostics")` is true in `hook()` or when
  Bukkit announces the `Diagnostics` service; the other methods return before touching it. The service is
  recognised by class **name**, because `Diagnostics.class` would load the class;
- has no API type in any method signature or lambda. Bukkit's `registerEvents` reflects over every declared
  method of a listener, which resolves their parameter and return types and would fail;
- is a plain class in your plugin with the call-site methods of the old `Probe`: off cost is a field read and a
  null check. With the service present and no capture, it is one interface call that returns after a boolean
  check (nothing timed, built or allocated);
- uses the service-returned `Diagnostics.Probe` only through the interface, loaded from SneakyDiagnostics'
  classloader, so there is no shading and no class-identity problem. All other types are Bukkit or JDK types.
  The handle is created per plugin by the service (`Diagnostics.probe(...)`), which owns the recorder and reports
  it like any other provider;
- catches `RuntimeException` and `LinkageError` while attaching, so an older SneakyDiagnostics without
  `probe(...)` leaves the plugin running with measurements off and one warning;
- survives a SneakyDiagnostics-only reload: when SneakyDiagnostics is disabled it unregisters its service
  (`ServiceUnregisterEvent`, the shim drops the dead handle), and when it is enabled again its service is
  registered (`ServiceRegisterEvent`, the shim asks the new service for a fresh handle). Start order does not matter
  either: if SneakyDiagnostics enables after your plugin, the same event attaches it. A capture running during a
  reload is ended by SneakyDiagnostics (reason `diagnostics_disable`) and is not continued.

Test: `ProbeShimTest` loads the shim through a classloader that hides the API package and runs every method
with the service absent.

## Choosing: shared probe or custom provider

The shared probe is for **simple plugins**: work that runs on the server thread, nests cleanly, and fits a small
fixed set of labels (parts of a tick, event handlers, kinds of work). SneakyWizards, Racials, CCTV, Switches,
Appliances, Powerplant, Casino and Heist use it. Use a **custom provider** (the v1 API below) when the measurement
cannot be expressed that way:

| Use the shared probe when | Use a custom provider when |
|---|---|
| everything runs on the server thread | work runs on other threads (SneakyConfigurableVoice records on voice threads; the probe is server-thread only) |
| a fixed label set describes the work (`tick.<part>`, `event.<name>`, `<kind>.<type>`) | the data is dimensional or high-cardinality, with exact aggregates that must survive detail overflow (MagicSpells: per spell and operation, plus scheduler task lifecycle) |
| sections nest strictly | scopes overlap or are unfinished at the capture boundary |

This is a rule of thumb, not a ban in either direction. Strict consolidation of the measurement vocabulary was
considered and rejected: forcing MagicSpells or Voice into sections, counts and levels would discard the data the
production investigations rely on.

**What every provider shares anyway:** registration and selection (`/diagnostics plugins start ... <Plugin>`), the
per-provider report file and its own `complete` flag, the capture manifest, and re-registration after a
Diagnostics-only reload. Only the contents of `measurements` differ.

**What a custom provider costs:**
1. **Tooling:** its `measurements` are free-form. There are no per-second series, percentiles or slowest-call
   offsets, so slow timeline windows cannot be attributed to it. The analysis tools carry provider-specific recovery
   code (for example MagicSpells' mixed-case spell totals) and refuse to rank if the fields they rely on change
   meaning. Changing a custom provider's report means changing the tools in the same step.
2. **Duplicated lifecycle code:** each custom provider keeps its own copy of the small `DiagnosticsAttachment`
   class (see MagicSpells `util/performance` and SneakyConfigurableVoice `diagnostics`), as the shim does. A copy per
   plugin is required so the API class is never loaded when SneakyDiagnostics is absent; a change to the pattern
   must be applied in every copy.
3. **One provider per plugin name:** a plugin cannot use the shared probe for a few coarse sections alongside its
   custom provider. Placing a spike in time (MagicSpells' 692 ms variable save is the known case) would need
   either two registrations per plugin or a custom provider that embeds a probe. Neither exists yet.

## Custom providers (v1 API)

Use this only when the shared probe does not fit (see above). MagicSpells and SneakyConfigurableVoice do. The API's
`Diagnostics.register` stays supported unchanged; the report is the provider's own free-form `measurements`. The
same optional-dependency rules apply: keep the API-dependent adapter in a separate class that is loaded only when
SneakyDiagnostics' service exists, and register again whenever the `Diagnostics` service reappears
(`ServiceRegisterEvent`/`ServiceUnregisterEvent`, matched by class name). Copy the `DiagnosticsAttachment` class
from MagicSpells or SneakyConfigurableVoice for that; it takes a registrar that registers and returns the action
that unregisters.

```java v1-sketch
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

`recorder` is the plugin's bounded measurement implementation. Close `registration` on the server thread
**before** reload or disable clears measured state. Close is idempotent; an old handle cannot remove a newer
registration. MagicSpells records server-thread scopes; Voice uses a fixed-size, thread-safe recorder for
overlapping microphone handlers and scheduled delivery.

## Capture contract (v1 providers; the shared probe follows it for you)

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
