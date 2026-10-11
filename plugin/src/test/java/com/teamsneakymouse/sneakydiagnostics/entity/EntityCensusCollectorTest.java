package com.teamsneakymouse.sneakydiagnostics.entity;

import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityCensusCollectorTest {
    @Test void resumesMidChunkWithSameIdentityAndNoRepeatedEntities() {
        var fixture = new Fixture();
        fixture.start();
        fixture.runUntilSampled();
        fixture.collector.stop("manual_stop");
        assertTrue(fixture.collector.resumable());
        var first = fixture.reports.getFirst();
        assertEquals(false, first.get("complete"));
        var partial = (EntityCensusCollector.CoverageRow) ((List<?>) first.get("coverage")).getFirst();
        assertEquals(1, partial.partialChunks());
        assertEquals(0, partial.completedChunks());
        fixture.collector.resume(120);
        fixture.runUntilFinished();
        assertFalse(fixture.collector.resumable());
        var second = fixture.reports.getLast();
        assertEquals(first.get("captureId"), second.get("captureId"));
        assertEquals(2, second.get("segment"));
        assertEquals(true, second.get("complete"));
        Set<String> ids = new HashSet<>();
        int samples = 0;
        for (var report : fixture.reports) for (Object value : (List<?>) report.get("entities")) {
            assertTrue(ids.add(((EntitySample) value).entityId()));
            samples++;
        }
        assertEquals(1000, samples);
        var complete = (EntityCensusCollector.CoverageRow) ((List<?>) second.get("coverage")).getFirst();
        assertEquals(1, complete.completedChunks());
        assertEquals(0, complete.partialChunks());
        assertEquals(1000, complete.sampledEntities());
    }

    @Test void unloadingPausedChunkDoesNotReadRemainingEntitiesAndMarksCoverageIncomplete() {
        var fixture = new Fixture();
        fixture.start();
        fixture.runUntilSampled();
        fixture.collector.stop("manual_stop");
        int reads = fixture.reads;
        fixture.chunkLoaded = false;
        fixture.collector.resume(120);
        fixture.runUntilFinished();
        assertEquals(reads, fixture.reads);
        var report = fixture.reports.getLast();
        assertEquals(false, report.get("complete"));
        var coverage = (EntityCensusCollector.CoverageRow) ((List<?>) report.get("coverage")).getFirst();
        assertEquals(1, coverage.skippedChunks());
        assertEquals(0, coverage.partialChunks());
    }

    @Test void worldRemovedBeforeSnapshotCannotProduceCompleteReport() {
        var fixture = new Fixture();
        fixture.worldLoaded = false;
        fixture.start();
        fixture.runUntilFinished();
        assertEquals(0, fixture.reads);
        assertEquals(false, fixture.reports.getFirst().get("complete"));
        var coverage = (EntityCensusCollector.CoverageRow) ((List<?>) fixture.reports.getFirst().get("coverage")).getFirst();
        assertFalse(coverage.snapshotAvailable());
    }

    private static final class Fixture implements EntityCensusCollector.Environment, ReportSink {
        final UUID worldId = UUID.randomUUID();
        final List<Map<String, Object>> reports = new ArrayList<>();
        final World world;
        final Chunk chunk;
        final Entity[] entities = new Entity[1000];
        final EntityCensusCollector collector;
        boolean chunkLoaded = true, worldLoaded = true;
        int reads, tick;
        Runnable scheduled;

        Fixture() {
            chunk = stub(Chunk.class, (method, args) -> switch (method) {
                case "isLoaded", "isEntitiesLoaded" -> chunkLoaded;
                case "getEntities" -> entities;
                default -> unexpected(method);
            });
            world = stub(World.class, (method, args) -> switch (method) {
                case "getName" -> "world";
                case "getUID" -> worldId;
                case "getLoadedChunks" -> new Chunk[]{chunk};
                default -> unexpected(method);
            });
            var pdc = stub(PersistentDataContainer.class, (method, args) -> method.equals("getKeys") ? Set.of() : unexpected(method));
            for (int i = 0; i < entities.length; i++) {
                UUID id = UUID.randomUUID();
                entities[i] = stub(Entity.class, (method, args) -> switch (method) {
                    case "getUniqueId" -> id;
                    case "getWorld" -> world;
                    case "getLocation" -> { reads++; yield new Location(world, 0, 64, 0); }
                    case "isValid", "isPersistent", "isTicking", "isVisibleByDefault" -> true;
                    case "hasMetadata" -> false;
                    case "getType" -> EntityType.MARKER;
                    case "getTicksLived" -> 1;
                    case "getPersistentDataContainer" -> pdc;
                    case "getScoreboardTags", "getTrackedBy" -> Set.of();
                    case "getPassengers" -> List.of();
                    case "getVehicle" -> null;
                    default -> unexpected(method);
                });
            }
            Plugin plugin = stub(Plugin.class, (method, args) -> {
                if (method.equals("getLogger")) return java.util.logging.Logger.getLogger("test");
                return unexpected(method);
            });
            collector = new EntityCensusCollector(plugin, this, this);
        }

        void start() { collector.start(new CaptureContext("capture-id", "2026-10-11T00:00:00Z", System.nanoTime(), tick, 120), List.of(world)); }
        void runUntilSampled() {
            for (int i = 0; i < 100 && reads == 0; i++) runTick();
            assertTrue(reads > 0 && reads < entities.length);
        }
        void runUntilFinished() {
            for (int i = 0; i < 1000 && collector.active(); i++) runTick();
            assertFalse(collector.active());
        }
        void runTick() { tick++; Runnable task = scheduled; if (task != null) task.run(); }
        @Override public World world(UUID id) { return worldLoaded && id.equals(worldId) ? world : null; }
        @Override public Collection<? extends Player> players() { return List.of(); }
        @Override public int currentTick() { return tick; }
        @Override public double averageTickTime() { return 1; }
        @Override public BukkitTask repeat(Runnable task) {
            scheduled = task;
            return stub(BukkitTask.class, (method, args) -> {
                if (method.equals("cancel")) { scheduled = null; return null; }
                return unexpected(method);
            });
        }
        @Override public boolean busy(String collector) { return false; }
        @Override public Map<String, Object> header(String collector, CaptureContext context, String reason, int segment) {
            return new HashMap<>(Map.of("captureId", context.id(), "reason", reason, "segment", segment));
        }
        @Override public void write(String collector, CaptureContext context, int segment, Supplier<Map<String, Object>> report) {
            reports.add(report.get());
        }
    }

    interface Calls { Object call(String method, Object[] args); }
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Calls calls) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> calls.call(method.getName(), args));
    }
    private static Object unexpected(String method) { throw new AssertionError("Unexpected call (including any chunk load): " + method); }
}
