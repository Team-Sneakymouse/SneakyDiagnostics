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

@EnabledIfSystemProperty(named = "voiceJar", matches = ".+")
class VoiceJarTest {
    @Test void rebuiltVoiceProviderSharesTheApiAndPreservesPartialResultsOnUnregister() throws Exception {
        Path jar = Path.of(System.getProperty("voiceJar"));
        try (var archive = new JarFile(jar.toFile())) {
            assertNull(archive.getEntry("com/teamsneakymouse/sneakydiagnostics/api/Diagnostics.class"));
            assertTrue(new String(archive.getInputStream(archive.getEntry("plugin.yml")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).contains("softdepend: [\"SneakyDiagnostics\"]"));
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, Diagnostics.class.getClassLoader())) {
            var recorderType = loader.loadClass("ca.bungo.sneakyconfigurablevoice.diagnostics.VoiceRecorder");
            var probeType = loader.loadClass("ca.bungo.sneakyconfigurablevoice.diagnostics.VoiceProbe");
            var modeType = loader.loadClass(recorderType.getName() + "$Mode");
            var queueType = loader.loadClass(recorderType.getName() + "$QueuedWork");
            assertSame(loader, recorderType.getClassLoader());
            assertSame(Diagnostics.class, loader.loadClass(Diagnostics.class.getName()));
            var recorder = recorderType.getConstructor().newInstance();
            var provider = (Diagnostics.Provider) probeType.getConstructor(recorderType).newInstance(recorder);
            var registry = new ProviderRegistry(() -> {}, () -> 0);
            var registration = registry.register("SneakyConfigurableVoice", "rebuilt", "voice-routing-1", provider);
            registry.start(new Diagnostics.Capture("shared", "now", System.nanoTime(), 5, 60));
            Object mode = modeType.getEnumConstants()[0];
            Object queued;
            try (var packet = (AutoCloseable) recorderType.getMethod("packet").invoke(recorder)) {
                packet.getClass().getMethod("route", modeType).invoke(packet, mode);
                queued = packet.getClass().getMethod("queue").invoke(packet);
            }
            try (var dispatch = (AutoCloseable) recorderType.getMethod("dispatch", modeType, queueType, int.class)
                    .invoke(recorder, mode, queued, 32)) {
                dispatch.getClass().getMethod("queryStarted").invoke(dispatch);
                dispatch.getClass().getMethod("queryFinished", int.class, int.class, boolean.class).invoke(dispatch, 3, 2, true);
                dispatch.getClass().getMethod("sendAttempt").invoke(dispatch);
                dispatch.getClass().getMethod("sent").invoke(dispatch);
            }
            registration.close();
            var report = registry.stop("completed").getFirst();
            assertFalse(report.containsKey("error"), () -> String.valueOf(report.get("error")));
            assertEquals("provider_unregistered", report.get("reason"));
            assertEquals(false, report.get("complete"));
            var measurements = (Map<?, ?>) report.get("measurements");
            assertEquals("shared", measurements.get("captureId"));
            assertEquals(1L, measurements.get("microphoneEvents"));
            var normal = (Map<?, ?>) ((List<?>) measurements.get("modes")).getFirst();
            assertEquals(3L, normal.get("candidatesExamined"));
            assertEquals(1L, normal.get("sendCallsReturned"));
            assertEquals(1L, ((Map<?, ?>) normal.get("queueDelay")).get("samples"));
        }
    }
}
