#!/usr/bin/env python3
"""Analyze the retained windows of a spark profile exported by SneakyDiagnostics.

    uv run python -I tools/analyze_spark.py <profile.sparkprofile> [--prefix PKG ...] [--match TEXT ...]
                                            [--windows N | --all] [--thread NAME] [--json OUT]

Shows ownership/coverage (from the sibling spark-1.json), the last retained windows, hot methods
by inclusive and self sampled time, and per-prefix attribution: ``--prefix com.example.myplugin.``
reports how much of the server thread's sampled time ran under that code, counted at the first
matching frame of each stack. ``--match`` prints the stack paths of frames whose name contains
the text. Standard library only: the protobuf wire format is decoded directly (sneakydiag/sparkwire.py).
"""
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))  # -I removes the script dir from sys.path

from sneakydiag.util import sanitize  # noqa: E402
from sneakydiag.render import table  # noqa: E402
from sneakydiag.spark import PATH_FRAMES, analyze_profile, load_spark_report  # noqa: E402
from sneakydiag.sparkwire import ProfileError, read_profile  # noqa: E402


def render(r: dict) -> str:
    o: list[str] = []
    m, own = r["metadata"], r["ownership"]
    o.append(f"spark profile: {m['platform']} {m['platformVersion'] or ''} mode={m['mode']} engine={m['engine']} "
             f"interval={m['intervalMicros']} us  nominal {m['start']} .. {m['end']}  {m['windows']} retained windows")
    o.append(f"  threads: {', '.join(m['threads'][:8])}{' ...' if len(m['threads']) > 8 else ''}")
    if own["available"]:
        o.append(f"  ownership: owned={own['owned']} coverage={own['coverage']} complete={own['complete']} "
                 f"background={own['background']}")
    if own.get("note"):
        o.append("  ! " + own["note"])
    if r["captureWindow"]:
        o.append(f"  diagnostics capture window: {r['captureWindow']['start']} .. {r['captureWindow']['end']}")
    for w in r["warnings"]:
        o.append("  ! " + w)
    for s in r["selections"]:
        o.append("")
        ids = s["windowIds"]
        title = f"windows {ids[0]}..{ids[-1]} (aggregate)" if len(ids) > 1 else f"window {ids[0]}"
        o.append(f"{title}: {s['start']} .. {s['end']}")
        o.append(f"  players={s['players']} entities={s['entities']} chunks={s['chunks']} ticks={s['ticks']} "
                 f"mspt median/max (last window)={s['msptMedianLast']}/{s['msptMaxLast']}  "
                 f"server-thread sampled {s['threadSampledMillis']:,.0f} ms")
        ov = s["overlapWithCapture"]
        if ov:
            o.append(f"  overlap with capture: {ov['seconds']} s of {ov['ofWindowSeconds']} s window - {ov['note']}")
        if s["walkAnomalies"]:
            o.append(f"  ! {s['walkAnomalies']} node(s) referenced more than once were skipped (corrupt profile?)")
        for p, a in s["prefixes"].items():
            o.append(f"  prefix {p}: first-frame {a['firstFrameMillis']:,.1f} ms = {a['pctOfThread']}% of sampled "
                     f"thread time; self {a['selfMillis']:,.1f} ms ({a['selfPctOfThread']}%); {a['matchedFrames']} frames")
            if a["topSelfMethods"]:
                o += table(a["topSelfMethods"], [("method", "top self methods under prefix"), ("selfMillis", "self ms")], "    ")
        if "anyPrefix" in s and len(s["prefixes"]) > 1:
            a = s["anyPrefix"]
            o.append(f"  any prefix (union, no double counting): {a['firstFrameMillis']:,.1f} ms = {a['pctOfThread']}%")
        o.append("  inclusive methods (overlap; not additive):")
        o += table(s["inclusiveMethods"], [("method", "method"), ("millis", "ms"), ("pctOfThread", "% thread")], "    ")
        o.append("  self methods (exclusive, additive):")
        o += table(s["selfMethods"], [("method", "method"), ("millis", "ms"), ("pctOfThread", "% thread")], "    ")
        if r["matches"]:
            o.append(f"  stacks through the OUTERMOST frame matching {r['matches']} (inclusive ms of that frame; "
                     f"each stack counted once):")
            for t in s["targetedPaths"]:
                o.append(f"    {t['millis']:,.1f} ms ({t['pctOfThread']}%)  call path (outermost first, last {PATH_FRAMES} frames):")
                o.extend(f"        {f}" for f in t["path"])
                if t["heaviestDescent"]:
                    o.append("      heaviest callee chain below the match:")
                    o.extend(f"        > {f}" for f in t["heaviestDescent"])
        o.extend("  note: " + n for n in s["notes"])
    return "\n".join(o) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("profile", type=Path)
    ap.add_argument("--prefix", action="append", default=[], metavar="CLASS_PREFIX",
                    help="class-name prefix to attribute (repeatable or comma separated), e.g. com.nisovin.magicspells.")
    ap.add_argument("--match", action="append", default=[], metavar="TEXT",
                    help="print stack paths through frames whose 'class.method' contains TEXT (repeatable)")
    ap.add_argument("--windows", type=int, default=1, help="analyze the last N retained windows separately (default 1)")
    ap.add_argument("--all", action="store_true", help="analyze all retained windows as one aggregate instead")
    ap.add_argument("--thread", default="Server thread", help="thread to analyze (default 'Server thread')")
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--spark-json", type=Path, help="spark-1.json (default: next to the profile)")
    ap.add_argument("--json", type=Path, metavar="OUT")
    args = ap.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    prefixes = [p for chunk in args.prefix for p in chunk.split(",") if p]
    try:
        prof = read_profile(args.profile, thread=args.thread)
    except (OSError, ProfileError) as exc:
        print(f"error: cannot read profile: {exc}", file=sys.stderr)
        return 2
    sj = args.spark_json or args.profile.with_name("spark-1.json")
    report = load_spark_report(sj) if sj.is_file() else None
    result = analyze_profile(prof, prefixes=prefixes, matches=args.match, windows=max(1, args.windows),
                             aggregate=args.all, top=args.top, report=report)
    sys.stdout.write(sanitize(render(result)))
    if args.json:
        args.json.write_text(json.dumps(result, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
