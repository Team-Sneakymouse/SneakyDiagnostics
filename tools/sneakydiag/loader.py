"""Load a capture directory (schema v1 or v2) into one normalized in-memory model.

Capture files are untrusted data: they are only ever parsed as JSON, reads are size-limited
(including gzip decompression), and non-finite numbers are rejected. Problems never abort the
load; they become entries in ``Capture.warnings`` and a per-file ``status`` so analyses can
report the gap as uncertainty rather than as zero.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

# Largest JSON document (after decompression) that will be parsed. Override with the
# SNEAKYDIAG_MAX_BYTES environment variable or ``load_capture(max_bytes=...)``.
DEFAULT_MAX_BYTES = 512 * 1024 * 1024

_REPORT_RE = re.compile(r"^(?P<collector>[a-z]+)-(?P<segment>\d+)\.json(?P<gz>\.gz)?$")
_PROVIDER_RE = re.compile(r"^plugins-(?P<slug>.+)-(?P<segment>\d+)\.json(?P<gz>\.gz)?$")


class CaptureReadError(Exception):
    """A single file could not be read safely; the message is suitable for a warning."""


@dataclass
class FileEntry:
    name: str
    collector: str | None = None  # "timeline", "plugins", ... ("plugins-provider" for provider files)
    segment: int | None = None
    in_manifest: bool = False
    present: bool = False
    size: int | None = None  # bytes on disk
    manifest_bytes: int | None = None
    manifest_uncompressed_bytes: int | None = None
    manifest_sha256: str | None = None
    sha256: str | None = None  # of the file as stored
    complete: bool | None = None  # as claimed by the manifest
    reason: str | None = None
    # ok | missing | hash_mismatch | size_mismatch | unreadable | too_large | unlisted | unverified
    status: str = "unverified"
    detail: str | None = None
    loaded: bool = False


@dataclass
class Provider:
    plugin: str
    plugin_version: str | None = None
    instrumentation_version: str | None = None
    started: bool | None = None
    reason: str | None = None
    complete: bool | None = None
    error: str | None = None
    file: str | None = None  # v2 provider file name; None for v1 (embedded)
    measurements: dict[str, Any] | None = None
    measurements_status: str = "ok"  # ok | missing | unreadable | not_started | absent

    @property
    def schema(self) -> str | None:
        m = self.measurements
        return m.get("schema") if isinstance(m, dict) and isinstance(m.get("schema"), str) else None

    @property
    def is_probe(self) -> bool:
        return bool(self.schema and self.schema.startswith("sneakydiagnostics.probe/"))


@dataclass
class Capture:
    path: Path
    schema_version: int = 1
    capture_id: str | None = None
    manifest: dict[str, Any] | None = None
    files: list[FileEntry] = field(default_factory=list)
    # collector -> reports sorted by segment (entities may have several segments)
    reports: dict[str, list[dict[str, Any]]] = field(default_factory=dict)
    index: dict[str, Any] | None = None  # plugins-1.json
    providers: list[Provider] = field(default_factory=list)
    selected: Any = None  # "all", a list of names, or None (v1 / unknown)
    warnings: list[str] = field(default_factory=list)
    has_profile: bool = False

    def report(self, collector: str) -> dict[str, Any] | None:
        """The first (usually only) segment of a collector, or None."""
        segs = self.reports.get(collector)
        return segs[0] if segs else None

    @property
    def header(self) -> dict[str, Any]:
        """Identity/start fields: manifest first, else the first report found."""
        h: dict[str, Any] = {}
        first = next((s[0] for s in self.reports.values() if s), {})
        server = (self.manifest or {}).get("server") or {}
        h["captureId"] = (self.manifest or {}).get("captureId") or first.get("captureId")
        h["captureStartedAt"] = (self.manifest or {}).get("captureStartedAt") or first.get("captureStartedAt")
        h["requestedDurationSeconds"] = ((self.manifest or {}).get("requestedDurationSeconds")
                                         or first.get("requestedDurationSeconds"))
        h["serverVersion"] = server.get("version") or first.get("serverVersion")
        h["diagnosticsVersion"] = server.get("diagnosticsVersion") or first.get("pluginVersion")
        h["javaVersion"] = server.get("javaVersion")
        return h


# --------------------------------------------------------------------------- safe reading

def max_bytes_limit(explicit: int | None = None) -> int:
    if explicit is not None:
        return explicit
    try:
        return int(os.environ.get("SNEAKYDIAG_MAX_BYTES", DEFAULT_MAX_BYTES))
    except ValueError:
        return DEFAULT_MAX_BYTES


def read_limited(path: Path, limit: int) -> bytes:
    """Read a file, transparently gunzipping ``*.gz``, refusing more than ``limit`` bytes."""
    if path.suffix == ".gz":
        try:
            with gzip.open(path, "rb") as fh:
                data = fh.read(limit + 1)
        except (OSError, EOFError, gzip.BadGzipFile) as exc:
            raise CaptureReadError(f"gzip error: {exc}") from exc
    else:
        with path.open("rb") as fh:
            data = fh.read(limit + 1)
    if len(data) > limit:
        raise CaptureReadError(f"larger than the {limit} byte read limit (set SNEAKYDIAG_MAX_BYTES to raise)")
    return data


def _reject_constant(name: str) -> Any:
    raise ValueError(f"non-finite number {name}")


def parse_json(data: bytes) -> Any:
    try:
        return json.loads(data.decode("utf-8"), parse_constant=_reject_constant)
    except (ValueError, RecursionError, UnicodeDecodeError) as exc:
        raise CaptureReadError(f"invalid JSON: {exc}") from exc


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


# --------------------------------------------------------------------------- classification

def classify_name(name: str) -> tuple[str | None, int | None]:
    """Return (collector, segment) from a report file name, using the filename conventions.

    ``plugins-1.json`` is the collector index; ``plugins-<slug>-1.json`` is a provider file.
    """
    m = _REPORT_RE.match(name)
    if m:
        return m["collector"], int(m["segment"])
    m = _PROVIDER_RE.match(name)
    if m:
        return "plugins-provider", int(m["segment"])
    return None, None


# --------------------------------------------------------------------------- loading

def load_capture(path: str | os.PathLike[str], *, max_bytes: int | None = None, verify: bool = True) -> Capture:
    root = Path(path)
    if not root.is_dir():
        raise NotADirectoryError(f"not a capture directory: {root}")
    limit = max_bytes_limit(max_bytes)
    cap = Capture(path=root)
    entries: dict[str, FileEntry] = {}

    manifest = _load_manifest(root, cap, limit)
    if manifest is not None:
        cap.manifest = manifest
        for item in manifest.get("files") or []:
            if not isinstance(item, dict) or not isinstance(item.get("name"), str):
                cap.warnings.append("manifest: ignoring a files[] entry without a name")
                continue
            name = item["name"]
            if "/" in name or "\\" in name or name.startswith(".."):
                cap.warnings.append(f"manifest: ignoring file name that is not a plain file name: {name!r}")
                continue
            collector, segment = classify_name(name)
            entries[name] = FileEntry(
                name=name, collector=item.get("collector") or collector, segment=item.get("segment", segment),
                in_manifest=True, manifest_bytes=_int(item.get("bytes")),
                manifest_uncompressed_bytes=_int(item.get("uncompressedBytes")),
                manifest_sha256=item.get("sha256") if isinstance(item.get("sha256"), str) else None,
                complete=item.get("complete") if isinstance(item.get("complete"), bool) else None,
                reason=item.get("reason") if isinstance(item.get("reason"), str) else None)
        # The manifest's collector label for provider files is not specified; trust the name.
        for name, e in entries.items():
            if classify_name(name)[0] == "plugins-provider":
                e.collector = "plugins-provider"

    for child in sorted(root.iterdir()):
        if child.is_symlink():
            cap.warnings.append(f"{child.name}: symbolic link ignored")
            continue
        if not child.is_file() or child.name == "manifest.json":
            continue
        e = entries.get(child.name)
        if e is None:
            collector, segment = classify_name(child.name)
            if collector is None and child.name != "profile.sparkprofile":
                continue  # not a SneakyDiagnostics file
            e = entries[child.name] = FileEntry(name=child.name, collector=collector, segment=segment)
            if manifest is not None:
                e.status = "unlisted"
                cap.warnings.append(f"{child.name}: present but not listed in manifest.json "
                                    "(written after the last manifest update, or the manifest is stale)")
        e.present = True
        e.size = child.stat().st_size
    for e in entries.values():
        if not e.present:
            e.status = "missing"
            cap.warnings.append(f"{e.name}: listed in manifest.json but missing from the capture folder")
    cap.files = sorted(entries.values(), key=lambda e: (e.collector or "", e.segment or 0, e.name))
    cap.has_profile = any(e.name == "profile.sparkprofile" and e.present for e in cap.files)

    if verify and manifest is not None:
        for e in cap.files:
            if e.present and e.in_manifest:
                _verify_stored(root / e.name, e, cap)

    for e in cap.files:
        if e.present and e.name.endswith((".json", ".json.gz")):
            _load_report(root, e, cap, limit)

    _normalize_providers(root, cap, limit)
    _finish(cap)
    return cap


def _int(v: Any) -> int | None:
    return v if isinstance(v, int) and not isinstance(v, bool) else None


def _load_manifest(root: Path, cap: Capture, limit: int) -> dict[str, Any] | None:
    mp = root / "manifest.json"
    if not mp.is_file():
        gz = root / "manifest.json.gz"
        if gz.is_file():
            cap.warnings.append("manifest.json.gz found; the schema says the manifest is never compressed")
        return None
    try:
        data = parse_json(read_limited(mp, min(limit, 64 * 1024 * 1024)))
    except (CaptureReadError, OSError) as exc:
        cap.warnings.append(f"manifest.json: unreadable ({exc}); treating the capture as unverified")
        return None
    if not isinstance(data, dict):
        cap.warnings.append("manifest.json: top level is not an object; ignored")
        return None
    return data


def _verify_stored(path: Path, e: FileEntry, cap: Capture) -> None:
    """Check bytes and sha256 of the file as stored against the manifest."""
    e.status = "ok"
    if e.manifest_bytes is not None and e.size != e.manifest_bytes:
        e.status = "size_mismatch"
        e.detail = f"manifest says {e.manifest_bytes} bytes, found {e.size}"
        cap.warnings.append(f"{e.name}: size mismatch ({e.detail})")
    if e.manifest_sha256 is not None:
        try:
            e.sha256 = sha256_file(path)
        except OSError as exc:
            e.status, e.detail = "unreadable", str(exc)
            cap.warnings.append(f"{e.name}: cannot hash ({exc})")
            return
        if e.sha256.lower() != e.manifest_sha256.lower():
            e.status = "hash_mismatch"
            e.detail = "sha256 differs from manifest; the file was modified or is partial"
            cap.warnings.append(f"{e.name}: sha256 mismatch with manifest (file modified, truncated or replaced)")
    else:
        e.status = e.status if e.status != "ok" else "unverified"
        cap.warnings.append(f"{e.name}: manifest has no sha256; contents not verified")


def _load_report(root: Path, e: FileEntry, cap: Capture, limit: int) -> None:
    try:
        data = read_limited(root / e.name, limit)
    except CaptureReadError as exc:
        e.status = "too_large" if "limit" in str(exc) else "unreadable"
        e.detail = str(exc)
        cap.warnings.append(f"{e.name}: not loaded ({exc})")
        return
    except OSError as exc:
        e.status, e.detail = "unreadable", str(exc)
        cap.warnings.append(f"{e.name}: not loaded ({exc})")
        return
    if e.manifest_uncompressed_bytes is not None and len(data) != e.manifest_uncompressed_bytes:
        cap.warnings.append(f"{e.name}: uncompressed size {len(data)} differs from manifest "
                            f"uncompressedBytes {e.manifest_uncompressed_bytes}")
        if e.status == "ok":
            e.status = "size_mismatch"
            e.detail = "uncompressed size differs from manifest"
    try:
        doc = parse_json(data)
    except CaptureReadError as exc:
        e.status, e.detail = "unreadable", str(exc)
        cap.warnings.append(f"{e.name}: not loaded ({exc})")
        return
    if not isinstance(doc, dict):
        e.status, e.detail = "unreadable", "top level is not an object"
        cap.warnings.append(f"{e.name}: not loaded (top level is not an object)")
        return
    e.loaded = True
    if e.collector == "plugins-provider":
        cap.reports.setdefault("plugins-provider", []).append({"__file__": e.name, **doc})
        return
    declared = doc.get("collector")
    if isinstance(declared, str) and e.collector and declared != e.collector:
        cap.warnings.append(f"{e.name}: header collector {declared!r} does not match file name")
    cap.reports.setdefault(e.collector or str(declared), []).append(doc)


def _normalize_providers(root: Path, cap: Capture, limit: int) -> None:
    """Build ``cap.providers`` from a v2 index + provider files, or a v1 embedded list."""
    index = cap.report("plugins")
    cap.index = index
    by_file = {r["__file__"]: r for r in cap.reports.get("plugins-provider", [])}
    used: set[str] = set()
    if index is None:
        if by_file:
            cap.warnings.append("plugins-1.json (collector index) missing; building providers from provider files")
            for fname, doc in by_file.items():
                cap.providers.append(_provider_from_file(fname, doc))
        return
    cap.selected = index.get("selected")
    entries = index.get("providers")
    if not isinstance(entries, list):
        cap.warnings.append("plugins-1.json: no providers[] list")
        return
    for raw in entries:
        if not isinstance(raw, dict):
            continue
        p = Provider(
            plugin=str(raw.get("plugin", "?")), plugin_version=raw.get("pluginVersion"),
            instrumentation_version=raw.get("instrumentationVersion"), started=raw.get("started"),
            reason=raw.get("reason"), complete=raw.get("complete"), error=raw.get("error"),
            file=raw.get("file") if isinstance(raw.get("file"), str) else None)
        if isinstance(raw.get("measurements"), dict):  # v1: embedded
            p.measurements = raw["measurements"]
        elif p.file is not None:  # v2: separate file, index file name is authoritative
            # Only when the named file is absent: a present but unreadable one stays authoritative.
            if (p.file.endswith(".json") and p.file + ".gz" in by_file
                    and not any(f.name == p.file and f.present for f in cap.files)):
                # Writers before 2026-11 listed the uncompressed name of a provider file stored as .json.gz.
                cap.warnings.append(f"{p.plugin}: index lists {p.file} but only {p.file}.gz exists; reading "
                                    f"{p.file}.gz (older writers recorded the uncompressed name)")
                p.file += ".gz"
            used.add(p.file)
            doc = by_file.get(p.file)
            if doc is not None:
                m = doc.get("measurements")
                if isinstance(m, dict):
                    p.measurements = m
                else:
                    p.measurements_status = "absent"
                if doc.get("plugin") not in (None, p.plugin):
                    cap.warnings.append(f"{p.file}: header plugin {doc.get('plugin')!r} differs from index {p.plugin!r}")
            else:
                entry = next((f for f in cap.files if f.name == p.file), None)
                p.measurements_status = "missing" if entry is None or not entry.present else "unreadable"
                cap.warnings.append(f"{p.plugin}: provider file {p.file} is {p.measurements_status}; "
                                    "its measurements are unavailable (not zero)")
        else:
            p.measurements_status = "not_started" if p.started is False else "absent"
        cap.providers.append(p)
    for fname, doc in by_file.items():
        if fname not in used:
            cap.warnings.append(f"{fname}: provider file not referenced by plugins-1.json; ignored")


def _provider_from_file(fname: str, doc: dict[str, Any]) -> Provider:
    m = doc.get("measurements")
    return Provider(plugin=str(doc.get("plugin", fname)), plugin_version=doc.get("pluginVersion"),
                    instrumentation_version=doc.get("instrumentationVersion"), reason=doc.get("reason"),
                    complete=doc.get("complete"), error=doc.get("error"), file=fname, started=True,
                    measurements=m if isinstance(m, dict) else None)


def _finish(cap: Capture) -> None:
    for segs in cap.reports.values():
        segs.sort(key=lambda r: r.get("segment", 0) if isinstance(r.get("segment"), int) else 0)
    versions = {r.get("schemaVersion") for k, segs in cap.reports.items() for r in segs if k != "plugins-provider"}
    versions.discard(None)
    if cap.manifest is not None and isinstance(cap.manifest.get("schemaVersion"), int):
        cap.schema_version = cap.manifest["schemaVersion"]
    elif versions:
        cap.schema_version = max(v for v in versions if isinstance(v, int))
    else:
        cap.schema_version = 2 if cap.manifest is not None else 1
    stray = {v for v in versions if v != cap.schema_version}
    if stray:
        cap.warnings.append(f"reports declare schemaVersion {sorted(stray, key=str)} but the capture is "
                            f"treated as schemaVersion {cap.schema_version}")
    cap.capture_id = cap.header["captureId"]
    ids = {r.get("captureId") for k, segs in cap.reports.items() for r in segs if k != "plugins-provider"}
    ids.discard(None)
    if cap.manifest and cap.manifest.get("captureId"):
        ids.add(cap.manifest["captureId"])
    if len(ids) > 1:
        cap.warnings.append(f"files carry different captureIds: {sorted(map(str, ids))}")
    if not cap.reports and not cap.manifest:
        cap.warnings.append("no SneakyDiagnostics report files found in this folder")


# --------------------------------------------------------------------------- compatible additions (2026-11)
#
# Fields added to existing reports without a schema version change. Each accessor returns None when the writer
# predates the field (unknown, never zero) and drops malformed entries instead of failing. Memory stays proportional
# to the list lengths in the file, never to the values in it.

def _n(v: Any) -> float:
    return v if isinstance(v, (int, float)) and not isinstance(v, bool) else 0


def _numbers(v: Any) -> list[float] | None:
    return [_n(x) for x in v] if isinstance(v, list) else None


def _ignite(cap: Capture) -> dict[str, Any]:
    s = cap.report("scheduler") or {}
    return s.get("ignite") if isinstance(s.get("ignite"), dict) else {}


def listener_totals(cap: Capture) -> dict[str, Any] | None:
    """Ignite ``listenerTotals`` (scheduler report): every listener call per owner x listener x event x priority x async.

    Rows carry calls, inclusive ``totalNanos`` (all threads), server-thread ``selfNanos`` (nested listener calls
    subtracted), ``maxNanos``, other-thread calls/nanos and ``histogramLog2``. ``series.owners`` holds per-second
    server-thread total/self nanos per owner plugin. None when the companion predates listener totals.
    """
    lt = _ignite(cap).get("listenerTotals")
    if not isinstance(lt, dict):
        return None
    rows = []
    for r in lt.get("rows") or []:
        if not isinstance(r, dict):
            continue
        hist = _numbers(r.get("histogramLog2"))
        rows.append({"owner": str(r.get("owner")), "listenerClass": str(r.get("listenerClass")),
                     "eventClass": str(r.get("eventClass")), "priority": str(r.get("priority")),
                     "asyncEvent": r.get("asyncEvent") is True, "calls": _n(r.get("calls")),
                     "totalNanos": _n(r.get("totalNanos")), "selfNanos": _n(r.get("selfNanos")),
                     "maxNanos": _n(r.get("maxNanos")), "otherThreadCalls": _n(r.get("otherThreadCalls")),
                     "otherThreadNanos": _n(r.get("otherThreadNanos")),
                     "histogramLog2": hist if hist is not None and len(hist) <= 64 else None})
    raw_series = lt.get("series") if isinstance(lt.get("series"), dict) else {}
    owners = {}
    for name, s in (raw_series.get("owners") or {}).items() if isinstance(raw_series.get("owners"), dict) else ():
        if not isinstance(s, dict):
            continue
        total, self_ = _numbers(s.get("totalNanos")), _numbers(s.get("selfNanos"))
        if total is not None and self_ is not None and len(total) == len(self_):
            owners[str(name)] = {"totalNanos": total, "selfNanos": self_}
    overflow = lt.get("overflow") if isinstance(lt.get("overflow"), dict) else {}
    window = raw_series.get("windowMillis")
    return {"rows": rows, "keys": _n(lt.get("keys")), "maxKeys": lt.get("maxKeys"),
            "overflowCalls": _n(overflow.get("calls")), "overflowNanos": _n(overflow.get("totalNanos")),
            "nestingOverflow": _n(lt.get("nestingOverflow")), "complete": lt.get("complete"),
            "series": {"windowMillis": int(window) if isinstance(window, int) and window > 0 else 1000,
                       "owners": owners, "omittedOwners": _n(raw_series.get("omittedOwners"))}}


def group_histogram(group: dict[str, Any]) -> list[float] | None:
    """An Ignite execution group's ``histogramLog2`` (completed scopes by floor(log2 nanos)); None before 2026-11."""
    hist = _numbers(group.get("histogramLog2"))
    return hist if hist is not None and len(hist) <= 64 else None


