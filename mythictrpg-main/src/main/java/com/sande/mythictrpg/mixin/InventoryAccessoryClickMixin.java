package com.sande.mythictrpg.mixin;

import com.sande.mythictrpg.accessory.AccessoryInventory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Equipment transactions validate against the server ledger before moving a physical token. */
@Mixin(AbstractContainerMenu.class)
abstract class InventoryAccessoryClickMixin {
    @Inject(method="clicked", at=@At("HEAD"), cancellable=true)
    private void mythictrpg$accessoryClick(int index, int button, ClickType type, Player player, CallbackInfo ci) {
        if ((Object)this instanceof InventoryMenu menu && AccessoryInventory.handleClick(menu, index, button, type, player)) {
            if (player instanceof ServerPlayer serverPlayer && serverPlayer.containerMenu == menu) menu.broadcastFullState();
            ci.cancel();
        }
    }

    @Inject(method="clicked", at=@At("RETURN"))
    private void mythictrpg$creativeInventoryCursor(int index, int button, ClickType type, Player player, CallbackInfo ci) {
        // Creative ignores vanilla cursor-only corrections; its inventory tab uses the same authoritative menu.
        if ((Object)this instanceof InventoryMenu menu && player instanceof ServerPlayer serverPlayer
                && serverPlayer.containerMenu == menu && player.isCreative()) menu.broadcastFullState();
    }
}
