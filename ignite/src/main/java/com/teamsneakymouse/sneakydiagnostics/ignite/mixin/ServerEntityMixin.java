package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.TrackerProbe;
import java.util.TreeSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.bukkit.NamespacedKey;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Times the entity tracker's per-tick send for one entity ({@code sendChanges}: movement, rotation, passengers,
 * velocity, map updates and changed metadata) and, nested in it, the metadata part ({@code sendDirtyEntityData}).
 * The entity's group (Bukkit entity type and owner hint) is resolved the first time a capture sees the entity and
 * cached on this tracker entry.
 */
@Mixin(targets = "net.minecraft.server.level.ServerEntity", remap = false)
abstract class ServerEntityMixin {
    @Shadow @Final private Entity entity;
    @Unique private int diagnostics$generation;
    @Unique private int diagnostics$group;

    @WrapMethod(method = "sendChanges()V", require = 1, allow = 1)
    private void diagnostics$sendChanges(Operation<Void> original) {
        TrackerProbe.Session session = TrackerProbe.recording();
        if (session == null) { original.call(); return; }
        if (diagnostics$generation != session.generation) {
            diagnostics$group = session.group(this::diagnostics$label);
            diagnostics$generation = session.generation;
        }
        long started = session.now();
        try { original.call(); }
        finally { session.send(diagnostics$group, started, session.now()); }
    }

    @WrapMethod(method = "sendDirtyEntityData()V", require = 1, allow = 1)
    private void diagnostics$metadata(Operation<Void> original) {
        TrackerProbe.Session session = TrackerProbe.recording();
        if (session == null || diagnostics$generation != session.generation) { original.call(); return; }
        long started = session.now();
        try { original.call(); }
        finally { session.metadata(diagnostics$group, started, session.now()); }
    }

    /**
     * Owner hint: {@code (player)} for a connected player (exactly {@code ServerPlayer}, whose PDC holds many plugins'
     * keys); {@code class:<name>} for a non-Minecraft entity class (plugin NPCs such as Citizens); otherwise the
     * entity's sorted PDC namespaces as in the entity census ({@code pdc:a,pdc:b}), or {@code (none)}.
     */
    @Unique
    private String[] diagnostics$label() {
        var bukkit = entity.getBukkitEntity();
        String type = bukkit.getType().name();
        Class<?> implementation = entity.getClass();
        if (implementation == ServerPlayer.class) return new String[]{type, "(player)"};
        if (!implementation.getName().startsWith("net.minecraft.")) return new String[]{type, "class:" + implementation.getName()};
        var namespaces = new TreeSet<String>();
        for (NamespacedKey key : bukkit.getPersistentDataContainer().getKeys()) namespaces.add("pdc:" + key.getNamespace());
        return new String[]{type, namespaces.isEmpty() ? "(none)" : String.join(",", namespaces)};
    }
}
