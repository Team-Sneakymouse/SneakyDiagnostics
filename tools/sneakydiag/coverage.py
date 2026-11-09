"""Coverage audit: which enabled plugins have a provider, what each measures, and where measured time has no provider."""
from __future__ import annotations

from typing import Any

from .loader import Capture
from .util import ms, num

NOTE = ("A plugin without a provider is UNMEASURED, not cheap: its event listeners, commands and per-tick hooks are "
        "invisible to provider rankings. Ignite columns cover only Bukkit scheduler tasks (all of them, aggregated) and "
        "the retained slow (>= threshold) listener and task calls; fast listener calls, packets, entity ticking and "
        "work inside other plugins' calls are not attributed. Ignite times are inclusive and overlap provider sections, "
        "so they rank gaps; they are not shares of tick time and must not be added to provider totals.")


def analyze_coverage(cap: Capture, providers: list[dict[str, Any]], scheduler: dict[str, Any] | None) -> dict[str, Any]:
    server = (cap.manifest or {}).get("server") if isinstance((cap.manifest or {}).get("server"), dict) else {}
    enabled = [str(p.get("name")) for p in server.get("plugins", []) if isinstance(p, dict) and p.get("name")]
    by_name = {p["plugin"]: p for p in providers}
    ignite = (scheduler or {}).get("ignite") if isinstance((scheduler or {}).get("ignite"), dict) else None
    owners = _ignite_by_owner(ignite) if ignite else {}
    slow_tasks = bool(ignite) and isinstance(ignite.get("slowExecutions"), dict)
    slow_listeners = bool(ignite) and isinstance(ignite.get("slowListeners"), dict)
    selection = cap.selected
    # Absence of a provider is established only by a captured collector index (v2 plugins-1.json or v1 embedded list).
    # Without it (scheduler-only capture, lost index) registration is unknown, not missing.
    inventory = cap.index is not None
    rows = []
    for name in sorted(set(enabled) | set(by_name), key=str.lower):
        p = by_name.get(name)
        o = owners.get(name, {})
        row: dict[str, Any] = {
            "plugin": name, "enabled": name in enabled if enabled else None,
            "provider": _status(p, selection, inventory),
            "instrumentation": p.get("instrumentationVersion") if p else None,
            "measured": _measured(p) if p else None,
            "limits": _limits(p) if p else [],
            "igniteSyncTaskMs": ms(o.get("syncNanos", 0)) if ignite else None,
            "igniteSyncTaskRuns": int(o.get("syncRuns", 0)) if ignite else None,
            "igniteAsyncTaskMs": ms(o.get("asyncNanos", 0)) if ignite else None,
            "slowTaskCalls": int(o.get("slowTasks", 0)) if slow_tasks else None,
            "slowListenerCalls": int(o.get("slowListeners", 0)) if slow_listeners else None,
            "slowListenerMs": ms(o.get("slowListenerNanos", 0)) if slow_listeners else None,
            "_rank": o.get("syncNanos", 0) + o.get("slowListenerNanos", 0),
        }
        rows.append(row)
    measured = sorted((r for r in rows if r["_rank"] > 0), key=lambda r: r["_rank"], reverse=True)
    gaps = [r for r in measured if r["provider"] == "none"]
    unknown = [r for r in measured if r["provider"] == UNKNOWN]
    for r in rows:
        r.pop("_rank", None)
    listeners = ignite.get("slowListeners") if ignite and isinstance(ignite.get("slowListeners"), dict) else None
    return {
        "available": bool(enabled or providers),
        "enabledPlugins": len(enabled) if enabled else None,
        "enabledSource": "manifest server.plugins" if enabled else "unknown (no manifest plugin list; v1 capture?)",
        "providers": len(providers),
        "selection": selection,
        "providerInventory": "captured" if inventory else "not captured",
        "igniteAvailable": ignite is not None,
        "slowListenerRecords": listeners is not None,
        "rows": rows,
        "missingProvidersByMeasuredTime": [_gap(r) for r in gaps],
        "unknownProviderByMeasuredTime": [_gap(r) for r in unknown],
        "note": NOTE + ("" if inventory else
                        " The plugins collector was not captured, so which plugins have a provider is unknown: none "
                        "is reported missing.")
                + ("" if selection in (None, "all") else
                   " Providers were selected for this capture, so a plugin shown without one may be registered "
                   "but unselected."),
    }


