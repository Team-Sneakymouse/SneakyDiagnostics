package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.EntityLabels;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PacketProbe;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Tracker send causes on {@code ServerEntity}: {@code sendChanges} ({@code tracker.update}: movement, rotation,
 * passengers, velocity, map updates), its nested {@code sendDirtyEntityData} ({@code tracker.metadata}),
 * {@code addPairing} ({@code tracker.spawn}) and {@code removePairing} ({@code tracker.despawn}), each with the entity's
 * group (entity type and owner, {@link EntityLabels#typeAndOwner}), resolved once per entity per capture and cached here.
 */
@Mixin(targets = "net.minecraft.server.level.ServerEntity", remap = false)
abstract class PacketMixinTracker {
    @Shadow @Final private Entity entity;
    @Unique private int diagnostics$packetGeneration;
    @Unique private int diagnostics$packetGroup;

    @Unique
    private int diagnostics$group(PacketProbe.Session session) {
        if (diagnostics$packetGeneration != session.generation) {
            diagnostics$packetGroup = session.group(() -> EntityLabels.typeAndOwner(entity));
            diagnostics$packetGeneration = session.generation;
        }
        return diagnostics$packetGroup;
    }

    @WrapMethod(method = "sendChanges()V", require = 1, allow = 1)
    private void diagnostics$update(Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(); return; }
        int frame = session.enter(PacketProbe.TRACKER_UPDATE, diagnostics$group(session));
        try { original.call(); }
        finally { session.exit(frame); }
    }

    @WrapMethod(method = "sendDirtyEntityData()V", require = 1, allow = 1)
    private void diagnostics$metadataCause(Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(); return; }
        int frame = session.enter(PacketProbe.TRACKER_METADATA, diagnostics$group(session));
        try { original.call(); }
        finally { session.exit(frame); }
    }

    @WrapMethod(method = "addPairing(Lnet/minecraft/server/level/ServerPlayer;)V", require = 1, allow = 1)
    private void diagnostics$spawn(ServerPlayer player, Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(player); return; }
        int frame = session.enter(PacketProbe.TRACKER_SPAWN, diagnostics$group(session));
        try { original.call(player); }
        finally { session.exit(frame); }
    }

    @WrapMethod(method = "removePairing(Lnet/minecraft/server/level/ServerPlayer;)V", require = 1, allow = 1)
    private void diagnostics$despawn(ServerPlayer player, Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(player); return; }
        int frame = session.enter(PacketProbe.TRACKER_DESPAWN, diagnostics$group(session));
        try { original.call(player); }
        finally { session.exit(frame); }
    }
}
