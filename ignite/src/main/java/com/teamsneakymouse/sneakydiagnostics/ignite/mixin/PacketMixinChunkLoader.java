package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PacketProbe;
import org.spongepowered.asm.mixin.Mixin;

/** {@code chunks} cause: Moonrise's chunk unload for one player (the forget packet and PlayerChunkUnloadEvent). */
@Mixin(targets = "ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader$PlayerChunkLoaderData", remap = false)
abstract class PacketMixinChunkLoader {
    @WrapMethod(method = "sendUnloadChunkRaw(II)V", require = 1, allow = 1)
    private void diagnostics$unload(int chunkX, int chunkZ, Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(chunkX, chunkZ); return; }
        int frame = session.enter(PacketProbe.CHUNKS, 0);
        try { original.call(chunkX, chunkZ); }
        finally { session.exit(frame); }
    }
}
