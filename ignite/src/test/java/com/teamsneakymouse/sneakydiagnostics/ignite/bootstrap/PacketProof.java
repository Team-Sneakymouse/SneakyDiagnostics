package com.teamsneakymouse.sneakydiagnostics.ignite.bootstrap;

import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PacketProbe;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.SpawnOwner;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;

/**
 * Packet part of the runtime proof: every packet target is transformed by real Ignite/Mixin (injection counts are
 * enforced at class definition), a real {@code Connection.send} (no channel: the packet is queued) and a real
 * {@code ListenerAndPacket.handle} are recorded through the hooks, and the plugin's bridge drives the probe from its own
 * classloader. Prints idle and recording costs of both hooks and of one cause frame.
 */
final class PacketProof {
    private static final String NAME = "com.teamsneakymouse.sneakydiagnostics:type=ServerProbe";
    static final String IDLE = "phases available, idle; tracker available, idle; packets available, idle";
    private static final String[] START = {"java.lang.String", "java.lang.String", "long", "int"}, STOP = {"java.lang.String", "java.lang.String"};
    /** Target class -> a handler name its packet mixin adds. */
    private static final Map<String, String> TARGETS = Map.ofEntries(
            Map.entry("net.minecraft.network.Connection", "diagnostics$send"),
            Map.entry("net.minecraft.network.PacketProcessor$ListenerAndPacket", "diagnostics$handle"),
            Map.entry("net.minecraft.server.MinecraftServer", "diagnostics$betweenTicks"),
            Map.entry("net.minecraft.server.level.ServerEntity", "diagnostics$despawn"),
            Map.entry("net.minecraft.world.entity.LivingEntity", "diagnostics$swing"),
            Map.entry("net.minecraft.server.level.ServerLevel", "diagnostics$spawner"),
            Map.entry("net.minecraft.server.players.PlayerList", "diagnostics$chat"),
            Map.entry("org.bukkit.craftbukkit.entity.CraftPlayer", "diagnostics$particle"),
            Map.entry("net.minecraft.server.network.PlayerChunkSender", "diagnostics$chunk"),
            Map.entry("ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader$PlayerChunkLoaderData", "diagnostics$unload"));
    private static final int WARMUP = 20_000, COUNT = 100_000, ROUNDS = 5;

    private PacketProof() {}

    static void prove() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(NAME);
        check(IDLE.equals(server.getAttribute(name, "Status")), "Server probes not idle: " + server.getAttribute(name, "Status"));
        ClassLoader loader = PacketProof.class.getClassLoader();
        for (var target : TARGETS.entrySet()) {
            Class<?> type = Class.forName(target.getKey(), false, loader);
            var handlers = Arrays.stream(type.getDeclaredMethods()).map(java.lang.reflect.Method::getName).toList();
            check(handlers.stream().anyMatch(n -> n.endsWith(target.getValue())), "Packet hook " + target.getValue() + " not applied to " + target.getKey());
        }
        var serverEntity = Arrays.stream(Class.forName("net.minecraft.server.level.ServerEntity", false, loader).getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).toList();
        check(serverEntity.stream().anyMatch(n -> n.endsWith("diagnostics$sendChanges")) && serverEntity.stream().anyMatch(n -> n.endsWith("diagnostics$update")),
                "ServerEntity must carry both the tracker and the packet hooks: " + serverEntity);
        check(SpawnOwner.class.isAssignableFrom(Class.forName("net.minecraft.world.entity.Entity", false, loader)), "Entity does not implement SpawnOwner");

        var connection = connection();
        var listener = listener();
        Object inbound = inbound(listener, new ServerboundKeepAlivePacket(7));
        var handle = inbound.getClass().getDeclaredMethod("handle");
        handle.setAccessible(true);

