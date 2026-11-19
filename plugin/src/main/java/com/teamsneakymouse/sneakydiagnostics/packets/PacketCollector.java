package com.teamsneakymouse.sneakydiagnostics.packets;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.DiagnosticCollector;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportSink;
import com.teamsneakymouse.sneakydiagnostics.serverprobe.IgniteServerProbe;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * {@code packets-1.json}, measured by two probes of the Ignite companion under one capture ID: {@code tracker}
 * (entity-tracker send time by entity type and owner hint) and {@code packets} (outbound packets by class and cause,
 * tracker causes by entity type and owner, inbound packets by class). They stay separate probes so either section can
 * be measured alone and an older companion with only the tracker probe still yields the tracker section. Without the
 * companion the report says the measurements are unavailable.
 */
public final class PacketCollector implements DiagnosticCollector, Listener {
    /** The packets probe's sections, written under these names. */
    static final List<String> PACKET_SECTIONS = List.of("outbound", "causes", "trackerCauses", "inbound");
    private final Logger logger;
    private final ReportSink reports;
    private final IgniteServerProbe tracker, packets;
    private CaptureContext context;
    private boolean trackerActive, packetsActive;
    private String trackerAvailability, packetsAvailability;

    public PacketCollector(Logger logger, ReportSink reports) {
        this(logger, reports, new IgniteServerProbe("tracker"), new IgniteServerProbe("packets"));
    }
    PacketCollector(Logger logger, ReportSink reports, IgniteServerProbe tracker, IgniteServerProbe packets) {
        this.logger = logger;
        this.reports = reports;
        this.tracker = tracker;
        this.packets = packets;
    }

    @Override public String name() { return "packets"; }
    @Override public boolean active() { return context != null; }
    @Override public boolean busy() { return active() || reports.busy(name()); }
    @Override public String status() {
        return (active() ? "Collecting " + context.id() : reports.busy(name()) ? "Writing report" : "Idle")
                + "; tracker probe: " + tracker.status() + "; packets probe: " + packets.status();
    }

    @Override public void start(CaptureContext context, List<World> worlds) {
        if (busy()) throw new IllegalStateException("Packets collector is busy");
        trackerAvailability = tracker.status();
        packetsAvailability = packets.status();
        trackerActive = false;
        packetsActive = false;
        if (tracker.available()) {
            try { tracker.start(context); trackerActive = true; }
            catch (Exception e) { throw new IllegalStateException("Cannot start Ignite tracker probe", e); }
        }
        if (packets.available()) {
            try { packets.start(context); packetsActive = true; }
            catch (Exception e) {
                if (trackerActive) {
                    try { tracker.stop(context.id()); } catch (Exception stop) { e.addSuppressed(stop); }
                    trackerActive = false;
                }
                throw new IllegalStateException("Cannot start Ignite packets probe", e);
            }
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
        report.put("igniteAvailability", trackerAvailability);
        report.put("packetsAvailability", packetsAvailability);
        boolean trackerComplete = false, packetsComplete = false;
        int errors = 0;
        if (trackerActive) {
            try {
                Map<String, Object> measurement = tracker.stop(finished.id());
                Object section = measurement.get("tracker");
                report.put("tracker", section);
                trackerComplete = complete(section);
            } catch (Exception e) {
                errors++;
                report.put("igniteError", e.toString());
                logger.log(Level.WARNING, "Cannot stop Ignite tracker probe", e);
            }
        }
        if (packetsActive) {
            try {
                Map<String, Object> measurement = packets.stop(finished.id());
                if (measurement.get("error") != null) throw new IllegalStateException(String.valueOf(measurement.get("error")));
                packetsComplete = true;
                for (String key : PACKET_SECTIONS) {
                    Object section = measurement.get(key);
                    if (section == null) { packetsComplete = false; continue; }
                    report.put(key, section);
                    packetsComplete &= complete(section);
                }
            } catch (Exception e) {
                errors++;
                report.put("packetsError", e.toString());
                logger.log(Level.WARNING, "Cannot stop Ignite packets probe", e);
            }
        }
        trackerActive = false;
        packetsActive = false;
        report.put("complete", reason.equals("completed") && errors == 0 && trackerComplete && packetsComplete);
        report.put("errors", errors);
        report.put("notes", "tracker: server-thread wall time inside ServerEntity.sendChanges (the entity tracker's per-tick send: "
                + "movement, rotation, passengers, velocity, map updates and changed metadata) by Bukkit entity type and owner hint, "
                + "measured by the Ignite companion. metadataNanos is the nested sendDirtyEntityData part. Owner hints are candidates, "
                + "not proven ownership: (player) for connected players, class:<name> for non-Minecraft entity classes (plugin NPCs), "
                + "otherwise the entity's PDC namespaces, resolved once per entity per capture. Timing adds two clock reads per send, "
                + "which are included in the measured time. Player checks (updatePlayer) and the tracker's entity loop are not included. "
                + "outbound: Connection.send calls by packet class; serverThreadNanos is inclusive server-thread time inside send "
                + "(ProtocolLib's proxy, Netty hand-off or queueing below it), overlapping every tick phase; bundles count once and their "
                + "sub-packets per class (bundled). causes: the innermost wrapped call site a send happened in (tracker.update, "
                + "tracker.metadata, tracker.spawn, tracker.despawn, entity.event, broadcast, particles, chunks, else other); "
                + "fan-out = packets / broadcasts. trackerCauses: tracker causes by entity type and owner (plugin:<name> = the plugin on "
                + "the stack when the entity was spawned during a packets capture, else the tracker owner hint). inbound: "
                + "PacketProcessor handling on the server thread by packet class, inclusive of plugin events it fires. No player "
                + "identities, payloads or bytes on the wire. A missing section is unmeasured, not zero.");
        reports.write(name(), finished, 1, () -> report);
    }

    private static boolean complete(Object section) {
        return section instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("complete"));
    }
}
