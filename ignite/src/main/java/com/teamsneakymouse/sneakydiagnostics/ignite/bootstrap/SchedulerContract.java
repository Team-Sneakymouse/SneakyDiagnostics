package com.teamsneakymouse.sneakydiagnostics.ignite.bootstrap;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** A release is compatible when these method/field/call-path contracts remain intact. */
public final class SchedulerContract {
    private static final String PREFIX = "org/bukkit/craftbukkit/scheduler/";
    private SchedulerContract() {}

    public static void verify(ClassNode node) {
        switch (node.name.substring(PREFIX.length())) {
            case "CraftScheduler" -> {
                method(node, "<init>", "(Z)V");
                method(node, "cancelTask", "(I)V");
                method(node, "cancelTasks", "(Lorg/bukkit/plugin/Plugin;)V");
                field(node, "runners", "Ljava/util/concurrent/ConcurrentHashMap;");
                boolean internalRun = false;
                for (var instruction : method(node, "parsePending", "()V").instructions) {
                    if (instruction instanceof MethodInsnNode call && call.owner.equals(PREFIX + "CraftTask")
                            && call.name.equals("run") && call.desc.equals("()V")) internalRun = true;
                }
                require(internalRun, node, "parsePending must still execute internal CraftTask.run");
            }
            case "CraftTask" -> {
                method(node, "<init>", "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Object;IJ)V");
                method(node, "run", "()V");
                method(node, "getTaskClass", "()Ljava/lang/Class;");
            }
            case "CraftFuture" -> {
                method(node, "<init>", "(Ljava/util/concurrent/Callable;Lorg/bukkit/plugin/Plugin;I)V");
                method(node, "run", "()V");
                field(node, "callable", "Ljava/util/concurrent/Callable;");
                method(node, "cancel", "(Z)Z");
                boolean invokesCallable = false;
                for (var instruction : method(node, "run", "()V").instructions) {
                    if (instruction instanceof MethodInsnNode call && call.owner.equals("java/util/concurrent/Callable")
                            && call.name.equals("call") && call.desc.equals("()Ljava/lang/Object;")) invokesCallable = true;
                }
                require(invokesCallable, node, "Future.run must still invoke Callable.call");
            }
            case "CraftAsyncTask" -> {
                boolean invokesTask = false;
                for (var instruction : method(node, "run", "()V").instructions) {
                    if (instruction instanceof MethodInsnNode call && call.owner.equals(PREFIX + "CraftTask")
                            && call.name.equals("run") && call.desc.equals("()V")) invokesTask = true;
                }
                require(invokesTask, node, "Async task must still delegate its body to CraftTask.run");
            }
            default -> throw new IllegalStateException("Unexpected scheduler target " + node.name);
        }
    }
    private static org.objectweb.asm.tree.MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(desc)).findFirst()
                .orElseThrow(() -> new IllegalStateException("SneakyDiagnostics incompatible scheduler: " + node.name + "." + name + desc));
    }
    private static void field(ClassNode node, String name, String desc) {
        require(node.fields.stream().anyMatch(field -> field.name.equals(name) && field.desc.equals(desc)), node, "missing " + name + ":" + desc);
    }
    private static void require(boolean condition, ClassNode node, String detail) {
        if (!condition) throw new IllegalStateException("SneakyDiagnostics incompatible scheduler: " + node.name + ": " + detail);
    }
}
