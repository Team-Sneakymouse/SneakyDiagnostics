"""Plain-text rendering of the analysis dict. Observations and hypotheses are labelled separately."""
from __future__ import annotations

from typing import Any, Iterable


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
    o.extend(_entities(r["entities"]))
    o.extend(_spark(r["spark"]))
    o.extend(_correlation(r["correlation"]))
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
                                   ("coverageFlags", "coverage flags")], "    ")
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
    o.append("  series: " + (f"{len(pr['seriesSections'])} sections at {pr['seriesWindowMillis']} ms windows"
                             if pr["hasSeries"] else "NONE - cannot be correlated with timeline windows"))
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
    else:
        o.append("  no Ignite section: execution/cancellation attribution unavailable (public snapshots only)")
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
    e = r["entities"]
    if e and e["ownerHintsByCount"]:
        k, v = next(iter(e["ownerHintsByCount"].items()))
        o.append(f"  - Entities: owner hint '{k}' covers {v:,} sampled entities; counts do not establish tick cost.")
    if len(o) == n0:
        o.append("  (none: not enough measurements)")
    return o