PHASE_KINDS = ("entities", "chunks", "tracker", "block-entities", "block-events", "spawning.state", "spawning.spawn",
               "spawning.custom", "other")


def _ints_or_none(v: Any, n: int) -> list[int | None] | None:
    """A list of exactly ``n`` integers or nulls, else None (malformed)."""
    if not isinstance(v, list) or len(v) != n:
        return None
    out: list[int | None] = []
    for x in v:
        if x is None or (isinstance(x, int) and not isinstance(x, bool)):
            out.append(x)
        else:
            return None
    return out


def split_phase(name: str, worlds: list[str]) -> tuple[str | None, str]:
    """``level.<world>.<kind>`` -> (world, kind) using the report's world list (names may contain dots); else (None, name)."""
    if name.startswith("level."):
        for world in sorted(worlds, key=len, reverse=True):
            prefix = f"level.{world}."
            if name.startswith(prefix) and name[len(prefix):] in PHASE_KINDS:
                return world, name[len(prefix):]
    return None, name


def phases(cap: Capture) -> dict[str, Any] | None:
    """``phases-1.json`` (2026-11): exclusive per-tick phase time (microseconds) from the Ignite server probe.

    None when the capture has no phases report (collector not run: "not recorded"). Otherwise ``available`` is False with
    a ``reason`` when the companion was missing, or the normalized rows: ``ticks``/``start``/``measured`` (None where
    unknown), ``phases`` and ``outside`` (name -> list aligned with the ticks), ``other`` (None where unknown), ``totals``,
    ``spawning`` and the bound counters. Malformed phase lists are dropped and named in ``malformed``.
    """
    r = cap.report("phases")
    if r is None:
        return None
    header = {"reason": r.get("reason"), "complete": r.get("complete"),
              "finishedAfterMillis": _n(r.get("finishedAfterMillis"))}
    if r.get("available") is not True:
        why = r.get("unavailableReason") or r.get("igniteError") or r.get("igniteAvailability") or "no phase data"
        return {**header, "available": False, "reason": str(why)}
    t = r.get("ticks") if isinstance(r.get("ticks"), dict) else {}
    ticks = _ints_or_none(t.get("tick"), len(t.get("tick")) if isinstance(t.get("tick"), list) else -1)
    if ticks is None or any(x is None for x in ticks):
        return {**header, "available": False, "reason": "phases report has no valid tick rows"}
    n = len(ticks)
    start = _ints_or_none(t.get("startOffsetMicros"), n)
    measured = _ints_or_none(t.get("measuredMicros"), n)
    other = _ints_or_none(r.get("other"), n)
    worlds = [str(w) for w in r.get("worlds") or [] if isinstance(w, str)]
    malformed: list[str] = []

    def columns(raw: Any, prefix: str) -> dict[str, list[int]]:
        out: dict[str, list[int]] = {}
        for name, values in (raw or {}).items() if isinstance(raw, dict) else ():
            col = _ints_or_none(values, n)
            if col is None or any(x is None for x in col):
                malformed.append(prefix + str(name))
                continue
            out[str(name)] = col  # type: ignore[assignment]
        return out

    totals = {}
    for name, v in (r.get("totals") or {}).items() if isinstance(r.get("totals"), dict) else ():
        if isinstance(v, dict):
            totals[str(name)] = {"totalNanos": _n(v.get("totalNanos")), "calls": _n(v.get("calls")),
                                 "ticksWithTime": _n(v.get("ticksWithTime")), "maxTickMicros": _n(v.get("maxTickMicros"))}
    spawning = {}
    for world, v in (r.get("spawning") or {}).items() if isinstance(r.get("spawning"), dict) else ():
        if isinstance(v, dict):
            spawning[str(world)] = {k: _n(v.get(k)) for k in ("stateCalls", "spawnChunkCalls", "spawnChunkCallsThatSpawned",
                                                               "naturalSpawnAttempts", "customSpawnerCalls")}
    m = r.get("measured") if isinstance(r.get("measured"), dict) else {}
    return {**header, "available": True, "phasesComplete": r.get("phasesComplete"),
            "ticks": ticks, "start": start or [None] * n, "measured": measured or [None] * n,
            "other": other or [None] * n, "otherValid": other is not None,
            "phases": columns(r.get("phases"), ""), "outside": columns(r.get("outside"), "outside."),
            "totals": totals, "worlds": worlds, "spawning": spawning, "malformed": malformed,
            "capacity": _n(t.get("capacity")), "dropped": _n(t.get("dropped")),
            "unmeasuredTicks": _n(m.get("unmeasuredTicks")), "negativeOtherTicks": _n(m.get("negativeOtherTicks")),
            "omittedWorlds": [str(w) for w in r.get("omittedWorlds") or [] if isinstance(w, str)],
            "omittedSeries": [str(w) for w in r.get("omittedSeries") or [] if isinstance(w, str)],
            "depthOverflow": _n(r.get("depthOverflow")), "nestingErrors": _n(r.get("nestingErrors")),
            "spanningTicks": _n(r.get("spanningTicks")), "lastTickPartial": r.get("lastTickPartial") is True}


