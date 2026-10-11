package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.SchedulerControl;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.SchedulerProbe;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.TaskDetails;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "org.bukkit.craftbukkit.scheduler.CraftScheduler", remap = false)
abstract class SchedulerMixin {
    @Shadow @Final private ConcurrentHashMap<Integer, BukkitTask> runners;

    @Inject(method = "<init>(Z)V", at = @At("RETURN"), require = 1, allow = 1)
    private void diagnostics$initialize(boolean async, CallbackInfo callback) { SchedulerControl.initialize(); }

    @WrapMethod(method = "cancelTask(I)V", require = 1, allow = 1)
    private void diagnostics$cancelTask(int id, Operation<Void> original) {
        if (!SchedulerProbe.enabled()) { original.call(id); return; }
        BukkitTask target = runners.get(id);
        SchedulerProbe.Identity hint = target == null || target.getOwner() == null ? null
                : new SchedulerProbe.Identity(target.getOwner().getName(), ((TaskDetails) target).diagnosticsTaskClass(), target.isSync());
        var cancellation = SchedulerProbe.beginCancellation(id, hint);
        boolean throwing = false;
        try { original.call(id); }
        catch (RuntimeException | Error e) { throwing = true; throw e; }
        finally { SchedulerProbe.endCancellation(cancellation, throwing); }
    }

    @WrapMethod(method = "cancelTasks(Lorg/bukkit/plugin/Plugin;)V", require = 1, allow = 1)
    private void diagnostics$cancelPlugin(Plugin owner, Operation<Void> original) {
        if (!SchedulerProbe.enabled() || owner == null) { original.call(owner); return; }
        var cancellation = SchedulerProbe.beginCancellation(-1, new SchedulerProbe.Identity(owner.getName(), "<plugin-wide>", true));
        boolean throwing = false;
        try { original.call(owner); }
        catch (RuntimeException | Error e) { throwing = true; throw e; }
        finally { SchedulerProbe.endCancellation(cancellation, throwing); }
    }
}
