package com.sande.mythictrpg.gameplay.promotion;

import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import com.sande.mythictrpg.gameplay.observation.BlockBrokenPayload;
import com.sande.mythictrpg.gameplay.observation.EntityKilledPayload;
import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationPayload;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationType;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import com.sande.mythictrpg.gameplay.observation.ItemFirstObtainedPayload;
import com.sande.mythictrpg.gameplay.observation.MatureCropHarvestPayload;
import com.sande.mythictrpg.gameplay.sampling.VanillaStatisticKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stats;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourAThreeAThreeAGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation OVERWORLD = Level.OVERWORLD.location();
    private static final ResourceLocation NETHER = Level.NETHER.location();
    private static final ResourceLocation STONE = BuiltInRegistries.BLOCK.getKey(Blocks.STONE);
    private static final ResourceLocation WHEAT = BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT);
    private static final ResourceLocation CARROTS = BuiltInRegistries.BLOCK.getKey(Blocks.CARROTS);
    private static final ResourceLocation ZOMBIE = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.ZOMBIE);
    private static final ResourceLocation DIAMOND = BuiltInRegistries.ITEM.getKey(Items.DIAMOND);

    private PhaseFourAThreeAThreeAGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void simpleMatcherSchemasAreStrictAndRegistryAware(GameTestHelper helper) {
        BlockBrokenPromotionMatcher block = GameplayPromotionSchema.parseBlockBrokenMatcher(json(
                "{\"block\":\"minecraft:stone\",\"dimension\":\"missingaddon:realm\"}"));
        helper.assertValueEqual(block.blockId(), Optional.of(STONE), "registered block matcher");
        helper.assertValueEqual(block.dimensionId(), Optional.of(id("missingaddon", "realm")),
                "syntax-only dimension matcher");
        helper.assertTrue(GameplayPromotionSchema.parseBlockBrokenMatcher(json("{}"))
                .blockId().isEmpty(), "omitted block was not wildcard");
        helper.assertValueEqual(GameplayPromotionSchema.parseMatureCropHarvestMatcher(
                json("{\"crop\":\"minecraft:wheat\"}")).cropBlockId(), Optional.of(WHEAT),
                "registered crop matcher");
        helper.assertValueEqual(GameplayPromotionSchema.parseEntityKilledMatcher(
                json("{\"entity\":\"minecraft:zombie\"}")).entityTypeId(), Optional.of(ZOMBIE),
                "registered entity matcher");
        helper.assertValueEqual(GameplayPromotionSchema.parseItemFirstObtainedMatcher(
                json("{\"item\":\"minecraft:diamond\"}")).itemId(), Optional.of(DIAMOND),
                "registered item matcher");

        expectRejected(helper, () -> GameplayPromotionSchema.parseBlockBrokenMatcher(
                json("{\"block\":\"missingaddon:block\"}")), "unknown block");
        expectRejected(helper, () -> GameplayPromotionSchema.parseMatureCropHarvestMatcher(
                json("{\"crop\":\"missingaddon:crop\"}")), "unknown crop");
        expectRejected(helper, () -> GameplayPromotionSchema.parseEntityKilledMatcher(
                json("{\"entity\":\"missingaddon:entity\"}")), "unknown entity");
        expectRejected(helper, () -> GameplayPromotionSchema.parseItemFirstObtainedMatcher(
                json("{\"item\":\"missingaddon:item\"}")), "unknown item");
        expectRejected(helper, () -> GameplayPromotionSchema.parseBlockBrokenMatcher(
                json("{\"block\":null}")), "null block");
        expectRejected(helper, () -> GameplayPromotionSchema.parseBlockBrokenMatcher(
                json("{\"unexpected\":true}")), "unknown matcher field");
        expectRejected(helper, () -> GameplayPromotionSchema.parseEntityKilledMatcher(
                json("{\"dimension\":\"overworld\"}")), "unnamespaced dimension");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void exactAndWildcardCandidatesMergeDeterministically(GameTestHelper helper) {
        GameplayPromotionDefinition rejectedExact = blockDefinition(
                "rejected_exact", 40, Optional.of(STONE), Optional.of(NETHER));
        GameplayPromotionDefinition wildcardHigh = blockDefinition(
                "wildcard_high", 30, Optional.empty(), Optional.of(OVERWORLD));
        GameplayPromotionDefinition exactMiddle = blockDefinition(
                "exact_middle", 20, Optional.of(STONE), Optional.empty());
        GameplayPromotionDefinition wildcardLow = blockDefinition(
                "wildcard_low", 10, Optional.empty(), Optional.empty());
        GameplayPromotionIndex index = GameplayPromotionManager.compile(List.of(
                wildcardLow, exactMiddle, rejectedExact, wildcardHigh)).index();

        List<GameplayPromotionDefinition> matches = index.match(observation(
                GameplayObservationTypes.BLOCK_BROKEN,
                new BlockBrokenPayload(STONE, OVERWORLD, BlockPos.ZERO)));
        helper.assertValueEqual(matches.stream().map(GameplayPromotionDefinition::id).toList(),
                List.of(wildcardHigh.id(), exactMiddle.id(), wildcardLow.id()),
                "exact/wildcard deterministic match order");
        helper.assertValueEqual(new HashSet<>(matches).size(), matches.size(),
                "exact/wildcard merge duplicated a rule");
        helper.assertValueEqual(index.exactCandidates(GameplayObservationTypes.BLOCK_BROKEN.id(),
                PromotionLookupKey.id(STONE)).size(), 2, "exact block bucket size");
        helper.assertValueEqual(index.wildcardCandidates(
                GameplayObservationTypes.BLOCK_BROKEN.id()).size(), 2,
                "wildcard block bucket size");
        expectUnsupported(helper, () -> matches.add(exactMiddle), "match result");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void simpleAdaptersApplySubjectAndDimensionConditions(GameTestHelper helper) {
        GameplayPromotionDefinition cropExact = cropDefinition("crop_exact", 20, Optional.of(WHEAT));
        GameplayPromotionDefinition cropAny = cropDefinition("crop_any", 10, Optional.empty());
        GameplayPromotionDefinition killExact = killDefinition(
                "kill_exact", 20, Optional.of(ZOMBIE), Optional.of(OVERWORLD));
        GameplayPromotionDefinition killWrongDimension = killDefinition(
                "kill_wrong_dimension", 30, Optional.of(ZOMBIE), Optional.of(NETHER));
        GameplayPromotionDefinition killAny = killDefinition(
                "kill_any", 10, Optional.empty(), Optional.empty());
        GameplayPromotionDefinition itemExact = itemDefinition("item_exact", 20, Optional.of(DIAMOND));
        GameplayPromotionDefinition itemAny = itemDefinition("item_any", 10, Optional.empty());
        GameplayPromotionIndex index = GameplayPromotionManager.compile(List.of(
                cropExact, cropAny, killExact, killWrongDimension, killAny, itemExact, itemAny)).index();

        helper.assertValueEqual(index.match(observation(GameplayObservationTypes.MATURE_CROP_HARVESTED,
                        new MatureCropHarvestPayload(WHEAT))).stream()
                        .map(GameplayPromotionDefinition::id).toList(),
                List.of(cropExact.id(), cropAny.id()), "wheat promotion matches");
        helper.assertValueEqual(index.match(observation(GameplayObservationTypes.MATURE_CROP_HARVESTED,
                        new MatureCropHarvestPayload(CARROTS))).stream()
                        .map(GameplayPromotionDefinition::id).toList(),
                List.of(cropAny.id()), "crop wildcard match");
        helper.assertValueEqual(index.match(observation(GameplayObservationTypes.ENTITY_KILLED,
                        new EntityKilledPayload(ZOMBIE, OVERWORLD))).stream()
                        .map(GameplayPromotionDefinition::id).toList(),
                List.of(killExact.id(), killAny.id()), "entity/dimension promotion matches");
        helper.assertValueEqual(index.match(observation(GameplayObservationTypes.ITEM_FIRST_OBTAINED,
                        new ItemFirstObtainedPayload(DIAMOND))).stream()
                        .map(GameplayPromotionDefinition::id).toList(),
                List.of(itemExact.id(), itemAny.id()), "item promotion matches");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void vanillaExactLookupRemainsCompatible(GameTestHelper helper) {
        GameplayPromotionDefinition vanilla = new GameplayPromotionDefinition(
                id("vanilla_exact"), GameplayObservationTypes.VANILLA_STAT_THRESHOLD_CROSSED.id(),
                id("signal_vanilla_exact"), 0,
                GameplayPromotionDefinition.DEFAULT_ATTEMPT_COOLDOWN_TICKS,
                new VanillaStatThresholdPromotionMatcher(
                        VanillaStatisticKey.custom(Stats.WALK_ONE_CM),
                        GameplayMetricKey.aggregate(id("travel_distance")), 100, 100));
        GameplayPromotionManager.Prepared prepared = GameplayPromotionManager.compile(List.of(vanilla));
        helper.assertValueEqual(prepared.index().exactCandidates(vanilla.observationTypeId(),
                PromotionLookupKey.id(vanilla.id())), List.of(vanilla), "Vanilla exact lookup");
        helper.assertValueEqual(prepared.watchedMetrics().byWatchId().get(vanilla.id()).watchId(),
                vanilla.id(), "Vanilla watch ID");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void effectiveBucketLimitAllows64AndRejects65(GameTestHelper helper) {
        List<GameplayPromotionDefinition> wildcard64 = new ArrayList<>();
        for (int index = 0; index < GameplayPromotionIndex.MAX_RUNTIME_CANDIDATES; index++) {
            wildcard64.add(cropDefinition("wildcard_limit_" + index, index, Optional.empty()));
        }
        helper.assertValueEqual(GameplayPromotionManager.compile(wildcard64).index()
                .wildcardCandidates(GameplayObservationTypes.MATURE_CROP_HARVESTED.id()).size(),
                64, "maximum wildcard bucket size");
        List<GameplayPromotionDefinition> wildcard65 = new ArrayList<>(wildcard64);
        wildcard65.add(cropDefinition("wildcard_limit_64", 64, Optional.empty()));
        expectRejected(helper, () -> GameplayPromotionManager.compile(wildcard65),
                "65 wildcard candidates");

        List<GameplayPromotionDefinition> combined64 = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            combined64.add(cropDefinition("combined_wildcard_" + index, index, Optional.empty()));
            combined64.add(cropDefinition("combined_exact_" + index, index, Optional.of(WHEAT)));
        }
        helper.assertValueEqual(GameplayPromotionManager.compile(combined64).index().match(observation(
                GameplayObservationTypes.MATURE_CROP_HARVESTED,
                new MatureCropHarvestPayload(WHEAT))).size(), 64,
                "maximum combined runtime candidates");
        List<GameplayPromotionDefinition> combined65 = new ArrayList<>(combined64);
        combined65.add(cropDefinition("combined_exact_32", 32, Optional.of(WHEAT)));
        expectRejected(helper, () -> GameplayPromotionManager.compile(combined65),
                "65 combined candidates");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void nonSamplingPromotionsDoNotCreateWatchesAndWrongMatchersFail(GameTestHelper helper) {
        List<GameplayPromotionDefinition> definitions = List.of(
                blockDefinition("no_watch_block", 0, Optional.of(STONE), Optional.empty()),
                cropDefinition("no_watch_crop", 0, Optional.of(WHEAT)),
                killDefinition("no_watch_kill", 0, Optional.of(ZOMBIE), Optional.empty()),
                itemDefinition("no_watch_item", 0, Optional.of(DIAMOND)));
        GameplayPromotionManager.Prepared prepared = GameplayPromotionManager.compile(definitions);
        helper.assertTrue(prepared.watchedMetrics().isEmpty(),
                "non-sampling promotions created sampling watches");

        GameplayPromotionDefinition wrongMatcher = new GameplayPromotionDefinition(
                id("wrong_matcher"), GameplayObservationTypes.BLOCK_BROKEN.id(), id("signal_wrong"), 0,
                GameplayPromotionDefinition.DEFAULT_ATTEMPT_COOLDOWN_TICKS,
                new ItemFirstObtainedPromotionMatcher(Optional.of(DIAMOND)));
        expectRejected(helper, () -> GameplayPromotionManager.compile(List.of(wrongMatcher)),
                "wrong typed matcher");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void bucketCompilationFailureKeepsCommittedBundle(GameTestHelper helper) {
        GameplayPromotionManager manager = GameplayPromotionManager.INSTANCE;
        GameplayPromotionSnapshot original = manager.snapshot();
        try {
            GameplayPromotionManager.Prepared valid = GameplayPromotionManager.compile(List.of(
                    cropDefinition("transaction_valid", 0, Optional.empty())));
            manager.apply(valid, helper.getLevel().getServer().getResourceManager(),
                    InactiveProfiler.INSTANCE);
            GameplayPromotionSnapshot committed = manager.snapshot();

            List<GameplayPromotionDefinition> invalid = new ArrayList<>();
            for (int index = 0; index <= GameplayPromotionIndex.MAX_RUNTIME_CANDIDATES; index++) {
                invalid.add(cropDefinition("transaction_invalid_" + index, index, Optional.empty()));
            }
            expectRejected(helper, () -> GameplayPromotionManager.compile(invalid),
                    "transactional bucket overflow");
            helper.assertTrue(manager.snapshot() == committed,
                    "failed bucket compilation replaced committed bundle");
            helper.assertValueEqual(manager.snapshot().generation(), committed.generation(),
                    "failed bucket compilation changed generation");
        } finally {
            manager.restoreSnapshotForTesting(original);
        }
        helper.succeed();
    }

    private static GameplayPromotionDefinition blockDefinition(String path, int priority,
            Optional<ResourceLocation> block, Optional<ResourceLocation> dimension) {
        return definition(path, GameplayObservationTypes.BLOCK_BROKEN.id(), priority,
                new BlockBrokenPromotionMatcher(block, dimension));
    }

    private static GameplayPromotionDefinition cropDefinition(String path, int priority,
            Optional<ResourceLocation> crop) {
        return definition(path, GameplayObservationTypes.MATURE_CROP_HARVESTED.id(), priority,
                new MatureCropHarvestPromotionMatcher(crop));
    }

    private static GameplayPromotionDefinition killDefinition(String path, int priority,
            Optional<ResourceLocation> entity, Optional<ResourceLocation> dimension) {
        return definition(path, GameplayObservationTypes.ENTITY_KILLED.id(), priority,
                new EntityKilledPromotionMatcher(entity, dimension));
    }

    private static GameplayPromotionDefinition itemDefinition(String path, int priority,
            Optional<ResourceLocation> item) {
        return definition(path, GameplayObservationTypes.ITEM_FIRST_OBTAINED.id(), priority,
                new ItemFirstObtainedPromotionMatcher(item));
    }

    private static GameplayPromotionDefinition definition(String path, ResourceLocation observation,
            int priority, PromotionMatcher matcher) {
        return new GameplayPromotionDefinition(id(path), observation, id("signal_" + path), priority,
                GameplayPromotionDefinition.DEFAULT_ATTEMPT_COOLDOWN_TICKS, matcher);
    }

    private static <P extends GameplayObservationPayload> GameplayObservation<P> observation(
            GameplayObservationType<P> type, P payload) {
        return new GameplayObservation<>(type, UUID.randomUUID(), 100, payload);
    }

    private static com.google.gson.JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    private static ResourceLocation id(String path) {
        return id(MythicTrpg.MOD_ID, path);
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static void expectRejected(GameTestHelper helper, Runnable action, String label) {
        try {
            action.run();
            helper.fail(label + " was accepted");
        } catch (IllegalArgumentException | JsonParseException expected) {
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
