"""Before/after comparison of two captures (the controlled-change workflow)."""
from __future__ import annotations

from pathlib import Path
from typing import Any

from .analysis import Options, analyze
from .loader import Capture
from .providers import recover_spells
from .sparkwire import ProfileError, read_profile
from .spark import utc
from .util import ms, num

Key = tuple[str, str]  # (provider, name)


# ------------------------------------------------------------------ metric extraction

def workload_of(cap: Capture) -> dict[str, Any]:
    """Players/entities/chunks at start (and end) from the manifest, else from spark, else unknown."""
    wl = (cap.manifest or {}).get("workload")
    if isinstance(wl, dict) and isinstance(wl.get("atStart"), dict):
        return {"source": "manifest", "atStart": _norm_wl(wl["atStart"]), "atEnd": _norm_wl(wl.get("atEnd"))}
    profile = cap.path / "profile.sparkprofile"
    if profile.is_file():
        try:
            prof = read_profile(profile, thread=None)
        except (OSError, ProfileError):
            prof = None
        if prof and prof.time_windows:
            st = prof.window_stats.get(prof.time_windows[-1])
            if st:
                return {"source": "spark", "approximate": True,
                        "note": f"last retained spark window ({utc(st.startTime)} .. {utc(st.endTime)}); it may "
                                "precede or only partly overlap the capture",
                        "atStart": {"players": st.players, "entities": st.entities, "chunks": st.chunks,
                                    "worlds": None}, "atEnd": None}
    return {"source": None, "atStart": None, "atEnd": None}


def _norm_wl(x: Any) -> dict[str, Any] | None:
    if not isinstance(x, dict):
        return None
    worlds = [w for w in x.get("worlds") or [] if isinstance(w, dict)]
    return {"players": x.get("players"), "entities": sum(int(num(w.get("entities"))) for w in worlds),
            "chunks": sum(int(num(w.get("loadedChunks"))) for w in worlds),
            "worlds": {str(w.get("name")): {"entities": w.get("entities"), "chunks": w.get("loadedChunks")}
                       for w in worlds}}


def extract(cap: Capture) -> dict[str, Any]:
    r = analyze(cap, Options(top=10**9))
    tl = r["timeline"]
    observed_ms = (tl or {}).get("observedMillis") or 0
    m: dict[str, Any] = {
        "result": r, "workload": workload_of(cap), "timeline": tl,
        "sections": {}, "levels": {}, "counts": {}, "freeform": {}, "scheduler": {}, "providers": {},
    }
    for p in cap.providers:
        pr = next((x for x in r["providers"] if x["plugin"] == p.plugin), {})
        m["providers"][p.plugin] = {"complete": p.complete, "reason": p.reason, "version": p.plugin_version,
                                    "instrumentation": p.instrumentation_version, "why": pr.get("why", []),
                                    "available": p.measurements is not None, "kind": pr.get("kind")}
        if p.measurements is None:
            continue
        meas = p.measurements
        if p.is_probe:
            span = num(meas.get("observedMillis")) or observed_ms
            for s in meas.get("sections", []):
                if isinstance(s, dict) and isinstance(s.get("name"), str):
                    m["sections"][(p.plugin, s["name"])] = {
                        "selfNanos": num(s.get("selfNanos")), "totalNanos": num(s.get("totalNanos")),
                        "calls": num(s.get("calls")), "p95Nanos": num(s.get("p95Nanos")), "spanMs": span}
            for k, v in (meas.get("levels") or {}).items():
                if isinstance(v, dict):
                    m["levels"][(p.plugin, k)] = {"mean": num(v.get("mean")), "max": num(v.get("max")),
                                                  "last": num(v.get("last")), "samples": num(v.get("samples"))}
            for k, v in (meas.get("counts") or {}).items():
                m["counts"][(p.plugin, k)] = {"value": num(v), "spanMs": span}
        elif pr.get("kind") == "magicspells":
            span = num(meas.get("elapsedNanos")) / 1e6 or observed_ms
            rec = recover_spells(meas, [x for x in meas.get("rows", []) if isinstance(x, dict)],
                                 meas.get("totals") or {}, 10**9)
            rows = rec["topBySelf"] if rec["valid"] else None
            for x in rows or []:
                m["freeform"][(p.plugin, "spell:" + x["spell"])] = {
                    "selfNanos": x["selfMs"] * 1e6, "calls": x["calls"], "spanMs": span, "exactDetail": True}
            if rows is None:
                for x in meas.get("spellTotals", []):
                    if isinstance(x, dict):
                        m["freeform"][(p.plugin, "spell:" + str(x.get("spell")).lower())] = {
                            "selfNanos": num(x.get("selfNanos")), "calls": num(x.get("calls")), "spanMs": span,
                            "exactDetail": False}
            for x in meas.get("operationTotals", []):
                if isinstance(x, dict):
                    m["freeform"][(p.plugin, "operation:" + str(x.get("operation")))] = {
                        "selfNanos": num(x.get("selfNanos")), "calls": num(x.get("calls")), "spanMs": span}
            t = meas.get("totals") or {}
            m["freeform"][(p.plugin, "TOTAL")] = {"selfNanos": num(t.get("selfNanos")), "calls": num(t.get("calls")),
                                                  "spanMs": span}
        else:
            for t in pr.get("freeform", {}).get("timersByTotalInclusive", []):
                if "[waiting" in t["path"]:
                    continue  # waiting time is not cost; compare it from the individual reports
                m["freeform"][(p.plugin, "timer:" + t["path"])] = {
                    "selfNanos": t["totalMsInclusive"] * 1e6, "calls": t["count"], "spanMs": observed_ms,
                    "inclusive": True}
    sch = r["scheduler"]
    if sch:
        span = observed_ms
        m["scheduler"]["pending.max"] = sch["pending"]["max"]
        m["scheduler"]["pending.last"] = sch["pending"]["last"]
        for row in sch["pendingByPlugin"]:
            m["scheduler"][f"syncMax:{row['plugin']}"] = row["syncMax"]
        ig = sch.get("ignite")
        if ig:
            for row in ig["byOwnerEvent"]:
                m["freeform"][(row["owner"], f"ignite:{row['event']}:{'sync' if row['sync'] else 'async'}")] = {
                    "selfNanos": row["totalMsInclusive"] * 1e6, "calls": row["started"], "spanMs": span,
                    "inclusive": True}
    return m


