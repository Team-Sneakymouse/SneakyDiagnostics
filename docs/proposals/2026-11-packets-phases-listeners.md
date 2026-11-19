# Proposal: packet collector, tick phases, listener totals, coverage fixes

Status: draft for review (2026-11-19). Motivated by capture `01a1f23a` (dev-main, 48 bots, ~8 TPS), where spark showed
ProtocolLib's per-packet send path at 18% of the server thread and entity-tracker sends at 24%, but no report could say
which entities, plugins or packet types caused them, which damage listeners were expensive, or how the tick split up.

All changes are **compatible additions to schema v2**: new files and new fields only, `schemaVersion` stays 2
(`report-schema-v2.md`, Compatibility policy). Every change updates the contract, `tools/sneakydiag/loader.py` and the
analyses that read it, and the fixtures in `tools/tests`, in the same PR.

## Where the hooks live

Everything that needs server internals goes into the Ignite companion, like the scheduler hooks today: Mixins with
`remap = false` (Paper 26.2 runs Mojang-mapped), structural contract checks before applying (startup fails rather than
measuring partially), contract tests against the real server jar and an extended runtime proof. The plugin keeps using
only public API and talks to the companion over the local MBean server with JDK values.

The companion gets a second MBean, `com.teamsneakymouse.sneakydiagnostics:type=ServerProbe` (protocol 1), for packets
and tick phases, so the scheduler bridge (protocol 1) stays unchanged. Without the companion the new collectors report
`unavailable` and write a report saying so, like the scheduler's Ignite section.

Hot-path rules for every new hook: idle cost is one volatile read; while recording, server-thread work uses plain fields
behind a `Thread.currentThread() == serverThread` check (no locks, no `ThreadLocal`), class labels are interned once
through a `ClassValue` to an int slot, and nothing is allocated per call. Other threads go through a lock-protected path
and are reported separately.

## 1. Packet collector: `packets-1.json`

New collector `packets` (`/diagnostics packets start [seconds]`, included in `all`, permission
`sneakydiagnostics.packets`).

