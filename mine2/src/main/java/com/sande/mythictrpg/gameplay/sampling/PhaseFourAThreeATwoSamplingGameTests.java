package com.sande.mythictrpg.gameplay.sampling;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import com.sande.mythictrpg.gameplay.observation.GameplayIngressService;
import com.sande.mythictrpg.gameplay.stat.DistanceStatistic;
import com.sande.mythictrpg.gameplay.stat.PlayerGameplayStatisticsView;
import com.sande.mythictrpg.gameplay.stat.StatisticValue;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourAThreeATwoSamplingGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation METRIC = id("travel_distance");

    private PhaseFourAThreeATwoSamplingGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void snapshotChangesRetainSharedCursorsAndBaselineNewSources(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayStatSamplingService service = GameplayStatSamplingService.INSTANCE;
        service.resetForTesting(server);
        GameplayIngressService.INSTANCE.resetForTesting();
        UUID playerId = UUID.randomUUID();
        MutableStatisticsView statistics = new MutableStatisticsView();
        statistics.set(Stats.PLAY_TIME, 90);
        statistics.set(Stats.WALK_ONE_CM, 40);
        statistics.set(Stats.FLY_ONE_CM, 500);
        SamplingPlayerView player = new SamplingPlayerView(playerId, statistics);

        WatchedMetricSnapshot initial = WatchedMetricSnapshot.of(List.of(
                watch("retained", Stats.PLAY_TIME, 100),
                watch("removed", Stats.WALK_ONE_CM, 50)));
        SamplingResult baseline = service.sampleForTesting(server, initial, 0, List.of(player));
        helper.assertValueEqual(baseline.baselinesCreated(), 2, "initial source baselines");
        helper.assertValueEqual(SamplingRuntimeState.get(server).cursorCountForTesting(), 2,
                "initial cursor count");

        statistics.set(Stats.PLAY_TIME, 120);
        WatchedMetricSnapshot replacement = WatchedMetricSnapshot.of(List.of(
                watch("retained", Stats.PLAY_TIME, 100),
                watch("new_source", Stats.FLY_ONE_CM, 100)));
        SamplingResult replaced = service.sampleForTesting(server, replacement, 100, List.of(player));
        helper.assertValueEqual(replaced.crossings().size(), 1,
                "retained source cursor was not preserved across snapshot replacement");
        helper.assertValueEqual(replaced.crossings().getFirst().watchId(), id("retained"),
                "unexpected replacement crossing");
        helper.assertValueEqual(replaced.baselinesCreated(), 1,
                "new source did not baseline without catch-up");
        helper.assertValueEqual(SamplingRuntimeState.get(server).cursorCountForTesting(), 2,
                "removed source cursor was not replaced by the new source cursor");

        service.installManualSnapshot(WatchedMetricSnapshot.empty());
        service.onServerTickPost(new ServerTickEvent.Post(() -> true, server));
        helper.assertTrue(!SamplingRuntimeState.hasStateForTesting(server),
                "empty watch snapshot did not discard runtime sampling state");
        helper.assertValueEqual(GameplayIngressService.INSTANCE.acceptedCountForTesting(), 0L,
                "snapshot changes emitted a gameplay observation");
        service.resetForTesting(server);
        GameplayIngressService.INSTANCE.resetForTesting();
        helper.succeed();
    }

    private static WatchedMetricDefinition watch(String path, ResourceLocation statistic, long threshold) {
        return WatchedMetricDefinition.milestone(id(path), GameplayMetricKey.aggregate(METRIC),
                VanillaStatisticSource.custom(statistic), threshold, 100);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }

    private static final class MutableStatisticsView implements PlayerGameplayStatisticsView {
        private final Map<ResourceLocation, Integer> values = new HashMap<>();

        void set(ResourceLocation statisticId, int value) {
            values.put(statisticId, value);
        }

        @Override
        public StatisticValue blockMined(Block block) {
            return StatisticValue.unavailable();
        }

        @Override
        public StatisticValue entityKilled(EntityType<?> entityType) {
            return StatisticValue.unavailable();
        }

        @Override
        public StatisticValue totalMobKills() {
            return custom(Stats.MOB_KILLS);
        }

        @Override
        public StatisticValue animalsBred() {
            return custom(Stats.ANIMALS_BRED);
        }

        @Override
        public StatisticValue deaths() {
            return custom(Stats.DEATHS);
        }

        @Override
        public StatisticValue playTime() {
            return custom(Stats.PLAY_TIME);
        }

        @Override
        public StatisticValue distance(DistanceStatistic statistic) {
            return custom(statistic.statisticId());
        }

        @Override
        public StatisticValue custom(ResourceLocation statisticId) {
            return StatisticValue.available(values.getOrDefault(statisticId, 0));
        }
    }
}
