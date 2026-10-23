package com.sande.mythictrpg.client.accessory;

import com.sande.mythictrpg.accessory.AccessoryInventory;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;

import javax.annotation.Nullable;
import java.util.List;

/** Creative's personal-inventory tab uses the same authoritative inventory transaction as survival. */
public final class AccessoryCreativeInventory {
    private static final int SLOT_X=135;
    private static final int FIRST_SLOT_Y=6;
    private static final int SLOT_PITCH=27;
    private static final Component RETURN_CURSOR=Component.literal("손에 든 아이템을 빈 칸에 놓은 뒤 탭을 바꿔 주세요.");
    private static final Component TRASH_NOTICE=Component.literal("이 탭에서는 아이템을 빈 칸에 놓거나 화면 밖으로 드롭해 주세요.");
    private AccessoryCreativeInventory() { }

    public static int slotX(){return SLOT_X;}
    public static int slotY(int logical){return FIRST_SLOT_Y+logical*SLOT_PITCH;}

    /** Catalog and personal-inventory cursors use different authorities. Finish the move before crossing. */
    public static boolean keepInventoryTab(CreativeModeInventoryScreen screen,CreativeModeTab requested) {
        var player=Minecraft.getInstance().player;
        boolean wasInventory=screen.isInventoryOpen(),willBeInventory=requested.getType()==CreativeModeTab.Type.INVENTORY;
        if(wasInventory==willBeInventory||player==null
                ||player.inventoryMenu.getCarried().isEmpty())return false;
        player.displayClientMessage(RETURN_CURSOR,true);return true;
    }

    /** Return true to consume the creative path, including its client-only deletion and creative-set packets. */
    public static boolean click(CreativeModeInventoryScreen screen,@Nullable Slot slot,int fallbackSlotId,
                                int button,ClickType type,@Nullable Slot trash) {
        if(!screen.isInventoryOpen())return false;
        var client=Minecraft.getInstance();var player=client.player;
        if(player==null||player.connection==null)return true;
        if(slot==trash&&slot!=null) {
            player.displayClientMessage(TRASH_NOTICE,true);return true;
        }
        int index=fallbackSlotId;
        if(slot!=null) {
            // Vanilla inserts SlotWrapper with slots.add(), so wrapper.index is NOT its menu index.
            int wrapperIndex=screen.getMenu().slots.indexOf(slot);
            if(wrapperIndex<0||wrapperIndex>=player.inventoryMenu.slots.size())return true;
            index=player.inventoryMenu.slots.get(wrapperIndex).index;
        }
        if(index>=player.inventoryMenu.slots.size()||index<0&&index!=-1&&index!=-999)return true;
        // Deliberately omit prediction. The server validates entitlement, cursor, and both equipment slots,
        // then sends complete inventory contents (including the carried stack) back to this client.
        player.connection.send(new ServerboundContainerClickPacket(player.inventoryMenu.containerId,
                player.inventoryMenu.getStateId(),index,button,type,player.inventoryMenu.getCarried().copy(),Int2ObjectMaps.emptyMap()));
        return true;
    }

    public static void background(CreativeModeInventoryScreen screen,GuiGraphics graphics) {
        var player=Minecraft.getInstance().player;
        if(!screen.isInventoryOpen()||player==null)return;
        for(int logical=0;logical<2;logical++) {
            int index=AccessoryInventory.slotIndex(player.inventoryMenu,logical);
            if(index<0)continue;
            AccessoryInventoryDecoration.drawSlot(graphics,screen.getGuiLeft()+slotX(),screen.getGuiTop()+slotY(logical),
                    !player.inventoryMenu.getSlot(index).hasItem());
        }
    }

    public static void tooltip(CreativeModeInventoryScreen screen,GuiGraphics graphics,int mouseX,int mouseY,@Nullable Slot trash) {
        var client=Minecraft.getInstance();var player=client.player;
        if(!screen.isInventoryOpen()||player==null)return;
        for(int logical=0;logical<2;logical++) {
            int index=AccessoryInventory.slotIndex(player.inventoryMenu,logical);
            if(index<0||player.inventoryMenu.getSlot(index).hasItem())continue;
            if(hovered(mouseX,mouseY,screen.getGuiLeft()+slotX(),screen.getGuiTop()+slotY(logical))) {
                AccessoryInventoryDecoration.tooltip(graphics,client.font,logical,mouseX,mouseY);return;
            }
        }
        if(trash!=null&&hovered(mouseX,mouseY,screen.getGuiLeft()+trash.x,screen.getGuiTop()+trash.y))
            graphics.renderComponentTooltip(client.font,List.of(Component.literal("아이템 이동"),TRASH_NOTICE),mouseX,mouseY);
    }

    private static boolean hovered(int mouseX,int mouseY,int x,int y){return mouseX>=x-1&&mouseX<x+17&&mouseY>=y-1&&mouseY<y+17;}
}
