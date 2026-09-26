package com.sande.mythictrpg;

import com.mojang.logging.LogUtils;
import com.sande.mythictrpg.command.MythAdminCommands;
import com.sande.mythictrpg.command.EconomyCommands;
import com.sande.mythictrpg.ai.server.AiConversationRuntimeService;
import com.sande.mythictrpg.ai.action.AiActionTemplateManager;
import com.sande.mythictrpg.condition.engine.ConditionChangeDispatcher;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.data.god.GodUnlockService;
import com.sande.mythictrpg.data.player.ModAttachments;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythHistoryService;
import com.sande.mythictrpg.gameplay.activity.PlayerActivityRuntimeState;
import com.sande.mythictrpg.gameplay.activity.PlayerActivityService;
import com.sande.mythictrpg.gameplay.observation.GameplayIngressService;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationAdapters;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import com.sande.mythictrpg.gameplay.observation.AnimalFeedingObservationTracker;
import com.sande.mythictrpg.gameplay.promotion.GameplayPromotionManager;
import com.sande.mythictrpg.gameplay.promotion.GameplayPromotionService;
import com.sande.mythictrpg.gameplay.promotion.GameplaySpontaneousInteractionSink;
import com.sande.mythictrpg.gameplay.sampling.GameplayStatSamplingService;
import com.sande.mythictrpg.gameplay.sampling.SamplingRuntimeState;
import com.sande.mythictrpg.interaction.spontaneous.SpontaneousInteractionSubmissionService;
import com.sande.mythictrpg.network.DialogueNetwork;
import com.sande.mythictrpg.quest.FtbQuestBindingManager;
import com.sande.mythictrpg.quest.QuestRuntimeService;
import com.sande.mythictrpg.quest.QuestReminderService;
import com.sande.mythictrpg.quest.reward.NpcRewardTableManager;
import com.sande.mythictrpg.quest.reward.RewardClaimService;
import com.sande.mythictrpg.quest.dynamic.GeneratedQuestService;
import com.sande.mythictrpg.quest.dynamic.GeneratedQuestTemplateManager;
import com.sande.mythictrpg.quest.structure.StructureEvaluationPolicyManager;
import com.sande.mythictrpg.quest.structure.StructureTrackingService;
import com.sande.mythictrpg.quest.structure.FreeStructureService;
import com.sande.mythictrpg.quest.structure.StructureVisualEvaluationService;
import com.sande.mythictrpg.item.ModItems;
import com.sande.mythictrpg.interaction.rule.InteractionRuleManager;
import com.sande.mythictrpg.interaction.runtime.InteractionRuntimeState;
import com.sande.mythictrpg.relation.GodRelationTransitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.runtime.StoryEventService;
import com.sande.mythictrpg.story.runtime.StoryGameplayObservationAdapter;
import com.sande.mythictrpg.shop.ShopCatalogManager;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
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
        ModItems.register(modEventBus);
        ModAttachments.register(modEventBus);
        modEventBus.addListener(DialogueNetwork::register);
        ConditionChangeDispatcher.registerHandler(GodUnlockService.INSTANCE);
        NeoForge.EVENT_BUS.addListener(GodDefinitionManager.INSTANCE::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(InteractionRuleManager.INSTANCE::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(GameplayPromotionManager.INSTANCE::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(FtbQuestBindingManager.INSTANCE::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(NpcRewardTableManager.INSTANCE::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(AiActionTemplateManager.INSTANCE::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(GeneratedQuestTemplateManager.INSTANCE::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(StructureEvaluationPolicyManager.INSTANCE::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(GodRelationTransitionManager.INSTANCE::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(StoryDefinitionManager.INSTANCE::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(ShopCatalogManager.INSTANCE::onAddReloadListeners);
        QuestRuntimeService.INSTANCE.registerFtbEvents();
        GameplayStatSamplingService.INSTANCE.configureProductionProvider(
                GameplayPromotionManager.INSTANCE::watchedMetrics);
        GameplaySpontaneousInteractionSink.configureProduction();
        GameplayIngressService.INSTANCE.configureProductionSink((server, observation) -> {
            GameplayPromotionService.INSTANCE.accept(server, observation);
            QuestReminderService.INSTANCE.accept(server, observation);
            GeneratedQuestService.INSTANCE.accept(server, observation);
            com.sande.mythictrpg.quest.QuestParticipationService.INSTANCE.accept(server, observation);
            StoryGameplayObservationAdapter.INSTANCE.accept(server, observation);
        });
        NeoForge.EVENT_BUS.addListener(GodUnlockService.INSTANCE::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ActionLedgerService::onServerStarted);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.rumor.CourierRumorService::started);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, net.neoforged.neoforge.event.entity.living.LivingDeathEvent.class,
                com.sande.mythictrpg.rumor.CourierRumorService::death);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.rumor.CourierRumorService::tick);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.rumor.CourierRumorService::stopped);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.rumor.ReputationService::started);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.rumor.ReputationService::stopped);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.rumor.SocialRuntime::started);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.rumor.SocialRuntime::tick);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.rumor.SocialRuntime::stopped);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.rumor.SocialRuntime::commands);
        NeoForge.EVENT_BUS.addListener(ActionLedgerService::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(ActionLedgerService::onServerStopped);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.gameplay.ledger.detail.DetailEvents::tick);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.gameplay.ledger.detail.DetailEvents::dimension);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.gameplay.ledger.detail.DetailEvents::logout);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.gameplay.ledger.detail.DetailEvents::respawn);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.gameplay.ledger.detail.DetailEvents::endTick);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.gameplay.ledger.detail.DetailEvents::damage);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.gameplay.ledger.detail.DetailEvents::pickup);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.gameplay.ledger.detail.ImportantEvents::advancement);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.gameplay.ledger.detail.ImportantEvents::damage);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.gameplay.watch.GodWatchRuntime::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.gameplay.watch.GodWatchRuntime::onPlayerRespawn);
        NeoForge.EVENT_BUS.addListener(StoryEventService.INSTANCE::onServerStarted);
        NeoForge.EVENT_BUS.addListener(MythAdminCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.command.QuestParticipationCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(EconomyCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(PlayerMythDataService::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(StoryEventService.INSTANCE::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(QuestRuntimeService.INSTANCE::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(RewardClaimService.INSTANCE::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(GeneratedQuestService.INSTANCE::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(AiConversationRuntimeService.INSTANCE::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(AiConversationRuntimeService.INSTANCE::onGodIdentified);
        NeoForge.EVENT_BUS.addListener(StoryEventService.INSTANCE::onGodIdentified);
        NeoForge.EVENT_BUS.addListener(PlayerMythDataService::onPlayerClone);
        NeoForge.EVENT_BUS.addListener(PlayerActivityService.INSTANCE::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(PlayerActivityService.INSTANCE::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(AiConversationRuntimeService.INSTANCE::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(PlayerActivityService.INSTANCE::onPlayerClone);
        NeoForge.EVENT_BUS.addListener(PlayerActivityService.INSTANCE::onPlayerRespawn);
        NeoForge.EVENT_BUS.addListener(PlayerActivityService.INSTANCE::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(GameplayPromotionService.INSTANCE::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(
                SpontaneousInteractionSubmissionService.INSTANCE::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(PlayerMythHistoryService::onItemEntityPickup);
        NeoForge.EVENT_BUS.addListener(PlayerMythHistoryService::onItemCrafted);
        NeoForge.EVENT_BUS.addListener(PlayerMythHistoryService::onItemSmelted);
        NeoForge.EVENT_BUS.addListener(GameplayObservationAdapters::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(StructureTrackingService.INSTANCE::onBlockPlaced);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, BlockEvent.BreakEvent.class,
                StructureTrackingService.INSTANCE::onBlockBroken);
        NeoForge.EVENT_BUS.addListener(StructureTrackingService.INSTANCE::onToolModified);
        NeoForge.EVENT_BUS.addListener(StructureTrackingService.INSTANCE::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(StructureTrackingService.INSTANCE::onEntityJoined);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, BlockEvent.BreakEvent.class,
                GameplayObservationAdapters::onMatureCropBreak);
        NeoForge.EVENT_BUS.addListener(GameplayObservationAdapters::onBabyEntitySpawn);
        NeoForge.EVENT_BUS.addListener(GameplayObservationAdapters::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, PlayerInteractEvent.EntityInteract.class,
                AnimalFeedingObservationTracker.INSTANCE::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(AnimalFeedingObservationTracker.INSTANCE::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(AnimalFeedingObservationTracker.INSTANCE::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(GameplayStatSamplingService.INSTANCE::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(GameplayStatSamplingService.INSTANCE::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(PlayerActivityService.INSTANCE::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, GameplayIngressService.INSTANCE::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, QuestReminderService.INSTANCE::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.quest.QuestParticipationService.INSTANCE::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, GeneratedQuestService.INSTANCE::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, StoryEventService.INSTANCE::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(FreeStructureService.INSTANCE::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(AiConversationRuntimeService.INSTANCE::onServerChat);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.ai.server.ConversationRooms.INSTANCE::tick);
        NeoForge.EVENT_BUS.addListener(com.sande.mythictrpg.command.ConversationRoomCommands::register);
        NeoForge.EVENT_BUS.addListener(GameplayPromotionService.INSTANCE::onServerStopped);
        NeoForge.EVENT_BUS.addListener(
                SpontaneousInteractionSubmissionService.INSTANCE::onServerStopped);
        NeoForge.EVENT_BUS.addListener(AiConversationRuntimeService.INSTANCE::onServerStopped);
        NeoForge.EVENT_BUS.addListener(StoryEventService.INSTANCE::onServerStopped);
        NeoForge.EVENT_BUS.addListener(FreeStructureService.INSTANCE::onServerStopped);
        NeoForge.EVENT_BUS.addListener(StructureVisualEvaluationService.INSTANCE::onServerStopped);
        NeoForge.EVENT_BUS.addListener(MythicTrpg::onServerStopped);
        LOGGER.info("Mythic TRPG initialized.");
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        InteractionRuntimeState.discard(event.getServer());
        SamplingRuntimeState.discard(event.getServer());
        AnimalFeedingObservationTracker.discard(event.getServer());
        PlayerActivityRuntimeState.discard(event.getServer());
    }
}
