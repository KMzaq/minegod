package com.sande.mythictrpg.interaction.preview;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.player.PlayerGodKnowledgeService;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import com.sande.mythictrpg.data.world.MythicWorldState;
import com.sande.mythictrpg.interaction.api.EmptyInteractionPayload;
import com.sande.mythictrpg.interaction.api.ExplicitGodCallPayload;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.api.InteractionSignalTypes;
import com.sande.mythictrpg.interaction.candidate.CandidateReasons;
import com.sande.mythictrpg.interaction.content.InteractionContentPreparer;
import com.sande.mythictrpg.interaction.content.PreparationResult;
import com.sande.mythictrpg.interaction.director.InteractionDecision;
import com.sande.mythictrpg.interaction.orchestration.InteractionOrchestrator;
import com.sande.mythictrpg.interaction.policy.CooldownView;
import com.sande.mythictrpg.interaction.policy.InteractionRuntimeView;
import com.sande.mythictrpg.interaction.runtime.InteractionRuntimeReasons;
import com.sande.mythictrpg.interaction.runtime.InteractionRuntimeState;
import com.sande.mythictrpg.interaction.spontaneous.ContentPreparerResolution;
import com.sande.mythictrpg.interaction.spontaneous.InteractionContentPreparerResolver;
import com.sande.mythictrpg.interaction.start.InteractionStartStatus;
import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourAFourCGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation DEMETER = id("demeter");

    private PhaseFourAFourCGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void sharedPlanningPreviewsExplicitGodWithoutCreatingRuntime(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = onlinePlayer(helper);
        InteractionRuntimeState.discard(server);
        try {
            PlayerMythProfile before = PlayerMythDataService.get(server)
                    .find(player.getUUID()).orElseThrow();
            Set<ResourceLocation> worldBefore = MythicWorldState.get(server).unlockedGods();
            var knowledgeBefore = PlayerGodKnowledgeService.get(server)
                    .snapshot(player.getUUID(), DEMETER);
            var signal = signal(player.getUUID(), DEMETER);

            InteractionDecision decision = InteractionOrchestrator.INSTANCE.plan(
                    server, signal, CooldownView.NONE, InteractionRuntimeView.AVAILABLE);
            helper.assertValueEqual(decision.status(), InteractionDecision.Status.START,
                    "shared explicit planning status");
            helper.assertValueEqual(decision.plan().orElseThrow().participants().primaryGodId(),
                    DEMETER, "shared planned primary God");

            InteractionPreviewResult preview = InteractionPreviewService.INSTANCE.preview(
                    server, signal);
            helper.assertValueEqual(preview.status(), InteractionPreviewStatus.PROVIDER_UNAVAILABLE,
                    "production provider availability");
            helper.assertValueEqual(preview.primaryGodId(), Optional.of(DEMETER),
                    "preview primary God");
            helper.assertTrue(InteractionRuntimeState.readOnlyViewsIfPresent(server)
                            == InteractionRuntimeState.ReadOnlyViews.EMPTY,
                    "preview created interaction runtime state");
            helper.assertTrue(PlayerMythDataService.get(server).find(player.getUUID()).orElseThrow()
                            == before,
                    "preview replaced the player profile");
            helper.assertValueEqual(before.dataVersion(), PlayerMythProfile.CURRENT_DATA_VERSION,
                    "player profile data version");
            helper.assertValueEqual(PlayerGodKnowledgeService.get(server)
                            .snapshot(player.getUUID(), DEMETER), knowledgeBefore,
                    "preview changed God knowledge");
            helper.assertValueEqual(MythicWorldState.get(server).unlockedGods(), worldBefore,
                    "preview changed unlocked Gods");
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void unknownAndIllegalGodRequestsNeverResolveProvider(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = onlinePlayer(helper);
        InteractionRuntimeState.discard(server);
        try {
            AtomicInteger providerCalls = new AtomicInteger();
            InteractionPreviewService preview = service(ignored -> {
                providerCalls.incrementAndGet();
                return ContentPreparerResolution.unavailable();
            });

            InteractionPreviewResult unknown = preview.preview(server,
                    signal(player.getUUID(), id("unknown_phase_4a4c_god")));
            helper.assertValueEqual(unknown.status(), InteractionPreviewStatus.NO_PLAN,
                    "unknown God preview status");
            helper.assertValueEqual(unknown.reasonId(), Optional.of(CandidateReasons.DEFINITION_UNAVAILABLE),
                    "unknown God preview reason");

            var illegalPolicy = new InteractionSignal<>(InteractionSignalTypes.EXPLICIT_GOD_CALL,
                    player.getUUID(), Set.of(), new ExplicitGodCallPayload(
                            DEMETER, id("unsupported_phase_4a4c_policy")));
            helper.assertValueEqual(preview.preview(server, illegalPolicy).status(),
                    InteractionPreviewStatus.NO_PLAN, "illegal policy preview status");
            helper.assertValueEqual(providerCalls.get(), 0, "rejected preview provider calls");
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void invalidAndOfflineRequestsStopBeforePlanning(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        InteractionRuntimeState.discard(server);
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger resolverCalls = new AtomicInteger();
        InteractionPreviewService preview = new InteractionPreviewService(
                (ignoredServer, ignoredSignal, ignoredCooldowns, ignoredRuntime) -> {
                    plannerCalls.incrementAndGet();
                    return InteractionDecision.noStart(CandidateReasons.NO_ELIGIBLE_CANDIDATE);
                }, (ignoredServer, ignoredPlayer) -> false,
                InteractionRuntimeState::readOnlyViewsIfPresent, ignored -> {
                    resolverCalls.incrementAndGet();
                    return ContentPreparerResolution.unavailable();
                });

        var unsupported = new InteractionSignal<>(InteractionSignalTypes.TEST_SPONTANEOUS,
                UUID.randomUUID(), Set.of(), EmptyInteractionPayload.INSTANCE);
        helper.assertValueEqual(preview.preview(server, unsupported).status(),
                InteractionPreviewStatus.INVALID_REQUEST, "unsupported preview signal status");
        helper.assertValueEqual(preview.preview(server, signal(UUID.randomUUID(), DEMETER)).status(),
                InteractionPreviewStatus.PLAYER_OFFLINE, "offline preview status");
        helper.assertValueEqual(plannerCalls.get(), 0, "invalid preview planner calls");
        helper.assertValueEqual(resolverCalls.get(), 0, "invalid preview resolver calls");
        helper.assertTrue(InteractionRuntimeState.readOnlyViewsIfPresent(server)
                        == InteractionRuntimeState.ReadOnlyViews.EMPTY,
                "invalid preview created interaction runtime state");
        expectNull(helper, () -> preview.preview(null, signal(UUID.randomUUID(), DEMETER)),
                "null preview server");
        expectNull(helper, () -> preview.preview(server, null), "null preview signal");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void availableProviderIsNeverAskedToPrepare(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = onlinePlayer(helper);
        InteractionRuntimeState.discard(server);
        try {
            AtomicInteger resolverCalls = new AtomicInteger();
            AtomicInteger preparationCalls = new AtomicInteger();
            InteractionContentPreparer preparer = request -> {
                preparationCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                        PreparationResult.noContent(id("preview_preparation_forbidden")));
            };
            InteractionPreviewService preview = service(ignored -> {
                resolverCalls.incrementAndGet();
                return ContentPreparerResolution.available(preparer);
            });
            PlayerMythProfile before = PlayerMythDataService.get(server)
                    .find(player.getUUID()).orElseThrow();

            InteractionPreviewResult result = preview.preview(server, signal(player.getUUID(), DEMETER));
            helper.assertValueEqual(result.status(), InteractionPreviewStatus.PROVIDER_AVAILABLE,
                    "available provider status");
            helper.assertValueEqual(result.reasonId(), Optional.empty(),
                    "available provider rejection reason");
            helper.assertValueEqual(resolverCalls.get(), 1, "available resolver call count");
            helper.assertValueEqual(preparationCalls.get(), 0, "preview content preparation count");
            helper.assertTrue(PlayerMythDataService.get(server).find(player.getUUID()).orElseThrow()
                            == before,
                    "available provider preview replaced player profile");
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void providerFailureAndNullRemainBounded(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = onlinePlayer(helper);
        InteractionRuntimeState.discard(server);
        try {
            InteractionPreviewService throwing = service(ignored -> {
                throw new IllegalStateException("private provider diagnostic");
            });
            InteractionPreviewResult thrown = throwing.preview(server, signal(player.getUUID(), DEMETER));
            helper.assertValueEqual(thrown.status(), InteractionPreviewStatus.PROVIDER_FAILED,
                    "throwing provider status");
            helper.assertValueEqual(thrown.reasonId(), Optional.of(InteractionPreviewService.PROVIDER_FAILED),
                    "throwing provider bounded reason");

            InteractionPreviewService nullResolver = service(ignored -> null);
            InteractionPreviewResult missing = nullResolver.preview(server,
                    signal(player.getUUID(), DEMETER));
            helper.assertValueEqual(missing.status(), InteractionPreviewStatus.PROVIDER_FAILED,
                    "null provider resolution status");
            helper.assertValueEqual(missing.primaryGodId(), Optional.of(DEMETER),
                    "failed provider planned primary God");
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void busyRuntimeBlocksPreviewWithoutReleasingReservation(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = onlinePlayer(helper);
        InteractionRuntimeState.discard(server);
        InteractionRuntimeState runtime = InteractionRuntimeState.get(server);
        InteractionRuntimeState.Reservation reservation = runtime.tryReserve(player.getUUID()).orElseThrow();
        try {
            InteractionRuntimeState.ReadOnlyViews views =
                    InteractionRuntimeState.readOnlyViewsIfPresent(server);
            helper.assertTrue(!(views.runtime() instanceof InteractionRuntimeState),
                    "read-only runtime leaked its mutable implementation");
            helper.assertValueEqual(views.runtime().planningBlockReason(
                            player.getUUID(), InteractionMode.EXPLICIT),
                    Optional.of(InteractionRuntimeReasons.PLAYER_BUSY), "busy read-only runtime view");

            InteractionPreviewResult result = InteractionPreviewService.INSTANCE.preview(
                    server, signal(player.getUUID(), DEMETER));
            helper.assertValueEqual(result.status(), InteractionPreviewStatus.NO_PLAN,
                    "busy preview status");
            helper.assertValueEqual(result.reasonId(), Optional.of(InteractionRuntimeReasons.PLAYER_BUSY),
                    "busy preview reason");
            helper.assertValueEqual(views.runtime().planningBlockReason(
                            player.getUUID(), InteractionMode.EXPLICIT),
                    Optional.of(InteractionRuntimeReasons.PLAYER_BUSY),
                    "preview released the active reservation");
        } finally {
            runtime.release(reservation);
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void spontaneousCooldownDoesNotBlockOrChangeExplicitPreview(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = onlinePlayer(helper);
        InteractionRuntimeState.discard(server);
        InteractionRuntimeState runtime = InteractionRuntimeState.get(server);
        InteractionRuntimeState.Reservation reservation = runtime.tryReserve(player.getUUID()).orElseThrow();
        runtime.commit(reservation, InteractionMode.SPONTANEOUS);
        runtime.release(reservation);
        try {
            var views = InteractionRuntimeState.readOnlyViewsIfPresent(server);
            Optional<ResourceLocation> before = views.cooldowns().blockReason(
                    player.getUUID(), DEMETER, InteractionMode.SPONTANEOUS);
            helper.assertValueEqual(before, Optional.of(InteractionRuntimeReasons.SPONTANEOUS_COOLDOWN),
                    "existing spontaneous cooldown");
            helper.assertValueEqual(views.cooldowns().blockReason(
                            player.getUUID(), DEMETER, InteractionMode.EXPLICIT), Optional.empty(),
                    "explicit cooldown policy");

            InteractionPreviewResult preview = InteractionPreviewService.INSTANCE.preview(
                    server, signal(player.getUUID(), DEMETER));
            helper.assertValueEqual(preview.status(), InteractionPreviewStatus.PROVIDER_UNAVAILABLE,
                    "explicit preview during spontaneous cooldown");
            helper.assertValueEqual(views.cooldowns().blockReason(
                            player.getUUID(), DEMETER, InteractionMode.SPONTANEOUS), before,
                    "preview changed spontaneous cooldown");
            helper.assertValueEqual(views.runtime().planningBlockReason(
                            player.getUUID(), InteractionMode.EXPLICIT), Optional.empty(),
                    "preview created a reservation");
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void previewResultRejectsInvalidShapes(GameTestHelper helper) {
        ResourceLocation signalType = InteractionSignalTypes.EXPLICIT_GOD_CALL.id();
        expectInvalid(helper, () -> new InteractionPreviewResult(
                InteractionPreviewStatus.NO_PLAN, signalType, Optional.of(DEMETER),
                Optional.of(CandidateReasons.NO_ELIGIBLE_CANDIDATE)),
                "unplanned preview primary God");
        expectInvalid(helper, () -> new InteractionPreviewResult(
                InteractionPreviewStatus.PROVIDER_AVAILABLE, signalType, Optional.of(DEMETER),
                Optional.of(InteractionPreviewService.PROVIDER_FAILED)),
                "available provider rejection reason");
        expectInvalid(helper, () -> new InteractionPreviewResult(
                InteractionPreviewStatus.PROVIDER_FAILED, signalType, Optional.of(DEMETER),
                Optional.empty()), "failed provider missing reason");
        expectInvalid(helper, () -> new InteractionPreviewResult(
                InteractionPreviewStatus.PROVIDER_UNAVAILABLE, signalType, Optional.empty(),
                Optional.of(InteractionPreviewService.PROVIDER_UNAVAILABLE)),
                "provider evaluation missing primary God");
        expectNull(helper, () -> new InteractionRuntimeState.ReadOnlyViews(null,
                InteractionRuntimeView.AVAILABLE), "null read-only cooldown view");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void adminDryRunCommandIsBoundedAndPermissionProtected(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = onlinePlayer(helper);
        InteractionRuntimeState.discard(server);
        try {
            List<String> diagnostics = new ArrayList<>();
            CommandSource collector = new CommandSource() {
                @Override
                public void sendSystemMessage(Component message) {
                    diagnostics.add(message.getString());
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            };
            var dispatcher = server.getCommands().getDispatcher();
            String command = "mythadmin interaction dry-run god "
                    + player.getGameProfile().getName() + " " + DEMETER;
            int result = dispatcher.execute(command,
                    server.createCommandSourceStack().withSource(collector).withPermission(2));
            helper.assertValueEqual(result, 1, "admin dry-run command result");
            helper.assertValueEqual(diagnostics.size(), 1, "admin dry-run diagnostic count");
            String diagnostic = diagnostics.getFirst();
            helper.assertTrue(diagnostic.contains("status=PROVIDER_UNAVAILABLE")
                            && diagnostic.contains("signal=mythictrpg:explicit_god_call")
                            && diagnostic.contains("primary_god=mythictrpg:demeter")
                            && diagnostic.contains("provider=UNAVAILABLE"),
                    "admin dry-run diagnostic omitted bounded fields");
            helper.assertTrue(diagnostic.length() <= 512, "admin dry-run diagnostic was unbounded");

            try {
                dispatcher.execute(command,
                        server.createCommandSourceStack().withSource(collector).withPermission(1));
                helper.fail("Permission level 1 unexpectedly executed interaction dry-run");
                return;
            } catch (CommandSyntaxException expected) {
                // The command root remains inaccessible below permission level 2.
            }
            helper.assertTrue(InteractionRuntimeState.readOnlyViewsIfPresent(server)
                            == InteractionRuntimeState.ReadOnlyViews.EMPTY,
                    "admin dry-run created interaction runtime state");
        } catch (CommandSyntaxException exception) {
            helper.fail("Admin interaction dry-run command failed: " + exception.getMessage());
            return;
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void productionExecuteRetainsExistingPreparationBehavior(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = onlinePlayer(helper);
        InteractionRuntimeState.discard(server);
        try {
            AtomicInteger preparationCalls = new AtomicInteger();
            ResourceLocation reason = id("phase4a4c_existing_no_content");
            var result = InteractionOrchestrator.INSTANCE.execute(server,
                    signal(player.getUUID(), DEMETER), request -> {
                        preparationCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(PreparationResult.noContent(reason));
                    }).toCompletableFuture().join();
            helper.assertValueEqual(result.status(), InteractionStartStatus.NO_CONTENT,
                    "existing production execute status");
            helper.assertValueEqual(result.reason(), Optional.of(reason),
                    "existing production execute reason");
            helper.assertValueEqual(preparationCalls.get(), 1,
                    "existing production preparation call count");
            helper.assertTrue(!PlayerGodKnowledgeService.get(server)
                            .snapshot(player.getUUID(), DEMETER).encountered(),
                    "no-content production execution recorded an encounter");
        } finally {
            cleanup(server, player);
        }
        helper.succeed();
    }

    private static InteractionPreviewService service(InteractionContentPreparerResolver resolver) {
        return new InteractionPreviewService(InteractionOrchestrator.INSTANCE::plan,
                (server, playerId) -> server.getPlayerList().getPlayer(playerId) != null,
                InteractionRuntimeState::readOnlyViewsIfPresent, resolver);
    }

    private static InteractionSignal<ExplicitGodCallPayload> signal(UUID playerId,
            ResourceLocation godId) {
        return new InteractionSignal<>(InteractionSignalTypes.EXPLICIT_GOD_CALL, playerId, Set.of(),
                new ExplicitGodCallPayload(godId, InteractionSignalTypes.PLAYER_EXPLICIT_POLICY));
    }

    @SuppressWarnings("removal")
    private static ServerPlayer onlinePlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    private static void cleanup(MinecraftServer server, ServerPlayer player) {
        InteractionRuntimeState.discard(server);
        if (server.getPlayerList().getPlayer(player.getUUID()) != null) {
            server.getPlayerList().remove(player);
        }
    }

    private static void expectInvalid(GameTestHelper helper, Runnable action, String label) {
        try {
            action.run();
            helper.fail(label + " was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected invalid preview result shape.
        }
    }

    private static void expectNull(GameTestHelper helper, Runnable action, String label) {
        try {
            action.run();
            helper.fail(label + " was accepted");
        } catch (NullPointerException expected) {
            // Expected null input rejection.
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
