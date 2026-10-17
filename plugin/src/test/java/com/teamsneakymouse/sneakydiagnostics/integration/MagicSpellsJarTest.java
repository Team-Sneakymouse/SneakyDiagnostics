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
            var constructor = probeType.getDeclaredConstructor(recorderType);
            constructor.setAccessible(true);
            var probe = (Diagnostics.Provider) constructor.newInstance(recorder);
            var registry = new ProviderRegistry(() -> {});
            var registration = registry.register("MagicSpells", "rebuilt", "magicspells-execution-1", probe);
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
            assertEquals(Map.of("MATCH_CALLS", 1L), row.get("counters"));
        }
    }
}
