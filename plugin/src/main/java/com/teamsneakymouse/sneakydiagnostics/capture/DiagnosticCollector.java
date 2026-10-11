package com.teamsneakymouse.sneakydiagnostics.capture;

import java.util.List;
import org.bukkit.World;

/** Lifecycle calls run on the server thread; report writing is included in busy(). */
public interface DiagnosticCollector {
    String name();
    boolean active();
    boolean busy();
    String status();
    void start(CaptureContext context, List<World> worlds);
    void stop(String reason);
}