def spawns(cap: Capture) -> dict[str, Any] | None:
    """The timeline's ``spawns``: entity spawns by reason x entity type (x plugin for CUSTOM/DEFAULT spawns).

    Each row's ``windows`` maps window index -> spawns in that one-second window (sparse; cancelled spawns have no
    window). None when the timeline predates spawn counts.
    """
    t = cap.report("timeline") or {}
    sp = t.get("spawns")
    if not isinstance(sp, dict):
        return None
    rows = []
    for r in sp.get("rows") or []:
        if not isinstance(r, dict):
            continue
        ws, ns = r.get("windows"), r.get("n")
        windows: dict[int, float] = {}
        if isinstance(ws, list) and isinstance(ns, list) and len(ws) == len(ns):
            for w, n in zip(ws, ns):
                if isinstance(w, int) and not isinstance(w, bool) and w >= 0:
                    windows[w] = windows.get(w, 0) + _n(n)
        plugin = r.get("plugin")
        rows.append({"reason": str(r.get("reason")), "entityType": str(r.get("entityType")),
                     "plugin": plugin if isinstance(plugin, str) else None, "total": _n(r.get("total")),
                     "cancelled": _n(r.get("cancelled")), "windows": windows})
    window = sp.get("windowMillis")
    return {"rows": rows, "observed": _n(sp.get("observed")), "overflow": _n(sp.get("overflow")),
            "droppedOffThread": _n(sp.get("droppedOffThread")), "complete": sp.get("complete"),
            "windowMillis": int(window) if isinstance(window, int) and window > 0 else 1000,
            "attributedReasons": [str(x) for x in sp.get("attributedReasons") or [] if isinstance(x, str)]}


