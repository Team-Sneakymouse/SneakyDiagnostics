package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.SchedulerProbe;
import java.util.concurrent.Callable;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "org.bukkit.craftbukkit.scheduler.CraftFuture", remap = false)
abstract class FutureMixin {
    @Shadow @Final private Callable<?> callable;

    @Inject(method = "<init>(Ljava/util/concurrent/Callable;Lorg/bukkit/plugin/Plugin;I)V", at = @At("RETURN"), require = 1, allow = 1)
    private void diagnostics$created(Callable<?> body, Plugin owner, int id, CallbackInfo callback) {
        if (SchedulerProbe.enabled()) SchedulerProbe.created(id, new SchedulerProbe.Identity(owner.getName(), body.getClass().getName(), true));
    }

    @WrapOperation(method = "run()V", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/Callable;call()Ljava/lang/Object;"), require = 1, allow = 1)
    private Object diagnostics$execution(Callable<?> body, Operation<Object> original) throws Exception {
        if (!SchedulerProbe.enabled()) return original.call(body);
        BukkitTask task = (BukkitTask) this;
        var token = SchedulerProbe.execution(new SchedulerProbe.Identity(task.getOwner().getName(), callable.getClass().getName(), true));
        boolean throwing = false;
        try { return original.call(body); }
        catch (Exception | Error e) { throwing = true; throw e; }
        finally { SchedulerProbe.finish(token, throwing); }
    }

    @WrapMethod(method = "cancel(Z)Z", require = 1, allow = 1)
    private boolean diagnostics$cancel(boolean interrupt, Operation<Boolean> original) {
        if (!SchedulerProbe.enabled()) return original.call(interrupt);
        BukkitTask task = (BukkitTask) this;
        var cancellation = SchedulerProbe.beginCancellation(task.getTaskId(),
                new SchedulerProbe.Identity(task.getOwner().getName(), callable.getClass().getName(), true));
        boolean throwing = false;
        try { return original.call(interrupt); }
        catch (RuntimeException | Error e) { throwing = true; throw e; }
        finally { SchedulerProbe.endCancellation(cancellation, throwing); }
    }
}
