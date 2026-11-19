package com.teamsneakymouse.sneakydiagnostics.timeline;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.DiagnosticCollector;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class TimelineCollector implements DiagnosticCollector, Listener {
    private final ReportSink reports;
    private CaptureContext context;
    private TickTimeline timeline;
    private long overheadNanos, maxOverheadNanos, spawnNanos;
    private SpawnCounts spawns;
    private final AtomicLong spawnsOffThread = new AtomicLong();
    /** Class loader -> owning plugin name ("" when not a plugin's), filled while walking spawn stacks. Server thread. */
    private final Map<ClassLoader, String> pluginByLoader = new IdentityHashMap<>();
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    public TimelineCollector(ReportSink reports) { this.reports = reports; }
    @Override public String name() { return "timeline"; }
    @Override public boolean active() { return context != null; }
    @Override public boolean busy() { return active() || reports.busy(name()); }
    @Override public String status() { return active() ? "Collecting " + context.id() : reports.busy(name()) ? "Writing report" : "Idle"; }
    @Override public void start(CaptureContext context, List<World> worlds) {
        if (busy()) throw new IllegalStateException("Timeline is busy");
        this.context = context;
        timeline = new TickTimeline();
        spawns = new SpawnCounts();
        spawnsOffThread.set(0);
        overheadNanos = maxOverheadNanos = spawnNanos = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTick(ServerTickEndEvent event) {
        if (!active()) return;
        long begin = System.nanoTime();
        long millis = context.offsetMillis();
        // Both counters: the tick number for continuity, the current tick to match scheduler/listener/probe records.
        timeline.record(millis, event.getTickNumber(), Bukkit.getCurrentTick(), event.getTickDuration());
        long overhead = System.nanoTime() - begin;
        overheadNanos += overhead;
        maxOverheadNanos = Math.max(maxOverheadNanos, overhead);
        if (millis >= context.seconds() * 1000L) stop("completed");
    }

    /**
     * Every entity spawn while recording (cancelled ones counted apart), by spawn reason and entity type. For CUSTOM and
     * DEFAULT spawns the spawning plugin is the first plugin-classloader frame on the stack (one stack walk per such
     * spawn, only while recording). Off the server thread spawns are only counted as dropped.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpawn(EntitySpawnEvent event) {
        if (!active()) return;
        if (!Bukkit.isPrimaryThread()) { spawnsOffThread.incrementAndGet(); return; }
        long begin = System.nanoTime();
        var reason = event instanceof CreatureSpawnEvent creature ? creature.getSpawnReason() : event.getEntity().getEntitySpawnReason();
        String name = reason == null ? "<unknown>" : reason.name();
        String plugin = SpawnCounts.ATTRIBUTED.contains(name) ? spawningPlugin() : null;
        spawns.record(context.offsetMillis() / 1000, name, event.getEntityType().name(), plugin, event.isCancelled());
        spawnNanos += System.nanoTime() - begin;
    }

    /** The first plugin on the stack other than SneakyDiagnostics, or "<unknown>". */
    private String spawningPlugin() {
        ClassLoader own = TimelineCollector.class.getClassLoader();
        return WALKER.walk(frames -> frames.limit(256).map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> type.getClassLoader() != null && type.getClassLoader() != own)
                .map(type -> pluginByLoader.computeIfAbsent(type.getClassLoader(), loader -> pluginOf(type)))
                .filter(name -> !name.isEmpty()).findFirst().orElse("<unknown>"));
    }

    /** The plugin whose class loader loaded {@code type} (public API), or "" for server and library classes. */
    private static String pluginOf(Class<?> type) {
        try {
            return JavaPlugin.getProvidingPlugin(type).getName();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "";
        }
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
        report.put("spawns", spawns.export(spawnsOffThread.get()));
        report.put("spawnCollectionNanos", spawnNanos);
        report.put("notes", "Tick-end duration from Paper; excludes remaining tick-end listeners and idle sleep. "
                + "Windows are indexed by tick-end observation offset, not tick start. Missing seconds contain no observed tick ends. "
                + "This measures the whole server, not individual plugin CPU cost. Report dispatch is outside collection timing. "
                + "firstTick/lastTick are Paper tick numbers; firstCurrentTick/lastCurrentTick are Bukkit.getCurrentTick() at the same tick ends, "
                + "the counter other records carry (it also advances while the server pauses when empty, without tick ends). "
                + "spawns: entity spawns while recording by spawn reason and entity type (cancelled ones counted apart, without windows); "
                + "for CUSTOM and DEFAULT spawns plugin is the first plugin class on the spawning stack (a hint: a library or callback "
                + "can stand between), <unknown> when none. windows/n list only the one-second windows with spawns. "
                + "spawnCollectionNanos is the listener's own server-thread cost, outside totalCollectionNanos.");
        context = null;
        timeline = null;
        spawns = null;
        pluginByLoader.clear();     // never keep plugin class loaders past a capture
        reports.write(name(), finished, 1, () -> report);
    }
}
