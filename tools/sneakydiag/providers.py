"""Per-provider analysis: probe/1 measurements, MagicSpells free-form recovery, generic free-form."""
from __future__ import annotations

import statistics
from typing import Any

from .loader import Provider
from .util import ms, num, pct, scan_flags, top

TIMING_NOTE = ("Timings are server-thread wall time. totalNanos is INCLUSIVE and overlaps nested sections, "
               "so totals must not be added; selfNanos excludes nested sections and is additive for "
               "strictly nested sections.")


def analyze_provider(p: Provider, n: int) -> dict[str, Any]:
    out: dict[str, Any] = {
        "plugin": p.plugin, "pluginVersion": p.plugin_version,
        "instrumentationVersion": p.instrumentation_version, "reason": p.reason,
        "complete": p.complete, "started": p.started, "error": p.error, "file": p.file,
        "measurementsStatus": p.measurements_status,
    }
    m = p.measurements
    if m is None:
        out["kind"] = "unavailable"
        out["why"] = _why(p, None, [])
        return out
    flags = scan_flags(m)
    if p.is_probe:
        out["kind"] = "probe"
        out["probe"] = analyze_probe(m, n)
    elif _is_magicspells(p, m):
        out["kind"] = "magicspells"
        out["magicspells"] = analyze_magicspells(m, n)
        out["timers"] = find_timers(m.get("taskLifecycle"), "taskLifecycle", n) if m.get("taskLifecycle") else []
    else:
        out["kind"] = "freeform"
        out["freeform"] = analyze_freeform(m, n)
    out["flags"] = flags
    out["why"] = _why(p, m, flags)
    return out


def _why(p: Provider, m: dict[str, Any] | None, flags: list[str]) -> list[str]:
    """Observed reasons a provider is incomplete (or unavailable), taken from its own fields."""
    why: list[str] = []
    if p.measurements_status in ("missing", "unreadable", "absent"):
        why.append(f"measurements {p.measurements_status}: provider file could not be used")
    if p.started is False:
        why.append("provider did not start")
    if p.reason and p.reason != "completed":
        why.append(f"reason={p.reason}")
    if p.error:
        why.append(f"error: {p.error}")
    if m is not None:
        if num(m.get("omittedKeys")):
            why.append(f"omittedKeys={m['omittedKeys']:g} (names beyond maxKeys were not recorded)")
        if num(m.get("nestingErrors")):
            why.append(f"nestingErrors={m['nestingErrors']:g} (selfNanos may be inexact)")
        why.extend(f for f in flags if f not in why)
    if p.complete is False and not why:
        why.append("marked incomplete but the report records no cause")
    return why


# ------------------------------------------------------------------ probe/1

