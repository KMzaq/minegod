package com.sande.mythictrpg.client.dialogue;

import com.sande.mythictrpg.MythicTrpg;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = MythicTrpg.MOD_ID, value = Dist.CLIENT)
public final class DialogueClientGameEvents {
    private DialogueClientGameEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        DialogueHudController.INSTANCE.tick();
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        DialogueHudController.INSTANCE.reset();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        DialogueHudController.INSTANCE.reset();
    }
}