UNKNOWN = "unknown (plugins collector not captured)"


def _gap(r: dict[str, Any]) -> dict[str, Any]:
    return {k: r[k] for k in ("plugin", "igniteSyncTaskMs", "igniteSyncTaskRuns", "slowListenerCalls", "slowListenerMs")}


def _status(p: dict[str, Any] | None, selection: Any, inventory: bool = True) -> str:
    if p is None:
        if not inventory:
            return UNKNOWN
        return "none" if selection in (None, "all") else "none or not selected"
    if p.get("kind") == "unavailable":
        return "registered, measurements unavailable"
    return "complete" if p.get("complete") is True else "partial"


def _measured(p: dict[str, Any]) -> str:
    if p.get("kind") == "probe":
        pr = p["probe"]
        return (f"{pr['sectionCount']} sections, {len(pr['counts'])} counts, {len(pr['levels'])} levels"
                + (", per-second series" if pr["hasSeries"] else ""))
    if p.get("kind") == "magicspells":
        m = p["magicspells"]
        parts = [f"{len(m['operationTotals'])} operations", "spell/detail totals"]
        if m.get("hasSeries"):
            parts.append("per-second operation series")
        if (m.get("slowOperations") or {}).get("available"):
            parts.append("slow operations")
        if m.get("taskLifecycle"):
            parts.append("task lifecycle")
        return ", ".join(parts)
    if p.get("kind") == "freeform":
        return f"free-form: {len(p['freeform'].get('timersByTotalInclusive', []))} timers"
    return "unavailable"


def _limits(p: dict[str, Any]) -> list[str]:
    out = list(p.get("why") or [])
    if p.get("kind") == "probe":
        pr = p["probe"]
        ks = pr.get("keySeries") or {}
        left = (ks.get("omitted") or {}).get("counts", []) + (ks.get("omitted") or {}).get("levels", [])
        if left:
            out.append(f"{len(left)} count/level series left out for the report budget")
        if not pr["hasSeries"]:
            out.append("no section series: cannot be placed in timeline windows")
    elif p.get("kind") == "magicspells":
        m = p["magicspells"]
        slow = m.get("slowOperations") or {}
        if slow.get("available") and slow.get("evicted"):
            out.append(f"{slow['evicted']:g} slow operations evicted for capacity")
        if not m.get("hasSeries"):
            out.append("no per-second series: cannot be placed in timeline windows")
        for counter, state in (m.get("counterCoverage") or {}).items():
            if state != "observed":
                out.append(f"{counter}: {state}")
    elif p.get("kind") == "freeform":
        out.append("free-form: no series, cannot be placed in timeline windows")
    return out


def _ignite_by_owner(ig: dict[str, Any]) -> dict[str, dict[str, float]]:
    owners: dict[str, dict[str, float]] = {}
    for g in ig.get("groups", []):
        if not isinstance(g, dict) or g.get("event") != "execution" or not isinstance(g.get("counts"), dict):
            continue
        o = owners.setdefault(str(g.get("owner")), {})
        kind = "sync" if g.get("sync") else "async"
        o[kind + "Nanos"] = o.get(kind + "Nanos", 0) + num(g["counts"].get("totalNanos"))
        o[kind + "Runs"] = o.get(kind + "Runs", 0) + num(g["counts"].get("completed"))
    for section, key in (("slowExecutions", "slowTasks"), ("slowListeners", "slowListeners")):
        sec = ig.get(section)
        for r in (sec.get("records", []) if isinstance(sec, dict) else []):
            if isinstance(r, dict):
                o = owners.setdefault(str(r.get("owner")), {})
                o[key] = o.get(key, 0) + 1
                if section == "slowListeners":
                    o["slowListenerNanos"] = o.get("slowListenerNanos", 0) + num(r.get("nanos"))
    return owners
