"""Window correlation: for each slow timeline window, what the providers' series and the scheduler showed."""
from __future__ import annotations

from typing import Any

from .loader import Capture
from .providers import key_series, level_at, level_median, median, series_windows
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
    for p in cap.providers:
        sw = series_windows(p.measurements) if p.measurements else None
        if sw is None:
            why = ("measurements unavailable" if p.measurements is None else
                   "free-form provider without a series" if not p.is_probe else "probe without a series")
            no_series.append({"plugin": p.plugin, "why": why})
        else:
            probes.append((p, sw))
    out: dict[str, Any] = {
        "available": True, "slowThresholdMs": opts.slow_ms, "slowWindowCount": len(slow),
        "windowCount": len(wins), "shownWindows": len(chosen), "note": NOTE,
        "providersWithoutSeries": no_series,
        "statement": _statement(probes, no_series),
        "igniteNote": ("Ignite groups are aggregated over the whole capture (first/last offsets only), so "
                       "Ignite execution time cannot be attributed to individual windows."
                       if (scheduler or {}).get("ignite") else None),
        "windows": [], "lift": _lift(probes, wins, slow, opts.slow_ms),
    }
    snaps = (scheduler or {}).get("snapshots", [])
    for w in chosen:
        out["windows"].append(_window(w, probes, snaps, scheduler))
    return out


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
