package com.teamsneakymouse.sneakydiagnostics.velocity;

import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.teamsneakymouse.sneakydiagnostics.connection.ConnectionArguments;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/** Loaded only by velocity-plugin.json; no Bukkit classes are referenced from this entry point. */
public final class SneakyDiagnosticsVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private final AtomicBoolean writing = new AtomicBoolean();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SneakyDiagnostics-proxy-reports");
        thread.setDaemon(true);
        return thread;
    });
    private VelocityConnections connections;
    private ScheduledTask task;

    @Inject public SneakyDiagnosticsVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy;
        this.logger = logger;
        this.directory = directory;
    }

    @Subscribe public void initialize(ProxyInitializeEvent event) {
        String version = proxy.getPluginManager().fromInstance(this).orElseThrow()
                .getDescription().getVersion().orElse("unknown");
        connections = new VelocityConnections(proxy::getAllPlayers, this::write, writing::get, System::nanoTime,
                proxy.getVersion().toString(), version, id -> proxy.getPlayer(id).orElse(null));
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("vdiagnostics").plugin(this).build(), new Command());
        task = proxy.getScheduler().buildTask(this, connections::poll).repeat(Duration.ofSeconds(1)).schedule();
        logger.info("Ready; /vdiagnostics connections <start [seconds] [player ...]|status|stop>. Collection is manual.");
    }

    private void write(Map<String, Object> report) {
        writing.set(true);
        Path file = directory.resolve("reports").resolve((String) report.get("captureId")).resolve("connections-1.json");
        writer.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                try (var output = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW)) {
                    new GsonBuilder().setPrettyPrinting().create().toJson(report, output);
                }
                logger.info("Saved {}", file.toAbsolutePath());
            } catch (IOException | RuntimeException e) {
                logger.error("Cannot save connection report {}", file.toAbsolutePath(), e);
            } finally { writing.set(false); }
        });
    }

    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        if (task != null) task.cancel();
        if (connections != null) connections.close();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) logger.warn("Connection report writer is still pending at shutdown");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private final class Command implements SimpleCommand {
        private boolean allowed(Invocation invocation) {
            return invocation.source() instanceof ConsoleCommandSource
                    || invocation.source().hasPermission("sneakydiagnostics.connections");
        }
        @Override public boolean hasPermission(Invocation invocation) { return allowed(invocation); }
        @Override public void execute(Invocation invocation) {
            if (!allowed(invocation)) return;
            String[] args = invocation.arguments();
            String message;
            try {
                if (args.length == 0 || !args[0].equalsIgnoreCase("connections")) throw new IllegalArgumentException(usage());
                String action = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
                message = switch (action) {
                    case "start" -> {
                        var parsed = ConnectionArguments.start(Arrays.copyOfRange(args, 2, args.length),
                                name -> proxy.getPlayer(name).map(com.velocitypowered.api.proxy.Player::getUniqueId).orElse(null));
                        yield "Started connections: " + connections.start(parsed.seconds(), parsed.targets()) + ", " + parsed.seconds()
                                + " seconds; " + parsed.targets().size() + " targets numbered in supplied order. Everyone else remains the comparison baseline.";
                    }
                    case "status" -> {
                        if (args.length > 2) throw new IllegalArgumentException(usage());
                        yield connections.status();
                    }
                    case "stop" -> {
                        if (args.length > 2) throw new IllegalArgumentException(usage());
                        connections.stop("manual_stop");
                        yield "Active connection capture stopped; partial report queued.";
                    }
                    default -> throw new IllegalArgumentException(usage());
                };
            } catch (IllegalArgumentException | IllegalStateException e) { message = e.getMessage(); }
            invocation.source().sendMessage(Component.text(message));
        }
        @Override public List<String> suggest(Invocation invocation) {
            if (!allowed(invocation)) return List.of();
            String[] args = invocation.arguments();
            if (args.length == 0) return List.of("connections");
            if (args.length == 1) return List.of("connections").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
            if (args.length == 2 && args[0].equalsIgnoreCase("connections"))
                return List.of("start", "status", "stop").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
            if (args.length >= 3 && args[0].equalsIgnoreCase("connections") && args[1].equalsIgnoreCase("start")) {
                String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
                return proxy.getAllPlayers().stream().map(com.velocitypowered.api.proxy.Player::getUsername)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
            }
            return List.of();
        }
        private String usage() { return "/vdiagnostics connections <start [seconds] [player ...]|status|stop>"; }
    }
}
