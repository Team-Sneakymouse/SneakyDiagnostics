package com.teamsneakymouse.sneakydiagnostics.probe;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProbeRecorderTest {
    private static final long MS = 1_000_000L, SEC = 1_000_000_000L, BASE = 5_000_000_000L;

    private long now = BASE;
    private int tick = 100;
    private final ProbeRecorder probe = new ProbeRecorder(() -> now, () -> tick);

    private void begin(int seconds) {
        now = BASE;
        probe.begin(BASE, seconds, tick);
    }

    /** A section of the given duration, starting now. */
    private void run(String name, long nanos) {
        long t = probe.start();
        now += nanos;
        probe.end(name, t);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    private static Map<String, Object> section(Map<String, Object> report, String name) {
        for (Object o : list(report.get("sections"))) if (name.equals(map(o).get("name"))) return map(o);
        return null;
    }

    private static long num(Object o) {
        return (Long) o;
    }

    /** Mirrors ProviderRegistry.detachValue: every value counts one, maps and lists included, keys not. */
    private static int values(Object value, int depth) {
        assertTrue(depth <= 16, "too deep");
        if (value instanceof Map<?, ?> m) {
            int n = 1;
            for (var e : m.entrySet()) {
                assertTrue(e.getKey() instanceof String k && k.length() <= 192);
                n += values(e.getValue(), depth + 1);
            }
            return n;
        }
        if (value instanceof List<?> l) {
            int n = 1;
            for (Object o : l) n += values(o, depth + 1);
            return n;
        }
        assertTrue(value instanceof Long || value instanceof Double || value instanceof String || value instanceof Boolean,
                "not a JSON value: " + value);
        if (value instanceof String s) assertTrue(s.length() <= 1024);
        if (value instanceof Double d) assertTrue(Double.isFinite(d));
        return 1;
    }

    private static int reportValues(Map<String, Object> report) {
        int n = 0;
        for (Object v : report.values()) n += values(v, 1);
        return n;
    }

    @Test void offPathTouchesNoState() {
        // never begun
        assertEquals(0, probe.start());
        assertFalse(probe.on());
        probe.end("unseen", 12345);
        probe.end("unseen", 0);
        probe.count("c");
        probe.level("l", 3);
        assertFalse(probe.sampleDue());
        var report = probe.finish("completed");
        assertTrue(list(report.get("sections")).isEmpty());
        assertTrue(map(report.get("counts")).isEmpty());
        assertTrue(map(report.get("levels")).isEmpty());
        assertTrue(list(report.get("slowest")).isEmpty());
        assertEquals(0L, report.get("nestingErrors"));
        assertEquals(0L, report.get("omittedKeys"));
        assertEquals(0L, report.get("observedMillis"));
        assertTrue(probe.complete());
    }

    @Test void offPathAfterFinishAndAfterDeadlineRecordsNothing() {
        begin(2);
        long t = probe.start();
        assertNotEquals(0, t);
        now += 10;
        var first = probe.finish("stopped");
        probe.end("late", t);               // token from a running capture, but it has finished
        probe.count("late");
        probe.level("late", 1);
        assertEquals(0, probe.start());
        assertSame(first, probe.finish("again"));
        assertEquals("stopped", probe.reason());
        assertTrue(list(first.get("sections")).isEmpty());
        assertTrue(map(first.get("counts")).isEmpty());
    }

    @Test void offPathReadsNoClockOrTick() {
        var reads = new int[1];
        var idle = new ProbeRecorder(() -> {
            reads[0]++;
            return BASE;
        }, () -> {
            reads[0]++;
            return 1;
        });
        idle.start();
        idle.end("x", 1);
        idle.count("x");
        idle.level("x", 1);
        idle.sampleDue();
        idle.on();
        assertEquals(0, reads[0]);
    }

    @Test void nestedSelfTimeSumsToParentTotal() {
        begin(10);
        long parent = probe.start();
        now += 10;
        long a = probe.start();
        now += 30;
        probe.end("child.a", a);
        now += 10;
        long b = probe.start();
        now += 20;
        long grandchild = probe.start();
        now += 5;
        probe.end("grandchild", grandchild);
        now += 5;
        probe.end("child.b", b);
        now += 20;
        probe.end("parent", parent);
        var r = probe.finish("completed");
        var p = section(r, "parent");
        assertEquals(100L, p.get("totalNanos"));
        assertEquals(10 + 10 + 20L, p.get("selfNanos"));
        assertEquals(p.get("totalNanos"), num(p.get("selfNanos")) + num(section(r, "child.a").get("totalNanos"))
                + num(section(r, "child.b").get("totalNanos")));
        assertEquals(30L, section(r, "child.a").get("selfNanos"));
        assertEquals(30L, section(r, "child.b").get("totalNanos"));
        assertEquals(25L, section(r, "child.b").get("selfNanos"));
        assertEquals(5L, section(r, "grandchild").get("selfNanos"));
        assertEquals(0L, r.get("nestingErrors"));
    }

    @Test void siblingsRepeatedCallsAccumulateAndMeanIsIntegerDivision() {
        begin(10);
        run("s", 10);
        run("s", 20);
        run("s", 31);
        var s = section(probe.finish("completed"), "s");
        assertEquals(3L, s.get("calls"));
        assertEquals(61L, s.get("totalNanos"));
        assertEquals(61L, s.get("selfNanos"));
        assertEquals(20L, s.get("meanNanos"));
        assertEquals(31L, s.get("maxNanos"));
    }

    @Test void missingInnerEndUnwindsAndCountsAnError() {
        begin(10);
        long outer = probe.start();
        now += 10;
        probe.start();                        // never ended
        now += 40;
        long leaf = probe.start();            // also never ended
        now += 10;
        now += 5;
        probe.end("outer", outer);            // unwinds both
        run("after", 7);
        var r = probe.finish("completed");
        assertEquals(2L, r.get("nestingErrors"));
        var o = section(r, "outer");
        assertEquals(65L, o.get("totalNanos"));
        assertEquals(10L, o.get("selfNanos"));     // the dropped frames' time (55 ns) is excluded as child time
        assertEquals(7L, section(r, "after").get("totalNanos"));
        assertEquals(7L, section(r, "after").get("selfNanos"));
        assertNotEquals(0, leaf);
    }

    @Test void unknownAndDuplicateEndsAreCountedAndHarmless() {
        begin(10);
        long outer = probe.start();
        now += 10;
        probe.end("bogus", outer + 3);        // no such frame
        long inner = probe.start();
        now += 10;
        probe.end("inner", inner);
        probe.end("inner", inner);            // double end: frame already gone
        now += 10;
        probe.end("outer", outer);
        var r = probe.finish("completed");
        assertEquals(2L, r.get("nestingErrors"));
        assertNull(section(r, "bogus"));
        assertEquals(1L, section(r, "inner").get("calls"));
        assertEquals(30L, section(r, "outer").get("totalNanos"));
        assertEquals(20L, section(r, "outer").get("selfNanos"));
    }

    @Test void tokenFromBeforeThisCaptureIsIgnoredSilently() {
        begin(10);
        long old = probe.start();
        probe.finish("first");
        now = BASE + SEC;
        probe.begin(now, 10, 0);
        now += 5;
        probe.end("old", old);
        var r = probe.finish("second");
        assertTrue(list(r.get("sections")).isEmpty());
        assertEquals(0L, r.get("nestingErrors"));
    }

    @Test void deeperThanMaximumCountsAnErrorAndNeverThrows() {
        begin(10);
        long[] tokens = new long[ProbeRecorder.MAX_DEPTH + 3];
        for (int i = 0; i < tokens.length; i++) {
            now += 1;
            tokens[i] = probe.start();
        }
        for (int i = 0; i < ProbeRecorder.MAX_DEPTH; i++) assertNotEquals(0, tokens[i]);
        for (int i = ProbeRecorder.MAX_DEPTH; i < tokens.length; i++) assertEquals(0, tokens[i]);
        for (int i = tokens.length - 1; i >= 0; i--) probe.end("d", tokens[i]);
        var r = probe.finish("completed");
        assertEquals(3L, r.get("nestingErrors"));
        assertEquals((long) ProbeRecorder.MAX_DEPTH, section(r, "d").get("calls"));
    }

    @Test void deadlineStopsRecordingWithoutFinish() {
        begin(2);
        now = BASE + 1500 * MS;
        run("in", 100 * MS);
        probe.count("in");
        long straddler = probe.start();
        now = BASE + 2 * SEC - 1;
        assertTrue(probe.on());
        probe.count("last");
        now = BASE + 2 * SEC;                 // the deadline: nothing is recorded from here on
        assertFalse(probe.on());
        probe.end("straddler", straddler);
        assertEquals(0, probe.start());
        probe.count("late");
        probe.level("late", 1);
        assertFalse(probe.sampleDue());
        now = BASE + 10 * SEC;
        var r = probe.finish("completed");
        assertEquals(2000L, r.get("observedMillis"));
        assertEquals(List.of("in"), list(r.get("sections")).stream().map(o -> map(o).get("name")).toList());
        assertEquals(Map.of("in", 1L, "last", 1L), map(r.get("counts")));
        assertTrue(map(r.get("levels")).isEmpty());
    }

    @Test void zeroSecondsRecordsNothing() {
        begin(0);
        assertEquals(0, probe.start());
        assertFalse(probe.on());
        assertEquals(0L, probe.finish("completed").get("observedMillis"));
    }

    @Test void observedMillisIsElapsedTimeWhenFinishedEarly() {
        begin(60);
        now += 4321 * MS + 999;
        assertEquals(4321L, probe.finish("stopped").get("observedMillis"));
    }

    @Test void histogramBucketsAndPercentileBounds() {
        assertEquals(0, ProbeRecorder.bucket(0));
        assertEquals(0, ProbeRecorder.bucket(1));
        assertEquals(1, ProbeRecorder.bucket(2));
        assertEquals(1, ProbeRecorder.bucket(3));
        assertEquals(9, ProbeRecorder.bucket(1000));
        assertEquals(10, ProbeRecorder.bucket(1024));
        assertEquals(40, ProbeRecorder.bucket(1L << 40));
        assertEquals(40, ProbeRecorder.bucket(Long.MAX_VALUE));

        begin(60);
        for (int i = 0; i < 98; i++) run("h", 1000);       // bucket 9, upper bound 1024
        run("h", 1_000_000);                               // bucket 19 (524288 <= n < 1048576), upper bound 2^20
        run("h", 1_000_000);
        var h = section(probe.finish("completed"), "h");
        var hist = list(h.get("histogramLog2"));
        assertEquals(ProbeRecorder.HISTOGRAM_BUCKETS, hist.size());
        assertEquals(98L, hist.get(9));
        assertEquals(2L, hist.get(19));
        assertEquals(100L, hist.stream().mapToLong(ProbeRecorderTest::num).sum());
        assertEquals(1024L, h.get("p50Nanos"));
        assertEquals(1024L, h.get("p95Nanos"));
        assertEquals(1L << 20, h.get("p99Nanos"));          // rank 99 is the second slow call
        assertEquals(1_000_000L, h.get("maxNanos"));
    }

    @Test void hugeCallsLandInTheLastBucket() {
        probe.begin(BASE, 1_000_000, 1);        // long enough that the call ends before the deadline
        now = BASE;
        run("huge", 100_000L * SEC);
        var h = section(probe.finish("completed"), "huge");
        assertEquals(1L, list(h.get("histogramLog2")).get(40));
        assertEquals(1L << 41, h.get("p99Nanos"));
    }

    @Test void maxRecordsWhenAndTickOfTheFirstLongestCall() {
        begin(10);
        tick = 7;
        now = BASE + 100 * MS;
        run("m", 50);
        tick = 8;
        now = BASE + 2500 * MS;
        run("m", 900);
        tick = 9;
        run("m", 900);                         // equal to max: the first one stays
        tick = 10;
        run("m", 10);
        var m = section(probe.finish("completed"), "m");
        assertEquals(900L, m.get("maxNanos"));
        assertEquals(2500L, m.get("maxAtMillis"));      // offset of the call's end
        assertEquals(8L, m.get("maxAtTick"));
    }

    @Test void slowestKeepsTheTenLongestAcrossSectionsInOrder() {
        begin(100);
        for (int i = 1; i <= 25; i++) {
            tick = 1000 + i;
            run(i % 2 == 0 ? "even" : "odd", ((i * 7) % 25 + 1) * 100L);     // a permutation of 100..2500
        }
        long t = probe.start();
        tick = 5000;
        now += 2600;
        probe.end("outer", t);
        var slowest = list(probe.finish("completed").get("slowest"));
        assertEquals(ProbeRecorder.SLOWEST, slowest.size());
        List<Long> nanos = slowest.stream().map(o -> num(map(o).get("nanos"))).toList();
        assertEquals(List.of(2600L, 2500L, 2400L, 2300L, 2200L, 2100L, 2000L, 1900L, 1800L, 1700L), nanos);
        assertEquals("outer", map(slowest.get(0)).get("section"));
        assertEquals(5000L, map(slowest.get(0)).get("atTick"));
        for (Object o : slowest) assertTrue(map(o).get("section") instanceof String);
        assertEquals(List.of("section", "nanos", "atMillis", "atTick"), List.copyOf(map(slowest.get(0)).keySet()));
    }

    @Test void slowestBoundIsFixedUnderManyCalls() {
        begin(1800);
        for (int i = 0; i < 20_000; i++) run("x", 1 + (i * 31 % 977));
        var slowest = list(probe.finish("completed").get("slowest"));
        assertEquals(10, slowest.size());
        assertEquals(977L, map(slowest.get(0)).get("nanos"));
    }

    @Test void slowestTiesOrderByTimeAndUnderTenReturnsAll() {
        begin(10);
        now = BASE + 3 * SEC;
        run("late", 100);
        now = BASE + 1 * SEC;
        run("early", 100);
        var slowest = list(probe.finish("completed").get("slowest"));
        assertEquals(2, slowest.size());
        assertEquals("early", map(slowest.get(0)).get("section"));
    }

    @Test void seriesWindowsAlignToCaptureOffsetAndAttributeToTheEndingWindow() {
        begin(5);
        now = BASE + 100 * MS;
        run("s", 10 * MS);                              // window 0
        now = BASE + 900 * MS;
        run("s", 200 * MS);                             // 900..1100 ms: ends in window 1
        now = BASE + 999 * MS;
        run("s", MS - 1);                               // ends at 999.999999 ms: window 0
        now = BASE + 1000 * MS;
        run("s", 0);                                    // window 1 exactly at its start
        now = BASE + 3200 * MS;
        run("s", 5);                                    // window 3
        now = BASE + 3500 * MS;
        var r = probe.finish("completed");
        var series = map(r.get("series"));
        assertEquals(1000L, series.get("windowMillis"));
        var s = map(map(series.get("sections")).get("s"));
        assertEquals(List.of(2L, 2L, 0L, 1L), list(s.get("calls")));
        assertEquals(List.of(10 * MS + MS - 1, 200 * MS, 0L, 5L), list(s.get("nanos")));
        long total = list(s.get("nanos")).stream().mapToLong(ProbeRecorderTest::num).sum();
        assertEquals(section(r, "s").get("totalNanos"), total);
        assertEquals(5L, list(s.get("calls")).stream().mapToLong(ProbeRecorderTest::num).sum());
    }

    @Test void seriesRunsToTheDeadlineForAFullCaptureAndIsClamped() {
        begin(3);
        now = BASE + 2999 * MS;
        run("s", 1);
        now = BASE + 10 * SEC;
        var s = map(map(map(probe.finish("completed").get("series")).get("sections")).get("s"));
        assertEquals(3, list(s.get("calls")).size());
        assertEquals(List.of(0L, 0L, 1L), list(s.get("calls")));
    }

    @Test void seriesCoversOnlyTheLargest16Sections() {
        begin(10);
        for (int i = 0; i < 20; i++) run(String.format("sec%02d", i), (i + 1) * 10L);
        var r = probe.finish("completed");
        assertEquals(20, list(r.get("sections")).size());
        var names = map(map(r.get("series")).get("sections")).keySet();
        assertEquals(16, names.size());
        for (int i = 4; i < 20; i++) assertTrue(names.contains(String.format("sec%02d", i)));
        assertFalse(names.contains("sec03"));
        // sections are listed by total, largest first
        assertEquals("sec19", map(list(r.get("sections")).get(0)).get("name"));
    }

    @Test void countSeriesListsOnlyWindowsWithACount() {
        begin(10);
        now = BASE + 100 * MS;
        probe.count("hunt.started");
        now = BASE + 900 * MS;
        probe.count("hunt.started", 2);
        now = BASE + 4 * SEC;
        probe.count("hunt.started");
        probe.count("never", 0);
        now = BASE + 6 * SEC;
        var r = probe.finish("completed");
        var counts = map(map(r.get("series")).get("counts"));
        var hunt = map(counts.get("hunt.started"));
        assertEquals(List.of(0L, 4L), list(hunt.get("windows")));
        assertEquals(List.of(3L, 1L), list(hunt.get("n")));
        assertEquals(4L, map(r.get("counts")).get("hunt.started"));
        var never = map(counts.get("never"));            // counted, but never non-zero: present and empty, not omitted
        assertTrue(list(never.get("windows")).isEmpty());
        assertTrue(list(map(map(r.get("series")).get("omitted")).get("counts")).isEmpty());
    }

    @Test void levelSeriesIsRunsOfEqualSamplesWithGapsForUnsampledWindows() {
        begin(10);
        long[] values = {3, 3, 5, -1, 5, 5, 7};              // -1: not sampled in that window
        for (int w = 0; w < values.length; w++) {
            now = BASE + w * SEC + 20 * MS;
            assertTrue(probe.sampleDue());
            if (values[w] >= 0) probe.level("players.inside", values[w]);
        }
        now = BASE + 6 * SEC + 500 * MS;
        probe.level("players.inside", 8);                    // a second sample in window 6: the last one counts
        now = BASE + 8 * SEC;
        var r = probe.finish("completed");
        var level = map(map(map(r.get("series")).get("levels")).get("players.inside"));
        assertEquals(List.of(0L, 2L, 4L, 6L), list(level.get("from")));
        assertEquals(List.of(1L, 2L, 5L, 6L), list(level.get("to")));
        assertEquals(List.of(3L, 5L, 5L, 8L), list(level.get("values")));
        assertEquals(7L, map(map(r.get("levels")).get("players.inside")).get("samples"));
    }

    @Test void sampleDueIsOncePerWindowEvenWithIrregularTicks() {
        begin(10);
        // ticks a bit over 50 ms apart, drifting against the windows: the old "a second since the last sample" rule
        // skipped a window now and then; one sample per window is what the level series needs
        int due = 0;
        var dueWindows = new java.util.TreeSet<Long>();
        for (long t = 0; t < 9 * SEC; t += 52 * MS + 300_000) {
            now = BASE + t;
            if (probe.sampleDue()) {
                due++;
                dueWindows.add(t / SEC);
            }
        }
        assertEquals(9, due);
        assertEquals(9, dueWindows.size());
    }

    @Test void seriesBudgetTakesCheapKeysFirstAndNamesTheRest() {
        int seconds = 1800;
        begin(seconds);
        for (int w = 0; w < seconds; w++) {
            now = BASE + w * SEC + 10 * MS;
            for (int i = 0; i < 30; i++) probe.count(String.format("busy.%02d", i));
            if (probe.sampleDue()) probe.level("steady", 4);
            if (w == 77) probe.count("hunt.started");
        }
        now = BASE + seconds * SEC;
        var series = map(probe.finish("completed").get("series"));
        var counts = map(series.get("counts"));
        // 2 (the marker) + 3 (one run) leave room for 13 busy counters at 2 x 1800 values each
        assertTrue(counts.containsKey("hunt.started"));
        assertTrue(map(series.get("levels")).containsKey("steady"));
        assertEquals(1 + 13, counts.size());
        var omitted = list(map(series.get("omitted")).get("counts"));
        assertEquals(17, omitted.size());
        assertEquals("busy.13", omitted.get(0));
        assertTrue(counts.containsKey("busy.12"));
        int used = 0;
        for (Object o : counts.values()) used += 2 * list(map(o).get("windows")).size();
        assertTrue(used <= ProbeRecorder.SERIES_VALUES);
    }

    @Test void keysAreCappedAndOmissionsMakeTheResultIncomplete() {
        begin(10);
        for (int i = 0; i < 256; i++) {
            run("s" + i, 1);
            probe.count("c" + i);
            probe.level("l" + i, i);
        }
        assertTrue(probe.complete());
        run("s256", 1);
        run("s256", 1);
        run("s0", 1);                      // existing names keep working
        probe.count("c256");
        probe.count("c0", 5);
        probe.level("l256", 1);
        probe.level("l0", 9);
        assertFalse(probe.complete());
        var r = probe.finish("completed");
        assertEquals(256, list(r.get("sections")).size());
        assertEquals(256, map(r.get("counts")).size());
        assertEquals(256, map(r.get("levels")).size());
        assertEquals(2L + 1 + 1, r.get("omittedKeys"));
        assertEquals(256L, r.get("maxKeys"));
        assertEquals(6L, map(r.get("counts")).get("c0"));
        assertEquals(2L, section(r, "s0").get("calls"));
        assertNull(section(r, "s256"));
        assertFalse(probe.complete());
    }

    @Test void nullAndOverlongNamesAreOmittedNotThrown() {
        begin(10);
        run(null, 1);
        run("x".repeat(193), 1);
        probe.count(null);
        probe.count("y".repeat(193));
        run("x".repeat(192), 1);
        var r = probe.finish("completed");
        assertEquals(4L, r.get("omittedKeys"));
        assertEquals(1, list(r.get("sections")).size());
        assertFalse(probe.complete());
    }

    @Test void omittedSectionStillPopsItsFrameAndKeepsParentSelfTime() {
        begin(10);
        for (int i = 0; i < 256; i++) run("s" + i, 1);
        long outer = probe.start();
        now += 10;
        long inner = probe.start();
        now += 30;
        probe.end("overflow", inner);
        probe.end("s0", outer);
        var r = probe.finish("completed");
        assertEquals(0L, r.get("nestingErrors"));
        assertEquals(1L, r.get("omittedKeys"));
        assertEquals(40L + 1, section(r, "s0").get("totalNanos"));
        assertEquals(40L + 1, section(r, "s0").get("selfNanos"));   // untracked child time stays in the parent
    }

    @Test void levelsAreSampledOnTheCadenceOfSampleDue() {
        begin(10);
        assertTrue(probe.sampleDue());          // due immediately after begin
        probe.level("placed", 4);
        now += 999 * MS;
        assertFalse(probe.sampleDue());
        now += MS;
        assertTrue(probe.sampleDue());
        probe.level("placed", 10);
        assertFalse(probe.sampleDue());
        now += 2 * SEC;
        assertTrue(probe.sampleDue());
        probe.level("placed", 1);
        probe.level("other", 7);
        var levels = map(probe.finish("completed").get("levels"));
        var placed = map(levels.get("placed"));
        assertEquals(3L, placed.get("samples"));
        assertEquals(5.0, placed.get("mean"));
        assertEquals(1L, placed.get("min"));
        assertEquals(10L, placed.get("max"));
        assertEquals(1L, placed.get("last"));
        assertEquals(Map.of("samples", 1L, "mean", 7.0, "min", 7L, "max", 7L, "last", 7L), map(levels.get("other")));
        assertEquals(List.of("samples", "mean", "min", "max", "last"), List.copyOf(placed.keySet()));
    }

    @Test void negativeLevelsKeepMinMax() {
        begin(10);
        probe.level("n", -5);
        probe.level("n", 3);
        var n = map(map(probe.finish("completed").get("levels")).get("n"));
        assertEquals(-5L, n.get("min"));
        assertEquals(3L, n.get("max"));
        assertEquals(-1.0, n.get("mean"));
    }

    @Test void beginResetsEverything() {
        begin(10);
        run("a", 100);
        probe.count("a");
        probe.level("a", 1);
        long open = probe.start();
        for (int i = 0; i < 300; i++) probe.count("k" + i);
        assertFalse(probe.complete());
        now += SEC;
        probe.begin(now, 10, 42);            // without finish: a new capture simply replaces it
        assertTrue(probe.complete());
        assertEquals(42, probe.startTick());
        assertNotEquals(0, open);
        probe.end("stale", open);               // an open frame from the old capture cannot leak in
        var r = probe.finish("completed");
        assertTrue(list(r.get("sections")).isEmpty());
        assertTrue(map(r.get("counts")).isEmpty());
        assertTrue(map(r.get("levels")).isEmpty());
        assertTrue(list(r.get("slowest")).isEmpty());
        assertEquals(0L, r.get("nestingErrors"));
        assertEquals(0L, r.get("omittedKeys"));
    }

    @Test void repeatedCapturesAreIndependent() {
        for (int round = 1; round <= 3; round++) {
            long base = BASE * round;
            now = base;
            probe.begin(base, 10, 0);
            for (int i = 0; i < round; i++) {
                long t = probe.start();
                now += 1000;
                probe.end("r", t);
            }
            assertTrue(probe.sampleDue());
            probe.level("l", round);
            var r = probe.finish("completed");
            assertEquals((long) round, section(r, "r").get("calls"));
            assertEquals(round * 1000L, section(r, "r").get("totalNanos"));
            assertEquals((long) round, map(map(r.get("levels")).get("l")).get("last"));
            assertEquals(1, map(map(r.get("series")).get("sections")).size());
            assertEquals(0L, r.get("nestingErrors"));
            assertEquals(round, list(r.get("slowest")).size());
        }
    }

    @Test void finishIsIdempotentAndStopsRecording() {
        begin(10);
        run("a", 5);
        var first = probe.finish("completed");
        run("a", 5);
        probe.count("x");
        assertSame(first, probe.finish("other"));
        assertEquals("completed", probe.reason());
        assertEquals(1L, section(first, "a").get("calls"));
    }

    @Test void outputMatchesTheSchemaShapeAndOnlyUsesJsonValues() {
        begin(5);
        tick = 3;
        run("tick", 1000);
        probe.count("energy.arrived", 3);
        probe.level("placed", 2);
        now = BASE + 2 * SEC;
        var r = probe.finish("completed");
        assertEquals(List.of("schema", "observedMillis", "sections", "slowest", "counts", "levels", "series",
                "nestingErrors", "omittedKeys", "maxKeys"), List.copyOf(r.keySet()));
        assertEquals("sneakydiagnostics.probe/1", r.get("schema"));
        assertEquals(List.of("name", "calls", "totalNanos", "selfNanos", "meanNanos", "maxNanos", "maxAtMillis",
                "maxAtTick", "p50Nanos", "p95Nanos", "p99Nanos", "histogramLog2"),
                List.copyOf(section(r, "tick").keySet()));
        var series = map(r.get("series"));
        assertEquals(List.of("windowMillis", "sections", "counts", "levels", "omitted"), List.copyOf(series.keySet()));
        assertEquals(List.of("windows", "n"), List.copyOf(map(map(series.get("counts")).get("energy.arrived")).keySet()));
        assertEquals(List.of("from", "to", "values"), List.copyOf(map(map(series.get("levels")).get("placed")).keySet()));
        assertEquals(List.of("counts", "levels"), List.copyOf(map(series.get("omitted")).keySet()));
        assertEquals(3L, section(r, "tick").get("maxAtTick"));
        assertEquals(2000L, r.get("observedMillis"));
        assertTrue(reportValues(r) > 0);
    }

    @Test void worstCaseReportStaysUnderTheProviderValueLimit() {
        int seconds = 1800;
        begin(seconds);
        // 256 sections, counts and levels, each in every window so every series array is full (levels change each window)
        for (int w = 0; w < seconds; w++) {
            now = BASE + w * SEC + 10 * MS;
            for (int i = 0; i < ProbeRecorder.MAX_KEYS; i++) run("section." + i, 100L + i);
            for (int i = 0; i < ProbeRecorder.MAX_KEYS; i++) probe.count("count." + i);
            if (probe.sampleDue()) for (int i = 0; i < ProbeRecorder.MAX_KEYS; i++) probe.level("level." + i, w);
        }
        now = BASE + seconds * SEC + 5 * SEC;
        var r = probe.finish("completed");
        assertEquals(256, list(r.get("sections")).size());
        var series = map(map(r.get("series")).get("sections"));
        assertEquals(ProbeRecorder.SERIES_SECTIONS, series.size());
        for (Object o : series.values()) {
            assertEquals(seconds, list(map(o).get("calls")).size());
            assertEquals(seconds, list(map(o).get("nanos")).size());
        }
        var omitted = map(map(r.get("series")).get("omitted"));
        assertFalse(list(omitted.get("counts")).isEmpty());
        assertFalse(list(omitted.get("levels")).isEmpty());
        int total = reportValues(r);
        // 256 x (1 map + 11 scalars + 1 list + 41 buckets) + 16 x (1 + 2 x (1 + 1800)) + counts/levels/slowest/header
        // + the count/level series (at most SERIES_VALUES plus a few per key) + the omitted names
        assertTrue(total < 250_000, "values: " + total);
        assertTrue(total > 70_000, "values: " + total);
        assertTrue(probe.complete());
    }

    @Test void worstCaseAtTheWindowClampStaysUnderTheLimit() {
        int windows = ProbeRecorder.MAX_WINDOWS;
        probe.begin(BASE, windows, 0);
        now = BASE;
        for (int i = 0; i < ProbeRecorder.MAX_KEYS; i++) run("section." + i, 10L + i);
        now = BASE + windows * SEC;
        var r = probe.finish("completed");
        assertTrue(reportValues(r) < 250_000);
        var any = map(map(map(r.get("series")).get("sections")).values().iterator().next());
        assertEquals(windows, list(any.get("calls")).size());
    }

    @Test void countsAndLevelsPastTheWindowClampKeepSamplingAndLandInTheLastWindow() {
        int seconds = ProbeRecorder.MAX_WINDOWS + 3;
        probe.begin(BASE, seconds, 0);
        int slots = 0;
        for (int s = 0; s < seconds; s++) {
            now = BASE + s * SEC + 10 * MS;
            if (probe.sampleDue()) {
                slots++;
                probe.level("players", s < ProbeRecorder.MAX_WINDOWS ? 2 : 9);
            }
            if (s >= ProbeRecorder.MAX_WINDOWS - 1) probe.count("late");
        }
        assertEquals(seconds, slots);                       // sampling goes on past the last window
        now = BASE + seconds * SEC;                           // the deadline
        assertFalse(probe.sampleDue());
        probe.count("late");
        probe.level("players", 100);
        var r = probe.finish("completed");
        var series = map(r.get("series"));
        var late = map(map(series.get("counts")).get("late"));
        assertEquals(List.of((long) ProbeRecorder.MAX_WINDOWS - 1), list(late.get("windows")));
        assertEquals(List.of(4L), list(late.get("n")));       // the last real window and the 3 past it, nothing at the deadline
        var players = map(map(series.get("levels")).get("players"));
        assertEquals(List.of(0L, (long) ProbeRecorder.MAX_WINDOWS - 1), list(players.get("from")));
        assertEquals(List.of((long) ProbeRecorder.MAX_WINDOWS - 2, (long) ProbeRecorder.MAX_WINDOWS - 1), list(players.get("to")));
        assertEquals(List.of(2L, 9L), list(players.get("values")));
        assertEquals((long) seconds, map(map(r.get("levels")).get("players")).get("samples"));
        assertTrue(reportValues(r) < 250_000);
        assertTrue(probe.complete());
    }

    @Test void startedZeroNanosStillUsesANonZeroToken() {
        probe.begin(0, 10, 0);
        now = 0;
        long t = probe.start();
        assertNotEquals(0, t);
        now = 50;
        probe.end("z", t);
        assertEquals(1L, section(probe.finish("completed"), "z").get("calls"));
    }

    @Test void manyDifferentSectionsAndAFinishThatSortsByTotalThenName() {
        begin(10);
        run("b", 5);
        run("a", 5);
        run("c", 9);
        var names = new ArrayList<Object>();
        for (Object o : list(probe.finish("completed").get("sections"))) names.add(map(o).get("name"));
        assertEquals(List.of("c", "a", "b"), names);
    }
}
