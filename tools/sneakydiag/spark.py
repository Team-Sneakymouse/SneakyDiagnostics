"""spark profile window analysis: ownership/coverage, prefix attribution, hot methods, stack paths."""
from __future__ import annotations

import collections
import datetime
import re
from pathlib import Path
from typing import Any

from .loader import CaptureReadError, parse_json, read_limited
from .sparkwire import Profile, ThreadTree

PATH_FRAMES = 16
_FRACTION = re.compile(r"(\.\d{6})\d+")  # fromisoformat accepts at most microseconds


def utc(ms: int) -> str | None:
    if not ms:
        return None
    return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).isoformat(timespec="milliseconds")


def load_spark_report(path: Path) -> dict[str, Any] | None:
    """spark-1.json next to the profile (or given explicitly); None when absent/unreadable."""
    try:
        doc = parse_json(read_limited(path, 16 * 1024 * 1024))
    except (OSError, CaptureReadError):
        return None
    return doc if isinstance(doc, dict) else None


def capture_window_ms(report: dict[str, Any] | None) -> tuple[int, int] | None:
    """Capture window in unix ms from spark-1.json (start timestamp + finishedAfterMillis)."""
    if not report or not isinstance(report.get("captureStartedAt"), str):
        return None
    text = _FRACTION.sub(r"\1", report["captureStartedAt"]).replace("Z", "+00:00")
    try:
        start = datetime.datetime.fromisoformat(text)
    except ValueError:
        return None
    if start.tzinfo is None:
        start = start.replace(tzinfo=datetime.timezone.utc)
    ms0 = int(start.timestamp() * 1000)
    after = report.get("finishedAfterMillis")
    return (ms0, ms0 + int(after)) if isinstance(after, int) else None


def ownership(report: dict[str, Any] | None) -> dict[str, Any]:
    if report is None:
        return {"available": False,
                "note": "spark-1.json not found: ownership and coverage are unknown; the profile may contain "
                        "history outside any capture window"}
    dedicated = report.get("owned") is True and report.get("coverage") == "dedicated_session"
    return {"available": True, "owned": report.get("owned"), "coverage": report.get("coverage"),
            "complete": report.get("complete"), "reason": report.get("reason"), "mode": report.get("mode"),
            "engine": report.get("engine"), "background": report.get("background"),
            "dedicatedSession": dedicated,
            "note": None if dedicated else
            "NOT a dedicated session: the profile can include samples before/after the diagnostics window. "
            "Use the per-window overlap below; do not attribute stacks to the whole capture."}


def analyze_profile(prof: Profile, *, prefixes: list[str], matches: list[str], windows: int, aggregate: bool,
                    top: int, report: dict[str, Any] | None) -> dict[str, Any]:
    md = prof.metadata
    cap_win = capture_window_ms(report)
    out: dict[str, Any] = {
        "metadata": {"start": utc(md.start_time), "end": utc(md.end_time), "intervalMicros": md.interval_micros,
                     "mode": md.mode, "engine": md.engine, "platform": md.platform,
                     "platformVersion": md.platform_version, "minecraft": md.minecraft_version,
                     "numberOfTicks": md.number_of_ticks, "windows": len(prof.time_windows),
                     "threads": prof.thread_names},
        "ownership": ownership(report),
        "captureWindow": ({"start": utc(cap_win[0]), "end": utc(cap_win[1])} if cap_win else None),
        "prefixes": prefixes, "matches": matches, "selections": [], "warnings": [],
    }
    if md.mode != "EXECUTION":
        out["warnings"].append(f"sampler mode is {md.mode}; times are not execution (CPU/wall) samples")
    tree = prof.thread
    if tree is None:
        out["warnings"].append("requested thread not found; available: " + ", ".join(prof.thread_names))
        return out
    if not prof.time_windows:
        out["warnings"].append("profile has no time windows")
        return out
    if aggregate:
        selections = [list(range(len(prof.time_windows)))]
    else:
        selections = [[i] for i in range(max(0, len(prof.time_windows) - windows), len(prof.time_windows))]
    for idxs in selections:
        out["selections"].append(_selection(prof, tree, idxs, prefixes, matches, top, cap_win))
    for p in prefixes:
        if all(sel["prefixes"][p]["matchedFrames"] == 0 for sel in out["selections"]):
            out["warnings"].append(f"prefix {p!r} matched no frames with samples in the selected windows "
                                   "(wrong package, or this code never ran on the thread)")
    return out


