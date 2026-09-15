package com.sande.mythictrpg.data.god;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionContext;
import com.sande.mythictrpg.condition.api.ConditionEnvironment;
import com.sande.mythictrpg.condition.api.ConditionEventContext;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.api.GodDefinitionView;
import com.sande.mythictrpg.condition.api.WorldStateView;
import com.sande.mythictrpg.condition.engine.ConditionEngine;
import com.sande.mythictrpg.condition.engine.ConditionTreeParser;
import com.sande.mythictrpg.condition.registry.ConditionTypeRegistry;
import com.sande.mythictrpg.data.player.ModAttachments;
import com.sande.mythictrpg.data.player.ParticipationStatus;
import com.sande.mythictrpg.data.player.PlayerMythDataRepository;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerGodKnowledgeService;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import com.sande.mythictrpg.data.player.PlayerMythQueryService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.neoforged.neoforge.common.util.FakePlayer;
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
public final class PhaseTwoAGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation DEMETER = id("demeter");
    private static final ResourceLocation APHRODITE = id("aphrodite");
    private static final ResourceLocation FORTUNA = id("fortuna");
    private static final ResourceLocation LOVE = id("love");
    private static final UUID RESTART_PLAYER_ID = UUID.fromString("8473bc23-a16d-4452-852d-3ac60a20fc3e");
    private static final ConditionTreeParser PARSER = new ConditionTreeParser(ConditionTypeRegistry.INSTANCE);

    private PhaseTwoAGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void definitionsPoliciesAndAdminCommands(GameTestHelper helper) {
        GodDefinitionManager manager = GodDefinitionManager.INSTANCE;
        helper.assertValueEqual(manager.definitions().size(), 4, "Exactly four God definitions must load");
        manager.definitions().forEach((godId, definition) -> {
            helper.assertValueEqual(definition.schemaVersion(), 2, godId + ": schema version");
            helper.assertValueEqual(definition.unlockPolicy(), UnlockPolicy.NOT_REQUIRED,
                    godId + ": absent unlock policy");
            AppearancePolicy expectedAppearance = godId.equals(DEMETER) || godId.equals(FORTUNA)
                    ? AppearancePolicy.CONDITION_DRIVEN : AppearancePolicy.EXPLICIT_ONLY;
            helper.assertValueEqual(definition.appearancePolicy(), expectedAppearance,
                    godId + ": production appearance policy");
            helper.assertValueEqual(definition.identificationPolicy(), IdentificationPolicy.EXPLICIT_ONLY,
                    godId + ": absent identification policy");
        });

        WorldStateView emptyWorld = world(Set.of());
        GodAccessService access = new GodAccessService(manager);
        helper.assertTrue(access.isEffectivelyUnlocked(DEMETER, emptyWorld),
                "A God without unlock conditions must be effectively unlocked");

        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        try {
            helper.assertValueEqual(dispatcher.execute("mythadmin gods",
                    helper.getLevel().getServer().createCommandSourceStack().withPermission(2)), 4,
                    "/mythadmin gods result");
            helper.assertValueEqual(dispatcher.execute("mythadmin god mythictrpg:demeter",
                    helper.getLevel().getServer().createCommandSourceStack().withPermission(2)), 1,
                    "/mythadmin god result");
            try {
                dispatcher.execute("mythadmin gods",
                        helper.getLevel().getServer().createCommandSourceStack().withPermission(1));
                helper.fail("Permission level 1 unexpectedly executed /mythadmin gods");
                return;
            } catch (CommandSyntaxException expected) {
                // The command root is unavailable below permission level 2.
            }
        } catch (CommandSyntaxException exception) {
            helper.fail("Admin command failed: " + exception.getMessage());
            return;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE, timeoutTicks = 1200)
    public static void reloadCommandCommitsCompleteDataset(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        long previousGeneration = GodDefinitionManager.INSTANCE.generation();
        try {
            server.getCommands().getDispatcher().execute("reload", server.createCommandSourceStack().withPermission(4));
        } catch (CommandSyntaxException exception) {
            helper.fail("/reload command failed to start: " + exception.getMessage());
            return;
        }
        helper.succeedWhen(() -> {
            helper.assertTrue(GodDefinitionManager.INSTANCE.generation() > previousGeneration,
                    "/reload did not commit a new generation");
            helper.assertValueEqual(GodDefinitionManager.INSTANCE.definitions().size(), 4,
                    "/reload did not retain the complete dataset");
        });
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void invalidGodConditionKeepsPreviousCache(GameTestHelper helper) {
        GodDefinitionManager manager = GodDefinitionManager.INSTANCE;
        Map<ResourceLocation, GodDefinition> previous = manager.definitions();
        long previousGeneration = manager.generation();
        ResourceManager resources = new InvalidGodResourceManager(helper.getLevel().getServer().getResourceManager());

        try {
            manager.prepare(resources, InactiveProfiler.INSTANCE);
            helper.fail("A God JSON with an unknown condition type was accepted");
            return;
        } catch (IllegalStateException expected) {
            helper.assertTrue(expected.getMessage().contains("previous cache remains active"),
                    "Transactional rejection did not state that the cache was preserved");
        }
        helper.assertTrue(manager.definitions() == previous, "Rejected reload replaced the cache reference");
        helper.assertValueEqual(manager.generation(), previousGeneration,
                "Rejected reload changed the cache generation");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void repositoryLifecycleAndPersistence(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PlayerMythDataService service = PlayerMythDataService.get(server);
        UUID lifecycleId = UUID.randomUUID();
        FakePlayer original = fakePlayer(helper, lifecycleId, "MythicOriginal");

        helper.assertValueEqual(original.getData(ModAttachments.PLAYER_MYTH_VIEW).playerId(), lifecycleId,
                "Runtime attachment must only identify the canonical profile");
        service.ensureProfile(lifecycleId);
        service.setAffinity(lifecycleId, DEMETER, 17);
        PlayerGodKnowledgeService knowledge = PlayerGodKnowledgeService.get(server);
        knowledge.recordEncounter(lifecycleId, DEMETER);
        knowledge.identifyGod(lifecycleId, DEMETER);

        FakePlayer afterDeath = fakePlayer(helper, lifecycleId, "MythicAfterDeath");
        FakePlayer afterReconnect = fakePlayer(helper, lifecycleId, "MythicReconnect");
        assertStoredProfile(helper, service.find(afterDeath.getData(ModAttachments.PLAYER_MYTH_VIEW).playerId())
                .orElseThrow(), "death");
        assertStoredProfile(helper, service.find(afterReconnect.getData(ModAttachments.PLAYER_MYTH_VIEW).playerId())
                .orElseThrow(), "reconnect");

        service.setParticipationStatus(lifecycleId, ParticipationStatus.ARCHIVED);
        helper.assertTrue(!service.activeProfiles().containsKey(lifecycleId),
                "ARCHIVED profile remained in the ACTIVE query set");
        helper.assertTrue(service.find(lifecycleId).isPresent(), "ARCHIVED profile data was deleted");
        service.setParticipationStatus(lifecycleId, ParticipationStatus.ACTIVE);
        helper.assertTrue(service.activeProfiles().containsKey(lifecycleId),
                "ACTIVE profile was absent from the ACTIVE query set");

        Optional<PlayerMythProfile> restartProfile = service.find(RESTART_PLAYER_ID);
        if (restartProfile.isPresent()) {
            assertStoredProfile(helper, restartProfile.orElseThrow(), "server restart");
            MythicTrpg.LOGGER.info("PHASE 2-A repository persistence probe verified after server restart.");
        } else {
            service.setAffinity(RESTART_PLAYER_ID, DEMETER, 17);
            PlayerGodKnowledgeService.get(server).recordEncounter(RESTART_PLAYER_ID, DEMETER);
            PlayerGodKnowledgeService.get(server).identifyGod(RESTART_PLAYER_ID, DEMETER);
            server.saveEverything(false, true, false);
            MythicTrpg.LOGGER.info("PHASE 2-A repository persistence probe initialized; run GameTestServer again.");
        }
        helper.assertValueEqual(PlayerMythDataRepository.get(server).dataVersion(), 1,
                "Repository dataVersion");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void compositesUnknownAndValidationLimits(GameTestHelper helper) {
        ConditionContext noTarget = context(Map.of(), Set.of(), Optional.empty(), readyGods());
        assertResult(helper, condition("{\"type\":\"mythictrpg:always\"}"), noTarget,
                ConditionResult.MATCH, "always");
        assertResult(helper, condition("{\"type\":\"mythictrpg:not\",\"condition\":"
                + "{\"type\":\"mythictrpg:always\"}}"), noTarget, ConditionResult.NO_MATCH, "not");

        String unknown = affinity("player", "god", DEMETER, 1);
        assertResult(helper, condition("{\"type\":\"mythictrpg:all\",\"conditions\":["
                + "{\"type\":\"mythictrpg:always\"}," + unknown + "]}"), noTarget,
                ConditionResult.UNKNOWN, "ALL unknown propagation");
        assertResult(helper, condition("{\"type\":\"mythictrpg:any\",\"conditions\":["
                + unknown + ",{\"type\":\"mythictrpg:always\"}]}"), noTarget,
                ConditionResult.MATCH, "ANY short circuit");
        assertResult(helper, condition("{\"type\":\"mythictrpg:not\",\"condition\":" + unknown + "}"),
                noTarget, ConditionResult.UNKNOWN, "NOT unknown propagation");

        expectInvalid(helper, "{\"type\":\"mythictrpg:all\",\"conditions\":[]}", "empty ALL");
        expectInvalid(helper, "{\"type\":\"mythictrpg:any\",\"conditions\":[]}", "empty ANY");
        expectInvalid(helper, "{\"type\":\"exampleaddon:missing\"}", "unknown type");
        expectInvalid(helper, affinity("world", "god", DEMETER, 1), "invalid scope");
        expectInvalid(helper, "{\"type\":\"mythictrpg:god_affinity\",\"scope\":\"player\","
                + "\"god\":\"mythictrpg:demeter\",\"god_category\":\"mythictrpg:agriculture\","
                + "\"minimum\":1}", "god/category XOR");
        expectInvalid(helper, nestedNot(ConditionTreeParser.MAX_DEPTH), "depth limit");
        expectInvalid(helper, oversizedAll(), "node limit");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void scopesAndGodAffinity(GameTestHelper helper) {
        PlayerMythDataService service = PlayerMythDataService.get(helper.getLevel().getServer());
        UUID matchingOffline = UUID.randomUUID();
        UUID nonMatchingOnline = UUID.randomUUID();
        UUID archivedMatching = UUID.randomUUID();
        service.setAffinity(matchingOffline, APHRODITE, 1000);
        service.setAffinity(nonMatchingOnline, APHRODITE, 5);
        service.setAffinity(archivedMatching, APHRODITE, 1000);
        service.setParticipationStatus(archivedMatching, ParticipationStatus.ARCHIVED);

        Map<UUID, PlayerMythProfile> profiles = Map.of(
                matchingOffline, service.find(matchingOffline).orElseThrow(),
                nonMatchingOnline, service.find(nonMatchingOnline).orElseThrow(),
                archivedMatching, service.find(archivedMatching).orElseThrow());
        ConditionContext targeted = context(profiles, Set.of(nonMatchingOnline), Optional.of(matchingOffline),
                readyGods());

        assertResult(helper, condition(affinity("player", "god", APHRODITE, 1000)), targeted,
                ConditionResult.MATCH, "PLAYER");
        assertResult(helper, condition(affinity("any_player", "god", APHRODITE, 1000)), targeted,
                ConditionResult.MATCH, "ANY_PLAYER includes offline ACTIVE");
        assertResult(helper, condition(affinity("all_players", "god", APHRODITE, 1000)), targeted,
                ConditionResult.NO_MATCH, "ALL_PLAYERS");
        assertResult(helper, condition(affinity("any_online_player", "god", APHRODITE, 1000)), targeted,
                ConditionResult.NO_MATCH, "ANY_ONLINE_PLAYER excludes offline matches");
        assertResult(helper, condition(affinity("any_player", "god_category", LOVE, 1000)), targeted,
                ConditionResult.MATCH, "category MAX/ANY_MEMBER");

        ConditionContext archivedOnly = context(Map.of(archivedMatching, profiles.get(archivedMatching)),
                Set.of(archivedMatching), Optional.empty(), readyGods());
        assertResult(helper, condition(affinity("any_player", "god", APHRODITE, 1000)), archivedOnly,
                ConditionResult.NO_MATCH, "ARCHIVED excluded");
        assertResult(helper, condition(affinity("all_players", "god", APHRODITE, 1)),
                context(Map.of(), Set.of(), Optional.empty(), readyGods()), ConditionResult.NO_MATCH,
                "ALL_PLAYERS empty set");
        assertResult(helper, condition(affinity("any_online_player", "god", APHRODITE, 1)),
                context(Map.of(matchingOffline, profiles.get(matchingOffline)), Set.of(), Optional.empty(), readyGods()),
                ConditionResult.NO_MATCH, "ANY_ONLINE_PLAYER empty set");
        assertResult(helper, condition(affinity("player", "god_category", LOVE, 1)),
                context(profiles, Set.of(), Optional.of(matchingOffline), unavailableGods()),
                ConditionResult.UNKNOWN, "category without God cache");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void schemaMigrationAndValidation(GameTestHelper helper) {
        ResourceLocation testId = ResourceLocation.fromNamespaceAndPath("exampleaddon", "schema_probe");
        GodDefinition migrated = GodDefinitionSchema.parse(testId, JsonParser.parseString(baseGodJson(1,
                ",\"unlock_conditions\":[],\"appearance_conditions\":[],\"identification_conditions\":[]"))
                .getAsJsonObject());
        helper.assertValueEqual(migrated.schemaVersion(), 2, "Schema 1 empty arrays must migrate to schema 2");
        helper.assertTrue(migrated.unlockConditions().isEmpty(), "Migrated unlock condition must be absent");

        GodDefinition conditional = GodDefinitionSchema.parse(testId, JsonParser.parseString(baseGodJson(2,
                ",\"unlock_conditions\":{\"type\":\"mythictrpg:always\"}"))
                .getAsJsonObject());
        helper.assertValueEqual(conditional.unlockPolicy(), UnlockPolicy.CONDITION_REQUIRED,
                "Schema 2 root condition was not compiled");
        GodAccessService access = new GodAccessService(() -> Map.of(testId, conditional));
        helper.assertTrue(!access.isEffectivelyUnlocked(testId, world(Set.of())),
                "A condition-bearing God bypassed WorldState unlock membership");
        helper.assertTrue(access.isEffectivelyUnlocked(testId, world(Set.of(testId))),
                "An unlocked condition-bearing God was not effectively unlocked");

        expectGodInvalid(helper, testId, baseGodJson(1,
                ",\"unlock_conditions\":[{\"type\":\"mythictrpg:always\"}]"),
                "schema 1 non-empty conditions");
        expectGodInvalid(helper, testId, baseGodJson(2,
                ",\"unlock_conditions\":{\"type\":\"exampleaddon:missing\"}"), "invalid type");
        expectGodInvalid(helper, testId, baseGodJson(2,
                ",\"unlock_conditions\":" + affinity("world", "god", DEMETER, 1)), "invalid scope");
        expectGodInvalid(helper, testId, baseGodJson(99, ""), "future schema");
        helper.succeed();
    }

    private static void assertStoredProfile(GameTestHelper helper, PlayerMythProfile profile, String stage) {
        helper.assertValueEqual(profile.dataVersion(), 4, stage + ": profile dataVersion");
        helper.assertValueEqual(profile.affinities().get(DEMETER), 17, stage + ": affinity");
        helper.assertTrue(profile.encounteredGods().contains(DEMETER), stage + ": encountered God");
        helper.assertTrue(profile.identifiedGods().contains(DEMETER), stage + ": identified God");
    }

    private static FakePlayer fakePlayer(GameTestHelper helper, UUID id, String name) {
        return new FakePlayer(helper.getLevel(), new GameProfile(id, name));
    }

    private static ConditionNode condition(String json) {
        return PARSER.parse(JsonParser.parseString(json));
    }

    private static void assertResult(GameTestHelper helper, ConditionNode condition, ConditionContext context,
            ConditionResult expected, String stage) {
        helper.assertValueEqual(ConditionEngine.INSTANCE.evaluate(condition, context), expected, stage);
    }

    private static void expectInvalid(GameTestHelper helper, String json, String stage) {
        try {
            condition(json);
            helper.fail(stage + " was accepted");
        } catch (JsonParseException expected) {
            // Expected validation rejection.
        }
    }

    private static void expectGodInvalid(GameTestHelper helper, ResourceLocation id, String json, String stage) {
        try {
            GodDefinitionSchema.parse(id, JsonParser.parseString(json).getAsJsonObject());
            helper.fail(stage + " was accepted");
        } catch (JsonParseException expected) {
            // Expected validation rejection.
        }
    }

    private static String affinity(String scope, String selector, ResourceLocation value, int minimum) {
        return "{\"type\":\"mythictrpg:god_affinity\",\"scope\":\"" + scope + "\",\""
                + selector + "\":\"" + value + "\",\"minimum\":" + minimum + "}";
    }

    private static String nestedNot(int wrappers) {
        String value = "{\"type\":\"mythictrpg:always\"}";
        for (int index = 0; index < wrappers; index++) {
            value = "{\"type\":\"mythictrpg:not\",\"condition\":" + value + "}";
        }
        return value;
    }

    private static String oversizedAll() {
        return "{\"type\":\"mythictrpg:all\",\"conditions\":[" + Stream.generate(
                () -> "{\"type\":\"mythictrpg:always\"}").limit(ConditionTreeParser.MAX_NODES)
                .collect(Collectors.joining(",")) + "]}";
    }

    private static ConditionContext context(Map<UUID, PlayerMythProfile> profiles, Set<UUID> online,
            Optional<UUID> target, GodDefinitionView gods) {
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
        return new ConditionContext(world(Set.of()), players, () -> Set.copyOf(online), gods, target,
                Optional.<ConditionEnvironment>empty(), Optional.<ConditionEventContext>empty());
    }

    private static WorldStateView world(Set<ResourceLocation> unlocked) {
        return new WorldStateView() {
            @Override
            public int dataVersion() {
                return 1;
            }

            @Override
            public Set<ResourceLocation> unlockedGods() {
                return Set.copyOf(unlocked);
            }
        };
    }

    private static GodDefinitionView readyGods() {
        Map<ResourceLocation, GodDefinition> definitions = GodDefinitionManager.INSTANCE.definitions();
        return new GodDefinitionView() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public Set<ResourceLocation> godsInCategory(ResourceLocation categoryId) {
                return definitions.entrySet().stream()
                        .filter(entry -> entry.getValue().categories().contains(categoryId))
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toUnmodifiableSet());
            }
        };
    }

    private static GodDefinitionView unavailableGods() {
        return new GodDefinitionView() {
            @Override
            public boolean isReady() {
                return false;
            }

            @Override
            public Set<ResourceLocation> godsInCategory(ResourceLocation categoryId) {
                return Set.of();
            }
        };
    }

    private static String baseGodJson(int version, String extraFields) {
        return "{\"schema_version\":" + version
                + ",\"display_name\":\"Schema Probe\",\"origin\":\"exampleaddon:test\""
                + ",\"faction\":\"exampleaddon:test\",\"categories\":[\"exampleaddon:test\"]"
                + extraFields + "}";
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }

    private static final class InvalidGodResourceManager implements ResourceManager {
        private static final ResourceLocation INVALID_FILE = ResourceLocation.fromNamespaceAndPath(
                "exampleaddon", "mythictrpg/gods/broken.json");
        private static final byte[] INVALID_JSON = baseGodJson(2,
                ",\"unlock_conditions\":{\"type\":\"exampleaddon:missing\"}")
                .getBytes(StandardCharsets.UTF_8);

        private final ResourceManager delegate;
        private final Resource invalidResource;

        private InvalidGodResourceManager(ResourceManager delegate) {
            this.delegate = delegate;
            PackResources sourcePack = delegate.listPacks().findFirst().orElseThrow();
            this.invalidResource = new Resource(sourcePack, () -> new ByteArrayInputStream(INVALID_JSON));
        }

        @Override
        public Optional<Resource> getResource(ResourceLocation location) {
            return location.equals(INVALID_FILE) ? Optional.of(invalidResource) : delegate.getResource(location);
        }

        @Override
        public Set<String> getNamespaces() {
            Set<String> namespaces = new LinkedHashSet<>(delegate.getNamespaces());
            namespaces.add(INVALID_FILE.getNamespace());
            return Set.copyOf(namespaces);
        }

        @Override
        public List<Resource> getResourceStack(ResourceLocation location) {
            return location.equals(INVALID_FILE) ? List.of(invalidResource) : delegate.getResourceStack(location);
        }

        @Override
        public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
            Map<ResourceLocation, Resource> resources = new LinkedHashMap<>(delegate.listResources(path, filter));
            if (INVALID_FILE.getPath().startsWith(path) && filter.test(INVALID_FILE)) {
                resources.put(INVALID_FILE, invalidResource);
            }
            return resources;
        }

        @Override
        public Map<ResourceLocation, List<Resource>> listResourceStacks(
                String path, Predicate<ResourceLocation> filter) {
            Map<ResourceLocation, List<Resource>> resources = new LinkedHashMap<>(
                    delegate.listResourceStacks(path, filter));
            if (INVALID_FILE.getPath().startsWith(path) && filter.test(INVALID_FILE)) {
                resources.put(INVALID_FILE, List.of(invalidResource));
            }
            return resources;
        }

        @Override
        public Stream<PackResources> listPacks() {
            return delegate.listPacks();
        }
    }
}
