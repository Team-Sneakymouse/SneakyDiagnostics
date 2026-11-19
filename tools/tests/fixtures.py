"""Hand-written, synthetic capture fixtures. Nothing here is real data."""
from __future__ import annotations

import gzip
import hashlib
import json
from pathlib import Path
from typing import Any

CAPTURE_ID = "01900000-0000-7000-8000-000000000001"
START = "2026-01-01T12:00:00.000000000Z"


def header(collector: str, schema: int, **extra: Any) -> dict[str, Any]:
    return {"schemaVersion": schema, "collector": collector, "captureId": extra.pop("captureId", CAPTURE_ID),
            "captureStartedAt": START, "captureStartTick": 100, "finishedAt": "2026-01-01T12:00:05Z",
            "finishedAfterMillis": 5000, "requestedDurationSeconds": 5, "reason": "completed", "segment": 1,
            "serverVersion": "Paper test", "pluginVersion": "2026.01.01-abc", "complete": True, **extra}


def timeline(schema: int, slow_index: int = 2, **extra: Any) -> dict[str, Any]:
    wins = []
    for i in range(5):
        slow = i == slow_index
        wins.append({"fromMillis": i * 1000, "toMillis": i * 1000 + 950, "firstTick": 100 + i * 20,
                     "lastTick": 119 + i * 20, "ticks": 6 if slow else 20,
                     "meanMspt": 160.0 if slow else 30.0, "minMspt": 20.0, "maxMspt": 400.0 if slow else 45.0,
                     "over50ms": 6 if slow else 0, "over100ms": 3 if slow else 0})
    return {**header("timeline", schema, **extra), "windows": wins, "totalCollectionNanos": 2_000_000,
            "maxCollectionNanos": 500_000}


def with_current_ticks(offset: int):
    """A timeline edit: current-tick ranges = tick numbers + offset (the gap an empty-server pause leaves)."""
    def edit(doc: dict[str, Any]) -> None:
        for w in doc["windows"]:
            w["firstCurrentTick"] = w["firstTick"] + offset
            w["lastCurrentTick"] = w["lastTick"] + offset
    return edit


def slow_ignite() -> dict[str, Any]:
    """Ignite slow-call records: in tick 142 (window 2) a 100 ms task with a 40 ms listener nested inside it, plus an
    async 30 ms task ending in window 3 (no tick) and one scheduler call still running at stop."""
    def rec(start_ms: float, dur_ms: float, **fields: Any) -> dict[str, Any]:
        return {**fields, "startOffsetMicros": int(start_ms * 1000), "endOffsetMicros": int((start_ms + dur_ms) * 1000),
                "nanos": int(dur_ms * 1e6), "throwing": False, "endedAfterDeadline": False}
    return {
        "slowExecutions": {"thresholdNanos": 1_000_000, "capacity": 512, "observed": 3, "retained": 2, "evicted": 1,
                           "complete": False, "records": [
                               rec(2000.0, 100.0, event="execution", owner="Gamma", taskClass="com.gamma.MenuTask",
                                   sync=True, thread="server", tick=142),
                               rec(3100.0, 30.0, event="execution", owner="Gamma", taskClass="com.gamma.SaveTask",
                                   sync=False, thread="other")]},
        "slowListeners": {"thresholdNanos": 1_000_000, "capacity": 512, "observed": 1, "retained": 1, "evicted": 0,
                          "complete": True, "observedInvocations": 5000, "records": [
                              rec(2030.0, 40.0, owner="Delta", listenerClass="com.delta.OpenListener",
                                  eventClass="org.bukkit.event.inventory.InventoryOpenEvent", priority="LOWEST",
                                  asyncEvent=False, thread="server", tick=142)]},
        "runningAtStop": {"count": 1, "capacity": 32, "records": [
            {"event": "execution", "owner": "Gamma", "taskClass": "com.gamma.StuckTask", "sync": False,
             "startOffsetMicros": 4_900_000, "runningNanosAtStop": 200_000_000}]}}


