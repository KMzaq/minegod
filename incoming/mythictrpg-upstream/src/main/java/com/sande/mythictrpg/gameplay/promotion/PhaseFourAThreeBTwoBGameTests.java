package com.sande.mythictrpg.gameplay.promotion;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.activity.PlayerActivitySnapshot;
import com.sande.mythictrpg.gameplay.activity.PlayerActivityState;
import com.sande.mythictrpg.gameplay.observation.BlockBrokenPayload;
import com.sande.mythictrpg.gameplay.observation.GameplayIngressService;
import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
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
public final class PhaseFourAThreeBTwoBGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation STONE = BuiltInRegistries.BLOCK.getKey(Blocks.STONE);

    private PhaseFourAThreeBTwoBGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void signalSinkRouterSeparatesProductionAndTestOverride(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayPromotionSnapshot snapshot = snapshot(List.of(blockDefinition("router")), 1);
        GameplayPromotionDefinition definition = snapshot.orderedDefinitions().getFirst();
        InteractionSignal<GameplayActionPayload> signal = GameplayPromotionSignalFactory.create(
                snapshot, definition, observation(UUID.randomUUID())).orElseThrow();
        GameplaySignalSinkRouter router = GameplaySignalSinkRouter.forTesting();
        helper.assertValueEqual(router.accept(server, signal), GameplaySignalResult.UNAVAILABLE,
                "default production sink result");

        AtomicInteger productionCalls = new AtomicInteger();
        AtomicInteger testCalls = new AtomicInteger();
        GameplaySignalSink production = (ignored, ignoredSignal) -> {
            productionCalls.incrementAndGet();
            return GameplaySignalResult.ACCEPTED;
        };
        router.configureProductionSink(production);
        router.configureProductionSink(production);
        expectRejected(helper, () -> router.configureProductionSink(
                (ignored, ignoredSignal) -> GameplaySignalResult.FAILED),
                "conflicting production signal sink");
        expectNullRejected(helper, () -> router.configureProductionSink(null),
                "null production signal sink");
        expectNullRejected(helper, () -> router.setSinkForTesting(null),
                "null test signal sink");

        router.setSinkForTesting((ignored, ignoredSignal) -> {
            testCalls.incrementAndGet();
            return GameplaySignalResult.REJECTED;
        });
        helper.assertValueEqual(router.accept(server, signal), GameplaySignalResult.REJECTED,
                "test override result");
        helper.assertValueEqual(productionCalls.get(), 0, "production fan-out count");
        helper.assertValueEqual(testCalls.get(), 1, "test override call count");
        router.resetForTesting();
        helper.assertValueEqual(router.accept(server, signal), GameplaySignalResult.ACCEPTED,
                "production result after reset");
        helper.assertValueEqual(productionCalls.get(), 1, "production call after reset");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void ingressPromotesActiveObservationToTypedSignalAndAppliesCooldown(
            GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayIngressService ingress = GameplayIngressService.INSTANCE;
        GameplaySignalSinkRouter router = GameplaySignalSinkRouter.forTesting();
        AtomicLong tick = new AtomicLong();
        GameplayPromotionRuntimeState runtime = GameplayPromotionRuntimeState.forTesting(server, tick::get);
        GameplayPromotionSnapshot snapshot = snapshot(List.of(blockDefinition("end_to_end")), 7);
        List<InteractionSignal<GameplayActionPayload>> signals = new ArrayList<>();
        router.setSinkForTesting((ignored, signal) -> {
            signals.add(signal);
            return GameplaySignalResult.ACCEPTED;
        });
        GameplayPromotionService service = service(snapshot, true, PlayerActivityState.ACTIVE,
                runtime, router);
        UUID playerId = UUID.randomUUID();

        ingress.resetForTesting();
        ingress.setSinkForTesting(service);
        try {
            ingress.accept(server, observation(playerId));
            helper.assertValueEqual(ingress.drain(server), 1, "first ingress drain");
            helper.assertValueEqual(signals.size(), 1, "first promoted signal count");
            InteractionSignal<GameplayActionPayload> signal = signals.getFirst();
            helper.assertValueEqual(signal.type().id(), id("end_to_end_signal"), "signal ID");
            helper.assertValueEqual(signal.initiatingPlayerId(), playerId, "signal player ID");
            helper.assertValueEqual(signal.payload().promotionRuleId(), id("end_to_end"),
                    "payload promotion ID");
            helper.assertTrue(signal.payload().evidence() instanceof BlockBrokenEvidence,
                    "typed block evidence");

            ingress.accept(server, observation(playerId));
            ingress.drain(server);
            helper.assertValueEqual(signals.size(), 1, "cooldown allowed duplicate signal");
        } finally {
            ingress.resetForTesting();
            router.resetForTesting();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void ingressActivityGatesAndUnavailableSinkFailClosed(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayIngressService ingress = GameplayIngressService.INSTANCE;
        GameplayPromotionSnapshot snapshot = snapshot(List.of(blockDefinition("gates")), 2);
        AtomicInteger signals = new AtomicInteger();
        GameplaySignalSink capturing = (ignored, signal) -> {
            signals.incrementAndGet();
            return GameplaySignalResult.ACCEPTED;
        };
        UUID playerId = UUID.randomUUID();

        GameplayPromotionRuntimeState idleRuntime = GameplayPromotionRuntimeState.forTesting(server, () -> 0);
        ingress.resetForTesting();
        try {
            ingress.setSinkForTesting(service(snapshot, true, PlayerActivityState.IDLE,
                    idleRuntime, capturing));
            ingress.accept(server, observation(playerId));
            ingress.drain(server);
            helper.assertValueEqual(signals.get(), 0, "IDLE signal count");
            helper.assertValueEqual(idleRuntime.entryCountForTesting(), 0, "IDLE cooldown count");

            GameplayPromotionRuntimeState offlineRuntime = GameplayPromotionRuntimeState.forTesting(
                    server, () -> 0);
            ingress.setSinkForTesting(service(snapshot, false, PlayerActivityState.ACTIVE,
                    offlineRuntime, capturing));
            ingress.accept(server, observation(playerId));
            ingress.drain(server);
            helper.assertValueEqual(signals.get(), 0, "offline signal count");
            helper.assertValueEqual(offlineRuntime.entryCountForTesting(), 0,
                    "offline cooldown count");

            GameplayPromotionRuntimeState unavailableRuntime = GameplayPromotionRuntimeState.forTesting(
                    server, () -> 0);
            GameplayPromotionService unavailableService = service(snapshot, true,
                    PlayerActivityState.ACTIVE, unavailableRuntime, GameplaySignalSink.UNAVAILABLE);
            GameplayPromotionResult unavailable = unavailableService.promote(server, observation(playerId));
            helper.assertValueEqual(unavailable.status(),
                    GameplayPromotionResult.Status.SINK_UNAVAILABLE,
                    "default unavailable sink status");
            helper.assertTrue(!unavailableRuntime.isEligible(playerId, id("gates")),
                    "UNAVAILABLE sink did not retain cooldown");

            GameplayPromotionRuntimeState emptyRuntime = GameplayPromotionRuntimeState.forTesting(
                    server, () -> 0);
            GameplayPromotionResult noMatch = service(GameplayPromotionSnapshot.empty(0), true,
                    PlayerActivityState.ACTIVE, emptyRuntime, capturing)
                    .promote(server, observation(playerId));
            helper.assertValueEqual(noMatch.status(), GameplayPromotionResult.Status.NO_MATCH,
                    "empty production-style snapshot result");
            helper.assertValueEqual(emptyRuntime.entryCountForTesting(), 0,
                    "empty snapshot cooldown count");
        } finally {
            ingress.resetForTesting();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void lifecycleListenersRemovePlayerAndServerRuntimeState(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayPromotionRuntimeState.discard(server);
        GameplayPromotionRuntimeState runtime = GameplayPromotionRuntimeState.get(server);
        runtime.alignGeneration(1);
        UUID firstPlayerId = UUID.randomUUID();
        UUID secondPlayerId = UUID.randomUUID();
        ResourceLocation ruleId = id("lifecycle");
        runtime.reserve(firstPlayerId, ruleId, 20);
        runtime.reserve(secondPlayerId, ruleId, 20);
        FakePlayer firstPlayer = new FakePlayer(helper.getLevel(),
                new GameProfile(firstPlayerId, "PromotionLogout"));

        GameplayPromotionService.INSTANCE.onPlayerLoggedOut(
                new PlayerEvent.PlayerLoggedOutEvent(firstPlayer));
        helper.assertTrue(runtime.isEligible(firstPlayerId, ruleId),
                "logged-out player cooldown remained");
        helper.assertTrue(!runtime.isEligible(secondPlayerId, ruleId),
                "other player cooldown was removed");

        GameplayPromotionService.INSTANCE.onServerStopped(new ServerStoppedEvent(server));
        helper.assertTrue(!GameplayPromotionRuntimeState.hasStateForTesting(server),
                "server runtime remained after stop");
        GameplayPromotionService.INSTANCE.onPlayerLoggedOut(
                new PlayerEvent.PlayerLoggedOutEvent(firstPlayer));
        GameplayPromotionService.INSTANCE.onServerStopped(new ServerStoppedEvent(server));
        helper.assertTrue(!GameplayPromotionRuntimeState.hasStateForTesting(server),
                "no-op cleanup recreated runtime");
        helper.succeed();
    }

    private static GameplayPromotionService service(GameplayPromotionSnapshot snapshot,
            boolean online, PlayerActivityState activityState,
            GameplayPromotionRuntimeState runtime, GameplaySignalSink sink) {
        return new GameplayPromotionService(() -> snapshot,
                (server, playerId) -> online,
                server -> playerId -> activityState == null ? Optional.empty()
                        : Optional.of(new PlayerActivitySnapshot(activityState, 0, 0)),
                ignored -> runtime, GameplayPromotionSignalFactory::create, sink);
    }

    private static GameplayPromotionDefinition blockDefinition(String path) {
        return new GameplayPromotionDefinition(id(path), GameplayObservationTypes.BLOCK_BROKEN.id(),
                id(path + "_signal"), 0, 20,
                new BlockBrokenPromotionMatcher(Optional.of(STONE), Optional.empty()));
    }

    private static GameplayPromotionSnapshot snapshot(List<GameplayPromotionDefinition> definitions,
            long generation) {
        GameplayPromotionManager.Prepared prepared = GameplayPromotionManager.compile(definitions);
        return new GameplayPromotionSnapshot(prepared.definitions(), prepared.orderedDefinitions(),
                prepared.index(), prepared.watchedMetrics(), prepared.signalTypes(), generation);
    }

    private static GameplayObservation<BlockBrokenPayload> observation(UUID playerId) {
        return new GameplayObservation<>(GameplayObservationTypes.BLOCK_BROKEN, playerId, 0,
                new BlockBrokenPayload(STONE, Level.OVERWORLD.location(), BlockPos.ZERO));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }

    private static void expectRejected(GameTestHelper helper, Runnable action, String label) {
        try {
            action.run();
            helper.fail(label + " was accepted");
        } catch (IllegalStateException expected) {
            // Expected configuration rejection.
        }
    }

    private static void expectNullRejected(GameTestHelper helper, Runnable action, String label) {
        try {
            action.run();
            helper.fail(label + " was accepted");
        } catch (NullPointerException expected) {
            // Expected null rejection.
        }
    }
}
