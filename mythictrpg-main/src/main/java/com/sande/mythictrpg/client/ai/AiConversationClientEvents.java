package com.sande.mythictrpg.client.ai;

import com.mojang.blaze3d.platform.InputConstants;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.network.ClientAiConversationBridge;
import com.sande.mythictrpg.network.ClientAiActionConfirmationBridge;
import com.sande.mythictrpg.network.ClientRewardChoiceBridge;
import com.sande.mythictrpg.network.ClientStoryChoiceBridge;
import com.sande.mythictrpg.client.quest.RewardChoiceClientController;
import com.sande.mythictrpg.client.story.StoryChoiceClientController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.lwjgl.glfw.GLFW;
import net.minecraft.resources.ResourceLocation;

@EventBusSubscriber(modid = MythicTrpg.MOD_ID, value = Dist.CLIENT)
public final class AiConversationClientEvents {
    private static final ResourceLocation HUD_LAYER = ResourceLocation.fromNamespaceAndPath(
            MythicTrpg.MOD_ID, "ai_conversation_hud");
    private static final KeyMapping TOGGLE = new KeyMapping("key.mythictrpg.toggle_ai_conversation",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.mythictrpg");
    private static final KeyMapping ROOMS = new KeyMapping("대화방 선택", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.mythictrpg");

    private AiConversationClientEvents() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> com.sande.mythictrpg.network.ClientConversationRoomsBridge.install(AiConversationHudController.INSTANCE::receiveRooms));
        event.enqueueWork(() -> ClientAiConversationBridge.install(
                AiConversationHudController.INSTANCE::receive));
        event.enqueueWork(() -> ClientAiActionConfirmationBridge.install(payload ->
                Minecraft.getInstance().execute(() -> Minecraft.getInstance()
                        .setScreen(new AiActionConfirmationScreen(payload)))));
        event.enqueueWork(() -> ClientRewardChoiceBridge.install(payload ->
                Minecraft.getInstance().execute(() -> RewardChoiceClientController.INSTANCE.receive(payload))));
        event.enqueueWork(() -> ClientStoryChoiceBridge.install(payload ->
                Minecraft.getInstance().execute(() -> StoryChoiceClientController.INSTANCE.receive(payload))));
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
        event.register(ROOMS);
    }

    @SubscribeEvent
    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CHAT, HUD_LAYER, AiConversationHudRenderer::render);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        StoryChoiceClientController.INSTANCE.tick();
        while (ROOMS.consumeClick()) Minecraft.getInstance().setScreen(new ConversationRoomsScreen());
        while (TOGGLE.consumeClick()) {
            AiConversationHudController.INSTANCE.toggleVisibility();
        }
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        AiConversationHudController.INSTANCE.reset();
        RewardChoiceClientController.INSTANCE.reset();
        StoryChoiceClientController.INSTANCE.reset();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        AiConversationHudController.INSTANCE.reset();
        RewardChoiceClientController.INSTANCE.reset();
        StoryChoiceClientController.INSTANCE.reset();
    }
}
