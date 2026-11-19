"""Tick phases (phases-1.json): where each tick's time went, exclusive per phase, plus a natural-spawning finding."""
from __future__ import annotations

from typing import Any

from .loader import Capture, phases, split_phase
from .util import ms, pct

MISSING = "not recorded (no phases collector in this capture; it needs the Ignite companion, 2026-11)"
SPAWNING_KINDS = ("spawning.state", "spawning.spawn", "spawning.custom")

NOTE = ("Exclusive server-thread time per tick phase (nested phases subtracted, so phases add up). With Paper's measured "
        "duration for a tick, the phases plus 'other' equal it exactly; 'other' is unknown (not zero) for ticks without "
        "one. Share = phase time over the measured tick time of the ticks with a measured duration. 'outside' phases "
        "(the ServerTickEndEvent dispatch and diagnostics' own work there) are after the measured interval and not in the "
        "share. 'diagnostics' is SneakyDiagnostics' own server-thread work: subtract it to estimate the tick without the "
        "capture. 'connections' includes player entity ticks.")


def analyze_phases(cap: Capture, n: int) -> dict[str, Any]:
    ph = phases(cap)
    if ph is None:
        return {"available": False, "reason": MISSING}
    if not ph["available"]:
        return {"available": False, "reason": f"unavailable: {ph['reason']}", "complete": ph.get("complete")}
    rows_n = len(ph["ticks"])
    known = [i for i in range(rows_n) if ph["measured"][i] is not None and ph["other"][i] is not None]
    measured_us = sum(ph["measured"][i] for i in known)
    other_us = sum(ph["other"][i] for i in known)
    rows = []
    for name, col in ph["phases"].items():
        rows.append(_row(name, col, ph, known, measured_us, False))
    for name, col in ph["outside"].items():
        rows.append(_row(name, col, ph, known, measured_us, True))
    for name in ph["omittedSeries"]:
        t = ph["totals"].get(name) or ph["totals"].get("outside." + name) or {}
        rows.append({"phase": name, "world": split_phase(name, ph["worlds"])[0], "outside": False,
                     "totalMs": ms(t.get("totalNanos", 0)), "meanUsPerTick": None, "maxTickMs": ms(t.get("maxTickMicros", 0) * 1000),
                     "pctOfMeasured": None, "ticksWithTime": t.get("ticksWithTime"), "_t": t.get("totalNanos", 0),
                     "note": "no per-tick values (report value budget); totals only"})
    inside = sorted((r for r in rows if not r["outside"]), key=lambda r: r["_t"], reverse=True)
    outside = sorted((r for r in rows if r["outside"]), key=lambda r: r["_t"], reverse=True)
    other_row = {"phase": "other", "world": None, "outside": False, "totalMs": round(other_us / 1000, 3),
                 "meanUsPerTick": round(other_us / len(known), 1) if known else None,
                 "maxTickMs": round(max((ph["other"][i] for i in known), default=0) / 1000, 3),
                 "pctOfMeasured": pct(other_us, measured_us), "ticksWithTime": len(known),
                 "note": f"measured ticks only; unknown for {rows_n - len(known)} ticks" if rows_n - len(known) else None}
    consistency = _consistency(ph, known)
    by_kind: dict[str, list[float]] = {}
    by_world: dict[str, list[float]] = {}
    for name, col in ph["phases"].items():
        world, kind = split_phase(name, ph["worlds"])
        if world is None:
            continue
        s = sum(col[i] for i in known)
        k = by_kind.setdefault(kind, [0, 0])
        k[0] += s
        k[1] += sum(col)
        w = by_world.setdefault(world, [0, 0])
        w[0] += s
        w[1] += sum(col)
    diag_in = sum(ph["phases"].get("diagnostics", [0]))
    diag_out = sum(ph["outside"].get("diagnostics", [0]))
    spawning = _spawning(ph)
    why = _why(ph)
    strip = lambda rs: [{k: v for k, v in r.items() if not k.startswith("_")} for r in rs]  # noqa: E731
    return {
        "available": True, "complete": ph["complete"], "phasesComplete": ph["phasesComplete"], "why": why,
        "ticks": rows_n, "measuredTicks": len(known), "measuredMs": round(measured_us / 1000, 3),
        "meanMeasuredMsPerTick": round(measured_us / len(known) / 1000, 3) if known else None,
        "worlds": ph["worlds"],
        "inside": strip(inside[:n]) + [other_row], "insideShown": min(n, len(inside)), "insideCount": len(inside),
        "outside": strip(outside),
        "byKind": sorted(({"kind": k, "totalMs": round(v[1] / 1000, 3), "pctOfMeasured": pct(v[0], measured_us)}
                          for k, v in by_kind.items()), key=lambda r: r["totalMs"], reverse=True),
        "byWorld": sorted(({"world": k, "totalMs": round(v[1] / 1000, 3), "pctOfMeasured": pct(v[0], measured_us)}
                           for k, v in by_world.items()), key=lambda r: r["totalMs"], reverse=True),
        "diagnostics": {"insideMs": round(diag_in / 1000, 3), "outsideMs": round(diag_out / 1000, 3),
                        "pctOfMeasured": pct(sum(ph["phases"].get("diagnostics", [0])[i] for i in known), measured_us)
                        if "diagnostics" in ph["phases"] else 0.0},
        "spawning": spawning, "consistency": consistency, "note": NOTE,
    }


