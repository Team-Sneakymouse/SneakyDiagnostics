package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Bounded capture state owned only by the server's companion classloader. */
public final class SchedulerProbe {
    static final int MAX_GROUPS = 2048, MAX_TASK_IDS = 100_000;
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
        current = new Session(id, startedNanos, seconds, System::nanoTime);
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
        return session == null ? null : session.begin(new Key("execution", identity.owner(), identity.taskClass(), identity.sync(), ""));
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
                identity.owner(), identity.taskClass(), identity.sync(), caller));
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
                origin.identity().sync(), origin.caller()));
    }
    private static String caller() {
        return WALKER.walk(frames -> frames.filter(frame -> !frame.getClassName().startsWith("com.teamsneakymouse.sneakydiagnostics.ignite.")
                && !frame.getClassName().startsWith("org.bukkit.craftbukkit.scheduler.")
                && !frame.getClassName().startsWith("org.bukkit.scheduler."))
                .limit(16).findFirst().map(frame -> label(frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber())).orElse("<unknown>"));
    }
    private static String label(String value) { return value.length() <= 192 ? value : value.substring(0, 192); }

    static final class Session {
        final String id;
        final long startedNanos, deadline;
        final LongSupplier clock;
        final Map<Key, Totals> groups = new LinkedHashMap<>();
        final Map<Integer, Identity> identities = new HashMap<>();
        final Map<Integer, Long> cancellations = new HashMap<>();
        volatile boolean closed;
        long droppedEvents, droppedTaskIds, repeatedCancellations, inFlight;

        Session(String id, long startedNanos, int seconds, LongSupplier clock) {
            this.id = id; this.startedNanos = startedNanos; this.deadline = startedNanos + TimeUnit.SECONDS.toNanos(seconds); this.clock = clock;
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
            Token token = begin(new Key("creation", label(identity.owner()), label(identity.taskClass()), identity.sync(), ""));
            finish(token, false);
        }
        synchronized void cancellation(int id) {
            if (id < 0 || closed) return;
            if (!cancellations.containsKey(id) && cancellations.size() >= MAX_TASK_IDS) { droppedTaskIds++; return; }
            if (cancellations.merge(id, 1L, Long::sum) > 1) repeatedCancellations++;
        }
        synchronized Token begin(Key raw) {
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
            return new Token(this, key, clock.getAsLong());
        }
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
        }
        synchronized Map<String, Object> close() {
            closed = true;
            List<Map<String, Object>> rows = new ArrayList<>();
            groups.forEach((key, totals) -> rows.add(Map.of("event", key.event(), "owner", key.owner(), "taskClass", key.taskClass(),
                    "sync", key.sync(), "caller", key.caller(), "counts", Map.of("started", totals.started, "completed", totals.completed,
                    "throwing", totals.throwing, "totalNanos", totals.totalNanos, "maxNanos", totals.maxNanos,
                    "firstOffsetMillis", totals.firstOffsetMillis, "lastOffsetMillis", totals.lastOffsetMillis))));
            return Map.of("protocolVersion", 1, "captureId", id, "groups", List.copyOf(rows),
                    "droppedEvents", droppedEvents, "droppedTaskIds", droppedTaskIds,
                    "repeatedTaskCancellationRequests", repeatedCancellations, "inFlightAtStop", inFlight,
                    "limits", Map.of("groups", MAX_GROUPS, "taskIds", MAX_TASK_IDS),
                    "notes", "Creation counts cover registrations during this capture; their timing fields measure probe bookkeeping, not scheduling latency. Execution times cover runnable/consumer bodies and callable calls. "
                    + "Cancellation requests are not counts of removed tasks. Deferred scans include the entire queued cancellation runnable. "
                    + "Timings are inclusive wall time, not CPU time; request and deferred costs overlap other execution scopes and must not be added. "
                    + "Unknown cancellation targets may predate capture or have exceeded the task-ID cap. Caller is the first external stack frame, not proven plugin ownership. "
                    + "In-flight scopes at stop have no completed duration; late completions cannot mutate the detached report. Callable exceptions are measured before Future handles them.");
        }
    }
}
