package com.teamsneakymouse.sneakydiagnostics.report;

import com.teamsneakymouse.sneakydiagnostics.report.CaptureManifest.Server;
import com.teamsneakymouse.sneakydiagnostics.report.CaptureManifest.Workload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Writes report files and keeps each capture's manifest current; call from the single writer thread only. */
public final class ReportStore {
    private static final int CACHED_MANIFESTS = 32;

    /** Detached capture identity and server-thread samples, copied before the writer thread uses them. */
    public record Capture(String id, String startedAt, int seconds, Server server, Workload atStart, Workload atEnd) {}

    private final Path root;
    private final Clock clock;
    private final BiConsumer<Path, IOException> manifestFailure;
    // Evicted captures are reloaded from their manifest file, so late segments never drop earlier entries.
    private final Map<String, CaptureManifest> manifests = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CaptureManifest> eldest) { return size() > CACHED_MANIFESTS; }
    };

    public ReportStore(Path root, Clock clock, BiConsumer<Path, IOException> manifestFailure) {
        this.root = root;
        this.clock = clock;
        this.manifestFailure = manifestFailure;
    }

    public Path directory(String captureId) { return root.resolve(captureId); }

    /** Serializes first so the compression decision sees the final size; returns the stored path. */
    public Path writeJson(Capture capture, String collector, int segment, String jsonName, Map<String, Object> report) throws IOException {
        StoredFile stored = StoredFile.json(jsonName, StoredFile.serialize(report));
        return write(capture, collector, segment, stored, Boolean.TRUE.equals(report.get("complete")),
                report.get("reason") instanceof String reason ? reason : null);
    }

    public Path writeArtifact(Capture capture, String collector, int segment, String name, byte[] data, boolean complete, String reason) throws IOException {
        return write(capture, collector, segment, StoredFile.raw(name, data), complete, reason);
    }

    private Path write(Capture capture, String collector, int segment, StoredFile stored, boolean complete, String reason) throws IOException {
        Path directory = directory(capture.id());
        // A deleted folder must not resurrect cached entries for files that no longer exist.
        if (!Files.exists(directory.resolve(CaptureManifest.FILE))) manifests.remove(capture.id());
        Files.createDirectories(directory);
        Path path = directory.resolve(stored.name());
        Files.write(path, stored.data(), StandardOpenOption.CREATE_NEW);
        var entry = new CaptureManifest.Entry(stored.name(), collector, segment, stored.bytes(), stored.uncompressedBytes(),
                stored.sha256(), complete, reason, clock.instant().toString());
        var manifest = manifests.computeIfAbsent(capture.id(), id -> CaptureManifest.load(directory).orElseGet(() ->
                new CaptureManifest(id, capture.startedAt(), capture.seconds(), capture.server(), capture.atStart())));
        manifest.add(entry).atEnd(capture.atEnd());
        try { manifest.save(directory, clock.instant()); }
        catch (IOException e) { manifestFailure.accept(directory, e); }
        return path;
    }
}
