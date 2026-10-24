package com.teamsneakymouse.sneakydiagnostics.connection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Resolves the entire selection before starting a capture; player names are never retained. */
public final class ConnectionArguments {
    public record Start(int seconds, List<UUID> targets) {}
    private ConnectionArguments() {}

    public static Start start(String[] options, Function<String, UUID> onlinePlayer) {
        int seconds = 60, firstPlayer = 0;
        if (options.length > 0 && options[0].matches("[+-]?\\d+")) {
            try { seconds = Integer.parseInt(options[0]); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Duration must be an integer from 1 to 1800 seconds"); }
            firstPlayer = 1;
        }
        if (seconds < 1 || seconds > 1800) throw new IllegalArgumentException("Duration must be an integer from 1 to 1800 seconds");
        if (options.length - firstPlayer > ConnectionCapture.MAX_TARGETS)
            throw new IllegalArgumentException("Select at most " + ConnectionCapture.MAX_TARGETS + " target players");
        var targets = new ArrayList<UUID>();
        for (int i = firstPlayer; i < options.length; i++) {
            UUID id = onlinePlayer.apply(options[i]);
            if (id == null) throw new IllegalArgumentException("Unknown or offline player: " + options[i] + ". Use exact online names separated by spaces.");
            if (targets.contains(id)) throw new IllegalArgumentException("Duplicate target player: " + options[i]);
            targets.add(id);
        }
        return new Start(seconds, List.copyOf(targets));
    }
}
