package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.util.Map;

/**
 * Local classloader-neutral control protocol for server-internal probes (the entity-tracker send probe today; packets
 * and tick phases are planned). Separate from the scheduler bridge, so that protocol stays unchanged.
 */
public interface ServerControlMBean {
    int getProtocolVersion();
    String getStatus();
    void start(String captureId, long startedNanos, int seconds);
    Map<String, Object> stop(String captureId);
}
