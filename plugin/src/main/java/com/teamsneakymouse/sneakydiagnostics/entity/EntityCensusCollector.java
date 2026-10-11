package com.teamsneakymouse.sneakydiagnostics.entity;

import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.DiagnosticCollector;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Bounded incremental census. Pause keeps the traversal cursor; each segment writes detached samples. */
public final class EntityCensusCollector implements DiagnosticCollector {
    static final int MAX_ENTITIES = 100_000, MAX_CHUNKS = 30_000, MAX_CAPTURE_ENTITIES = 500_000;
    static final long SLICE_BUDGET_NANOS = 2_000_000L;
    private final Plugin plugin;
    private final ReportSink reports;
    private final Environment environment;
    private Scan scan;

    interface Environment {
        World world(UUID id);
        Collection<? extends Player> players();
        int currentTick();
        double averageTickTime();
        BukkitTask repeat(Runnable task);
    }

    public EntityCensusCollector(Plugin plugin, ReportSink reports) {
        this(plugin, reports, new Environment() {
            @Override public World world(UUID id) { return Bukkit.getWorld(id); }
            @Override public Collection<? extends Player> players() { return Bukkit.getOnlinePlayers(); }
            @Override public int currentTick() { return Bukkit.getCurrentTick(); }
            @Override public double averageTickTime() { return Bukkit.getAverageTickTime(); }
            @Override public BukkitTask repeat(Runnable task) { return Bukkit.getScheduler().runTaskTimer(plugin, task, 1, 1); }
        });
    }

    EntityCensusCollector(Plugin plugin, ReportSink reports, Environment environment) {
        this.plugin = plugin;
        this.reports = reports;
        this.environment = environment;
    }
    @Override public String name() { return "entities"; }
    @Override public boolean active() { return scan != null && scan.running; }
    @Override public boolean busy() { return active() || reports.busy(name()); }
    public boolean resumable() { return scan != null && !scan.running; }
    @Override public String status() {
        if (reports.busy(name())) return "Writing report";
        if (scan == null) return "Idle";
        return (scan.running ? "Collecting " : "Paused; resume available for ") + scan.context.id()
                + ": " + scan.seen.size() + " unique attempts, segment " + scan.segment;
    }

    @Override public void start(CaptureContext context, List<World> worlds) {
        if (busy()) throw new IllegalStateException("Entity census is busy");
        scan = new Scan(context, worlds);
        scan.begin(context.seconds());
    }

    public void resume(int seconds) {
        if (busy() || !resumable()) throw new IllegalStateException("No paused census ready to resume");
        scan.begin(seconds);
    }

    @Override public void stop(String reason) {
        if (active()) scan.finish(reason);
        if (reason.equals("plugin_disable") || reason.equals("start_error")) scan = null;
    }

    private static final class Coverage {
        final String world;
        int snapshotChunks = -1, visitedChunks, completedChunks, skippedChunks;
        boolean snapshotAvailable;
        long attemptedEntities, sampledEntities;
        Coverage(String world) { this.world = world; }
        CoverageRow row() {
            return new CoverageRow(world, snapshotAvailable, snapshotChunks, visitedChunks, completedChunks, skippedChunks,
                    snapshotChunks < 0 ? -1 : snapshotChunks - visitedChunks,
                    visitedChunks - completedChunks - skippedChunks, attemptedEntities, sampledEntities);
        }
    }
    record CoverageRow(String world, boolean snapshotAvailable, int snapshotChunks, int visitedChunks, int completedChunks, int skippedChunks,
                       int unvisitedChunks, int partialChunks, long attemptedEntities, long sampledEntities) {}

    private final class Scan implements Runnable {
        final CaptureContext context;
        final List<World> worlds;
        final List<Coverage> coverage;
        final List<EntitySample> samples = new ArrayList<>();
        final Set<UUID> seen = new HashSet<>();
        Chunk[] chunks = new Chunk[0];
        Entity[] entities = new Entity[0];
        World currentWorld;
        Coverage currentCoverage;
        Chunk currentChunk;
        int worldIndex, chunkIndex, entityIndex, segment;
        int visitedChunks, skippedChunks, invalidEntities, errors, duplicateEntities, attemptedEntities;
        long totalSliceNanos, maxSliceNanos, segmentStartedNanos;
        int slices, seconds, startTick;
        String startedAt;
        double startMspt;
        boolean running;
        BukkitTask task;

