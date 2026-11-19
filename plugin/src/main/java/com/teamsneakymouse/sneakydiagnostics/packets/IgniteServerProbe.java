package com.teamsneakymouse.sneakydiagnostics.packets;

import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import java.lang.management.ManagementFactory;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * The companion's server-internals bridge ({@code type=ServerProbe}, protocol 1), probe {@code tracker}, separate from
 * the scheduler bridge. JDK-only values cross the classloader seam; the companion is never a plugin dependency. Other
 * probes on the same MBean (tick phases) record independently.
 */
final class IgniteServerProbe {
    static final int PROTOCOL = 1;
    static final String PROBE = "tracker";
    private static final ObjectName NAME;
    static {
        try { NAME = new ObjectName("com.teamsneakymouse.sneakydiagnostics:type=ServerProbe"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private final MBeanServer server;
    IgniteServerProbe() { this(ManagementFactory.getPlatformMBeanServer()); }
    IgniteServerProbe(MBeanServer server) { this.server = server; }

    String status() {
        if (!server.isRegistered(NAME)) return "unavailable (companion not installed, or older than the tracker probe)";
        try {
            if (!Integer.valueOf(PROTOCOL).equals(server.getAttribute(NAME, "ProtocolVersion"))) return "unavailable (incompatible bridge protocol)";
            String own = probeStatus();
            return own == null ? "unavailable (companion without the " + PROBE + " probe)" : "Ignite " + own;
        } catch (Exception e) { return "unavailable (" + e.getClass().getSimpleName() + ")"; }
    }
    boolean available() {
        try { return server.isRegistered(NAME) && Integer.valueOf(PROTOCOL).equals(server.getAttribute(NAME, "ProtocolVersion")) && probeStatus() != null; }
        catch (Exception e) { return false; }
    }
    /** This probe's part of the MBean status ({@code "phases ...; tracker available, idle"}), or null when it is not listed. */
    private String probeStatus() throws Exception {
        for (String part : String.valueOf(server.getAttribute(NAME, "Status")).split("; ")) {
            if (part.startsWith(PROBE + " ")) return part;
        }
        return null;
    }
    /** On the server thread: the companion times tracker sends on the thread that starts the capture. */
    void start(CaptureContext context) throws Exception {
        server.invoke(NAME, "start", new Object[]{PROBE, context.id(), context.startedNanos(), context.seconds()},
                new String[]{"java.lang.String", "java.lang.String", "long", "int"});
    }
    /** {@code {captureId, tracker}}: the probe's packets-1.json section under its name. */
    @SuppressWarnings("unchecked")
    Map<String, Object> stop(String id) throws Exception {
        return (Map<String, Object>) server.invoke(NAME, "stop", new Object[]{PROBE, id}, new String[]{"java.lang.String", "java.lang.String"});
    }
}
