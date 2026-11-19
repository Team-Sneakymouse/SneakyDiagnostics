package com.teamsneakymouse.sneakydiagnostics.entity;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.bukkit.Chunk;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SliceBudgetTest {
    private static final long MS = 1_000_000L, SECOND = 1_000_000_000L;

    /** Ticks at a fixed interval for one second: the slices it hands out and what they add up to. */
    private static long[] second(long intervalMs) {
        var budget = new SliceBudget(0);
        int ticks = (int) (1000 / intervalMs);
        long[] slices = new long[ticks];
        for (int i = 0; i < ticks; i++) {
            long slice = budget.next(i * intervalMs * MS);
            slices[i] = slice;
            budget.used(slice);
        }
        return slices;
    }

    @Test void aFullSpeedServerGetsAboutTwoAndAHalfMillisecondsPerTick() {
        long[] slices = second(50);
        assertEquals(2_500_000L, slices[0]);
        assertEquals(SliceBudget.BUDGET_NANOS_PER_SECOND, Arrays.stream(slices).sum(), 1_000);
    }

    @Test void aLaggingServerGetsLargerSlicesAndTheSameBudgetPerSecond() {
        long[] slices = second(125);                         // 8 TPS, as in capture 01a1f23a
        assertEquals(8, slices.length);
        assertTrue(slices[1] >= 6_000_000L && slices[1] <= 7_000_000L, "about 50 ms / 8: " + slices[1]);
        assertEquals(SliceBudget.BUDGET_NANOS_PER_SECOND, Arrays.stream(slices).sum(), 1_000);
    }

    @Test void noSliceTargetExceedsTenMilliseconds() {
        long[] slices = second(500);                          // 2 TPS
        assertTrue(Arrays.stream(slices).allMatch(slice -> slice <= SliceBudget.MAX_SLICE_NANOS));
        assertEquals(SliceBudget.MAX_SLICE_NANOS, slices[1], "the first tick has no interval yet; the second knows it is slow");
    }

    @Test void anOverrunSpendsTheRestOfThatSecondAndTheNextSecondStartsFresh() {
        var budget = new SliceBudget(0);
        assertTrue(budget.next(0) > 0);
        budget.used(60 * MS);                                 // a non-preemptible Paper call overran the whole budget
        assertEquals(0, budget.next(50 * MS));
        assertEquals(0, budget.next(100 * MS));
        assertTrue(budget.next(SECOND + 10 * MS) > 0, "a new wall second has a new budget");
    }

    @Test void chunksNearestToPlayersAreVisitedFirst() {
        Chunk far = chunk(100, 100), near = chunk(1, 0), middle = chunk(10, -10), otherFar = chunk(-200, 0);
        Chunk[] loaded = {far, near, otherFar, middle};
        var humans = List.of(new EntitySample.Human(UUID.randomUUID(), "world", 20.5, 3.0),
                new EntitySample.Human(UUID.randomUUID(), "nether", 1600.0, 1600.0));
        assertArrayEquals(new Chunk[]{near, middle, far, otherFar},
                EntityCensusCollector.nearestPlayersFirst(loaded, "world", humans));
        assertSame(loaded, EntityCensusCollector.nearestPlayersFirst(loaded, "end", humans), "no human: original order");
    }

    private static Chunk chunk(int x, int z) {
        return (Chunk) Proxy.newProxyInstance(Chunk.class.getClassLoader(), new Class<?>[]{Chunk.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getX" -> x;
            case "getZ" -> z;
            case "toString" -> "chunk " + x + "," + z;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new AssertionError(method.getName());
        });
    }
}
