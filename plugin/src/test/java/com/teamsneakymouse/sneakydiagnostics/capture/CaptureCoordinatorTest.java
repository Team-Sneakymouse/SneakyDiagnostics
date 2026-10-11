package com.teamsneakymouse.sneakydiagnostics.capture;

import java.util.List;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureCoordinatorTest {
    private final CaptureContext context = new CaptureContext("shared-id", "2026-10-11T00:00:00Z", 0, 20, 60);

    @Test void busyWriterRejectsWholeStartBeforeAnyCollectorChanges() {
        var first = new FakeCollector();
        var writing = new FakeCollector();
        writing.writing = true;
        var selected = List.<DiagnosticCollector>of(first, writing);
        var coordinator = new CaptureCoordinator(selected);
        assertThrows(IllegalArgumentException.class, () -> coordinator.start(selected, context, List.of(), true));
        assertNull(first.context);
        assertNull(writing.context);
    }

    @Test void partialStartIsRolledBackAndUntouchedCollectorsDoNotStart() {
        var first = new FakeCollector();
        var failed = new FakeCollector();
        failed.fail = true;
        var last = new FakeCollector();
        var selected = List.<DiagnosticCollector>of(first, failed, last);
        var coordinator = new CaptureCoordinator(selected);
        assertThrows(IllegalStateException.class, () -> coordinator.start(selected, context, List.of(), true));
        assertFalse(first.active());
        assertFalse(failed.active());
        assertEquals("start_error", first.stopReason);
        assertNull(last.context);
        assertFalse(coordinator.coordinatedRunning());
    }

    @Test void allSharesIdentityAndBlocksIndividualStartUntilAllCollectorsStop() {
        var first = new FakeCollector();
        var second = new FakeCollector();
        var selected = List.<DiagnosticCollector>of(first, second);
        var coordinator = new CaptureCoordinator(selected);
        coordinator.start(selected, context, List.of(), true);
        assertSame(context, first.context);
        assertSame(context, second.context);
        first.stop("completed");
        assertTrue(coordinator.coordinatedRunning());
        assertThrows(IllegalArgumentException.class, () -> coordinator.resume(() -> first.start(context, List.of())));
        assertThrows(IllegalArgumentException.class, () -> coordinator.start(List.of(first), context, List.of(), false));
        second.stop("completed");
        assertFalse(coordinator.coordinatedRunning());
        coordinator.resume(() -> first.start(context, List.of()));
        assertTrue(first.active());
        assertFalse(coordinator.coordinatedRunning());
    }

    @Test void independentCollectorsCanOverlapWithoutBecomingCoordinated() {
        var first = new FakeCollector();
        var second = new FakeCollector();
        var coordinator = new CaptureCoordinator(List.of(first, second));
        coordinator.start(List.of(first), context, List.of(), false);
        coordinator.start(List.of(second), context, List.of(), false);
        assertFalse(coordinator.coordinatedRunning());
        assertTrue(first.active());
        assertTrue(second.active());
    }

    private static final class FakeCollector implements DiagnosticCollector {
        CaptureContext context;
        boolean active, writing, fail;
        String stopReason;
        @Override public String name() { return "fake"; }
        @Override public boolean active() { return active; }
        @Override public boolean busy() { return active || writing; }
        @Override public String status() { return "fake"; }
        @Override public void start(CaptureContext context, List<World> worlds) {
            this.context = context;
            active = true;
            if (fail) throw new IllegalStateException("failed after acquiring resources");
        }
        @Override public void stop(String reason) { active = false; stopReason = reason; }
    }
}
