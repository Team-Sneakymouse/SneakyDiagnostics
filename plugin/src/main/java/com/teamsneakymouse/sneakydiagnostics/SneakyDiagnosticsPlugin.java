package com.teamsneakymouse.sneakydiagnostics;

import com.teamsneakymouse.sneakydiagnostics.capture.CaptureArguments;
import com.teamsneakymouse.sneakydiagnostics.capture.CaptureContext;
import com.teamsneakymouse.sneakydiagnostics.capture.CaptureCoordinator;
import com.teamsneakymouse.sneakydiagnostics.capture.DiagnosticCollector;
import com.teamsneakymouse.sneakydiagnostics.capture.ReportWriter;
import com.teamsneakymouse.sneakydiagnostics.entity.EntityCensusCollector;
import com.teamsneakymouse.sneakydiagnostics.connection.ConnectionCollector;
import com.teamsneakymouse.sneakydiagnostics.connection.ConnectionArguments;
import com.teamsneakymouse.sneakydiagnostics.scheduler.SchedulerCollector;
import com.teamsneakymouse.sneakydiagnostics.timeline.TimelineCollector;
import com.teamsneakymouse.sneakydiagnostics.api.Diagnostics;
import com.teamsneakymouse.sneakydiagnostics.integration.PluginCollector;
import com.teamsneakymouse.sneakydiagnostics.integration.ProviderRegistry;
import com.teamsneakymouse.sneakydiagnostics.spark.SparkCollector;
import com.teamsneakymouse.sneakydiagnostics.spark.ReflectiveSparkProfiler;
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
import org.bukkit.plugin.ServicePriority;

/** Command routing and coordinated starts; collectors own their capture state. */
public final class SneakyDiagnosticsPlugin extends JavaPlugin {
    private final Map<String, DiagnosticCollector> collectors = new LinkedHashMap<>();
    private EntityCensusCollector entities;
    private ReportWriter reports;
    private CaptureCoordinator coordinator;
    private ProviderRegistry providers;

    @Override public void onEnable() {
        reports = new ReportWriter(this);
        entities = new EntityCensusCollector(this, reports);
        var timeline = new TimelineCollector(reports);
        var connections = new ConnectionCollector(reports);
        var scheduler = new SchedulerCollector(this, reports);
        providers = new ProviderRegistry(() -> {
            if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Diagnostics lifecycle must run on the server thread");
        }, Bukkit::getCurrentTick);
        var plugins = new PluginCollector(providers, reports);
        var spark = new SparkCollector(new ReflectiveSparkProfiler(getServer()), reports);
        for (var collector : List.of(entities, timeline, scheduler, plugins, spark, connections)) collectors.put(collector.name(), collector);
        coordinator = new CaptureCoordinator(List.copyOf(collectors.values()));
        getServer().getPluginManager().registerEvents(timeline, this);
        getServer().getPluginManager().registerEvents(connections, this);
        getServer().getPluginManager().registerEvents(scheduler, this);
        getServer().getPluginManager().registerEvents(plugins, this);
        getServer().getPluginManager().registerEvents(spark, this);
        getServer().getServicesManager().register(Diagnostics.class, providers, this, ServicePriority.Normal);
        getLogger().info("Ready; /diagnostics <entities|timeline|scheduler|plugins|spark|connections|all> <start|status|stop>. Collection is manual.");
    }

    @Override public void onDisable() {
        for (var collector : collectors.values()) collector.stop("plugin_disable");
        if (providers != null) providers.close();
        getServer().getServicesManager().unregisterAll(this);
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
                    if (name.equals("connections")) {
                        var parsed = ConnectionArguments.start(options, playerName -> {
                            var player = Bukkit.getPlayerExact(playerName);
                            return player == null ? null : player.getUniqueId();
                        });
                        var context = CaptureContext.create(parsed.seconds());
                        coordinator.start(selected, false, collector -> ((ConnectionCollector) collector)
                                .start(context, List.of(), parsed.targets()));
                        sender.sendMessage("Started connections: " + context.id() + ", " + parsed.seconds()
                                + " seconds; " + parsed.targets().size() + " targets numbered in supplied order. Everyone else remains the comparison baseline.");
                        break;
                    }
                    var parsed = CaptureArguments.start(name, options, providers.providers());
                    List<World> worlds = Bukkit.getWorlds();
                    if (parsed.world() != null) {
                        World world = Bukkit.getWorld(parsed.world());
                        if (world == null) throw new IllegalArgumentException("Unknown or unloaded world: " + parsed.world());
                        worlds = List.of(world);
                    }
                    CaptureContext context = CaptureContext.create(parsed.seconds());
                    List<World> startWorlds = worlds;
                    coordinator.start(selected, name.equals("all"), collector -> {
                        if (collector instanceof PluginCollector pluginCollector) pluginCollector.start(context, startWorlds, parsed.plugins());
                        else collector.start(context, startWorlds);
                    });
                    sender.sendMessage("Started " + name + ": " + context.id() + ", " + parsed.seconds() + " seconds"
                            + (parsed.plugins().isEmpty() ? "" : "; providers: " + String.join(", ", parsed.plugins()))
                            + ". Reports share this capture ID.");
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
        sender.sendMessage("/" + label + " <entities|timeline|scheduler|plugins|spark|connections|all> <start|status|stop>; entities also supports resume. "
                + "plugins start [seconds] [provider ...]; all start [seconds] [world] [plugins=<provider>,<provider>].");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean legacy = command.getName().equalsIgnoreCase("entitycensus");
        if (!legacy && args.length == 1) {
            return List.of("entities", "timeline", "scheduler", "plugins", "spark", "connections", "all").stream()
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
            if (name.equals("connections")) {
                String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream().map(org.bukkit.entity.Player::getName)
                        .filter(player -> player.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
            }
            if (name.equals("plugins")) {
                var typed = Arrays.asList(args).subList(actionIndex + 1, args.length - 1);
                return CaptureArguments.completeProviders(providers.providers(), typed, args[args.length - 1]);
            }
            if (name.equals("all") && args[args.length - 1].toLowerCase(Locale.ROOT).startsWith("plugins=")) {
                return CaptureArguments.completePluginsOption(providers.providers(), args[args.length - 1]);
            }
            if (name.equals("entities") && args.length == actionIndex + 2
                    || name.equals("all") && args.length >= actionIndex + 2 && args.length <= actionIndex + 3) {
                String prefix = args[args.length - 1];
                var matches = new java.util.ArrayList<>(Bukkit.getWorlds().stream().map(World::getName).filter(world -> world.startsWith(prefix)).toList());
                if (name.equals("all") && "plugins=".startsWith(prefix.toLowerCase(Locale.ROOT))
                        && Arrays.stream(args).noneMatch(arg -> arg.toLowerCase(Locale.ROOT).startsWith("plugins="))) matches.add("plugins=");
                return matches;
            }
        }
        return List.of();
    }
}
