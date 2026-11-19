package com.teamsneakymouse.sneakydiagnostics.ignite.bootstrap;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Selects Paper before target discovery and verifies structural contracts (scheduler, listener dispatch, tick phases, tracker sends, packets), not release numbers. */
public final class SchedulerMixins implements IMixinConfigPlugin {
    private boolean active;
    @Override public void onLoad(String mixinPackage) {
        var loader = Thread.currentThread().getContextClassLoader();
        active = loader.getResource("org/bukkit/craftbukkit/scheduler/CraftScheduler.class") != null
                && loader.getResource("com/velocitypowered/proxy/Velocity.class") == null;
        if (active) {
            for (String name : List.of("CraftScheduler", "CraftTask", "CraftFuture", "CraftAsyncTask")) {
                try (var input = loader.getResourceAsStream("org/bukkit/craftbukkit/scheduler/" + name + ".class")) {
                    if (input == null) throw new IllegalStateException("Missing scheduler target: " + name);
                    var node = new ClassNode();
                    new org.objectweb.asm.ClassReader(input).accept(node, 0);
                    SchedulerContract.verify(node);
                } catch (java.io.IOException e) { throw new IllegalStateException("Cannot verify scheduler target: " + name, e); }
            }
            try (var input = loader.getResourceAsStream(SchedulerContract.LISTENER + ".class")) {
                if (input == null) throw new IllegalStateException("Missing listener target: RegisteredListener");
                var node = new ClassNode();
                new org.objectweb.asm.ClassReader(input).accept(node, 0);
                SchedulerContract.verify(node);
            } catch (java.io.IOException e) { throw new IllegalStateException("Cannot verify listener target", e); }
            PhaseContract.verifyAll(loader);
            for (String name : List.of(TrackerContract.SERVER_ENTITY, TrackerContract.CHUNK_MAP)) {
                try (var input = loader.getResourceAsStream(name + ".class")) {
                    if (input == null) throw new IllegalStateException("Missing tracker target: " + name);
                    var node = new ClassNode();
                    new org.objectweb.asm.ClassReader(input).accept(node, 0);
                    TrackerContract.verify(node);
                } catch (java.io.IOException e) { throw new IllegalStateException("Cannot verify tracker target: " + name, e); }
            }
            PacketContract.verifyAll(loader);
        }
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) { return active; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    /** Every mixin; the packet probe's are the {@code PacketMixin*} ones. */
    static final List<String> MIXINS = List.of("SchedulerMixin", "TaskMixin", "FutureMixin", "ListenerMixin",
            "PhaseMixinServer", "PhaseMixinLevel", "PhaseMixinChunks", "PhaseMixinSpawner", "PhaseMixinScheduler", "PhaseMixinTask", "PhaseMixinListener",
            "ServerEntityMixin",
            "PacketMixinConnection", "PacketMixinInbound", "PacketMixinServer", "PacketMixinTracker", "PacketMixinEntity", "PacketMixinLiving",
            "PacketMixinLevel", "PacketMixinPlayers", "PacketMixinCraftPlayer", "PacketMixinChunkSender", "PacketMixinChunkLoader");
    @Override public List<String> getMixins() { return active ? MIXINS : List.of(); }
    /**
     * Re-verifies each target against every contract that covers it, so a class transformed by several hook sets
     * satisfies all of them: {@code ServerEntity} (tracker and packets), {@code ServerLevel} and {@code MinecraftServer}
     * (tick phases and packets). {@code ChunkMap} is in the tracker and phase contracts but only read at load.
     */
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        boolean covered = false;
        if (TrackerContract.covers(node.name)) { TrackerContract.verify(node); covered = true; }
        if (PacketContract.covers(node.name)) { PacketContract.verify(node); covered = true; }
        if (PhaseContract.verify(node)) covered = true;                     // server classes the phase hooks transform
        if (!covered) SchedulerContract.verify(node);
    }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
