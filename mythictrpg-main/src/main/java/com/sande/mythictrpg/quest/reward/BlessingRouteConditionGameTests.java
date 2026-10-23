package com.sande.mythictrpg.quest.reward;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.condition.api.BlessingHistoryView;
import com.sande.mythictrpg.condition.api.ConditionContext;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.builtin.BuiltinConditionTypes;
import com.sande.mythictrpg.condition.engine.ConditionContexts;
import com.sande.mythictrpg.condition.engine.ConditionEngine;
import com.sande.mythictrpg.condition.engine.ConditionTreeParser;
import com.sande.mythictrpg.condition.registry.ConditionTypeRegistry;
import com.sande.mythictrpg.quest.reward.blessing.GodRank;
import com.sande.mythictrpg.quest.reward.blessing.ResolvedBlessing;
import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitionSnapshot;
import com.sande.mythictrpg.story.definition.StoryDefinitions.*;
import com.sande.mythictrpg.story.runtime.StoryEventService;
import com.sande.mythictrpg.story.signal.StorySignalTypes;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@GameTestHolder("mythictrpg_blessings")
@PrefixGameTestTemplate(false)
public final class BlessingRouteConditionGameTests {
    private static final String HAS = "{\"type\":\"mythictrpg:has_non_raid_blessing\",\"scope\":\"player\"}";
    private static final String NONE = "{\"type\":\"mythictrpg:not\",\"condition\":" + HAS + "}";
    private static final ResourceLocation GOD = id("fortuna"), STRENGTH = ResourceLocation.withDefaultNamespace("strength");
    private static final ResourceLocation EVENT = id("test_blessing_route"), GATE = id("test_blessing_route_gate"),
            FACT = id("test_blessing_route_fact"), HAS_OUTCOME = id("test_blessing_route_has"),
            NONE_OUTCOME = id("test_blessing_route_none");

