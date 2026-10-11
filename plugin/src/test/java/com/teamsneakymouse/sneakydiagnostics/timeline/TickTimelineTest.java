package com.teamsneakymouse.sneakydiagnostics.timeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TickTimelineTest {
    @Test void alignsWindowsAndPreservesEmptySecondsAsGaps() {
        var timeline = new TickTimeline();
        timeline.record(100, 10, 20);
        timeline.record(999, 11, 120);
        timeline.record(1000, 12, 50);
        timeline.record(3001, 13, 51);
        var windows = timeline.finish();
        assertEquals(3, windows.size());
        assertEquals(0, windows.get(0).fromMillis());
        assertEquals(2, windows.get(0).ticks());
        assertEquals(70, windows.get(0).meanMspt());
        assertEquals(20, windows.get(0).minMspt());
        assertEquals(120, windows.get(0).maxMspt());
        assertEquals(1, windows.get(0).over50ms());
        assertEquals(1, windows.get(0).over100ms());
        assertEquals(0, windows.get(1).over50ms());
        assertEquals(3000, windows.get(2).fromMillis());
        assertEquals(13, windows.get(2).firstTick());
    }

    @Test void emptyCaptureAndRepeatedFinishDoNotProducePhantomTicks() {
        var timeline = new TickTimeline();
        assertTrue(timeline.finish().isEmpty());
        timeline.record(40, 1, 2);
        assertEquals(timeline.finish(), timeline.finish());
    }
}
