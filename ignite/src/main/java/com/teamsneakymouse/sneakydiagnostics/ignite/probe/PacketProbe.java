package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Outbound packets ({@code Connection.send}) by packet class and send cause, tracker causes by entity type and owner,
 * and inbound packets ({@code PacketProcessor$ListenerAndPacket.handle}) by packet class, for {@code packets-1.json}.
 *
 * <p>Hot path: a hook reads {@link #current()} (idle: one volatile read and a null check) or {@link #active()} (the
 * same plus a thread comparison). While recording, server-thread calls update plain arrays of the session: no lock, no
 * ThreadLocal, no allocation per call. Packet classes map to int slots through a {@link ClassValue}, once per class per
 * JVM. Sends on other threads take {@link Session#offThreadLock} and are reported apart. Lazily allocated per-class
 * arrays (histogram, series) are allocated once per class and capture.
 *
 * <p>Causes: hooks around the call sites that send on behalf of an entity or a broadcast push a cause frame on a small
 * server-thread stack; a send is attributed to the innermost frame, else {@code other}. Re-entering the cause (and
 * tracker group) already on top does not count as another broadcast, so nested overloads count once.
 */
public final class PacketProbe {
    static final int MAX_CLASSES = 512, MAX_GROUPS = 512, LABEL_LIMIT = 192, HISTOGRAM_BUCKETS = 41, SERIES_CLASSES = 32, MAX_DEPTH = 32;
    /** Class slot (and tracker group) used once {@link #MAX_CLASSES} classes ({@link #MAX_GROUPS} groups) exist. */
    public static final int OVERFLOW = 0;
    public static final int OTHER = 0, TRACKER_UPDATE = 1, TRACKER_METADATA = 2, TRACKER_SPAWN = 3, TRACKER_DESPAWN = 4,
            ENTITY_EVENT = 5, BROADCAST = 6, PARTICLES = 7, CHUNKS = 8;
    static final String[] CAUSES = {"other", "tracker.update", "tracker.metadata", "tracker.spawn", "tracker.despawn",
            "entity.event", "broadcast", "particles", "chunks"};
    /** Tracker causes are {@code 1..TRACKER_CAUSES}. */
    static final int TRACKER_CAUSES = 4;
    /** Returned by {@link Session#enter} when the cause stack is full; {@link Session#exit} ignores it. */
    public static final int NO_FRAME = -1;

    private static volatile Session current;
    private static final AtomicInteger GENERATIONS = new AtomicInteger();

    // Packet classes: one global slot per class (a closed set in practice), so a capture never looks a class up by name.
    private static final String[] CLASS_NAMES = new String[MAX_CLASSES + 1];
    private static int classCount = 1;
    private static final ClassValue<Integer> SLOTS = new ClassValue<>() {
        @Override protected Integer computeValue(Class<?> type) { return register(type.getName()); }
    };
    static { CLASS_NAMES[OVERFLOW] = "(overflow)"; }

    private PacketProbe() {}

    /** The recording session on any thread, or null (idle: one volatile read). */
    public static Session current() {
        Session session = current;
        return session == null || session.closed ? null : session;
    }
    /** The recording session when called on the server thread, else null (idle: one volatile read). */
    public static Session active() {
        Session session = current;
        return session != null && !session.closed && Thread.currentThread() == session.serverThread ? session : null;
    }
    /** The slot of a packet class: a {@link ClassValue} lookup, no allocation after the class's first call. */
    public static int slot(Class<?> type) { return SLOTS.get(type); }

    private static synchronized Integer register(String name) {
        if (classCount > MAX_CLASSES) return OVERFLOW;
        CLASS_NAMES[classCount] = limit(name);
        return classCount++;
    }
    private static synchronized String className(int slot) { return CLASS_NAMES[slot]; }

    /** Server thread: sends on this thread are timed without a lock and causes are tracked. */
    public static synchronized void start(String id, long startedNanos, int seconds) {
        start(new Session(id, startedNanos, seconds, System::nanoTime, Thread.currentThread()));
    }
    static synchronized void start(Session session) {
        if (current != null) throw new IllegalStateException("Packet probe already has a capture; stop it first");
        current = session;
    }
    public static synchronized Map<String, Object> stop(String id) {
        Session session = current;
        if (session == null || !session.id.equals(id)) throw new IllegalStateException("Packet capture ID does not match");
        current = null;
        if (Thread.currentThread() != session.serverThread) {
            session.closed = true;
            var failed = new LinkedHashMap<String, Object>();
            failed.put("captureId", id);
            failed.put("error", "stopped off the server thread; packet counts discarded");
            return failed;
        }
        return session.close();
    }
    public static String status() {
        Session session = current;
        return session == null ? "available, idle" : session.closed ? "capture stopped; awaiting report" : "recording " + session.id;
    }

    /** Resolves an entity's tracker group label ({@code {entityType, owner}}) once per entity per capture. */
    @FunctionalInterface
    public interface Labeller { String[] typeAndOwner(); }

    public static final class Session {
        final String id;
        /** Distinct per session, so per-entity caches recognise a new capture without holding a reference. */
        public final int generation = GENERATIONS.incrementAndGet();
        final long startedNanos, deadline;
        final int windows;
        final LongSupplier clock;
        /** The thread that started the capture (the server thread in production). Only it touches the plain fields. */
        public final Thread serverThread;
        volatile boolean closed;

        // Outbound, server thread.
        private final long[] sends = new long[MAX_CLASSES + 1], nanos = new long[MAX_CLASSES + 1], maxNanos = new long[MAX_CLASSES + 1],
                bundled = new long[MAX_CLASSES + 1];
        private final long[][] histogram = new long[MAX_CLASSES + 1][], seriesSends = new long[MAX_CLASSES + 1][],
                seriesNanos = new long[MAX_CLASSES + 1][];
        private final long[] windowSends, windowNanos;
        private int sendDepth;
        long outermostNanos, nestedSends, lateSends, bundles;
        // Outbound, other threads (under offThreadLock).
        final Object offThreadLock = new Object();
        private final long[] offThreadSends = new long[MAX_CLASSES + 1], offThreadNanos = new long[MAX_CLASSES + 1];
        private long offThreadLate;

        // Causes, server thread.
        private final int[] stackCause = new int[MAX_DEPTH], stackGroup = new int[MAX_DEPTH];
        private int depth;
        long depthOverflow, nestingErrors;
        private final long[] causePackets = new long[CAUSES.length], causeBroadcasts = new long[CAUSES.length],
                causeNanos = new long[CAUSES.length], causeMax = new long[CAUSES.length];
        private final long[][] causeSeriesPackets, causeSeriesNanos;

        // Tracker groups (entity type x owner), server thread.
        private final Map<String, Integer> index = new HashMap<>();
        private final List<String[]> labels = new ArrayList<>();
        private int groups;
        private final long[] entities = new long[MAX_GROUPS + 1];
        private final long[][] trackerPackets = new long[TRACKER_CAUSES][MAX_GROUPS + 1], trackerBroadcasts = new long[TRACKER_CAUSES][MAX_GROUPS + 1],
                trackerNanos = new long[TRACKER_CAUSES][MAX_GROUPS + 1];
        long labelErrors, overflowEntities;
        long spawnLookups, spawnsAttributed, spawnLookupNanos, passThroughFrames;

        // Inbound, server thread.
        private final long[] inPackets = new long[MAX_CLASSES + 1], inNanos = new long[MAX_CLASSES + 1], inMax = new long[MAX_CLASSES + 1],
                inBetweenPackets = new long[MAX_CLASSES + 1], inBetweenNanos = new long[MAX_CLASSES + 1];
        private final long[][] inHistogram = new long[MAX_CLASSES + 1][], inSeriesPackets = new long[MAX_CLASSES + 1][],
                inSeriesNanos = new long[MAX_CLASSES + 1][];
        private final long[] inWindowPackets, inWindowNanos;
        private boolean betweenTicks;
        long inLate;
        /** Handled on another thread: counted (racy, approximate), not timed. */
        volatile long inOffThread;

        Session(String id, long startedNanos, int seconds, LongSupplier clock, Thread serverThread) {
            if (id == null || id.isBlank() || seconds < 1 || seconds > 1800) throw new IllegalArgumentException("Invalid packet capture");
            this.id = id;
            this.startedNanos = startedNanos;
            this.deadline = startedNanos + seconds * 1_000_000_000L;
            this.windows = seconds + 1;
            this.clock = clock;
            this.serverThread = serverThread;
            windowSends = new long[windows];
            windowNanos = new long[windows];
            inWindowPackets = new long[windows];
            inWindowNanos = new long[windows];
            causeSeriesPackets = new long[CAUSES.length][windows];
            causeSeriesNanos = new long[CAUSES.length][windows];
            labels.add(new String[]{"(overflow)", "(overflow)"});
            groups = 1;
        }

        public long now() { return clock.getAsLong(); }

        // ---- causes ----

        /** Server thread: pushes a cause frame ({@code group} is the tracker group, 0 for other causes). */
        public int enter(int cause, int group) {
            if (depth == MAX_DEPTH) { depthOverflow++; return NO_FRAME; }
            boolean again = depth > 0 && stackCause[depth - 1] == cause && stackGroup[depth - 1] == group;
            if (!again && !closed) {
                causeBroadcasts[cause]++;
                if (cause >= TRACKER_UPDATE && cause <= TRACKER_CAUSES) trackerBroadcasts[cause - 1][group]++;
            }
            stackCause[depth] = cause;
            stackGroup[depth] = group;
            return depth++;
        }
        /** Server thread: pops the frame {@link #enter} returned. */
        public void exit(int frame) {
            if (frame == NO_FRAME) return;
            if (depth != frame + 1) nestingErrors++;
            depth = Math.min(depth, frame);
        }

        /** The tracker group for an entity seen for the first time in this capture. */
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

        /** Server thread: one spawning-plugin lookup at {@code addFreshEntity}. */
        public void spawnLookup(long elapsedNanos, boolean attributed, int skippedFrames) {
            spawnLookups++;
            spawnLookupNanos += Math.max(0, elapsedNanos);
            if (attributed) spawnsAttributed++;
            passThroughFrames += skippedFrames;
        }

        // ---- outbound ----

        /** Server thread, before the send: returns the enclosing send depth for {@link #sent}. */
        public int sendBegin() { return sendDepth++; }

        /** Server thread: one {@code Connection.send} of class {@code slot}, from {@code started} to {@code ended}. */
        public void sent(int slot, long started, long ended, int outer) {
            sendDepth = outer;
            if (closed) return;
            if (ended - deadline >= 0) { lateSends++; return; }
            long elapsed = Math.max(0, ended - started);
            sends[slot]++;
            nanos[slot] += elapsed;
            if (elapsed > maxNanos[slot]) maxNanos[slot] = elapsed;
            long[] buckets = histogram[slot];
            if (buckets == null) buckets = histogram[slot] = new long[HISTOGRAM_BUCKETS];
            buckets[bucket(elapsed)]++;
            if (outer == 0) outermostNanos += elapsed; else nestedSends++;
            int cause = depth == 0 ? OTHER : stackCause[depth - 1];
            causePackets[cause]++;
            causeNanos[cause] += elapsed;
            if (elapsed > causeMax[cause]) causeMax[cause] = elapsed;
            if (cause >= TRACKER_UPDATE && cause <= TRACKER_CAUSES) {
                int group = stackGroup[depth - 1];
                trackerPackets[cause - 1][group]++;
                trackerNanos[cause - 1][group] += elapsed;
            }
            int window = window(ended);
            if (window >= 0) {
                windowSends[window]++;
                windowNanos[window] += elapsed;
                causeSeriesPackets[cause][window]++;
                causeSeriesNanos[cause][window] += elapsed;
                long[] own = seriesSends[slot];
                if (own == null) { own = seriesSends[slot] = new long[windows]; seriesNanos[slot] = new long[windows]; }
                own[window]++;
                seriesNanos[slot][window] += elapsed;
            }
        }

        /** Server thread, after {@link #sent} of a bundle: the bundle itself was counted in {@link #sent}. */
        public void bundle() { if (!closed) bundles++; }
        /** Server thread: one sub-packet of a bundle (counted per class, not as a send). */
        public void bundled(int slot) { if (!closed) bundled[slot]++; }

        /** Another thread: one send, timed under {@link #offThreadLock}. */
        public void sentOffThread(int slot, long started, long ended) {
            synchronized (offThreadLock) {
                if (closed) return;
                if (ended - deadline >= 0) { offThreadLate++; return; }
                offThreadSends[slot]++;
                offThreadNanos[slot] += Math.max(0, ended - started);
            }
        }

        // ---- inbound ----

        /** Server thread: handling now runs from {@code MinecraftServer.pollTask} (between ticks), or not. */
        public boolean betweenTicks(boolean value) {
            boolean previous = betweenTicks;
            betweenTicks = value;
            return previous;
        }

        /** Server thread: one inbound packet of class {@code slot} handled from {@code started} to {@code ended}. */
        public void handled(int slot, long started, long ended) {
            if (closed) return;
            if (ended - deadline >= 0) { inLate++; return; }
            long elapsed = Math.max(0, ended - started);
            inPackets[slot]++;
            inNanos[slot] += elapsed;
            if (elapsed > inMax[slot]) inMax[slot] = elapsed;
            if (betweenTicks) { inBetweenPackets[slot]++; inBetweenNanos[slot] += elapsed; }
            long[] buckets = inHistogram[slot];
            if (buckets == null) buckets = inHistogram[slot] = new long[HISTOGRAM_BUCKETS];
            buckets[bucket(elapsed)]++;
            int window = window(ended);
            if (window >= 0) {
                inWindowPackets[window]++;
                inWindowNanos[window] += elapsed;
                long[] own = inSeriesPackets[slot];
                if (own == null) { own = inSeriesPackets[slot] = new long[windows]; inSeriesNanos[slot] = new long[windows]; }
                own[window]++;
                inSeriesNanos[slot][window] += elapsed;
            }
        }
        /** Another thread: inbound handling is not timed there. */
        public void handledOffThread() { inOffThread++; }

        private int window(long nanosTime) {
            long offset = nanosTime - startedNanos;
            if (offset < 0) return -1;
            long window = offset / 1_000_000_000L;
            return window < windows ? (int) window : -1;
        }

        Map<String, Object> close() {
            closed = true;
            long[] offSends, offNanos;
            long offLate;
            synchronized (offThreadLock) {
                offSends = offThreadSends.clone();
                offNanos = offThreadNanos.clone();
                offLate = offThreadLate;
            }
            long observedMillis = Math.max(0, Math.min(now(), deadline) - startedNanos) / 1_000_000L;
            var result = new LinkedHashMap<String, Object>();
            result.put("captureId", id);
            result.put("outbound", outbound(observedMillis, offSends, offNanos, offLate));
            result.put("causes", causes());
            result.put("trackerCauses", trackerCauses());
            result.put("inbound", inbound(observedMillis));
            return result;
        }

        private Map<String, Object> outbound(long observedMillis, long[] offSends, long[] offNanos, long offLate) {
            var order = new ArrayList<Integer>();
            for (int c = 0; c <= MAX_CLASSES; c++) if (sends[c] > 0 || offSends[c] > 0 || bundled[c] > 0) order.add(c);
            order.sort(Comparator.comparingLong((Integer c) -> nanos[c]).reversed().thenComparing(Comparator.comparingLong((Integer c) -> sends[c] + offSends[c]).reversed())
                    .thenComparing(PacketProbe::className));
            long serverSends = 0, otherSends = 0, otherNanos = 0, bundledTotal = 0, classNanos = 0;
            var rows = new ArrayList<Map<String, Object>>();
            for (int c : order) {
                serverSends += sends[c];
                otherSends += offSends[c];
                otherNanos += offNanos[c];
                bundledTotal += bundled[c];
                classNanos += nanos[c];
                var row = new LinkedHashMap<String, Object>();
                row.put("packetClass", className(c));
                row.put("sends", sends[c] + offSends[c]);
                row.put("serverThreadSends", sends[c]);
                row.put("otherThreadSends", offSends[c]);
                row.put("totalNanos", nanos[c]);
                row.put("maxNanos", maxNanos[c]);
                row.put("otherThreadNanos", offNanos[c]);
                row.put("bundled", bundled[c]);
                row.put("histogramLog2", list(histogram[c] == null ? new long[HISTOGRAM_BUCKETS] : histogram[c]));
                rows.add(row);
            }
            var seriesClasses = new ArrayList<Map<String, Object>>();
            for (int c : order) {
                if (seriesClasses.size() == SERIES_CLASSES) break;
                if (seriesSends[c] == null) continue;
                var row = new LinkedHashMap<String, Object>();
                row.put("packetClass", className(c));
                row.put("sends", list(seriesSends[c]));
                row.put("nanos", list(seriesNanos[c]));
                seriesClasses.add(row);
            }
            var series = new LinkedHashMap<String, Object>();
            series.put("windowMillis", 1000L);
            series.put("sends", list(windowSends));
            series.put("nanos", list(windowNanos));
            series.put("classes", seriesClasses);
            boolean overflow = sends[OVERFLOW] > 0 || offSends[OVERFLOW] > 0 || bundled[OVERFLOW] > 0;
            var out = new LinkedHashMap<String, Object>();
            out.put("observedMillis", observedMillis);
            out.put("sends", serverSends + otherSends);
            out.put("serverThreadSends", serverSends);
            out.put("otherThreadSends", otherSends);
            out.put("serverThreadNanos", outermostNanos);
            out.put("classNanos", classNanos);
            out.put("nestedSends", nestedSends);
            out.put("otherThreadNanos", otherNanos);
            out.put("bundles", bundles);
            out.put("bundledPackets", bundledTotal);
            out.put("lateSends", lateSends + offLate);
            out.put("maxClasses", (long) MAX_CLASSES);
            out.put("classOverflow", overflow);
            out.put("complete", !overflow);
            out.put("classes", rows);
            out.put("series", series);
            return out;
        }

        private Map<String, Object> causes() {
            var rows = new ArrayList<Map<String, Object>>();
            var seriesRows = new ArrayList<Map<String, Object>>();
            var order = new ArrayList<Integer>();
            for (int c = 0; c < CAUSES.length; c++) order.add(c);
            order.sort(Comparator.comparingLong((Integer c) -> causeNanos[c]).reversed().thenComparing(c -> CAUSES[c]));
            for (int c : order) {
                var row = new LinkedHashMap<String, Object>();
                row.put("cause", CAUSES[c]);
                row.put("packets", causePackets[c]);
                if (c != OTHER) row.put("broadcasts", causeBroadcasts[c]);     // other has no call site to count
                row.put("totalNanos", causeNanos[c]);
                row.put("maxNanos", causeMax[c]);
                rows.add(row);
                var series = new LinkedHashMap<String, Object>();
                series.put("cause", CAUSES[c]);
                series.put("packets", list(causeSeriesPackets[c]));
                series.put("nanos", list(causeSeriesNanos[c]));
                seriesRows.add(series);
            }
            var series = new LinkedHashMap<String, Object>();
            series.put("windowMillis", 1000L);
            series.put("causes", seriesRows);
            var out = new LinkedHashMap<String, Object>();
            out.put("maxDepth", (long) MAX_DEPTH);
            out.put("depthOverflow", depthOverflow);
            out.put("nestingErrors", nestingErrors);
            out.put("complete", depthOverflow == 0 && nestingErrors == 0);
            out.put("rows", rows);
            out.put("series", series);
            return out;
        }

        private Map<String, Object> trackerCauses() {
            long[] total = new long[groups];
            var order = new ArrayList<Integer>();
            for (int g = 0; g < groups; g++) {
                long packets = 0;
                for (int c = 0; c < TRACKER_CAUSES; c++) { total[g] += trackerNanos[c][g]; packets += trackerPackets[c][g]; }
                if (packets > 0 || entities[g] > 0) order.add(g);
            }
            order.sort(Comparator.comparingLong((Integer g) -> total[g]).reversed().thenComparing(g -> labels.get(g)[0]).thenComparing(g -> labels.get(g)[1]));
            long allEntities = 0;
            var rows = new ArrayList<Map<String, Object>>();
            for (int g : order) {
                allEntities += entities[g];
                long packets = 0, broadcasts = 0;
                var byCause = new LinkedHashMap<String, Object>();
                for (int c = 0; c < TRACKER_CAUSES; c++) {
                    packets += trackerPackets[c][g];
                    broadcasts += trackerBroadcasts[c][g];
                    if (trackerPackets[c][g] == 0 && trackerBroadcasts[c][g] == 0) continue;
                    var cell = new LinkedHashMap<String, Object>();
                    cell.put("packets", trackerPackets[c][g]);
                    cell.put("broadcasts", trackerBroadcasts[c][g]);
                    cell.put("totalNanos", trackerNanos[c][g]);
                    byCause.put(CAUSES[c + 1], cell);
                }
                var row = new LinkedHashMap<String, Object>();
                row.put("entityType", labels.get(g)[0]);
                row.put("owner", labels.get(g)[1]);
                row.put("entities", entities[g]);
                row.put("packets", packets);
                row.put("broadcasts", broadcasts);
                row.put("totalNanos", total[g]);
                row.put("causes", byCause);
                rows.add(row);
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("entities", allEntities);
            out.put("maxGroups", (long) MAX_GROUPS);
            out.put("overflowEntities", overflowEntities);
            out.put("labelErrors", labelErrors);
            out.put("spawnLookups", spawnLookups);
            out.put("spawnsAttributed", spawnsAttributed);
            out.put("spawnLookupNanos", spawnLookupNanos);
            out.put("passThroughFrames", passThroughFrames);
            out.put("complete", overflowEntities == 0 && labelErrors == 0);
            out.put("groups", rows);
            return out;
        }

        private Map<String, Object> inbound(long observedMillis) {
            var order = new ArrayList<Integer>();
            for (int c = 0; c <= MAX_CLASSES; c++) if (inPackets[c] > 0) order.add(c);
            order.sort(Comparator.comparingLong((Integer c) -> inNanos[c]).reversed().thenComparing(PacketProbe::className));
            long packets = 0, total = 0, between = 0, betweenNanos = 0;
            var rows = new ArrayList<Map<String, Object>>();
            var seriesClasses = new ArrayList<Map<String, Object>>();
            for (int c : order) {
                packets += inPackets[c];
                total += inNanos[c];
                between += inBetweenPackets[c];
                betweenNanos += inBetweenNanos[c];
                var row = new LinkedHashMap<String, Object>();
                row.put("packetClass", className(c));
                row.put("packets", inPackets[c]);
                row.put("totalNanos", inNanos[c]);
                row.put("maxNanos", inMax[c]);
                row.put("betweenTicksPackets", inBetweenPackets[c]);
                row.put("betweenTicksNanos", inBetweenNanos[c]);
                row.put("histogramLog2", list(inHistogram[c] == null ? new long[HISTOGRAM_BUCKETS] : inHistogram[c]));
                rows.add(row);
                if (inSeriesPackets[c] == null) continue;
                var series = new LinkedHashMap<String, Object>();
                series.put("packetClass", className(c));
                series.put("packets", list(inSeriesPackets[c]));
                series.put("nanos", list(inSeriesNanos[c]));
                seriesClasses.add(series);
            }
            var series = new LinkedHashMap<String, Object>();
            series.put("windowMillis", 1000L);
            series.put("packets", list(inWindowPackets));
            series.put("nanos", list(inWindowNanos));
            series.put("classes", seriesClasses);
            boolean overflow = inPackets[OVERFLOW] > 0;
            long offThread = inOffThread;
            var out = new LinkedHashMap<String, Object>();
            out.put("observedMillis", observedMillis);
            out.put("packets", packets);
            out.put("totalNanos", total);
            out.put("betweenTicksPackets", between);
            out.put("betweenTicksNanos", betweenNanos);
            out.put("offThreadPackets", offThread);
            out.put("latePackets", inLate);
            out.put("classOverflow", overflow);
            out.put("complete", !overflow && offThread == 0);
            out.put("classes", rows);
            out.put("series", series);
            return out;
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