def with_listener_totals(doc: dict[str, Any]) -> None:
    """A scheduler edit: Ignite listener totals (2026-11) and execution-group histograms. Delta's open listener spikes
    in window 2 (the slow window); its calls plus Gamma's and Alpha's add up to slowListeners.observedInvocations (5000)."""
    def row(owner: str, listener: str, event: str, calls: int, self_ms: float, total_ms: float, max_ms: float,
            bucket: int) -> dict[str, Any]:
        hist = [0] * 41
        hist[bucket] = calls
        return {"owner": owner, "listenerClass": listener, "eventClass": event, "priority": "NORMAL", "asyncEvent": False,
                "calls": calls, "totalNanos": int(total_ms * 1e6), "selfNanos": int(self_ms * 1e6),
                "maxNanos": int(max_ms * 1e6), "otherThreadCalls": 0, "otherThreadNanos": 0, "histogramLog2": hist}
    ms_ = lambda xs: [int(x * 1e6) for x in xs]  # noqa: E731
    doc["ignite"]["listenerTotals"] = {
        "keys": 3, "maxKeys": 4096, "overflow": {"calls": 0, "totalNanos": 0}, "nestingOverflow": 0, "maxDepth": 64,
        "complete": True, "rows": [
            row("Delta", "com.delta.OpenListener", "org.bukkit.event.inventory.InventoryOpenEvent", 500, 45.0, 60.0, 40.0, 16),
            row("Gamma", "com.gamma.DamageListener", "org.bukkit.event.entity.EntityDamageEvent", 4400, 30.0, 35.0, 1.0, 12),
            row("Alpha", "com.alpha.TickListener", "com.destroystokyo.paper.event.server.ServerTickEndEvent", 100, 5.0, 5.0,
                0.2, 15)],
        "series": {"windowMillis": 1000, "maxOwners": 256, "omittedOwners": 0, "owners": {
            "Delta": {"totalNanos": ms_([2, 2, 52, 2, 2]), "selfNanos": ms_([1, 1, 40, 2, 1])},
            "Gamma": {"totalNanos": ms_([7, 7, 7, 7, 7]), "selfNanos": ms_([6, 6, 6, 6, 6])},
            "Alpha": {"totalNanos": ms_([1, 1, 1, 1, 1]), "selfNanos": ms_([1, 1, 1, 1, 1])}}}}
    for group in doc["ignite"]["groups"]:
        hist = [0] * 41
        hist[20] = group["counts"]["completed"]       # about 1..2 ms each
        group["histogramLog2"] = hist


def with_spawns(natural: bool = False):
    """A timeline edit: spawn counts (2026-11). ``natural`` adds NATURAL zombies (one cancelled) and a CHUNK_GEN cow."""
    def edit(doc: dict[str, Any]) -> None:
        rows = [{"reason": "CUSTOM", "entityType": "ZOMBIE", "plugin": "Shrine", "total": 30, "cancelled": 0,
                 "windows": [0, 2, 4], "n": [5, 20, 5]},
                {"reason": "CUSTOM", "entityType": "ITEM_DISPLAY", "plugin": "Alpha", "total": 12, "cancelled": 2,
                 "windows": [1, 2], "n": [6, 6]},
                {"reason": "BREEDING", "entityType": "COW", "total": 1, "cancelled": 0, "windows": [3], "n": [1]}]
        if natural:
            rows += [{"reason": "NATURAL", "entityType": "ZOMBIE", "total": 4, "cancelled": 1, "windows": [2, 3], "n": [3, 1]},
                     {"reason": "CHUNK_GEN", "entityType": "COW", "total": 2, "cancelled": 0, "windows": [4], "n": [2]}]
        doc["spawns"] = {"windowMillis": 1000, "observed": sum(r["total"] + r["cancelled"] for r in rows),
                         "keys": len(rows), "maxKeys": 512, "overflow": 0, "droppedOffThread": 0,
                         "attributedReasons": ["CUSTOM", "DEFAULT"], "complete": True, "rows": rows}
        doc["spawnCollectionNanos"] = 400_000
    return edit


