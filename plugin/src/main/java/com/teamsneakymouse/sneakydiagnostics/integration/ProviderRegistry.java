package com.teamsneakymouse.sneakydiagnostics.integration;

import com.teamsneakymouse.sneakydiagnostics.api.Diagnostics;
import com.teamsneakymouse.sneakydiagnostics.probe.ProbeRecorder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** Owns provider lifecycle, including partial starts and unloads during a capture. */
public final class ProviderRegistry implements Diagnostics, AutoCloseable {
    private final Map<String, Entry> providers = new LinkedHashMap<>();
    private final Runnable checkThread;
    private final IntSupplier tick;
    private final LongSupplier nanos;
    private List<Run> runs;
    private boolean closed;

    /** {@code tick} is the server's current tick (Bukkit::getCurrentTick), injected so the registry runs without Bukkit. */
    public ProviderRegistry(Runnable checkThread, IntSupplier tick) { this(checkThread, tick, System::nanoTime); }

    public ProviderRegistry(Runnable checkThread, IntSupplier tick, LongSupplier nanos) {
        this.checkThread = checkThread;
        this.tick = tick;
        this.nanos = nanos;
    }

    /** One recorder per plugin, registered as an ordinary provider so it is selected, written and completed like any other. */
    @Override public Probe probe(String plugin, String version, String instrumentation) {
        var recorder = new ProbeRecorder(nanos, tick);
        var registration = register(plugin, version, instrumentation, new Provider() {
            @Override public void start(Capture capture) { recorder.begin(capture.startedNanos(), capture.seconds(), capture.startTick()); }
            @Override public Result stop(String reason) { return new Result(recorder.finish(reason), recorder.complete()); }
        });
        return new ProbeHandle(recorder, registration);
    }

    @Override public Registration register(String plugin, String version, String instrumentation, Provider provider) {
        checkThread.run();
        if (closed) throw new IllegalStateException("Diagnostics service is closed");
        for (String label : List.of(plugin, version, instrumentation)) {
            if (label.isBlank() || label.length() > 192) throw new IllegalArgumentException("Provider labels must have 1..192 characters");
        }
        if (providers.containsKey(plugin)) throw new IllegalStateException("Provider already registered: " + plugin);
        var entry = new Entry(plugin, version, instrumentation, Objects.requireNonNull(provider));
        providers.put(plugin, entry);
        return () -> {
            checkThread.run();
            if (providers.get(plugin) != entry) return;
            if (runs != null) runs.stream().filter(run -> run.entry == entry).forEach(run -> run.stop("provider_unregistered"));
            providers.remove(plugin);
        };
    }

    public List<String> providers() { checkThread.run(); return List.copyOf(providers.keySet()); }

    public void start(Capture capture) { start(capture, List.of()); }

    /** Starts only the named providers (exact registered names); an empty selection starts all of them. */
    public void start(Capture capture, List<String> selection) {
        checkThread.run();
        if (closed || runs != null) throw new IllegalStateException("Plugin probes are unavailable or already recording");
        for (String name : selection) {
            if (!providers.containsKey(name)) throw new IllegalArgumentException("Unknown provider: " + name);
        }
        // Registrations added during a capture join the next capture only.
        runs = providers.values().stream().filter(entry -> selection.isEmpty() || selection.contains(entry.plugin)).map(Run::new).toList();
        for (Run run : runs) {
            run.started = true;
            try { run.entry.provider.start(capture); }
            catch (RuntimeException e) {
                run.error = e.toString();
                throw new IllegalStateException("Cannot start plugin probe: " + run.entry.plugin, e);
            }
        }
    }

    public List<Map<String, Object>> stop(String reason) {
        checkThread.run();
        if (runs == null) return List.of();
        var finished = runs;
        // Keep runs accessible during callbacks so an unregister can see its stopped run.
        try {
            for (Run run : finished) run.stop(reason);
            return finished.stream().map(Run::report).toList();
        } finally { runs = null; }
    }

    @Override public void close() {
        checkThread.run();
        closed = true;
        stop("diagnostics_disable");
        providers.clear();
    }

    /** Server thread only by contract: deliberately no per-call thread check on the hot path. */
    private record ProbeHandle(ProbeRecorder recorder, Registration registration) implements Probe {
        @Override public long start() { return recorder.start(); }
        @Override public void end(String section, long token) { recorder.end(section, token); }
        @Override public void count(String key, long n) { recorder.count(key, n); }
        @Override public boolean sampleDue() { return recorder.sampleDue(); }
        @Override public void level(String key, long value) { recorder.level(key, value); }
        @Override public boolean on() { return recorder.on(); }
        @Override public void close() { registration.close(); }
    }

    private record Entry(String plugin, String version, String instrumentation, Provider provider) {}

    private static final class Run {
        final Entry entry;
        boolean started, stopped;
        String reason, error;
        Result result;
        Run(Entry entry) { this.entry = entry; }
        void stop(String reason) {
            if (!started || stopped) return;
            stopped = true;
            this.reason = reason;
            try {
                Result provided = Objects.requireNonNull(entry.provider.stop(reason), "Provider returned no result");
                // Copy before control returns to the plugin, including nested maps/lists.
                result = new Result(detach(provided.measurements(), new int[]{250_000}, 0), provided.complete());
            } catch (RuntimeException e) { error = error == null ? e.toString() : error + "; stop: " + e; }
        }
        Map<String, Object> report() {
            var report = new LinkedHashMap<String, Object>();
            report.put("plugin", entry.plugin);
            report.put("pluginVersion", entry.version);
            report.put("instrumentationVersion", entry.instrumentation);
            report.put("started", started);
            report.put("reason", reason == null ? "not_started" : reason);
            report.put("complete", error == null && result != null && result.complete() && "completed".equals(reason));
            if (result != null) report.put("measurements", result.measurements());
            if (error != null) report.put("error", error);
            return report;
        }
    }

    private static Map<String, Object> detach(Map<?, ?> source, int[] remaining, int depth) {
        var copy = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (!(key instanceof String label) || label.length() > 192) throw new IllegalArgumentException("Report map keys must be strings of at most 192 characters");
            copy.put(label, detachValue(value, remaining, depth + 1));
        });
        return java.util.Collections.unmodifiableMap(copy);
    }

    private static Object detachValue(Object value, int[] remaining, int depth) {
        if (--remaining[0] < 0 || depth > 16) throw new IllegalArgumentException("Provider report exceeds size/depth limit");
        if (value == null || value instanceof Boolean) return value;
        if (value instanceof String label) {
            if (label.length() > 1024) throw new IllegalArgumentException("Provider report string exceeds 1024 characters");
            return label;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return value;
        if (value instanceof Double number && Double.isFinite(number)) return value;
        if (value instanceof Float number && Float.isFinite(number)) return value;
        if (value instanceof Map<?, ?> map) return detach(map, remaining, depth);
        if (value instanceof List<?> list) {
            var copy = new ArrayList<>();
            for (Object item : list) copy.add(detachValue(item, remaining, depth + 1));
            return java.util.Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Provider report contains a non-JSON value: " + value.getClass().getName());
    }
}
