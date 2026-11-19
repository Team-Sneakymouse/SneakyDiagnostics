package com.teamsneakymouse.sneakydiagnostics.entity;

/**
 * The census's wall-clock budget: {@link #BUDGET_NANOS_PER_SECOND} of slice time per wall second (counted from the
 * segment start), spread over the ticks still expected in that second, with no slice target above
 * {@link #MAX_SLICE_NANOS}. A lagging server ticks less often, so each tick gets a larger slice and the census keeps
 * its coverage per second instead of shrinking with TPS. The tick interval is taken from the gap since the last call
 * and never taken as shorter than 50 ms. Pure arithmetic over an injected clock; server thread only.
 */
final class SliceBudget {
    static final long BUDGET_NANOS_PER_SECOND = 50_000_000L;
    static final long MAX_SLICE_NANOS = 10_000_000L;
    static final long MIN_TICK_INTERVAL_NANOS = 50_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private final long origin;
    private long second = -1, used, lastCall, interval = MIN_TICK_INTERVAL_NANOS;
    private boolean called;

    SliceBudget(long originNanos) { this.origin = originNanos; }

    /** The slice target for a tick beginning at {@code now}; 0 when this second's budget is spent. */
    long next(long now) {
        if (called) interval = Math.max(MIN_TICK_INTERVAL_NANOS, now - lastCall);     // the last tick interval
        called = true;
        lastCall = now;
        long offset = Math.max(0, now - origin);
        long current = offset / SECOND;
        if (current != second) {
            second = current;
            used = 0;
        }
        long remaining = BUDGET_NANOS_PER_SECOND - used;
        if (remaining <= 0) return 0;
        long wallLeft = (current + 1) * SECOND - offset;
        long ticks = Math.max(1, (wallLeft + interval - 1) / interval);
        return Math.min(MAX_SLICE_NANOS, remaining / ticks);
    }

    /** Charges a slice (overruns included) to the second it began in. */
    void used(long nanos) { used += Math.max(0, nanos); }
}
