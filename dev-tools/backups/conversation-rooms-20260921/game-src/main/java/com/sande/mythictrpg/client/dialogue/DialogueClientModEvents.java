package com.sande.mythictrpg.client.dialogue;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.network.ClientDialogueBridge;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

@EventBusSubscriber(modid = MythicTrpg.MOD_ID, value = Dist.CLIENT)
public final class DialogueClientModEvents {
    private static final ResourceLocation DIALOGUE_LAYER = ResourceLocation.fromNamespaceAndPath(
            MythicTrpg.MOD_ID, "dialogue_hud");

    private DialogueClientModEvents() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ClientDialogueBridge.install(DialogueHudController.INSTANCE::receive));
    }

    @SubscribeEvent
    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CHAT, DIALOGUE_LAYER, DialogueHudRenderer::render);
    }
}
