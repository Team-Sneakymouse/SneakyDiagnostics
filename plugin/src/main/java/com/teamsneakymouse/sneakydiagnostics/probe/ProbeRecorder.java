package com.teamsneakymouse.sneakydiagnostics.probe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * A consumer plugin's own measurements, producing the {@code sneakydiagnostics.probe/1} measurement map of the v2 report
 * schema. Pure Java (no Bukkit, no JSON library); the clock and tick source are injected. <b>Server thread only</b>: no
 * synchronization, and begin/finish must run on the same thread as the instrumented code.
 * <ul>
 *   <li>sections: calls, inclusive {@code totalNanos}, exclusive {@code selfNanos} (via a strictly nested stack), max with
 *   when it happened, a log2 histogram with p50/p95/p99, and a per-second series (the 16 largest sections are reported);</li>
 *   <li>slowest: the 10 longest calls across all sections; counts; levels (sampled when {@link #sampleDue()}).</li>
 * </ul>
 * <b>Off costs nothing:</b> while no capture runs (or its deadline passed) {@code start} returns 0 after a boolean check
 * and every other method returns after the same check: no clock read, no map access, no allocation. Callers must keep
 * their own work off too, e.g. {@code long t = probe.start(); ... if (t != 0) probe.end("kind." + kind, t);} so no string is
 * built for a probe that is off. Once a name has been seen, recording does not allocate (only the first sight of a name does).
 * <p>
 * Timing: a section's time is attributed to the window and tick of its <em>end</em>. A section still running at the deadline
 * is dropped (nothing is recorded at or after it). Names are fixed labels, at most 192 characters.
 * <p>
 * Memory bound: per section 2 series arrays of at most {@value #MAX_WINDOWS} longs (allocated on the section's first call;
 * windows = capture seconds, which the commands cap at 1800), a 41-long histogram and the stat fields. Worst case 256
 * sections x 2 x 1800 x 8 B = 7.4 MiB (14.7 MiB at the hard clamp of 3600 windows), dropped at {@link #finish} and on
 * {@link #begin}. Only the 16 largest sections are exported, so the exported value count stays near 72,000 (see the tests).
 */
public final class ProbeRecorder {

    public static final int MAX_KEYS = 256;
    public static final int MAX_DEPTH = 64;
    public static final int HISTOGRAM_BUCKETS = 41;
    public static final int SLOWEST = 10;
    public static final int SERIES_SECTIONS = 16;
    public static final int WINDOW_MILLIS = 1000;
    public static final int MAX_WINDOWS = 3600;
    public static final int MAX_NAME = 192;
    public static final String SCHEMA = "sneakydiagnostics.probe/1";
    private static final long SECOND = 1_000_000_000L;
    private static final long WINDOW_NANOS = WINDOW_MILLIS * 1_000_000L;

    private static final class Section {
        final String name;
        long calls, total, self, max, maxAtMillis;
        int maxAtTick;
        final long[] histogram = new long[HISTOGRAM_BUCKETS];
        final long[] windowCalls, windowNanos;

        Section(String name, int windows) {
            this.name = name;
            windowCalls = new long[windows];
            windowNanos = new long[windows];
        }
    }

    private final LongSupplier clock;
    private final IntSupplier tick;

    private boolean on, begun;
    private long startedNanos, deadline, lastSample, omitted, nestingErrors;
    private int startTick, windows;
    private String reason;
    private Map<String, Object> result;

    private final Map<String, Section> sections = new HashMap<>();
    private final Map<String, long[]> counts = new HashMap<>();     // n
    private final Map<String, long[]> levels = new HashMap<>();     // samples, sum, min, max, last

    // Nesting stack (preallocated): start nanos and time spent in recorded children, per open frame.
    private final long[] frameStart = new long[MAX_DEPTH];
    private final long[] frameChild = new long[MAX_DEPTH];
    private int depth;

    // Slowest calls (unsorted, bounded); slowMin is the index of the smallest once full.
    private final Section[] slowSection = new Section[SLOWEST];
    private final long[] slowNanos = new long[SLOWEST], slowAtMillis = new long[SLOWEST];
    private final int[] slowAtTick = new int[SLOWEST];
    private int slowCount, slowMin;

    public ProbeRecorder(LongSupplier nanoClock, IntSupplier tickSource) {
        this.clock = nanoClock;
        this.tick = tickSource;
    }

    /** A capture starts: everything from before is dropped. {@code startedNanos} is the shared capture origin. */
    public void begin(long startedNanos, int seconds, int startTick) {
        sections.clear();
        counts.clear();
        levels.clear();
        java.util.Arrays.fill(slowSection, null);
        slowCount = slowMin = depth = 0;
        omitted = nestingErrors = 0;
        result = null;
        reason = null;
        this.startedNanos = startedNanos;
        this.startTick = startTick;
        this.windows = Math.max(1, Math.min(seconds, MAX_WINDOWS));
        deadline = startedNanos + seconds * SECOND;
        lastSample = startedNanos - SECOND;
        on = seconds > 0;
        begun = true;
    }

    /** Whether a capture is running and its time isn't up. */
    public boolean on() {
        if (!on) return false;
        if (clock.getAsLong() - deadline >= 0) {
            on = false;
            return false;
        }
        return true;
    }

    /** The start of a timed section: pass it to {@link #end}. 0 (nothing timed) unless a capture is running. */
    public long start() {
        if (!on) return 0;
        long now = clock.getAsLong();
        if (now - deadline >= 0) {
            on = false;
            return 0;
        }
        if (depth == MAX_DEPTH) {          // too deep: counted, not timed (the work lands in the parent's self time)
            nestingErrors++;
            return 0;
        }
        if (now == 0) now = 1;             // 0 is the "off" token
        frameStart[depth] = now;
        frameChild[depth++] = 0;
        return now;
    }

    /**
     * The end of the section started by {@link #start()}. Strictly LIFO; an end whose frame isn't on top unwinds the frames
     * above it (each counted in {@code nestingErrors}); an unknown token is counted and changes nothing.
     */
    public void end(String section, long token) {
        if (token == 0 || !on) return;
        long now = clock.getAsLong();
        if (now - deadline >= 0) {
            on = false;
            return;
        }
        if (token - startedNanos < 0) return;   // from before this capture
        int i = depth - 1;
        while (i >= 0 && frameStart[i] != token) i--;
        if (i < 0) {
            nestingErrors++;
            return;
        }
        if (i != depth - 1) {              // frames above i never ended: drop them, their time is i's child time
            nestingErrors += depth - 1 - i;
            frameChild[i] += now - frameStart[i + 1];
        }
        depth = i;
        long d = Math.max(0, now - token);
        long self = Math.max(0, d - frameChild[i]);
        Section s = section(section);
        if (s == null) return;
        if (i > 0) frameChild[i - 1] += d;
        long offset = now - startedNanos;
        long atMillis = offset / 1_000_000L;
        if (s.calls == 0 || d > s.max) {
            s.max = d;
            s.maxAtMillis = atMillis;
            s.maxAtTick = tick.getAsInt();
        }
        s.calls++;
        s.total += d;
        s.self += self;
        s.histogram[bucket(d)]++;
        int w = (int) Math.min(Math.max(offset / WINDOW_NANOS, 0), windows - 1);
        s.windowCalls[w]++;
        s.windowNanos[w] += d;
        if (slowCount < SLOWEST || d > slowNanos[slowMin]) slow(s, d, atMillis);
    }

    /** Something happened n times. */
    public void count(String key, long n) {
        if (!on()) return;
        long[] c = slot(counts, key, 1);
        if (c != null) c[0] += n;
    }

    public void count(String key) {
        count(key, 1);
    }

    /** Whether it's time to sample the levels again (about once a second while a capture runs; due right after begin). */
    public boolean sampleDue() {
        if (!on()) return false;
        long now = clock.getAsLong();
        if (now - lastSample < SECOND) return false;
        lastSample = now;
        return true;
    }

    /** How much of something there is now (call it when {@link #sampleDue()}). */
    public void level(String key, long value) {
        if (!on()) return;
        long[] l = slot(levels, key, 5);
        if (l == null) return;
        if (l[0] == 0 || value < l[2]) l[2] = value;
        if (l[0] == 0 || value > l[3]) l[3] = value;
        l[0]++;
        l[1] += value;
        l[4] = value;
    }

    /** Whether everything fit: nothing was omitted for the key cap. */
    public boolean complete() {
        return omitted == 0;
    }

    /** The tick the capture began at (for the caller's use; reported ticks are the absolute tick numbers). */
    public int startTick() {
        return startTick;
    }

    /** Why the capture ended, as given to the first {@link #finish}. */
    public String reason() {
        return reason;
    }

    /**
     * The capture's over: the {@code sneakydiagnostics.probe/1} map (only string-keyed maps, lists, longs, doubles, strings),
     * and it stops. Repeated calls return the same map. The reason is kept ({@link #reason()}), not part of the map: the
     * collector's file header carries it.
     */
    public Map<String, Object> finish(String reason) {
        if (result != null) return result;
        this.reason = reason;
        long end = on ? clock.getAsLong() : deadline;
        if (on) {
            on = false;
            if (end - deadline > 0) end = deadline;
        }
        long observed = begun ? Math.max(0, end - startedNanos) : 0;
        int shown = (int) Math.min(windows, observed / WINDOW_NANOS + 1);

        List<Section> ordered = new ArrayList<>(sections.values());
        ordered.sort((a, b) -> a.total != b.total ? Long.compare(b.total, a.total) : a.name.compareTo(b.name));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", SCHEMA);
        out.put("observedMillis", observed / 1_000_000L);
        List<Object> sectionList = new ArrayList<>();
        for (Section s : ordered) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name);
            m.put("calls", s.calls);
            m.put("totalNanos", s.total);
            m.put("selfNanos", s.self);
            m.put("meanNanos", s.calls == 0 ? 0L : s.total / s.calls);
            m.put("maxNanos", s.max);
            m.put("maxAtMillis", s.maxAtMillis);
            m.put("maxAtTick", (long) s.maxAtTick);
            m.put("p50Nanos", percentile(s, 50));
            m.put("p95Nanos", percentile(s, 95));
            m.put("p99Nanos", percentile(s, 99));
            List<Object> hist = new ArrayList<>(HISTOGRAM_BUCKETS);
            for (long h : s.histogram) hist.add(h);
            m.put("histogramLog2", hist);
            sectionList.add(m);
        }
        out.put("sections", sectionList);

        Integer[] order = new Integer[slowCount];
        for (int i = 0; i < slowCount; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> slowNanos[a] != slowNanos[b] ? Long.compare(slowNanos[b], slowNanos[a])
                : Long.compare(slowAtMillis[a], slowAtMillis[b]));
        List<Object> slowest = new ArrayList<>();
        for (int i : order) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("section", slowSection[i].name);
            m.put("nanos", slowNanos[i]);
            m.put("atMillis", slowAtMillis[i]);
            m.put("atTick", (long) slowAtTick[i]);
            slowest.add(m);
        }
        out.put("slowest", slowest);

        Map<String, Object> cs = new LinkedHashMap<>();
        for (String key : sortedKeys(counts)) cs.put(key, counts.get(key)[0]);
        out.put("counts", cs);

        Map<String, Object> ls = new LinkedHashMap<>();
        for (String key : sortedKeys(levels)) {
            long[] l = levels.get(key);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("samples", l[0]);
            m.put("mean", l[0] == 0 ? 0.0 : (double) l[1] / l[0]);
            m.put("min", l[2]);
            m.put("max", l[3]);
            m.put("last", l[4]);
            ls.put(key, m);
        }
        out.put("levels", ls);

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("windowMillis", (long) WINDOW_MILLIS);
        Map<String, Object> seriesSections = new LinkedHashMap<>();
        for (Section s : ordered.subList(0, Math.min(SERIES_SECTIONS, ordered.size()))) {
            List<Object> calls = new ArrayList<>(shown), nanos = new ArrayList<>(shown);
            for (int i = 0; i < shown; i++) {
                calls.add(s.windowCalls[i]);
                nanos.add(s.windowNanos[i]);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("calls", calls);
            m.put("nanos", nanos);
            seriesSections.put(s.name, m);
        }
        series.put("sections", seriesSections);
        out.put("series", series);

        out.put("nestingErrors", nestingErrors);
        out.put("omittedKeys", omitted);
        out.put("maxKeys", (long) MAX_KEYS);
        result = out;
        sections.clear();                  // the series arrays are the bulk of the memory
        java.util.Arrays.fill(slowSection, null);
        slowCount = depth = 0;
        return out;
    }

    private Section section(String name) {
        Section s = sections.get(name);
        if (s != null) return s;
        if (!validName(name) || sections.size() >= MAX_KEYS) {
            omitted++;
            return null;
        }
        s = new Section(name, windows);
        sections.put(name, s);
        return s;
    }

    private long[] slot(Map<String, long[]> map, String key, int size) {
        long[] v = map.get(key);
        if (v != null) return v;
        if (!validName(key) || map.size() >= MAX_KEYS) {
            omitted++;
            return null;
        }
        v = new long[size];
        map.put(key, v);
        return v;
    }

    private static boolean validName(String name) {
        return name != null && name.length() <= MAX_NAME;
    }

    private void slow(Section s, long d, long atMillis) {
        int i = slowCount < SLOWEST ? slowCount++ : slowMin;
        slowSection[i] = s;
        slowNanos[i] = d;
        slowAtMillis[i] = atMillis;
        slowAtTick[i] = tick.getAsInt();
        if (slowCount == SLOWEST) {
            slowMin = 0;
            for (int k = 1; k < SLOWEST; k++) if (slowNanos[k] < slowNanos[slowMin]) slowMin = k;
        }
    }

    /** floor(log2(nanos)), 0 for nanos < 2, at most 40. */
    static int bucket(long nanos) {
        return nanos < 2 ? 0 : Math.min(HISTOGRAM_BUCKETS - 1, 63 - Long.numberOfLeadingZeros(nanos));
    }

    /** The upper bound of the histogram bucket holding the call at rank ceil(calls * percent / 100). */
    private static long percentile(Section s, int percent) {
        long rank = Math.max(1, (s.calls * percent + 99) / 100), seen = 0;
        for (int k = 0; k < HISTOGRAM_BUCKETS; k++) {
            seen += s.histogram[k];
            if (seen >= rank) return 1L << (k + 1);
        }
        return 1L << HISTOGRAM_BUCKETS;
    }

    private static List<String> sortedKeys(Map<String, long[]> map) {
        List<String> keys = new ArrayList<>(map.keySet());
        java.util.Collections.sort(keys);
        return keys;
    }
}
