package com.sande.mythictrpg.data.god;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionContext;
import com.sande.mythictrpg.condition.api.ConditionEnvironment;
import com.sande.mythictrpg.condition.api.ConditionEventContext;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.api.GodDefinitionView;
import com.sande.mythictrpg.condition.api.WorldStateView;
import com.sande.mythictrpg.condition.engine.ConditionTreeParser;
import com.sande.mythictrpg.condition.registry.ConditionTypeRegistry;
import com.sande.mythictrpg.data.player.GodKnowledgeSnapshot;
import com.sande.mythictrpg.data.player.KnowledgeMutationResult;
import com.sande.mythictrpg.data.player.ParticipationStatus;
import com.sande.mythictrpg.data.player.PlayerGodKnowledgeService;
import com.sande.mythictrpg.data.player.PlayerMythDataRepository;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import com.sande.mythictrpg.data.player.PlayerMythQueryService;
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
public final class PhaseTwoDGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ConditionTreeParser PARSER = new ConditionTreeParser(ConditionTypeRegistry.INSTANCE);
    private static final ResourceLocation CHERRY_GROVE = minecraft("cherry_grove");
    private static final ResourceLocation PERSISTENCE_GOD = id("phase2d_knowledge_persistence");
    private static final UUID PERSISTENCE_ENCOUNTER_PLAYER = UUID.fromString(
            "9012bbca-8f2c-4b8c-a9a4-fd8e03a30c40");
    private static final UUID PERSISTENCE_IDENTIFY_PLAYER = UUID.fromString(
            "942d408a-c010-4ed9-927a-949aa51420a8");

    private PhaseTwoDGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void appearancePoliciesAndEnvironmentAreLazy(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID playerId = UUID.randomUUID();
        PlayerMythDataService.get(server).ensureProfile(playerId);
        String suffix = suffix();
        ResourceLocation locked = id("phase2d_locked_" + suffix);
        ResourceLocation notRequired = id("phase2d_not_required_" + suffix);
        ResourceLocation explicitlyUnlocked = id("phase2d_unlocked_" + suffix);
        ResourceLocation noMatch = id("phase2d_no_match_" + suffix);
        ResourceLocation unknown = id("phase2d_unknown_" + suffix);
        ResourceLocation explicitOnly = id("phase2d_explicit_" + suffix);
        ResourceLocation environmentGod = id("phase2d_environment_" + suffix);
        Map<ResourceLocation, GodDefinition> definitions = Map.of(
                locked, god("Locked", always(), always(), null),
                notRequired, god("Not Required", null, always(), null),
                explicitlyUnlocked, god("Unlocked", always(), always(), null),
                noMatch, god("No Match", null, biome("minecraft:plains"), null),
                unknown, god("Unknown", null, biome("minecraft:cherry_grove"), null),
                explicitOnly, god("Explicit", null, null, null),
                environmentGod, god("Environment", null, all(
                        biome("minecraft:cherry_grove"), time(5000, 7000), yMinimum(100)), null)
        );
        GodDefinitionManager.ProgressionSnapshot snapshot = snapshot(definitions);
        GodAppearanceService service = GodAppearanceService.INSTANCE;
        ConditionContext noEnvironment = context(definitions, Set.of(explicitlyUnlocked),
                Optional.of(playerId), Optional.empty());

        helper.assertValueEqual(service.evaluateAutomaticAppearance(snapshot, noEnvironment, true, locked).reason(),
                AppearanceEvaluationReason.NOT_EFFECTIVELY_UNLOCKED, "locked appearance");
        helper.assertTrue(service.evaluateAutomaticAppearance(snapshot, noEnvironment, true, notRequired).eligible(),
                "NOT_REQUIRED God did not evaluate appearance");
        helper.assertTrue(service.evaluateAutomaticAppearance(
                snapshot, noEnvironment, true, explicitlyUnlocked).eligible(),
                "Explicitly unlocked God was not eligible");
        helper.assertValueEqual(service.evaluateAutomaticAppearance(
                        snapshot, noEnvironment, true, unknown).conditionResult().orElseThrow(),
                ConditionResult.UNKNOWN, "missing appearance environment");
        helper.assertValueEqual(service.evaluateAutomaticAppearance(
                        snapshot, noEnvironment, true, explicitOnly).reason(),
                AppearanceEvaluationReason.EXPLICIT_ONLY, "EXPLICIT_ONLY appearance");
        helper.assertValueEqual(service.evaluateAutomaticAppearance(
                        snapshot, noEnvironment, false, notRequired).reason(),
                AppearanceEvaluationReason.PLAYER_NOT_ACTIVE, "ARCHIVED appearance");

        ConditionContext matching = context(definitions, Set.of(explicitlyUnlocked), Optional.of(playerId),
                Optional.of(environment(CHERRY_GROVE, 6000, 120)));
        helper.assertValueEqual(service.evaluateAutomaticAppearance(snapshot, matching, true, noMatch).reason(),
                AppearanceEvaluationReason.CONDITION_NO_MATCH, "NO_MATCH appearance");
        helper.assertTrue(service.evaluateAutomaticAppearance(snapshot, matching, true, environmentGod).eligible(),
                "matching biome/time/Y appearance");
        helper.assertTrue(!service.evaluateAutomaticAppearance(snapshot,
                context(definitions, Set.of(), Optional.of(playerId),
                        Optional.of(environment(minecraft("plains"), 6000, 120))), true, environmentGod).eligible(),
                "biome change did not alter appearance");
        helper.assertTrue(!service.evaluateAutomaticAppearance(snapshot,
                context(definitions, Set.of(), Optional.of(playerId),
                        Optional.of(environment(CHERRY_GROVE, 7000, 120))), true, environmentGod).eligible(),
                "time change did not alter appearance");
        helper.assertTrue(!service.evaluateAutomaticAppearance(snapshot,
                context(definitions, Set.of(), Optional.of(playerId),
                        Optional.of(environment(CHERRY_GROVE, 6000, 99))), true, environmentGod).eligible(),
                "Y change did not alter appearance");

        List<ResourceLocation> candidates = service.getAutomaticallyEligibleGods(
                snapshot, noEnvironment, true);
        helper.assertValueEqual(Set.copyOf(candidates), Set.of(notRequired, explicitlyUnlocked),
                "lazy automatic candidate list");
        helper.assertValueEqual(PlayerGodKnowledgeService.get(server).snapshot(playerId, notRequired),
                new GodKnowledgeSnapshot(false, false), "appearance must not persist knowledge");
        PlayerMythDataService.get(server).setParticipationStatus(playerId, ParticipationStatus.ARCHIVED);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void allKnowledgeCombinationsRemainIndependent(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PlayerMythDataService players = PlayerMythDataService.get(server);
        PlayerGodKnowledgeService knowledge = PlayerGodKnowledgeService.get(server);
        ResourceLocation godId = id("phase2d_knowledge_" + suffix());
        Map<ResourceLocation, GodDefinition> definitions = Map.of(
                godId, god("Known Name", null, null, null));
        GodDefinitionManager.ProgressionSnapshot previous = install(helper, definitions);
        UUID neither = UUID.randomUUID();
        UUID identifiedOnly = UUID.randomUUID();
        UUID encounteredOnly = UUID.randomUUID();
        UUID both = UUID.randomUUID();
        List<UUID> playerIds = List.of(neither, identifiedOnly, encounteredOnly, both);
        playerIds.forEach(players::ensureProfile);

        try {
            players.setParticipationStatus(identifiedOnly, ParticipationStatus.ARCHIVED);
            helper.assertValueEqual(knowledge.identifyGod(identifiedOnly, godId),
                    KnowledgeMutationResult.NEW_RECORD, "identify ARCHIVED profile");
            helper.assertValueEqual(knowledge.recordEncounter(encounteredOnly, godId),
                    KnowledgeMutationResult.NEW_RECORD, "record encounter");
            knowledge.recordEncounter(both, godId);
            knowledge.identifyGod(both, godId);

            helper.assertValueEqual(knowledge.snapshot(neither, godId),
                    new GodKnowledgeSnapshot(false, false), "neither state");
            helper.assertValueEqual(knowledge.snapshot(identifiedOnly, godId),
                    new GodKnowledgeSnapshot(false, true), "identified without encounter");
            helper.assertValueEqual(knowledge.snapshot(encounteredOnly, godId),
                    new GodKnowledgeSnapshot(true, false), "encountered without identification");
            helper.assertValueEqual(knowledge.snapshot(both, godId),
                    new GodKnowledgeSnapshot(true, true), "encountered and identified");

            helper.assertValueEqual(knowledge.identifyGod(identifiedOnly, godId),
                    KnowledgeMutationResult.ALREADY_RECORDED, "duplicate identification");
            helper.assertValueEqual(knowledge.recordEncounter(encounteredOnly, godId),
                    KnowledgeMutationResult.ALREADY_RECORDED, "duplicate encounter");
            helper.assertValueEqual(knowledge.recordEncounter(neither, id("missing_" + suffix())),
                    KnowledgeMutationResult.UNKNOWN_GOD, "unknown God encounter");
            helper.assertValueEqual(knowledge.identifyGod(neither, id("missing_" + suffix())),
                    KnowledgeMutationResult.UNKNOWN_GOD, "unknown God identification");

            helper.assertValueEqual(GodIdentityService.INSTANCE
                    .getDisplayName(server, encounteredOnly, godId).getString(), "???",
                    "encountered unknown display");
            helper.assertValueEqual(GodIdentityService.INSTANCE
                    .getDisplayName(server, identifiedOnly, godId).getString(), "Known Name",
                    "identified display");
            helper.assertValueEqual(GodIdentityService.INSTANCE
                    .getDisplayName(server, both, godId).getString(), "Known Name",
                    "identified encountered display");

            installWithoutSavingPrevious(helper, Map.of());
            helper.assertValueEqual(knowledge.snapshot(identifiedOnly, godId),
                    new GodKnowledgeSnapshot(false, true), "removed God knowledge preservation");
            helper.assertValueEqual(GodIdentityService.INSTANCE
                    .getDisplayName(server, identifiedOnly, godId).getString(), "Unknown God",
                    "missing definition display fallback");
            helper.assertValueEqual(players.find(identifiedOnly).orElseThrow().dataVersion(), 4,
                    "profile dataVersion remains 4");
        } finally {
            playerIds.forEach(playerId -> players.setParticipationStatus(playerId, ParticipationStatus.ARCHIVED));
            restore(helper, previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void identificationEligibilityDoesNotPersist(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID playerId = UUID.randomUUID();
        PlayerMythDataService.get(server).ensureProfile(playerId);
        String suffix = suffix();
        ResourceLocation eligible = id("phase2d_identify_eligible_" + suffix);
        ResourceLocation explicit = id("phase2d_identify_explicit_" + suffix);
        ResourceLocation noMatch = id("phase2d_identify_no_match_" + suffix);
        ResourceLocation unknown = id("phase2d_identify_unknown_" + suffix);
        Map<ResourceLocation, GodDefinition> definitions = Map.of(
                eligible, god("Eligible", null, null, always()),
                explicit, god("Explicit", null, null, null),
                noMatch, god("No Match", null, null, biome("minecraft:plains")),
                unknown, god("Unknown", null, null, biome("minecraft:cherry_grove"))
        );
        GodDefinitionManager.ProgressionSnapshot snapshot = snapshot(definitions);
        ConditionContext matching = context(definitions, Set.of(), Optional.of(playerId),
                Optional.of(environment(CHERRY_GROVE, 6000, 120)));
        ConditionContext noEnvironment = context(definitions, Set.of(), Optional.of(playerId), Optional.empty());
        GodIdentificationService service = GodIdentificationService.INSTANCE;

        helper.assertTrue(service.evaluateEligibility(snapshot, matching, true, eligible).eligible(),
                "identification eligibility unexpectedly required encounter");
        helper.assertValueEqual(service.evaluateEligibility(snapshot, matching, true, explicit).reason(),
                IdentificationEvaluationReason.EXPLICIT_ONLY, "explicit identification policy");
        helper.assertValueEqual(service.evaluateEligibility(snapshot, matching, true, noMatch).reason(),
                IdentificationEvaluationReason.CONDITION_NO_MATCH, "identification NO_MATCH");
        helper.assertValueEqual(service.evaluateEligibility(snapshot, noEnvironment, true, unknown).reason(),
                IdentificationEvaluationReason.CONDITION_UNKNOWN, "identification UNKNOWN");
        helper.assertValueEqual(service.evaluateEligibility(snapshot, matching, false, eligible).reason(),
                IdentificationEvaluationReason.PLAYER_NOT_ACTIVE, "ARCHIVED identification eligibility");
        helper.assertValueEqual(PlayerGodKnowledgeService.get(server).snapshot(playerId, eligible),
                new GodKnowledgeSnapshot(false, false), "eligibility must not persist identification");
        PlayerMythDataService.get(server).setParticipationStatus(playerId, ParticipationStatus.ARCHIVED);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void appearanceIndexReloadIsTransactional(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GodDefinitionManager manager = GodDefinitionManager.INSTANCE;
        GodDefinitionManager.ProgressionSnapshot previous = manager.progressionSnapshot();
        String suffix = suffix();
        ResourceLocation validFile = ResourceLocation.fromNamespaceAndPath(
                "phase2dtest", "mythictrpg/gods/appearance_" + suffix + ".json");
        ResourceLocation validId = ResourceLocation.fromNamespaceAndPath("phase2dtest", "appearance_" + suffix);

        try {
            GodDefinitionManager.Prepared prepared = manager.prepare(new OverlayResourceManager(
                    server.getResourceManager(), validFile, godJson(",\"appearance_conditions\":" + always())),
                    InactiveProfiler.INSTANCE);
            manager.apply(prepared, server.getResourceManager(), InactiveProfiler.INSTANCE);
            helper.assertTrue(manager.appearanceIndex().conditionalGods().contains(validId),
                    "valid reload did not update appearance index");

            Map<ResourceLocation, GodDefinition> committedDefinitions = manager.definitions();
            GodAppearanceIndex committedAppearanceIndex = manager.appearanceIndex();
            long committedGeneration = manager.generation();
            ResourceLocation invalidFile = ResourceLocation.fromNamespaceAndPath(
                    "phase2dtest", "mythictrpg/gods/invalid_" + suffix + ".json");
            try {
                manager.prepare(new OverlayResourceManager(server.getResourceManager(), invalidFile,
                        godJson(",\"appearance_conditions\":{\"type\":\"phase2dtest:missing\"}")),
                        InactiveProfiler.INSTANCE);
                helper.fail("Invalid appearance reload was accepted");
            } catch (IllegalStateException expected) {
                helper.assertTrue(manager.definitions() == committedDefinitions,
                        "rejected reload replaced God cache");
                helper.assertTrue(manager.appearanceIndex() == committedAppearanceIndex,
                        "rejected reload replaced appearance index");
                helper.assertValueEqual(manager.generation(), committedGeneration,
                        "rejected reload changed generation");
            }
        } finally {
            restore(helper, previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void appearanceIndexFiltersEightHundredGods(GameTestHelper helper) {
        String suffix = suffix();
        ResourceLocation relevant = id("phase2d_800_relevant_" + suffix);
        Map<ResourceLocation, GodDefinition> definitions = new LinkedHashMap<>();
        definitions.put(relevant, god("Relevant", null, always(), null));
        for (int index = 0; index < 799; index++) {
            definitions.put(id("phase2d_800_" + suffix + "_" + index),
                    god("No Appearance " + index, null, null, null));
        }
        GodDefinitionManager.ProgressionSnapshot snapshot = snapshot(definitions);
        GodAppearanceService service = GodAppearanceService.INSTANCE;
        long before = service.totalConditionEvaluations();
        List<ResourceLocation> eligible = service.getAutomaticallyEligibleGods(
                snapshot, context(definitions, Set.of(), Optional.of(UUID.randomUUID()), Optional.empty()), true);

        helper.assertValueEqual(definitions.size(), 800, "synthetic God count");
        helper.assertValueEqual(snapshot.appearanceIndex().conditionalGods().size(), 1,
                "appearance index candidate count");
        helper.assertValueEqual(eligible, List.of(relevant), "eligible appearance list");
        helper.assertValueEqual(service.totalConditionEvaluations() - before, 1L,
                "appearance condition evaluation count");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void independentKnowledgePersistsAcrossRestart(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PlayerMythDataService players = PlayerMythDataService.get(server);
        players.ensureProfile(PERSISTENCE_ENCOUNTER_PLAYER);
        players.ensureProfile(PERSISTENCE_IDENTIFY_PLAYER);
        PlayerGodKnowledgeService knowledge = PlayerGodKnowledgeService.get(server);
        GodDefinitionManager.ProgressionSnapshot previous = install(helper, Map.of(
                PERSISTENCE_GOD, god("Persistence Name", null, null, null)));
        GodKnowledgeSnapshot encounterBefore = knowledge.snapshot(PERSISTENCE_ENCOUNTER_PLAYER, PERSISTENCE_GOD);
        GodKnowledgeSnapshot identifyBefore = knowledge.snapshot(PERSISTENCE_IDENTIFY_PLAYER, PERSISTENCE_GOD);
        boolean loadedFromPreviousRun = encounterBefore.encountered() && !encounterBefore.identified()
                && !identifyBefore.encountered() && identifyBefore.identified();

        try {
            if (!encounterBefore.encountered()) {
                knowledge.recordEncounter(PERSISTENCE_ENCOUNTER_PLAYER, PERSISTENCE_GOD);
            }
            if (!identifyBefore.identified()) {
                knowledge.identifyGod(PERSISTENCE_IDENTIFY_PLAYER, PERSISTENCE_GOD);
            }
            helper.assertValueEqual(knowledge.snapshot(PERSISTENCE_ENCOUNTER_PLAYER, PERSISTENCE_GOD),
                    new GodKnowledgeSnapshot(true, false), "persistent encounter-only state");
            helper.assertValueEqual(knowledge.snapshot(PERSISTENCE_IDENTIFY_PLAYER, PERSISTENCE_GOD),
                    new GodKnowledgeSnapshot(false, true), "persistent identify-only state");
            helper.assertValueEqual(PlayerMythDataRepository.get(server).dataVersion(), 1,
                    "repository dataVersion");
            helper.assertValueEqual(players.find(PERSISTENCE_ENCOUNTER_PLAYER).orElseThrow().dataVersion(), 4,
                    "profile dataVersion");
            server.saveEverything(false, true, false);
            MythicTrpg.LOGGER.info(loadedFromPreviousRun
                    ? "PHASE 2-D independent knowledge persistence verified after server restart."
                    : "PHASE 2-D independent knowledge persistence initialized; run GameTestServer again.");
        } finally {
            restore(helper, previous);
        }
        helper.succeed();
    }

    private static GodDefinitionManager.ProgressionSnapshot snapshot(
            Map<ResourceLocation, GodDefinition> definitions) {
        Map<ResourceLocation, GodDefinition> immutable = Map.copyOf(definitions);
        return new GodDefinitionManager.ProgressionSnapshot(immutable,
                GodUnlockDependencyIndex.build(immutable), GodAppearanceIndex.build(immutable), 1);
    }

    private static GodDefinitionManager.ProgressionSnapshot install(
            GameTestHelper helper, Map<ResourceLocation, GodDefinition> definitions) {
        GodDefinitionManager.ProgressionSnapshot previous = GodDefinitionManager.INSTANCE.progressionSnapshot();
        installWithoutSavingPrevious(helper, definitions);
        return previous;
    }

    private static void installWithoutSavingPrevious(
            GameTestHelper helper, Map<ResourceLocation, GodDefinition> definitions) {
        GodDefinitionManager manager = GodDefinitionManager.INSTANCE;
        Map<ResourceLocation, GodDefinition> immutable = Map.copyOf(definitions);
        manager.apply(new GodDefinitionManager.Prepared(immutable,
                        GodUnlockDependencyIndex.build(immutable), GodAppearanceIndex.build(immutable)),
                helper.getLevel().getServer().getResourceManager(), InactiveProfiler.INSTANCE);
    }

    private static void restore(GameTestHelper helper, GodDefinitionManager.ProgressionSnapshot previous) {
        GodDefinitionManager.INSTANCE.apply(new GodDefinitionManager.Prepared(
                        previous.definitions(), previous.unlockIndex(), previous.appearanceIndex()),
                helper.getLevel().getServer().getResourceManager(), InactiveProfiler.INSTANCE);
    }

    private static GodDefinition god(String name, String unlock, String appearance, String identification) {
        return new GodDefinition(2, Component.literal(name), id("test_origin"), id("test_faction"),
                Set.of(id("test_category")), optionalCondition(unlock), optionalCondition(appearance),
                optionalCondition(identification));
    }

    private static Optional<ConditionNode> optionalCondition(String json) {
        return json == null ? Optional.empty() : Optional.of(PARSER.parse(JsonParser.parseString(json)));
    }

    private static ConditionContext context(Map<ResourceLocation, GodDefinition> definitions,
            Set<ResourceLocation> unlocked, Optional<UUID> target, Optional<ConditionEnvironment> environment) {
        PlayerMythQueryService players = new PlayerMythQueryService() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public Optional<PlayerMythProfile> find(UUID playerId) {
                return Optional.empty();
            }

            @Override
            public Map<UUID, PlayerMythProfile> activeProfiles() {
                return Map.of();
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
        GodDefinitionView gods = new GodDefinitionView() {
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
        return new ConditionContext(world, players, Set::of, gods, target, environment,
                Optional.<ConditionEventContext>empty());
    }

    private static ConditionEnvironment environment(ResourceLocation biome, int time, int y) {
        return new ConditionEnvironment(Level.OVERWORLD, new BlockPos(0, y, 0), Optional.of(biome), time);
    }

    private static String always() {
        return "{\"type\":\"mythictrpg:always\"}";
    }

    private static String biome(String biome) {
        return "{\"type\":\"mythictrpg:biome\",\"scope\":\"player\",\"biome\":\""
                + biome + "\"}";
    }

    private static String time(int start, int end) {
        return "{\"type\":\"mythictrpg:time_range\",\"scope\":\"player\",\"start\":"
                + start + ",\"end\":" + end + "}";
    }

    private static String yMinimum(int minimum) {
        return "{\"type\":\"mythictrpg:y_range\",\"scope\":\"player\",\"minimum\":"
                + minimum + "}";
    }

    private static String all(String... conditions) {
        return "{\"type\":\"mythictrpg:all\",\"conditions\":[" + String.join(",", conditions) + "]}";
    }

    private static String godJson(String extraFields) {
        return "{\"schema_version\":2,\"display_name\":\"PHASE 2-D Reload Probe\","
                + "\"origin\":\"phase2dtest:test\",\"faction\":\"phase2dtest:test\","
                + "\"categories\":[\"phase2dtest:test\"]" + extraFields + "}";
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }

    private static ResourceLocation minecraft(String path) {
        return ResourceLocation.fromNamespaceAndPath("minecraft", path);
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
