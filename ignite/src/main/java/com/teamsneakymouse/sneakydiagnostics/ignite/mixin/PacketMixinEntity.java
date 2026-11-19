package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.teamsneakymouse.sneakydiagnostics.ignite.probe.SpawnOwner;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Caches the spawning plugin on the entity (set once at {@code addFreshEntity} while a packet capture records). */
@Mixin(value = Entity.class, remap = false)
abstract class PacketMixinEntity implements SpawnOwner {
    @Unique private String diagnostics$spawner;

    @Override public String diagnostics$spawnedBy() { return diagnostics$spawner; }
    @Override public void diagnostics$spawnedBy(String plugin) { diagnostics$spawner = plugin; }
}
