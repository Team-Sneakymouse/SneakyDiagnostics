package com.teamsneakymouse.sneakydiagnostics.timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates exact tick durations into wall-clock seconds without retaining every tick.
 * {@code firstTick/lastTick} are Paper's tick numbers ({@code ServerTickEndEvent.getTickNumber()}, the server's
 * {@code tickCount}); {@code firstCurrentTick/lastCurrentTick} are {@code Bukkit.getCurrentTick()} at the same tick ends,
 * the counter scheduler, listener and probe records carry. The two differ by a constant that grows whenever the server
 * pauses while empty (that loop runs scheduled tasks and advances the current tick, but no tick ends), so records must
 * be matched against the current-tick range, never against the tick number. Null when not recorded.
 */
public final class TickTimeline {
    public record Window(long fromMillis, long toMillis, int firstTick, int lastTick, long ticks,
                  double meanMspt, double minMspt, double maxMspt, long over50ms, long over100ms,
                  Integer firstCurrentTick, Integer lastCurrentTick) {}
    /** For {@link #record(long, int, int, double)}: the current tick was not read. */
    public static final int NO_CURRENT_TICK = Integer.MIN_VALUE;
    private final List<Window> windows = new ArrayList<>();
    private long second = -1, count, over50, over100;
    private long fromMillis, lastMillis;
    private int firstTick, lastTick, firstCurrentTick, lastCurrentTick;
    private double sum, min = Double.POSITIVE_INFINITY, max;

    public void record(long millis, int tick, double duration) { record(millis, tick, NO_CURRENT_TICK, duration); }

    public void record(long millis, int tick, int currentTick, double duration) {
        long bucket = millis / 1000;
        if (bucket != second) {
            flush();
            second = bucket;
            fromMillis = bucket * 1000;
            firstTick = tick;
            firstCurrentTick = currentTick;
        }
        count++;
        lastTick = tick;
        lastCurrentTick = currentTick;
        lastMillis = millis;
        sum += duration;
        min = Math.min(min, duration);
        max = Math.max(max, duration);
        if (duration > 50) over50++;
        if (duration > 100) over100++;
    }

    public List<Window> finish() { flush(); return List.copyOf(windows); }

    private void flush() {
        if (count == 0) return;
        boolean current = firstCurrentTick != NO_CURRENT_TICK && lastCurrentTick != NO_CURRENT_TICK;
        windows.add(new Window(fromMillis, lastMillis, firstTick, lastTick, count,
                sum / count, min, max, over50, over100,
                current ? firstCurrentTick : null, current ? lastCurrentTick : null));
        count = over50 = over100 = 0;
        sum = max = 0;
        min = Double.POSITIVE_INFINITY;
    }
}