def _selection(prof: Profile, tree: ThreadTree, idxs: list[int], prefixes: list[str], matches: list[str],
               top: int, cap_win: tuple[int, int] | None) -> dict[str, Any]:
    ids = [prof.time_windows[i] for i in idxs]
    stats = [prof.window_stats.get(w) for w in ids]
    starts = [s.startTime for s in stats if s and s.startTime]
    ends = [s.endTime for s in stats if s and s.endTime]
    thread_total = sum(tree.total[i] if i < len(tree.total) else 0.0 for i in idxs)
    acc = _walk(tree, idxs, prefixes, matches)
    total = thread_total or acc["rootMillis"]
    last = stats[-1]
    sel: dict[str, Any] = {
        "windowIds": ids, "start": utc(min(starts)) if starts else None, "end": utc(max(ends)) if ends else None,
        "players": last.players if last else None, "entities": last.entities if last else None,
        "chunks": last.chunks if last else None,
        "ticks": sum(s.ticks for s in stats if s), "msptMedianLast": round(last.msptMedian, 2) if last else None,
        "msptMaxLast": round(last.msptMax, 2) if last else None,
        "threadSampledMillis": round(thread_total, 1), "walkAnomalies": acc["anomalies"],
    }
    if cap_win and starts and ends:
        lo, hi = max(min(starts), cap_win[0]), min(max(ends), cap_win[1])
        overlap = max(0, hi - lo) / 1000.0
        span = (max(ends) - min(starts)) / 1000.0
        sel["overlapWithCapture"] = {"seconds": round(overlap, 2), "ofWindowSeconds": round(span, 2),
                                     "note": ("OUTSIDE the diagnostics capture window; context only" if overlap == 0 else
                                         "barely overlaps the diagnostics capture; treat as context"
                                         if span and overlap / span < 0.5 else "mostly inside the capture")}
    else:
        sel["overlapWithCapture"] = None
    sel["prefixes"] = {}
    for p in prefixes:
        a = acc["prefix"][p]
        sel["prefixes"][p] = {
            "firstFrameMillis": round(a["first"], 1), "pctOfThread": _pct(a["first"], total),
            "selfMillis": round(a["self"], 1), "selfPctOfThread": _pct(a["self"], total),
            "matchedFrames": a["frames"],
            "topSelfMethods": [{"method": k, "selfMillis": round(v, 1)} for k, v in a["selfMethods"].most_common(top)]}
    if prefixes:
        sel["anyPrefix"] = {"firstFrameMillis": round(acc["anyFirst"], 1), "pctOfThread": _pct(acc["anyFirst"], total),
                            "note": "union: time under the first frame matching ANY prefix on each stack"}
    sel["inclusiveMethods"] = [{"method": k, "millis": round(v, 1), "pctOfThread": _pct(v, total)}
                               for k, v in acc["inclusive"].most_common(top)]
    sel["selfMethods"] = [{"method": k, "millis": round(v, 1), "pctOfThread": _pct(v, total)}
                          for k, v in acc["self"].most_common(top)]
    sel["targetedPaths"] = [{"millis": round(v, 1), "pctOfThread": _pct(v, total), "path": list(path),
                             "heaviestDescent": down}
                            for v, path, down in sorted(acc["targeted"], key=lambda x: x[0], reverse=True)[:top]]
    sel["notes"] = [
        "Times are sampled server-thread milliseconds in the window (spark's per-window node times).",
        "Inclusive rows overlap (a method contains its callees) and are not additive. A method or prefix is counted "
        "once per stack, at its first (outermost) frame, so recursion is not double counted.",
        "Self time is exclusive and additive across methods.",
        "Sampling shows where the server thread was, not why; shares are of sampled thread time, not of 50 ms tick budget.",
    ]
    return sel


