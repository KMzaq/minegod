package com.sande.mythictrpg.client.raid;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.network.ClientRaidBridge;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = MythicTrpg.MOD_ID, value = Dist.CLIENT)
public final class RaidClientEvents {
    private RaidClientEvents() { }
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ClientRaidBridge.install(page -> {
            Minecraft client = Minecraft.getInstance();
            if (client.screen instanceof RaidScreen screen) screen.receive(page);
            else if (page.open()) client.setScreen(new RaidScreen(page));
            // Late refresh/action responses never reopen a screen the player has closed.
        }));
    }
}
