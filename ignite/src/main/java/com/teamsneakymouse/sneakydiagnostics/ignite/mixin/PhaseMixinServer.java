package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PhaseProbe;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PhaseWorld;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tick boundaries and the server-level phases: a row per tick from {@code processPacketsAndTick}, tick-start tasks,
 * each world's {@code ServerLevel.tick}, the connection tick, and the ServerTickEndEvent dispatch, where Paper's
 * measured duration is read. Idle: one volatile read per wrapped call.
 */
@Mixin(value = MinecraftServer.class, remap = false)
abstract class PhaseMixinServer {
    @Inject(method = "processPacketsAndTick(Z)V", at = @At("HEAD"), require = 1, allow = 1)
    private void diagnostics$tickBegin(boolean sprinting, CallbackInfo callback) {
        PhaseProbe.tickBegin(MinecraftServer.currentTick);     // runServer incremented it just before this call
    }

    @WrapOperation(method = "processPacketsAndTick(Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;runAllTasksAtTickStart()V"), require = 1, allow = 1)
    private void diagnostics$tickStartTasks(MinecraftServer server, Operation<Void> original) {
        PhaseProbe.Session session = PhaseProbe.active();
        if (session == null) { original.call(server); return; }
        int frame = session.enter(PhaseProbe.TICK_START_TASKS);
        try { original.call(server); }
        finally { session.exit(frame); }
    }

    @WrapOperation(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V"), require = 1, allow = 1)
    private void diagnostics$level(ServerLevel level, BooleanSupplier haveTime, Operation<Void> original) {
        PhaseProbe.Session session = PhaseProbe.active();
        if (session == null) { original.call(level, haveTime); return; }
        int frame = session.enter(PhaseProbe.Session.level(((PhaseWorld) level).diagnostics$phaseWorld(session), PhaseProbe.LEVEL_OTHER));
        try { original.call(level, haveTime); }
        finally { session.exit(frame); }
    }

    @WrapOperation(method = "tickConnection()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerConnectionListener;tick()V"), require = 1, allow = 1)
    private void diagnostics$connections(ServerConnectionListener connections, Operation<Void> original) {
        PhaseProbe.Session session = PhaseProbe.active();
        if (session == null) { original.call(connections); return; }
        int frame = session.enter(PhaseProbe.CONNECTIONS);
        try { original.call(connections); }
        finally { session.exit(frame); }
    }

    /** Paper read the tick duration just before this dispatch: phases so far are inside it, the dispatch is not. */
    @WrapOperation(method = "tickServer(Ljava/util/function/BooleanSupplier;)V", at = @At(value = "INVOKE",
            target = "Lcom/destroystokyo/paper/event/server/ServerTickEndEvent;callEvent()Z"), require = 1, allow = 1)
    private boolean diagnostics$tickEnd(ServerTickEndEvent event, Operation<Boolean> original) {
        PhaseProbe.Session session = PhaseProbe.tickEnd(event.getTickDuration());
        if (session == null) return original.call(event);
        int frame = session.enter(PhaseProbe.TICK_END);
        try { return original.call(event); }
        finally {
            session.exit(frame);
            session.tickEndDone();
        }
    }
}
