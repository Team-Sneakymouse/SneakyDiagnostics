package com.teamsneakymouse.sneakydiagnostics.capture;

import java.util.List;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureProgressTest {
    private static CaptureContext context() { return new CaptureContext("id", "2026-10-24T00:00:00Z", 0, 0, 60); }

    @Test void onlyTheLastRegisteredCollectorToFinishSamplesTheEndWorkload() {
        var progress = new CaptureProgress();
        progress.begin(List.of("timeline", "entities", "spark"));
        assertFalse(progress.finish("spark"));
        assertFalse(progress.finish("timeline"));
        assertTrue(progress.finish("entities"));
    }

    @Test void unregisteredLoneCollectorsAndResumedSegmentsFinishLast() {
        var progress = new CaptureProgress();
        assertTrue(progress.finish("connections"));
        progress.begin(List.of("entities"));
        assertTrue(progress.finish("entities"));
        assertTrue(progress.finish("entities"), "a resumed segment finishing again samples a fresh end");
    }

    @Test void coordinatorRegistersAllSelectedCollectorsBeforeAnyStarts() {
        var context = context();
        var early = new Named("spark");
        early.reportOnStart = true;
        var later = new Named("timeline");
        var selected = List.<DiagnosticCollector>of(early, later);
        var coordinator = new CaptureCoordinator(selected);
        boolean[] lastDuringStart = new boolean[1];
        early.onStart = () -> lastDuringStart[0] = context.progress().finish("spark");
        coordinator.start(selected, context, List.of(), true);
        assertFalse(lastDuringStart[0], "a collector reporting during start must not look like the last one");
        assertTrue(context.progress().finish("timeline"));
    }

    @Test void rolledBackStartDropsCollectorsThatNeverStarted() {
        var context = context();
        var first = new Named("entities");
        var failing = new Named("timeline");
        failing.fail = true;
        var untouched = new Named("scheduler");
        var selected = List.<DiagnosticCollector>of(first, failing, untouched);
        var coordinator = new CaptureCoordinator(selected);
        assertThrows(IllegalStateException.class, () -> coordinator.start(selected, context, List.of(), true));
        // The started collectors report on their own rollback stop; the one that never started must not hold the capture open.
        assertFalse(context.progress().finish("entities"));
        assertTrue(context.progress().finish("timeline"), "scheduler never started and must not keep the capture open");
    }

    @Test void startWithoutContextLeavesProgressUntouched() {
        var collector = new Named("connections");
        var coordinator = new CaptureCoordinator(List.of(collector));
        coordinator.start(List.of(collector), false, c -> c.start(context(), List.of()));
        assertTrue(collector.active());
    }

    private static final class Named implements DiagnosticCollector {
        final String name;
        boolean active, fail, reportOnStart;
        Runnable onStart = () -> {};
        Named(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public boolean active() { return active; }
        @Override public boolean busy() { return active; }
        @Override public String status() { return name; }
        @Override public void start(CaptureContext context, List<World> worlds) {
            active = true;
            if (reportOnStart) onStart.run();
            if (fail) throw new IllegalStateException("start failed");
        }
        @Override public void stop(String reason) { active = false; }
    }
}
