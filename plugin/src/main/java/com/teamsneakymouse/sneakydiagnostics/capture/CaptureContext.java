package com.teamsneakymouse.sneakydiagnostics.capture;

import java.time.Instant;
import java.util.UUID;
import org.bukkit.Bukkit;

/** One clock and identity shared by every collector in a coordinated capture. */
public record CaptureContext(String id, String startedAt, long startedNanos, int startTick, int seconds) {
    public static CaptureContext create(int seconds) {
        Instant startedAt = Instant.now();
        return new CaptureContext(createId(startedAt).toString(), startedAt.toString(),
                System.nanoTime(), Bukkit.getCurrentTick(), seconds);
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
