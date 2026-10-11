package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.util.Map;

/** Local classloader-neutral control protocol. No remotely exposed connector is opened. */
public interface SchedulerControlMBean {
    int getProtocolVersion();
    String getStatus();
    void start(String captureId, long startedNanos, int seconds);
    Map<String, Object> stop(String captureId);
}
