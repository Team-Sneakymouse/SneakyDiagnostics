package com.teamsneakymouse.sneakydiagnostics.integration;

import com.teamsneakymouse.sneakydiagnostics.api.Diagnostics;
import com.teamsneakymouse.sneakydiagnostics.integration.reference.Probe;
import io.papermc.paper.plugin.configuration.PluginMeta;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The consumer-side shim: works without the API classes, and re-attaches when SneakyDiagnostics comes back. */
class ProbeShimTest {
    private static final String API = "com.teamsneakymouse.sneakydiagnostics.api.";
    private static final String REFERENCE = "com.teamsneakymouse.sneakydiagnostics.integration.reference.";

    static {
        // Bukkit events ask Bukkit.isPrimaryThread() on construction; Bukkit.setServer would need a full server build.
        try {
            var field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            if (field.get(null) == null) field.set(null, proxy(Server.class, (name, args) -> switch (name) {
                case "isPrimaryThread" -> true;
                default -> throw new UnsupportedOperationException(name);
            }));
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    private interface Answer { Object call(String method, Object[] args) throws Throwable; }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) { case "hashCode" -> 1; case "equals" -> self == args[0]; default -> "proxy"; };
            }
            return answer.call(method.getName(), args == null ? new Object[0] : args);
        });
    }

    /** A Bukkit stand-in: just enough Plugin/Server/PluginManager/ServicesManager for the shim. */
    private static final class Host {
        boolean diagnosticsEnabled;
        Object service;
        int loads;
        final List<Object> listeners = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Logger logger = Logger.getAnonymousLogger();
        final Plugin plugin;

        Host() {
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
                @Override public void flush() {}
                @Override public void close() {}
            });
            var meta = proxy(PluginMeta.class, (name, args) -> name.equals("getVersion") ? "3.1" : null);
            var manager = proxy(PluginManager.class, (name, args) -> switch (name) {
                case "registerEvents" -> { listeners.add(args[0]); yield null; }
                case "isPluginEnabled" -> diagnosticsEnabled;
                default -> throw new UnsupportedOperationException(name);
            });
            var services = proxy(ServicesManager.class, (name, args) -> {
                if (!name.equals("load")) throw new UnsupportedOperationException(name);
                loads++;
                return service;
            });
            var server = proxy(Server.class, (name, args) -> switch (name) {
                case "getPluginManager" -> manager;
                case "getServicesManager" -> services;
                default -> throw new UnsupportedOperationException(name);
            });
            plugin = proxy(Plugin.class, (name, args) -> switch (name) {
                case "getServer" -> server;
                case "getLogger" -> logger;
                case "getName" -> "Consumer";
                case "getPluginMeta" -> meta;
                default -> throw new UnsupportedOperationException(name);
            });
        }

        ServiceRegisterEvent up(Class<?> type) { return new ServiceRegisterEvent(provider(type)); }
        ServiceUnregisterEvent down(Class<?> type) { return new ServiceUnregisterEvent(provider(type)); }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private RegisteredServiceProvider<?> provider(Class<?> type) {
            return new RegisteredServiceProvider(type, service == null ? "other" : service, ServicePriority.Normal, plugin);
        }
    }

    private final long base = 10_000_000_000L;
    private long now = base;
    private ProviderRegistry registry() { return new ProviderRegistry(() -> {}, () -> 7, () -> now); }
    private Diagnostics.Capture capture() { return new Diagnostics.Capture("c", "then", base, 7, 30); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> measurements(Map<String, Object> row) { return (Map<String, Object>) row.get("measurements"); }

    @Test void hooksUpWhenDiagnosticsIsEnabledAndMeasuresThroughTheService() {
        var host = new Host();
        host.diagnosticsEnabled = true;
        host.service = registry();
        var probe = new Probe(host.plugin, "consumer-ticks-2");
        probe.hook();
        assertSame(probe, host.listeners.getFirst());
        var registry = (ProviderRegistry) host.service;
        assertEquals(List.of("Consumer"), registry.providers());
        assertEquals(0, probe.start());
        registry.start(capture());
        long t = probe.start();
        assertNotEquals(0, t);
        now += 100;
        probe.end("tick", t);
        probe.count("n");
        probe.count("n", 4);
        assertTrue(probe.sampleDue());
        probe.level("lvl", 9);
        assertTrue(probe.on());
        var row = registry.stop("completed").getFirst();
        var report = measurements(row);
        assertEquals("consumer-ticks-2", row.get("instrumentationVersion"));
        assertEquals("3.1", row.get("pluginVersion"));
        assertEquals(5L, ((Map<?, ?>) report.get("counts")).get("n"));
        assertEquals(100L, ((Map<?, ?>) ((List<?>) report.get("sections")).getFirst()).get("totalNanos"));
        assertEquals(List.of(), host.warnings);
    }

    @Test void survivesADiagnosticsOnlyReloadWithTheSameShimInstance() {
        var host = new Host();
        host.diagnosticsEnabled = true;
        var first = registry();
        host.service = first;
        var probe = new Probe(host.plugin, "consumer-ticks-2");
        probe.hook();
        first.start(capture());
        probe.count("before");
        // SneakyDiagnostics is disabled: it closes its registry (ending the capture), then Bukkit unregisters the service.
        first.close();
        probe.onServiceDown(host.down(Diagnostics.class));
        assertEquals(0, probe.start());
        assertFalse(probe.on());
        assertDoesNotThrow(() -> probe.count("while-away"));
        // ...and enabled again: a new registry is registered, which fires the event.
        var second = registry();
        host.service = second;
        probe.onServiceUp(host.up(Diagnostics.class));
        assertEquals(List.of("Consumer"), second.providers());
        second.start(capture());
        probe.count("after");
        assertEquals(Map.of("after", 1L), measurements(second.stop("completed").getFirst()).get("counts"));
        assertEquals(List.of(), host.warnings);
    }

    @Test void attachesWhenDiagnosticsEnablesAfterTheConsumerAndIgnoresOtherServices() {
        var host = new Host();
        var probe = new Probe(host.plugin, "consumer-ticks-2");
        probe.hook();
        probe.onServiceUp(host.up(Runnable.class));
        probe.onServiceDown(host.down(Runnable.class));
        assertEquals(0, host.loads);
        assertEquals(0, probe.start());
        var registry = registry();
        host.service = registry;
        probe.onServiceUp(host.up(Diagnostics.class));
        assertEquals(List.of("Consumer"), registry.providers());
        probe.onServiceUp(host.up(Diagnostics.class));   // a repeated event replaces rather than duplicates
        assertEquals(List.of("Consumer"), registry.providers());
        assertEquals(List.of(), host.warnings);
    }

    @Test void unhookEndsTheCaptureWithTheUnregisteredReasonAndFreesTheName() {
        var host = new Host();
        host.diagnosticsEnabled = true;
        var registry = registry();
        host.service = registry;
        var probe = new Probe(host.plugin, "consumer-ticks-2");
        probe.hook();
        registry.start(capture());
        probe.count("kept");
        probe.unhook();
        probe.unhook();
        assertEquals("provider_unregistered", registry.stop("completed").getFirst().get("reason"));
        assertEquals(List.of(), registry.providers());
        assertEquals(0, probe.start());
    }

    @Test void anOlderServiceWithoutProbeSupportLeavesTheConsumerOffWithAWarning() {
        var host = new Host();
        host.diagnosticsEnabled = true;
        host.service = new Diagnostics() {
            @Override public Registration register(String plugin, String version, String instrumentation, Provider provider) { return () -> {}; }
        };
        var probe = new Probe(host.plugin, "consumer-ticks-2");
        assertDoesNotThrow(probe::hook);
        assertEquals(0, probe.start());
        assertEquals(1, host.warnings.size());
    }

    // ---- the API classes are absent ----------------------------------------------------------------------------

    /** Child-first for the consumer-shaped classes; refuses the API package like a server without SneakyDiagnostics. */
    private static final class HidingLoader extends ClassLoader {
        final List<String> refused = new ArrayList<>();
        HidingLoader() { super(ProbeShimTest.class.getClassLoader()); }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(API)) {
                refused.add(name);
                throw new ClassNotFoundException("SneakyDiagnostics is absent: " + name);
            }
            if (!name.startsWith(REFERENCE) && !name.contains("ProbeShimTest$Naive")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name);
                if (loaded == null) {
                    try (var in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        var bytes = in.readAllBytes();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException e) { throw new ClassNotFoundException(name, e); }
                }
                return loaded;
            }
        }
    }

    /** The mistake the shim avoids: an API type in a method signature. Bukkit's event registration reflects over these. */
    @SuppressWarnings("unused")
    static final class Naive {
        void use(Diagnostics.Probe probe) { probe.start(); }
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (var method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                try { return method.invoke(target, args); }
                catch (InvocationTargetException e) { throw e.getCause() instanceof Exception cause ? cause : e; }
            }
        }
        throw new NoSuchMethodException(name);
    }

    @Test void theShimLinksAndRunsWithoutAnyApiClass() throws Exception {
        var loader = new HidingLoader();
        // Control: a class with an API type in a signature really does fail in this harness.
        assertThrows(NoClassDefFoundError.class, () -> loader.loadClass(Naive.class.getName()).getDeclaredMethods());
        loader.refused.clear();

        var host = new Host();   // SneakyDiagnostics not installed: not enabled, no service
        Class<?> shim = loader.loadClass(REFERENCE + "Probe");
        assertSame(loader, shim.getClassLoader());
        Object probe = shim.getConstructor(Plugin.class, String.class).newInstance(host.plugin, "consumer-ticks-2");
        // What Bukkit's registerEvents reflects over.
        assertDoesNotThrow(shim::getMethods);
        assertDoesNotThrow(shim::getDeclaredMethods);
        assertDoesNotThrow(shim::getDeclaredConstructors);
        assertTrue(probe instanceof Listener);

        call(probe, "hook");
        assertSame(probe, host.listeners.getFirst());
        assertEquals(0L, call(probe, "start"));
        call(probe, "end", "tick", 0L);
        call(probe, "count", "n");
        call(probe, "count", "n", 3L);
        assertEquals(false, call(probe, "sampleDue"));
        call(probe, "level", "lvl", 1L);
        assertEquals(false, call(probe, "on"));
        // Events for unrelated services, and the shutdown path, never need the API either.
        call(probe, "onServiceUp", host.up(Runnable.class));
        call(probe, "onServiceDown", host.down(Runnable.class));
        call(probe, "unhook");
        assertEquals(List.of(), loader.refused, "no API class may be requested while SneakyDiagnostics is absent");
        assertEquals(0, host.loads);
        assertEquals(List.of(), host.warnings);
    }

    @Test void aServiceThatCannotBeLinkedLeavesTheShimOffInsteadOfThrowing() throws Exception {
        var loader = new HidingLoader();
        var host = new Host();
        host.diagnosticsEnabled = true;   // claims to be enabled, but this classloader cannot see the API (version skew)
        host.service = registry();
        Class<?> shim = loader.loadClass(REFERENCE + "Probe");
        Object probe = shim.getConstructor(Plugin.class, String.class).newInstance(host.plugin, "consumer-ticks-2");
        assertDoesNotThrow(() -> call(probe, "hook"));
        assertEquals(0L, call(probe, "start"));
        assertTrue(loader.refused.contains(API + "Diagnostics"));
        assertEquals(1, host.warnings.size());
        assertTrue(host.warnings.getFirst().contains("NoClassDefFoundError"));
    }
}
