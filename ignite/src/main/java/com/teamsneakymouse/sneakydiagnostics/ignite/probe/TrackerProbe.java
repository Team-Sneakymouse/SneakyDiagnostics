package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Entity-tracker send time ({@code ServerEntity.sendChanges}) by entity type and owner hint, recorded on the server
 * thread only. Idle cost per call is one volatile read. While recording, a call costs two clock reads and a few plain
 * array updates; the entity's group is resolved once per entity per capture (see {@link #group}), and nothing is
 * allocated per call. Calls on other threads are counted, not timed.
 */
public final class TrackerProbe {
    static final int MAX_GROUPS = 512, LABEL_LIMIT = 192, HISTOGRAM_BUCKETS = 41, SERIES_GROUPS = 16;
    /** Group index for entities that arrive after {@link #MAX_GROUPS} groups exist. */
    public static final int OVERFLOW = 0;
    private static volatile Session current;
    private static int generations;

    private TrackerProbe() {}

    /** The session to record into, or null: no capture, capture stopped or expired, or not the server thread. */
    public static Session recording() {
        Session session = current;
        if (session == null || session.closed) return null;
        if (Thread.currentThread() != session.serverThread) { session.offThreadCalls++; return null; }
        return session;
    }

    public static synchronized void start(String id, long startedNanos, int seconds) {
        start(new Session(id, startedNanos, seconds, System::nanoTime, Thread.currentThread(), ++generations));
    }
    static synchronized void start(Session session) {
        if (current != null) throw new IllegalStateException("Tracker probe already has a capture; stop it first");
        current = session;
    }
    public static synchronized Map<String, Object> stop(String id) {
        Session session = current;
        if (session == null || !session.id.equals(id)) throw new IllegalStateException("Tracker capture ID does not match");
        current = null;
        return session.close();
    }
    public static String status() {
        Session session = current;
        return session == null ? "available, idle" : session.closed ? "capture stopped; awaiting report" : "recording " + session.id;
    }

    /** Resolves an entity's group label once per entity per capture. */
    @FunctionalInterface
    public interface Labeller { String[] typeAndOwner(); }

    public static final class Session {
        final String id;
        final long startedNanos, deadline;
        final int windows;
        final Thread serverThread;
        /** Distinguishes captures, so an entity's cached group from an earlier capture is never reused. */
        public final int generation;
        final LongSupplier clock;
        volatile boolean closed;
        // Server-thread state only (offThreadCalls is a racy, approximate counter written by other threads).
        long offThreadCalls, labelErrors, overflowEntities, lateCalls;
        private final Map<String, Integer> index = new HashMap<>();
        private final List<String[]> labels = new ArrayList<>();
        private int groups;
        private final long[] entities = new long[MAX_GROUPS + 1], calls = new long[MAX_GROUPS + 1], nanos = new long[MAX_GROUPS + 1],
                maxNanos = new long[MAX_GROUPS + 1], metadataCalls = new long[MAX_GROUPS + 1], metadataNanos = new long[MAX_GROUPS + 1];
        private final long[][] histogram = new long[MAX_GROUPS + 1][];
        private final long[][] series = new long[MAX_GROUPS + 1][];
        private final long[] windowCalls, windowNanos;

        Session(String id, long startedNanos, int seconds, LongSupplier clock, Thread serverThread, int generation) {
            if (id == null || id.isBlank() || seconds < 1 || seconds > 1800) throw new IllegalArgumentException("Invalid tracker capture");
            this.id = id;
            this.startedNanos = startedNanos;
            this.deadline = startedNanos + seconds * 1_000_000_000L;
            this.windows = seconds + 1;
            this.clock = clock;
            this.serverThread = serverThread;
            this.generation = generation;
            windowCalls = new long[windows];
            windowNanos = new long[windows];
            labels.add(new String[]{"(overflow)", "(overflow)"});
            groups = 1;
        }

        public long now() { return clock.getAsLong(); }

        /** The group for an entity seen for the first time in this capture. */
        public int group(Labeller labeller) {
            String type, owner;
            try {
                String[] label = labeller.typeAndOwner();
                type = limit(label[0]);
                owner = limit(label[1]);
            } catch (RuntimeException | LinkageError e) {
                labelErrors++;
                type = "(unknown)";
                owner = "(unknown)";
            }
            String key = type + '\u0000' + owner;
            Integer slot = index.get(key);
            if (slot == null) {
                if (groups > MAX_GROUPS) { overflowEntities++; entities[OVERFLOW]++; return OVERFLOW; }
                slot = groups++;
                index.put(key, slot);
                labels.add(new String[]{type, owner});
            }
            entities[slot]++;
            return slot;
        }