        server.invoke(name, "start", new Object[]{"packets", "packet-proof", System.nanoTime(), 60}, START);
        connection.send(new ClientboundKeepAlivePacket(1));
        connection.send(new ClientboundBundlePacket(List.of(new ClientboundKeepAlivePacket(2), new ClientboundKeepAlivePacket(3))));
        var other = new Thread(() -> connection.send(new ClientboundKeepAlivePacket(4)));
        other.start();
        other.join();
        var session = PacketProbe.active();
        check(session != null, "Packet probe not recording on the starting thread");
        int frame = session.enter(PacketProbe.BROADCAST, 0);
        connection.send(new ClientboundKeepAlivePacket(5));
        session.exit(frame);
        handle.invoke(inbound);
        Map<?, ?> report = (Map<?, ?>) server.invoke(name, "stop", new Object[]{"packets", "packet-proof"}, STOP);
        clear(connection);
        Map<?, ?> outbound = (Map<?, ?>) report.get("outbound");
        check(Long.valueOf(4).equals(outbound.get("sends")) && Long.valueOf(3).equals(outbound.get("serverThreadSends"))
                && Long.valueOf(1).equals(outbound.get("otherThreadSends")), "Connection.send not counted per thread: " + outbound);
        check(Long.valueOf(1).equals(outbound.get("bundles")) && Long.valueOf(2).equals(outbound.get("bundledPackets")), "Bundle not unpacked: " + outbound);
        var keepAlive = row((List<?>) outbound.get("classes"), ClientboundKeepAlivePacket.class.getName());
        check(Long.valueOf(3).equals(keepAlive.get("sends")) && Long.valueOf(2).equals(keepAlive.get("bundled")), "Keep-alive class row wrong: " + keepAlive);
        var broadcast = row((List<?>) ((Map<?, ?>) report.get("causes")).get("rows"), "broadcast", "cause");
        check(Long.valueOf(1).equals(broadcast.get("packets")) && Long.valueOf(1).equals(broadcast.get("broadcasts")), "Cause not attributed: " + broadcast);
        Map<?, ?> in = (Map<?, ?>) report.get("inbound");
        check(Long.valueOf(1).equals(in.get("packets")) && ServerboundKeepAlivePacket.class.getName().equals(((Map<?, ?>) ((List<?>) in.get("classes")).getFirst()).get("packetClass")),
                "Inbound handle not recorded: " + in);
        check(IDLE.equals(server.getAttribute(name, "Status")), "Packet probe did not return to idle");
    }

    /** The plugin's shared bridge, in its own classloader, starts and stops a packets capture. */
    static void checkPluginBridge(String path) throws Exception {
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{java.nio.file.Path.of(path).toUri().toURL()}, PacketProof.class.getClassLoader())) {
            Class<?> type = Class.forName("com.teamsneakymouse.sneakydiagnostics.serverprobe.IgniteServerProbe", true, loader);
            Object bridge = type.getConstructor(String.class).newInstance("packets");
            check(Boolean.TRUE.equals(type.getMethod("available").invoke(bridge)), "Plugin classloader cannot see the packets probe");
            Class<?> contextType = Class.forName("com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext", true, loader);
            Object context = contextType.getConstructor(String.class, String.class, long.class, int.class, int.class)
                    .newInstance("plugin-packets", "proof", System.nanoTime(), 0, 60);
            type.getMethod("start", contextType).invoke(bridge, context);
            Map<?, ?> packets = (Map<?, ?>) type.getMethod("stop", String.class).invoke(bridge, "plugin-packets");
            check(packets != null && "plugin-packets".equals(packets.get("captureId")) && packets.get("outbound") instanceof Map,
                    "Cross-classloader packets capture failed: " + packets);
        }
    }

    static void benchmark() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(NAME);
        var connection = connection();
        Packet<?> packet = new ClientboundKeepAlivePacket(1);
        Object inbound = inbound(listener(), new ServerboundKeepAlivePacket(7));
        var handleMethod = inbound.getClass().getDeclaredMethod("handle");
        handleMethod.setAccessible(true);
        var handle = (Runnable) () -> {
            try { handleMethod.invoke(inbound); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        };
        Runnable send = () -> connection.send(packet);
        Runnable[] bodies = {send, handle};
        long[][] idleRounds = new long[bodies.length][ROUNDS], recordingRounds = new long[bodies.length][ROUNDS];
        long[] frameRounds = new long[ROUNDS], plainRounds = new long[ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
            for (int i = 0; i < bodies.length; i++) { idleRounds[i][round] = loop(bodies[i]); clear(connection); }
            server.invoke(name, "start", new Object[]{"packets", "packet-benchmark", System.nanoTime(), 60}, START);
            for (int i = 0; i < bodies.length; i++) { recordingRounds[i][round] = loop(bodies[i]); clear(connection); }
            var session = PacketProbe.active();
            plainRounds[round] = loop(() -> { if (PacketProbe.active() == null) throw new IllegalStateException(); });
            frameRounds[round] = loop(() -> {
                var active = PacketProbe.active();
                active.exit(active.enter(PacketProbe.PARTICLES, 0));
            });
            check(session != null, "Benchmark capture not recording");
            Map<?, ?> report = (Map<?, ?>) server.invoke(name, "stop", new Object[]{"packets", "packet-benchmark"}, STOP);
            long sends = ((Number) ((Map<?, ?>) report.get("outbound")).get("serverThreadSends")).longValue();
            check(sends == WARMUP + COUNT, "Packet probe lost benchmark sends: " + sends);
        }
        System.out.println("INFO synthetic packet hook cost (ns/op, idle -> recording, median of " + ROUNDS + " rounds; warm single-thread loop, not a server TPS benchmark): "
                + "Connection.send of a keep-alive with no channel (queued) " + median(idleRounds[0]) + " -> " + median(recordingRounds[0])
                + "; ListenerAndPacket.handle of a keep-alive (reflective call included in both) " + median(idleRounds[1]) + " -> " + median(recordingRounds[1])
                + "; one cause frame (active() + enter + exit, probe API) " + median(frameRounds) + " against active() alone " + median(plainRounds)
                + ". Cause hooks on ServerEntity/LivingEntity/ServerLevel/PlayerList/CraftPlayer/chunk sending use the same frame code but need a world.");
    }

    /** A real Connection without a channel: {@code send} queues the packet (no Netty, no encoding). */
    private static Connection connection() throws Exception {
        var instance = io.papermc.paper.configuration.GlobalConfiguration.class.getDeclaredField("instance");
        instance.setAccessible(true);
        if (instance.get(null) == null) {
            // Connection reads misc and packetLimiter; configurate would normally create the parts with their defaults.
            var config = new io.papermc.paper.configuration.GlobalConfiguration();
            config.misc = config.new Misc();
            config.packetLimiter = config.new PacketLimiter();
            fillParts(config.misc);
            fillParts(config.packetLimiter);
            instance.set(null, config);
        }
        return new Connection(PacketFlow.CLIENTBOUND);
    }
    /** Creates every null configuration part (inner classes take their enclosing part), recursively, with field defaults. */
    private static void fillParts(Object part) throws Exception {
        for (var field : part.getClass().getFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            Class<?> type = field.getType();
            if (!io.papermc.paper.configuration.ConfigurationPart.class.isAssignableFrom(type) || field.get(part) != null) continue;
            Object child = type.getEnclosingClass() != null && !java.lang.reflect.Modifier.isStatic(type.getModifiers())
                    ? type.getDeclaredConstructor(type.getEnclosingClass()).newInstance(part) : type.getDeclaredConstructor().newInstance();
            field.set(part, child);
            fillParts(child);
        }
    }
    private static void clear(Connection connection) throws Exception {
        var field = Connection.class.getDeclaredField("pendingActions");
        field.setAccessible(true);
        ((Queue<?>) field.get(connection)).clear();
    }
    private static PacketListener listener() {
        return (PacketListener) Proxy.newProxyInstance(PacketProof.class.getClassLoader(), new Class<?>[]{ServerGamePacketListener.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? Boolean.TRUE : null);
    }
    private static Object inbound(PacketListener listener, Packet<?> packet) throws Exception {
        Class<?> type = Class.forName("net.minecraft.network.PacketProcessor$ListenerAndPacket");
        var constructor = type.getDeclaredConstructor(PacketListener.class, Packet.class);
        constructor.setAccessible(true);
        return constructor.newInstance(listener, packet);
    }
    private static Map<?, ?> row(List<?> rows, String value) { return row(rows, value, "packetClass"); }
    private static Map<?, ?> row(List<?> rows, String value, String field) {
        return rows.stream().map(r -> (Map<?, ?>) r).filter(r -> value.equals(r.get(field))).findFirst()
                .orElseThrow(() -> new AssertionError(value + " missing in " + rows));
    }
    private static long loop(Runnable body) {
        for (int i = 0; i < WARMUP; i++) body.run();
        long begin = System.nanoTime();
        for (int i = 0; i < COUNT; i++) body.run();
        return (System.nanoTime() - begin) / COUNT;
    }
    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
