"""Minimal, dependency-free reader for spark's ``.sparkprofile`` (protobuf ``spark.SamplerData``).

Why not the protobuf runtime: generated ``*_pb2`` modules are tied to the protoc/runtime version
that produced them (a module from protoc 7.x refuses to import on older runtimes), and the
profile is read-only evidence. This module decodes just the fields the analysis needs straight
from the wire format, so ``uv run python`` works with no packages. Unknown fields are skipped,
so newer spark versions that add fields stay readable.

Field numbers follow spark's schema
(https://github.com/lucko/spark/blob/master/spark-common/src/main/proto/spark/spark_sampler.proto
and spark.proto). If spark renumbers these, update the ``F_*`` constants below.

Safety: the profile is untrusted. Length prefixes are bounds-checked, only one thread is decoded
into memory, node/refs counts are capped, and the file size is limited.
"""
from __future__ import annotations

import os
import struct
import sys
from array import array
from dataclasses import dataclass, field
from pathlib import Path
from typing import BinaryIO

DEFAULT_MAX_PROFILE_BYTES = 1024 * 1024 * 1024
MAX_NODES = 20_000_000

# SamplerData
F_META, F_THREADS, F_TIME_WINDOWS, F_WINDOW_STATS = 1, 2, 6, 7
# SamplerMetadata
F_M_START, F_M_INTERVAL, F_M_END, F_M_TICKS, F_M_PLATFORM, F_M_MODE, F_M_ENGINE = 2, 3, 11, 12, 7, 15, 16
# PlatformMetadata
F_P_NAME, F_P_VERSION, F_P_MC, F_P_BRAND, F_P_SPARK = 2, 3, 4, 8, 9
# ThreadNode / StackTraceNode
F_T_NAME, F_T_CHILDREN, F_T_TIMES, F_T_REFS = 1, 3, 4, 5
F_N_CLASS, F_N_METHOD, F_N_TIMES, F_N_REFS = 3, 4, 8, 9
# WindowStatistics
F_W = dict(ticks=1, tps=4, msptMedian=5, msptMax=6, players=7, entities=8, tileEntities=9, chunks=10,
           startTime=11, endTime=12, duration=13)
_W_FLOAT = {"tps", "msptMedian", "msptMax"}

SAMPLER_MODES = {0: "EXECUTION", 1: "ALLOCATION", 2: "LOCK"}
SAMPLER_ENGINES = {0: "JAVA", 1: "ASYNC"}


class ProfileError(Exception):
    pass


