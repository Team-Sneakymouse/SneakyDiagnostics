package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PacketProbe;
import net.minecraft.network.PacketProcessor;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Marks inbound packets handled while the server waits for the next tick ({@code pollTask}), apart from tick start. */
@Mixin(value = MinecraftServer.class, remap = false)
abstract class PacketMixinServer {
    @WrapOperation(method = "pollTask()Z", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/PacketProcessor;executeSinglePacket()Z"), require = 1, allow = 1)
    private boolean diagnostics$betweenTicks(PacketProcessor processor, Operation<Boolean> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) return original.call(processor);
        boolean previous = session.betweenTicks(true);
        try { return original.call(processor); }
        finally { session.betweenTicks(previous); }
    }
}
