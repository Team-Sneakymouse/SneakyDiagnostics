package com.teamsneakymouse.sneakydiagnostics.ignite.bootstrap;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.ObjectName;
import org.bukkit.craftbukkit.scheduler.CraftScheduler;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Executes real transformed scheduler classes without starting Minecraft, worlds, or a network listener. */
public final class SchedulerProof {
    public static void main(String[] args) throws Exception {
        try { prove(); System.out.println("PASS Ignite scheduler hooks: sync/async/consumer/callable execution, exception preservation, cancellations, deferred attribution, and idle behavior"); System.exit(0); }
        catch (Throwable e) { e.printStackTrace(); System.exit(1); }
    }
    private static void prove() throws Exception {
        var scheduler = new CraftScheduler();
        var server = ManagementFactory.getPlatformMBeanServer();
        var name = new ObjectName("com.teamsneakymouse.sneakydiagnostics:type=SchedulerProbe");
        check(server.isRegistered(name), "Scheduler control MBean missing");
        check(Integer.valueOf(1).equals(server.getAttribute(name, "ProtocolVersion")), "Wrong protocol");
        Plugin owner = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, values) -> switch (method.getName()) {
            case "getName" -> "ProofPlugin";
            case "isEnabled" -> true;
            case "getLogger" -> java.util.logging.Logger.getLogger("ProofPlugin");
            case "equals" -> proxy == values[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> throw new AssertionError("Unexpected plugin access: " + method.getName());
        });
        AtomicInteger calls = new AtomicInteger();
        scheduler.runTask(owner, (Runnable) calls::incrementAndGet);
        scheduler.mainThreadHeartbeat();
        check(calls.get() == 1, "Idle instrumentation changed execution");
        server.invoke(name, "start", new Object[]{"proof", System.nanoTime(), 60}, new String[]{"java.lang.String", "long", "int"});
        BukkitTask task = scheduler.runTaskTimer(owner, (Runnable) calls::incrementAndGet, 0, 10);
        scheduler.runTask(owner, value -> calls.incrementAndGet());
        BukkitTask async = scheduler.runTaskAsynchronously(owner, (Runnable) calls::incrementAndGet);
        ((Runnable) async).run();
        Future<String> future = scheduler.callSyncMethod(owner, (Callable<String>) () -> "callable-result");
        Future<String> failed = scheduler.callSyncMethod(owner, (Callable<String>) () -> { throw new Exception("expected callable failure"); });
        scheduler.mainThreadHeartbeat();
        check("callable-result".equals(future.get()), "Callable return value changed");
        try { failed.get(); throw new AssertionError("Callable exception was swallowed"); }
        catch (java.util.concurrent.ExecutionException expected) { check(expected.getCause().getMessage().equals("expected callable failure"), "Callable exception changed"); }
        BukkitTask throwing = scheduler.runTask(owner, (Runnable) () -> { throw new IllegalArgumentException("expected runnable failure"); });
        try { ((Runnable) throwing).run(); throw new AssertionError("Runnable exception was swallowed"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().equals("expected runnable failure"), "Runnable exception changed"); }
        scheduler.cancelTask(throwing.getTaskId());
        scheduler.cancelTask(task.getTaskId());
        scheduler.cancelTask(task.getTaskId());
        scheduler.cancelTasks(owner);
        Future<String> cancelled = scheduler.callSyncMethod(owner, (Callable<String>) () -> "must not run");
        check(cancelled.cancel(false), "Future cancellation failed");
        scheduler.mainThreadHeartbeat();
        Map<?, ?> report = (Map<?, ?>) server.invoke(name, "stop", new Object[]{"proof"}, new String[]{"java.lang.String"});
        List<?> groups = (List<?>) report.get("groups");
        check(groups.stream().map(value -> (Map<?, ?>) value).anyMatch(row -> row.get("event").equals("deferred_cancel_scan") && row.get("owner").equals("ProofPlugin")), "Deferred cancellation lost owner attribution");
        check(groups.stream().map(value -> (Map<?, ?>) value).anyMatch(row -> row.get("event").equals("execution") && Boolean.FALSE.equals(row.get("sync"))), "Async execution missing");
        check(groups.stream().map(value -> (Map<?, ?>) value).anyMatch(row -> row.get("event").equals("execution") && ((Number) ((Map<?, ?>) row.get("counts")).get("throwing")).longValue() > 0), "Throwing execution missing");
        check(((Number) report.get("repeatedTaskCancellationRequests")).longValue() >= 1, "Repeated cancellation missing");
        check(((Number) report.get("inFlightAtStop")).longValue() == 0, "Unbalanced scopes");
        check(server.getAttribute(name, "Status").equals("available, idle"), "Probe did not return to idle");
        // Exercise the plugin's bridge in its own classloader, with no companion classes bundled there.
        if (pluginJar != null) checkPluginBridge(pluginJar);
        benchmark(scheduler, owner, calls, server, name);
    }
    private static final String pluginJar = System.getProperty("sneakydiagnostics.proof.pluginJar");
    private static void checkPluginBridge(String path) throws Exception {
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{java.nio.file.Path.of(path).toUri().toURL()}, SchedulerProof.class.getClassLoader())) {
            Class<?> type = Class.forName("com.teamsneakymouse.sneakydiagnostics.scheduler.IgniteSchedulerProbe", true, loader);
            var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
            Object bridge = constructor.newInstance();
            var available = type.getDeclaredMethod("available"); available.setAccessible(true);
            check(Boolean.TRUE.equals(available.invoke(bridge)), "Plugin classloader cannot see the companion protocol");
            Class<?> contextType = Class.forName("com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext", true, loader);
            Object context = contextType.getConstructor(String.class, String.class, long.class, int.class, int.class)
                    .newInstance("plugin-bridge", "proof", System.nanoTime(), 0, 60);
            var start = type.getDeclaredMethod("start", contextType); start.setAccessible(true); start.invoke(bridge, context);
            var stop = type.getDeclaredMethod("stop", String.class); stop.setAccessible(true);
            check(((Map<?, ?>) stop.invoke(bridge, "plugin-bridge")).get("captureId").equals("plugin-bridge"), "Cross-classloader capture failed");
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void benchmark(CraftScheduler scheduler, Plugin owner, AtomicInteger calls,
                                  javax.management.MBeanServer server, ObjectName name) throws Exception {
        Runnable task = (Runnable) scheduler.runTask(owner, (Runnable) calls::incrementAndGet);
        for (int i = 0; i < 20_000; i++) task.run();
        int count = 100_000;
        long begin = System.nanoTime();
        for (int i = 0; i < count; i++) task.run();
        long idle = System.nanoTime() - begin;
        server.invoke(name, "start", new Object[]{"benchmark", System.nanoTime(), 60}, new String[]{"java.lang.String", "long", "int"});
        for (int i = 0; i < 20_000; i++) task.run();
        begin = System.nanoTime();
        for (int i = 0; i < count; i++) task.run();
        long active = System.nanoTime() - begin;
        server.invoke(name, "stop", new Object[]{"benchmark"}, new String[]{"java.lang.String"});
        System.out.println("INFO synthetic task-run cost: idle=" + idle / count + " ns/op, recording=" + active / count
                + " ns/op; warm single-thread loop, excludes creation/cancellation and is not a server TPS benchmark");
    }
}
