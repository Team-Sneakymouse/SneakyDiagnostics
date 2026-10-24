#!/usr/bin/env python3
"""Compare two SneakyDiagnostics captures (before/after one controlled change).

    uv run python -I tools/diff_captures.py <before-dir> <after-dir> [--json out.json] [--tolerance 0.10]

Matches probe sections by provider + section name and compares per-second rates, tick statistics,
levels and counts. Loudly warns when workload (players/entities/chunks) differs, when either
capture or provider is incomplete, or when versions/provider sets differ.
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
from sneakydiag.diff import compare, render_diff  # noqa: E402
from sneakydiag.loader import load_capture  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("before", type=Path)
    ap.add_argument("after", type=Path)
    ap.add_argument("--json", type=Path, metavar="OUT")
    ap.add_argument("--tolerance", type=float, default=0.10,
                    help="relative workload difference that triggers the loud warning (default 0.10)")
    ap.add_argument("--top", type=int, default=20, help="rows per table (default 20)")
    ap.add_argument("--max-bytes", type=int, help="largest JSON document to parse (default 512 MiB)")
    args = ap.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    try:
        before = load_capture(args.before, max_bytes=args.max_bytes)
        after = load_capture(args.after, max_bytes=args.max_bytes)
    except OSError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    result = compare(before, after, tolerance=args.tolerance, top=args.top)
    sys.stdout.write(sanitize(render_diff(result)))
    if args.json:
        args.json.write_text(json.dumps(result, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
