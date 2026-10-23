package com.sande.mythictrpg.quest.reward;

import com.sande.mythictrpg.data.god.GodDefinition;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.quest.reward.blessing.*;
import com.sande.mythictrpg.quest.reward.luck.*;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitionSnapshot;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Exercises preparation before sibling caches apply, then live settlement/reference validation. */
@GameTestHolder("mythictrpg_blessings")
@PrefixGameTestTemplate(false)
public final class RewardPolicyValidationGameTests {
    private static final ResourceLocation GOD = id("test_policy_god"), DEFINITION = id("test_policy_blessing"),
            POOL = id("test_policy_pool"), TABLE = id("test_policy_table"), EVENT = id("test_policy_cold_story");
    private static final ResourceLocation STRENGTH = ResourceLocation.withDefaultNamespace("strength");

    @GameTest(templateNamespace = "mythictrpg_blessings", template = "empty", batch = "reward_policy_validation")
    public static void storyPreparesBeforeDynamicCachesAndLivePoliciesFailClosed(GameTestHelper h) throws Exception {
        try (var fixture = new Policies()) {
            var rewards = List.<RewardEntry>of(new AdaptiveBlessingRewardEntry(DEFINITION), new LuckyRewardEntry(POOL));
            field(BlessingDefinitions.INSTANCE, "snapshot", new BlessingDefinitions.Snapshot(Map.of(), Map.of(), Map.of()));
            field(LuckDefinitions.INSTANCE, "snapshot", new LuckDefinitions.Snapshot(Map.of(), LuckModel.DEFAULT));
            var eventJson = """
                    {"schemaVersion":1,"narrativeRole":"SIDE","scope":"PLAYER",
                     "triggers":[{"signal":"mythictrpg:admin_triggered","mode":"IMMEDIATE"}],
                     "prerequisites":{"type":"mythictrpg:always"},"actors":[],
                     "repeatPolicy":{"type":"ONCE"},"resolutionPolicy":"FIXED","failurePolicy":"BLOCK_AND_REPORT",
                     "outcomes":[{"id":"mythictrpg:test_policy_cold_outcome","effects":[
                       {"effectId":"mythictrpg:test_policy_cold_reward","type":"GRANT_REWARDS","god":"%s",
                        "rewards":[{"type":"adaptive_blessing","definitionId":"%s"},{"type":"lucky_item","poolId":"%s"}]}]}]}
                    """.formatted(GOD, DEFINITION, POOL);
            var resources = new OverlayResources(h.getLevel().getServer().getResourceManager(), Map.of(
                    ResourceLocation.fromNamespaceAndPath(EVENT.getNamespace(), "mythictrpg/story_events/" + EVENT.getPath() + ".json"), eventJson));
            var prepare = StoryDefinitionManager.class.getDeclaredMethod("prepare", ResourceManager.class, ProfilerFiller.class);
            prepare.setAccessible(true);
            var prepared = (StoryDefinitionSnapshot) prepare.invoke(StoryDefinitionManager.INSTANCE, resources, InactiveProfiler.INSTANCE);
            h.assertTrue(prepared.events().containsKey(EVENT), "Cold Story preparation consulted unapplied sibling caches");
            h.assertFalse(RewardExecutionService.validate(rewards, RewardGrantPurpose.QUEST).allowed(),
                    "Runtime must still reject missing dynamic definitions");
            fixture.install();
            h.assertTrue(RewardExecutionService.validateForGod(GOD, rewards, RewardGrantPurpose.QUEST).allowed(),
                    "Authored references remained invalid after their cache applied");
            h.assertFalse(RewardExecutionService.validateForGod(id("another_god"), rewards, RewardGrantPurpose.QUEST).allowed(),
                    "Another God's blessing passed pre-settlement validation");
            fixture.god(Optional.empty());
            h.assertFalse(resolve(rewards).status() == QuestRewardResolver.Status.RESOLVED, "Quest resolver accepted an unranked adaptive God");
            var previousStory = StoryDefinitionManager.INSTANCE.snapshot();
            try (var connected = new Connected(h)) {
                field(StoryDefinitionManager.INSTANCE, "snapshot", prepared);
                var started = com.sande.mythictrpg.story.runtime.StoryEventService.INSTANCE.triggerRegistered(
                        connected.player.server, EVENT, Optional.of(connected.player));
                var instance = com.sande.mythictrpg.story.state.StoryRuntimeState.get(connected.player.server)
                        .eventInstance(started.startedInstanceIds().getFirst()).orElseThrow();
                h.assertValueEqual(instance.status(), com.sande.mythictrpg.story.state.StoryRuntimeModels.EventStatus.RECOVERY_REQUIRED,
                        "Story must block invalid God/rank references before committing outcome effects");
                h.assertTrue(instance.selectedOutcomeId().isEmpty() && instance.appliedEffectIds().isEmpty(),
                        "Invalid adaptive references committed a Story outcome");
                var rewardSource = com.sande.mythictrpg.story.runtime.StoryEventService.rewardSource(instance, id("test_policy_cold_reward"));
                h.assertTrue(RewardClaimState.get(connected.player.server).findBySource(connected.player.getUUID(), rewardSource).isEmpty(),
                        "Invalid Story policy consumed a reward receipt");
            } finally {
                var reset = com.sande.mythictrpg.story.runtime.StoryEventService.class.getDeclaredMethod("resetScenarioForTesting",
                        net.minecraft.server.MinecraftServer.class, Set.class, Set.class, Set.class, Set.class);
                reset.setAccessible(true);
                reset.invoke(com.sande.mythictrpg.story.runtime.StoryEventService.INSTANCE,
                        h.getLevel().getServer(), Set.of(EVENT), Set.of(), Set.of(), Set.of());
                field(StoryDefinitionManager.INSTANCE, "snapshot", previousStory);
            }
            UUID offline = UUID.randomUUID(); var source = id("test_policy_queue/" + UUID.randomUUID());
            h.assertFalse(RewardClaimService.INSTANCE.queueBatch(h.getLevel().getServer(), GOD, source,
                    Map.of(offline, new ResolvedQuestReward("test", rewards, List.of()))).succeeded(),
                    "Offline settlement queued an unranked adaptive God");
            h.assertTrue(RewardClaimState.get(h.getLevel().getServer()).findBySource(offline, source).isEmpty(),
                    "Invalid static policy consumed an offline receipt");
            fixture.god(Optional.of(GodRank.LOWEST));
            field(BlessingDefinitions.INSTANCE, "snapshot", new BlessingDefinitions.Snapshot(
                    fixture.blessings.definitions(), fixture.blessings.godPolicies(), Map.of()));
            h.assertFalse(resolve(rewards).status() == QuestRewardResolver.Status.RESOLVED, "Quest resolver accepted a missing rank policy");
            fixture.install();
            h.assertValueEqual(resolve(rewards).status(), QuestRewardResolver.Status.RESOLVED, "Valid authored quest policy rejected");
            var invalidTable = new NpcRewardTable(TABLE, id("another_god"), Map.of(1, new NpcRewardTier(1, rewards)));
            fixture.table(invalidTable);
            h.assertFalse(NpcRewardGrantService.validate(id("another_god"), TABLE, 1).allowed(),
                    "Owning a table must not authorize another God's adaptive blessing");
            h.succeed();
        }
    }

