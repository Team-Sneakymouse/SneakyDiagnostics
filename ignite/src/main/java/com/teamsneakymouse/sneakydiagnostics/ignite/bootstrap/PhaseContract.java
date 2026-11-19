package com.teamsneakymouse.sneakydiagnostics.ignite.bootstrap;

import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Structural contracts of the tick-phase hooks (Paper 26.2, Mojang-mapped, Moonrise tracker): every wrapped call site
 * exists exactly as often as the mixins expect, and the tick identity and measured interval are where the probe assumes.
 * A mismatch fails startup instead of measuring some phases.
 */
public final class PhaseContract {
    static final String SERVER = "net/minecraft/server/MinecraftServer";
    static final String LEVEL = "net/minecraft/server/level/ServerLevel";
    static final String CHUNKS = "net/minecraft/server/level/ServerChunkCache";
    static final String CHUNK_MAP = "net/minecraft/server/level/ChunkMap";
    static final String SPAWNER = "net/minecraft/world/level/NaturalSpawner";
    static final String SCHEDULER = "org/bukkit/craftbukkit/scheduler/CraftScheduler";
    static final String TASK = "org/bukkit/craftbukkit/scheduler/CraftTask";
    /** Every class whose structure the phase hooks depend on (ChunkMap is checked, not transformed). */
    public static final List<String> CLASSES = List.of(SERVER, LEVEL, CHUNKS, CHUNK_MAP, SPAWNER, SCHEDULER, TASK);
    static final String SUPPLIER = "Ljava/util/function/BooleanSupplier;";
    static final String CREATE_STATE = "(ILjava/lang/Iterable;Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;"
            + "Lnet/minecraft/world/level/LocalMobCapCalculator;Z)Lnet/minecraft/world/level/NaturalSpawner$SpawnState;";
    static final String SPAWN_FOR_CHUNK = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;"
            + "Lnet/minecraft/world/level/NaturalSpawner$SpawnState;Ljava/util/List;)V";
    static final String SPAWNING_CHUNK = "(Lnet/minecraft/world/level/chunk/LevelChunk;JLjava/util/List;"
            + "Lnet/minecraft/world/level/NaturalSpawner$SpawnState;)V";
    static final String POSITION = "(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;"
            + "Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;ILjava/util/function/Consumer;)V";
    static final String ADD_FRESH = "(Lnet/minecraft/world/entity/Entity;Lorg/bukkit/event/entity/CreatureSpawnEvent$SpawnReason;)V";
    static final String TICK_END_EVENT = "com/destroystokyo/paper/event/server/ServerTickEndEvent";

    private PhaseContract() {}

    /** Verifies every class in {@link #CLASSES} from {@code loader} before any mixin applies. */
    public static void verifyAll(ClassLoader loader) {
        for (String name : CLASSES) {
            try (var input = loader.getResourceAsStream(name + ".class")) {
                if (input == null) throw new IllegalStateException("SneakyDiagnostics incompatible server: missing phase target " + name);
                var node = new ClassNode();
                new org.objectweb.asm.ClassReader(input).accept(node, 0);
                verify(node);
            } catch (java.io.IOException e) { throw new IllegalStateException("Cannot verify phase target " + name, e); }
        }
    }

