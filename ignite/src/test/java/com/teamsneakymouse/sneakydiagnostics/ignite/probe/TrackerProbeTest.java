package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrackerProbeTest {
    private static final long SECOND = 1_000_000_000L;
    private final AtomicLong clock = new AtomicLong(1_000 * SECOND);
    private String id;

    @AfterEach void cleanup() { if (id != null) { try { TrackerProbe.stop(id); } catch (IllegalStateException ignored) { } } }

    private TrackerProbe.Session start(int seconds, int generation) {
        id = "tracker-test";
        var session = new TrackerProbe.Session(id, clock.get(), seconds, clock::get, Thread.currentThread(), generation);
        TrackerProbe.start(session);
        return session;
    }
    private static TrackerProbe.Labeller label(String type, String owner) { return () -> new String[]{type, owner}; }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> tracker(Map<String, Object> report) { return (Map<String, Object>) report.get("tracker"); }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groups(Map<String, Object> report) { return (List<Map<String, Object>>) tracker(report).get("groups"); }

    @Test void sendsAreGroupedByTypeAndOwnerWithMetadataNestedAndRankedByTime() {
        var session = start(60, 1);
        assertSame(session, TrackerProbe.recording());
        int display = session.group(label("ITEM_DISPLAY", "pdc:sneakygrounditems"));
        assertEquals(display, session.group(label("ITEM_DISPLAY", "pdc:sneakygrounditems")), "same label, same group");
        int npc = session.group(label("PLAYER", "class:net.citizensnpcs.EntityHumanNPC"));
        long t = clock.get();
        session.send(display, t, t + 2_000);
        session.metadata(display, t + 500, t + 1_500);
        session.send(display, t + SECOND, t + SECOND + 3_000);
        session.send(npc, t + SECOND, t + SECOND + 10_000);
        var report = TrackerProbe.stop(id);
        var rows = groups(report);
        assertEquals("PLAYER", rows.get(0).get("entityType"), "largest total first");
        var displays = rows.get(1);
        assertEquals(2L, displays.get("entities"));
        assertEquals(2L, displays.get("calls"));
        assertEquals(5_000L, displays.get("totalNanos"));
        assertEquals(1L, displays.get("metadataCalls"));
        assertEquals(1_000L, displays.get("metadataNanos"));
        assertEquals(3_000L, displays.get("maxNanos"));
        var totals = tracker(report);
        assertEquals(3L, totals.get("calls"));
        assertEquals(15_000L, totals.get("totalNanos"));
        assertEquals(3L, totals.get("entities"));
        assertEquals(true, totals.get("complete"));
        @SuppressWarnings("unchecked") var series = (Map<String, Object>) totals.get("series");
        assertEquals(List.of(1L, 2L), ((List<?>) series.get("calls")).subList(0, 2));
        assertEquals(List.of(2_000L, 13_000L), ((List<?>) series.get("nanos")).subList(0, 2));
        assertEquals(61, ((List<?>) series.get("nanos")).size(), "one window per second plus the boundary");
    }

    @Test void histogramBucketsAreFloorLog2() {
        assertEquals(0, TrackerProbe.bucket(0));
        assertEquals(0, TrackerProbe.bucket(1));
        assertEquals(1, TrackerProbe.bucket(2));
        assertEquals(9, TrackerProbe.bucket(1_000));
        assertEquals(TrackerProbe.HISTOGRAM_BUCKETS - 1, TrackerProbe.bucket(Long.MAX_VALUE));
    }

    @Test void groupsBeyondTheCapGoToOverflowAndMakeTheReportIncomplete() {
        var session = start(60, 2);
        for (int i = 0; i < TrackerProbe.MAX_GROUPS; i++) assertNotEquals(TrackerProbe.OVERFLOW, session.group(label("T" + i, "o")));
        assertEquals(TrackerProbe.OVERFLOW, session.group(label("one-too-many", "o")));
        var totals = tracker(TrackerProbe.stop(id));
        assertEquals(1L, totals.get("overflowEntities"));
        assertEquals(false, totals.get("complete"));
    }

    @Test void labelFailuresAreCountedNotThrown() {
        var session = start(60, 3);
        int group = session.group(() -> { throw new IllegalStateException("entity gone"); });
        session.send(group, clock.get(), clock.get() + 10);
        var report = TrackerProbe.stop(id);
        assertEquals("(unknown)", groups(report).get(0).get("entityType"));
        assertEquals(1L, tracker(report).get("labelErrors"));
        assertEquals(false, tracker(report).get("complete"));
    }

    @Test void callsEndingAfterTheDeadlineOrAfterStopAreNotRecorded() {
        var session = start(1, 4);
        int group = session.group(label("ZOMBIE", "(none)"));
        long t = clock.get();
        session.send(group, t + 2 * SECOND, t + 2 * SECOND + 5);
        var report = TrackerProbe.stop(id);
        assertEquals(0L, tracker(report).get("calls"));
        assertEquals(1L, tracker(report).get("lateCalls"));
        session.send(group, t, t + 5);           // a call that held the session across the stop
        assertNull(TrackerProbe.recording());
        assertEquals(0L, tracker(report).get("calls"), "the detached report does not change");
    }

    @Test void otherThreadsAreCountedNotRecorded() throws Exception {
        var session = start(60, 5);
        var worker = new Thread(() -> assertNull(TrackerProbe.recording()));
        worker.start();
        worker.join();
        assertSame(session, TrackerProbe.recording());
        var totals = tracker(TrackerProbe.stop(id));
        assertEquals(1L, totals.get("offThreadCalls"));
        assertEquals(false, totals.get("complete"));
    }

    @Test void onlyOneCaptureAtATimeAndStopNeedsItsId() {
        start(60, 6);
        assertThrows(IllegalStateException.class,
                () -> TrackerProbe.start(new TrackerProbe.Session("other", clock.get(), 60, clock::get, Thread.currentThread(), 7)));
        assertThrows(IllegalStateException.class, () -> TrackerProbe.stop("other"));
        assertTrue(TrackerProbe.status().startsWith("recording"));
        TrackerProbe.stop(id);
        assertEquals("available, idle", TrackerProbe.status());
    }
}
