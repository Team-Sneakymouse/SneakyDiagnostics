package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PacketProbe;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Inbound packets handled on the server thread: wraps {@code PacketProcessor$ListenerAndPacket.handle}, run from
 * {@code MinecraftServer.runAllTasksAtTickStart} (tick start) and {@code pollTask} (between ticks, marked by
 * {@code PacketMixinServer}). Inclusive: plugin events fired by the packet and packets sent in response are inside it.
 */
@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket", remap = false)
abstract class PacketMixinInbound {
    @Shadow @Final private Packet<?> packet;

    @WrapMethod(method = "handle()V", require = 1, allow = 1)
    private void diagnostics$handle(Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.current();
        if (session == null || packet == null) { original.call(); return; }
        if (Thread.currentThread() != session.serverThread) { session.handledOffThread(); original.call(); return; }
        int slot = PacketProbe.slot(packet.getClass());
        long started = session.now();
        try { original.call(); }
        finally { session.handled(slot, started, session.now()); }
    }
}
