package com.teamsneakymouse.sneakydiagnostics.packets;

import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import com.teamsneakymouse.sneakydiagnostics.serverprobe.IgniteServerProbe;
import java.util.ArrayList;
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
    private static final Map<String, Object> COMPLETE = Map.of("complete", true);

    private static PacketCollector collector(Sink sink, MBeanServer server) {
        return new PacketCollector(LOG, sink, new IgniteServerProbe("tracker", server), new IgniteServerProbe("packets", server));
    }

    @Test void missingCompanionWritesAnIncompleteReportWithoutMeasuredSections() {
        var sink = new Sink();
        var collector = collector(sink, MBeanServerFactory.newMBeanServer());
        assertTrue(collector.status().contains("tracker probe: unavailable") && collector.status().contains("packets probe: unavailable"));
        collector.start(new CaptureContext("shared", "now", System.nanoTime(), 1, 60), List.of());
        collector.stop("completed");
        assertEquals(1, sink.writes);
        for (String key : List.of("tracker", "outbound", "causes", "trackerCauses", "inbound")) assertFalse(sink.report.containsKey(key), "unmeasured, not zero: " + key);
        assertEquals(false, sink.report.get("complete"));
        assertTrue(((String) sink.report.get("igniteAvailability")).contains("unavailable"));
        assertTrue(((String) sink.report.get("packetsAvailability")).contains("unavailable"));
    }

    @Test void bothProbesRecordOneCaptureAndTheirSectionsDecideCompleteness() throws Exception {
        var server = MBeanServerFactory.newMBeanServer();
        var control = register(server);
        control.tracker = Map.of("complete", true, "calls", 3L);
        var sink = new Sink();
        var collector = collector(sink, server);
        collector.start(new CaptureContext("shared", "now", 12345, 1, 60), List.of());
        assertEquals(List.of("tracker:shared", "packets:shared"), control.started);
        assertEquals(12345L, control.startedNanos);
        assertEquals(60, control.seconds);
        assertThrows(IllegalStateException.class, () -> collector.start(new CaptureContext("other", "now", 0, 1, 60), List.of()));
        collector.stop("completed");
        collector.stop("plugin_disable");
        assertEquals(1, sink.writes);
        assertEquals(List.of("tracker:shared", "packets:shared"), control.stopped);
        assertEquals(Map.of("complete", true, "calls", 3L), sink.report.get("tracker"));
        for (String key : PacketCollector.PACKET_SECTIONS) assertEquals(COMPLETE, sink.report.get(key), key);
        assertEquals(true, sink.report.get("complete"));

        control.sections.put("inbound", Map.of("complete", false));
        collector.start(new CaptureContext("next", "now", System.nanoTime(), 1, 60), List.of());
        collector.stop("completed");
        assertEquals(false, sink.report.get("complete"), "an incomplete packet section makes the report incomplete");
        control.sections.put("inbound", COMPLETE);
        control.tracker = Map.of("complete", false);
        collector.start(new CaptureContext("third", "now", System.nanoTime(), 1, 60), List.of());
        collector.stop("completed");
        assertEquals(false, sink.report.get("complete"), "an incomplete tracker makes the report incomplete");
        control.tracker = COMPLETE;
        collector.start(new CaptureContext("fourth", "now", System.nanoTime(), 1, 60), List.of());
        collector.stop("manual_stop");
        assertEquals(false, sink.report.get("complete"), "a manual stop is partial");
    }

    @Test void aCompanionWithOnlyTheTrackerProbeStillWritesTheTrackerSection() throws Exception {
        var server = MBeanServerFactory.newMBeanServer();
        var control = register(server);
        control.status = "phases available, idle; tracker available, idle";
        var sink = new Sink();
        var collector = collector(sink, server);
        collector.start(new CaptureContext("shared", "now", System.nanoTime(), 1, 60), List.of());
        collector.stop("completed");
        assertEquals(List.of("tracker:shared"), control.started);
        assertEquals(COMPLETE, sink.report.get("tracker"));
        assertFalse(sink.report.containsKey("outbound"));
        assertTrue(((String) sink.report.get("packetsAvailability")).contains("without the packets probe"));
        assertEquals(false, sink.report.get("complete"), "packets unmeasured");
    }

    @Test void aFailedPacketsStartStopsTheTrackerAgain() throws Exception {
        var server = MBeanServerFactory.newMBeanServer();
        var control = register(server);
        control.failStart = "packets";
        var collector = collector(new Sink(), server);
        assertThrows(IllegalStateException.class, () -> collector.start(new CaptureContext("shared", "now", System.nanoTime(), 1, 60), List.of()));
        assertEquals(List.of("tracker:shared"), control.stopped, "the tracker capture must not be left running");
        assertFalse(collector.active());
    }

    @Test void aPacketsProbeErrorIsReported() throws Exception {
        var server = MBeanServerFactory.newMBeanServer();
        var control = register(server);
        control.packetsError = "stopped off the server thread; packet counts discarded";
        var sink = new Sink();
        var collector = collector(sink, server);
        collector.start(new CaptureContext("shared", "now", System.nanoTime(), 1, 60), List.of());
        collector.stop("completed");
        assertEquals(1, sink.report.get("errors"));
        assertTrue(sink.report.get("packetsError").toString().contains("off the server thread"));
        assertFalse(sink.report.containsKey("outbound"));
        assertEquals(false, sink.report.get("complete"));
    }

    @Test void expiredCaptureStopsOnTheFirstTick() throws Exception {
        var server = MBeanServerFactory.newMBeanServer();
        register(server);
        var sink = new Sink();
        var collector = collector(sink, server);
        collector.start(new CaptureContext("shared", "now", System.nanoTime() - 2_000_000_000L, 1, 1), List.of());
        collector.onTick(null);
        assertFalse(collector.active());
        assertEquals("completed", sink.report.get("reason"));
    }

    @Test void incompatibleProtocolIsUnavailable() throws Exception {
        var server = MBeanServerFactory.newMBeanServer();
        register(server).protocol = 2;
        var bridge = new IgniteServerProbe("packets", server);
        assertFalse(bridge.available());
        assertTrue(bridge.status().contains("incompatible"));
    }

    private static Control register(MBeanServer server) throws Exception {
        var control = new Control();
        server.registerMBean(control, new ObjectName(IgniteServerProbe.OBJECT_NAME));
        return control;
    }

    private static final class Control implements DynamicMBean {
        final List<String> started = new ArrayList<>(), stopped = new ArrayList<>();
        long startedNanos;
        int seconds, protocol = 1;
        String status = "phases available, idle; tracker available, idle; packets available, idle", failStart, packetsError;
        Map<String, Object> tracker = COMPLETE;
        final Map<String, Object> sections = new LinkedHashMap<>(Map.of("outbound", COMPLETE, "causes", COMPLETE, "trackerCauses", COMPLETE, "inbound", COMPLETE));
        @Override public Object getAttribute(String name) { return name.equals("ProtocolVersion") ? protocol : status; }
        @Override public void setAttribute(Attribute attribute) { throw new UnsupportedOperationException(); }
        @Override public AttributeList getAttributes(String[] names) { return new AttributeList(); }
        @Override public AttributeList setAttributes(AttributeList attributes) { return new AttributeList(); }
        @Override public Object invoke(String operation, Object[] args, String[] signature) {
            String probe = (String) args[0], id = (String) args[1];
            assertTrue(probe.equals("tracker") || probe.equals("packets"), probe);
            if (operation.equals("start")) {
                assertArrayEquals(new String[]{"java.lang.String", "java.lang.String", "long", "int"}, signature);
                if (probe.equals(failStart)) throw new IllegalStateException("boom");
                started.add(probe + ":" + id);
                startedNanos = (Long) args[2];
                seconds = (Integer) args[3];
                return null;
            }
            assertEquals("stop", operation);
            assertArrayEquals(new String[]{"java.lang.String", "java.lang.String"}, signature);
            stopped.add(probe + ":" + id);
            var result = new LinkedHashMap<String, Object>();
            result.put("captureId", id);
            if (probe.equals("tracker")) result.put("tracker", tracker);
            else if (packetsError != null) result.put("error", packetsError);
            else result.putAll(sections);
            return result;
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
