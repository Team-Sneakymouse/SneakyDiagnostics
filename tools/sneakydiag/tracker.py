"""Entity-tracker send time from ``packets-1.json`` (``tracker`` section, written by the Ignite companion)."""
from __future__ import annotations

import collections
from typing import Any

from .loader import Capture
from .util import ms, num, pct, scan_flags, top


def _p99_upper_nanos(hist: Any, calls: float) -> float | None:
    """Upper bound of the log2 bucket holding the 99th percentile (overestimates by < 2x)."""
    if not isinstance(hist, list) or not calls:
        return None
    rank, seen = 0.99 * calls, 0.0
    for k, n in enumerate(hist):
        seen += num(n)
        if seen >= rank:
            return float(2 ** (k + 1))
    return None


def analyze_tracker(cap: Capture, n: int) -> dict[str, Any] | None:
    rep = cap.report("packets")
    if rep is None:
        return None
    out: dict[str, Any] = {"reason": rep.get("reason"), "complete": rep.get("complete"),
                           "igniteAvailability": rep.get("igniteAvailability"), "igniteError": rep.get("igniteError")}
    tr = rep.get("tracker")
    if not isinstance(tr, dict):
        out["available"] = False
        out["note"] = ("No tracker section: the Ignite companion was missing, too old or failed. "
                       "Tracker send time is unmeasured, not zero.")
        return out
    out["available"] = True
    observed_ms = num(tr.get("observedMillis"))
    total = num(tr.get("totalNanos"))
    meta = num(tr.get("metadataNanos"))
    rows = []
    problems: list[str] = []
    sum_calls = sum_nanos = 0.0
    by_type: dict[str, list[float]] = collections.defaultdict(lambda: [0.0, 0.0, 0.0, 0.0])
    by_owner: dict[str, list[float]] = collections.defaultdict(lambda: [0.0, 0.0, 0.0, 0.0])
    for g in tr.get("groups") or []:
        if not isinstance(g, dict):
            continue
        calls, nanos, mnanos, ents = num(g.get("calls")), num(g.get("totalNanos")), num(g.get("metadataNanos")), num(g.get("entities"))
        sum_calls += calls
        sum_nanos += nanos
        hist = g.get("histogramLog2")
        if isinstance(hist, list) and calls and sum(num(x) for x in hist) != calls:
            problems.append(f"{g.get('entityType')}/{g.get('owner')}: histogramLog2 total != calls")
        p99 = _p99_upper_nanos(hist, calls)
        rows.append({
            "entityType": g.get("entityType"), "owner": g.get("owner"), "entities": ents, "calls": calls,
            "totalMs": ms(nanos), "metadataMs": ms(mnanos), "otherMs": ms(max(0.0, nanos - mnanos)),
            "sharePct": pct(nanos, total), "meanUs": round(nanos / calls / 1e3, 3) if calls else None,
            "callsPerEntity": round(calls / ents, 1) if ents else None,
            "maxMs": ms(num(g.get("maxNanos"))), "p99MsUpper": ms(p99) if p99 is not None else None,
        })
        for agg, key in ((by_type, g.get("entityType")), (by_owner, g.get("owner"))):
            a = agg[str(key)]
            a[0] += ents
            a[1] += calls
            a[2] += nanos
            a[3] += mnanos
    if sum_calls != num(tr.get("calls")) or sum_nanos != total:
        problems.append("group sums differ from the tracker totals")

    def ranked(agg: dict[str, list[float]], label: str) -> list[dict[str, Any]]:
        lst = [{label: k, "entities": v[0], "calls": v[1], "totalMs": ms(v[2]), "metadataMs": ms(v[3]),
                "sharePct": pct(v[2], total)} for k, v in agg.items()]
        return top(lst, "totalMs", n)

    series = tr.get("series") if isinstance(tr.get("series"), dict) else {}
    wn = [num(x) for x in series.get("nanos") or []]
    peak = max(range(len(wn)), key=lambda i: wn[i]) if wn else None
    out.update({
        "observedMillis": observed_ms,
        "calls": num(tr.get("calls")), "entities": num(tr.get("entities")),
        "totalMs": ms(total), "metadataMs": ms(meta), "otherMs": ms(max(0.0, total - meta)),
        "pctOfObservedWall": pct(total / 1e6, observed_ms),
        "offThreadCalls": num(tr.get("offThreadCalls")), "lateCalls": num(tr.get("lateCalls")),
        "labelErrors": num(tr.get("labelErrors")), "overflowEntities": num(tr.get("overflowEntities")),
        "trackerComplete": tr.get("complete"),
        "coverageFlags": scan_flags(tr, depth=0),
        "groupCount": len(rows),
        "topGroups": top(rows, "totalMs", n),
        "byType": ranked(by_type, "entityType"),
        "byOwner": ranked(by_owner, "owner"),
        "peakWindow": ({"window": peak, "ms": ms(wn[peak]), "medianMs": ms(sorted(wn)[len(wn) // 2])}
                       if peak is not None else None),
        "problems": problems,
        "note": ("Server-thread wall time inside ServerEntity.sendChanges (movement, rotation, passengers, velocity, map "
                 "updates and changed metadata; 'other' = total - metadata). Player checks (updatePlayer) and the "
                 "tracker's entity loop are NOT included. Owner hints are candidates, not proven ownership. Two clock "
                 "reads per send are inside the measured time."),
    })
    return out


def tracker_window(cap: Capture, from_millis: float, tick_ms: float) -> dict[str, Any] | None:
    """Tracker send time in the one-second timeline window starting at ``from_millis``, with its largest groups."""
    tr = (cap.report("packets") or {}).get("tracker")
    series = tr.get("series") if isinstance(tr, dict) else None
    if not isinstance(series, dict):
        return None
    if num(series.get("windowMillis")) != 1000:
        return None
    window = int(from_millis // 1000)
    wn = series.get("nanos") or []
    if window >= len(wn):
        return None
    groups = []
    for g in series.get("groups") or []:
        vals = g.get("nanos") if isinstance(g, dict) else None
        if isinstance(vals, list) and window < len(vals) and num(vals[window]):
            groups.append({"entityType": g.get("entityType"), "owner": g.get("owner"), "ms": ms(num(vals[window]))})
    return {"ms": ms(num(wn[window])), "pctOfWindowTickTime": pct(num(wn[window]) / 1e6, tick_ms), "calls": num((series.get("calls") or [0] * (window + 1))[window]),
            "groups": top(groups, "ms", 5)}
