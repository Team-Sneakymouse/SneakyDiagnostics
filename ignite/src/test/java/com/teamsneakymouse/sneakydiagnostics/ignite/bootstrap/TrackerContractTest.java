package com.teamsneakymouse.sneakydiagnostics.ignite.bootstrap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;

class TrackerContractTest {
    @Test void baselineServerSatisfiesTheTrackerContract() throws Exception {
        TrackerContract.verify(read(TrackerContract.SERVER_ENTITY));
        TrackerContract.verify(read(TrackerContract.CHUNK_MAP));
    }
    @Test void metadataSentOutsideTheTimedSendIsRejected() throws Exception {
        var type = read(TrackerContract.SERVER_ENTITY);
        type.methods.stream().filter(m -> m.name.equals("sendChanges")).findFirst().orElseThrow().instructions.clear();
        assertThrows(IllegalStateException.class, () -> TrackerContract.verify(type));
    }
    @Test void trackerLoopThatStopsCallingSendChangesIsRejected() throws Exception {
        var type = read(TrackerContract.CHUNK_MAP);
        type.methods.stream().filter(m -> m.name.equals("newTrackerTick")).findFirst().orElseThrow().instructions.clear();
        assertThrows(IllegalStateException.class, () -> TrackerContract.verify(type));
    }
    @Test void missingEntityFieldIsRejected() throws Exception {
        var type = read(TrackerContract.SERVER_ENTITY);
        type.fields.removeIf(f -> f.name.equals("entity"));
        assertThrows(IllegalStateException.class, () -> TrackerContract.verify(type));
    }
    private ClassNode read(String name) throws Exception {
        String external = System.getProperty("compatibilityServerJar");
        if (external != null) {
            try (var jar = new java.util.zip.ZipFile(external)) {
                var entry = jar.getEntry(name + ".class");
                assertNotNull(entry, "Use the actual server JAR under versions/, not the Paperclip launcher");
                try (var input = jar.getInputStream(entry)) {
                    var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
                }
            }
        }
        try (var input = getClass().getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, "Baseline Paper server must be on the test classpath");
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
