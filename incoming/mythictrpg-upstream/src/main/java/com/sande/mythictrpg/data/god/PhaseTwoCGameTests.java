package com.sande.mythictrpg.data.god;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.builtin.BuiltinConditionTypes;
import com.sande.mythictrpg.condition.engine.ConditionContexts;
import com.sande.mythictrpg.condition.engine.ConditionEngine;
import com.sande.mythictrpg.condition.engine.ConditionTreeParser;
import com.sande.mythictrpg.condition.registry.ConditionTypeRegistry;
import com.sande.mythictrpg.data.player.ItemHistoryRecordResult;
import com.sande.mythictrpg.data.player.ParticipationStatus;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythHistoryService;
import com.sande.mythictrpg.data.world.MythicWorldState;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
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
import java.util.stream.Stream;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseTwoCGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ConditionTreeParser PARSER = new ConditionTreeParser(ConditionTypeRegistry.INSTANCE);
    private static final ResourceLocation AFFINITY_GOD = id("phase2c_affinity_probe");
    private static final ResourceLocation PERSISTENCE_GOD = id("phase2c_persistence_probe");
    private static final UUID PERSISTENCE_PLAYER = UUID.fromString("0eebeb4a-efce-4c4e-8072-a384c82b7753");

    private PhaseTwoCGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void dependencyTriggersAndSamePlayerSemantics(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PlayerMythDataService players = PlayerMythDataService.get(server);
        UUID itemPlayer = UUID.randomUUID();
        UUID affinityPlayer = UUID.randomUUID();
        players.ensureProfile(itemPlayer);
        players.setAffinity(affinityPlayer, AFFINITY_GOD, 100);

        String suffix = suffix();
        ResourceLocation itemGod = id("phase2c_item_" + suffix);
        ResourceLocation samePlayerGod = id("phase2c_same_player_" + suffix);
        Map<ResourceLocation, GodDefinition> definitions = Map.of(
                itemGod, god("Item Trigger", itemCondition("player", Items.JIGSAW)),
                samePlayerGod, god("Same Player", all(
                        itemCondition("player", Items.JIGSAW),
                        affinityCondition("player", AFFINITY_GOD, 100)))
        );

        GodDefinitionManager.ProgressionSnapshot previous = install(helper, definitions);
        try {
            MythicWorldState world = MythicWorldState.get(server);
            helper.assertTrue(!world.isGodUnlocked(itemGod), "Conditional God started unlocked");
            PlayerMythHistoryService.recordItemObtained(server, itemPlayer, Items.JIGSAW);
            helper.assertTrue(world.isGodUnlocked(itemGod), "Item dependency did not unlock its God");
            helper.assertTrue(!world.isGodUnlocked(samePlayerGod),
                    "Different players incorrectly combined PLAYER-scoped conditions");

            long beforeDuplicate = GodUnlockService.INSTANCE.totalConditionEvaluations();
            helper.assertValueEqual(PlayerMythHistoryService.recordItemObtained(
                    server, itemPlayer, Items.JIGSAW), ItemHistoryRecordResult.ALREADY_RECORDED,
                    "Duplicate item result");
            helper.assertValueEqual(GodUnlockService.INSTANCE.totalConditionEvaluations(), beforeDuplicate,
                    "ALREADY_RECORDED dispatched another unlock evaluation");

            players.setAffinity(itemPlayer, AFFINITY_GOD, 100);
            helper.assertTrue(world.isGodUnlocked(samePlayerGod),
                    "One ACTIVE player satisfying the complete tree did not unlock the God");
            long beforeSameAffinity = GodUnlockService.INSTANCE.totalConditionEvaluations();
            players.setAffinity(itemPlayer, AFFINITY_GOD, 100);
            helper.assertValueEqual(GodUnlockService.INSTANCE.totalConditionEvaluations(), beforeSameAffinity,
                    "Unchanged affinity dispatched another unlock evaluation");
            server.saveEverything(false, true, false);
        } finally {
            players.setParticipationStatus(itemPlayer, ParticipationStatus.ARCHIVED);
            players.setParticipationStatus(affinityPlayer, ParticipationStatus.ARCHIVED);
            restore(helper, previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void populationCatchUpAndUnknownRetry(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PlayerMythDataService players = PlayerMythDataService.get(server);
        UUID activeOffline = UUID.randomUUID();
        UUID archived = UUID.randomUUID();
        UUID laterTarget = UUID.randomUUID();
        UUID activatedTarget = UUID.randomUUID();
        PlayerMythHistoryService.recordItemObtained(server, activeOffline, Items.TRIDENT);
        PlayerMythHistoryService.recordItemObtained(server, archived, Items.DRAGON_EGG);
        players.setParticipationStatus(archived, ParticipationStatus.ARCHIVED);
        players.setAffinity(activatedTarget, AFFINITY_GOD, 200);
        players.setParticipationStatus(activatedTarget, ParticipationStatus.ARCHIVED);

        String suffix = suffix();
        ResourceLocation anyActiveGod = id("phase2c_any_active_" + suffix);
        ResourceLocation archivedOnlyGod = id("phase2c_archived_only_" + suffix);
        ResourceLocation unknownGod = id("phase2c_unknown_" + suffix);
        ResourceLocation activatedGod = id("phase2c_activated_" + suffix);
        Map<ResourceLocation, GodDefinition> definitions = Map.of(
                anyActiveGod, god("Any Active", itemCondition("any_player", Items.TRIDENT)),
                archivedOnlyGod, god("Archived Excluded", itemCondition("any_player", Items.DRAGON_EGG)),
                unknownGod, god("Unknown Retry", itemCondition("player", Items.SNIFFER_EGG)),
                activatedGod, god("Activated Target", affinityCondition("player", AFFINITY_GOD, 200))
        );

        GodDefinitionManager.ProgressionSnapshot previous = install(helper, definitions);
        try {
            MythicWorldState world = MythicWorldState.get(server);
            helper.assertTrue(world.isGodUnlocked(anyActiveGod),
                    "Offline ACTIVE profile was not included in reload catch-up");
            helper.assertTrue(!world.isGodUnlocked(archivedOnlyGod),
                    "ARCHIVED profile participated in automatic unlock catch-up");
            helper.assertTrue(!world.isGodUnlocked(activatedGod),
                    "ARCHIVED player target participated in reload catch-up");
            players.setParticipationStatus(activatedTarget, ParticipationStatus.ACTIVE);
            helper.assertTrue(world.isGodUnlocked(activatedGod),
                    "ACTIVE player-ready catch-up did not unlock a satisfied PLAYER tree");
            ConditionResult withoutTarget = ConditionEngine.INSTANCE.evaluate(
                    definitions.get(unknownGod).unlockConditions().orElseThrow(),
                    ConditionContexts.forServer(server, Optional.empty(), definitions, true));
            helper.assertValueEqual(withoutTarget, ConditionResult.UNKNOWN, "PLAYER condition without target");
            helper.assertTrue(!world.isGodUnlocked(unknownGod), "UNKNOWN condition unlocked a God");

            PlayerMythHistoryService.recordItemObtained(server, laterTarget, Items.SNIFFER_EGG);
            helper.assertTrue(world.isGodUnlocked(unknownGod),
                    "UNKNOWN result was treated as permanent and not retried with a valid target");
        } finally {
            players.setParticipationStatus(activeOffline, ParticipationStatus.ARCHIVED);
            players.setParticipationStatus(laterTarget, ParticipationStatus.ARCHIVED);
            players.setParticipationStatus(activatedTarget, ParticipationStatus.ARCHIVED);
            restore(helper, previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void cascadingUnlockAndLockedCycle(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID playerId = UUID.randomUUID();
        PlayerMythDataService.get(server).ensureProfile(playerId);
        String suffix = suffix();
        // Reverse lexical order forces dependents to be checked before their prerequisites during catch-up.
        ResourceLocation first = id("phase2c_cascade_z_" + suffix);
        ResourceLocation second = id("phase2c_cascade_y_" + suffix);
        ResourceLocation third = id("phase2c_cascade_x_" + suffix);
        ResourceLocation cycleA = id("phase2c_cycle_a_" + suffix);
        ResourceLocation cycleB = id("phase2c_cycle_b_" + suffix);
        Map<ResourceLocation, GodDefinition> definitions = Map.of(
                first, god("Cascade A", itemCondition("player", Items.COMMAND_BLOCK_MINECART)),
                second, god("Cascade B", godUnlockedCondition(first)),
                third, god("Cascade C", godUnlockedCondition(second)),
                cycleA, god("Cycle A", godUnlockedCondition(cycleB)),
                cycleB, god("Cycle B", godUnlockedCondition(cycleA))
        );

        GodDefinitionManager.ProgressionSnapshot previous = install(helper, definitions);
        try {
            PlayerMythHistoryService.recordItemObtained(server, playerId, Items.COMMAND_BLOCK_MINECART);
            MythicWorldState world = MythicWorldState.get(server);
            helper.assertTrue(world.isGodUnlocked(first), "Cascade root did not unlock");
            helper.assertTrue(world.isGodUnlocked(second), "Cascade did not reach second God");
            helper.assertTrue(world.isGodUnlocked(third), "Cascade did not reach third God");
            helper.assertTrue(!world.isGodUnlocked(cycleA) && !world.isGodUnlocked(cycleB),
                    "A cycle without a starting match unlocked itself");
        } finally {
            PlayerMythDataService.get(server).setParticipationStatus(playerId, ParticipationStatus.ARCHIVED);
            restore(helper, previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void reloadCommitsCacheAndIndexTogether(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID playerId = UUID.randomUUID();
        PlayerMythHistoryService.recordItemObtained(server, playerId, Items.KNOWLEDGE_BOOK);
        String suffix = suffix();
        ResourceLocation validFile = ResourceLocation.fromNamespaceAndPath(
                "phase2ctest", "mythictrpg/gods/reload_" + suffix + ".json");
        ResourceLocation validId = ResourceLocation.fromNamespaceAndPath("phase2ctest", "reload_" + suffix);
        String validJson = godJson(itemCondition("player", Items.KNOWLEDGE_BOOK));
        GodDefinitionManager manager = GodDefinitionManager.INSTANCE;
        GodDefinitionManager.ProgressionSnapshot previous = manager.progressionSnapshot();

        try {
            GodDefinitionManager.Prepared prepared = manager.prepare(
                    new OverlayResourceManager(server.getResourceManager(), validFile, validJson),
                    InactiveProfiler.INSTANCE);
            manager.apply(prepared, server.getResourceManager(), InactiveProfiler.INSTANCE);
            helper.assertTrue(manager.unlockIndex().candidates(
                    BuiltinConditionTypes.PLAYER_ITEM_HISTORY_DEPENDENCY).contains(validId),
                    "Valid reload did not update the dependency index");
            helper.assertTrue(MythicWorldState.get(server).isGodUnlocked(validId),
                    "Valid reload did not run catch-up against existing player data");

            Map<ResourceLocation, GodDefinition> committedDefinitions = manager.definitions();
            GodUnlockDependencyIndex committedIndex = manager.unlockIndex();
            long committedGeneration = manager.generation();
            ResourceLocation invalidFile = ResourceLocation.fromNamespaceAndPath(
                    "phase2ctest", "mythictrpg/gods/invalid_" + suffix + ".json");
            try {
                manager.prepare(new OverlayResourceManager(server.getResourceManager(), invalidFile,
                        godJson("{\"type\":\"phase2ctest:missing\"}")), InactiveProfiler.INSTANCE);
                helper.fail("Invalid God reload was accepted");
            } catch (IllegalStateException expected) {
                helper.assertTrue(manager.definitions() == committedDefinitions,
                        "Rejected reload replaced the God cache");
                helper.assertTrue(manager.unlockIndex() == committedIndex,
                        "Rejected reload replaced the dependency index");
                helper.assertValueEqual(manager.generation(), committedGeneration,
                        "Rejected reload changed generation");
            }
        } finally {
            PlayerMythDataService.get(server).setParticipationStatus(playerId, ParticipationStatus.ARCHIVED);
            restore(helper, previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void oneDependencyDoesNotScanEightHundredGods(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID playerId = UUID.randomUUID();
        PlayerMythDataService.get(server).ensureProfile(playerId);
        String suffix = suffix();
        ResourceLocation relevant = id("phase2c_800_relevant_" + suffix);
        Map<ResourceLocation, GodDefinition> definitions = new LinkedHashMap<>();
        definitions.put(relevant, god("Relevant", itemCondition("player", Items.STRUCTURE_VOID)));
        for (int index = 0; index < 799; index++) {
            ResourceLocation godId = id("phase2c_800_" + suffix + "_" + index);
            definitions.put(godId, god("Unconditional " + index, null));
        }

        GodDefinitionManager.ProgressionSnapshot previous = install(helper, definitions);
        try {
            GodUnlockDependencyIndex index = GodDefinitionManager.INSTANCE.unlockIndex();
            helper.assertValueEqual(GodDefinitionManager.INSTANCE.definitions().size(), 800,
                    "Synthetic God count");
            helper.assertValueEqual(index.candidates(
                    BuiltinConditionTypes.PLAYER_ITEM_HISTORY_DEPENDENCY).size(), 1,
                    "Item dependency candidate count");
            long before = GodUnlockService.INSTANCE.totalConditionEvaluations();
            UnlockEvaluationReport report = GodUnlockService.INSTANCE.evaluateDependency(server,
                    BuiltinConditionTypes.PLAYER_ITEM_HISTORY_DEPENDENCY, Optional.of(playerId));
            helper.assertValueEqual(report.checked(), 1, "Evaluation count for one dependency change");
            helper.assertValueEqual(GodUnlockService.INSTANCE.totalConditionEvaluations() - before, 1L,
                    "Total evaluator delta for one dependency change");
        } finally {
            PlayerMythDataService.get(server).setParticipationStatus(playerId, ParticipationStatus.ARCHIVED);
            restore(helper, previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void automaticUnlockPersistsAcrossRestart(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PlayerMythDataService players = PlayerMythDataService.get(server);
        players.ensureProfile(PERSISTENCE_PLAYER);
        MythicWorldState world = MythicWorldState.get(server);
        boolean loadedFromPreviousRun = world.isGodUnlocked(PERSISTENCE_GOD);
        Map<ResourceLocation, GodDefinition> definitions = Map.of(
                PERSISTENCE_GOD, god("Persistence Probe", itemCondition("player", Items.TRIAL_KEY)));
        GodDefinitionManager.ProgressionSnapshot previous = install(helper, definitions);

        try {
            if (!world.isGodUnlocked(PERSISTENCE_GOD)) {
                PlayerMythHistoryService.recordItemObtained(server, PERSISTENCE_PLAYER, Items.TRIAL_KEY);
                GodUnlockService.INSTANCE.evaluateDependency(server,
                        BuiltinConditionTypes.PLAYER_ITEM_HISTORY_DEPENDENCY, Optional.of(PERSISTENCE_PLAYER));
            }
            helper.assertTrue(world.isGodUnlocked(PERSISTENCE_GOD), "Persistence probe did not unlock");

            Map<ResourceLocation, GodDefinition> changed = Map.of(
                    PERSISTENCE_GOD, god("Persistence Probe", itemCondition("player", Items.BARRIER)));
            installWithoutSavingPrevious(helper, changed);
            ConditionResult changedResult = ConditionEngine.INSTANCE.evaluate(
                    changed.get(PERSISTENCE_GOD).unlockConditions().orElseThrow(),
                    ConditionContexts.forServer(server, Optional.of(PERSISTENCE_PLAYER), changed, true));
            helper.assertValueEqual(changedResult, ConditionResult.NO_MATCH,
                    "Changed persistence condition should be unsatisfied");
            helper.assertTrue(new GodAccessService(GodDefinitionManager.INSTANCE)
                    .isEffectivelyUnlocked(server, PERSISTENCE_GOD),
                    "An explicit unlock was relocked after its condition became false");
            server.saveEverything(false, true, false);
            MythicTrpg.LOGGER.info(loadedFromPreviousRun
                    ? "PHASE 2-C automatic unlock persistence verified after server restart."
                    : "PHASE 2-C automatic unlock persistence initialized; run GameTestServer again.");
        } finally {
            restore(helper, previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void adminCatchUpCommandExecutes(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(), "mythadmin evaluate unlocks");
        helper.succeed();
    }

    private static GodDefinitionManager.ProgressionSnapshot install(
            GameTestHelper helper, Map<ResourceLocation, GodDefinition> definitions) {
        GodDefinitionManager manager = GodDefinitionManager.INSTANCE;
        GodDefinitionManager.ProgressionSnapshot previous = manager.progressionSnapshot();
        installWithoutSavingPrevious(helper, definitions);
        return previous;
    }

    private static void installWithoutSavingPrevious(
            GameTestHelper helper, Map<ResourceLocation, GodDefinition> definitions) {
        GodDefinitionManager manager = GodDefinitionManager.INSTANCE;
        Map<ResourceLocation, GodDefinition> immutable = Map.copyOf(definitions);
        manager.apply(new GodDefinitionManager.Prepared(immutable, GodUnlockDependencyIndex.build(immutable)),
                helper.getLevel().getServer().getResourceManager(), InactiveProfiler.INSTANCE);
    }

    private static void restore(GameTestHelper helper, GodDefinitionManager.ProgressionSnapshot previous) {
        GodDefinitionManager.INSTANCE.apply(new GodDefinitionManager.Prepared(
                        previous.definitions(), previous.unlockIndex()),
                helper.getLevel().getServer().getResourceManager(), InactiveProfiler.INSTANCE);
    }

    private static GodDefinition god(String name, String unlockJson) {
        return new GodDefinition(2, Component.literal(name), id("test_origin"), id("test_faction"),
                Set.of(id("test_category")),
                unlockJson == null ? Optional.empty() : Optional.of(condition(unlockJson)),
                Optional.empty(), Optional.empty());
    }

    private static ConditionNode condition(String json) {
        return PARSER.parse(JsonParser.parseString(json));
    }

    private static String itemCondition(String scope, Item item) {
        return "{\"type\":\"mythictrpg:item_ever_obtained\",\"scope\":\"" + scope
                + "\",\"item\":\"" + itemId(item) + "\"}";
    }

    private static String affinityCondition(String scope, ResourceLocation godId, int minimum) {
        return "{\"type\":\"mythictrpg:god_affinity\",\"scope\":\"" + scope
                + "\",\"god\":\"" + godId + "\",\"minimum\":" + minimum + "}";
    }

    private static String godUnlockedCondition(ResourceLocation godId) {
        return "{\"type\":\"mythictrpg:god_unlocked\",\"scope\":\"world\",\"god\":\""
                + godId + "\"}";
    }

    private static String all(String... conditions) {
        return "{\"type\":\"mythictrpg:all\",\"conditions\":[" + String.join(",", conditions) + "]}";
    }

    private static String godJson(String unlockCondition) {
        return "{\"schema_version\":2,\"display_name\":\"PHASE 2-C Reload Probe\"," 
                + "\"origin\":\"phase2ctest:test\",\"faction\":\"phase2ctest:test\"," 
                + "\"categories\":[\"phase2ctest:test\"],\"unlock_conditions\":"
                + unlockCondition + "}";
    }

    private static ResourceLocation itemId(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "");
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