def analyze_probe(m: dict[str, Any], n: int) -> dict[str, Any]:
    observed = num(m.get("observedMillis"))
    sections = [s for s in m.get("sections", []) if isinstance(s, dict) and isinstance(s.get("name"), str)]
    problems: list[str] = []
    rows = []
    for s in sections:
        total, selfn = num(s.get("totalNanos")), num(s.get("selfNanos"))
        if selfn > total:
            problems.append(f"section {s['name']}: selfNanos > totalNanos")
        hist = s.get("histogramLog2")
        if isinstance(hist, list) and hist and sum(num(h) for h in hist) != num(s.get("calls")):
            problems.append(f"section {s['name']}: histogramLog2 total != calls")
        rows.append({
            "name": s["name"], "calls": num(s.get("calls")),
            "selfMs": ms(selfn), "totalMsInclusive": ms(total),
            "meanMs": ms(num(s.get("meanNanos"))), "maxMs": ms(num(s.get("maxNanos"))),
            "maxAtMillis": s.get("maxAtMillis"), "maxAtTick": s.get("maxAtTick"),
            "p50MsUpperBound": ms(num(s.get("p50Nanos"))), "p95MsUpperBound": ms(num(s.get("p95Nanos"))),
            "p99MsUpperBound": ms(num(s.get("p99Nanos"))),
            "selfSharePctOfObserved": pct(selfn / 1e6, observed), "_self": selfn, "_total": total,
        })
    self_sum = sum(r["_self"] for r in rows)
    omitted = num(m.get("omittedKeys"))
    series = m.get("series") if isinstance(m.get("series"), dict) else None
    levels = m.get("levels") if isinstance(m.get("levels"), dict) else {}
    counts = m.get("counts") if isinstance(m.get("counts"), dict) else {}
    owned = {k: v for k, v in levels.items() if k == "entities.owned" or k.startswith("entities.owned.")}
    result = {
        "schema": m.get("schema"), "observedMillis": observed, "sectionCount": len(rows),
        "instrumentedSelfMs": ms(self_sum),
        "instrumentedSelfNote": ("sum of selfNanos over retained sections" +
                                 ("; omitted names make this a lower bound" if omitted else "") +
                                 "; exact only for strictly nested sections"),
        "instrumentedSelfSharePctOfObserved": pct(self_sum / 1e6, observed),
        "nestingErrors": num(m.get("nestingErrors")), "omittedKeys": omitted, "maxKeys": m.get("maxKeys"),
        "topBySelf": _clean(top(rows, "_self", n)),
        "topByTotalInclusive": _clean(top(rows, "_total", n)),
        "slowest": [dict(s, ms=ms(num(s.get("nanos")))) for s in (m.get("slowest") or [])
                    if isinstance(s, dict)],
        "counts": dict(sorted(counts.items(), key=lambda kv: num(kv[1]), reverse=True)),
        "levels": levels, "ownedEntities": owned,
        "hasSeries": bool(series and isinstance(series.get("sections"), dict) and series["sections"]),
        "seriesWindowMillis": series.get("windowMillis") if series else None,
        "seriesSections": sorted(series["sections"]) if series and isinstance(series.get("sections"), dict) else [],
        "keySeries": None,
        "consistencyProblems": problems, "timingNote": TIMING_NOTE,
    }
    ks = key_series(m)
    if ks is not None:
        result["keySeries"] = {"counts": sorted(ks["counts"]), "levels": sorted(ks["levels"]), "omitted": ks["omitted"]}
    return result


