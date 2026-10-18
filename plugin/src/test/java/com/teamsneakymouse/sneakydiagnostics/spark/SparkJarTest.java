package com.teamsneakymouse.sneakydiagnostics.spark;

import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.bukkit.Server;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Executes discovery contracts and binary export with real spark classes in a separate loader. */
class SparkJarTest {
    @TempDir Path directory;

    @Test void bundledSparkIsCancelledBeforeDedicatedCaptureAndExport() throws Exception {
        String jar = System.getProperty("sparkJar");
        assumeTrue(jar != null, "Run :plugin:verifySpark -PsparkJar=...");
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()}, getClass().getClassLoader())) {
            String root = "me.lucko.spark.paper.common";
            Class<?> pluginType = loader.loadClass(root + ".SparkPlugin");
            Class<?> infoType = loader.loadClass(root + ".platform.PlatformInfo");
            Object info = Proxy.newProxyInstance(loader, new Class<?>[]{infoType}, (p, method, args) -> {
                if (method.isDefault()) return InvocationHandler.invokeDefault(p, method, args);
                if (method.getName().equals("getType")) return method.getReturnType().getField("SERVER").get(null);
                return method.getReturnType() == String.class ? "test" : null;
            });
            Class<?> dumperType = loader.loadClass(root + ".sampler.ThreadDumper");
            Object dumper = dumperType.getField("ALL").get(null);
            Object plugin = Proxy.newProxyInstance(loader, new Class<?>[]{pluginType}, (p, method, args) -> switch (method.getName()) {
                case "getPlatformInfo" -> info;
                case "getPluginDirectory" -> directory;
                case "getDefaultThreadDumper" -> dumper;
                case "createClassSourceLookup" -> loader.loadClass(root + ".sampler.source.ClassSourceLookup").getField("NO_OP").get(null);
                case "getVersion", "getCommandName" -> "test";
                case "getKnownSources" -> List.of();
                case "getCommandSenders" -> java.util.stream.Stream.empty();
                default -> null;
            });
            Class<?> platformType = loader.loadClass(root + ".SparkPlatform");
            Object platform = platformType.getConstructor(pluginType).newInstance(plugin);
            var access = new ReflectiveSparkProfiler.Access(platform);
            Class<?> grouperType = loader.loadClass(root + ".sampler.ThreadGrouper");
            var grouper = (Supplier<?>) grouperType.getField("BY_NAME").get(null);
            Class<?> settingsType = loader.loadClass(root + ".sampler.SamplerSettings");
            Object settings = settingsType.getConstructor(int.class, dumperType, grouperType, long.class, boolean.class, boolean.class)
                    .newInstance(4000, dumper, grouper.get(), -1L, true, false);
            // Run a genuine background sampler to exercise cancellation of its task and executor.
            Object sampler = loader.loadClass(root + ".sampler.java.JavaSampler")
                    .getConstructor(platformType, settingsType).newInstance(platform, settings);
            sampler.getClass().getMethod("start").invoke(sampler);
            access.set.invoke(access.container, sampler);
            Object api = loader.loadClass(root + ".api.SparkApi").getConstructor(platformType).newInstance(platform);
            Class<?> service = loader.loadClass("me.lucko.spark.api.Spark");
            var services = (ServicesManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ServicesManager.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "getKnownServices" -> List.of(service);
                        case "load" -> api;
                        default -> throw new AssertionError("Unexpected service call: " + method);
                    });
            var server = (Server) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Server.class},
                    (p, method, args) -> {
                        assertEquals("getServicesManager", method.getName());
                        return services;
                    });
            var session = new ReflectiveSparkProfiler(server).open("proof");
            try {
                assertTrue(session.owned());
                assertTrue(session.current());
                assertNotSame(sampler, access.active.invoke(access.container));
                assertTrue(((java.util.concurrent.Future<?>) ReflectiveSparkProfiler.field(sampler, "task")).isCancelled());
                assertTrue(((java.util.concurrent.ExecutorService) ReflectiveSparkProfiler.field(sampler, "workerPool")).isShutdown());
                assertEquals(false, session.details().get("background"));
            } finally {
                session.stop();
                // Clean up even when this regression test encounters the old reuse behavior.
                if (access.active.invoke(access.container) == sampler) {
                    access.unset.invoke(access.container, sampler);
                    access.stop.invoke(sampler, true);
                }
            }
            assertNull(access.active.invoke(access.container));
            byte[] bytes = session.export();
            assertTrue(bytes.length > 0);
            Object proto = access.export.getReturnType().getMethod("parseFrom", byte[].class).invoke(null, (Object) bytes);
            Object metadata = proto.getClass().getMethod("getMetadata").invoke(proto);
            assertEquals("SneakyDiagnostics capture proof", metadata.getClass().getMethod("getComment").invoke(metadata));
            assertNull(access.active.invoke(access.container));
            Files.write(directory.resolve("proof.sparkprofile"), bytes);
            var owned = new ReflectiveSparkProfiler(() -> platform).open("owned-proof");
            try {
                assertTrue(owned.owned());
                assertTrue(owned.current());
            } finally { owned.stop(); }
            assertNull(access.active.invoke(access.container));
            assertTrue(owned.export().length > 0);

            var replaced = new ReflectiveSparkProfiler(() -> platform).open("replacement-proof");
            // Simulate an administrator stopping our sampler, then starting another profile.
            Object old = access.active.invoke(access.container);
            access.stop.invoke(old, false);
            access.unset.invoke(access.container, old);
            access.set.invoke(access.container, sampler);
            replaced.stop();
            assertSame(sampler, access.active.invoke(access.container));

            access.unset.invoke(access.container, sampler);
            Object excludedDumper = loader.loadClass(root + ".sampler.ThreadDumper$Specific")
                    .getConstructor(Thread.class).newInstance(new Thread("unrelated-thread"));
            Object excludedSettings = settingsType.getConstructor(int.class, dumperType, grouperType, long.class, boolean.class, boolean.class)
                    .newInstance(4000, excludedDumper, grouper.get(), -1L, false, false);
            Object excluded = loader.loadClass(root + ".sampler.java.JavaSampler")
                    .getConstructor(platformType, settingsType).newInstance(platform, excludedSettings);
            excluded.getClass().getMethod("start").invoke(excluded);
            access.set.invoke(access.container, excluded);
            var fresh = new ReflectiveSparkProfiler(() -> platform).open("excluded");
            try {
                assertTrue(fresh.owned());
                assertTrue(fresh.current());
                assertNotSame(excluded, access.active.invoke(access.container));
                assertTrue(((java.util.concurrent.Future<?>) ReflectiveSparkProfiler.field(excluded, "task")).isCancelled());
            } finally {
                fresh.stop();
            }
        }
    }
}
