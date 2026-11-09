"""Window correlation: for each slow timeline window, what the providers' series and the scheduler showed."""
from __future__ import annotations

import bisect
from typing import Any

from .loader import Capture
from .providers import key_series, level_at, level_median, magicspells_series, median, series_windows
from .util import ms, num, pct

NOTE = ("Observations only. A section that is busy in a slow window may be a cause, a symptom, or unrelated. "
        "Section times are INCLUSIVE per section and overlap their parents/children, so they are not additive and "
        "can exceed the window's tick time. Only the 16 busiest sections per provider have a series; a section "
        "missing from a window's list is unknown, not zero. Counts and levels in a window are what the provider "
        "counted or sampled in that second; a level with no sample there is left out (unknown), and keys named as "
        "omitted had no room in the report.")


def correlate(cap: Capture, timeline: dict[str, Any] | None, scheduler: dict[str, Any] | None, opts: Any) -> dict[str, Any]:
    if timeline is None:
        return {"available": False, "reason": "no timeline report in this capture"}
    wins = timeline["windows"]
    slow = sorted((w for w in wins if w["maxMspt"] >= opts.slow_ms), key=lambda w: w["maxMspt"], reverse=True)
    chosen = slow[: opts.top_windows]
    probes = []
    no_series = []
    spells = []
    for p in cap.providers:
        sw = series_windows(p.measurements) if p.measurements else None
        mss = magicspells_series(p.measurements) if p.measurements and not p.is_probe else None
        if mss is not None:
            spells.append((p, mss))
        elif sw is None:
            why = ("measurements unavailable" if p.measurements is None else
                   "free-form provider without a series" if not p.is_probe else "probe without a series")
            no_series.append({"plugin": p.plugin, "why": why})
        else:
            probes.append((p, sw))
    out: dict[str, Any] = {
        "available": True, "slowThresholdMs": opts.slow_ms, "slowWindowCount": len(slow),
        "windowCount": len(wins), "shownWindows": len(chosen), "note": NOTE,
        "providersWithoutSeries": no_series,
        "statement": _statement(probes + spells, no_series),
        "igniteNote": _ignite_note(cap),
        "slowCallNote": SLOW_NOTE,
        "tickMatching": None,
        "windows": [], "lift": _lift(probes, wins, slow, opts.slow_ms),
    }
    snaps = (scheduler or {}).get("snapshots", [])
    calls = slow_calls(cap)
    ticks = TickRanges(wins)
    out["tickMatching"] = ticks.status
    measured = MeasuredTicks(cap)
    out["measuredTicks"] = measured.status
    for w in chosen:
        entry = _window(w, probes, snaps, scheduler)
        entry["magicSpells"] = [_spells_in_window(p, mss, w) for p, mss in spells]
        entry["slowCalls"] = _calls_in_window(calls, w, ticks, measured)
        out["windows"].append(entry)
    return out


def _tick(v: Any) -> int | None:
    return v if isinstance(v, int) and not isinstance(v, bool) else None


class TickRanges:
    """Which timeline window a server tick belongs to, by the windows' current-tick ranges.

    Records carry ``Bukkit.getCurrentTick()``. Only the timeline's ``firstCurrentTick/lastCurrentTick`` use the same
    counter; its ``firstTick/lastTick`` are Paper tick numbers, which fall behind whenever the server pauses while empty,
    so they are never used for matching. Ranges are kept as validated endpoints and searched with bisect: a hostile or
    corrupt range costs nothing to hold, however wide. Overlapping or inverted ranges disable tick matching.
    """

    def __init__(self, wins: list[dict[str, Any]]):
        ranges = []
        bad = 0
        for w in wins:
            raw_a, raw_b = w.get("firstCurrentTick"), w.get("lastCurrentTick")
            if raw_a is None and raw_b is None:  # absent (older writer): this window has no range
                continue
            a, b = _tick(raw_a), _tick(raw_b)
            if a is None or b is None or a > b:  # present but partial, non-integer (true, "6", 1.5) or inverted
                bad += 1
                continue
            ranges.append((a, b, w["index"]))
        ranges.sort()
        overlap = any(ranges[i][0] <= ranges[i - 1][1] for i in range(1, len(ranges)))
        self.ranges = [] if bad or overlap else ranges
        self.starts = [r[0] for r in self.ranges]
        if self.ranges:
            self.status = {"available": True, "windowsWithRange": len(self.ranges),
                           "note": "calls with a server tick are placed by the timeline's current-tick ranges (exact)"}
        else:
            why = ("timeline ranges are inconsistent (inverted, partial or overlapping)" if bad or overlap else
                   "timeline has no current-tick ranges (written before 2026-11)")
            self.status = {"available": False, "reason": why,
                           "note": "every call is placed by its end offset; tick numbers are not comparable with record ticks"}

    def window(self, tick: int) -> int | None:
        i = bisect.bisect_right(self.starts, tick) - 1
        if i >= 0 and tick <= self.ranges[i][1]:
            return self.ranges[i][2]
        return None


