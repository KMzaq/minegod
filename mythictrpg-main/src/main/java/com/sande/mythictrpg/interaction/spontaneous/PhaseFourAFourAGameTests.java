package com.sande.mythictrpg.interaction.spontaneous;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import com.sande.mythictrpg.interaction.api.EmptyInteractionPayload;
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
import com.sande.mythictrpg.interaction.start.InteractionStartResult;
import com.sande.mythictrpg.interaction.start.InteractionStartStatus;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourAFourAGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation TEST_GOD = id("phase4a4_test_god");
    private static final InteractionSignalType<EmptyInteractionPayload> SPONTANEOUS =
            new InteractionSignalType<>(id("phase4a4_spontaneous"),
                    InteractionMode.SPONTANEOUS, EmptyInteractionPayload.class);
    private static final InteractionSignalType<EmptyInteractionPayload> EXPLICIT =
            new InteractionSignalType<>(id("phase4a4_explicit"),
                    InteractionMode.EXPLICIT, EmptyInteractionPayload.class);

    private PhaseFourAFourAGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void unavailableAndWrongModeDoNotAcquirePermits(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        SpontaneousInteractionRuntimeState runtime = runtime(server, new AtomicLong());
        AtomicInteger resolverCalls = new AtomicInteger();
        SpontaneousInteractionSubmissionService service = service(runtime, signal -> {
            resolverCalls.incrementAndGet();
            return ContentPreparerResolution.unavailable();
        }, neverCompletingExecutor(), SpontaneousInteractionObserver.NO_OP, true);

        helper.assertValueEqual(service.submit(server, signal(EXPLICIT, UUID.randomUUID())),
                SpontaneousSubmissionResult.REJECTED, "explicit submission result");
        helper.assertValueEqual(resolverCalls.get(), 0, "resolver called for explicit signal");
        helper.assertValueEqual(service.submit(server, signal(SPONTANEOUS, UUID.randomUUID())),
                SpontaneousSubmissionResult.UNAVAILABLE, "unavailable submission result");
        helper.assertValueEqual(runtime.entryCountForTesting(), 0,
                "unavailable resolver created permit");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void inFlightIsPerPlayerAndRuntimeInstance(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        SpontaneousInteractionRuntimeState firstRuntime = runtime(server, new AtomicLong());
        SpontaneousInteractionRuntimeState secondRuntime = runtime(server, new AtomicLong());
        CompletableFuture<InteractionStartResult> held = new CompletableFuture<>();
        InteractionContentPreparerResolver resolver = available(
                request -> CompletableFuture.completedFuture(noContent("unused")));
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        SpontaneousInteractionSubmissionService first = service(firstRuntime, resolver,
                (ignoredServer, ignoredSignal, ignoredPreparer) -> held,
                SpontaneousInteractionObserver.NO_OP, true);
        SpontaneousInteractionSubmissionService second = service(secondRuntime, resolver,
                (ignoredServer, ignoredSignal, ignoredPreparer) -> new CompletableFuture<>(),
                SpontaneousInteractionObserver.NO_OP, true);

        helper.assertValueEqual(first.submit(server, signal(SPONTANEOUS, firstPlayer)),
                SpontaneousSubmissionResult.ACCEPTED, "first player submission");
        helper.assertValueEqual(first.submit(server, signal(SPONTANEOUS, firstPlayer)),
                SpontaneousSubmissionResult.REJECTED, "duplicate player submission");
        helper.assertValueEqual(first.submit(server, signal(SPONTANEOUS, secondPlayer)),
                SpontaneousSubmissionResult.ACCEPTED, "different player submission");
        helper.assertValueEqual(second.submit(server, signal(SPONTANEOUS, firstPlayer)),
                SpontaneousSubmissionResult.ACCEPTED, "independent runtime submission");
        helper.assertValueEqual(firstRuntime.entryCountForTesting(), 2,
                "first runtime permit count");
        helper.assertValueEqual(secondRuntime.entryCountForTesting(), 1,
                "second runtime permit count");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void normalCompletionReleasesPermitAndObserverFailureIsIsolated(
            GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        SpontaneousInteractionRuntimeState runtime = runtime(server, new AtomicLong());
        AtomicInteger observerCalls = new AtomicInteger();
        SpontaneousInteractionSubmissionService service = service(runtime,
                available(request -> CompletableFuture.completedFuture(noContent("normal"))),
                invokingExecutor(), (signal, result) -> {
                    observerCalls.incrementAndGet();
                    throw new IllegalStateException("expected observer failure");
                }, true);

        UUID playerId = UUID.randomUUID();
        helper.assertValueEqual(service.submit(server, signal(SPONTANEOUS, playerId)),
                SpontaneousSubmissionResult.ACCEPTED, "normal submission result");
        helper.assertValueEqual(observerCalls.get(), 1, "observer call count");
        helper.assertValueEqual(runtime.entryCountForTesting(), 0,
                "normal completion retained permit");
        helper.assertValueEqual(service.submit(server, signal(SPONTANEOUS, playerId)),
                SpontaneousSubmissionResult.ACCEPTED, "submission after observer failure");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void exactTimeoutRejectsOldCompletionWithoutReleasingNewPermit(
            GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        AtomicLong tick = new AtomicLong();
        SpontaneousInteractionRuntimeState runtime = runtime(server, tick);
        Deque<CompletableFuture<PreparationResult>> preparations = new ArrayDeque<>();
        CompletableFuture<PreparationResult> oldPreparation = new CompletableFuture<>();
        CompletableFuture<PreparationResult> newPreparation = new CompletableFuture<>();
        preparations.add(oldPreparation);
        preparations.add(newPreparation);
        List<InteractionStartResult> observed = new ArrayList<>();
        SpontaneousInteractionSubmissionService service = service(runtime,
                available(request -> preparations.removeFirst()), invokingExecutor(),
                (signal, result) -> observed.add(result), true);
        UUID playerId = UUID.randomUUID();

        helper.assertValueEqual(service.submit(server, signal(SPONTANEOUS, playerId)),
                SpontaneousSubmissionResult.ACCEPTED, "old submission");
        tick.set(1_199L);
        helper.assertValueEqual(service.submit(server, signal(SPONTANEOUS, playerId)),
                SpontaneousSubmissionResult.REJECTED, "submission before exact expiry");
        tick.set(1_200L);
        helper.assertValueEqual(service.submit(server, signal(SPONTANEOUS, playerId)),
                SpontaneousSubmissionResult.ACCEPTED, "submission at exact expiry");

        oldPreparation.complete(noContent("old_provider_result"));
        assertStale(helper, observed.getFirst(), "expired completion");
        helper.assertTrue(runtime.hasPermitForTesting(playerId),
                "old completion released new permit");
        newPreparation.complete(noContent("new_provider_result"));
        helper.assertValueEqual(observed.size(), 2, "completion count");
        helper.assertValueEqual(observed.get(1).reason().orElseThrow(), id("new_provider_result"),
                "new completion reason");
        helper.assertValueEqual(runtime.entryCountForTesting(), 0,
                "new completion retained permit");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void playerRemovalAndOfflineCompletionBecomeStale(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        SpontaneousInteractionRuntimeState runtime = runtime(server, new AtomicLong());
        Deque<CompletableFuture<PreparationResult>> preparations = new ArrayDeque<>();
        preparations.add(new CompletableFuture<>());
        preparations.add(new CompletableFuture<>());
        List<InteractionStartResult> observed = new ArrayList<>();
        UUID playerId = UUID.randomUUID();

        CompletableFuture<PreparationResult> removed = preparations.removeFirst();
        SpontaneousInteractionSubmissionService removalService = service(runtime,
                available(request -> removed), invokingExecutor(),
                (signal, result) -> observed.add(result), true);
        removalService.submit(server, signal(SPONTANEOUS, playerId));
        runtime.removePlayer(playerId);
        removed.complete(noContent("removed"));
        assertStale(helper, observed.getFirst(), "removed player completion");

        CompletableFuture<PreparationResult> offline = preparations.removeFirst();
        AtomicBoolean currentlyOnline = new AtomicBoolean(true);
        SpontaneousInteractionSubmissionService offlineService = service(runtime,
                available(request -> offline), invokingExecutor(),
                (signal, result) -> observed.add(result), currentlyOnline::get);
        offlineService.submit(server, signal(SPONTANEOUS, playerId));
        currentlyOnline.set(false);
        offline.complete(noContent("offline"));
        assertStale(helper, observed.get(1), "offline player completion");
        helper.assertValueEqual(runtime.entryCountForTesting(), 0,
                "stale completions retained permits");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void serverDiscardMakesCompletionStale(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        SpontaneousInteractionRuntimeState.discard(server);
        CompletableFuture<PreparationResult> preparation = new CompletableFuture<>();
        List<InteractionStartResult> observed = new ArrayList<>();
        SpontaneousInteractionSubmissionService service = new SpontaneousInteractionSubmissionService(
                available(request -> preparation), SpontaneousInteractionRuntimeState::get,
                (ignored, playerId) -> true, invokingExecutor(),
                (signal, result) -> observed.add(result));
        try {
            service.submit(server, signal(SPONTANEOUS, UUID.randomUUID()));
            SpontaneousInteractionRuntimeState.discard(server);
            preparation.complete(noContent("discarded"));
            assertStale(helper, observed.getFirst(), "discarded server completion");
            helper.assertTrue(!SpontaneousInteractionRuntimeState.hasStateForTesting(server),
                    "discarded server runtime was recreated");
        } finally {
            SpontaneousInteractionRuntimeState.discard(server);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void resolverExecutorAndProviderFailuresAreContained(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID playerId = UUID.randomUUID();
        InteractionSignal<?> signal = signal(SPONTANEOUS, playerId);
        SpontaneousInteractionRuntimeState runtime = runtime(server, new AtomicLong());

        helper.assertValueEqual(service(runtime, ignored -> {
            throw new IllegalStateException("expected resolver failure");
        }, neverCompletingExecutor(), SpontaneousInteractionObserver.NO_OP, true).submit(server, signal),
                SpontaneousSubmissionResult.FAILED, "resolver exception result");
        helper.assertValueEqual(service(runtime, ignored -> null,
                neverCompletingExecutor(), SpontaneousInteractionObserver.NO_OP, true)
                        .submit(server, signal),
                SpontaneousSubmissionResult.FAILED, "null resolver result");

        InteractionContentPreparerResolver available = available(
                request -> CompletableFuture.completedFuture(noContent("unused")));
        helper.assertValueEqual(service(runtime, available, (ignoredServer, ignoredSignal, preparer) -> {
            throw new IllegalStateException("expected executor failure");
        }, SpontaneousInteractionObserver.NO_OP, true).submit(server, signal),
                SpontaneousSubmissionResult.FAILED, "executor exception result");
        helper.assertValueEqual(service(runtime, available,
                (ignoredServer, ignoredSignal, preparer) -> null,
                SpontaneousInteractionObserver.NO_OP, true).submit(server, signal),
                SpontaneousSubmissionResult.FAILED, "null executor stage result");

        List<InteractionStartResult> observed = new ArrayList<>();
        List<InteractionContentPreparer> brokenPreparers = List.of(
                request -> { throw new IllegalStateException("expected provider failure"); },
                request -> null,
                request -> CompletableFuture.failedFuture(
                        new IllegalStateException("expected async provider failure")));
        for (InteractionContentPreparer broken : brokenPreparers) {
            helper.assertValueEqual(service(runtime, available(broken), invokingExecutor(),
                    (ignoredSignal, result) -> observed.add(result), true).submit(server, signal),
                    SpontaneousSubmissionResult.ACCEPTED, "provider failure submission");
        }
        helper.assertValueEqual(observed.size(), 3, "provider failure observer count");
        observed.forEach(result -> helper.assertValueEqual(result.status(),
                InteractionStartStatus.CONTENT_FAILED, "provider failure result"));
        helper.assertValueEqual(runtime.entryCountForTesting(), 0,
                "failure handling retained permits");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void timeoutValidationCapacityAndOverflowAreBounded(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        expectRejected(helper, () -> runtime(server, new AtomicLong(), 99L), "timeout below minimum");
        expectRejected(helper, () -> runtime(server, new AtomicLong(), 12_001L),
                "timeout above maximum");

        AtomicLong nearOverflow = new AtomicLong(Long.MAX_VALUE - 10L);
        SpontaneousInteractionRuntimeState overflow = runtime(server, nearOverflow);
        var overflowPermit = overflow.tryAcquire(UUID.randomUUID()).permit();
        helper.assertValueEqual(overflowPermit.expiresAtTick(), Long.MAX_VALUE,
                "overflow expiry saturation");

        SpontaneousInteractionRuntimeState capacity = runtime(server, new AtomicLong());
        for (int index = 0; index < SpontaneousInteractionRuntimeState.MAX_IN_FLIGHT_PER_SERVER;
                index++) {
            helper.assertValueEqual(capacity.tryAcquire(UUID.randomUUID()).status(),
                    SpontaneousInteractionRuntimeState.AcquireStatus.ACQUIRED,
                    "capacity entry " + index);
        }
        helper.assertValueEqual(capacity.tryAcquire(UUID.randomUUID()).status(),
                SpontaneousInteractionRuntimeState.AcquireStatus.CAPACITY_REJECTED,
                "capacity overflow result");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE, timeoutTicks = 200)
    public static void workerCompletionReturnsToServerThread(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        SpontaneousInteractionRuntimeState runtime = runtime(server, new AtomicLong());
        CompletableFuture<PreparationResult> preparation = new CompletableFuture<>();
        AtomicBoolean observerOnServerThread = new AtomicBoolean();
        SpontaneousInteractionSubmissionService service = service(runtime,
                available(request -> preparation), invokingExecutor(),
                (signal, result) -> observerOnServerThread.set(server.isSameThread()), true);
        service.submit(server, signal(SPONTANEOUS, UUID.randomUUID()));
        CompletableFuture.runAsync(() -> preparation.complete(noContent("worker")));
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(observerOnServerThread.get(), "observer did not run on server thread");
            helper.assertValueEqual(runtime.entryCountForTesting(), 0,
                    "worker completion retained permit");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void submissionDoesNotCreatePersistentPlayerData(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID playerId = UUID.randomUUID();
        PlayerMythDataService players = PlayerMythDataService.get(server);
        helper.assertTrue(players.find(playerId).isEmpty(), "test player already had a profile");
        SpontaneousInteractionRuntimeState runtime = runtime(server, new AtomicLong());
        SpontaneousInteractionSubmissionService service = service(runtime,
                available(request -> CompletableFuture.completedFuture(noContent("no_persistence"))),
                invokingExecutor(), SpontaneousInteractionObserver.NO_OP, true);
        service.submit(server, signal(SPONTANEOUS, playerId));
        helper.assertTrue(players.find(playerId).isEmpty(), "submission created persistent profile");
        helper.assertValueEqual(PlayerMythProfile.CURRENT_DATA_VERSION, 4,
                "player profile data version");
        helper.succeed();
    }

    private static SpontaneousInteractionSubmissionService service(
            SpontaneousInteractionRuntimeState runtime,
            InteractionContentPreparerResolver resolver,
            SpontaneousInteractionExecutor executor,
            SpontaneousInteractionObserver observer,
            boolean online) {
        return service(runtime, resolver, executor, observer, () -> online);
    }

    private static SpontaneousInteractionSubmissionService service(
            SpontaneousInteractionRuntimeState runtime,
            InteractionContentPreparerResolver resolver,
            SpontaneousInteractionExecutor executor,
            SpontaneousInteractionObserver observer,
            java.util.function.BooleanSupplier online) {
        return new SpontaneousInteractionSubmissionService(resolver, ignored -> runtime,
                (ignored, playerId) -> online.getAsBoolean(), executor, observer);
    }

    private static InteractionContentPreparerResolver available(InteractionContentPreparer preparer) {
        return signal -> ContentPreparerResolution.available(preparer);
    }

    private static SpontaneousInteractionExecutor invokingExecutor() {
        return (server, signal, preparer) -> preparer.prepare(request(signal))
                .thenApply(PhaseFourAFourAGameTests::toStartResult);
    }

    private static SpontaneousInteractionExecutor neverCompletingExecutor() {
        return (server, signal, preparer) -> new CompletableFuture<>();
    }

    private static InteractionStartResult toStartResult(PreparationResult preparation) {
        return switch (preparation.status()) {
            case PREPARED -> throw new IllegalArgumentException("Test executor does not start content");
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

    private static InteractionSignal<EmptyInteractionPayload> signal(
            InteractionSignalType<EmptyInteractionPayload> type, UUID playerId) {
        return new InteractionSignal<>(type, playerId, Set.of(), EmptyInteractionPayload.INSTANCE);
    }

    private static PreparationResult noContent(String reasonPath) {
        return PreparationResult.noContent(id(reasonPath));
    }

    private static SpontaneousInteractionRuntimeState runtime(MinecraftServer server,
            AtomicLong tick) {
        return runtime(server, tick, SpontaneousInteractionRuntimeState.DEFAULT_TIMEOUT_TICKS);
    }

    private static SpontaneousInteractionRuntimeState runtime(MinecraftServer server,
            AtomicLong tick, long timeoutTicks) {
        return SpontaneousInteractionRuntimeState.forTesting(server, tick::get, timeoutTicks);
    }

    private static void assertStale(GameTestHelper helper, InteractionStartResult result,
            String label) {
        helper.assertValueEqual(result.status(), InteractionStartStatus.NO_CONTENT,
                label + " status");
        helper.assertValueEqual(result.reason().orElseThrow(),
                SpontaneousSubmissionReasons.STALE_SUBMISSION, label + " reason");
    }

    private static void expectRejected(GameTestHelper helper, Runnable action, String label) {
        try {
            action.run();
            helper.fail(label + " was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected validation rejection.
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
