package com.teamsneakymouse.sneakydiagnostics.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CensusSummary {
    record Group(String world, int chunkX, int chunkZ, String type, List<String> ownerHints) {}
    record Row(Group group, long entities, long trackedHumanSubscriptions, long untrackedEntities) {}
    record Section(String world, int chunkX, int sectionY, int chunkZ, String type) {}
    record SectionRow(Section section, long entities, long trackedHumanSubscriptions) {}

    static List<SectionRow> sections(List<EntitySample> samples) {
        Map<Section, long[]> totals = new LinkedHashMap<>();
        for (var sample : samples) {
            var key = new Section(sample.world(), sample.chunkX(), (int) Math.floor(sample.y() / 16), sample.chunkZ(), sample.type());
            var values = totals.computeIfAbsent(key, ignored -> new long[2]);
            values[0]++;
            values[1] += sample.trackedHumans();
        }
        List<SectionRow> rows = new ArrayList<>();
        totals.forEach((key, count) -> rows.add(new SectionRow(key, count[0], count[1])));
        rows.sort(Comparator.comparingLong(SectionRow::entities).reversed().thenComparing(row -> row.section().toString()));
        return rows;
    }

    static List<Row> summarize(List<EntitySample> samples) {
        Map<Group, long[]> totals = new LinkedHashMap<>();
        for (var sample : samples) {
            var key = new Group(sample.world(), sample.chunkX(), sample.chunkZ(), sample.type(), sample.ownerHints());
            var values = totals.computeIfAbsent(key, ignored -> new long[3]);
            values[0]++;
            values[1] += sample.trackedHumans();
            if (sample.trackedPlayers() == 0) values[2]++;
        }
        List<Row> result = new ArrayList<>();
        totals.forEach((key, values) -> result.add(new Row(key, values[0], values[1], values[2])));
        result.sort(Comparator.comparingLong(Row::trackedHumanSubscriptions).reversed()
                .thenComparing(Comparator.comparingLong(Row::entities).reversed())
                .thenComparing(row -> row.group().toString()));
        return result;
    }
}