# ------------------------------------------------------------------ comparison

def compare(before: Capture, after: Capture, *, tolerance: float = 0.10, top: int = 20) -> dict[str, Any]:
    b, a = extract(before), extract(after)
    out: dict[str, Any] = {
        "before": _id(before), "after": _id(after), "tolerance": tolerance,
        "warnings": _warnings(before, after, b, a, tolerance),
        "timeline": _timeline_delta(b["timeline"], a["timeline"]),
    }
    out["sections"] = _rate_deltas(b["sections"], a["sections"], top, ("selfNanos", "totalNanos"))
    out["freeform"] = _rate_deltas(b["freeform"], a["freeform"], top, ("selfNanos",))
    out["levels"] = _level_deltas(b["levels"], a["levels"], top)
    out["counts"] = _count_deltas(b["counts"], a["counts"], top)
    out["scheduler"] = [{"metric": k, "before": b["scheduler"].get(k), "after": a["scheduler"].get(k)}
                        for k in sorted(set(b["scheduler"]) | set(a["scheduler"]))
                        if k.startswith("pending") or abs(num(a["scheduler"].get(k)) - num(b["scheduler"].get(k))) > 0][:top + 2]
    out["comparable"] = not any(w["severity"] == "blocker" for w in out["warnings"])
    return out


def _id(c: Capture) -> dict[str, Any]:
    return {"captureId": c.capture_id, "schemaVersion": c.schema_version, "path": c.path.name,
            "startedAt": c.header.get("captureStartedAt")}


def _warn(sev: str, text: str) -> dict[str, str]:
    return {"severity": sev, "text": text}


