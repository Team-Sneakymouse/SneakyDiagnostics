"""Plain-text rendering of the analysis dict. Observations and hypotheses are labelled separately."""
from __future__ import annotations

from typing import Any, Iterable

from .util import num


def table(rows: Iterable[dict[str, Any]], cols: list[tuple[str, str]], indent: str = "  ") -> list[str]:
    """Render dict rows as an aligned table. ``cols`` = [(key, header)]; numbers right-aligned."""
    rows = list(rows)
    if not rows:
        return [indent + "(none)"]
    cells = [[_fmt(r.get(k)) for k, _ in cols] for r in rows]
    widths = [max(len(h), *(len(c[i]) for c in cells)) for i, (_, h) in enumerate(cols)]
    left = [not _is_num(rows[0].get(k)) for k, _ in cols]
    def line(vals: list[str]) -> str:
        return indent + "  ".join(v.ljust(w) if lf else v.rjust(w) for v, w, lf in zip(vals, widths, left)).rstrip()
    return [line([h for _, h in cols]), *(line(c) for c in cells)]


def _is_num(v: Any) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def _fmt(v: Any) -> str:
    if v is None:
        return "-"
    if isinstance(v, bool):
        return "yes" if v else "no"
    if isinstance(v, float):
        return f"{v:,.3f}".rstrip("0").rstrip(".") if abs(v) < 1000 else f"{v:,.0f}"
    if isinstance(v, int):
        return f"{v:,}"
    if isinstance(v, list):
        return ",".join(map(str, v)) or "-"
    return str(v)


def render(r: dict[str, Any]) -> str:
    o: list[str] = []
    h = r["header"]
    o.append(f"SneakyDiagnostics capture {r['captureId']}  (schema v{r['schemaVersion']})")
    o.append(f"  started {h.get('captureStartedAt')}  requested {h.get('requestedDurationSeconds')}s  "
             f"server {h.get('serverVersion')}  diagnostics {h.get('diagnosticsVersion')}")
    if r.get("workload"):
        o.append("  workload (manifest): " + _workload(r["workload"]))
    if r["warnings"]:
        o.append("")
        o.append("WARNINGS (read before trusting numbers)")
        o.extend(f"  ! {w}" for w in r["warnings"])
    o.extend(_inventory(r["inventory"]))
    o.extend(_timeline(r["timeline"]))
    for p in r["providers"]:
        o.extend(_provider(p))
    o.extend(_scheduler(r["scheduler"]))
    o.extend(_listeners(r.get("listeners")))
    o.extend(_phases(r.get("phases")))
    o.extend(_tracker(r.get("tracker")))
    o.extend(_packets(r.get("packets")))
    o.extend(_entities(r["entities"]))
    o.extend(_spawns(r.get("spawns")))
    o.extend(_spark(r["spark"]))
    o.extend(_correlation(r["correlation"]))
    o.extend(_coverage(r.get("coverage")))
    o.extend(_leads(r))
    return "\n".join(o) + "\n"


def _workload(w: dict[str, Any]) -> str:
    def one(x: Any) -> str:
        if not isinstance(x, dict):
            return "unknown"
        worlds = ", ".join(f"{k.get('name')}: {k.get('loadedChunks')} chunks/{k.get('entities')} entities"
                           for k in x.get("worlds", []))
        return f"{x.get('players')} players; {worlds}"
    return f"start [{one(w.get('atStart'))}]  end [{one(w.get('atEnd')) if w.get('atEnd') else 'not recorded'}]"


def _head(title: str) -> list[str]:
    return ["", title, "=" * len(title)]


def _inventory(inv: dict[str, Any]) -> list[str]:
    o = _head("Inventory")
    o += table(inv["files"], [("name", "file"), ("bytes", "bytes"), ("status", "status"), ("manifestReason", "reason"),
                              ("manifestComplete", "complete")])
    o.append("  collectors:")
    o += table(inv["collectors"], [("collector", "collector"), ("segment", "seg"), ("reason", "reason"),
                                   ("complete", "complete"), ("finishedAfterMillis", "after ms"),
                                   ("coverageFlags", "coverage flags"), ("detail", "detail")], "    ")
    if inv["collectorsNotCaptured"]:
        o.append("  not captured: " + ", ".join(inv["collectorsNotCaptured"]) + " (absent, not zero)")
    if inv["selectedProviders"] is not None:
        o.append(f"  provider selection: {inv['selectedProviders']}")
    if inv["noProvidersNote"]:
        o.append("  " + inv["noProvidersNote"])
    if inv["incompleteProviders"]:
        o.append("  INCOMPLETE PROVIDERS (coverage gaps; measured values are lower bounds / partial):")
        for p in inv["incompleteProviders"]:
            o.append(f"    - {p['plugin']} (reason={p['reason']}): " + ("; ".join(p["why"]) or "no cause recorded"))
    return o


def _timeline(t: dict[str, Any] | None) -> list[str]:
    o = _head("Timeline")
    if t is None:
        return o + ["  no timeline report"]
    o.append(f"  OBSERVATION  {t['ticks']:,.0f} tick ends in {t['observedMillis'] / 1000:.1f}s "
             f"= {t['observedTicksPerSecond']} observed ticks/s   reason={t['reason']} complete={t['complete']}")
    o.append(f"  tick duration (ms): tick-weighted mean {t['tickWeightedMeanMs']}, min {t['minMs']}, max {t['maxMs']}")
    o.append(f"  over 50 ms: {t['over50']:,.0f} ticks ({t['over50Pct']}%)   over 100 ms: {t['over100']:,.0f} ticks "
             f"({t['over100Pct']}%)")
    o.append(f"  windows: {t['windowCount']} of {t['expectedWindows']} one-second windows have ticks"
             + (f"; EMPTY windows (stall or no tick end, not zero cost): {t['gapWindows']}" if t["gapWindows"] else ""))
    o.append(f"  timeline collector overhead: {t['collectionOverheadMs']} ms total")
    o.append("  " + t["note"])
    return o


