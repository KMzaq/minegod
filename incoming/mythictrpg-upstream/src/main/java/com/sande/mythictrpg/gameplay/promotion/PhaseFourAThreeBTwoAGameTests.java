package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.activity.PlayerActivitySnapshot;
import com.sande.mythictrpg.gameplay.activity.PlayerActivityState;
import com.sande.mythictrpg.gameplay.observation.BlockBrokenPayload;
import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.runtime.InteractionRuntimeState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourAThreeBTwoAGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation STONE = BuiltInRegistries.BLOCK.getKey(Blocks.STONE);
    private static final ResourceLocation DIRT = BuiltInRegistries.BLOCK.getKey(Blocks.DIRT);
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    private PhaseFourAThreeBTwoAGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void activeMatchSelectsHighestPriorityAndCreatesTypedSignal(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        AtomicLong tick = new AtomicLong();
        GameplayPromotionRuntimeState runtime = GameplayPromotionRuntimeState.forTesting(server, tick::get);
        GameplayPromotionDefinition low = blockDefinition("low", 10, 20);
        GameplayPromotionDefinition high = blockDefinition("high", 20, 20);
        GameplayPromotionSnapshot snapshot = snapshot(List.of(low, high), 3);
        List<InteractionSignal<GameplayActionPayload>> captured = new ArrayList<>();
        GameplayPromotionService service = service(snapshot, true, PlayerActivityState.ACTIVE,
                runtime, GameplayPromotionSignalFactory::create, (ignored, signal) -> {
                    captured.add(signal);
                    return GameplaySignalResult.ACCEPTED;
                });

        GameplayPromotionResult result = service.promote(server, observation(UUID.randomUUID(), STONE));
        helper.assertValueEqual(result.status(), GameplayPromotionResult.Status.SINK_ACCEPTED,
                "accepted result");
        helper.assertValueEqual(result.promotionRuleId(), Optional.of(high.id()), "highest rule");
        helper.assertValueEqual(captured.size(), 1, "captured signal count");
        GameplayActionPayload payload = captured.getFirst().payload();
        helper.assertValueEqual(payload.promotionRuleId(), high.id(), "signal rule ID");
        helper.assertTrue(payload.evidence() instanceof BlockBrokenEvidence,
                "typed block evidence");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void offlineActivityAndNoMatchFailBeforeRuntimeCreation(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayPromotionSnapshot snapshot = snapshot(List.of(blockDefinition("gate", 0, 20)), 1);
        AtomicInteger runtimeRequests = new AtomicInteger();
        GameplayPromotionService.RuntimeStateProvider forbiddenRuntime = ignored -> {
            runtimeRequests.incrementAndGet();
            throw new AssertionError("runtime must not be requested");
        };
        UUID playerId = UUID.randomUUID();

        helper.assertValueEqual(service(snapshot, false, PlayerActivityState.ACTIVE,
                forbiddenRuntime).promote(server, observation(playerId, STONE)).status(),
                GameplayPromotionResult.Status.PLAYER_OFFLINE, "offline gate");
        helper.assertValueEqual(service(snapshot, true, null, forbiddenRuntime)
                        .promote(server, observation(playerId, STONE)).status(),
                GameplayPromotionResult.Status.ACTIVITY_UNAVAILABLE, "missing activity gate");
        helper.assertValueEqual(service(snapshot, true, PlayerActivityState.IDLE, forbiddenRuntime)
                        .promote(server, observation(playerId, STONE)).status(),
                GameplayPromotionResult.Status.PLAYER_IDLE, "idle gate");
        helper.assertValueEqual(service(snapshot, true, PlayerActivityState.ACTIVE, forbiddenRuntime)
                        .promote(server, observation(playerId, DIRT)).status(),
                GameplayPromotionResult.Status.NO_MATCH, "no-match result");
        helper.assertValueEqual(runtimeRequests.get(), 0, "runtime request count");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void cooldownSkipsHigherRuleAndExpiresAtExactTick(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        AtomicLong tick = new AtomicLong();
        GameplayPromotionRuntimeState runtime = GameplayPromotionRuntimeState.forTesting(server, tick::get);
        GameplayPromotionDefinition high = blockDefinition("cooldown_high", 20, 20);
        GameplayPromotionDefinition low = blockDefinition("cooldown_low", 10, 20);
        GameplayPromotionSnapshot snapshot = snapshot(List.of(low, high), 4);
        GameplayPromotionService service = service(snapshot, true, PlayerActivityState.ACTIVE,
                runtime, GameplayPromotionSignalFactory::create,
                (ignored, signal) -> GameplaySignalResult.ACCEPTED);
        UUID playerId = UUID.randomUUID();

        helper.assertValueEqual(service.promote(server, observation(playerId, STONE)).promotionRuleId(),
                Optional.of(high.id()), "first selected rule");
        helper.assertValueEqual(service.promote(server, observation(playerId, STONE)).promotionRuleId(),
                Optional.of(low.id()), "cooldown fallback rule");
        helper.assertValueEqual(service.promote(server, observation(playerId, STONE)).status(),
                GameplayPromotionResult.Status.ALL_CANDIDATES_COOLDOWN, "all cooldown result");
        tick.set(19);
        helper.assertValueEqual(service.promote(server, observation(playerId, STONE)).status(),
                GameplayPromotionResult.Status.ALL_CANDIDATES_COOLDOWN, "pre-expiry result");
        tick.set(20);
        helper.assertValueEqual(service.promote(server, observation(playerId, STONE)).promotionRuleId(),
                Optional.of(high.id()), "exact expiry rule");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void everySinkOutcomeAndExceptionRetainsCooldown(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayPromotionDefinition definition = blockDefinition("sink_outcomes", 0, 20);
        GameplayPromotionSnapshot snapshot = snapshot(List.of(definition), 5);
        UUID playerId = UUID.randomUUID();
        for (GameplaySignalResult sinkResult : GameplaySignalResult.values()) {
            GameplayPromotionRuntimeState runtime = GameplayPromotionRuntimeState.forTesting(server, () -> 0);
            GameplayPromotionService service = service(snapshot, true, PlayerActivityState.ACTIVE,
                    runtime, GameplayPromotionSignalFactory::create,
                    (ignored, signal) -> sinkResult);
            GameplayPromotionResult first = service.promote(server, observation(playerId, STONE));
            helper.assertValueEqual(first.signalResult(), Optional.of(sinkResult),
                    "sink result " + sinkResult);
            helper.assertValueEqual(service.promote(server, observation(playerId, STONE)).status(),
                    GameplayPromotionResult.Status.ALL_CANDIDATES_COOLDOWN,
                    "cooldown after " + sinkResult);
        }

        GameplayPromotionRuntimeState runtime = GameplayPromotionRuntimeState.forTesting(server, () -> 0);
        GameplayPromotionDefinition lower = blockDefinition("sink_exception_lower", -1, 20);
        GameplayPromotionSnapshot fallbackSnapshot = snapshot(List.of(lower, definition), 5);
        AtomicInteger exceptionSinkCalls = new AtomicInteger();
        GameplayPromotionService throwing = service(fallbackSnapshot, true, PlayerActivityState.ACTIVE,
                runtime, GameplayPromotionSignalFactory::create,
                (ignored, signal) -> {
                    exceptionSinkCalls.incrementAndGet();
                    throw new IllegalStateException("test sink failure");
                });
        GameplayPromotionResult exceptionResult = throwing.promote(
                server, observation(playerId, STONE));
        helper.assertValueEqual(exceptionResult.status(),
                GameplayPromotionResult.Status.SINK_FAILED, "sink exception result");
        helper.assertValueEqual(exceptionResult.promotionRuleId(), Optional.of(definition.id()),
                "sink exception selected rule");
        helper.assertValueEqual(exceptionSinkCalls.get(), 1, "sink exception call count");
        helper.assertTrue(!runtime.isEligible(playerId, definition.id()),
                "cooldown after sink exception");
        helper.assertTrue(runtime.isEligible(playerId, lower.id()),
                "lower rule was not attempted after sink exception");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void signalCreationFailureDoesNotReserveOrFallback(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayPromotionRuntimeState runtime = GameplayPromotionRuntimeState.forTesting(server, () -> 0);
        GameplayPromotionDefinition high = blockDefinition("factory_high", 20, 20);
        GameplayPromotionDefinition low = blockDefinition("factory_low", 10, 20);
        GameplayPromotionSnapshot snapshot = snapshot(List.of(low, high), 6);
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicInteger sinkCalls = new AtomicInteger();
        GameplayPromotionService service = service(snapshot, true, PlayerActivityState.ACTIVE,
                runtime, (ignoredSnapshot, ignoredDefinition, ignoredObservation) -> {
                    factoryCalls.incrementAndGet();
                    return Optional.empty();
                }, (ignored, signal) -> {
                    sinkCalls.incrementAndGet();
                    return GameplaySignalResult.ACCEPTED;
                });

        GameplayPromotionResult result = service.promote(server, observation(UUID.randomUUID(), STONE));
        helper.assertValueEqual(result.status(), GameplayPromotionResult.Status.SIGNAL_CREATION_FAILED,
                "factory failure result");
        helper.assertValueEqual(result.promotionRuleId(), Optional.of(high.id()),
                "failed selected rule");
        helper.assertValueEqual(factoryCalls.get(), 1, "factory call count");
        helper.assertValueEqual(sinkCalls.get(), 0, "sink call count");
        helper.assertValueEqual(runtime.entryCountForTesting(), 0, "factory failure cooldown count");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void generationAndLifecycleCleanupAreIsolatedFromInteractionRuntime(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        AtomicLong tick = new AtomicLong();
        GameplayPromotionRuntimeState runtime = GameplayPromotionRuntimeState.forTesting(server, tick::get);
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        ResourceLocation firstRule = id("first_rule");
        ResourceLocation secondRule = id("second_rule");
        runtime.alignGeneration(1);
        runtime.reserve(firstPlayer, firstRule, 20);
        runtime.reserve(secondPlayer, firstRule, 20);
        runtime.reserve(firstPlayer, secondRule, 20);
        helper.assertValueEqual(runtime.entryCountForTesting(), 3, "independent cooldown entries");
        runtime.removePlayer(firstPlayer);
        helper.assertValueEqual(runtime.entryCountForTesting(), 1, "player cleanup count");
        runtime.alignGeneration(2);
        helper.assertValueEqual(runtime.entryCountForTesting(), 0, "generation cleanup count");

        InteractionRuntimeState interaction = InteractionRuntimeState.forTesting(server, tick::get);
        helper.assertTrue(interaction.blockReason(secondPlayer, id("god"),
                InteractionMode.SPONTANEOUS).isEmpty(), "interaction cooldown before promotion reserve");
        runtime.reserve(secondPlayer, firstRule, 20);
        helper.assertTrue(interaction.blockReason(secondPlayer, id("god"),
                InteractionMode.SPONTANEOUS).isEmpty(), "interaction cooldown after promotion reserve");

        GameplayPromotionRuntimeState.discard(server);
        helper.assertTrue(!GameplayPromotionRuntimeState.hasStateForTesting(server),
                "discarded static runtime");
        GameplayPromotionRuntimeState staticState = GameplayPromotionRuntimeState.get(server);
        staticState.alignGeneration(1);
        staticState.reserve(secondPlayer, firstRule, 20);
        GameplayPromotionRuntimeState.removePlayerIfPresent(server, secondPlayer);
        helper.assertValueEqual(staticState.entryCountForTesting(), 0, "static player cleanup");
        GameplayPromotionRuntimeState.discard(server);
        helper.assertTrue(!GameplayPromotionRuntimeState.hasStateForTesting(server),
                "static server cleanup");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE, timeoutTicks = 200)
    public static void runtimeCapacityLimitsAndExpiredSpaceReuse(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        AtomicLong tick = new AtomicLong();
        GameplayPromotionRuntimeState playerRuntime = GameplayPromotionRuntimeState.forTesting(server, tick::get);
        playerRuntime.alignGeneration(0);
        UUID playerId = UUID.randomUUID();
        for (int index = 0; index < GameplayPromotionRuntimeState.MAX_ENTRIES_PER_PLAYER; index++) {
            helper.assertValueEqual(playerRuntime.reserve(playerId, id("player_" + index), 20),
                    AttemptReservationResult.RECORDED, "player entry " + index);
        }
        helper.assertValueEqual(playerRuntime.reserve(playerId, id("player_overflow"), 20),
                AttemptReservationResult.CAPACITY_REJECTED, "player capacity");

        GameplayPromotionDefinition capacityDefinition = blockDefinition("capacity_service", 0, 20);
        AtomicInteger sinkCalls = new AtomicInteger();
        GameplayPromotionService capacityService = service(snapshot(List.of(capacityDefinition), 0),
                true, PlayerActivityState.ACTIVE, playerRuntime,
                GameplayPromotionSignalFactory::create, (ignored, signal) -> {
                    sinkCalls.incrementAndGet();
                    return GameplaySignalResult.ACCEPTED;
                });
        helper.assertValueEqual(capacityService.promote(server, observation(playerId, STONE)).status(),
                GameplayPromotionResult.Status.CAPACITY_REJECTED, "service capacity result");
        helper.assertValueEqual(sinkCalls.get(), 0, "capacity sink calls");
        tick.set(20);
        helper.assertValueEqual(playerRuntime.reserve(playerId, id("player_reused"), 20),
                AttemptReservationResult.RECORDED, "expired player space reuse");

        GameplayPromotionRuntimeState serverRuntime = GameplayPromotionRuntimeState.forTesting(server, () -> 0);
        for (int playerIndex = 0; playerIndex < 32; playerIndex++) {
            UUID capacityPlayer = new UUID(0, playerIndex + 1L);
            for (int ruleIndex = 0; ruleIndex < 256; ruleIndex++) {
                AttemptReservationResult result = serverRuntime.reserve(capacityPlayer,
                        id("server_" + playerIndex + "_" + ruleIndex), 20);
                if (result != AttemptReservationResult.RECORDED) {
                    helper.fail("server capacity rejected entry " + playerIndex + ":" + ruleIndex);
                }
            }
        }
        helper.assertValueEqual(serverRuntime.entryCountForTesting(), 8_192,
                "server entry count");
        helper.assertValueEqual(serverRuntime.reserve(UUID.randomUUID(), id("server_overflow"), 20),
                AttemptReservationResult.CAPACITY_REJECTED, "server capacity");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void overflowSaturatesAndResultContractsRejectInvalidCombinations(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayPromotionRuntimeState runtime = GameplayPromotionRuntimeState.forTesting(
                server, () -> Long.MAX_VALUE - 5);
        UUID playerId = UUID.randomUUID();
        ResourceLocation ruleId = id("overflow_rule");
        helper.assertValueEqual(runtime.reserve(playerId, ruleId, 20),
                AttemptReservationResult.RECORDED, "overflow reserve");
        helper.assertValueEqual(runtime.nextEligibleTickForTesting(playerId, ruleId).orElseThrow(),
                Long.MAX_VALUE, "saturated next eligible tick");

        expectRejected(helper, () -> new GameplayPromotionResult(
                GameplayPromotionResult.Status.SINK_ACCEPTED, Optional.of(ruleId), Optional.empty()),
                "missing sink result");
        expectRejected(helper, () -> new GameplayPromotionResult(
                GameplayPromotionResult.Status.NO_MATCH, Optional.of(ruleId), Optional.empty()),
                "unexpected rule ID");
        expectRejected(helper, () -> new GameplayPromotionResult(
                GameplayPromotionResult.Status.SINK_ACCEPTED, Optional.of(ruleId),
                Optional.of(GameplaySignalResult.REJECTED)), "mismatched sink result");
        helper.succeed();
    }

    private static GameplayPromotionService service(GameplayPromotionSnapshot snapshot,
            boolean online, PlayerActivityState activityState,
            GameplayPromotionRuntimeState runtime,
            GameplayPromotionService.SignalFactory signalFactory,
            GameplaySignalSink sink) {
        return service(snapshot, online, activityState, ignored -> runtime, signalFactory, sink);
    }

    private static GameplayPromotionService service(GameplayPromotionSnapshot snapshot,
            boolean online, PlayerActivityState activityState,
            GameplayPromotionService.RuntimeStateProvider runtimeProvider) {
        return service(snapshot, online, activityState, runtimeProvider,
                GameplayPromotionSignalFactory::create, GameplaySignalSink.UNAVAILABLE);
    }

    private static GameplayPromotionService service(GameplayPromotionSnapshot snapshot,
            boolean online, PlayerActivityState activityState,
            GameplayPromotionService.RuntimeStateProvider runtimeProvider,
            GameplayPromotionService.SignalFactory signalFactory,
            GameplaySignalSink sink) {
        return new GameplayPromotionService(() -> snapshot,
                (server, playerId) -> online,
                server -> playerId -> activityState == null ? Optional.empty()
                        : Optional.of(new PlayerActivitySnapshot(activityState, 0, 0)),
                runtimeProvider, signalFactory, sink);
    }

    private static GameplayPromotionDefinition blockDefinition(String path, int priority,
            long cooldownTicks) {
        return new GameplayPromotionDefinition(id(path), GameplayObservationTypes.BLOCK_BROKEN.id(),
                id(path + "_signal"), priority, cooldownTicks,
                new BlockBrokenPromotionMatcher(Optional.of(STONE), Optional.empty()));
    }

    private static GameplayPromotionSnapshot snapshot(List<GameplayPromotionDefinition> definitions,
            long generation) {
        GameplayPromotionManager.Prepared prepared = GameplayPromotionManager.compile(definitions);
        return new GameplayPromotionSnapshot(prepared.definitions(), prepared.orderedDefinitions(),
                prepared.index(), prepared.watchedMetrics(), prepared.signalTypes(), generation);
    }

    private static GameplayObservation<BlockBrokenPayload> observation(UUID playerId,
            ResourceLocation blockId) {
        return new GameplayObservation<>(GameplayObservationTypes.BLOCK_BROKEN, playerId, 0,
                new BlockBrokenPayload(blockId, OVERWORLD, BlockPos.ZERO));
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
}
