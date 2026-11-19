package com.teamsneakymouse.sneakydiagnostics.phases;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.DiagnosticCollector;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Tick phases ({@code phases-1.json}) from the Ignite companion's server probe. Without the companion the report says
 * the phases are unavailable. World filters do not restrict it: every loaded world (up to the companion's cap) is timed.
 */
public final class PhasesCollector implements DiagnosticCollector, Listener {
    /** Fields of the companion's phase map that the report header already carries or renames. */
    private static final Set<String> HEADER_FIELDS = Set.of("captureId", "complete");
    private final Logger logger;
    private final ReportSink reports;
    private final IgniteServerProbe probe;
    private CaptureContext context;
    private boolean probeActive;
    private String availability;

    public PhasesCollector(Logger logger, ReportSink reports) { this(logger, reports, new IgniteServerProbe()); }
    PhasesCollector(Logger logger, ReportSink reports, IgniteServerProbe probe) {
        this.logger = logger;
        this.reports = reports;
        this.probe = probe;
    }

    @Override public String name() { return "phases"; }
    @Override public boolean active() { return context != null; }
    @Override public boolean busy() { return active() || reports.busy(name()); }
    @Override public String status() {
        return (active() ? "Collecting " + context.id() : reports.busy(name()) ? "Writing report" : "Idle") + "; server probe: " + probe.status();
    }

    @Override public void start(CaptureContext context, List<World> worlds) {
        if (busy()) throw new IllegalStateException("Phases collector is busy");
        this.context = context;
        probeActive = false;
        availability = probe.status();
        if (probe.available()) {
            try { probe.start(context); probeActive = true; }
            catch (Exception e) { this.context = null; throw new IllegalStateException("Cannot start Ignite server probe", e); }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTick(ServerTickEndEvent event) {
        if (active() && context.offsetMillis() >= context.seconds() * 1000L) stop("completed");
    }

    @Override public void stop(String reason) {
        if (!active()) return;
        CaptureContext finished = context;
        var report = reports.header(name(), finished, reason, 1);
        report.put("igniteAvailability", availability);
        int errors = 0;
        boolean phasesComplete = false;
        report.put("available", probeActive);
        if (probeActive) {
            try {
                Object phases = probe.stop(finished.id()).get("phases");
                if (!(phases instanceof Map<?, ?> map)) throw new IllegalStateException("Server probe returned no phases");
                for (var entry : map.entrySet()) {
                    if (entry.getKey() instanceof String key && !HEADER_FIELDS.contains(key)) report.put(key, entry.getValue());
                }
                phasesComplete = Boolean.TRUE.equals(map.get("complete"));
                if (map.get("error") != null) errors++;
            } catch (Exception e) {
                errors++;
                report.put("available", false);
                report.put("igniteError", e.toString());
                logger.log(Level.WARNING, "Cannot stop Ignite server probe", e);
            }
        } else {
            report.put("unavailableReason", "Tick phases need the Ignite companion: " + availability);
        }
        probeActive = false;
        report.put("phasesComplete", phasesComplete);
        report.put("errors", errors);
        report.put("complete", reason.equals("completed") && errors == 0 && phasesComplete);
        context = null;
        reports.write(name(), finished, 1, () -> report);
    }
}