def _warnings(bc: Capture, ac: Capture, b: dict[str, Any], a: dict[str, Any], tol: float) -> list[dict[str, str]]:
    w: list[dict[str, str]] = []
    if bc.capture_id and bc.capture_id == ac.capture_id:
        w.append(_warn("blocker", "both arguments are the same capture"))
    for label, cap, m in (("before", bc, b), ("after", ac, a)):
        for text in cap.warnings:
            w.append(_warn("warning", f"{label}: {text}"))
        inv = m["result"]["inventory"]
        for c in inv["collectors"]:
            if c["complete"] is False:
                w.append(_warn("warning", f"{label}: collector {c['collector']} is INCOMPLETE (reason={c['reason']}); "
                                          "its numbers are partial"))
        for name, p in m["providers"].items():
            if p["complete"] is not True:
                w.append(_warn("blocker", f"{label}: provider {name} is INCOMPLETE/unavailable "
                                          f"({'; '.join(p['why']) or 'no cause recorded'}); its deltas are not "
                                          "reliable and may reflect coverage, not cost"))
        if m["timeline"] is None:
            w.append(_warn("warning", f"{label}: no timeline report; tick statistics cannot be compared"))
    w.extend(_workload_warnings(b["workload"], a["workload"], tol))
    tb, ta = b["timeline"], a["timeline"]
    if tb and ta and tb["observedMillis"] and abs(ta["observedMillis"] - tb["observedMillis"]) > tol * tb["observedMillis"]:
        w.append(_warn("warning", f"capture lengths differ ({tb['observedMillis'] / 1000:.0f}s vs "
                                  f"{ta['observedMillis'] / 1000:.0f}s); per-second rates are compared, but "
                                  "short captures are noisier"))
    if bc.schema_version != ac.schema_version:
        w.append(_warn("warning", f"schema versions differ (v{bc.schema_version} vs v{ac.schema_version}); probe/1 "
                                  "sections exist only in v2, so section deltas may show everything as added/removed"))
    only_b, only_a = set(b["providers"]) - set(a["providers"]), set(a["providers"]) - set(b["providers"])
    if only_b or only_a:
        w.append(_warn("warning", f"provider sets differ: only before {sorted(only_b)}, only after {sorted(only_a)}"))
    for name in sorted(set(b["providers"]) & set(a["providers"])):
        pb, pa = b["providers"][name], a["providers"][name]
        if pb["instrumentation"] != pa["instrumentation"]:
            w.append(_warn("warning", f"{name}: instrumentation version changed ({pb['instrumentation']} -> "
                                      f"{pa['instrumentation']}); measurement meaning may differ"))
        if pb["version"] != pa["version"]:
            w.append(_warn("info", f"{name}: plugin version {pb['version']} -> {pa['version']} "
                                   "(expected if this is the change under test)"))
    hb, ha = bc.header, ac.header
    for key in ("serverVersion", "diagnosticsVersion", "javaVersion"):
        if hb.get(key) != ha.get(key):
            w.append(_warn("warning" if key != "diagnosticsVersion" else "info",
                           f"{key} differs: {hb.get(key)} -> {ha.get(key)}"))
    return w


def _workload_warnings(wb: dict[str, Any], wa: dict[str, Any], tol: float) -> list[dict[str, str]]:
    out: list[dict[str, str]] = []
    if wb["atStart"] is None or wa["atStart"] is None:
        side = " and ".join(n for n, x in (("before", wb), ("after", wa)) if x["atStart"] is None)
        out.append(_warn("blocker", f"WORKLOAD UNKNOWN for {side} (no manifest workload and no spark window): "
                                    "players/entities/chunks cannot be compared, so a delta may be workload, not the change"))
        return out
    for n, x in (("before", wb), ("after", wa)):
        if x.get("approximate"):
            out.append(_warn("warning", f"{n}: workload taken from spark, approximate: {x['note']}"))
    sb, sa = wb["atStart"], wa["atStart"]
    for key in ("players", "entities", "chunks"):
        vb, va = sb.get(key), sa.get(key)
        if vb is None or va is None:
            continue
        base = max(abs(num(vb)), 1)
        if abs(num(va) - num(vb)) / base > tol:
            out.append(_warn("blocker", f"WORKLOAD DIFFERS: {key} {vb} -> {va} ({(num(va) - num(vb)) / base:+.0%}, "
                                        f"tolerance {tol:.0%}); deltas below are confounded by load"))
    if sb.get("worlds") and sa.get("worlds") and set(sb["worlds"]) != set(sa["worlds"]):
        out.append(_warn("warning", f"loaded worlds differ: {sorted(sb['worlds'])} vs {sorted(sa['worlds'])}"))
    for n, x in (("before", wb), ("after", wa)):
        s, e = x["atStart"], x.get("atEnd")
        if e and s.get("players") is not None and e.get("players") is not None and \
                abs(num(e["players"]) - num(s["players"])) > tol * max(num(s["players"]), 1):
            out.append(_warn("warning", f"{n}: player count changed during the capture ({s['players']} -> {e['players']})"))
    return out


