package com.teamsneakymouse.sneakydiagnostics;

import com.teamsneakymouse.sneakydiagnostics.capture.CaptureArguments;
import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.CaptureCoordinator;
import com.teamsneakymouse.sneakydiagnostics.capture.DiagnosticCollector;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportWriter;
import com.teamsneakymouse.sneakydiagnostics.entity.EntityCensusCollector;
import com.teamsneakymouse.sneakydiagnostics.scheduler.SchedulerCollector;
import com.teamsneakymouse.sneakydiagnostics.timeline.TimelineCollector;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Command routing and coordinated starts; collectors own their capture state. */
public final class SneakyDiagnosticsPlugin extends JavaPlugin {
    private final Map<String, DiagnosticCollector> collectors = new LinkedHashMap<>();
    private EntityCensusCollector entities;
    private ReportWriter reports;
    private CaptureCoordinator coordinator;

    @Override public void onEnable() {
        reports = new ReportWriter(this);
        entities = new EntityCensusCollector(this, reports);
        var timeline = new TimelineCollector(reports);
        var scheduler = new SchedulerCollector(this, reports);
        for (var collector : List.of(entities, timeline, scheduler)) collectors.put(collector.name(), collector);
        coordinator = new CaptureCoordinator(List.copyOf(collectors.values()));
        getServer().getPluginManager().registerEvents(timeline, this);
        getServer().getPluginManager().registerEvents(scheduler, this);
        getLogger().info("Ready; /diagnostics <entities|timeline|scheduler|all> <start|status|stop>. Collection is manual.");
    }

    @Override public void onDisable() {
        for (var collector : collectors.values()) collector.stop("plugin_disable");
        if (reports != null) reports.close();
        collectors.clear();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean legacy = command.getName().equalsIgnoreCase("entitycensus");
        String name = legacy ? "entities" : args.length == 0 ? "all" : args[0].toLowerCase(Locale.ROOT);
        if (!name.equals("all") && !collectors.containsKey(name)) { help(sender, label); return true; }
        if (!allowed(sender, name, legacy)) { sender.sendMessage("Missing permission: " + (legacy ? "sneaky.entitycensus" : "sneakydiagnostics." + name)); return true; }
        int actionIndex = legacy ? 0 : 1;
        String action = args.length <= actionIndex ? "status" : args[actionIndex].toLowerCase(Locale.ROOT);
        String[] options = args.length <= actionIndex + 1 ? new String[0] : Arrays.copyOfRange(args, actionIndex + 1, args.length);
        List<DiagnosticCollector> selected = name.equals("all") ? List.copyOf(collectors.values()) : List.of(collectors.get(name));
        try {
            switch (action) {
                case "start" -> {
                    var parsed = CaptureArguments.start(name, options);
                    List<World> worlds = Bukkit.getWorlds();
                    if (parsed.world() != null) {
                        World world = Bukkit.getWorld(parsed.world());
                        if (world == null) throw new IllegalArgumentException("Unknown or unloaded world: " + parsed.world());
                        worlds = List.of(world);
                    }
                    CaptureContext context = CaptureContext.create(parsed.seconds());
                    coordinator.start(selected, context, worlds, name.equals("all"));
                    sender.sendMessage("Started " + name + ": " + context.id() + ", " + parsed.seconds() + " seconds. Reports share this capture ID.");
                }
                case "resume" -> {
                    if (!name.equals("entities")) throw new IllegalArgumentException("Resume is available for entities only");
                    if (options.length > 1) throw new IllegalArgumentException("entities resume [seconds]");
                    int seconds = options.length == 0 ? 120 : CaptureArguments.seconds(options[0]);
                    coordinator.resume(() -> entities.resume(seconds));
                    sender.sendMessage(entities.status());
                }
                case "stop" -> {
                    noOptions(options);
                    for (var collector : selected) collector.stop("manual_stop");
                    sender.sendMessage("Selected active collectors stopped; partial reports queued.");
                }
                case "status" -> {
                    noOptions(options);
                    for (var collector : selected) sender.sendMessage(collector.name() + ": " + collector.status());
                }
                default -> help(sender, label);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            sender.sendMessage(e.getMessage());
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Diagnostics command failed", e);
            sender.sendMessage("Diagnostics command failed; see the server log.");
        }
        return true;
    }

    private static void noOptions(String[] options) {
        if (options.length != 0) throw new IllegalArgumentException("status and stop take no additional arguments");
    }

    private static boolean allowed(CommandSender sender, String name, boolean legacy) {
        return legacy ? sender.hasPermission("sneaky.entitycensus") : sender.hasPermission("sneakydiagnostics." + name);
    }

    private static void help(CommandSender sender, String label) {
        sender.sendMessage("/" + label + " <entities|timeline|scheduler|all> <start|status|stop>; entities also supports resume.");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean legacy = command.getName().equalsIgnoreCase("entitycensus");
        if (!legacy && args.length == 1) {
            return List.of("entities", "timeline", "scheduler", "all").stream()
                    .filter(name -> allowed(sender, name, false) && name.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        String name = legacy ? "entities" : args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if ((!name.equals("all") && !collectors.containsKey(name)) || !allowed(sender, name, legacy)) return List.of();
        int actionIndex = legacy ? 0 : 1;
        if (args.length == actionIndex + 1) {
            List<String> actions = name.equals("entities") ? List.of("start", "status", "stop", "resume") : List.of("start", "status", "stop");
            return actions.stream().filter(action -> action.startsWith(args[actionIndex].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length > actionIndex + 1 && args[actionIndex].equalsIgnoreCase("start")) {
            if (name.equals("entities") && args.length == actionIndex + 2
                    || name.equals("all") && args.length == actionIndex + 3) {
                String prefix = args[args.length - 1];
                return Bukkit.getWorlds().stream().map(World::getName).filter(world -> world.startsWith(prefix)).toList();
            }
        }
        return List.of();
    }
}
