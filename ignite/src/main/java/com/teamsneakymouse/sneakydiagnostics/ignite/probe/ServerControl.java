package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.management.ObjectName;

/**
 * The companion's second MBean (protocol 1): server-internal probes, each started and stopped by name. Each probe owns
 * its own capture state, so one capture ID can record in several probes at once and each probe can run alone.
 */
public final class ServerControl implements ServerControlMBean {
    public static final String OBJECT_NAME = "com.teamsneakymouse.sneakydiagnostics:type=ServerProbe";
    public static final int PROTOCOL = 1;

    @FunctionalInterface interface Starter { void start(String captureId, long startedNanos, int seconds); }
    record Probe(Starter start, Function<String, Map<String, Object>> stop, Supplier<String> status) {}

    /** The probes by name. A new probe joins with one line. */
    static final Map<String, Probe> PROBES;
    static {
        var probes = new LinkedHashMap<String, Probe>();
        probes.put("phases", new Probe(PhaseProbe::start, PhaseProbe::stop, PhaseProbe::status));
        probes.put("tracker", new Probe(TrackerProbe::start, TrackerProbe::stop, TrackerProbe::status));
        probes.put("packets", new Probe(PacketProbe::start, PacketProbe::stop, PacketProbe::status));
        PROBES = Collections.unmodifiableMap(probes);
    }

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

    @Override public int getProtocolVersion() { return PROTOCOL; }
    @Override public String getStatus() {
        var status = new StringJoiner("; ");
        PROBES.forEach((name, probe) -> status.add(name + " " + probe.status().get()));
        return status.toString();
    }
    @Override public void start(String probe, String captureId, long startedNanos, int seconds) {
        probe(probe).start().start(captureId, startedNanos, seconds);
    }
    /**
     * The probe's own map: {@code phases} returns the phases-1.json fields at top level; {@code tracker} returns
     * {@code {captureId, tracker}}, its packets-1.json section under its name; {@code packets} returns
     * {@code {captureId, outbound, causes, trackerCauses, inbound}}, its packets-1.json sections.
     */
    @Override public Map<String, Object> stop(String probe, String captureId) { return probe(probe).stop().apply(captureId); }

    private static Probe probe(String name) {
        Probe probe = name == null ? null : PROBES.get(name);
        if (probe == null) throw new IllegalArgumentException("Unknown server probe '" + name + "'; known: " + String.join(", ", PROBES.keySet()));
        return probe;
    }
}
