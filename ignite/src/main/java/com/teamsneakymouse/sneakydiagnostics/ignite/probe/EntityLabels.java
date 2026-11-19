package com.teamsneakymouse.sneakydiagnostics.ignite.probe;

import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import java.util.TreeSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.LevelCallback;
import org.bukkit.NamespacedKey;

/** Entity labels shared by the tracker and packet hooks, and the spawning-plugin lookup. Server thread only. */
public final class EntityLabels {
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final int MAX_FRAMES = 256;
    /** A pass-through frame: a plugin class that the server calls for every entity (an injected {@link LevelCallback}). */
    private static final String PASS_THROUGH = "\u0000pass";
    /** Per class: its plugin's name, {@link #PASS_THROUGH}, or "" for server, library and JDK classes. */
    private static final ClassValue<String> PLUGIN = new ClassValue<>() {
        @Override protected String computeValue(Class<?> type) {
            if (!(type.getClassLoader() instanceof ConfiguredPluginClassLoader loader)) return "";
            if (LevelCallback.class.isAssignableFrom(type)) return PASS_THROUGH;
            try {
                var meta = loader.getConfiguration();
                return meta == null || meta.getName() == null ? "" : meta.getName();
            } catch (RuntimeException | LinkageError e) {
                return "";
            }
        }
    };

    private EntityLabels() {}

    /**
     * Owner hint as in the tracker section: {@code (player)} for a connected player (exactly {@code ServerPlayer},
     * whose PDC holds many plugins' keys); {@code class:<name>} for a non-Minecraft entity class (plugin NPCs such as
     * Citizens); otherwise the entity's sorted PDC namespaces as in the entity census ({@code pdc:a,pdc:b}), or
     * {@code (none)}.
     */
    public static String[] typeAndHint(Entity entity) {
        var bukkit = entity.getBukkitEntity();
        String type = bukkit.getType().name();
        Class<?> implementation = entity.getClass();
        if (implementation == ServerPlayer.class) return new String[]{type, "(player)"};
        if (!implementation.getName().startsWith("net.minecraft.")) return new String[]{type, "class:" + implementation.getName()};
        var namespaces = new TreeSet<String>();
        for (NamespacedKey key : bukkit.getPersistentDataContainer().getKeys()) namespaces.add("pdc:" + key.getNamespace());
        return new String[]{type, namespaces.isEmpty() ? "(none)" : String.join(",", namespaces)};
    }

    /** {@code plugin:<name>} when the spawning plugin is known, else the {@link #typeAndHint owner hint}. */
    public static String[] typeAndOwner(Entity entity) {
        String spawnedBy = ((SpawnOwner) entity).diagnostics$spawnedBy();
        if (spawnedBy == null || spawnedBy.isEmpty()) return typeAndHint(entity);
        return new String[]{entity.getBukkitEntity().getType().name(), "plugin:" + spawnedBy};
    }

    /**
     * Records the plugin that is spawning {@code entity}: the first plugin-classloader frame on the stack, skipping
     * pass-through frames (plugin classes implementing {@link LevelCallback}, such as CraftEngine's
     * {@code InjectedPaperLevelCallback}, which wraps every entity's tracking start). One stack walk, only while recording,
     * and only for an entity without a recorded spawner.
     */
    public static void recordSpawner(PacketProbe.Session session, Entity entity) {
        var owner = (SpawnOwner) entity;
        if (owner.diagnostics$spawnedBy() != null) return;
        long started = session.now();
        int[] skipped = new int[1];
        String plugin = WALKER.walk(frames -> frames.limit(MAX_FRAMES).map(frame -> PLUGIN.get(frame.getDeclaringClass()))
                .filter(name -> {
                    if (name == PASS_THROUGH) { skipped[0]++; return false; }
                    return !name.isEmpty();
                }).findFirst().orElse(null));
        if (plugin != null) owner.diagnostics$spawnedBy(plugin);
        session.spawnLookup(session.now() - started, plugin != null, skipped[0]);
    }
}
