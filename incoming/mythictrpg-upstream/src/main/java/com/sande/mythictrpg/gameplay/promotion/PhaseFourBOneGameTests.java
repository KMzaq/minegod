package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.data.god.AppearancePolicy;
import com.sande.mythictrpg.data.god.GodAppearanceService;
import com.sande.mythictrpg.data.god.GodDefinition;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.data.god.IdentificationPolicy;
import com.sande.mythictrpg.data.god.UnlockPolicy;
import com.sande.mythictrpg.data.player.GodKnowledgeSnapshot;
import com.sande.mythictrpg.data.player.PlayerGodKnowledgeService;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import com.sande.mythictrpg.data.world.MythicWorldState;
import com.sande.mythictrpg.gameplay.activity.PlayerActivityService;
import com.sande.mythictrpg.gameplay.activity.PlayerActivitySnapshot;
import com.sande.mythictrpg.gameplay.activity.PlayerActivityState;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricTypes;
import com.sande.mythictrpg.gameplay.observation.BlockBrokenPayload;
import com.sande.mythictrpg.gameplay.observation.GameplayIngressService;
import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationAdapters;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import com.sande.mythictrpg.gameplay.observation.MatureCropHarvestPayload;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.director.InteractionDecision;
import com.sande.mythictrpg.interaction.orchestration.InteractionOrchestrator;
import com.sande.mythictrpg.interaction.policy.CooldownView;
import com.sande.mythictrpg.interaction.policy.InteractionRuntimeView;
import com.sande.mythictrpg.interaction.rule.InteractionRule;
import com.sande.mythictrpg.interaction.rule.InteractionRuleBinding;
import com.sande.mythictrpg.interaction.rule.InteractionRuleManager;
import com.sande.mythictrpg.interaction.runtime.InteractionRuntimeState;
import com.sande.mythictrpg.interaction.spontaneous.ContentPreparerResolution;
import com.sande.mythictrpg.interaction.spontaneous.InteractionContentPreparerResolverRouter;
import com.sande.mythictrpg.interaction.spontaneous.SpontaneousInteractionRuntimeState;
import com.sande.mythictrpg.interaction.spontaneous.SpontaneousInteractionSubmissionService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourBOneGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation DEMETER = id("demeter");
    private static final ResourceLocation APHRODITE = id("aphrodite");
    private static final ResourceLocation LUBRAS = id("lubras");
    private static final ResourceLocation PROMOTION = id("demeter_wheat_harvest");
    private static final ResourceLocation SIGNAL = id("demeter_harvest");
    private static final ResourceLocation WHEAT = blockId(Blocks.WHEAT);
    private static final List<Block> OTHER_SUPPORTED_CROPS = List.of(
            Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS, Blocks.NETHER_WART, Blocks.COCOA);

    private PhaseFourBOneGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void productionGodPromotionAndInteractionRuleLoadTogether(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = onlinePlayer(helper);
        try {
            GodDefinition demeter = GodDefinitionManager.INSTANCE.definitions().get(DEMETER);
            helper.assertTrue(demeter != null, "production Demeter definition did not load");
            helper.assertValueEqual(demeter.schemaVersion(), 2, "Demeter God schema version");
            helper.assertValueEqual(demeter.unlockPolicy(), UnlockPolicy.NOT_REQUIRED,
                    "Demeter unlock policy");
            helper.assertValueEqual(demeter.appearancePolicy(), AppearancePolicy.CONDITION_DRIVEN,
                    "Demeter appearance policy");
            helper.assertValueEqual(demeter.identificationPolicy(), IdentificationPolicy.EXPLICIT_ONLY,
                    "Demeter identification policy");
            helper.assertValueEqual(GodDefinitionManager.INSTANCE.definitions().get(APHRODITE)
                            .appearancePolicy(), AppearancePolicy.EXPLICIT_ONLY,
                    "Aphrodite appearance policy");
            helper.assertValueEqual(GodDefinitionManager.INSTANCE.definitions().get(LUBRAS)
                            .appearancePolicy(), AppearancePolicy.EXPLICIT_ONLY,
                    "Lubras appearance policy");
            var appearance = GodAppearanceService.INSTANCE.evaluateAutomaticAppearance(player, DEMETER);
            helper.assertTrue(appearance.eligible(), "production Demeter is not automatically eligible");
            helper.assertValueEqual(appearance.conditionResult(), Optional.of(ConditionResult.MATCH),
                    "Demeter appearance condition result");

            GameplayPromotionSnapshot snapshot = GameplayPromotionManager.INSTANCE.snapshot();
            helper.assertValueEqual(snapshot.definitions().keySet(), Set.of(PROMOTION),
                    "production promotion IDs");
            GameplayPromotionDefinition definition = snapshot.definitions().get(PROMOTION);
            helper.assertValueEqual(definition.observationTypeId(),
                    GameplayObservationTypes.MATURE_CROP_HARVESTED.id(), "production observation ID");
            helper.assertValueEqual(definition.signalId(), SIGNAL, "production signal ID");
            helper.assertValueEqual(definition.priority(), 10, "production promotion priority");
            helper.assertValueEqual(definition.attemptCooldownTicks(), 1_200L,
                    "production attempt cooldown");
            helper.assertTrue(definition.matcher() instanceof MatureCropHarvestPromotionMatcher,
                    "production crop matcher type");
            helper.assertValueEqual(((MatureCropHarvestPromotionMatcher) definition.matcher()).cropBlockId(),
                    Optional.of(WHEAT), "production exact wheat matcher");
            helper.assertTrue(snapshot.watchedMetrics().isEmpty(),
                    "event promotion unexpectedly created statistic watches");

            List<InteractionRule> rules = InteractionRuleManager.INSTANCE.snapshot().rules();
            helper.assertValueEqual(rules.size(), 1, "production interaction rule count");
            InteractionRule rule = rules.getFirst();
            helper.assertValueEqual(rule.schemaVersion(), 1, "production interaction rule schema");
            helper.assertValueEqual(rule.signalType(), SIGNAL, "production interaction rule signal");
            helper.assertValueEqual(rule.bindings().size(), 1, "production direct binding count");
            InteractionRuleBinding binding = rule.bindings().getFirst();
            helper.assertValueEqual(binding.godId(), Optional.of(DEMETER), "production direct God");
            helper.assertTrue(binding.godCategoryId().isEmpty(),
                    "production interaction rule added a category binding");
            helper.assertValueEqual(binding.score(), 10, "production interaction rule score");
            helper.assertValueEqual(binding.reason(), PROMOTION, "production interaction rule reason");
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void productionIndexMatchesOnlyMatureWheatObservations(GameTestHelper helper) {
        GameplayPromotionSnapshot snapshot = GameplayPromotionManager.INSTANCE.snapshot();
        UUID playerId = UUID.randomUUID();
        GameplayPromotionIndex index = snapshot.index();

        helper.assertValueEqual(index.exactCandidates(
                        GameplayObservationTypes.MATURE_CROP_HARVESTED.id(),
                        PromotionLookupKey.id(WHEAT)).stream()
                        .map(GameplayPromotionDefinition::id).toList(),
                List.of(PROMOTION), "exact wheat promotion candidates");
        helper.assertTrue(index.wildcardCandidates(
                        GameplayObservationTypes.MATURE_CROP_HARVESTED.id()).isEmpty(),
                "production wheat promotion created a wildcard bucket");
        helper.assertValueEqual(index.match(observation(playerId, WHEAT)).stream()
                        .map(GameplayPromotionDefinition::id).toList(),
                List.of(PROMOTION), "mature wheat promotion match");

        for (Block crop : OTHER_SUPPORTED_CROPS) {
            ResourceLocation cropId = blockId(crop);
            helper.assertTrue(index.match(observation(playerId, cropId)).isEmpty(),
                    "non-wheat mature crop matched production promotion: " + cropId);
        }
        GameplayObservation<BlockBrokenPayload> blockBreak = new GameplayObservation<>(
                GameplayObservationTypes.BLOCK_BROKEN, playerId, 0,
                new BlockBrokenPayload(WHEAT, Level.OVERWORLD.location(), BlockPos.ZERO));
        helper.assertTrue(index.match(blockBreak).isEmpty(),
                "generic block break matched the mature wheat production promotion");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void productionWheatSignalPlansOnlyDemeterWithoutMutation(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = onlinePlayer(helper);
        InteractionRuntimeState.discard(server);
        try {
            PlayerMythProfile before = PlayerMythDataService.get(server)
                    .find(player.getUUID()).orElseThrow();
            GodKnowledgeSnapshot knowledgeBefore = PlayerGodKnowledgeService.get(server)
                    .snapshot(player.getUUID(), DEMETER);
            Set<ResourceLocation> unlockedBefore = MythicWorldState.get(server).unlockedGods();

            GameplayPromotionSnapshot snapshot = GameplayPromotionManager.INSTANCE.snapshot();
            InteractionSignal<GameplayActionPayload> signal = GameplayPromotionSignalFactory.create(
                    snapshot, snapshot.definitions().get(PROMOTION),
                    observation(player.getUUID(), WHEAT)).orElseThrow();
            helper.assertValueEqual(signal.type().id(), SIGNAL, "production generated signal ID");
            helper.assertValueEqual(signal.mode(), InteractionMode.SPONTANEOUS,
                    "production generated signal mode");
            helper.assertValueEqual(signal.payload().promotionRuleId(), PROMOTION,
                    "production signal promotion ID");
            helper.assertValueEqual(signal.payload().subjectId(), Optional.of(WHEAT),
                    "production signal crop subject");
            helper.assertTrue(signal.payload().evidence() instanceof MatureCropHarvestEvidence,
                    "production signal did not contain typed mature crop evidence");
            helper.assertValueEqual(((MatureCropHarvestEvidence) signal.payload().evidence()).cropBlockId(),
                    WHEAT, "production signal crop evidence");

            var seeds = InteractionRuleManager.INSTANCE.snapshot().seedIndex().resolve(
                    SIGNAL, GodDefinitionManager.INSTANCE.progressionSnapshot());
            helper.assertTrue(seeds.signalMapped(), "production signal was not indexed");
            helper.assertValueEqual(seeds.seeds().keySet(), Set.of(DEMETER),
                    "production signal did not resolve directly to Demeter");
            helper.assertTrue(seeds.unresolvedExactGods().isEmpty(),
                    "production signal referenced an unresolved God");

            InteractionDecision decision = InteractionOrchestrator.INSTANCE.plan(
                    server, signal, CooldownView.NONE, InteractionRuntimeView.AVAILABLE);
            helper.assertValueEqual(decision.status(), InteractionDecision.Status.START,
                    "production spontaneous planning status");
            var plan = decision.plan().orElseThrow();
            helper.assertValueEqual(plan.participants().primaryGodId(), DEMETER,
                    "production planned primary God");
            helper.assertTrue(plan.participants().secondaryGodIds().isEmpty(),
                    "production planning added a secondary God");
            helper.assertValueEqual(plan.selectionReasons(), List.of(PROMOTION),
                    "production planning reason");
            helper.assertTrue(PlayerMythDataService.get(server).find(player.getUUID()).orElseThrow() == before,
                    "read-only production planning replaced the player profile");
            helper.assertValueEqual(PlayerGodKnowledgeService.get(server)
                            .snapshot(player.getUUID(), DEMETER), knowledgeBefore,
                    "read-only production planning changed God knowledge");
            helper.assertValueEqual(MythicWorldState.get(server).unlockedGods(), unlockedBefore,
                    "read-only production planning changed world unlocks");
            helper.assertTrue(InteractionRuntimeState.readOnlyViewsIfPresent(server)
                            == InteractionRuntimeState.ReadOnlyViews.EMPTY,
                    "read-only production planning created interaction runtime state");
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void unavailableProviderConsumesOnlyProductionAttemptCooldown(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = onlinePlayer(helper);
        GameplayPromotionRuntimeState.discard(server);
        InteractionRuntimeState.discard(server);
        SpontaneousInteractionRuntimeState.discard(server);
        try {
            PlayerMythProfile before = PlayerMythDataService.get(server)
                    .find(player.getUUID()).orElseThrow();
            GodKnowledgeSnapshot knowledgeBefore = PlayerGodKnowledgeService.get(server)
                    .snapshot(player.getUUID(), DEMETER);
            Set<ResourceLocation> unlockedBefore = MythicWorldState.get(server).unlockedGods();
            GameplayObservation<MatureCropHarvestPayload> observation =
                    observation(player.getUUID(), WHEAT);
            GameplayPromotionSnapshot snapshot = GameplayPromotionManager.INSTANCE.snapshot();
            InteractionSignal<GameplayActionPayload> signal = GameplayPromotionSignalFactory.create(
                    snapshot, snapshot.definitions().get(PROMOTION), observation).orElseThrow();
            helper.assertValueEqual(InteractionContentPreparerResolverRouter.INSTANCE.resolve(signal).status(),
                    ContentPreparerResolution.Status.UNAVAILABLE,
                    "production content provider must remain unavailable");

            long tick = server.overworld().getGameTime();
            GameplayPromotionResult first = GameplayPromotionService.INSTANCE.promote(server, observation);
            helper.assertValueEqual(first.status(), GameplayPromotionResult.Status.SINK_UNAVAILABLE,
                    "production unavailable provider status");
            helper.assertValueEqual(first.promotionRuleId(), Optional.of(PROMOTION),
                    "production unavailable provider rule ID");
            GameplayPromotionRuntimeState runtime = GameplayPromotionRuntimeState.get(server);
            helper.assertValueEqual(runtime.nextEligibleTickForTesting(player.getUUID(), PROMOTION)
                            .orElseThrow(), tick + 1_200L,
                    "production provider failure did not retain its attempt cooldown");
            helper.assertValueEqual(runtime.playerEntryCountForTesting(player.getUUID()), 1,
                    "production attempt cooldown entry count");

            GameplayPromotionResult repeated = GameplayPromotionService.INSTANCE.promote(server, observation);
            helper.assertValueEqual(repeated.status(),
                    GameplayPromotionResult.Status.ALL_CANDIDATES_COOLDOWN,
                    "production attempt cooldown did not reject a repeated harvest");
            helper.assertTrue(PlayerMythDataService.get(server).find(player.getUUID()).orElseThrow() == before,
                    "unavailable provider replaced the player profile");
            helper.assertValueEqual(before.dataVersion(), PlayerMythProfile.CURRENT_DATA_VERSION,
                    "unavailable provider changed profile data version");
            helper.assertValueEqual(PlayerGodKnowledgeService.get(server)
                            .snapshot(player.getUUID(), DEMETER), knowledgeBefore,
                    "unavailable provider changed God encounter or identification");
            helper.assertValueEqual(MythicWorldState.get(server).unlockedGods(), unlockedBefore,
                    "unavailable provider changed world unlocks");
            helper.assertTrue(InteractionRuntimeState.readOnlyViewsIfPresent(server)
                            == InteractionRuntimeState.ReadOnlyViews.EMPTY,
                    "unavailable provider reached interaction start or delivery");
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void idlePlayersCannotPromoteProductionWheat(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID playerId = UUID.randomUUID();
        AtomicInteger delivered = new AtomicInteger();
        GameplayPromotionRuntimeState runtime = GameplayPromotionRuntimeState.forTesting(server, () -> 0);
        GameplayPromotionService service = new GameplayPromotionService(
                GameplayPromotionManager.INSTANCE::snapshot,
                (ignored, ignoredPlayer) -> true,
                ignored -> ignoredPlayer -> Optional.of(new PlayerActivitySnapshot(
                        PlayerActivityState.IDLE, 0, 6_000)),
                ignored -> runtime,
                GameplayPromotionSignalFactory::create,
                (ignored, signal) -> {
                    delivered.incrementAndGet();
                    return GameplaySignalResult.ACCEPTED;
                });

        GameplayPromotionResult result = service.promote(server, observation(playerId, WHEAT));
        helper.assertValueEqual(result.status(), GameplayPromotionResult.Status.PLAYER_IDLE,
                "IDLE player promoted a production wheat observation");
        helper.assertValueEqual(delivered.get(), 0, "IDLE player emitted a production signal");
        helper.assertValueEqual(runtime.entryCountForTesting(), 0,
                "IDLE player consumed a production attempt cooldown");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void matureWheatBlockEventTraversesProductionBinding(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = onlinePlayer(helper);
        GameplayIngressService ingress = GameplayIngressService.INSTANCE;
        GameplaySignalSinkRouter router = GameplaySignalSinkRouter.INSTANCE;
        GameplayPromotionRuntimeState.discard(server);
        InteractionRuntimeState.discard(server);
        ingress.resetForTesting();
        List<InteractionSignal<GameplayActionPayload>> signals = new ArrayList<>();
        router.setSinkForTesting((currentServer, signal) -> {
            signals.add(signal);
            return new GameplaySpontaneousInteractionSink(
                    SpontaneousInteractionSubmissionService.INSTANCE).accept(currentServer, signal);
        });
        try {
            GameplayObservationAdapters.onMatureCropBreak(new BlockEvent.BreakEvent(
                    helper.getLevel(), BlockPos.ZERO,
                    ((CropBlock) Blocks.WHEAT).getStateForAge(((CropBlock) Blocks.WHEAT).getMaxAge()),
                    player));

            PlayerMythProfile afterExistingHarvest = PlayerMythDataService.get(server)
                    .find(player.getUUID()).orElseThrow();
            helper.assertValueEqual(afterExistingHarvest.customGameplayCounter(
                            GameplayMetricKey.aggregate(GameplayMetricTypes.MATURE_CROP_HARVESTED)), 1L,
                    "existing mature harvest aggregate counter");
            helper.assertValueEqual(afterExistingHarvest.customGameplayCounter(
                            GameplayMetricKey.subject(GameplayMetricTypes.MATURE_CROP_HARVESTED, WHEAT)), 1L,
                    "existing mature wheat subject counter");
            GodKnowledgeSnapshot knowledgeBefore = PlayerGodKnowledgeService.get(server)
                    .snapshot(player.getUUID(), DEMETER);
            helper.assertValueEqual(ingress.drain(server), 1,
                    "production mature wheat observation drain count");
            helper.assertValueEqual(signals.size(), 1,
                    "production mature wheat event signal count");

            InteractionSignal<GameplayActionPayload> signal = signals.getFirst();
            helper.assertValueEqual(signal.type().id(), SIGNAL,
                    "production mature wheat event signal ID");
            helper.assertValueEqual(((MatureCropHarvestEvidence) signal.payload().evidence()).cropBlockId(),
                    WHEAT, "production mature wheat event evidence");
            helper.assertTrue(PlayerMythDataService.get(server).find(player.getUUID()).orElseThrow()
                            == afterExistingHarvest,
                    "unavailable provider changed the profile after the existing crop counter update");
            helper.assertValueEqual(PlayerGodKnowledgeService.get(server)
                            .snapshot(player.getUUID(), DEMETER), knowledgeBefore,
                    "production mature wheat event started a God encounter");
            helper.assertTrue(InteractionRuntimeState.readOnlyViewsIfPresent(server)
                            == InteractionRuntimeState.ReadOnlyViews.EMPTY,
                    "production mature wheat event reached interaction delivery");
        } finally {
            router.clearSinkOverrideForTesting();
            ingress.resetForTesting();
            cleanup(server, player);
        }
        helper.succeed();
    }

    private static GameplayObservation<MatureCropHarvestPayload> observation(UUID playerId,
            ResourceLocation cropId) {
        return new GameplayObservation<>(GameplayObservationTypes.MATURE_CROP_HARVESTED,
                playerId, 0, new MatureCropHarvestPayload(cropId));
    }

    @SuppressWarnings("removal")
    private static ServerPlayer onlinePlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        PlayerMythDataService.get(player.server).ensureProfile(player.getUUID());
        PlayerActivityService.INSTANCE.onPlayerLoggedIn(new PlayerEvent.PlayerLoggedInEvent(player));
        return player;
    }

    private static void cleanup(MinecraftServer server, ServerPlayer player) {
        GameplayPromotionRuntimeState.removePlayerIfPresent(server, player.getUUID());
        PlayerActivityService.INSTANCE.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
        InteractionRuntimeState.discard(server);
        SpontaneousInteractionRuntimeState.removePlayerIfPresent(server, player.getUUID());
        if (server.getPlayerList().getPlayer(player.getUUID()) != null) {
            server.getPlayerList().remove(player);
        }
    }

    private static ResourceLocation blockId(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
