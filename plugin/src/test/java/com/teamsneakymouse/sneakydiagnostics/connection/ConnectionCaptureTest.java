package com.teamsneakymouse.sneakydiagnostics.connection;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionCaptureTest {
    @SuppressWarnings("unchecked")
    private static List<ConnectionCapture.Sample> samples(ConnectionCapture capture) {
        return (List<ConnectionCapture.Sample>) capture.report("completed").get("samples");
    }
    private static ConnectionCapture.Observation observation(long id, long ping) {
        return new ConnectionCapture.Observation(new UUID(0, id), ping, "survival");
    }

    @Test void capturesSimultaneousTwoSecondLatencyWhileKeepingPlayerHistoryAndUnknownSeparate() {
        var capture = new ConnectionCapture(60);
        capture.sample(0, List.of(observation(1, 30), observation(2, -1), observation(3, 80)), p -> p);
        capture.sample(1000, List.of(observation(3, 2500), observation(1, 2000), observation(2, -1)), p -> p);
        var first = samples(capture).getFirst();
        var spike = samples(capture).getLast();
        assertEquals(1, first.unknownPings());
        assertEquals(30L, first.p50Millis());
        assertEquals(2, spike.atLeast2000ms());
        assertEquals(2500L, spike.p95Millis());
        assertEquals(2000L, spike.p50Millis());
        assertEquals(first.players().getFirst().player(), spike.players().get(1).player());
        assertEquals(false, capture.report("completed").get("complete"));
        String json = new Gson().toJson(capture.report("completed"));
        assertFalse(json.contains(new UUID(0, 1).toString()));
        assertFalse(json.contains("\"identity\""));
    }

    @Test void preservesGapsAndNeverReadsBeforeDueOrAfterDeadline() {
        var capture = new ConnectionCapture(10);
        var players = List.of(observation(1, 2000));
        capture.sample(100, players, p -> p);
        capture.sample(999, players, p -> { fail("Not due"); return p; });
        capture.sample(4000, players, p -> p);
        capture.sample(10_000, players, p -> { fail("Expired"); return p; });
        assertEquals(2, samples(capture).size());
        assertEquals(3900, samples(capture).getLast().sincePreviousSampleMillis());
        var detached = samples(capture);
        capture.sample(5000, players, p -> p);
        assertEquals(2, detached.size());
        assertEquals(false, capture.report("manual_stop").get("complete"));
    }

    @Test void boundsReadsAndRowsAndReportsOmissionsRatherThanBiasedCompleteCoverage() {
        var capture = new ConnectionCapture(1800);
        int[] reads = {0};
        var players = Collections.nCopies(600, observation(1, 10));
        capture.sample(0, players, p -> { reads[0]++; return p; });
        assertEquals(512, reads[0]);
        assertEquals(88, samples(capture).getFirst().omittedPlayers());
        for (int i = 1; i < 500; i++) capture.sample(i * 1000L, players, p -> { reads[0]++; return p; });
        assertEquals(ConnectionCapture.MAX_ROWS, reads[0]);
        assertEquals(600, samples(capture).getLast().omittedPlayers());
        assertEquals(true, capture.report("completed").get("truncated"));
        assertEquals(false, capture.report("completed").get("complete"));
    }

    @Test void boundsIdentityChurnAndDoesNotExportReadExceptions() {
        var capture = new ConnectionCapture(60);
        for (int i = 0; i < 8; i++) {
            var batch = new ArrayList<ConnectionCapture.Observation>();
            for (int j = 0; j < 512; j++) batch.add(observation(i * 512L + j, 10));
            capture.sample(i * 1000L, batch, p -> p);
        }
        capture.sample(8000, List.of(observation(5000, 10)), p -> p);
        assertEquals(1, samples(capture).getLast().omittedPlayers());
        capture.sample(9000, List.of(observation(0, 10)), p -> { throw new IllegalStateException("secret-player-address"); });
        assertEquals(1, samples(capture).getLast().failedReads());
        assertFalse(new Gson().toJson(capture.report("completed")).contains("secret-player-address"));
    }

    @Test void emptyPopulationIsDistinctFromUnknownPing() {
        var capture = new ConnectionCapture(1);
        capture.sample(0, List.<ConnectionCapture.Observation>of(), p -> p);
        assertNull(samples(capture).getFirst().p50Millis());
        assertEquals(true, capture.report("completed").get("complete"));
    }

    @Test void selectedPlayersHaveSeparateSummariesAndStableNumberedMarkersWithoutIdentifiers() {
        var alice = observation(100, 2000);
        var bob = observation(101, -1);
        var control = observation(102, 30);
        var capture = new ConnectionCapture(60, List.of(bob.identity(), alice.identity()));
        capture.sample(0, List.of(control, alice, bob), p -> p,
                id -> id.equals(alice.identity()) ? alice : id.equals(bob.identity()) ? bob : null);
        var sample = samples(capture).getFirst();
        assertEquals(3, sample.players().size(), "Priority players must not be sampled twice");
        assertEquals(2, sample.targets().sampledPlayers());
        assertEquals(1, sample.targets().unknownPings());
        assertEquals(2000L, sample.targets().p95Millis());
        assertEquals(1, sample.targets().atLeast2000ms());
        assertEquals(1, sample.baseline().sampledPlayers());
        assertEquals(30L, sample.baseline().p95Millis());
        assertEquals(List.of(1, 2, 0), sample.players().stream().map(ConnectionCapture.PlayerPing::target).toList());
        String json = new Gson().toJson(capture.report("completed"));
        assertFalse(json.contains(alice.identity().toString()));
        assertFalse(json.contains(bob.identity().toString()));

        // Reconnect with a new live object but the same UUID retains the selection and anonymous ID.
        var rejoined = observation(100, 2500);
        capture.sample(1000, List.of(control), p -> p, id -> null);
        assertEquals(0, samples(capture).getLast().targets().sampledPlayers());
        capture.sample(2000, List.of(control, rejoined), p -> p, id -> id.equals(rejoined.identity()) ? rejoined : null);
        assertEquals(2, samples(capture).getLast().players().getFirst().target());
        assertEquals(sample.players().get(1).player(), samples(capture).getLast().players().getFirst().player());
    }

    @Test void prioritizesTargetsBeyondThePopulationReadCap() {
        var population = new ArrayList<ConnectionCapture.Observation>();
        for (int i = 0; i < 600; i++) population.add(observation(i, 30));
        var target = population.getLast();
        var capture = new ConnectionCapture(60, List.of(target.identity()));
        int[] reads = {0};
        capture.sample(0, population, p -> { reads[0]++; return p; }, id -> target);
        var sample = samples(capture).getFirst();
        assertEquals(512, reads[0]);
        assertEquals(88, sample.omittedPlayers());
        assertEquals(1, sample.players().getFirst().target());
        assertEquals(1, sample.targets().sampledPlayers());
        assertEquals(511, sample.baseline().sampledPlayers());
    }

    @Test void laterUntargetedCaptureDoesNotInheritPreviousSelection() {
        var target = observation(1, 2000);
        var targeted = new ConnectionCapture(60, List.of(target.identity()));
        targeted.sample(0, List.of(target), p -> p);
        var next = new ConnectionCapture(60);
        next.sample(0, List.of(target), p -> p);
        assertEquals(1, samples(targeted).getFirst().targets().sampledPlayers());
        assertEquals(0, samples(next).getFirst().targets().sampledPlayers());
        assertEquals(1, samples(next).getFirst().baseline().sampledPlayers());
    }
}
