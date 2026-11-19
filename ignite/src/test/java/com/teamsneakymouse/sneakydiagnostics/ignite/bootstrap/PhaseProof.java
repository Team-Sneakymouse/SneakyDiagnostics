package com.teamsneakymouse.sneakydiagnostics.ignite.bootstrap;

import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PhaseProbe;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PhaseWorld;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.bukkit.craftbukkit.scheduler.CraftScheduler;
import org.bukkit.plugin.Plugin;

/**
 * Tick-phase part of the runtime proof: every phase target is transformed by real Ignite/Mixin (injection counts are
 * enforced at class definition), the ServerProbe MBean answers, and the hooks that can run without a Minecraft server
 * (scheduler heartbeat, task run, listener call) record exclusive phases. Prints idle and recording costs.
 */
final class PhaseProof {
    private static final String NAME = "com.teamsneakymouse.sneakydiagnostics:type=ServerProbe";
    private static final List<String> TARGETS = List.of("net.minecraft.server.MinecraftServer", "net.minecraft.server.level.ServerLevel",
            "net.minecraft.server.level.ServerChunkCache", "net.minecraft.world.level.NaturalSpawner");

    private PhaseProof() {}

    static void prove(CraftScheduler scheduler, Plugin other) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(NAME);
        check(server.isRegistered(name), "Server probe MBean missing");
        check(Integer.valueOf(1).equals(server.getAttribute(name, "ProtocolVersion")), "Wrong server probe protocol");
        check("phases available, idle".equals(server.getAttribute(name, "Status")), "Server probe not idle: " + server.getAttribute(name, "Status"));
        // Defining a target applies its mixins; a missing call site fails here (require = allow = expected count).
        ClassLoader loader = PhaseProof.class.getClassLoader();
        for (String target : TARGETS) {
            Class<?> type = Class.forName(target, false, loader);
            check(Arrays.stream(type.getDeclaredMethods()).anyMatch(method -> method.getName().contains("diagnostics$")),
                    "Phase hooks were not applied to " + target);
        }
        check(PhaseWorld.class.isAssignableFrom(Class.forName("net.minecraft.server.level.ServerLevel", false, loader)),
                "ServerLevel does not implement PhaseWorld");

