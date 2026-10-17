package com.teamsneakymouse.sneakydiagnostics.integration;

import com.teamsneakymouse.sneakydiagnostics.api.Diagnostics;
import com.teamsneakymouse.sneakydiagnostics.capture.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PluginCollectorTest {
    @Test void tickDeadlineWritesProviderResultsWithTheSharedIdentity() {
        var registry = new ProviderRegistry(() -> {});
        var sink = new Sink();
        var collector = new PluginCollector(registry, sink);
        registry.register("MagicSpells", "v", "i", new Diagnostics.Provider() {
            @Override public void start(Diagnostics.Capture capture) { assertEquals("shared", capture.id()); }
            @Override public Diagnostics.Result stop(String reason) { return new Diagnostics.Result(Map.of("calls", 3), true); }
        });
        var context = new CaptureContext("shared", "now", System.nanoTime() - 2_000_000_000L, 4, 1);
        collector.start(context, List.of());
        collector.onTick(null);
        assertFalse(collector.active());
        assertEquals("shared", sink.report.get("captureId"));
        assertEquals(true, sink.report.get("complete"));
        assertEquals("completed", sink.report.get("reason"));
        assertEquals(1, sink.writes);
        collector.stop("plugin_disable");
        assertEquals(1, sink.writes);
    }

    @Test void coordinatorRollsBackPluginRecorderWhenItsStartFails() {
        var registry = new ProviderRegistry(() -> {});
        var sink = new Sink();
        var collector = new PluginCollector(registry, sink);
        int[] stops = {0};
        registry.register("failing", "v", "i", new Diagnostics.Provider() {
            @Override public void start(Diagnostics.Capture capture) { throw new IllegalStateException("failed after starting"); }
            @Override public Diagnostics.Result stop(String reason) { stops[0]++; return new Diagnostics.Result(Map.of(), false); }
        });
        var coordinator = new CaptureCoordinator(List.of(collector));
        assertThrows(IllegalStateException.class, () -> coordinator.start(List.of(collector),
                new CaptureContext("shared", "now", System.nanoTime(), 4, 60), List.of(), true));
        assertFalse(collector.active());
        assertEquals(1, stops[0]);
        assertEquals(false, sink.report.get("complete"));
        assertEquals("start_error", sink.report.get("reason"));
    }

    private static class Sink implements ReportSink {
        Map<String, Object> report;
        int writes;
        @Override public boolean busy(String collector) { return false; }
        @Override public Map<String, Object> header(String collector, CaptureContext context, String reason, int segment) {
            return new HashMap<>(Map.of("captureId", context.id(), "reason", reason));
        }
        @Override public void write(String collector, CaptureContext context, int segment, Supplier<Map<String, Object>> report) {
            writes++;
            this.report = report.get();
        }
    }
}