SLOW_NOTE = ("Slow calls are individual timed calls kept by their sources: Ignite tasks and listeners (>= 1 ms, the "
             "longest few hundred), each provider's slowest sections and per-section maxima, MagicSpells slow "
             "operations. A call is placed in a window by its server tick when the timeline has current-tick ranges "
             "(exact), otherwise by its end offset. Calls nest and overlap (a listener inside a task, a section inside a "
             "listener): 'covered' is the union of their intervals on the server thread, so nothing is counted twice. "
             "The timeline's tick duration is measured before ServerTickEndEvent listeners run: time inside those "
             "listeners is reported separately and is not part of the percentage. Uncovered tick time is unattributed, "
             "not idle.")
TICK_END_EVENT = "com.destroystokyo.paper.event.server.ServerTickEndEvent"


def _ignite_note(cap: Capture) -> str | None:
    s = cap.report("scheduler") or {}
    ig = s.get("ignite") if isinstance(s.get("ignite"), dict) else None
    if ig is None:
        return None
    if isinstance(ig.get("slowExecutions"), dict):
        return ("Ignite groups are whole-capture aggregates; individual slow task and listener calls are listed per "
                "window below.")
    return ("Ignite groups are aggregated over the whole capture (first/last offsets only), and this companion "
            "predates slow-call records, so Ignite execution time cannot be attributed to individual windows.")


def slow_calls(cap: Capture) -> list[dict[str, Any]]:
    """Every timestamped slow call in the capture, normalised: source, label, ms, start/end offset (ms), tick, thread."""
    out: list[dict[str, Any]] = []
    s = cap.report("scheduler") or {}
    ig = s.get("ignite") if isinstance(s.get("ignite"), dict) else {}
    for r in _records(ig.get("slowExecutions")):
        label = f"{r.get('owner')} {_short(r.get('taskClass'))}"
        if r.get("event") != "execution":
            label = f"{r.get('event')} {label}"
        out.append(_call("Ignite task", label, r))
    for r in _records(ig.get("slowListeners")):
        call = _call("Ignite listener", f"{r.get('owner')} {_short(r.get('listenerClass'))} on "
                                         f"{_short(r.get('eventClass'))}", r)
        call["tickEnd"] = r.get("eventClass") == TICK_END_EVENT
        out.append(call)
    for p in cap.providers:
        m = p.measurements or {}
        if p.is_probe:
            seen = set()
            for sl in m.get("slowest") or []:
                if isinstance(sl, dict) and isinstance(sl.get("atMillis"), (int, float)):
                    seen.add((sl.get("section"), sl.get("atMillis"), sl.get("nanos")))
                    out.append(_probe_call(p.plugin, str(sl.get("section")), num(sl.get("nanos")), sl["atMillis"],
                                           sl.get("atTick")))
            for sec in m.get("sections") or []:
                if not isinstance(sec, dict) or not isinstance(sec.get("maxAtMillis"), (int, float)) or not num(sec.get("calls")):
                    continue
                if (sec.get("name"), sec.get("maxAtMillis"), sec.get("maxNanos")) in seen:
                    continue
                out.append(_probe_call(p.plugin, f"{sec.get('name')} (max)", num(sec.get("maxNanos")),
                                       sec["maxAtMillis"], sec.get("maxAtTick")))
        else:
            for r in _records(m.get("slowOperations")):
                label = f"{r.get('operation')} {r.get('spell')} {r.get('detail') or ''}".strip()
                out.append(_call(p.plugin, label + (f" (depth {r['depth']})" if r.get("depth") else ""), r))
    return out


def _records(sec: Any) -> list[dict[str, Any]]:
    return [r for r in sec.get("records", []) if isinstance(r, dict)] if isinstance(sec, dict) else []


