package com.sande.mythictrpg.interaction.spontaneous;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.promotion.BlockBrokenEvidence;
import com.sande.mythictrpg.gameplay.promotion.GameplayActionPayload;
import com.sande.mythictrpg.gameplay.promotion.GameplaySignalResult;
import com.sande.mythictrpg.gameplay.promotion.GameplaySpontaneousInteractionSink;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.api.InteractionSignalType;
import com.sande.mythictrpg.interaction.content.ContentPreparationRequest;
import com.sande.mythictrpg.interaction.content.InteractionContentPreparer;
import com.sande.mythictrpg.interaction.content.PreparationResult;
import com.sande.mythictrpg.interaction.director.InteractionAudience;
import com.sande.mythictrpg.interaction.director.InteractionParticipants;
import com.sande.mythictrpg.interaction.director.InteractionPlan;
import com.sande.mythictrpg.interaction.director.PlanRevisionStamp;
import com.sande.mythictrpg.interaction.runtime.InteractionRuntimeState;
import com.sande.mythictrpg.interaction.start.InteractionStartResult;
import com.sande.mythictrpg.interaction.start.InteractionStartStatus;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourAFourBGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation TEST_GOD = id("phase4a4b_test_god");
    private static final InteractionSignalType<GameplayActionPayload> SIGNAL_TYPE =
            new InteractionSignalType<>(id("phase4a4b_spontaneous"),
                    InteractionMode.SPONTANEOUS, GameplayActionPayload.class);

    private PhaseFourAFourBGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void resolverRouterSeparatesProductionAndTestOverride(GameTestHelper helper) {
        InteractionContentPreparerResolverRouter router =
                InteractionContentPreparerResolverRouter.forTesting();
        InteractionSignal<GameplayActionPayload> signal = signal(UUID.randomUUID());
        helper.assertValueEqual(router.resolve(signal).status(),
                ContentPreparerResolution.Status.UNAVAILABLE, "default resolver status");

        AtomicInteger productionCalls = new AtomicInteger();
        AtomicInteger overrideCalls = new AtomicInteger();
        InteractionContentPreparerResolver production = ignored -> {
            productionCalls.incrementAndGet();
            return ContentPreparerResolution.unavailable();
        };
        router.configureProductionResolver(production);
        router.configureProductionResolver(production);
        expectIllegalState(helper, () -> router.configureProductionResolver(
                ignored -> ContentPreparerResolution.unavailable()),
                "conflicting production resolver");
        expectNull(helper, () -> router.configureProductionResolver(null),
                "null production resolver");
        expectNull(helper, () -> router.setResolverForTesting(null), "null test resolver");

        router.setResolverForTesting(ignored -> {
            overrideCalls.incrementAndGet();
            return ContentPreparerResolution.available(noContentPreparer("override"));
        });
        helper.assertValueEqual(router.resolve(signal).status(),
                ContentPreparerResolution.Status.AVAILABLE, "override resolver status");
        helper.assertValueEqual(productionCalls.get(), 0, "production fan-out count");
        helper.assertValueEqual(overrideCalls.get(), 1, "override call count");

        router.resetForTesting();
        helper.assertValueEqual(router.resolve(signal).status(),
                ContentPreparerResolution.Status.UNAVAILABLE, "production status after reset");
        helper.assertValueEqual(productionCalls.get(), 1, "production call after reset");

        InteractionContentPreparerResolverRouter defaultRouter =
                InteractionContentPreparerResolverRouter.forTesting();
        defaultRouter.setResolverForTesting(ignored -> ContentPreparerResolution.available(
                noContentPreparer("temporary")));
        defaultRouter.resetForTesting();
        helper.assertValueEqual(defaultRouter.resolve(signal).status(),
                ContentPreparerResolution.Status.UNAVAILABLE,
                "default unavailable after override reset");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void unavailableBridgeDoesNotAcquireOrExecute(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        AtomicInteger executorCalls = new AtomicInteger();
        SpontaneousInteractionRuntimeState runtime = SpontaneousInteractionRuntimeState.forTesting(
                server, new AtomicLong()::get, SpontaneousInteractionRuntimeState.DEFAULT_TIMEOUT_TICKS);
        SpontaneousInteractionSubmissionService service = new SpontaneousInteractionSubmissionService(
                InteractionContentPreparerResolver.UNAVAILABLE, ignored -> runtime,
                (ignoredServer, ignoredPlayer) -> true,
                (ignoredServer, ignoredSignal, ignoredPreparer) -> {
                    executorCalls.incrementAndGet();
                    return new CompletableFuture<>();
                }, SpontaneousInteractionObserver.NO_OP);
        GameplaySpontaneousInteractionSink sink = new GameplaySpontaneousInteractionSink(service);

        helper.assertValueEqual(sink.accept(server, signal(UUID.randomUUID())),
                GameplaySignalResult.UNAVAILABLE, "unavailable production-like bridge result");
        helper.assertValueEqual(runtime.entryCountForTesting(), 0,
                "unavailable bridge permit count");
        helper.assertValueEqual(executorCalls.get(), 0, "unavailable bridge executor calls");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void scriptedBridgeUsesGuardedPreparerAndReleasesPermit(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        AtomicLong tick = new AtomicLong();
        SpontaneousInteractionRuntimeState runtime = SpontaneousInteractionRuntimeState.forTesting(
                server, tick::get, SpontaneousInteractionRuntimeState.DEFAULT_TIMEOUT_TICKS);
        CompletableFuture<PreparationResult> preparation = new CompletableFuture<>();
        InteractionContentPreparer scripted = request -> preparation;
        AtomicReference<InteractionContentPreparer> supplied = new AtomicReference<>();
        List<InteractionStartResult> observed = new ArrayList<>();
        SpontaneousInteractionSubmissionService service = new SpontaneousInteractionSubmissionService(
                ignored -> ContentPreparerResolution.available(scripted), ignored -> runtime,
                (ignoredServer, ignoredPlayer) -> true,
                (ignoredServer, interactionSignal, preparer) -> {
                    supplied.set(preparer);
                    return preparer.prepare(request(interactionSignal)).thenApply(
                            PhaseFourAFourBGameTests::toStartResult);
                }, (ignoredSignal, result) -> observed.add(result));
        GameplaySpontaneousInteractionSink sink = new GameplaySpontaneousInteractionSink(service);

        helper.assertValueEqual(sink.accept(server, signal(UUID.randomUUID())),
                GameplaySignalResult.ACCEPTED, "scripted bridge result");
        helper.assertTrue(supplied.get() != null && supplied.get() != scripted,
                "executor did not receive guarded preparer");
        helper.assertValueEqual(runtime.entryCountForTesting(), 1, "in-flight permit count");

        preparation.complete(PreparationResult.noContent(id("scripted_no_content")));
        helper.assertValueEqual(runtime.entryCountForTesting(), 0,
                "completed bridge permit count");
        helper.assertValueEqual(observed.size(), 1, "observer result count");
        helper.assertValueEqual(observed.getFirst().status(), InteractionStartStatus.NO_CONTENT,
                "observer result status");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void productionExecutorReachesOrchestratorWithoutDialogue(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        SpontaneousInteractionRuntimeState.discard(server);
        InteractionRuntimeState.discard(server);
        InteractionContentPreparerResolverRouter resolver =
                InteractionContentPreparerResolverRouter.INSTANCE;
        resolver.setResolverForTesting(ignored -> ContentPreparerResolution.available(
                noContentPreparer("production_no_content")));
        try {
            GameplaySpontaneousInteractionSink sink = new GameplaySpontaneousInteractionSink(
                    SpontaneousInteractionSubmissionService.INSTANCE);
            helper.assertValueEqual(sink.accept(server, signal(UUID.randomUUID())),
                    GameplaySignalResult.ACCEPTED, "production executor submission result");
            SpontaneousInteractionRuntimeState runtime =
                    SpontaneousInteractionRuntimeState.get(server);
            helper.assertValueEqual(runtime.entryCountForTesting(), 0,
                    "production executor retained permit");
        } finally {
            resolver.clearResolverOverrideForTesting();
            SpontaneousInteractionRuntimeState.discard(server);
            InteractionRuntimeState.discard(server);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void lifecycleCleanupIsScopedAndIdempotent(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        SpontaneousInteractionRuntimeState.discard(server);
        SpontaneousInteractionRuntimeState runtime = SpontaneousInteractionRuntimeState.get(server);
        UUID firstPlayerId = UUID.randomUUID();
        UUID secondPlayerId = UUID.randomUUID();
        SpontaneousInteractionRuntimeState.Permit first = runtime.tryAcquire(firstPlayerId).permit();
        SpontaneousInteractionRuntimeState.Permit second = runtime.tryAcquire(secondPlayerId).permit();
        FakePlayer firstPlayer = new FakePlayer(helper.getLevel(),
                new GameProfile(firstPlayerId, "SpontaneousLogout"));

        SpontaneousInteractionSubmissionService.INSTANCE.onPlayerLoggedOut(
                new PlayerEvent.PlayerLoggedOutEvent(firstPlayer));
        helper.assertTrue(!runtime.isActive(first), "logged-out player permit remained");
        helper.assertTrue(runtime.isActive(second), "other player permit was removed");

        SpontaneousInteractionSubmissionService.INSTANCE.onServerStopped(
                new ServerStoppedEvent(server));
        helper.assertTrue(!SpontaneousInteractionRuntimeState.hasStateForTesting(server),
                "server runtime remained after stop");
        helper.assertTrue(!runtime.isActive(second), "discarded runtime permit remained active");

        SpontaneousInteractionSubmissionService.INSTANCE.onPlayerLoggedOut(
                new PlayerEvent.PlayerLoggedOutEvent(firstPlayer));
        SpontaneousInteractionSubmissionService.INSTANCE.onServerStopped(
                new ServerStoppedEvent(server));
        helper.assertTrue(!SpontaneousInteractionRuntimeState.hasStateForTesting(server),
                "idempotent cleanup recreated runtime");
        helper.succeed();
    }

    private static InteractionContentPreparer noContentPreparer(String reasonPath) {
        return request -> CompletableFuture.completedFuture(
                PreparationResult.noContent(id(reasonPath)));
    }

    private static InteractionStartResult toStartResult(PreparationResult preparation) {
        return switch (preparation.status()) {
            case PREPARED -> throw new IllegalArgumentException("Test content must not start");
            case NO_CONTENT -> InteractionStartResult.rejected(InteractionStartStatus.NO_CONTENT,
                    preparation.reason().orElseThrow());
            case FAILED -> InteractionStartResult.rejected(InteractionStartStatus.CONTENT_FAILED,
                    preparation.reason().orElseThrow());
        };
    }

    private static ContentPreparationRequest request(InteractionSignal<?> signal) {
        InteractionPlan plan = new InteractionPlan(signal.initiatingPlayerId(),
                InteractionAudience.initiatorOnly(signal.initiatingPlayerId()), signal.mode(),
                signal.type().id(), InteractionParticipants.primaryOnly(TEST_GOD),
                PlanRevisionStamp.spontaneous(1L, 1L), List.of());
        return new ContentPreparationRequest(signal, plan);
    }

    private static InteractionSignal<GameplayActionPayload> signal(UUID playerId) {
        GameplayActionPayload payload = new GameplayActionPayload(id("phase4a4b_rule"),
                id("phase4a4b_observation"), Optional.empty(),
                new BlockBrokenEvidence(id("phase4a4b_block"), Level.OVERWORLD.location()));
        return new InteractionSignal<>(SIGNAL_TYPE, playerId, Set.of(), payload);
    }

    private static void expectIllegalState(GameTestHelper helper, Runnable action, String label) {
        try {
            action.run();
            helper.fail(label + " was accepted");
        } catch (IllegalStateException expected) {
            // Expected configure-once rejection.
        }
    }

    private static void expectNull(GameTestHelper helper, Runnable action, String label) {
        try {
            action.run();
            helper.fail(label + " was accepted");
        } catch (NullPointerException expected) {
            // Expected null rejection.
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
