package com.teamsneakymouse.sneakydiagnostics.integration;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.teamsneakymouse.sneakydiagnostics.api.Diagnostics;
import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.DiagnosticCollector;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Coordinates all providers as one collector; writing always belongs to diagnostics. */
public final class PluginCollector implements DiagnosticCollector, Listener {
    private final ProviderRegistry providers;
    private final ReportSink reports;
    private CaptureContext context;
    private List<String> selection = List.of();

    public PluginCollector(ProviderRegistry providers, ReportSink reports) { this.providers = providers; this.reports = reports; }
    @Override public String name() { return "plugins"; }
    @Override public boolean active() { return context != null; }
    @Override public boolean busy() { return active() || reports.busy(name()); }
    @Override public String status() {
        return (active() ? "Collecting " + context.id() + " (providers: " + (selection.isEmpty() ? "all" : String.join(", ", selection)) + ")"
                : reports.busy(name()) ? "Writing report" : "Idle")
                + "; registered probes: " + (providers.providers().isEmpty() ? "none" : String.join(", ", providers.providers()));
    }
    @Override public void start(CaptureContext context, List<World> worlds) { start(context, worlds, List.of()); }

    /** {@code selection} holds exact registered names; empty selects every registered provider. */
    public void start(CaptureContext context, List<World> worlds, List<String> selection) {
        if (busy()) throw new IllegalStateException("Plugin collector is busy");
        this.context = context;
        this.selection = List.copyOf(selection);
        providers.start(new Diagnostics.Capture(context.id(), context.startedAt(), context.startedNanos(), context.startTick(), context.seconds()), this.selection);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTick(ServerTickEndEvent event) {
        if (active() && context.offsetMillis() >= context.seconds() * 1000L) stop("completed");
    }
    @Override public void stop(String reason) {
        if (!active()) return;
        CaptureContext finished = context;
        var results = providers.stop(reason);
        var chosen = selection;
        context = null;
        selection = List.of();
        var slugs = ProviderFiles.slugs(results.stream().map(row -> (String) row.get("plugin")).toList());
        var files = new ArrayList<ReportSink.ReportFile>();
        var entries = new ArrayList<Map<String, Object>>();
        var incomplete = new ArrayList<String>();
        boolean normal = reason.equals("completed");
        for (int i = 0; i < results.size(); i++) {
            var row = results.get(i);
            String file = ReportSink.fileName(name(), slugs.get(i), 1);
            boolean complete = Boolean.TRUE.equals(row.get("complete"));
            // A provider that failed to start or stop breaks the coordination; merely partial coverage does not.
            normal &= Boolean.TRUE.equals(row.get("started")) && row.get("error") == null;
            if (!complete) incomplete.add((String) row.get("plugin"));
            var entry = new LinkedHashMap<String, Object>();
            for (String key : List.of("plugin", "pluginVersion", "instrumentationVersion", "started", "reason", "complete")) entry.put(key, row.get(key));
            entry.put("file", file);
            entry.put("error", row.get("error"));
            entries.add(entry);
            // Provider fields replace the diagnostics plugin's pluginVersion and the collector's reason in the standard header.
            var detail = reports.header(name(), finished, reason, 1);
            for (String key : List.of("plugin", "pluginVersion", "instrumentationVersion", "reason", "complete")) detail.put(key, row.get(key));
            detail.put("error", row.get("error"));
            if (row.containsKey("measurements")) detail.put("measurements", row.get("measurements"));
            files.add(new ReportSink.ReportFile(slugs.get(i), () -> detail));
        }
        var index = reports.header(name(), finished, reason, 1);
        index.put("selected", chosen.isEmpty() ? "all" : chosen);
        index.put("providers", entries);
        index.put("incompleteProviders", incomplete);
        index.put("complete", normal);
        index.put("notes", "Only providers registered at capture start participate. World filters do not restrict plugin measurements. "
                + "Provider timings can overlap scheduler and other provider timings; do not add inclusive totals. "
                + "No registered providers means no plugin measurements, not zero plugin cost. Provider unload/reload ends that provider's capture early.");
        files.addFirst(new ReportSink.ReportFile(null, () -> index));
        reports.write(name(), finished, 1, files);
    }
}
