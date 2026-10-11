package com.teamsneakymouse.sneakydiagnostics.scheduler;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.DiagnosticCollector;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class SchedulerCollector implements DiagnosticCollector, Listener {
    private final JavaPlugin plugin;
    private final ReportSink reports;
    private CaptureContext context;
    private final List<SchedulerSnapshot.Snapshot> snapshots = new ArrayList<>();
    private Set<SchedulerSnapshot.TaskKey> previous;
    private boolean previousComplete;
    private long lastOffset;
    private long totalCollectionNanos, maxCollectionNanos;
    private int errors;
    private final IgniteSchedulerProbe ignite = new IgniteSchedulerProbe();
    private boolean igniteActive;
    private String igniteAvailability;

    public SchedulerCollector(JavaPlugin plugin, ReportSink reports) { this.plugin = plugin; this.reports = reports; }
    @Override public String name() { return "scheduler"; }
    @Override public boolean active() { return context != null; }
    @Override public boolean busy() { return active() || reports.busy(name()); }
    @Override public String status() { return (active() ? "Collecting " + context.id() + ": " + snapshots.size() + " snapshots" : reports.busy(name()) ? "Writing report" : "Idle") + "; scheduler probe: " + ignite.status(); }
    @Override public void start(CaptureContext context, List<World> worlds) {
        if (busy()) throw new IllegalStateException("Scheduler collector is busy");
        this.context = context;
        previous = null;
        snapshots.clear();
        errors = 0;
        totalCollectionNanos = maxCollectionNanos = 0;
        lastOffset = -1000;
        igniteActive = false;
        igniteAvailability = ignite.status();
        if (ignite.available()) {
            try { ignite.start(context); igniteActive = true; }
            catch (Exception e) { this.context = null; throw new IllegalStateException("Cannot start Ignite scheduler probe", e); }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTick(ServerTickEndEvent event) {
        if (!active()) return;
        long offset = context.offsetMillis();
        if (offset - lastOffset >= 1000) {
            lastOffset = offset;
            long begin = System.nanoTime();
            try {
                var scheduler = Bukkit.getScheduler();
                var observed = SchedulerSnapshot.read(offset, scheduler.getPendingTasks(), scheduler.getActiveWorkers(),
                        previous, previousComplete, begin);
                snapshots.add(observed.snapshot());
                previous = observed.taskIds();
                previousComplete = observed.snapshot().pendingTasks() <= SchedulerSnapshot.MAX_TASKS;
            } catch (RuntimeException e) {
                errors++;
                previous = null;
                if (errors == 1) plugin.getLogger().log(Level.WARNING, "Scheduler snapshot failed", e);
            } finally {
                long elapsed = System.nanoTime() - begin;
                totalCollectionNanos += elapsed;
                maxCollectionNanos = Math.max(maxCollectionNanos, elapsed);
            }
        }
        if (offset >= context.seconds() * 1000L) stop("completed");
    }

    @Override public void stop(String reason) {
        if (!active()) return;
        CaptureContext finished = context;
        var report = reports.header(name(), finished, reason, 1);
        boolean probeComplete = true;
        report.put("startedAt", finished.startedAt());
        report.put("igniteAvailability", igniteAvailability);
        if (igniteActive) {
            try {
                var measurement = ignite.stop(finished.id());
                report.put("ignite", measurement);
                probeComplete = ((Number) measurement.get("droppedEvents")).longValue() == 0 && ((Number) measurement.get("droppedTaskIds")).longValue() == 0
                        && ((Number) measurement.get("inFlightAtStop")).longValue() == 0;
                report.put("igniteComplete", probeComplete);
            } catch (Exception e) {
                errors++;
                report.put("igniteError", e.toString());
                plugin.getLogger().log(Level.WARNING, "Cannot stop Ignite scheduler probe", e);
            }
        }
        igniteActive = false;
        report.put("complete", reason.equals("completed") && errors == 0 && probeComplete && snapshots.stream().noneMatch(SchedulerSnapshot.Snapshot::truncated));
        report.put("errors", errors);
        report.put("totalCollectionNanos", totalCollectionNanos);
        report.put("maxCollectionNanos", maxCollectionNanos);
        report.put("snapshots", List.copyOf(snapshots));
        report.put("limits", java.util.Map.of("sampleIntervalMillis", 1000, "tasksPerSnapshot", SchedulerSnapshot.MAX_TASKS,
                "workersPerSnapshot", SchedulerSnapshot.MAX_WORKERS, "groupsPerSnapshot", SchedulerSnapshot.MAX_GROUPS));
        report.put("notes", "Public Bukkit scheduler snapshots, including diagnostics' own pending tasks. "
                + "In public snapshots, task implementation class is the scheduler wrapper; runnable class and cancellation callers are unavailable. Optional Ignite measurements are reported separately. "
                + "New/missing task IDs between complete snapshots are observed churn, not creation/execution/cancellation rates; short-lived tasks can be missed. "
                + "getPendingTasks/getActiveWorkers copy their full lists before our caps apply; these calls are not preemptible and their cost is included. "
                + "Async scheduler state can change during a snapshot. World filters do not restrict scheduler collection.");
        snapshots.clear();
        previous = null;
        context = null;
        reports.write(name(), finished, 1, () -> report);
    }
}
