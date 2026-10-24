"""Tiny protobuf encoder to build synthetic .sparkprofile files (no protobuf runtime needed)."""
from __future__ import annotations

import struct
from pathlib import Path


def varint(n: int) -> bytes:
    n &= (1 << 64) - 1
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def ld(num: int, payload: bytes) -> bytes:  # length-delimited field
    return varint(num << 3 | 2) + varint(len(payload)) + payload


def vi(num: int, value: int) -> bytes:  # varint field
    return varint(num << 3) + varint(value)


def dbl(num: int, value: float) -> bytes:
    return varint(num << 3 | 1) + struct.pack("<d", value)


def packed_doubles(num: int, values: list[float]) -> bytes:
    return ld(num, b"".join(struct.pack("<d", v) for v in values))


def packed_ints(num: int, values: list[int]) -> bytes:
    return ld(num, b"".join(varint(v) for v in values))


def node(cls: str, method: str, times: list[float], refs: list[int]) -> bytes:
    return (ld(3, cls.encode()) + ld(4, method.encode()) + packed_doubles(8, times)
            + (packed_ints(9, refs) if refs else b""))


def build_profile(path: Path, *, windows: tuple[int, ...] = (10, 11), start: int = 1_767_268_800_000) -> Path:
    """Server thread tree (two windows), values are sampled ms:

    Thread.run 100/200 -> Server.tick 90/180 -> { acme.Plugin.run 50/100 -> acme.Plugin.recurse 30/60 ->
    FileChannel.force 20/40 ; Chunk.update 40/80 }
    """
    nodes = [
        node("java.lang.Thread", "run", [100, 200], [1]),
        node("net.minecraft.Server", "tick", [90, 180], [2, 3]),
        node("com.acme.Plugin", "run", [50, 100], [4]),
        node("net.minecraft.Chunk", "update", [40, 80], []),
        node("com.acme.Plugin", "recurse", [30, 60], [5]),
        node("sun.nio.ch.FileChannelImpl", "force", [20, 40], []),
    ]
    thread = ld(1, b"Server thread") + b"".join(ld(3, n) for n in nodes) + packed_doubles(4, [100, 200]) \
        + packed_ints(5, [0])
    other = ld(1, b"Worker-1") + ld(3, node("x.Y", "z", [1, 1], []))
    meta = vi(2, start) + vi(3, 4000) + vi(11, start + 120_000) + vi(12, 99) + vi(15, 0) + vi(16, 1) \
        + ld(7, ld(2, b"Paper") + ld(3, b"test"))

    def stats(players: int, entities: int, chunks: int, s: int) -> bytes:
        return vi(1, 1200) + dbl(5, 20.5) + vi(7, players) + vi(8, entities) + vi(10, chunks) + vi(11, s) + vi(12, s + 60_000)

    entries = b"".join(ld(7, vi(1, w) + ld(2, stats(30 + i, 1000 + i, 500, start + 60_000 * i)))
                       for i, w in enumerate(windows))
    data = ld(1, meta) + ld(2, thread) + ld(2, other) + ld(6, b"".join(varint(w) for w in windows)) + entries
    path.write_bytes(data)
    return path
