"""Packets from ``packets-1.json`` (Ignite ``packets`` probe): outbound classes, send causes, tracker owners, inbound."""
from __future__ import annotations

import collections
from typing import Any

from .loader import TRACKER_CAUSES, Capture, packets, phases, split_phase
from .util import hist_upper_ns, ms, num, pct, top

PREFIX = "net.minecraft.network.protocol."

RELATION_NOTE = (
    "How the packet times relate: outbound send time is INCLUSIVE server-thread time inside Connection.send "
    "(ProtocolLib's proxy and the Netty hand-off are below it). Sends happen inside every tick phase, so send time "
    "overlaps the phases and must not be added to them. Tracker causes (tracker.update + tracker.metadata) are sends "
    "made inside ServerEntity.sendChanges, so they are part of the Tracker sends total (sendChanges, inclusive), which "
    "in turn is part of the EXCLUSIVE level.<world>.tracker phase (Moonrise's tracker tick: sendChanges, player checks "
    "and tracker.spawn/despawn pairing). Inbound handling time is inclusive (plugin events and packets sent in "
    "response are inside it) and lies in the tick-start-tasks phase, or between ticks when handled from pollTask.")


def short(name: str) -> str:
    """Mojang packet class without the common package prefix (``game.ClientboundSetEntityDataPacket``)."""
    return name[len(PREFIX):] if name.startswith(PREFIX) else name


def _fan_out(packets_: float, broadcasts: float | None) -> float | None:
    return round(packets_ / broadcasts, 2) if broadcasts else None


def _observed_s(section: dict[str, Any] | None) -> float:
    return num((section or {}).get("observedMillis")) / 1000


def analyze_packets(cap: Capture, n: int) -> dict[str, Any] | None:
    p = packets(cap)
    if p is None:
        return None
    out: dict[str, Any] = {"reason": p["reason"], "complete": p["complete"], "availability": p["availability"],
                           "error": p["error"]}
    if all(p[k] is None for k in ("outbound", "causes", "trackerCauses", "inbound")):
        out["available"] = False
        out["note"] = ("No packet sections: the Ignite companion was missing, older than the packets probe, or failed. "
                       "Packet counts and send time are unmeasured, not zero.")
        return out
    out["available"] = True
    out["outbound"] = _outbound(p["outbound"], n)
    out["causes"] = _causes(p["causes"], n)
    out["trackerOwners"] = _tracker_owners(p["trackerCauses"], n)
    out["inbound"] = _inbound(p["inbound"], n)
    out["relations"] = _relations(cap, p)
    return out


