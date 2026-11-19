package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketProbeTest {
    private static final long SECOND = 1_000_000_000L;
    /** Stand-ins for packet classes: the registry only needs distinct classes. */
    private static final Class<?> MOVE = Integer.class, DATA = Long.class, SOUND = Short.class, BUNDLE = Byte.class, USE_ITEM = Double.class,
            SWING = Float.class;
    private final AtomicLong clock = new AtomicLong(1_000 * SECOND);
    private String id;

    @AfterEach void cleanup() { if (id != null) { try { PacketProbe.stop(id); } catch (IllegalStateException ignored) { } } }

    private PacketProbe.Session start(int seconds) {
        id = "packet-test";
        var session = new PacketProbe.Session(id, clock.get(), seconds, clock::get, Thread.currentThread());
        PacketProbe.start(session);
        return session;
    }
    private static void send(PacketProbe.Session session, Class<?> type, long started, long nanos) {
        session.sent(PacketProbe.slot(type), started, started + nanos, session.sendBegin());
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Map<String, Object> map, String key) { return (Map<String, Object>) map.get(key); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Map<String, Object> map, String key) { return (List<Map<String, Object>>) map.get(key); }
    private static Map<String, Object> row(List<Map<String, Object>> rows, String field, String value) {
        return rows.stream().filter(r -> value.equals(r.get(field))).findFirst().orElseThrow(() -> new AssertionError(value + " missing in " + rows));
    }

    @Test void outboundSendsAreCountedPerClassWithServerAndOtherThreadsApartAndRankedByTime() throws Exception {
        var session = start(60);
        assertSame(session, PacketProbe.active());
        assertSame(session, PacketProbe.current());
        long t = clock.get();
        send(session, MOVE, t, 2_000);
        send(session, MOVE, t + SECOND, 3_000);
        send(session, DATA, t + SECOND, 10_000);
        var other = new Thread(() -> {
            assertNull(PacketProbe.active(), "active() is server-thread only");
            assertSame(session, PacketProbe.current());
            session.sentOffThread(PacketProbe.slot(MOVE), t, t + 7_000);
        });
        other.start();
        other.join();
        var outbound = map(PacketProbe.stop(id), "outbound");
        var classes = rows(outbound, "classes");
        assertEquals(DATA.getName(), classes.get(0).get("packetClass"), "largest server-thread time first");
        var move = row(classes, "packetClass", MOVE.getName());
        assertEquals(3L, move.get("sends"));
        assertEquals(2L, move.get("serverThreadSends"));
        assertEquals(1L, move.get("otherThreadSends"));
        assertEquals(5_000L, move.get("totalNanos"), "server-thread time only");
        assertEquals(7_000L, move.get("otherThreadNanos"));
        assertEquals(3_000L, move.get("maxNanos"));
        assertEquals(2L, ((List<?>) move.get("histogramLog2")).stream().mapToLong(v -> (Long) v).sum());
        assertEquals(4L, outbound.get("sends"));
        assertEquals(3L, outbound.get("serverThreadSends"));
        assertEquals(15_000L, outbound.get("serverThreadNanos"));
        assertEquals(15_000L, outbound.get("classNanos"));
        assertEquals(true, outbound.get("complete"));
        var series = map(outbound, "series");
        assertEquals(List.of(1L, 2L), ((List<?>) series.get("sends")).subList(0, 2));
        assertEquals(List.of(2_000L, 13_000L), ((List<?>) series.get("nanos")).subList(0, 2));
        assertEquals(61, ((List<?>) series.get("nanos")).size());
        assertEquals(DATA.getName(), rows(series, "classes").get(0).get("packetClass"));
    }

    @Test void aBundleCountsOnceAndItsSubPacketsPerClass() {
        var session = start(60);
        long t = clock.get();
        send(session, BUNDLE, t, 1_000);
        session.bundle();
        session.bundled(PacketProbe.slot(MOVE));
        session.bundled(PacketProbe.slot(DATA));
        session.bundled(PacketProbe.slot(DATA));
        var outbound = map(PacketProbe.stop(id), "outbound");
        assertEquals(1L, outbound.get("sends"));
        assertEquals(1L, outbound.get("bundles"));
        assertEquals(3L, outbound.get("bundledPackets"));
        var classes = rows(outbound, "classes");
        assertEquals(1L, row(classes, "packetClass", BUNDLE.getName()).get("sends"));
        assertEquals(2L, row(classes, "packetClass", DATA.getName()).get("bundled"));
        assertEquals(0L, row(classes, "packetClass", DATA.getName()).get("sends"), "sub-packets are not sends");
    }

    @Test void nestedSendsCountOnceTowardsTheNonOverlappingTotal() {
        var session = start(60);
        long t = clock.get();
        int outer = session.sendBegin();
        send(session, DATA, t + 100, 400);                                // a plugin sends inside another send
        session.sent(PacketProbe.slot(MOVE), t, t + 1_000, outer);
        var outbound = map(PacketProbe.stop(id), "outbound");
        assertEquals(1_000L, outbound.get("serverThreadNanos"), "only outermost sends add up");
        assertEquals(1_400L, outbound.get("classNanos"), "class times are inclusive and may overlap");
        assertEquals(1L, outbound.get("nestedSends"));
    }

    @Test void sendsAreAttributedToTheInnermostCauseAndBroadcastsGiveFanOut() {
        var session = start(60);
        long t = clock.get();
        int broadcast = session.enter(PacketProbe.BROADCAST, 0);
        int again = session.enter(PacketProbe.BROADCAST, 0);               // an overload funnelling into another: one broadcast
        for (int i = 0; i < 3; i++) send(session, SOUND, t, 1_000);
        session.exit(again);
        session.exit(broadcast);
        int particles = session.enter(PacketProbe.PARTICLES, 0);
        send(session, SOUND, t, 500);
        session.exit(particles);
        send(session, SWING, t, 200);                                       // no cause: other
        var report = PacketProbe.stop(id);
        var causes = map(report, "causes");
        var rows = rows(causes, "rows");
        assertEquals(PacketProbe.CAUSES.length, rows.size(), "every cause is listed");
        var b = row(rows, "cause", "broadcast");
        assertEquals(3L, b.get("packets"));
        assertEquals(1L, b.get("broadcasts"));
        assertEquals(3_000L, b.get("totalNanos"));
        assertEquals(1L, row(rows, "cause", "particles").get("packets"));
        var other = row(rows, "cause", "other");
        assertEquals(1L, other.get("packets"));
        assertFalse(other.containsKey("broadcasts"), "other has no call site");
        assertEquals(true, causes.get("complete"));
        var bSeries = row(rows(map(causes, "series"), "causes"), "cause", "broadcast");
        assertEquals(3L, ((List<?>) bSeries.get("packets")).getFirst());
    }

    @Test void trackerCausesAreGroupedByEntityTypeAndOwnerWithMetadataNestedInUpdate() {
        var session = start(60);
        long t = clock.get();
        int display = session.group(() -> new String[]{"ITEM_DISPLAY", "plugin:SneakyWartime"});
        int bot = session.group(() -> new String[]{"PLAYER", "(player)"});
        int update = session.enter(PacketProbe.TRACKER_UPDATE, display);
        send(session, MOVE, t, 1_000);
        int metadata = session.enter(PacketProbe.TRACKER_METADATA, display);
        send(session, DATA, t, 4_000);
        session.exit(metadata);
        session.exit(update);
        int spawn = session.enter(PacketProbe.TRACKER_SPAWN, bot);
        send(session, BUNDLE, t, 9_000);
        session.exit(spawn);
        int despawn = session.enter(PacketProbe.TRACKER_DESPAWN, bot);
        session.exit(despawn);                                              // a despawn that sent nothing still counts as a call
        var report = PacketProbe.stop(id);
        var tracker = map(report, "trackerCauses");
        var groups = rows(tracker, "groups");
        assertEquals("PLAYER", groups.get(0).get("entityType"), "largest send time first");
        var displays = row(groups, "owner", "plugin:SneakyWartime");
        assertEquals(1L, displays.get("entities"));
        assertEquals(2L, displays.get("packets"));
        assertEquals(2L, displays.get("broadcasts"));
        assertEquals(5_000L, displays.get("totalNanos"));
        var byCause = map(displays, "causes");
        assertEquals(1L, map(byCause, "tracker.update").get("packets"));
        assertEquals(4_000L, map(byCause, "tracker.metadata").get("totalNanos"));
        var bots = map(groups.get(0), "causes");
        assertEquals(1L, map(bots, "tracker.despawn").get("broadcasts"));
        assertEquals(0L, map(bots, "tracker.despawn").get("packets"));
        assertEquals(2L, tracker.get("entities"));
        assertEquals(true, tracker.get("complete"));
        var causes = rows(map(report, "causes"), "rows");
        assertEquals(1L, row(causes, "cause", "tracker.update").get("packets"), "metadata sends are not update sends");
        assertEquals(1L, row(causes, "cause", "tracker.metadata").get("packets"));
    }

    @Test void trackerGroupsAreBoundedAndLabelErrorsCounted() {
        var session = start(60);
        for (int i = 0; i < PacketProbe.MAX_GROUPS + 5; i++) {
            int n = i;
            session.group(() -> new String[]{"ZOMBIE", "pdc:p" + n});
        }
        assertEquals(PacketProbe.OVERFLOW, session.group(() -> { throw new IllegalStateException("no world"); }));
        session.spawnLookup(2_000, true, 1);
        session.spawnLookup(1_000, false, 0);
        var tracker = map(PacketProbe.stop(id), "trackerCauses");
        assertEquals(6L, tracker.get("overflowEntities"), "five overflowed labels plus the unreadable one, all past the cap");
        assertEquals(1L, tracker.get("labelErrors"));
        assertEquals(false, tracker.get("complete"));
        assertEquals(2L, tracker.get("spawnLookups"));
        assertEquals(1L, tracker.get("spawnsAttributed"));
        assertEquals(3_000L, tracker.get("spawnLookupNanos"));
        assertEquals(1L, tracker.get("passThroughFrames"));
    }

    @Test void inboundPacketsAreTimedPerClassWithBetweenTickHandlingApart() throws Exception {
        var session = start(60);
        long t = clock.get();
        session.handled(PacketProbe.slot(USE_ITEM), t, t + 50_000);
        boolean previous = session.betweenTicks(true);
        session.handled(PacketProbe.slot(USE_ITEM), t + SECOND, t + SECOND + 10_000);
        session.betweenTicks(previous);
        session.handled(PacketProbe.slot(SWING), t, t + 1_000);
        var other = new Thread(session::handledOffThread);
        other.start();
        other.join();
        var inbound = map(PacketProbe.stop(id), "inbound");
        var classes = rows(inbound, "classes");
        var use = classes.get(0);
        assertEquals(USE_ITEM.getName(), use.get("packetClass"));
        assertEquals(2L, use.get("packets"));
        assertEquals(60_000L, use.get("totalNanos"));
        assertEquals(50_000L, use.get("maxNanos"));
        assertEquals(1L, use.get("betweenTicksPackets"));
        assertEquals(10_000L, use.get("betweenTicksNanos"));
        assertEquals(3L, inbound.get("packets"));
        assertEquals(1L, inbound.get("offThreadPackets"));
        assertEquals(false, inbound.get("complete"), "untimed handling makes inbound incomplete");
        var series = map(inbound, "series");
        assertEquals(List.of(51_000L, 10_000L), ((List<?>) series.get("nanos")).subList(0, 2));
        assertEquals(2, rows(series, "classes").size(), "every inbound class has a series");
    }

    @Test void onlyTheLargestOutboundClassesGetASeries() {
        var session = start(5);
        Class<?>[] types = {String.class, Integer.class, Long.class, Short.class, Byte.class, Double.class, Float.class, Character.class,
                Boolean.class, Object.class, Number.class, Thread.class, Runnable.class, Map.class, List.class, java.util.Set.class,
                java.util.ArrayList.class, java.util.HashMap.class, java.util.HashSet.class, java.util.LinkedList.class, java.util.TreeMap.class,
                java.util.TreeSet.class, java.util.ArrayDeque.class, java.util.Optional.class, java.util.UUID.class, java.time.Instant.class,
                java.time.Duration.class, java.math.BigDecimal.class, java.math.BigInteger.class, StringBuilder.class, Exception.class,
                RuntimeException.class, Error.class, Class.class};
        long t = clock.get();
        for (int i = 0; i < types.length; i++) send(session, types[i], t, 100 + i);
        var outbound = map(PacketProbe.stop(id), "outbound");
        assertEquals(types.length, rows(outbound, "classes").size());
        var series = rows(map(outbound, "series"), "classes");
        assertEquals(PacketProbe.SERIES_CLASSES, series.size());
        assertEquals(Class.class.getName(), series.getFirst().get("packetClass"), "the largest class first");
    }

    @Test void callsAfterTheDeadlineAreLeftOutAndCountedLate() {
        var session = start(1);
        long t = clock.get();
        send(session, MOVE, t + 2 * SECOND, 100);
        session.handled(PacketProbe.slot(USE_ITEM), t + 2 * SECOND, t + 2 * SECOND + 10);
        var report = PacketProbe.stop(id);
        assertEquals(1L, map(report, "outbound").get("lateSends"));
        assertEquals(0L, map(report, "outbound").get("sends"));
        assertEquals(1L, map(report, "inbound").get("latePackets"));
    }

    @Test void causeStackOverflowAndMismatchedFramesMakeCausesIncomplete() {
        var session = start(60);
        int[] frames = new int[PacketProbe.MAX_DEPTH + 1];
        for (int i = 0; i < frames.length; i++) frames[i] = session.enter(i % 2 == 0 ? PacketProbe.BROADCAST : PacketProbe.PARTICLES, 0);
        assertEquals(PacketProbe.NO_FRAME, frames[PacketProbe.MAX_DEPTH]);
        session.exit(frames[PacketProbe.MAX_DEPTH]);
        session.exit(frames[0]);                                            // skips the inner frames
        var causes = map(PacketProbe.stop(id), "causes");
        assertEquals(1L, causes.get("depthOverflow"));
        assertEquals(1L, causes.get("nestingErrors"));
        assertEquals(false, causes.get("complete"));
    }

    @Test void recordingIsSingleCaptureAndStopChecksTheId() {
        start(5);
        assertThrows(IllegalStateException.class, () -> PacketProbe.start(new PacketProbe.Session("other", clock.get(), 5, clock::get, Thread.currentThread())));
        assertThrows(IllegalStateException.class, () -> PacketProbe.stop("other"));
        assertTrue(PacketProbe.status().startsWith("recording packet-test"));
        PacketProbe.stop(id);
        assertEquals("available, idle", PacketProbe.status());
        assertNull(PacketProbe.current());
        assertThrows(IllegalArgumentException.class, () -> new PacketProbe.Session("bad", 0, 0, clock::get, Thread.currentThread()));
    }

    @Test void stoppingOffTheServerThreadDiscardsTheCounts() throws Exception {
        var session = start(5);
        send(session, MOVE, clock.get(), 10);
        Map<?, ?>[] result = new Map<?, ?>[1];
        var other = new Thread(() -> result[0] = PacketProbe.stop(id));
        other.start();
        other.join();
        assertNotNull(result[0].get("error"));
        assertFalse(result[0].containsKey("outbound"));
        assertNull(PacketProbe.current());
    }

    @Test void classSlotsAreStablePerClass() {
        assertEquals(PacketProbe.slot(MOVE), PacketProbe.slot(MOVE));
        assertNotEquals(PacketProbe.slot(MOVE), PacketProbe.slot(DATA));
        assertNotEquals(PacketProbe.OVERFLOW, PacketProbe.slot(MOVE));
    }
}
