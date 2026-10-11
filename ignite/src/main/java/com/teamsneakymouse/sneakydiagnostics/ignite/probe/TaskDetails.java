package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

/** Runtime-only interface implemented by transformed tasks; never linked by the Paper plugin. */
public interface TaskDetails {
    String diagnosticsTaskClass();
}
