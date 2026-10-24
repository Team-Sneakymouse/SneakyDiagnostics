"""Small shared helpers."""
from __future__ import annotations

import re
from typing import Any

_FLAG_KEY = re.compile(r"(trunc|overflow|omitted|dropped|unfinished|unstarted|inflight|nestingerrors|incomplete)", re.I)


_CONTROL = re.compile("[\x00-\x08\x0b-\x1f\x7f-\x9f]")


def sanitize(text: str) -> str:
    """Replace control characters (terminal escapes) that untrusted capture strings may carry."""
    return _CONTROL.sub("?", text)


def num(v: Any, default: float = 0) -> float:
    """A JSON number or ``default`` (bool is not a number here)."""
    return v if isinstance(v, (int, float)) and not isinstance(v, bool) else default


def ms(nanos: float) -> float:
    return round(nanos / 1e6, 3)


def pct(part: float, whole: float) -> float | None:
    return round(100.0 * part / whole, 2) if whole else None


def top(rows: list[dict[str, Any]], key: str, n: int) -> list[dict[str, Any]]:
    return sorted(rows, key=lambda r: num(r.get(key)), reverse=True)[:n]


def scan_flags(obj: Any, path: str = "", depth: int = 2) -> list[str]:
    """Names of coverage-loss fields with a non-zero/false value, e.g. ``droppedScopes=3``.

    Looks at dict keys only (not list elements) down to ``depth`` levels and ignores prose.
    ``fooComplete: false`` and ``complete: false`` are reported as ``fooComplete=false``.
    """
    out: list[str] = []
    if not isinstance(obj, dict):
        return out
    for key, val in obj.items():
        here = f"{path}{key}"
        if key == "notes" or key == "limits":
            continue
        if isinstance(val, bool):
            if (key == "complete" or key.endswith("Complete")) and not val:
                out.append(f"{here}=false")
            elif _FLAG_KEY.search(key) and val:
                out.append(f"{here}=true")
        elif isinstance(val, (int, float)):
            if _FLAG_KEY.search(key) and val:
                out.append(f"{here}={val:g}")
        elif isinstance(val, dict) and depth > 0:
            out.extend(scan_flags(val, here + ".", depth - 1))
    return out
