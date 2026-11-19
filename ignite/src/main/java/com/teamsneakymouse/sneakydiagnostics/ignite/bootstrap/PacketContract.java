package com.teamsneakymouse.sneakydiagnostics.ignite.bootstrap;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Structural contracts of the packet hooks (Paper 26.2, Mojang-mapped, Moonrise): every wrapped method exists, the
 * overloads the hooks rely on still delegate to the wrapped ones, and nothing new sends around {@code Connection.send}.
 * A mismatch fails startup instead of counting some packets.
 */
public final class PacketContract {
    static final String CONNECTION = "net/minecraft/network/Connection";
    static final String PROCESSOR = "net/minecraft/network/PacketProcessor";
    static final String LISTENER_AND_PACKET = "net/minecraft/network/PacketProcessor$ListenerAndPacket";
    static final String SERVER_ENTITY = "net/minecraft/server/level/ServerEntity";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    static final String LEVEL = "net/minecraft/server/level/ServerLevel";
    static final String PLAYERS = "net/minecraft/server/players/PlayerList";
    static final String CRAFT_PLAYER = "org/bukkit/craftbukkit/entity/CraftPlayer";
    static final String CHUNK_SENDER = "net/minecraft/server/network/PlayerChunkSender";
    static final String CHUNK_LOADER = "ca/spottedleaf/moonrise/patches/chunk_system/player/RegionizedPlayerChunkLoader$PlayerChunkLoaderData";
    static final String SERVER = "net/minecraft/server/MinecraftServer";
    /** Classes the packet mixins transform. */
    static final Set<String> TRANSFORMED = Set.of(CONNECTION, LISTENER_AND_PACKET, SERVER_ENTITY, ENTITY, LIVING, LEVEL, PLAYERS,
            CRAFT_PLAYER, CHUNK_SENDER, CHUNK_LOADER, SERVER);
    /** Every class whose structure the packet hooks depend on ({@code PacketProcessor} is checked, not transformed). */
    public static final List<String> CLASSES = List.of(CONNECTION, PROCESSOR, LISTENER_AND_PACKET, SERVER_ENTITY, ENTITY, LIVING, LEVEL,
            PLAYERS, CRAFT_PLAYER, CHUNK_SENDER, CHUNK_LOADER, SERVER);

