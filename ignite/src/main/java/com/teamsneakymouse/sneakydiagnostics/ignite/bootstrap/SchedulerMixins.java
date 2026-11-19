package com.teamsneakymouse.sneakydiagnostics.ignite.bootstrap;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Selects Paper before target discovery and verifies structural contracts (scheduler, listener dispatch, tick phases, tracker sends), not release numbers. */
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
        }
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) { return active; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return active ? List.of("SchedulerMixin", "TaskMixin", "FutureMixin", "ListenerMixin",
            "PhaseMixinServer", "PhaseMixinLevel", "PhaseMixinChunks", "PhaseMixinSpawner", "PhaseMixinScheduler", "PhaseMixinTask", "PhaseMixinListener",
            "ServerEntityMixin") : List.of(); }
    /**
     * Re-verifies each target against every contract that covers it. No class is transformed by both a phase and the
     * tracker hook today (ChunkMap is in both contracts but only read at load), but a shared target would satisfy both.
     */
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        boolean tracker = TrackerContract.covers(node.name);
        if (tracker) TrackerContract.verify(node);
        boolean phaseOnly = PhaseContract.verify(node);                     // server classes only the phase hooks transform
        if (!tracker && !phaseOnly) SchedulerContract.verify(node);
    }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
