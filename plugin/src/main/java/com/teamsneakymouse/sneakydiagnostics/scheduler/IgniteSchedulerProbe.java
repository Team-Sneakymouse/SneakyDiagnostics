package com.teamsneakymouse.sneakydiagnostics.scheduler;

import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import java.lang.management.ManagementFactory;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/** Uses JDK-only values across the classloader seam; the companion is never a plugin dependency. */
final class IgniteSchedulerProbe {
    private static final ObjectName NAME;
    static {
        try { NAME = new ObjectName("com.teamsneakymouse.sneakydiagnostics:type=SchedulerProbe"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private final MBeanServer server;
    IgniteSchedulerProbe() { this(ManagementFactory.getPlatformMBeanServer()); }
    IgniteSchedulerProbe(MBeanServer server) { this.server = server; }
    String status() {
        if (!server.isRegistered(NAME)) return "unavailable (companion not installed)";
        try {
            if (!Integer.valueOf(1).equals(server.getAttribute(NAME, "ProtocolVersion"))) return "unavailable (incompatible bridge protocol)";
            return "Ignite " + server.getAttribute(NAME, "Status");
        } catch (Exception e) { return "unavailable (" + e.getClass().getSimpleName() + ")"; }
    }
    boolean available() {
        try { return server.isRegistered(NAME) && Integer.valueOf(1).equals(server.getAttribute(NAME, "ProtocolVersion")); }
        catch (Exception e) { return false; }
    }
    void start(CaptureContext context) throws Exception {
        server.invoke(NAME, "start", new Object[]{context.id(), context.startedNanos(), context.seconds()}, new String[]{"java.lang.String", "long", "int"});
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> stop(String id) throws Exception {
        return (Map<String, Object>) server.invoke(NAME, "stop", new Object[]{id}, new String[]{"java.lang.String"});
    }
}