    static final String PACKET = "Lnet/minecraft/network/protocol/Packet;";
    static final String SEND_1 = "(" + PACKET + ")V";
    static final String SEND_2 = "(" + PACKET + "Lio/netty/channel/ChannelFutureListener;)V";
    static final String SEND_3 = "(" + PACKET + "Lio/netty/channel/ChannelFutureListener;Z)V";
    static final String SEND_PACKET = SEND_3;
    /** Methods of {@code Connection} allowed to call {@code sendPacket} directly (login hand-off, spam kick, client side). */
    static final List<String> SEND_PACKET_CALLERS = List.of("send", "killForPacketSpam", "lambda$initiateServerboundConnection$",
            "lambda$sendPacketAndScheduleNettyTask$");
    static final String PLAYER = "Lnet/minecraft/server/level/ServerPlayer;";
    static final String CHUNK_CACHE = "net/minecraft/server/level/ServerChunkCache";
    static final String TO_TRACKING = "(Lnet/minecraft/world/entity/Entity;" + PACKET + ")V";
    static final String PARTICLE_ARGS = "ZZDDDIDDDD";
    static final String PARTICLES_LIST = "(Ljava/util/List;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;" + PARTICLE_ARGS + ")I";
    static final String PARTICLES_ENTITY = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;" + PARTICLE_ARGS + ")I";
    static final String PARTICLES_PLAYER = "(" + PLAYER + "Lnet/minecraft/core/particles/ParticleOptions;" + PARTICLE_ARGS + ")Z";
    static final String ADD_FRESH = "(Lnet/minecraft/world/entity/Entity;Lorg/bukkit/event/entity/CreatureSpawnEvent$SpawnReason;)Z";
    static final String CRAFT_PARTICLE = "(Lorg/bukkit/Particle;DDDIDDDDLjava/lang/Object;Z)V";
    static final String SEND_CHUNK = "(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/level/chunk/LevelChunk;)V";
    static final List<String[]> BROADCASTS = List.of(
            new String[]{"broadcast", "(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;" + PACKET + ")V"},
            new String[]{"broadcastAll", "(" + PACKET + ")V"},
            new String[]{"broadcastAll", "(" + PACKET + "Lnet/minecraft/resources/ResourceKey;)V"},
            new String[]{"broadcastAll", "(" + PACKET + "Lnet/minecraft/world/entity/player/Player;)V"},
            new String[]{"broadcastAll", "(" + PACKET + "Lnet/minecraft/world/level/Level;)V"},
            new String[]{"broadcastSystemMessage", "(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V"},
            new String[]{"broadcastChatMessage", "(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;" + PLAYER
                    + "Lnet/minecraft/network/chat/ChatType$Bound;Ljava/util/function/Function;)V"});

    private PacketContract() {}

    /** True for classes a packet mixin transforms. */
    public static boolean covers(String internalName) { return TRANSFORMED.contains(internalName); }

    /** Verifies every class in {@link #CLASSES} from {@code loader} before any mixin applies. */
    public static void verifyAll(ClassLoader loader) {
        for (String name : CLASSES) {
            try (var input = loader.getResourceAsStream(name + ".class")) {
                if (input == null) throw new IllegalStateException("SneakyDiagnostics incompatible server: missing packet target " + name);
                var node = new ClassNode();
                new org.objectweb.asm.ClassReader(input).accept(node, 0);
                verify(node);
            } catch (java.io.IOException e) { throw new IllegalStateException("Cannot verify packet target " + name, e); }
        }
    }

    public static void verify(ClassNode node) {
        switch (node.name) {
            case CONNECTION -> {
                exactly(node, method(node, "send", SEND_1), CONNECTION, "send", SEND_2, 1);
                exactly(node, method(node, "send", SEND_2), CONNECTION, "send", SEND_3, 1);
                require(count(method(node, "send", SEND_3), CONNECTION, "sendPacket", SEND_PACKET) >= 1, node,
                        "send" + SEND_3 + " must send through sendPacket");
                for (MethodNode method : node.methods) {
                    if (count(method, CONNECTION, "sendPacket", SEND_PACKET) == 0) continue;
                    require(SEND_PACKET_CALLERS.stream().anyMatch(allowed -> allowed.endsWith("$") ? method.name.startsWith(allowed) : method.name.equals(allowed)),
                            node, method.name + method.desc + " sends around Connection.send; outbound counts would miss it");
                }
            }
            case PROCESSOR -> {
                exactly(node, method(node, "executeSinglePacket", "()Z"), LISTENER_AND_PACKET, "handle", "()V", 1);
                exactly(node, method(node, "processQueuedPackets", "()V"), LISTENER_AND_PACKET, "handle", "()V", 1);
            }
            case LISTENER_AND_PACKET -> {
                field(node, "packet", PACKET);
                exactly(node, method(node, "handle", "()V"), "net/minecraft/network/protocol/Packet", "handle",
                        "(Lnet/minecraft/network/PacketListener;)V", 1);
            }
            case SERVER_ENTITY -> {
                field(node, "entity", "Lnet/minecraft/world/entity/Entity;");
                require(count(method(node, "sendChanges", "()V"), SERVER_ENTITY, "sendDirtyEntityData", "()V") >= 1, node,
                        "sendChanges must still send metadata through sendDirtyEntityData");
                method(node, "addPairing", "(" + PLAYER + ")V");
                method(node, "removePairing", "(" + PLAYER + ")V");
            }
            case ENTITY -> require((node.access & Opcodes.ACC_INTERFACE) == 0, node, "Entity must be a class");
            case LIVING -> {
                exactly(node, method(node, "handleEquipmentChanges", "(Ljava/util/Map;)V"), CHUNK_CACHE, "sendToTrackingPlayers", TO_TRACKING, 1);
                MethodNode swing = method(node, "swing", "(Lnet/minecraft/world/InteractionHand;Z)V");
                require(count(swing, CHUNK_CACHE, "sendToTrackingPlayers", TO_TRACKING) + count(swing, CHUNK_CACHE, "sendToTrackingPlayersAndSelf", TO_TRACKING) >= 1,
                        node, "swing must broadcast through ServerChunkCache");
            }
            case LEVEL -> {
                exactly(node, method(node, "broadcastEntityEvent", "(Lnet/minecraft/world/entity/Entity;B)V"), CHUNK_CACHE, "sendToTrackingPlayersAndSelf", TO_TRACKING, 1);
                exactly(node, method(node, "broadcastDamageEvent", "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;)V"),
                        CHUNK_CACHE, "sendToTrackingPlayersAndSelf", TO_TRACKING, 1);
                exactly(node, method(node, "sendParticles", "(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"), LEVEL, "sendParticlesSource", PARTICLES_ENTITY, 1);
                exactly(node, method(node, "sendParticles", "(Lnet/minecraft/core/particles/ParticleOptions;" + PARTICLE_ARGS + ")I"), LEVEL, "sendParticlesSource", PARTICLES_ENTITY, 1);
                exactly(node, method(node, "sendParticlesSource", PARTICLES_ENTITY), LEVEL, "sendParticlesSource", PARTICLES_LIST, 1);
                method(node, "sendParticlesSource", PARTICLES_LIST);
                method(node, "sendParticles", PARTICLES_PLAYER);
                exactly(node, method(node, "addFreshEntity", "(Lnet/minecraft/world/entity/Entity;)Z"), LEVEL, "addFreshEntity", ADD_FRESH, 1);
                method(node, "addFreshEntity", ADD_FRESH);
            }
            case PLAYERS -> { for (String[] broadcast : BROADCASTS) method(node, broadcast[0], broadcast[1]); }
            case CRAFT_PLAYER -> {
                method(node, "spawnParticle", CRAFT_PARTICLE);
                require(node.methods.stream().filter(m -> m.name.equals("spawnParticle")).count() == 1, node,
                        "CraftPlayer must implement exactly one spawnParticle (the API overloads delegate to it)");
            }
            case CHUNK_SENDER -> require((method(node, "sendChunk", SEND_CHUNK).access & Opcodes.ACC_STATIC) != 0, node, "sendChunk must be static");
            case CHUNK_LOADER -> {
                exactly(node, method(node, "sendChunk", "(II)V"), CHUNK_SENDER, "sendChunk", SEND_CHUNK, 1);
                method(node, "sendUnloadChunkRaw", "(II)V");
            }
            case SERVER -> exactly(node, method(node, "pollTask", "()Z"), PROCESSOR, "executeSinglePacket", "()Z", 1);
            default -> throw new IllegalStateException("Unexpected packet target " + node.name);
        }
    }

    private static int count(MethodNode method, String owner, String name, String desc) {
        int found = 0;
        for (var insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc)) found++;
        }
        return found;
    }
    private static void exactly(ClassNode node, MethodNode method, String owner, String name, String desc, int expected) {
        int found = count(method, owner, name, desc);
        require(found == expected, node, method.name + method.desc + " must call " + owner + "." + name + desc + " "
                + expected + " time(s), found " + found);
    }
    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
                .orElseThrow(() -> new IllegalStateException("SneakyDiagnostics incompatible server (packets): " + node.name + "." + name + desc));
    }
    private static void field(ClassNode node, String name, String desc) {
        require(node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(desc)), node, "missing field " + name + ":" + desc);
    }
    private static void require(boolean condition, ClassNode node, String detail) {
        if (!condition) throw new IllegalStateException("SneakyDiagnostics incompatible server (packets): " + node.name + ": " + detail);
    }
}
