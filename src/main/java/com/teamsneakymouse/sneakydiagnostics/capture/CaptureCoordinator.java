package com.teamsneakymouse.sneakydiagnostics.capture;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.World;

/** Validates a whole start before mutating collectors and rolls back partial starts. */
public final class CaptureCoordinator {
    private final List<DiagnosticCollector> collectors;
    private boolean coordinated;

    public CaptureCoordinator(List<DiagnosticCollector> collectors) { this.collectors = List.copyOf(collectors); }
    public boolean coordinatedRunning() { return coordinated && collectors.stream().anyMatch(DiagnosticCollector::active); }

    public void resume(Runnable resume) {
        if (coordinatedRunning()) throw new IllegalArgumentException("Wait for or stop the coordinated capture before resuming the census");
        resume.run();
        coordinated = false;
    }

    public void start(List<DiagnosticCollector> selected, CaptureContext context, List<World> worlds, boolean all) {
        if (coordinatedRunning()) throw new IllegalArgumentException("A coordinated capture is still running; stop it before starting another capture");
        if (selected.stream().anyMatch(DiagnosticCollector::busy)) throw new IllegalArgumentException("A selected collector is collecting or writing a report");
        List<DiagnosticCollector> started = new ArrayList<>();
        try {
            for (var collector : selected) { started.add(collector); collector.start(context, worlds); }
        } catch (RuntimeException e) {
            for (var collector : started) {
                try { collector.stop("start_error"); }
                catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            }
            throw e;
        }
        coordinated = all;
    }
}
