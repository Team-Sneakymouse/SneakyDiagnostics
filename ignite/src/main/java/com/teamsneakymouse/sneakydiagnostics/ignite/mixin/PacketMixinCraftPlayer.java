package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PacketProbe;
import org.bukkit.Particle;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.spongepowered.asm.mixin.Mixin;

/** {@code particles} cause for {@code Player.spawnParticle}: every API overload ends in this CraftPlayer method. */
@Mixin(value = CraftPlayer.class, remap = false)
abstract class PacketMixinCraftPlayer {
    @WrapMethod(method = "spawnParticle(Lorg/bukkit/Particle;DDDIDDDDLjava/lang/Object;Z)V", require = 1, allow = 1)
    private void diagnostics$particle(Particle particle, double x, double y, double z, int count, double dx, double dy, double dz,
                                      double extra, Object data, boolean force, Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(particle, x, y, z, count, dx, dy, dz, extra, data, force); return; }
        int frame = session.enter(PacketProbe.PARTICLES, 0);
        try { original.call(particle, x, y, z, count, dx, dy, dz, extra, data, force); }
        finally { session.exit(frame); }
    }
}