    /**
     * Verifies {@code node} if the phase hooks depend on it. Returns true for server classes only the phase hooks
     * transform (no scheduler contract applies), false for the shared scheduler/listener classes and anything else.
     */
    public static boolean verify(ClassNode node) {
        switch (node.name) {
            case SERVER -> {
                field(node, "currentTick", "I", true);
                field(node, "currentTickStart", "J", false);
                MethodNode loop = method(node, "runServer", "()V");
                int tickStart = first(loop, insn -> insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
                        && f.owner.equals(SERVER) && f.name.equals("currentTickStart"));
                int tickIncrement = first(loop, insn -> insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC
                        && f.owner.equals(SERVER) && f.name.equals("currentTick"));
                int tick = first(loop, insn -> call(insn, SERVER, "processPacketsAndTick", "(Z)V"));
                require(tickStart >= 0 && tickIncrement >= 0 && tick > tickStart && tick > tickIncrement, node,
                        "runServer must set currentTickStart and increment currentTick before processPacketsAndTick");
                MethodNode process = method(node, "processPacketsAndTick", "(Z)V");
                int tasks = first(process, insn -> call(insn, SERVER, "runAllTasksAtTickStart", "()V"));
                int body = first(process, insn -> call(insn, SERVER, "tickServer", "(" + SUPPLIER + ")V"));
                require(tasks >= 0 && body > tasks && count(process, SERVER, "runAllTasksAtTickStart", "()V") == 1, node,
                        "processPacketsAndTick must run tick-start tasks once, before tickServer");
                method(node, "runAllTasksAtTickStart", "()V");
                MethodNode tickServer = method(node, "tickServer", "(" + SUPPLIER + ")V");
                exactly(node, tickServer, TICK_END_EVENT, "callEvent", "()Z", 1);
                exactly(node, tickServer, TICK_END_EVENT, "<init>", "(IDJ)V", 1);
                require(first(tickServer, insn -> insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
                        && f.owner.equals(SERVER) && f.name.equals("currentTickStart")) >= 0, node,
                        "tickServer must measure the tick duration from currentTickStart");
                MethodNode children = method(node, "tickChildren", "(" + SUPPLIER + ")V");
                exactly(node, children, LEVEL, "tick", "(" + SUPPLIER + ")V", 1);
                exactly(node, children, SCHEDULER, "mainThreadHeartbeat", "()V", 1);
                exactly(node, children, SERVER, "tickConnection", "()V", 1);
                exactly(node, method(node, "tickConnection", "()V"), "net/minecraft/server/network/ServerConnectionListener", "tick", "()V", 1);
                return true;
            }
            case LEVEL -> {
                MethodNode tick = method(node, "tick", "(" + SUPPLIER + ")V");
                exactly(node, tick, "net/minecraft/world/level/entity/EntityTickList", "forEach", "(Ljava/util/function/Consumer;)V", 1);
                exactly(node, tick, CHUNKS, "tick", "(" + SUPPLIER + "Z)V", 1);
                exactly(node, tick, LEVEL, "tickBlockEntities", "()V", 1);
                exactly(node, tick, LEVEL, "runBlockEvents", "()V", 1);
                return true;
            }
            case CHUNKS -> {
                field(node, "level", "L" + LEVEL + ";", false);
                exactly(node, method(node, "tick", "(" + SUPPLIER + "Z)V"), CHUNK_MAP, "tick", "()V", 1);
                MethodNode chunks = method(node, "tickChunks", "(Lnet/minecraft/util/profiling/ProfilerFiller;J)V");
                exactly(node, chunks, SPAWNER, "createState", CREATE_STATE, 2);
                exactly(node, chunks, LEVEL, "tickCustomSpawners", "(Z)V", 1);
                exactly(node, chunks, CHUNKS, "tickSpawningChunk", SPAWNING_CHUNK, 1);
                exactly(node, method(node, "tickSpawningChunk", SPAWNING_CHUNK), SPAWNER, "spawnForChunk", SPAWN_FOR_CHUNK, 1);
                return true;
            }
            case CHUNK_MAP -> {
                // The tracker phase wraps ChunkMap.tick(); it must still be Moonrise's tracker tick.
                exactly(node, method(node, "tick", "()V"), CHUNK_MAP, "newTrackerTick", "()V", 1);
                return true;
            }
            case SPAWNER -> {
                exactly(node, method(node, "spawnCategoryForPosition", POSITION), LEVEL, "addFreshEntityWithPassengers", ADD_FRESH, 1);
                return true;
            }
            case SCHEDULER -> { method(node, "mainThreadHeartbeat", "()V"); return false; }
            case TASK -> {
                method(node, "run", "()V");
                method(node, "getOwner", "()Lorg/bukkit/plugin/Plugin;");
                return false;
            }
            default -> { return false; }
        }
    }

    static boolean call(AbstractInsnNode insn, String owner, String name, String desc) {
        return insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc);
    }
    static int count(MethodNode method, String owner, String name, String desc) {
        int found = 0;
        for (var insn : method.instructions) if (call(insn, owner, name, desc)) found++;
        return found;
    }
    private static int first(MethodNode method, java.util.function.Predicate<AbstractInsnNode> test) {
        int index = 0;
        for (var insn : method.instructions) {
            if (test.test(insn)) return index;
            index++;
        }
        return -1;
    }
    private static void exactly(ClassNode node, MethodNode method, String owner, String name, String desc, int expected) {
        int found = count(method, owner, name, desc);
        require(found == expected, node, method.name + method.desc + " must call " + owner + "." + name + desc + " "
                + expected + " time(s), found " + found);
    }
    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(desc)).findFirst()
                .orElseThrow(() -> new IllegalStateException("SneakyDiagnostics incompatible server: " + node.name + "." + name + desc));
    }
    private static void field(ClassNode node, String name, String desc, boolean isStatic) {
        require(node.fields.stream().anyMatch(field -> field.name.equals(name) && field.desc.equals(desc)
                && ((field.access & Opcodes.ACC_STATIC) != 0) == isStatic), node, "missing field " + name + ":" + desc);
    }
    private static void require(boolean condition, ClassNode node, String detail) {
        if (!condition) throw new IllegalStateException("SneakyDiagnostics incompatible server (tick phases): " + node.name + ": " + detail);
    }
}
