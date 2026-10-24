package com.teamsneakymouse.sneakydiagnostics.capture;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureArgumentsTest {
    @Test void existingWorldCommandAndNewTimedCapturesAreSupported() {
        assertEquals(new CaptureArguments.Start(120, "world"), CaptureArguments.start("entities", new String[]{"world"}));
        assertEquals(new CaptureArguments.Start(240, "world"), CaptureArguments.start("entities", new String[]{"world", "240"}));
        assertEquals(new CaptureArguments.Start(30, null), CaptureArguments.start("entities", new String[]{"30"}));
        assertEquals(new CaptureArguments.Start(60, null), CaptureArguments.start("all", new String[0]));
        assertEquals(new CaptureArguments.Start(90, "world"), CaptureArguments.start("all", new String[]{"90", "world"}));
    }

    @Test void malformedOrUnboundedDurationsAndExtraArgumentsAreRejected() {
        for (String value : new String[]{"0", "-1", "1801", "999999999999", "1.5", "world"}) {
            assertThrows(IllegalArgumentException.class, () -> CaptureArguments.seconds(value));
        }
        assertThrows(IllegalArgumentException.class, () -> CaptureArguments.start("timeline", new String[]{"60", "world"}));
        assertThrows(IllegalArgumentException.class, () -> CaptureArguments.start("entities", new String[]{"world", "60", "extra"}));
    }

    private static final List<String> REGISTERED = List.of("MagicSpells", "Voice", "SneakyWizards");

    private static CaptureArguments.Start plugins(String collector, String... options) {
        return CaptureArguments.start(collector, options, REGISTERED);
    }

    @Test void pluginsDurationIsOptionalWhenTheFirstOptionIsNotAnInteger() {
        assertEquals(new CaptureArguments.Start(60, null, List.of()), plugins("plugins"));
        assertEquals(new CaptureArguments.Start(30, null, List.of()), plugins("plugins", "30"));
        assertEquals(new CaptureArguments.Start(60, null, List.of("Voice")), plugins("plugins", "voice"));
        assertEquals(new CaptureArguments.Start(90, null, List.of("Voice", "MagicSpells")), plugins("plugins", "90", "VOICE", "magicspells"));
        assertEquals(new CaptureArguments.Start(1800, null, List.of("Voice")), plugins("plugins", "+1800", "Voice"));
    }

    @Test void pluginsRejectsBadDurationsUnknownDuplicateAndTooManyProviders() {
        assertThrows(IllegalArgumentException.class, () -> plugins("plugins", "0", "Voice"));
        assertThrows(IllegalArgumentException.class, () -> plugins("plugins", "1801"));
        assertThrows(IllegalArgumentException.class, () -> plugins("plugins", "Voice", "30"));
        var unknown = assertThrows(IllegalArgumentException.class, () -> plugins("plugins", "Voice", "Nope"));
        assertTrue(unknown.getMessage().contains("Nope"));
        assertThrows(IllegalArgumentException.class, () -> plugins("plugins", "Voice", "voice"));
        assertThrows(IllegalArgumentException.class, () -> plugins("plugins", "60", "Voice", "Voice"));
        assertThrows(IllegalArgumentException.class, () -> CaptureArguments.start("plugins", new String[]{"Voice"}, List.of()));
        var many = new java.util.ArrayList<String>();
        for (int i = 0; i < 33; i++) many.add("p" + i);
        assertEquals(32, CaptureArguments.start("plugins", many.subList(0, 32).toArray(String[]::new), many).plugins().size());
        assertThrows(IllegalArgumentException.class, () -> CaptureArguments.start("plugins", many.toArray(String[]::new), many));
    }

    @Test void caseDifferingRegisteredNamesNeedTheExactCase() {
        var registered = List.of("Foo", "foo");
        assertEquals(List.of("foo"), CaptureArguments.start("plugins", new String[]{"foo"}, registered).plugins());
        assertEquals(List.of("Foo", "foo"), CaptureArguments.start("plugins", new String[]{"Foo", "foo"}, registered).plugins());
        assertThrows(IllegalArgumentException.class, () -> CaptureArguments.start("plugins", new String[]{"FOO"}, registered));
    }

    @Test void allAcceptsOptionalDurationWorldAndPluginsInAnyOrder() {
        assertEquals(new CaptureArguments.Start(60, null, List.of("Voice", "SneakyWizards")), plugins("all", "plugins=voice,sneakywizards"));
        assertEquals(new CaptureArguments.Start(90, "world", List.of("Voice")), plugins("all", "90", "world", "PLUGINS=Voice"));
        assertEquals(new CaptureArguments.Start(90, null, List.of("Voice")), plugins("all", "plugins=Voice", "90"));
        assertEquals(new CaptureArguments.Start(60, "world", List.of()), plugins("all", "world"));
        assertEquals(new CaptureArguments.Start(60, null, List.of()), plugins("all"));
    }

    @Test void allRejectsMalformedPluginsOptions() {
        for (String[] options : new String[][]{{"plugins="}, {"plugins=Voice,"}, {"plugins=,Voice"}, {"plugins=Voice,Voice"}, {"plugins=voice,VOICE"},
                {"plugins=Nope"}, {"plugins=Voice", "plugins=MagicSpells"}, {"60", "world", "extra", "plugins=Voice"}, {"world", "60"}}) {
            assertThrows(IllegalArgumentException.class, () -> plugins("all", options), String.join(" ", options));
        }
    }

    @Test void otherCollectorsRejectProviderOptions() {
        assertThrows(IllegalArgumentException.class, () -> plugins("timeline", "60", "Voice"));
        assertThrows(IllegalArgumentException.class, () -> plugins("spark", "plugins=Voice"));
    }

    @Test void completionOffersUnusedRegisteredNamesCaseInsensitively() {
        assertEquals(List.of("MagicSpells", "Voice", "SneakyWizards"), CaptureArguments.completeProviders(REGISTERED, List.of(), ""));
        assertEquals(List.of("MagicSpells"), CaptureArguments.completeProviders(REGISTERED, List.of("60"), "ma"));
        assertEquals(List.of("Voice"), CaptureArguments.completeProviders(REGISTERED, List.of("magicspells"), "v"));
        assertEquals(List.of(), CaptureArguments.completeProviders(REGISTERED, List.of("voice"), "vo"));
        assertEquals(List.of("plugins=MagicSpells", "plugins=Voice", "plugins=SneakyWizards"), CaptureArguments.completePluginsOption(REGISTERED, "plugins="));
        assertEquals(List.of("plugins=Voice,MagicSpells"), CaptureArguments.completePluginsOption(REGISTERED, "plugins=Voice,m"));
        assertEquals(List.of("plugins=magicspells,SneakyWizards"), CaptureArguments.completePluginsOption(REGISTERED, "plugins=magicspells,s"));
        assertEquals(List.of(), CaptureArguments.completePluginsOption(REGISTERED, "world"));
    }
}
