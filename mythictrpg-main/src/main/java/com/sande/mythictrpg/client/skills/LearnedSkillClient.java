package com.sande.mythictrpg.client.skills;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.skills.*;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid=MythicTrpg.MOD_ID,value=Dist.CLIENT)
public final class LearnedSkillClient {
    private static final KeyMapping BOOK=new KeyMapping("습득 스킬 목록",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_K,"key.categories.mythictrpg");
    private static final KeyMapping USE=new KeyMapping("선택한 습득 스킬 사용",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_J,"key.categories.mythictrpg");
    private LearnedSkillClient() { }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        var c=Minecraft.getInstance();
        while(BOOK.consumeClick())if(c.player!=null&&c.screen==null)PacketDistributor.sendToServer(new SkillBookRequest(SkillBookRequest.Action.OPEN,0,""));
        while(USE.consumeClick())if(c.player!=null&&c.screen==null)PacketDistributor.sendToServer(new SkillBookRequest(SkillBookRequest.Action.USE_SELECTED,0,""));
    }
    @EventBusSubscriber(modid=MythicTrpg.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event){event.register(BOOK);event.register(USE);}
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->SkillBookNetwork.receiver(page->{
            var c=Minecraft.getInstance();if(c.player==null)return;
            if(page.open())c.setScreen(new SkillBookScreen(page));else if(c.screen instanceof SkillBookScreen screen)screen.receive(page);
        }));}
    }
}
