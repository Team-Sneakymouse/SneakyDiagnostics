package com.teamsneakymouse.sneakydiagnostics.report;

import com.teamsneakymouse.sneakydiagnostics.report.ReportCatalog.Capture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReportCatalogTest {
    @TempDir Path root;

    private Path folder(String id) throws IOException { return Files.createDirectories(root.resolve(id)); }
    private static String id(int n) { return new UUID(0x0123456789abL << 16 | 0x7000, n | Long.MIN_VALUE).toString(); }
    private Path manifest(String id, String startedAt) throws IOException {
        return Files.writeString(folder(id).resolve("manifest.json"), "{\"schemaVersion\":2,\"captureId\":\"" + id + "\",\"captureStartedAt\":\"" + startedAt + "\",\"files\":[]}");
    }
    private static List<String> ids(ReportCatalog.Listing listing) { return listing.captures().stream().map(Capture::id).toList(); }

    @Test void listsNewestFirstByCaptureStartNotByFolderName() throws IOException {
        manifest(id(1), "2026-10-24T10:00:00Z");
        manifest(id(2), "2026-10-24T12:00:00Z");
        manifest(id(3), "2026-10-24T11:00:00Z");
        assertEquals(List.of(id(2), id(3), id(1)), ids(ReportCatalog.scan(root)));
    }

    @Test void legacyUuidV4FoldersWithoutManifestsUseReportHeadersPlainOrGzipped() throws IOException {
        String old = UUID.randomUUID().toString(), older = UUID.randomUUID().toString();
        // Leading keys are read; a much larger body after them is never parsed.
        Files.writeString(folder(old).resolve("timeline-1.json"), "{\"schemaVersion\":1,\"collector\":\"timeline\",\"captureId\":\"x\",\"captureStartedAt\":\"2026-10-20T00:00:00Z\",\"windows\":[1,2,3]}");
        try (var out = new GZIPOutputStream(Files.newOutputStream(folder(older).resolve("entities-1.json.gz")))) {
            out.write("{\"collector\":\"entities\",\"captureStartedAt\":\"2026-10-19T00:00:00Z\"}".getBytes());
        }
        manifest(id(1), "2026-10-24T10:00:00Z");
        var listing = ReportCatalog.scan(root);
        assertEquals(List.of(id(1), old, older), ids(listing));
        assertEquals(Instant.parse("2026-10-20T00:00:00Z"), listing.captures().get(1).startedAt());
        assertEquals(Instant.parse("2026-10-19T00:00:00Z"), listing.captures().get(2).startedAt());
    }

    @Test void foldersWithoutUsableTimestampsFallBackToFolderModificationTime() throws IOException {
        String empty = UUID.randomUUID().toString(), broken = UUID.randomUUID().toString();
        Files.writeString(folder(broken).resolve("manifest.json"), "{ nope");
        Files.writeString(root.resolve(broken).resolve("timeline-1.json"), "garbage");
        // Set last: creating the files above changes the folder's own modification time.
        Files.setLastModifiedTime(folder(empty), FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(root.resolve(broken), FileTime.from(Instant.parse("2026-02-01T00:00:00Z")));
        var listing = ReportCatalog.scan(root);
        assertEquals(List.of(broken, empty), ids(listing));
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), listing.captures().get(1).startedAt());
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), listing.captures().get(0).startedAt());
    }

    @Test void countsFilesAndBytesAndIgnoresEverythingThatIsNotACaptureFolder() throws IOException {
        manifest(id(1), "2026-10-24T10:00:00Z");
        Files.write(root.resolve(id(1)).resolve("a.json"), new byte[100]);
        Files.createDirectories(root.resolve("not-a-capture"));
        Files.writeString(root.resolve("notes.txt"), "keep me");
        Files.writeString(root.resolve(id(9)), "a file named like a capture");
        var listing = ReportCatalog.scan(root);
        assertEquals(1, listing.captures().size());
        assertEquals(2, listing.captures().getFirst().files());
        assertTrue(listing.captures().getFirst().bytes() >= 100);
        assertTrue(listing.problems().isEmpty());
    }

    @Test void missingReportsDirectoryIsAnEmptyListing() throws IOException {
        assertTrue(ReportCatalog.scan(root.resolve("missing")).captures().isEmpty());
    }

    @Test void planKeepsTheNewestAndNeverDeletesProtectedCaptures() throws IOException {
        for (int i = 1; i <= 5; i++) manifest(id(i), "2026-10-24T1" + i + ":00:00Z");
        var listing = ReportCatalog.scan(root);
        var plan = ReportCatalog.plan(listing.captures(), 2, Set.of(id(2), id(5)));
        assertEquals(List.of(id(5), id(4)), plan.keep().stream().map(Capture::id).toList());
        assertEquals(List.of(id(3), id(1)), plan.delete().stream().map(Capture::id).toList());
        assertEquals(List.of(id(2)), plan.skipped().stream().map(Capture::id).toList());
        assertTrue(ReportCatalog.plan(listing.captures(), 99, Set.of()).delete().isEmpty());
        assertEquals(5, ReportCatalog.plan(listing.captures(), 0, Set.of()).delete().size());
        assertThrows(IllegalArgumentException.class, () -> ReportCatalog.plan(listing.captures(), -1, Set.of()));
    }

    @Test void deleteRemovesOnlyTheChosenFoldersAndTheirFiles() throws IOException {
        manifest(id(1), "2026-10-24T10:00:00Z");
        manifest(id(2), "2026-10-24T11:00:00Z");
        Files.writeString(root.resolve(id(1)).resolve("timeline-1.json"), "{}");
        Files.writeString(root.resolve("keep.txt"), "x");
        var listing = ReportCatalog.scan(root);
        var result = ReportCatalog.delete(root, List.of(listing.captures().get(1)));
        assertEquals(1, result.deleted().size());
        assertTrue(result.failed().isEmpty());
        assertFalse(Files.exists(root.resolve(id(1))));
        assertTrue(Files.exists(root.resolve(id(2)).resolve("manifest.json")));
        assertTrue(Files.exists(root.resolve("keep.txt")));
    }

    @Test void foldersWithSubdirectoriesAreRefusedWholeAndReportedWithoutPartialDeletion() throws IOException {
        manifest(id(1), "2026-10-24T10:00:00Z");
        Files.writeString(root.resolve(id(1)).resolve("a.json"), "{}");
        Files.createDirectories(root.resolve(id(1)).resolve("surprise"));
        var listing = ReportCatalog.scan(root);
        var result = ReportCatalog.delete(root, listing.captures());
        assertTrue(result.deleted().isEmpty());
        assertEquals(1, result.failed().size());
        assertTrue(result.failed().getFirst().message().contains("surprise"));
        assertTrue(Files.exists(root.resolve(id(1)).resolve("a.json")));
        assertTrue(Files.exists(root.resolve(id(1)).resolve("manifest.json")));
    }

    @Test void deleteRefusesCapturesThatAreNotDirectChildrenOfTheReportsDirectory() throws IOException {
        Path elsewhere = Files.createDirectories(root.resolve("sub").resolve(id(1)));
        Files.writeString(elsewhere.resolve("a.json"), "{}");
        var result = ReportCatalog.delete(root, List.of(new Capture(id(1), elsewhere, Instant.EPOCH, 1, 2)));
        assertEquals(1, result.failed().size());
        assertTrue(Files.exists(elsewhere.resolve("a.json")));
        var traversal = ReportCatalog.delete(root, List.of(new Capture("..", root.resolve(".."), Instant.EPOCH, 0, 0)));
        assertEquals(1, traversal.failed().size());
        assertTrue(Files.exists(root));
    }

    @Test void symbolicLinksAreIgnoredWhenListingAndNeverFollowedWhenDeleting() throws IOException {
        Path outside = Files.createDirectories(root.resolve("outside"));
        Files.writeString(outside.resolve("precious.txt"), "do not delete");
        Path reports = Files.createDirectories(root.resolve("reports"));
        try {
            Files.createSymbolicLink(reports.resolve(id(1)), outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "Symbolic links are unavailable: " + e);
        }
        manifest2(reports, id(2), "2026-10-24T10:00:00Z");
        Files.createSymbolicLink(reports.resolve(id(2)).resolve("link.json"), outside.resolve("precious.txt"));
        var listing = ReportCatalog.scan(reports);
        assertEquals(List.of(id(2)), ids(listing));
        assertEquals(List.of(id(1)), listing.problems().stream().map(ReportCatalog.Problem::name).toList());

        var forged = ReportCatalog.delete(reports, List.of(new Capture(id(1), reports.resolve(id(1)), Instant.EPOCH, 0, 0)));
        assertEquals(1, forged.failed().size());
        assertTrue(Files.exists(outside.resolve("precious.txt")));

        var result = ReportCatalog.delete(reports, listing.captures());
        assertTrue(result.failed().isEmpty());
        assertFalse(Files.exists(reports.resolve(id(2))));
        assertTrue(Files.exists(outside.resolve("precious.txt")), "the link is removed, not its target");
    }

    private static void manifest2(Path reports, String id, String startedAt) throws IOException {
        Files.writeString(Files.createDirectories(reports.resolve(id)).resolve("manifest.json"), "{\"captureStartedAt\":\"" + startedAt + "\"}");
    }

    @Test void reportsPerFolderDeletionFailuresAndContinuesWithTheRest() throws IOException {
        manifest(id(1), "2026-10-24T10:00:00Z");
        manifest(id(2), "2026-10-24T11:00:00Z");
        Files.createDirectories(root.resolve(id(2)).resolve("nested"));
        var listing = ReportCatalog.scan(root);
        var result = ReportCatalog.delete(root, listing.captures());
        assertEquals(List.of(id(1)), result.deleted().stream().map(Capture::id).toList());
        assertEquals(List.of(id(2)), result.failed().stream().map(ReportCatalog.Problem::name).toList());
    }
}
