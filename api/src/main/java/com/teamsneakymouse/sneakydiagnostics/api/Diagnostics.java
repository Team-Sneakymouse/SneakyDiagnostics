package com.teamsneakymouse.sneakydiagnostics.api;

import java.util.Map;

/**
 * Optional diagnostics service, obtained from Bukkit's ServicesManager. All methods,
 * including provider callbacks and registration close, must run on the server thread.
 * Compile against this artifact; do not bundle it in a provider plugin.
 */
public interface Diagnostics {
    /** Register once after the plugin is ready; close before reload/disable destroys its state. */
    Registration register(String plugin, String pluginVersion, String instrumentationVersion, Provider provider);

    interface Registration extends AutoCloseable {
        @Override void close();
    }

    interface Provider {
        /** Begin a bounded capture. No provider should write its own report or schedule its own stop. */
        void start(Capture capture);

        /** Stop and detach all measurements, even after a partially failed start. */
        Result stop(String reason);
    }

    /** The monotonic origin and deadline are shared with every other collector. */
    record Capture(String id, String startedAt, long startedNanos, int startTick, int seconds) {
        public Capture {
            if (id == null || id.isBlank() || startedAt == null) throw new IllegalArgumentException("Capture identity is required");
            if (seconds < 1 || seconds > 1800) throw new IllegalArgumentException("Duration must be 1..1800 seconds");
        }
        public long offsetMillis() { return (System.nanoTime() - startedNanos) / 1_000_000; }
    }

    /**
     * Measurements must be bounded, detached JSON values: string-keyed maps, lists,
     * strings, finite numbers, booleans and null. No Bukkit objects or provider records.
     * complete describes coverage, not whether the requested duration elapsed.
     */
    record Result(Map<String, Object> measurements, boolean complete) {
        public Result { measurements = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(measurements)); }
    }
}