def _timeline_delta(tb: dict[str, Any] | None, ta: dict[str, Any] | None) -> list[dict[str, Any]]:
    if tb is None or ta is None:
        return []
    keys = [("observedTicksPerSecond", "observed ticks/s"), ("tickWeightedMeanMs", "tick mean ms"),
            ("maxMs", "tick max ms"), ("over50Pct", "% ticks > 50 ms"), ("over100Pct", "% ticks > 100 ms"),
            ("ticks", "tick count"), ("collectionOverheadMs", "timeline overhead ms")]
    return [_delta_row(label, tb.get(k), ta.get(k)) for k, label in keys]


def _delta_row(label: str, b: Any, a: Any) -> dict[str, Any]:
    d = None if b is None or a is None else round(num(a) - num(b), 3)
    return {"metric": label, "before": b, "after": a, "delta": d,
            "pct": round(100 * (num(a) - num(b)) / num(b), 1) if d is not None and num(b) else None}


def _rate_deltas(b: dict[Key, dict[str, Any]], a: dict[Key, dict[str, Any]], top: int,
                 fields: tuple[str, ...]) -> dict[str, Any]:
    rows, added, removed = [], [], []
    for key in set(b) | set(a):
        rb, ra = b.get(key), a.get(key)
        row: dict[str, Any] = {"provider": key[0], "name": key[1]}
        for f in fields:
            sb = _per_sec(rb, f)
            sa = _per_sec(ra, f)
            row[f"{f[:-5]}MsPerSecBefore"] = sb
            row[f"{f[:-5]}MsPerSecAfter"] = sa
            row[f"{f[:-5]}DeltaMsPerSec"] = round((sa or 0) - (sb or 0), 3)
        row["callsPerSecBefore"] = _calls_per_sec(rb)
        row["callsPerSecAfter"] = _calls_per_sec(ra)
        row["status"] = "added" if rb is None else "removed" if ra is None else "changed"
        row["basis"] = "inclusive" if (ra or rb or {}).get("inclusive") else "self"
        row["_rank"] = abs(row[f"{fields[0][:-5]}DeltaMsPerSec"])
        (added if rb is None else removed if ra is None else rows).append(row)
    ranked = sorted(rows + added + removed, key=lambda r: r["_rank"], reverse=True)[:top]
    for r in ranked:
        r.pop("_rank", None)
    return {"matchedBy": "provider + section name", "top": ranked, "added": len(added), "removed": len(removed),
            "matched": len(rows),
            "note": ("Rates are per observed second. 'added'/'removed' mean the section exists in only one capture; "
                     "it may be a code change, or simply not hit/retained (maxKeys, truncation) in the other.")}


def _per_sec(r: dict[str, Any] | None, field: str) -> float | None:
    if r is None or not r.get("spanMs"):
        return None
    return round(r[field] / 1e6 / (r["spanMs"] / 1000), 3)


def _calls_per_sec(r: dict[str, Any] | None) -> float | None:
    if r is None or not r.get("spanMs"):
        return None
    return round(r["calls"] / (r["spanMs"] / 1000), 2)


def _level_deltas(b: dict[Key, dict[str, Any]], a: dict[Key, dict[str, Any]], top: int) -> list[dict[str, Any]]:
    rows = []
    for key in set(b) | set(a):
        rb, ra = b.get(key), a.get(key)
        rows.append({"provider": key[0], "level": key[1], "meanBefore": rb and rb["mean"], "meanAfter": ra and ra["mean"],
                     "maxBefore": rb and rb["max"], "maxAfter": ra and ra["max"],
                     "status": "added" if rb is None else "removed" if ra is None else "changed",
                     "_d": abs(((ra or {}).get("mean") or 0) - ((rb or {}).get("mean") or 0))})
    rows.sort(key=lambda r: r["_d"], reverse=True)
    return [{k: v for k, v in r.items() if k != "_d"} for r in rows[:top]]


