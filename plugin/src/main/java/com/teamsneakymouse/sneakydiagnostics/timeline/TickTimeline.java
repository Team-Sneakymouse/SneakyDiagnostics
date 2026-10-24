package com.teamsneakymouse.sneakydiagnostics.timeline;

import java.util.ArrayList;
import java.util.List;

/** Aggregates exact tick durations into wall-clock seconds without retaining every tick. */
public final class TickTimeline {
    public record Window(long fromMillis, long toMillis, int firstTick, int lastTick, long ticks,
                  double meanMspt, double minMspt, double maxMspt, long over50ms, long over100ms) {}
    private final List<Window> windows = new ArrayList<>();
    private long second = -1, count, over50, over100;
    private long fromMillis, lastMillis;
    private int firstTick, lastTick;
    private double sum, min = Double.POSITIVE_INFINITY, max;

    public void record(long millis, int tick, double duration) {
        long bucket = millis / 1000;
        if (bucket != second) {
            flush();
            second = bucket;
            fromMillis = bucket * 1000;
            firstTick = tick;
        }
        count++;
        lastTick = tick;
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
        windows.add(new Window(fromMillis, lastMillis, firstTick, lastTick, count,
                sum / count, min, max, over50, over100));
        count = over50 = over100 = 0;
        sum = max = 0;
        min = Double.POSITIVE_INFINITY;
    }
}
