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

    @Test void slowExecutionsKeepTheLongestWithOffsetsAndTickOnlyOnTheServerThread() {
        var clock = new AtomicLong(1_000_000_000L);
        var tick = new java.util.concurrent.atomic.AtomicInteger(4242);
        var session = new SchedulerProbe.Session("slow", 1_000_000_000L, 120, clock::get, tick::get);
        clock.addAndGet(5_000_000);                                       // starts 5 ms into the capture
        var fast = session.execution(identity("Fast"));
        clock.addAndGet(SchedulerProbe.SLOW_NANOS - 1);
        session.finish(fast, false);
        var sync = session.execution(identity("Menu"));
        clock.addAndGet(300_000_000);
        session.finish(sync, false);
        var async = session.execution(identity("Async"));
        clock.addAndGet(2_000_000);
        tick.set(ThreadTick.OTHER_THREAD);
        session.finish(async, true);
        session.created(9, identity());                                   // bookkeeping: never a slow call
        var slow = slow(session.close(), "slowExecutions");
        assertEquals(2L, slow.get("observed"));
        assertEquals(0L, slow.get("evicted"));
        assertEquals(true, slow.get("complete"));
        var records = records(slow);
        var menu = records.get(0);
        assertEquals("Menu", menu.get("taskClass"));
        assertEquals(5_000L + (SchedulerProbe.SLOW_NANOS - 1) / 1_000, menu.get("startOffsetMicros"));
        assertEquals(300_000_000L, menu.get("nanos"));
        assertEquals("server", menu.get("thread"));
        assertEquals(4242L, menu.get("tick"));
        var other = records.get(1);
        assertEquals("other", other.get("thread"));
        assertFalse(other.containsKey("tick"), "an async call has no tick identity");
        assertEquals(true, other.get("throwing"));
    }

    @Test void slowExecutionCapEvictsTheShortestAndCountsEveryOmission() {
        var clock = new AtomicLong();
        var session = new SchedulerProbe.Session("cap", 0, 120, clock::get, () -> 1);
        int extra = 7;
        for (int i = 0; i < SchedulerProbe.MAX_SLOW_EXECUTIONS + extra; i++) {
            var token = session.execution(identity("t"));
            clock.addAndGet(SchedulerProbe.SLOW_NANOS + i);               // later calls are longer
            session.finish(token, false);
        }
        var slow = slow(session.close(), "slowExecutions");
        assertEquals((long) SchedulerProbe.MAX_SLOW_EXECUTIONS + extra, slow.get("observed"));
        assertEquals((long) extra, slow.get("evicted"));
        assertEquals(false, slow.get("complete"));
        long shortestKept = records(slow).stream().mapToLong(row -> (long) row.get("nanos")).min().orElseThrow();
        assertEquals(SchedulerProbe.SLOW_NANOS + extra, shortestKept);
    }

    @Test void callsPastTheDeadlineAreFlaggedAndUnfinishedOnesAreReportedRunningAtStop() {
        var clock = new AtomicLong();
        var session = new SchedulerProbe.Session("deadline", 0, 1, clock::get, () -> 7);
        var straddling = session.execution(identity("Straddle"));
        var stuck = session.execution(identity("Stuck"));
        clock.set(1_200_000_000L);
        session.finish(straddling, false);
        assertNull(session.execution(identity("Late")), "nothing new starts after the deadline");
        var report = session.close();
        var straddle = records(slow(report, "slowExecutions")).getFirst();
        assertEquals(true, straddle.get("endedAfterDeadline"));
        var running = slow(report, "runningAtStop");
        assertEquals(1L, running.get("count"));
        assertEquals("Stuck", records(running).getFirst().get("taskClass"));
        assertEquals(1_200_000_000L, records(running).getFirst().get("runningNanosAtStop"));
        session.finish(stuck, false);                                     // a late completion changes nothing
        assertEquals(1L, records(slow(report, "slowExecutions")).size());
    }

    @Test void listenerHookIsInertWhenIdleAndRecordsOnlySlowCalls() {
        assertEquals(0L, SchedulerProbe.listenerStart(), "idle: no clock read, no record");
        var clock = new AtomicLong(10_000_000_000L);
        id = "listeners";
        SchedulerProbe.start(new SchedulerProbe.Session(id, clock.get(), 60, clock::get, () -> 99));
        long quick = SchedulerProbe.listenerStart();
        clock.addAndGet(10_000);
        assertEquals(-1L, SchedulerProbe.listenerEnd(quick));
        long started = SchedulerProbe.listenerStart();
        clock.addAndGet(250_000_000);
        long elapsed = SchedulerProbe.listenerEnd(started);
        assertEquals(250_000_000L, elapsed);
        SchedulerProbe.slowListener(started, elapsed, "SneakyKnives", "com.sneakyknives.listener.MenuListener",
                "org.bukkit.event.inventory.InventoryClickEvent", "NORMAL", false, false);
        clock.addAndGet(61_000_000_000L);
        assertEquals(0L, SchedulerProbe.listenerStart(), "no new calls after the deadline");
        var listeners = slow(SchedulerProbe.stop(id), "slowListeners");
        id = null;
        assertEquals(2L, listeners.get("observedInvocations"));
        assertEquals(1L, listeners.get("observed"));
        var row = records(listeners).getFirst();
        assertEquals("SneakyKnives", row.get("owner"));
        assertEquals("org.bukkit.event.inventory.InventoryClickEvent", row.get("eventClass"));
        assertEquals(99L, row.get("tick"));
        assertEquals(10_000L / 1_000, row.get("startOffsetMicros"));
    }

    /** The tick read runs inside finish's critical section: a stop racing there must still see the completed slow call. */
    @Test void aStopRacingASlowCompletionSeesItAsCompletedNotLost() throws Exception {
        var clock = new AtomicLong();
        var report = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        var closer = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var holder = new SchedulerProbe.Session[1];
        holder[0] = new SchedulerProbe.Session("race", 0, 60, clock::get, () -> {
            // Old code read the tick after releasing the lock: the stop completed here, missing the call entirely.
            Thread stop = new Thread(() -> report.set(holder[0].close()));
            closer.set(stop);
            stop.start();
            try { stop.join(200); } catch (InterruptedException e) { throw new AssertionError(e); }
            return 5;
        });
        var token = holder[0].execution(identity("Slow"));
        clock.addAndGet(3 * SchedulerProbe.SLOW_NANOS);
        holder[0].finish(token, false);
        closer.get().join(5_000);
        var stopped = report.get();
        assertNotNull(stopped);
        assertEquals(0L, slow(stopped, "runningAtStop").get("count"));
        assertEquals(1L, slow(stopped, "slowExecutions").get("observed"), "completed before the stop, so it is in the list");
        assertEquals("Slow", records(slow(stopped, "slowExecutions")).getFirst().get("taskClass"));
    }

    @Test void listenersRunningAtStopAreCountedAndMakeTheListIncompleteUnlessTheyEncloseTheStop() throws Exception {
        var clock = new AtomicLong(1_000_000_000L);
        id = "listeners-running";
        SchedulerProbe.start(new SchedulerProbe.Session(id, clock.get(), 60, clock::get, () -> 1));
        long around = SchedulerProbe.listenerStart();               // this thread started the capture: it stops it too
        var other = new long[1];
        Thread async = new Thread(() -> other[0] = SchedulerProbe.listenerStart());
        async.start();
        async.join();
        clock.addAndGet(500_000_000L);
        var listeners = slow(SchedulerProbe.stop(id), "slowListeners");
        id = null;
        assertEquals(2L, listeners.get("observedInvocations"));
        assertEquals(2L, listeners.get("runningAtStop"));
        assertEquals(1L, listeners.get("runningAroundStop"), "the listener the stop runs inside");
        assertEquals(0L, listeners.get("observed"));
        assertEquals(false, listeners.get("complete"), "the other-thread call may have been slow");
        assertEquals(-1L, SchedulerProbe.listenerEnd(other[0]), "late ends change nothing");
        assertEquals(-1L, SchedulerProbe.listenerEnd(around));
        assertEquals(2L, listeners.get("runningAtStop"));

        id = "listeners-around-only";
        SchedulerProbe.start(new SchedulerProbe.Session(id, clock.get(), 60, clock::get, () -> 1));
        SchedulerProbe.listenerStart();
        var enclosingOnly = slow(SchedulerProbe.stop(id), "slowListeners");
        id = null;
        assertEquals(1L, enclosingOnly.get("runningAroundStop"));
        assertEquals(true, enclosingOnly.get("complete"), "the stop's own enclosing listener is expected");
    }

    /** A slow listener whose capture stops between its end and its record is reported running at stop, not dropped. */
    @Test void aStopBetweenASlowListenerEndAndItsRecordReportsItRunning() throws Exception {
        var clock = new AtomicLong(1_000_000_000L);
        id = "listener-race";
        var stopped = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        Thread[] listener = new Thread[1];
        SchedulerProbe.start(new SchedulerProbe.Session(id, clock.get(), 60, clock::get, () -> {
            stopped.set(SchedulerProbe.stop(id));                     // the stop wins the race, from the starting thread
            return 3;
        }));
        listener[0] = new Thread(() -> {
            long started = SchedulerProbe.listenerStart();
            clock.addAndGet(50_000_000L);
            long elapsed = SchedulerProbe.listenerEnd(started);
            SchedulerProbe.slowListener(started, elapsed, "Owner", "L", "E", "NORMAL", true, false);
        });
        listener[0].start();
        listener[0].join(5_000);
        id = null;
        var listeners = slow(stopped.get(), "slowListeners");
        assertEquals(0L, listeners.get("observed"));
        assertEquals(1L, listeners.get("runningAtStop"));
        assertEquals(0L, listeners.get("runningAroundStop"));
        assertEquals(false, listeners.get("complete"));
    }

    /** Every tick's measured interval is kept from its first tick-end listener call, however short the listeners are. */
    @Test void measuredTickIntervalsComeFromTheFirstTickEndListenerCallOfEachTick() throws Exception {
        var clock = new AtomicLong(1_000_000_000L);
        var tick = new java.util.concurrent.atomic.AtomicInteger(700);
        id = "measured";
        SchedulerProbe.start(new SchedulerProbe.Session(id, clock.get(), 1, clock::get, tick::get));
        clock.addAndGet(40_000_000L);                                   // the tick ran 0..40 ms, then tick-end listeners
        long first = SchedulerProbe.listenerStart();
        SchedulerProbe.tickEndDispatch(first, 40.0);
        clock.addAndGet(200_000L);                                      // a 0.2 ms listener: never a slow record
        SchedulerProbe.listenerEnd(first);
        long second = SchedulerProbe.listenerStart();                   // a later listener of the same tick: ignored
        SchedulerProbe.tickEndDispatch(second, 40.0);
        SchedulerProbe.listenerEnd(second);
        Thread other = new Thread(() -> SchedulerProbe.tickEndDispatch(SchedulerProbe.listenerStart(), 5.0));
        other.start();
        other.join();                                                   // tick-end dispatch is server-thread only
        for (int t = 1; t <= SchedulerProbe.MEASURED_TICKS_PER_SECOND + 2; t++) {
            tick.set(700 + t);
            clock.addAndGet(10_000_000L);
            SchedulerProbe.tickEndDispatch(SchedulerProbe.listenerStart(), 8.5);
        }
        var measured = slow(SchedulerProbe.stop(id), "measuredTicks");
        id = null;
        var ticks = (List<?>) measured.get("tick");
        assertEquals(SchedulerProbe.MEASURED_TICKS_PER_SECOND, ticks.size(), "capped at the capture's capacity");
        assertEquals(3L, measured.get("dropped"), "the over-capacity ticks are counted, not silently lost");
        assertEquals(700L, ticks.getFirst());
        assertEquals(0L, ((List<?>) measured.get("startOffsetMicros")).getFirst());
        assertEquals(40_000L, ((List<?>) measured.get("endOffsetMicros")).getFirst());
        assertEquals(701L, ticks.get(1));
        assertEquals(50_200L - 8_500L, ((List<?>) measured.get("startOffsetMicros")).get(1));
    }

    private static SchedulerProbe.Identity identity(String taskClass) { return new SchedulerProbe.Identity("Owner", taskClass, true); }
    @SuppressWarnings("unchecked") private static Map<String, Object> slow(Map<String, Object> report, String name) { return (Map<String, Object>) report.get(name); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> records(Map<String, Object> section) { return (List<Map<String, Object>>) section.get("records"); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> rows(Map<String, Object> report) { return (List<Map<String, Object>>) report.get("groups"); }
}
