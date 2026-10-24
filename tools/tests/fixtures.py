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


def scheduler(schema: int) -> dict[str, Any]:
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
                                                                "firstOffsetMillis": 10, "lastOffsetMillis": 4900}}]},
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
          levels: dict[str, Any] | None = None) -> dict[str, Any]:
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
        {"plugin": "SneakyConfigurableVoice", "pluginVersion": "1", "instrumentationVersion": "voice-routing-1",
         "started": True, "reason": "completed", "complete": False, "measurements": freeform_voice()}]
    write_json(d / "plugins-1.json", {**header("plugins", 1), "providers": provs, "complete": False, "notes": "x"})
    return d


def v2_capture(root: Path, name: str = "v2", *, players: int = 30, entity_total: int = 1000, chunks: int = 500,
               gz: tuple[str, ...] = ("timeline",), probes: dict[str, dict[str, Any]] | None = None,
               extra_providers: dict[str, dict[str, Any]] | None = None, capture_id: str = CAPTURE_ID,
               provider_complete: bool = True, with_workload: bool = True, instrumentation: str = "alpha-1",
               failed_providers: tuple[str, ...] = ()) -> Path:
    """A v2 folder: manifest, index, per-provider files, optionally gzipped reports."""
    d = root / name
    d.mkdir(parents=True)
    probes = probes if probes is not None else {"Alpha": probe({
        "tick": (100, 40.0, 60.0), "tick.watching": (100, 20.0, 20.0)})}
    files: list[dict[str, Any]] = []

    def add(fname: str, collector: str, doc: dict[str, Any], gzip_it: bool) -> None:
        p = write_json(d / fname, doc, gzip_it)
        raw = len(json.dumps(doc).encode())
        files.append({"name": p.name, "collector": collector, "segment": 1, "bytes": p.stat().st_size,
                      "uncompressedBytes": raw, "sha256": hashlib.sha256(p.read_bytes()).hexdigest(),
                      "complete": bool(doc.get("complete")), "reason": doc.get("reason"),
                      "writtenAt": "2026-01-01T12:00:06Z"})

    cid = {"captureId": capture_id}
    add("timeline-1.json", "timeline", {**timeline(2), **cid}, "timeline" in gz)
    add("scheduler-1.json", "scheduler", {**scheduler(2), **cid}, "scheduler" in gz)
    add("entities-1.json", "entities", {**entities(2), **cid}, "entities" in gz)
    index_entries = []
    all_probes = {**probes, **(extra_providers or {})}
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
        add(fname, "plugins", doc, False)
        entry = {"plugin": plugin, "pluginVersion": "1.0", "instrumentationVersion": instrumentation,
                 "started": not failed, "reason": "error" if failed else "completed",
                 "complete": provider_complete and not failed, "file": fname, "error": doc.get("error")}
        index_entries.append(entry)
    index = {**header("plugins", 2, captureId=capture_id), "selected": "all", "providers": index_entries,
             "incompleteProviders": [e["plugin"] for e in index_entries if not e["complete"]],
             "complete": not failed_providers, "notes": "x"}
    add("plugins-1.json", "plugins", index, False)
    manifest: dict[str, Any] = {
        "schemaVersion": 2, "captureId": capture_id, "captureStartedAt": START, "requestedDurationSeconds": 5,
        "updatedAt": "2026-01-01T12:00:07Z",
        "server": {"version": "Paper test", "javaVersion": "25", "diagnosticsVersion": "2026.01.01-abc",
                   "plugins": [{"name": "Alpha", "version": "1.0"}]},
        "workload": {"atStart": {"players": players, "worlds": [
            {"name": "world", "loadedChunks": chunks, "entities": entity_total}]}, "atEnd": None}
        if with_workload else None,
        "files": files}
    (d / "manifest.json").write_text(json.dumps(manifest))
    return d
