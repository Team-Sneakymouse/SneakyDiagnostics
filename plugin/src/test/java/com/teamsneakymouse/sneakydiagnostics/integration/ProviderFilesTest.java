package com.teamsneakymouse.sneakydiagnostics.integration;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderFilesTest {
    @Test void slugsAreLowercaseAndReplaceEveryCharacterOutsideTheSafeSet() {
        assertEquals("magicspells", ProviderFiles.slug("MagicSpells"));
        assertEquals("my_plugin_2", ProviderFiles.slug("My Plugin 2"));
        assertEquals("a.b-c_d9", ProviderFiles.slug("A.b-c_d9"));
        assertEquals("..___", ProviderFiles.slug("../\\:"));
        assertEquals("__", ProviderFiles.slug("éè"));
        assertEquals("a_b", ProviderFiles.slug("a😀b"));
    }

    @Test void collidingSlugsGetTildeSuffixesInRegistrationOrder() {
        assertEquals(List.of("a_b", "a_b~2", "a_b~3", "other"), ProviderFiles.slugs(List.of("A b", "a_b", "a/b", "Other")));
        assertEquals(List.of(), ProviderFiles.slugs(List.of()));
    }

    @Test void suffixedSlugsNeverCollideWithBaseSlugs() {
        var slugs = ProviderFiles.slugs(List.of("a", "A", "a~2", "a_2"));
        assertEquals(List.of("a", "a~2", "a_2", "a_2~2"), slugs);
        assertEquals(slugs.size(), new HashSet<>(slugs).size());
    }
}
