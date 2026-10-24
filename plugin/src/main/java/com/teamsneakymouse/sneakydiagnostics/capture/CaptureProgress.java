package com.teamsneakymouse.sneakydiagnostics.capture;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Which collectors of one capture are still running; server thread only. */
public final class CaptureProgress {
    private final Set<String> active = new HashSet<>();

    public void begin(Collection<String> collectors) { active.addAll(collectors); }
    /** A start rolled back before this collector began. */
    public void cancel(String collector) { active.remove(collector); }

    /**
     * Records a collector's finish. True when no collector of the capture remains, which includes captures whose
     * starters were not registered (a lone collector, or a resumed segment).
     */
    public boolean finish(String collector) {
        active.remove(collector);
        return active.isEmpty();
    }
}