def _provider(p: dict[str, Any]) -> list[str]:
    o = _head(f"Provider {p['plugin']} {p.get('pluginVersion') or ''} [{p.get('instrumentationVersion') or '?'}]")
    o.append(f"  kind={p['kind']} reason={p['reason']} complete={p['complete']}"
             + (f" file={p['file']}" if p.get("file") else ""))
    if p.get("why"):
        o.append("  INCOMPLETE/UNAVAILABLE: " + "; ".join(p["why"]))
    if p["kind"] == "unavailable":
        o.append("  measurements unavailable: this is a coverage gap, not zero cost")
        return o
    if p["kind"] == "probe":
        o += _probe(p["probe"])
    elif p["kind"] == "magicspells":
        o += _magicspells(p["magicspells"])
        if p.get("timers"):
            o.append("  taskLifecycle timers (inclusive):")
            o += table(p["timers"], [("path", "path"), ("count", "count"), ("totalMsInclusive", "total ms"),
                                     ("maxMs", "max ms")], "    ")
    else:
        f = p["freeform"]
        o.append("  free-form provider; scalar fields: " + ", ".join(f"{k}={_fmt(v)}" for k, v in f["scalars"].items()))
        o.append("  timers ranked by inclusive total (overlap each other and scheduler timings):")
        o += table(f["timersByTotalInclusive"], [("path", "path"), ("count", "count"), ("totalMsInclusive", "total ms"),
                                                 ("meanMs", "mean ms"), ("maxMs", "max ms")], "    ")
        o.append("  " + f["note"])
    return o


def _probe(pr: dict[str, Any]) -> list[str]:
    o = [f"  probe/1 over {pr['observedMillis'] / 1000:.1f}s; {pr['sectionCount']} sections; "
         f"instrumented self time {pr['instrumentedSelfMs']} ms "
         f"({pr['instrumentedSelfSharePctOfObserved']}% of observed wall time; {pr['instrumentedSelfNote']})"]
    if pr["nestingErrors"] or pr["omittedKeys"]:
        o.append(f"  WARNING nestingErrors={pr['nestingErrors']:g} omittedKeys={pr['omittedKeys']:g} "
                 f"(maxKeys={pr['maxKeys']}): rankings are partial")
    for prob in pr["consistencyProblems"]:
        o.append("  WARNING inconsistent data: " + prob)
    cols = [("name", "section"), ("calls", "calls"), ("selfMs", "self ms"), ("totalMsInclusive", "total ms (incl.)"),
            ("meanMs", "mean ms"), ("p95MsUpperBound", "p95 ms<="), ("p99MsUpperBound", "p99 ms<="),
            ("maxMs", "max ms"), ("maxAtMillis", "max @ms"), ("maxAtTick", "max @tick")]
    o.append("  top by SELF time (exclusive; additive for nested sections):")
    o += table(pr["topBySelf"], cols, "    ")
    o.append("  top by TOTAL time (INCLUSIVE; overlaps parents/children, do not add):")
    o += table(pr["topByTotalInclusive"], cols, "    ")
    o.append("  percentiles are histogram bucket upper bounds (overestimate by < 2x)")
    o.append("  slowest single calls (capture offset / tick):")
    o += table(pr["slowest"], [("section", "section"), ("ms", "ms"), ("atMillis", "at ms"), ("atTick", "tick")], "    ")
    if pr["counts"]:
        o.append("  counts: " + ", ".join(f"{k}={_fmt(v)}" for k, v in list(pr["counts"].items())[:20]))
    if pr["levels"]:
        o.append("  levels (sampled gauges; samples/mean/min/max/last):")
        rows = [dict(name=k, **{c: v.get(c) for c in ("samples", "mean", "min", "max", "last")})
                for k, v in pr["levels"].items() if isinstance(v, dict)]
        o += table(rows, [("name", "level"), ("samples", "samples"), ("mean", "mean"), ("min", "min"),
                          ("max", "max"), ("last", "last")], "    ")
    if pr["ownedEntities"]:
        o.append("  entities owned by this provider (per-type counts; candidates, not tick cost): see levels "
                 "'entities.owned*' above")
    ks = pr.get("keySeries")
    has_keys = bool(ks and (ks["counts"] or ks["levels"]))
    o.append("  series: " + (f"{len(pr['seriesSections'])} sections at {pr['seriesWindowMillis']} ms windows"
                             if pr["hasSeries"] else
                             "no section series (only counts/levels can be matched to timeline windows)" if has_keys else
                             "NONE - cannot be correlated with timeline windows"))
    if ks is not None:
        left = ks["omitted"]["counts"] + ks["omitted"]["levels"]
        o.append(f"  count/level series: {len(ks['counts'])} counts, {len(ks['levels'])} levels"
                 + (f"; left out for the report budget ({len(left)}): {', '.join(left[:10])}"
                    + (", ..." if len(left) > 10 else "") if left else ""))
    return o


def _magicspells(m: dict[str, Any]) -> list[str]:
    t = m["totals"]
    o = [f"  totals: {t['calls']:,.0f} scopes, self {t['selfMs']:,.1f} ms, inclusive {t['totalMsInclusive']:,.1f} ms "
         f"(overlap; do not add) over {m['elapsedMs']:,.0f} ms",
         f"  completeness: totals={m['totalsComplete']} operations={m['operationTotalsComplete']} "
         f"spells={m['spellTotalsComplete']} detail={m['detailComplete']}  unknownOwnerScopes={m['unknownOwnerScopes']:,.0f}"]
    cols = [("spell", "spell"), ("calls", "calls"), ("selfMs", "self ms"), ("totalMsInclusive", "total ms (incl.)"),
            ("maxMs", "max ms")]
    o.append("  operations by self time:")
    o += table(m["operationTotals"], [("operation", "operation")] + cols[1:], "    ")
    o.append("  top spells by self time (spellTotals" + ("" if m["spellTotalsComplete"] else
                                                          "; INCOMPLETE, may mis-rank") + "):")
    o += table(m["topSpellTotalsBySelf"], cols, "    ")
    rec = m["recovery"]
    if rec["valid"]:
        o.append("  RECOVERED ranking (detail rows grouped by lower-cased spell; valid: detail complete and sums "
                 "to the global self total):")
        o += table(rec["topBySelf"], cols, "    ")
        o.append(f"  mixed-case rows: {rec['mixedCaseRows']} rows, {rec['mixedCaseCalls']:,.0f} calls, "
                 f"{rec['mixedCaseSelfMs']:,.1f} ms self (vs unknownOwnerScopes {rec['unknownOwnerScopes']:,.0f})")
    else:
        o.append("  recovery NOT possible: " + "; ".join(rec["blockers"]) + " (not ranking incomplete detail)")
    o.append("  top detail rows by self time:")
    o += table(m["topDetailRowsBySelf"], [("operation", "op"), ("spell", "spell"), ("detail", "detail"),
                                          ("calls", "calls"), ("selfMs", "self ms"), ("maxMs", "max ms")], "    ")
    o.append("  " + m["note"])
    o.append("  per-second series: " + (", ".join(m["seriesOperations"]) if m.get("hasSeries") else
                                        "NONE (cannot be placed in timeline windows)"))
    if m.get("lateScopes"):
        lt = m["lateScopes"]
        o.append(f"  scopes that ended after the deadline (kept apart from the windows): {lt['rootCalls']:g} outermost, "
                 f"{lt['outermostMs']} ms, the last ending at {lt['lastEndOffsetMillis']:g} ms")
    o += _slow("slow operations (capture offset / tick)", m.get("slowOperations"),
               [("operation", "op"), ("spell", "spell"), ("detail", "detail"), ("depth", "depth"), ("ms", "ms"),
                ("selfNanos", "self ns"), ("startMs", "start ms"), ("endMs", "end ms"), ("tick", "tick")])
    for counter, state in (m.get("counterCoverage") or {}).items():
        if state != "observed":
            o.append(f"  counter {counter}: {state}")
    t = m.get("taskLifecycle")
    if t:
        o.append(f"  task lifecycle: inventoryComplete={t['inventoryComplete']} lifecycleComplete={t['lifecycleComplete']}"
                 + (f"; reasons: {', '.join(t['incompleteReasons'])}" if t.get("incompleteReasons") else "")
                 + (f"; end inventory {t['endObservedLateMillis']} ms after the deadline"
                    if t.get("endObservedLateMillis") else ""))
        if t["unattributedByOrigin"]:
            o.append("  unattributed task events by scheduling origin:")
            o += table(t["unattributedByOrigin"], [("origin", "origin"), ("events", "events"), ("kind", "kind")], "    ")
            o.append("  " + t["note"])
    return o