PACKET_CAUSES = ("other", "tracker.update", "tracker.metadata", "tracker.spawn", "tracker.despawn", "entity.event",
                 "broadcast", "particles", "chunks")
TRACKER_CAUSES = ("tracker.update", "tracker.metadata", "tracker.spawn", "tracker.despawn")


def _hist(v: Any) -> list[float] | None:
    h = _numbers(v)
    return h if h is not None and len(h) <= 64 else None


def _series_rows(raw: Any, key: str, fields: tuple[str, ...]) -> dict[str, dict[str, list[float]]]:
    """``[{key: name, field: [..], ...}]`` -> name -> field -> numbers (rows with mismatched lengths are dropped)."""
    out: dict[str, dict[str, list[float]]] = {}
    for row in raw if isinstance(raw, list) else ():
        if not isinstance(row, dict) or not isinstance(row.get(key), str):
            continue
        cols = {f: _numbers(row.get(f)) for f in fields}
        if all(c is not None for c in cols.values()) and len({len(c) for c in cols.values()}) == 1:  # type: ignore[arg-type]
            out[row[key]] = cols  # type: ignore[assignment]
    return out


def _window_millis(series: dict[str, Any]) -> int:
    w = series.get("windowMillis")
    return int(w) if isinstance(w, int) and not isinstance(w, bool) and w > 0 else 1000


