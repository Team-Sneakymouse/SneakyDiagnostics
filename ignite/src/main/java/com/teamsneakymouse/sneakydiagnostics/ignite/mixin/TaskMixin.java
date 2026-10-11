package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.SchedulerProbe;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.TaskDetails;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "org.bukkit.craftbukkit.scheduler.CraftTask", remap = false)
abstract class TaskMixin implements TaskDetails {
    @Shadow abstract Class<?> getTaskClass();
    @Unique private SchedulerProbe.Cancellation diagnostics$origin;

    @Override public String diagnosticsTaskClass() {
        Class<?> type = getTaskClass();
        return type == null ? getClass().getName() : type.getName();
    }

    @Inject(method = "<init>(Lorg/bukkit/plugin/Plugin;Ljava/lang/Object;IJ)V", at = @At("RETURN"), require = 1, allow = 1)
    private void diagnostics$created(Plugin owner, Object body, int id, long period, CallbackInfo callback) {
        if (owner == null && body != null) diagnostics$origin = SchedulerProbe.cancellationOrigin();
        if (owner != null && body != null && SchedulerProbe.enabled()) {
            SchedulerProbe.created(id, new SchedulerProbe.Identity(owner.getName(), body.getClass().getName(), ((BukkitTask) this).isSync()));
        }
    }

    @WrapMethod(method = "run()V", require = 1, allow = 1)
    private void diagnostics$execution(Operation<Void> original) {
        if (!SchedulerProbe.enabled()) { original.call(); return; }
        BukkitTask task = (BukkitTask) this;
        SchedulerProbe.Token token = diagnostics$origin != null ? SchedulerProbe.deferred(diagnostics$origin)
                : task.getOwner() == null ? null : SchedulerProbe.execution(new SchedulerProbe.Identity(task.getOwner().getName(), diagnosticsTaskClass(), task.isSync()));
        boolean throwing = false;
        try { original.call(); }
        catch (RuntimeException | Error e) { throwing = true; throw e; }
        finally { SchedulerProbe.finish(token, throwing); }
    }
}
