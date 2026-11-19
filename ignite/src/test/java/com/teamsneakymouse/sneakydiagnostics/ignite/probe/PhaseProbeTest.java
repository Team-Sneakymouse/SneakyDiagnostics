package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PhaseProbeTest {
    private final AtomicLong time = new AtomicLong(1_000_000_000L);
    private String id;

    @AfterEach void stopLeftover() {
        try { if (id != null) PhaseProbe.stop(id); } catch (IllegalStateException ignored) { }
    }

    private PhaseProbe.Session start(String captureId, int seconds) {
        id = captureId;
        var session = new PhaseProbe.Session(captureId, time.get(), seconds, time::get);
        PhaseProbe.start(session);
        return session;
    }
    private void ms(double millis) { time.addAndGet(Math.round(millis * 1_000_000)); }
    private void phase(int slot, double millis, Runnable inside) {
        var session = PhaseProbe.active();
        int frame = session.enter(slot);
        ms(millis);
        if (inside != null) inside.run();
        session.exit(frame);
    }
    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> map, String... path) {
        Object value = map;
        for (String key : path) value = ((Map<String, Object>) value).get(key);
        if (value instanceof int[] column) return java.util.Arrays.stream(column).boxed().map(Object.class::cast).toList();
        return (List<Object>) value;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> map, String key) { return (Map<String, Object>) map.get(key); }

    @Test void phasesAreExclusiveAndAddUpToTheMeasuredTick() {
        var session = start("exclusive", 60);
        assertNull(PhaseProbe.active(), "the tick that was running at start is not recorded");
        PhaseProbe.tickBegin(500);
        int world = session.world("world");
        ms(0.5);                                                                      // before tick-start tasks: other
        phase(PhaseProbe.TICK_START_TASKS, 2, null);
        phase(PhaseProbe.SCHEDULER, 1, () -> phase(session.diagnosticsSlot(), 4, null));   // a census slice in the heartbeat
        phase(PhaseProbe.Session.level(world, PhaseProbe.LEVEL_OTHER), 1, () -> {
            phase(PhaseProbe.Session.level(world, PhaseProbe.ENTITIES), 6, null);
            phase(PhaseProbe.Session.level(world, PhaseProbe.CHUNKS), 1, () -> {
                phase(PhaseProbe.Session.level(world, PhaseProbe.TRACKER), 3, null);
                phase(PhaseProbe.Session.level(world, PhaseProbe.SPAWN_STATE), 0.25, null);
            });
        });
        phase(PhaseProbe.CONNECTIONS, 1.5, null);
        ms(0.75);
        var dispatch = PhaseProbe.tickEnd(21.0);
        assertSame(session, dispatch);
        int frame = session.enter(PhaseProbe.TICK_END);
        ms(1);
        phase(session.diagnosticsSlot(), 2, null);                                   // timeline sampling at tick end
        session.exit(frame);
        session.tickEndDone();
        PhaseProbe.tickBegin(501);
        var report = PhaseProbe.stop("exclusive");
        id = null;

        assertEquals(List.of(500, 501), list(report, "ticks", "tick"));
        assertEquals(java.util.Arrays.asList(21_000, null), list(report, "ticks", "measuredMicros"));
        var phases = map(report, "phases");
        assertEquals(2_000, list(report, "phases", "tick-start-tasks").get(0));
        assertEquals(1_000, list(report, "phases", "scheduler").get(0), "the nested diagnostics slice is subtracted");
        assertEquals(4_000, list(report, "phases", "diagnostics").get(0));
        assertEquals(6_000, list(report, "phases", "level.world.entities").get(0));
        assertEquals(1_000, list(report, "phases", "level.world.chunks").get(0));
        assertEquals(3_000, list(report, "phases", "level.world.tracker").get(0));
        assertEquals(250, list(report, "phases", "level.world.spawning.state").get(0));
        assertEquals(1_000, list(report, "phases", "level.world.other").get(0));
        assertEquals(1_500, list(report, "phases", "connections").get(0));
        assertFalse(phases.containsKey("level.world.spawning.spawn"), "a phase that never ran is absent");
        long sum = phases.values().stream().mapToLong(values -> ((int[]) values)[0]).sum();
        assertEquals(21_000 - sum, ((Number) list(report, "other").get(0)).longValue());
        assertEquals(1_250, list(report, "other").get(0), "0.5 ms before the tasks and 0.75 ms before tick end");
        assertNull(list(report, "other").get(1), "the last tick was stopped before Paper measured it");
        assertEquals(1_000, list(report, "outside", "tick-end").get(0));
        assertEquals(2_000, list(report, "outside", "diagnostics").get(0));
        assertEquals(Boolean.TRUE, report.get("lastTickPartial"));
        assertEquals(Boolean.TRUE, report.get("complete"));
        var totals = map(report, "totals");
        assertEquals(4_000_000L, map(totals, "diagnostics").get("totalNanos"));
        assertEquals(2_000_000L, map(totals, "outside.diagnostics").get("totalNanos"));
        assertEquals(List.of("world"), report.get("worlds"));
    }

    @Test void stoppingInsideTheTickEndDispatchKeepsTheWholeMeasuredTick() {
        var session = start("tick-end-stop", 60);
        PhaseProbe.tickBegin(7);
        phase(PhaseProbe.SCHEDULER, 3, null);
        PhaseProbe.tickEnd(5.0);
        session.enter(PhaseProbe.TICK_END);
        ms(1);
        session.enter(session.diagnosticsSlot());                                      // the stopping listener
        ms(0.5);
        var report = PhaseProbe.stop("tick-end-stop");
        id = null;
        assertEquals(Boolean.FALSE, report.get("lastTickPartial"));
        assertEquals(2_000, list(report, "other").get(0));
        assertEquals(1_000, list(report, "outside", "tick-end").get(0));
        assertEquals(500, list(report, "outside", "diagnostics").get(0));
        assertNull(PhaseProbe.active());
        session.exit(1);                                                               // late exits after the stop are ignored
        assertEquals(0L, report.get("nestingErrors"));
    }

    @Test void capacityDropsRowsButTotalsCoverEveryTick() {
        start("capacity", 1);                                                          // 40 rows
        for (int tick = 0; tick < 45; tick++) {
            PhaseProbe.tickBegin(tick);
            phase(PhaseProbe.CONNECTIONS, 0.001, null);
            PhaseProbe.tickEnd(0.002);
        }
        PhaseProbe.tickBegin(45);
        var report = PhaseProbe.stop("capacity");
        id = null;
        var ticks = map(report, "ticks");
        assertEquals(40L, ticks.get("capacity"));
        assertEquals(40, list(report, "ticks", "tick").size());
        assertEquals(6L, ticks.get("dropped"), "45 measured ticks and the stopped one, 40 kept");
        assertEquals(45L, map(map(report, "totals"), "connections").get("calls"));
        assertEquals(Boolean.FALSE, report.get("complete"));
    }

    @Test void ticksAfterTheDeadlineAreNotRecorded() {
        start("deadline", 1);
        PhaseProbe.tickBegin(1);
        ms(1_001);
        PhaseProbe.tickBegin(2);
        assertNull(PhaseProbe.active());
        assertTrue(PhaseProbe.status().contains("expired"));
        var report = PhaseProbe.stop("deadline");
        id = null;
        assertEquals(List.of(1), list(report, "ticks", "tick"));
    }

    @Test void nestingProblemsAreCountedAndMakeTheReportIncomplete() {
        var session = start("nesting", 60);
        PhaseProbe.tickBegin(1);
        int[] frames = new int[PhaseProbe.MAX_DEPTH + 1];
        for (int i = 0; i < frames.length; i++) frames[i] = session.enter(PhaseProbe.SCHEDULER);
        assertEquals(-1, frames[PhaseProbe.MAX_DEPTH]);
        for (int i = frames.length - 1; i >= 0; i--) session.exit(frames[i]);
        int outer = session.enter(PhaseProbe.SCHEDULER);
        session.enter(PhaseProbe.CONNECTIONS);                                         // never exited
        session.exit(outer);
        session.enter(PhaseProbe.CONNECTIONS);                                         // still open at the next tick
        PhaseProbe.tickBegin(2);
        var report = PhaseProbe.stop("nesting");
        id = null;
        assertEquals(1L, report.get("depthOverflow"));
        assertEquals(1L, report.get("nestingErrors"));
        assertEquals(1L, report.get("spanningTicks"));
        assertEquals(Boolean.FALSE, report.get("complete"));
    }

    @Test void worldsAreAClosedSetAndSpawningIsCounted() {
        var session = start("worlds", 60);
        PhaseProbe.tickBegin(1);
        for (int i = 0; i < PhaseProbe.MAX_WORLDS; i++) assertEquals(i, session.world("w" + i));
        assertEquals(0, session.world("w0"));
        assertEquals(-1, session.world("extra"));
        assertEquals(-1, session.enter(PhaseProbe.Session.level(-1, PhaseProbe.ENTITIES)));
        session.spawnState(0);
        long before = session.spawnChunk(0);
        session.spawnChunkDone(0, before);                                             // a spawnForChunk call that spawned nothing
        before = session.spawnChunk(0);
        session.naturalSpawnAttempt(0);
        session.naturalSpawnAttempt(0);
        session.spawnChunkDone(0, before);
        session.customSpawners(0);
        var report = PhaseProbe.stop("worlds");
        id = null;
        assertEquals(List.of("extra"), report.get("omittedWorlds"));
        assertEquals(Boolean.FALSE, report.get("complete"));
        var w0 = map(map(report, "spawning"), "w0");
        assertEquals(1L, w0.get("stateCalls"));
        assertEquals(2L, w0.get("spawnChunkCalls"));
        assertEquals(1L, w0.get("spawnChunkCallsThatSpawned"));
        assertEquals(2L, w0.get("naturalSpawnAttempts"));
        assertEquals(1L, w0.get("customSpawnerCalls"));
    }

    @Test void longCapturesKeepTotalsForColumnsBeyondTheValueBudget() {
        var session = start("budget", 1800);                                           // 72,000 rows: 111 columns fit
        PhaseProbe.tickBegin(1);
        for (int world = 0; world < 13; world++) {
            int index = session.world("w" + world);
            for (int kind = 0; kind < PhaseProbe.KINDS; kind++) phase(PhaseProbe.Session.level(index, kind), 0.001, null);
        }
        var report = PhaseProbe.stop("budget");
        id = null;
        List<?> omitted = (List<?>) report.get("omittedSeries");
        assertEquals(13 * PhaseProbe.KINDS - (int) (PhaseProbe.MAX_VALUES / 72_000), omitted.size());
        assertTrue(map(report, "totals").containsKey((String) omitted.getFirst()), "omitted series keep their totals");
        assertEquals(Boolean.FALSE, report.get("complete"));
    }

    @Test void onlyTheServerThreadRecordsAndAnOffThreadStopDiscards() throws Exception {
        start("threads", 60);
        PhaseProbe.tickBegin(1);
        AtomicReference<Object> seen = new AtomicReference<>("unset");
        Thread other = new Thread(() -> {
            seen.set(PhaseProbe.active());
            PhaseProbe.tickBegin(99);
            seen.set(new Object[]{seen.get(), PhaseProbe.stop("threads")});
        });
        other.start();
        other.join();
        id = null;
        Object[] result = (Object[]) seen.get();
        assertNull(result[0]);
        @SuppressWarnings("unchecked") var report = (Map<String, Object>) result[1];
        assertEquals(Boolean.FALSE, report.get("complete"));
        assertTrue(((String) report.get("error")).contains("server thread"));
        assertEquals("available, idle", PhaseProbe.status());
    }
}
