package com.teamsneakymouse.sneakydiagnostics.phases;

import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import java.lang.management.ManagementFactory;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/** The companion's ServerProbe MBean (protocol 1), reached with JDK values only; the companion is never a plugin dependency. */
final class IgniteServerProbe {
    static final String OBJECT_NAME = "com.teamsneakymouse.sneakydiagnostics:type=ServerProbe";
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
            return "Ignite " + server.getAttribute(NAME, "Status");
        } catch (Exception e) { return "unavailable (" + e.getClass().getSimpleName() + ")"; }
    }
    boolean available() {
        try { return server.isRegistered(NAME) && Integer.valueOf(1).equals(server.getAttribute(NAME, "ProtocolVersion")); }
        catch (Exception e) { return false; }
    }
    /** Server thread. */
    void start(CaptureContext context) throws Exception {
        server.invoke(NAME, "start", new Object[]{context.id(), context.startedNanos(), context.seconds()}, new String[]{"java.lang.String", "long", "int"});
    }
    /** Server thread. One top-level key per companion probe ({@code phases}). */
    @SuppressWarnings("unchecked")
    Map<String, Object> stop(String id) throws Exception {
        return (Map<String, Object>) server.invoke(NAME, "stop", new Object[]{id}, new String[]{"java.lang.String"});
    }
}
