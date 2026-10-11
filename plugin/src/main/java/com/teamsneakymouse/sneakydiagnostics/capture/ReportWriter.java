package com.teamsneakymouse.sneakydiagnostics.capture;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Accepts detached reports only; no Bukkit access in the serialization worker. */
public final class ReportWriter implements ReportSink, AutoCloseable {
    private final JavaPlugin plugin;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SneakyDiagnostics-reports");
        thread.setDaemon(true);
        return thread;
    });

    public ReportWriter(JavaPlugin plugin) { this.plugin = plugin; }
    public boolean busy(String collector) { return pending.contains(collector); }

    public Map<String, Object> header(String collector, CaptureContext context, String reason, int segment) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("collector", collector);
        report.put("captureId", context.id());
        report.put("captureStartedAt", context.startedAt());
        report.put("captureStartTick", context.startTick());
        report.put("finishedAt", Instant.now().toString());
        report.put("finishedAfterMillis", context.offsetMillis());
        report.put("requestedDurationSeconds", context.seconds());
        report.put("reason", reason);
        report.put("segment", segment);
        report.put("serverVersion", Bukkit.getVersion());
        report.put("pluginVersion", plugin.getPluginMeta().getVersion());
        return report;
    }

    public void write(String collector, CaptureContext context, int segment, Supplier<Map<String, Object>> detachedReport) {
        if (!pending.add(collector)) throw new IllegalStateException("Report already pending: " + collector);
        Path path = plugin.getDataFolder().toPath().resolve("reports").resolve(context.id())
                .resolve(collector + "-" + segment + ".json");
        worker.execute(() -> {
            try {
                Files.createDirectories(path.getParent());
                try (var out = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW)) {
                    new GsonBuilder().setPrettyPrinting().create().toJson(detachedReport.get(), out);
                }
                plugin.getLogger().info("Saved " + path.toAbsolutePath());
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Cannot save " + path, e);
            } finally {
                pending.remove(collector);
            }
        });
    }

    @Override public void close() { worker.shutdown(); }
}
