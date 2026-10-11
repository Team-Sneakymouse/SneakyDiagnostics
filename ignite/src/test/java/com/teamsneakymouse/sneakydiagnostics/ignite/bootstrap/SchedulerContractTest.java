package com.teamsneakymouse.sneakydiagnostics.ignite.bootstrap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerContractTest {
    @Test void baselineServerSatisfiesAllHookContracts() throws Exception {
        for (String name : new String[]{"CraftScheduler", "CraftTask", "CraftFuture", "CraftAsyncTask"}) SchedulerContract.verify(read(name));
    }
    @Test void changedTargetIsRejectedRatherThanSilentlySkippingProbe() throws Exception {
        var type = read("CraftTask");
        type.methods.removeIf(method -> method.name.equals("getTaskClass"));
        assertThrows(IllegalStateException.class, () -> SchedulerContract.verify(type));
    }
    @Test void movingInternalExecutionOutsideMeasuredPathIsRejected() throws Exception {
        var type = read("CraftScheduler");
        type.methods.stream().filter(method -> method.name.equals("parsePending")).findFirst().orElseThrow().instructions.clear();
        assertThrows(IllegalStateException.class, () -> SchedulerContract.verify(type));
    }
    private ClassNode read(String name) throws Exception {
        String external = System.getProperty("compatibilityServerJar");
        if (external != null) {
            try (var jar = new java.util.zip.ZipFile(external)) {
                var entry = jar.getEntry("org/bukkit/craftbukkit/scheduler/" + name + ".class");
                assertNotNull(entry, "Use the actual server JAR under versions/, not the Paperclip launcher");
                try (var input = jar.getInputStream(entry)) {
                    var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
                }
            }
        }
        try (var input = getClass().getClassLoader().getResourceAsStream("org/bukkit/craftbukkit/scheduler/" + name + ".class")) {
            assertNotNull(input, "Baseline Paper server must be on the test classpath");
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
