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

    /**
     * The shared probe: the service keeps one recorder per plugin and reports it like any provider (own report file, provider
     * selection, completeness) in the {@code sneakydiagnostics.probe/1} shape of docs/report-schema-v2.md. Replaces a private
     * copy of Probe plus a Provider. Fails for a plugin name that is already registered. When the service is closed the
     * handle goes dead (it keeps answering and measures nothing): obtain a new one, see docs/plugin-providers.md.
     * The default exists only so fakes written before this method keep compiling.
     */
    default Probe probe(String plugin, String pluginVersion, String instrumentationVersion) {
        throw new UnsupportedOperationException("This Diagnostics implementation has no shared probe");
    }

    interface Registration extends AutoCloseable {
        @Override void close();
    }

    /**
     * Server thread only. While no capture runs every method returns after one boolean check: nothing is timed, allocated
     * or recorded, so callers must also keep their own work off ({@code if (token != 0)} before building a name).
     * Names are fixed labels of at most 192 characters (never player names or ids), at most 256 per kind.
     */
    interface Probe extends AutoCloseable {
        /** Starts a timed section: pass the token to {@link #end}. 0 (nothing timed) unless a capture is running. */
        long start();

        /** Ends the section opened by {@link #start()}. Sections must nest strictly (LIFO); a 0 token does nothing. */
        void end(String section, long token);

        /** Something happened n times. */
        void count(String key, long n);

        default void count(String key) { count(key, 1); }

        /** Whether it is time to sample levels again (about once a second while a capture runs). */
        boolean sampleDue();

        /** How much of something there is now; call when {@link #sampleDue()}. */
        void level(String key, long value);

        /** Whether a capture is running and its time is not up. */
        boolean on();

        /** Unregisters. A running capture of this probe stops with reason {@code provider_unregistered}. Idempotent. */
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