def _count_deltas(b: dict[Key, dict[str, Any]], a: dict[Key, dict[str, Any]], top: int) -> list[dict[str, Any]]:
    rows = []
    for key in set(b) | set(a):
        rb, ra = b.get(key), a.get(key)
        pb = rb and rb["value"] / (rb["spanMs"] / 1000) if rb and rb["spanMs"] else None
        pa = ra and ra["value"] / (ra["spanMs"] / 1000) if ra and ra["spanMs"] else None
        rows.append({"provider": key[0], "count": key[1], "perSecBefore": pb and round(pb, 3),
                     "perSecAfter": pa and round(pa, 3),
                     "status": "added" if rb is None else "removed" if ra is None else "changed",
                     "_d": abs((pa or 0) - (pb or 0))})
    rows.sort(key=lambda r: r["_d"], reverse=True)
    return [{k: v for k, v in r.items() if k != "_d"} for r in rows[:top]]


# ------------------------------------------------------------------ text rendering

def render_diff(d: dict[str, Any]) -> str:
    from .render import table

    o: list[str] = []
    b, a = d["before"], d["after"]
    o.append(f"BEFORE {b['captureId']} (schema v{b['schemaVersion']}, {b['startedAt']})")
    o.append(f"AFTER  {a['captureId']} (schema v{a['schemaVersion']}, {a['startedAt']})")
    blockers = [w for w in d["warnings"] if w["severity"] == "blocker"]
    if blockers:
        o += ["", "!" * 78, "!!  THIS COMPARISON MAY NOT BE VALID: workload, coverage or completeness problems below can",
              "!!  produce differences that are NOT caused by the change under test.",
              "!" * 78]
        o += [f"  !!! {w['text']}" for w in blockers]
    others = [w for w in d["warnings"] if w["severity"] != "blocker"]
    if others:
        o += ["", "Warnings and context"]
        o += [f"  {'!' if w['severity'] == 'warning' else '-'} {w['text']}" for w in others]
    if not blockers:
        o += ["", "No blocking differences found in workload or coverage (this does not prove the comparison is fair)."]
    o += ["", "Timeline (tick-end observations)"]
    o += table(d["timeline"], [("metric", "metric"), ("before", "before"), ("after", "after"), ("delta", "delta"),
                               ("pct", "%")]) if d["timeline"] else ["  unavailable"]
    for title, key, cols in (
            ("Probe sections (matched by provider + section name; rates per observed second)", "sections",
             [("provider", "provider"), ("name", "section"), ("status", "status"), ("selfMsPerSecBefore", "self ms/s before"),
              ("selfMsPerSecAfter", "self ms/s after"), ("selfDeltaMsPerSec", "delta"),
              ("totalMsPerSecBefore", "incl ms/s before"), ("totalMsPerSecAfter", "incl ms/s after"),
              ("callsPerSecBefore", "calls/s before"), ("callsPerSecAfter", "calls/s after")]),
            ("Free-form providers and Ignite groups (rates per observed second)", "freeform",
             [("provider", "provider"), ("name", "item"), ("status", "status"), ("basis", "basis"),
              ("selfMsPerSecBefore", "ms/s before"), ("selfMsPerSecAfter", "ms/s after"),
              ("selfDeltaMsPerSec", "delta"), ("callsPerSecBefore", "calls/s before"),
              ("callsPerSecAfter", "calls/s after")])):
        sec = d[key]
        o += ["", f"{title}: {sec['matched']} matched, {sec['added']} only in after, {sec['removed']} only in before"]
        o += table(sec["top"], cols)
        o.append("  " + sec["note"])
    o += ["", "Levels (sampled gauges)"]
    o += table(d["levels"], [("provider", "provider"), ("level", "level"), ("status", "status"), ("meanBefore", "mean before"),
                             ("meanAfter", "mean after"), ("maxBefore", "max before"), ("maxAfter", "max after")])
    o += ["", "Counts (per observed second)"]
    o += table(d["counts"], [("provider", "provider"), ("count", "count"), ("status", "status"),
                             ("perSecBefore", "/s before"), ("perSecAfter", "/s after")])
    o += ["", "Scheduler"]
    o += table(d["scheduler"], [("metric", "metric"), ("before", "before"), ("after", "after")])
    o += ["", "Reading this: inclusive timings overlap and are not additive; a delta is evidence of a difference between "
              "two captures, and attributes the change only if workload and coverage match. Repeat the capture to estimate noise."]
    return "\n".join(o) + "\n"