def phases_report(*, available: bool = True, spawn_chunks: bool = False, entities_slow_us: int = 60_000,
                  world: str = "world.main", **extra: Any) -> dict[str, Any]:
    """phases-1.json (2026-11): three ticks per timeline window (current ticks 100+20i .. 102+20i), one world (a name with
    a dot). Entities spike in window 2. createState (mob-cap bookkeeping) runs every tick; ``spawn_chunks`` adds
    spawnForChunk time with entities attempted. The last tick has no measured duration (the stop interrupted it)."""
    doc = header("phases", 2, **extra)
    if not available:
        doc.update({"igniteAvailability": "unavailable (companion not installed)", "available": False,
                    "unavailableReason": "Tick phases need the Ignite companion: unavailable (companion not installed)",
                    "phasesComplete": False, "errors": 0, "complete": False})
        return doc
    lvl = f"level.{world}."
    base = {"tick-start-tasks": 1_000, "scheduler": 2_000, "diagnostics": 500, "connections": 2_000,
            lvl + "entities": 10_000, lvl + "chunks": 1_200, lvl + "tracker": 3_000, lvl + "spawning.state": 1_500,
            lvl + "other": 400}
    if spawn_chunks:
        base[lvl + "spawning.spawn"] = 800
    ticks, starts, measured, other = [], [], [], []
    cols: dict[str, list[int]] = {k: [] for k in base}
    outside = {"tick-end": [], "diagnostics": []}
    for i in range(5):
        for j in range(3):
            ticks.append(100 + 20 * i + j)
            starts.append(i * 1_000_000 + j * 300_000)
            row = dict(base)
            if i == 2:
                row[lvl + "entities"] = entities_slow_us
            for k in cols:
                cols[k].append(row[k])
            last = i == 4 and j == 2
            measured.append(None if last else sum(row.values()) + 1_000)
            other.append(None if last else 1_000)
            outside["tick-end"].append(300)
            outside["diagnostics"].append(200)
    totals = {k: {"totalNanos": sum(v) * 1000, "calls": len(v), "ticksWithTime": len(v), "maxTickMicros": max(v),
                  "insideMeasuredTick": True} for k, v in cols.items()}
    totals.update({"outside." + k: {"totalNanos": sum(v) * 1000, "calls": len(v), "ticksWithTime": len(v),
                                    "maxTickMicros": max(v), "insideMeasuredTick": False} for k, v in outside.items()})
    doc.update({
        "igniteAvailability": "Ignite phases available, idle", "available": True, "schema": "sneakydiagnostics.phases/1",
        "phasesComplete": True, "errors": 0, "complete": True,
        "ticks": {"tick": ticks, "startOffsetMicros": starts, "measuredMicros": measured, "count": len(ticks),
                  "capacity": 200, "dropped": 0},
        "phases": cols, "other": other, "outside": outside, "totals": totals,
        "measured": {"measuredTicks": 14, "unmeasuredTicks": 1, "measuredNanos": sum(m for m in measured if m) * 1000,
                     "otherMicros": 14_000, "negativeOtherTicks": 0},
        "worlds": [world], "omittedWorlds": [], "omittedWorldCalls": 0, "omittedSeries": [],
        "spawning": {world: {"stateCalls": 15, "spawnChunkCalls": 30 if spawn_chunks else 0,
                             "spawnChunkCallsThatSpawned": 4 if spawn_chunks else 0,
                             "naturalSpawnAttempts": 5 if spawn_chunks else 0, "customSpawnerCalls": 0}},
        "depthOverflow": 0, "nestingErrors": 0, "spanningTicks": 0, "lastTickPartial": True,
        "limits": {"ticksPerSecond": 40, "worlds": 64, "depth": 32, "values": 8_000_000}, "notes": "fixture"})
    return doc


def census_budget(doc: dict[str, Any]) -> None:
    """An entities edit: the wall-clock census budget fields (2026-11)."""
    doc.update({"budgetMillisPerSecond": 50, "budgetExhaustedTicks": 3, "visitOrder": "players-first"})


def scheduler(schema: int, slow: bool = False, gamma: bool = False) -> dict[str, Any]:
    """``gamma`` adds a second task owner (Gamma) without a provider; ``slow`` adds Ignite slow-call records."""
    def snap(t: int, pending: int, new: int, missing: int, spells: int) -> dict[str, Any]:
        return {"observedAfterMillis": t, "pendingTasks": pending, "examinedTasks": pending, "activeWorkers": 0,
                "examinedWorkers": 0, "truncated": False, "overflowTasks": 0, "overflowWorkers": 0,
                "churnComparable": t > 0, "observedNewTasks": new, "observedMissingTasks": missing,
                "collectionNanos": 3_000_000,
                "groups": [{"key": {"plugin": "Alpha", "taskImplementationClass": "CraftTask"},
                            "syncPending": spells, "asyncPending": 0, "cancelledPending": 0, "activeWorkers": 0},
                           {"key": {"plugin": "Beta", "taskImplementationClass": "CraftTask"},
                            "syncPending": 5, "asyncPending": 1, "cancelledPending": 0, "activeWorkers": 0}]}
    snaps = [snap(100, 105, 0, 0, 100), snap(1100, 106, 5, 4, 101), snap(2200, 905, 800, 1, 900),
             snap(3100, 120, 2, 787, 115), snap(4100, 121, 3, 2, 116)]
    return {**header("scheduler", schema), "igniteAvailability": "Ignite available", "igniteComplete": True,
            "errors": 0, "totalCollectionNanos": 15_000_000, "snapshots": snaps,
            "limits": {"sampleIntervalMillis": 1000},
            "ignite": {"droppedEvents": 0, "droppedTaskIds": 0, "inFlightAtStop": 0, "limits": {},
                       "groups": [{"event": "execution", "sync": True, "taskClass": "AlphaTask", "caller": "",
                                   "owner": "Alpha", "counts": {"started": 10, "completed": 10, "throwing": 0,
                                                                "totalNanos": 50_000_000, "maxNanos": 9_000_000,
                                                                "firstOffsetMillis": 10, "lastOffsetMillis": 4900}}]
                       + ([{"event": "execution", "sync": True, "taskClass": "com.gamma.MenuTask", "caller": "",
                            "owner": "Gamma", "counts": {"started": 4, "completed": 4, "throwing": 0,
                                                         "totalNanos": 120_000_000, "maxNanos": 100_000_000,
                                                         "firstOffsetMillis": 10, "lastOffsetMillis": 4900}}]
                          if gamma or slow else []),
                       **(slow_ignite() if slow else {})},
            "complete": True}


