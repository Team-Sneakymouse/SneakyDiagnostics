package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerControlTest {
    private final ServerControl control = new ServerControl();

    @AfterEach void stopLeftovers() {
        for (String probe : ServerControl.PROBES.keySet()) {
            for (String id : List.of("shared", "phases-only", "tracker-only", "packets-only")) {
                try { control.stop(probe, id); } catch (IllegalStateException ignored) { }
            }
        }
    }

    @Test void bothProbesRecordTheSameCaptureConcurrently() {
        long started = System.nanoTime();
        control.start("phases", "shared", started, 5);
        control.start("tracker", "shared", started, 5);
        assertEquals("phases starting shared at the next tick; tracker recording shared; packets available, idle", control.getStatus());

        recordPhase();
        recordSend();

        var tracker = control.stop("tracker", "shared");
        assertEquals("phases recording shared; tracker available, idle; packets available, idle", control.getStatus(), "stopping one probe leaves the other");
        var phases = control.stop("phases", "shared");
        assertEquals("phases available, idle; tracker available, idle; packets available, idle", control.getStatus());

        assertEquals("shared", phases.get("captureId"));
        assertEquals("sneakydiagnostics.phases/1", phases.get("schema"));
        assertEquals(1, schedulerCalls(phases));
        assertEquals("shared", tracker.get("captureId"));
        assertEquals(1L, map(tracker, "tracker").get("calls"));
    }

    @Test void eachProbeRunsAlone() {
        long started = System.nanoTime();
        control.start("phases", "phases-only", started, 5);
        assertNull(TrackerProbe.recording(), "starting phases must not start the tracker");
        recordPhase();
        assertEquals(1, schedulerCalls(control.stop("phases", "phases-only")));

        control.start("tracker", "tracker-only", started, 5);
        assertTrue(control.getStatus().startsWith("phases available, idle; "), control.getStatus());
        recordSend();
        assertEquals(1L, map(control.stop("tracker", "tracker-only"), "tracker").get("calls"));
    }

    @Test void aProbeRejectsASecondCaptureButTheOtherProbeIsUnaffected() {
        long started = System.nanoTime();
        control.start("tracker", "shared", started, 5);
        assertThrows(IllegalStateException.class, () -> control.start("tracker", "tracker-only", started, 5));
        control.start("phases", "shared", started, 5);
        assertThrows(IllegalStateException.class, () -> control.stop("phases", "tracker-only"), "capture IDs are checked per probe");
    }

    /** The packets collector starts the tracker and packets probes under one capture ID; each returns its own sections. */
    @Test void trackerAndPacketsProbesRecordOneCaptureAndReturnTheirOwnSections() {
        long started = System.nanoTime();
        control.start("tracker", "shared", started, 5);
        control.start("packets", "shared", started, 5);
        assertEquals("phases available, idle; tracker recording shared; packets recording shared", control.getStatus());
        recordSend();
        var packets = PacketProbe.active();
        assertNotNull(packets);
        long now = System.nanoTime();
        packets.sent(PacketProbe.slot(String.class), now, now + 500, packets.sendBegin());
        var packetMap = control.stop("packets", "shared");
        assertEquals("phases available, idle; tracker recording shared; packets available, idle", control.getStatus());
        var tracker = control.stop("tracker", "shared");
        assertEquals("shared", packetMap.get("captureId"));
        assertEquals(List.of("captureId", "outbound", "causes", "trackerCauses", "inbound"), List.copyOf(packetMap.keySet()));
        assertEquals(1L, map(packetMap, "outbound").get("sends"));
        assertEquals(1L, map(tracker, "tracker").get("calls"));
        assertFalse(tracker.containsKey("outbound"), "probes return only their own sections");
    }

    @Test void unknownProbeNamesAreRejectedClearly() {
        var start = assertThrows(IllegalArgumentException.class, () -> control.start("bytes", "shared", System.nanoTime(), 5));
        assertEquals("Unknown server probe 'bytes'; known: phases, tracker, packets", start.getMessage());
        assertThrows(IllegalArgumentException.class, () -> control.stop(null, "shared"));
        assertEquals("phases available, idle; tracker available, idle; packets available, idle", control.getStatus(), "a rejected start starts nothing");
        assertEquals(1, control.getProtocolVersion());
    }

    private static void recordPhase() {
        PhaseProbe.tickBegin(1);
        var session = PhaseProbe.active();
        assertNotNull(session);
        session.exit(session.enter(PhaseProbe.SCHEDULER));
    }
    private static void recordSend() {
        var session = TrackerProbe.recording();
        assertNotNull(session);
        int group = session.group(() -> new String[]{"ITEM_DISPLAY", "pdc:test"});
        long now = System.nanoTime();
        session.send(group, now, now + 1_000);
    }
    private static long schedulerCalls(Map<String, Object> phases) {
        return ((Number) map(map(phases, "totals"), "scheduler").get("calls")).longValue();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> map, String key) { return (Map<String, Object>) map.get(key); }
}
