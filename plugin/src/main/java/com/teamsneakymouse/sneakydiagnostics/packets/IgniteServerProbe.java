package com.teamsneakymouse.sneakydiagnostics.packets;

import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import java.lang.management.ManagementFactory;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * The companion's server-internals bridge ({@code type=ServerProbe}, protocol 1), separate from the scheduler bridge.
 * JDK-only values cross the classloader seam; the companion is never a plugin dependency.
 */
final class IgniteServerProbe {
    static final int PROTOCOL = 1;
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
            return "Ignite " + server.getAttribute(NAME, "Status");
        } catch (Exception e) { return "unavailable (" + e.getClass().getSimpleName() + ")"; }
    }
    boolean available() {
        try { return server.isRegistered(NAME) && Integer.valueOf(PROTOCOL).equals(server.getAttribute(NAME, "ProtocolVersion")); }
        catch (Exception e) { return false; }
    }
    /** On the server thread: the companion times tracker sends on the thread that starts the capture. */
    void start(CaptureContext context) throws Exception {
        server.invoke(NAME, "start", new Object[]{context.id(), context.startedNanos(), context.seconds()}, new String[]{"java.lang.String", "long", "int"});
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> stop(String id) throws Exception {
        return (Map<String, Object>) server.invoke(NAME, "stop", new Object[]{id}, new String[]{"java.lang.String"});
    }
}