    @GameTest(templateNamespace = "mythictrpg_blessings", template = "empty", batch = "blessing_routes")
    public static void absentContextAndRejectedHistoryStayUnknownEvenUnderNot(GameTestHelper helper) {
        var base = ConditionContexts.forServer(helper.getLevel().getServer(), Optional.of(UUID.randomUUID()));
        var unavailable = new ConditionContext(base.world(), base.players(), base.server(), base.gods(),
                base.targetPlayerId(), base.environment(), base.event(), base.structures(), base.relations(),
                base.story(), base.storyScope());
        assertConditions(helper, unavailable, ConditionResult.UNKNOWN, ConditionResult.UNKNOWN);
        assertConditions(helper, ConditionContexts.forServer(helper.getLevel().getServer(), Optional.empty()),
                ConditionResult.UNKNOWN, ConditionResult.UNKNOWN);
        var invalid = new CompoundTag(); invalid.putInt("dataVersion", Integer.MAX_VALUE);
        var rejected = RewardClaimState.load(invalid, helper.getLevel().registryAccess());
        assertConditions(helper, withHistory(base, rejected), ConditionResult.UNKNOWN, ConditionResult.UNKNOWN);
        helper.assertTrue(ConditionEngine.INSTANCE.dependencies(parse(NONE))
                .contains(BuiltinConditionTypes.PLAYER_BLESSING_HISTORY_DEPENDENCY), "Negation lost acquisition dependency");
        for (String invalidJson : List.of(HAS.replace("player", "world"), HAS.replace("player", "any_player"),
                HAS.replace("\"scope\":\"player\"", "\"god\":\"mythictrpg:fortuna\""),
                HAS.replace("}", ",\"include_raid\":true}"))) {
            try { parse(invalidJson); helper.fail("Invalid blessing route condition accepted: " + invalidJson); }
            catch (JsonParseException expected) { }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_blessings", template = "empty", batch = "blessing_routes")
    public static void acquisitionRatherThanPendingOfferOrEffectControlsRoute(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        UUID player = UUID.randomUUID();
        var state = RewardClaimState.get(server);
        var context = ConditionContexts.forStory(server, Optional.of(player), StoryScopeKey.player(player));
        assertConditions(helper, context, ConditionResult.NO_MATCH, ConditionResult.MATCH);
        var direct = create(state, player, List.of(new BlessingRewardEntry(STRENGTH, -1, 4)), RewardOrigin.RAID_DIRECT);
        state.markAutomaticGranted(direct.claimId());
        assertConditions(helper, context, ConditionResult.NO_MATCH, ConditionResult.MATCH);
        var ownedOnly = new ResolvedBlessingRewardEntry(new ResolvedBlessing(id("test_route_ownership"), GOD,
                GodRank.LOWEST, "ownership_only", Optional.empty(), 0, 1));
        var quest = create(state, player, List.of(ownedOnly), RewardOrigin.QUEST);
        assertConditions(helper, context, ConditionResult.NO_MATCH, ConditionResult.MATCH);
        state.markAutomaticGranted(quest.claimId());
        assertConditions(helper, context, ConditionResult.MATCH, ConditionResult.NO_MATCH);
        state.suppressBlessing(player, STRENGTH);
        var encoded = state.save(new CompoundTag(), helper.getLevel().registryAccess());
        var restored = RewardClaimState.load(encoded, helper.getLevel().registryAccess());
        assertConditions(helper, withHistory(context, restored), ConditionResult.MATCH, ConditionResult.NO_MATCH);
        encoded.putInt("dataVersion", 5);
        var legacy = RewardClaimState.load(encoded, helper.getLevel().registryAccess());
        helper.assertTrue(legacy.isWritable(), "Legacy history fixture must be readable");
        assertConditions(helper, withHistory(context, legacy), ConditionResult.MATCH, ConditionResult.NO_MATCH);
        helper.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_blessings", template = "empty", batch = "blessing_routes", timeoutTicks = 100)
    public static void registeredStoryRoutesDistinguishRaidQuestAndMilk(GameTestHelper helper) {
        try (var connected = new Connected(helper)) {
            var player = connected.player;
            var previous = StoryDefinitionManager.INSTANCE.snapshot();
            try {
                install(previous); reset(player.server);
                assertRoute(helper, player, NONE_OUTCOME, false);
                var reward = new ResolvedQuestReward("test", List.of(new BlessingRewardEntry(STRENGTH, -1, 4)), List.of());
                var direct = RewardClaimService.INSTANCE.issue(player, GOD, id("test_route/raid/" + player.getUUID()),
                        reward, RewardOrigin.RAID_DIRECT);
                helper.assertTrue(direct.succeeded(), "Direct raid fixture did not deliver: " + direct.reason());
                helper.assertTrue(player.hasEffect(MobEffects.DAMAGE_BOOST), "Fixture must have a real active raid blessing");
                assertRoute(helper, player, NONE_OUTCOME, false);
                helper.assertValueEqual(StoryEventService.INSTANCE.triggerRegistered(player.server, GATE, Optional.of(player)).status(),
                        StoryEventService.Status.STARTED, "Direct raid blessing must allow the negative prerequisite");
                var quest = RewardClaimService.INSTANCE.issue(player, GOD, id("raid/quest_completion/" + player.getUUID()),
                        new ResolvedQuestReward("test", List.of(new BlessingRewardEntry(STRENGTH, -1, 0)), List.of()));
                helper.assertTrue(quest.succeeded(), "Raid-completion quest fixture did not deliver: " + quest.reason());
                assertRoute(helper, player, HAS_OUTCOME, true);
                helper.assertValueEqual(StoryEventService.INSTANCE.triggerRegistered(player.server, GATE, Optional.of(player)).status(),
                        StoryEventService.Status.REJECTED, "Quest acquisition must close the negative prerequisite");
                Items.MILK_BUCKET.finishUsingItem(new ItemStack(Items.MILK_BUCKET), player.serverLevel(), player);
                helper.assertFalse(player.hasEffect(MobEffects.DAMAGE_BOOST), "Milk must actually clear the active effect");
                assertRoute(helper, player, HAS_OUTCOME, true);
                helper.succeed();
            } finally {
                reset(player.server);
                field(StoryDefinitionManager.INSTANCE, "snapshot", previous);
            }
        }
    }

    private static RewardClaim create(RewardClaimState state, UUID player, List<RewardEntry> rewards, RewardOrigin origin) {
        return state.create(new RewardClaim(UUID.randomUUID(), player, GOD, id("test_route/" + UUID.randomUUID()),
                "test", rewards, List.of(), false, Optional.empty(), 1, origin));
    }

    private static ConditionContext withHistory(ConditionContext base, RewardClaimState state) {
        return new ConditionContext(base.world(), base.players(), base.server(), base.gods(), base.targetPlayerId(),
                base.environment(), base.event(), base.structures(), base.relations(), base.story(), base.storyScope(),
                new BlessingHistoryView() {
                    @Override public boolean isReady() { return state.isWritable(); }
                    @Override public boolean hasNonRaidBlessing(UUID playerId) { return state.hasNonRaidBlessing(playerId); }
                });
    }

    private static void assertConditions(GameTestHelper helper, ConditionContext context, ConditionResult has, ConditionResult none) {
        helper.assertValueEqual(ConditionEngine.INSTANCE.evaluate(parse(HAS), context), has, "Has-blessing route mismatch");
        helper.assertValueEqual(ConditionEngine.INSTANCE.evaluate(parse(NONE), context), none, "No-blessing route mismatch");
    }

    private static ConditionNode parse(String json) {
        return new ConditionTreeParser(ConditionTypeRegistry.INSTANCE).parse(JsonParser.parseString(json));
    }

    private static void assertRoute(GameTestHelper helper, ServerPlayer player, ResourceLocation outcome, boolean fact) {
        var result = StoryEventService.INSTANCE.triggerRegistered(player.server, EVENT, Optional.of(player));
        helper.assertValueEqual(result.status(), StoryEventService.Status.STARTED, "Registered route event must start");
        var state = StoryRuntimeState.get(player.server);
        helper.assertValueEqual(state.eventInstance(result.startedInstanceIds().getFirst()).orElseThrow().selectedOutcomeId(),
                Optional.of(outcome), "Registered event selected the wrong acquisition route");
        helper.assertValueEqual(state.fact(StoryScopeKey.player(player.getUUID()), FACT), fact, "Route effect did not run");
    }

    private static void install(StoryDefinitionSnapshot previous) {
        var facts = new LinkedHashMap<>(previous.facts());
        facts.put(FACT, new FactDefinition(FACT, Set.of(ScopeType.PLAYER), false, "test-only",
                List.of(new FactKnowledgeLevel(1, "test.blessing.route")), Set.of()));
        var events = new LinkedHashMap<>(previous.events());
        var has = new OutcomeDefinition(HAS_OUTCOME, 1, Optional.of(parse(HAS)),
                List.of(new Effect.SetFact(id("test_route_has_effect"), FACT, ScopeSelector.EVENT_SCOPE, true)));
        var none = new OutcomeDefinition(NONE_OUTCOME, 1, Optional.of(parse(NONE)),
                List.of(new Effect.SetFact(id("test_route_none_effect"), FACT, ScopeSelector.EVENT_SCOPE, false)));
        events.put(EVENT, event(EVENT, parse("{\"type\":\"mythictrpg:always\"}"), List.of(has, none)));
        events.put(GATE, event(GATE, parse(NONE), List.of(none)));
        field(StoryDefinitionManager.INSTANCE, "snapshot", new StoryDefinitionSnapshot(previous.actors(), previous.locations(), facts,
                previous.coverStories(), previous.disclosurePolicies(), events, previous.hooks(), previous.presentations(),
                previous.eventsBySignal(), previous.generation() + 1));
    }

    private static EventDefinition event(ResourceLocation id, ConditionNode prerequisite, List<OutcomeDefinition> outcomes) {
        return new EventDefinition(id, NarrativeRole.SIDE, ScopeType.PLAYER,
                List.of(new TriggerDefinition(StorySignalTypes.ADMIN_TRIGGERED, Optional.empty(), TriggerMode.IMMEDIATE, 0)),
                prerequisite, List.of(), new RepeatPolicy(RepeatType.LIMITED, 10, 0), ResolutionPolicy.FIRST_MATCH,
                false, outcomes, Optional.empty(), FailurePolicy.BLOCK_AND_REPORT, "test-blessing-origin-v1");
    }

    private static void field(Object target, String name, Object value) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException("Cannot install isolated route fixture", exception); }
    }

    private static void reset(MinecraftServer server) {
        try {
            var reset = StoryEventService.class.getDeclaredMethod("resetScenarioForTesting", MinecraftServer.class,
                    Set.class, Set.class, Set.class, Set.class);
            reset.setAccessible(true);
            reset.invoke(StoryEventService.INSTANCE, server, Set.of(EVENT, GATE), Set.of(), Set.of(FACT), Set.of());
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException("Cannot reset isolated route fixture", exception); }
    }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mythictrpg", path); }

    private static final class Connected implements AutoCloseable {
        final ServerPlayer player;
        final io.netty.channel.embedded.EmbeddedChannel channel;
        @SuppressWarnings("removal")
        Connected(GameTestHelper helper) {
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "BlessingRoute"), false);
            player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        }
        @Override public void close() { player.server.getPlayerList().remove(player); channel.finishAndReleaseAll(); }
    }
}
