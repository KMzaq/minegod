package com.sande.mythictrpg.client.shop;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.network.ClientShopBridge;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = MythicTrpg.MOD_ID, value = Dist.CLIENT)
public final class ShopClientEvents {
    private ShopClientEvents() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ClientShopBridge.install(payload -> Minecraft.getInstance().execute(() ->
                Minecraft.getInstance().setScreen(new ShopScreen(payload)))));
    }
}