def _slow(title: str, sec: dict[str, Any] | None, cols: list[tuple[str, str]], indent: str = "  ") -> list[str]:
    if not sec:
        return []
    if not sec.get("available"):
        return [f"{indent}{title}: {sec.get('reason')}"]
    gaps = []
    if sec.get("evicted"):
        gaps.append("shorter slow calls were left out")
    if sec.get("runningOtherThreads"):
        gaps.append(f"{sec['runningOtherThreads']:g} still running on other threads at stop (durations unknown)")
    if not sec.get("complete") and not gaps:
        gaps.append("the report records no cause")
    o = [f"{indent}{title}: {sec['observed']:,.0f} calls >= {sec['thresholdMs']} ms, {sec['retained']} kept "
         f"(capacity {sec['capacity']}), {sec['evicted']:,.0f} evicted"
         + ("" if sec.get("complete") else " - INCOMPLETE: " + "; ".join(gaps))
         + (f"; {sec['runningAroundStop']:g} enclosing the stop (expected, not a gap)" if sec.get("runningAroundStop") else "")
         + (f"; {sec['observedInvocations']:,} calls observed in total" if sec.get("observedInvocations") is not None else "")]
    o += table(sec["top"], cols, indent + "  ")
    return o


def _scheduler(s: dict[str, Any] | None) -> list[str]:
    o = _head("Scheduler")
    if s is None:
        return o + ["  no scheduler report"]
    p, c = s["pending"], s["churn"]
    o.append(f"  reason={s['reason']} complete={s['complete']} igniteComplete={s['igniteComplete']} "
             f"errors={s['errors']}  Ignite: {s['igniteAvailability']}")
    o.append(f"  OBSERVATION {s['snapshotCount']} public snapshots; pending tasks first {p['first']}, last {p['last']}, "
             f"min {p['min']}, max {p['max']}")
    o.append(f"  churn between comparable snapshots: +{c['newTasksObserved']:,.0f} new / -{c['missingTasksObserved']:,.0f} "
             f"missing over {c['comparableSnapshots']} pairs (not execution/cancellation rates)")
    if s["truncatedSnapshots"] or s["overflowTasks"]:
        o.append(f"  GAP truncated snapshots={s['truncatedSnapshots']} overflowTasks={s['overflowTasks']:g}: grouping is partial")
    o.append(f"  snapshot collection overhead: {s['snapshotCollectionMs']['total']} ms total, max {s['snapshotCollectionMs']['max']} ms (diagnostics' own cost)")
    o.append("  pending by owning plugin (sync/async/cancelled maxima over snapshots):")
    o += table(s["pendingByPlugin"], [("plugin", "plugin"), ("syncFirst", "sync first"), ("syncLast", "sync last"),
                                      ("syncMax", "sync max"), ("asyncMax", "async max"),
                                      ("cancelledMax", "cancelled max")], "    ")
    ig = s["ignite"]
    if ig:
        o.append(f"  Ignite groups={ig['groupCount']} droppedEvents={ig['droppedEvents']} droppedTaskIds={ig['droppedTaskIds']} "
                 f"inFlightAtStop={ig['inFlightAtStop']}")
        o.append("  Ignite by owner x event x thread (INCLUSIVE wall time):")
        o += table(ig["byOwnerEvent"], [("owner", "owner"), ("event", "event"), ("sync", "sync"), ("started", "started"),
                                        ("throwing", "throwing"), ("totalMsInclusive", "total ms (incl.)"),
                                        ("maxMs", "max ms")], "    ")
        o.append("  top execution groups:")
        o += table(ig["topExecutionGroups"], [("owner", "owner"), ("sync", "sync"), ("taskClass", "task class"),
                                              ("started", "started"), ("totalMsInclusive", "total ms (incl.)")], "    ")
        o.append("  " + ig["note"])
        o += _slow("Ignite slow task calls", ig.get("slowExecutions"),
                   [("event", "event"), ("owner", "owner"), ("taskClass", "task class"), ("ms", "ms"),
                    ("startMs", "start ms"), ("endMs", "end ms"), ("thread", "thread"), ("tick", "tick")])
        o += _slow("Ignite slow listener calls", ig.get("slowListeners"),
                   [("owner", "owner"), ("listenerClass", "listener"), ("eventClass", "event"), ("ms", "ms"),
                    ("startMs", "start ms"), ("thread", "thread"), ("tick", "tick")])
        running = ig.get("runningAtStop")
        if running and running.get("count"):
            o.append(f"  scheduler calls still running at stop: {running['count']} (no completed duration)")
            o += table(running.get("records", []), [("event", "event"), ("owner", "owner"), ("taskClass", "task class"),
                                                    ("startOffsetMicros", "start us"),
                                                    ("runningNanosAtStop", "running ns")], "    ")
    else:
        o.append("  no Ignite section: execution/cancellation attribution unavailable (public snapshots only)")
    return o


