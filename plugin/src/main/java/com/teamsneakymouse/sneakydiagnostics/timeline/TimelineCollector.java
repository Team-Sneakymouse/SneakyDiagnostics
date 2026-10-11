package com.teamsneakymouse.sneakydiagnostics.timeline;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.DiagnosticCollector;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import java.util.List;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class TimelineCollector implements DiagnosticCollector, Listener {
    private final ReportSink reports;
    private CaptureContext context;
    private TickTimeline timeline;
    private long overheadNanos, maxOverheadNanos;

    public TimelineCollector(ReportSink reports) { this.reports = reports; }
    @Override public String name() { return "timeline"; }
    @Override public boolean active() { return context != null; }
    @Override public boolean busy() { return active() || reports.busy(name()); }
    @Override public String status() { return active() ? "Collecting " + context.id() : reports.busy(name()) ? "Writing report" : "Idle"; }
    @Override public void start(CaptureContext context, List<World> worlds) {
        if (busy()) throw new IllegalStateException("Timeline is busy");
        this.context = context;
        timeline = new TickTimeline();
        overheadNanos = maxOverheadNanos = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTick(ServerTickEndEvent event) {
        if (!active()) return;
        long begin = System.nanoTime();
        long millis = context.offsetMillis();
        timeline.record(millis, event.getTickNumber(), event.getTickDuration());
        long overhead = System.nanoTime() - begin;
        overheadNanos += overhead;
        maxOverheadNanos = Math.max(maxOverheadNanos, overhead);
        if (millis >= context.seconds() * 1000L) stop("completed");
    }

    @Override public void stop(String reason) {
        if (!active()) return;
        CaptureContext finished = context;
        var report = reports.header(name(), finished, reason, 1);
        report.put("complete", reason.equals("completed"));
        report.put("startedAt", finished.startedAt());
        report.put("windows", timeline.finish());
        report.put("totalCollectionNanos", overheadNanos);
        report.put("maxCollectionNanos", maxOverheadNanos);
        report.put("notes", "Tick-end duration from Paper; excludes remaining tick-end listeners and idle sleep. "
                + "Windows are indexed by tick-end observation offset, not tick start. Missing seconds contain no observed tick ends. "
                + "This measures the whole server, not individual plugin CPU cost. Report dispatch is outside collection timing.");
        context = null;
        timeline = null;
        reports.write(name(), finished, 1, () -> report);
    }
}
