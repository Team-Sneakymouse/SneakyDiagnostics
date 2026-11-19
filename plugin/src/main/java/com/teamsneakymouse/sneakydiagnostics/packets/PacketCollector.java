package com.teamsneakymouse.sneakydiagnostics.packets;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.DiagnosticCollector;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * {@code packets-1.json}. Today it holds only the entity tracker's send time by entity type and owner hint, measured
 * by the Ignite companion; outbound/inbound packet counts are planned (docs/report-schema-v2.md). Without the companion
 * the report says the measurement is unavailable.
 */
public final class PacketCollector implements DiagnosticCollector, Listener {
    private final Logger logger;
    private final ReportSink reports;
    private final IgniteServerProbe ignite;
    private CaptureContext context;
    private boolean igniteActive;
    private String igniteAvailability;

    public PacketCollector(Logger logger, ReportSink reports) { this(logger, reports, new IgniteServerProbe()); }
    PacketCollector(Logger logger, ReportSink reports, IgniteServerProbe ignite) { this.logger = logger; this.reports = reports; this.ignite = ignite; }

    @Override public String name() { return "packets"; }
    @Override public boolean active() { return context != null; }
    @Override public boolean busy() { return active() || reports.busy(name()); }
    @Override public String status() {
        return (active() ? "Collecting " + context.id() : reports.busy(name()) ? "Writing report" : "Idle") + "; tracker probe: " + ignite.status();
    }

    @Override public void start(CaptureContext context, List<World> worlds) {
        if (busy()) throw new IllegalStateException("Packets collector is busy");
        igniteAvailability = ignite.status();
        igniteActive = false;
        if (ignite.available()) {
            try { ignite.start(context); igniteActive = true; }
            catch (Exception e) { throw new IllegalStateException("Cannot start Ignite tracker probe", e); }
        }
        this.context = context;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTick(ServerTickEndEvent event) {
        if (active() && context.offsetMillis() >= context.seconds() * 1000L) stop("completed");
    }

    @Override public void stop(String reason) {
        if (!active()) return;
        CaptureContext finished = context;
        context = null;
        var report = reports.header(name(), finished, reason, 1);
        report.put("startedAt", finished.startedAt());
        report.put("igniteAvailability", igniteAvailability);
        boolean trackerComplete = false;
        int errors = 0;
        if (igniteActive) {
            try {
                Map<String, Object> measurement = ignite.stop(finished.id());
                Object tracker = measurement.get("tracker");
                report.put("tracker", tracker);
                trackerComplete = tracker instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("complete"));
            } catch (Exception e) {
                errors++;
                report.put("igniteError", e.toString());
                logger.log(Level.WARNING, "Cannot stop Ignite tracker probe", e);
            }
        }
        igniteActive = false;
        report.put("complete", reason.equals("completed") && errors == 0 && trackerComplete);
        report.put("errors", errors);
        report.put("notes", "tracker: server-thread wall time inside ServerEntity.sendChanges (the entity tracker's per-tick send: "
                + "movement, rotation, passengers, velocity, map updates and changed metadata) by Bukkit entity type and owner hint, "
                + "measured by the Ignite companion. metadataNanos is the nested sendDirtyEntityData part. Owner hints are candidates, "
                + "not proven ownership: (player) for connected players, class:<name> for non-Minecraft entity classes (plugin NPCs), "
                + "otherwise the entity's PDC namespaces, resolved once per entity per capture. Timing adds two clock reads per send, "
                + "which are included in the measured time. Player checks (updatePlayer) and the tracker's entity loop are not included. "
                + "Without the companion there is no tracker section: unmeasured, not zero.");
        reports.write(name(), finished, 1, () -> report);
    }
}
