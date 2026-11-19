package com.teamsneakymouse.sneakydiagnostics.timeline;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpawnCountsTest {
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> report) { return (List<Map<String, Object>>) report.get("rows"); }

    @Test void countsPerReasonTypeAndPluginWithSparseWindows() {
        var spawns = new SpawnCounts();
        spawns.record(0, "CUSTOM", "ZOMBIE", "SneakyShrine", false);
        spawns.record(0, "CUSTOM", "ZOMBIE", "SneakyShrine", false);
        spawns.record(3, "CUSTOM", "ZOMBIE", "SneakyShrine", false);
        spawns.record(3, "CUSTOM", "ZOMBIE", "SneakyShrine", true);
        spawns.record(4, "NATURAL", "ZOMBIE", null, true);
        spawns.record(2, "CUSTOM", "ITEM_DISPLAY", "SneakyWartime", false);
        var report = spawns.export(0);
        assertEquals(6L, report.get("observed"));
        assertEquals(true, report.get("complete"));
        var shrine = rows(report).getFirst();
        assertEquals("SneakyShrine", shrine.get("plugin"));
        assertEquals(3L, shrine.get("total"));
        assertEquals(1L, shrine.get("cancelled"));
        assertEquals(List.of(0L, 3L), shrine.get("windows"));
        assertEquals(List.of(2L, 1L), shrine.get("n"));
        var natural = rows(report).stream().filter(row -> row.get("reason").equals("NATURAL")).findFirst().orElseThrow();
        assertFalse(natural.containsKey("plugin"), "only CUSTOM/DEFAULT spawns carry a plugin");
        assertEquals(0L, natural.get("total"));
        assertEquals(1L, natural.get("cancelled"), "a cancelled natural spawn is still reported");
        assertEquals(List.of(), natural.get("windows"));
    }

    @Test void keyCapAndOffThreadDropsMakeTheCountsIncomplete() {
        var spawns = new SpawnCounts();
        for (int i = 0; i < SpawnCounts.MAX_KEYS + 2; i++) spawns.record(0, "CUSTOM", "TYPE_" + i, "P", false);
        var report = spawns.export(1);
        assertEquals((long) SpawnCounts.MAX_KEYS, report.get("keys"));
        assertEquals(2L, report.get("overflow"));
        assertEquals(1L, report.get("droppedOffThread"));
        assertEquals(false, report.get("complete"));
    }
}
