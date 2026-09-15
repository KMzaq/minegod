package com.sande.mythictrpg.data.god;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionContext;
import com.sande.mythictrpg.condition.api.ConditionEnvironment;
import com.sande.mythictrpg.condition.api.ConditionEventContext;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.api.GodDefinitionView;
import com.sande.mythictrpg.condition.api.WorldStateView;
import com.sande.mythictrpg.condition.builtin.BuiltinConditionTypes;
import com.sande.mythictrpg.condition.engine.ConditionEngine;
import com.sande.mythictrpg.condition.engine.ConditionTreeParser;
import com.sande.mythictrpg.condition.registry.ConditionTypeRegistry;
import com.sande.mythictrpg.data.player.ParticipationStatus;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythHistoryService;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import com.sande.mythictrpg.data.player.PlayerMythQueryService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.item.Items;
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
public final class PhaseTwoBConditionGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ConditionTreeParser PARSER = new ConditionTreeParser(ConditionTypeRegistry.INSTANCE);
    private static final ResourceLocation DEMETER = id("demeter");
    private static final ResourceLocation CHERRY_GROVE = minecraft("cherry_grove");
    private static final ResourceLocation NAUTILUS_SHELL = BuiltInRegistries.ITEM.getKey(Items.NAUTILUS_SHELL);

    private PhaseTwoBConditionGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void compositeReadsHistoryAndEnvironment(GameTestHelper helper) {
        PlayerMythDataService service = PlayerMythDataService.get(helper.getLevel().getServer());
        UUID matchingPlayer = UUID.randomUUID();
        UUID emptyPlayer = UUID.randomUUID();
        PlayerMythHistoryService.recordItemObtained(
                helper.getLevel().getServer(), matchingPlayer, Items.NAUTILUS_SHELL);
        service.ensureProfile(emptyPlayer);
        Map<UUID, PlayerMythProfile> profiles = Map.of(
                matchingPlayer, service.find(matchingPlayer).orElseThrow(),
                emptyPlayer, service.find(emptyPlayer).orElseThrow());

        ConditionNode all = condition("{\"type\":\"mythictrpg:all\",\"conditions\":["
                + itemCondition("player", NAUTILUS_SHELL) + ","
                + biomeCondition("minecraft:cherry_grove") + ","
                + timeCondition(5000, 7000) + ","
                + yCondition("\"minimum\":100") + "]}");
        ConditionEnvironment matching = environment(CHERRY_GROVE, 6000, 100);
        assertResult(helper, all, context(profiles, Set.of(), Optional.of(matchingPlayer), Optional.of(matching)),
                ConditionResult.MATCH, "combined matching context");
        assertResult(helper, all, context(profiles, Set.of(), Optional.of(emptyPlayer), Optional.of(matching)),
                ConditionResult.NO_MATCH, "missing item history");
        assertResult(helper, all, context(profiles, Set.of(), Optional.of(matchingPlayer),
                Optional.of(environment(minecraft("plains"), 6000, 100))),
                ConditionResult.NO_MATCH, "wrong biome");
        assertResult(helper, all, context(profiles, Set.of(), Optional.of(matchingPlayer),
                Optional.of(environment(CHERRY_GROVE, 7000, 100))),
                ConditionResult.NO_MATCH, "time end boundary");
        assertResult(helper, all, context(profiles, Set.of(), Optional.of(matchingPlayer),
                Optional.of(environment(CHERRY_GROVE, 6000, 99))),
                ConditionResult.NO_MATCH, "Y below minimum");
        assertResult(helper, all, context(profiles, Set.of(), Optional.of(matchingPlayer), Optional.empty()),
                ConditionResult.UNKNOWN, "missing environment");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void itemHistoryScopesRespectParticipation(GameTestHelper helper) {
        PlayerMythDataService service = PlayerMythDataService.get(helper.getLevel().getServer());
        UUID archived = UUID.randomUUID();
        UUID activeOffline = UUID.randomUUID();
        PlayerMythHistoryService.recordItemObtained(helper.getLevel().getServer(), archived, Items.NAUTILUS_SHELL);
        PlayerMythHistoryService.recordItemObtained(
                helper.getLevel().getServer(), activeOffline, Items.NAUTILUS_SHELL);
        service.setParticipationStatus(archived, ParticipationStatus.ARCHIVED);
        Map<UUID, PlayerMythProfile> profiles = Map.of(
                archived, service.find(archived).orElseThrow(),
                activeOffline, service.find(activeOffline).orElseThrow());

        assertResult(helper, condition(itemCondition("player", NAUTILUS_SHELL)),
                context(profiles, Set.of(), Optional.of(archived), Optional.empty()),
                ConditionResult.MATCH, "explicit PLAYER can query ARCHIVED history");
        assertResult(helper, condition(itemCondition("any_player", NAUTILUS_SHELL)),
                context(Map.of(archived, profiles.get(archived)), Set.of(), Optional.empty(), Optional.empty()),
                ConditionResult.NO_MATCH, "ANY_PLAYER excludes ARCHIVED");
        assertResult(helper, condition(itemCondition("any_player", NAUTILUS_SHELL)),
                context(profiles, Set.of(), Optional.empty(), Optional.empty()),
                ConditionResult.MATCH, "ANY_PLAYER includes offline ACTIVE");
        assertResult(helper, condition(itemCondition("any_online_player", NAUTILUS_SHELL)),
                context(profiles, Set.of(), Optional.empty(), Optional.empty()),
                ConditionResult.NO_MATCH, "ANY_ONLINE_PLAYER excludes offline ACTIVE");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void biomeTimeAndYRules(GameTestHelper helper) {
        ConditionContext cherryAtStart = context(Map.of(), Set.of(), Optional.empty(),
                Optional.of(environment(CHERRY_GROVE, 5000, 100)));
        assertResult(helper, condition(biomeCondition("minecraft:cherry_grove")), cherryAtStart,
                ConditionResult.MATCH, "biome match");
        ConditionNode moddedBiome = condition(biomeCondition("regions_unexplored:maple_forest"));
        assertResult(helper, moddedBiome, cherryAtStart, ConditionResult.NO_MATCH,
                "unknown but valid namespaced biome");
        expectInvalid(helper, biomeCondition("regions_unexplored:"), "malformed biome ID");

        ConditionNode normal = condition(timeCondition(5000, 7000));
        assertEnvironmentResult(helper, normal, CHERRY_GROVE, 5000, 0, ConditionResult.MATCH, "time start");
        assertEnvironmentResult(helper, normal, CHERRY_GROVE, 6999, 0, ConditionResult.MATCH, "time inside");
        assertEnvironmentResult(helper, normal, CHERRY_GROVE, 7000, 0, ConditionResult.NO_MATCH, "time end");
        ConditionNode wrapped = condition(timeCondition(13000, 1000));
        assertEnvironmentResult(helper, wrapped, CHERRY_GROVE, 13000, 0, ConditionResult.MATCH, "wrap start");
        assertEnvironmentResult(helper, wrapped, CHERRY_GROVE, 0, 0, ConditionResult.MATCH, "wrap midnight");
        assertEnvironmentResult(helper, wrapped, CHERRY_GROVE, 1000, 0, ConditionResult.NO_MATCH, "wrap end");
        assertEnvironmentResult(helper, wrapped, CHERRY_GROVE, 12000, 0, ConditionResult.NO_MATCH, "outside wrap");
        expectInvalid(helper, timeCondition(5000, 5000), "equal time range");
        expectInvalid(helper, timeCondition(-1, 5000), "negative time");

        ConditionNode minimum = condition(yCondition("\"minimum\":100"));
        ConditionNode maximum = condition(yCondition("\"maximum\":100"));
        ConditionNode both = condition(yCondition("\"minimum\":100,\"maximum\":200"));
        assertEnvironmentResult(helper, minimum, CHERRY_GROVE, 0, 100, ConditionResult.MATCH, "Y minimum boundary");
        assertEnvironmentResult(helper, maximum, CHERRY_GROVE, 0, 100, ConditionResult.MATCH, "Y maximum boundary");
        assertEnvironmentResult(helper, both, CHERRY_GROVE, 0, 200, ConditionResult.MATCH, "Y both boundary");
        assertEnvironmentResult(helper, both, CHERRY_GROVE, 0, 201, ConditionResult.NO_MATCH, "Y above maximum");
        expectInvalid(helper, yCondition(""), "empty Y range");
        expectInvalid(helper, yCondition("\"minimum\":2,\"maximum\":1"), "reversed Y range");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void godUnlockedUsesAccessPolicy(GameTestHelper helper) {
        ConditionNode demeterUnlocked = condition(godUnlockedCondition(DEMETER));
        assertResult(helper, demeterUnlocked, context(Map.of(), Set.of(), Optional.empty(), Optional.empty()),
                ConditionResult.MATCH, "NOT_REQUIRED God");

        ResourceLocation lockedId = ResourceLocation.fromNamespaceAndPath("exampleaddon", "locked_god");
        GodDefinition locked = new GodDefinition(2, Component.literal("Locked God"),
                id("test_origin"), id("test_faction"), Set.of(id("test_category")),
                Optional.of(condition("{\"type\":\"mythictrpg:always\"}")), Optional.empty(), Optional.empty());
        Map<ResourceLocation, GodDefinition> definitions = new LinkedHashMap<>(
                GodDefinitionManager.INSTANCE.definitions());
        definitions.put(lockedId, locked);
        ConditionNode lockedCondition = condition(godUnlockedCondition(lockedId));
        assertResult(helper, lockedCondition,
                context(Map.of(), Set.of(), Optional.empty(), Optional.empty(), world(Set.of()), definitions, true),
                ConditionResult.NO_MATCH, "locked God");
        assertResult(helper, lockedCondition,
                context(Map.of(), Set.of(), Optional.empty(), Optional.empty(), world(Set.of(lockedId)), definitions, true),
                ConditionResult.MATCH, "explicitly unlocked God");
        assertResult(helper, condition(godUnlockedCondition(ResourceLocation.fromNamespaceAndPath(
                        "exampleaddon", "missing"))),
                context(Map.of(), Set.of(), Optional.empty(), Optional.empty()),
                ConditionResult.UNKNOWN, "missing God definition");
        assertResult(helper, lockedCondition,
                context(Map.of(), Set.of(), Optional.empty(), Optional.empty(), world(Set.of()), definitions, false),
                ConditionResult.UNKNOWN, "unavailable God cache");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void dependenciesAndReloadValidation(GameTestHelper helper) {
        helper.assertTrue(ConditionEngine.INSTANCE.dependencies(condition(itemCondition("player", NAUTILUS_SHELL)))
                .contains(BuiltinConditionTypes.PLAYER_ITEM_HISTORY_DEPENDENCY), "item history dependency");
        helper.assertTrue(ConditionEngine.INSTANCE.dependencies(condition(biomeCondition("minecraft:plains")))
                .containsAll(Set.of(BuiltinConditionTypes.PLAYER_ENVIRONMENT_DEPENDENCY,
                        BuiltinConditionTypes.BIOME_DEPENDENCY)), "biome dependencies");
        helper.assertTrue(ConditionEngine.INSTANCE.dependencies(condition(timeCondition(1, 2)))
                .contains(BuiltinConditionTypes.LEVEL_TIME_DEPENDENCY), "time dependency");
        helper.assertTrue(ConditionEngine.INSTANCE.dependencies(condition(yCondition("\"minimum\":1")))
                .containsAll(Set.of(BuiltinConditionTypes.PLAYER_POSITION_DEPENDENCY,
                        BuiltinConditionTypes.Y_DEPENDENCY)), "Y dependencies");
        helper.assertTrue(ConditionEngine.INSTANCE.dependencies(condition(godUnlockedCondition(DEMETER)))
                .containsAll(Set.of(BuiltinConditionTypes.WORLD_GOD_STATE_DEPENDENCY,
                        BuiltinConditionTypes.GOD_DEFINITIONS_DEPENDENCY)), "God unlock dependencies");

        GodDefinitionManager manager = GodDefinitionManager.INSTANCE;
        String validTree = "{\"type\":\"mythictrpg:all\",\"conditions\":["
                + itemCondition("player", NAUTILUS_SHELL) + ","
                + biomeCondition("minecraft:cherry_grove") + "," + timeCondition(1, 2) + ","
                + yCondition("\"minimum\":1") + "," + godUnlockedCondition(DEMETER) + "]}";
        GodDefinitionManager.Prepared prepared = manager.prepare(
                new SingleGodResourceManager(helper.getLevel().getServer().getResourceManager(), godJson(validTree)),
                InactiveProfiler.INSTANCE);
        helper.assertTrue(prepared.definitions().containsKey(SingleGodResourceManager.FILE_ID),
                "Valid PHASE 2-B condition fixture did not prepare");

        Map<ResourceLocation, GodDefinition> previous = manager.definitions();
        long generation = manager.generation();
        List<String> invalidTrees = List.of(
                itemCondition("player", ResourceLocation.fromNamespaceAndPath("exampleaddon", "missing_item")),
                "{\"type\":\"mythictrpg:biome\",\"scope\":\"world\",\"biome\":\"minecraft:plains\"}",
                timeCondition(5, 5),
                yCondition("\"minimum\":5,\"maximum\":4"),
                "{\"type\":\"mythictrpg:biome\",\"scope\":\"player\","
                        + "\"biome\":\"minecraft:plains\",\"unexpected\":true}"
        );
        for (String invalid : invalidTrees) {
            try {
                manager.prepare(new SingleGodResourceManager(
                        helper.getLevel().getServer().getResourceManager(), godJson(invalid)),
                        InactiveProfiler.INSTANCE);
                helper.fail("Invalid reload fixture was accepted: " + invalid);
                return;
            } catch (IllegalStateException expected) {
                helper.assertTrue(manager.definitions() == previous, "Rejected reload replaced cache reference");
                helper.assertValueEqual(manager.generation(), generation, "Rejected reload changed generation");
            }
        }
        helper.succeed();
    }

    private static ConditionNode condition(String json) {
        return PARSER.parse(JsonParser.parseString(json));
    }

    private static void expectInvalid(GameTestHelper helper, String json, String stage) {
        try {
            condition(json);
            helper.fail(stage + " was accepted");
        } catch (JsonParseException expected) {
            // Expected validation rejection.
        }
    }

    private static void assertResult(GameTestHelper helper, ConditionNode node, ConditionContext context,
            ConditionResult expected, String stage) {
        helper.assertValueEqual(ConditionEngine.INSTANCE.evaluate(node, context), expected, stage);
    }

    private static void assertEnvironmentResult(GameTestHelper helper, ConditionNode node,
            ResourceLocation biome, int time, int y, ConditionResult expected, String stage) {
        assertResult(helper, node, context(Map.of(), Set.of(), Optional.empty(),
                Optional.of(environment(biome, time, y))), expected, stage);
    }

    private static ConditionContext context(Map<UUID, PlayerMythProfile> profiles, Set<UUID> online,
            Optional<UUID> target, Optional<ConditionEnvironment> environment) {
        return context(profiles, online, target, environment, world(Set.of()),
                GodDefinitionManager.INSTANCE.definitions(), true);
    }

    private static ConditionContext context(Map<UUID, PlayerMythProfile> profiles, Set<UUID> online,
            Optional<UUID> target, Optional<ConditionEnvironment> environment, WorldStateView world,
            Map<ResourceLocation, GodDefinition> definitions, boolean godsReady) {
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
        GodDefinitionView gods = new GodDefinitionView() {
            @Override
            public boolean isReady() {
                return godsReady;
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
        return new ConditionContext(world, players, () -> Set.copyOf(online), gods, target, environment,
                Optional.<ConditionEventContext>empty());
    }

    private static ConditionEnvironment environment(ResourceLocation biome, int time, int y) {
        return new ConditionEnvironment(Level.OVERWORLD, new BlockPos(0, y, 0), Optional.of(biome), time);
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

    private static String itemCondition(String scope, ResourceLocation item) {
        return "{\"type\":\"mythictrpg:item_ever_obtained\",\"scope\":\"" + scope
                + "\",\"item\":\"" + item + "\"}";
    }

    private static String biomeCondition(String biome) {
        return "{\"type\":\"mythictrpg:biome\",\"scope\":\"player\",\"biome\":\""
                + biome + "\"}";
    }

    private static String timeCondition(int start, int end) {
        return "{\"type\":\"mythictrpg:time_range\",\"scope\":\"player\",\"start\":"
                + start + ",\"end\":" + end + "}";
    }

    private static String yCondition(String fields) {
        return "{\"type\":\"mythictrpg:y_range\",\"scope\":\"player\""
                + (fields.isEmpty() ? "" : "," + fields) + "}";
    }

    private static String godUnlockedCondition(ResourceLocation god) {
        return "{\"type\":\"mythictrpg:god_unlocked\",\"scope\":\"world\",\"god\":\""
                + god + "\"}";
    }

    private static String godJson(String condition) {
        return "{\"schema_version\":2,\"display_name\":\"PHASE 2-B Probe\","
                + "\"origin\":\"exampleaddon:test\",\"faction\":\"exampleaddon:test\","
                + "\"categories\":[\"exampleaddon:test\"],\"unlock_conditions\":" + condition + "}";
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }

    private static ResourceLocation minecraft(String path) {
        return ResourceLocation.fromNamespaceAndPath("minecraft", path);
    }

    private static final class SingleGodResourceManager implements ResourceManager {
        private static final ResourceLocation FILE = ResourceLocation.fromNamespaceAndPath(
                "exampleaddon", "mythictrpg/gods/phase2b_probe.json");
        private static final ResourceLocation FILE_ID = ResourceLocation.fromNamespaceAndPath(
                "exampleaddon", "phase2b_probe");
        private final ResourceManager delegate;
        private final Resource resource;

        private SingleGodResourceManager(ResourceManager delegate, String json) {
            this.delegate = delegate;
            PackResources source = delegate.listPacks().findFirst().orElseThrow();
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            this.resource = new Resource(source, () -> new ByteArrayInputStream(bytes));
        }

        @Override
        public Optional<Resource> getResource(ResourceLocation location) {
            return location.equals(FILE) ? Optional.of(resource) : delegate.getResource(location);
        }

        @Override
        public Set<String> getNamespaces() {
            Set<String> result = new LinkedHashSet<>(delegate.getNamespaces());
            result.add(FILE.getNamespace());
            return Set.copyOf(result);
        }

        @Override
        public List<Resource> getResourceStack(ResourceLocation location) {
            return location.equals(FILE) ? List.of(resource) : delegate.getResourceStack(location);
        }

        @Override
        public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
            Map<ResourceLocation, Resource> result = new LinkedHashMap<>(delegate.listResources(path, filter));
            if (FILE.getPath().startsWith(path) && filter.test(FILE)) {
                result.put(FILE, resource);
            }
            return result;
        }

        @Override
        public Map<ResourceLocation, List<Resource>> listResourceStacks(
                String path, Predicate<ResourceLocation> filter) {
            Map<ResourceLocation, List<Resource>> result = new LinkedHashMap<>(
                    delegate.listResourceStacks(path, filter));
            if (FILE.getPath().startsWith(path) && filter.test(FILE)) {
                result.put(FILE, List.of(resource));
            }
            return result;
        }

        @Override
        public Stream<PackResources> listPacks() {
            return delegate.listPacks();
        }
    }
}
