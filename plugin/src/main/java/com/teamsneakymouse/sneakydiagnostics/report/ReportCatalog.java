package com.teamsneakymouse.sneakydiagnostics.report;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Finds, orders and deletes capture folders directly under {@code reports/}; no Bukkit. */
public final class ReportCatalog {
    private static final Pattern CAPTURE_ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int HEADER_FILES_TRIED = 3;

    public record Capture(String id, Path path, Instant startedAt, int files, long bytes) {}
    public record Problem(String name, String message) {}
    /** Captures are newest first by capture start; problems are entries that looked like captures but were not usable. */
    public record Listing(List<Capture> captures, List<Problem> problems) {}
    public record Plan(List<Capture> keep, List<Capture> delete, List<Capture> skipped) {}
    public record Deletion(List<Capture> deleted, List<Problem> failed) {}

    private ReportCatalog() {}

    public static boolean looksLikeCapture(String name) { return CAPTURE_ID.matcher(name).matches(); }

    public static Listing scan(Path root) throws IOException {
        List<Capture> captures = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (!looksLikeCapture(name)) continue;
                if (Files.isSymbolicLink(child)) { problems.add(new Problem(name, "symbolic link, ignored")); continue; }
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
                try { captures.add(inspect(child)); }
                catch (IOException e) { problems.add(new Problem(name, "unreadable: " + e)); }
            }
        } catch (NoSuchFileException e) {
            return new Listing(List.of(), List.of());
        }
        captures.sort(Comparator.comparing(Capture::startedAt).thenComparing(Capture::id).reversed());
        problems.sort(Comparator.comparing(Problem::name));
        return new Listing(List.copyOf(captures), List.copyOf(problems));
    }

    private static Capture inspect(Path directory) throws IOException {
        int files = 0;
        long bytes = 0;
        List<Path> reports = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) continue;
                files++;
                bytes += Files.size(child);
                String name = child.getFileName().toString();
                if (!name.equals(CaptureManifest.FILE) && (name.endsWith(".json") || name.endsWith(".json.gz"))) reports.add(child);
            }
        }
        Instant started = manifestStart(directory);
        if (started == null) {
            reports.sort(Comparator.comparing(Path::getFileName));
            for (Path report : reports.subList(0, Math.min(reports.size(), HEADER_FILES_TRIED))) {
                started = headerStart(report);
                if (started != null) break;
            }
        }
        if (started == null) started = Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS).toInstant();
        return new Capture(directory.getFileName().toString(), directory, started, files, bytes);
    }

    private static Instant manifestStart(Path directory) {
        Path manifest = directory.resolve(CaptureManifest.FILE);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return null;
        try (var reader = new JsonReader(Files.newBufferedReader(manifest, StandardCharsets.UTF_8))) {
            return captureStart(reader);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** v1 and v2 reports carry {@code captureStartedAt} in their header; only the leading keys are read. */
    private static Instant headerStart(Path report) {
        try (var in = Files.newInputStream(report);
             var reader = new JsonReader(new InputStreamReader(report.toString().endsWith(".gz") ? new GZIPInputStream(in) : in, StandardCharsets.UTF_8))) {
            return captureStart(reader);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Instant captureStart(JsonReader reader) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            if (reader.nextName().equals("captureStartedAt") && reader.peek() == JsonToken.STRING) return Instant.parse(reader.nextString());
            reader.skipValue();
        }
        return null;
    }

    /** Keeps the newest {@code keep} captures; deletable ones that are protected are skipped, not deleted. */
    public static Plan plan(List<Capture> newestFirst, int keep, Set<String> protectedIds) {
        if (keep < 0) throw new IllegalArgumentException("keep must not be negative");
        List<Capture> kept = new ArrayList<>(), delete = new ArrayList<>(), skipped = new ArrayList<>();
        for (int i = 0; i < newestFirst.size(); i++) {
            Capture capture = newestFirst.get(i);
            if (i < keep) kept.add(capture);
            else if (protectedIds.contains(capture.id())) skipped.add(capture);
            else delete.add(capture);
        }
        return new Plan(List.copyOf(kept), List.copyOf(delete), List.copyOf(skipped));
    }

    /**
     * Deletes the files of each capture and then the folder. Symbolic links inside are removed as links and
     * never followed; a subdirectory or I/O error leaves that folder in place and is reported.
     */
    public static Deletion delete(Path root, List<Capture> captures) {
        List<Capture> deleted = new ArrayList<>();
        List<Problem> failed = new ArrayList<>();
        for (Capture capture : captures) {
            try {
                if (!looksLikeCapture(capture.id()) || !root.equals(capture.path().getParent())
                        || Files.isSymbolicLink(capture.path()) || !Files.isDirectory(capture.path(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("not a capture folder directly under " + root);
                }
                List<Path> children = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(capture.path())) { stream.forEach(children::add); }
                for (Path child : children) {
                    // Check everything first so a refused folder is left untouched.
                    if (!Files.isSymbolicLink(child) && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("unexpected subdirectory " + child.getFileName());
                    }
                }
                for (Path child : children) Files.delete(child);
                Files.delete(capture.path());
                deleted.add(capture);
            } catch (IOException | RuntimeException e) {
                failed.add(new Problem(capture.id(), e.toString()));
            }
        }
        return new Deletion(List.copyOf(deleted), List.copyOf(failed));
    }
}
