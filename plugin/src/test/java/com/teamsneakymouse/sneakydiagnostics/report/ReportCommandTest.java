package com.teamsneakymouse.sneakydiagnostics.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReportCommandTest {
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    @TempDir Path root;

    private static String id(int n) { return new UUID(0x0123456789abL << 16 | 0x7000, n | Long.MIN_VALUE).toString(); }
    private void capture(int n, String startedAt) throws IOException {
        Path folder = Files.createDirectories(root.resolve(id(n)));
        Files.writeString(folder.resolve("manifest.json"), "{\"captureStartedAt\":\"" + startedAt + "\"}");
        Files.write(folder.resolve("timeline-1.json"), new byte[2048]);
    }
    private List<String> run(Set<String> busy, String... args) { return ReportCommand.run(root, args, NOW, busy::contains); }

    @Test void listShowsIdAgeFileCountAndSizeNewestFirst() throws IOException {
        capture(1, "2026-10-22T09:00:00Z");
        capture(2, "2026-10-24T11:30:00Z");
        var lines = run(Set.of(), "list");
        assertTrue(lines.getFirst().startsWith("2 capture folders"));
        assertTrue(lines.get(1).startsWith(id(2)) && lines.get(1).contains("30m 0s ago") && lines.get(1).contains("2 files"), lines.get(1));
        assertTrue(lines.get(2).startsWith(id(1)) && lines.get(2).contains("2d 3h ago"), lines.get(2));
    }

    @Test void pruneWithoutConfirmOnlyListsWhatWouldBeDeleted() throws IOException {
        for (int i = 1; i <= 4; i++) capture(i, "2026-10-2" + i + "T00:00:00Z");
        var lines = run(Set.of(), "prune", "keep", "2");
        assertTrue(lines.getFirst().startsWith("Would delete 2 of 4"), lines.getFirst());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith(id(1))) && lines.stream().anyMatch(line -> line.startsWith(id(2))));
        for (int i = 1; i <= 4; i++) assertTrue(Files.isDirectory(root.resolve(id(i))));
    }

    @Test void confirmDeletesAllButTheNewestAndSkipsBusyCaptures() throws IOException {
        for (int i = 1; i <= 4; i++) capture(i, "2026-10-2" + i + "T00:00:00Z");
        var lines = run(Set.of(id(1)), "prune", "keep", "1", "confirm");
        assertTrue(lines.getFirst().startsWith("Deleted 2 of 2"), lines.getFirst());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("Skipped " + id(1))));
        assertTrue(Files.isDirectory(root.resolve(id(4))));
        assertTrue(Files.isDirectory(root.resolve(id(1))));
        assertFalse(Files.exists(root.resolve(id(2))));
        assertFalse(Files.exists(root.resolve(id(3))));
    }

    @Test void nothingToDeleteWhenEverythingFitsOrIsBusy() throws IOException {
        capture(1, "2026-10-22T00:00:00Z");
        assertTrue(run(Set.of(), "prune", "keep", "5", "confirm").getFirst().startsWith("Nothing to delete"));
        assertTrue(run(Set.of(id(1)), "prune", "keep", "0", "confirm").getFirst().startsWith("Nothing to delete"));
        assertTrue(Files.isDirectory(root.resolve(id(1))));
    }

    @Test void deletionFailuresAreReportedPerCapture() throws IOException {
        capture(1, "2026-10-22T00:00:00Z");
        capture(2, "2026-10-23T00:00:00Z");
        Files.createDirectories(root.resolve(id(1)).resolve("nested"));
        var lines = run(Set.of(), "prune", "keep", "1", "confirm");
        assertTrue(lines.getFirst().startsWith("Deleted 0 of 1"), lines.getFirst());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("Failed to delete " + id(1))));
    }

    @Test void badArgumentsAreRejectedWithoutTouchingDisk() throws IOException {
        capture(1, "2026-10-22T00:00:00Z");
        for (var args : List.of(new String[0], new String[]{"nope"}, new String[]{"list", "x"}, new String[]{"prune"},
                new String[]{"prune", "keep"}, new String[]{"prune", "keep", "x"}, new String[]{"prune", "keep", "-1"},
                new String[]{"prune", "keep", "1", "yes"}, new String[]{"prune", "keep", "1", "confirm", "x"}, new String[]{"prune", "1"})) {
            assertThrows(IllegalArgumentException.class, () -> run(Set.of(), args));
        }
        assertTrue(Files.isDirectory(root.resolve(id(1))));
    }

    @Test void missingReportsDirectoryListsNothing() {
        assertTrue(ReportCommand.run(root.resolve("missing"), new String[]{"list"}, NOW, id -> false).getFirst().startsWith("0 capture folders"));
    }

    @Test void completionOffersTheSubcommandGrammar() {
        assertEquals(List.of("list", "prune"), ReportCommand.complete(new String[]{""}));
        assertEquals(List.of("prune"), ReportCommand.complete(new String[]{"p"}));
        assertEquals(List.of("keep"), ReportCommand.complete(new String[]{"prune", ""}));
        assertEquals(List.of(), ReportCommand.complete(new String[]{"prune", "keep", ""}));
        assertEquals(List.of("confirm"), ReportCommand.complete(new String[]{"prune", "keep", "3", "c"}));
        assertEquals(List.of(), ReportCommand.complete(new String[]{"list", ""}));
    }

    @Test void formatsAgeAndSizeCompactly() {
        assertEquals("5s", ReportCommand.age(Duration.ofSeconds(5)));
        assertEquals("0s", ReportCommand.age(Duration.ofSeconds(-5)));
        assertEquals("3h 4m", ReportCommand.age(Duration.ofMinutes(184)));
        assertEquals("512 B", ReportCommand.size(512));
        assertEquals("1.5 KiB", ReportCommand.size(1536));
        assertEquals("2.0 MiB", ReportCommand.size(2 * 1024 * 1024));
    }
}