def _pct(v: float, total: float) -> float | None:
    return round(100.0 * v / total, 2) if total else None


def _walk(tree: ThreadTree, idxs: list[int], prefixes: list[str], matches: list[str]) -> dict[str, Any]:
    """One depth-first pass over the stack tree, summed over ``idxs`` windows."""
    n = len(tree.classes)
    visited = bytearray(n)
    inclusive: collections.Counter[str] = collections.Counter()
    selfc: collections.Counter[str] = collections.Counter()
    pref = {p: {"first": 0.0, "self": 0.0, "frames": 0, "selfMethods": collections.Counter()} for p in prefixes}
    active_name: collections.Counter[str] = collections.Counter()
    active_pref: collections.Counter[str] = collections.Counter()
    active_any = 0
    any_first = 0.0
    active_hit = 0
    targeted: list[tuple[float, tuple[str, ...], list[str]]] = []
    anomalies = 0
    path: list[str] = []
    single = idxs[0] if len(idxs) == 1 else None

    def val(ref: int) -> float:
        t = tree.times[ref]
        if single is not None:
            return t[single] if single < len(t) else 0.0
        return sum(t[i] for i in idxs if i < len(t))

    root_total = 0.0
    stack: list[tuple[Any, ...]] = [(r, False) for r in reversed(tree.roots)]
    while stack:
        item = stack.pop()
        if item[1]:  # exit
            _, _, name, matched, hit = item
            path.pop()
            active_hit -= hit
            active_name[name] -= 1
            for p in matched:
                active_pref[p] -= 1
            if matched:
                active_any -= 1
            continue
        ref = item[0]
        v = val(ref)
        if v <= 0:
            continue
        if visited[ref]:
            anomalies += 1
            continue
        visited[ref] = 1
        cls, meth = tree.classes[ref], tree.methods[ref]
        name = f"{cls}.{meth}"
        kids = tree.refs[ref]
        own = v - sum(val(c) for c in kids)
        if not path:
            root_total += v
        if active_name[name] == 0:
            inclusive[name] += v
        selfc[name] += max(own, 0.0)
        matched = [p for p in prefixes if cls.startswith(p)]
        for p in matched:
            a = pref[p]
            a["frames"] += 1
            a["self"] += max(own, 0.0)
            a["selfMethods"][name] += max(own, 0.0)
            if active_pref[p] == 0:
                a["first"] += v
        if matched and active_any == 0:
            any_first += v
        path.append(name)
        hit = 1 if matches and any(m in name for m in matches) else 0
        if hit and not active_hit:  # outermost matching frame on this stack
            targeted.append((v, tuple(path[-PATH_FRAMES:]), _heaviest_descent(tree, ref, val)))
        active_hit += hit
        active_name[name] += 1
        for p in matched:
            active_pref[p] += 1
        if matched:
            active_any += 1
        stack.append((ref, True, name, matched, hit))
        stack.extend((c, False) for c in reversed(kids))
    return {"inclusive": inclusive, "self": selfc, "prefix": pref, "anyFirst": any_first, "targeted": targeted,
            "anomalies": anomalies, "rootMillis": root_total}


def _heaviest_descent(tree: ThreadTree, ref: int, val: Any, limit: int = 14) -> list[str]:
    """Names below ``ref`` following the child with the most sampled time at each step."""
    out: list[str] = []
    seen = {ref}
    while len(out) < limit:
        kids = [c for c in tree.refs[ref] if c not in seen and val(c) > 0]
        if not kids:
            break
        ref = max(kids, key=val)
        seen.add(ref)
        out.append(f"{tree.classes[ref]}.{tree.methods[ref]}")
    return out
