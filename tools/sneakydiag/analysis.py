"""Capture-level analysis: inventory, timeline, scheduler, entities, spark metadata, providers."""
from __future__ import annotations

import collections
from dataclasses import dataclass
from typing import Any

from .correlate import correlate
from .loader import Capture
from .providers import analyze_provider
from .util import ms, num, pct, scan_flags, top

STANDARD_COLLECTORS = ("timeline", "scheduler", "entities", "plugins", "spark", "connections")


@dataclass
class Options:
    top: int = 15  # rows per ranking
    slow_ms: float = 100.0  # a timeline window is "slow" when its maxMspt reaches this
    top_windows: int = 10  # slow windows to correlate


def analyze(cap: Capture, opts: Options | None = None) -> dict[str, Any]:
    opts = opts or Options()
    timeline = analyze_timeline(cap)
    providers = [analyze_provider(p, opts.top) for p in cap.providers]
    result: dict[str, Any] = {
        "tool": "sneakydiag", "captureId": cap.capture_id, "schemaVersion": cap.schema_version,
        "path": cap.path.name, "header": cap.header,
        "workload": (cap.manifest or {}).get("workload"),
        "warnings": list(cap.warnings),
        "inventory": analyze_inventory(cap, providers),
        "timeline": timeline,
        "providers": providers,
        "scheduler": analyze_scheduler(cap, opts.top),
        "entities": analyze_entities(cap, opts.top),
        "spark": analyze_spark_meta(cap),
        "connections": analyze_connections(cap),
        "options": {"top": opts.top, "slowMs": opts.slow_ms, "topWindows": opts.top_windows},
    }
    result["correlation"] = correlate(cap, timeline, result["scheduler"], opts)
    return result


# ------------------------------------------------------------------ inventory

def analyze_inventory(cap: Capture, providers: list[dict[str, Any]]) -> dict[str, Any]:
    files = [{"name": e.name, "collector": e.collector, "segment": e.segment, "bytes": e.size,
              "status": e.status, "detail": e.detail, "manifestComplete": e.complete, "manifestReason": e.reason}
             for e in cap.files]
    collectors = []
    for name in STANDARD_COLLECTORS:
        for r in cap.reports.get(name, []):
            collectors.append({"collector": name, "segment": r.get("segment"), "reason": r.get("reason"),
                               "complete": r.get("complete"), "finishedAfterMillis": r.get("finishedAfterMillis"),
                               "coverageFlags": scan_flags(r, depth=1)})
    missing = [c for c in STANDARD_COLLECTORS if c not in cap.reports]
    return {
        "files": files, "collectors": collectors,
        "collectorsNotCaptured": missing,
        "selectedProviders": cap.selected,
        "indexIncompleteProviders": (cap.index or {}).get("incompleteProviders"),
        "incompleteProviders": [{"plugin": p["plugin"], "reason": p["reason"], "why": p.get("why", [])}
                                for p in providers if p.get("complete") is not True],
        "providerCount": len(providers),
        "noProvidersNote": ("No registered providers means no plugin measurements, not zero plugin cost."
                            if not providers else None),
    }


# ------------------------------------------------------------------ timeline

