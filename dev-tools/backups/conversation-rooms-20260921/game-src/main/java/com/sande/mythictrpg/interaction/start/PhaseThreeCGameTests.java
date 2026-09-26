package com.sande.mythictrpg.interaction.start;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionContext;
import com.sande.mythictrpg.condition.api.ConditionEnvironment;
import com.sande.mythictrpg.condition.api.ConditionEventContext;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.api.GodDefinitionView;
import com.sande.mythictrpg.condition.api.WorldStateView;
import com.sande.mythictrpg.condition.engine.ConditionTreeParser;
import com.sande.mythictrpg.condition.registry.ConditionTypeRegistry;
import com.sande.mythictrpg.data.god.GodAppearanceIndex;
import com.sande.mythictrpg.data.god.GodCategoryIndex;
import com.sande.mythictrpg.data.god.GodDefinition;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.data.god.GodUnlockDependencyIndex;
import com.sande.mythictrpg.data.player.EncounterBatchResult;
import com.sande.mythictrpg.data.player.EncounterBatchStatus;
import com.sande.mythictrpg.data.player.GodKnowledgeSnapshot;
import com.sande.mythictrpg.data.player.ParticipationStatus;
import com.sande.mythictrpg.data.player.PlayerGodKnowledgeService;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import com.sande.mythictrpg.data.player.PlayerMythQueryService;
import com.sande.mythictrpg.dialogue.api.DialogueDisplayOptions;
import com.sande.mythictrpg.dialogue.api.DialoguePriority;
import com.sande.mythictrpg.interaction.api.EmptyInteractionPayload;
import com.sande.mythictrpg.interaction.api.ExplicitGodCallPayload;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.api.InteractionSignalTypes;
import com.sande.mythictrpg.interaction.content.PreparationResult;
import com.sande.mythictrpg.interaction.content.PreparedDialogueTurn;
import com.sande.mythictrpg.interaction.content.PreparedInteractionContent;
import com.sande.mythictrpg.interaction.content.PreparedInteractionContentValidator;
import com.sande.mythictrpg.interaction.content.ScriptedInteractionContentPreparer;
import com.sande.mythictrpg.interaction.content.ValidatedInteractionContent;
import com.sande.mythictrpg.interaction.context.InteractionContext;
import com.sande.mythictrpg.interaction.director.InteractionAudience;
import com.sande.mythictrpg.interaction.director.InteractionParticipants;
import com.sande.mythictrpg.interaction.director.InteractionPlan;
import com.sande.mythictrpg.interaction.director.PlanRevisionStamp;
import com.sande.mythictrpg.interaction.policy.CooldownView;
import com.sande.mythictrpg.interaction.policy.InteractionRuntimeView;
import com.sande.mythictrpg.interaction.rule.InteractionRuleManager;
import com.sande.mythictrpg.interaction.rule.InteractionRuleSeedIndex;
import com.sande.mythictrpg.interaction.runtime.InteractionRuntimeState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseThreeCGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ConditionTreeParser PARSER =
            new ConditionTreeParser(ConditionTypeRegistry.INSTANCE);
    private static final ResourceLocation DEMETER = id("demeter");
    private static final ResourceLocation APHRODITE = id("aphrodite");
    private static final ResourceLocation LUBRAS = id("lubras");

    private PhaseThreeCGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void preparationAndSpeakerValidation(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        InteractionPlan plan = plan(explicit(playerId, DEMETER),
                new InteractionParticipants(DEMETER, List.of(APHRODITE)),
                PlanRevisionStamp.explicit(7));
        PreparationResult scripted = new ScriptedInteractionContentPreparer(Component.literal("Prepared"))
                .prepare(new com.sande.mythictrpg.interaction.content.ContentPreparationRequest(
                        explicit(playerId, DEMETER), plan)).toCompletableFuture().getNow(null);
        helper.assertValueEqual(scripted.status(), PreparationResult.Status.PREPARED,
                "scripted preparation status");
        helper.assertValueEqual(PreparationResult.noContent(id("none")).status(),
                PreparationResult.Status.NO_CONTENT, "no-content status");
        helper.assertValueEqual(PreparationResult.failed(id("failed")).status(),
                PreparationResult.Status.FAILED, "failed status");

        var validator = PreparedInteractionContentValidator.INSTANCE;
        helper.assertValueEqual(validator.validate(plan, new PreparedInteractionContent(List.of()))
                        .rejectionReason().orElseThrow(),
                PreparedInteractionContentValidator.EMPTY_CONTENT, "empty content rejection");
        helper.assertValueEqual(validator.validate(plan, content(turn(APHRODITE, "secondary only")))
                        .rejectionReason().orElseThrow(),
                PreparedInteractionContentValidator.PRIMARY_SILENT, "silent primary rejection");
        helper.assertValueEqual(validator.validate(plan, content(turn(LUBRAS, "outsider")))
                        .rejectionReason().orElseThrow(),
                PreparedInteractionContentValidator.OUTSIDER_SPEAKER, "outsider rejection");
        var accepted = validator.validate(plan, content(turn(DEMETER, "primary only")))
                .content().orElseThrow();
        helper.assertValueEqual(accepted.manifestedGodIds(), Set.of(DEMETER),
                "silent secondary became manifested");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void encounterBatchIsAtomicAndDoesNotIdentify(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PlayerMythDataService players = PlayerMythDataService.get(server);
        PlayerGodKnowledgeService knowledge = PlayerGodKnowledgeService.get(server);
        UUID playerId = UUID.randomUUID();
        UUID invalidPlayerId = UUID.randomUUID();
        players.ensureProfile(playerId);
        players.ensureProfile(invalidPlayerId);
        try {
            EncounterBatchResult result = knowledge.recordEncounters(
                    playerId, List.of(DEMETER, APHRODITE, LUBRAS, DEMETER));
            helper.assertValueEqual(result.status(), EncounterBatchStatus.NEW_RECORDS,
                    "three-God batch status");
            helper.assertValueEqual(result.newRecordCount(), 3, "three-God batch count");
            for (ResourceLocation godId : Set.of(DEMETER, APHRODITE, LUBRAS)) {
                helper.assertValueEqual(knowledge.snapshot(playerId, godId),
                        new GodKnowledgeSnapshot(true, false), "batch knowledge for " + godId);
            }
            helper.assertValueEqual(knowledge.recordEncounters(playerId, Set.of(DEMETER)).status(),
                    EncounterBatchStatus.ALREADY_RECORDED, "duplicate batch status");

            ResourceLocation missing = id("missing_phase3c_god");
            helper.assertValueEqual(knowledge.recordEncounters(
                    invalidPlayerId, List.of(DEMETER, missing)).status(),
                    EncounterBatchStatus.UNKNOWN_GOD, "unknown batch status");
            helper.assertTrue(!knowledge.snapshot(invalidPlayerId, DEMETER).encountered(),
                    "invalid batch partially mutated profile");
        } finally {
            players.setParticipationStatus(playerId, ParticipationStatus.ARCHIVED);
            players.setParticipationStatus(invalidPlayerId, ParticipationStatus.ARCHIVED);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void manifestedSpeakersAloneReachEncounterCommit(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID playerId = UUID.randomUUID();
        AtomicLong time = new AtomicLong();
        InteractionRuntimeState runtime = InteractionRuntimeState.forTesting(server, time::get);
        var signal = explicit(playerId, DEMETER);
        var participants = new InteractionParticipants(DEMETER, List.of(APHRODITE, LUBRAS));
        var plan = plan(signal, participants, PlanRevisionStamp.explicit(7));
        Map<ResourceLocation, GodDefinition> definitions = Map.of(
                DEMETER, god("Demeter", null, null),
                APHRODITE, god("Aphrodite", null, null),
                LUBRAS, god("Lubras", null, null));
        InteractionStartStateProvider state = (ignoredServer, ignoredRequest, currentRuntime) ->
                startSnapshot(context(signal, definitions, Set.of(), 7, 11,
                        currentRuntime, currentRuntime), playerId, true, true);
        java.util.concurrent.atomic.AtomicReference<Set<ResourceLocation>> committed =
                new java.util.concurrent.atomic.AtomicReference<>();
        EncounterCommitter capture = (ignoredServer, ignoredPlayer, gods) -> {
            committed.set(Set.copyOf(gods));
            return new EncounterBatchResult(EncounterBatchStatus.ALREADY_RECORDED, gods, 0);
        };
        InteractionStartService service = new InteractionStartService(
                PreparedInteractionContentValidator.INSTANCE,
                output(new AtomicInteger(), DeliveryStatus.ALL_SENT), state,
                InteractionStartValidator.INSTANCE, capture, UUID::randomUUID);

        helper.assertValueEqual(service.start(server, runtime,
                        request(signal, plan, turn(DEMETER, "primary only"))).status(),
                InteractionStartStatus.STARTED, "silent-secondary start");
        helper.assertValueEqual(committed.get(), Set.of(DEMETER),
                "silent secondary reached encounter commit");

        helper.assertValueEqual(service.start(server, runtime,
                        request(signal, plan, turn(DEMETER, "one"),
                                turn(APHRODITE, "two"), turn(LUBRAS, "three"))).status(),
                InteractionStartStatus.STARTED, "three-speaker start");
        helper.assertValueEqual(committed.get(), Set.of(DEMETER, APHRODITE, LUBRAS),
                "three speakers did not form three-God encounter batch");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void finalRevalidationUsesActualDependenciesAndModePolicy(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        ResourceLocation conditional = id("phase3c_conditional");
        ResourceLocation locked = id("phase3c_locked");
        Map<ResourceLocation, GodDefinition> definitions = Map.of(
                conditional, god("Conditional", null, biome("minecraft:desert")),
                locked, god("Locked", always(), null));
        InteractionRuntimeView available = InteractionRuntimeView.AVAILABLE;

        var explicitSignal = explicit(playerId, conditional);
        var explicitPlan = plan(explicitSignal, InteractionParticipants.primaryOnly(conditional),
                PlanRevisionStamp.explicit(7));
        var explicitRequest = request(explicitSignal, explicitPlan, turn(conditional, "explicit"));
        StartValidationResult explicitResult = InteractionStartValidator.INSTANCE.validate(explicitRequest,
                startSnapshot(context(explicitSignal, definitions, Set.of(), 7, 99,
                        CooldownView.NONE, available), playerId, true, true));
        helper.assertValueEqual(explicitResult.status(), StartValidationResult.Status.ACCEPTED,
                "explicit call depended on interaction-rule generation or appearance");

        var staleGodContext = context(explicitSignal, definitions, Set.of(), 8, 99,
                CooldownView.NONE, available);
        helper.assertValueEqual(InteractionStartValidator.INSTANCE.validate(explicitRequest,
                        startSnapshot(staleGodContext, playerId, true, true)).status(),
                StartValidationResult.Status.STALE_PLAN, "stale God generation");

        var spontaneousSignal = spontaneous(playerId);
        var spontaneousPlan = plan(spontaneousSignal, InteractionParticipants.primaryOnly(conditional),
                PlanRevisionStamp.spontaneous(7, 11));
        var spontaneousRequest = request(spontaneousSignal, spontaneousPlan,
                turn(conditional, "spontaneous"));
        helper.assertValueEqual(InteractionStartValidator.INSTANCE.validate(spontaneousRequest,
                        startSnapshot(context(spontaneousSignal, definitions, Set.of(), 7, 12,
                                CooldownView.NONE, available), playerId, true, true)).status(),
                StartValidationResult.Status.STALE_PLAN, "stale spontaneous rule generation");
        helper.assertValueEqual(InteractionStartValidator.INSTANCE.validate(spontaneousRequest,
                        startSnapshot(context(spontaneousSignal, definitions, Set.of(), 7, 11,
                                CooldownView.NONE, available), playerId, true, true)).status(),
                StartValidationResult.Status.LEGALITY_FAILED, "moved player appearance legality");

        var lockedSignal = explicit(playerId, locked);
        var lockedPlan = plan(lockedSignal, InteractionParticipants.primaryOnly(locked),
                PlanRevisionStamp.explicit(7));
        helper.assertValueEqual(InteractionStartValidator.INSTANCE.validate(
                        request(lockedSignal, lockedPlan, turn(locked, "locked")),
                        startSnapshot(context(lockedSignal, definitions, Set.of(), 7, 11,
                                CooldownView.NONE, available), playerId, true, true)).status(),
                StartValidationResult.Status.LEGALITY_FAILED, "explicit bypassed unlock legality");
        helper.assertValueEqual(InteractionStartValidator.INSTANCE.validate(explicitRequest,
                        startSnapshot(context(explicitSignal, definitions, Set.of(), 7, 11,
                                CooldownView.NONE, available), playerId, false, true)).status(),
                StartValidationResult.Status.AUDIENCE_UNAVAILABLE, "offline audience");
        helper.assertValueEqual(InteractionStartValidator.INSTANCE.validate(explicitRequest,
                        startSnapshot(context(explicitSignal, definitions, Set.of(), 7, 11,
                                CooldownView.NONE, available), playerId, true, false)).status(),
                StartValidationResult.Status.AUDIENCE_UNAVAILABLE, "inactive audience");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void commitBoundaryRuntimeCooldownAndDeliveryFailure(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID playerId = UUID.randomUUID();
        PlayerMythDataService players = PlayerMythDataService.get(server);
        players.ensureProfile(playerId);
        AtomicLong time = new AtomicLong(1000);
        InteractionRuntimeState runtime = InteractionRuntimeState.forTesting(server, time::get);
        AtomicInteger generatedIds = new AtomicInteger();
        AtomicInteger deliveries = new AtomicInteger();
        var signal = explicit(playerId, DEMETER);
        long godGeneration = GodDefinitionManager.INSTANCE.generation();
        var plan = plan(signal, InteractionParticipants.primaryOnly(DEMETER),
                PlanRevisionStamp.explicit(godGeneration));
        InteractionContext liveContext = productionLikeContext(signal, runtime);
        InteractionStartStateProvider state = (ignoredServer, ignoredRequest, ignoredRuntime) ->
                startSnapshot(liveContext, playerId, true, true);
        InteractionIdGenerator ids = () -> {
            generatedIds.incrementAndGet();
            return UUID.fromString("00000000-0000-0000-0000-00000000003c");
        };
        InteractionDialogueOutput failedOutput = output(deliveries, DeliveryStatus.FAILED);
        InteractionStartService service = new InteractionStartService(
                PreparedInteractionContentValidator.INSTANCE, failedOutput, state,
                InteractionStartValidator.INSTANCE, EncounterCommitter.PERSISTENT, ids);
        try {
            InteractionStartResult result = service.start(server, runtime,
                    request(signal, plan, turn(DEMETER, "network failure")));
            helper.assertValueEqual(result.status(), InteractionStartStatus.STARTED,
                    "network failure rolled back committed start");
            helper.assertValueEqual(result.delivery().orElseThrow().status(), DeliveryStatus.FAILED,
                    "network failure delivery summary");
            helper.assertTrue(PlayerGodKnowledgeService.get(server).snapshot(playerId, DEMETER).encountered(),
                    "network failure rolled back encounter");
            helper.assertTrue(!PlayerGodKnowledgeService.get(server).snapshot(playerId, DEMETER).identified(),
                    "interaction identified God");
            helper.assertValueEqual(generatedIds.get(), 1, "successful commit ID count");

            AtomicInteger failedIds = new AtomicInteger();
            AtomicInteger failedDeliveries = new AtomicInteger();
            EncounterCommitter rejected = (ignoredServer, ignoredPlayer, gods) ->
                    new EncounterBatchResult(EncounterBatchStatus.PLAYER_UNAVAILABLE, gods, 0);
            InteractionStartService commitFailure = new InteractionStartService(
                    PreparedInteractionContentValidator.INSTANCE,
                    output(failedDeliveries, DeliveryStatus.ALL_SENT), state,
                    InteractionStartValidator.INSTANCE, rejected, () -> {
                        failedIds.incrementAndGet();
                        return UUID.randomUUID();
                    });
            InteractionStartResult failed = commitFailure.start(server, runtime,
                    request(signal, plan, turn(DEMETER, "commit fail")));
            helper.assertValueEqual(failed.status(), InteractionStartStatus.COMMIT_FAILED,
                    "encounter failure status");
            helper.assertTrue(failed.interactionId().isEmpty(), "failed commit exposed interaction ID");
            helper.assertValueEqual(failedIds.get(), 1, "pending ID was not generated after reserve");
            helper.assertValueEqual(failedDeliveries.get(), 0, "commit failure delivered dialogue");

            var held = runtime.tryReserve(playerId).orElseThrow();
            int beforeBusyIds = failedIds.get();
            InteractionStartResult busy = commitFailure.start(server, runtime,
                    request(signal, plan, turn(DEMETER, "busy")));
            runtime.release(held);
            helper.assertValueEqual(busy.status(), InteractionStartStatus.RUNTIME_BUSY,
                    "runtime busy status");
            helper.assertValueEqual(failedIds.get(), beforeBusyIds, "busy path generated interaction ID");

            verifyCooldownModes(helper, server, playerId, time);
        } finally {
            players.setParticipationStatus(playerId, ParticipationStatus.ARCHIVED);
        }
        helper.succeed();
    }

    private static void verifyCooldownModes(GameTestHelper helper, MinecraftServer server,
            UUID playerId, AtomicLong time) {
        ResourceLocation godId = DEMETER;
        Map<ResourceLocation, GodDefinition> definitions = Map.of(
                godId, god("Cooldown", null, always()));
        InteractionRuntimeState spontaneousRuntime = InteractionRuntimeState.forTesting(server, time::get);
        var spontaneous = spontaneous(playerId);
        var spontaneousPlan = plan(spontaneous, InteractionParticipants.primaryOnly(godId),
                PlanRevisionStamp.spontaneous(7, 11));
        InteractionStartStateProvider spontaneousState = (ignoredServer, ignoredRequest, runtime) ->
                startSnapshot(context(spontaneous, definitions, Set.of(), 7, 11, runtime, runtime),
                        playerId, true, true);
        EncounterCommitter accepted = (ignoredServer, ignoredPlayer, gods) ->
                new EncounterBatchResult(EncounterBatchStatus.ALREADY_RECORDED, gods, 0);
        InteractionStartService service = new InteractionStartService(
                PreparedInteractionContentValidator.INSTANCE,
                output(new AtomicInteger(), DeliveryStatus.ALL_SENT), spontaneousState,
                InteractionStartValidator.INSTANCE, accepted, UUID::randomUUID);
        helper.assertValueEqual(service.start(server, spontaneousRuntime,
                        request(spontaneous, spontaneousPlan, turn(godId, "spontaneous"))).status(),
                InteractionStartStatus.STARTED, "spontaneous start");
        helper.assertTrue(spontaneousRuntime.blockReason(playerId, godId,
                        InteractionMode.SPONTANEOUS).isPresent(),
                "spontaneous start omitted runtime cooldown");
        helper.assertTrue(spontaneousRuntime.blockReason(playerId, godId,
                        InteractionMode.EXPLICIT).isEmpty(),
                "spontaneous cooldown blocked explicit mode");

        InteractionRuntimeState explicitRuntime = InteractionRuntimeState.forTesting(server, time::get);
        var explicit = explicit(playerId, godId);
        var explicitPlan = plan(explicit, InteractionParticipants.primaryOnly(godId),
                PlanRevisionStamp.explicit(7));
        InteractionStartStateProvider explicitState = (ignoredServer, ignoredRequest, runtime) ->
                startSnapshot(context(explicit, definitions, Set.of(), 7, 91, runtime, runtime),
                        playerId, true, true);
        InteractionStartService explicitService = new InteractionStartService(
                PreparedInteractionContentValidator.INSTANCE,
                output(new AtomicInteger(), DeliveryStatus.ALL_SENT), explicitState,
                InteractionStartValidator.INSTANCE, accepted, UUID::randomUUID);
        helper.assertValueEqual(explicitService.start(server, explicitRuntime,
                        request(explicit, explicitPlan, turn(godId, "explicit"))).status(),
                InteractionStartStatus.STARTED, "explicit start");
        helper.assertTrue(explicitRuntime.blockReason(playerId, godId,
                        InteractionMode.SPONTANEOUS).isEmpty(),
                "explicit/admin start wrote spontaneous cooldown");
    }

    private static InteractionDialogueOutput output(AtomicInteger deliveries, DeliveryStatus status) {
        return new InteractionDialogueOutput() {
            @Override
            public PresentationPreflightResult preflight(MinecraftServer server,
                    InteractionAudience audience, ValidatedInteractionContent content) {
                return PresentationPreflightResult.ready();
            }

            @Override
            public DeliverySummary deliver(MinecraftServer server, UUID interactionId,
                    InteractionAudience audience, ValidatedInteractionContent content) {
                deliveries.incrementAndGet();
                int attempted = audience.recipientPlayerIds().size() * content.turns().size();
                int sent = status == DeliveryStatus.ALL_SENT ? attempted
                        : status == DeliveryStatus.PARTIAL ? Math.max(1, attempted - 1) : 0;
                return DeliverySummary.of(attempted, sent);
            }
        };
    }

    private static InteractionContext productionLikeContext(InteractionSignal<?> signal,
            InteractionRuntimeState runtime) {
        var snapshot = GodDefinitionManager.INSTANCE.progressionSnapshot();
        return context(signal, snapshot.definitions(), Set.of(), snapshot.generation(),
                InteractionRuleManager.INSTANCE.snapshot().generation(), runtime, runtime);
    }

    private static InteractionStartSnapshot startSnapshot(InteractionContext context, UUID playerId,
            boolean online, boolean active) {
        return new InteractionStartSnapshot(context, online ? Set.of(playerId) : Set.of(),
                active ? Set.of(playerId) : Set.of());
    }

    private static InteractionStartRequest request(InteractionSignal<?> signal, InteractionPlan plan,
            PreparedDialogueTurn... turns) {
        return new InteractionStartRequest(signal, plan, content(turns));
    }

    private static PreparedInteractionContent content(PreparedDialogueTurn... turns) {
        return new PreparedInteractionContent(List.of(turns));
    }

    private static PreparedDialogueTurn turn(ResourceLocation godId, String text) {
        return new PreparedDialogueTurn(godId, Component.literal(text),
                DialoguePriority.NORMAL, DialogueDisplayOptions.defaults());
    }

    private static InteractionPlan plan(InteractionSignal<?> signal, InteractionParticipants participants,
            PlanRevisionStamp revisions) {
        return new InteractionPlan(signal.initiatingPlayerId(),
                InteractionAudience.initiatorOnly(signal.initiatingPlayerId()), signal.mode(),
                signal.type().id(), participants, revisions, List.of(id("test_selection")));
    }

    private static InteractionContext context(InteractionSignal<?> signal,
            Map<ResourceLocation, GodDefinition> definitions, Set<ResourceLocation> unlocked,
            long godGeneration, long ruleGeneration, CooldownView cooldowns,
            InteractionRuntimeView runtime) {
        Map<UUID, PlayerMythProfile> profiles = Map.of(
                signal.initiatingPlayerId(), PlayerMythProfile.createActive());
        Map<ResourceLocation, GodDefinition> immutable = Map.copyOf(definitions);
        var gods = new GodDefinitionManager.ProgressionSnapshot(immutable,
                GodUnlockDependencyIndex.build(immutable), GodAppearanceIndex.build(immutable),
                GodCategoryIndex.build(immutable), godGeneration);
        var rules = new InteractionRuleManager.RuleSnapshot(
                List.of(), InteractionRuleSeedIndex.build(List.of()), ruleGeneration);
        PlayerMythQueryService players = new PlayerMythQueryService() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public Optional<PlayerMythProfile> find(UUID playerId) {
                return Optional.ofNullable(profiles.get(playerId));
            }

            @Override
            public Map<UUID, PlayerMythProfile> activeProfiles() {
                return profiles;
            }
        };
        WorldStateView world = new WorldStateView() {
            @Override
            public int dataVersion() {
                return 1;
            }

            @Override
            public Set<ResourceLocation> unlockedGods() {
                return Set.copyOf(unlocked);
            }
        };
        GodDefinitionView godView = new GodDefinitionView() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public Optional<GodDefinition> find(ResourceLocation godId) {
                return Optional.ofNullable(definitions.get(godId));
            }

            @Override
            public Set<ResourceLocation> godsInCategory(ResourceLocation categoryId) {
                return definitions.entrySet().stream()
                        .filter(entry -> entry.getValue().categories().contains(categoryId))
                        .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
            }
        };
        ConditionEnvironment environment = new ConditionEnvironment(Level.OVERWORLD, BlockPos.ZERO,
                Optional.of(id("test_biome")), 6000);
        ConditionContext conditions = new ConditionContext(world, players,
                () -> Set.of(signal.initiatingPlayerId()), godView,
                Optional.of(signal.initiatingPlayerId()), Optional.of(environment),
                Optional.<ConditionEventContext>empty());
        return new InteractionContext(signal, Set.of(), Optional.of(environment), conditions,
                profiles, gods, rules, cooldowns, runtime, true, Optional.empty());
    }

    private static InteractionSignal<EmptyInteractionPayload> spontaneous(UUID playerId) {
        return new InteractionSignal<>(InteractionSignalTypes.TEST_SPONTANEOUS,
                playerId, Set.of(), EmptyInteractionPayload.INSTANCE);
    }

    private static InteractionSignal<ExplicitGodCallPayload> explicit(UUID playerId,
            ResourceLocation godId) {
        return new InteractionSignal<>(InteractionSignalTypes.EXPLICIT_GOD_CALL,
                playerId, Set.of(), new ExplicitGodCallPayload(
                        godId, InteractionSignalTypes.PLAYER_EXPLICIT_POLICY));
    }

    private static GodDefinition god(String name, String unlock, String appearance) {
        return new GodDefinition(2, Component.literal(name), id("test_origin"), id("test_faction"),
                Set.of(id("test_category")), condition(unlock), condition(appearance), Optional.empty());
    }

    private static Optional<ConditionNode> condition(String json) {
        return json == null ? Optional.empty()
                : Optional.of(PARSER.parse(JsonParser.parseString(json)));
    }

    private static String always() {
        return "{\"type\":\"mythictrpg:always\"}";
    }

    private static String biome(String biome) {
        return "{\"type\":\"mythictrpg:biome\",\"scope\":\"player\",\"biome\":\""
                + biome + "\"}";
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
