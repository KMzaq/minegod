package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import com.sande.mythictrpg.gameplay.observation.AnimalBredPayload;
import com.sande.mythictrpg.gameplay.observation.AnimalFeedEntry;
import com.sande.mythictrpg.gameplay.observation.AnimalFedPayload;
import com.sande.mythictrpg.gameplay.observation.BlockBrokenPayload;
import com.sande.mythictrpg.gameplay.observation.EntityKilledPayload;
import com.sande.mythictrpg.gameplay.observation.FeedingOutcome;
import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationPayload;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationType;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import com.sande.mythictrpg.gameplay.observation.ItemFirstObtainedPayload;
import com.sande.mythictrpg.gameplay.observation.MatureCropHarvestPayload;
import com.sande.mythictrpg.gameplay.observation.PlayerDiedPayload;
import com.sande.mythictrpg.gameplay.observation.VanillaStatThresholdCrossedPayload;
import com.sande.mythictrpg.gameplay.sampling.VanillaStatisticKey;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.api.InteractionSignalType;
import com.sande.mythictrpg.interaction.api.InteractionSignalTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stats;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourAThreeBOneGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation COW = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.COW);
    private static final ResourceLocation SHEEP = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.SHEEP);
    private static final ResourceLocation WHEAT = BuiltInRegistries.ITEM.getKey(Items.WHEAT);
    private static final ResourceLocation CARROT = BuiltInRegistries.ITEM.getKey(Items.CARROT);
    private static final ResourceLocation STONE = BuiltInRegistries.BLOCK.getKey(Blocks.STONE);
    private static final ResourceLocation WHEAT_CROP = BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT);
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation FALL = ResourceLocation.withDefaultNamespace("fall");

    private PhaseFourAThreeBOneGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void allObservationAdaptersCreateTypedEvidence(GameTestHelper helper) {
        BlockBrokenEvidence block = evidence(BlockBrokenPromotionAdapter.INSTANCE,
                new BlockBrokenPayload(STONE, OVERWORLD, new BlockPos(1, 2, 3)),
                new BlockBrokenPromotionMatcher(Optional.of(STONE), Optional.of(OVERWORLD)),
                BlockBrokenEvidence.class);
        helper.assertValueEqual(block, new BlockBrokenEvidence(STONE, OVERWORLD),
                "block evidence excludes position");

        MatureCropHarvestEvidence crop = evidence(MatureCropHarvestPromotionAdapter.INSTANCE,
                new MatureCropHarvestPayload(WHEAT_CROP),
                new MatureCropHarvestPromotionMatcher(Optional.of(WHEAT_CROP)),
                MatureCropHarvestEvidence.class);
        helper.assertValueEqual(crop.cropBlockId(), WHEAT_CROP, "crop evidence");

        EntityKilledEvidence killed = evidence(EntityKilledPromotionAdapter.INSTANCE,
                new EntityKilledPayload(COW, OVERWORLD),
                new EntityKilledPromotionMatcher(Optional.of(COW), Optional.of(OVERWORLD)),
                EntityKilledEvidence.class);
        helper.assertValueEqual(killed, new EntityKilledEvidence(COW, OVERWORLD), "kill evidence");

        ItemFirstObtainedEvidence item = evidence(ItemFirstObtainedPromotionAdapter.INSTANCE,
                new ItemFirstObtainedPayload(WHEAT),
                new ItemFirstObtainedPromotionMatcher(Optional.of(WHEAT)),
                ItemFirstObtainedEvidence.class);
        helper.assertValueEqual(item.itemId(), WHEAT, "item evidence");

        AnimalBredEvidence bred = evidence(AnimalBredPromotionAdapter.INSTANCE,
                new AnimalBredPayload(SHEEP, COW, COW),
                new AnimalBredPromotionMatcher(Optional.of(COW),
                        Optional.of(AnimalParentPair.of(COW, SHEEP))), AnimalBredEvidence.class);
        helper.assertValueEqual(bred.parents(), AnimalParentPair.of(COW, SHEEP),
                "canonical breeding parents");

        PlayerDiedEvidence death = evidence(PlayerDiedPromotionAdapter.INSTANCE,
                new PlayerDiedPayload(Optional.of(FALL)),
                new PlayerDiedPromotionMatcher(new DamageTypeCriterion.Exact(FALL)),
                PlayerDiedEvidence.class);
        helper.assertValueEqual(death.damageTypeId(), Optional.of(FALL), "death evidence ID");
        PlayerDiedEvidence missingDeath = evidence(PlayerDiedPromotionAdapter.INSTANCE,
                new PlayerDiedPayload(Optional.empty()),
                new PlayerDiedPromotionMatcher(DamageTypeCriterion.Missing.INSTANCE),
                PlayerDiedEvidence.class);
        helper.assertTrue(missingDeath.damageTypeId().isEmpty(), "missing death evidence ID");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void animalFeedingEvidenceFiltersAndPreservesBatch(GameTestHelper helper) {
        AnimalFedPayload payload = new AnimalFedPayload(COW, List.of(
                new AnimalFeedEntry(CARROT, FeedingOutcome.GROWTH_ACCELERATED, 3),
                new AnimalFeedEntry(WHEAT, FeedingOutcome.LOVE_MODE, 2),
                new AnimalFeedEntry(CARROT, FeedingOutcome.LOVE_MODE, 1)));
        AnimalFeedingEvidence exact = evidence(AnimalFedPromotionAdapter.INSTANCE, payload,
                new AnimalFedPromotionMatcher(Optional.of(COW), Optional.of(WHEAT), Optional.empty()),
                AnimalFeedingEvidence.class);
        helper.assertValueEqual(exact.matchingEntries(),
                List.of(new AnimalFeedEntry(WHEAT, FeedingOutcome.LOVE_MODE, 2)),
                "exact feeding evidence");

        AnimalFeedingEvidence wildcard = evidence(AnimalFedPromotionAdapter.INSTANCE, payload,
                new AnimalFedPromotionMatcher(Optional.empty(), Optional.empty(), Optional.empty()),
                AnimalFeedingEvidence.class);
        helper.assertValueEqual(wildcard.matchingEntries(), List.of(
                new AnimalFeedEntry(CARROT, FeedingOutcome.LOVE_MODE, 1),
                new AnimalFeedEntry(CARROT, FeedingOutcome.GROWTH_ACCELERATED, 3),
                new AnimalFeedEntry(WHEAT, FeedingOutcome.LOVE_MODE, 2)),
                "wildcard feeding evidence order and counts");
        helper.assertTrue(AnimalFedPromotionAdapter.INSTANCE.createEvidence(payload,
                new AnimalFedPromotionMatcher(Optional.of(SHEEP), Optional.empty(), Optional.empty())).isEmpty(),
                "mismatched feeding matcher created evidence");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void animalFeedingEvidenceIsImmutableAndValidated(GameTestHelper helper) {
        List<AnimalFeedEntry> mutable = new ArrayList<>();
        mutable.add(new AnimalFeedEntry(WHEAT, FeedingOutcome.LOVE_MODE, 2));
        AnimalFeedingEvidence evidence = new AnimalFeedingEvidence(COW, mutable);
        mutable.add(new AnimalFeedEntry(CARROT, FeedingOutcome.GROWTH_ACCELERATED, 1));
        helper.assertValueEqual(evidence.matchingEntries().size(), 1, "feeding defensive copy");
        expectUnsupported(helper, () -> evidence.matchingEntries().clear(), "feeding evidence list");
        expectRejected(helper, () -> new AnimalFeedingEvidence(COW, List.of()), "empty feeding evidence");
        expectRejected(helper, () -> new AnimalFeedingEvidence(COW, List.of(
                new AnimalFeedEntry(WHEAT, FeedingOutcome.LOVE_MODE, 1),
                new AnimalFeedEntry(WHEAT, FeedingOutcome.LOVE_MODE, 2))), "duplicate feeding evidence");

        List<AnimalFeedEntry> tooMany = new ArrayList<>();
        for (int index = 0; index <= AnimalFedPayload.MAX_ENTRIES; index++) {
            tooMany.add(new AnimalFeedEntry(id("food_" + index), FeedingOutcome.LOVE_MODE, 1));
        }
        expectRejected(helper, () -> new AnimalFeedingEvidence(COW, tooMany),
                "feeding evidence entry limit");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void vanillaEvidenceAndPayloadEnforceMilestoneContract(GameTestHelper helper) {
        GameplayPromotionDefinition definition = vanillaDefinition("walk_milestone", id("walk_signal"));
        VanillaStatThresholdCrossedPayload observationPayload = vanillaPayload(definition.id());
        VanillaStatMilestoneEvidence evidence = evidence(VanillaStatThresholdPromotionAdapter.INSTANCE,
                observationPayload, (VanillaStatThresholdPromotionMatcher) definition.matcher(),
                VanillaStatMilestoneEvidence.class);
        helper.assertValueEqual(evidence.watchId(), definition.id(), "Vanilla evidence watch ID");
        helper.assertValueEqual(evidence.delta(), 15L, "Vanilla evidence delta");
        new GameplayActionPayload(definition.id(), definition.observationTypeId(),
                observationPayload.subjectId(), evidence);
        expectRejected(helper, () -> new GameplayActionPayload(id("different_rule"),
                definition.observationTypeId(), observationPayload.subjectId(), evidence),
                "Vanilla watch/promotion mismatch");
        expectRejected(helper, () -> new VanillaStatMilestoneEvidence(definition.id(),
                evidence.metricKey(), evidence.statisticKey(), 90, 105, 14, 100),
                "Vanilla evidence delta mismatch");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void dynamicSignalTypesAreCanonicalAndDeterministic(GameTestHelper helper) {
        ResourceLocation sharedSignal = id("shared_gameplay_signal");
        ResourceLocation earlierSignal = id("a_gameplay_signal");
        GameplayPromotionDefinition first = blockDefinition("shared_first", sharedSignal, STONE);
        GameplayPromotionDefinition second = itemDefinition("shared_second", sharedSignal, WHEAT);
        GameplayPromotionDefinition earlier = itemDefinition("earlier", earlierSignal, CARROT);
        GameplayPromotionManager.Prepared prepared = GameplayPromotionManager.compile(
                List.of(second, earlier, first));
        helper.assertValueEqual(prepared.signalTypes().size(), 2, "compiled signal type count");
        InteractionSignalType<GameplayActionPayload> type = prepared.signalTypes().get(sharedSignal);
        helper.assertValueEqual(type.id(), sharedSignal, "dynamic signal ID");
        helper.assertValueEqual(type.mode(), InteractionMode.SPONTANEOUS, "dynamic signal mode");
        helper.assertTrue(type.payloadType() == GameplayActionPayload.class,
                "dynamic signal payload contract");
        helper.assertTrue(prepared.signalTypes().get(first.signalId())
                == prepared.signalTypes().get(second.signalId()), "shared type identity");
        helper.assertValueEqual(prepared.signalTypes().keySet().stream().toList(),
                List.of(earlierSignal, sharedSignal),
                "deterministic signal type order");
        expectUnsupported(helper, () -> prepared.signalTypes().clear(), "signal type table");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void staticSignalContractIsReusedOrRejected(GameTestHelper helper) {
        ResourceLocation staticId = id("synthetic_static_gameplay");
        InteractionSignalType<GameplayActionPayload> canonical = new InteractionSignalType<>(
                staticId, InteractionMode.SPONTANEOUS, GameplayActionPayload.class);
        GameplayPromotionDefinition compatible = blockDefinition("compatible_static", staticId, STONE);
        Map<ResourceLocation, InteractionSignalType<GameplayActionPayload>> compiled =
                GameplayPromotionSignalTypeCompiler.compile(List.of(compatible), Map.of(staticId, canonical));
        helper.assertTrue(compiled.get(staticId) == canonical, "canonical static type was not reused");

        GameplayPromotionDefinition conflicting = blockDefinition("conflicting_static",
                InteractionSignalTypes.TEST_SPONTANEOUS.id(), STONE);
        expectRejected(helper, () -> GameplayPromotionManager.compile(List.of(conflicting)),
                "static payload contract conflict");
        GameplayPromotionDefinition explicitConflict = blockDefinition("explicit_static",
                InteractionSignalTypes.EXPLICIT_GOD_CALL.id(), STONE);
        expectRejected(helper, () -> GameplayPromotionManager.compile(List.of(explicitConflict)),
                "static mode contract conflict");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void pureFactoryCreatesOnlyValidatedSignals(GameTestHelper helper) {
        GameplayPromotionDefinition definition = blockDefinition("factory_block", id("unbound_signal"), STONE);
        GameplayPromotionManager.Prepared prepared = GameplayPromotionManager.compile(List.of(definition));
        GameplayPromotionSnapshot snapshot = snapshot(prepared, 9);
        UUID playerId = UUID.randomUUID();
        GameplayObservation<BlockBrokenPayload> observation = new GameplayObservation<>(
                GameplayObservationTypes.BLOCK_BROKEN, playerId, 123,
                new BlockBrokenPayload(STONE, OVERWORLD, new BlockPos(4, 5, 6)));
        InteractionSignal<GameplayActionPayload> signal = GameplayPromotionSignalFactory.create(
                snapshot, definition, observation).orElseThrow();
        helper.assertValueEqual(signal.type().id(), definition.signalId(), "factory signal ID");
        helper.assertValueEqual(signal.mode(), InteractionMode.SPONTANEOUS, "factory mode");
        helper.assertValueEqual(signal.initiatingPlayerId(), playerId, "factory player");
        helper.assertValueEqual(signal.involvedPlayerIds(), Set.of(), "factory involved players");
        helper.assertValueEqual(signal.payload().promotionRuleId(), definition.id(),
                "factory promotion rule ID");
        helper.assertValueEqual(signal.payload().subjectId(), Optional.of(STONE), "factory subject");
        helper.assertTrue(signal.payload().evidence() instanceof BlockBrokenEvidence,
                "factory evidence type");

        GameplayPromotionDefinition foreign = blockDefinition("foreign", definition.signalId(), STONE);
        helper.assertTrue(GameplayPromotionSignalFactory.create(snapshot, foreign, observation).isEmpty(),
                "factory accepted definition outside snapshot");
        GameplayObservation<BlockBrokenPayload> mismatch = new GameplayObservation<>(
                GameplayObservationTypes.BLOCK_BROKEN, playerId, 123,
                new BlockBrokenPayload(BuiltInRegistries.BLOCK.getKey(Blocks.DIRT), OVERWORLD, BlockPos.ZERO));
        helper.assertTrue(GameplayPromotionSignalFactory.create(snapshot, definition, mismatch).isEmpty(),
                "factory accepted matcher mismatch");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void signalTypesJoinAtomicSnapshotAndPreserveWatches(GameTestHelper helper) {
        GameplayPromotionDefinition vanilla = vanillaDefinition("atomic_watch", id("atomic_signal"));
        GameplayPromotionDefinition block = blockDefinition("atomic_block", id("atomic_signal"), STONE);
        GameplayPromotionManager.Prepared prepared = GameplayPromotionManager.compile(List.of(block, vanilla));
        helper.assertTrue(prepared.watchedMetrics().byWatchId().containsKey(vanilla.id()),
                "watched metric was not preserved");
        helper.assertValueEqual(prepared.signalTypes().size(), 1, "atomic signal table");

        GameplayPromotionManager manager = GameplayPromotionManager.INSTANCE;
        GameplayPromotionSnapshot original = manager.snapshot();
        try {
            manager.apply(prepared, helper.getLevel().getServer().getResourceManager(),
                    InactiveProfiler.INSTANCE);
            GameplayPromotionSnapshot committed = manager.snapshot();
            helper.assertTrue(committed.signalTypes().containsKey(vanilla.signalId()),
                    "signal type table was not committed");

            GameplayPromotionDefinition conflict = blockDefinition("atomic_conflict",
                    InteractionSignalTypes.TEST_SPONTANEOUS.id(), STONE);
            expectRejected(helper, () -> GameplayPromotionManager.compile(List.of(conflict)),
                    "transactional static signal conflict");
            helper.assertTrue(manager.snapshot() == committed,
                    "failed signal compilation replaced snapshot");
            helper.assertValueEqual(manager.snapshot().generation(), committed.generation(),
                    "failed signal compilation changed generation");
            helper.assertTrue(manager.snapshot().signalTypes() == committed.signalTypes(),
                    "failed signal compilation replaced signal type table");
        } finally {
            manager.restoreSnapshotForTesting(original);
        }
        helper.succeed();
    }

    private static VanillaStatThresholdCrossedPayload vanillaPayload(ResourceLocation watchId) {
        return new VanillaStatThresholdCrossedPayload(watchId,
                GameplayMetricKey.aggregate(id("travel_distance")),
                VanillaStatisticKey.custom(Stats.WALK_ONE_CM), 90, 105, 15, 100);
    }

    private static GameplayPromotionDefinition vanillaDefinition(String path, ResourceLocation signalId) {
        return new GameplayPromotionDefinition(id(path),
                GameplayObservationTypes.VANILLA_STAT_THRESHOLD_CROSSED.id(), signalId, 0,
                GameplayPromotionDefinition.DEFAULT_ATTEMPT_COOLDOWN_TICKS,
                new VanillaStatThresholdPromotionMatcher(
                        VanillaStatisticKey.custom(Stats.WALK_ONE_CM),
                        GameplayMetricKey.aggregate(id("travel_distance")), 100, 100));
    }

    private static GameplayPromotionDefinition blockDefinition(String path,
            ResourceLocation signalId, ResourceLocation blockId) {
        return new GameplayPromotionDefinition(id(path), GameplayObservationTypes.BLOCK_BROKEN.id(),
                signalId, 0, GameplayPromotionDefinition.DEFAULT_ATTEMPT_COOLDOWN_TICKS,
                new BlockBrokenPromotionMatcher(Optional.of(blockId), Optional.empty()));
    }

    private static GameplayPromotionDefinition itemDefinition(String path,
            ResourceLocation signalId, ResourceLocation itemId) {
        return new GameplayPromotionDefinition(id(path),
                GameplayObservationTypes.ITEM_FIRST_OBTAINED.id(), signalId, 0,
                GameplayPromotionDefinition.DEFAULT_ATTEMPT_COOLDOWN_TICKS,
                new ItemFirstObtainedPromotionMatcher(Optional.of(itemId)));
    }

    private static GameplayPromotionSnapshot snapshot(GameplayPromotionManager.Prepared prepared,
            long generation) {
        return new GameplayPromotionSnapshot(prepared.definitions(), prepared.orderedDefinitions(),
                prepared.index(), prepared.watchedMetrics(), prepared.signalTypes(), generation);
    }

    private static <P extends GameplayObservationPayload, M extends PromotionMatcher,
            E extends GameplayActionEvidence> E evidence(GameplayPromotionAdapter<P, M> adapter,
            P payload, M matcher, Class<E> evidenceType) {
        return evidenceType.cast(adapter.createEvidence(payload, matcher).orElseThrow());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }

    private static void expectRejected(GameTestHelper helper, Runnable action, String label) {
        try {
            action.run();
            helper.fail(label + " was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected validation rejection.
        }
    }

    private static void expectUnsupported(GameTestHelper helper, Runnable action, String label) {
        try {
            action.run();
            helper.fail(label + " was mutable");
        } catch (UnsupportedOperationException expected) {
            // Expected immutable collection.
        }
    }
}