def _clean(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [{k: v for k, v in r.items() if not k.startswith("_")} for r in rows]


# ------------------------------------------------------------------ MagicSpells (free-form)

def _is_magicspells(p: Provider, m: dict[str, Any]) -> bool:
    return p.plugin.lower() == "magicspells" or ("spellTotals" in m and isinstance(m.get("rows"), list))


def analyze_magicspells(m: dict[str, Any], n: int) -> dict[str, Any]:
    """Rank spells; recover mixed-case attribution from detail rows when the detail is complete."""
    rows = [r for r in m.get("rows", []) if isinstance(r, dict)]
    spell_totals = [r for r in m.get("spellTotals", []) if isinstance(r, dict)]
    totals = m.get("totals") if isinstance(m.get("totals"), dict) else {}
    out: dict[str, Any] = {
        "totals": _ms_view(totals), "elapsedMs": ms(num(m.get("elapsedNanos"))),
        "totalsComplete": m.get("totalsComplete"), "operationTotalsComplete": m.get("operationTotalsComplete"),
        "spellTotalsComplete": m.get("spellTotalsComplete"), "detailComplete": m.get("detailComplete"),
        "unknownOwnerScopes": num(m.get("unknownOwnerScopes")),
        "operationTotals": [_ms_view(r, "operation") for r in top(
            [r for r in m.get("operationTotals", []) if isinstance(r, dict)], "selfNanos", n)],
        "topSpellTotalsBySelf": [_ms_view(r, "spell") for r in top(spell_totals, "selfNanos", n)],
        "topDetailRowsBySelf": [dict(_ms_view(r, "spell"), operation=r.get("operation"), detail=r.get("detail"))
                                for r in top(rows, "selfNanos", n)],
        "unattributedSpellTotals": _ms_view(m["unattributedSpellTotals"]) if isinstance(
            m.get("unattributedSpellTotals"), dict) else None,
        "note": ("Rank spells by selfNanos (exclusive). totals/operationTotals/spellTotals are independent "
                 "views of the same scopes; do not add them."),
    }
    out["recovery"] = recover_spells(m, rows, totals, n)
    out["slowOperations"] = slow_records(m.get("slowOperations"), n,
                                         missing="not recorded (instrumentation before magicspells-execution-4)")
    ms_series = magicspells_series(m)
    out["hasSeries"] = ms_series is not None
    out["seriesOperations"] = sorted(ms_series["operations"]) if ms_series else []
    late = (ms_series or {}).get("late")
    out["lateScopes"] = ({"rootCalls": late["rootCalls"], "outermostMs": ms(late["rootNanos"]),
                          "lastEndOffsetMillis": late["lastEndOffsetMillis"]} if late and late["rootCalls"] else None)
    lifecycle = m.get("taskLifecycle") if isinstance(m.get("taskLifecycle"), dict) else None
    out["taskLifecycle"] = task_lifecycle(lifecycle) if lifecycle else None
    coverage = m.get("counterCoverage")
    out["counterCoverage"] = coverage if isinstance(coverage, dict) else None
    return out


def slow_records(sec: Any, n: int, *, missing: str) -> dict[str, Any]:
    """A bounded slow-call section (Ignite slowExecutions/slowListeners, MagicSpells slowOperations)."""
    if not isinstance(sec, dict):
        return {"available": False, "reason": missing}
    records = [r for r in sec.get("records", []) if isinstance(r, dict)]
    rows = sorted(records, key=lambda r: num(r.get("nanos")), reverse=True)
    running = sec.get("runningAtStop") if isinstance(sec.get("runningAtStop"), (int, float)) else None
    around = sec.get("runningAroundStop") if isinstance(sec.get("runningAroundStop"), (int, float)) else None
    return {"available": True, "thresholdMs": ms(num(sec.get("thresholdNanos"))), "capacity": sec.get("capacity"),
            "observed": num(sec.get("observed")), "retained": len(records), "evicted": num(sec.get("evicted")),
            "complete": sec.get("complete"), "observedInvocations": sec.get("observedInvocations"),
            # listener calls not ended at stop (durations unknown); those enclosing the stop do not make it incomplete
            "runningAtStop": running, "runningAroundStop": around,
            "runningOtherThreads": max(0, running - (around or 0)) if running is not None else None,
            "top": [dict(r, ms=ms(num(r.get("nanos"))), startMs=_offset_ms(r.get("startOffsetMicros")),
                         endMs=_offset_ms(r.get("endOffsetMicros"))) for r in rows[:n]],
            "note": ("Only calls at or above the threshold are kept, the longest first; 'evicted' counts slow calls "
                     "left out for capacity. Durations are inclusive and overlap nested and parallel records.")}


def _offset_ms(micros: Any) -> float | None:
    return round(num(micros) / 1000, 3) if isinstance(micros, (int, float)) and not isinstance(micros, bool) else None


def magicspells_series(m: dict[str, Any]) -> dict[str, Any] | None:
    """MagicSpells per-second series (magicspells-execution-4+): outermost-scope time and per-operation self time."""
    s = m.get("series")
    if not isinstance(s, dict) or not isinstance(s.get("operations"), dict):
        return None
    wm = int(num(s.get("windowMillis")) or 1000)
    ops = {k: {"calls": [num(x) for x in v.get("calls", [])], "selfNanos": [num(x) for x in v.get("selfNanos", [])]}
           for k, v in s["operations"].items() if isinstance(v, dict)}
    roots = [num(x) for x in s.get("rootNanos", [])]
    late = s.get("late") if isinstance(s.get("late"), dict) else None
    late_view = None
    if late and isinstance(late.get("operations"), dict):
        last_end = num(late.get("lastEndOffsetMillis"))
        first = len(roots)  # the late bucket starts where the regular windows end (at the deadline)
        late_view = {"rootNanos": num(late.get("rootNanos")), "rootCalls": num(late.get("rootCalls")),
                     "lastEndOffsetMillis": last_end,
                     "operations": {k: {"calls": num(v.get("calls")), "selfNanos": num(v.get("selfNanos"))}
                                    for k, v in late["operations"].items() if isinstance(v, dict)},
                     # every late scope ended in window `first` (the timeline's post-deadline window) or later
                     "fitsWindow": first if num(late.get("rootCalls")) and last_end < (first + 1) * wm else None}
    return {"windowMillis": wm, "rootNanos": roots,
            "rootCalls": [num(x) for x in s.get("rootCalls", [])], "operations": ops, "late": late_view}


def task_lifecycle(t: dict[str, Any]) -> dict[str, Any]:
    """Why the MagicSpells task lifecycle is partial, and which unattributed origins are infrastructure."""
    groups = [g for g in t.get("groups", []) if isinstance(g, dict)]
    events = ("registrations", "executions", "callbackCompletions", "cancellationRequests", "trackedCancellations",
              "cleanupTerminations")
    by_origin: dict[str, float] = {}
    for g in groups:
        if g.get("spell") == "[unattributed]":
            by_origin[str(g.get("origin"))] = by_origin.get(str(g.get("origin")), 0) + sum(num(g.get(e)) for e in events)
    unattributed = [{"origin": o, "events": v, "kind": _origin_kind(o)}
                    for o, v in sorted(by_origin.items(), key=lambda kv: kv[1], reverse=True)]
    reasons = t.get("incompleteReasons")
    return {"inventoryComplete": t.get("inventoryComplete"), "lifecycleComplete": t.get("lifecycleComplete"),
            "incompleteReasons": reasons if isinstance(reasons, list) else None,
            "endObservedAfterDeadline": t.get("endObservedAfterDeadline"),
            "endObservedLateMillis": t.get("endObservedLateMillis"),
            "unknownOwnerEvents": num(t.get("unknownOwnerEvents")),
            "unattributedByOrigin": unattributed[:10],
            "note": ("'spell code' origins scheduled inside a spell without passing it as owner: attribution gaps. "
                     "Listener and core origins may have no spell owner by design (managers, casts not yet resolved). "
                     "lifecycleComplete stays false for either.")}


def _origin_kind(origin: str) -> str:
    if origin.startswith("com.nisovin.magicspells.spells."):
        return "spell code"
    if origin.startswith("com.nisovin.magicspells.listeners."):
        return "listener"
    return "core/manager"


def _ms_view(r: dict[str, Any], label: str | None = None) -> dict[str, Any]:
    v: dict[str, Any] = {}
    if label:
        v[label] = r.get(label)
    v.update(calls=num(r.get("calls")), selfMs=ms(num(r.get("selfNanos"))),
             totalMsInclusive=ms(num(r.get("totalNanos"))), maxMs=ms(num(r.get("maxNanos"))))
    if isinstance(r.get("counters"), dict):
        v["counters"] = r["counters"]
    return v


def recover_spells(m: dict[str, Any], rows: list[dict[str, Any]], totals: dict[str, Any], n: int) -> dict[str, Any]:
    """Group detail rows by case-folded spell name (the old mixed-case workaround).

    Only valid when every detail row was retained and the rows sum exactly to the global self
    total; otherwise the ranking would silently omit work, so it is refused with the reason.
    """
    blockers = []
    if m.get("detailComplete") is not True:
        blockers.append("detailComplete is not true")
    if num(m.get("omittedDetailRows")):
        blockers.append(f"omittedDetailRows={m.get('omittedDetailRows')}")
    if num(m.get("droppedScopes")):
        blockers.append(f"droppedScopes={m.get('droppedScopes')}")
    if "selfNanos" not in totals:
        blockers.append("totals.selfNanos missing")
    grouped: dict[str, dict[str, Any]] = {}
    for r in rows:
        spell = r.get("spell")
        if not isinstance(spell, str):
            continue
        g = grouped.setdefault(spell.lower(), {"spell": spell.lower(), "selfNanos": 0, "calls": 0, "totalNanos": 0})
        g["selfNanos"] += num(r.get("selfNanos"))
        g["calls"] += num(r.get("calls"))
        g["totalNanos"] += num(r.get("totalNanos"))
    if not blockers and sum(g["selfNanos"] for g in grouped.values()) != num(totals.get("selfNanos")):
        blockers.append("detail rows do not sum to totals.selfNanos")
    mixed = [r for r in rows if isinstance(r.get("spell"), str) and r["spell"] != r["spell"].lower()]
    result: dict[str, Any] = {
        "valid": not blockers, "blockers": blockers,
        "mixedCaseRows": len(mixed), "mixedCaseCalls": sum(num(r.get("calls")) for r in mixed),
        "mixedCaseSelfMs": ms(sum(num(r.get("selfNanos")) for r in mixed)),
        "unknownOwnerScopes": num(m.get("unknownOwnerScopes")),
        "note": ("Mixed-case spell names can be attributed to [unattributed] by the provider; grouping "
                 "complete detail rows by lower-cased name recovers them. Observation, not a fix."),
    }
    if not blockers:
        result["topBySelf"] = [dict(spell=g["spell"], calls=g["calls"], selfMs=ms(g["selfNanos"]),
                                    totalMsInclusive=ms(g["totalNanos"]))
                               for g in top(list(grouped.values()), "selfNanos", n)]
    return result


# ------------------------------------------------------------------ generic free-form providers

def find_timers(obj: Any, path: str, n: int, depth: int = 5) -> list[dict[str, Any]]:
    """Find ``{totalNanos, samples|calls, maxNanos}`` objects anywhere in a free-form measurement."""
    found: list[dict[str, Any]] = []

    def walk(o: Any, p: str, d: int) -> None:
        if d < 0:
            return
        if isinstance(o, dict):
            if "totalNanos" in o and ("samples" in o or "calls" in o):
                cnt = num(o.get("samples", o.get("calls")))
                waiting = any(w in p.lower() for w in ("delay", "queue", "wait"))
                found.append({"path": p + (" [waiting time, not CPU]" if waiting else ""), "count": cnt, "totalMsInclusive": ms(num(o["totalNanos"])),
                              "maxMs": ms(num(o.get("maxNanos"))),
                              "meanMs": ms(num(o["totalNanos"]) / cnt) if cnt else None,
                              "_t": num(o["totalNanos"])})
            for k, v in o.items():
                if isinstance(v, (dict, list)):
                    walk(v, f"{p}.{k}" if p else str(k), d - 1)
        elif isinstance(o, list):
            for i, v in enumerate(o[:200]):
                label = next((str(v[k]) for k in ("mode", "name", "spell", "key", "kind") if isinstance(v, dict)
                              and isinstance(v.get(k), str)), str(i))
                walk(v, f"{p}[{label}]", d - 1)

    walk(obj, path, depth)
    return [{k: v for k, v in r.items() if k != "_t"} for r in top(found, "_t", n)]


def analyze_freeform(m: dict[str, Any], n: int) -> dict[str, Any]:
    scalars = {k: v for k, v in m.items() if isinstance(v, (int, float, bool))}
    return {
        "scalars": scalars, "timersByTotalInclusive": find_timers(m, "", n),
        "note": ("Free-form provider: fields are interpreted by name only. Timers listed here are inclusive "
                 "and may overlap each other and scheduler/Ignite timings; check the provider's own notes."),
        "providerNotes": m.get("notes") if isinstance(m.get("notes"), str) else None,
    }


# ------------------------------------------------------------------ series helpers

def series_windows(m: dict[str, Any]) -> tuple[int, dict[str, dict[str, list[float]]]] | None:
    s = m.get("series")
    if not isinstance(s, dict) or not isinstance(s.get("sections"), dict):
        return None
    wm = s.get("windowMillis")
    if not isinstance(wm, int) or wm <= 0:
        return None
    secs: dict[str, dict[str, list[float]]] = {}
    for name, d in s["sections"].items():
        if isinstance(d, dict) and isinstance(d.get("nanos"), list) and isinstance(d.get("calls"), list):
            secs[name] = {"calls": d["calls"], "nanos": d["nanos"]}
    return wm, secs


# The recorder's hard window clamp: no valid series window index reaches this.
MAX_SERIES_WINDOWS = 3600


def _window_index(v: Any) -> int | None:
    return v if isinstance(v, int) and not isinstance(v, bool) and 0 <= v < MAX_SERIES_WINDOWS else None


def key_series(m: dict[str, Any]) -> dict[str, Any] | None:
    """Count and level series (added to probe/1 later; None for reports without them).

    counts: name -> {window: n} (windows with a non-zero count); levels: name -> [(from, to, value)] runs of sampled
    windows (a window in no run had no sample: unknown, not zero); omitted: names left out for the value budget.
    Malformed entries (non-integer or out-of-range windows, non-numeric values) are dropped, never expanded.
    """
    s = m.get("series")
    if not isinstance(s, dict) or not isinstance(s.get("counts"), dict) or not isinstance(s.get("levels"), dict):
        return None
    counts: dict[str, dict[int, float]] = {}
    for name, d in s["counts"].items():
        if isinstance(d, dict) and isinstance(d.get("windows"), list) and isinstance(d.get("n"), list):
            counts[name] = {w: num(n) for w, n in ((_window_index(w), n) for w, n in zip(d["windows"], d["n"]))
                            if w is not None and num(n, None) is not None}
    levels: dict[str, list[tuple[int, int, float]]] = {}
    for name, d in s["levels"].items():
        if isinstance(d, dict) and all(isinstance(d.get(k), list) for k in ("from", "to", "values")):
            runs = []
            for a, b, v in zip(d["from"], d["to"], d["values"]):
                a, b = _window_index(a), _window_index(b)
                if a is not None and b is not None and a <= b and num(v, None) is not None:
                    runs.append((a, b, num(v)))
            levels[name] = runs
    om = s.get("omitted") if isinstance(s.get("omitted"), dict) else {}

    def names(v: Any) -> list[str]:
        return [x for x in v if isinstance(x, str)] if isinstance(v, list) else []

    return {"counts": counts, "levels": levels,
            "omitted": {"counts": names(om.get("counts")), "levels": names(om.get("levels"))}}


def level_at(runs: list[tuple[int, int, float]], window: int) -> float | None:
    """The level's sample in the window, or None when it had none."""
    for a, b, v in runs:
        if a <= window <= b:
            return v
    return None


def level_median(runs: list[tuple[int, int, float]]) -> float:
    """The median over sampled windows (each run weighted by its length)."""
    ordered = sorted((v, b - a + 1) for a, b, v in runs)
    total = sum(n for _, n in ordered)
    if not total:
        return 0.0
    def kth(k: int) -> float:
        for v, n in ordered:
            if k < n:
                return v
            k -= n
        return ordered[-1][0]
    return kth(total // 2) if total % 2 else (kth(total // 2 - 1) + kth(total // 2)) / 2


def median(xs: list[float]) -> float:
    return statistics.median(xs) if xs else 0.0
