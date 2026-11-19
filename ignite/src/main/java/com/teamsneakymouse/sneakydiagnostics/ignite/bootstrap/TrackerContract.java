package com.teamsneakymouse.sneakydiagnostics.ignite.bootstrap;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** What the entity-tracker send probe relies on; a mismatch fails startup instead of measuring partially. */
public final class TrackerContract {
    public static final String SERVER_ENTITY = "net/minecraft/server/level/ServerEntity";
    /** Read-only check: Moonrise's tracker loop must still send changes through {@code ServerEntity.sendChanges}. */
    public static final String CHUNK_MAP = "net/minecraft/server/level/ChunkMap";
    private TrackerContract() {}

    public static boolean covers(String internalName) { return internalName.equals(SERVER_ENTITY) || internalName.equals(CHUNK_MAP); }

    public static void verify(ClassNode node) {
        switch (node.name) {
            case SERVER_ENTITY -> {
                require(node.fields.stream().anyMatch(f -> f.name.equals("entity") && f.desc.equals("Lnet/minecraft/world/entity/Entity;")),
                        node, "missing entity field");
                var send = method(node, "sendChanges", "()V");
                method(node, "sendDirtyEntityData", "()V");
                require(calls(send, SERVER_ENTITY, "sendDirtyEntityData"), node, "sendChanges must still send metadata through sendDirtyEntityData");
            }
            case CHUNK_MAP -> require(calls(method(node, "newTrackerTick", "()V"), SERVER_ENTITY, "sendChanges"),
                    node, "newTrackerTick must still send changes through ServerEntity.sendChanges");
            default -> throw new IllegalStateException("Unexpected tracker target " + node.name);
        }
    }
    private static boolean calls(MethodNode method, String owner, String name) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
        }
        return false;
    }
    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
                .orElseThrow(() -> new IllegalStateException("SneakyDiagnostics incompatible tracker: " + node.name + "." + name + desc));
    }
    private static void require(boolean condition, ClassNode node, String detail) {
        if (!condition) throw new IllegalStateException("SneakyDiagnostics incompatible tracker: " + node.name + ": " + detail);
    }
}
