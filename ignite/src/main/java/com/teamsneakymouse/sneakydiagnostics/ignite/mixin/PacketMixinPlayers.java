package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PacketProbe;
import java.util.function.Function;
import java.util.function.Predicate;
import net.kyori.adventure.audience.Audience;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/**
 * {@code broadcast} causes on {@code PlayerList}: the positional broadcast (sounds, level events), every
 * {@code broadcastAll} overload, system messages and chat. The other public overloads funnel into the wrapped ones;
 * re-entering the cause already on top does not count as another broadcast.
 */
@Mixin(value = PlayerList.class, remap = false)
abstract class PacketMixinPlayers {
    @WrapMethod(method = "broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V",
            require = 1, allow = 1)
    private void diagnostics$near(Player except, double x, double y, double z, double radius, ResourceKey<Level> dimension, Packet<?> packet,
                                  Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(except, x, y, z, radius, dimension, packet); return; }
        int frame = session.enter(PacketProbe.BROADCAST, 0);
        try { original.call(except, x, y, z, radius, dimension, packet); }
        finally { session.exit(frame); }
    }

    @WrapMethod(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;)V", require = 1, allow = 1)
    private void diagnostics$all(Packet<?> packet, Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(packet); return; }
        int frame = session.enter(PacketProbe.BROADCAST, 0);
        try { original.call(packet); }
        finally { session.exit(frame); }
    }

    @WrapMethod(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/resources/ResourceKey;)V", require = 1, allow = 1)
    private void diagnostics$dimension(Packet<?> packet, ResourceKey<Level> dimension, Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(packet, dimension); return; }
        int frame = session.enter(PacketProbe.BROADCAST, 0);
        try { original.call(packet, dimension); }
        finally { session.exit(frame); }
    }

    @WrapMethod(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/world/entity/player/Player;)V", require = 1, allow = 1)
    private void diagnostics$visibleTo(Packet<?> packet, Player source, Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(packet, source); return; }
        int frame = session.enter(PacketProbe.BROADCAST, 0);
        try { original.call(packet, source); }
        finally { session.exit(frame); }
    }

    @WrapMethod(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/world/level/Level;)V", require = 1, allow = 1)
    private void diagnostics$level(Packet<?> packet, Level level, Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(packet, level); return; }
        int frame = session.enter(PacketProbe.BROADCAST, 0);
        try { original.call(packet, level); }
        finally { session.exit(frame); }
    }

    @WrapMethod(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V", require = 1, allow = 1)
    private void diagnostics$system(Component message, Function<ServerPlayer, Component> perPlayer, boolean overlay, Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(message, perPlayer, overlay); return; }
        int frame = session.enter(PacketProbe.BROADCAST, 0);
        try { original.call(message, perPlayer, overlay); }
        finally { session.exit(frame); }
    }

    @WrapMethod(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;Ljava/util/function/Function;)V",
            require = 1, allow = 1)
    private void diagnostics$chat(PlayerChatMessage message, Predicate<ServerPlayer> filtered, ServerPlayer sender, ChatType.Bound type,
                                  Function<Audience, Component> renderer, Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(message, filtered, sender, type, renderer); return; }
        int frame = session.enter(PacketProbe.BROADCAST, 0);
        try { original.call(message, filtered, sender, type, renderer); }
        finally { session.exit(frame); }
    }
}
