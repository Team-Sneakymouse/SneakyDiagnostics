package com.teamsneakymouse.sneakydiagnostics.report;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.teamsneakymouse.sneakydiagnostics.report.CaptureManifest.Plugin;
import com.teamsneakymouse.sneakydiagnostics.report.CaptureManifest.Server;
import com.teamsneakymouse.sneakydiagnostics.report.CaptureManifest.Workload;
import com.teamsneakymouse.sneakydiagnostics.report.CaptureManifest.WorldLoad;
import com.teamsneakymouse.sneakydiagnostics.report.ReportStore.Capture;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReportStoreTest {
    private static final Server SERVER = new Server("Paper 26.2", "25.0.1", "test", List.of(new Plugin("MagicSpells", "4.0")));
    private static final Workload START = new Workload(34, List.of(new WorldLoad("world", 5414, 22356)));
    private static final Workload END = new Workload(30, List.of(new WorldLoad("world", 5400, 22000)));

    @TempDir Path root;
    private final List<IOException> manifestFailures = new ArrayList<>();
    private final ReportStore store() {
        return new ReportStore(root, Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC), (dir, e) -> manifestFailures.add(e));
    }
    private static Capture capture(String id, Workload end) { return new Capture(id, "2026-10-24T11:59:00Z", 60, SERVER, START, end); }
    private static Map<String, Object> report(String collector, boolean complete, int padding) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("collector", collector);
        report.put("reason", "completed");
        report.put("complete", complete);
        if (padding > 0) {
            report.put("padding", "x".repeat(padding));
        }
        return report;
    }
    private static JsonObject manifest(Path directory) throws IOException {
        return JsonParser.parseString(Files.readString(directory.resolve("manifest.json"))).getAsJsonObject();
    }

    @Test void manifestIsValidAfterEveryFileIncludingPartialCaptures() throws IOException {
        var store = store();
        var capture = capture("c1", null);
        store.writeJson(capture, "timeline", 1, "timeline-1.json", report("timeline", true, 0));
        var afterFirst = manifest(store.directory("c1"));
        assertEquals(2, afterFirst.get("schemaVersion").getAsInt());
        assertEquals("c1", afterFirst.get("captureId").getAsString());
        assertEquals(1, afterFirst.getAsJsonArray("files").size());
        assertTrue(afterFirst.getAsJsonObject("workload").get("atEnd").isJsonNull());
        assertEquals("MagicSpells", afterFirst.getAsJsonObject("server").getAsJsonArray("plugins").get(0).getAsJsonObject().get("name").getAsString());

        store.writeJson(capture, "scheduler", 1, "scheduler-1.json", report("scheduler", false, 0));
        var afterSecond = manifest(store.directory("c1"));
        assertEquals(2, afterSecond.getAsJsonArray("files").size());
        var second = afterSecond.getAsJsonArray("files").get(1).getAsJsonObject();
        assertEquals("scheduler", second.get("collector").getAsString());
        assertFalse(second.get("complete").getAsBoolean());
        assertEquals("completed", second.get("reason").getAsString());
        assertEquals("2026-10-24T12:00:00Z", second.get("writtenAt").getAsString());
        assertEquals("2026-10-24T12:00:00Z", afterSecond.get("updatedAt").getAsString());
        assertFalse(Files.exists(store.directory("c1").resolve("manifest.json.tmp")));
        assertTrue(manifestFailures.isEmpty());
    }

    @Test void workloadHoldsCountsOnlyAndEndIsKeptOnceSampled() throws IOException {
        var store = store();
        store.writeJson(capture("c1", null), "timeline", 1, "timeline-1.json", report("timeline", true, 0));
        store.writeJson(capture("c1", END), "entities", 1, "entities-1.json", report("entities", true, 0));
        store.writeJson(capture("c1", null), "spark", 1, "spark-1.json", report("spark", true, 0));
        var workload = manifest(store.directory("c1")).getAsJsonObject("workload");
        var start = workload.getAsJsonObject("atStart");
        assertEquals(34, start.get("players").getAsInt());
        assertEquals(Set.of("loadedChunks", "entities", "name"), start.getAsJsonArray("worlds").get(0).getAsJsonObject().keySet());
        assertEquals(Set.of("players", "worlds"), start.keySet());
        assertEquals(30, workload.getAsJsonObject("atEnd").get("players").getAsInt());
    }

    @Test void plainFilesRecordIdenticalByteCountsAndShaOfTheStoredBytes() throws IOException {
        var store = store();
        Path path = store.writeJson(capture("c1", null), "timeline", 1, "timeline-1.json", report("timeline", true, 100));
        assertEquals("timeline-1.json", path.getFileName().toString());
        var entry = manifest(store.directory("c1")).getAsJsonArray("files").get(0).getAsJsonObject();
        byte[] stored = Files.readAllBytes(path);
        assertEquals(stored.length, entry.get("bytes").getAsLong());
        assertEquals(stored.length, entry.get("uncompressedBytes").getAsLong());
        assertEquals(StoredFile.sha256(stored), entry.get("sha256").getAsString());
    }

    @Test void largeReportsAreGzippedAndTheManifestDescribesTheStoredFile() throws IOException {
        var store = store();
        Path path = store.writeJson(capture("c1", null), "entities", 1, "entities-1.json", report("entities", true, 2 * 1024 * 1024));
        assertEquals("entities-1.json.gz", path.getFileName().toString());
        assertFalse(Files.exists(store.directory("c1").resolve("entities-1.json")));
        byte[] stored = Files.readAllBytes(path);
        byte[] json = new GZIPInputStream(new ByteArrayInputStream(stored)).readAllBytes();
        assertTrue(json.length > StoredFile.COMPRESS_ABOVE_BYTES);
        assertEquals("entities", JsonParser.parseString(new String(json)).getAsJsonObject().get("collector").getAsString());
        var entry = manifest(store.directory("c1")).getAsJsonArray("files").get(0).getAsJsonObject();
        assertEquals("entities-1.json.gz", entry.get("name").getAsString());
        assertEquals(stored.length, entry.get("bytes").getAsLong());
        assertEquals(json.length, entry.get("uncompressedBytes").getAsLong());
        assertTrue(entry.get("bytes").getAsLong() < entry.get("uncompressedBytes").getAsLong());
        assertEquals(StoredFile.sha256(stored), entry.get("sha256").getAsString());
        assertNotEquals(StoredFile.sha256(json), entry.get("sha256").getAsString());
    }

    @Test void artifactsAreRecordedUncompressedBesideTheirReport() throws IOException {
        var store = store();
        byte[] profile = new byte[3 * 1024 * 1024];
        store.writeArtifact(capture("c1", null), "spark", 1, "profile.sparkprofile", profile, true, "completed");
        store.writeJson(capture("c1", null), "spark", 1, "spark-1.json", report("spark", true, 0));
        var files = manifest(store.directory("c1")).getAsJsonArray("files");
        assertEquals(2, files.size());
        var artifact = files.get(0).getAsJsonObject();
        assertEquals("profile.sparkprofile", artifact.get("name").getAsString());
        assertEquals(profile.length, artifact.get("bytes").getAsLong());
        assertEquals(profile.length, artifact.get("uncompressedBytes").getAsLong());
        assertEquals(StoredFile.sha256(profile), artifact.get("sha256").getAsString());
        assertEquals(profile.length, Files.size(store.directory("c1").resolve("profile.sparkprofile")));
    }

    @Test void resumedSegmentsAppearAsAdditionalFiles() throws IOException {
        var store = store();
        store.writeJson(capture("c1", END), "entities", 1, "entities-1.json", report("entities", false, 0));
        store.writeJson(capture("c1", END), "entities", 2, "entities-2.json", report("entities", true, 0));
        var files = manifest(store.directory("c1")).getAsJsonArray("files");
        assertEquals(2, files.size());
        assertEquals("entities-1.json", files.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(1, files.get(0).getAsJsonObject().get("segment").getAsInt());
        assertEquals("entities-2.json", files.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals(2, files.get(1).getAsJsonObject().get("segment").getAsInt());
    }

    @Test void overlappingCapturesKeepSeparateFoldersAndManifests() throws IOException {
        var store = store();
        store.writeJson(capture("a", null), "timeline", 1, "timeline-1.json", report("timeline", true, 0));
        store.writeJson(capture("b", null), "entities", 1, "entities-1.json", report("entities", true, 0));
        store.writeJson(capture("a", END), "scheduler", 1, "scheduler-1.json", report("scheduler", true, 0));
        var a = manifest(store.directory("a"));
        var b = manifest(store.directory("b"));
        assertEquals("a", a.get("captureId").getAsString());
        assertEquals("b", b.get("captureId").getAsString());
        assertEquals(2, a.getAsJsonArray("files").size());
        assertEquals(1, b.getAsJsonArray("files").size());
        assertFalse(a.getAsJsonObject("workload").get("atEnd").isJsonNull());
        assertTrue(b.getAsJsonObject("workload").get("atEnd").isJsonNull());
    }

    @Test void evictedOrRestartedStoreKeepsEarlierEntriesByReloadingTheManifest() throws IOException {
        store().writeJson(capture("c1", null), "timeline", 1, "timeline-1.json", report("timeline", true, 0));
        store().writeJson(capture("c1", END), "scheduler", 1, "scheduler-1.json", report("scheduler", true, 0));
        var manifest = manifest(store().directory("c1"));
        assertEquals(2, manifest.getAsJsonArray("files").size());
        assertEquals(30, manifest.getAsJsonObject("workload").getAsJsonObject("atEnd").get("players").getAsInt());
        var store = store();
        for (int i = 0; i < 40; i++) store.writeJson(capture("other" + i, null), "timeline", 1, "timeline-1.json", report("timeline", true, 0));
        store.writeJson(capture("c1", null), "spark", 1, "spark-1.json", report("spark", true, 0));
        assertEquals(3, manifest(store.directory("c1")).getAsJsonArray("files").size());
    }

    @Test void deletedFolderDoesNotResurrectEntriesForFilesThatAreGone() throws IOException {
        var store = store();
        store.writeJson(capture("c1", null), "timeline", 1, "timeline-1.json", report("timeline", true, 0));
        for (Path file : Files.list(store.directory("c1")).toList()) Files.delete(file);
        Files.delete(store.directory("c1"));
        store.writeJson(capture("c1", null), "scheduler", 1, "scheduler-1.json", report("scheduler", true, 0));
        assertEquals(1, manifest(store.directory("c1")).getAsJsonArray("files").size());
    }

    @Test void existingFilesAreNeverOverwrittenAndLeaveTheManifestUnchanged() throws IOException {
        var store = store();
        store.writeJson(capture("c1", null), "timeline", 1, "timeline-1.json", report("timeline", true, 0));
        assertThrows(FileAlreadyExistsException.class,
                () -> store.writeJson(capture("c1", null), "timeline", 1, "timeline-1.json", report("timeline", false, 0)));
        var files = manifest(store.directory("c1")).getAsJsonArray("files");
        assertEquals(1, files.size());
        assertTrue(files.get(0).getAsJsonObject().get("complete").getAsBoolean());
    }

    @Test void manifestRoundTripsThroughJson() throws IOException {
        var original = new CaptureManifest("c1", "2026-10-24T11:59:00Z", 60, SERVER, START).atEnd(END)
                .add(new CaptureManifest.Entry("a.json", "timeline", 1, 10, 10, "ab", true, "completed", "t"));
        var parsed = CaptureManifest.parse(new String(original.toJson(Instant.EPOCH)));
        assertEquals(original.files(), parsed.files());
        assertEquals(END, parsed.atEnd());
        assertEquals(original.toMap(Instant.EPOCH), parsed.toMap(Instant.EPOCH));
    }

    @Test void unreadableManifestStartsFreshInsteadOfFailingTheWrite() throws IOException {
        var store = store();
        Files.createDirectories(store.directory("c1"));
        Files.writeString(store.directory("c1").resolve("manifest.json"), "{ not json");
        store.writeJson(capture("c1", null), "timeline", 1, "timeline-1.json", report("timeline", true, 0));
        assertEquals(1, manifest(store.directory("c1")).getAsJsonArray("files").size());
    }
}