def _short(name: Any) -> str:
    return str(name).rsplit(".", 1)[-1] if name else "?"


def _int_or_none(v: Any) -> int | None:
    return v if isinstance(v, int) and not isinstance(v, bool) else None


def _call(source: str, label: str, r: dict[str, Any]) -> dict[str, Any]:
    tick = _int_or_none(r.get("tick"))
    return {"source": source, "label": label, "ms": ms(num(r.get("nanos"))),
            "startMs": round(num(r.get("startOffsetMicros")) / 1000, 3),
            "endMs": round(num(r.get("endOffsetMicros")) / 1000, 3), "tick": tick,
            "thread": r.get("thread") or ("server" if tick is not None else None)}


def _probe_call(plugin: str, label: str, nanos: float, at_millis: float, tick: Any) -> dict[str, Any]:
    # probe offsets are whole milliseconds at the call's end: the start is approximate to 1 ms
    return {"source": plugin, "label": label, "ms": ms(nanos), "startMs": round(at_millis - nanos / 1e6, 3),
            "endMs": float(at_millis), "tick": _int_or_none(tick), "thread": "server"}


class MeasuredTicks:
    """Per server tick, the interval Paper measured as its duration (Ignite ``measuredTicks``), in capture ms.

    Recorded for every tick from the first ServerTickEndEvent listener call, so it does not depend on which listener
    calls were slow enough to keep. Validated as equal-length integer lists with start <= end and unique ticks; anything
    else makes it unavailable. Memory is proportional to the list lengths, never to the tick values.
    """

    def __init__(self, cap: Capture):
        s = cap.report("scheduler") or {}
        ig = s.get("ignite") if isinstance(s.get("ignite"), dict) else {}
        m = ig.get("measuredTicks")
        self.by_tick: dict[int, tuple[float, float]] = {}
        self.sorted_ticks: list[int] = []
        if not isinstance(m, dict):
            self.status = {"available": False, "reason": "no measured tick intervals (Ignite companion absent or older)"}
            return
        ticks, starts, ends = m.get("tick"), m.get("startOffsetMicros"), m.get("endOffsetMicros")
        valid = (isinstance(ticks, list) and isinstance(starts, list) and isinstance(ends, list)
                 and len(ticks) == len(starts) == len(ends)
                 and all(_tick(v) is not None for v in ticks + starts + ends)
                 and all(a <= b for a, b in zip(starts, ends)) and len(set(ticks)) == len(ticks))
        if not valid:
            self.status = {"available": False, "reason": "measured tick intervals are inconsistent"}
            return
        self.by_tick = {t: (a / 1000, b / 1000) for t, a, b in zip(ticks, starts, ends)}
        self.sorted_ticks = sorted(self.by_tick)
        dropped = _tick(m.get("dropped")) or 0
        self.status = {"available": True, "ticks": len(ticks), "dropped": dropped}

    def in_range(self, first: int, last: int) -> list[int]:
        lo = bisect.bisect_left(self.sorted_ticks, first)
        hi = bisect.bisect_right(self.sorted_ticks, last)
        return self.sorted_ticks[lo:hi]


