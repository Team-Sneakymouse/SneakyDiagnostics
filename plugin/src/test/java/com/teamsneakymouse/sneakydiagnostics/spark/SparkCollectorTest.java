package com.teamsneakymouse.sneakydiagnostics.spark;

import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.CaptureCoordinator;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SparkCollectorTest {
    @Test void missingSparkDoesNotAbortCoordinatedCapture() {
        var sink = new Sink();
        var collector = new SparkCollector(id -> { throw new ClassNotFoundException("spark"); }, sink);
        var coordinator = new CaptureCoordinator(List.of(collector));
        assertDoesNotThrow(() -> coordinator.start(List.of(collector), context(), List.of(), true));
        assertFalse(collector.active());
        assertEquals("unavailable", sink.report.get("reason"));
        assertEquals(false, sink.report.get("complete"));
        assertTrue(collector.status().contains("Skipped"));
    }

    @Test void ownedCaptureFinalizesAndExportsOffTheTickCallback() {
        var session = new Recording(true);
        var sink = new Sink();
        var collector = new SparkCollector(id -> { assertEquals("shared", id); return session; }, sink);
        collector.start(context(), List.of());
        collector.onTick(null);
        assertFalse(collector.active());
        assertTrue(collector.busy());
        assertEquals(0, session.stops);
        assertEquals(0, session.exports);
        assertEquals("completed", sink.report.get("reason"));
        assertEquals("dedicated_session", sink.report.get("coverage"));
        assertArrayEquals(new byte[]{1, 2}, sink.finish());
        assertEquals(1, session.stops);
        assertEquals(1, session.exports);
        collector.stop("plugin_disable");
        assertEquals(1, sink.writes);
    }

    @Test void sharedCaptureExportsWithoutStoppingTheExistingProfiler() {
        var session = new Recording(false);
        var sink = new Sink();
        var collector = new SparkCollector(id -> session, sink);
        collector.start(context(), List.of());
        collector.onTick(null);
        sink.finish();
        assertEquals(0, session.stops);
        assertEquals(1, session.exports);
        assertEquals(false, sink.report.get("owned"));
        assertEquals("shared_history", sink.report.get("coverage"));
    }

    @Test void operatorReplacementEndsObservationAndMarksTheExportPartial() {
        var session = new Recording(false);
        var sink = new Sink();
        var collector = new SparkCollector(id -> session, sink);
        collector.start(context(), List.of());
        session.current = false;
        collector.onTick(null);
        sink.finish();
        assertEquals("profiler_changed", sink.report.get("reason"));
        assertEquals(false, sink.report.get("complete"));
        assertEquals(true, sink.report.get("profilerChanged"));
        assertEquals(0, session.stops);
    }

    @Test void disableAndRollbackFinalizeOwnedPartialProfiles() {
        for (String reason : List.of("plugin_disable", "start_error", "manual_stop")) {
            var session = new Recording(true);
            var sink = new Sink();
            var collector = new SparkCollector(id -> session, sink);
            collector.start(context(), List.of());
            collector.stop(reason);
            sink.finish();
            assertEquals(reason, sink.report.get("reason"));
            assertEquals(false, sink.report.get("complete"));
            assertEquals(1, session.stops);
        }
    }

    @Test void exportFailureStillStopsTheOwnedSampler() {
        var session = new Recording(true);
        session.failExport = true;
        var sink = new Sink();
        var collector = new SparkCollector(id -> session, sink);
        collector.start(context(), List.of());
        collector.stop("completed");
        assertThrows(IllegalStateException.class, sink::finish);
        assertEquals(1, session.stops);
    }

    private static CaptureContext context() {
        return new CaptureContext("shared", "now", System.nanoTime() - 2_000_000_000L, 4, 1);
    }

    private static final class Recording implements SparkProfiler.Session {
        final boolean owned;
        boolean current = true, failExport;
        int stops, exports;
        Recording(boolean owned) { this.owned = owned; }
        @Override public boolean owned() { return owned; }
        @Override public boolean current() { return current; }
        @Override public Map<String, Object> details() { return Map.of("engine", "test"); }
        @Override public void stop() { stops++; }
        @Override public byte[] export() {
            if (owned) assertEquals(1, stops);
            exports++;
            if (failExport) throw new IllegalStateException("disk/export failure");
            return new byte[]{1, 2};
        }
    }

    private static final class Sink implements ReportSink {
        Map<String, Object> report;
        Supplier<byte[]> artifact;
        int writes;
        @Override public boolean busy(String collector) { return artifact != null; }
        @Override public Map<String, Object> header(String collector, CaptureContext context, String reason, int segment) {
            return new HashMap<>(Map.of("captureId", context.id(), "reason", reason));
        }
        @Override public void write(String collector, CaptureContext context, int segment, Supplier<Map<String, Object>> report) {
            writes++;
            this.report = report.get();
        }
        @Override public void write(String collector, CaptureContext context, int segment, Supplier<Map<String, Object>> report,
                                    String name, Supplier<byte[]> artifact) {
            assertEquals("profile.sparkprofile", name);
            write(collector, context, segment, report);
            this.artifact = artifact;
        }
        byte[] finish() {
            try { return artifact.get(); }
            finally { artifact = null; }
        }
    }
}
