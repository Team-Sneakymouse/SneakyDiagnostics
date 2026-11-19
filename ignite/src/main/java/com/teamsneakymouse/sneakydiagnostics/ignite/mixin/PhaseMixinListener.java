package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PhaseProbe;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.RegisteredListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SneakyDiagnostics' own listeners (timeline sampling, scheduler snapshots, spawn counts, stops) are the
 * {@code diagnostics} phase: inside the measured tick, or outside it for tick-end listeners.
 */
@Mixin(value = RegisteredListener.class, remap = false)
abstract class PhaseMixinListener {
    /** 0: not yet known, 1: a SneakyDiagnostics listener, 2: another owner. The owner never changes. */
    @Unique private byte diagnostics$phaseOwner;

    @WrapOperation(method = "callEvent(Lorg/bukkit/event/Event;)V", at = @At(value = "INVOKE",
            target = "Lorg/bukkit/plugin/EventExecutor;execute(Lorg/bukkit/event/Listener;Lorg/bukkit/event/Event;)V"), require = 1, allow = 1)
    private void diagnostics$phaseListener(EventExecutor executor, Listener listener, Event event, Operation<Void> original) throws Exception {
        PhaseProbe.Session session = PhaseProbe.active();
        if (session == null) { original.call(executor, listener, event); return; }
        byte owner = diagnostics$phaseOwner;
        if (owner == 0) {
            var plugin = ((RegisteredListener) (Object) this).getPlugin();
            diagnostics$phaseOwner = owner = plugin != null && PhaseProbe.DIAGNOSTICS_PLUGIN.equals(plugin.getName()) ? (byte) 1 : (byte) 2;
        }
        if (owner != 1) { original.call(executor, listener, event); return; }
        int frame = session.enter(session.diagnosticsSlot());
        try { original.call(executor, listener, event); }
        finally { session.exit(frame); }
    }
}
