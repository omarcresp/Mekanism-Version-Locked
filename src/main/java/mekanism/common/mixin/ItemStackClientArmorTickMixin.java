package mekanism.common.mixin;

import mekanism.common.item.gear.ItemMekaSuitArmor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/// ItemStack#inventoryTick only forwards to the item on a ServerLevel, so worn MekaSuit modules would never receive their client tick. Only the local player is
/// ticked, as remote players are not ticked through their equipment on the client.
@Mixin(ItemStack.class)
public abstract class ItemStackClientArmorTickMixin {

    @Inject(method = "inventoryTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/EquipmentSlot;)V", at = @At("TAIL"))
    private void mekanism$tickWornMekaSuitModules(Level level, Entity owner, @Nullable EquipmentSlot slot, CallbackInfo ci) {
        ItemStack stack = (ItemStack) (Object) this;
        if (owner instanceof LocalPlayer && stack.getItem() instanceof ItemMekaSuitArmor) {
            try (Transaction transaction = Transaction.openRoot()) {
                ItemMekaSuitArmor.tickWornModules(stack, owner, slot, transaction);
            }
        }
    }
}
