package com.teamsneakymouse.sneakydiagnostics.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses command options before any collector state is changed. */
public final class CaptureArguments {
    public static final int MAX_PROVIDERS = 32;
    private static final String PLUGINS_OPTION = "plugins=";

    /** {@code plugins} holds exact registered names in supplied order; empty selects every provider. */
    public record Start(int seconds, String world, List<String> plugins) {
        public Start { plugins = List.copyOf(plugins); }
        public Start(int seconds, String world) { this(seconds, world, List.of()); }
    }
    private CaptureArguments() {}

    public static Start start(String collector, String[] options) { return start(collector, options, List.of()); }

    /** {@code registered} are the provider names that a plugin selection is validated against. */
    public static Start start(String collector, String[] options, List<String> registered) {
        int seconds = collector.equals("entities") ? 120 : 60;
        String world = null;
        List<String> plugins = List.of();
        if (collector.equals("entities")) {
            if (options.length > 2) throw new IllegalArgumentException("entities start [world] [seconds], or entities start [seconds]");
            if (options.length == 1) {
                if (isInteger(options[0])) seconds = seconds(options[0]);
                else world = options[0];
            } else if (options.length == 2) {
                world = options[0];
                seconds = seconds(options[1]);
            }
        } else if (collector.equals("plugins")) {
            int first = options.length > 0 && isInteger(options[0]) ? 1 : 0;
            if (first == 1) seconds = seconds(options[0]);
            plugins = providers(java.util.Arrays.asList(options).subList(first, options.length), registered);
        } else if (collector.equals("all")) {
            var positional = new ArrayList<String>();
            List<String> named = null;
            for (String option : options) {
                if (!option.toLowerCase(Locale.ROOT).startsWith(PLUGINS_OPTION)) { positional.add(option); continue; }
                if (named != null) throw new IllegalArgumentException("Give plugins=<provider>,<provider> only once");
                String list = option.substring(PLUGINS_OPTION.length());
                if (list.isEmpty()) throw new IllegalArgumentException("plugins= needs at least one provider name");
                named = List.of(list.split(",", -1));
            }
            if (positional.size() > 2) throw new IllegalArgumentException("all start [seconds] [world] [plugins=<provider>,<provider>]");
            int first = !positional.isEmpty() && isInteger(positional.getFirst()) ? 1 : 0;
            if (first == 1) seconds = seconds(positional.getFirst());
            if (positional.size() > first) world = positional.get(first);
            if (positional.size() > first + 1) throw new IllegalArgumentException("all start [seconds] [world] [plugins=<provider>,<provider>]");
            if (named != null) plugins = providers(named, registered);
        } else {
            if (options.length > 1) throw new IllegalArgumentException(collector + " start [seconds]");
            if (options.length > 0) seconds = seconds(options[0]);
        }
        return new Start(seconds, world, plugins);
    }

    /** Case-insensitive exact matching; an exact-case match wins, ambiguity, unknown and duplicate names are rejected. */
    static List<String> providers(List<String> names, List<String> registered) {
        if (names.size() > MAX_PROVIDERS) throw new IllegalArgumentException("Select at most " + MAX_PROVIDERS + " providers");
        var resolved = new ArrayList<String>();
        for (String name : names) {
            String match = registered.contains(name) ? name : null;
            if (match == null) {
                var matches = registered.stream().filter(candidate -> candidate.equalsIgnoreCase(name)).toList();
                if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous provider name: " + name + ". Use the exact registered case.");
                if (!matches.isEmpty()) match = matches.getFirst();
            }
            if (match == null) {
                throw new IllegalArgumentException("Unknown provider: " + name + ". Registered: "
                        + (registered.isEmpty() ? "none" : String.join(", ", registered)));
            }
            if (resolved.contains(match)) throw new IllegalArgumentException("Duplicate provider: " + name);
            resolved.add(match);
        }
        return List.copyOf(resolved);
    }

    /** Provider names for {@code plugins start}: registered, not yet typed, starting with the prefix. */
    public static List<String> completeProviders(List<String> registered, List<String> typed, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return registered.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower)
                && typed.stream().noneMatch(name::equalsIgnoreCase)).toList();
    }

    /** Completes {@code plugins=a,b,<partial>} for {@code all start}; returns the whole token. */
    public static List<String> completePluginsOption(List<String> registered, String token) {
        if (!token.toLowerCase(Locale.ROOT).startsWith(PLUGINS_OPTION)) return List.of();
        String list = token.substring(PLUGINS_OPTION.length());
        int comma = list.lastIndexOf(',');
        String head = token.substring(0, PLUGINS_OPTION.length() + comma + 1);
        var typed = List.of(list.substring(0, Math.max(comma, 0)).split(","));
        return completeProviders(registered, typed, list.substring(comma + 1)).stream().map(name -> head + name).toList();
    }

    private static boolean isInteger(String text) { return text.matches("[+-]?\\d+"); }

    public static int seconds(String text) {
        try {
            int seconds = Integer.parseInt(text);
            if (seconds >= 1 && seconds <= 1800) return seconds;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Duration must be an integer from 1 to 1800 seconds");
    }
}
