"""Ignite listener totals: every plugin listener call, ranked by server-thread self time."""
from __future__ import annotations

from typing import Any

from .loader import Capture, listener_totals
from .util import hist_upper_ns, ms, num, pct

TICK_END_EVENT = "com.destroystokyo.paper.event.server.ServerTickEndEvent"
MISSING = "not recorded (no Ignite companion, or a companion before listener totals)"

NOTE = ("Self time is server-thread wall time inside the listener minus the nested listener calls that ran inside it, so "
        "self times add up across listeners (nested scheduler work or plugin code reached without an event is not "
        "subtracted). Total time is inclusive and overlaps nested calls: do not add it. Async-event and other-thread "
        "calls count in calls/total but have no self time. ServerTickEndEvent listeners run after Paper measured the "
        "tick, so their time is outside tick durations. p99 is a log2 bucket upper bound (< 2x over).")


def short(name: Any) -> str:
    return str(name).rsplit(".", 1)[-1] if name else "?"


def analyze_listeners(cap: Capture, n: int) -> dict[str, Any]:
    lt = listener_totals(cap)
    if lt is None:
        return {"available": False, "reason": MISSING}
    tl = cap.report("timeline") or {}
    observed = num(tl.get("finishedAfterMillis")) or num((cap.report("scheduler") or {}).get("finishedAfterMillis"))
    rows = []
    owners: dict[str, dict[str, float]] = {}
    tick_end_self = 0.0
    for r in lt["rows"]:
        calls = r["calls"]
        rows.append({"owner": r["owner"], "listener": short(r["listenerClass"]), "event": short(r["eventClass"]),
                     "priority": r["priority"], "async": r["asyncEvent"], "calls": int(calls),
                     "selfMs": ms(r["selfNanos"]), "totalMsInclusive": ms(r["totalNanos"]),
                     "meanUs": round(r["totalNanos"] / calls / 1000, 2) if calls else None,
                     "p99MsUpperBound": _ms_or_none(hist_upper_ns(r["histogramLog2"], 0.99)),
                     "maxMs": ms(r["maxNanos"]), "otherThreadCalls": int(r["otherThreadCalls"]),
                     "listenerClass": r["listenerClass"], "eventClass": r["eventClass"],
                     "_self": r["selfNanos"], "_total": r["totalNanos"]})
        o = owners.setdefault(r["owner"], {"calls": 0, "selfNanos": 0, "totalNanos": 0, "keys": 0})
        o["calls"] += calls
        o["selfNanos"] += r["selfNanos"]
        o["totalNanos"] += r["totalNanos"]
        o["keys"] += 1
        if r["eventClass"] == TICK_END_EVENT:
            tick_end_self += r["selfNanos"]
    rows.sort(key=lambda r: (r["_self"], r["_total"]), reverse=True)
    by_owner = sorted(({"owner": k, "keys": int(v["keys"]), "calls": int(v["calls"]), "selfMs": ms(v["selfNanos"]),
                        "selfPctOfObserved": pct(v["selfNanos"] / 1e6, observed),
                        "totalMsInclusive": ms(v["totalNanos"]), "_self": v["selfNanos"]} for k, v in owners.items()),
                      key=lambda r: r["_self"], reverse=True)
    counted = sum(r["calls"] for r in lt["rows"]) + lt["overflowCalls"]
    slow = (cap.report("scheduler") or {}).get("ignite", {}).get("slowListeners")
    observed_calls = slow.get("observedInvocations") if isinstance(slow, dict) else None
    running = num(slow.get("runningAtStop")) if isinstance(slow, dict) else 0
    accounting = None
    if isinstance(observed_calls, (int, float)) and not isinstance(observed_calls, bool):
        diff = observed_calls - counted - running
        accounting = {"observedInvocations": observed_calls, "countedCalls": counted, "runningAtStop": running,
                      "difference": diff,
                      "note": ("calls observed = calls in totals + overflow + running at stop" if diff == 0 else
                               f"{diff:g} calls are in neither totals nor running-at-stop: listener calls on other "
                               "threads raced the stop, or the totals are inconsistent")}
    why = []
    if lt["overflowCalls"]:
        why.append(f"{lt['overflowCalls']:g} calls over the {lt['maxKeys']}-key cap are counted only in total "
                   f"({ms(lt['overflowNanos'])} ms)")
    if lt["nestingOverflow"]:
        why.append(f"{lt['nestingOverflow']:g} calls nested deeper than the tracked depth: their parents' self time "
                   "is overstated")
    if lt["series"]["omittedOwners"]:
        why.append(f"{lt['series']['omittedOwners']:g} owners without a per-second series")
    strip = lambda rs: [{k: v for k, v in r.items() if not k.startswith("_")} for r in rs]  # noqa: E731
    return {"available": True, "complete": lt["complete"], "why": why, "keys": int(lt["keys"]),
            "overflowCalls": lt["overflowCalls"], "observedMillis": observed,
            "selfMs": ms(sum(r["_self"] for r in rows)), "tickEndSelfMs": ms(tick_end_self),
            "bySelf": strip(rows[:n]), "byOwner": strip(by_owner[:n]), "accounting": accounting,
            "ownersWithSeries": sorted(lt["series"]["owners"]), "note": NOTE}


def _ms_or_none(nanos: float | None) -> float | None:
    return ms(nanos) if nanos is not None else None
