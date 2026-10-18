package com.teamsneakymouse.sneakydiagnostics.integration;

import com.teamsneakymouse.sneakydiagnostics.api.Diagnostics;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "magicSpellsJar", matches = ".+")
class MagicSpellsJarTest {
    @Test void rebuiltLifecycleInventorySurvivesTheRealConsumerGuardAndProviderUnregistration() throws Exception {
        Path jar = Path.of(System.getProperty("magicSpellsJar"));
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, Diagnostics.class.getClassLoader())) {
            var recorderType = loader.loadClass("com.nisovin.magicspells.util.performance.PerformanceRecorder");
            var tasksType = loader.loadClass("com.nisovin.magicspells.util.performance.TaskLifecycle");
            var pendingType = loader.loadClass(tasksType.getName() + "$Pending");
            var probeType = loader.loadClass("com.nisovin.magicspells.util.performance.MagicSpellsProbe");
            var recorder = recorderType.getConstructor().newInstance();
            var tasks = tasksType.getConstructor().newInstance();
            var schedule = tasksType.getMethod("schedule", String.class, String.class, long.class, long.class,
                    Runnable.class, java.util.function.ToIntFunction.class);
            var pendingConstructor = pendingType.getConstructor(int.class, boolean.class);
            var pending = new java.util.ArrayList<Object>();
            var callbacks = new java.util.ArrayList<Runnable>();
            for (int i = 0; i < 36_000; i++) {
                int id = i;
                schedule.invoke(tasks, "Mixed-Spell", "origin-" + i % 600, 1000L, i == 0 ? -1L : 5L, (Runnable) () -> {},
                        (java.util.function.ToIntFunction<Runnable>) body -> {
                            if (id < 2) callbacks.add(body);
                            return id;
                        });
                pending.add(pendingConstructor.newInstance(id, true));
            }
            var constructor = probeType.getDeclaredConstructor(recorderType, java.util.Set.class, tasksType, java.util.function.Supplier.class);
            constructor.setAccessible(true);
            var probe = (Diagnostics.Provider) constructor.newInstance(recorder, java.util.Set.of("Mixed-Spell"), tasks,
                    (java.util.function.Supplier<Iterable<?>>) () -> pending);
            var registry = new ProviderRegistry(() -> {});
            var registration = registry.register("MagicSpells", "rebuilt", "magicspells-execution-3", probe);
            registry.start(new Diagnostics.Capture("lifecycle", "now", System.nanoTime(), 0, 60));
            callbacks.get(0).run();
            callbacks.get(1).run();
            callbacks.get(1).run();
            tasksType.getMethod("cancel", int.class, Runnable.class).invoke(tasks, 2, (Runnable) () -> {});
            pending.remove(2);
            pending.removeFirst();
            var enter = recorderType.getMethod("enter", String.class, String.class, String.class);
            for (int i = 0; i < 30_000; i++) {
                ((AutoCloseable) enter.invoke(recorder, "spell", "mixed-spell", "detail-" + i)).close();
            }
            registration.close();
            var report = registry.stop("completed").getFirst();
            assertFalse(report.containsKey("error"), () -> String.valueOf(report.get("error")));
            assertEquals("provider_unregistered", report.get("reason"));
            var measurements = (Map<?, ?>) report.get("measurements");
            var lifecycle = (Map<?, ?>) measurements.get("taskLifecycle");
            assertEquals(36_000L, ((Map<?, ?>) lifecycle.get("start")).get("pendingTasks"));
            assertEquals(35_998L, ((Map<?, ?>) lifecycle.get("end")).get("pendingTasks"));
            var counts = (Map<?, ?>) lifecycle.get("totals");
            assertEquals(0L, counts.get("registrations"));
            assertEquals(3L, counts.get("executions"));
            assertEquals(2L, counts.get("repeatingExecutions"));
            assertEquals(1L, counts.get("oneShotCompletions"));
            assertEquals(1L, counts.get("trackedCancellations"));
            assertEquals(true, measurements.get("spellTotalsComplete"));
            assertEquals(0L, measurements.get("unknownOwnerScopes"));
            assertEquals(30_000L, ((Map<?, ?>) measurements.get("totals")).get("calls"));
            tasksType.getMethod("clear").invoke(tasks);
            assertEquals(0, tasksType.getMethod("retainedTasks").invoke(tasks));
        }
    }

    @Test void saturatedDetailsKeepEveryAggregateAndFitTheRealConsumerReportLimit() throws Exception {
        Path jar = Path.of(System.getProperty("magicSpellsJar"));
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, Diagnostics.class.getClassLoader())) {
            var recorderType = loader.loadClass("com.nisovin.magicspells.util.performance.PerformanceRecorder");
            var probeType = loader.loadClass("com.nisovin.magicspells.util.performance.MagicSpellsProbe");
            var counterType = loader.loadClass(recorderType.getName() + "$Counter");
            var recorder = recorderType.getConstructor().newInstance();
            var constructor = probeType.getDeclaredConstructor(recorderType, java.util.Set.class);
            constructor.setAccessible(true);
            var probe = (Diagnostics.Provider) constructor.newInstance(recorder, java.util.Set.of("stress"));
            var registry = new ProviderRegistry(() -> {});
            registry.register("MagicSpells", "rebuilt", "magicspells-execution-2", probe);
            registry.start(new Diagnostics.Capture("saturated", "now", System.nanoTime(), 5, 60));
            var enter = recorderType.getMethod("enter", String.class, String.class, String.class);
            var match = recorderType.getMethod("matchCall");
            var add = enter.getReturnType().getMethod("add", counterType, long.class);
            Object inventory = counterType.getEnumConstants()[0];
            for (int i = 0; i < 30_000; i++) {
                try (var scope = (AutoCloseable) enter.invoke(recorder, "spell", "stress", "detail-" + i)) {
                    match.invoke(recorder);
                    add.invoke(scope, inventory, 1L);
                }
            }
            var report = registry.stop("completed").getFirst();
            assertFalse(report.containsKey("error"), () -> String.valueOf(report.get("error")));
            var measurements = (Map<?, ?>) report.get("measurements");
            assertEquals(true, measurements.get("totalsComplete"));
            assertEquals(true, measurements.get("spellTotalsComplete"));
            assertEquals(false, measurements.get("detailComplete"));
            assertTrue((Long) measurements.get("droppedScopes") > 0);
            assertTrue(((List<?>) measurements.get("rows")).size() > 4096);
            var totals = (Map<?, ?>) measurements.get("totals");
            assertEquals(30_000L, totals.get("calls"));
            assertEquals(Map.of("MATCH_CALLS", 30_000L, "INVENTORY_SLOTS", 30_000L), totals.get("counters"));
            var spells = (List<?>) measurements.get("spellTotals");
            assertEquals(1, spells.size());
            assertEquals(30_000L, ((Map<?, ?>) spells.getFirst()).get("calls"));
        }
    }

    @Test void rebuiltPluginUsesTheConsumersApiAndDeliversItsRealMeasurementsAcrossClassloaders() throws Exception {
        Path jar = Path.of(System.getProperty("magicSpellsJar"));
        try (var archive = new JarFile(jar.toFile())) {
            assertNull(archive.getEntry("com/teamsneakymouse/sneakydiagnostics/api/Diagnostics.class"), "Providers must not shade the API");
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, Diagnostics.class.getClassLoader())) {
            var recorderType = loader.loadClass("com.nisovin.magicspells.util.performance.PerformanceRecorder");
            var probeType = loader.loadClass("com.nisovin.magicspells.util.performance.MagicSpellsProbe");
            assertSame(loader, recorderType.getClassLoader());
            assertSame(Diagnostics.class, loader.loadClass(Diagnostics.class.getName()));
            var recorder = recorderType.getConstructor().newInstance();
            var constructor = probeType.getDeclaredConstructor(recorderType, java.util.Set.class);
            constructor.setAccessible(true);
            var probe = (Diagnostics.Provider) constructor.newInstance(recorder, java.util.Set.of("integration-test"));
            var registry = new ProviderRegistry(() -> {});
            var registration = registry.register("MagicSpells", "rebuilt", "magicspells-execution-2", probe);
            registry.start(new Diagnostics.Capture("shared", "now", System.nanoTime(), 5, 60));
            try (var scope = (AutoCloseable) recorderType.getMethod("enter", String.class, String.class, String.class)
                    .invoke(recorder, "spell", "integration-test", "execute")) {
                recorderType.getMethod("matchCall").invoke(recorder);
            }
            registration.close(); // Simulates plugin reload while a diagnostics capture is running.
            var report = registry.stop("completed").getFirst();
            assertEquals("provider_unregistered", report.get("reason"));
            assertEquals(false, report.get("complete"));
            var measurements = (Map<?, ?>) report.get("measurements");
            assertEquals("shared", measurements.get("captureId"));
            var row = (Map<?, ?>) ((List<?>) measurements.get("rows")).getFirst();
            assertEquals("integration-test", row.get("spell"));
            assertEquals(Map.of("MATCH_CALLS", 1L, "INVENTORY_SLOTS", 0L), row.get("counters"));
            assertEquals(true, measurements.get("totalsComplete"));
            assertEquals(1L, ((Map<?, ?>) measurements.get("totals")).get("calls"));
        }
    }
}
