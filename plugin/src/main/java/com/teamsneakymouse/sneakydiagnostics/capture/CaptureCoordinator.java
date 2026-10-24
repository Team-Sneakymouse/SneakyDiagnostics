package com.teamsneakymouse.sneakydiagnostics.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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
        start(selected, context, all, collector -> collector.start(context, worlds));
    }

    /**
     * Like the options variant below, and also tells the capture which collectors are running, so the manifest
     * can sample its end workload when the last one finishes. Prefer this whenever a context exists.
     */
    public void start(List<DiagnosticCollector> selected, CaptureContext context, boolean all, Consumer<DiagnosticCollector> startCollector) {
        run(selected, context, all, startCollector);
    }

    /** Keeps validation and rollback shared when a collector has additional start options. */
    public void start(List<DiagnosticCollector> selected, boolean all, Consumer<DiagnosticCollector> startCollector) {
        run(selected, null, all, startCollector);
    }

    private void run(List<DiagnosticCollector> selected, CaptureContext context, boolean all, Consumer<DiagnosticCollector> startCollector) {
        if (coordinatedRunning()) throw new IllegalArgumentException("A coordinated capture is still running; stop it before starting another capture");
        if (selected.stream().anyMatch(DiagnosticCollector::busy)) throw new IllegalArgumentException("A selected collector is collecting or writing a report");
        // Registered up front: a collector that reports during its own start must not look like the last one.
        if (context != null) context.progress().begin(selected.stream().map(DiagnosticCollector::name).toList());
        List<DiagnosticCollector> started = new ArrayList<>();
        try {
            for (var collector : selected) { started.add(collector); startCollector.accept(collector); }
        } catch (RuntimeException e) {
            if (context != null) selected.stream().filter(collector -> !started.contains(collector)).forEach(collector -> context.progress().cancel(collector.name()));
            for (var collector : started) {
                try { collector.stop("start_error"); }
                catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            }
            throw e;
        }
        coordinated = all;
    }
}
