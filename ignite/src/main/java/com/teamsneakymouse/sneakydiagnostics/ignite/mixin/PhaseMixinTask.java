package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PhaseProbe;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** SneakyDiagnostics' own scheduler tasks (census slices) are the {@code diagnostics} phase. */
@Mixin(targets = "org.bukkit.craftbukkit.scheduler.CraftTask", remap = false)
abstract class PhaseMixinTask {
    /** 0: not yet known, 1: a SneakyDiagnostics task, 2: another owner. The owner never changes. */
    @Unique private byte diagnostics$phaseOwner;

    @WrapMethod(method = "run()V", require = 1, allow = 1)
    private void diagnostics$phaseRun(Operation<Void> original) {
        PhaseProbe.Session session = PhaseProbe.active();
        if (session == null) { original.call(); return; }
        byte owner = diagnostics$phaseOwner;
        if (owner == 0) {
            Plugin plugin = ((BukkitTask) (Object) this).getOwner();
            diagnostics$phaseOwner = owner = plugin != null && PhaseProbe.DIAGNOSTICS_PLUGIN.equals(plugin.getName()) ? (byte) 1 : (byte) 2;
        }
        if (owner != 1) { original.call(); return; }
        int frame = session.enter(session.diagnosticsSlot());
        try { original.call(); }
        finally { session.exit(frame); }
    }
}
