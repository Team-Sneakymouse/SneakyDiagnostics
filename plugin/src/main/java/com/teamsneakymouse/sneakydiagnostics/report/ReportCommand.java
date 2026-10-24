package com.teamsneakymouse.sneakydiagnostics.report;

import com.teamsneakymouse.sneakydiagnostics.report.ReportCatalog.Capture;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** {@code /diagnostics reports ...} as text in and lines out, so it needs no server. */
public final class ReportCommand {
    public static final String USAGE = "/diagnostics reports <list|prune keep <n> [confirm]>";
    private static final int MAX_LINES = 40;

    private ReportCommand() {}

    /** {@code args} follow the {@code reports} word; {@code busy} marks captures that are active or have a pending write. */
    public static List<String> run(Path root, String[] args, Instant now, Predicate<String> busy) {
        if (args.length == 0) throw new IllegalArgumentException(USAGE);
        try {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "list" -> {
                    if (args.length != 1) throw new IllegalArgumentException("reports list takes no arguments");
                    yield list(ReportCatalog.scan(root), now);
                }
                case "prune" -> prune(root, args, now, busy);
                default -> throw new IllegalArgumentException(USAGE);
            };
        } catch (IOException e) {
            return List.of("Cannot read " + root + ": " + e);
        }
    }

    public static List<String> complete(String[] args) {
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = switch (args.length) {
            case 1 -> List.of("list", "prune");
            case 2 -> args[0].equalsIgnoreCase("prune") ? List.of("keep") : List.of();
            case 4 -> args[0].equalsIgnoreCase("prune") && args[1].equalsIgnoreCase("keep") ? List.of("confirm") : List.of();
            default -> List.of();
        };
        return options.stream().filter(option -> option.startsWith(prefix)).toList();
    }

    static List<String> list(ReportCatalog.Listing listing, Instant now) {
        List<String> lines = new ArrayList<>();
        long total = listing.captures().stream().mapToLong(Capture::bytes).sum();
        lines.add(listing.captures().size() + " capture folders, " + size(total) + ", newest first");
        for (Capture capture : listing.captures().subList(0, Math.min(listing.captures().size(), MAX_LINES))) lines.add(describe(capture, now));
        if (listing.captures().size() > MAX_LINES) lines.add("... and " + (listing.captures().size() - MAX_LINES) + " older");
        for (var problem : listing.problems()) lines.add("Ignored " + problem.name() + ": " + problem.message());
        return lines;
    }

    private static List<String> prune(Path root, String[] args, Instant now, Predicate<String> busy) throws IOException {
        String usage = "/diagnostics reports prune keep <n> [confirm]";
        if (args.length < 3 || args.length > 4 || !args[1].equalsIgnoreCase("keep")) throw new IllegalArgumentException(usage);
        boolean confirm = args.length == 4;
        if (confirm && !args[3].equalsIgnoreCase("confirm")) throw new IllegalArgumentException(usage);
        int keep;
        try { keep = Integer.parseInt(args[2]); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("keep must be a whole number: " + args[2]); }
        if (keep < 0) throw new IllegalArgumentException("keep must not be negative");

        var listing = ReportCatalog.scan(root);
        Set<String> protectedIds = listing.captures().stream().map(Capture::id).filter(busy).collect(Collectors.toSet());
        var plan = ReportCatalog.plan(listing.captures(), keep, protectedIds);
        List<String> lines = new ArrayList<>();
        long freed = plan.delete().stream().mapToLong(Capture::bytes).sum();
        if (plan.delete().isEmpty()) {
            lines.add("Nothing to delete: " + listing.captures().size() + " captures, keeping the newest " + keep);
        } else if (!confirm) {
            lines.add("Would delete " + plan.delete().size() + " of " + listing.captures().size() + " captures (" + size(freed)
                    + "), keeping the newest " + keep + ". Nothing was deleted; add 'confirm' to delete:");
            int shown = Math.min(plan.delete().size(), MAX_LINES);
            for (int i = 0; i < shown; i++) lines.add(describe(plan.delete().get(i), now));
            if (plan.delete().size() > shown) lines.add("... and " + (plan.delete().size() - shown) + " more");
        } else {
            var result = ReportCatalog.delete(root, plan.delete());
            long deletedBytes = result.deleted().stream().mapToLong(Capture::bytes).sum();
            lines.add("Deleted " + result.deleted().size() + " of " + plan.delete().size() + " captures (" + size(deletedBytes)
                    + "), keeping the newest " + keep + ".");
            for (var failure : result.failed()) lines.add("Failed to delete " + failure.name() + ": " + failure.message());
        }
        for (var capture : plan.skipped()) lines.add("Skipped " + capture.id() + ": capture is active or still writing");
        for (var problem : listing.problems()) lines.add("Ignored " + problem.name() + ": " + problem.message());
        return lines;
    }

    private static String describe(Capture capture, Instant now) {
        return capture.id() + "  " + age(Duration.between(capture.startedAt(), now)) + " ago  " + capture.files()
                + (capture.files() == 1 ? " file  " : " files  ") + size(capture.bytes());
    }

    static String age(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return seconds / 60 + "m " + seconds % 60 + "s";
        if (seconds < 86_400) return seconds / 3600 + "h " + seconds % 3600 / 60 + "m";
        return seconds / 86_400 + "d " + seconds % 86_400 / 3600 + "h";
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GiB", bytes / 1073741824.0);
    }
}
