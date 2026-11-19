package com.teamsneakymouse.sneakydiagnostics.ignite.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.teamsneakymouse.sneakydiagnostics.ignite.probe.PacketProbe;
import java.util.Map;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** {@code entity.event} causes on {@code LivingEntity}: equipment-change and swing broadcasts. */
@Mixin(value = LivingEntity.class, remap = false)
abstract class PacketMixinLiving {
    @WrapMethod(method = "handleEquipmentChanges(Ljava/util/Map;)V", require = 1, allow = 1)
    private void diagnostics$equipment(Map<EquipmentSlot, ItemStack> changes, Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(changes); return; }
        int frame = session.enter(PacketProbe.ENTITY_EVENT, 0);
        try { original.call(changes); }
        finally { session.exit(frame); }
    }

    @WrapMethod(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", require = 1, allow = 1)
    private void diagnostics$swing(InteractionHand hand, boolean updateSelf, Operation<Void> original) {
        PacketProbe.Session session = PacketProbe.active();
        if (session == null) { original.call(hand, updateSelf); return; }
        int frame = session.enter(PacketProbe.ENTITY_EVENT, 0);
        try { original.call(hand, updateSelf); }
        finally { session.exit(frame); }
    }
}