def entities(schema: int, count: int = 4) -> dict[str, Any]:
    ents = [{"type": "ITEM_DISPLAY" if i % 2 else "MARKER", "world": "world", "ownerHints": ["pdc:alpha"],
             "trackedHumans": 2, "entityId": 1000 + i} for i in range(count)]
    return {**header("entities", schema), "resumable": False, "entityCount": count,
            "uniqueEntitiesAttempted": count, "elapsedMillis": 4000, "startMspt": 30.0, "endMspt": 31.0,
            "totalSliceNanos": 1_000_000, "maxSliceNanos": 100_000,
            "coverage": [{"world": "world", "snapshotAvailable": True, "snapshotChunks": 10, "visitedChunks": 10,
                          "completedChunks": 10, "skippedChunks": 0, "unvisitedChunks": 0, "partialChunks": 0,
                          "attemptedEntities": count, "sampledEntities": count}],
            "entities": ents,
            "groups": [{"group": {"world": "world", "chunkX": 1, "chunkZ": 2, "type": "ITEM_DISPLAY",
                                  "ownerHints": ["pdc:alpha"]}, "entities": count, "trackedHumanSubscriptions": 2 * count,
                        "untrackedEntities": 0}],
            "sections": []}


def probe(sections: dict[str, tuple[int, float, float]], *, series: bool = True, omitted: int = 0,
          levels: dict[str, Any] | None = None, key_series: bool = True) -> dict[str, Any]:
    """sections: name -> (calls, self_ms, total_ms)."""
    rows = []
    for name, (calls, self_ms, total_ms) in sections.items():
        hist = [0] * 41
        hist[17] = calls  # ~131 us..262 us
        rows.append({"name": name, "calls": calls, "totalNanos": int(total_ms * 1e6), "selfNanos": int(self_ms * 1e6),
                     "meanNanos": int(total_ms * 1e6 / max(calls, 1)), "maxNanos": 3_000_000, "maxAtMillis": 2100,
                     "maxAtTick": 142, "p50Nanos": 262144, "p95Nanos": 524288, "p99Nanos": 1048576,
                     "histogramLog2": hist})
    m: dict[str, Any] = {
        "schema": "sneakydiagnostics.probe/1", "observedMillis": 5000, "sections": rows,
        "slowest": [{"section": rows[0]["name"], "nanos": 3_000_000, "atMillis": 2100, "atTick": 142}],
        "counts": {"energy.arrived": 7}, "levels": levels if levels is not None else {
            "placed": {"samples": 5, "mean": 3.0, "min": 1, "max": 5, "last": 4},
            "entities.owned": {"samples": 5, "mean": 10.0, "min": 10, "max": 10, "last": 10},
            "entities.owned.item_display": {"samples": 5, "mean": 6.0, "min": 6, "max": 6, "last": 6}},
        "nestingErrors": 0, "omittedKeys": omitted, "maxKeys": 256}
    if series:
        # the first (busiest) section spikes in window 2 (the slow timeline window)
        m["series"] = {"windowMillis": 1000, "sections": {
            rows[0]["name"]: {"calls": [10, 10, 60, 10, 10], "nanos": [1_000_000, 1_000_000, 90_000_000, 1_000_000, 1_000_000]}}}
        if key_series:
            # energy arrives in windows 0 and 2; "placed" is 3 for two windows, 5 in the slow window 2, unsampled in 3
            m["series"]["counts"] = {"energy.arrived": {"windows": [0, 2], "n": [2, 5]}}
            m["series"]["levels"] = {"placed": {"from": [0, 2, 4], "to": [1, 2, 4], "values": [3, 5, 3]}}
            m["series"]["omitted"] = {"counts": [], "levels": ["entities.owned"]}
    return m


