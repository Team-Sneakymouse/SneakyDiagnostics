package com.teamsneakymouse.sneakydiagnostics.integration.reference;

import com.teamsneakymouse.sneakydiagnostics.api.Diagnostics;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;

/**
 * This plugin's measurements for SneakyDiagnostics (optional: without it every method below does nothing).
 * Keep API types out of every method signature (Bukkit reflects over them when registering events).
 */
public final class Probe implements Listener {
    private static final String SERVICE = "com.teamsneakymouse.sneakydiagnostics.api.Diagnostics";
    private final Plugin plugin;
    private final String instrumentation;
    private Diagnostics.Probe probe; // null unless SneakyDiagnostics is enabled; the only member naming an API type

    public Probe(Plugin plugin, String instrumentation) {
        this.plugin = plugin;
        this.instrumentation = instrumentation;
    }

    /** Call at the end of onEnable (server thread). Attaches now if SneakyDiagnostics is enabled, and again whenever it (re)appears. */
    public void hook() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (plugin.getServer().getPluginManager().isPluginEnabled("SneakyDiagnostics")) attach();
    }

    /** Call first thing in onDisable (server thread). */
    public void unhook() { detach(); }

    public long start() { var p = probe; return p == null ? 0 : p.start(); }
    public void end(String section, long token) { var p = probe; if (p != null) p.end(section, token); }
    public void count(String key) { count(key, 1); }
    public void count(String key, long n) { var p = probe; if (p != null) p.count(key, n); }
    public boolean sampleDue() { var p = probe; return p != null && p.sampleDue(); }
    public void level(String key, long value) { var p = probe; if (p != null) p.level(key, value); }
    public boolean on() { var p = probe; return p != null && p.on(); }

    @EventHandler public void onServiceUp(ServiceRegisterEvent event) {
        if (SERVICE.equals(event.getProvider().getService().getName())) attach();
    }

    @EventHandler public void onServiceDown(ServiceUnregisterEvent event) {
        if (SERVICE.equals(event.getProvider().getService().getName())) detach();
    }

    private void attach() {
        detach();
        try {
            Diagnostics diagnostics = plugin.getServer().getServicesManager().load(Diagnostics.class);
            if (diagnostics != null) probe = diagnostics.probe(plugin.getName(), plugin.getPluginMeta().getVersion(), instrumentation);
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Couldn't register with SneakyDiagnostics (" + e + "); its captures won't include " + plugin.getName() + ".");
        }
    }

    private void detach() {
        var p = probe;
        probe = null;
        if (p == null) return;
        try { p.close(); } catch (RuntimeException e) { plugin.getLogger().warning("Couldn't leave SneakyDiagnostics: " + e); }
    }
}
