package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PhaseProbe;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PhaseWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.NaturalSpawner;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Counts entities natural spawning tries to add (before a spawn event can cancel them): did spawning actually spawn? */
@Mixin(value = NaturalSpawner.class, remap = false)
abstract class PhaseMixinSpawner {
    @WrapOperation(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;ILjava/util/function/Consumer;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;Lorg/bukkit/event/entity/CreatureSpawnEvent$SpawnReason;)V"),
            require = 1, allow = 1)
    private static void diagnostics$naturalSpawn(ServerLevel level, Entity entity, CreatureSpawnEvent.SpawnReason reason, Operation<Void> original) {
        PhaseProbe.Session session = PhaseProbe.active();
        if (session != null) session.naturalSpawnAttempt(((PhaseWorld) level).diagnostics$phaseWorld(session));
        original.call(level, entity, reason);
    }
}
