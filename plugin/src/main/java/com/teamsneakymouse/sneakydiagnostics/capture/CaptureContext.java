package com.teamsneakymouse.sneakydiagnostics.capture;

import com.teamsneakymouse.sneakydiagnostics.report.CaptureManifest.Workload;
import com.teamsneakymouse.sneakydiagnostics.report.CaptureManifest.WorldLoad;
import java.time.Instant;
import java.util.UUID;
import org.bukkit.Bukkit;

/** One clock and identity shared by every collector in a coordinated capture. */
public record CaptureContext(String id, String startedAt, long startedNanos, int startTick, int seconds,
                             Workload atStart, CaptureProgress progress) {
    public CaptureContext(String id, String startedAt, long startedNanos, int startTick, int seconds) {
        this(id, startedAt, startedNanos, startTick, seconds, null, new CaptureProgress());
    }

    public static CaptureContext create(int seconds) {
        Instant startedAt = Instant.now();
        return new CaptureContext(createId(startedAt).toString(), startedAt.toString(),
                System.nanoTime(), Bukkit.getCurrentTick(), seconds, sampleWorkload(), new CaptureProgress());
    }

    /** Server thread only. Counts for the manifest: no player names, UUIDs or locations. */
    public static Workload sampleWorkload() {
        return new Workload(Bukkit.getOnlinePlayers().size(), Bukkit.getWorlds().stream()
                .map(world -> new WorldLoad(world.getName(), world.getChunkCount(), world.getEntityCount())).toList());
    }

    /** RFC 9562 UUIDv7: 48-bit Unix milliseconds, version 7, and 74 random bits. */
    static UUID createId(Instant startedAt) {
        UUID random = UUID.randomUUID();
        long mostSignificant = (startedAt.toEpochMilli() << 16)
                | 0x7000L | (random.getMostSignificantBits() & 0x0fffL);
        // randomUUID already supplies the RFC variant and 62 random low bits.
        return new UUID(mostSignificant, random.getLeastSignificantBits());
    }

    public long offsetMillis() { return (System.nanoTime() - startedNanos) / 1_000_000; }
}