    @GameTest(templateNamespace = "mythictrpg_blessings", template = "empty", batch = "reward_policy_validation")
    public static void repeatedNpcLuckyClaimReportsNoNewExecution(GameTestHelper h) {
        try (var fixture = new Policies(); var connected = new Connected(h)) {
            fixture.install();
            fixture.table(new NpcRewardTable(TABLE, GOD, Map.of(1, new NpcRewardTier(1, List.of(new LuckyRewardEntry(POOL))))));
            var player = connected.player;
            var first = NpcRewardGrantService.grant(player, GOD, TABLE, 1, RewardGrantPurpose.AI_ACTION);
            h.assertTrue(first.granted(), "First authorized lucky table grant failed: " + first.reason());
            var second = NpcRewardGrantService.grant(player, GOD, TABLE, 1, RewardGrantPurpose.AI_ACTION);
            h.assertFalse(second.granted(), "Already-claimed NPC reward falsely reported another successful execution");
            h.assertTrue(second.reason().contains("이미 수령"), "Repeat must explain that the reward was already received");
            h.assertValueEqual(player.getInventory().countItem(Items.PAPER), 1, "Repeated NPC invocation duplicated its lucky item");
            h.succeed();
        }
    }

    private static QuestRewardResolver.Resolution resolve(List<RewardEntry> rewards) {
        return QuestRewardResolver.resolve(GOD, Optional.of(new QuestRewardPolicy(QuestRewardPolicy.Mode.REPLACE,
                0, "test", rewards, List.of())), Optional.empty());
    }
    private static final class Policies implements AutoCloseable {
        final GodDefinitionManager.ProgressionSnapshot gods = GodDefinitionManager.INSTANCE.progressionSnapshot();
        final BlessingDefinitions.Snapshot priorBlessings = BlessingDefinitions.INSTANCE.snapshot();
        final LuckDefinitions.Snapshot priorLuck = LuckDefinitions.INSTANCE.snapshot();
        final Object tables = field(NpcRewardTableManager.INSTANCE, "tables");
        final BlessingDefinitions.Snapshot blessings;
        final LuckDefinitions.Snapshot luck;
        Policies() {
            var definition = new BlessingDefinition(DEFINITION, GOD, 5,
                    List.of(new BlessingCandidate("strength", STRENGTH, BlessingCandidate.Mode.FLOOR, 1)), BlessingDefinition.Fallback.NONE);
            blessings = new BlessingDefinitions.Snapshot(Map.of(DEFINITION, definition),
                    Map.of(GOD, new GodBlessingPolicy(GOD, Map.of(STRENGTH, new GodBlessingPolicy.EffectRule(1, 0)))),
                    Map.of(GodRank.LOWEST, new BlessingRankPolicy(GodRank.LOWEST, Map.of(STRENGTH, 1))));
            luck = new LuckDefinitions.Snapshot(Map.of(POOL, new LuckyItemPool(POOL, List.of(
                    new LuckyItemCandidate("paper", new NpcRewardEntry(ResourceLocation.withDefaultNamespace("paper"), 1),
                            1, 1, 0, 1, false)))), LuckModel.DEFAULT);
        }
        void install() { god(Optional.of(GodRank.LOWEST)); field(BlessingDefinitions.INSTANCE, "snapshot", blessings); field(LuckDefinitions.INSTANCE, "snapshot", luck); }
        void god(Optional<GodRank> rank) {
            var definitions = new LinkedHashMap<>(gods.definitions());
            definitions.put(GOD, new GodDefinition(2, Component.literal("Synthetic policy God"), id("test_origin"), id("test_faction"),
                    Set.of(id("test_category")), Optional.empty(), Optional.empty(), Optional.empty(), rank));
            field(GodDefinitionManager.INSTANCE, "snapshot", new GodDefinitionManager.ProgressionSnapshot(definitions,
                    gods.unlockIndex(), gods.appearanceIndex(), gods.categoryIndex(), gods.generation() + 1));
        }
        @SuppressWarnings("unchecked") void table(NpcRewardTable value) {
            var copy = new LinkedHashMap<>((Map<ResourceLocation, NpcRewardTable>) tables); copy.put(value.id(), value);
            field(NpcRewardTableManager.INSTANCE, "tables", Map.copyOf(copy));
        }
        @Override public void close() {
            field(GodDefinitionManager.INSTANCE, "snapshot", gods); field(BlessingDefinitions.INSTANCE, "snapshot", priorBlessings);
            field(LuckDefinitions.INSTANCE, "snapshot", priorLuck); field(NpcRewardTableManager.INSTANCE, "tables", tables);
        }
    }
    private static final class Connected implements AutoCloseable {
        final ServerPlayer player; final io.netty.channel.embedded.EmbeddedChannel channel;
        Connected(GameTestHelper h) {
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(), "PolicyReward"), false);
            player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        }
        @Override public void close() { player.server.getPlayerList().remove(player); channel.finishAndReleaseAll(); }
    }
    private static Object field(Object target, String name) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
    }
    private static void field(Object target, String name, Object value) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mythictrpg", path); }
    private static final class OverlayResources implements ResourceManager {
        private final ResourceManager delegate;
        private final Map<ResourceLocation, Resource> files;
        OverlayResources(ResourceManager delegate, Map<ResourceLocation, String> json) {
            this.delegate = delegate; var pack = delegate.listPacks().findFirst().orElseThrow();
            var values = new LinkedHashMap<ResourceLocation, Resource>();
            json.forEach((id, value) -> values.put(id, new Resource(pack, () -> new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)))));
            files = Map.copyOf(values);
        }
        @Override public Optional<Resource> getResource(ResourceLocation id) { return Optional.ofNullable(files.get(id)).or(() -> delegate.getResource(id)); }
        @Override public Set<String> getNamespaces() { var result = new HashSet<>(delegate.getNamespaces()); files.keySet().forEach(id -> result.add(id.getNamespace())); return Set.copyOf(result); }
        @Override public List<Resource> getResourceStack(ResourceLocation id) { return files.containsKey(id) ? List.of(files.get(id)) : delegate.getResourceStack(id); }
        @Override public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
            var result = new LinkedHashMap<>(delegate.listResources(path, filter));
            files.forEach((id, resource) -> { if (id.getPath().startsWith(path + "/") && filter.test(id)) result.put(id, resource); }); return result;
        }
        @Override public Map<ResourceLocation, List<Resource>> listResourceStacks(String path, Predicate<ResourceLocation> filter) {
            var result = new LinkedHashMap<>(delegate.listResourceStacks(path, filter));
            files.forEach((id, resource) -> { if (id.getPath().startsWith(path + "/") && filter.test(id)) result.put(id, List.of(resource)); }); return result;
        }
        @Override public Stream<PackResources> listPacks() { return delegate.listPacks(); }
    }
}
