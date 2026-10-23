package com.sande.mythictrpg.client.accessory;

import com.mojang.blaze3d.platform.InputConstants;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.accessory.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid=MythicTrpg.MOD_ID,value=Dist.CLIENT)
public final class AccessoryClient{
    private static final KeyMapping OPEN=new KeyMapping("장신구",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_O,"key.categories.mythictrpg");
    private static boolean jumpDown;
    private AccessoryClient(){}
    private static void open(){PacketDistributor.sendToServer(new AccessoryRequest(AccessoryRequest.NONE,0,AccessoryRequest.Action.OPEN,0,"",0));}
    @SubscribeEvent public static void inventory(ScreenEvent.Init.Post event){if(event.getScreen()instanceof InventoryScreen screen)
        event.addListener(Button.builder(Component.literal("장신구 관리"),b->open()).bounds(Math.max(0,screen.width-98),8,90,20).build());}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        var client=Minecraft.getInstance();while(OPEN.consumeClick())if(client.player!=null)open();
        boolean down=client.options.keyJump.isDown();
        if(down&&!jumpDown&&client.screen==null&&client.player!=null&&client.player.isAlive()&&!client.player.onGround())
            PacketDistributor.sendToServer(new AccessoryRequest(AccessoryRequest.NONE,0,AccessoryRequest.Action.JUMP,0,"",0));
        jumpDown=down;
    }
    @SubscribeEvent public static void left(ClientPlayerNetworkEvent.LoggingOut event){jumpDown=false;}
    @EventBusSubscriber(modid=MythicTrpg.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Registration{
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event){event.register(OPEN);}
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->AccessoryNetwork.receiver(page->{
            var client=Minecraft.getInstance();if(page.open())client.setScreen(new AccessoryScreen(page));
            else if(client.screen instanceof AccessoryScreen screen)screen.receive(page);
        }));}
    }
}
