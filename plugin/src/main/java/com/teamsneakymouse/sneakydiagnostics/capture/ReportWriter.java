package com.teamsneakymouse.sneakydiagnostics.capture;

import com.teamsneakymouse.sneakydiagnostics.report.CaptureManifest;
import com.teamsneakymouse.sneakydiagnostics.report.ReportStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Serializes detached reports and optional spark exports; Bukkit headers are assembled by the caller. */
public final class ReportWriter implements ReportSink, AutoCloseable {
    private final JavaPlugin plugin;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> pendingCaptures = new ConcurrentHashMap<>();
    private final ReportStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SneakyDiagnostics-reports");
        thread.setDaemon(true);
        return thread;
    });

    public ReportWriter(JavaPlugin plugin) {
        this.plugin = plugin;
        this.store = new ReportStore(plugin.getDataFolder().toPath().resolve("reports"), Clock.systemUTC(),
                (directory, e) -> plugin.getLogger().log(Level.WARNING, "Cannot update manifest in " + directory, e));
    }
    public boolean busy(String collector) { return pending.contains(collector); }
    /** True while a report or artifact of this capture is queued or being written. */
    public boolean pendingCapture(String captureId) { return pendingCaptures.containsKey(captureId); }

    public Map<String, Object> header(String collector, CaptureContext context, String reason, int segment) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 2);
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
        write(collector, context, segment, detachedReport, null, null);
    }

    @Override public void write(String collector, CaptureContext context, int segment, Supplier<Map<String, Object>> detachedReport,
                                String artifactName, Supplier<byte[]> artifact) {
        ReportStore.Capture capture = reserve(collector, context);
        Path path = store.directory(context.id()).resolve(ReportSink.fileName(collector, null, segment));
        worker.execute(() -> {
            try {
                var report = detachedReport.get();
                byte[] artifactBytes = null;
                if (artifact != null) {
                    try {
                        // Finalize an owned profiler even if the report directory cannot be created.
                        artifactBytes = artifact.get();
                    } catch (RuntimeException | LinkageError e) {
                        report.put("complete", false);
                        report.put("exportError", e.toString());
                        plugin.getLogger().log(Level.WARNING, "Cannot export " + artifactName, e);
                    }
                }
                Files.createDirectories(path.getParent());
                if (artifactBytes != null) {
                    Path artifactPath = path.resolveSibling(artifactName);
                    try {
                        store.writeArtifact(capture, collector, segment, artifactName, artifactBytes,
                                !Boolean.FALSE.equals(report.get("complete")), report.get("reason") instanceof String reason ? reason : null);
                        report.put("profileFile", artifactName);
                        plugin.getLogger().info("Saved " + artifactPath.toAbsolutePath());
                    } catch (IOException | RuntimeException | LinkageError e) {
                        report.put("complete", false);
                        report.put("exportError", e.toString());
                        plugin.getLogger().log(Level.WARNING, "Cannot save " + artifactPath, e);
                    }
                }
                save(capture, collector, segment, path.getFileName().toString(), report);
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Cannot save " + path, e);
            } finally {
                release(collector, context);
            }
        });
    }

    @Override public void write(String collector, CaptureContext context, int segment, List<ReportFile> files) {
        ReportStore.Capture capture = reserve(collector, context);
        Path dir = store.directory(context.id());
        worker.execute(() -> {
            try {
                Files.createDirectories(dir);
                for (ReportFile file : files) {
                    String name = ReportSink.fileName(collector, file.suffix(), segment);
                    try {
                        save(capture, collector, segment, name, file.report().get());
                    } catch (IOException | RuntimeException e) {
                        plugin.getLogger().log(Level.SEVERE, "Cannot save " + dir.resolve(name), e);
                    }
                }
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Cannot save reports in " + dir, e);
            } finally {
                release(collector, context);
            }
        });
    }

    /** Compresses when large and updates the manifest after the file is on disk. */
    private void save(ReportStore.Capture capture, String collector, int segment, String name, Map<String, Object> report) throws IOException {
        Path path = store.writeJson(capture, collector, segment, name, report);
        plugin.getLogger().info("Saved " + path.toAbsolutePath());
    }

    /** Server thread: marks the collector and capture pending and takes the manifest samples. */
    private ReportStore.Capture reserve(String collector, CaptureContext context) {
        if (!pending.add(collector)) throw new IllegalStateException("Report already pending: " + collector);
        pendingCaptures.merge(context.id(), 1, Integer::sum);
        try {
            var server = new CaptureManifest.Server(Bukkit.getVersion(), System.getProperty("java.version"), plugin.getPluginMeta().getVersion(),
                    Arrays.stream(Bukkit.getPluginManager().getPlugins()).filter(Plugin::isEnabled)
                            .map(p -> new CaptureManifest.Plugin(p.getName(), p.getPluginMeta().getVersion())).toList());
            var atEnd = context.progress().finish(collector) ? CaptureContext.sampleWorkload() : null;
            return new ReportStore.Capture(context.id(), context.startedAt(), context.seconds(), server, context.atStart(), atEnd);
        } catch (RuntimeException e) {
            release(collector, context);
            throw e;
        }
    }

    private void release(String collector, CaptureContext context) {
        pendingCaptures.computeIfPresent(context.id(), (id, count) -> count > 1 ? count - 1 : null);
        pending.remove(collector);
    }

    @Override public void close() { worker.shutdown(); }
}
