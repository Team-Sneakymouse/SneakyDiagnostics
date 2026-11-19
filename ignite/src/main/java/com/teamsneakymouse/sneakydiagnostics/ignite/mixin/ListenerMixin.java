package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.ListenerTotals;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.SchedulerProbe;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.RegisteredListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Times one listener's executor call. Every call is added to the capture's listener totals through a slot cached on
 * this registration (one entry per event class and async flag), so steady-state calls read no labels, do no map lookup
 * and allocate nothing; only slow calls build a record (see {@link SchedulerProbe#listenerStart}). Idle: one volatile
 * read. Cancelled events a listener ignores never reach the executor.
 */
@Mixin(value = RegisteredListener.class, remap = false)
abstract class ListenerMixin {
    /** Totals slots of the last capture this registration was called in; replaced wholesale for a new capture. */
    @Unique private ListenerTotals.Node diagnostics$slots;

    @WrapOperation(method = "callEvent(Lorg/bukkit/event/Event;)V", at = @At(value = "INVOKE",
            target = "Lorg/bukkit/plugin/EventExecutor;execute(Lorg/bukkit/event/Listener;Lorg/bukkit/event/Event;)V"), require = 1, allow = 1)
    private void diagnostics$listener(EventExecutor executor, Listener listener, Event event, Operation<Void> original) throws Exception {
        long started = SchedulerProbe.listenerStart();
        if (started == 0) { original.call(executor, listener, event); return; }
        if (event instanceof ServerTickEndEvent tickEnd) SchedulerProbe.tickEndDispatch(started, tickEnd.getTickDuration());
        RegisteredListener registration = (RegisteredListener) (Object) this;
        Class<?> type = event.getClass();
        boolean async = event.isAsynchronous();
        ListenerTotals totals = SchedulerProbe.listenerTotals();
        ListenerTotals.Node cache = diagnostics$slots;
        ListenerTotals.Slot slot = ListenerTotals.find(cache, totals, type, async);
        if (slot == null && totals != null) {       // first call of this key in the capture: labels are read once here
            slot = totals.slot(registration.getPlugin().getName(), listener.getClass().getName(), type,
                    registration.getPriority().name(), async);
            if (slot != null) diagnostics$slots = ListenerTotals.push(cache, slot, type, async);
        }
        boolean throwing = false;
        try { original.call(executor, listener, event); }
        catch (Exception | Error e) { throwing = true; throw e; }     // the executor's EventException passes through unchanged
        finally {
            long elapsed = SchedulerProbe.listenerEnd(started, slot);
            if (elapsed >= 0) {
                SchedulerProbe.slowListener(started, elapsed, registration.getPlugin().getName(), listener.getClass().getName(),
                        event.getClass().getName(), registration.getPriority().name(), event.isAsynchronous(), throwing);
            }
        }
    }
}
