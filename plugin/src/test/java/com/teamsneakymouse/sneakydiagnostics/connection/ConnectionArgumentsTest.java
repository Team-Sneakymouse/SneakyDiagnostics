package com.teamsneakymouse.sneakydiagnostics.connection;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionArgumentsTest {
    private static final UUID ALICE = new UUID(0, 1), BOB = new UUID(0, 2);
    private static UUID resolve(String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "alice" -> ALICE;
            case "bob" -> BOB;
            default -> null;
        };
    }
    @Test void acceptsUntargetedAndTargetedStartsAndPreservesInputOrder() {
        assertEquals(new ConnectionArguments.Start(60, List.of()), ConnectionArguments.start(new String[]{}, ConnectionArgumentsTest::resolve));
        assertEquals(new ConnectionArguments.Start(120, List.of()), ConnectionArguments.start(new String[]{"120"}, ConnectionArgumentsTest::resolve));
        assertEquals(new ConnectionArguments.Start(60, List.of(ALICE)), ConnectionArguments.start(new String[]{"Alice"}, ConnectionArgumentsTest::resolve));
        assertEquals(new ConnectionArguments.Start(120, List.of(BOB, ALICE)), ConnectionArguments.start(new String[]{"120", "Bob", "Alice"}, ConnectionArgumentsTest::resolve));
    }
    @Test void rejectsUnknownDuplicateAndExcessTargetsBeforeStartingAnything() {
        for (String[] args : new String[][]{{"120", "Alice", "absent"}, {"Alice", "ALICE"}, {"0"}, {"1801"}, {"999999999999999999"}})
            assertThrows(IllegalArgumentException.class, () -> ConnectionArguments.start(args, ConnectionArgumentsTest::resolve));
        var tooMany = java.util.Collections.nCopies(17, "Alice").toArray(String[]::new);
        assertThrows(IllegalArgumentException.class, () -> ConnectionArguments.start(tooMany, name -> { fail("Validate target count before resolving"); return null; }));
    }
}
