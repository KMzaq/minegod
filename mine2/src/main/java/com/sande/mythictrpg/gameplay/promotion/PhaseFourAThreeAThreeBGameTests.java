package com.sande.mythictrpg.gameplay.promotion;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import com.sande.mythictrpg.gameplay.observation.AnimalBredPayload;
import com.sande.mythictrpg.gameplay.observation.AnimalFeedEntry;
import com.sande.mythictrpg.gameplay.observation.AnimalFedPayload;
import com.sande.mythictrpg.gameplay.observation.FeedingOutcome;
import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationPayload;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationType;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import com.sande.mythictrpg.gameplay.observation.PlayerDiedPayload;
import com.sande.mythictrpg.gameplay.sampling.VanillaStatisticKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stats;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourAThreeAThreeBGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation COW = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.COW);
    private static final ResourceLocation SHEEP = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.SHEEP);
    private static final ResourceLocation PIG = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.PIG);
    private static final ResourceLocation WHEAT = BuiltInRegistries.ITEM.getKey(Items.WHEAT);
    private static final ResourceLocation CARROT = BuiltInRegistries.ITEM.getKey(Items.CARROT);
    private static final ResourceLocation FALL = ResourceLocation.withDefaultNamespace("fall");
    private static final ResourceLocation IN_FIRE = ResourceLocation.withDefaultNamespace("in_fire");

    private PhaseFourAThreeAThreeBGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void animalFedSchemaIsStrictAndRegistryAware(GameTestHelper helper) {
        AnimalFedPromotionMatcher matcher = GameplayPromotionSchema.parseAnimalFedMatcher(json(
                "{\"entity\":\"minecraft:cow\",\"food\":\"minecraft:wheat\","
                        + "\"outcome\":\"love_mode\"}"));
        helper.assertValueEqual(matcher.entityTypeId(), Optional.of(COW), "animal feeding entity");
        helper.assertValueEqual(matcher.foodItemId(), Optional.of(WHEAT), "animal feeding food");
        helper.assertValueEqual(matcher.outcome(), Optional.of(FeedingOutcome.LOVE_MODE),
                "animal feeding outcome");
        AnimalFedPromotionMatcher wildcard = GameplayPromotionSchema.parseAnimalFedMatcher(json("{}"));
        helper.assertTrue(wildcard.entityTypeId().isEmpty() && wildcard.foodItemId().isEmpty()
                && wildcard.outcome().isEmpty(), "empty animal feeding matcher was not wildcard");
        helper.assertValueEqual(GameplayPromotionSchema.parseAnimalFedMatcher(
                json("{\"outcome\":\"growth_accelerated\"}")).outcome(),
                Optional.of(FeedingOutcome.GROWTH_ACCELERATED), "growth outcome");

        expectRejected(helper, () -> GameplayPromotionSchema.parseAnimalFedMatcher(
                json("{\"entity\":\"missingaddon:animal\"}")), "unknown feeding entity");
        expectRejected(helper, () -> GameplayPromotionSchema.parseAnimalFedMatcher(
                json("{\"food\":\"missingaddon:food\"}")), "unknown feeding food");
        expectRejected(helper, () -> GameplayPromotionSchema.parseAnimalFedMatcher(
                json("{\"outcome\":\"LOVE_MODE\"}")), "noncanonical feeding outcome");
        expectRejected(helper, () -> GameplayPromotionSchema.parseAnimalFedMatcher(
                json("{\"entity\":null}")), "null feeding entity");
        expectRejected(helper, () -> GameplayPromotionSchema.parseAnimalFedMatcher(
                json("{\"food\":null}")), "null feeding food");
        expectRejected(helper, () -> GameplayPromotionSchema.parseAnimalFedMatcher(
                json("{\"outcome\":null}")), "null feeding outcome");
        expectRejected(helper, () -> GameplayPromotionSchema.parseAnimalFedMatcher(
                json("{\"unexpected\":true}")), "unknown feeding field");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void animalFedMatchesBatchWithoutDuplicatingDefinitions(GameTestHelper helper) {
        GameplayPromotionDefinition anyEntry = fedDefinition(
                "fed_any", 60, Optional.empty(), Optional.empty(), Optional.empty());
        GameplayPromotionDefinition wrongOutcome = fedDefinition(
                "fed_wrong_outcome", 50, Optional.of(COW), Optional.of(WHEAT),
                Optional.of(FeedingOutcome.GROWTH_ACCELERATED));
        GameplayPromotionDefinition growth = fedDefinition(
                "fed_growth", 40, Optional.of(COW), Optional.empty(),
                Optional.of(FeedingOutcome.GROWTH_ACCELERATED));
        GameplayPromotionDefinition exactLove = fedDefinition(
                "fed_exact_love", 30, Optional.of(COW), Optional.of(WHEAT),
                Optional.of(FeedingOutcome.LOVE_MODE));
        GameplayPromotionDefinition wrongEntity = fedDefinition(
                "fed_wrong_entity", 70, Optional.of(SHEEP), Optional.empty(), Optional.empty());
        GameplayPromotionIndex index = GameplayPromotionManager.compile(List.of(
                exactLove, anyEntry, wrongOutcome, growth, wrongEntity)).index();
        AnimalFedPayload payload = new AnimalFedPayload(COW, List.of(
                new AnimalFeedEntry(WHEAT, FeedingOutcome.LOVE_MODE, 2),
                new AnimalFeedEntry(CARROT, FeedingOutcome.GROWTH_ACCELERATED, 3)));

        List<GameplayPromotionDefinition> matches = index.match(observation(
                GameplayObservationTypes.ANIMAL_FED, payload));
        helper.assertValueEqual(matches.stream().map(GameplayPromotionDefinition::id).toList(),
                List.of(anyEntry.id(), growth.id(), exactLove.id()), "animal feeding match order");
        helper.assertValueEqual(new HashSet<>(matches).size(), matches.size(),
                "animal feeding batch duplicated a matching definition");
        helper.assertValueEqual(matches.stream().filter(definition -> definition == anyEntry).count(),
                1L, "multi-entry wildcard rule count");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void animalBredSchemaCanonicalizesParentMultiset(GameTestHelper helper) {
        AnimalBredPromotionMatcher forward = GameplayPromotionSchema.parseAnimalBredMatcher(json(
                "{\"child\":\"minecraft:cow\","
                        + "\"parents\":[\"minecraft:cow\",\"minecraft:sheep\"]}"));
        AnimalBredPromotionMatcher reverse = GameplayPromotionSchema.parseAnimalBredMatcher(json(
                "{\"child\":\"minecraft:cow\","
                        + "\"parents\":[\"minecraft:sheep\",\"minecraft:cow\"]}"));
        helper.assertValueEqual(forward.parents(), reverse.parents(), "parent order canonicalization");
        helper.assertValueEqual(GameplayPromotionSchema.parseAnimalBredMatcher(json(
                "{\"parents\":[\"minecraft:cow\",\"minecraft:cow\"]}"))
                .parents().orElseThrow(), AnimalParentPair.of(COW, COW),
                "duplicate parent multiplicity");
        helper.assertTrue(GameplayPromotionSchema.parseAnimalBredMatcher(json("{}"))
                .parents().isEmpty(), "omitted parents were not wildcard");

        expectRejected(helper, () -> GameplayPromotionSchema.parseAnimalBredMatcher(
                json("{\"child\":\"missingaddon:child\"}")), "unknown child");
        expectRejected(helper, () -> GameplayPromotionSchema.parseAnimalBredMatcher(
                json("{\"parents\":[]}")), "empty parent array");
        expectRejected(helper, () -> GameplayPromotionSchema.parseAnimalBredMatcher(
                json("{\"parents\":[\"minecraft:cow\"]}")), "single parent array");
        expectRejected(helper, () -> GameplayPromotionSchema.parseAnimalBredMatcher(
                json("{\"parents\":[\"minecraft:cow\",\"minecraft:sheep\",\"minecraft:pig\"]}")),
                "three parent array");
        expectRejected(helper, () -> GameplayPromotionSchema.parseAnimalBredMatcher(
                json("{\"parents\":[\"minecraft:cow\",7]}")), "numeric parent");
        expectRejected(helper, () -> GameplayPromotionSchema.parseAnimalBredMatcher(
                json("{\"parents\":[\"minecraft:cow\",{}]}")), "object parent");
        expectRejected(helper, () -> GameplayPromotionSchema.parseAnimalBredMatcher(
                json("{\"parents\":[\"minecraft:cow\",null]}")), "null parent");
        expectRejected(helper, () -> GameplayPromotionSchema.parseAnimalBredMatcher(
                json("{\"parents\":[\"minecraft:cow\",\"missingaddon:parent\"]}")),
                "unknown parent entity");
        expectRejected(helper, () -> GameplayPromotionSchema.parseAnimalBredMatcher(
                json("{\"unexpected\":true}")), "unknown breeding field");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void animalBredMatchesUnorderedParentsWithMultiplicity(GameTestHelper helper) {
        GameplayPromotionDefinition any = bredDefinition(
                "bred_any", 40, Optional.empty(), Optional.empty());
        GameplayPromotionDefinition exact = bredDefinition(
                "bred_exact", 30, Optional.of(COW), Optional.of(AnimalParentPair.of(COW, SHEEP)));
        GameplayPromotionDefinition sameParents = bredDefinition(
                "bred_same", 20, Optional.of(COW), Optional.of(AnimalParentPair.of(COW, COW)));
        GameplayPromotionDefinition childOnly = bredDefinition(
                "bred_child", 10, Optional.of(COW), Optional.empty());
        GameplayPromotionIndex index = GameplayPromotionManager.compile(
                List.of(childOnly, sameParents, exact, any)).index();

        helper.assertValueEqual(index.match(observation(GameplayObservationTypes.ANIMAL_BRED,
                        new AnimalBredPayload(SHEEP, COW, COW))).stream()
                        .map(GameplayPromotionDefinition::id).toList(),
                List.of(any.id(), exact.id(), childOnly.id()), "reversed parent pair match");
        helper.assertValueEqual(index.match(observation(GameplayObservationTypes.ANIMAL_BRED,
                        new AnimalBredPayload(COW, COW, COW))).stream()
                        .map(GameplayPromotionDefinition::id).toList(),
                List.of(any.id(), sameParents.id(), childOnly.id()), "duplicate parent pair match");
        helper.assertValueEqual(index.match(observation(GameplayObservationTypes.ANIMAL_BRED,
                        new AnimalBredPayload(COW, PIG, COW))).stream()
                        .map(GameplayPromotionDefinition::id).toList(),
                List.of(any.id(), childOnly.id()), "parent multiset mismatch");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void playerDiedSchemaDistinguishesAnyExactAndMissing(GameTestHelper helper) {
        helper.assertTrue(GameplayPromotionSchema.parsePlayerDiedMatcher(json("{}"))
                .damageTypeCriterion() == DamageTypeCriterion.Any.INSTANCE,
                "omitted damage type was not Any");
        helper.assertValueEqual(GameplayPromotionSchema.parsePlayerDiedMatcher(
                json("{\"damage_type\":\"minecraft:fall\"}"))
                .damageTypeCriterion(), new DamageTypeCriterion.Exact(FALL),
                "exact damage type criterion");
        helper.assertTrue(GameplayPromotionSchema.parsePlayerDiedMatcher(
                json("{\"damage_type\":null}")).damageTypeCriterion()
                == DamageTypeCriterion.Missing.INSTANCE, "null damage type was not Missing");

        for (String invalid : List.of("7", "true", "[]", "{}")) {
            expectRejected(helper, () -> GameplayPromotionSchema.parsePlayerDiedMatcher(
                    json("{\"damage_type\":" + invalid + "}")), "invalid damage type " + invalid);
        }
        expectRejected(helper, () -> GameplayPromotionSchema.parsePlayerDiedMatcher(
                json("{\"damage_type\":\"fall\"}")), "unnamespaced damage type");
        expectRejected(helper, () -> GameplayPromotionSchema.parsePlayerDiedMatcher(
                json("{\"unexpected\":true}")), "unknown death field");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void playerDiedMergesMissingOrExactWithWildcardInOrder(GameTestHelper helper) {
        GameplayPromotionDefinition missing = diedDefinition(
                "death_missing", 40, DamageTypeCriterion.Missing.INSTANCE);
        GameplayPromotionDefinition any = diedDefinition(
                "death_any", 30, DamageTypeCriterion.Any.INSTANCE);
        GameplayPromotionDefinition exactFall = diedDefinition(
                "death_fall", 20, new DamageTypeCriterion.Exact(FALL));
        GameplayPromotionIndex index = GameplayPromotionManager.compile(
                List.of(exactFall, any, missing)).index();

        helper.assertValueEqual(index.match(observation(GameplayObservationTypes.PLAYER_DIED,
                        new PlayerDiedPayload(Optional.empty()))).stream()
                        .map(GameplayPromotionDefinition::id).toList(),
                List.of(missing.id(), any.id()), "missing/wildcard death order");
        helper.assertValueEqual(index.match(observation(GameplayObservationTypes.PLAYER_DIED,
                        new PlayerDiedPayload(Optional.of(FALL)))).stream()
                        .map(GameplayPromotionDefinition::id).toList(),
                List.of(any.id(), exactFall.id()), "exact/wildcard death order");
        helper.assertValueEqual(index.match(observation(GameplayObservationTypes.PLAYER_DIED,
                        new PlayerDiedPayload(Optional.of(IN_FIRE)))).stream()
                        .map(GameplayPromotionDefinition::id).toList(),
                List.of(any.id()), "different damage type match");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void complexReloadKeepsSamplingAndTransactionalBoundaries(GameTestHelper helper) {
        GameplayPromotionDefinition fed = fedDefinition(
                "boundary_fed", 0, Optional.empty(), Optional.empty(), Optional.empty());
        GameplayPromotionDefinition bred = bredDefinition(
                "boundary_bred", 0, Optional.empty(), Optional.empty());
        GameplayPromotionDefinition died = diedDefinition(
                "boundary_died", 0, DamageTypeCriterion.Any.INSTANCE);
        GameplayPromotionDefinition vanilla = new GameplayPromotionDefinition(
                id("boundary_vanilla"), GameplayObservationTypes.VANILLA_STAT_THRESHOLD_CROSSED.id(),
                id("signal_boundary_vanilla"), 0,
                GameplayPromotionDefinition.DEFAULT_ATTEMPT_COOLDOWN_TICKS,
                new VanillaStatThresholdPromotionMatcher(
                        VanillaStatisticKey.custom(Stats.WALK_ONE_CM),
                        GameplayMetricKey.aggregate(id("travel_distance")), 100, 100));
        GameplayPromotionManager.Prepared mixed = GameplayPromotionManager.compile(
                List.of(fed, bred, died, vanilla));
        helper.assertValueEqual(mixed.watchedMetrics().definitions().size(), 1,
                "complex promotions changed Vanilla watch count");
        helper.assertTrue(mixed.watchedMetrics().byWatchId().containsKey(vanilla.id()),
                "Vanilla watch was not preserved");

        GameplayPromotionManager manager = GameplayPromotionManager.INSTANCE;
        GameplayPromotionSnapshot original = manager.snapshot();
        try {
            GameplayPromotionManager.Prepared complexOnly = GameplayPromotionManager.compile(
                    List.of(fed, bred, died));
            manager.apply(complexOnly, helper.getLevel().getServer().getResourceManager(),
                    InactiveProfiler.INSTANCE);
            GameplayPromotionSnapshot committed = manager.snapshot();
            helper.assertTrue(committed.watchedMetrics().isEmpty(),
                    "non-sampling complex promotions created watches");

            List<GameplayPromotionDefinition> overflow = new ArrayList<>();
            for (int index = 0; index <= GameplayPromotionIndex.MAX_RUNTIME_CANDIDATES; index++) {
                overflow.add(fedDefinition("boundary_overflow_" + index, index,
                        Optional.empty(), Optional.empty(), Optional.empty()));
            }
            expectRejected(helper, () -> GameplayPromotionManager.compile(overflow),
                    "complex wildcard bucket overflow");
            helper.assertTrue(manager.snapshot() == committed,
                    "failed complex compilation replaced the committed bundle");
            helper.assertValueEqual(manager.snapshot().generation(), committed.generation(),
                    "failed complex compilation changed generation");
        } finally {
            manager.restoreSnapshotForTesting(original);
        }
        helper.succeed();
    }

    private static GameplayPromotionDefinition fedDefinition(String path, int priority,
            Optional<ResourceLocation> entity, Optional<ResourceLocation> food,
            Optional<FeedingOutcome> outcome) {
        return definition(path, GameplayObservationTypes.ANIMAL_FED.id(), priority,
                new AnimalFedPromotionMatcher(entity, food, outcome));
    }

    private static GameplayPromotionDefinition bredDefinition(String path, int priority,
            Optional<ResourceLocation> child, Optional<AnimalParentPair> parents) {
        return definition(path, GameplayObservationTypes.ANIMAL_BRED.id(), priority,
                new AnimalBredPromotionMatcher(child, parents));
    }

    private static GameplayPromotionDefinition diedDefinition(String path, int priority,
            DamageTypeCriterion criterion) {
        return definition(path, GameplayObservationTypes.PLAYER_DIED.id(), priority,
                new PlayerDiedPromotionMatcher(criterion));
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
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }

    private static void expectRejected(GameTestHelper helper, Runnable action, String label) {
        try {
            action.run();
            helper.fail(label + " was accepted");
        } catch (IllegalArgumentException | JsonParseException expected) {
            // Expected validation rejection.
        }
    }
}
