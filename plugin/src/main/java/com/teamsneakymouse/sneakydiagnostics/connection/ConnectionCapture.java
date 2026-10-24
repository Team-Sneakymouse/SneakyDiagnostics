package com.teamsneakymouse.sneakydiagnostics.connection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/** Platform-neutral, bounded recorder. Its owner serializes all access. */
public final class ConnectionCapture {
    public static final int MAX_PLAYERS_PER_SAMPLE = 512;
    public static final int MAX_IDENTITIES = 4096;
    public static final int MAX_ROWS = 250_000;
    public static final int MAX_TARGETS = 16;
    public record Observation(UUID identity, long pingMillis, String backend) {}
    public record PlayerPing(int player, long pingMillis, String backend, int target) {}
    public record Target(int target, Integer player) {}
    public record Group(int sampledPlayers, int unknownPings, Long p50Millis, Long p95Millis, Long maxMillis,
                        int atLeast250ms, int atLeast500ms, int atLeast1000ms, int atLeast2000ms) {}
    public record Sample(long offsetMillis, long sincePreviousSampleMillis, int onlinePlayers,
                         int examinedPlayers, int omittedPlayers, int failedReads, int unknownPings,
                         Long p50Millis, Long p95Millis, Long maxMillis,
                         int atLeast250ms, int atLeast500ms, int atLeast1000ms, int atLeast2000ms,
                         List<PlayerPing> players, Group targets, Group baseline) {}

    private final int seconds;
    private final List<UUID> targets;
    private final Map<UUID, Integer> identities = new HashMap<>();
    private final List<Sample> samples = new ArrayList<>();
    private long lastSample = -1;
    private int rows;
    private boolean truncated, failed, unavailable;

    public ConnectionCapture(int seconds) { this(seconds, List.of()); }
    public ConnectionCapture(int seconds, List<UUID> targets) {
        if (seconds < 1 || seconds > 1800) throw new IllegalArgumentException("Duration must be from 1 to 1800 seconds");
        if (targets.size() > MAX_TARGETS || targets.stream().distinct().count() != targets.size())
            throw new IllegalArgumentException("Select at most " + MAX_TARGETS + " distinct target players");
        this.seconds = seconds;
        this.targets = List.copyOf(targets);
        // Reserve target identity slots so churn cannot prevent their later observation.
        for (UUID id : this.targets) identities.put(id, identities.size() + 1);
    }

    public boolean expired(long offsetMillis) { return offsetMillis >= seconds * 1000L; }
    public boolean due(long offsetMillis) {
        return offsetMillis >= 0 && !expired(offsetMillis) && (lastSample < 0 || offsetMillis - lastSample >= 1000);
    }

    /** Reads only bounded public API fields. Negative pings remain unknown, never zero. */
    public <P> void sample(long offsetMillis, Collection<? extends P> players, Function<P, Observation> read) {
        sample(offsetMillis, players, read, id -> null);
    }

