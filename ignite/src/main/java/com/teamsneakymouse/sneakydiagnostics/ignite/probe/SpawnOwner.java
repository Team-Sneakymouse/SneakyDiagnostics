package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

/**
 * Added to {@code net.minecraft.world.entity.Entity} by a mixin: the plugin that spawned the entity, found once at
 * {@code ServerLevel.addFreshEntity} while a packet capture records, or null (not looked up, or no plugin on the stack).
 */
public interface SpawnOwner {
    String diagnostics$spawnedBy();
    void diagnostics$spawnedBy(String plugin);
}
