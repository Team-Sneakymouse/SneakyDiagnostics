package com.teamsneakymouse.sneakydiagnostics.velocity;

import com.teamsneakymouse.sneakydiagnostics.connection.ConnectionCapture;
import com.velocitypowered.api.proxy.Player;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Velocity commands, scheduler and shutdown can race; this object owns their synchronization. */
public final class VelocityConnections {
    private final Supplier<? extends Collection<Player>> players;
    private final Consumer<Map<String, Object>> writer;
    private final BooleanSupplier writing;
    private final LongSupplier clock;
    private final String proxyVersion, pluginVersion;
    private final Function<UUID, Player> lookup;
    private ConnectionCapture capture;
    private long startedNanos, totalNanos, maxNanos;
    private int seconds;
    private String id, startedAt;
    private boolean closed;

    public VelocityConnections(Supplier<? extends Collection<Player>> players, Consumer<Map<String, Object>> writer,
                               BooleanSupplier writing, LongSupplier clock, String proxyVersion, String pluginVersion) {
        this(players, writer, writing, clock, proxyVersion, pluginVersion, id -> null);
    }
    public VelocityConnections(Supplier<? extends Collection<Player>> players, Consumer<Map<String, Object>> writer,
                               BooleanSupplier writing, LongSupplier clock, String proxyVersion, String pluginVersion,
                               Function<UUID, Player> lookup) {
        this.players = players;
        this.writer = writer;
        this.writing = writing;
        this.clock = clock;
        this.proxyVersion = proxyVersion;
        this.pluginVersion = pluginVersion;
        this.lookup = lookup;
    }

    public synchronized String status() {
        return capture != null ? "Collecting " + id : writing.getAsBoolean() ? "Writing report" : "Idle";
    }

    public synchronized String start(int seconds) {
        return start(seconds, List.of());
    }
    public synchronized String start(int seconds, List<UUID> targets) {
        if (closed) throw new IllegalStateException("Diagnostics is shutting down");
        if (capture != null || writing.getAsBoolean()) throw new IllegalStateException("Connections is busy");
        capture = new ConnectionCapture(seconds, targets);
        this.seconds = seconds;
        id = UUID.randomUUID().toString();
        startedAt = Instant.now().toString();
        startedNanos = clock.getAsLong();
        totalNanos = maxNanos = 0;
        poll();
        if (capture == null) throw new IllegalStateException("Connection collection failed; partial report queued");
        return id;
    }

    public synchronized void poll() {
        if (capture == null) return;
        long begin = clock.getAsLong();
        long offset = (begin - startedNanos) / 1_000_000;
        if (capture.expired(offset)) { stop("completed"); return; }
        try {
            if (capture.due(offset)) capture.sample(offset, players.get(), player ->
                    new ConnectionCapture.Observation(player.getUniqueId(), player.getPing(),
                            player.getCurrentServer().map(server -> server.getServerInfo().getName()).orElse(null)), lookup);
        } catch (RuntimeException e) {
            stop("collection_error");
            return;
        }
        long elapsed = clock.getAsLong() - begin;
        totalNanos += elapsed;
        maxNanos = Math.max(maxNanos, elapsed);
    }

    public synchronized void stop(String reason) {
        if (capture == null) return;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 2);
        report.put("collector", "connections");
        report.put("platform", "velocity");
        report.put("captureId", id);
        report.put("captureStartedAt", startedAt);
        report.put("finishedAt", Instant.now().toString());
        report.put("finishedAfterMillis", (clock.getAsLong() - startedNanos) / 1_000_000);
        report.put("requestedDurationSeconds", seconds);
        report.put("reason", reason);
        report.put("serverVersion", proxyVersion);
        report.put("pluginVersion", pluginVersion);
        report.putAll(capture.report(reason));
        report.put("totalCollectionNanos", totalNanos);
        report.put("maxCollectionNanos", maxNanos);
        report.put("proxyNotes", "Velocity public Player.getPing estimate, not an isolated backend-hop measurement. "
                + "Backend labels are registered server names, not addresses. Scheduler delays appear as sample gaps. "
                + "Proxy and Paper player numbers are unrelated; compare UTC timestamps and population trends, not IDs or ping subtraction.");
        capture = null;
        writer.accept(report);
    }

    public synchronized void close() { closed = true; stop("plugin_disable"); }
}
