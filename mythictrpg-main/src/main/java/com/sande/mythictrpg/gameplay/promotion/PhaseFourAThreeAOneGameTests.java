package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import com.sande.mythictrpg.gameplay.observation.VanillaStatThresholdCrossedPayload;
import com.sande.mythictrpg.gameplay.sampling.VanillaStatisticKey;
import com.sande.mythictrpg.gameplay.sampling.WatchedMetricDefinition;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.stats.Stats;
import net.minecraft.util.profiling.InactiveProfiler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourAThreeAOneGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final String PREFIX = "mythictrpg/gameplay_promotions/";
    private static final ResourceLocation OBSERVATION =
            GameplayObservationTypes.VANILLA_STAT_THRESHOLD_CROSSED.id();
    private static final ResourceLocation METRIC = id("travel_distance");

    private PhaseFourAThreeAOneGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void emptyAndValidResourcesBuildCanonicalSnapshot(GameTestHelper helper) {
        GameplayPromotionManager manager = GameplayPromotionManager.INSTANCE;
        ResourceManager base = helper.getLevel().getServer().getResourceManager();
        GameplayPromotionManager.Prepared empty = GameplayPromotionManager.compile(List.of());
        helper.assertTrue(empty.definitions().isEmpty(), "empty promotion fixture produced definitions");
        GameplayPromotionManager.Prepared production = manager.prepare(
                new OverlayResourceManager(base, Map.of()), InactiveProfiler.INSTANCE);
        ResourceLocation productionId = id("demeter_wheat_harvest");
        helper.assertValueEqual(production.definitions().keySet(), Set.of(productionId),
                "base resources must contain only the production Demeter promotion");

        ResourceLocation defaultFile = file("exampleaddon", "nested/walk_default");
        ResourceLocation explicitFile = file("exampleaddon", "walk_explicit");
        GameplayPromotionManager.Prepared prepared = manager.prepare(new OverlayResourceManager(base, Map.of(
                defaultFile, validJson("mythictrpg:travel_milestone", 10, null, 100_000, 100, null),
                explicitFile, validJson("addon:other_signal", 20, 2_400L, 200_000, 200,
                        "minecraft:cow"))), InactiveProfiler.INSTANCE);

        ResourceLocation defaultId = ResourceLocation.parse("exampleaddon:nested/walk_default");
        ResourceLocation explicitId = ResourceLocation.parse("exampleaddon:walk_explicit");
        helper.assertValueEqual(prepared.definitions().keySet(),
                Set.of(productionId, defaultId, explicitId),
                "overlay resources must preserve production and both fixture promotions");
        GameplayPromotionDefinition defaultDefinition = prepared.definitions().get(defaultId);
        GameplayPromotionDefinition explicitDefinition = prepared.definitions().get(explicitId);
        helper.assertTrue(defaultDefinition != null, "resource path did not derive nested promotion ID");
        helper.assertValueEqual(defaultDefinition.attemptCooldownTicks(), 1_200L,
                "default attempt cooldown");
        helper.assertValueEqual(explicitDefinition.attemptCooldownTicks(), 2_400L,
                "explicit attempt cooldown");
        VanillaStatThresholdPromotionMatcher matcher =
                (VanillaStatThresholdPromotionMatcher) explicitDefinition.matcher();
        helper.assertValueEqual(matcher.metricKey(),
                GameplayMetricKey.subject(METRIC, ResourceLocation.withDefaultNamespace("cow")),
                "canonical metric subject");
        helper.assertValueEqual(explicitDefinition.signalId(), ResourceLocation.parse("addon:other_signal"),
                "signal ID was coupled to interaction rules");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void orderedDefinitionsAndExactTypedLookupAreDeterministic(GameTestHelper helper) {
        GameplayPromotionDefinition low = definition("z_low", 1, 100, 100);
        GameplayPromotionDefinition highB = definition("b_high", 20, 200, 100);
        GameplayPromotionDefinition highA = definition("a_high", 20, 300, 100);
        GameplayPromotionManager.Prepared prepared = GameplayPromotionManager.compile(
                List.of(low, highB, highA));
        helper.assertValueEqual(prepared.orderedDefinitions().stream()
                        .map(GameplayPromotionDefinition::id).toList(),
                List.of(highA.id(), highB.id(), low.id()), "global promotion ordering");

        GameplayPromotionIndex index = prepared.index();
        helper.assertTrue(index.exactCandidates(OBSERVATION,
                PromotionLookupKey.id(highA.id())).getFirst() == highA,
                "exact watch lookup did not return its single definition");
        helper.assertTrue(index.exactCandidates(OBSERVATION,
                PromotionLookupKey.id(id("missing"))).isEmpty(),
                "missing exact watch lookup returned a definition");

        GameplayMetricKey metric = GameplayMetricKey.aggregate(METRIC);
        GameplayObservation<VanillaStatThresholdCrossedPayload> matching = observation(
                highA.id(), metric, VanillaStatisticKey.custom(Stats.WALK_ONE_CM), 300);
        helper.assertTrue(index.match(matching).getFirst() == highA,
                "typed matcher rejected exact stat/metric/threshold payload");
        helper.assertTrue(index.match(observation(highA.id(), metric,
                VanillaStatisticKey.custom(Stats.PLAY_TIME), 300)).isEmpty(),
                "typed matcher accepted a different Vanilla statistic");
        helper.assertTrue(index.match(observation(highA.id(),
                GameplayMetricKey.aggregate(id("other_metric")),
                VanillaStatisticKey.custom(Stats.WALK_ONE_CM), 300)).isEmpty(),
                "typed matcher accepted a different metric");
        helper.assertTrue(index.match(observation(highA.id(), metric,
                VanillaStatisticKey.custom(Stats.WALK_ONE_CM), 301)).isEmpty(),
                "typed matcher accepted a different threshold");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void schemaValidationRejectsUnsupportedAndMalformedData(GameTestHelper helper) {
        ResourceManager base = helper.getLevel().getServer().getResourceManager();
        Map<String, String> invalid = new LinkedHashMap<>();
        invalid.put("unknown_observation", validJson("mythictrpg:signal", 0, null, 100, 100, null)
                .replace(OBSERVATION.toString(), "mythictrpg:block_broken"));
        invalid.put("unknown_field", validJson("mythictrpg:signal", 0, null, 100, 100, null)
                .replaceFirst("\\{", "{\"unexpected\":true,"));
        invalid.put("future_schema", validJson("mythictrpg:signal", 0, null, 100, 100, null)
                .replace("\"schema_version\":1", "\"schema_version\":2"));
        invalid.put("malformed_signal", validJson("mythictrpg:signal", 0, null, 100, 100, null)
                .replace("mythictrpg:signal", "not namespaced"));
        invalid.put("missing_signal", validJson("mythictrpg:signal", 0, null, 100, 100, null)
                .replace("\"signal\":\"mythictrpg:signal\",", ""));
        invalid.put("unsupported_stat", validJson("mythictrpg:signal", 0, null, 100, 100, null)
                .replace("minecraft:walk_one_cm", "minecraft:not_a_stat"));
        invalid.put("wrong_stat_type", validJson("mythictrpg:signal", 0, null, 100, 100, null)
                .replace("minecraft:custom", "minecraft:mined"));
        invalid.put("zero_threshold", validJson("mythictrpg:signal", 0, null, 0, 100, null));
        invalid.put("large_threshold", validJson("mythictrpg:signal", 0, null,
                (long) Integer.MAX_VALUE + 1, 100, null));
        invalid.put("short_interval", validJson("mythictrpg:signal", 0, null, 100, 19, null));
        invalid.put("long_interval", validJson("mythictrpg:signal", 0, null, 100, 12_001, null));
        invalid.put("large_priority", validJson("mythictrpg:signal", 1_000_001, null, 100, 100, null));
        invalid.put("short_cooldown", validJson("mythictrpg:signal", 0, 19L, 100, 100, null));
        invalid.put("long_cooldown", validJson("mythictrpg:signal", 0, 72_001L, 100, 100, null));
        invalid.put("string_metric", validJson("mythictrpg:signal", 0, null, 100, 100, null)
                .replace("{\"type\":\"mythictrpg:travel_distance\"}",
                        "\"mythictrpg:travel_distance\""));
        invalid.put("fractional_threshold", validJson("mythictrpg:signal", 0, null, 100, 100, null)
                .replace("\"threshold\":100", "\"threshold\":1.5"));
        invalid.put("overflow_priority", validJson("mythictrpg:signal", 0, null, 100, 100, null)
                .replace("\"priority\":0", "\"priority\":999999999999999999999"));

        invalid.forEach((name, json) -> expectRejected(helper, () -> GameplayPromotionManager.INSTANCE.prepare(
                new OverlayResourceManager(base, Map.of(file("phase4a3invalid", name), json)),
                InactiveProfiler.INSTANCE), name));
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void promotionCountAndDuplicateIdBoundariesAreEnforced(GameTestHelper helper) {
        List<GameplayPromotionDefinition> maximum = new ArrayList<>();
        for (int index = 0; index < GameplayPromotionManager.MAX_PROMOTIONS; index++) {
            maximum.add(definition("limit_" + index, index % 11, index + 1L, 100));
        }
        GameplayPromotionManager.validatePromotionCount(maximum.size());
        helper.assertValueEqual(maximum.size(), 512, "maximum promotion count");

        List<GameplayPromotionDefinition> tooMany = new ArrayList<>(maximum);
        tooMany.add(definition("limit_512", 0, 999, 100));
        expectRejected(helper, () -> GameplayPromotionManager.validatePromotionCount(tooMany.size()),
                "513 promotions");

        GameplayPromotionDefinition duplicate = definition("duplicate", 0, 100, 100);
        expectRejected(helper, () -> GameplayPromotionManager.compile(List.of(duplicate, duplicate)),
                "duplicate promotion ID");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void failedReloadRetainsSnapshotAndSuccessfulReloadIsAtomic(GameTestHelper helper) {
        GameplayPromotionManager manager = GameplayPromotionManager.INSTANCE;
        GameplayPromotionSnapshot original = manager.snapshot();
        ResourceManager base = helper.getLevel().getServer().getResourceManager();
        try {
            GameplayPromotionManager.Prepared first = manager.prepare(new OverlayResourceManager(base, Map.of(
                    file("phase4a3transaction", "first"),
                    validJson("mythictrpg:first", 1, null, 100, 100, null))), InactiveProfiler.INSTANCE);
            manager.apply(first, base, InactiveProfiler.INSTANCE);
            GameplayPromotionSnapshot committed = manager.snapshot();
            long committedGeneration = committed.generation();

            expectRejected(helper, () -> manager.prepare(new OverlayResourceManager(base, Map.of(
                    file("phase4a3transaction", "broken"), "{\"schema_version\":999}")),
                    InactiveProfiler.INSTANCE), "transactional invalid reload");
            helper.assertTrue(manager.snapshot() == committed, "failed reload replaced snapshot reference");
            helper.assertValueEqual(manager.snapshot().generation(), committedGeneration,
                    "failed reload changed generation");

            GameplayPromotionManager.Prepared second = manager.prepare(new OverlayResourceManager(base, Map.of(
                    file("phase4a3transaction", "second"),
                    validJson("missingaddon:no_interaction_binding", 2, null, 200, 100, null))),
                    InactiveProfiler.INSTANCE);
            manager.apply(second, base, InactiveProfiler.INSTANCE);
            helper.assertValueEqual(manager.snapshot().generation(), committedGeneration + 1,
                    "successful reload did not increment generation once");
            helper.assertTrue(manager.snapshot() != committed, "successful reload retained snapshot reference");
            helper.assertTrue(manager.snapshot().definitions().containsKey(
                    ResourceLocation.parse("phase4a3transaction:second")),
                    "successful reload did not atomically replace definitions");
        } finally {
            manager.restoreSnapshotForTesting(original);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void snapshotAndIndexCollectionsAreImmutable(GameTestHelper helper) {
        GameplayPromotionDefinition definition = definition("immutable", 0, 100, 100);
        GameplayPromotionManager.Prepared prepared = GameplayPromotionManager.compile(List.of(definition));
        GameplayPromotionSnapshot snapshot = new GameplayPromotionSnapshot(prepared.definitions(),
                prepared.orderedDefinitions(), prepared.index(), prepared.watchedMetrics(),
                prepared.signalTypes(), 7);
        expectUnsupported(helper, () -> snapshot.definitions().put(id("other"), definition),
                "snapshot definitions");
        expectUnsupported(helper, () -> snapshot.orderedDefinitions().add(definition),
                "ordered definitions");
        expectUnsupported(helper, () -> snapshot.index().exactEntries().put(OBSERVATION, Map.of()),
                "index outer map");
        expectUnsupported(helper, () -> snapshot.index().exactEntries().get(OBSERVATION)
                .put(PromotionLookupKey.id(id("other")), List.of(definition)), "index lookup map");
        expectUnsupported(helper, () -> snapshot.signalTypes().clear(), "snapshot signal types");
        helper.assertValueEqual(PlayerMythProfile.CURRENT_DATA_VERSION, 4,
                "promotion snapshot changed PlayerMythProfile dataVersion");
        helper.assertValueEqual(WatchedMetricDefinition.DEFAULT_INTERVAL_TICKS, 100,
                "promotion snapshot changed sampling defaults");
        helper.succeed();
    }

    private static GameplayPromotionDefinition definition(String path, int priority,
            long threshold, int interval) {
        ResourceLocation promotionId = id(path);
        return new GameplayPromotionDefinition(promotionId, OBSERVATION, id("signal_" + path), priority,
                GameplayPromotionDefinition.DEFAULT_ATTEMPT_COOLDOWN_TICKS,
                new VanillaStatThresholdPromotionMatcher(
                        VanillaStatisticKey.custom(Stats.WALK_ONE_CM),
                        GameplayMetricKey.aggregate(METRIC), threshold, interval));
    }

    private static GameplayObservation<VanillaStatThresholdCrossedPayload> observation(
            ResourceLocation watchId, GameplayMetricKey metric, VanillaStatisticKey statistic, long threshold) {
        return new GameplayObservation<>(GameplayObservationTypes.VANILLA_STAT_THRESHOLD_CROSSED,
                UUID.randomUUID(), 100,
                new VanillaStatThresholdCrossedPayload(watchId, metric, statistic,
                        threshold - 1, threshold, 1, threshold));
    }

    private static String validJson(String signal, int priority, Long cooldown,
            long threshold, int interval, String subject) {
        String cooldownField = cooldown == null ? "" : "\"attempt_cooldown_ticks\":" + cooldown + ",";
        String subjectField = subject == null ? "" : ",\"subject\":\"" + subject + "\"";
        return "{\"schema_version\":1,"
                + "\"observation\":\"" + OBSERVATION + "\","
                + "\"signal\":\"" + signal + "\","
                + "\"priority\":" + priority + "," + cooldownField
                + "\"match\":{\"stat_type\":\"minecraft:custom\","
                + "\"stat\":\"minecraft:walk_one_cm\","
                + "\"metric\":{\"type\":\"mythictrpg:travel_distance\"" + subjectField + "},"
                + "\"threshold\":" + threshold + ","
                + "\"sampling_interval_ticks\":" + interval + "}}";
    }

    private static ResourceLocation file(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, PREFIX + path + ".json");
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }

    private static void expectRejected(GameTestHelper helper, Runnable action, String label) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            rejected = true;
        }
        if (!rejected) {
            helper.fail(label + " was accepted");
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

    private static final class OverlayResourceManager implements ResourceManager {
        private final ResourceManager delegate;
        private final Map<ResourceLocation, Resource> resources;

        private OverlayResourceManager(ResourceManager delegate, Map<ResourceLocation, String> jsonByFile) {
            this.delegate = delegate;
            PackResources source = delegate.listPacks().findFirst().orElseThrow();
            Map<ResourceLocation, Resource> mutable = new LinkedHashMap<>();
            jsonByFile.forEach((file, json) -> {
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                mutable.put(file, new Resource(source, () -> new ByteArrayInputStream(bytes)));
            });
            this.resources = Map.copyOf(mutable);
        }

        @Override
        public Optional<Resource> getResource(ResourceLocation location) {
            return Optional.ofNullable(resources.get(location)).or(() -> delegate.getResource(location));
        }

        @Override
        public Set<String> getNamespaces() {
            Set<String> result = new LinkedHashSet<>(delegate.getNamespaces());
            resources.keySet().stream().map(ResourceLocation::getNamespace).forEach(result::add);
            return Set.copyOf(result);
        }

        @Override
        public List<Resource> getResourceStack(ResourceLocation location) {
            Resource resource = resources.get(location);
            return resource == null ? delegate.getResourceStack(location) : List.of(resource);
        }

        @Override
        public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
            Map<ResourceLocation, Resource> result = new LinkedHashMap<>(delegate.listResources(path, filter));
            resources.forEach((file, resource) -> {
                if (file.getPath().startsWith(path) && filter.test(file)) {
                    result.put(file, resource);
                }
            });
            return result;
        }

        @Override
        public Map<ResourceLocation, List<Resource>> listResourceStacks(
                String path, Predicate<ResourceLocation> filter) {
            Map<ResourceLocation, List<Resource>> result = new LinkedHashMap<>(
                    delegate.listResourceStacks(path, filter));
            resources.forEach((file, resource) -> {
                if (file.getPath().startsWith(path) && filter.test(file)) {
                    result.put(file, List.of(resource));
                }
            });
            return result;
        }

        @Override
        public Stream<PackResources> listPacks() {
            return delegate.listPacks();
        }
    }
}
