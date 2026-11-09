package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/** Bounded capture state owned only by the server's companion classloader. */
public final class SchedulerProbe {
    static final int MAX_GROUPS = 2048, MAX_TASK_IDS = 100_000;
    /** Slow calls: at or above 1 ms (2% of a 50 ms tick), the 512 longest of each kind, 32 still running at stop. */
    static final long SLOW_NANOS = 1_000_000L;
    static final int MAX_SLOW_EXECUTIONS = 512, MAX_SLOW_LISTENERS = 512, MAX_RUNNING_AT_STOP = 32;
    /** Measured tick intervals kept per capture second (20 ticks/s with room for faster ticking); more are counted. */
    static final int MEASURED_TICKS_PER_SECOND = 40;
    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final ThreadLocal<Cancellation> CANCELLATION = new ThreadLocal<>();
    private static volatile Session current;

    public record Identity(String owner, String taskClass, boolean sync) {}
    private record Key(String event, String owner, String taskClass, boolean sync, String caller) {}
    private static final class Totals {
        long started, completed, throwing, totalNanos, maxNanos, firstOffsetMillis, lastOffsetMillis;
    }
    public record Cancellation(Session session, int taskId, Identity identity, String caller, Cancellation previous, Token token) {}
    public record Token(Session session, Key key, long startedNanos) {}

    private SchedulerProbe() {}
    public static boolean enabled() {
        Session session = current;
        return session != null && session.accepting();
    }
    public static synchronized void start(String id, long startedNanos, int seconds) {
        if (id == null || id.isBlank() || seconds < 1 || seconds > 1800) throw new IllegalArgumentException("Invalid scheduler capture");
        if (current != null) throw new IllegalStateException("Scheduler probe already has a capture; stop it first");
        current = new Session(id, startedNanos, seconds, System::nanoTime, ThreadTick.BUKKIT);
    }
    /** Tests: install a session with an injected clock and thread/tick source. */
    static synchronized void start(Session session) {
        if (current != null) throw new IllegalStateException("Scheduler probe already has a capture; stop it first");
        current = session;
    }
    public static synchronized Map<String, Object> stop(String id) {
        Session session = current;
        if (session == null || !session.id.equals(id)) throw new IllegalStateException("Scheduler capture ID does not match");
        Map<String, Object> result = session.close();
        current = null;
        return result;
    }
    public static String status() {
        Session session = current;
        return session == null ? "available, idle" : session.accepting() ? "recording " + session.id : "capture expired; awaiting report";
    }

    public static void created(int id, Identity identity) {
        Session session = current;
        if (session != null && session.accepting()) session.created(id, identity);
    }
    public static Token execution(Identity identity) {
        Session session = current;
        return session == null ? null : session.execution(identity);
    }
    public static void finish(Token token, boolean throwing) {
        if (token != null) token.session().finish(token, throwing);
    }
    public static Cancellation beginCancellation(int id, Identity hint) {
        Session session = current;
        if (session == null || !session.accepting()) return null;
        String caller = caller();
        Identity identity = session.identity(id, hint);
        Token token = session.begin(new Key(id == -1 ? "cancel_plugin_request" : "cancel_task_request",
                identity.owner(), identity.taskClass(), identity.sync(), caller), true);
        if (token == null) return null;
        session.cancellation(id);
        Cancellation cancellation = new Cancellation(session, id, identity, caller, CANCELLATION.get(), token);
        CANCELLATION.set(cancellation);
        return cancellation;
    }
    public static void endCancellation(Cancellation cancellation, boolean throwing) {
        if (cancellation == null) return;
        if (cancellation.previous() == null) CANCELLATION.remove();
        else CANCELLATION.set(cancellation.previous());
        finish(cancellation.token(), throwing);
    }
    public static Cancellation cancellationOrigin() { return CANCELLATION.get(); }
    public static Token deferred(Cancellation origin) {
        if (origin == null || origin.session() != current) return null;
        return origin.session().begin(new Key("deferred_cancel_scan", origin.identity().owner(), origin.identity().taskClass(),
                origin.identity().sync(), origin.caller()), true);
    }

