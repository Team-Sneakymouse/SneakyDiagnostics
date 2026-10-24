package com.teamsneakymouse.sneakydiagnostics.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Every plain {@code java} block of the consumer guide is source that the reference classes compile. */
class ConsumerGuideTest {
    private static final Path GUIDE = firstExisting("../docs/plugin-providers.md", "docs/plugin-providers.md");
    private static final Path REFERENCE = firstExisting(
            "src/test/java/com/teamsneakymouse/sneakydiagnostics/integration/reference",
            "plugin/src/test/java/com/teamsneakymouse/sneakydiagnostics/integration/reference");

    private static Path firstExisting(String... candidates) {
        for (String candidate : candidates) if (Files.exists(Path.of(candidate))) return Path.of(candidate);
        throw new IllegalStateException("Not found: " + List.of(candidates));
    }

    private static String squash(String text) { return text.replaceAll("\\s+", ""); }

    private static List<String> javaBlocks() throws IOException {
        var blocks = new ArrayList<String>();
        StringBuilder current = null;
        for (String line : Files.readAllLines(GUIDE)) {
            if (current == null && line.equals("```java")) current = new StringBuilder();
            else if (current != null && line.equals("```")) { blocks.add(current.toString()); current = null; }
            else if (current != null) current.append(line).append('\n');
        }
        return blocks;
    }

    @Test void guideSnippetsAreCompiledReferenceSource() throws IOException {
        var source = new StringBuilder();
        try (var files = Files.list(REFERENCE)) {
            for (Path file : files.toList()) source.append(Files.readString(file));
        }
        String compiled = squash(source.toString());
        var blocks = javaBlocks();
        assertEquals(4, blocks.size(), "shim, wiring, tick loop, event handler");
        for (String block : blocks) assertTrue(compiled.contains(squash(block)), "Not in the reference sources:\n" + block);
    }

    @Test void theGuideShimIsTheReferenceShim() throws IOException {
        String shim = Files.readString(REFERENCE.resolve("Probe.java"));
        assertEquals(squash(shim.substring(shim.indexOf("\nimport "))), squash(javaBlocks().getFirst()));
    }
}
