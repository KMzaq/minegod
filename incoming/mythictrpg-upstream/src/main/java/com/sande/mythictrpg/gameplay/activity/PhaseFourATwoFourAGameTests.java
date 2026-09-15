package com.sande.mythictrpg.gameplay.activity;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import com.sande.mythictrpg.gameplay.observation.GameplayIngressService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourATwoFourAGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation NETHER = ResourceLocation.withDefaultNamespace("the_nether");
    private static final PlayerActivityPolicy FAST_POLICY =
            new PlayerActivityPolicy(PlayerActivityPolicy.MIN_IDLE_THRESHOLD_TICKS, 0.01D, 0.5D);

    private PhaseFourATwoFourAGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void policyValidationAndProductionDefaults(GameTestHelper helper) {
        PlayerActivityPolicy defaults = PlayerActivityPolicy.PRODUCTION_DEFAULT;
        helper.assertValueEqual(defaults.idleThresholdTicks(), 6_000L, "default idle threshold");
        helper.assertValueEqual(defaults.positionEpsilon(), 0.01D, "default position epsilon");
        helper.assertValueEqual(defaults.positionEpsilonSquared(), 0.0001D,
                "default squared position epsilon");
        helper.assertValueEqual(defaults.rotationEpsilonDegrees(), 0.5D, "default rotation epsilon");
        new PlayerActivityPolicy(PlayerActivityPolicy.MIN_IDLE_THRESHOLD_TICKS, 0.01D, 0.5D);
        new PlayerActivityPolicy(PlayerActivityPolicy.MAX_IDLE_THRESHOLD_TICKS, 0.01D, 180.0D);

        expectRejected(helper, () -> new PlayerActivityPolicy(1_199L, 0.01D, 0.5D), "short threshold");
        expectRejected(helper, () -> new PlayerActivityPolicy(72_001L, 0.01D, 0.5D), "long threshold");
        expectRejected(helper, () -> new PlayerActivityPolicy(6_000L, 0.0D, 0.5D), "zero position epsilon");
        expectRejected(helper, () -> new PlayerActivityPolicy(6_000L, Double.NaN, 0.5D), "NaN position epsilon");
        expectRejected(helper, () -> new PlayerActivityPolicy(6_000L, Double.MAX_VALUE, 0.5D),
                "unsafe position epsilon");
        expectRejected(helper, () -> new PlayerActivityPolicy(6_000L, 0.01D, 0.0D), "zero rotation epsilon");
        expectRejected(helper, () -> new PlayerActivityPolicy(6_000L, 0.01D, 180.1D),
                "oversized rotation epsilon");
        expectRejected(helper, () -> new PlayerActivityPolicy(6_000L, 0.01D, Double.NaN),
                "NaN rotation epsilon");
        expectRejected(helper, () -> new PlayerActivitySnapshot(PlayerActivityState.ACTIVE, 0L, -1L),
                "negative snapshot inactive ticks");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void baselineBecomesIdleExactlyOnceAtThreshold(GameTestHelper helper) {
        AtomicLong gameTime = new AtomicLong();
        PlayerActivityRuntimeState state = detached(helper, gameTime);
        UUID playerId = UUID.randomUUID();
        var baseline = sample(playerId, OVERWORLD, 0.0D, 0.0D, 10L);

        helper.assertValueEqual(state.observe(baseline, 0L),
                PlayerActivityRuntimeState.UpdateResult.BASELINED, "initial baseline result");
        helper.assertValueEqual(state.find(playerId).orElseThrow().state(), PlayerActivityState.ACTIVE,
                "initial activity state");
        gameTime.set(1_199L);
        helper.assertValueEqual(state.observe(baseline, 1_199L),
                PlayerActivityRuntimeState.UpdateResult.UNCHANGED, "pre-threshold result");
        helper.assertValueEqual(state.find(playerId).orElseThrow().inactiveTicks(), 1_199L,
                "pre-threshold inactive ticks");

        gameTime.set(1_200L);
        helper.assertValueEqual(state.observe(baseline, 1_200L),
                PlayerActivityRuntimeState.UpdateResult.BECAME_IDLE, "threshold transition");
        helper.assertValueEqual(state.find(playerId).orElseThrow().state(), PlayerActivityState.IDLE,
                "threshold state");
        gameTime.set(1_201L);
        helper.assertValueEqual(state.observe(baseline, 1_201L),
                PlayerActivityRuntimeState.UpdateResult.UNCHANGED, "repeated idle transition");
        helper.assertTrue(PlayerActivitySnapshot.class.isRecord(), "activity snapshot is not immutable record data");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void positionAndRotationEvidenceReactivateImmediately(GameTestHelper helper) {
        AtomicLong gameTime = new AtomicLong();
        PlayerActivityRuntimeState state = detached(helper, gameTime);
        UUID movingPlayer = UUID.randomUUID();
        var baseline = sample(movingPlayer, OVERWORLD, 0.0D, 0.0D, 10L);
        state.observe(baseline, 0L);
        state.observe(baseline, 1_200L);

        gameTime.set(1_201L);
        helper.assertValueEqual(state.observe(sample(movingPlayer, OVERWORLD, 0.009D, 0.0D, 10L), 1_201L),
                PlayerActivityRuntimeState.UpdateResult.UNCHANGED, "sub-epsilon position movement");
        gameTime.set(1_202L);
        helper.assertValueEqual(state.observe(sample(movingPlayer, OVERWORLD, 0.011D, 0.0D, 10L), 1_202L),
                PlayerActivityRuntimeState.UpdateResult.BECAME_ACTIVE, "position reactivation");
        helper.assertValueEqual(state.find(movingPlayer).orElseThrow().lastActivityGameTick(), 1_202L,
                "position activity tick");

        UUID rotatingPlayer = UUID.randomUUID();
        var rotationBaseline = sample(rotatingPlayer, OVERWORLD, 0.0D, 0.0D, 10.0D, 20L);
        state.observe(rotationBaseline, 0L);
        state.observe(rotationBaseline, 1_200L);
        helper.assertValueEqual(state.observe(
                        sample(rotatingPlayer, OVERWORLD, 0.0D, 0.0D, 10.49D, 20L), 1_201L),
                PlayerActivityRuntimeState.UpdateResult.UNCHANGED, "sub-epsilon pitch movement");
        helper.assertValueEqual(state.observe(
                        sample(rotatingPlayer, OVERWORLD, 0.0D, 0.0D, 10.51D, 20L), 1_202L),
                PlayerActivityRuntimeState.UpdateResult.BECAME_ACTIVE, "pitch reactivation");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void yawWrapUsesMinimumAngularDifference(GameTestHelper helper) {
        AtomicLong gameTime = new AtomicLong();
        PlayerActivityRuntimeState state = detached(helper, gameTime);
        UUID playerId = UUID.randomUUID();
        var baseline = sample(playerId, OVERWORLD, 0.0D, 179.8D, 10L);
        state.observe(baseline, 0L);
        state.observe(baseline, 1_200L);

        helper.assertValueEqual(state.observe(sample(playerId, OVERWORLD, 0.0D, -179.9D, 10L), 1_201L),
                PlayerActivityRuntimeState.UpdateResult.UNCHANGED, "wrapped sub-epsilon yaw");
        helper.assertValueEqual(state.observe(sample(playerId, OVERWORLD, 0.0D, -179.6D, 10L), 1_202L),
                PlayerActivityRuntimeState.UpdateResult.BECAME_ACTIVE, "wrapped yaw reactivation");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void actionTokenAndDimensionResetAreActivity(GameTestHelper helper) {
        AtomicLong gameTime = new AtomicLong();
        PlayerActivityRuntimeState state = detached(helper, gameTime);
        UUID playerId = UUID.randomUUID();
        var baseline = sample(playerId, OVERWORLD, 0.0D, 0.0D, 10L);
        state.observe(baseline, 0L);
        state.observe(baseline, 1_200L);
        helper.assertValueEqual(state.observe(baseline, 1_201L),
                PlayerActivityRuntimeState.UpdateResult.UNCHANGED, "unchanged action token");
        helper.assertValueEqual(state.observe(sample(playerId, OVERWORLD, 0.0D, 0.0D, 11L), 1_202L),
                PlayerActivityRuntimeState.UpdateResult.BECAME_ACTIVE, "action-token reactivation");

        state.observe(sample(playerId, OVERWORLD, 0.0D, 0.0D, 11L), 2_402L);
        helper.assertValueEqual(state.find(playerId).orElseThrow().state(), PlayerActivityState.IDLE,
                "second idle setup");
        helper.assertValueEqual(state.observe(sample(playerId, NETHER, 50.0D, 90.0D, 11L), 2_403L),
                PlayerActivityRuntimeState.UpdateResult.RESET_ACTIVE, "dimension reset result");
        helper.assertValueEqual(state.find(playerId).orElseThrow().lastActivityGameTick(), 2_403L,
                "dimension reset tick");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void multiplePlayersRemainIndependentAndUnknownIsEmpty(GameTestHelper helper) {
        AtomicLong gameTime = new AtomicLong(1_200L);
        PlayerActivityRuntimeState state = detached(helper, gameTime);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var firstBaseline = sample(first, OVERWORLD, 0.0D, 0.0D, 1L);
        var secondBaseline = sample(second, OVERWORLD, 0.0D, 0.0D, 1L);
        state.observe(firstBaseline, 0L);
        state.observe(secondBaseline, 0L);
        state.observe(firstBaseline, 1_200L);
        state.observe(secondBaseline, 1_200L);

        gameTime.set(1_201L);
        state.observe(sample(first, OVERWORLD, 0.02D, 0.0D, 1L), 1_201L);
        helper.assertValueEqual(state.find(first).orElseThrow().state(), PlayerActivityState.ACTIVE,
                "first player activity");
        helper.assertValueEqual(state.find(second).orElseThrow().state(), PlayerActivityState.IDLE,
                "second player isolation");
        helper.assertTrue(state.find(UUID.randomUUID()).isEmpty(), "unknown player returned activity");
        state.removePlayer(first);
        helper.assertTrue(state.find(first).isEmpty(), "removed player returned activity");
        helper.assertValueEqual(state.entryCountForTesting(), 1, "removed player entry retained");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void lifecycleCleanupIsRuntimeOnlyWithoutObservation(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PlayerActivityRuntimeState.discard(server);
        GameplayIngressService.INSTANCE.resetForTesting();
        int profilesBefore = PlayerMythDataService.get(server).activeProfiles().size();

        PlayerActivityService.INSTANCE.onServerTickPost(new ServerTickEvent.Post(() -> true, server));
        helper.assertTrue(!PlayerActivityRuntimeState.hasStateForTesting(server),
                "zero-player tick created activity state");

        UUID playerId = UUID.randomUUID();
        FakePlayer original = new FakePlayer(helper.getLevel(), new GameProfile(playerId, "ActivityOriginal"));
        PlayerActivityService.INSTANCE.onPlayerLoggedIn(new PlayerEvent.PlayerLoggedInEvent(original));
        assertActive(helper, server, playerId, "login");

        FakePlayer replacement = new FakePlayer(helper.getLevel(), new GameProfile(playerId, "ActivityClone"));
        PlayerActivityService.INSTANCE.onPlayerClone(new PlayerEvent.Clone(replacement, original, true));
        assertActive(helper, server, playerId, "clone");
        PlayerActivityService.INSTANCE.onPlayerRespawn(new PlayerEvent.PlayerRespawnEvent(replacement, false));
        assertActive(helper, server, playerId, "respawn");
        PlayerActivityService.INSTANCE.onPlayerChangedDimension(new PlayerEvent.PlayerChangedDimensionEvent(
                replacement, Level.OVERWORLD, Level.NETHER));
        assertActive(helper, server, playerId, "dimension change");

        PlayerActivityService.INSTANCE.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(replacement));
        helper.assertTrue(PlayerActivityService.INSTANCE.find(server, playerId).isEmpty(),
                "logout retained player entry");
        helper.assertValueEqual(GameplayIngressService.INSTANCE.acceptedCountForTesting(), 0L,
                "activity runtime emitted an observation");
        helper.assertValueEqual(PlayerMythDataService.get(server).activeProfiles().size(), profilesBefore,
                "activity runtime changed player profiles");
        helper.assertValueEqual(PlayerMythProfile.CURRENT_DATA_VERSION, 3,
                "activity runtime changed profile dataVersion");

        PlayerActivityRuntimeState.discard(server);
        helper.assertTrue(!PlayerActivityRuntimeState.hasStateForTesting(server),
                "server discard retained activity runtime state");
        GameplayIngressService.INSTANCE.resetForTesting();
        helper.succeed();
    }

    private static PlayerActivityRuntimeState detached(GameTestHelper helper, AtomicLong gameTime) {
        return PlayerActivityRuntimeState.forTesting(helper.getLevel().getServer(), FAST_POLICY, gameTime::get);
    }

    private static PlayerActivityRuntimeState.PlayerSample sample(UUID playerId, ResourceLocation dimension,
            double x, double yaw, long actionToken) {
        return sample(playerId, dimension, x, yaw, 0.0D, actionToken);
    }

    private static PlayerActivityRuntimeState.PlayerSample sample(UUID playerId, ResourceLocation dimension,
            double x, double yaw, double pitch, long actionToken) {
        return new PlayerActivityRuntimeState.PlayerSample(playerId, dimension,
                x, 64.0D, 0.0D, yaw, pitch, actionToken);
    }

    private static void assertActive(GameTestHelper helper, MinecraftServer server, UUID playerId, String stage) {
        helper.assertValueEqual(PlayerActivityService.INSTANCE.find(server, playerId).orElseThrow().state(),
                PlayerActivityState.ACTIVE, stage + " activity state");
    }

    private static void expectRejected(GameTestHelper helper, Runnable operation, String stage) {
        try {
            operation.run();
            helper.fail(stage + " was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected validation rejection.
        }
    }
}
