package com.teamsneakymouse.sneakydiagnostics.integration;

import com.teamsneakymouse.sneakydiagnostics.api.Diagnostics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderRegistryTest {
    private final ProviderRegistry registry = new ProviderRegistry(() -> {});
    private final Diagnostics.Capture capture = new Diagnostics.Capture("shared", "now", 123, 5, 1800);

    @Test void providersShareIdentityAndDeadlineAndReturnDetachedReports() {
        var first = new Probe();
        var second = new Probe();
        registry.register("MagicSpells", "version", "execution-1", first);
        registry.register("Voice", "version", "queries-1", second);
        registry.start(capture);
        assertSame(capture, first.capture);
        assertSame(capture, second.capture);
        var result = registry.stop("completed");
        assertEquals(2, result.size());
        assertTrue((Boolean) result.getFirst().get("complete"));
        assertEquals("execution-1", result.getFirst().get("instrumentationVersion"));
        first.rows.clear();
        assertEquals(Map.of("rows", List.of(Map.of("calls", 7))), result.getFirst().get("measurements"));
    }

    @Test void selectionStartsAndReportsOnlyNamedProviders() {
        var first = new Probe();
        var second = new Probe();
        var third = new Probe();
        registry.register("first", "v", "i", first);
        registry.register("second", "v", "i", second);
        registry.register("third", "v", "i", third);
        registry.start(capture, List.of("third", "first"));
        var result = registry.stop("completed");
        assertEquals(List.of("first", "third"), result.stream().map(row -> row.get("plugin")).toList());
        assertNull(second.capture);
        assertEquals(0, second.stops);
        assertEquals(1, first.stops);
        assertEquals(1, third.stops);
    }

    @Test void unknownSelectionIsRejectedBeforeAnyProviderStarts() {
        var first = new Probe();
        registry.register("first", "v", "i", first);
        assertThrows(IllegalArgumentException.class, () -> registry.start(capture, List.of("first", "missing")));
        assertNull(first.capture);
        registry.start(capture); // The failed validation left no run behind.
        assertSame(capture, first.capture);
    }

    @Test void unloadingFinishesOnlyThatProviderAndKeepsItsPartialResult() {
        var first = new Probe();
        var second = new Probe();
        var registration = registry.register("MagicSpells", "v", "i", first);
        registry.register("Voice", "v", "i", second);
        registry.start(capture);
        registration.close();
        registration.close();
        assertEquals(1, first.stops);
        assertEquals(0, second.stops);
        var replacement = new Probe();
        registry.register("MagicSpells", "new", "i", replacement);
        var result = registry.stop("completed");
        assertEquals("provider_unregistered", result.getFirst().get("reason"));
        assertFalse((Boolean) result.getFirst().get("complete"));
        assertTrue(result.getFirst().containsKey("measurements"));
        assertTrue((Boolean) result.get(1).get("complete"));
        assertNull(replacement.capture);
        registry.start(capture);
        registration.close(); // An old handle must not remove the replacement.
        assertSame(capture, replacement.capture);
        assertEquals(List.of("Voice", "MagicSpells"), registry.providers());
    }

    @Test void failedStartCanRollBackPartiallyStartedProviderAndLeaveOthersUntouched() {
        var first = new Probe();
        var failed = new Probe() {
            @Override public void start(Diagnostics.Capture capture) { super.start(capture); throw new IllegalStateException("failed"); }
        };
        var last = new Probe();
        registry.register("first", "v", "i", first);
        registry.register("failed", "v", "i", failed);
        registry.register("last", "v", "i", last);
        assertThrows(IllegalStateException.class, () -> registry.start(capture));
        var result = registry.stop("start_error");
        assertEquals(1, first.stops);
        assertEquals(1, failed.stops);
        assertNull(last.capture);
        assertTrue(result.get(1).containsKey("error"));
        assertEquals(false, result.get(2).get("started"));
    }

    @Test void failedStopDoesNotPreventOtherProvidersFromStoppingOrAnotherCapture() {
        registry.register("failed", "v", "i", new Probe() {
            @Override public Diagnostics.Result stop(String reason) { throw new IllegalStateException("failed stop"); }
        });
        var second = new Probe();
        registry.register("second", "v", "i", second);
        registry.start(capture);
        var result = registry.stop("completed");
        assertTrue(result.getFirst().containsKey("error"));
        assertTrue((Boolean) result.get(1).get("complete"));
        assertEquals(1, second.stops);
        registry.start(capture);
        registry.stop("manual_stop");
        assertEquals(2, second.stops);
    }

    @Test void registrationsDuringCaptureWaitUntilTheNextCapture() {
        registry.start(capture);
        var probe = new Probe();
        registry.register("late", "v", "i", probe);
        assertTrue(registry.stop("completed").isEmpty());
        assertNull(probe.capture);
        registry.start(capture);
        assertSame(capture, probe.capture);
    }

    @Test void malformedProviderValuesProduceAnExplicitErrorAndCleanupStillRuns() {
        registry.register("bad", "v", "i", new Probe() {
            @Override public Diagnostics.Result stop(String reason) { return new Diagnostics.Result(Map.of("entity", new Object()), true); }
        });
        registry.start(capture);
        var result = registry.stop("completed").getFirst();
        assertEquals(false, result.get("complete"));
        assertTrue(result.get("error").toString().contains("non-JSON"));
    }

    @Test void duplicateRegistrationsAndUseAfterShutdownAreRejected() {
        registry.register("p", "v", "i", new Probe());
        assertThrows(IllegalStateException.class, () -> registry.register("p", "v", "i", new Probe()));
        registry.start(capture);
        registry.close();
        assertTrue(registry.providers().isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.register("new", "v", "i", new Probe()));
    }

    @Test void cyclicAndOversizedReportsAreRejectedWithoutBreakingTheCapture() {
        var cycle = new ArrayList<Object>();
        cycle.add(cycle);
        for (Object invalid : List.of(cycle, java.util.Collections.nCopies(250_001, 0), Double.NaN, "x".repeat(1025))) {
            var bounded = new ProviderRegistry(() -> {});
            bounded.register("bad", "v", "i", new Probe() {
                @Override public Diagnostics.Result stop(String reason) {
                    return new Diagnostics.Result(Map.of("value", invalid), true);
                }
            });
            bounded.start(capture);
            var report = bounded.stop("completed").getFirst();
            assertEquals(false, report.get("complete"));
            assertTrue(report.containsKey("error"));
            assertFalse(report.containsKey("measurements"));
        }
    }

    @Test void lifecycleEnforcesTheThreadContract() {
        var guarded = new ProviderRegistry(() -> { throw new IllegalStateException("wrong thread"); });
        assertThrows(IllegalStateException.class, () -> guarded.register("p", "v", "i", new Probe()));
        assertThrows(IllegalStateException.class, () -> guarded.start(capture));
    }

    private static class Probe implements Diagnostics.Provider {
        Diagnostics.Capture capture;
        int stops;
        final List<Map<String, Object>> rows = new ArrayList<>(List.of(Map.of("calls", 7)));
        @Override public void start(Diagnostics.Capture capture) { this.capture = capture; }
        @Override public Diagnostics.Result stop(String reason) { stops++; return new Diagnostics.Result(Map.of("rows", rows), true); }
    }
}
