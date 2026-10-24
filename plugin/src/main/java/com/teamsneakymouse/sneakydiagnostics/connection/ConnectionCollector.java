package com.teamsneakymouse.sneakydiagnostics.connection;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.DiagnosticCollector;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import com.teamsneakymouse.sneakydiagnostics.timeline.TickTimeline;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** All Bukkit reads and recorder access stay on the server thread. */
public final class ConnectionCollector implements DiagnosticCollector, Listener {
    private final ReportSink reports;
    private final Supplier<? extends Collection<? extends Player>> players;
    private final Function<UUID, Player> lookup;
    private CaptureContext context;
    private ConnectionCapture capture;
    private TickTimeline ticks;
    private long totalNanos, maxNanos;

    public ConnectionCollector(ReportSink reports) { this(reports, Bukkit::getOnlinePlayers, Bukkit::getPlayer); }
    public ConnectionCollector(ReportSink reports, Supplier<? extends Collection<? extends Player>> players) {
        this(reports, players, id -> null);
    }
    public ConnectionCollector(ReportSink reports, Supplier<? extends Collection<? extends Player>> players, Function<UUID, Player> lookup) {
        this.reports = reports;
        this.players = players;
        this.lookup = lookup;
    }
    @Override public String name() { return "connections"; }
    @Override public boolean active() { return context != null; }
    @Override public boolean busy() { return active() || reports.busy(name()); }
    @Override public String status() { return active() ? "Collecting " + context.id() : reports.busy(name()) ? "Writing report" : "Idle"; }
    @Override public void start(CaptureContext context, List<World> worlds) {
        start(context, worlds, List.of());
    }
    public void start(CaptureContext context, List<World> worlds, List<UUID> targets) {
        if (busy()) throw new IllegalStateException("Connections is busy");
        var recorder = new ConnectionCapture(context.seconds(), targets);
        this.context = context;
        capture = recorder;
        ticks = new TickTimeline();
        totalNanos = maxNanos = 0;
        long begin = System.nanoTime();
        sample(context.offsetMillis());
        totalNanos = maxNanos = System.nanoTime() - begin;
    }
    private void sample(long offset) {
        if (capture.due(offset)) {
            capture.sample(offset, players.get(),
                    player -> new ConnectionCapture.Observation(player.getUniqueId(), player.getPing(), null), lookup);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTick(ServerTickEndEvent event) {
        if (!active()) return;
        long begin = System.nanoTime();
        long offset = context.offsetMillis();
        if (capture.expired(offset)) { stop("completed"); return; }
        ticks.record(offset, event.getTickNumber(), event.getTickDuration());
        try { sample(offset); }
        catch (RuntimeException e) { stop("collection_error"); return; }
        long overhead = System.nanoTime() - begin;
        totalNanos += overhead;
        maxNanos = Math.max(maxNanos, overhead);
    }
    @Override public void stop(String reason) {
        if (!active()) return;
        var finished = context;
        var report = reports.header(name(), finished, reason, 1);
        report.putAll(capture.report(reason));
        report.put("platform", "paper");
        report.put("tickWindows", ticks.finish());
        report.put("tickNotes", "Paper tick-end durations; sampling runs on the server thread. Stalls delay samples and stop. "
                + "No new samples begin after the capture deadline; API reads cannot be preempted. Tick windows omit ends observed after the deadline. "
                + "Behind a proxy this ping is not an isolated measurement of the backend-to-proxy hop.");
        report.put("totalCollectionNanos", totalNanos);
        report.put("maxCollectionNanos", maxNanos);
        context = null;
        capture = null;
        ticks = null;
        reports.write(name(), finished, 1, () -> report);
    }
}
