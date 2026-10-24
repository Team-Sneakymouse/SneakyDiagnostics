package com.teamsneakymouse.sneakydiagnostics.integration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** File-name slugs for per-provider reports; the index {@code file} field stays authoritative. */
public final class ProviderFiles {
    private ProviderFiles() {}

    /** Lowercase, with every character outside {@code [a-z0-9._-]} replaced by {@code _}. */
    public static String slug(String plugin) {
        return plugin.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    /** One unique slug per name, in order; later names that collapse to the same slug get {@code ~2}, {@code ~3}. */
    public static List<String> slugs(List<String> plugins) {
        var seen = new HashMap<String, Integer>();
        var result = new ArrayList<String>(plugins.size());
        for (String plugin : plugins) {
            String slug = slug(plugin);
            int count = seen.merge(slug, 1, Integer::sum);
            // '~' is never produced by slug(), so a suffixed name cannot collide with another base slug.
            result.add(count == 1 ? slug : slug + "~" + count);
        }
        return List.copyOf(result);
    }
}
