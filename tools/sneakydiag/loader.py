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
