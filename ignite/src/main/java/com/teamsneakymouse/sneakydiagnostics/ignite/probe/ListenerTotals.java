package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every plugin listener call of one capture, aggregated per owner plugin x listener class x event class x priority x
 * async flag: calls, inclusive time, server-thread self time (nested listener calls subtracted), maximum and a log2
 * histogram, plus a per-second total/self series per owner plugin.
 * <p>
 * Hot path: the listener mixin caches each key's {@link Slot} on its {@code RegisteredListener} (a short {@link Node}
 * chain, one entry per event class and async flag that registration has received), so steady-state calls do no map
 * lookup and allocate nothing. Server-thread calls update plain fields (only the server thread writes them); calls on
 * other threads take the slot's lock and are kept apart ({@code otherThreadCalls/otherThreadNanos}, no self time, no
 * series). Only a cache miss (the first call of a key in a capture, or a chain evicted by more than {@value #MAX_CHAIN}
 * event classes) builds labels and takes the totals lock.
 */
public final class ListenerTotals {
    /** Distinct keys; more are counted in {@code overflow}. */
    static final int MAX_KEYS = 4096;
    /** Owner plugins with a per-second series; more are counted in {@code series.omittedOwners}. */
    static final int MAX_OWNERS = 256;
    /** Nesting depth of server-thread listener calls whose children are subtracted; deeper calls are counted. */
    static final int MAX_DEPTH = 64;
    /** The shared probe's log2 buckets: {@code floor(log2(nanos))}, 0 for 0..1 ns, the last bucket for longer calls. */
    static final int BUCKETS = 41;
    /** Event classes cached per registration before the chain starts over. */
    static final int MAX_CHAIN = 8;

    record Key(String owner, String listenerClass, String eventClass, String priority, boolean async) {}

    /** One key's totals ({@code key == null}: the overflow slot of a capture whose key table was full). */
    public static final class Slot {
        final ListenerTotals totals;
        final Key key;
        final Owner owner;
        /** Server thread only. */
        long calls, totalNanos, selfNanos, maxNanos;
        final long[] histogram;
        /** Other threads, guarded by this slot. */
        long otherCalls, otherNanos, otherMax;
        final long[] otherHistogram;

        Slot(ListenerTotals totals, Key key, Owner owner) {
            this.totals = totals;
            this.key = key;
            this.owner = owner;
            histogram = key == null ? null : new long[BUCKETS];
            otherHistogram = key == null ? null : new long[BUCKETS];
        }
    }

    /** An owner plugin's per-second server-thread time (inclusive and self), indexed by window. */
    static final class Owner {
        final String name;
        final long[] total, self;
        Owner(String name, int windows) {
            this.name = name;
            total = new long[windows];
            self = new long[windows];
        }
    }

    /** Per-registration cache entry: immutable, so a racing writer can only lose an entry (a later miss rebuilds it). */
    public static final class Node {
        final Slot slot;
        final Class<?> type;
        final boolean async;
        final Node next;
        final int length;
        Node(Slot slot, Class<?> type, boolean async, Node next) {
            this.slot = slot;
            this.type = type;
            this.async = async;
            this.next = next;
            this.length = next == null ? 1 : next.length + 1;
        }
    }

    private final long startedNanos;
    private final int windows;
    private final Map<Key, Slot> slots = new HashMap<>();
    private final Map<String, Owner> owners = new LinkedHashMap<>();
    private long omittedOwners;
    private boolean closed;
    /** Overflow totals: server thread plain, other threads under this object's lock. */
    private long overflowCalls, overflowNanos, overflowOtherCalls, overflowOtherNanos;

    ListenerTotals(long startedNanos, int seconds) {
        this.startedNanos = startedNanos;
        this.windows = Math.max(1, seconds);
    }

    /** The cached slot for this event class and async flag, or null (no cache, another capture's cache, or a miss). */
    public static Slot find(Node node, ListenerTotals totals, Class<?> type, boolean async) {
        if (node == null || totals == null || node.slot.totals != totals) return null;
        for (Node n = node; n != null; n = n.next) if (n.type == type && n.async == async) return n.slot;
        return null;
    }

    /** The registration's new cache after a miss: starts over for a new capture or a full chain. */
    public static Node push(Node node, Slot slot, Class<?> type, boolean async) {
        if (node == null || node.slot.totals != slot.totals || node.length >= MAX_CHAIN) return new Node(slot, type, async, null);
        return new Node(slot, type, async, node);
    }

    /** Miss path: labels are built here, once per key and capture. Null once the capture has been exported. */
    public synchronized Slot slot(String owner, String listenerClass, Class<?> type, String priority, boolean async) {
        if (closed) return null;
        Key key = new Key(SchedulerProbe.label(owner), SchedulerProbe.label(listenerClass), SchedulerProbe.label(type.getName()),
                priority, async);
        Slot slot = slots.get(key);
        if (slot != null) return slot;
        if (slots.size() >= MAX_KEYS) return new Slot(this, null, null);
        Owner series = owners.get(key.owner());
        if (series == null) {
            if (owners.size() < MAX_OWNERS) owners.put(key.owner(), series = new Owner(key.owner(), windows));
            else omittedOwners++;
        }
        slot = new Slot(this, key, series);
        slots.put(key, slot);
        return slot;
    }

    /** A completed server-thread call; {@code self} excludes nested listener calls. Server thread only, no allocation. */
    void server(Slot slot, long elapsed, long self, long endedNanos) {
        if (slot.key == null) {
            overflowCalls++;
            overflowNanos += elapsed;
            return;
        }
        slot.calls++;
        slot.totalNanos += elapsed;
        slot.selfNanos += self;
        if (elapsed > slot.maxNanos) slot.maxNanos = elapsed;
        slot.histogram[bucket(elapsed)]++;
        Owner owner = slot.owner;
        if (owner != null) {
            long offset = endedNanos - startedNanos;
            int window = offset <= 0 ? 0 : (int) Math.min(windows - 1, offset / 1_000_000_000L);
            owner.total[window] += elapsed;
            owner.self[window] += self;
        }
    }

    /** A completed call on another thread: counted apart, no self time and no series. */
    void other(Slot slot, long elapsed) {
        if (slot.key == null) {
            synchronized (this) {
                overflowOtherCalls++;
                overflowOtherNanos += elapsed;
            }
            return;
        }
        synchronized (slot) {
            slot.otherCalls++;
            slot.otherNanos += elapsed;
            if (elapsed > slot.otherMax) slot.otherMax = elapsed;
            slot.otherHistogram[bucket(elapsed)]++;
        }
    }

    /**
     * Detached JDK values; later calls are rejected. Server-thread fields are read on the stopping thread, which is the
     * server thread in production (the stop runs in a tick-end listener or a command).
     */
    synchronized Map<String, Object> export(long nowNanos, long nestingOverflow) {
        closed = true;
        List<Map<String, Object>> rows = new ArrayList<>(slots.size());
        List<Slot> ordered = new ArrayList<>(slots.values());
        ordered.sort(Comparator.comparingLong((Slot slot) -> slot.selfNanos).reversed()
                .thenComparing(Comparator.comparingLong((Slot slot) -> slot.totalNanos).reversed()));
        for (Slot slot : ordered) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("owner", slot.key.owner());
            row.put("listenerClass", slot.key.listenerClass());
            row.put("eventClass", slot.key.eventClass());
            row.put("priority", slot.key.priority());
            row.put("asyncEvent", slot.key.async());
            long otherCalls, otherNanos, otherMax;
            long[] otherHistogram;
            synchronized (slot) {
                otherCalls = slot.otherCalls;
                otherNanos = slot.otherNanos;
                otherMax = slot.otherMax;
                otherHistogram = slot.otherHistogram.clone();
            }
            row.put("calls", slot.calls + otherCalls);
            row.put("totalNanos", slot.totalNanos + otherNanos);
            row.put("selfNanos", slot.selfNanos);
            row.put("maxNanos", Math.max(slot.maxNanos, otherMax));
            row.put("otherThreadCalls", otherCalls);
            row.put("otherThreadNanos", otherNanos);
            List<Object> histogram = new ArrayList<>(BUCKETS);
            for (int k = 0; k < BUCKETS; k++) histogram.add(slot.histogram[k] + otherHistogram[k]);
            row.put("histogramLog2", histogram);
            rows.add(row);
        }
        int shown = (int) Math.max(1, Math.min(windows, (nowNanos - startedNanos) / 1_000_000_000L + 1));
        Map<String, Object> ownerSeries = new LinkedHashMap<>();
        for (Owner owner : owners.values()) {
            List<Object> total = new ArrayList<>(shown), self = new ArrayList<>(shown);
            boolean any = false;
            for (int i = 0; i < shown; i++) {
                total.add(owner.total[i]);
                self.add(owner.self[i]);
                any |= owner.total[i] != 0;
            }
            if (!any) continue;
            Map<String, Object> series = new LinkedHashMap<>();
            series.put("totalNanos", total);
            series.put("selfNanos", self);
            ownerSeries.put(owner.name, series);
        }
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("windowMillis", 1000L);
        series.put("owners", ownerSeries);
        series.put("omittedOwners", omittedOwners);
        series.put("maxOwners", (long) MAX_OWNERS);
        Map<String, Object> overflow = new LinkedHashMap<>();
        overflow.put("calls", overflowCalls + overflowOtherCalls);
        overflow.put("totalNanos", overflowNanos + overflowOtherNanos);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keys", (long) rows.size());
        result.put("maxKeys", (long) MAX_KEYS);
        result.put("overflow", overflow);
        result.put("nestingOverflow", nestingOverflow);
        result.put("maxDepth", (long) MAX_DEPTH);
        result.put("complete", overflowCalls + overflowOtherCalls == 0 && omittedOwners == 0 && nestingOverflow == 0);
        result.put("rows", List.copyOf(rows));
        result.put("series", series);
        return result;
    }

    /** The shared probe's bucket: {@code floor(log2(nanos))}, 0 for 0 and 1 ns, capped at the last bucket. */
    static int bucket(long nanos) {
        return nanos < 2 ? 0 : Math.min(BUCKETS - 1, 63 - Long.numberOfLeadingZeros(nanos));
    }
}
