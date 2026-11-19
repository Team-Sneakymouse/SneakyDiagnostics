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
    /** Per-slice caps per millisecond of slice target (the former 200 entities and 8 chunks per 2 ms slice). */
    static final int ENTITIES_PER_SLICE_MILLI = 100, CHUNKS_PER_SLICE_MILLI = 4;
    static final String VISIT_ORDER = "players-first";
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
        default long nanoTime() { return System.nanoTime(); }
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

    /**
     * The world's loaded chunks, nearest to a human in that world first (horizontal distance in chunks to the nearest
     * one), so a census that runs out of time still covers where the players are. Chunks without a human in the world
     * keep their order. Part of the non-preemptible snapshot step, counted in slice time.
     */
    static Chunk[] nearestPlayersFirst(Chunk[] chunks, String world, List<EntitySample.Human> humans) {
        int[] xs = humans.stream().filter(h -> h.world().equals(world)).mapToInt(h -> (int) Math.floor(h.x()) >> 4).toArray();
        int[] zs = humans.stream().filter(h -> h.world().equals(world)).mapToInt(h -> (int) Math.floor(h.z()) >> 4).toArray();
        if (xs.length == 0 || chunks.length < 2 || chunks.length >= 1 << 21) return chunks;
        long[] order = new long[chunks.length];
        for (int i = 0; i < chunks.length; i++) {
            long nearest = Long.MAX_VALUE;
            int cx = chunks[i].getX(), cz = chunks[i].getZ();
            for (int h = 0; h < xs.length; h++) {
                long dx = cx - (long) xs[h], dz = cz - (long) zs[h];
                nearest = Math.min(nearest, dx * dx + dz * dz);
            }
            order[i] = (Math.min(nearest, (1L << 42) - 1) << 21) | i;      // distance, then the original index
        }
        java.util.Arrays.sort(order);
        Chunk[] sorted = new Chunk[chunks.length];
        for (int i = 0; i < order.length; i++) sorted[i] = chunks[(int) (order[i] & ((1L << 21) - 1))];
        return sorted;
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
        int budgetExhaustedTicks;
        SliceBudget budget;
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
            segmentStartedNanos = environment.nanoTime();
            budget = new SliceBudget(segmentStartedNanos);
            budgetExhaustedTicks = 0;
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
            long begin = environment.nanoTime();
            String completion = null;
            if (begin - segmentStartedNanos >= seconds * 1_000_000_000L) { finish("timeout"); return; }
            long target = budget.next(begin);
            if (target == 0) { budgetExhaustedTicks++; return; }      // this wall second's budget is spent
            long millis = Math.max(1, (target + 999_999L) / 1_000_000L);
            int maxEntities = (int) (ENTITIES_PER_SLICE_MILLI * millis), maxChunks = (int) (CHUNKS_PER_SLICE_MILLI * millis);
            try {
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
                while (environment.nanoTime() - begin < target && examinedThisTick < maxEntities && chunksThisTick < maxChunks) {
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
                        chunks = currentCoverage.snapshotAvailable ? nearestPlayersFirst(currentWorld.getLoadedChunks(), currentWorld.getName(), humans) : new Chunk[0];
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
                long elapsed = environment.nanoTime() - begin;
                budget.used(elapsed);
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
            report.put("elapsedMillis", (environment.nanoTime() - segmentStartedNanos) / 1_000_000);
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
            report.put("budgetMillisPerSecond", SliceBudget.BUDGET_NANOS_PER_SECOND / 1_000_000L);
            report.put("budgetExhaustedTicks", budgetExhaustedTicks);
            report.put("visitOrder", VISIT_ORDER);
            report.put("maxSliceNanos", maxSliceNanos);
            report.put("entityCount", detached.size());
            report.put("uniqueEntitiesAttempted", seen.size());
            report.put("limits", Map.of("entitiesPerSegment", MAX_ENTITIES, "chunksPerSegment", MAX_CHUNKS,
                    "entitiesPerCapture", MAX_CAPTURE_ENTITIES, "durationSeconds", seconds,
                    "targetSliceMillis", SliceBudget.MAX_SLICE_NANOS / 1_000_000L,
                    "entitiesPerTick", ENTITIES_PER_SLICE_MILLI * (SliceBudget.MAX_SLICE_NANOS / 1_000_000L),
                    "chunksPerTick", CHUNKS_PER_SLICE_MILLI * (SliceBudget.MAX_SLICE_NANOS / 1_000_000L),
                    "budgetMillisPerSecond", SliceBudget.BUDGET_NANOS_PER_SECOND / 1_000_000L));
            report.put("notes", "Incremental census, not a simultaneous snapshot. Slice time is budgeted per wall second (budgetMillisPerSecond, slices target at most targetSliceMillis; "
                    + "budgetExhaustedTicks ran no slice) and chunks nearest to humans are visited first within each world (visitOrder). "
                    + "This file contains only this segment's entities/groups/sections; "
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