def _tracker(t: dict[str, Any] | None) -> list[str]:
    o = _head("Tracker sends (packets-1.json)")
    if t is None:
        return o + ["  not recorded (no packets collector in this capture; it needs the Ignite companion, 2026-11): tracker send time is absent, not zero"]
    o.append(f"  reason={t['reason']} complete={t['complete']}  Ignite: {t['igniteAvailability']}")
    if not t["available"]:
        o.append("  " + t["note"])
        if t.get("igniteError"):
            o.append(f"  error: {t['igniteError']}")
        return o
    o.append(f"  OBSERVATION {t['calls']:,.0f} sends of {t['entities']:,.0f} entities over {t['observedMillis'] / 1000:g}s: "
             f"{t['totalMs']} ms ({t['pctOfObservedWall']}% of observed wall time); metadata {t['metadataMs']} ms, "
             f"other {t['otherMs']} ms")
    if t["coverageFlags"] or t["problems"]:
        o.append("  GAP " + ", ".join(t["coverageFlags"] + t["problems"]))
    if t["peakWindow"]:
        pw = t["peakWindow"]
        o.append(f"  busiest second: window {pw['window']} with {pw['ms']} ms (median window {pw['medianMs']} ms)")
    o.append("  by entity type x owner hint (inclusive send time; metadata is part of total):")
    o += table(t["topGroups"], [("entityType", "type"), ("owner", "owner hint"), ("entities", "entities"),
                                ("calls", "sends"), ("totalMs", "total ms"), ("metadataMs", "metadata ms"),
                                ("sharePct", "%"), ("meanUs", "mean us"), ("maxMs", "max ms"),
                                ("p99MsUpper", "p99 ms<=")], "    ")
    o.append("  by entity type:")
    o += table(t["byType"], [("entityType", "type"), ("entities", "entities"), ("calls", "sends"),
                             ("totalMs", "total ms"), ("metadataMs", "metadata ms"), ("sharePct", "%")], "    ")
    o.append("  by owner hint:")
    o += table(t["byOwner"], [("owner", "owner hint"), ("entities", "entities"), ("calls", "sends"),
                              ("totalMs", "total ms"), ("metadataMs", "metadata ms"), ("sharePct", "%")], "    ")
    o.append("  " + t["note"])
    return o


def _packets(p: dict[str, Any] | None) -> list[str]:
    o = _head("Packets (packets-1.json: outbound classes, send causes, tracker owners, inbound)")
    if p is None:
        return o + ["  not recorded (no packets collector in this capture; it needs the Ignite companion, 2026-11): "
                    "packet counts and send time are absent, not zero"]
    o.append(f"  reason={p['reason']} complete={p['complete']}  Ignite: {p['availability']}")
    if not p["available"]:
        o.append("  " + p["note"])
        if p.get("error"):
            o.append(f"  error: {p['error']}")
        return o
    ob = p["outbound"]
    if ob is None:
        o.append("  outbound: not in this report (unmeasured, not zero)")
    else:
        o.append(f"  OBSERVATION {ob['sends']:,.0f} outbound sends ({ob['sendsPerSec']}/s; {ob['otherThreadSends']:,.0f} on other "
                 f"threads, {ob['otherThreadMs']} ms there) over {ob['observedMillis'] / 1000:g}s: {ob['serverThreadMs']} ms "
                 f"server-thread send time ({ob['pctOfObservedWall']}% of observed wall time, inclusive); "
                 f"{ob['bundles']:,.0f} bundles carrying {ob['bundledPackets']:,.0f} packets")
        flags = ([f"classOverflow (more than the companion's class slots)"] if ob["classOverflow"] else []) + ob["problems"]
        if ob["nestedSends"]:
            flags.append(f"{ob['nestedSends']:,.0f} sends nested in other sends (class times overlap; total counts outermost only)")
        if flags:
            o.append("  GAP " + "; ".join(flags))
        if ob["peakWindow"]:
            pw = ob["peakWindow"]
            o.append(f"  busiest second: window {pw['window']} with {pw['ms']} ms (median window {pw['medianMs']} ms)")
        o.append("  packet classes by server-thread send time (inclusive; bundled = sent inside a bundle, not a send):")
        o += table(ob["topClasses"], [("packetClass", "class"), ("sends", "sends"), ("sendsPerSec", "/s"),
                                      ("otherThreadSends", "other thr"), ("bundled", "bundled"), ("totalMs", "send ms"),
                                      ("sharePct", "%"), ("meanUs", "mean us"), ("p99MsUpper", "p99 ms<="),
                                      ("maxMs", "max ms")], "    ")
    ca = p["causes"]
    if ca is None:
        o.append("  causes: not in this report (unmeasured, not zero)")
    else:
        gap = [f"depthOverflow={ca['depthOverflow']:g}"] if ca["depthOverflow"] else []
        gap += [f"nestingErrors={ca['nestingErrors']:g}"] if ca["nestingErrors"] else []
        o.append("  send causes by send time (fan-out = packets per call of the cause):" + (f"  GAP {', '.join(gap)}" if gap else ""))
        o += table(ca["rows"], [("cause", "cause"), ("packets", "packets"), ("broadcasts", "calls"), ("fanOut", "fan-out"),
                                ("totalMs", "send ms"), ("sharePct", "%"), ("meanUs", "us/packet"),
                                ("usPerBroadcast", "us/call"), ("maxMs", "max ms")], "    ")
    tr = p["trackerOwners"]
    if tr is None:
        o.append("  tracker owners: not in this report (unmeasured, not zero)")
    else:
        gap = [k + "=" + f"{tr[k]:g}" for k in ("overflowEntities", "labelErrors") if tr[k]]
        o.append(f"  tracker causes by entity type x owner ({tr['totalMs']} ms send time; {tr['spawnsAttributed']:,.0f} of "
                 f"{tr['spawnLookups']:,.0f} spawns during the capture attributed to a plugin, lookups {tr['spawnLookupMs']} ms, "
                 f"{tr['passThroughFrames']:,.0f} pass-through frames skipped)" + (f"  GAP {', '.join(gap)}" if gap else "") + ":")
        o += table(tr["topGroups"], [("entityType", "type"), ("owner", "owner"), ("entities", "entities"),
                                     ("packets", "packets"), ("broadcasts", "calls"), ("fanOut", "fan-out"),
                                     ("totalMs", "send ms"), ("sharePct", "%"), ("updateMs", "update ms"),
                                     ("metadataMs", "metadata ms"), ("spawnMs", "spawn ms"), ("despawnMs", "despawn ms")], "    ")
        o.append("  tracker causes by owner:")
        o += table(tr["byOwner"], [("owner", "owner"), ("entities", "entities"), ("packets", "packets"),
                                   ("fanOut", "fan-out"), ("totalMs", "send ms"), ("sharePct", "%")], "    ")
        o.append("  " + tr["note"])
    ib = p["inbound"]
    if ib is None:
        o.append("  inbound: not in this report (unmeasured, not zero)")
    else:
        gap = (["classOverflow"] if ib["classOverflow"] else []) + \
              ([f"{ib['offThreadPackets']:,.0f} handled off the server thread (not timed)"] if ib["offThreadPackets"] else [])
        o.append(f"  inbound: {ib['packets']:,.0f} packets handled on the server thread, {ib['totalMs']} ms "
                 f"({ib['pctOfObservedWall']}% of observed wall time, inclusive of plugin events); "
                 f"{ib['betweenTicksPackets']:,.0f} of them between ticks ({ib['betweenTicksMs']} ms)"
                 + (f"  GAP {'; '.join(gap)}" if gap else ""))
        o += table(ib["topClasses"], [("packetClass", "class"), ("packets", "packets"), ("packetsPerSec", "/s"),
                                      ("totalMs", "handle ms"), ("sharePct", "%"), ("meanUs", "mean us"),
                                      ("p99MsUpper", "p99 ms<="), ("maxMs", "max ms"),
                                      ("betweenTicksPackets", "between ticks")], "    ")
    rel = p["relations"]
    parts = [f"outbound send {rel['outboundSendMs']} ms"
             + (f" ({rel['outboundPctOfMeasuredTicks']}% of measured tick time)" if rel["outboundPctOfMeasuredTicks"] is not None else "")]
    if rel["trackerSendChangesSendMs"] is not None:
        parts.append(f"sends inside sendChanges {rel['trackerSendChangesSendMs']} ms"
                     + (f" of {rel['trackerSectionMs']} ms Tracker sends ({rel['sendShareOfTrackerSectionPct']}%)"
                        if rel["trackerSectionMs"] is not None else ""))
        parts.append(f"pairing sends {rel['trackerPairingSendMs']} ms")
    if rel["trackerPhaseMs"] is not None:
        parts.append(f"tracker phases {rel['trackerPhaseMs']} ms (exclusive)")
    if rel["inboundMs"] is not None:
        parts.append(f"inbound {rel['inboundMs']} ms" + (f", tick-start-tasks phase {rel['tickStartPhaseMs']} ms"
                                                          if rel["tickStartPhaseMs"] is not None else ""))
    o.append("  relations: " + "; ".join(parts))
    o.append("  " + rel["note"])
    return o