def _varint(buf: bytes | memoryview, pos: int, end: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        if pos >= end:
            raise ProfileError("truncated varint")
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7
        if shift > 70:
            raise ProfileError("varint too long")


def _signed(v: int) -> int:
    v &= (1 << 64) - 1
    return v - (1 << 64) if v >= 1 << 63 else v


def fields(buf: bytes | memoryview, pos: int = 0, end: int | None = None):
    """Yield (field_number, wire_type, value) where value is an int, or (start, stop) for wire type 2.

    Wire type 1/5 values are returned as raw little-endian bytes.
    """
    end = len(buf) if end is None else end
    while pos < end:
        key, pos = _varint(buf, pos, end)
        num, wt = key >> 3, key & 7
        if wt == 0:
            val, pos = _varint(buf, pos, end)
            yield num, wt, val
        elif wt == 1:
            if pos + 8 > end:
                raise ProfileError("truncated fixed64")
            yield num, wt, bytes(buf[pos:pos + 8])
            pos += 8
        elif wt == 5:
            if pos + 4 > end:
                raise ProfileError("truncated fixed32")
            yield num, wt, bytes(buf[pos:pos + 4])
            pos += 4
        elif wt == 2:
            ln, pos = _varint(buf, pos, end)
            if pos + ln > end:
                raise ProfileError("length prefix beyond end of message")
            yield num, wt, (pos, pos + ln)
            pos += ln
        else:
            raise ProfileError(f"unsupported wire type {wt}")


def _doubles(buf: bytes | memoryview, wt: int, val) -> array:
    out = array("d")
    if wt == 2:
        s, e = val
        if (e - s) % 8:
            raise ProfileError("packed double length not a multiple of 8")
        out.frombytes(bytes(buf[s:e]))
        if sys.byteorder == "big":
            out.byteswap()
    elif wt == 1:
        out.append(struct.unpack("<d", val)[0])
    return out


def _i32(v: int) -> int:
    v &= 0xFFFFFFFF
    return v - (1 << 32) if v >= 1 << 31 else v


def _int_list(buf: bytes | memoryview, wt: int, val) -> list[int]:
    if wt == 0:
        return [_i32(val)]
    if wt != 2:
        return []
    s, e = val
    out: list[int] = []
    pos = s
    while pos < e:
        v, pos = _varint(buf, pos, e)
        out.append(_i32(v))
    return out


def _text(buf: bytes | memoryview, span: tuple[int, int]) -> str:
    return bytes(buf[span[0]:span[1]]).decode("utf-8", "replace")


@dataclass
class Metadata:
    start_time: int = 0  # unix ms
    end_time: int = 0
    interval_micros: int = 0
    number_of_ticks: int = 0
    mode: str = "EXECUTION"  # proto3 default (0) is not written to the wire
    engine: str = "JAVA"
    platform: str | None = None
    platform_version: str | None = None
    minecraft_version: str | None = None
    spark_version: str | None = None


@dataclass
class WindowStats:
    ticks: int = 0
    tps: float = 0.0
    msptMedian: float = 0.0
    msptMax: float = 0.0
    players: int = 0
    entities: int = 0
    tileEntities: int = 0
    chunks: int = 0
    startTime: int = 0  # unix ms
    endTime: int = 0
    duration: int = 0


@dataclass
class ThreadTree:
    """One thread's stack-trace nodes. ``children[i]`` is a node; ``roots`` index into it.

    ``times[i]`` is per-window inclusive sampled milliseconds for node i, indexed like
    ``Profile.time_windows``.
    """
    name: str
    classes: list[str] = field(default_factory=list)
    methods: list[str] = field(default_factory=list)
    times: list[array] = field(default_factory=list)
    refs: list[list[int]] = field(default_factory=list)
    roots: list[int] = field(default_factory=list)
    total: array = field(default_factory=lambda: array("d"))  # thread-level times per window

    def value(self, node: int, window: int) -> float:
        t = self.times[node]
        return t[window] if window < len(t) else 0.0


@dataclass
class Profile:
    metadata: Metadata = field(default_factory=Metadata)
    time_windows: list[int] = field(default_factory=list)
    window_stats: dict[int, WindowStats] = field(default_factory=dict)
    thread_names: list[str] = field(default_factory=list)
    thread: ThreadTree | None = None


def _parse_metadata(buf, span) -> Metadata:
    m = Metadata()
    for num, wt, val in fields(buf, *span):
        if num == F_M_START and wt == 0:
            m.start_time = _signed(val)
        elif num == F_M_END and wt == 0:
            m.end_time = _signed(val)
        elif num == F_M_INTERVAL and wt == 0:
            m.interval_micros = val
        elif num == F_M_TICKS and wt == 0:
            m.number_of_ticks = val
        elif num == F_M_MODE and wt == 0:
            m.mode = SAMPLER_MODES.get(val, str(val))
        elif num == F_M_ENGINE and wt == 0:
            m.engine = SAMPLER_ENGINES.get(val, str(val))
        elif num == F_M_PLATFORM and wt == 2:
            for n2, w2, v2 in fields(buf, *val):
                if w2 != 2:
                    continue
                text = _text(buf, v2)
                if n2 == F_P_NAME:
                    m.platform = text
                elif n2 == F_P_VERSION:
                    m.platform_version = text
                elif n2 == F_P_MC:
                    m.minecraft_version = text
                elif n2 == F_P_SPARK:
                    m.spark_version = text
    return m


def _parse_window_entry(buf, span) -> tuple[int, WindowStats]:
    """A ``map<int32, WindowStatistics>`` entry: key = 1, value = 2."""
    key, stats = 0, WindowStats()
    by_num = {v: k for k, v in F_W.items()}
    for num, wt, val in fields(buf, *span):
        if num == 1 and wt == 0:
            key = _i32(val)
        elif num == 2 and wt == 2:
            for n2, w2, v2 in fields(buf, *val):
                name = by_num.get(n2)
                if name is None:
                    continue
                if name in _W_FLOAT and w2 == 1:
                    setattr(stats, name, struct.unpack("<d", v2)[0])
                elif w2 == 0:
                    setattr(stats, name, _signed(v2) if name in ("startTime", "endTime") else v2)
    return key, stats


def _parse_thread(buf, span, name: str) -> ThreadTree:
    tree = ThreadTree(name=name)
    classes, methods, times, refs = tree.classes, tree.methods, tree.times, tree.refs
    intern: dict[str, str] = {}
    for num, wt, val in fields(buf, *span):
        if num == F_T_CHILDREN and wt == 2:
            if len(classes) >= MAX_NODES:
                raise ProfileError(f"more than {MAX_NODES} stack nodes; refusing")
            cls = method = ""
            ts, rs = array("d"), []
            for n2, w2, v2 in fields(buf, *val):
                if n2 == F_N_CLASS and w2 == 2:
                    cls = _text(buf, v2)
                elif n2 == F_N_METHOD and w2 == 2:
                    method = _text(buf, v2)
                elif n2 == F_N_TIMES:
                    ts.extend(_doubles(buf, w2, v2))
                elif n2 == F_N_REFS:
                    rs.extend(_int_list(buf, w2, v2))
            classes.append(intern.setdefault(cls, cls))
            methods.append(intern.setdefault(method, method))
            times.append(ts)
            refs.append(rs)
        elif num == F_T_TIMES:
            tree.total.extend(_doubles(buf, wt, val))
        elif num == F_T_REFS:
            tree.roots.extend(_int_list(buf, wt, val))
    n = len(classes)
    for rs in refs:
        for r in rs:
            if r >= n:
                raise ProfileError("child reference outside the node list")
    for r in tree.roots:
        if r >= n:
            raise ProfileError("root reference outside the node list")
    return tree


def read_profile(path: str | os.PathLike[str], thread: str | None = "Server thread",
                 max_bytes: int | None = None) -> Profile:
    """Decode a profile. ``thread=None`` skips all stack data (metadata and windows only)."""
    p = Path(path)
    limit = max_bytes if max_bytes is not None else int(
        os.environ.get("SNEAKYDIAG_MAX_PROFILE_BYTES", DEFAULT_MAX_PROFILE_BYTES))
    size = p.stat().st_size
    if size > limit:
        raise ProfileError(f"profile is {size} bytes, over the {limit} byte limit "
                           "(set SNEAKYDIAG_MAX_PROFILE_BYTES to raise)")
    prof = Profile()
    with p.open("rb") as fh:
        _read_stream(fh, size, prof, thread)
    return prof


def _thread_name(head: bytes) -> str:
    """Thread name is field 1 and written first; read it without decoding the body."""
    try:
        for num, wt, val in fields(head):
            if num == F_T_NAME and wt == 2:
                return _text(head, val)
            break
    except ProfileError:
        pass
    return ""


def _read_stream(fh: BinaryIO, size: int, prof: Profile, thread: str | None) -> None:
    """Walk top-level fields, seeking over thread bodies that are not needed."""
    pos = 0
    while pos < size:
        fh.seek(pos)
        head = fh.read(20)
        key, i = _varint(head, 0, len(head))
        num, wt = key >> 3, key & 7
        if wt == 0:  # unpacked repeated int32 (time_windows) or an unknown scalar
            val, i = _varint(head, i, len(head))
            if num == F_TIME_WINDOWS:
                prof.time_windows.append(_i32(val))
            pos += i
            continue
        if wt != 2:
            raise ProfileError(f"unexpected top-level wire type {wt} for field {num}")
        ln, i = _varint(head, i, len(head))
        start = pos + i
        if start + ln > size:
            raise ProfileError("top-level length prefix beyond end of file (truncated profile?)")
        fh.seek(start)
        if num == F_THREADS:
            name = _thread_name(fh.read(min(ln, 4096)))
            prof.thread_names.append(name)
            if thread is not None and name == thread and prof.thread is None:
                fh.seek(start)
                body = fh.read(ln)
                prof.thread = _parse_thread(body, (0, len(body)), name)
        elif num in (F_META, F_WINDOW_STATS, F_TIME_WINDOWS):
            body = fh.read(ln)
            if num == F_META:
                prof.metadata = _parse_metadata(body, (0, len(body)))
            elif num == F_WINDOW_STATS:
                k, st = _parse_window_entry(body, (0, len(body)))
                prof.window_stats[k] = st
            else:
                prof.time_windows.extend(_int_list(body, 2, (0, len(body))))
        pos = start + ln