def packets(cap: Capture) -> dict[str, Any] | None:
    """The packet sections of ``packets-1.json`` (Ignite ``packets`` probe, 2026-11), normalized.

    None when the capture has no packets report (collector not run). Otherwise a dict with the header fields and
    ``outbound``, ``causes``, ``trackerCauses`` and ``inbound``, each None when that section is absent (companion
    missing, older than the packets probe, or failed: unmeasured, not zero).
    """
    r = cap.report("packets")
    if r is None:
        return None
    out: dict[str, Any] = {"reason": r.get("reason"), "complete": r.get("complete"),
                           "availability": r.get("packetsAvailability"), "error": r.get("packetsError"),
                           "outbound": None, "causes": None, "trackerCauses": None, "inbound": None}
    ob = r.get("outbound")
    if isinstance(ob, dict):
        classes = []
        for c in ob.get("classes") or []:
            if isinstance(c, dict) and isinstance(c.get("packetClass"), str):
                classes.append({"packetClass": c["packetClass"], **{k: _n(c.get(k)) for k in (
                    "sends", "serverThreadSends", "otherThreadSends", "totalNanos", "maxNanos", "otherThreadNanos", "bundled")},
                    "histogramLog2": _hist(c.get("histogramLog2"))})
        s = ob.get("series") if isinstance(ob.get("series"), dict) else {}
        out["outbound"] = {**{k: _n(ob.get(k)) for k in (
            "observedMillis", "sends", "serverThreadSends", "otherThreadSends", "serverThreadNanos", "classNanos",
            "nestedSends", "otherThreadNanos", "bundles", "bundledPackets", "lateSends", "maxClasses")},
            "classOverflow": ob.get("classOverflow") is True, "complete": ob.get("complete"), "classes": classes,
            "series": {"windowMillis": _window_millis(s), "sends": _numbers(s.get("sends")) or [],
                       "nanos": _numbers(s.get("nanos")) or [],
                       "classes": _series_rows(s.get("classes"), "packetClass", ("sends", "nanos"))}}
    ca = r.get("causes")
    if isinstance(ca, dict):
        rows = []
        for c in ca.get("rows") or []:
            if isinstance(c, dict) and isinstance(c.get("cause"), str):
                b = c.get("broadcasts")
                rows.append({"cause": c["cause"], "packets": _n(c.get("packets")), "totalNanos": _n(c.get("totalNanos")),
                             "maxNanos": _n(c.get("maxNanos")),
                             "broadcasts": _n(b) if isinstance(b, (int, float)) and not isinstance(b, bool) else None})
        s = ca.get("series") if isinstance(ca.get("series"), dict) else {}
        out["causes"] = {"rows": rows, "depthOverflow": _n(ca.get("depthOverflow")), "nestingErrors": _n(ca.get("nestingErrors")),
                         "complete": ca.get("complete"),
                         "series": {"windowMillis": _window_millis(s),
                                    "causes": _series_rows(s.get("causes"), "cause", ("packets", "nanos"))}}
    tc = r.get("trackerCauses")
    if isinstance(tc, dict):
        groups = []
        for g in tc.get("groups") or []:
            if not isinstance(g, dict):
                continue
            causes = {}
            for name, cell in (g.get("causes") or {}).items() if isinstance(g.get("causes"), dict) else ():
                if isinstance(cell, dict) and name in TRACKER_CAUSES:
                    causes[name] = {k: _n(cell.get(k)) for k in ("packets", "broadcasts", "totalNanos")}
            groups.append({"entityType": str(g.get("entityType")), "owner": str(g.get("owner")),
                           **{k: _n(g.get(k)) for k in ("entities", "packets", "broadcasts", "totalNanos")}, "causes": causes})
        out["trackerCauses"] = {"groups": groups, "complete": tc.get("complete"), **{k: _n(tc.get(k)) for k in (
            "entities", "maxGroups", "overflowEntities", "labelErrors", "spawnLookups", "spawnsAttributed",
            "spawnLookupNanos", "passThroughFrames")}}
    ib = r.get("inbound")
    if isinstance(ib, dict):
        classes = []
        for c in ib.get("classes") or []:
            if isinstance(c, dict) and isinstance(c.get("packetClass"), str):
                classes.append({"packetClass": c["packetClass"], **{k: _n(c.get(k)) for k in (
                    "packets", "totalNanos", "maxNanos", "betweenTicksPackets", "betweenTicksNanos")},
                    "histogramLog2": _hist(c.get("histogramLog2"))})
        s = ib.get("series") if isinstance(ib.get("series"), dict) else {}
        out["inbound"] = {**{k: _n(ib.get(k)) for k in (
            "observedMillis", "packets", "totalNanos", "betweenTicksPackets", "betweenTicksNanos", "offThreadPackets",
            "latePackets")}, "classOverflow": ib.get("classOverflow") is True, "complete": ib.get("complete"),
            "classes": classes,
            "series": {"windowMillis": _window_millis(s), "packets": _numbers(s.get("packets")) or [],
                       "nanos": _numbers(s.get("nanos")) or [],
                       "classes": _series_rows(s.get("classes"), "packetClass", ("packets", "nanos"))}}
    return out