def _entities(e: dict[str, Any] | None) -> list[str]:
    o = _head("Entity census")
    if e is None:
        return o + ["  no entities report"]
    o.append(f"  {e['segments']} segment(s); {e['entityCount']:,} entities sampled; reason={e['reason']} "
             f"complete={e['complete']}; MSPT at start/end {e['startMspt']}/{e['endMspt']}; "
             f"scan overhead {e['scanOverheadMs']} ms")
    o += table(e["coverage"], [("world", "world"), ("snapshotAvailable", "snapshot"), ("snapshotChunks", "chunks"),
                               ("completedChunks", "done"), ("skippedChunks", "skipped"),
                               ("unvisitedChunks", "unvisited"), ("partialChunks", "partial"),
                               ("sampledEntities", "entities")], "    ")
    if e["coverageNote"]:
        o.append("  GAP " + e["coverageNote"] + (f" Not reached: {e['worldsNotReached']}" if e["worldsNotReached"] else ""))
    o.append("  by type: " + ", ".join(f"{k}={v:,}" for k, v in e["typesByCount"].items()))
    o.append("  owner hints (candidate attribution only): " + ", ".join(f"{k}={v:,}" for k, v in e["ownerHintsByCount"].items()))
    o.append("  groups by tracked-human subscriptions (entity x human pairs, not unique viewers):")
    o += table(e["groupsByTrackedSubscriptions"], [("type", "type"), ("ownerHints", "owner hints"),
                                                   ("entities", "entities"), ("trackedHumanSubscriptions", "subscriptions")], "    ")
    o.append("  " + e["note"])
    return o


def _spark(s: dict[str, Any] | None) -> list[str]:
    o = _head("spark (spark-1.json)")
    if s is None:
        return o + ["  no spark report"]
    o.append("  " + ", ".join(f"{k}={_fmt(v)}" for k, v in s.items() if k != "warnings"))
    o.extend(f"  ! {w}" for w in s["warnings"])
    o.append("  analyze the stacks with: uv run python -I tools/analyze_spark.py <capture>/profile.sparkprofile")
    return o