def _outbound(ob: dict[str, Any] | None, n: int) -> dict[str, Any] | None:
    if ob is None:
        return None
    secs = _observed_s(ob)
    class_total = num(ob["classNanos"])
    rows = []
    problems = []
    for c in ob["classes"]:
        hist = c["histogramLog2"]
        if hist is not None and sum(hist) != c["serverThreadSends"]:
            problems.append(f"{short(c['packetClass'])}: histogramLog2 total != serverThreadSends")
        p99 = hist_upper_ns(hist, 0.99)
        rows.append({"packetClass": short(c["packetClass"]), "sends": c["sends"],
                     "sendsPerSec": round(c["sends"] / secs, 1) if secs else None,
                     "otherThreadSends": c["otherThreadSends"], "bundled": c["bundled"],
                     "totalMs": ms(c["totalNanos"]), "sharePct": pct(c["totalNanos"], class_total),
                     "meanUs": round(c["totalNanos"] / c["serverThreadSends"] / 1e3, 3) if c["serverThreadSends"] else None,
                     "p99MsUpper": ms(p99) if p99 is not None else None, "maxMs": ms(c["maxNanos"]),
                     "otherThreadMs": ms(c["otherThreadNanos"])})
    if sum(c["serverThreadSends"] for c in ob["classes"]) != ob["serverThreadSends"]:
        problems.append("class sends differ from the outbound total")
    wn = ob["series"]["nanos"]
    peak = max(range(len(wn)), key=lambda i: wn[i]) if wn else None
    return {
        "observedMillis": ob["observedMillis"], "sends": ob["sends"], "serverThreadSends": ob["serverThreadSends"],
        "otherThreadSends": ob["otherThreadSends"], "sendsPerSec": round(ob["sends"] / secs, 1) if secs else None,
        "serverThreadMs": ms(ob["serverThreadNanos"]), "pctOfObservedWall": pct(ob["serverThreadNanos"] / 1e6, ob["observedMillis"]),
        "classMs": ms(class_total), "nestedSends": ob["nestedSends"], "otherThreadMs": ms(ob["otherThreadNanos"]),
        "bundles": ob["bundles"], "bundledPackets": ob["bundledPackets"], "lateSends": ob["lateSends"],
        "classOverflow": ob["classOverflow"], "complete": ob["complete"], "classCount": len(rows),
        "topClasses": top(rows, "totalMs", n),
        "peakWindow": ({"window": peak, "ms": ms(wn[peak]), "medianMs": ms(sorted(wn)[len(wn) // 2])} if peak is not None else None),
        "problems": problems,
    }


def _causes(ca: dict[str, Any] | None, n: int) -> dict[str, Any] | None:
    if ca is None:
        return None
    total = sum(r["totalNanos"] for r in ca["rows"])
    rows = [{"cause": r["cause"], "packets": r["packets"], "broadcasts": r["broadcasts"],
             "fanOut": _fan_out(r["packets"], r["broadcasts"]), "totalMs": ms(r["totalNanos"]),
             "sharePct": pct(r["totalNanos"], total),
             "meanUs": round(r["totalNanos"] / r["packets"] / 1e3, 3) if r["packets"] else None,
             "usPerBroadcast": round(r["totalNanos"] / r["broadcasts"] / 1e3, 3) if r["broadcasts"] else None,
             "maxMs": ms(r["maxNanos"])} for r in ca["rows"]]
    return {"rows": top(rows, "totalMs", n), "totalMs": ms(total), "complete": ca["complete"],
            "depthOverflow": ca["depthOverflow"], "nestingErrors": ca["nestingErrors"]}


def _tracker_owners(tc: dict[str, Any] | None, n: int) -> dict[str, Any] | None:
    if tc is None:
        return None
    total = sum(g["totalNanos"] for g in tc["groups"])
    rows = []
    by_owner: dict[str, list[float]] = collections.defaultdict(lambda: [0.0, 0.0, 0.0, 0.0])
    by_type: dict[str, list[float]] = collections.defaultdict(lambda: [0.0, 0.0, 0.0, 0.0])
    for g in tc["groups"]:
        row = {"entityType": g["entityType"], "owner": g["owner"], "entities": g["entities"], "packets": g["packets"],
               "broadcasts": g["broadcasts"], "fanOut": _fan_out(g["packets"], g["broadcasts"]),
               "totalMs": ms(g["totalNanos"]), "sharePct": pct(g["totalNanos"], total)}
        for cause in TRACKER_CAUSES:
            row[cause.split(".")[1] + "Ms"] = ms(g["causes"].get(cause, {}).get("totalNanos", 0))
        rows.append(row)
        for agg, key in ((by_owner, g["owner"]), (by_type, g["entityType"])):
            a = agg[key]
            a[0] += g["entities"]
            a[1] += g["packets"]
            a[2] += g["broadcasts"]
            a[3] += g["totalNanos"]

    def ranked(agg: dict[str, list[float]], label: str) -> list[dict[str, Any]]:
        return top([{label: k, "entities": v[0], "packets": v[1], "broadcasts": v[2], "fanOut": _fan_out(v[1], v[2]),
                     "totalMs": ms(v[3]), "sharePct": pct(v[3], total)} for k, v in agg.items()], "totalMs", n)

    lookups = tc["spawnLookups"]
    return {"totalMs": ms(total), "groupCount": len(rows), "topGroups": top(rows, "totalMs", n),
            "byOwner": ranked(by_owner, "owner"), "byType": ranked(by_type, "entityType"),
            "entities": tc["entities"], "overflowEntities": tc["overflowEntities"], "labelErrors": tc["labelErrors"],
            "complete": tc["complete"],
            "spawnLookups": lookups, "spawnsAttributed": tc["spawnsAttributed"],
            "spawnLookupMs": ms(tc["spawnLookupNanos"]), "passThroughFrames": tc["passThroughFrames"],
            "note": ("Owner 'plugin:<name>' = the plugin on the stack when the entity was spawned during a packets capture "
                     "(pass-through frames such as CraftEngine's level callback skipped); otherwise the tracker owner hint "
                     "((player), class:<name>, PDC namespaces, (none)). Hints are candidates, not proven ownership. "
                     "Broadcasts are calls of the cause for that entity (sendChanges per tick, one per pairing), so "
                     "fan-out is packets per call.")}


def _inbound(ib: dict[str, Any] | None, n: int) -> dict[str, Any] | None:
    if ib is None:
        return None
    secs = _observed_s(ib)
    total = ib["totalNanos"]
    rows = []
    for c in ib["classes"]:
        p99 = hist_upper_ns(c["histogramLog2"], 0.99)
        rows.append({"packetClass": short(c["packetClass"]), "packets": c["packets"],
                     "packetsPerSec": round(c["packets"] / secs, 1) if secs else None,
                     "totalMs": ms(c["totalNanos"]), "sharePct": pct(c["totalNanos"], total),
                     "meanUs": round(c["totalNanos"] / c["packets"] / 1e3, 3) if c["packets"] else None,
                     "p99MsUpper": ms(p99) if p99 is not None else None, "maxMs": ms(c["maxNanos"]),
                     "betweenTicksPackets": c["betweenTicksPackets"]})
    return {"observedMillis": ib["observedMillis"], "packets": ib["packets"], "totalMs": ms(total),
            "pctOfObservedWall": pct(total / 1e6, ib["observedMillis"]),
            "betweenTicksPackets": ib["betweenTicksPackets"], "betweenTicksMs": ms(ib["betweenTicksNanos"]),
            "offThreadPackets": ib["offThreadPackets"], "latePackets": ib["latePackets"],
            "classOverflow": ib["classOverflow"], "complete": ib["complete"], "topClasses": top(rows, "totalMs", n)}


def _relations(cap: Capture, p: dict[str, Any]) -> dict[str, Any]:
    """Outbound and tracker send time next to the tracker section and the tracker/tick-start phases."""
    causes = {r["cause"]: r for r in (p["causes"] or {}).get("rows", [])}
    in_send_changes = sum(num(causes.get(c, {}).get("totalNanos")) for c in ("tracker.update", "tracker.metadata"))
    pairing = sum(num(causes.get(c, {}).get("totalNanos")) for c in ("tracker.spawn", "tracker.despawn"))
    tracker = (cap.report("packets") or {}).get("tracker")
    tracker_ns = num(tracker.get("totalNanos")) if isinstance(tracker, dict) else None
    ph = phases(cap)
    phase_ns = tick_start_ns = measured_us = None
    if ph is not None and ph.get("available"):
        phase_ns = sum(v["totalNanos"] for k, v in ph["totals"].items() if split_phase(k, ph["worlds"])[1] == "tracker"
                       and k.startswith("level."))
        tick_start_ns = ph["totals"].get("tick-start-tasks", {}).get("totalNanos")
        measured_us = sum(m for m in ph["measured"] if m is not None)
    ob = p["outbound"]
    ib = p["inbound"]
    return {
        "outboundSendMs": ms(ob["serverThreadNanos"]) if ob else None,
        "outboundPctOfMeasuredTicks": pct(ob["serverThreadNanos"] / 1e3, measured_us) if ob and measured_us else None,
        "trackerSendChangesSendMs": ms(in_send_changes) if p["causes"] else None,
        "trackerPairingSendMs": ms(pairing) if p["causes"] else None,
        "trackerSectionMs": ms(tracker_ns) if tracker_ns is not None else None,
        "sendShareOfTrackerSectionPct": pct(in_send_changes, tracker_ns) if tracker_ns and p["causes"] else None,
        "trackerPhaseMs": ms(phase_ns) if phase_ns is not None else None,
        "inboundMs": ms(ib["totalNanos"]) if ib else None,
        "inboundTickStartMs": ms(ib["totalNanos"] - ib["betweenTicksNanos"]) if ib else None,
        "tickStartPhaseMs": ms(tick_start_ns) if tick_start_ns is not None else None,
        "note": RELATION_NOTE,
    }


def packets_window(cap: Capture, from_millis: float, tick_ms: float) -> dict[str, Any] | None:
    """Outbound send time (inclusive) and inbound handling in the one-second window at ``from_millis``."""
    p = packets(cap)
    if p is None or p["outbound"] is None:
        return None
    s = p["outbound"]["series"]
    if s["windowMillis"] != 1000:
        return None
    i = int(from_millis // 1000)
    if i >= len(s["nanos"]):
        return None
    classes = [{"packetClass": short(name), "ms": ms(c["nanos"][i]), "sends": c["sends"][i]}
               for name, c in s["classes"].items() if i < len(c["nanos"]) and c["nanos"][i]]
    causes = []
    if p["causes"] is not None and p["causes"]["series"]["windowMillis"] == 1000:
        causes = [{"cause": name, "ms": ms(c["nanos"][i]), "packets": c["packets"][i]}
                  for name, c in p["causes"]["series"]["causes"].items() if i < len(c["nanos"]) and c["nanos"][i]]
    inbound = None
    if p["inbound"] is not None and p["inbound"]["series"]["windowMillis"] == 1000 and i < len(p["inbound"]["series"]["nanos"]):
        inbound = ms(p["inbound"]["series"]["nanos"][i])
    nanos = s["nanos"][i]
    return {"ms": ms(nanos), "sends": s["sends"][i] if i < len(s["sends"]) else None,
            "pctOfWindowTickTime": pct(nanos / 1e6, tick_ms), "classes": top(classes, "ms", 4),
            "causes": top(causes, "ms", 4), "inboundMs": inbound}


def cause_series(cap: Capture) -> dict[str, list[float]] | None:
    """Per-second send nanos per cause (for the slow-vs-other comparison), or None."""
    p = packets(cap)
    if p is None or p["causes"] is None or p["causes"]["series"]["windowMillis"] != 1000:
        return None
    return {name: c["nanos"] for name, c in p["causes"]["series"]["causes"].items()}
