package com.teamsneakymouse.sneakydiagnostics.entity;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class EntityCensusCollector implements AutoCloseable {
    static final int MAX_ENTITIES = 100_000;
    static final int MAX_CHUNKS = 30_000;
    static final long MAX_DURATION_NANOS = 120_000_000_000L;
    static final long SLICE_BUDGET_NANOS = 2_000_000L;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "SneakyDiagnostics-entity-report");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean writing;
    private Scan scan;

    private final JavaPlugin plugin;

    public EntityCensusCollector(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public void close() {
        if (scan != null) scan.finish("plugin_disable");
        writer.shutdown();
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("sneaky.diagnostics.entities") && !sender.hasPermission("sneaky.entitycensus")) {
            sender.sendMessage("You do not have permission to collect an entity census.");
            return true;
        }
        String usage = command.getName().equalsIgnoreCase("entitycensus") ? "/entitycensus" : "/" + label + " entities";
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "start" -> {
                if (scan != null || writing) { sender.sendMessage("A census or report write is already in progress."); break; }
                if (args.length > 2) { sender.sendMessage("Usage: " + usage + " start [world]"); break; }
                List<World> worlds;
                if (args.length == 2) {
                    World world = Bukkit.getWorld(args[1]);
                    if (world == null) { sender.sendMessage("Unknown or unloaded world: " + args[1]); break; }
                    worlds = List.of(world);
                } else worlds = Bukkit.getWorlds();
                scan = new Scan(worlds);
                scan.task = Bukkit.getScheduler().runTaskTimer(plugin, scan, 1, 1);
                sender.sendMessage("Census started. Loaded chunks only, 2 ms target per tick, 120-second limit. Report path is logged on completion.");
            }
            case "stop" -> {
                if (scan == null) sender.sendMessage("No census running.");
                else { scan.finish("manual_stop"); sender.sendMessage("Stopped; partial report queued."); }
            }
            case "status" -> sender.sendMessage(scan != null ? "Collecting: " + scan.samples.size()
                    + " entities, " + scan.visitedChunks + " chunks visited." : writing ? "Writing report." : "Idle.");
            default -> sender.sendMessage("Usage: " + usage + " <start [world]|status|stop>");
        }
        return true;
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("sneaky.diagnostics.entities") && !sender.hasPermission("sneaky.entitycensus")) return List.of();
        if (args.length == 1) return List.of("start", "status", "stop").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) return Bukkit.getWorlds().stream()
                .map(World::getName).filter(s -> s.startsWith(args[1])).toList();
        return List.of();
    }

    private final class Scan implements Runnable {
        final long startedNanos = System.nanoTime();
        final String startedAt = Instant.now().toString();
        final int startTick = Bukkit.getCurrentTick();
        final double startMspt = Bukkit.getAverageTickTime();
        final Iterator<World> worlds;
        final List<EntitySample> samples = new ArrayList<>();
        final Set<UUID> seen = new HashSet<>();
        Chunk[] chunks = new Chunk[0];
        Entity[] entities = new Entity[0];
        World currentWorld;
        int chunkIndex, entityIndex, visitedChunks, skippedChunks, invalidEntities, errors, duplicateEntities;
        long totalSliceNanos, maxSliceNanos;
        int slices;
        boolean finished;
        BukkitTask task;

        Scan(List<World> worlds) { this.worlds = List.copyOf(worlds).iterator(); }

        @Override public void run() {
            long begin = System.nanoTime();
            String completion = null;
            try {
                if (begin - startedNanos >= MAX_DURATION_NANOS) { completion = "timeout"; return; }
                List<EntitySample.Human> humans = new ArrayList<>();
                Set<UUID> humanIds = new HashSet<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.hasMetadata("NPC")) continue;
                    var location = player.getLocation();
                    humanIds.add(player.getUniqueId());
                    humans.add(new EntitySample.Human(player.getUniqueId(), player.getWorld().getName(),
                            location.getX(), location.getZ()));
                }
                int examinedThisTick = 0, chunksThisTick = 0;
                while (System.nanoTime() - begin < SLICE_BUDGET_NANOS && examinedThisTick < 200 && chunksThisTick < 8) {
                    if (seen.size() >= MAX_ENTITIES) { completion = "entity_limit"; break; }
                    if (entityIndex < entities.length) {
                        Entity entity = entities[entityIndex++];
                        examinedThisTick++;
                        if (!entity.isValid()) { invalidEntities++; continue; }
                        if (!seen.add(entity.getUniqueId())) { duplicateEntities++; continue; }
                        try {
                            samples.add(EntitySample.read(entity, humans, humanIds,
                                    (System.nanoTime() - startedNanos) / 1_000_000));
                        } catch (RuntimeException e) {
                            errors++;
                            if (errors == 1) plugin.getLogger().log(java.util.logging.Level.WARNING, "[ENTITY-CENSUS] Entity read failed", e);
                        }
                        continue;
                    }
                    entities = new Entity[0];
                    entityIndex = 0;
                    if (chunkIndex >= chunks.length) {
                        if (!worlds.hasNext()) { completion = "completed"; break; }
                        currentWorld = worlds.next();
                        // This one API call is not preemptible; its cost is included in slice telemetry.
                        chunks = Bukkit.getWorld(currentWorld.getUID()) == currentWorld
                                ? currentWorld.getLoadedChunks() : new Chunk[0];
                        chunkIndex = 0;
                        continue;
                    }
                    if (visitedChunks >= MAX_CHUNKS) { completion = "chunk_limit"; break; }
                    Chunk chunk = chunks[chunkIndex++];
                    visitedChunks++;
                    chunksThisTick++;
                    if (Bukkit.getWorld(currentWorld.getUID()) != currentWorld || !chunk.isLoaded() || !chunk.isEntitiesLoaded()) {
                        skippedChunks++;
                        continue;
                    }
                    // No getChunkAt, load, chunk tickets, or world-wide entity scan.
                    entities = chunk.getEntities();
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "[ENTITY-CENSUS] Scan failed", e);
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
            if (finished) return;
            finished = true;
            if (task != null) task.cancel();
            List<EntitySample> detached = List.copyOf(samples);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schemaVersion", 1);
            report.put("startedAt", startedAt);
            report.put("finishedAt", Instant.now().toString());
            report.put("reason", reason);
            report.put("complete", reason.equals("completed") && errors == 0 && skippedChunks == 0);
            report.put("serverVersion", Bukkit.getVersion());
            report.put("elapsedMillis", (System.nanoTime() - startedNanos) / 1_000_000);
            report.put("serverTicks", Bukkit.getCurrentTick() - startTick);
            report.put("startMspt", startMspt);
            report.put("endMspt", Bukkit.getAverageTickTime());
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
            report.put("limits", Map.of("entities", MAX_ENTITIES, "chunks", MAX_CHUNKS,
                    "durationSeconds", 120, "targetSliceMillis", 2, "entitiesPerTick", 200, "chunksPerTick", 8));
            report.put("notes", "Incremental census, not a simultaneous snapshot. Nearby humans use horizontal distance. "
                    + "Owner hints are namespaces/metadata providers, not proven creators. Tracking counts are current subscriptions, not CPU cost. "
                    + "Labels capped at 64 entries and 128 characters; player names, human UUIDs, PDC values and display text are omitted.");
            report.put("entities", detached);
            chunks = new Chunk[0]; entities = new Entity[0]; seen.clear(); samples.clear();
            scan = null;
            writing = true;
            Path output = plugin.getDataFolder().toPath().resolve("reports")
                    .resolve("entities-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".json");
            writer.execute(() -> {
                try {
                    report.put("groups", CensusSummary.summarize(detached));
                    Files.createDirectories(output.getParent());
                    try (var out = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW)) {
                        new GsonBuilder().setPrettyPrinting().create().toJson(report, out);
                    }
                    plugin.getLogger().info("[ENTITY-CENSUS] Saved " + output.toAbsolutePath() + " (" + reason + ", " + detached.size() + " entities)");
                } catch (IOException | RuntimeException e) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "[ENTITY-CENSUS] Cannot save " + output, e);
                } finally { writing = false; }
            });
        }
    }
}
