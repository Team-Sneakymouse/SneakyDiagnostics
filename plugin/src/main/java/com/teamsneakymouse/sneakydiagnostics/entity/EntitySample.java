package com.teamsneakymouse.sneakydiagnostics.entity;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;

/** Contains only detached data; safe to serialize after leaving the server thread. */
public record EntitySample(String entityId, String world, int chunkX, int chunkZ,
        double x, double y, double z, String type, boolean onlineHuman, boolean npcMetadata,
        boolean persistent, boolean ticking, boolean visibleByDefault, int ageTicks,
        int trackedPlayers, int trackedHumans, int nearbyHumans32, int nearbyHumans64, int nearbyHumans128,
        List<String> pdcKeys, List<String> ownerHints, List<String> scoreboardTags,
        boolean labelsTruncated, int passengers, String vehicleId, DisplayData display, long observedAfterMillis) {

    public record Human(UUID id, String world, double x, double z) {}
    public record DisplayData(float viewRange, float width, float height, int interpolationTicks,
                              int teleportTicks, String material) {}

    static EntitySample read(Entity entity, List<Human> humans, Set<UUID> humanIds, long observedAfterMillis) {
        var location = entity.getLocation();
        String world = entity.getWorld().getName();
        UUID id = entity.getUniqueId();
        var nearby = nearby(location.getX(), location.getZ(), world, id, humans);
        var keys = entity.getPersistentDataContainer().getKeys();
        var tags = entity.getScoreboardTags();
        boolean labelsTruncated = keys.size() > 64 || tags.size() > 64;
        var owners = new TreeSet<String>();
        var pdc = new TreeSet<String>();
        int visited = 0;
        for (var key : keys) {
            if (visited++ == 64) break;
            labelsTruncated |= key.toString().length() > 128;
            pdc.add(shortLabel(key.toString()));
            owners.add("pdc:" + shortLabel(key.getNamespace()));
        }
        boolean npc = entity.hasMetadata("NPC");
        // A metadata/PDC namespace is evidence of involvement, not proof of entity creation.
        if (npc) for (var value : entity.getMetadata("NPC")) {
            var plugin = value.getOwningPlugin();
            if (plugin != null) owners.add("NPC-metadata:" + shortLabel(plugin.getName()));
        }
        var tagList = new TreeSet<String>();
        visited = 0;
        for (String tag : tags) {
            if (visited++ == 64) break;
            labelsTruncated |= tag.length() > 128;
            tagList.add(shortLabel(tag));
        }
        var tracked = entity.getTrackedBy();
        int trackedHumans = 0;
        for (Player player : tracked) if (humanIds.contains(player.getUniqueId())) trackedHumans++;
        Entity vehicle = entity.getVehicle();
        DisplayData display = null;
        if (entity instanceof Display d) {
            String material = entity instanceof ItemDisplay item ? item.getItemStack().getType().name()
                    : entity instanceof BlockDisplay block ? block.getBlock().getMaterial().name() : null;
            display = new DisplayData(d.getViewRange(), d.getDisplayWidth(), d.getDisplayHeight(),
                    d.getInterpolationDuration(), d.getTeleportDuration(), material);
        }
        return new EntitySample(humanIds.contains(id) ? null : id.toString(), world,
                location.getBlockX() >> 4, location.getBlockZ() >> 4,
                location.getX(), location.getY(), location.getZ(), entity.getType().name(),
                humanIds.contains(id), npc, entity.isPersistent(), entity.isTicking(),
                entity.isVisibleByDefault(), entity.getTicksLived(), tracked.size(), trackedHumans,
                nearby[0], nearby[1], nearby[2], List.copyOf(pdc), List.copyOf(owners), List.copyOf(tagList),
                labelsTruncated,
                entity.getPassengers().size(), vehicle == null || humanIds.contains(vehicle.getUniqueId())
                        ? null : vehicle.getUniqueId().toString(), display, observedAfterMillis);
    }

    static String shortLabel(String text) { return text.length() <= 128 ? text : text.substring(0, 128); }

    static int[] nearby(double x, double z, String world, UUID self, List<Human> humans) {
        int[] count = new int[3];
        for (Human human : humans) {
            if (!human.world().equals(world) || human.id().equals(self)) continue;
            double dx = human.x() - x, dz = human.z() - z;
            double squared = dx * dx + dz * dz;
            if (squared <= 32 * 32) count[0]++;
            if (squared <= 64 * 64) count[1]++;
            if (squared <= 128 * 128) count[2]++;
        }
        return count;
    }
}
