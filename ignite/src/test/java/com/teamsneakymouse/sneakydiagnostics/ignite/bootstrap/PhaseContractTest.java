package com.teamsneakymouse.sneakydiagnostics.ignite.bootstrap;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

/** Tick-phase hook contracts against the baseline server (or -PcompatibilityServerJar=<versions/.../paper.jar>). */
class PhaseContractTest {
    @Test void baselineServerSatisfiesEveryPhaseContract() throws Exception {
        for (String name : PhaseContract.CLASSES) PhaseContract.verify(read(name));
    }
    @Test void serverClassesAreOwnedSchedulerClassesAreShared() throws Exception {
        assertTrue(PhaseContract.verify(read(PhaseContract.SERVER)));
        assertTrue(PhaseContract.verify(read(PhaseContract.CHUNKS)));
        assertFalse(PhaseContract.verify(read(PhaseContract.SCHEDULER)));    // SchedulerContract still applies to it
        assertFalse(PhaseContract.verify(read(PhaseContract.TASK)));
    }
    @Test void aMissingTrackerCallSiteIsRejected() throws Exception {
        assertRejected(PhaseContract.CHUNKS, node -> removeCalls(method(node, "tick", "(Ljava/util/function/BooleanSupplier;Z)V"),
                PhaseContract.CHUNK_MAP, "tick"));
    }
    @Test void aTrackerThatIsNoLongerMoonrisesIsRejected() throws Exception {
        assertRejected(PhaseContract.CHUNK_MAP, node -> removeCalls(method(node, "tick", "()V"), PhaseContract.CHUNK_MAP, "newTrackerTick"));
    }
    @Test void aChangedNumberOfSpawnStateCallsIsRejected() throws Exception {
        assertRejected(PhaseContract.CHUNKS, node -> {
            MethodNode chunks = method(node, "tickChunks", "(Lnet/minecraft/util/profiling/ProfilerFiller;J)V");
            for (var insn : chunks.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("createState")) { chunks.instructions.remove(call); return; }
            }
        });
    }
    @Test void spawningAndEntityCallSitesAreRequired() throws Exception {
        assertRejected(PhaseContract.CHUNKS, node -> removeCalls(method(node, "tickSpawningChunk", PhaseContract.SPAWNING_CHUNK),
                PhaseContract.SPAWNER, "spawnForChunk"));
        assertRejected(PhaseContract.SPAWNER, node -> removeCalls(method(node, "spawnCategoryForPosition", PhaseContract.POSITION),
                PhaseContract.LEVEL, "addFreshEntityWithPassengers"));
        assertRejected(PhaseContract.LEVEL, node -> removeCalls(method(node, "tick", "(Ljava/util/function/BooleanSupplier;)V"),
                "net/minecraft/world/level/entity/EntityTickList", "forEach"));
    }
    @Test void theTickEndDispatchAndMeasuredIntervalAreRequired() throws Exception {
        assertRejected(PhaseContract.SERVER, node -> removeCalls(method(node, "tickServer", "(Ljava/util/function/BooleanSupplier;)V"),
                PhaseContract.TICK_END_EVENT, "callEvent"));
        // Tick-start tasks after tickServer would no longer be the first phase of the measured tick.
        assertRejected(PhaseContract.SERVER, node -> removeCalls(method(node, "processPacketsAndTick", "(Z)V"),
                PhaseContract.SERVER, "runAllTasksAtTickStart"));
        assertRejected(PhaseContract.SERVER, node -> removeCalls(method(node, "runServer", "()V"), PhaseContract.SERVER, "processPacketsAndTick"));
    }
    @Test void schedulerHeartbeatIsRequired() throws Exception {
        assertRejected(PhaseContract.SCHEDULER, node -> node.methods.removeIf(m -> m.name.equals("mainThreadHeartbeat")));
    }

    private void assertRejected(String name, Consumer<ClassNode> change) throws Exception {
        ClassNode node = read(name);
        change.accept(node);
        assertThrows(IllegalStateException.class, () -> PhaseContract.verify(node));
    }
    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
    }
    private static void removeCalls(MethodNode method, String owner, String name) {
        for (var insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) method.instructions.remove(call);
        }
    }
    private ClassNode read(String name) throws Exception {
        String external = System.getProperty("compatibilityServerJar");
        if (external != null) {
            try (var jar = new java.util.zip.ZipFile(external)) {
                var entry = jar.getEntry(name + ".class");
                assertNotNull(entry, "Use the actual server JAR under versions/, not the Paperclip launcher: " + name);
                try (var input = jar.getInputStream(entry)) {
                    var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
                }
            }
        }
        try (var input = getClass().getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, "Baseline Paper server must be on the test classpath: " + name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
