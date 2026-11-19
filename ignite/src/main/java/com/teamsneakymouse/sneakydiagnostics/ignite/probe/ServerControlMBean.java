package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.util.Map;

/**
 * Local classloader-neutral control protocol (protocol 1) for server-internal probes, each addressed by name
 * ({@code phases}, {@code tracker}). Probes record independently, so several can capture at once. Separate from the
 * scheduler bridge, so that protocol stays unchanged. No remotely exposed connector is opened.
 */
public interface ServerControlMBean {
    int getProtocolVersion();
    /** Every probe's state, in registry order: {@code "phases available, idle; tracker recording <id>"}. */
    String getStatus();
    /** Server thread. Throws {@link IllegalArgumentException} for an unknown probe name. */
    void start(String probe, String captureId, long startedNanos, int seconds);
    /** Server thread. Returns that probe's result map, which always holds {@code captureId}. */
    Map<String, Object> stop(String probe, String captureId);
}
