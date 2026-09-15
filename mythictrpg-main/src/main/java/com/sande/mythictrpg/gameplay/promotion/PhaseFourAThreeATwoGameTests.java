package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import com.sande.mythictrpg.gameplay.observation.GameplayIngressService;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import com.sande.mythictrpg.gameplay.sampling.GameplayStatSamplingService;
import com.sande.mythictrpg.gameplay.sampling.ThresholdPolicy;
import com.sande.mythictrpg.gameplay.sampling.VanillaStatisticKey;
import com.sande.mythictrpg.gameplay.sampling.VanillaStatisticSource;
import com.sande.mythictrpg.gameplay.sampling.WatchedMetricDefinition;
import com.sande.mythictrpg.gameplay.sampling.WatchedMetricSnapshot;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.stats.Stats;
import net.minecraft.util.profiling.InactiveProfiler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourAThreeATwoGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation OBSERVATION =
            GameplayObservationTypes.VANILLA_STAT_THRESHOLD_CROSSED.id();
    private static final ResourceLocation METRIC = id("travel_distance");

    private PhaseFourAThreeATwoGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void promotionCompilationProducesLosslessMilestoneWatches(GameTestHelper helper) {
        GameplayPromotionManager.Prepared empty = GameplayPromotionManager.compile(List.of());
        helper.assertTrue(empty.watchedMetrics().isEmpty(), "empty promotions produced watched metrics");

        GameplayPromotionDefinition promotion = definition(
                "lossless", Stats.WALK_ONE_CM, METRIC, 123_456, 240, 10);
        GameplayPromotionManager.Prepared prepared = GameplayPromotionManager.compile(List.of(promotion));
        WatchedMetricSnapshot watches = prepared.watchedMetrics();
        helper.assertValueEqual(watches.definitions().size(), 1, "compiled watch count");
        WatchedMetricDefinition watch = watches.byWatchId().get(promotion.id());
        helper.assertTrue(watch != null, "promotion ID was not used as watch ID");
        helper.assertValueEqual(watch.metricKey(), GameplayMetricKey.aggregate(METRIC), "watch metric key");
        helper.assertValueEqual(watch.source(), VanillaStatisticSource.custom(Stats.WALK_ONE_CM),
                "watch statistic source");
        helper.assertValueEqual(watch.thresholdPolicy(), ThresholdPolicy.milestone(123_456),
                "watch milestone threshold");
        helper.assertValueEqual(watch.intervalTicks(), 240, "watch sampling interval");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void sharedSourcesAreGroupedWithoutMergingDistinctWatches(GameTestHelper helper) {
        GameplayPromotionDefinition slower = definition(
                "shared_slow", Stats.PLAY_TIME, id("play_time"), 100, 200, 100);
        GameplayPromotionDefinition faster = definition(
                "shared_fast", Stats.PLAY_TIME, id("play_time"), 100, 40, -100);
        WatchedMetricSnapshot watches = GameplayPromotionManager.compile(List.of(slower, faster)).watchedMetrics();
        VanillaStatisticSource source = VanillaStatisticSource.playTime();

        helper.assertValueEqual(watches.definitions().stream()
                        .map(WatchedMetricDefinition::watchId).toList(),
                List.of(faster.id(), slower.id()), "watch ordering must be independent of promotion priority");
        helper.assertValueEqual(watches.byWatchId().size(), 2,
                "same source and threshold merged distinct promotion watches");
        helper.assertValueEqual(watches.sourceGroups().size(), 1, "shared source group count");
        helper.assertValueEqual(watches.sourceGroups().get(source).intervalTicks(), 40,
                "shared source effective interval");
        helper.assertValueEqual(watches.sourceGroups().get(source).watches().size(), 2,
                "shared source watch count");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void promotionWatchLimitRejectsTheWholeCompilation(GameTestHelper helper) {
        List<GameplayPromotionDefinition> maximum = new ArrayList<>();
        for (int index = 0; index < WatchedMetricSnapshot.MAX_WATCHES; index++) {
            maximum.add(definition("watch_limit_" + index, Stats.PLAY_TIME, id("play_time"),
                    index + 1L, 100, 0));
        }
        helper.assertValueEqual(GameplayPromotionManager.compile(maximum).watchedMetrics()
                .definitions().size(), 256, "maximum compiled watch count");

        List<GameplayPromotionDefinition> tooMany = new ArrayList<>(maximum);
        tooMany.add(definition("watch_limit_256", Stats.PLAY_TIME, id("play_time"), 999, 100, 0));
        expectRejected(helper, () -> GameplayPromotionManager.compile(tooMany), "257 Vanilla watches");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void managerBundleAndSamplingProviderSwitchAtomically(GameTestHelper helper) {
        GameplayPromotionManager manager = GameplayPromotionManager.INSTANCE;
        GameplayStatSamplingService sampling = GameplayStatSamplingService.INSTANCE;
        GameplayPromotionSnapshot original = manager.snapshot();
        ResourceManager resources = helper.getLevel().getServer().getResourceManager();
        sampling.resetForTesting(helper.getLevel().getServer());
        GameplayIngressService.INSTANCE.resetForTesting();
        try {
            GameplayPromotionManager.Prepared first = GameplayPromotionManager.compile(List.of(
                    definition("atomic_first", Stats.PLAY_TIME, id("play_time"), 100, 100, 0)));
            manager.apply(first, resources, InactiveProfiler.INSTANCE);
            GameplayPromotionSnapshot committed = manager.snapshot();
            helper.assertTrue(sampling.snapshot() == committed.watchedMetrics(),
                    "sampling did not pull the committed promotion bundle watch snapshot");
            helper.assertValueEqual(committed.definitions().size(), 1, "committed definitions count");
            helper.assertValueEqual(committed.index().exactCandidates(OBSERVATION,
                    PromotionLookupKey.id(id("atomic_first"))).getFirst().id(),
                    id("atomic_first"), "committed exact index");

            WatchedMetricSnapshot manual = WatchedMetricSnapshot.of(List.of(
                    WatchedMetricDefinition.milestone(id("manual_override"),
                            GameplayMetricKey.aggregate(id("play_time")),
                            VanillaStatisticSource.playTime(), 500, 100)));
            sampling.installManualSnapshot(manual);
            helper.assertTrue(sampling.snapshot() == manual, "manual snapshot did not override production");
            sampling.clearManualSnapshot();
            helper.assertTrue(sampling.snapshot() == committed.watchedMetrics(),
                    "clearing manual snapshot did not restore production provider");

            List<GameplayPromotionDefinition> invalid = new ArrayList<>();
            for (int index = 0; index <= WatchedMetricSnapshot.MAX_WATCHES; index++) {
                invalid.add(definition("atomic_invalid_" + index, Stats.PLAY_TIME, id("play_time"),
                        index + 1L, 100, 0));
            }
            expectRejected(helper, () -> GameplayPromotionManager.compile(invalid),
                    "transactional watch compilation");
            helper.assertTrue(manager.snapshot() == committed,
                    "failed watch compilation replaced the promotion bundle");
            helper.assertValueEqual(manager.snapshot().generation(), committed.generation(),
                    "failed watch compilation changed generation");

            GameplayPromotionManager.Prepared empty = GameplayPromotionManager.compile(List.of());
            manager.apply(empty, resources, InactiveProfiler.INSTANCE);
            GameplayPromotionSnapshot cleared = manager.snapshot();
            helper.assertValueEqual(cleared.generation(), committed.generation() + 1,
                    "successful empty reload generation");
            helper.assertTrue(cleared.definitions().isEmpty(), "empty reload retained definitions");
            helper.assertTrue(cleared.index().exactEntries().isEmpty(), "empty reload retained index entries");
            helper.assertTrue(cleared.watchedMetrics().isEmpty(), "empty reload retained watches");
            helper.assertTrue(sampling.snapshot() == cleared.watchedMetrics(),
                    "sampling provider retained the old bundle after empty reload");
            helper.assertValueEqual(GameplayIngressService.INSTANCE.acceptedCountForTesting(), 0L,
                    "snapshot replacement emitted a gameplay observation");
        } finally {
            manager.restoreSnapshotForTesting(original);
            sampling.resetForTesting(helper.getLevel().getServer());
            GameplayIngressService.INSTANCE.resetForTesting();
        }
        helper.succeed();
    }

    private static GameplayPromotionDefinition definition(String path, ResourceLocation statistic,
            ResourceLocation metric, long threshold, int interval, int priority) {
        ResourceLocation promotionId = id(path);
        return new GameplayPromotionDefinition(promotionId, OBSERVATION, id("signal_" + path), priority,
                GameplayPromotionDefinition.DEFAULT_ATTEMPT_COOLDOWN_TICKS,
                new VanillaStatThresholdPromotionMatcher(
                        VanillaStatisticKey.custom(statistic), GameplayMetricKey.aggregate(metric),
                        threshold, interval));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }

    private static void expectRejected(GameTestHelper helper, Runnable action, String label) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        if (!rejected) {
            helper.fail(label + " was accepted");
        }
    }
}
