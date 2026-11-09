package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.SchedulerProbe;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.RegisteredListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Times one listener's executor call, but keeps only slow ones (see {@link SchedulerProbe#listenerStart}): no labels
 * are read and nothing is allocated for fast calls. Cancelled events a listener ignores never reach the executor.
 */
@Mixin(value = RegisteredListener.class, remap = false)
abstract class ListenerMixin {
    @WrapOperation(method = "callEvent(Lorg/bukkit/event/Event;)V", at = @At(value = "INVOKE",
            target = "Lorg/bukkit/plugin/EventExecutor;execute(Lorg/bukkit/event/Listener;Lorg/bukkit/event/Event;)V"), require = 1, allow = 1)
    private void diagnostics$listener(EventExecutor executor, Listener listener, Event event, Operation<Void> original) throws Exception {
        long started = SchedulerProbe.listenerStart();
        if (started == 0) { original.call(executor, listener, event); return; }
        if (event instanceof ServerTickEndEvent tickEnd) SchedulerProbe.tickEndDispatch(started, tickEnd.getTickDuration());
        boolean throwing = false;
        try { original.call(executor, listener, event); }
        catch (Exception | Error e) { throwing = true; throw e; }     // the executor's EventException passes through unchanged
        finally {
            long elapsed = SchedulerProbe.listenerEnd(started);
            if (elapsed >= 0) {
                RegisteredListener registration = (RegisteredListener) (Object) this;
                SchedulerProbe.slowListener(started, elapsed, registration.getPlugin().getName(), listener.getClass().getName(),
                        event.getClass().getName(), registration.getPriority().name(), event.isAsynchronous(), throwing);
            }
        }
    }
}