    /** Lookups return the same live player objects as the population collection. */
    public <P> void sample(long offsetMillis, Collection<? extends P> players, Function<P, Observation> read, Function<UUID, P> lookup) {
        if (!due(offsetMillis)) return;
        int examined = 0, omitted = 0, errors = 0, unknown = 0;
        int high250 = 0, high500 = 0, high1000 = 0, high2000 = 0;
        var values = new ArrayList<PlayerPing>();
        var pings = new ArrayList<Long>();
        var targetPings = new ArrayList<PlayerPing>();
        var baselinePings = new ArrayList<PlayerPing>();
        var priority = new ArrayList<P>();
        for (UUID id : targets) {
            try {
                P player = lookup.apply(id);
                if (player != null) priority.add(player);
            } catch (RuntimeException e) { errors++; }
        }
        var prioritized = Collections.newSetFromMap(new IdentityHashMap<P, Boolean>());
        prioritized.addAll(priority);
        int online = players.size();
        var ordered = Stream.concat(priority.stream(), players.stream().filter(player -> !prioritized.contains(player))).iterator();
        while (ordered.hasNext()) {
            if (examined >= MAX_PLAYERS_PER_SAMPLE || rows >= MAX_ROWS) break;
            P player = ordered.next();
            examined++;
            try {
                Observation observation = read.apply(player);
                Integer id = identities.get(observation.identity());
                if (id == null) {
                    if (identities.size() >= MAX_IDENTITIES) { omitted++; continue; }
                    id = identities.size() + 1;
                    identities.put(observation.identity(), id);
                }
                long ping = observation.pingMillis();
                String backend = observation.backend();
                if (backend != null && backend.length() > 128) { backend = backend.substring(0, 128); truncated = true; }
                int target = targets.indexOf(observation.identity()) + 1;
                var value = new PlayerPing(id, ping, backend, target);
                values.add(value);
                (target > 0 ? targetPings : baselinePings).add(value);
                rows++;
                if (ping < 0) { unknown++; continue; }
                pings.add(ping);
                if (ping >= 250) high250++;
                if (ping >= 500) high500++;
                if (ping >= 1000) high1000++;
                if (ping >= 2000) high2000++;
            } catch (RuntimeException e) {
                // Never retain exception messages: platform exceptions can contain player identifiers.
                errors++;
            }
        }
        omitted += Math.max(0, online - examined);
        truncated |= omitted > 0;
        failed |= errors > 0;
        unavailable |= unknown > 0;
        pings.sort(Long::compare);
        samples.add(new Sample(offsetMillis, lastSample < 0 ? 0 : offsetMillis - lastSample,
                online, examined, omitted, errors, unknown,
                percentile(pings, .50), percentile(pings, .95), percentile(pings, 1),
                high250, high500, high1000, high2000, List.copyOf(values), group(targetPings), group(baselinePings)));
        lastSample = offsetMillis;
    }

    private static Long percentile(List<Long> sorted, double percentile) {
        return sorted.isEmpty() ? null : sorted.get((int) Math.ceil(sorted.size() * percentile) - 1);
    }

    private static Group group(List<PlayerPing> players) {
        var known = players.stream().map(PlayerPing::pingMillis).filter(ping -> ping >= 0).sorted().toList();
        return new Group(players.size(), players.size() - known.size(), percentile(known, .50), percentile(known, .95), percentile(known, 1),
                (int) known.stream().filter(ping -> ping >= 250).count(), (int) known.stream().filter(ping -> ping >= 500).count(),
                (int) known.stream().filter(ping -> ping >= 1000).count(), (int) known.stream().filter(ping -> ping >= 2000).count());
    }

    public Map<String, Object> report(String reason) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("complete", reason.equals("completed") && !truncated && !failed && !unavailable && !samples.isEmpty());
        report.put("truncated", truncated);
        report.put("samples", List.copyOf(samples));
        report.put("targetCount", targets.size());
        report.put("targets", java.util.stream.IntStream.range(0, targets.size())
                .mapToObj(i -> new Target(i + 1, identities.get(targets.get(i)))).toList());
        report.put("targetNotes", "Row target=0 is the comparison baseline; targets 1..N follow the command's player order. "
                + "Selection is capture-local and follows UUID in memory across reconnects, not player names. "
                + "Targets are sampled before baseline players and have reserved identity slots. "
                + "Missing target rows are unobserved, not zero ping. Absence can mean offline, read failure or exhausted row capacity. "
                + "Baseline players are comparison players, not verified unaffected players. Targeting does not enable deeper packet instrumentation.");
        report.put("sampleIntervalMillis", 1000);
        report.put("maxPlayersPerSample", MAX_PLAYERS_PER_SAMPLE);
        report.put("maxIdentities", MAX_IDENTITIES);
        report.put("maxPlayerRows", MAX_ROWS);
        report.put("notes", "Public API cached application-level ping estimates, not fresh probes or TCP RTT. "
                + "Repeated values do not prove a new reply. No packet loss, throughput, retransmission or network-hop measurement. "
                + "Percentiles use nearest rank over known retained pings; thresholds are inclusive. "
                + "Player numbers are capture-local and cannot be matched across processes or captures. "
                + "Sampling gaps are real elapsed time; missed samples are not backfilled. "
                + "Omitted players and read failures limit coverage. No names, UUIDs, addresses or packet contents are exported.");
        return report;
    }
}
