package com.sande.mythai.response;

import com.mojang.logging.LogUtils;
import com.sande.mythictrpg.ai.AiDialogueConfig;
import com.sande.mythictrpg.ai.AiInteractionContentProvider;
import com.sande.mythictrpg.ai.MythAiConversationEngine;
import com.sande.mythictrpg.ai.api.AiConversationEngineRouter;
import com.sande.mythictrpg.interaction.spontaneous.InteractionContentPreparerResolverRouter;
import com.sande.mythictrpg.quest.structure.StructureVisualEvaluationGateway;
import com.sande.mythai.response.structure.OllamaStructureVisionProvider;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/** Separate server-side owner of the tested local Ollama/Gemma response pipeline. */
@Mod(MythAiResponseMod.MOD_ID)
public final class MythAiResponseMod {
    public static final String MOD_ID = "mythai_ai_response";
    private static final Logger LOGGER = LogUtils.getLogger();

    public MythAiResponseMod(IEventBus ignoredModBus) {
        var settings = AiDialogueConfig.INSTANCE.load();
        InteractionContentPreparerResolverRouter.INSTANCE.configureProductionResolver(
                AiInteractionContentProvider.INSTANCE);
        AiConversationEngineRouter.INSTANCE.configureProductionEngine(
                MythAiConversationEngine.INSTANCE);
        StructureVisualEvaluationGateway.INSTANCE.configureProductionProvider(
                OllamaStructureVisionProvider.INSTANCE);
        NeoForge.EVENT_BUS.addListener(AiTestCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(com.sande.mythai.response.memory.MemoryCommands::register);
        NeoForge.EVENT_BUS.addListener(com.sande.mythai.response.memory.DialogueMemoryBridge::onTick);
        com.sande.mythictrpg.rumor.SocialReview.configure(com.sande.mythai.response.memory.SocialReviewProvider.INSTANCE);
        NeoForge.EVENT_BUS.addListener(com.sande.mythai.response.memory.SocialReviewProvider::stopped);
        NeoForge.EVENT_BUS.addListener(AiTestChatEvents::onServerChat);
        NeoForge.EVENT_BUS.addListener(AiTestChatEvents::onPlayerLoggedOut);
        LOGGER.info("MythAI response engine ready (Ollama={}, model={})",
                settings.ollamaChatUrl(), settings.ollamaModel());
    }
}
