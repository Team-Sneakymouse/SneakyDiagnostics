package com.teamsneakymouse.sneakydiagnostics.capture;

/** Parses command options before any collector state is changed. */
public final class CaptureArguments {
    public record Start(int seconds, String world) {}
    private CaptureArguments() {}

    public static Start start(String collector, String[] options) {
        int seconds = collector.equals("entities") ? 120 : 60;
        String world = null;
        if (collector.equals("entities")) {
            if (options.length > 2) throw new IllegalArgumentException("entities start [world] [seconds], or entities start [seconds]");
            if (options.length == 1) {
                if (options[0].matches("[+-]?\\d+")) seconds = seconds(options[0]);
                else world = options[0];
            } else if (options.length == 2) {
                world = options[0];
                seconds = seconds(options[1]);
            }
        } else {
            int max = collector.equals("all") ? 2 : 1;
            if (options.length > max) throw new IllegalArgumentException(collector + " start [seconds]" + (max == 2 ? " [world]" : ""));
            if (options.length > 0) seconds = seconds(options[0]);
            if (options.length == 2) world = options[1];
        }
        return new Start(seconds, world);
    }

    public static int seconds(String text) {
        try {
            int seconds = Integer.parseInt(text);
            if (seconds >= 1 && seconds <= 1800) return seconds;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Duration must be an integer from 1 to 1800 seconds");
    }
}
