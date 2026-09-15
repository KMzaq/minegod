package com.sande.mythictrpg.gameplay.sampling;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import com.sande.mythictrpg.gameplay.observation.GameplayIngressService;
import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import com.sande.mythictrpg.gameplay.observation.VanillaStatThresholdCrossedPayload;
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
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourATwoTwoAGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation PLAY_TIME_METRIC = id("play_time");

    private PhaseFourATwoTwoAGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void snapshotValidationEnforcesBounds(GameTestHelper helper) {
        VanillaStatisticSource source = VanillaStatisticSource.playTime();
        expectInvalid(helper, () -> watch("short_interval", source, 1, 19), "interval below minimum");
        expectInvalid(helper, () -> watch("long_interval", source, 1, 12_001), "interval above maximum");
        WatchedMetricDefinition defaultInterval = WatchedMetricDefinition.milestone(
                id("default_interval"), GameplayMetricKey.aggregate(PLAY_TIME_METRIC), source, 1);
        helper.assertValueEqual(defaultInterval.intervalTicks(), 100, "default sampling interval");

        List<WatchedMetricDefinition> maximumWatches = new ArrayList<>();
        for (int index = 0; index < WatchedMetricSnapshot.MAX_WATCHES; index++) {
            maximumWatches.add(watch("watch_" + index, syntheticSource(index % 64), index + 1L, 100));
        }
        WatchedMetricSnapshot maximum = WatchedMetricSnapshot.of(maximumWatches);
        helper.assertValueEqual(maximum.definitions().size(), 256, "maximum watch count");
        helper.assertValueEqual(maximum.sourceGroups().size(), 64, "maximum unique source count");

        List<WatchedMetricDefinition> tooManyWatches = new ArrayList<>(maximumWatches);
        tooManyWatches.add(watch("watch_256", syntheticSource(0), 999, 100));
        expectInvalid(helper, () -> WatchedMetricSnapshot.of(tooManyWatches), "257 watched metrics");

        List<WatchedMetricDefinition> tooManySources = new ArrayList<>();
        for (int index = 0; index <= WatchedMetricSnapshot.MAX_UNIQUE_SOURCES; index++) {
            tooManySources.add(watch("source_watch_" + index, syntheticSource(index), index + 1L, 100));
        }
        expectInvalid(helper, () -> WatchedMetricSnapshot.of(tooManySources), "65 unique sources");
        expectInvalid(helper, () -> WatchedMetricSnapshot.of(List.of(
                watch("duplicate", source, 1, 100), watch("duplicate", source, 2, 100))),
                "duplicate watch ID");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void emptySnapshotIsNoOpWithoutPersistenceOrObservation(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayStatSamplingService.INSTANCE.resetForTesting(server);
        GameplayIngressService.INSTANCE.resetForTesting();
        int profilesBefore = PlayerMythDataService.get(server).activeProfiles().size();

        GameplayStatSamplingService.INSTANCE.onServerTickPost(new ServerTickEvent.Post(() -> true, server));

        helper.assertTrue(!SamplingRuntimeState.hasStateForTesting(server),
                "Empty watch snapshot created runtime state");
        helper.assertValueEqual(GameplayIngressService.INSTANCE.acceptedCountForTesting(), 0L,
                "Sampling emitted a GameplayObservation");
        helper.assertValueEqual(PlayerMythDataService.get(server).activeProfiles().size(), profilesBefore,
                "Sampling changed player profiles");
        helper.assertValueEqual(PlayerMythProfile.CURRENT_DATA_VERSION, 3,
                "Sampling changed PlayerMythProfile dataVersion");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void baselineCrossingAndResetAreRuntimeOnly(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayStatSamplingService.INSTANCE.resetForTesting(server);
        MutableStatisticsView statistics = new MutableStatisticsView();
        statistics.set(Stats.PLAY_TIME, 90);
        UUID playerId = UUID.randomUUID();
        SamplingPlayerView player = new SamplingPlayerView(playerId, statistics);
        WatchedMetricSnapshot snapshot = WatchedMetricSnapshot.of(List.of(
                watch("play_time_100", VanillaStatisticSource.playTime(), 100, 100)));

        SamplingResult baseline = sample(server, snapshot, 0, player);
        helper.assertValueEqual(baseline.baselinesCreated(), 1, "initial baseline count");
        helper.assertTrue(baseline.crossings().isEmpty(), "Initial baseline emitted a crossing");

        statistics.set(Stats.PLAY_TIME, 120);
        SamplingResult notDue = sample(server, snapshot, 50, player);
        helper.assertValueEqual(notDue.sourceReads(), 0, "Source was read before its due tick");
        SamplingResult crossed = sample(server, snapshot, 100, player);
        helper.assertValueEqual(crossed.crossings().size(), 1, "milestone crossing count");
        ThresholdCrossing crossing = crossed.crossings().getFirst();
        helper.assertValueEqual(crossing.previousValue(), 90L, "crossing previous value");
        helper.assertValueEqual(crossing.currentValue(), 120L, "crossing current value");
        helper.assertValueEqual(crossing.threshold(), 100L, "crossed milestone");
        helper.assertValueEqual(crossing.delta(), 30L, "crossing delta");

        statistics.set(Stats.PLAY_TIME, 20);
        SamplingResult reset = sample(server, snapshot, 200, player);
        helper.assertValueEqual(reset.resets(), 1, "decreased stat reset count");
        helper.assertTrue(reset.crossings().isEmpty(), "Stat reset emitted a crossing");
        SamplingRuntimeState.discard(server);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void sharedSourceReadsOnceAndUnavailableDoesNotCreateCursor(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayStatSamplingService.INSTANCE.resetForTesting(server);
        VanillaStatisticSource source = VanillaStatisticSource.playTime();
        WatchedMetricSnapshot snapshot = WatchedMetricSnapshot.of(List.of(
                watch("play_time_100", source, 100, 100),
                watch("play_time_200", source, 200, 200)));
        helper.assertValueEqual(snapshot.sourceGroups().get(source).intervalTicks(), 100,
                "shared source did not use fastest interval");

        MutableStatisticsView available = new MutableStatisticsView();
        available.set(Stats.PLAY_TIME, 50);
        MutableStatisticsView unavailable = new MutableStatisticsView();
        unavailable.setUnavailable(true);
        MutableStatisticsView offline = new MutableStatisticsView();
        offline.set(Stats.PLAY_TIME, 50);
        SamplingPlayerView availablePlayer = new SamplingPlayerView(UUID.randomUUID(), available);
        SamplingPlayerView unavailablePlayer = new SamplingPlayerView(UUID.randomUUID(), unavailable);

        SamplingResult baseline = GameplayStatSamplingService.INSTANCE.sampleForTesting(server, snapshot, 0,
                List.of(availablePlayer, unavailablePlayer));
        helper.assertValueEqual(baseline.sourceReads(), 2, "source reads per online player");
        helper.assertValueEqual(available.readCount(), 1, "shared source was read once for available player");
        helper.assertValueEqual(unavailable.readCount(), 1, "unavailable source read count");
        helper.assertValueEqual(offline.readCount(), 0, "offline player was sampled");
        helper.assertValueEqual(baseline.unavailableReads(), 1, "unavailable read result");
        helper.assertValueEqual(SamplingRuntimeState.get(server).cursorCountForTesting(), 1,
                "Unavailable player created a cursor");

        available.set(Stats.PLAY_TIME, 250);
        SamplingResult crossing = GameplayStatSamplingService.INSTANCE.sampleForTesting(server, snapshot, 100,
                List.of(availablePlayer, unavailablePlayer));
        helper.assertValueEqual(available.readCount(), 2, "shared source was duplicated by two watches");
        helper.assertValueEqual(crossing.crossings().size(), 2, "shared source milestone count");
        SamplingRuntimeState.discard(server);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void logoutAndDiscardRemoveRuntimeState(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayStatSamplingService.INSTANCE.resetForTesting(server);
        GameplayIngressService.INSTANCE.resetForTesting();
        UUID playerId = UUID.randomUUID();
        MutableStatisticsView statistics = new MutableStatisticsView();
        statistics.set(Stats.PLAY_TIME, 10);
        WatchedMetricSnapshot snapshot = WatchedMetricSnapshot.of(List.of(
                watch("lifecycle", VanillaStatisticSource.playTime(), 100, 100)));
        GameplayStatSamplingService.INSTANCE.installManualSnapshot(snapshot);
        helper.assertTrue(GameplayStatSamplingService.INSTANCE.snapshot() == snapshot,
                "Manual snapshot registration did not install the snapshot");
        sample(server, snapshot, 0, new SamplingPlayerView(playerId, statistics));
        SamplingRuntimeState state = SamplingRuntimeState.get(server);
        helper.assertValueEqual(state.cursorCountForTesting(), 1, "lifecycle cursor setup");

        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(playerId, "SamplingLogout"));
        GameplayStatSamplingService.INSTANCE.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
        helper.assertValueEqual(state.cursorCountForTesting(), 0, "logout retained player cursor");

        sample(server, snapshot, 100, new SamplingPlayerView(playerId, statistics));
        helper.assertTrue(SamplingRuntimeState.hasStateForTesting(server), "runtime state was not registered");
        SamplingRuntimeState.discard(server);
        helper.assertTrue(!SamplingRuntimeState.hasStateForTesting(server), "server discard retained runtime state");
        GameplayStatSamplingService.INSTANCE.resetForTesting(server);
        helper.assertValueEqual(GameplayIngressService.INSTANCE.acceptedCountForTesting(), 0L,
                "Lifecycle sampling emitted a GameplayObservation");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void playTimeAndEveryDistanceStatisticHaveDirectSources(GameTestHelper helper) {
        helper.assertValueEqual(VanillaStatisticSource.playTime().key().valueId(), Stats.PLAY_TIME,
                "playtime source ID");
        helper.assertTrue(java.util.Arrays.asList(DistanceStatistic.values())
                        .contains(DistanceStatistic.WALK_ON_WATER),
                "walk-on-water distance source missing");
        helper.assertTrue(java.util.Arrays.asList(DistanceStatistic.values())
                        .contains(DistanceStatistic.WALK_UNDER_WATER),
                "walk-under-water distance source missing");
        for (DistanceStatistic statistic : DistanceStatistic.values()) {
            helper.assertValueEqual(VanillaStatisticSource.distance(statistic).key().valueId(),
                    statistic.statisticId(), "distance source ID: " + statistic);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void thresholdCrossingsBecomeTypedObservations(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayStatSamplingService.INSTANCE.resetForTesting(server);
        GameplayIngressService.INSTANCE.resetForTesting();
        List<GameplayObservation<?>> captured = new ArrayList<>();
        GameplayIngressService.INSTANCE.setSinkForTesting((ignoredServer, observation) -> captured.add(observation));

        MutableStatisticsView statistics = new MutableStatisticsView();
        statistics.set(Stats.PLAY_TIME, 90);
        UUID playerId = UUID.randomUUID();
        SamplingPlayerView player = new SamplingPlayerView(playerId, statistics);
        WatchedMetricSnapshot snapshot = WatchedMetricSnapshot.of(List.of(
                watch("play_time_100", VanillaStatisticSource.playTime(), 100, 100)));
        GameplayStatSamplingService.INSTANCE.installManualSnapshot(snapshot);

        GameplayStatSamplingService.INSTANCE.sampleForTesting(server, snapshot, 0, List.of(player));
        helper.assertValueEqual(GameplayIngressService.INSTANCE.acceptedCountForTesting(), 0L,
                "baseline emitted threshold observation");

        statistics.set(Stats.PLAY_TIME, 120);
        GameplayStatSamplingService.INSTANCE.sampleForTesting(server, snapshot, 100, List.of(player))
                .crossings().forEach(crossing ->
                        com.sande.mythictrpg.gameplay.observation.ThresholdCrossingObservationPublisher.INSTANCE
                                .publish(server, crossing));
        helper.assertValueEqual(GameplayIngressService.INSTANCE.pendingCountForTesting(), 1,
                "threshold observation was not queued");
        GameplayIngressService.INSTANCE.onServerTickPost(new ServerTickEvent.Post(() -> true, server));

        helper.assertValueEqual(captured.size(), 1, "threshold observation drain count");
        GameplayObservation<?> observation = captured.getFirst();
        helper.assertValueEqual(observation.type(), GameplayObservationTypes.VANILLA_STAT_THRESHOLD_CROSSED,
                "threshold observation type");
        helper.assertValueEqual(observation.initiatingPlayerId(), playerId, "threshold observation player");
        VanillaStatThresholdCrossedPayload payload =
                (VanillaStatThresholdCrossedPayload) observation.payload();
        helper.assertValueEqual(payload.watchId(), id("play_time_100", "phase4a2test"),
                "threshold watch ID");
        helper.assertValueEqual(payload.metricKey(), GameplayMetricKey.aggregate(PLAY_TIME_METRIC),
                "threshold metric key");
        helper.assertValueEqual(payload.vanillaStatisticKey(), VanillaStatisticKey.custom(Stats.PLAY_TIME),
                "threshold vanilla stat key");
        helper.assertValueEqual(payload.previousValue(), 90L, "threshold previous");
        helper.assertValueEqual(payload.currentValue(), 120L, "threshold current");
        helper.assertValueEqual(payload.delta(), 30L, "threshold delta");
        helper.assertValueEqual(payload.threshold(), 100L, "threshold value");
        helper.assertTrue(payload.subjectId().isEmpty(), "aggregate metric invented subject");
        helper.assertValueEqual(payload.coalescingDiscriminator().orElseThrow(),
                id("play_time_100", "phase4a2test"), "threshold coalescing discriminator");
        SamplingRuntimeState.discard(server);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void thresholdCoalescingUsesWatchIdWithoutRepeatedIntervals(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayStatSamplingService.INSTANCE.resetForTesting(server);
        GameplayIngressService.INSTANCE.resetForTesting();
        List<GameplayObservation<?>> captured = new ArrayList<>();
        GameplayIngressService.INSTANCE.setSinkForTesting((ignoredServer, observation) -> captured.add(observation));
        UUID playerId = UUID.randomUUID();
        MutableStatisticsView statistics = new MutableStatisticsView();
        statistics.set(Stats.PLAY_TIME, 90);
        SamplingPlayerView player = new SamplingPlayerView(playerId, statistics);
        VanillaStatisticSource source = VanillaStatisticSource.playTime();
        WatchedMetricSnapshot snapshot = WatchedMetricSnapshot.of(List.of(
                watch("play_time_100", source, 100, 100),
                watch("play_time_110", source, 110, 100)));

        GameplayStatSamplingService.INSTANCE.sampleForTesting(server, snapshot, 0, List.of(player));
        statistics.set(Stats.PLAY_TIME, 120);
        GameplayStatSamplingService.INSTANCE.sampleForTesting(server, snapshot, 100, List.of(player))
                .crossings().forEach(crossing ->
                        com.sande.mythictrpg.gameplay.observation.ThresholdCrossingObservationPublisher.INSTANCE
                                .publish(server, crossing));
        helper.assertValueEqual(GameplayIngressService.INSTANCE.pendingCountForTesting(), 2,
                "different watches were coalesced together");
        GameplayIngressService.INSTANCE.drain(server);
        helper.assertValueEqual(captured.size(), 2, "watch-discriminated observation count");
        helper.assertTrue(captured.stream()
                        .map(observation -> (VanillaStatThresholdCrossedPayload) observation.payload())
                        .map(VanillaStatThresholdCrossedPayload::threshold)
                        .collect(java.util.stream.Collectors.toSet())
                        .equals(java.util.Set.of(100L, 110L)),
                "milestone thresholds were not preserved");

        captured.clear();
        statistics.set(Stats.PLAY_TIME, 350);
        GameplayStatSamplingService.INSTANCE.sampleForTesting(server, snapshot, 200, List.of(player))
                .crossings().forEach(crossing ->
                        com.sande.mythictrpg.gameplay.observation.ThresholdCrossingObservationPublisher.INSTANCE
                                .publish(server, crossing));
        GameplayIngressService.INSTANCE.drain(server);
        helper.assertTrue(captured.isEmpty(), "repeated interval crossing was emitted");
        SamplingRuntimeState.discard(server);
        helper.succeed();
    }

    private static SamplingResult sample(MinecraftServer server, WatchedMetricSnapshot snapshot,
            long gameTime, SamplingPlayerView player) {
        return GameplayStatSamplingService.INSTANCE.sampleForTesting(server, snapshot, gameTime, List.of(player));
    }

    private static WatchedMetricDefinition watch(String path, VanillaStatisticSource source,
            long threshold, int interval) {
        ResourceLocation watchId = ResourceLocation.fromNamespaceAndPath("phase4a2test", path);
        GameplayMetricKey metric = GameplayMetricKey.aggregate(PLAY_TIME_METRIC);
        return WatchedMetricDefinition.milestone(watchId, metric, source, threshold, interval);
    }

    private static VanillaStatisticSource syntheticSource(int index) {
        return VanillaStatisticSource.custom(
                ResourceLocation.fromNamespaceAndPath("phase4a2test", "source_" + index));
    }

    private static void expectInvalid(GameTestHelper helper, Runnable operation, String stage) {
        try {
            operation.run();
            helper.fail(stage + " was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected validation rejection.
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }

    private static ResourceLocation id(String path, String namespace) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static final class MutableStatisticsView implements PlayerGameplayStatisticsView {
        private final Map<ResourceLocation, Integer> values = new HashMap<>();
        private boolean unavailable;
        private int readCount;

        void set(ResourceLocation statisticId, int value) {
            values.put(statisticId, value);
        }

        void setUnavailable(boolean unavailable) {
            this.unavailable = unavailable;
        }

        int readCount() {
            return readCount;
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
            readCount++;
            return unavailable ? StatisticValue.unavailable()
                    : StatisticValue.available(values.getOrDefault(statisticId, 0));
        }
    }
}
