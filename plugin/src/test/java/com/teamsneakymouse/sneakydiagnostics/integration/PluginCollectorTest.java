package com.teamsneakymouse.sneakydiagnostics.integration;

import com.teamsneakymouse.sneakydiagnostics.api.Diagnostics;
import com.teamsneakymouse.sneakydiagnostics.capture.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PluginCollectorTest {
    private final ProviderRegistry registry = new ProviderRegistry(() -> {});
    private final Sink sink = new Sink();
    private final PluginCollector collector = new PluginCollector(registry, sink);
    private final CaptureContext context = new CaptureContext("shared", "now", System.nanoTime() - 2_000_000_000L, 4, 1);

    @Test void tickDeadlineWritesAnIndexAndOneFilePerProviderWithTheSharedIdentity() {
        registry.register("MagicSpells", "ms-version", "i", new Diagnostics.Provider() {
            @Override public void start(Diagnostics.Capture capture) { assertEquals("shared", capture.id()); }
            @Override public Diagnostics.Result stop(String reason) { return new Diagnostics.Result(Map.of("calls", 3), true); }
        });
        registry.register("Voice", "v", "i", new Probe(true));
        collector.start(context, List.of());
        collector.onTick(null);
        assertFalse(collector.active());
        assertEquals(1, sink.writes);
        assertEquals(List.of("plugins-1.json", "plugins-magicspells-1.json", "plugins-voice-1.json"), sink.names());
        var index = sink.report(null);
        assertEquals("shared", index.get("captureId"));
        assertEquals("completed", index.get("reason"));
        assertEquals(true, index.get("complete"));
        assertEquals("all", index.get("selected"));
        assertEquals(List.of(), index.get("incompleteProviders"));
        assertTrue(index.get("notes").toString().contains("not zero plugin cost"));
        var entries = rows(index.get("providers"));
        assertEquals("plugins-magicspells-1.json", entries.getFirst().get("file"));
        assertEquals("ms-version", entries.getFirst().get("pluginVersion"));
        assertEquals(true, entries.getFirst().get("started"));
        assertFalse(entries.getFirst().containsKey("measurements"));
        assertFalse(entries.getFirst().containsKey("uncompressedBytes"));
        var file = sink.report("magicspells");
        assertEquals("shared", file.get("captureId"));
        assertEquals("plugins", file.get("collector"));
        assertEquals("MagicSpells", file.get("plugin"));
        assertEquals("ms-version", file.get("pluginVersion"));
        assertEquals("i", file.get("instrumentationVersion"));
        assertEquals("completed", file.get("reason"));
        assertEquals(true, file.get("complete"));
        assertNull(file.get("error"));
        assertEquals(Map.of("calls", 3), file.get("measurements"));
        collector.stop("plugin_disable");
        assertEquals(1, sink.writes);
    }

    @Test void incompleteProviderIsListedWithoutMakingTheCollectorIncomplete() {
        registry.register("Partial", "v", "i", new Probe(false));
        registry.register("Whole", "v", "i", new Probe(true));
        collector.start(context, List.of());
        collector.stop("completed");
        var index = sink.report(null);
        assertEquals(true, index.get("complete"));
        assertEquals(List.of("Partial"), index.get("incompleteProviders"));
        assertEquals(false, sink.report("partial").get("complete"));
        assertEquals(true, sink.report("whole").get("complete"));
        assertTrue(sink.report("partial").containsKey("measurements"));
    }

    @Test void providerThatFailsToStopIsErroredAndBreaksCoordination() {
        registry.register("Broken", "v", "i", new Probe(true) {
            @Override public Diagnostics.Result stop(String reason) { throw new IllegalStateException("failed stop"); }
        });
        registry.register("Fine", "v", "i", new Probe(true));
        collector.start(context, List.of());
        collector.stop("completed");
        var index = sink.report(null);
        assertEquals(false, index.get("complete"));
        assertEquals(List.of("Broken"), index.get("incompleteProviders"));
        assertTrue(rows(index.get("providers")).getFirst().get("error").toString().contains("failed stop"));
        var file = sink.report("broken");
        assertEquals(false, file.get("complete"));
        assertTrue(file.get("error").toString().contains("failed stop"));
        assertFalse(file.containsKey("measurements"));
        assertEquals(true, sink.report("fine").get("complete"));
    }

    @Test void manualStopAndUnregisteredProvidersKeepTheirOwnReasons() {
        var registration = registry.register("Gone", "v", "i", new Probe(true));
        registry.register("Stays", "v", "i", new Probe(true));
        collector.start(context, List.of());
        registration.close();
        collector.stop("manual_stop");
        var index = sink.report(null);
        assertEquals("manual_stop", index.get("reason"));
        assertEquals(false, index.get("complete"));
        assertEquals(List.of("Gone", "Stays"), rows(index.get("providers")).stream().map(row -> row.get("plugin")).toList());
        assertEquals("provider_unregistered", sink.report("gone").get("reason"));
        assertEquals("manual_stop", sink.report("stays").get("reason"));
    }

    @Test void collidingNamesGetStableSuffixesRecordedInTheIndex() {
        registry.register("My Plugin", "v", "i", new Probe(true));
        registry.register("my_plugin", "v", "i", new Probe(true));
        collector.start(context, List.of());
        collector.stop("completed");
        assertEquals(List.of("plugins-1.json", "plugins-my_plugin-1.json", "plugins-my_plugin~2-1.json"), sink.names());
        assertEquals("plugins-my_plugin~2-1.json", rows(sink.report(null).get("providers")).get(1).get("file"));
        assertEquals("my_plugin", sink.report("my_plugin~2").get("plugin"));
    }

    @Test void selectionStartsOnlyNamedProvidersAndIsRecorded() {
        var chosen = new Probe(true);
        var other = new Probe(true);
        registry.register("Chosen", "v", "i", chosen);
        registry.register("Other", "v", "i", other);
        collector.start(context, List.of(), List.of("Chosen"));
        assertTrue(collector.status().contains("providers: Chosen"));
        collector.stop("completed");
        assertEquals(1, chosen.stops);
        assertNull(other.capture);
        assertEquals(0, other.stops);
        assertEquals(List.of("plugins-1.json", "plugins-chosen-1.json"), sink.names());
        var index = sink.report(null);
        assertEquals(List.of("Chosen"), index.get("selected"));
        assertEquals(1, rows(index.get("providers")).size());
        assertEquals(true, index.get("complete"));
        // The next capture selects everything again.
        collector.start(context, List.of());
        collector.stop("completed");
        assertEquals("all", sink.report(null).get("selected"));
        assertEquals(2, rows(sink.report(null).get("providers")).size());
    }

    @Test void unknownSelectionIsRejectedWithoutChangingCollectorOrProviderState() {
        var probe = new Probe(true);
        registry.register("Known", "v", "i", probe);
        assertThrows(IllegalArgumentException.class, () -> collector.start(context, List.of(), List.of("Known", "Missing")));
        assertNull(probe.capture);
        assertEquals(0, sink.writes);
    }

    @Test void noRegisteredProvidersWritesOnlyTheIndexWithTheNoMeasurementsNote() {
        collector.start(context, List.of());
        collector.stop("completed");
        assertEquals(List.of("plugins-1.json"), sink.names());
        var index = sink.report(null);
        assertEquals(List.of(), index.get("providers"));
        assertEquals(List.of(), index.get("incompleteProviders"));
        assertEquals(true, index.get("complete"));
        assertTrue(index.get("notes").toString().contains("No registered providers means no plugin measurements, not zero plugin cost"));
    }

    @Test void coordinatorRollsBackPluginRecorderWhenItsStartFails() {
        int[] stops = {0};
        registry.register("failing", "v", "i", new Diagnostics.Provider() {
            @Override public void start(Diagnostics.Capture capture) { throw new IllegalStateException("failed after starting"); }
            @Override public Diagnostics.Result stop(String reason) { stops[0]++; return new Diagnostics.Result(Map.of(), false); }
        });
        var coordinator = new CaptureCoordinator(List.of(collector));
        assertThrows(IllegalStateException.class, () -> coordinator.start(List.of(collector),
                new CaptureContext("shared", "now", System.nanoTime(), 4, 60), List.of(), true));
        assertFalse(collector.active());
        assertEquals(1, stops[0]);
        var index = sink.report(null);
        assertEquals(false, index.get("complete"));
        assertEquals("start_error", index.get("reason"));
        assertEquals(List.of("failing"), index.get("incompleteProviders"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) { return (List<Map<String, Object>>) value; }

    private static class Probe implements Diagnostics.Provider {
        final boolean complete;
        Diagnostics.Capture capture;
        int stops;
        Probe(boolean complete) { this.complete = complete; }
        @Override public void start(Diagnostics.Capture capture) { this.capture = capture; }
        @Override public Diagnostics.Result stop(String reason) { stops++; return new Diagnostics.Result(Map.of("rows", List.of()), complete); }
    }

    private static class Sink implements ReportSink {
        final Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        int writes;
        @Override public boolean busy(String collector) { return false; }
        @Override public Map<String, Object> header(String collector, CaptureContext context, String reason, int segment) {
            return new HashMap<>(Map.of("collector", collector, "captureId", context.id(), "reason", reason, "pluginVersion", "diagnostics"));
        }
        @Override public void write(String collector, CaptureContext context, int segment, Supplier<Map<String, Object>> report) {
            throw new AssertionError("plugins writes through the multi-file seam");
        }
        @Override public void write(String collector, CaptureContext context, int segment, List<ReportFile> reports) {
            writes++;
            files.clear();
            for (var file : reports) files.put(ReportSink.fileName(collector, file.suffix(), segment), file.report().get());
        }
        List<String> names() { return new ArrayList<>(files.keySet()); }
        /** The index for a null slug, otherwise the provider file with that slug. */
        Map<String, Object> report(String slug) { return files.get(ReportSink.fileName("plugins", slug, 1)); }
    }
}
