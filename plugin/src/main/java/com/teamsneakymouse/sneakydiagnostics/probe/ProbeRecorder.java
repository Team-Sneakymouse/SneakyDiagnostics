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
 *   <li>slowest: the 10 longest calls across all sections; counts; levels (sampled when {@link #sampleDue()});</li>
 *   <li>a per-second series for counts (windows with a non-zero count) and levels (runs of windows with the same last
 *   sample), cheapest keys first within {@value #SERIES_VALUES} values.</li>
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
 * windows = capture seconds, which the commands cap at 1800), a 41-long histogram and the stat fields; per count and per
 * level one such array (a level also has a bitset of its sampled windows). Worst case (256 sections, counts and levels)
 * 4 x 256 x 1800 x 8 B = 14.7 MiB (29.5 MiB at the hard clamp of 3600 windows), dropped at {@link #finish} and on
 * {@link #begin}. Only the 16 largest sections and at most {@value #SERIES_VALUES} count/level series values are exported,
 * so the report stays well under the provider value limit (see the tests).
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
    /** Values (2 per count window, 3 per level run) the count and level series may use in total. */
    public static final int SERIES_VALUES = 50_000;
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
    private long startedNanos, deadline, lastSampleWindow, omitted, nestingErrors;
    private int startTick, windows;
    private String reason;
    private Map<String, Object> result;

    private static final class Count {
        long n;
        final long[] window;

        Count(int windows) {
            window = new long[windows];
        }
    }

    private static final class Level {
        long samples, sum, min, max, last;
        final long[] window;               // the window's last sample, valid where its bit in sampled is set
        final long[] sampled;

        Level(int windows) {
            window = new long[windows];
            sampled = new long[(windows + 63) >>> 6];
        }

        boolean sampled(int w) {
            return (sampled[w >>> 6] & (1L << w)) != 0;
        }
    }

    private final Map<String, Section> sections = new HashMap<>();
    private final Map<String, Count> counts = new HashMap<>();
    private final Map<String, Level> levels = new HashMap<>();

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
        lastSampleWindow = -1;
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
        int w = window(now);
        s.windowCalls[w]++;
        s.windowNanos[w] += d;
        if (slowCount < SLOWEST || d > slowNanos[slowMin]) slow(s, d, atMillis);
    }

    /** Something happened n times. */
    public void count(String key, long n) {
        if (!on) return;
        long now = clock.getAsLong();
        if (now - deadline >= 0) {
            on = false;
            return;
        }
        Count c = counts.get(key);
        if (c == null) {
            if (!roomFor(counts, key)) return;
            c = new Count(windows);
            counts.put(key, c);
        }
        c.n += n;
        c.window[window(now)] += n;
    }

    public void count(String key) {
        count(key, 1);
    }

    /**
     * Whether it's time to sample the levels again: at the first call in each one-second series window (so right after
     * begin), which gives every window one sample as long as the server ticks in it.
     */
    public boolean sampleDue() {
        if (!on) return false;
        long now = clock.getAsLong();
        if (now - deadline >= 0) {
            on = false;
            return false;
        }
        long w = Math.max(0, now - startedNanos) / WINDOW_NANOS;     // not clamped: sampling goes on past the last window
        if (w <= lastSampleWindow) return false;
        lastSampleWindow = w;
        return true;
    }

    /** How much of something there is now (call it when {@link #sampleDue()}). */
    public void level(String key, long value) {
        if (!on) return;
        long now = clock.getAsLong();
        if (now - deadline >= 0) {
            on = false;
            return;
        }
        Level l = levels.get(key);
        if (l == null) {
            if (!roomFor(levels, key)) return;
            l = new Level(windows);
            levels.put(key, l);
        }
        if (l.samples == 0 || value < l.min) l.min = value;
        if (l.samples == 0 || value > l.max) l.max = value;
        l.samples++;
        l.sum += value;
        l.last = value;
        int w = window(now);
        l.window[w] = value;
        l.sampled[w >>> 6] |= 1L << w;
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
        for (String key : sortedKeys(counts)) cs.put(key, counts.get(key).n);
        out.put("counts", cs);

        Map<String, Object> ls = new LinkedHashMap<>();
        for (String key : sortedKeys(levels)) {
            Level l = levels.get(key);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("samples", l.samples);
            m.put("mean", l.samples == 0 ? 0.0 : (double) l.sum / l.samples);
            m.put("min", l.min);
            m.put("max", l.max);
            m.put("last", l.last);
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
        keySeries(series, shown);
        out.put("series", series);

        out.put("nestingErrors", nestingErrors);
        out.put("omittedKeys", omitted);
        out.put("maxKeys", (long) MAX_KEYS);
        result = out;
        sections.clear();                  // the series arrays are the bulk of the memory
        counts.clear();
        levels.clear();
        java.util.Arrays.fill(slowSection, null);
        slowCount = depth = 0;
        return out;
    }

    /**
     * The count and level series. {@code counts.<key>} lists the windows with a non-zero count ({@code windows}, {@code n}).
     * {@code levels.<key>} lists runs of consecutive sampled windows with the same last sample ({@code from}, {@code to}
     * inclusive, {@code values}); a window in no run had no sample. Keys are taken cheapest first (rare markers before
     * busy counters) while their values fit in {@link #SERIES_VALUES}; the rest are named in {@code omitted}.
     */
    private void keySeries(Map<String, Object> series, int shown) {
        record Candidate(String key, boolean level, List<Object> a, List<Object> b, List<Object> c, int cost) {}
        List<Candidate> all = new ArrayList<>(counts.size() + levels.size());
        for (Map.Entry<String, Count> e : counts.entrySet()) {
            long[] win = e.getValue().window;
            List<Object> ws = new ArrayList<>(), ns = new ArrayList<>();
            for (int i = 0; i < shown; i++) {
                if (win[i] == 0) continue;
                ws.add((long) i);
                ns.add(win[i]);
            }
            all.add(new Candidate(e.getKey(), false, ws, ns, null, 2 * ws.size()));
        }
        for (Map.Entry<String, Level> e : levels.entrySet()) {
            Level l = e.getValue();
            List<Object> from = new ArrayList<>(), to = new ArrayList<>(), values = new ArrayList<>();
            long runTo = -2, runValue = 0;
            for (int i = 0; i < shown; i++) {
                if (!l.sampled(i)) continue;
                long v = l.window[i];
                if (runTo == i - 1 && runValue == v) {
                    to.set(to.size() - 1, (long) i);
                } else {
                    from.add((long) i);
                    to.add((long) i);
                    values.add(v);
                    runValue = v;
                }
                runTo = i;
            }
            all.add(new Candidate(e.getKey(), true, from, to, values, 3 * from.size()));
        }
        all.sort((x, y) -> x.cost != y.cost ? Integer.compare(x.cost, y.cost)
                : x.level != y.level ? Boolean.compare(x.level, y.level) : x.key.compareTo(y.key));
        Map<String, Object> cs = new java.util.TreeMap<>(), ls = new java.util.TreeMap<>();
        List<String> omittedCounts = new ArrayList<>(), omittedLevels = new ArrayList<>();
        int budget = SERIES_VALUES;
        for (Candidate k : all) {
            if (k.cost > budget) {
                (k.level ? omittedLevels : omittedCounts).add(k.key);
                continue;
            }
            budget -= k.cost;
            Map<String, Object> m = new LinkedHashMap<>();
            if (k.level) {
                m.put("from", k.a);
                m.put("to", k.b);
                m.put("values", k.c);
                ls.put(k.key, m);
            } else {
                m.put("windows", k.a);
                m.put("n", k.b);
                cs.put(k.key, m);
            }
        }
        java.util.Collections.sort(omittedCounts);
        java.util.Collections.sort(omittedLevels);
        series.put("counts", new LinkedHashMap<>(cs));
        series.put("levels", new LinkedHashMap<>(ls));
        Map<String, Object> left = new LinkedHashMap<>();
        left.put("counts", new ArrayList<Object>(omittedCounts));
        left.put("levels", new ArrayList<Object>(omittedLevels));
        series.put("omitted", left);
    }

    /** The series window of a clock reading: its capture offset in whole windows, clamped to the windows there are. */
    private int window(long now) {
        return (int) Math.min(Math.max((now - startedNanos) / WINDOW_NANOS, 0), windows - 1);
    }

    private Section section(String name) {
        Section s = sections.get(name);
        if (s != null) return s;
        if (!roomFor(sections, name)) return null;
        s = new Section(name, windows);
        sections.put(name, s);
        return s;
    }

    /** Whether a new key may join the map (a valid name, under the cap); if not, it counts as omitted. */
    private boolean roomFor(Map<String, ?> map, String key) {
        if (validName(key) && map.size() < MAX_KEYS) return true;
        omitted++;
        return false;
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

    private static List<String> sortedKeys(Map<String, ?> map) {
        List<String> keys = new ArrayList<>(map.keySet());
        java.util.Collections.sort(keys);
        return keys;
    }
}
