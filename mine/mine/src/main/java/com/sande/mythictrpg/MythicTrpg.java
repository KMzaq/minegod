package com.sande.mythictrpg;

import com.mojang.logging.LogUtils;
import com.sande.mythictrpg.ai.AiDialogueCommands;
import com.sande.mythictrpg.ai.AiDialogueConfig;
import com.sande.mythictrpg.ai.AiTestDialogueAdapter;
import com.sande.mythictrpg.ai.AiTestDialogueCommands;
import com.sande.mythictrpg.ai.AiTestDialogueEvents;
import com.sande.mythictrpg.ai.GodPersonaRepository;
import com.sande.mythictrpg.ai.GodAiDialogueEvents;
import com.sande.mythictrpg.ai.GodAiDialogueService;
import com.sande.mythictrpg.ai.agent.NpcAgentRepository;
import com.sande.mythictrpg.ai.example.JsonDialogueExampleRepository;
import com.sande.mythictrpg.ai.example.JsonTagDialogueGuidanceRepository;
import com.sande.mythictrpg.ai.knowledge.JsonKnowledgeRepository;
import com.sande.mythictrpg.ai.reaction.ReactionGuidelineRepository;
import com.sande.mythictrpg.ai.tag.CharacterStyleTagMapper;
import com.sande.mythictrpg.ai.tag.CharacterTagRegistry;
import com.sande.mythictrpg.ai.tag.NpcCharacterTagRepository;
import com.sande.mythictrpg.ai.voice.JsonVoiceStyleRepository;
import com.sande.mythictrpg.command.MythAdminCommands;
import com.sande.mythictrpg.condition.engine.ConditionChangeDispatcher;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.data.god.GodUnlockService;
import com.sande.mythictrpg.data.player.ModAttachments;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythHistoryService;
import com.sande.mythictrpg.gameplay.observation.GameplayIngressService;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationAdapters;
import com.sande.mythictrpg.gameplay.sampling.GameplayStatSamplingService;
import com.sande.mythictrpg.gameplay.sampling.SamplingRuntimeState;
import com.sande.mythictrpg.network.DialogueNetwork;
import com.sande.mythictrpg.interaction.rule.InteractionRuleManager;
import com.sande.mythictrpg.interaction.runtime.InteractionRuntimeState;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(MythicTrpg.MOD_ID)
public final class MythicTrpg {
    public static final String MOD_ID = "mythictrpg";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MythicTrpg(IEventBus modEventBus) {
        AiDialogueConfig.INSTANCE.load();
        GodPersonaRepository.INSTANCE.load();
        CharacterTagRegistry.INSTANCE.load();
        NpcCharacterTagRepository.INSTANCE.load();
        CharacterStyleTagMapper.INSTANCE.load();
        ReactionGuidelineRepository.INSTANCE.load();
        JsonVoiceStyleRepository.INSTANCE.load();
        NpcAgentRepository.INSTANCE.load();
        JsonKnowledgeRepository.INSTANCE.load();
        JsonDialogueExampleRepository.INSTANCE.load();
        JsonTagDialogueGuidanceRepository.INSTANCE.load();
        ModAttachments.register(modEventBus);
        modEventBus.addListener(DialogueNetwork::register);
        ConditionChangeDispatcher.registerHandler(GodUnlockService.INSTANCE);
        NeoForge.EVENT_BUS.addListener(GodDefinitionManager.INSTANCE::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(InteractionRuleManager.INSTANCE::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(GodUnlockService.INSTANCE::onServerStarted);
        NeoForge.EVENT_BUS.addListener(MythAdminCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(AiDialogueCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(GodAiDialogueEvents::onServerChat);
        NeoForge.EVENT_BUS.addListener(GodAiDialogueEvents::onPlayerLoggedOut);
        if (AiTestDialogueAdapter.enabled()) {
            NeoForge.EVENT_BUS.addListener(AiTestDialogueCommands::onRegisterCommands);
            NeoForge.EVENT_BUS.addListener(AiTestDialogueEvents::onServerChat);
            NeoForge.EVENT_BUS.addListener(AiTestDialogueEvents::onPlayerLoggedOut);
            LOGGER.info("AI test dialogue adapter enabled.");
        }
        NeoForge.EVENT_BUS.addListener(PlayerMythDataService::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(PlayerMythDataService::onPlayerClone);
        NeoForge.EVENT_BUS.addListener(PlayerMythHistoryService::onItemEntityPickup);
        NeoForge.EVENT_BUS.addListener(PlayerMythHistoryService::onItemCrafted);
        NeoForge.EVENT_BUS.addListener(PlayerMythHistoryService::onItemSmelted);
        NeoForge.EVENT_BUS.addListener(GameplayObservationAdapters::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, BlockEvent.BreakEvent.class,
                GameplayObservationAdapters::onMatureCropBreak);
        NeoForge.EVENT_BUS.addListener(GameplayObservationAdapters::onBabyEntitySpawn);
        NeoForge.EVENT_BUS.addListener(GameplayObservationAdapters::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(GameplayStatSamplingService.INSTANCE::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(GameplayStatSamplingService.INSTANCE::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(GameplayIngressService.INSTANCE::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(MythicTrpg::onServerStopped);
        LOGGER.info("Mythic TRPG initialized.");
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        GodAiDialogueService.INSTANCE.stop();
        AiTestDialogueAdapter.INSTANCE.stop();
        InteractionRuntimeState.discard(event.getServer());
        SamplingRuntimeState.discard(event.getServer());
    }
}
