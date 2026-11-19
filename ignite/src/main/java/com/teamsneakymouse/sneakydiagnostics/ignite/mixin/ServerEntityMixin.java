package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.EntityLabels;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.TrackerProbe;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Times the entity tracker's per-tick send for one entity ({@code sendChanges}: movement, rotation, passengers,
 * velocity, map updates and changed metadata) and, nested in it, the metadata part ({@code sendDirtyEntityData}).
 * The entity's group (Bukkit entity type and owner hint, {@link EntityLabels#typeAndHint}) is resolved the first time a
 * capture sees the entity and cached on this tracker entry. The packet probe's cause hooks on the same methods are in
 * {@code PacketMixinTracker}.
 */
@Mixin(targets = "net.minecraft.server.level.ServerEntity", remap = false)
abstract class ServerEntityMixin {
    @Shadow @Final private Entity entity;
    @Unique private int diagnostics$generation;
    @Unique private int diagnostics$group;

    @WrapMethod(method = "sendChanges()V", require = 1, allow = 1)
    private void diagnostics$sendChanges(Operation<Void> original) {
        TrackerProbe.Session session = TrackerProbe.recording();
        if (session == null) { original.call(); return; }
        if (diagnostics$generation != session.generation) {
            diagnostics$group = session.group(() -> EntityLabels.typeAndHint(entity));
            diagnostics$generation = session.generation;
        }
        long started = session.now();
        try { original.call(); }
        finally { session.send(diagnostics$group, started, session.now()); }
    }

    @WrapMethod(method = "sendDirtyEntityData()V", require = 1, allow = 1)
    private void diagnostics$metadata(Operation<Void> original) {
        TrackerProbe.Session session = TrackerProbe.recording();
        if (session == null || diagnostics$generation != session.generation) { original.call(); return; }
        long started = session.now();
        try { original.call(); }
        finally { session.metadata(diagnostics$group, started, session.now()); }
    }
}
