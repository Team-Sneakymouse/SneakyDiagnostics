package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerProbeTest {
    private String id;
    @AfterEach void cleanup() { if (id != null) { try { SchedulerProbe.stop(id); } catch (IllegalStateException ignored) { } } }
    private void start() { id = "test-capture"; SchedulerProbe.start(id, System.nanoTime(), 120); }
    private SchedulerProbe.Identity identity() { return new SchedulerProbe.Identity("MagicSpells", "UndoTask", true); }

    @Test void cancellationCallerSurvivesDeferredExecutionAndRepeatedRequestsAreCounted() {
        start();
        SchedulerProbe.created(7, identity());
        var request = SchedulerProbe.beginCancellation(7, null);
        assertSame(request, SchedulerProbe.cancellationOrigin());
        SchedulerProbe.endCancellation(request, false);
        assertNull(SchedulerProbe.cancellationOrigin());
        SchedulerProbe.finish(SchedulerProbe.deferred(request), false);
        var repeated = SchedulerProbe.beginCancellation(7, null);
        SchedulerProbe.endCancellation(repeated, false);
        var report = SchedulerProbe.stop(id);
        assertEquals(1L, report.get("repeatedTaskCancellationRequests"));
        var groups = rows(report);
        var deferred = groups.stream().filter(row -> row.get("event").equals("deferred_cancel_scan")).findFirst().orElseThrow();
        var cancel = groups.stream().filter(row -> row.get("event").equals("cancel_task_request")).findFirst().orElseThrow();
        assertEquals("MagicSpells", deferred.get("owner"));
        assertEquals(cancel.get("caller"), deferred.get("caller"));
        assertEquals(2L, ((Map<?, ?>) cancel.get("counts")).get("started"));
    }

    @Test void stopFreezesInFlightCountsAndOldDeferredWorkCannotEnterNextCapture() {
        start();
        var running = SchedulerProbe.execution(identity());
        var cancellation = SchedulerProbe.beginCancellation(7, identity());
        SchedulerProbe.endCancellation(cancellation, false);
        var report = SchedulerProbe.stop(id);
        assertEquals(1L, report.get("inFlightAtStop"));
        SchedulerProbe.finish(running, false);
        assertEquals(1L, report.get("inFlightAtStop"));
        SchedulerProbe.start(id, System.nanoTime(), 120);
        assertNull(SchedulerProbe.deferred(cancellation));
        assertThrows(IllegalStateException.class, () -> SchedulerProbe.stop("wrong-id"));
    }

    @Test void groupCapAndTaskIdentityCapAreExplicit() {
        var clock = new AtomicLong();
        var session = new SchedulerProbe.Session("bounded", 0, 120, clock::get);
        for (int i = 0; i < SchedulerProbe.MAX_GROUPS + 1; i++) session.created(i, new SchedulerProbe.Identity("p", "task" + i, true));
        var groups = session.close();
        assertEquals(SchedulerProbe.MAX_GROUPS, rows(groups).size());
        assertEquals(1L, groups.get("droppedEvents"));
        var ids = new SchedulerProbe.Session("ids", 0, 120, clock::get);
        for (int i = 0; i < SchedulerProbe.MAX_TASK_IDS + 1; i++) ids.created(i, identity());
        assertEquals(1L, ids.close().get("droppedTaskIds"));
    }

    @Test void expiryStopsNewRecordsAndDisabledHooksKeepNoCaptureState() {
        var clock = new AtomicLong();
        var session = new SchedulerProbe.Session("deadline", 0, 1, clock::get);
        session.created(1, identity());
        clock.set(1_000_000_000L);
        session.created(2, identity());
        var report = session.close();
        assertEquals(1L, ((Map<?, ?>) rows(report).getFirst().get("counts")).get("started"));
        assertFalse(SchedulerProbe.enabled());
        assertNull(SchedulerProbe.execution(identity()));
        assertNull(SchedulerProbe.beginCancellation(1, identity()));
    }

    @SuppressWarnings("unchecked") private List<Map<String, Object>> rows(Map<String, Object> report) { return (List<Map<String, Object>>) report.get("groups"); }
}