def _placed(c: dict[str, Any], ticks: TickRanges) -> tuple[int, bool]:
    """The window a call belongs to, and whether that came from its tick (exact) rather than its end offset."""
    if c["tick"] is not None and ticks.ranges:
        w = ticks.window(c["tick"])
        if w is not None:
            return w, True
    return int(c["endMs"] // 1000), False


def _calls_in_window(calls: list[dict[str, Any]], w: dict[str, Any], ticks: TickRanges,
                     measured: "MeasuredTicks | None" = None) -> dict[str, Any]:
    idx = w["index"]
    here, by_offset = [], 0
    for c in calls:
        window, exact = _placed(c, ticks)
        if window == idx:
            here.append(c)
            by_offset += not exact
    here.sort(key=lambda c: c["ms"], reverse=True)
    server = [c for c in here if c["thread"] == "server"]
    groups: dict[int, list[dict[str, Any]]] = {}
    for c in server:
        if c["tick"] is not None:
            groups.setdefault(c["tick"], []).append(c)
    spans = _measured_spans(w, ticks, measured)
    by_tick_rows = []
    for t, cs in groups.items():
        known = measured.by_tick.get(t) if measured and spans is not None else None
        inside, tick_end = _covered(cs, [known] if known else None)
        by_tick_rows.append({"tick": t, "coveredMs": inside, "tickEndListenerMs": tick_end, "calls": len(cs),
                             "assurance": "exact" if known else "upper-bound",
                             "longest": [f"{c['source']}: {c['label']} {c['ms']} ms"
                                         for c in sorted(cs, key=lambda c: c["ms"], reverse=True)[:3]]})
    by_tick_rows.sort(key=lambda r: r["coveredMs"], reverse=True)
    tick_ms = w["ticks"] * w["meanMspt"]
    covered, tick_end = _covered(server, spans)
    share = pct(covered, tick_ms)
    return {"count": len(here), "placedByOffset": by_offset,
            "serverThreadCoveredMs": covered, "pctOfWindowTickTime": share,
            "tickEndListenerMs": tick_end,
            "outsideMeasuredTickMs": round(_union(server) - covered, 3) if spans is not None else None,
            "coverageAssurance": "exact" if spans is not None else "upper-bound",
            "coverageNote": (None if spans is not None else
                             "the measured tick intervals of this window are not all known, so only retained "
                             "ServerTickEndEvent listener records (>= 1 ms) are excluded: calls inside shorter, evicted or "
                             "unfinished tick-end listeners may still be counted. The figure is an upper bound."),
            "exceedsTickTime": bool(tick_ms and covered > tick_ms),
            "calls": here[:10], "byTick": by_tick_rows[:3],
            "otherThreadCalls": sum(1 for c in here if c["thread"] != "server")}


def _measured_spans(w: dict[str, Any], ticks: TickRanges, measured: "MeasuredTicks | None") -> list[tuple[float, float]] | None:
    """The measured intervals of every tick of the window, or None unless all of them are known.

    The window's ticks are those in its current-tick range; a tick that ended (and so has an interval) is counted
    against the window's tick count, so a window with a missing or dropped interval is not called exact.
    """
    if not measured or not measured.by_tick or not ticks.ranges:
        return None
    rng = next((r for r in ticks.ranges if r[2] == w["index"]), None)
    if rng is None:
        return None
    inside = measured.in_range(rng[0], rng[1])
    if len(inside) != int(w["ticks"]):
        return None
    return sorted(measured.by_tick[t] for t in inside)


def _covered(calls: list[dict[str, Any]], spans: list[tuple[float, float]] | None) -> tuple[float, float]:
    """Union time inside the measured tick duration, and union time inside retained ServerTickEndEvent listeners.

    With the measured intervals known this is an exact intersection: everything outside them (tick-end listeners of any
    length, the stop, empty-server pauses) is excluded whether or not a record of it was kept. Without them only the
    retained tick-end listener records can be removed, an upper bound.
    """
    if spans is None:
        return _split(calls)
    every = _intervals(calls)
    tick_end = _intervals([c for c in calls if c.get("tickEnd")])
    merged = _intervals([{"startMs": a, "endMs": b} for a, b in spans])
    return round(_overlap(every, merged), 3), round(_length(tick_end), 3)


def _intervals(calls: list[dict[str, Any]]) -> list[tuple[float, float]]:
    """The union of the calls' [start, end] intervals, as sorted disjoint intervals."""
    merged: list[tuple[float, float]] = []
    for c in sorted(calls, key=lambda c: c["startMs"]):
        a, b = c["startMs"], max(c["startMs"], c["endMs"])
        if merged and a <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], b))
        else:
            merged.append((a, b))
    return merged


def _length(spans: list[tuple[float, float]]) -> float:
    return sum(b - a for a, b in spans)


def _overlap(xs: list[tuple[float, float]], ys: list[tuple[float, float]]) -> float:
    total, i, j = 0.0, 0, 0
    while i < len(xs) and j < len(ys):
        a, b = max(xs[i][0], ys[j][0]), min(xs[i][1], ys[j][1])
        if b > a:
            total += b - a
        if xs[i][1] < ys[j][1]:
            i += 1
        else:
            j += 1
    return total


def _split(calls: list[dict[str, Any]]) -> tuple[float, float]:
    """Union time inside the timeline's measured tick duration, and union time inside ServerTickEndEvent listeners.

    Paper measures the tick duration before dispatching ServerTickEndEvent, so those listeners (and every call nested in
    them, by interval) are outside it. Only known phases are removed; nothing is clamped.
    """
    every = _intervals(calls)
    tick_end = _intervals([c for c in calls if c.get("tickEnd")])
    return round(_length(every) - _overlap(every, tick_end), 3), round(_length(tick_end), 3)


