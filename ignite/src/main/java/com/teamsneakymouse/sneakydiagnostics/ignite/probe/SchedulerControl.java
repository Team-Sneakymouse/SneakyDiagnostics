package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.lang.management.ManagementFactory;
import java.util.Map;
import javax.management.ObjectName;

public final class SchedulerControl implements SchedulerControlMBean {
    public static final String OBJECT_NAME = "com.teamsneakymouse.sneakydiagnostics:type=SchedulerProbe";
    private static boolean registered;

    public static synchronized void initialize() {
        if (registered) return;
        try {
            ManagementFactory.getPlatformMBeanServer().registerMBean(new SchedulerControl(), new ObjectName(OBJECT_NAME));
            registered = true;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot register SneakyDiagnostics scheduler probe", e);
        }
    }

    @Override public int getProtocolVersion() { return 1; }
    @Override public String getStatus() { return SchedulerProbe.status(); }
    @Override public void start(String captureId, long startedNanos, int seconds) { SchedulerProbe.start(captureId, startedNanos, seconds); }
    @Override public Map<String, Object> stop(String captureId) { return SchedulerProbe.stop(captureId); }
}
