package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Supplier;

/**
 * The longest calls at or above a threshold, at most {@code capacity} of them. When full, a longer call replaces the
 * shortest kept one (ties keep the earlier call). Every call over the threshold is counted, kept or not, so the report
 * says exactly how many were left out. Thread safe; only calls over the threshold reach the lock. {@link #export} closes
 * it: later calls are rejected, so a call is either in the exported list (or its eviction count) or reported by its
 * caller as unfinished, never lost between the two.
 */
final class SlowRecords {
    private record Entry(long nanos, long sequence, Map<String, Object> fields) {}

    private final long thresholdNanos;
    private final int capacity;
    private final PriorityQueue<Entry> kept = new PriorityQueue<>(
            Comparator.comparingLong(Entry::nanos).thenComparing(Comparator.comparingLong(Entry::sequence).reversed()));
    private long observed, sequence;
    private boolean closed;

    SlowRecords(long thresholdNanos, int capacity) {
        this.thresholdNanos = thresholdNanos;
        this.capacity = capacity;
    }

    boolean slow(long nanos) { return nanos >= thresholdNanos; }

    /** Counts a slow call and keeps it if it is among the longest; {@code fields} is built only when it is kept. */
    synchronized void add(long nanos, Supplier<Map<String, Object>> fields) { add(nanos, fields, null); }

    /**
     * As {@link #add(long, Supplier)}; {@code accepted} runs under the same lock when the call is counted, so it is
     * atomic with respect to {@link #export}. Returns false (and runs nothing) once exported.
     */
    synchronized boolean add(long nanos, Supplier<Map<String, Object>> fields, Runnable accepted) {
        if (closed || !slow(nanos)) return false;
        observed++;
        sequence++;
        if (accepted != null) accepted.run();
        if (kept.size() >= capacity) {
            if (capacity == 0 || nanos <= kept.peek().nanos()) return true;
            kept.poll();
        }
        kept.add(new Entry(nanos, sequence, fields.get()));
        return true;
    }

    /** Detached JDK values: the kept calls in the order they started, and how many over the threshold were left out. */
    synchronized Map<String, Object> export() {
        closed = true;
        List<Entry> ordered = new ArrayList<>(kept);
        ordered.sort(Comparator.comparingLong((Entry entry) -> ((Number) entry.fields().get("startOffsetMicros")).longValue())
                .thenComparingLong(Entry::sequence));
        List<Map<String, Object>> records = new ArrayList<>(ordered.size());
        for (Entry entry : ordered) records.add(entry.fields());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("thresholdNanos", thresholdNanos);
        result.put("capacity", capacity);
        result.put("observed", observed);
        result.put("retained", (long) records.size());
        result.put("evicted", observed - records.size());
        result.put("complete", observed == records.size());
        result.put("records", List.copyOf(records));
        return result;
    }
}
