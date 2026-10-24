package com.teamsneakymouse.sneakydiagnostics.connection;

import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionCollectorTest {
    @Test void capturesPaperPingAndWritesDetachedPartialReportWithSharedIdentity() {
        var sink = new Sink();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> new UUID(0, 1);
            case "getPing" -> 2000;
            default -> throw new AssertionError("Unexpected read: " + method.getName());
        });
        var collector = new ConnectionCollector(sink, () -> List.of(player), id -> player);
        collector.start(new CaptureContext("shared", "now", System.nanoTime(), 1, 60), List.of(), List.of(new UUID(0, 1)));
        assertThrows(IllegalStateException.class, () -> collector.start(new CaptureContext("other", "now", 0, 1, 60), List.of()));
        collector.stop("manual_stop");
        collector.stop("plugin_disable");
        assertEquals(1, sink.writes);
        assertEquals("shared", sink.report.get("captureId"));
        assertEquals(false, sink.report.get("complete"));
        assertEquals("paper", sink.report.get("platform"));
        @SuppressWarnings("unchecked") var samples = (List<ConnectionCapture.Sample>) sink.report.get("samples");
        assertEquals(1, samples.getFirst().atLeast2000ms());
        assertEquals(1, samples.getFirst().targets().sampledPlayers());
        assertEquals(0, samples.getFirst().baseline().sampledPlayers());
        collector.start(new CaptureContext("next", "now", System.nanoTime(), 1, 60), List.of());
        collector.stop("manual_stop");
        assertEquals(0, sink.report.get("targetCount"));
    }

    @Test void expiredCaptureDoesNotReadBukkitAndStopsOnFirstTick() {
        var sink = new Sink();
        var collector = new ConnectionCollector(sink, () -> { fail("No reads after deadline"); return List.of(); });
        collector.start(new CaptureContext("shared", "now", System.nanoTime() - 2_000_000_000L, 1, 1), List.of());
        collector.onTick(null);
        assertFalse(collector.active());
        assertEquals("completed", sink.report.get("reason"));
        assertEquals(false, sink.report.get("complete"));
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
