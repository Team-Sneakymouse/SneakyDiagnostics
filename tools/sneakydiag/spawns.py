"""Entity spawns from the timeline report, with natural-type spawning called out (it should be off on this server)."""
from __future__ import annotations

from typing import Any

from .loader import Capture, spawns

# Spawn reasons produced by vanilla world mechanics rather than a plugin or a player action. Natural spawning is
# disabled on LoM, so any of these (even cancelled) is a finding.
NATURAL_REASONS = frozenset({"NATURAL", "CHUNK_GEN", "JOCKEY", "MOUNT", "PATROL", "RAID", "REINFORCEMENTS", "TRAP",
                             "VILLAGE_DEFENSE", "VILLAGE_INVASION", "SPAWNER", "TRIAL_SPAWNER"})
MISSING = "not recorded (timeline written before spawn counts, 2026-11)"

NOTE = ("Spawns counted at MONITOR priority while the timeline recorded: 'spawned' passed every listener, 'cancelled' "
        "was cancelled by one. For CUSTOM/DEFAULT spawns the plugin is the first plugin class on the spawning stack, a "
        "hint (a library or callback can stand between); other reasons carry no plugin. Counts are not entity tick cost.")


def analyze_spawns(cap: Capture, n: int) -> dict[str, Any]:
    sp = spawns(cap)
    if sp is None:
        return {"available": False, "reason": MISSING}
    by_reason: dict[str, list[float]] = {}
    by_plugin: dict[str, list[float]] = {}
    natural = []
    peak = 0.0
    for r in sp["rows"]:
        a = by_reason.setdefault(r["reason"], [0, 0])
        a[0] += r["total"]
        a[1] += r["cancelled"]
        if r["plugin"] is not None:
            p = by_plugin.setdefault(r["plugin"], [0, 0])
            p[0] += r["total"]
            p[1] += r["cancelled"]
        if r["reason"] in NATURAL_REASONS:
            ws = sorted(r["windows"])
            natural.append({"reason": r["reason"], "entityType": r["entityType"], "spawned": r["total"],
                            "cancelled": r["cancelled"], "firstWindow": ws[0] if ws else None,
                            "lastWindow": ws[-1] if ws else None})
        peak = max([peak, *r["windows"].values()])
    natural.sort(key=lambda r: r["spawned"] + r["cancelled"], reverse=True)
    top = sorted(sp["rows"], key=lambda r: (r["total"], r["cancelled"]), reverse=True)[:n]
    spawned = sum(r["spawned"] for r in natural)
    cancelled = sum(r["cancelled"] for r in natural)
    finding = None
    if natural:
        finding = (f"NATURAL-TYPE SPAWNS: {spawned:,.0f} spawned and {cancelled:,.0f} cancelled with reasons "
                   + ", ".join(sorted({r['reason'] for r in natural}))
                   + ". Natural spawning should be disabled on this server; something in the spawning path is active.")
    why = []
    if sp["overflow"]:
        why.append(f"{sp['overflow']:g} spawns over the key cap are counted only in 'observed'")
    if sp["droppedOffThread"]:
        why.append(f"{sp['droppedOffThread']:g} spawn events off the server thread were not classified")
    return {
        "available": True, "complete": sp["complete"], "why": why, "observed": sp["observed"],
        "spawned": sum(r["total"] for r in sp["rows"]), "cancelled": sum(r["cancelled"] for r in sp["rows"]),
        "peakPerSecondOneKey": peak,
        "byReason": sorted(({"reason": k, "spawned": v[0], "cancelled": v[1], "natural": k in NATURAL_REASONS}
                            for k, v in by_reason.items()), key=lambda r: r["spawned"] + r["cancelled"], reverse=True),
        "byPlugin": sorted(({"plugin": k, "spawned": v[0], "cancelled": v[1]} for k, v in by_plugin.items()),
                           key=lambda r: r["spawned"], reverse=True)[:n],
        "top": [{k: r[k] for k in ("reason", "entityType", "plugin", "total", "cancelled")}
                | {"windowsWithSpawns": len(r["windows"])} for r in top],
        "natural": natural, "naturalFinding": finding, "note": NOTE,
    }
