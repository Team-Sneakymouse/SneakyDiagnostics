package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.lang.management.ManagementFactory;
import java.util.Map;
import javax.management.ObjectName;

public final class ServerControl implements ServerControlMBean {
    public static final String OBJECT_NAME = "com.teamsneakymouse.sneakydiagnostics:type=ServerProbe";
    private static boolean registered;

    public static synchronized void initialize() {
        if (registered) return;
        try {
            ManagementFactory.getPlatformMBeanServer().registerMBean(new ServerControl(), new ObjectName(OBJECT_NAME));
            registered = true;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot register SneakyDiagnostics server probe", e);
        }
    }

    @Override public int getProtocolVersion() { return 1; }
    @Override public String getStatus() { return TrackerProbe.status(); }
    /** Call on the server thread: it becomes the thread whose tracker sends are timed. */
    @Override public void start(String captureId, long startedNanos, int seconds) { TrackerProbe.start(captureId, startedNanos, seconds); }
    @Override public Map<String, Object> stop(String captureId) { return TrackerProbe.stop(captureId); }
}