def magicspells_v4() -> dict[str, Any]:
    """magicspells-execution-4 additions: per-second series, slow operations, counter coverage, lifecycle reasons."""
    m = magicspells()
    m["series"] = {"windowMillis": 1000, "rootNanos": [1e6, 1e6, 80e6, 1e6, 1e6], "rootCalls": [5, 5, 9, 5, 5],
                   "operations": {"event": {"calls": [4, 4, 4, 4, 4], "selfNanos": [1e6, 1e6, 2e6, 1e6, 1e6]},
                                  "task": {"calls": [1, 1, 5, 1, 1], "selfNanos": [0, 0, 70e6, 0, 0]}}}
    m["slowOperations"] = {"thresholdNanos": 1_000_000, "capacity": 512, "observed": 1, "retained": 1, "evicted": 0,
                           "complete": True, "records": [
                               {"operation": "task", "spell": "crafting2", "detail": "Task:delayed", "depth": 0,
                                "startOffsetMicros": 2_010_000, "endOffsetMicros": 2_080_000, "nanos": 70_000_000,
                                "selfNanos": 69_000_000, "thread": "server", "tick": 143, "unfinishedAtStop": False,
                                "endedAfterDeadline": False}]}
    m["counterCoverage"] = {"INVENTORY_SLOTS": "observed",
                            "MATCH_CALLS": "unobserved: item matching moved to SneakyItems (see its match.calls)"}
    m["taskLifecycle"] = {"inventoryComplete": False, "lifecycleComplete": False, "unknownOwnerEvents": 12,
                          "endObservedAfterDeadline": True, "endObservedLateMillis": 39,
                          "incompleteReasons": ["end_observed_after_deadline", "unattributed_owner_events"],
                          "groups": [{"spell": "[unattributed]", "origin": "com.nisovin.magicspells.util.managers.X#save:1",
                                      "registrations": 0, "executions": 10, "callbackCompletions": 0},
                                     {"spell": "[unattributed]", "origin": "com.nisovin.magicspells.spells.targeted.Y#<init>:2",
                                      "registrations": 1, "executions": 1, "callbackCompletions": 0}]}
    return m


def magicspells(*, detail_complete: bool = True) -> dict[str, Any]:
    rows = [{"operation": "event", "spell": "Fire-Ball", "detail": "L.on", "calls": 10, "selfNanos": 6_000_000,
             "totalNanos": 7_000_000, "maxNanos": 1_000_000, "counters": {}},
            {"operation": "spell", "spell": "fire-ball", "detail": "execute", "calls": 5, "selfNanos": 4_000_000,
             "totalNanos": 5_000_000, "maxNanos": 1_000_000, "counters": {}},
            {"operation": "event", "spell": "[unattributed]", "detail": "M.on", "calls": 3, "selfNanos": 1_000_000,
             "totalNanos": 1_000_000, "maxNanos": 500_000, "counters": {}}]
    return {"elapsedNanos": 5_000_000_000, "unknownOwnerScopes": 10, "totals": {
        "selfNanos": 11_000_000, "totalNanos": 13_000_000, "calls": 18, "maxNanos": 1_000_000, "counters": {}},
        "operationTotals": [{"operation": "event", "selfNanos": 7_000_000, "totalNanos": 8_000_000, "calls": 13,
                             "maxNanos": 1, "counters": {}}],
        "spellTotals": [{"spell": "fire-ball", "selfNanos": 4_000_000, "totalNanos": 5_000_000, "calls": 5,
                         "maxNanos": 1, "counters": {}}],
        "rows": rows, "omittedDetailRows": 0, "droppedScopes": 0, "totalsComplete": True,
        "operationTotalsComplete": True, "spellTotalsComplete": False, "detailComplete": detail_complete,
        "notes": "fixture"}


def freeform_voice() -> dict[str, Any]:
    return {"microphoneEvents": 100, "unstartedSchedulesAtBoundary": 2,
            "handlerWallTime": {"totalNanos": 5_000_000, "samples": 100, "maxNanos": 200_000},
            "modes": [{"mode": "normal", "queueDelay": {"totalNanos": 9_000_000, "samples": 50, "maxNanos": 1}}]}


# ---------------------------------------------------------------- writers

def write_json(path: Path, doc: Any, gz: bool = False) -> Path:
    data = json.dumps(doc).encode()
    if gz:
        path = path.with_name(path.name + ".gz")
        path.write_bytes(gzip.compress(data))
    else:
        path.write_bytes(data)
    return path


def v1_capture(root: Path, name: str = "v1", *, providers: list[dict[str, Any]] | None = None) -> Path:
    d = root / name
    d.mkdir(parents=True)
    write_json(d / "timeline-1.json", timeline(1))
    write_json(d / "scheduler-1.json", scheduler(1))
    write_json(d / "entities-1.json", entities(1))
    provs = providers if providers is not None else [
        {"plugin": "MagicSpells", "pluginVersion": "1", "instrumentationVersion": "magicspells-execution-2",
         "started": True, "reason": "completed", "complete": False, "measurements": magicspells()},
        {"plugin": "SneakyVoiceChat", "pluginVersion": "1", "instrumentationVersion": "voice-routing-1",
         "started": True, "reason": "completed", "complete": False, "measurements": freeform_voice()}]
    write_json(d / "plugins-1.json", {**header("plugins", 1), "providers": provs, "complete": False, "notes": "x"})
    return d


