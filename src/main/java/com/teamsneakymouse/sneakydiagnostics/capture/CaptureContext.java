package com.teamsneakymouse.sneakydiagnostics.capture;

import java.time.Instant;
import java.util.UUID;
import org.bukkit.Bukkit;

/** One clock and identity shared by every collector in a coordinated capture. */
public record CaptureContext(String id, String startedAt, long startedNanos, int startTick, int seconds) {
    public static CaptureContext create(int seconds) {
        return new CaptureContext(UUID.randomUUID().toString(), Instant.now().toString(),
                System.nanoTime(), Bukkit.getCurrentTick(), seconds);
    }

    public long offsetMillis() { return (System.nanoTime() - startedNanos) / 1_000_000; }
}
