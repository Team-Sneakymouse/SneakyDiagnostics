package com.teamsneakymouse.sneakydiagnostics.velocity;

import com.teamsneakymouse.sneakydiagnostics.connection.ConnectionCapture;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VelocityConnectionsTest {
    private static Player player(long ping) {
        return player(1, ping);
    }
    private static Player player(long id, long ping) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> new UUID(0, id);
            case "getPing" -> ping;
            case "getCurrentServer" -> Optional.empty();
            default -> throw new AssertionError("Unexpected player read: " + method.getName());
        });
    }

    @Test void automaticDeadlineAndConcurrentStopsWriteExactlyOneDetachedReport() throws Exception {
        var clock = new AtomicLong();
        var reports = new ArrayList<Map<String, Object>>();
        var collector = new VelocityConnections(() -> List.of(player(2000)), reports::add, () -> false, clock::get, "proxy", "plugin");
        String id = collector.start(2);
        assertThrows(IllegalStateException.class, () -> collector.start(1));
        clock.set(1_000_000_000L);
        collector.poll();
        clock.set(2_000_000_000L);
        Thread poll = Thread.ofPlatform().start(collector::poll);
        Thread close = Thread.ofPlatform().start(collector::close);
        poll.join(); close.join();
        collector.stop("manual_stop");
        assertEquals(1, reports.size());
        assertEquals(id, reports.getFirst().get("captureId"));
        assertEquals("velocity", reports.getFirst().get("platform"));
        @SuppressWarnings("unchecked") var samples = (List<ConnectionCapture.Sample>) reports.getFirst().get("samples");
        assertEquals(2, samples.size());
        assertEquals(1, samples.getFirst().atLeast2000ms());
        assertThrows(IllegalStateException.class, () -> collector.start(1));
    }

    @Test void deadlineDoesNotReadPlayersAndPendingWriteBlocksRestart() {
        var clock = new AtomicLong();
        int[] reads = {0};
        var reports = new ArrayList<Map<String, Object>>();
        boolean[] writing = {false};
        var collector = new VelocityConnections(() -> { reads[0]++; return List.of(player(30)); },
                report -> { writing[0] = true; reports.add(report); }, () -> writing[0], clock::get, "proxy", "plugin");
        collector.start(1);
        clock.set(3_000_000_000L);
        collector.poll();
        assertEquals(1, reads[0]);
        assertEquals("completed", reports.getFirst().get("reason"));
        assertEquals("Writing report", collector.status());
        assertThrows(IllegalStateException.class, () -> collector.start(1));
        writing[0] = false;
        collector.start(1);
        collector.stop("manual_stop");
        assertEquals(false, reports.getLast().get("complete"));
    }

    @Test void collectionFailureProducesPartialReportWithoutExceptionContents() {
        var reports = new ArrayList<Map<String, Object>>();
        var collector = new VelocityConnections(() -> { throw new IllegalStateException("private-address"); },
                reports::add, () -> false, () -> 0L, "proxy", "plugin");
        assertThrows(IllegalStateException.class, () -> collector.start(60));
        assertEquals("collection_error", reports.getFirst().get("reason"));
        assertEquals(false, reports.getFirst().get("complete"));
        assertFalse(reports.toString().contains("private-address"));
    }

    @Test void selectedPlayersArePrioritizedAndASelectionDoesNotPersistToTheNextCapture() {
        var target = player(1, 2000);
        var baseline = player(2, 30);
        var reports = new ArrayList<Map<String, Object>>();
        var collector = new VelocityConnections(() -> List.of(baseline, target), reports::add, () -> false,
                () -> 0L, "proxy", "plugin", id -> id.equals(new UUID(0, 1)) ? target : null);
        collector.start(60, List.of(new UUID(0, 1)));
        collector.stop("manual_stop");
        @SuppressWarnings("unchecked") var samples = (List<ConnectionCapture.Sample>) reports.getFirst().get("samples");
        assertEquals(2, samples.getFirst().players().size());
        assertEquals(1, samples.getFirst().players().getFirst().target());
        assertEquals(2000L, samples.getFirst().targets().p95Millis());
        assertEquals(30L, samples.getFirst().baseline().p95Millis());
        collector.start(60);
        collector.stop("manual_stop");
        assertEquals(0, reports.getLast().get("targetCount"));
    }
}
