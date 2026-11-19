package com.teamsneakymouse.sneakydiagnostics.phases;

import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import java.util.HashMap;
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

class PhasesCollectorTest {
    private static final Logger LOGGER = Logger.getLogger("test");

    @Test void withoutTheCompanionTheReportSaysUnavailable() {
        var sink = new Sink();
        var collector = new PhasesCollector(LOGGER, sink, new IgniteServerProbe(MBeanServerFactory.newMBeanServer()));
        collector.start(context(60), List.of());
        assertTrue(collector.active());
        assertTrue(collector.status().contains("unavailable"));
        collector.stop("completed");
        assertFalse(collector.active());
        assertEquals(false, sink.report.get("available"));
        assertEquals(false, sink.report.get("complete"));
        assertTrue(sink.report.get("unavailableReason").toString().contains("companion"));
        assertFalse(sink.report.containsKey("phases"));
    }

    @Test void phasesAreCopiedFromTheServerProbeWithJdkValuesOnly() throws Exception {
        var server = MBeanServerFactory.newMBeanServer();
        var control = register(server);
        var sink = new Sink();
        var collector = new PhasesCollector(LOGGER, sink, new IgniteServerProbe(server));
        collector.start(context(60), List.of());
        assertEquals("shared", control.id);
        assertEquals(60, control.seconds);
        collector.onTick(null);                                                        // not yet due
        assertTrue(collector.active());
        collector.stop("completed");
        assertEquals(true, sink.report.get("available"));
        assertEquals(true, sink.report.get("phasesComplete"));
        assertEquals(true, sink.report.get("complete"));
        // The companion exports phase columns as int[]; the report serializer writes them as JSON arrays.
        String json = new String(com.teamsneakymouse.sneakydiagnostics.report.StoredFile.serialize(sink.report)).replaceAll("\\s", "");
        assertTrue(json.contains("\"phases\":{\"scheduler\":[1000]}"), json);
        assertEquals("shared", sink.report.get("captureId"), "the header's capture ID is kept");
    }

    @Test void theCaptureStopsAtItsDeadlineOnTickEnd() throws Exception {
        var server = MBeanServerFactory.newMBeanServer();
        register(server);
        var sink = new Sink();
        var collector = new PhasesCollector(LOGGER, sink, new IgniteServerProbe(server));
        collector.start(new CaptureContext("shared", "now", System.nanoTime() - 2_000_000_000L, 4, 1), List.of());
        collector.onTick(null);
        assertFalse(collector.active());
        assertEquals("completed", sink.report.get("reason"));
    }

    @Test void anIncompleteOrFailedProbeMakesTheReportIncomplete() throws Exception {
        var server = MBeanServerFactory.newMBeanServer();
        var control = register(server);
        control.phases.put("complete", false);
        var sink = new Sink();
        var collector = new PhasesCollector(LOGGER, sink, new IgniteServerProbe(server));
        collector.start(context(60), List.of());
        collector.stop("completed");
        assertEquals(false, sink.report.get("complete"));
        control.fail = true;
        collector.start(context(60), List.of());
        collector.stop("manual_stop");
        assertEquals(false, sink.report.get("available"));
        assertEquals(1, sink.report.get("errors"));
        assertTrue(sink.report.get("igniteError").toString().contains("boom"));
    }

    @Test void bridgeRejectsAnotherProtocol() throws Exception {
        var server = MBeanServerFactory.newMBeanServer();
        var control = register(server);
        var bridge = new IgniteServerProbe(server);
        assertTrue(bridge.available());
        assertEquals("Ignite phases available, idle", bridge.status(), "only this probe's part of the status");
        control.status = "tracker available, idle";
        assertFalse(bridge.available(), "a companion without the phases probe");
        assertTrue(bridge.status().contains("without the phases probe"));
        control.status = "phases available, idle";
        control.protocol = 2;
        assertFalse(bridge.available());
        assertTrue(bridge.status().contains("incompatible"));
    }

    private static Control register(MBeanServer server) throws Exception {
        var control = new Control();
        server.registerMBean(control, new ObjectName(IgniteServerProbe.OBJECT_NAME));
        return control;
    }
    private static CaptureContext context(int seconds) {
        return new CaptureContext("shared", "now", System.nanoTime(), 4, seconds);
    }

    private static final class Control implements DynamicMBean {
        String id;
        int seconds, protocol = 1;
        String status = "phases available, idle; tracker available, idle";
        boolean fail;
        final Map<String, Object> phases = new LinkedHashMap<>(Map.of("schema", "sneakydiagnostics.phases/1", "captureId", "ignored",
                "complete", true, "phases", Map.of("scheduler", new int[]{1000})));
        @Override public Object getAttribute(String name) { return name.equals("ProtocolVersion") ? protocol : status; }
        @Override public void setAttribute(Attribute attribute) { throw new UnsupportedOperationException(); }
        @Override public AttributeList getAttributes(String[] names) { return new AttributeList(); }
        @Override public AttributeList setAttributes(AttributeList attributes) { return new AttributeList(); }
        @Override public Object invoke(String operation, Object[] args, String[] signature) {
            if (operation.equals("start")) {
                assertArrayEquals(new String[]{"java.lang.String", "java.lang.String", "long", "int"}, signature);
                assertEquals("phases", args[0]);
                id = (String) args[1]; seconds = (Integer) args[3];
                return null;
            }
            assertEquals("stop", operation);
            assertArrayEquals(new String[]{"java.lang.String", "java.lang.String"}, signature);
            assertEquals("phases", args[0]);
            assertEquals(id, args[1]);
            if (fail) throw new IllegalStateException("boom");
            return phases;
        }
        @Override public MBeanInfo getMBeanInfo() { return new MBeanInfo(getClass().getName(), "control", null, null, null, null); }
    }

    private static final class Sink implements ReportSink {
        Map<String, Object> report;
        @Override public boolean busy(String collector) { return false; }
        @Override public Map<String, Object> header(String collector, CaptureContext context, String reason, int segment) {
            return new HashMap<>(Map.of("captureId", context.id(), "reason", reason, "collector", collector));
        }
        @Override public void write(String collector, CaptureContext context, int segment, Supplier<Map<String, Object>> report) {
            this.report = report.get();
        }
    }
}
