package com.sande.mythictrpg.client.understanding;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.understanding.UnderstandingNetwork;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = MythicTrpg.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class UnderstandingClient {
    private UnderstandingClient() { }
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> UnderstandingNetwork.receiver(page -> {
            var client = Minecraft.getInstance();
            if (page.open()) client.setScreen(new UnderstandingScreen(page));
            else if (client.screen instanceof UnderstandingScreen screen) screen.receive(page);
        }));
    }
}
