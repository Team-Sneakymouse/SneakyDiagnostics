package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PhaseProbe;
import org.spongepowered.asm.mixin.Mixin;

/** The {@code scheduler} phase: Bukkit's main-thread heartbeat (sync tasks), minus nested phases such as diagnostics' own tasks. */
@Mixin(targets = "org.bukkit.craftbukkit.scheduler.CraftScheduler", remap = false)
abstract class PhaseMixinScheduler {
    @WrapMethod(method = "mainThreadHeartbeat()V", require = 1, allow = 1)
    private void diagnostics$heartbeat(Operation<Void> original) {
        PhaseProbe.Session session = PhaseProbe.active();
        if (session == null) { original.call(); return; }
        int frame = session.enter(PhaseProbe.SCHEDULER);
        try { original.call(); }
        finally { session.exit(frame); }
    }
}