def analyze_timeline(cap: Capture) -> dict[str, Any] | None:
    t = cap.report("timeline")
    if t is None:
        return None
    wins = [w for w in t.get("windows", []) if isinstance(w, dict)]
    ticks = sum(num(w.get("ticks")) for w in wins)
    observed = num(t.get("finishedAfterMillis"))
    window_ms = 1000
    seen = {int(num(w.get("fromMillis")) // window_ms) for w in wins}
    expected = int(observed // window_ms) + (1 if observed % window_ms else 0)
    gaps = sorted(set(range(expected)) - seen)
    rows = [{"index": int(num(w.get("fromMillis")) // window_ms), "fromMillis": w.get("fromMillis"),
             "toMillis": w.get("toMillis"), "ticks": num(w.get("ticks")), "meanMspt": round(num(w.get("meanMspt")), 2),
             "maxMspt": round(num(w.get("maxMspt")), 2), "over50ms": num(w.get("over50ms")),
             "over100ms": num(w.get("over100ms")), "firstTick": w.get("firstTick"), "lastTick": w.get("lastTick")}
            for w in wins]
    weighted = sum(num(w.get("ticks")) * num(w.get("meanMspt")) for w in wins) / ticks if ticks else None
    return {
        "reason": t.get("reason"), "complete": t.get("complete"), "observedMillis": observed,
        "ticks": ticks, "observedTicksPerSecond": round(ticks / (observed / 1000), 2) if observed else None,
        "tickWeightedMeanMs": round(weighted, 2) if weighted is not None else None,
        "minMs": round(min((num(w.get("minMspt")) for w in wins), default=0), 2),
        "maxMs": round(max((num(w.get("maxMspt")) for w in wins), default=0), 2),
        "over50": sum(num(w.get("over50ms")) for w in wins),
        "over100": sum(num(w.get("over100ms")) for w in wins),
        "over50Pct": pct(sum(num(w.get("over50ms")) for w in wins), ticks),
        "over100Pct": pct(sum(num(w.get("over100ms")) for w in wins), ticks),
        "windowCount": len(wins), "expectedWindows": expected, "gapWindows": gaps,
        "collectionOverheadMs": ms(num(t.get("totalCollectionNanos"))),
        "windows": rows,
        "note": ("Tick-end observations of Paper's tick duration; excludes idle sleep and remaining tick-end "
                 "listeners. Empty seconds are gaps (stalls or no tick end), not zero-cost ticks. "
                 "Observed ticks/s is not a TPS metric when ticks overrun."),
    }


# ------------------------------------------------------------------ scheduler

def analyze_scheduler(cap: Capture, n: int) -> dict[str, Any] | None:
    s = cap.report("scheduler")
    if s is None:
        return None
    snaps = [x for x in s.get("snapshots", []) if isinstance(x, dict)]
    pend = [num(x.get("pendingTasks")) for x in snaps]
    per_plugin: dict[str, dict[str, float]] = {}
    for x in snaps:
        for plugin, kinds in _pending_by_plugin(x).items():
            pp = per_plugin.setdefault(plugin, {"syncFirst": kinds["sync"], "syncLast": 0, "syncMax": 0,
                                                "asyncMax": 0, "cancelledMax": 0})
            pp["syncLast"] = kinds["sync"]
            pp["syncMax"] = max(pp["syncMax"], kinds["sync"])
            pp["asyncMax"] = max(pp["asyncMax"], kinds["async"])
            pp["cancelledMax"] = max(pp["cancelledMax"], kinds["cancelled"])
    plugins = [dict(plugin=k, **v) for k, v in per_plugin.items()]
    ignite = s.get("ignite") if isinstance(s.get("ignite"), dict) else None
    return {
        "reason": s.get("reason"), "complete": s.get("complete"), "errors": s.get("errors"),
        "igniteAvailability": s.get("igniteAvailability"), "igniteComplete": s.get("igniteComplete"),
        "snapshotCount": len(snaps), "sampleIntervalMillis": (s.get("limits") or {}).get("sampleIntervalMillis"),
        "pending": {"first": pend[0] if pend else None, "last": pend[-1] if pend else None,
                    "min": min(pend) if pend else None, "max": max(pend) if pend else None},
        "churn": {"comparableSnapshots": sum(1 for x in snaps if x.get("churnComparable")),
                  "newTasksObserved": sum(num(x.get("observedNewTasks")) for x in snaps if x.get("churnComparable")),
                  "missingTasksObserved": sum(num(x.get("observedMissingTasks")) for x in snaps
                                              if x.get("churnComparable")),
                  "note": "Churn between public snapshots; short-lived tasks between snapshots are missed."},
        "truncatedSnapshots": sum(1 for x in snaps if x.get("truncated")),
        "overflowTasks": sum(num(x.get("overflowTasks")) for x in snaps),
        "overflowWorkers": sum(num(x.get("overflowWorkers")) for x in snaps),
        "snapshotCollectionMs": {"total": ms(sum(num(x.get("collectionNanos")) for x in snaps)),
                                 "max": ms(max((num(x.get("collectionNanos")) for x in snaps), default=0)),
                                 "note": "Diagnostics' own overhead on the server thread."},
        "pendingByPlugin": top(plugins, "syncMax", n),
        "ignite": analyze_ignite(ignite, n) if ignite else None,
        "snapshots": [{"observedAfterMillis": x.get("observedAfterMillis"), "pendingTasks": x.get("pendingTasks"),
                       "newTasks": x.get("observedNewTasks"), "missingTasks": x.get("observedMissingTasks"),
                       "churnComparable": x.get("churnComparable"),
                       "collectionMs": ms(num(x.get("collectionNanos"))),
                       "syncPendingByPlugin": {p: k["sync"] for p, k in _pending_by_plugin(x).items()}} for x in snaps],
        "flags": scan_flags(s, depth=1),
    }


def _pending_by_plugin(snapshot: dict[str, Any]) -> dict[str, dict[str, int]]:
    out: dict[str, dict[str, int]] = {}
    for g in snapshot.get("groups", []):
        if isinstance(g, dict) and isinstance(g.get("key"), dict):
            k = out.setdefault(str(g["key"].get("plugin")), {"sync": 0, "async": 0, "cancelled": 0})
            k["sync"] += int(num(g.get("syncPending")))
            k["async"] += int(num(g.get("asyncPending")))
            k["cancelled"] += int(num(g.get("cancelledPending")))
    return out


def analyze_ignite(ig: dict[str, Any], n: int) -> dict[str, Any]:
    groups = [g for g in ig.get("groups", []) if isinstance(g, dict) and isinstance(g.get("counts"), dict)]
    agg: dict[tuple[str, str, bool], dict[str, float]] = {}
    for g in groups:
        k = (str(g.get("owner")), str(g.get("event")), bool(g.get("sync")))
        a = agg.setdefault(k, {"groups": 0, "started": 0, "completed": 0, "throwing": 0, "totalNanos": 0, "maxNanos": 0})
        c = g["counts"]
        a["groups"] += 1
        a["started"] += num(c.get("started"))
        a["completed"] += num(c.get("completed"))
        a["throwing"] += num(c.get("throwing"))
        a["totalNanos"] += num(c.get("totalNanos"))
        a["maxNanos"] = max(a["maxNanos"], num(c.get("maxNanos")))
    by_owner = [{"owner": o, "event": e, "sync": s, "groups": int(a["groups"]), "started": int(a["started"]),
                 "completed": int(a["completed"]), "throwing": int(a["throwing"]),
                 "totalMsInclusive": ms(a["totalNanos"]), "maxMs": ms(a["maxNanos"]), "_t": a["totalNanos"]}
                for (o, e, s), a in agg.items()]
    rows = [{"owner": g.get("owner"), "event": g.get("event"), "sync": g.get("sync"),
             "taskClass": g.get("taskClass"), "caller": g.get("caller"),
             "started": num(g["counts"].get("started")), "throwing": num(g["counts"].get("throwing")),
             "totalMsInclusive": ms(num(g["counts"].get("totalNanos"))), "maxMs": ms(num(g["counts"].get("maxNanos"))),
             "firstOffsetMillis": g["counts"].get("firstOffsetMillis"),
             "lastOffsetMillis": g["counts"].get("lastOffsetMillis"),
             "_t": num(g["counts"].get("totalNanos"))} for g in groups]
    strip = lambda rs: [{k: v for k, v in r.items() if k != "_t"} for r in rs]  # noqa: E731
    return {
        "droppedEvents": ig.get("droppedEvents"), "droppedTaskIds": ig.get("droppedTaskIds"),
        "inFlightAtStop": ig.get("inFlightAtStop"), "groupCount": len(groups), "limits": ig.get("limits"),
        "repeatedTaskCancellationRequests": ig.get("repeatedTaskCancellationRequests"),
        "byOwnerEvent": strip(top(by_owner, "_t", n * 2)),
        "topExecutionGroups": strip(top([r for r in rows if r["event"] == "execution"], "_t", n)),
        "topDeferredCancelScans": strip(top([r for r in rows if r["event"] == "deferred_cancel_scan"], "_t", 5)),
        "note": ("Ignite timings are inclusive wall time per group. Within one event kind and thread kind, "
                 "sums per owner are comparable; async executions run on workers and can exceed capture time; "
                 "creation/cancellation/deferred-scan timings overlap executions and must not be added to them."),
    }


# ------------------------------------------------------------------ entities

def analyze_entities(cap: Capture, n: int) -> dict[str, Any] | None:
    segs = cap.reports.get("entities")
    if not segs:
        return None
    last = segs[-1]
    types: collections.Counter[str] = collections.Counter()
    hints: collections.Counter[str] = collections.Counter()
    for seg in segs:
        for e in seg.get("entities", []):
            if isinstance(e, dict):
                types[str(e.get("type"))] += 1
                for h in e.get("ownerHints") or []:
                    hints[str(h)] += 1
    # groups: by (type, ownerHints) summing across chunks; tracked subscriptions are not unique viewers
    grp: dict[tuple[str, tuple[str, ...]], list[int]] = collections.defaultdict(lambda: [0, 0])
    for seg in segs:
        for g in seg.get("groups", []):
            if not isinstance(g, dict) or not isinstance(g.get("group"), dict):
                continue
            k = (str(g["group"].get("type")), tuple(g["group"].get("ownerHints") or ()))
            grp[k][0] += int(num(g.get("entities")))
            grp[k][1] += int(num(g.get("trackedHumanSubscriptions")))
    groups = [{"type": k[0], "ownerHints": list(k[1]), "entities": v[0], "trackedHumanSubscriptions": v[1]}
              for k, v in grp.items()]
    cov = [c for c in last.get("coverage", []) if isinstance(c, dict)]
    unreached = [c.get("world") for c in cov if not c.get("snapshotAvailable") or num(c.get("snapshotChunks")) < 0]
    partial = [c.get("world") for c in cov if num(c.get("unvisitedChunks")) > 0 or num(c.get("skippedChunks")) > 0
               or num(c.get("partialChunks")) > 0]
    return {
        "segments": len(segs), "reason": last.get("reason"), "complete": last.get("complete"),
        "entityCount": sum(int(num(s.get("entityCount"))) for s in segs),
        "uniqueEntitiesAttempted": last.get("uniqueEntitiesAttempted"),
        "coverage": cov, "worldsNotReached": unreached, "worldsPartiallyCovered": partial,
        "coverageNote": ("Census is incremental and per-world coverage is partial where listed; counts are "
                         "lower bounds for the whole server. Unreached worlds are unknown, not zero."
                         if unreached or partial else None),
        "startMspt": last.get("startMspt"), "endMspt": last.get("endMspt"),
        "scanOverheadMs": ms(sum(num(s.get("totalSliceNanos")) for s in segs)),
        "maxSliceMs": ms(max(num(s.get("maxSliceNanos")) for s in segs)),
        "typesByCount": dict(types.most_common(n)),
        "ownerHintsByCount": dict(hints.most_common(n)),
        "groupsByTrackedSubscriptions": top(groups, "trackedHumanSubscriptions", n),
        "note": ("Counts and owner hints identify candidates, not entity tick cost or proven ownership. "
                 "Tracked subscriptions are entity x human pairs, not unique viewers."),
    }


# ------------------------------------------------------------------ spark-1.json / connections

def analyze_spark_meta(cap: Capture) -> dict[str, Any] | None:
    r = cap.report("spark")
    if r is None:
        return None
    coverage = r.get("coverage")
    warn = []
    if r.get("owned") is not True or coverage != "dedicated_session":
        warn.append("profile is not a dedicated session: it can include samples outside the capture window; "
                    "check its own sampling windows (analyze_spark.py prints the overlap)")
    if not cap.has_profile:
        warn.append("profile.sparkprofile is not in the capture folder")
    keys = ("reason", "complete", "owned", "coverage", "engine", "mode", "background", "sparkVersion",
            "samplerStartTimeMillis", "exportAfterMillis", "snapshotAfterMillis", "profileFile", "skipped", "skipReason")
    return {**{k: r.get(k) for k in keys if k in r}, "profilePresent": cap.has_profile, "warnings": warn}


def analyze_connections(cap: Capture) -> dict[str, Any] | None:
    r = cap.report("connections")
    if r is None:
        return None
    return {"reason": r.get("reason"), "complete": r.get("complete"), "flags": scan_flags(r, depth=1),
            "note": "Connections reports are summarized only by completion and coverage flags here; read the "
                    "file for ping percentiles. Missing player rows mean unobserved, not zero latency."}