def _row(name: str, col: list[int], ph: dict[str, Any], known: list[int], measured_us: float, outside: bool) -> dict[str, Any]:
    key = ("outside." if outside else "") + name
    total = (ph["totals"].get(key) or {}).get("totalNanos")
    stored = sum(col)
    return {"phase": name, "world": split_phase(name, ph["worlds"])[0], "outside": outside,
            "totalMs": ms(total) if total is not None else round(stored / 1000, 3),
            "meanUsPerTick": round(stored / len(col), 1) if col else None,
            "maxTickMs": round(max(col, default=0) / 1000, 3),
            "pctOfMeasured": None if outside else pct(sum(col[i] for i in known), measured_us),
            "ticksWithTime": sum(1 for x in col if x), "_t": total if total is not None else stored * 1000, "note": None}


def _consistency(ph: dict[str, Any], known: list[int]) -> dict[str, Any]:
    """phases + other == measured for every measured tick (true by construction unless the file is inconsistent)."""
    bad = 0
    for i in known:
        if sum(col[i] for col in ph["phases"].values()) + ph["other"][i] != ph["measured"][i]:
            bad += 1
    if ph["omittedSeries"]:
        return {"checked": False, "reason": "some phases have no per-tick values (omittedSeries)"}
    return {"checked": True, "ticks": len(known), "mismatchedTicks": bad}


def _spawning(ph: dict[str, Any]) -> dict[str, Any]:
    """Time and calls of the three spawning phases per world, and a finding when any of them ran."""
    worlds = []
    for world in ph["worlds"]:
        times = {}
        for kind in SPAWNING_KINDS:
            name = f"level.{world}.{kind}"
            t = (ph["totals"].get(name) or {}).get("totalNanos")
            times[kind] = t if t is not None else sum(ph["phases"].get(name, [])) * 1000
        c = ph["spawning"].get(world, {})
        if not any(times.values()) and not any(c.values()):
            continue
        worlds.append({"world": world, "stateMs": ms(times["spawning.state"]), "stateCalls": c.get("stateCalls"),
                       "spawnMs": ms(times["spawning.spawn"]), "spawnChunkCalls": c.get("spawnChunkCalls"),
                       "spawnChunkCallsThatSpawned": c.get("spawnChunkCallsThatSpawned"),
                       "naturalSpawnAttempts": c.get("naturalSpawnAttempts"),
                       "customMs": ms(times["spawning.custom"]), "customSpawnerCalls": c.get("customSpawnerCalls"),
                       "_t": sum(times.values())})
    total = sum(w["_t"] for w in worlds)
    state = sum(w["stateMs"] for w in worlds)
    spawn = sum(w["spawnMs"] for w in worlds)
    custom = sum(w["customMs"] for w in worlds)
    chunk_calls = sum(w["spawnChunkCalls"] or 0 for w in worlds)
    spawned_calls = sum(w["spawnChunkCallsThatSpawned"] or 0 for w in worlds)
    attempts = sum(w["naturalSpawnAttempts"] or 0 for w in worlds)
    finding = None
    if total:
        parts = []
        if state:
            parts.append(f"mob-cap bookkeeping (NaturalSpawner.createState) {state:,.1f} ms over "
                         f"{sum(w['stateCalls'] or 0 for w in worlds):,.0f} calls")
        if spawn or chunk_calls:
            parts.append(f"per-chunk spawning (spawnForChunk) {spawn:,.1f} ms over {chunk_calls:,.0f} calls, "
                         f"{spawned_calls:,.0f} of which tried to spawn ({attempts:,.0f} entities attempted)")
        if custom:
            parts.append(f"custom spawners (phantoms, patrols, cats, traders, sieges) {custom:,.1f} ms")
        verdict = ("only the mob-cap bookkeeping ran; no per-chunk spawning was attempted"
                   if not chunk_calls and not custom else
                   "natural spawning attempted to spawn entities" if attempts else
                   "the spawning path ran but attempted no entity")
        finding = (f"NATURAL SPAWNING TIME: {total / 1e6:,.1f} ms - " + "; ".join(parts) + f". Verdict: {verdict}. "
                   "Natural spawning should be disabled on this server.")
    return {"worlds": [{k: v for k, v in w.items() if k != "_t"} for w in sorted(worlds, key=lambda w: w["_t"], reverse=True)],
            "totalMs": ms(total), "finding": finding}


