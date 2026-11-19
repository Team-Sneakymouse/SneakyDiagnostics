package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PhaseProbe;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PhaseWorld;
import java.util.List;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Phases nested in a world's chunk tick: the entity tracker (Moonrise {@code ChunkMap.tick}) and natural spawning,
 * split into mob-cap bookkeeping ({@code createState}), per-chunk spawning ({@code spawnForChunk}) and the custom
 * spawners (phantoms, patrols, cats, traders, sieges), each with call counters.
 */
@Mixin(value = ServerChunkCache.class, remap = false)
abstract class PhaseMixinChunks {
    @Shadow @Final private ServerLevel level;

    @Unique private int diagnostics$world(PhaseProbe.Session session) {
        return ((PhaseWorld) level).diagnostics$phaseWorld(session);
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;tick()V"), require = 1, allow = 1)
    private void diagnostics$tracker(ChunkMap map, Operation<Void> original) {
        PhaseProbe.Session session = PhaseProbe.active();
        if (session == null) { original.call(map); return; }
        int frame = session.enter(PhaseProbe.Session.level(diagnostics$world(session), PhaseProbe.TRACKER));
        try { original.call(map); }
        finally { session.exit(frame); }
    }

    @WrapOperation(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/NaturalSpawner;createState(ILjava/lang/Iterable;Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;Lnet/minecraft/world/level/LocalMobCapCalculator;Z)Lnet/minecraft/world/level/NaturalSpawner$SpawnState;"),
            require = 2, allow = 2)
    private NaturalSpawner.SpawnState diagnostics$spawnState(int chunkCount, Iterable<Entity> entities, NaturalSpawner.ChunkGetter getter,
                                                             LocalMobCapCalculator calculator, boolean countMobs,
                                                             Operation<NaturalSpawner.SpawnState> original) {
        PhaseProbe.Session session = PhaseProbe.active();
        if (session == null) return original.call(chunkCount, entities, getter, calculator, countMobs);
        int world = diagnostics$world(session);
        session.spawnState(world);
        int frame = session.enter(PhaseProbe.Session.level(world, PhaseProbe.SPAWN_STATE));
        try { return original.call(chunkCount, entities, getter, calculator, countMobs); }
        finally { session.exit(frame); }
    }

    @WrapOperation(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tickCustomSpawners(Z)V"), require = 1, allow = 1)
    private void diagnostics$customSpawners(ServerLevel target, boolean spawnEnemies, Operation<Void> original) {
        PhaseProbe.Session session = PhaseProbe.active();
        if (session == null) { original.call(target, spawnEnemies); return; }
        int world = diagnostics$world(session);
        session.customSpawners(world);
        int frame = session.enter(PhaseProbe.Session.level(world, PhaseProbe.SPAWN_CUSTOM));
        try { original.call(target, spawnEnemies); }
        finally { session.exit(frame); }
    }

    @WrapOperation(method = "tickSpawningChunk(Lnet/minecraft/world/level/chunk/LevelChunk;JLjava/util/List;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;spawnForChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;Ljava/util/List;)V"),
            require = 1, allow = 1)
    private void diagnostics$spawnForChunk(ServerLevel target, LevelChunk chunk, NaturalSpawner.SpawnState state,
                                           List<MobCategory> categories, Operation<Void> original) {
        PhaseProbe.Session session = PhaseProbe.active();
        if (session == null) { original.call(target, chunk, state, categories); return; }
        int world = diagnostics$world(session);
        long attempts = session.spawnChunk(world);
        int frame = session.enter(PhaseProbe.Session.level(world, PhaseProbe.SPAWN_SPAWN));
        try { original.call(target, chunk, state, categories); }
        finally {
            session.exit(frame);
            session.spawnChunkDone(world, attempts);
        }
    }
}
