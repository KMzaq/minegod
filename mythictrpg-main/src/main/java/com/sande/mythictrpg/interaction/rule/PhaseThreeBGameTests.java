package com.sande.mythictrpg.interaction.rule;

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
import com.sande.mythictrpg.data.god.GodAppearanceService;
import com.sande.mythictrpg.data.god.GodCategoryIndex;
import com.sande.mythictrpg.data.god.GodDefinition;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.data.god.GodUnlockDependencyIndex;
import com.sande.mythictrpg.data.player.GodKnowledgeSnapshot;
import com.sande.mythictrpg.data.player.ParticipationStatus;
import com.sande.mythictrpg.data.player.PlayerGodKnowledgeService;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import com.sande.mythictrpg.data.player.PlayerMythQueryService;
import com.sande.mythictrpg.data.world.MythicWorldState;
import com.sande.mythictrpg.interaction.api.EmptyInteractionPayload;
import com.sande.mythictrpg.interaction.api.ExplicitGodCallPayload;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.api.InteractionPayload;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.api.InteractionSignalType;
import com.sande.mythictrpg.interaction.api.InteractionSignalTypes;
import com.sande.mythictrpg.interaction.candidate.CandidateReasons;
import com.sande.mythictrpg.interaction.candidate.CandidateSelectionResult;
import com.sande.mythictrpg.interaction.candidate.CandidateTraceMode;
import com.sande.mythictrpg.interaction.candidate.GodCandidateSelector;
import com.sande.mythictrpg.interaction.context.InteractionContext;
import com.sande.mythictrpg.interaction.director.GodInteractionDirector;
import com.sande.mythictrpg.interaction.director.InteractionDecision;
import com.sande.mythictrpg.interaction.policy.CooldownView;
import com.sande.mythictrpg.interaction.policy.InteractionRuntimeView;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseThreeBGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ConditionTreeParser CONDITION_PARSER =
            new ConditionTreeParser(ConditionTypeRegistry.INSTANCE);
    private static final ResourceLocation AGRICULTURE = id("agriculture");
    private static final ResourceLocation DIRECT_REASON = id("signal_direct_match");
    private static final ResourceLocation CATEGORY_REASON = id("signal_category_match");

    private PhaseThreeBGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void spontaneousAndExplicitLegalityAreSeparated(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        ResourceLocation locked = id("phase3b_locked");
        ResourceLocation noMatch = id("phase3b_no_match");
        ResourceLocation explicitOnly = id("phase3b_explicit_only");
        ResourceLocation eligible = id("phase3b_eligible");
        Map<ResourceLocation, GodDefinition> definitions = Map.of(
                locked, god("Locked", Set.of(AGRICULTURE), always(), always()),
                noMatch, god("No Match", Set.of(AGRICULTURE), null, biome("minecraft:plains")),
                explicitOnly, god("Explicit", Set.of(AGRICULTURE), null, null),
                eligible, god("Eligible", Set.of(AGRICULTURE), null, always()));
        InteractionRule rule = rule(InteractionSignalTypes.TEST_SPONTANEOUS.id(),
                exact(locked, 10), exact(noMatch, 20), exact(explicitOnly, 30), exact(eligible, 40));

        CandidateSelectionResult spontaneous = GodCandidateSelector.INSTANCE.select(
                context(spontaneous(playerId), definitions, Set.of(), List.of(rule)),
                CandidateTraceMode.VERBOSE);
        helper.assertValueEqual(spontaneous.status(), CandidateSelectionResult.Status.CANDIDATES,
                "spontaneous candidate status");
        helper.assertValueEqual(spontaneous.candidates().stream().map(candidate -> candidate.godId()).toList(),
                List.of(eligible), "spontaneous legality filters");
        Set<ResourceLocation> rejected = spontaneous.trace().orElseThrow().entries().stream()
                .flatMap(entry -> entry.rejectionReason().stream()).collect(Collectors.toSet());
        helper.assertTrue(rejected.contains(CandidateReasons.NOT_EFFECTIVELY_UNLOCKED),
                "locked God rejection trace");
        helper.assertTrue(rejected.contains(CandidateReasons.APPEARANCE_NO_MATCH),
                "appearance no-match rejection trace");
        helper.assertTrue(rejected.contains(CandidateReasons.EXPLICIT_ONLY),
                "explicit-only rejection trace");

        CandidateSelectionResult explicit = GodCandidateSelector.INSTANCE.select(
                context(explicit(playerId, explicitOnly), definitions, Set.of(), List.of()),
                CandidateTraceMode.NONE);
        helper.assertValueEqual(explicit.candidates().getFirst().godId(), explicitOnly,
                "explicit target bypasses automatic appearance condition only");
        helper.assertValueEqual(GodCandidateSelector.INSTANCE.select(
                        context(explicit(playerId, locked), definitions, Set.of(), List.of()),
                        CandidateTraceMode.NONE).status(),
                CandidateSelectionResult.Status.NO_CANDIDATE, "explicit locked God");
        helper.assertValueEqual(GodCandidateSelector.INSTANCE.select(
                        context(explicit(playerId, id("missing")), definitions, Set.of(), List.of()),
                        CandidateTraceMode.NONE).reason().orElseThrow(),
                CandidateReasons.DEFINITION_UNAVAILABLE, "unknown explicit target");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void rankingShortlistAndSeedKindsAreDeterministic(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        Map<ResourceLocation, GodDefinition> definitions = new LinkedHashMap<>();
        for (int index = 0; index < 10; index++) {
            ResourceLocation godId = id("phase3b_rank_" + index);
            definitions.put(godId, god("Rank " + index, Set.of(AGRICULTURE), null, always()));
        }
        ResourceLocation boosted = id("phase3b_rank_9");
        InteractionRule mixed = rule(InteractionSignalTypes.TEST_SPONTANEOUS.id(),
                category(AGRICULTURE, 10), exact(boosted, 5));
        CandidateSelectionResult result = GodCandidateSelector.INSTANCE.select(
                context(spontaneous(playerId), definitions, Set.of(), List.of(mixed)),
                CandidateTraceMode.NONE);

        helper.assertValueEqual(result.candidates().size(), CandidateSelectionResult.MAX_SHORTLIST,
                "shortlist bound");
        helper.assertValueEqual(result.candidates().getFirst().godId(), boosted,
                "higher binding score ranks first");
        helper.assertValueEqual(result.candidates().get(1).godId(), id("phase3b_rank_0"),
                "God ID ascending tie-break");
        helper.assertTrue(result.candidates().stream().allMatch(candidate ->
                        candidate.reasonIds().contains(CATEGORY_REASON)),
                "category binding reason");

        ResourceLocation exactOnly = id("phase3b_rank_4");
        InteractionRule exactRule = rule(InteractionSignalTypes.TEST_SPONTANEOUS.id(), exact(exactOnly, 7));
        CandidateSelectionResult exactResult = GodCandidateSelector.INSTANCE.select(
                context(spontaneous(playerId), definitions, Set.of(), List.of(exactRule)),
                CandidateTraceMode.NONE);
        helper.assertValueEqual(exactResult.candidates().stream().map(candidate -> candidate.godId()).toList(),
                List.of(exactOnly), "exact God seed");

        InteractionSignalType<EmptyInteractionPayload> unmappedType = new InteractionSignalType<>(
                id("unmapped_spontaneous"), InteractionMode.SPONTANEOUS, EmptyInteractionPayload.class);
        InteractionSignal<EmptyInteractionPayload> unmapped = new InteractionSignal<>(
                unmappedType, playerId, Set.of(), EmptyInteractionPayload.INSTANCE);
        long before = GodAppearanceService.INSTANCE.totalConditionEvaluations();
        CandidateSelectionResult unmappedResult = GodCandidateSelector.INSTANCE.select(
                context(unmapped, definitions, Set.of(), List.of(mixed)), CandidateTraceMode.NONE);
        helper.assertValueEqual(unmappedResult.reason().orElseThrow(), CandidateReasons.NO_SIGNAL_BINDING,
                "unmapped signal result");
        helper.assertValueEqual(GodAppearanceService.INSTANCE.totalConditionEvaluations() - before, 0L,
                "unmapped signal appearance evaluations");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void traceAndDirectorPlanningAreSideEffectFree(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID playerId = UUID.randomUUID();
        ResourceLocation godId = id("phase3b_side_effect_probe");
        PlayerMythDataService players = PlayerMythDataService.get(server);
        PlayerMythProfile profile = players.ensureProfile(playerId);
        PlayerGodKnowledgeService knowledge = PlayerGodKnowledgeService.get(server);
        GodKnowledgeSnapshot knowledgeBefore = knowledge.snapshot(playerId, godId);
        Set<ResourceLocation> worldBefore = MythicWorldState.get(server).unlockedGods();
        Map<ResourceLocation, GodDefinition> definitions = Map.of(
                godId, god("Probe", Set.of(AGRICULTURE), null, always()));
        InteractionRule rule = rule(InteractionSignalTypes.TEST_SPONTANEOUS.id(), exact(godId, 50));
        InteractionContext context = context(spontaneous(playerId), definitions, Set.of(),
                List.of(rule), Map.of(playerId, profile), CooldownView.NONE, InteractionRuntimeView.AVAILABLE);

        try {
            CandidateSelectionResult withoutTrace = GodCandidateSelector.INSTANCE.select(
                    context, CandidateTraceMode.NONE);
            CandidateSelectionResult withTrace = GodCandidateSelector.INSTANCE.select(
                    context, CandidateTraceMode.VERBOSE);
            helper.assertValueEqual(withTrace.status(), withoutTrace.status(), "trace status equality");
            helper.assertValueEqual(withTrace.candidates(), withoutTrace.candidates(),
                    "trace selection equality");
            helper.assertTrue(withoutTrace.trace().isEmpty() && withTrace.trace().isPresent(),
                    "trace allocation mode");

            InteractionDecision decision = GodInteractionDirector.INSTANCE.plan(context, withTrace);
            helper.assertValueEqual(decision.status(), InteractionDecision.Status.START,
                    "deterministic Director decision");
            helper.assertValueEqual(decision.plan().orElseThrow().participants().primaryGodId(), godId,
                    "Director primary");
            helper.assertTrue(decision.plan().orElseThrow().participants().secondaryGodIds().isEmpty(),
                    "Director must not auto-select secondary Gods");
            helper.assertValueEqual(decision.plan().orElseThrow().revisions().godDefinitionGeneration(), 7L,
                    "plan God generation");
            helper.assertValueEqual(decision.plan().orElseThrow().revisions()
                            .interactionRuleGeneration().orElseThrow(), 11L,
                    "plan rule generation");
            helper.assertValueEqual(knowledge.snapshot(playerId, godId), knowledgeBefore,
                    "selection/Director changed God knowledge");
            helper.assertValueEqual(MythicWorldState.get(server).unlockedGods(), worldBefore,
                    "selection/Director changed world state");

            CandidateSelectionResult noCandidates = new CandidateSelectionResult(
                    CandidateSelectionResult.Status.NO_CANDIDATE, List.of(), 7, 11,
                    Optional.of(CandidateReasons.NO_ELIGIBLE_CANDIDATE), Optional.empty());
            helper.assertValueEqual(GodInteractionDirector.INSTANCE.plan(context, noCandidates).status(),
                    InteractionDecision.Status.NO_START, "Director candidate-zero policy");
            CandidateSelectionResult stale = new CandidateSelectionResult(
                    CandidateSelectionResult.Status.CANDIDATES, withTrace.candidates(), 8, 11,
                    Optional.empty(), Optional.empty());
            helper.assertValueEqual(GodInteractionDirector.INSTANCE.plan(context, stale).reason().orElseThrow(),
                    CandidateReasons.STALE_SELECTION, "stale generation rejection");
        } finally {
            players.setParticipationStatus(playerId, ParticipationStatus.ARCHIVED);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void cooldownAndRuntimeViewsOnlyBlockPlanning(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        ResourceLocation godId = id("phase3b_runtime_probe");
        ResourceLocation cooldownReason = id("cooldown");
        ResourceLocation runtimeReason = id("interaction_busy");
        Map<ResourceLocation, GodDefinition> definitions = Map.of(
                godId, god("Runtime", Set.of(AGRICULTURE), null, always()));
        InteractionRule rule = rule(InteractionSignalTypes.TEST_SPONTANEOUS.id(), exact(godId, 1));
        CooldownView blocked = (player, god, mode) -> Optional.of(cooldownReason);
        CandidateSelectionResult cooldownResult = GodCandidateSelector.INSTANCE.select(
                context(spontaneous(playerId), definitions, Set.of(), List.of(rule),
                        Map.of(playerId, PlayerMythProfile.createActive()), blocked,
                        InteractionRuntimeView.AVAILABLE), CandidateTraceMode.VERBOSE);
        helper.assertValueEqual(cooldownResult.status(), CandidateSelectionResult.Status.NO_CANDIDATE,
                "cooldown filter status");
        helper.assertValueEqual(cooldownResult.trace().orElseThrow().entries().getFirst()
                .rejectionReason().orElseThrow(), cooldownReason, "cooldown filter reason");

        InteractionRuntimeView runtime = new InteractionRuntimeView() {
            @Override
            public Optional<ResourceLocation> planningBlockReason(UUID player, InteractionMode mode) {
                return Optional.of(runtimeReason);
            }
        };
        InteractionContext runtimeContext = context(spontaneous(playerId), definitions, Set.of(), List.of(rule),
                Map.of(playerId, PlayerMythProfile.createActive()), CooldownView.NONE, runtime);
        CandidateSelectionResult candidates = GodCandidateSelector.INSTANCE.select(
                runtimeContext, CandidateTraceMode.NONE);
        helper.assertValueEqual(GodInteractionDirector.INSTANCE.plan(runtimeContext, candidates)
                .reason().orElseThrow(), runtimeReason, "Director runtime block");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void interactionRuleReloadIsTransactional(GameTestHelper helper) {
        InteractionRuleManager manager = InteractionRuleManager.INSTANCE;
        InteractionRuleManager.RuleSnapshot previous = manager.snapshot();
        ResourceManager resources = helper.getLevel().getServer().getResourceManager();
        ResourceLocation validFile = ResourceLocation.fromNamespaceAndPath(
                "phase3btest", "mythictrpg/interaction_rules/valid.json");
        String validJson = "{\"schema_version\":1,\"signal\":\"mythictrpg:test_spontaneous\","
                + "\"bindings\":[{\"god\":\"missingaddon:future_god\",\"score\":10,"
                + "\"reason\":\"phase3btest:future_binding\"}]}";
        try {
            InteractionRuleManager.Prepared prepared = manager.prepare(
                    new OverlayResourceManager(resources, validFile, validJson), InactiveProfiler.INSTANCE);
            manager.apply(prepared, resources, InactiveProfiler.INSTANCE);
            helper.assertValueEqual(manager.snapshot().rules().size(), 3,
                    "valid interaction rule reload must retain production and fixture rules");
            helper.assertValueEqual(manager.snapshot().rules().stream()
                            .map(InteractionRule::signalType).collect(Collectors.toSet()),
                    Set.of(id("demeter_harvest"), id("fortuna_deep_sea_heart_reminder"),
                            InteractionSignalTypes.TEST_SPONTANEOUS.id()),
                    "valid interaction rule reload did not preserve distinct production and fixture signals");
            helper.assertTrue(manager.snapshot().seedIndex().resolve(
                            InteractionSignalTypes.TEST_SPONTANEOUS.id(), emptyGodSnapshot())
                    .unresolvedExactGods().contains(ResourceLocation.parse("missingaddon:future_god")),
                    "unknown exact God binding must remain unresolved, not remapped");

            InteractionRuleManager.RuleSnapshot committed = manager.snapshot();
            ResourceLocation invalidFile = ResourceLocation.fromNamespaceAndPath(
                    "phase3btest", "mythictrpg/interaction_rules/invalid.json");
            String invalidJson = "{\"schema_version\":1,\"signal\":\"mythictrpg:test_spontaneous\","
                    + "\"bindings\":[{\"god\":\"mythictrpg:demeter\","
                    + "\"god_category\":\"mythictrpg:agriculture\",\"score\":10,"
                    + "\"reason\":\"phase3btest:invalid\"}]}";
            try {
                manager.prepare(new OverlayResourceManager(resources, invalidFile, invalidJson),
                        InactiveProfiler.INSTANCE);
                helper.fail("Invalid interaction rule reload was accepted");
            } catch (IllegalStateException expected) {
                helper.assertTrue(manager.snapshot() == committed,
                        "invalid reload replaced the committed rule snapshot");
            }
        } finally {
            manager.apply(new InteractionRuleManager.Prepared(previous.rules(), previous.seedIndex()),
                    resources, InactiveProfiler.INSTANCE);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void eightHundredGodsEvaluateOnlyThreeRelevantSeeds(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        ResourceLocation relevantCategory = id("phase3b_relevant");
        ResourceLocation irrelevantCategory = id("phase3b_irrelevant");
        Map<ResourceLocation, GodDefinition> definitions = new LinkedHashMap<>();
        for (int index = 0; index < 800; index++) {
            Set<ResourceLocation> categories = index < 3
                    ? Set.of(relevantCategory) : Set.of(irrelevantCategory);
            definitions.put(id("phase3b_800_" + index),
                    god("God " + index, categories, null, always()));
        }
        InteractionRule rule = rule(InteractionSignalTypes.TEST_SPONTANEOUS.id(),
                category(relevantCategory, 10));
        long before = GodAppearanceService.INSTANCE.totalConditionEvaluations();
        CandidateSelectionResult result = GodCandidateSelector.INSTANCE.select(
                context(spontaneous(playerId), definitions, Set.of(), List.of(rule)),
                CandidateTraceMode.NONE);

        helper.assertValueEqual(definitions.size(), 800, "synthetic God count");
        helper.assertValueEqual(result.candidates().size(), 3, "rule seed count");
        helper.assertValueEqual(GodAppearanceService.INSTANCE.totalConditionEvaluations() - before, 3L,
                "deep appearance evaluation count");
        helper.succeed();
    }

    private static InteractionContext context(InteractionSignal<?> signal,
            Map<ResourceLocation, GodDefinition> definitions, Set<ResourceLocation> unlocked,
            List<InteractionRule> rules) {
        return context(signal, definitions, unlocked, rules,
                Map.of(signal.initiatingPlayerId(), PlayerMythProfile.createActive()),
                CooldownView.NONE, InteractionRuntimeView.AVAILABLE);
    }

    private static InteractionContext context(InteractionSignal<?> signal,
            Map<ResourceLocation, GodDefinition> definitions, Set<ResourceLocation> unlocked,
            List<InteractionRule> rules, Map<UUID, PlayerMythProfile> profiles,
            CooldownView cooldowns, InteractionRuntimeView runtime) {
        GodDefinitionManager.ProgressionSnapshot gods = snapshot(definitions);
        InteractionRuleManager.RuleSnapshot ruleSnapshot = new InteractionRuleManager.RuleSnapshot(
                rules, InteractionRuleSeedIndex.build(rules), 11);
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
                return profiles.entrySet().stream()
                        .filter(entry -> entry.getValue().participationStatus() == ParticipationStatus.ACTIVE)
                        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
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
                return gods.categoryIndex().godsInCategory(categoryId);
            }
        };
        ConditionEnvironment environment = new ConditionEnvironment(
                Level.OVERWORLD, BlockPos.ZERO, Optional.of(id("test_biome")), 6000);
        ConditionContext conditions = new ConditionContext(world, players,
                () -> Set.copyOf(profiles.keySet()), godView,
                Optional.of(signal.initiatingPlayerId()), Optional.of(environment),
                Optional.<ConditionEventContext>empty());
        return new InteractionContext(signal, Set.of(), Optional.of(environment), conditions,
                profiles, gods, ruleSnapshot, cooldowns, runtime, true, Optional.empty());
    }

    private static GodDefinitionManager.ProgressionSnapshot snapshot(
            Map<ResourceLocation, GodDefinition> definitions) {
        Map<ResourceLocation, GodDefinition> immutable = Map.copyOf(definitions);
        return new GodDefinitionManager.ProgressionSnapshot(immutable,
                GodUnlockDependencyIndex.build(immutable), GodAppearanceIndex.build(immutable),
                GodCategoryIndex.build(immutable), 7);
    }

    private static GodDefinitionManager.ProgressionSnapshot emptyGodSnapshot() {
        return snapshot(Map.of());
    }

    private static InteractionSignal<EmptyInteractionPayload> spontaneous(UUID playerId) {
        return new InteractionSignal<>(InteractionSignalTypes.TEST_SPONTANEOUS,
                playerId, Set.of(), EmptyInteractionPayload.INSTANCE);
    }

    private static InteractionSignal<ExplicitGodCallPayload> explicit(UUID playerId, ResourceLocation godId) {
        return new InteractionSignal<>(InteractionSignalTypes.EXPLICIT_GOD_CALL, playerId, Set.of(),
                new ExplicitGodCallPayload(godId, InteractionSignalTypes.PLAYER_EXPLICIT_POLICY));
    }

    private static InteractionRule rule(ResourceLocation signal, InteractionRuleBinding... bindings) {
        return new InteractionRule(InteractionRule.CURRENT_SCHEMA_VERSION, signal, List.of(bindings));
    }

    private static InteractionRuleBinding exact(ResourceLocation godId, int score) {
        return new InteractionRuleBinding(Optional.of(godId), Optional.empty(), score, DIRECT_REASON);
    }

    private static InteractionRuleBinding category(ResourceLocation categoryId, int score) {
        return new InteractionRuleBinding(Optional.empty(), Optional.of(categoryId), score, CATEGORY_REASON);
    }

    private static GodDefinition god(String name, Set<ResourceLocation> categories,
            String unlock, String appearance) {
        return new GodDefinition(2, Component.literal(name), id("test_origin"), id("test_faction"), categories,
                condition(unlock), condition(appearance), Optional.empty());
    }

    private static Optional<ConditionNode> condition(String json) {
        return json == null ? Optional.empty()
                : Optional.of(CONDITION_PARSER.parse(JsonParser.parseString(json)));
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

    private static final class OverlayResourceManager implements ResourceManager {
        private final ResourceManager delegate;
        private final ResourceLocation file;
        private final Resource resource;

        private OverlayResourceManager(ResourceManager delegate, ResourceLocation file, String json) {
            this.delegate = delegate;
            this.file = file;
            PackResources source = delegate.listPacks().findFirst().orElseThrow();
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            this.resource = new Resource(source, () -> new ByteArrayInputStream(bytes));
        }

        @Override
        public Optional<Resource> getResource(ResourceLocation location) {
            return location.equals(file) ? Optional.of(resource) : delegate.getResource(location);
        }

        @Override
        public Set<String> getNamespaces() {
            Set<String> result = new LinkedHashSet<>(delegate.getNamespaces());
            result.add(file.getNamespace());
            return Set.copyOf(result);
        }

        @Override
        public List<Resource> getResourceStack(ResourceLocation location) {
            return location.equals(file) ? List.of(resource) : delegate.getResourceStack(location);
        }

        @Override
        public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
            Map<ResourceLocation, Resource> result = new LinkedHashMap<>(delegate.listResources(path, filter));
            if (file.getPath().startsWith(path) && filter.test(file)) {
                result.put(file, resource);
            }
            return result;
        }

        @Override
        public Map<ResourceLocation, List<Resource>> listResourceStacks(
                String path, Predicate<ResourceLocation> filter) {
            Map<ResourceLocation, List<Resource>> result = new LinkedHashMap<>(
                    delegate.listResourceStacks(path, filter));
            if (file.getPath().startsWith(path) && filter.test(file)) {
                result.put(file, List.of(resource));
            }
            return result;
        }

        @Override
        public Stream<PackResources> listPacks() {
            return delegate.listPacks();
        }
    }
}