        Plugin diagnostics = plugin(PhaseProbe.DIAGNOSTICS_PLUGIN);
        var otherRuns = new AtomicInteger();
        server.invoke(name, "start", new Object[]{"phase-proof", System.nanoTime(), 60}, new String[]{"java.lang.String", "long", "int"});
        check(PhaseProbe.active() == null, "Recording must begin at the next tick");
        PhaseProbe.tickBegin(1);
        scheduler.runTask(diagnostics, (Runnable) () -> pause(3));                     // a census slice inside the heartbeat
        scheduler.runTask(other, (Runnable) () -> { otherRuns.incrementAndGet(); pause(2); });
        scheduler.mainThreadHeartbeat();
        new org.bukkit.plugin.RegisteredListener(new org.bukkit.event.Listener() {}, (listener, event) -> pause(1),
                org.bukkit.event.EventPriority.MONITOR, diagnostics, false).callEvent(new SchedulerProof.ProofEvent());
        var session = PhaseProbe.tickEnd(10.0);
        check(session != null, "Tick end not recorded");
        session.tickEndDone();
        PhaseProbe.tickBegin(2);
        Map<?, ?> stopped = (Map<?, ?>) server.invoke(name, "stop", new Object[]{"phase-proof"}, new String[]{"java.lang.String"});
        Map<?, ?> report = (Map<?, ?>) stopped.get("phases");
        check(otherRuns.get() == 1, "Phase hooks changed task execution");
        Map<?, ?> phases = (Map<?, ?>) report.get("phases");
        long diagnosticsMicros = first(phases, "diagnostics"), schedulerMicros = first(phases, "scheduler");
        check(diagnosticsMicros >= 4_000, "SneakyDiagnostics task and listener not in the diagnostics phase: " + diagnosticsMicros);
        check(schedulerMicros >= 2_000 && schedulerMicros < 3_000 + 1_000, "Heartbeat self time must exclude the nested diagnostics task: " + schedulerMicros);
        check(first((Map<?, ?>) report, "ticks", "measuredMicros") == 10_000, "Measured tick duration not recorded");
        check(((List<?>) report.get("other")).getFirst() instanceof Number, "other must be known for a measured tick");
        check("phases available, idle".equals(server.getAttribute(name, "Status")), "Server probe did not return to idle");
    }

    static void benchmark(CraftScheduler scheduler, Plugin other) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(NAME);
        Plugin diagnostics = plugin(PhaseProbe.DIAGNOSTICS_PLUGIN);
        var calls = new AtomicInteger();
        Runnable otherTask = (Runnable) scheduler.runTask(other, (Runnable) calls::incrementAndGet);
        Runnable ownTask = (Runnable) scheduler.runTask(diagnostics, (Runnable) calls::incrementAndGet);
        var otherListener = new org.bukkit.plugin.RegisteredListener(new org.bukkit.event.Listener() {}, (l, e) -> calls.incrementAndGet(),
                org.bukkit.event.EventPriority.NORMAL, other, false);
        var ownListener = new org.bukkit.plugin.RegisteredListener(new org.bukkit.event.Listener() {}, (l, e) -> calls.incrementAndGet(),
                org.bukkit.event.EventPriority.NORMAL, diagnostics, false);
        var event = new SchedulerProof.ProofEvent();
        Runnable heartbeat = scheduler::mainThreadHeartbeat;
        Runnable otherEvent = () -> call(otherListener, event), ownEvent = () -> call(ownListener, event);
        Runnable[] bodies = {heartbeat, otherTask, ownTask, otherEvent, ownEvent};
        // Alternating idle and recording rounds; the median of five per hook damps JIT and scheduling noise.
        long[][] idleRounds = new long[bodies.length][ROUNDS], recordingRounds = new long[bodies.length][ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
            for (int i = 0; i < bodies.length; i++) idleRounds[i][round] = loop(bodies[i]);
            server.invoke(name, "start", new Object[]{"phase-benchmark", System.nanoTime(), 60}, new String[]{"java.lang.String", "long", "int"});
            PhaseProbe.tickBegin(round);
            for (int i = 0; i < bodies.length; i++) recordingRounds[i][round] = loop(bodies[i]);
            Map<?, ?> report = (Map<?, ?>) ((Map<?, ?>) server.invoke(name, "stop", new Object[]{"phase-benchmark"},
                    new String[]{"java.lang.String"})).get("phases");
            Map<?, ?> totals = (Map<?, ?>) report.get("totals");
            long diagnosticsCalls = ((Number) ((Map<?, ?>) totals.get("diagnostics")).get("calls")).longValue();
            check(diagnosticsCalls == 2L * (WARMUP + COUNT), "Diagnostics phase lost benchmark calls: " + diagnosticsCalls);
        }
        long[] idle = new long[bodies.length], recording = new long[bodies.length];
        for (int i = 0; i < bodies.length; i++) { idle[i] = median(idleRounds[i]); recording[i] = median(recordingRounds[i]); }
        System.out.println("INFO synthetic tick-phase hook cost (ns/op, idle -> recording, median of " + ROUNDS + " rounds; warm single-thread loop, not a server TPS benchmark): "
                + "empty scheduler heartbeat " + idle[0] + " -> " + recording[0] + " (one phase frame); "
                + "task run, other owner " + idle[1] + " -> " + recording[1] + ", diagnostics owner " + idle[2] + " -> " + recording[2]
                + "; listener call, other owner " + idle[3] + " -> " + recording[3] + ", diagnostics owner " + idle[4] + " -> " + recording[4]
                + ". Server, level and chunk hooks use the same frame code but cannot run without a Minecraft server.");
    }

    /** The plugin's phases bridge, in its own classloader with no companion classes, starts and stops a phase capture. */
    static void checkPluginBridge(String path) throws Exception {
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{java.nio.file.Path.of(path).toUri().toURL()}, PhaseProof.class.getClassLoader())) {
            Class<?> type = Class.forName("com.teamsneakymouse.sneakydiagnostics.phases.IgniteServerProbe", true, loader);
            var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
            Object bridge = constructor.newInstance();
            var available = type.getDeclaredMethod("available"); available.setAccessible(true);
            check(Boolean.TRUE.equals(available.invoke(bridge)), "Plugin classloader cannot see the server probe protocol");
            Class<?> contextType = Class.forName("com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext", true, loader);
            Object context = contextType.getConstructor(String.class, String.class, long.class, int.class, int.class)
                    .newInstance("plugin-phases", "proof", System.nanoTime(), 0, 60);
            var start = type.getDeclaredMethod("start", contextType); start.setAccessible(true); start.invoke(bridge, context);
            var stop = type.getDeclaredMethod("stop", String.class); stop.setAccessible(true);
            Map<?, ?> phases = (Map<?, ?>) ((Map<?, ?>) stop.invoke(bridge, "plugin-phases")).get("phases");
            check(phases != null && "plugin-phases".equals(phases.get("captureId")), "Cross-classloader phase capture failed");
        }
    }

    private static void call(org.bukkit.plugin.RegisteredListener listener, org.bukkit.event.Event event) {
        try { listener.callEvent(event); }
        catch (org.bukkit.event.EventException e) { throw new IllegalStateException(e); }
    }

    private static final int WARMUP = 20_000, COUNT = 100_000, ROUNDS = 5;
    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
    private static long loop(Runnable body) {
        for (int i = 0; i < WARMUP; i++) body.run();
        long begin = System.nanoTime();
        for (int i = 0; i < COUNT; i++) body.run();
        return (System.nanoTime() - begin) / COUNT;
    }
    private static long first(Map<?, ?> map, String... path) {
        Object value = map;
        for (String key : path) value = ((Map<?, ?>) value).get(key);
        check(value instanceof List<?> list && !list.isEmpty(), "Missing " + String.join(".", path));
        return ((Number) ((List<?>) value).getFirst()).longValue();
    }
    private static Plugin plugin(String pluginName) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, values) -> switch (method.getName()) {
            case "getName" -> pluginName;
            case "isEnabled" -> true;
            case "getLogger" -> java.util.logging.Logger.getLogger(pluginName);
            case "equals" -> proxy == values[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> throw new AssertionError("Unexpected plugin access: " + method.getName());
        });
    }
    private static void pause(long millis) {
        long end = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < end) Thread.onSpinWait();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