def _union(calls: list[dict[str, Any]]) -> float:
    """Total length of the union of the calls' [start, end] intervals."""
    return round(_length(_intervals(calls)), 3)


def _spells_in_window(p: Any, mss: dict[str, Any], w: dict[str, Any]) -> dict[str, Any]:
    idx = int(w["fromMillis"] // mss["windowMillis"])
    late = mss.get("late")
    tick_ms = w["ticks"] * w["meanMspt"]
    if idx >= len(mss["rootNanos"]) and late:
        # Scopes ending at or after the deadline are kept apart; they belong to this window only if all ended in it.
        if late["fitsWindow"] == idx:
            ops = sorted(({"operation": k, "calls": v["calls"], "selfMs": ms(v["selfNanos"])}
                          for k, v in late["operations"].items()), key=lambda r: r["selfMs"], reverse=True)
            return {"plugin": p.plugin, "outermostScopesMs": ms(late["rootNanos"]),
                    "pctOfWindowTickTime": pct(late["rootNanos"] / 1e6, tick_ms),
                    "operationsBySelf": [o for o in ops if o["selfMs"]][:4],
                    "note": "scopes that ended after the deadline (late totals), all within this window"}
        return {"plugin": p.plugin, "outermostScopesMs": None, "pctOfWindowTickTime": None, "operationsBySelf": [],
                "note": (f"late scopes ({ms(late['rootNanos'])} ms outermost) ended after the deadline, up to "
                         f"{late['lastEndOffsetMillis']} ms: they span more than this window, so they are not placed")}
    root = mss["rootNanos"][idx] if idx < len(mss["rootNanos"]) else None
    ops = sorted(({"operation": k, "calls": v["calls"][idx] if idx < len(v["calls"]) else None,
                   "selfMs": ms(v["selfNanos"][idx])} for k, v in mss["operations"].items() if idx < len(v["selfNanos"])),
                 key=lambda r: r["selfMs"], reverse=True)
    return {"plugin": p.plugin, "outermostScopesMs": ms(root) if root is not None else None,
            "pctOfWindowTickTime": pct(root / 1e6, tick_ms) if root is not None else None,
            "operationsBySelf": [o for o in ops if o["selfMs"]][:4],
            "note": None if root is not None else "no series value for this window"}



def _statement(probes: list[Any], no_series: list[dict[str, str]]) -> str:
    parts = []
    if probes:
        parts.append("providers with per-window series: " + ", ".join(p.plugin for p, _ in probes))
    if no_series:
        parts.append("NO series, so their activity cannot be attributed to individual windows: " +
                     ", ".join(f"{n['plugin']} ({n['why']})" for n in no_series))
    return "; ".join(parts) or "no providers in this capture"


def _window(w: dict[str, Any], probes: list[Any], snaps: list[dict[str, Any]], scheduler: dict[str, Any] | None) -> dict[str, Any]:
    tick_ms = w["ticks"] * w["meanMspt"]
    entry: dict[str, Any] = {
        "index": w["index"], "fromMillis": w["fromMillis"], "toMillis": w["toMillis"], "ticks": w["ticks"],
        "meanMspt": w["meanMspt"], "maxMspt": w["maxMspt"], "over50ms": w["over50ms"], "over100ms": w["over100ms"],
        "tickTimeMs": round(tick_ms, 1), "providers": [],
    }
    for p, (wm, secs) in probes:
        idx = int(w["fromMillis"] // wm)
        rows = []
        for name, d in secs.items():
            nanos = [num(x) for x in d["nanos"]]
            if idx >= len(nanos):
                continue
            med = median(nanos)
            here = nanos[idx]
            rows.append({"section": name, "calls": num(d["calls"][idx]) if idx < len(d["calls"]) else None,
                         "inclusiveMs": ms(here), "pctOfWindowTickTime": pct(here / 1e6, tick_ms),
                         "medianWindowMs": ms(med), "xMedian": round(here / med, 2) if med else None})
        rows.sort(key=lambda r: r["inclusiveMs"], reverse=True)
        entry["providers"].append({
            "plugin": p.plugin, "windowMillis": wm,
            "alignedWithTimeline": wm == 1000,
            "sections": rows[:6], "sectionsWithSeries": len(secs),
            "note": "no section series data for this window index" if not rows else None,
            **_keys_in_window(p.measurements, idx)})
    near = [s for s in snaps if s.get("observedAfterMillis") is not None
            and w["fromMillis"] <= s["observedAfterMillis"] < w["fromMillis"] + 1000]
    sched: dict[str, Any] = {"snapshotsInWindow": len(near), "snapshots": []}
    for s in near:
        before = snaps[snaps.index(s) - 1] if snaps.index(s) else None
        deltas = _pending_deltas(before, s)
        sched["snapshots"].append({
            "observedAfterMillis": s["observedAfterMillis"], "pendingTasks": s["pendingTasks"],
            "newTasks": s["newTasks"], "missingTasks": s["missingTasks"], "churnComparable": s["churnComparable"],
            "snapshotCollectionMs": s["collectionMs"], "largestSyncPendingChanges": deltas})
    if not near:
        sched["note"] = ("no scheduler snapshot fell in this window (snapshots are at most 1 per second at tick "
                         "end); scheduler state for this window is unknown")
    if scheduler is None:
        sched["note"] = "no scheduler report in this capture"
    entry["scheduler"] = sched
    return entry


def _keys_in_window(m: dict[str, Any], idx: int) -> dict[str, Any]:
    """The provider's counts in the window and its levels there against their usual value (None: no count/level series)."""
    ks = key_series(m)
    if ks is None:
        return {"counts": None, "levels": None}
    counts = sorted(({"count": k, "n": w[idx], "windowsWithCount": len(w)} for k, w in ks["counts"].items() if idx in w),
                    key=lambda r: r["n"], reverse=True)
    levels = []
    for k, runs in ks["levels"].items():
        value = level_at(runs, idx)
        if value is None:
            continue
        med = level_median(runs)
        levels.append({"level": k, "value": value, "medianSample": med,
                       "xMedian": round(value / med, 2) if med else None})
    levels.sort(key=lambda r: abs(r["value"] - r["medianSample"]) / (abs(r["medianSample"]) or 1), reverse=True)
    return {"counts": counts[:8], "levels": levels[:6], "omittedKeySeries": ks["omitted"]}


def _previous(snaps: list[dict[str, Any]], s: dict[str, Any]) -> dict[str, Any] | None:
    i = snaps.index(s)
    return snaps[i - 1] if i else None


def _pending_deltas(before: dict[str, Any] | None, s: dict[str, Any]) -> list[dict[str, Any]]:
    if before is None:
        return []
    a, b = before.get("syncPendingByPlugin", {}), s.get("syncPendingByPlugin", {})
    d = [{"plugin": k, "delta": b.get(k, 0) - a.get(k, 0)} for k in set(a) | set(b) if b.get(k, 0) != a.get(k, 0)]
    return sorted(d, key=lambda r: abs(r["delta"]), reverse=True)[:3]


def _lift(probes: list[Any], wins: list[dict[str, Any]], slow: list[dict[str, Any]], slow_ms: float) -> dict[str, Any]:
    """Sections whose per-window time is higher in slow windows than in the other windows."""
    slow_idx = {w["index"] for w in slow}
    other_idx = {w["index"] for w in wins} - slow_idx
    if not slow_idx or not other_idx:
        return {"available": False,
                "reason": ("no window reaches the slow threshold" if not slow_idx else
                           "every window is slow; there is no baseline of non-slow windows to compare against")}
    rows = []
    for p, (wm, secs) in probes:
        if wm != 1000:
            continue
        for name, d in secs.items():
            nanos = [num(x) for x in d["nanos"]]
            a = [nanos[i] for i in slow_idx if i < len(nanos)]
            b = [nanos[i] for i in other_idx if i < len(nanos)]
            if a and b:
                ma, mb = sum(a) / len(a), sum(b) / len(b)
                rows.append({"plugin": p.plugin, "section": name, "slowMeanMs": ms(ma), "otherMeanMs": ms(mb),
                             "diffMs": ms(ma - mb), "ratio": round(ma / mb, 2) if mb else None})
    rows.sort(key=lambda r: r["diffMs"], reverse=True)
    return {"available": True, "slowWindows": len(slow_idx), "otherWindows": len(other_idx),
            "thresholdMs": slow_ms, "sections": rows[:10],
            "note": "Mean inclusive ms per one-second window. Correlation, not causation."}
