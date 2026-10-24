package com.teamsneakymouse.sneakydiagnostics.integration.reference;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** What the guide's snippets look like in a plugin; compiled by the tests so the guide builds. */
public final class ReferencePlugin extends JavaPlugin implements Listener {
    private final List<Machine> machines = new ArrayList<>();
    private final List<Entity> owned = new ArrayList<>();

    private final Probe probe = new Probe(this, "myplugin-ticks-2");

    @Override public void onEnable() {
        // ... existing setup ...
        probe.hook();
    }

    @Override public void onDisable() {
        probe.unhook();
        // ... existing teardown ...
    }

    private void tickAll() {
        long tick = probe.start();
        long ts = probe.start();
        deliver();
        probe.end("tick.deliveries", ts);
        ts = probe.start();
        for (Machine machine : machines) {
            long tm = probe.start();
            machine.tick();
            if (tm != 0) probe.end("machine." + machine.type(), tm);
        }
        probe.end("tick.machines", ts);
        probe.count("machines.ticked", machines.size());
        if (probe.sampleDue()) {
            probe.level("machines", machines.size());
            probe.level("entities.owned", owned.size());
            Map<EntityType, Integer> byType = new EnumMap<>(EntityType.class);
            for (Entity entity : owned) byType.merge(entity.getType(), 1, Integer::sum);
            for (var entry : byType.entrySet()) {
                probe.level("entities.owned." + entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        probe.end("tick", tick);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        long t = probe.start();
        try {
            for (Entity entity : event.getEntities()) register(entity);
        } finally {
            probe.end("event.entities-load", t);
        }
    }

    private void deliver() {}
    private void register(Entity entity) {}

    private interface Machine {
        void tick();
        String type();
    }
}
