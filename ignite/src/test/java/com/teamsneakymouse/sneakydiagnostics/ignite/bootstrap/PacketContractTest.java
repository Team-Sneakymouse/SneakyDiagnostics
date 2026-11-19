package com.teamsneakymouse.sneakydiagnostics.ignite.bootstrap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

class PacketContractTest {
    @Test void baselineServerSatisfiesThePacketContract() throws Exception {
        for (String name : PacketContract.CLASSES) PacketContract.verify(read(name));
    }

    /** Classes transformed by several hook sets satisfy every contract that covers them (as preApply checks). */
    @Test void sharedTargetsSatisfyEveryContract() throws Exception {
        var entity = read(PacketContract.SERVER_ENTITY);
        assertTrue(TrackerContract.covers(entity.name) && PacketContract.covers(entity.name));
        TrackerContract.verify(entity);
        PacketContract.verify(entity);
        for (String name : new String[]{PacketContract.LEVEL, PacketContract.SERVER}) {
            var node = read(name);
            assertTrue(PacketContract.covers(name));
            assertTrue(PhaseContract.verify(node), name + " is a phase target");
            PacketContract.verify(node);
        }
        assertFalse(PacketContract.covers(PhaseContract.CHUNKS), "ServerChunkCache is not a packet target (entity.event wraps LivingEntity/ServerLevel)");
        assertFalse(PacketContract.covers(PhaseContract.CHUNK_MAP));
    }

    @Test void aNewSendPathAroundConnectionSendIsRejected() throws Exception {
        var connection = read(PacketContract.CONNECTION);
        var bypass = new MethodNode(Opcodes.ACC_PUBLIC, "sendFast", PacketContract.SEND_1, null, null);
        bypass.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, PacketContract.CONNECTION, "sendPacket", PacketContract.SEND_PACKET));
        bypass.instructions.add(new InsnNode(Opcodes.RETURN));
        connection.methods.add(bypass);
        var error = assertThrows(IllegalStateException.class, () -> PacketContract.verify(connection));
        assertTrue(error.getMessage().contains("sendFast"), error.getMessage());
    }

    @Test void anOverloadThatStopsDelegatingToTheWrappedSendIsRejected() throws Exception {
        var connection = read(PacketContract.CONNECTION);
        method(connection, "send", PacketContract.SEND_2).instructions.clear();
        assertThrows(IllegalStateException.class, () -> PacketContract.verify(connection));
    }

    @Test void inboundHandlingThatNoLongerRunsThroughListenerAndPacketIsRejected() throws Exception {
        var processor = read(PacketContract.PROCESSOR);
        method(processor, "executeSinglePacket", "()Z").instructions.clear();
        assertThrows(IllegalStateException.class, () -> PacketContract.verify(processor));
        var server = read(PacketContract.SERVER);
        method(server, "pollTask", "()Z").instructions.clear();
        assertThrows(IllegalStateException.class, () -> PacketContract.verify(server));
    }

    @Test void missingCauseTargetsAreRejected() throws Exception {
        var players = read(PacketContract.PLAYERS);
        players.methods.removeIf(m -> m.name.equals("broadcastAll") && m.desc.equals("(" + PacketContract.PACKET + ")V"));
        assertThrows(IllegalStateException.class, () -> PacketContract.verify(players));
        var entity = read(PacketContract.SERVER_ENTITY);
        entity.methods.removeIf(m -> m.name.equals("addPairing"));
        assertThrows(IllegalStateException.class, () -> PacketContract.verify(entity));
        var loader = read(PacketContract.CHUNK_LOADER);
        loader.methods.removeIf(m -> m.name.equals("sendUnloadChunkRaw"));
        assertThrows(IllegalStateException.class, () -> PacketContract.verify(loader));
    }

    @Test void particleOverloadsThatBypassTheWrappedFunnelAreRejected() throws Exception {
        var level = read(PacketContract.LEVEL);
        method(level, "sendParticlesSource", PacketContract.PARTICLES_ENTITY).instructions.clear();
        assertThrows(IllegalStateException.class, () -> PacketContract.verify(level));
        var player = read(PacketContract.CRAFT_PLAYER);
        player.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "spawnParticle", "(Lorg/bukkit/Particle;DDDI)V", null, null));
        assertThrows(IllegalStateException.class, () -> PacketContract.verify(player));
    }

    @Test void allMixinsAreRegistered() {
        for (String mixin : SchedulerMixins.MIXINS) {
            assertNotNull(getClass().getClassLoader().getResource("com/teamsneakymouse/sneakydiagnostics/ignite/mixin/" + mixin + ".class"), mixin);
        }
        assertEquals(11, SchedulerMixins.MIXINS.stream().filter(m -> m.startsWith("PacketMixin")).count());
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
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
            assertNotNull(input, "Baseline Paper server must be on the test classpath: " + name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