def _why(ph: dict[str, Any]) -> list[str]:
    why = []
    if ph["dropped"]:
        why.append(f"{ph['dropped']:g} ticks over the {ph['capacity']:g}-row capacity have no per-tick values (totals include them)")
    if ph["omittedWorlds"]:
        why.append(f"worlds over the cap are not split out ({', '.join(ph['omittedWorlds'])}); their time is in the "
                   "enclosing phase")
    if ph["omittedSeries"]:
        why.append(f"{len(ph['omittedSeries'])} phases have totals but no per-tick values (report value budget)")
    if ph["depthOverflow"]:
        why.append(f"{ph['depthOverflow']:g} phase frames deeper than the tracked depth: their parents' time includes them")
    if ph["nestingErrors"] or ph["spanningTicks"]:
        why.append(f"nesting errors {ph['nestingErrors']:g}, frames across a tick boundary {ph['spanningTicks']:g}")
    if ph["malformed"]:
        why.append(f"malformed phase lists ignored: {', '.join(ph['malformed'][:5])}")
    if not ph["otherValid"]:
        why.append("'other' list is malformed: unknown for every tick")
    unknown = sum(1 for x in ph["measured"] if x is None)
    if unknown:
        why.append(f"{unknown} ticks without a measured duration ('other' unknown there"
                   + ("; includes the tick the stop interrupted" if ph["lastTickPartial"] else "") + ")")
    if ph["negativeOtherTicks"]:
        why.append(f"{ph['negativeOtherTicks']:g} ticks with negative 'other' (clock skew)")
    return why


def window_phases(ph: dict[str, Any] | None, ticks_window: Any, window_ms: int = 1000) -> dict[int, dict[str, Any]]:
    """Per timeline window index: the summed phase micros of its ticks, by current-tick range when the timeline has them
    (``ticks_window(tick) -> index | None``), else by the row's start offset. Returns index -> {"phases", "other", ...}."""
    out: dict[int, dict[str, Any]] = {}
    if not ph or not ph.get("available"):
        return out
    for i, tick in enumerate(ph["ticks"]):
        idx = ticks_window(tick) if ticks_window else None
        exact = idx is not None
        if idx is None:
            start = ph["start"][i]
            if start is None:
                continue
            idx = int(start // (window_ms * 1000))
        w = out.setdefault(idx, {"phases": {}, "other": 0, "otherUnknownTicks": 0, "ticks": 0, "byOffset": 0,
                                 "measured": 0})
        w["ticks"] += 1
        w["byOffset"] += not exact
        for name, col in ph["phases"].items():
            if col[i]:
                w["phases"][name] = w["phases"].get(name, 0) + col[i]
        if ph["other"][i] is None:
            w["otherUnknownTicks"] += 1
        else:
            w["other"] += ph["other"][i]
            w["measured"] += ph["measured"][i] or 0
    return out
