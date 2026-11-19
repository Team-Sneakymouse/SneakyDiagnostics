package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PacketProbe;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

/**
 * {@code chunks} cause: one chunk sent to one player. Moonrise's player chunk loader calls it; it includes the
 * PlayerChunkLoadEvent, so packets that event's listeners send also count as {@code chunks}.
 */
@Mixin(value = PlayerChunkSender.class, remap = false)
abstract class PacketMixinChunkSender {
    @WrapMethod(method = "sendChunk(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            require = 1, allow = 1)
    private static void diagnostics$chunk(ServerGamePacketListenerImpl connection, ServerLevel level, LevelChunk chunk, Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(connection, level, chunk); return; }
        int frame = session.enter(PacketProbe.CHUNKS, 0);
        try { original.call(connection, level, chunk); }
        finally { session.exit(frame); }
    }
}
