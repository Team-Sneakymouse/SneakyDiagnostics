package com.teamsneakymouse.sneakydiagnostics.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CensusSummary {
    record Group(String world, int chunkX, int chunkZ, String type, List<String> ownerHints) {}
    record Row(Group group, long entities, long trackedHumanSubscriptions, long untrackedEntities) {}

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