    /**
     * Start of one event-listener call: 0 unless a capture is recording, otherwise the clock reading to pass to
     * {@link #listenerEnd}. Only a volatile read when idle; a clock read and an uncontended counter while recording.
     */
    public static long listenerStart() {
        Session session = current;
        if (session == null || session.closed) return 0;
        long now = session.clock.getAsLong();
        if (now - session.deadline >= 0) return 0;
        session.listenerCalls.increment();
        if (Thread.currentThread() == session.serverThread) session.serverListenerDepth++;
        return now == 0 ? 1 : now;
    }
    /**
     * The call's duration if it was slow enough to record (then call {@link #slowListener}, which counts it as ended),
     * otherwise -1 (a fast call is counted as ended here). A call whose capture stopped first is never counted as ended:
     * the stopped report already lists it as running at stop.
     */
    public static long listenerEnd(long started) {
        Session session = current;
        if (started == 0 || session == null || session.closed || started - session.startedNanos < 0) return -1;
        long elapsed = session.clock.getAsLong() - started;
        if (Thread.currentThread() == session.serverThread) session.serverListenerDepth--;
        if (session.slowListeners.slow(elapsed)) return elapsed;
        session.listenerEnded.increment();
        return -1;
    }
    /** Records a slow listener call. Labels are fixed class/plugin names, built by the caller only for slow calls. */
    public static void slowListener(long started, long elapsed, String owner, String listenerClass, String eventClass,
                                    String priority, boolean asyncEvent, boolean throwing) {
        Session session = current;
        if (session == null || session.closed || started - session.startedNanos < 0) return;
        int tick = session.ticks.read();
        session.slowListeners.add(elapsed, () -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("owner", label(owner));
            fields.put("listenerClass", label(listenerClass));
            fields.put("eventClass", label(eventClass));
            fields.put("priority", priority);
            fields.put("asyncEvent", asyncEvent);
            session.timing(started, elapsed, tick, throwing, fields);
            return fields;
        }, session.listenerEnded::increment);         // rejected after export: then it counts as running at stop
    }

    /**
     * A ServerTickEndEvent listener call began at {@code started} (from {@link #listenerStart}). Paper read the tick's
     * duration immediately before dispatching that event, so the first such call of a tick marks where the measured
     * tick ended: the tick's measured interval is {@code [started - duration, started]} (to within the event's own
     * dispatch, microseconds). Recorded for every tick, independent of which listener calls were slow enough to keep.
     */
    public static void tickEndDispatch(long started, double durationMillis) {
        Session session = current;
        if (started == 0 || session == null || session.closed || started - session.startedNanos < 0) return;
        if (Thread.currentThread() != session.serverThread) return;
        session.measuredTick(started, durationMillis);
    }

    private static String caller() {
        return WALKER.walk(frames -> frames.filter(frame -> !frame.getClassName().startsWith("com.teamsneakymouse.sneakydiagnostics.ignite.")
                && !frame.getClassName().startsWith("org.bukkit.craftbukkit.scheduler.")
                && !frame.getClassName().startsWith("org.bukkit.scheduler."))
                .limit(16).findFirst().map(frame -> label(frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber())).orElse("<unknown>"));
    }
    private static String label(String value) { return value == null ? "<unknown>" : value.length() <= 192 ? value : value.substring(0, 192); }

    static final class Session {
        final String id;
        final long startedNanos, deadline;
        final LongSupplier clock;
        final ThreadTick ticks;
        final Map<Key, Totals> groups = new LinkedHashMap<>();
        final Map<Integer, Identity> identities = new HashMap<>();
        final Map<Integer, Long> cancellations = new HashMap<>();
        final Set<Token> running = Collections.newSetFromMap(new IdentityHashMap<>());
        final SlowRecords slowExecutions = new SlowRecords(SLOW_NANOS, MAX_SLOW_EXECUTIONS);
        final SlowRecords slowListeners = new SlowRecords(SLOW_NANOS, MAX_SLOW_LISTENERS);
        final LongAdder listenerCalls = new LongAdder(), listenerEnded = new LongAdder();
        /** The thread that started the capture (the server thread in production); only it touches the depth. */
        final Thread serverThread = Thread.currentThread();
        int serverListenerDepth;
        /** Per measured tick: current tick and capture offsets (micros) of the measured interval. Server thread writes. */
        final int measuredCapacity;
        long[] measuredTicks = new long[64], measuredStarts = new long[64], measuredEnds = new long[64];
        int measuredCount;
        long measuredDropped, lastMeasuredTick = Long.MIN_VALUE;
        volatile boolean closed;
        long droppedEvents, droppedTaskIds, repeatedCancellations, inFlight;

        Session(String id, long startedNanos, int seconds, LongSupplier clock) { this(id, startedNanos, seconds, clock, ThreadTick.BUKKIT); }
        Session(String id, long startedNanos, int seconds, LongSupplier clock, ThreadTick ticks) {
            this.id = id; this.startedNanos = startedNanos; this.deadline = startedNanos + TimeUnit.SECONDS.toNanos(seconds);
            this.clock = clock; this.ticks = ticks;
            this.measuredCapacity = Math.max(1, seconds) * MEASURED_TICKS_PER_SECOND;
        }
        /** Only the first tick-end listener call of each tick counts; ticks without a server tick identity are skipped. */
        synchronized void measuredTick(long started, double durationMillis) {
            if (closed) return;
            int tick = ticks.read();
            if (tick == ThreadTick.OTHER_THREAD || tick == ThreadTick.UNKNOWN || tick == lastMeasuredTick) return;
            lastMeasuredTick = tick;
            if (!(durationMillis >= 0) || Double.isInfinite(durationMillis)) return;
            if (measuredCount >= measuredCapacity) { measuredDropped++; return; }
            if (measuredCount == measuredTicks.length) {
                int size = Math.min(measuredCapacity, measuredTicks.length * 2);
                measuredTicks = java.util.Arrays.copyOf(measuredTicks, size);
                measuredStarts = java.util.Arrays.copyOf(measuredStarts, size);
                measuredEnds = java.util.Arrays.copyOf(measuredEnds, size);
            }
            long end = started - startedNanos;
            measuredTicks[measuredCount] = tick;
            measuredStarts[measuredCount] = (end - Math.round(durationMillis * 1_000_000d)) / 1_000;
            measuredEnds[measuredCount] = end / 1_000;
            measuredCount++;
        }
        boolean accepting() { return !closed && clock.getAsLong() - deadline < 0; }
        synchronized Identity identity(int id, Identity hint) {
            return identities.getOrDefault(id, hint != null ? hint : new Identity("<unknown: not observed>", "<unknown>", true));
        }
        synchronized void created(int id, Identity identity) {
            if (!accepting()) return;
            identity = new Identity(label(identity.owner()), label(identity.taskClass()), identity.sync());
            if (identities.containsKey(id) || identities.size() < MAX_TASK_IDS) identities.put(id, identity);
            else droppedTaskIds++;
            Token token = begin(new Key("creation", label(identity.owner()), label(identity.taskClass()), identity.sync(), ""), false);
            finish(token, false);
        }
        synchronized void cancellation(int id) {
            if (id < 0 || closed) return;
            if (!cancellations.containsKey(id) && cancellations.size() >= MAX_TASK_IDS) { droppedTaskIds++; return; }
            if (cancellations.merge(id, 1L, Long::sum) > 1) repeatedCancellations++;
        }
        Token execution(Identity identity) {
            return begin(new Key("execution", identity.owner(), identity.taskClass(), identity.sync(), ""), true);
        }
        /** {@code timed}: the scope runs plugin code, so it can be slow and still running at stop (not creation bookkeeping). */
        synchronized Token begin(Key raw, boolean timed) {
            if (!accepting()) return null;
            Key key = new Key(raw.event(), label(raw.owner()), label(raw.taskClass()), raw.sync(), label(raw.caller()));
            Totals totals = groups.get(key);
            if (totals == null) {
                if (groups.size() >= MAX_GROUPS) { droppedEvents++; return null; }
                totals = new Totals(); groups.put(key, totals);
                totals.firstOffsetMillis = (clock.getAsLong() - startedNanos) / 1_000_000;
            }
            totals.started++;
            totals.lastOffsetMillis = (clock.getAsLong() - startedNanos) / 1_000_000;
            inFlight++;
            Token token = new Token(this, key, clock.getAsLong());
            if (timed) running.add(token);
            return token;
        }
        /**
         * Completion, the running set and the slow record change together under the session lock, so a concurrent
         * {@link #close} sees a call either as running at stop or as completed (and slow, if it was), never neither.
         */
        synchronized void finish(Token token, boolean throwing) {
            if (token == null || closed) return;
            Totals totals = groups.get(token.key());
            long elapsed = clock.getAsLong() - token.startedNanos();
            totals.completed++;
            if (throwing) totals.throwing++;
            totals.totalNanos += elapsed;
            totals.maxNanos = Math.max(totals.maxNanos, elapsed);
            totals.lastOffsetMillis = (clock.getAsLong() - startedNanos) / 1_000_000;
            inFlight--;
            if (!running.remove(token)) return;         // creation bookkeeping is never a slow call
            if (!slowExecutions.slow(elapsed)) return;
            int tick = ticks.read();                     // this thread's tick: a thread check and a static read
            Key key = token.key();
            slowExecutions.add(elapsed, () -> {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("event", key.event());
                fields.put("owner", key.owner());
                fields.put("taskClass", key.taskClass());
                fields.put("sync", key.sync());
                if (!key.caller().isEmpty()) fields.put("caller", key.caller());
                timing(token.startedNanos(), elapsed, tick, throwing, fields);
                return fields;
            });
        }
        /** Capture offsets of the call's start and end, its duration, thread/tick, and whether it ended past the deadline. */
        void timing(long started, long elapsed, int tick, boolean throwing, Map<String, Object> fields) {
            fields.put("startOffsetMicros", (started - startedNanos) / 1_000);
            fields.put("endOffsetMicros", (started + elapsed - startedNanos) / 1_000);
            fields.put("nanos", elapsed);
            ThreadTick.describe(tick, fields);
            fields.put("throwing", throwing);
            fields.put("endedAfterDeadline", started + elapsed - deadline > 0);
        }
        synchronized Map<String, Object> close() {
            closed = true;
            long now = clock.getAsLong();
            List<Map<String, Object>> rows = new ArrayList<>();
            groups.forEach((key, totals) -> rows.add(Map.of("event", key.event(), "owner", key.owner(), "taskClass", key.taskClass(),
                    "sync", key.sync(), "caller", key.caller(), "counts", Map.of("started", totals.started, "completed", totals.completed,
                    "throwing", totals.throwing, "totalNanos", totals.totalNanos, "maxNanos", totals.maxNanos,
                    "firstOffsetMillis", totals.firstOffsetMillis, "lastOffsetMillis", totals.lastOffsetMillis))));
            List<Token> unfinished = new ArrayList<>(running);
            unfinished.sort(Comparator.comparingLong(Token::startedNanos));
            List<Map<String, Object>> runningRows = new ArrayList<>();
            for (Token token : unfinished.subList(0, Math.min(MAX_RUNNING_AT_STOP, unfinished.size()))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("event", token.key().event());
                row.put("owner", token.key().owner());
                row.put("taskClass", token.key().taskClass());
                row.put("sync", token.key().sync());
                if (!token.key().caller().isEmpty()) row.put("caller", token.key().caller());
                row.put("startOffsetMicros", (token.startedNanos() - startedNanos) / 1_000);
                row.put("runningNanosAtStop", now - token.startedNanos());
                runningRows.add(row);
            }
            Map<String, Object> runningAtStop = new LinkedHashMap<>();
            runningAtStop.put("count", (long) unfinished.size());
            runningAtStop.put("capacity", MAX_RUNNING_AT_STOP);
            runningAtStop.put("records", List.copyOf(runningRows));
            Map<String, Object> listeners = slowListeners.export();     // later slow listener calls are rejected
            long calls = listenerCalls.sum();
            long listenersRunning = Math.max(0, calls - listenerEnded.sum());
            long aroundStop = Thread.currentThread() == serverThread ? Math.min(Math.max(0, serverListenerDepth), listenersRunning) : 0;
            listeners.put("observedInvocations", calls);
            listeners.put("runningAtStop", listenersRunning);
            listeners.put("runningAroundStop", aroundStop);
            // A call still running elsewhere may have been slow: the list can no longer be called complete.
            listeners.put("complete", Boolean.TRUE.equals(listeners.get("complete")) && listenersRunning == aroundStop);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("protocolVersion", 1);
            result.put("captureId", id);
            result.put("groups", List.copyOf(rows));
            result.put("droppedEvents", droppedEvents);
            result.put("droppedTaskIds", droppedTaskIds);
            result.put("repeatedTaskCancellationRequests", repeatedCancellations);
            result.put("inFlightAtStop", inFlight);
            result.put("slowExecutions", slowExecutions.export());
            result.put("runningAtStop", runningAtStop);
            result.put("slowListeners", listeners);
            Map<String, Object> measured = new LinkedHashMap<>();
            List<Object> tickList = new ArrayList<>(measuredCount), startList = new ArrayList<>(measuredCount), endList = new ArrayList<>(measuredCount);
            for (int i = 0; i < measuredCount; i++) {
                tickList.add(measuredTicks[i]);
                startList.add(measuredStarts[i]);
                endList.add(measuredEnds[i]);
            }
            measured.put("tick", tickList);
            measured.put("startOffsetMicros", startList);
            measured.put("endOffsetMicros", endList);
            measured.put("capacity", (long) measuredCapacity);
            measured.put("dropped", measuredDropped);
            result.put("measuredTicks", measured);
            result.put("limits", Map.of("groups", MAX_GROUPS, "taskIds", MAX_TASK_IDS, "slowNanos", SLOW_NANOS,
                    "slowExecutions", MAX_SLOW_EXECUTIONS, "slowListeners", MAX_SLOW_LISTENERS, "runningAtStop", MAX_RUNNING_AT_STOP));
            result.put("notes", "Creation counts cover registrations during this capture; their timing fields measure probe bookkeeping, not scheduling latency. Execution times cover runnable/consumer bodies and callable calls. "
                    + "Cancellation requests are not counts of removed tasks. Deferred scans include the entire queued cancellation runnable. "
                    + "Timings are inclusive wall time, not CPU time; request and deferred costs overlap other execution scopes and must not be added. "
                    + "Unknown cancellation targets may predate capture or have exceeded the task-ID cap. Caller is the first external stack frame, not proven plugin ownership. "
                    + "In-flight scopes at stop have no completed duration; late completions cannot mutate the detached report. Callable exceptions are measured before Future handles them. "
                    + "slowExecutions and slowListeners keep the longest calls of at least slowNanos (evicted counts the rest over the threshold); offsets are capture offsets in microseconds. "
                    + "tick is the server tick (Bukkit.getCurrentTick, as in the timeline) and is present only for calls that ended on the server thread; async calls overlap ticks and have none. "
                    + "Listener records time one RegisteredListener's executor call (inclusive: nested events and any inventory opened inside count too); they overlap scheduler records and plugin sections. "
                    + "runningAtStop lists scheduler scopes begun before stop that had not ended, with their running time so far. "
                    + "slowListeners.runningAtStop counts listener calls begun before stop that had not ended (it can include calls ending while the stop ran); "
                    + "runningAroundStop are those on the stopping thread's own stack (normally the SneakyDiagnostics tick-end listener that stops the capture), "
                    + "which do not make the list incomplete; calls running on other threads do. "
                    + "measuredTicks: per server tick (current tick), the interval Paper measured as the tick duration: it ends where the "
                    + "tick's first ServerTickEndEvent listener call began (Paper reads the duration just before that dispatch), so "
                    + "tick-end listeners, the stop and empty-server pauses lie outside it.");
            return result;
        }
    }
}
