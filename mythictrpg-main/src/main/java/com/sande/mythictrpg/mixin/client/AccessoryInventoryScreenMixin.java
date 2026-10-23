package com.sande.mythictrpg.mixin.client;

import com.sande.mythictrpg.accessory.AccessoryInventory;
import com.sande.mythictrpg.client.accessory.AccessoryInventoryDecoration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryScreen.class)
abstract class AccessoryInventoryScreenMixin {
    @Shadow private boolean widthTooNarrow;
    @Shadow @Final private RecipeBookComponent recipeBookComponent;

    @Inject(method="renderBg", at=@At("TAIL"))
    private void mythictrpg$accessoryFrames(GuiGraphics g, float partial, int mouseX, int mouseY, CallbackInfo ci) {
        var screen = (InventoryScreen)(Object)this;
        for (int logical=0; logical<2; logical++) {
            int index = AccessoryInventory.slotIndex(screen.getMenu(), logical);
            if (index<0) continue;
            var slot = screen.getMenu().getSlot(index);
            AccessoryInventoryDecoration.drawSlot(g, screen.getGuiLeft()+slot.x, screen.getGuiTop()+slot.y, !slot.hasItem());
        }
    }

    @Inject(method="render", at=@At("TAIL"))
    private void mythictrpg$accessoryEmptyTooltip(GuiGraphics g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
        if (widthTooNarrow && recipeBookComponent.isVisible()) return;
        var screen = (InventoryScreen)(Object)this;
        for (int logical=0; logical<2; logical++) {
            int index = AccessoryInventory.slotIndex(screen.getMenu(), logical);
            if (index<0) continue;
            var slot = screen.getMenu().getSlot(index);
            int x=screen.getGuiLeft()+slot.x, y=screen.getGuiTop()+slot.y;
            if (!slot.hasItem() && mouseX>=x && mouseX<x+16 && mouseY>=y && mouseY<y+16)
                AccessoryInventoryDecoration.tooltip(g, Minecraft.getInstance().font, logical, mouseX, mouseY);
        }
    }
}
