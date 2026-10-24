package com.teamsneakymouse.sneakydiagnostics.report;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The per-capture {@code manifest.json}: detached data only, mutated on the writer thread and always saved whole. */
public final class CaptureManifest {
    public static final String FILE = "manifest.json";
    public static final int SCHEMA_VERSION = 2;

    public record Plugin(String name, String version) {}
    public record Server(String version, String javaVersion, String diagnosticsVersion, List<Plugin> plugins) {
        public Server { plugins = List.copyOf(plugins); }
    }
    public record WorldLoad(String name, int loadedChunks, int entities) {}
    /** Counts only: never player names, UUIDs or locations. */
    public record Workload(int players, List<WorldLoad> worlds) {
        public Workload { worlds = List.copyOf(worlds); }
    }
    public record Entry(String name, String collector, int segment, long bytes, long uncompressedBytes, String sha256,
                        boolean complete, String reason, String writtenAt) {}

    private final String captureId, captureStartedAt;
    private final int requestedDurationSeconds;
    private final Server server;
    private final Workload atStart;
    private Workload atEnd;
    private final Map<String, Entry> files = new LinkedHashMap<>();

    public CaptureManifest(String captureId, String captureStartedAt, int requestedDurationSeconds, Server server, Workload atStart) {
        this.captureId = captureId;
        this.captureStartedAt = captureStartedAt;
        this.requestedDurationSeconds = requestedDurationSeconds;
        this.server = server;
        this.atStart = atStart;
    }

    public String captureId() { return captureId; }
    public List<Entry> files() { return List.copyOf(files.values()); }
    public Workload atEnd() { return atEnd; }

    /** A later file of the same name replaces the earlier entry in place. */
    public CaptureManifest add(Entry entry) { files.put(entry.name(), entry); return this; }

    /** Null never clears a sample: only the finish of the capture's last active collector provides one. */
    public CaptureManifest atEnd(Workload workload) { if (workload != null) atEnd = workload; return this; }

    public Map<String, Object> toMap(Instant updatedAt) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("captureId", captureId);
        manifest.put("captureStartedAt", captureStartedAt);
        manifest.put("requestedDurationSeconds", requestedDurationSeconds);
        manifest.put("updatedAt", updatedAt.toString());
        Map<String, Object> serverMap = new LinkedHashMap<>();
        serverMap.put("version", server.version());
        serverMap.put("javaVersion", server.javaVersion());
        serverMap.put("diagnosticsVersion", server.diagnosticsVersion());
        serverMap.put("plugins", server.plugins().stream().map(p -> orderedMap("name", p.name(), "version", p.version())).toList());
        manifest.put("server", serverMap);
        manifest.put("workload", orderedMap("atStart", workload(atStart), "atEnd", workload(atEnd)));
        manifest.put("files", files.values().stream().map(CaptureManifest::file).toList());
        return manifest;
    }

    public byte[] toJson(Instant updatedAt) {
        return new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(toMap(updatedAt)).getBytes(StandardCharsets.UTF_8);
    }

    /** Temp file plus atomic move, so readers see the previous or the new manifest and never a partial one. */
    public void save(Path directory, Instant updatedAt) throws IOException {
        Path temp = directory.resolve(FILE + ".tmp");
        try {
            Files.write(temp, toJson(updatedAt));
            try { Files.move(temp, directory.resolve(FILE), StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, directory.resolve(FILE), StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** An existing manifest of this capture, or empty when missing or unreadable. */
    public static Optional<CaptureManifest> load(Path directory) {
        try {
            return Optional.of(parse(Files.readString(directory.resolve(FILE), StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static CaptureManifest parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject server = root.getAsJsonObject("server");
        List<Plugin> plugins = new ArrayList<>();
        for (JsonElement plugin : server.getAsJsonArray("plugins")) {
            plugins.add(new Plugin(string(plugin.getAsJsonObject(), "name"), string(plugin.getAsJsonObject(), "version")));
        }
        JsonObject workload = root.getAsJsonObject("workload");
        var manifest = new CaptureManifest(root.get("captureId").getAsString(), root.get("captureStartedAt").getAsString(),
                root.get("requestedDurationSeconds").getAsInt(),
                new Server(string(server, "version"), string(server, "javaVersion"), string(server, "diagnosticsVersion"), plugins),
                workload(workload.get("atStart")));
        manifest.atEnd = workload(workload.get("atEnd"));
        for (JsonElement element : root.getAsJsonArray("files")) {
            JsonObject file = element.getAsJsonObject();
            manifest.add(new Entry(file.get("name").getAsString(), string(file, "collector"), file.get("segment").getAsInt(),
                    file.get("bytes").getAsLong(), file.get("uncompressedBytes").getAsLong(), file.get("sha256").getAsString(),
                    file.get("complete").getAsBoolean(), string(file, "reason"), string(file, "writtenAt")));
        }
        return manifest;
    }

    private static Workload workload(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        JsonObject object = element.getAsJsonObject();
        List<WorldLoad> worlds = new ArrayList<>();
        for (JsonElement world : object.getAsJsonArray("worlds")) {
            JsonObject row = world.getAsJsonObject();
            worlds.add(new WorldLoad(row.get("name").getAsString(), row.get("loadedChunks").getAsInt(), row.get("entities").getAsInt()));
        }
        return new Workload(object.get("players").getAsInt(), worlds);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static Map<String, Object> workload(Workload workload) {
        if (workload == null) return null;
        return orderedMap("players", workload.players(), "worlds", workload.worlds().stream()
                .map(w -> orderedMap("name", w.name(), "loadedChunks", w.loadedChunks(), "entities", w.entities())).toList());
    }

    private static Map<String, Object> file(Entry entry) {
        return orderedMap("name", entry.name(), "collector", entry.collector(), "segment", entry.segment(), "bytes", entry.bytes(),
                "uncompressedBytes", entry.uncompressedBytes(), "sha256", entry.sha256(), "complete", entry.complete(),
                "reason", entry.reason(), "writtenAt", entry.writtenAt());
    }

    private static Map<String, Object> orderedMap(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) map.put((String) keyValues[i], keyValues[i + 1]);
        return map;
    }
}