def _correlation(c: dict[str, Any]) -> list[str]:
    o = _head("Window correlation (slow timeline windows vs provider series and scheduler snapshots)")
    if not c["available"]:
        return o + ["  " + c["reason"]]
    o.append(f"  slow = window max tick >= {c['slowThresholdMs']:g} ms: {c['slowWindowCount']} of {c['windowCount']} windows"
             f"; showing {c['shownWindows']} worst by max tick")
    o.append("  " + c["statement"])
    o.append("  " + c["note"])
    if c.get("igniteNote"):
        o.append("  " + c["igniteNote"])
    if c.get("slowCallNote"):
        o.append("  " + c["slowCallNote"])
    tm = c.get("tickMatching")
    if tm and not tm.get("available"):
        o.append(f"  tick matching unavailable: {tm['reason']}; {tm['note']}")
    lift = c["lift"]
    if lift["available"]:
        o.append(f"  sections busier in slow windows ({lift['slowWindows']}) than in other windows ({lift['otherWindows']}):")
        o += table(lift["sections"], [("plugin", "plugin"), ("section", "section"), ("slowMeanMs", "slow mean ms"),
                                      ("otherMeanMs", "other mean ms"), ("diffMs", "diff ms"), ("ratio", "x")], "    ")
    else:
        o.append("  slow-vs-other comparison unavailable: " + lift["reason"])
    for w in c["windows"]:
        o.append("")
        o.append(f"  window #{w['index']} [{w['fromMillis']}-{w['toMillis']} ms]: {w['ticks']:g} ticks, mean {w['meanMspt']} ms, "
                 f"max {w['maxMspt']} ms, over100={w['over100ms']:g}  (tick time ~{w['tickTimeMs']} ms)")
        for p in w["providers"]:
            o.append(f"    {p['plugin']} (series {p['windowMillis']} ms windows, {p['sectionsWithSeries']} sections with series)"
                     + ("" if p["alignedWithTimeline"] else "  NOTE: not 1000 ms; alignment with timeline windows is approximate"))
            if p.get("note"):
                o.append("      " + p["note"])
            else:
                o += table(p["sections"], [("section", "section"), ("calls", "calls"), ("inclusiveMs", "incl. ms"),
                                           ("pctOfWindowTickTime", "% of tick time"), ("medianWindowMs", "median win ms"),
                                           ("xMedian", "x median")], "      ")
            if p.get("counts") is not None:
                o.append("      counts this second: " + (", ".join(
                    f"{c['count']}={_fmt(c['n'])} (in {c['windowsWithCount']} windows)" for c in p["counts"]) or "none"))
            if p.get("levels"):
                o.append("      levels this second: " + ", ".join(
                    f"{lv['level']}={_fmt(lv['value'])} (median {_fmt(lv['medianSample'])})" for lv in p["levels"]))
            om = p.get("omittedKeySeries") or {}
            if om.get("counts") or om.get("levels"):
                o.append(f"      no series (report budget): {len(om.get('counts', []))} counts, {len(om.get('levels', []))} levels")
        for msw in w.get("magicSpells", []):
            if msw.get("note"):
                o.append(f"    {msw['plugin']}: {msw['note']}")
            else:
                o.append(f"    {msw['plugin']}: outermost scopes {msw['outermostScopesMs']} ms "
                         f"({msw['pctOfWindowTickTime']}% of tick time); self by operation: "
                         + (", ".join(f"{x['operation']} {x['selfMs']} ms" for x in msw["operationsBySelf"]) or "none"))
        pw = w.get("phases")
        if pw is not None and not pw.get("available"):
            o.append(f"    tick phases: {pw['reason']}")
        elif pw is not None and not pw["phases"]:
            o.append(f"    tick phases: {pw['note']}")
        elif pw is not None:
            o.append(f"    tick phases this second ({pw['ticks']} ticks"
                     + (f", {pw['placedByOffset']} placed by start offset" if pw["placedByOffset"] else "")
                     + f"): measured {pw['measuredMs']} ms, other {pw['otherMs']} ms"
                     + (f" (unknown for {pw['otherUnknownTicks']} ticks)" if pw["otherUnknownTicks"] else "")
                     + (f", diagnostics' own {pw['diagnosticsMs']} ms" if pw["diagnosticsMs"] else ""))
            o += table(pw["phases"], [("phase", "phase"), ("ms", "ms"), ("pctOfMeasured", "% of measured"),
                                      ("medianWindowMs", "median win ms"), ("xMedian", "x median")], "      ")
        lw = w.get("listeners")
        if lw is not None:
            o.append(f"    Ignite listeners this second: {lw['selfMs']} ms self over all owners (server thread)")
            o += table(lw["owners"], [("owner", "owner"), ("selfMs", "self ms"), ("totalMsInclusive", "incl. ms"),
                                      ("pctOfWindowTickTime", "% of tick time"), ("medianWindowSelfMs", "median win self ms"),
                                      ("xMedian", "x median")], "      ")
        sl = w.get("slowCalls")
        if sl is not None:
            o.append(f"    slow calls placed in this window: {sl['count']}"
                     + (f" ({sl['placedByOffset']} by end offset)" if sl["placedByOffset"] else "")
                     + f"; server-thread time covered inside the measured tick duration (union, no double counting) "
                     f"{sl['serverThreadCoveredMs']} ms = {sl['pctOfWindowTickTime']}% of tick time"
                     + (f"; plus {sl['tickEndListenerMs']} ms in ServerTickEndEvent listeners (after the tick duration was "
                        f"measured, not in the percentage)" if sl["tickEndListenerMs"] else "")
                     + (f"; {sl['otherThreadCalls']} on other threads" if sl["otherThreadCalls"] else ""))
            if sl.get("coverageAssurance") == "upper-bound" and sl["serverThreadCoveredMs"]:
                o.append("      UPPER BOUND: " + sl["coverageNote"])
            elif sl.get("outsideMeasuredTickMs"):
                o.append(f"      exact against the measured tick intervals; {sl['outsideMeasuredTickMs']} ms of these calls ran "
                         "outside them (tick-end listeners, the stop)")
            if sl["exceedsTickTime"]:
                o.append("      NOTE covered time exceeds the window's tick time: some calls ran outside the measured tick "
                         "duration in a phase that cannot be identified (or probe offsets, whole milliseconds, overlap)")
            for t in sl["byTick"]:
                o.append(f"      tick {t['tick']}: covered {t['coveredMs']} ms"
                         + (f" (+{t['tickEndListenerMs']} ms tick-end listeners)" if t["tickEndListenerMs"] else "")
                         + f" by {t['calls']} calls; longest: " + "; ".join(t["longest"]))
            o += table(sl["calls"], [("source", "source"), ("label", "call"), ("ms", "ms"), ("startMs", "start ms"),
                                     ("endMs", "end ms"), ("tick", "tick"), ("thread", "thread")], "      ")
        tw = w.get("tracker")
        if tw:
            gl = "; ".join(f"{g['entityType']} {g['owner']} {g['ms']} ms" for g in tw["groups"]) or "no series groups"
            o.append(f"    tracker sends: {tw['ms']} ms ({tw['pctOfWindowTickTime']}% of tick time) over {tw['calls']:,.0f} "
                     f"sends; largest: {gl}")
        pw = w.get("packets")
        if pw:
            cl = "; ".join(f"{c['packetClass']} {c['ms']} ms" for c in pw["classes"]) or "no class series"
            cs = "; ".join(f"{c['cause']} {c['ms']} ms" for c in pw["causes"]) or "no cause series"
            o.append(f"    packet sends: {pw['ms']} ms ({pw['pctOfWindowTickTime']}% of tick time, inclusive, overlaps the phases) "
                     f"over {num(pw['sends']):,.0f} sends; classes: {cl}; causes: {cs}"
                     + (f"; inbound handling {pw['inboundMs']} ms" if pw["inboundMs"] is not None else ""))
        sc = w["scheduler"]
        if sc.get("note"):
            o.append("    scheduler: " + sc["note"])
        for s in sc["snapshots"]:
            ch = ", ".join(f"{d['plugin']} {d['delta']:+d}" for d in s["largestSyncPendingChanges"]) or "none"
            o.append(f"    scheduler snapshot @{s['observedAfterMillis']} ms: pending {s['pendingTasks']}, new {s['newTasks']}, "
                     f"missing {s['missingTasks']}, collection {s['snapshotCollectionMs']} ms; largest sync-pending changes: {ch}")
    return o


