package com.teamsneakymouse.sneakydiagnostics.spark;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.DiagnosticCollector;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import java.util.List;
import java.time.Instant;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Owns only sessions it started; shared samplers are exported without stopping them. */
public final class SparkCollector implements DiagnosticCollector, Listener {
    private final SparkProfiler profiler;
    private final ReportSink reports;
    private CaptureContext context;
    private SparkProfiler.Session session;
    private String outcome = "Idle";

    public SparkCollector(SparkProfiler profiler, ReportSink reports) {
        this.profiler = profiler;
        this.reports = reports;
    }

    @Override public String name() { return "spark"; }
    @Override public boolean active() { return context != null; }
    @Override public boolean busy() { return active() || reports.busy(name()); }
    @Override public String status() {
        return active() ? (session.owned() ? "Collecting " : "Observing shared profiler for ") + context.id()
                : reports.busy(name()) ? "Exporting profile" : outcome;
    }

    @Override public void start(CaptureContext context, List<World> worlds) {
        if (busy()) throw new IllegalStateException("Spark collector is busy");
        try {
            session = profiler.open(context.id());
            this.context = context;
        } catch (Exception | LinkageError e) {
            outcome = "Skipped: " + e;
            var report = reports.header(name(), context, "unavailable", 1);
            report.put("complete", false);
            report.put("error", e.toString());
            reports.write(name(), context, 1, () -> report);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTick(ServerTickEndEvent event) {
        if (!active()) return;
        try {
            if (!session.current()) { stop("profiler_changed"); return; }
        } catch (Exception | LinkageError e) {
            stop("profiler_unavailable");
            return;
        }
        if (context.offsetMillis() >= context.seconds() * 1000L) stop("completed");
    }

    @Override public void stop(String reason) {
        if (!active()) return;
        var finished = context;
        var captured = session;
        context = null;
        session = null;
        var report = reports.header(name(), finished, reason, 1);
        report.put("owned", captured.owned());
        report.put("coverage", captured.owned() ? "dedicated_session" : "shared_history");
        report.put("complete", reason.equals("completed"));
        report.put("notes", "Shared profiles can include samples before this capture and omit the newest buffered samples. "
                + "Existing profiler settings are preserved. World filters do not restrict profiling. "
                + "Profile export runs off the server thread; inspect spark's own windows for actual sample coverage.");
        outcome = "Finished " + finished.id() + " (" + reason + ")";
        reports.write(name(), finished, 1, () -> report, "profile.sparkprofile", () -> {
            try {
                report.put("exportStartedAt", Instant.now().toString());
                report.put("exportAfterMillis", finished.offsetMillis());
                if (!captured.current()) {
                    report.put("complete", false);
                    report.put("profilerChanged", true);
                }
                // Finalization can aggregate a large native profile, so it belongs on the writer too.
                // The session checks identity before stopping anything.
                if (captured.owned()) captured.stop();
                report.put("snapshotAfterMillis", finished.offsetMillis());
                report.putAll(captured.details());
                return captured.export();
            }
            catch (Exception e) { throw new IllegalStateException("Spark export failed", e); }
        });
    }
}
