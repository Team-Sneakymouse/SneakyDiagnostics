package com.teamsneakymouse.sneakydiagnostics;

import com.teamsneakymouse.sneakydiagnostics.entity.EntityCensusCollector;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Plugin lifecycle and command routing; collectors own their capture state. */
public final class SneakyDiagnosticsPlugin extends JavaPlugin {
    private EntityCensusCollector entities;

    @Override public void onEnable() {
        entities = new EntityCensusCollector(this);
        getLogger().info("Ready; /diagnostics entities start [world]. Collection runs only when requested.");
    }

    @Override public void onDisable() {
        if (entities != null) entities.close();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("entitycensus")) {
            return entities.onCommand(sender, command, label, args);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("entities")) {
            return entities.onCommand(sender, command, label, Arrays.copyOfRange(args, 1, args.length));
        }
        sender.sendMessage("Usage: /" + label + " entities <start [world]|status|stop>");
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("entitycensus")) {
            return entities.onTabComplete(sender, command, alias, args);
        }
        if (!sender.hasPermission("sneaky.diagnostics.entities")) return List.of();
        if (args.length == 1) {
            return "entities".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("entities") : List.of();
        }
        if (args.length > 1 && args[0].equalsIgnoreCase("entities")) {
            return entities.onTabComplete(sender, command, alias, Arrays.copyOfRange(args, 1, args.length));
        }
        return List.of();
    }
}
