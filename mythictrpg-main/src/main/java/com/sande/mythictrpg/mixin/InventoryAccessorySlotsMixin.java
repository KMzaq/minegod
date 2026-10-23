package com.sande.mythictrpg.mixin;

import com.sande.mythictrpg.accessory.AccessoryInventory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Append equipment views without renumbering the vanilla crafting, armor, inventory, or offhand slots. */
@Mixin(InventoryMenu.class)
abstract class InventoryAccessorySlotsMixin extends AbstractContainerMenu {
    protected InventoryAccessorySlotsMixin(MenuType<?> type, int id) { super(type, id); }

    @Inject(method="<init>", at=@At("TAIL"))
    private void mythictrpg$accessorySlots(Inventory inventory, boolean active, Player owner, CallbackInfo ci) {
        // Player's constructor creates this menu before ServerPlayer.server is assigned. Slot state is lazy.
        addSlot(AccessoryInventory.slot(owner, 0, 77, 8));
        addSlot(AccessoryInventory.slot(owner, 1, 77, 26));
    }

    @Inject(method="quickMoveStack", at=@At("HEAD"), cancellable=true)
    private void mythictrpg$accessoryQuickMove(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        if (AccessoryInventory.handleClick((InventoryMenu)(Object)this, index, 0, ClickType.QUICK_MOVE, player))
            cir.setReturnValue(ItemStack.EMPTY);
    }
}
