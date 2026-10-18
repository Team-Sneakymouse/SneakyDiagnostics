package com.teamsneakymouse.sneakydiagnostics.capture;

import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureContextTest {
    @Test void captureIdsEncodeTheStartTimestampWithVersionSevenAndRfcVariant() {
        Instant start = Instant.parse("2026-10-18T01:00:00.123456789Z");
        UUID id = CaptureContext.createId(start);
        assertEquals(7, id.version());
        assertEquals(2, id.variant());
        assertEquals(start.toEpochMilli(), id.getMostSignificantBits() >>> 16);
        assertEquals(id, UUID.fromString(id.toString()));
    }

    @Test void folderNamesSortByTimestampAcrossMillisecondAndSecondBoundaries() {
        Instant start = Instant.parse("2026-10-18T01:00:00.999Z");
        String previous = CaptureContext.createId(start).toString();
        for (int i = 1; i <= 1000; i++) {
            String next = CaptureContext.createId(start.plusMillis(i)).toString();
            assertTrue(previous.compareTo(next) < 0);
            previous = next;
        }
    }

    @Test void capturesInTheSameMillisecondKeepIndependentRandomIds() {
        Instant start = Instant.parse("2026-10-18T01:00:00Z");
        var ids = new HashSet<UUID>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(ids.add(CaptureContext.createId(start)));
        }
    }
}
