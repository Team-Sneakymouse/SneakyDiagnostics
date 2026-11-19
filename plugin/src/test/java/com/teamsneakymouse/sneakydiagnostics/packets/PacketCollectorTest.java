package com.teamsneakymouse.sneakydiagnostics.packets;

import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketCollectorTest {
    private static final Logger LOG = Logger.getLogger("test");

    @Test void missingCompanionWritesAnIncompleteReportWithoutATrackerSection() {
        var sink = new Sink();
        var collector = new PacketCollector(LOG, sink, new IgniteServerProbe(MBeanServerFactory.newMBeanServer()));
        assertTrue(collector.status().contains("unavailable"));
        collector.start(new CaptureContext("shared", "now", System.nanoTime(), 1, 60), List.of());
        collector.stop("completed");
        assertEquals(1, sink.writes);
        assertFalse(sink.report.containsKey("tracker"), "unmeasured, not zero");
        assertEquals(false, sink.report.get("complete"));
        assertTrue(((String) sink.report.get("igniteAvailability")).contains("unavailable"));
    }

    @Test void trackerMeasurementIsPassedThroughAndDecidesCompleteness() throws Exception {
        var server = MBeanServerFactory.newMBeanServer();
        var control = register(server, Map.of("complete", true, "calls", 3L));
        var sink = new Sink();
        var collector = new PacketCollector(LOG, sink, new IgniteServerProbe(server));
        collector.start(new CaptureContext("shared", "now", 12345, 1, 60), List.of());
        assertEquals("shared", control.id);
        assertEquals(12345L, control.startedNanos);
        assertEquals(60, control.seconds);
        assertThrows(IllegalStateException.class, () -> collector.start(new CaptureContext("other", "now", 0, 1, 60), List.of()));
        collector.stop("completed");
        collector.stop("plugin_disable");
        assertEquals(1, sink.writes);
        assertEquals(Map.of("complete", true, "calls", 3L), sink.report.get("tracker"));
        assertEquals(true, sink.report.get("complete"));

        control.tracker = Map.of("complete", false);
        collector.start(new CaptureContext("next", "now", System.nanoTime(), 1, 60), List.of());
        collector.stop("completed");
        assertEquals(false, sink.report.get("complete"), "an incomplete tracker makes the report incomplete");
        collector.start(new CaptureContext("third", "now", System.nanoTime(), 1, 60), List.of());
        collector.stop("manual_stop");
        assertEquals(false, sink.report.get("complete"), "a manual stop is partial");
    }

    @Test void expiredCaptureStopsOnTheFirstTick() throws Exception {
        var server = MBeanServerFactory.newMBeanServer();
        register(server, Map.of("complete", true));
        var sink = new Sink();
        var collector = new PacketCollector(LOG, sink, new IgniteServerProbe(server));
        collector.start(new CaptureContext("shared", "now", System.nanoTime() - 2_000_000_000L, 1, 1), List.of());
        collector.onTick(null);
        assertFalse(collector.active());
        assertEquals("completed", sink.report.get("reason"));
    }

    @Test void incompatibleProtocolIsUnavailable() throws Exception {
        var server = MBeanServerFactory.newMBeanServer();
        register(server, Map.of()).protocol = 2;
        var bridge = new IgniteServerProbe(server);
        assertFalse(bridge.available());
        assertTrue(bridge.status().contains("incompatible"));
    }

    private static Control register(MBeanServer server, Map<String, Object> tracker) throws Exception {
        var control = new Control();
        control.tracker = tracker;
        server.registerMBean(control, new ObjectName("com.teamsneakymouse.sneakydiagnostics:type=ServerProbe"));
        return control;
    }

    private static final class Control implements DynamicMBean {
        String id;
        long startedNanos;
        int seconds, protocol = 1;
        Map<String, Object> tracker;
        @Override public Object getAttribute(String name) { return name.equals("ProtocolVersion") ? protocol : "phases available, idle; tracker available, idle"; }
        @Override public void setAttribute(Attribute attribute) { throw new UnsupportedOperationException(); }
        @Override public AttributeList getAttributes(String[] names) { return new AttributeList(); }
        @Override public AttributeList setAttributes(AttributeList attributes) { return new AttributeList(); }
        @Override public Object invoke(String operation, Object[] args, String[] signature) {
            if (operation.equals("start")) {
                assertArrayEquals(new String[]{"java.lang.String", "java.lang.String", "long", "int"}, signature);
                assertEquals("tracker", args[0]);
                id = (String) args[1]; startedNanos = (Long) args[2]; seconds = (Integer) args[3];
                return null;
            }
            assertEquals("stop", operation);
            assertArrayEquals(new String[]{"java.lang.String", "java.lang.String"}, signature);
            assertEquals("tracker", args[0]);
            assertEquals(id, args[1]);
            return Map.of("captureId", id, "tracker", tracker);
        }
        @Override public MBeanInfo getMBeanInfo() { return new MBeanInfo(getClass().getName(), "control", null, null, null, null); }
    }

    private static class Sink implements ReportSink {
        Map<String, Object> report;
        int writes;
        @Override public boolean busy(String collector) { return false; }
        @Override public Map<String, Object> header(String collector, CaptureContext context, String reason, int segment) {
            return new LinkedHashMap<>(Map.of("captureId", context.id(), "reason", reason));
        }
        @Override public void write(String collector, CaptureContext context, int segment, Supplier<Map<String, Object>> report) {
            writes++;
            this.report = report.get();
        }
    }
}
