package com.teamsneakymouse.sneakydiagnostics.capture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureArgumentsTest {
    @Test void existingWorldCommandAndNewTimedCapturesAreSupported() {
        assertEquals(new CaptureArguments.Start(120, "world"), CaptureArguments.start("entities", new String[]{"world"}));
        assertEquals(new CaptureArguments.Start(240, "world"), CaptureArguments.start("entities", new String[]{"world", "240"}));
        assertEquals(new CaptureArguments.Start(30, null), CaptureArguments.start("entities", new String[]{"30"}));
        assertEquals(new CaptureArguments.Start(60, null), CaptureArguments.start("all", new String[0]));
        assertEquals(new CaptureArguments.Start(90, "world"), CaptureArguments.start("all", new String[]{"90", "world"}));
    }

    @Test void malformedOrUnboundedDurationsAndExtraArgumentsAreRejected() {
        for (String value : new String[]{"0", "-1", "1801", "999999999999", "1.5", "world"}) {
            assertThrows(IllegalArgumentException.class, () -> CaptureArguments.seconds(value));
        }
        assertThrows(IllegalArgumentException.class, () -> CaptureArguments.start("timeline", new String[]{"60", "world"}));
        assertThrows(IllegalArgumentException.class, () -> CaptureArguments.start("entities", new String[]{"world", "60", "extra"}));
    }
}
