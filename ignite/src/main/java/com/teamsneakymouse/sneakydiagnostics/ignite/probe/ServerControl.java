package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.management.ObjectName;

/** The companion's second MBean (protocol 1): server-internal probes. {@code start} and {@code stop} run on the server thread. */
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
    @Override public String getStatus() { return "phases " + PhaseProbe.status(); }
    @Override public void start(String captureId, long startedNanos, int seconds) { PhaseProbe.start(captureId, startedNanos, seconds); }
    /** One top-level key per probe ({@code phases}), so further probes can add theirs beside it. */
    @Override public Map<String, Object> stop(String captureId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", 1);
        result.put("captureId", captureId);
        result.put("phases", PhaseProbe.stop(captureId));
        return result;
    }
}
