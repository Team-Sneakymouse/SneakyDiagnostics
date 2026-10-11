package com.teamsneakymouse.sneakydiagnostics.scheduler;

import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import java.util.Map;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.MBeanInfo;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IgniteSchedulerProbeTest {
    @Test void missingCompanionDoesNotRequireAnyCompanionClasses() {
        var bridge = new IgniteSchedulerProbe(MBeanServerFactory.newMBeanServer());
        assertFalse(bridge.available());
        assertTrue(bridge.status().contains("unavailable"));
    }
    @Test void bridgePassesSharedClockAndIdentityUsingOnlyJdkValues() throws Exception {
        var server = MBeanServerFactory.newMBeanServer();
        var control = new Control();
        server.registerMBean(control, new ObjectName("com.teamsneakymouse.sneakydiagnostics:type=SchedulerProbe"));
        var bridge = new IgniteSchedulerProbe(server);
        assertTrue(bridge.available());
        bridge.start(new CaptureContext("id", "now", 12345, 4, 60));
        assertEquals("id", control.id);
        assertEquals(12345L, control.startedNanos);
        assertEquals(60, control.seconds);
        assertEquals(Map.of("captureId", "id"), bridge.stop("id"));
        control.protocol = 2;
        assertFalse(bridge.available());
        assertTrue(bridge.status().contains("incompatible"));
    }
    private static final class Control implements DynamicMBean {
        String id;
        long startedNanos;
        int seconds, protocol = 1;
        @Override public Object getAttribute(String name) { return name.equals("ProtocolVersion") ? protocol : "available, idle"; }
        @Override public void setAttribute(Attribute attribute) { throw new UnsupportedOperationException(); }
        @Override public AttributeList getAttributes(String[] names) { return new AttributeList(); }
        @Override public AttributeList setAttributes(AttributeList attributes) { return new AttributeList(); }
        @Override public Object invoke(String operation, Object[] args, String[] signature) {
            if (operation.equals("start")) {
                assertArrayEquals(new String[]{"java.lang.String", "long", "int"}, signature);
                id = (String) args[0]; startedNanos = (Long) args[1]; seconds = (Integer) args[2];
                return null;
            }
            assertEquals("stop", operation);
            assertEquals(id, args[0]);
            return Map.of("captureId", id);
        }
        @Override public MBeanInfo getMBeanInfo() { return new MBeanInfo(getClass().getName(), "control", null, null, null, null); }
    }
}
