package com.teamsneakymouse.sneakydiagnostics.integration;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.teamsneakymouse.sneakydiagnostics.api.Diagnostics;
import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.DiagnosticCollector;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import java.util.List;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Coordinates all providers as one collector; writing always belongs to diagnostics. */
public final class PluginCollector implements DiagnosticCollector, Listener {
    private final ProviderRegistry providers;
    private final ReportSink reports;
    private CaptureContext context;

    public PluginCollector(ProviderRegistry providers, ReportSink reports) { this.providers = providers; this.reports = reports; }
    @Override public String name() { return "plugins"; }
    @Override public boolean active() { return context != null; }
    @Override public boolean busy() { return active() || reports.busy(name()); }
    @Override public String status() {
        return (active() ? "Collecting " + context.id() : reports.busy(name()) ? "Writing report" : "Idle")
                + "; registered probes: " + (providers.providers().isEmpty() ? "none" : String.join(", ", providers.providers()));
    }
    @Override public void start(CaptureContext context, List<World> worlds) {
        if (busy()) throw new IllegalStateException("Plugin collector is busy");
        this.context = context;
        providers.start(new Diagnostics.Capture(context.id(), context.startedAt(), context.startedNanos(), context.startTick(), context.seconds()));
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTick(ServerTickEndEvent event) {
        if (active() && context.offsetMillis() >= context.seconds() * 1000L) stop("completed");
    }
    @Override public void stop(String reason) {
        if (!active()) return;
        CaptureContext finished = context;
        var results = providers.stop(reason);
        context = null;
        var report = reports.header(name(), finished, reason, 1);
        report.put("providers", results);
        report.put("complete", reason.equals("completed") && results.stream().allMatch(row -> Boolean.TRUE.equals(row.get("complete"))));
        report.put("notes", "Only providers registered at capture start participate. World filters do not restrict plugin measurements. "
                + "Provider timings can overlap scheduler and other provider timings; do not add inclusive totals. "
                + "No registered providers means no plugin measurements, not zero plugin cost. Provider unload/reload ends that provider's capture early.");
        reports.write(name(), finished, 1, () -> report);
    }
}