def packets(available: bool = True, sections: bool = True, **tracker_extra: Any) -> dict[str, Any]:
    """packets-1.json with a tracker section (displays send in every window, the NPC group spikes in window 2) and,
    unless ``sections`` is False (a companion with only the tracker probe), the packets probe's sections."""
    doc = {**header("packets", 2), "startedAt": START, "igniteAvailability": "Ignite available, idle", "errors": 0,
           "notes": "x"}
    if not available:
        return {**doc, "igniteAvailability": "unavailable (companion not installed)", "complete": False,
                "packetsAvailability": "unavailable (companion not installed)"}

    def group(etype: str, owner: str, ents: int, calls: int, nanos: int, meta: int, max_n: int) -> dict[str, Any]:
        hist = [0] * 41
        hist[12] = calls  # 4096..8191 ns
        return {"entityType": etype, "owner": owner, "entities": ents, "calls": calls, "totalNanos": nanos,
                "metadataCalls": calls // 2, "metadataNanos": meta, "maxNanos": max_n, "histogramLog2": hist}

    groups = [group("PLAYER", "class:net.citizensnpcs.EntityHumanNPC", 2, 200, 9_000_000, 1_000_000, 900_000),
              group("ITEM_DISPLAY", "pdc:sneakygrounditems", 100, 1000, 6_000_000, 4_000_000, 50_000),
              group("ZOMBIE", "(none)", 5, 100, 1_000_000, 0, 20_000)]
    npc = [1_000_000, 1_000_000, 5_000_000, 1_000_000, 1_000_000, 0]
    disp = [1_200_000] * 5 + [0]
    zomb = [200_000] * 5 + [0]
    tracker = {"observedMillis": 5000, "calls": 1300, "totalNanos": 16_000_000, "metadataCalls": 650,
               "metadataNanos": 5_000_000, "entities": 107, "offThreadCalls": 0, "lateCalls": 0, "labelErrors": 0,
               "maxGroups": 512, "overflowEntities": 0, "complete": True, "groups": groups,
               "series": {"windowMillis": 1000, "calls": [260] * 5 + [0],
                          "nanos": [a + b + c for a, b, c in zip(npc, disp, zomb)],
                          "groups": [{"entityType": "PLAYER", "owner": "class:net.citizensnpcs.EntityHumanNPC", "nanos": npc},
                                     {"entityType": "ITEM_DISPLAY", "owner": "pdc:sneakygrounditems", "nanos": disp},
                                     {"entityType": "ZOMBIE", "owner": "(none)", "nanos": zomb}]}}
    tracker.update(tracker_extra)
    return {**doc, "tracker": tracker, **(packet_sections() if sections else {}),
            "packetsAvailability": "Ignite packets available, idle" if sections else "unavailable (companion without the packets probe)"}


def _hist(bucket: int, n: int) -> list[int]:
    h = [0] * 41
    h[bucket] = n
    return h


def packet_sections(**outbound_extra: Any) -> dict[str, Any]:
    """The packets probe's sections: entity data and movement dominate outbound, use-item spikes inbound in window 2.

    Sends inside sendChanges (update + metadata, 10 ms) stay below the tracker section's 16 ms.
    """
    p = "net.minecraft.network.protocol."
    sed, move, bundle = p + "game.ClientboundSetEntityDataPacket", p + "game.ClientboundMoveEntityPacket$Pos", p + "game.ClientboundBundlePacket"

    def cls(name: str, sends: int, nanos: int, max_n: int, bucket: int, other: int = 0, other_n: int = 0, bundled: int = 0):
        return {"packetClass": name, "sends": sends + other, "serverThreadSends": sends, "otherThreadSends": other,
                "totalNanos": nanos, "maxNanos": max_n, "otherThreadNanos": other_n, "bundled": bundled,
                "histogramLog2": _hist(bucket, sends)}

    zeros = [0] * 6
    sed_ns, move_ns, bundle_ns = [600_000, 600_000, 1_600_000, 600_000, 600_000, 0], [1_200_000] * 5 + [0], [600_000] * 5 + [0]
    outbound = {"observedMillis": 5000, "sends": 10_250, "serverThreadSends": 10_100, "otherThreadSends": 150,
                "serverThreadNanos": 13_000_000, "classNanos": 13_000_000, "nestedSends": 0, "otherThreadNanos": 300_000,
                "bundles": 100, "bundledPackets": 100, "lateSends": 0, "maxClasses": 512, "classOverflow": False,
                "complete": True,
                "classes": [cls(move, 6000, 6_000_000, 20_000, 9), cls(sed, 4000, 4_000_000, 50_000, 9),
                            cls(bundle, 100, 3_000_000, 90_000, 14),
                            cls(p + "common.ClientboundKeepAlivePacket", 0, 0, 0, 0, other=150, other_n=300_000),
                            cls(p + "game.ClientboundAddEntityPacket", 0, 0, 0, 0, bundled=100)],
                "series": {"windowMillis": 1000, "sends": [2020] * 5 + [0],
                           "nanos": [a + b + c for a, b, c in zip(sed_ns, move_ns, bundle_ns)],
                           "classes": [{"packetClass": move, "sends": [1200] * 5 + [0], "nanos": move_ns},
                                       {"packetClass": sed, "sends": [800] * 5 + [0], "nanos": sed_ns},
                                       {"packetClass": bundle, "sends": [20] * 5 + [0], "nanos": bundle_ns}]}}
    outbound.update(outbound_extra)

    def cause(name: str, packets_: int, broadcasts: int, nanos: int, max_n: int = 0) -> dict[str, Any]:
        row = {"cause": name, "packets": packets_, "totalNanos": nanos, "maxNanos": max_n}
        if name != "other":
            row["broadcasts"] = broadcasts
        return row

    causes = {"maxDepth": 32, "depthOverflow": 0, "nestingErrors": 0, "complete": True,
              "rows": [cause("tracker.update", 6000, 3000, 6_000_000, 20_000), cause("tracker.metadata", 4000, 4000, 4_000_000, 50_000),
                       cause("tracker.spawn", 100, 100, 3_000_000, 90_000), cause("tracker.despawn", 0, 20, 0),
                       cause("other", 0, 0, 0), cause("entity.event", 0, 0, 0), cause("broadcast", 0, 0, 0),
                       cause("particles", 0, 0, 0), cause("chunks", 0, 0, 0)],
              "series": {"windowMillis": 1000, "causes": [
                  {"cause": "tracker.update", "packets": [1200] * 5 + [0], "nanos": move_ns},
                  {"cause": "tracker.metadata", "packets": [800] * 5 + [0], "nanos": sed_ns},
                  {"cause": "tracker.spawn", "packets": [20] * 5 + [0], "nanos": bundle_ns},
                  *({"cause": c, "packets": zeros, "nanos": zeros} for c in ("tracker.despawn", "other", "entity.event",
                                                                            "broadcast", "particles", "chunks"))]}}

    def cell(packets_: int, broadcasts: int, nanos: int) -> dict[str, int]:
        return {"packets": packets_, "broadcasts": broadcasts, "totalNanos": nanos}

    tracker_causes = {"entities": 148, "maxGroups": 512, "overflowEntities": 0, "labelErrors": 0, "spawnLookups": 120,
                      "spawnsAttributed": 100, "spawnLookupNanos": 2_400_000, "passThroughFrames": 100, "complete": True,
                      "groups": [
                          {"entityType": "ITEM_DISPLAY", "owner": "plugin:SneakyWartime", "entities": 100, "packets": 8000,
                           "broadcasts": 6000, "totalNanos": 8_000_000,
                           "causes": {"tracker.update": cell(4000, 2000, 4_000_000), "tracker.metadata": cell(4000, 4000, 4_000_000)}},
                          {"entityType": "PLAYER", "owner": "(player)", "entities": 48, "packets": 2100, "broadcasts": 1120,
                           "totalNanos": 5_000_000,
                           "causes": {"tracker.update": cell(2000, 1000, 2_000_000), "tracker.spawn": cell(100, 100, 3_000_000),
                                      "tracker.despawn": cell(0, 20, 0)}}]}
    use, mv = p + "game.ServerboundUseItemPacket", p + "game.ServerboundMovePlayerPacket$Pos"
    use_ns, mv_ns = [6_000_000, 6_000_000, 16_000_000, 6_000_000, 6_000_000, 0], [1_000_000] * 5 + [0]
    inbound = {"observedMillis": 5000, "packets": 5500, "totalNanos": 45_000_000, "betweenTicksPackets": 10,
               "betweenTicksNanos": 500_000, "offThreadPackets": 0, "latePackets": 0, "classOverflow": False, "complete": True,
               "classes": [{"packetClass": use, "packets": 500, "totalNanos": 40_000_000, "maxNanos": 2_000_000,
                            "betweenTicksPackets": 10, "betweenTicksNanos": 500_000, "histogramLog2": _hist(16, 500)},
                           {"packetClass": mv, "packets": 5000, "totalNanos": 5_000_000, "maxNanos": 20_000,
                            "betweenTicksPackets": 0, "betweenTicksNanos": 0, "histogramLog2": _hist(9, 5000)}],
               "series": {"windowMillis": 1000, "packets": [1100] * 5 + [0],
                          "nanos": [a + b for a, b in zip(use_ns, mv_ns)],
                          "classes": [{"packetClass": use, "packets": [100] * 5 + [0], "nanos": use_ns},
                                      {"packetClass": mv, "packets": [1000] * 5 + [0], "nanos": mv_ns}]}}
    return {"outbound": outbound, "causes": causes, "trackerCauses": tracker_causes, "inbound": inbound}


def v2_capture(root: Path, name: str = "v2", *, players: int = 30, entity_total: int = 1000, chunks: int = 500,
               gz: tuple[str, ...] = ("timeline",), probes: dict[str, dict[str, Any]] | None = None,
               extra_providers: dict[str, dict[str, Any]] | None = None, capture_id: str = CAPTURE_ID,
               provider_complete: bool = True, with_workload: bool = True, instrumentation: str = "alpha-1",
               failed_providers: tuple[str, ...] = (), gz_providers: tuple[str, ...] = (),
               planned_index_names: bool = False, slow: bool = False, gamma: bool = False,
               enabled: tuple[str, ...] = ("Alpha",), timeline_edit: Any = None, scheduler_edit: Any = None,
               plugins_collector: bool = True, entities_edit: Any = None, phases: dict[str, Any] | None = None,
               packets_report: dict[str, Any] | None = None) -> Path:
    """A v2 folder: manifest, index, per-provider files, optionally gzipped reports.

    ``gz_providers`` stores those providers' files compressed. The index names each file as stored, unless
    ``planned_index_names`` reproduces older writers that listed the uncompressed name.
    """
    d = root / name
    d.mkdir(parents=True)
    probes = probes if probes is not None else {"Alpha": probe({
        "tick": (100, 40.0, 60.0), "tick.watching": (100, 20.0, 20.0)})}
    files: list[dict[str, Any]] = []

    def add(fname: str, collector: str, doc: dict[str, Any], gzip_it: bool) -> str:
        p = write_json(d / fname, doc, gzip_it)
        raw = len(json.dumps(doc).encode())
        files.append({"name": p.name, "collector": collector, "segment": 1, "bytes": p.stat().st_size,
                      "uncompressedBytes": raw, "sha256": hashlib.sha256(p.read_bytes()).hexdigest(),
                      "complete": bool(doc.get("complete")), "reason": doc.get("reason"),
                      "writtenAt": "2026-01-01T12:00:06Z"})
        return p.name

    cid = {"captureId": capture_id}
    # timeline_edit/scheduler_edit: functions that change the report before it is written (and hashed)
    tl = {**timeline(2), **cid}
    if timeline_edit:
        timeline_edit(tl)
    add("timeline-1.json", "timeline", tl, "timeline" in gz)
    sc = {**scheduler(2, slow, gamma), **cid}
    if scheduler_edit:
        scheduler_edit(sc)
    add("scheduler-1.json", "scheduler", sc, "scheduler" in gz)
    en = {**entities(2), **cid}
    if entities_edit:
        entities_edit(en)
    add("entities-1.json", "entities", en, "entities" in gz)
    if phases is not None:  # a phases-1.json (2026-11), e.g. phases_report()
        add("phases-1.json", "phases", {**phases, **cid}, "phases" in gz)
    if packets_report is not None:
        add("packets-1.json", "packets", {**packets_report, **cid}, "packets" in gz)
    index_entries = []
    # plugins_collector=False: a capture without the plugins collector (e.g. scheduler only): no index, no provider files
    all_probes = {**probes, **(extra_providers or {})} if plugins_collector else {}
    for plugin, meas in all_probes.items():
        slug = plugin.lower()
        fname = f"plugins-{slug}-1.json"
        # Mirrors the Java writer: provider fields replace the header's; `error` is omitted when null and
        # `measurements` is omitted for a provider that failed.
        failed = plugin in failed_providers
        doc = {**header("plugins", 2, plugin=plugin, pluginVersion="1.0", instrumentationVersion=instrumentation,
                        captureId=capture_id), "complete": provider_complete and not failed}
        if failed:
            doc["error"] = "start failed: boom"
            doc["reason"] = "error"
        else:
            doc["measurements"] = meas
        stored = add(fname, "plugins", doc, plugin in gz_providers)
        entry = {"plugin": plugin, "pluginVersion": "1.0", "instrumentationVersion": instrumentation,
                 "started": not failed, "reason": "error" if failed else "completed",
                 "complete": provider_complete and not failed,
                 "file": fname if planned_index_names else stored, "error": doc.get("error")}
        index_entries.append(entry)
    index = {**header("plugins", 2, captureId=capture_id), "selected": "all", "providers": index_entries,
             "incompleteProviders": [e["plugin"] for e in index_entries if not e["complete"]],
             "complete": not failed_providers, "notes": "x"}
    if plugins_collector:
        add("plugins-1.json", "plugins", index, False)
    manifest: dict[str, Any] = {
        "schemaVersion": 2, "captureId": capture_id, "captureStartedAt": START, "requestedDurationSeconds": 5,
        "updatedAt": "2026-01-01T12:00:07Z",
        "server": {"version": "Paper test", "javaVersion": "25", "diagnosticsVersion": "2026.01.01-abc",
                   "plugins": [{"name": name, "version": "1.0"} for name in enabled]},
        "workload": {"atStart": {"players": players, "worlds": [
            {"name": "world", "loadedChunks": chunks, "entities": entity_total}]}, "atEnd": None}
        if with_workload else None,
        "files": files}
    (d / "manifest.json").write_text(json.dumps(manifest))
    return d
