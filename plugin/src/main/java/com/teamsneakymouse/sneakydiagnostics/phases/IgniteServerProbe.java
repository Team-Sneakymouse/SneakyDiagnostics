package com.teamsneakymouse.sneakydiagnostics.phases;

import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import java.lang.management.ManagementFactory;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * The companion's ServerProbe MBean (protocol 1), probe {@code phases}, reached with JDK values only; the companion is
 * never a plugin dependency. Other probes on the same MBean (the tracker) record independently.
 */
final class IgniteServerProbe {
    static final String OBJECT_NAME = "com.teamsneakymouse.sneakydiagnostics:type=ServerProbe";
    static final String PROBE = "phases";
    private static final ObjectName NAME;
    static {
        try { NAME = new ObjectName(OBJECT_NAME); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private final MBeanServer server;
    IgniteServerProbe() { this(ManagementFactory.getPlatformMBeanServer()); }
    IgniteServerProbe(MBeanServer server) { this.server = server; }
    String status() {
        if (!server.isRegistered(NAME)) return "unavailable (companion not installed, or a companion without the server probe)";
        try {
            if (!Integer.valueOf(1).equals(server.getAttribute(NAME, "ProtocolVersion"))) return "unavailable (incompatible server probe protocol)";
            String own = probeStatus();
            return own == null ? "unavailable (companion without the " + PROBE + " probe)" : "Ignite " + own;
        } catch (Exception e) { return "unavailable (" + e.getClass().getSimpleName() + ")"; }
    }
    boolean available() {
        try { return server.isRegistered(NAME) && Integer.valueOf(1).equals(server.getAttribute(NAME, "ProtocolVersion")) && probeStatus() != null; }
        catch (Exception e) { return false; }
    }
    /** This probe's part of the MBean status ({@code "phases available, idle; tracker ..."}), or null when it is not listed. */
    private String probeStatus() throws Exception {
        for (String part : String.valueOf(server.getAttribute(NAME, "Status")).split("; ")) {
            if (part.startsWith(PROBE + " ")) return part;
        }
        return null;
    }
    /** Server thread. */
    void start(CaptureContext context) throws Exception {
        server.invoke(NAME, "start", new Object[]{PROBE, context.id(), context.startedNanos(), context.seconds()},
                new String[]{"java.lang.String", "java.lang.String", "long", "int"});
    }
    /** Server thread. The phases-1.json fields at top level. */
    @SuppressWarnings("unchecked")
    Map<String, Object> stop(String id) throws Exception {
        return (Map<String, Object>) server.invoke(NAME, "stop", new Object[]{PROBE, id}, new String[]{"java.lang.String", "java.lang.String"});
    }
}
