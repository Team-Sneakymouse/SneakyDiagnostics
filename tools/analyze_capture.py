#!/usr/bin/env python3
"""Summarize a SneakyDiagnostics capture folder (schema v1 or v2).

    uv run python -I tools/analyze_capture.py <capture-dir> [--json out.json] [--raw]

Prints a human-readable report; ``--json`` also writes the same analysis as JSON.
``--raw`` lifts the top-N row caps so rankings and tables list everything.
Read-only; capture files are parsed as data only.
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
from sneakydiag.analysis import Options, analyze  # noqa: E402
from sneakydiag.loader import load_capture  # noqa: E402
from sneakydiag.render import render  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("capture_dir", type=Path)
    ap.add_argument("--json", type=Path, metavar="OUT", help="also write the analysis as JSON")
    ap.add_argument("--raw", action="store_true", help="do not cap rankings/tables at --top rows")
    ap.add_argument("--top", type=int, default=15, help="rows per ranking (default 15)")
    ap.add_argument("--slow-ms", type=float, default=100.0, help="a timeline window is slow at this max tick ms (default 100)")
    ap.add_argument("--top-windows", type=int, default=10, help="slow windows to correlate (default 10)")
    ap.add_argument("--max-bytes", type=int, help="largest JSON document to parse (default 512 MiB)")
    ap.add_argument("--no-verify", action="store_true", help="skip manifest sha256 verification")
    args = ap.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    try:
        cap = load_capture(args.capture_dir, max_bytes=args.max_bytes, verify=not args.no_verify)
    except OSError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    opts = Options(top=10**9 if args.raw else args.top, slow_ms=args.slow_ms, top_windows=args.top_windows)
    result = analyze(cap, opts)
    sys.stdout.write(sanitize(render(result)))
    if args.json:
        args.json.write_text(json.dumps(result, indent=2, sort_keys=False), encoding="utf-8")
        print(f"\nJSON written to {args.json}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