def _leads(r: dict[str, Any]) -> list[str]:
    """Hypotheses suggested by the observations above. They are leads, not findings."""
    o = _head("Leads (HYPOTHESES - not established causes)")
    n0 = len(o)
    for p in r["providers"]:
        if p["kind"] == "probe" and p["probe"]["topBySelf"]:
            t = p["probe"]["topBySelf"][0]
            o.append(f"  - {p['plugin']}: section '{t['name']}' has the largest self time ({t['selfMs']} ms, "
                     f"{t['calls']:,.0f} calls). Test with a controlled change and diff_captures.py.")
        elif p["kind"] == "magicspells":
            rec = p["magicspells"]["recovery"]
            lst = [x for x in (rec.get("topBySelf") or p["magicspells"]["topSpellTotalsBySelf"])
                   if x["spell"] != "[unattributed]"]
            if lst:
                o.append(f"  - MagicSpells: '{lst[0]['spell']}' has the largest self time ({lst[0]['selfMs']} ms)."
                         + ("" if rec["valid"] else " Spell ranking may be incomplete."))
    s = r["scheduler"]
    if s and s["pendingByPlugin"]:
        t = s["pendingByPlugin"][0]
        o.append(f"  - Scheduler: {t['plugin']} has the most sync-pending tasks (max {t['syncMax']:,.0f}); a large "
                 "queue alone does not show per-tick cost or a leak.")
    li = r.get("listeners") or {}
    if li.get("available") and li["byOwner"]:
        t = li["byOwner"][0]
        o.append(f"  - Listeners: {t['owner']} has the most listener self time ({t['selfMs']} ms over {t['calls']:,} calls); "
                 "see the Listeners section for which listener and event.")
    ph = r.get("phases") or {}
    if ph.get("available"):
        top_phase = next((x for x in ph["inside"] if x["phase"] not in ("other", "diagnostics")), None)
        if top_phase:
            o.append(f"  - Tick phases: '{top_phase['phase']}' takes the most measured tick time ({top_phase['pctOfMeasured']}%, "
                     f"{top_phase['totalMs']:,.1f} ms); see Tick phases and the per-window breakdown.")
        if ph["spawning"]["finding"]:
            o.append("  - Spawning: natural-spawning code ran although spawning should be disabled (see Tick phases); "
                     "check spawn limits, per-player mob spawns and spawn-category settings.")
    sp = r.get("spawns") or {}
    if sp.get("naturalFinding"):
        o.append("  - Spawns: natural-type spawning is active although it should be disabled (see Spawns); check world "
                 "and spawner settings.")
    t = r.get("tracker")
    if t and t.get("available") and t["topGroups"]:
        g = t["topGroups"][0]
        o.append(f"  - Tracker: {g['entityType']} with owner hint '{g['owner']}' has the largest send time ({g['totalMs']} ms, "
                 f"{g['sharePct']}% of tracker sends; metadata {g['metadataMs']} ms). Sends cost even when unchanged; "
                 "test with a controlled change and diff_captures.py.")
    pk = r.get("packets")
    if pk and pk.get("available"):
        ca = pk.get("causes")
        if ca and ca["rows"] and ca["rows"][0]["totalMs"]:
            c = ca["rows"][0]
            o.append(f"  - Packet sends: cause '{c['cause']}' has the most send time ({c['totalMs']} ms, {c['sharePct']}%"
                     + (f", fan-out {c['fanOut']} packets per call" if c["fanOut"] is not None else "") + ").")
        ob = pk.get("outbound")
        if ob and ob["topClasses"]:
            c = ob["topClasses"][0]
            o.append(f"  - Packet sends: {c['packetClass']} has the most server-thread send time ({c['totalMs']} ms, "
                     f"{c['sendsPerSec']}/s); send time includes ProtocolLib and Netty hand-off below Connection.send.")
        tr = pk.get("trackerOwners")
        if tr and tr["topGroups"] and tr["topGroups"][0]["totalMs"]:
            g = tr["topGroups"][0]
            o.append(f"  - Packet sends: tracker packets of {g['entityType']} owned by '{g['owner']}' cost the most "
                     f"({g['totalMs']} ms, {g['sharePct']}% of tracker send time, {g['entities']:,.0f} entities).")
        ib = pk.get("inbound")
        if ib and ib["topClasses"] and ib["topClasses"][0]["totalMs"]:
            c = ib["topClasses"][0]
            o.append(f"  - Inbound packets: {c['packetClass']} handling takes the most time ({c['totalMs']} ms, "
                     f"{c['sharePct']}% of inbound handling, plugin events included).")
    e = r["entities"]
    if e and e["ownerHintsByCount"]:
        k, v = next(iter(e["ownerHintsByCount"].items()))
        o.append(f"  - Entities: owner hint '{k}' covers {v:,} sampled entities; counts do not establish tick cost.")
    if len(o) == n0:
        o.append("  (none: not enough measurements)")
    return o


def _coverage(c: dict[str, Any] | None) -> list[str]:
    o = _head("Coverage audit (enabled plugins vs providers)")
    if not c or not c.get("available"):
        return o + ["  no plugin list or providers to audit"]
    o.append(f"  {c['providers']} providers; enabled plugins: {c['enabledPlugins'] if c['enabledPlugins'] is not None else 'unknown'}"
             f" ({c['enabledSource']}); Ignite {'present' if c['igniteAvailable'] else 'absent'}"
             + ("" if c["slowListenerRecords"] or not c["igniteAvailable"] else "; no slow-listener records"))
    if c.get("providerInventory") == "not captured":
        o.append("  provider inventory not captured (no plugins collector index): no plugin is reported as missing a "
                 "provider. Ignite-measured owners, provider status unknown:")
        o += table(c.get("unknownProviderByMeasuredTime", []),
                   [("plugin", "plugin"), ("igniteSyncTaskMs", "sync task ms"), ("igniteSyncTaskRuns", "runs"),
                    ("slowListenerCalls", "slow listener calls"), ("slowListenerMs", "slow listener ms")], "    ")
    if c["missingProvidersByMeasuredTime"]:
        o.append("  NO PROVIDER, but Ignite measured time (priority for new providers; inclusive, overlapping):")
        o += table(c["missingProvidersByMeasuredTime"], [("plugin", "plugin"), ("igniteSyncTaskMs", "sync task ms"),
                                                         ("igniteSyncTaskRuns", "runs"), ("listenerSelfMs", "listener self ms"),
                                                         ("slowListenerCalls", "slow listener calls"),
                                                         ("slowListenerMs", "slow listener ms")], "    ")
    o.append("  all plugins:")
    o += table(c["rows"], [("plugin", "plugin"), ("provider", "provider"), ("instrumentation", "instrumentation"),
                           ("measured", "measures"), ("igniteSyncTaskMs", "sync task ms"),
                           ("listenerSelfMs", "listener self ms"), ("listenerCalls", "listener calls"),
                           ("slowListenerCalls", "slow listeners"), ("slowTaskCalls", "slow tasks")], "    ")
    limited = [r for r in c["rows"] if r["limits"]]
    if limited:
        o.append("  provider limits:")
        for r in limited:
            o.append(f"    - {r['plugin']}: " + "; ".join(r["limits"]))
    o.append("  " + c["note"])
    return o


