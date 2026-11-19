package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PhaseProbe;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PhaseWorld;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Per-world phases inside {@code ServerLevel.tick}; the rest of that call is the world's {@code other}. */
@Mixin(value = ServerLevel.class, remap = false)
abstract class PhaseMixinLevel implements PhaseWorld {
    /** The capture this level's slot belongs to (a session generation, so no reference outlives the capture). */
    @Unique private int diagnostics$phaseGeneration;
    @Unique private int diagnostics$phaseWorld;

    @Override public int diagnostics$phaseWorld(PhaseProbe.Session session) {
        if (diagnostics$phaseGeneration == session.generation) return diagnostics$phaseWorld;
        int world = session.world(((ServerLevel) (Object) this).getWorld().getName());     // once per world and capture
        diagnostics$phaseWorld = world;
        diagnostics$phaseGeneration = session.generation;
        return world;
    }

    @Unique private int diagnostics$slot(PhaseProbe.Session session, int kind) {
        return PhaseProbe.Session.level(diagnostics$phaseWorld(session), kind);
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"), require = 1, allow = 1)
    private void diagnostics$entities(EntityTickList list, Consumer<Entity> action, Operation<Void> original) {
        PhaseProbe.Session session = PhaseProbe.active();
        if (session == null) { original.call(list, action); return; }
        int frame = session.enter(diagnostics$slot(session, PhaseProbe.ENTITIES));
        try { original.call(list, action); }
        finally { session.exit(frame); }
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V"), require = 1, allow = 1)
    private void diagnostics$chunks(ServerChunkCache chunks, BooleanSupplier haveTime, boolean tickChunks, Operation<Void> original) {
        PhaseProbe.Session session = PhaseProbe.active();
        if (session == null) { original.call(chunks, haveTime, tickChunks); return; }
        int frame = session.enter(diagnostics$slot(session, PhaseProbe.CHUNKS));
        try { original.call(chunks, haveTime, tickChunks); }
        finally { session.exit(frame); }
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tickBlockEntities()V"), require = 1, allow = 1)
    private void diagnostics$blockEntities(ServerLevel level, Operation<Void> original) {
        PhaseProbe.Session session = PhaseProbe.active();
        if (session == null) { original.call(level); return; }
        int frame = session.enter(diagnostics$slot(session, PhaseProbe.BLOCK_ENTITIES));
        try { original.call(level); }
        finally { session.exit(frame); }
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;runBlockEvents()V"), require = 1, allow = 1)
    private void diagnostics$blockEvents(ServerLevel level, Operation<Void> original) {
        PhaseProbe.Session session = PhaseProbe.active();
        if (session == null) { original.call(level); return; }
        int frame = session.enter(diagnostics$slot(session, PhaseProbe.BLOCK_EVENTS));
        try { original.call(level); }
        finally { session.exit(frame); }
    }
}