        Scan(CaptureContext context, List<World> worlds) {
            this.context = context;
            this.worlds = List.copyOf(worlds);
            coverage = worlds.stream().map(world -> new Coverage(world.getName())).toList();
        }

        void begin(int seconds) {
            this.seconds = seconds;
            segment++;
            segmentStartedNanos = System.nanoTime();
            startedAt = Instant.now().toString();
            startTick = environment.currentTick();
            startMspt = environment.averageTickTime();
            visitedChunks = skippedChunks = invalidEntities = errors = duplicateEntities = attemptedEntities = slices = 0;
            totalSliceNanos = maxSliceNanos = 0;
            running = true;
            try { task = environment.repeat(this); }
            catch (RuntimeException e) { running = false; throw e; }
        }

        @Override public void run() {
            long begin = System.nanoTime();
            String completion = null;
            try {
                if (begin - segmentStartedNanos >= seconds * 1_000_000_000L) { completion = "timeout"; return; }
                if (currentChunk != null && (environment.world(currentWorld.getUID()) != currentWorld
                        || !currentChunk.isLoaded() || !currentChunk.isEntitiesLoaded())) {
                    currentCoverage.skippedChunks++;
                    skippedChunks++;
                    currentChunk = null;
                    entities = new Entity[0];
                    entityIndex = 0;
                }
                List<EntitySample.Human> humans = new ArrayList<>();
                Set<UUID> humanIds = new HashSet<>();
                for (Player player : environment.players()) {
                    if (player.hasMetadata("NPC")) continue;
                    var location = player.getLocation();
                    humanIds.add(player.getUniqueId());
                    humans.add(new EntitySample.Human(player.getUniqueId(), player.getWorld().getName(), location.getX(), location.getZ()));
                }
                int examinedThisTick = 0, chunksThisTick = 0;
                while (System.nanoTime() - begin < SLICE_BUDGET_NANOS && examinedThisTick < 200 && chunksThisTick < 8) {
                    if (seen.size() >= MAX_CAPTURE_ENTITIES) { completion = "capture_entity_limit"; break; }
                    if (attemptedEntities >= MAX_ENTITIES) { completion = "entity_limit"; break; }
                    if (entityIndex < entities.length) {
                        Entity entity = entities[entityIndex++];
                        examinedThisTick++;
                        if (!entity.isValid()) { invalidEntities++; continue; }
                        if (!seen.add(entity.getUniqueId())) { duplicateEntities++; continue; }
                        attemptedEntities++;
                        currentCoverage.attemptedEntities++;
                        try {
                            samples.add(EntitySample.read(entity, humans, humanIds, context.offsetMillis()));
                            currentCoverage.sampledEntities++;
                        } catch (RuntimeException e) {
                            errors++;
                            if (errors == 1) plugin.getLogger().log(Level.WARNING, "Entity read failed", e);
                        }
                        continue;
                    }
                    if (currentChunk != null) {
                        currentCoverage.completedChunks++;
                        currentChunk = null;
                    }
                    entities = new Entity[0];
                    entityIndex = 0;
                    if (chunkIndex >= chunks.length) {
                        if (worldIndex >= worlds.size()) { completion = "completed"; break; }
                        currentWorld = worlds.get(worldIndex);
                        currentCoverage = coverage.get(worldIndex++);
                        // Snapshot creation is not preemptible; its cost is included in slice telemetry.
                        currentCoverage.snapshotAvailable = environment.world(currentWorld.getUID()) == currentWorld;
                        chunks = currentCoverage.snapshotAvailable ? currentWorld.getLoadedChunks() : new Chunk[0];
                        currentCoverage.snapshotChunks = chunks.length;
                        chunkIndex = 0;
                        continue;
                    }
                    if (visitedChunks >= MAX_CHUNKS) { completion = "chunk_limit"; break; }
                    Chunk chunk = chunks[chunkIndex++];
                    visitedChunks++;
                    currentCoverage.visitedChunks++;
                    chunksThisTick++;
                    if (environment.world(currentWorld.getUID()) != currentWorld || !chunk.isLoaded() || !chunk.isEntitiesLoaded()) {
                        skippedChunks++;
                        currentCoverage.skippedChunks++;
                        continue;
                    }
                    currentChunk = chunk;
                    // No getChunkAt, load, tickets, or world-wide entity scan.
                    entities = chunk.getEntities();
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Entity scan failed", e);
                completion = "scan_error";
            } finally {
                long elapsed = System.nanoTime() - begin;
                totalSliceNanos += elapsed;
                maxSliceNanos = Math.max(maxSliceNanos, elapsed);
                slices++;
                if (completion != null) finish(completion);
            }
        }

        void finish(String reason) {
            if (!running) return;
            running = false;
            if (task != null) task.cancel();
            task = null;
            List<EntitySample> detached = List.copyOf(samples);
            var report = reports.header(name(), context, reason, segment);
            boolean canResume = Set.of("timeout", "manual_stop", "entity_limit", "chunk_limit").contains(reason)
                    && seen.size() < MAX_CAPTURE_ENTITIES;
            report.put("startedAt", startedAt);
            report.put("resumable", canResume);
            report.put("complete", reason.equals("completed") && coverage.stream().allMatch(row -> row.snapshotAvailable && row.skippedChunks == 0)
                    && coverage.stream().mapToLong(row -> row.attemptedEntities).sum()
                    == coverage.stream().mapToLong(row -> row.sampledEntities).sum());
            report.put("coverage", coverage.stream().map(Coverage::row).toList());
            report.put("elapsedMillis", (System.nanoTime() - segmentStartedNanos) / 1_000_000);
            report.put("segmentDurationSeconds", seconds);
            report.put("serverTicks", environment.currentTick() - startTick);
            report.put("startMspt", startMspt);
            report.put("endMspt", environment.averageTickTime());
            report.put("visitedChunks", visitedChunks);
            report.put("skippedChunks", skippedChunks);
            report.put("invalidEntities", invalidEntities);
            report.put("duplicateEntities", duplicateEntities);
            report.put("errors", errors);
            report.put("slices", slices);
            report.put("totalSliceNanos", totalSliceNanos);
            report.put("maxSliceNanos", maxSliceNanos);
            report.put("entityCount", detached.size());
            report.put("uniqueEntitiesAttempted", seen.size());
            report.put("limits", Map.of("entitiesPerSegment", MAX_ENTITIES, "chunksPerSegment", MAX_CHUNKS,
                    "entitiesPerCapture", MAX_CAPTURE_ENTITIES, "durationSeconds", seconds,
                    "targetSliceMillis", 2, "entitiesPerTick", 200, "chunksPerTick", 8));
            report.put("notes", "Incremental census, not a simultaneous snapshot. This file contains only this segment's entities/groups/sections; "
                    + "coverage and uniqueEntitiesAttempted are cumulative across resumed segments. complete describes traversal coverage across those segments. "
                    + "snapshotChunks=-1 means world not reached; unvisited/partial chunks expose unfinished coverage. "
                    + "Resume retains the original traversal and deduplication set, not an immutable world snapshot. New entities in already processed chunks are not revisited. "
                    + "Observation offsets are relative to captureStartedAt, including gaps between segments. Nearby humans use horizontal distance. "
                    + "Owner hints are not proven creators; tracking subscriptions are not CPU cost. "
                    + "Labels capped at 64 entries and 128 characters; player names, human UUIDs, PDC values and display text are omitted.");
            report.put("entities", detached);
            samples.clear();
            int finishedSegment = segment;
            reports.write(name(), context, finishedSegment, () -> {
                report.put("groups", CensusSummary.summarize(detached));
                report.put("sections", CensusSummary.sections(detached));
                return report;
            });
            if (!canResume) scan = null;
        }
    }
}