def _listeners(li: dict[str, Any] | None) -> list[str]:
    o = _head("Listeners (Ignite listener totals, ranked by server-thread self time)")
    if not li or not li.get("available"):
        return o + ["  " + (li or {}).get("reason", "no listener totals")]
    o.append(f"  {li['keys']:,} listener keys; {li['selfMs']:,.1f} ms self in total over "
             f"{li['observedMillis'] / 1000:.1f}s; complete={li['complete']}"
             + (f"; ServerTickEndEvent listeners {li['tickEndSelfMs']} ms self (after the tick was measured)"
                if li["tickEndSelfMs"] else ""))
    for why in li["why"]:
        o.append("  GAP " + why)
    acc = li.get("accounting")
    if acc:
        o.append(f"  calls: {acc['observedInvocations']:,.0f} observed = {acc['countedCalls']:,.0f} in totals + "
                 f"{acc['runningAtStop']:,.0f} running at stop" + ("" if not acc["difference"] else
                                                                  f"  MISMATCH: {acc['note']}"))
    o.append("  by owner plugin:")
    o += table(li["byOwner"], [("owner", "owner"), ("keys", "keys"), ("calls", "calls"), ("selfMs", "self ms"),
                               ("selfPctOfObserved", "self % of wall"), ("totalMsInclusive", "total ms (incl.)")], "    ")
    o.append("  by listener x event (top by self):")
    o += table(li["bySelf"], [("owner", "owner"), ("listener", "listener"), ("event", "event"), ("priority", "priority"),
                              ("calls", "calls"), ("selfMs", "self ms"), ("totalMsInclusive", "total ms (incl.)"),
                              ("meanUs", "mean us"), ("p99MsUpperBound", "p99 ms<="), ("maxMs", "max ms"),
                              ("otherThreadCalls", "other-thread calls")], "    ")
    o.append("  " + li["note"])
    return o


def _phases(p: dict[str, Any] | None) -> list[str]:
    o = _head("Tick phases (exclusive time per tick phase)")
    if not p or not p.get("available"):
        return o + ["  " + (p or {}).get("reason", "no tick phases")]
    o.append(f"  {p['ticks']:,} ticks, {p['measuredTicks']:,} with a measured duration ({p['measuredMs']:,.1f} ms, mean "
             f"{p['meanMeasuredMsPerTick']} ms per tick); worlds: {', '.join(p['worlds']) or 'none'}; "
             f"complete={p['complete']}")
    for why in p["why"]:
        o.append("  GAP " + why)
    c = p["consistency"]
    if c.get("checked") and c["mismatchedTicks"]:
        o.append(f"  WARNING inconsistent data: phases + other differ from the measured duration in {c['mismatchedTicks']} ticks")
    sp = p["spawning"]
    if sp["finding"]:
        o += ["  " + "!" * 76, "  !!! " + sp["finding"], "  " + "!" * 76]
        o += table(sp["worlds"], [("world", "world"), ("stateMs", "createState ms"), ("stateCalls", "calls"),
                                  ("spawnMs", "spawnForChunk ms"), ("spawnChunkCalls", "calls"),
                                  ("spawnChunkCallsThatSpawned", "calls that spawned"),
                                  ("naturalSpawnAttempts", "entities attempted"), ("customMs", "custom spawners ms"),
                                  ("customSpawnerCalls", "calls")], "    ")
    else:
        o.append("  no natural-spawning time (createState, spawnForChunk, custom spawners) was recorded")
    o.append(f"  inside the measured tick (top {p['insideShown']} of {p['insideCount']} phases, then 'other'):")
    o += table(p["inside"], [("phase", "phase"), ("totalMs", "total ms"), ("pctOfMeasured", "% of measured"),
                             ("meanUsPerTick", "mean us/tick"), ("maxTickMs", "max ms/tick"), ("ticksWithTime", "ticks"),
                             ("note", "note")], "    ")
    if p["byKind"]:
        o.append("  per-world phases summed over worlds:")
        o += table(p["byKind"], [("kind", "kind"), ("totalMs", "total ms"), ("pctOfMeasured", "% of measured")], "    ")
    if len(p["byWorld"]) > 1:
        o.append("  by world:")
        o += table(p["byWorld"], [("world", "world"), ("totalMs", "total ms"), ("pctOfMeasured", "% of measured")], "    ")
    o.append("  outside the measured tick (after Paper measured it):")
    o += table(p["outside"], [("phase", "phase"), ("totalMs", "total ms"), ("meanUsPerTick", "mean us/tick"),
                              ("maxTickMs", "max ms/tick")], "    ")
    d = p["diagnostics"]
    o.append(f"  SneakyDiagnostics' own server-thread work: {d['insideMs']} ms inside the measured ticks "
             f"({d['pctOfMeasured']}%), {d['outsideMs']} ms after them")
    o.append("  " + p["note"])
    return o


def _spawns(s: dict[str, Any] | None) -> list[str]:
    o = _head("Spawns (timeline)")
    if not s or not s.get("available"):
        return o + ["  " + (s or {}).get("reason", "no spawn counts")]
    if s["naturalFinding"]:
        o += ["  " + "!" * 76, "  !!! " + s["naturalFinding"], "  " + "!" * 76]
        o += table(s["natural"], [("reason", "reason"), ("entityType", "entity type"), ("spawned", "spawned"),
                                  ("cancelled", "cancelled"), ("firstWindow", "first s"), ("lastWindow", "last s")], "    ")
    else:
        o.append("  no natural-type spawns (NATURAL, CHUNK_GEN, spawners, raids, ...) were observed")
    o.append(f"  {s['spawned']:,.0f} spawned, {s['cancelled']:,.0f} cancelled ({s['observed']:,.0f} spawn events); "
             f"complete={s['complete']}")
    for why in s["why"]:
        o.append("  GAP " + why)
    o.append("  by reason:")
    o += table(s["byReason"], [("reason", "reason"), ("spawned", "spawned"), ("cancelled", "cancelled"),
                               ("natural", "natural-type")], "    ")
    if s["byPlugin"]:
        o.append("  CUSTOM/DEFAULT spawns by spawning plugin (first plugin frame; a hint):")
        o += table(s["byPlugin"], [("plugin", "plugin"), ("spawned", "spawned"), ("cancelled", "cancelled")], "    ")
    o.append("  top reason x type:")
    o += table(s["top"], [("reason", "reason"), ("entityType", "entity type"), ("plugin", "plugin"), ("total", "spawned"),
                          ("cancelled", "cancelled"), ("windowsWithSpawns", "seconds with spawns")], "    ")
    o.append("  " + s["note"])
    return o
