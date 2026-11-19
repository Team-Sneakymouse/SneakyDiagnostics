package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

/** Implemented on {@code ServerLevel} by a mixin: the level's world slot in a phase capture, cached per capture. */
public interface PhaseWorld {
    /** The world slot in {@code session} (-1 past the world cap). Server thread; steady state reads two fields. */
    int diagnostics$phaseWorld(PhaseProbe.Session session);
}