        /** One {@code sendChanges} call of an entity in {@code group}, from {@code started} to {@code ended}. */
        public void send(int group, long started, long ended) {
            if (closed) return;
            if (ended - deadline >= 0) { lateCalls++; return; }
            long elapsed = Math.max(0, ended - started);
            calls[group]++;
            nanos[group] += elapsed;
            if (elapsed > maxNanos[group]) maxNanos[group] = elapsed;
            long[] buckets = histogram[group];
            if (buckets == null) buckets = histogram[group] = new long[HISTOGRAM_BUCKETS];
            buckets[bucket(elapsed)]++;
            int window = window(ended);
            if (window >= 0) {
                windowCalls[window]++;
                windowNanos[window] += elapsed;
                long[] own = series[group];
                if (own == null) own = series[group] = new long[windows];
                own[window] += elapsed;
            }
        }

        /** The metadata part ({@code sendDirtyEntityData}) of a send, nested inside it. */
        public void metadata(int group, long started, long ended) {
            if (closed || ended - deadline >= 0) return;
            metadataCalls[group]++;
            metadataNanos[group] += Math.max(0, ended - started);
        }

        private int window(long nanosTime) {
            long offset = nanosTime - startedNanos;
            if (offset < 0) return -1;
            long window = offset / 1_000_000_000L;
            return window < windows ? (int) window : -1;
        }

        Map<String, Object> close() {
            closed = true;
            long observedMillis = Math.max(0, Math.min(now(), deadline) - startedNanos) / 1_000_000L;
            var order = new ArrayList<Integer>();
            for (int g = 0; g < groups; g++) if (calls[g] > 0 || entities[g] > 0) order.add(g);
            order.sort(Comparator.comparingLong((Integer g) -> nanos[g]).reversed().thenComparing(g -> labels.get(g)[0]).thenComparing(g -> labels.get(g)[1]));
            long totalCalls = 0, totalNanos = 0, totalMetadataCalls = 0, totalMetadataNanos = 0, totalEntities = 0;
            var rows = new ArrayList<Map<String, Object>>();
            for (int g : order) {
                totalCalls += calls[g];
                totalNanos += nanos[g];
                totalMetadataCalls += metadataCalls[g];
                totalMetadataNanos += metadataNanos[g];
                totalEntities += entities[g];
                var row = new LinkedHashMap<String, Object>();
                row.put("entityType", labels.get(g)[0]);
                row.put("owner", labels.get(g)[1]);
                row.put("entities", entities[g]);
                row.put("calls", calls[g]);
                row.put("totalNanos", nanos[g]);
                row.put("metadataCalls", metadataCalls[g]);
                row.put("metadataNanos", metadataNanos[g]);
                row.put("maxNanos", maxNanos[g]);
                row.put("histogramLog2", list(histogram[g] == null ? new long[HISTOGRAM_BUCKETS] : histogram[g]));
                rows.add(row);
            }
            var seriesGroups = new ArrayList<Map<String, Object>>();
            for (int g : order.subList(0, Math.min(SERIES_GROUPS, order.size()))) {
                if (series[g] == null) continue;
                var row = new LinkedHashMap<String, Object>();
                row.put("entityType", labels.get(g)[0]);
                row.put("owner", labels.get(g)[1]);
                row.put("nanos", list(series[g]));
                seriesGroups.add(row);
            }
            var seriesMap = new LinkedHashMap<String, Object>();
            seriesMap.put("windowMillis", 1000L);
            seriesMap.put("calls", list(windowCalls));
            seriesMap.put("nanos", list(windowNanos));
            seriesMap.put("groups", seriesGroups);

            var tracker = new LinkedHashMap<String, Object>();
            tracker.put("observedMillis", observedMillis);
            tracker.put("calls", totalCalls);
            tracker.put("totalNanos", totalNanos);
            tracker.put("metadataCalls", totalMetadataCalls);
            tracker.put("metadataNanos", totalMetadataNanos);
            tracker.put("entities", totalEntities);
            tracker.put("offThreadCalls", offThreadCalls);
            tracker.put("lateCalls", lateCalls);
            tracker.put("labelErrors", labelErrors);
            tracker.put("maxGroups", (long) MAX_GROUPS);
            tracker.put("overflowEntities", overflowEntities);
            tracker.put("complete", overflowEntities == 0 && labelErrors == 0 && offThreadCalls == 0);
            tracker.put("groups", rows);
            tracker.put("series", seriesMap);
            var result = new LinkedHashMap<String, Object>();
            result.put("captureId", id);
            result.put("tracker", tracker);
            return result;
        }
    }

    static int bucket(long nanos) {
        return nanos <= 1 ? 0 : Math.min(HISTOGRAM_BUCKETS - 1, 63 - Long.numberOfLeadingZeros(nanos));
    }

    private static String limit(String label) {
        if (label == null || label.isEmpty()) return "(none)";
        return label.length() <= LABEL_LIMIT ? label : label.substring(0, LABEL_LIMIT);
    }

    private static List<Long> list(long[] values) {
        return new ArrayList<>(Arrays.stream(values).boxed().toList());
    }
}
