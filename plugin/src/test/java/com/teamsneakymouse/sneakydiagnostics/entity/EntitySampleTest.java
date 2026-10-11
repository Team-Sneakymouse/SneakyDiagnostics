package com.teamsneakymouse.sneakydiagnostics.entity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.lang.reflect.Proxy;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntitySampleTest {
    @Test void readsDetachedLabelsAndTrackingWithoutLoadingChunksOrReadingPdcValues() {
        UUID humanId = UUID.randomUUID();
        World world = stub(World.class, Map.of("getName", "world"));
        Player human = stub(Player.class, Map.of("getUniqueId", humanId));
        var pdc = stub(PersistentDataContainer.class, Map.of("getKeys", Set.of(new NamespacedKey("testplugin", "owner"))));
        Display entity = stub(Display.class, Map.ofEntries(
                Map.entry("getUniqueId", UUID.randomUUID()), Map.entry("getWorld", world),
                Map.entry("getLocation", new Location(world, -1, 64, -17)),
                Map.entry("getPersistentDataContainer", pdc), Map.entry("getScoreboardTags", Set.of("decoration")),
                Map.entry("hasMetadata", false), Map.entry("getTrackedBy", Set.of(human)),
                Map.entry("getViewRange", 2.0f), Map.entry("getDisplayWidth", 1.0f), Map.entry("getDisplayHeight", 1.0f),
                Map.entry("getInterpolationDuration", 3), Map.entry("getTeleportDuration", 1),
                Map.entry("getType", EntityType.TEXT_DISPLAY), Map.entry("isPersistent", true),
                Map.entry("isTicking", true), Map.entry("isVisibleByDefault", true),
                Map.entry("getTicksLived", 123), Map.entry("getPassengers", List.of())));
        var sample = EntitySample.read(entity, List.of(new EntitySample.Human(humanId, "world", 0, 0)), Set.of(humanId), 12);
        assertEquals(-1, sample.chunkX());
        assertEquals(-2, sample.chunkZ());
        assertEquals(1, sample.trackedHumans());
        assertEquals(List.of("pdc:testplugin"), sample.ownerHints());
        assertEquals(List.of("testplugin:owner"), sample.pdcKeys());
        assertEquals(2.0f, sample.display().viewRange());
        assertFalse(sample.labelsTruncated());
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Map<String, Object> methods) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("getVehicle")) return null;
            if (methods.containsKey(method.getName())) return methods.get(method.getName());
            throw new AssertionError("Unexpected API call: " + type.getSimpleName() + "." + method.getName());
        });
    }

    @Test void nearbyCountsAreNestedAndExcludeSelfAndOtherWorlds() {
        UUID self = UUID.randomUUID();
        var humans = List.of(new EntitySample.Human(self, "world", 0, 0),
                new EntitySample.Human(UUID.randomUUID(), "world", 32, 0),
                new EntitySample.Human(UUID.randomUUID(), "world", 64, 0),
                new EntitySample.Human(UUID.randomUUID(), "world", 128, 0),
                new EntitySample.Human(UUID.randomUUID(), "elsewhere", 0, 0));
        assertArrayEquals(new int[]{1, 2, 3}, EntitySample.nearby(0, 0, "world", self, humans));
    }

    @Test void aggregationCountsSubscriptionsNotUniqueViewers() {
        var first = sample("a", 3);
        var second = sample("b", 0);
        var rows = CensusSummary.summarize(List.of(first, second));
        assertEquals(1, rows.size());
        assertEquals(2, rows.getFirst().entities());
        assertEquals(3, rows.getFirst().trackedHumanSubscriptions());
        assertEquals(1, rows.getFirst().untrackedEntities());
    }

    private EntitySample sample(String id, int tracked) {
        return new EntitySample(id, "world", -1, -2, -1, 60, -17, "ITEM_DISPLAY", false, false,
                true, true, true, 100, tracked, tracked, 1, 2, 3, List.of("test:owner"),
                List.of("pdc:test"), List.of(), false, 0, null, null, 10);
    }

    @Test void sectionDensityKeepsNegativeHeightSectionsSeparate() {
        var above = sample("above", 3);
        var below = new EntitySample("below", "world", -1, -2, -1, -1, -17, "ITEM_DISPLAY", false, false,
                true, true, true, 100, 0, 0, 1, 2, 3, List.of(), List.of(), List.of(), false, 0, null, null, 10);
        var rows = CensusSummary.sections(List.of(above, above, below));
        assertEquals(2, rows.size());
        assertEquals(3, rows.getFirst().section().sectionY());
        assertEquals(2, rows.getFirst().entities());
        assertEquals(6, rows.getFirst().trackedHumanSubscriptions());
        assertEquals(-1, rows.getLast().section().sectionY());
    }
}
