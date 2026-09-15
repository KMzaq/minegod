package com.sande.mythictrpg;

import com.mojang.logging.LogUtils;
import com.sande.mythictrpg.command.MythAdminCommands;
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
import com.sande.mythictrpg.gameplay.observation.AnimalFeedingObservationTracker;
import com.sande.mythictrpg.gameplay.promotion.GameplayPromotionManager;
import com.sande.mythictrpg.gameplay.promotion.GameplayPromotionService;
import com.sande.mythictrpg.gameplay.promotion.GameplaySpontaneousInteractionSink;
import com.sande.mythictrpg.gameplay.sampling.GameplayStatSamplingService;
import com.sande.mythictrpg.gameplay.sampling.SamplingRuntimeState;
import com.sande.mythictrpg.interaction.spontaneous.SpontaneousInteractionSubmissionService;
import com.sande.mythictrpg.network.DialogueNetwork;
import com.sande.mythictrpg.interaction.rule.InteractionRuleManager;
import com.sande.mythictrpg.interaction.runtime.InteractionRuntimeState;
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
        ModAttachments.register(modEventBus);
        modEventBus.addListener(DialogueNetwork::register);
        ConditionChangeDispatcher.registerHandler(GodUnlockService.INSTANCE);
        NeoForge.EVENT_BUS.addListener(GodDefinitionManager.INSTANCE::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(InteractionRuleManager.INSTANCE::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(GameplayPromotionManager.INSTANCE::onAddReloadListeners);
        GameplayStatSamplingService.INSTANCE.configureProductionProvider(
                GameplayPromotionManager.INSTANCE::watchedMetrics);
        GameplaySpontaneousInteractionSink.configureProduction();
        GameplayIngressService.INSTANCE.configureProductionSink(GameplayPromotionService.INSTANCE);
        NeoForge.EVENT_BUS.addListener(GodUnlockService.INSTANCE::onServerStarted);
        NeoForge.EVENT_BUS.addListener(MythAdminCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(PlayerMythDataService::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(PlayerMythDataService::onPlayerClone);
        NeoForge.EVENT_BUS.addListener(PlayerActivityService.INSTANCE::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(PlayerActivityService.INSTANCE::onPlayerLoggedOut);
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
        NeoForge.EVENT_BUS.addListener(GameplayPromotionService.INSTANCE::onServerStopped);
        NeoForge.EVENT_BUS.addListener(
                SpontaneousInteractionSubmissionService.INSTANCE::onServerStopped);
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
