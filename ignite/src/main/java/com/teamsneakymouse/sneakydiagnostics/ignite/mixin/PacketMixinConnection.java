package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PacketProbe;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Outbound packets: wraps {@code Connection.send(Packet, ChannelFutureListener, boolean)}, which the one- and
 * two-argument overloads delegate to (contract-checked). On the server thread the time inside {@code send} is
 * inclusive: the ProtocolLib proxy, the Netty hand-off or queueing happen below it. Other threads are timed under a
 * lock and reported apart. A bundle counts once as a send; its sub-packets are counted per class afterwards, outside
 * the timed interval.
 */
@Mixin(value = Connection.class, remap = false)
abstract class PacketMixinConnection {
    @WrapMethod(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", require = 1, allow = 1)
    private void diagnostics$send(Packet<?> packet, ChannelFutureListener listener, boolean flush, Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.current();
        if (session == null || packet == null) { original.call(packet, listener, flush); return; }
        int slot = PacketProbe.slot(packet.getClass());
        if (Thread.currentThread() != session.serverThread) {
            long started = session.now();
            try { original.call(packet, listener, flush); }
            finally { session.sentOffThread(slot, started, session.now()); }
            return;
        }
        int outer = session.sendBegin();
        long started = session.now();
        try { original.call(packet, listener, flush); }
        finally {
            session.sent(slot, started, session.now(), outer);
            if (packet instanceof BundlePacket<?> bundle) diagnostics$bundle(session, bundle);
        }
    }

    @Unique
    private static void diagnostics$bundle(PacketProbe.Session session, BundlePacket<?> bundle) {
        session.bundle();
        for (Packet<?> sub : bundle.subPackets()) if (sub != null) session.bundled(PacketProbe.slot(sub.getClass()));
    }
}
