package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.EntityLabels;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PacketProbe;
import java.util.List;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Packet causes on {@code ServerLevel}: entity status and damage broadcasts ({@code entity.event}), particles (the
 * public {@code sendParticles} overloads and CraftWorld funnel into {@code sendParticlesSource(List, ...)}; the
 * single-player overload is wrapped too) and the spawning-plugin lookup at {@code addFreshEntity}.
 */
@Mixin(value = ServerLevel.class, remap = false)
abstract class PacketMixinLevel {
    @WrapMethod(method = "broadcastEntityEvent(Lnet/minecraft/world/entity/Entity;B)V", require = 1, allow = 1)
    private void diagnostics$entityEvent(Entity entity, byte event, Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(entity, event); return; }
        int frame = session.enter(PacketProbe.ENTITY_EVENT, 0);
        try { original.call(entity, event); }
        finally { session.exit(frame); }
    }

    @WrapMethod(method = "broadcastDamageEvent(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;)V",
            require = 1, allow = 1)
    private void diagnostics$damageEvent(Entity entity, DamageSource source, Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(entity, source); return; }
        int frame = session.enter(PacketProbe.ENTITY_EVENT, 0);
        try { original.call(entity, source); }
        finally { session.exit(frame); }
    }

    @WrapMethod(method = "sendParticlesSource(Ljava/util/List;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I",
            require = 1, allow = 1)
    private int diagnostics$particles(List<ServerPlayer> receivers, Entity source, ParticleOptions particle, boolean force, boolean always,
                                      double x, double y, double z, int count, double dx, double dy, double dz, double speed,
                                      Operation<Integer> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) return original.call(receivers, source, particle, force, always, x, y, z, count, dx, dy, dz, speed);
        int frame = session.enter(PacketProbe.PARTICLES, 0);
        try { return original.call(receivers, source, particle, force, always, x, y, z, count, dx, dy, dz, speed); }
        finally { session.exit(frame); }
    }

    @WrapMethod(method = "sendParticles(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)Z",
            require = 1, allow = 1)
    private boolean diagnostics$playerParticles(ServerPlayer receiver, ParticleOptions particle, boolean force, boolean always,
                                                double x, double y, double z, int count, double dx, double dy, double dz, double speed,
                                                Operation<Boolean> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) return original.call(receiver, particle, force, always, x, y, z, count, dx, dy, dz, speed);
        int frame = session.enter(PacketProbe.PARTICLES, 0);
        try { return original.call(receiver, particle, force, always, x, y, z, count, dx, dy, dz, speed); }
        finally { session.exit(frame); }
    }

    @Inject(method = "addFreshEntity(Lnet/minecraft/world/entity/Entity;Lorg/bukkit/event/entity/CreatureSpawnEvent$SpawnReason;)Z",
            at = @At("HEAD"), require = 1, allow = 1)
    private void diagnostics$spawner(Entity entity, CreatureSpawnEvent.SpawnReason reason, CallbackInfoReturnable<Boolean> callback) {
        PacketProbe.Session session = PacketProbe.active();
        if (session != null && entity != null) EntityLabels.recordSpawner(session, entity);
    }
}
