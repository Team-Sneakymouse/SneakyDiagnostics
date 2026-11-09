package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import org.bukkit.Bukkit;

/**
 * Which thread a slow call ended on and, only on the server thread, the server tick it ran in
 * ({@code Bukkit.getCurrentTick()}, the counter the timeline and shared-probe reports use). Read only for slow calls.
 */
@FunctionalInterface
public interface ThreadTick {
    int OTHER_THREAD = Integer.MIN_VALUE;
    int UNKNOWN = Integer.MIN_VALUE + 1;

    /** The current tick on the server thread, {@link #OTHER_THREAD} elsewhere, {@link #UNKNOWN} without a server. */
    int read();

    ThreadTick BUKKIT = () -> {
        try {
            return Bukkit.isPrimaryThread() ? Bukkit.getCurrentTick() : OTHER_THREAD;
        } catch (RuntimeException | LinkageError e) {
            return UNKNOWN;     // no server (runtime proof, tests)
        }
    };

    /** Adds {@code thread} ("server", "other" or "unknown") and, on the server thread, {@code tick}. */
    static void describe(int tick, java.util.Map<String, Object> fields) {
        if (tick == OTHER_THREAD) fields.put("thread", "other");
        else if (tick == UNKNOWN) fields.put("thread", "unknown");
        else {
            fields.put("thread", "server");
            fields.put("tick", (long) tick);
        }
    }
}
