package com.sande.mythictrpg.mixin.client;

import com.sande.mythictrpg.accessory.AccessoryInventory;
import com.sande.mythictrpg.client.accessory.AccessoryCreativeInventory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import javax.annotation.Nullable;

/** Keep the creative catalog intact; its personal inventory uses the normal server inventory authority. */
@Mixin(CreativeModeInventoryScreen.class)
abstract class AccessoryCreativeInventoryMixin {
    @Shadow @Nullable private Slot destroyItemSlot;
    @Unique private boolean mythictrpg$rebuildingInventory;

    @Inject(method="init",at=@At("HEAD"),require=1)
    private void mythictrpg$beginInventoryRebuild(CallbackInfo ci) {
        mythictrpg$rebuildingInventory=true;
    }

    @Inject(method="init",at=@At("RETURN"),require=1)
    private void mythictrpg$finishInventoryRebuild(CallbackInfo ci) {
        mythictrpg$rebuildingInventory=false;
    }

    @Inject(method="selectTab",at=@At("HEAD"),cancellable=true,require=1)
    private void mythictrpg$finishCursorBeforeCatalog(CreativeModeTab requested,CallbackInfo ci) {
        if(!mythictrpg$rebuildingInventory&&AccessoryCreativeInventory.keepInventoryTab((CreativeModeInventoryScreen)(Object)this,requested))ci.cancel();
    }

    @ModifyArgs(method="selectTab",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen$SlotWrapper;<init>(Lnet/minecraft/world/inventory/Slot;III)V"),require=1)
    private void mythictrpg$accessorySlotPosition(Args args) {
        Slot original=args.get(0);int logical=AccessoryInventory.logicalSlot(original);
        if(logical>=0) {args.set(2,AccessoryCreativeInventory.slotX());args.set(3,AccessoryCreativeInventory.slotY(logical));}
    }

    @Inject(method="slotClicked",at=@At("HEAD"),cancellable=true,require=1)
    private void mythictrpg$authoritativeInventoryClick(@Nullable Slot slot,int slotId,int button,ClickType type,CallbackInfo ci) {
        if(AccessoryCreativeInventory.click((CreativeModeInventoryScreen)(Object)this,slot,slotId,button,type,destroyItemSlot))ci.cancel();
    }

    @Inject(method="renderBg",at=@At("TAIL"),require=1)
    private void mythictrpg$accessoryBackground(GuiGraphics graphics,float partialTick,int mouseX,int mouseY,CallbackInfo ci) {
        AccessoryCreativeInventory.background((CreativeModeInventoryScreen)(Object)this,graphics);
    }

    @Inject(method="render",at=@At("TAIL"),require=1)
    private void mythictrpg$accessoryTooltip(GuiGraphics graphics,int mouseX,int mouseY,float partialTick,CallbackInfo ci) {
        AccessoryCreativeInventory.tooltip((CreativeModeInventoryScreen)(Object)this,graphics,mouseX,mouseY,destroyItemSlot);
    }
}
