package com.teamsneakymouse.sneakydiagnostics.timeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entity spawns of one capture by spawn reason x entity type (x spawning plugin for {@link #ATTRIBUTED} reasons), with
 * the one-second windows they happened in. Pure Java; server thread only. Memory: at most {@value #MAX_KEYS} rows, each
 * with two growable arrays of the windows it had spawns in.
 */
public final class SpawnCounts {
    public static final int MAX_KEYS = 512;
    public static final int MAX_LABEL = 128;
    /** Reasons whose spawning plugin is looked up (a plugin called spawn/addEntity). */
    public static final Set<String> ATTRIBUTED = Set.of("CUSTOM", "DEFAULT");

    record Key(String reason, String entityType, String plugin) {}

    private static final class Row {
        long total, cancelled;
        long[] windows = new long[4], counts = new long[4];
        int size;

        void add(long window) {
            if (size > 0 && windows[size - 1] == window) { counts[size - 1]++; return; }
            if (size == windows.length) {
                windows = Arrays.copyOf(windows, size * 2);
                counts = Arrays.copyOf(counts, size * 2);
            }
            windows[size] = window;
            counts[size++] = 1;
        }
    }

    private final Map<Key, Row> rows = new HashMap<>();
    private long observed, overflow;

    /** One spawn event; {@code plugin} is null for reasons that are not attributed. Cancelled spawns have no window. */
    public void record(long window, String reason, String entityType, String plugin, boolean cancelled) {
        observed++;
        Key key = new Key(label(reason), label(entityType), plugin == null ? null : label(plugin));
        Row row = rows.get(key);
        if (row == null) {
            if (rows.size() >= MAX_KEYS) { overflow++; return; }
            rows.put(key, row = new Row());
        }
        if (cancelled) { row.cancelled++; return; }
        row.total++;
        row.add(Math.max(0, window));
    }

    /** Detached JDK values, rows by total (then cancelled) descending. */
    public Map<String, Object> export(long droppedOffThread) {
        List<Map.Entry<Key, Row>> ordered = new ArrayList<>(rows.entrySet());
        ordered.sort(Comparator.comparingLong((Map.Entry<Key, Row> e) -> e.getValue().total).reversed()
                .thenComparing(Comparator.comparingLong((Map.Entry<Key, Row> e) -> e.getValue().cancelled).reversed())
                .thenComparing(e -> e.getKey().toString()));
        List<Map<String, Object>> out = new ArrayList<>(ordered.size());
        for (var entry : ordered) {
            Key key = entry.getKey();
            Row row = entry.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reason", key.reason());
            m.put("entityType", key.entityType());
            if (key.plugin() != null) m.put("plugin", key.plugin());
            m.put("total", row.total);
            m.put("cancelled", row.cancelled);
            List<Object> windows = new ArrayList<>(row.size), counts = new ArrayList<>(row.size);
            for (int i = 0; i < row.size; i++) {
                windows.add(row.windows[i]);
                counts.add(row.counts[i]);
            }
            m.put("windows", windows);
            m.put("n", counts);
            out.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowMillis", 1000L);
        result.put("observed", observed);
        result.put("keys", (long) out.size());
        result.put("maxKeys", (long) MAX_KEYS);
        result.put("overflow", overflow);
        result.put("droppedOffThread", droppedOffThread);
        result.put("attributedReasons", ATTRIBUTED.stream().sorted().toList());
        result.put("complete", overflow == 0 && droppedOffThread == 0);
        result.put("rows", List.copyOf(out));
        return result;
    }

    private static String label(String value) {
        if (value == null) return "<unknown>";
        return value.length() <= MAX_LABEL ? value : value.substring(0, MAX_LABEL);
    }
}
