package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Exclusive server-thread time per tick phase, one row per server tick (current tick), owned by the companion's
 * classloader.
 *
 * <p>Hot path: a hook calls {@link #active()} (idle: one volatile read and a null check). While a capture records, it
 * returns the session only on the server thread inside a recorded tick; the hook then brackets the wrapped call with
 * {@link Session#enter} / {@link Session#exit}, which touch plain arrays: no lock, no ThreadLocal, no allocation. A
 * phase's self time is its wall time minus the phases nested in it, so phases add up.
 *
 * <p>Tick rows: a row opens at {@code MinecraftServer.processPacketsAndTick} (the current tick was just incremented)
 * and is committed when the next one opens or the capture stops. Paper's measured duration arrives at the
 * {@code ServerTickEndEvent} dispatch ({@link #tickEnd}); phases before it lie inside the measured interval, so
 * {@code other = measured - phases} exactly (in whole microseconds). The dispatch itself ({@code tick-end}) and
 * SneakyDiagnostics' work outside the measured interval are kept apart ({@code outside}).
 */
public final class PhaseProbe {
    /** Listener and task calls of this plugin are the {@code diagnostics} phase. */
    public static final String DIAGNOSTICS_PLUGIN = "SneakyDiagnostics";
    /** Tick rows kept per capture second (20 ticks/s with room for catch-up ticking); more are counted in dropped. */
    static final int TICKS_PER_SECOND = 40;
    public static final int MAX_WORLDS = 64;
    /** Nested phases tracked; deeper ones are counted and stay in their parent's self time. */
    static final int MAX_DEPTH = 32;
    /** At most this many per-tick values are kept (columns x capacity, about 32 MB); further phase columns keep totals only. */
    static final long MAX_VALUES = 8_000_000L;

    public static final int TICK_START_TASKS = 0, SCHEDULER = 1, CONNECTIONS = 2, DIAGNOSTICS = 3, TICK_END = 4,
            DIAGNOSTICS_OUTSIDE = 5;
    static final int GLOBALS = 6;
    public static final int ENTITIES = 0, CHUNKS = 1, TRACKER = 2, BLOCK_ENTITIES = 3, BLOCK_EVENTS = 4, SPAWN_STATE = 5,
            SPAWN_SPAWN = 6, SPAWN_CUSTOM = 7, LEVEL_OTHER = 8;
    static final int KINDS = 9;
    static final int SLOTS = GLOBALS + MAX_WORLDS * KINDS;
    static final String[] GLOBAL_NAMES = {"tick-start-tasks", "scheduler", "connections", "diagnostics", "tick-end", "diagnostics"};
    static final String[] KIND_NAMES = {"entities", "chunks", "tracker", "block-entities", "block-events", "spawning.state",
            "spawning.spawn", "spawning.custom", "other"};
    static final int IN_TICK = 0, IN_TICK_END = 1, BETWEEN = 2;
    static final int UNKNOWN = Integer.MIN_VALUE;

    private static volatile Session current;
    private static final java.util.concurrent.atomic.AtomicInteger GENERATIONS = new java.util.concurrent.atomic.AtomicInteger();

    private PhaseProbe() {}

    /**
     * The recording session when called on the server thread inside a recorded tick, else null. Idle cost: one
     * volatile read.
     */
    public static Session active() {
        Session session = current;
        return session != null && session.open && Thread.currentThread() == session.serverThread ? session : null;
    }

    /** Server thread. Recording starts with the next tick, so the first row is a whole tick. */
    public static synchronized void start(String id, long startedNanos, int seconds) {
        if (id == null || id.isBlank() || seconds < 1 || seconds > 1800) throw new IllegalArgumentException("Invalid phase capture");
        if (current != null) throw new IllegalStateException("Phase probe already has a capture; stop it first");
        current = new Session(id, startedNanos, seconds, System::nanoTime);
    }
    /** Tests: install a session with an injected clock (its creating thread is the server thread). */
    static synchronized void start(Session session) {
        if (current != null) throw new IllegalStateException("Phase probe already has a capture; stop it first");
        current = session;
    }
    public static synchronized Map<String, Object> stop(String id) {
        Session session = current;
        if (session == null || !session.id.equals(id)) throw new IllegalStateException("Phase capture ID does not match");
        current = null;
        if (Thread.currentThread() != session.serverThread) {
            // Only the server thread may read the per-tick state; it stops writing once it sees no current session.
            session.closed = true;
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("schema", "sneakydiagnostics.phases/1");
            failed.put("captureId", id);
            failed.put("complete", false);
            failed.put("error", "stopped off the server thread; tick phases discarded");
            return failed;
        }
        return session.close();
    }
    public static String status() {
        Session session = current;
        return session == null ? "available, idle" : session.expired ? "capture expired; awaiting report"
                : session.open ? "recording " + session.id : "starting " + session.id + " at the next tick";
    }

    /** {@code MinecraftServer.processPacketsAndTick} entry: commits the previous row and opens one for {@code tick}. */
    public static void tickBegin(int tick) {
        Session session = current;
        if (session != null && Thread.currentThread() == session.serverThread) session.beginTick(tick);
    }
    /**
     * {@code ServerTickEndEvent} dispatch: records Paper's measured duration for the open row and returns the session
     * (then bracket the dispatch with {@link #TICK_END} and call {@link Session#tickEndDone}), or null when not recording.
     */
    public static Session tickEnd(double durationMillis) {
        Session session = active();
        if (session != null) session.measured(durationMillis);
        return session;
    }

    static String phaseName(int slot, List<String> worlds) {
        if (slot < GLOBALS) return GLOBAL_NAMES[slot];
        int world = (slot - GLOBALS) / KINDS, kind = (slot - GLOBALS) % KINDS;
        return "level." + worlds.get(world) + "." + KIND_NAMES[kind];
    }
    static boolean insideMeasured(int slot) { return slot != TICK_END && slot != DIAGNOSTICS_OUTSIDE; }

    public static final class Session {
        final String id;
        /** Distinct per session (never 0), so per-level caches recognise a new capture without holding a reference. */
        public final int generation = GENERATIONS.incrementAndGet();
        final long startedNanos, deadline;
        final LongSupplier clock;
        /** The thread that started the capture: the server thread in production. Only it touches the fields below. */
        final Thread serverThread = Thread.currentThread();
        final int capacity;
        volatile boolean closed;
        boolean open, expired;
        int state = BETWEEN;
        // Nesting stack.
        final int[] stackSlot = new int[MAX_DEPTH];
        final long[] stackStart = new long[MAX_DEPTH], stackChild = new long[MAX_DEPTH];
        int depth;
        long depthOverflow, nestingErrors, spanningTicks;
        // The open row.
        int rowTick;
        long rowStartNanos, rowMeasuredNanos = -1;
        final long[] tickNanos = new long[SLOTS];
        final boolean[] touchedFlag = new boolean[SLOTS];
        final int[] touched = new int[SLOTS];
        int touchedCount;
        // Committed rows (grown by doubling up to capacity) and per-phase columns, allocated on a phase's first time.
        int[] ticks = new int[64], measuredMicros = new int[64], otherMicros = new int[64];
        long[] startMicros = new long[64];
        final int[][] columns = new int[SLOTS][];
        int columnCount, count;
        long dropped;
        final boolean[] columnOmitted = new boolean[SLOTS];
        // Totals over every committed tick, stored or dropped.
        final long[] totalNanos = new long[SLOTS], totalCalls = new long[SLOTS], ticksWithTime = new long[SLOTS];
        final int[] maxTickMicros = new int[SLOTS];
        long measuredTicks, unmeasuredTicks, measuredNanosTotal, otherMicrosTotal, negativeOther;
        boolean lastTickPartial;
        // Worlds (closed set) and spawning counters per world.
        final List<String> worlds = new ArrayList<>();
        final List<String> omittedWorlds = new ArrayList<>();
        long omittedWorldCalls;
        final long[] spawnStateCalls = new long[MAX_WORLDS], spawnChunkCalls = new long[MAX_WORLDS],
                spawnChunkCallsThatSpawned = new long[MAX_WORLDS], naturalSpawnAttempts = new long[MAX_WORLDS],
                customSpawnerCalls = new long[MAX_WORLDS];

        Session(String id, long startedNanos, int seconds, LongSupplier clock) {
            this.id = id;
            this.startedNanos = startedNanos;
            this.deadline = startedNanos + TimeUnit.SECONDS.toNanos(seconds);
            this.clock = clock;
            this.capacity = Math.max(1, seconds) * TICKS_PER_SECOND;
        }

        /** Opens a phase frame; returns the frame to pass to {@link #exit}. No allocation. */
        public int enter(int slot) {
            if (slot < 0) return -1;                    // a world past the cap: its time stays in the parent
            int frame = depth;
            if (frame >= MAX_DEPTH) { depthOverflow++; return -1; }
            stackSlot[frame] = slot;
            stackChild[frame] = 0;
            stackStart[frame] = clock.getAsLong();
            depth = frame + 1;
            return frame;
        }
        /** Closes a frame: its self time (wall time minus nested phases) is added to the open row. */
        public void exit(int frame) {
            if (frame < 0 || closed) return;            // an overflowed frame's time stays in its parent
            long now = clock.getAsLong();
            if (frame != depth - 1) {
                nestingErrors++;
                if (frame >= depth) return;             // already unwound (a tick boundary or the stop closed it)
                depth = frame + 1;                      // frames above were never exited: drop them
            }
            long elapsed = now - stackStart[frame];
            add(stackSlot[frame], elapsed - stackChild[frame]);
            depth = frame;
            if (frame > 0) stackChild[frame - 1] += elapsed;
        }
        /** The {@code diagnostics} slot for the current position: inside the measured tick or outside it. */
        public int diagnosticsSlot() { return state == IN_TICK ? DIAGNOSTICS : DIAGNOSTICS_OUTSIDE; }
        /** The slot of a per-world phase, or -1 for a world beyond the cap. */
        public static int level(int world, int kind) { return world < 0 ? -1 : GLOBALS + world * KINDS + kind; }
        /** Server thread: the world's slot in this capture, assigning the next one on first sight (-1 past the cap). */
        public int world(String name) {
            int index = worlds.indexOf(name);
            if (index >= 0) return index;
            if (worlds.size() >= MAX_WORLDS) {
                if (!omittedWorlds.contains(name) && omittedWorlds.size() < MAX_WORLDS) omittedWorlds.add(name);
                omittedWorldCalls++;
                return -1;
            }
            worlds.add(SchedulerProbe.label(name));
            return worlds.size() - 1;
        }
        public void spawnState(int world) { if (world >= 0) spawnStateCalls[world]++; }
        public void customSpawners(int world) { if (world >= 0) customSpawnerCalls[world]++; }
        public void naturalSpawnAttempt(int world) { if (world >= 0) naturalSpawnAttempts[world]++; }
        /** Before a spawnForChunk call: the attempt count to pass to {@link #spawnChunkDone}. */
        public long spawnChunk(int world) {
            if (world < 0) return 0;
            spawnChunkCalls[world]++;
            return naturalSpawnAttempts[world];
        }
        public void spawnChunkDone(int world, long attemptsBefore) {
            if (world >= 0 && naturalSpawnAttempts[world] > attemptsBefore) spawnChunkCallsThatSpawned[world]++;
        }
        /** After the ServerTickEndEvent dispatch: later work until the next tick is outside the measured interval. */
        public void tickEndDone() { state = BETWEEN; }

        void add(int slot, long nanos) {
            if (!touchedFlag[slot]) { touchedFlag[slot] = true; touched[touchedCount++] = slot; }
            tickNanos[slot] += nanos;
            totalCalls[slot]++;
        }
        void measured(double durationMillis) {
            if (durationMillis >= 0 && !Double.isInfinite(durationMillis)) rowMeasuredNanos = Math.round(durationMillis * 1_000_000d);
            state = IN_TICK_END;
        }
        void beginTick(int tick) {
            if (closed) return;
            long now = clock.getAsLong();
            if (open) {
                if (depth != 0) { spanningTicks++; depth = 0; }
                commit();
            }
            if (now - deadline >= 0) { open = false; expired = true; return; }
            open = true;
            state = IN_TICK;
            rowTick = tick;
            rowStartNanos = now;
            rowMeasuredNanos = -1;
        }
        void commit() {
            boolean store = count < capacity;
            if (store) ensure(count + 1);
            long inside = 0;
            for (int i = 0; i < touchedCount; i++) {
                int slot = touched[i];
                long nanos = tickNanos[slot];
                int micros = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.floorDiv(nanos, 1_000L)));
                totalNanos[slot] += nanos;
                ticksWithTime[slot]++;
                maxTickMicros[slot] = Math.max(maxTickMicros[slot], micros);
                if (insideMeasured(slot)) inside += micros;
                if (store) {
                    int[] column = column(slot);
                    if (column != null) column[count] = micros;
                }
                tickNanos[slot] = 0;
                touchedFlag[slot] = false;
            }
            touchedCount = 0;
            int measured = -1, other = UNKNOWN;
            if (rowMeasuredNanos >= 0) {
                long measuredMicrosValue = rowMeasuredNanos / 1_000L;
                long otherValue = measuredMicrosValue - inside;
                measured = (int) Math.min(Integer.MAX_VALUE, measuredMicrosValue);
                other = (int) Math.max(Integer.MIN_VALUE + 1, Math.min(Integer.MAX_VALUE, otherValue));
                measuredTicks++;
                measuredNanosTotal += rowMeasuredNanos;
                otherMicrosTotal += otherValue;
                if (otherValue < 0) negativeOther++;
            } else {
                unmeasuredTicks++;
            }
            if (!store) { dropped++; return; }
            ticks[count] = rowTick;
            startMicros[count] = (rowStartNanos - startedNanos) / 1_000L;
            measuredMicros[count] = measured;
            otherMicros[count] = other;
            count++;
        }
        private int[] column(int slot) {
            int[] column = columns[slot];
            if (column != null || columnOmitted[slot]) return column;
            if ((long) (columnCount + 1) * capacity > MAX_VALUES) { columnOmitted[slot] = true; return null; }
            column = columns[slot] = new int[ticks.length];
            columnCount++;
            return column;
        }
        private void ensure(int size) {
            if (size <= ticks.length) return;
            int length = (int) Math.min(capacity, Math.max(size, (long) ticks.length * 2));
            ticks = Arrays.copyOf(ticks, length);
            measuredMicros = Arrays.copyOf(measuredMicros, length);
            otherMicros = Arrays.copyOf(otherMicros, length);
            startMicros = Arrays.copyOf(startMicros, length);
            for (int slot = 0; slot < SLOTS; slot++) if (columns[slot] != null) columns[slot] = Arrays.copyOf(columns[slot], length);
        }

        /** Server thread: closes the frames still open (the stop runs inside some), commits the open row and exports. */
        Map<String, Object> close() {
            long now = clock.getAsLong();
            if (open) {
                for (int frame = depth - 1; frame >= 0; frame--) {
                    long elapsed = now - stackStart[frame];
                    add(stackSlot[frame], elapsed - stackChild[frame]);
                    if (frame > 0) stackChild[frame - 1] += elapsed;
                }
                lastTickPartial = state == IN_TICK;     // stopped before the tick's measured duration was known
                commit();
            }
            depth = 0;
            open = false;
            closed = true;
            return export();
        }

        private Map<String, Object> export() {
            Map<String, Object> inside = new LinkedHashMap<>(), outside = new LinkedHashMap<>(), totals = new LinkedHashMap<>();
            List<String> omittedSeries = new ArrayList<>();
            for (int slot = 0; slot < SLOTS; slot++) {
                if (totalCalls[slot] == 0) continue;
                String name = phaseName(slot, worlds);
                Map<String, Object> total = new LinkedHashMap<>();
                total.put("totalNanos", totalNanos[slot]);
                total.put("calls", totalCalls[slot]);
                total.put("ticksWithTime", ticksWithTime[slot]);
                total.put("maxTickMicros", (long) maxTickMicros[slot]);
                total.put("insideMeasuredTick", insideMeasured(slot));
                totals.put((insideMeasured(slot) ? "" : "outside.") + name, total);
                if (columnOmitted[slot]) { omittedSeries.add(name); continue; }
                // A detached primitive copy: no boxing on the server thread (Gson writes int[] as a JSON array).
                int[] column = columns[slot];
                (insideMeasured(slot) ? inside : outside).put(name, column == null ? new int[count] : Arrays.copyOf(column, count));
            }
            List<Object> tickList = new ArrayList<>(count), startList = new ArrayList<>(count),
                    measuredList = new ArrayList<>(count), otherList = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                tickList.add(ticks[i]);
                startList.add(startMicros[i]);
                measuredList.add(measuredMicros[i] < 0 ? null : measuredMicros[i]);
                otherList.add(otherMicros[i] == UNKNOWN ? null : otherMicros[i]);
            }
            Map<String, Object> tickRows = new LinkedHashMap<>();
            tickRows.put("tick", tickList);
            tickRows.put("startOffsetMicros", startList);
            tickRows.put("measuredMicros", measuredList);
            tickRows.put("count", (long) count);
            tickRows.put("capacity", (long) capacity);
            tickRows.put("dropped", dropped);
            Map<String, Object> spawning = new LinkedHashMap<>();
            for (int world = 0; world < worlds.size(); world++) {
                Map<String, Object> counters = new LinkedHashMap<>();
                counters.put("stateCalls", spawnStateCalls[world]);
                counters.put("spawnChunkCalls", spawnChunkCalls[world]);
                counters.put("spawnChunkCallsThatSpawned", spawnChunkCallsThatSpawned[world]);
                counters.put("naturalSpawnAttempts", naturalSpawnAttempts[world]);
                counters.put("customSpawnerCalls", customSpawnerCalls[world]);
                spawning.put(worlds.get(world), counters);
            }
            Map<String, Object> measured = new LinkedHashMap<>();
            measured.put("measuredTicks", measuredTicks);
            measured.put("unmeasuredTicks", unmeasuredTicks);
            measured.put("measuredNanos", measuredNanosTotal);
            measured.put("otherMicros", otherMicrosTotal);
            measured.put("negativeOtherTicks", negativeOther);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema", "sneakydiagnostics.phases/1");
            result.put("captureId", id);
            result.put("complete", dropped == 0 && depthOverflow == 0 && nestingErrors == 0 && spanningTicks == 0
                    && omittedWorlds.isEmpty() && omittedSeries.isEmpty());
            result.put("ticks", tickRows);
            result.put("phases", inside);
            result.put("other", otherList);
            result.put("outside", outside);
            result.put("totals", totals);
            result.put("measured", measured);
            result.put("worlds", List.copyOf(worlds));
            result.put("omittedWorlds", List.copyOf(omittedWorlds));
            result.put("omittedWorldCalls", omittedWorldCalls);
            result.put("omittedSeries", List.copyOf(omittedSeries));
            result.put("spawning", spawning);
            result.put("depthOverflow", depthOverflow);
            result.put("nestingErrors", nestingErrors);
            result.put("spanningTicks", spanningTicks);
            result.put("lastTickPartial", lastTickPartial);
            result.put("limits", Map.of("ticksPerSecond", TICKS_PER_SECOND, "worlds", MAX_WORLDS, "depth", MAX_DEPTH,
                    "values", MAX_VALUES));
            result.put("notes", "Per server tick (current tick), exclusive server-thread wall time per phase in microseconds: "
                    + "a nested phase is subtracted from its parent, so the phases of a tick add up. Recording starts at the first "
                    + "tick after start. measuredMicros is Paper's tick duration read at the ServerTickEndEvent dispatch (the "
                    + "same interval as the scheduler report's measuredTicks); other = measuredMicros - sum(phases) for that "
                    + "tick, null where no duration was measured (empty-server pause, the stop before tick end). outside "
                    + "holds time after the measured interval: the ServerTickEndEvent dispatch (tick-end, its listeners) and "
                    + "SneakyDiagnostics' own work there or between ticks (diagnostics). diagnostics is SneakyDiagnostics' "
                    + "listener and scheduler-task calls. A phase missing from phases/outside had no time in any tick. "
                    + "spawning.state is NaturalSpawner.createState (mob-cap bookkeeping), spawning.spawn is "
                    + "NaturalSpawner.spawnForChunk, spawning.custom is ServerLevel.tickCustomSpawners; naturalSpawnAttempts "
                    + "counts entities NaturalSpawner tried to add (before spawn events could cancel them). Totals include "
                    + "dropped ticks.");
            return result;
        }
    }
}