**Outbound**, by wrapping `Connection.send(Packet, …)`:
- per packet class: count, sends from the server thread vs other threads, server-thread time spent inside `send`
  (inclusive: ProtocolLib's proxy and the Netty hand-off happen below it, so this is the cost spark showed), histogram.
  A bundle is counted once and its sub-packets per class.
- per **cause**, set by wrapping the call sites that send on behalf of an entity or a broadcast:
  `ServerEntity.sendChanges` (`tracker.update`, split into movement and `sendDirtyEntityData` metadata),
  `ServerEntity.addPairing` (`tracker.spawn`), `ServerEntity.removePairing` (`tracker.despawn`),
  `LivingEntity` equipment/swing/damage broadcasts (`entity.event`), `PlayerList.broadcast` (`broadcast`, e.g. sounds),
  `ServerLevel.sendParticles` / `CraftPlayer.spawnParticle` (`particles`), chunk sending (`chunks`), else `other`.
  Per cause: packets, broadcasts (calls of the cause) so fan-out = packets / broadcasts, and send time.
- for tracker causes, by **entity type** and **owner**: the plugin that spawned the entity (one stack walk to the first
  plugin-classloader frame in `ServerLevel.addFreshEntity`, only while recording, cached on the entity in a `@Unique`
  field), else the entity's first PDC namespace (read once per entity per capture), else unknown. Owner is labelled a
  hint, like the census. This answers "Wartime displays vs bot players vs Shrine zombies".
- a per-second series of packets and send time for the 32 largest classes and all causes.

**Inbound**, by wrapping `PacketProcessor$ListenerAndPacket.handle` (packets handled on the server thread at tick start):
per packet class count and inclusive handling time (plugin events fired by the packet are inside it; use-item was 12.4%
in `01a1f23a`), histogram and per-second series.

Not included: player identities (counts are per class/cause/type only), payload contents, bytes on the wire (encoding
runs on Netty threads; see open questions).

## 2. Listener totals: `scheduler-1.json` → `ignite.listenerTotals`

The listener hook already reads the clock on both sides while recording; it now also aggregates every call, not only
slow ones:
- key: owner plugin, listener class, event class, priority, async flag (cached per `RegisteredListener` in a `@Unique`
  field plus the last event class, so the steady state does no map lookup);
- per key: calls, `totalNanos` (inclusive), `selfNanos` (server thread only: minus nested listener calls, using a small
  depth stack), `maxNanos`, log2 histogram (same buckets as the shared probe);
- a per-second series of total and self time **per owner plugin**.
- bounds: 4,096 keys (overflow counted), 192-character labels. `observedInvocations` stays and must equal the sum of
  calls plus overflow.

Readers: a "Listeners" ranking by self time in `analyze_capture.py`, owner series in window correlation, listener rows in
`diff_captures.py`, and the coverage audit uses listener totals instead of only slow listener calls.

## 3. Tick phases: `phases-1.json`

New collector `phases` (included in `all`). Per server tick (current tick, so it matches the timeline and Ignite records),
microseconds spent in each phase, **exclusive** (a nested phase is subtracted from its parent):

| phase | hook (call site wrapped) |
|---|---|
| `tick-start-tasks` | `MinecraftServer.runAllTasksAtTickStart` (inbound packets and queued tasks) |
| `scheduler` | `CraftScheduler.mainThreadHeartbeat` |
| `level.<world>.entities` | `EntityTickList.forEach` in `ServerLevel.tick` |
| `level.<world>.chunks` | `ServerChunkCache.tick`, minus the tracker |
| `level.<world>.tracker` | `ChunkMap.tick` (Moonrise `newTrackerTick`) |
| `level.<world>.block-entities` | `Level.tickBlockEntities` |
| `level.<world>.block-events` | `ServerLevel.runBlockEvents` |
| `level.<world>.spawning` | `NaturalSpawner.createState` and `NaturalSpawner.spawnForChunk` (natural spawning is meant to be off on LoM; any time here is a finding) |
| `level.<world>.other` | rest of `ServerLevel.tick` |
| `connections` | `ServerConnectionListener.tick` |
| `diagnostics` | SneakyDiagnostics' own server-thread work (census slices, scheduler snapshots, timeline), so analyses can subtract it |
| `other` | measured tick duration minus the phases (from Ignite `measuredTicks`; tick-end listeners excluded) |

Stored as one array per phase aligned with a tick array (capacity capture seconds x 40, `dropped` counted), plus totals.
World names are a closed set (loaded worlds, max 64). Readers: a "Tick phases" table and per-window phase breakdown in
window correlation, phase deltas in `diff_captures.py`. With `measuredTicks` present the phases and `other` sum exactly
to the measured tick; otherwise `other` is marked unknown.

## 5. Coverage fixes

- **Slow-call lists** (`slowExecutions`, `slowListeners`): capacity scales with capture length,
  `max(512, 64 x seconds)` capped at 16,384 (about 3 MB at the cap). Execution `groups` gain the log2 histogram, so
  distributions no longer depend on the capped list. Fields `capacity`/`evicted` already exist; no meaning changes.
- **Entity census**: today it gets a 2 ms slice per tick, so at 8 TPS it received ~40% of its expected budget and
  timed out after half the chunks. Captures are run by hand while the server is already lagging, and coverage matters
  more than the extra lag, so change to a wall-clock budget: 50 ms of slice time per wall second, spread over that
  second's ticks, with no single slice over 10 ms. Also visit chunks nearest to players first, so a census that times
  out still covers where the load is. The census time lands in the `diagnostics` phase and in the existing scan
  overhead fields, so tick analyses can take it out. New coverage fields: `budgetMillisPerSecond`,
  `visitOrder: "players-first"`. Existing fields keep their meaning.
- **Spawns** (`timeline-1.json` → `spawns`, public API, works without the companion): per-second counts of entity
  spawns by spawn reason and entity type, from a MONITOR `EntitySpawnEvent`/`CreatureSpawnEvent` listener, plus the
  spawning plugin for `CUSTOM`/`DEFAULT` spawns (first plugin frame on the stack, only while recording). Any `NATURAL`,
  `CHUNK_GEN`, `REINFORCEMENTS` etc. spawn is called out by the analyzer, since natural spawning should be off.
- **Probe series**: `SERIES_SECTIONS` 16 → 64, so per-weapon sections (SneakyWartime) and similar get series.
  Readers already handle any number.

## Delivery

Three PRs, each useful on its own, each with contract + readers + fixtures:
1. Listener totals, execution-group histograms, scaled slow lists, census budget/order, spawn counts, probe series
   (existing hook points and public API only; lowest risk).
2. Tick phases (new Mixin targets, contract checks, `phases-1.json`).
3. Packet collector (new Mixin targets, `ServerProbe` MBean, `packets-1.json`).

Verification per PR: unit tests for bounds and aggregation, contract tests against the Paper 26.2 jar, runtime proof
extended with idle/recording cost per hook, then one dev-main capture under the same bot load compared with spark
(phases and packet send time should agree with spark's shares within sampling error).

## Decisions

1. Bytes per packet class on the wire: left out for now.
2. Natural spawning is disabled on LoM; only plugin-induced spawns are expected. Spawning gets its own phase and spawn
   counts by reason, and any natural spawning is reported as a finding. Note: in `01a1f23a` spark sampled 332 ms of
   self time in `NaturalSpawner.createState`, so something in the spawning path does run; the new phase will show
   whether that is only the mob-cap bookkeeping or actual spawning.
3. Captures are manual and taken while the server lags; capture accuracy wins over minimizing extra lag. Diagnostics'
   own server-thread time is reported separately (`diagnostics` phase) so it can be subtracted.
