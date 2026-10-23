package com.sande.mythictrpg.quest.reward.luck;

import com.sande.mythictrpg.condition.builtin.AlwaysCondition;
import com.sande.mythictrpg.condition.builtin.BuiltinConditionTypes;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** Isolated fixtures verify the registered Story engine uses only its personal owner's active luck. */
@GameTestHolder("mythictrpg_blessings")
@PrefixGameTestTemplate(false)
public final class LuckStoryGameTests {
    private static final ResourceLocation EVENT = id("test_luck_story"), FACT = id("test_luck_story_fact"),
            GOOD = id("test_luck_good"), BAD = id("test_luck_bad");
    private LuckStoryGameTests() { }

    @SuppressWarnings("removal")
    @GameTest(templateNamespace = "mythictrpg_blessings", template = "empty", batch = "luck_story", timeoutTicks = 80)
    public static void personalMarkedEventsUseLuckAndPersistTheirDecision(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "LuckStory"), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        var channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        var previous = StoryDefinitionManager.INSTANCE.snapshot();
        var previousLuck = LuckDefinitions.INSTANCE.snapshot();
        try {
            field(LuckDefinitions.INSTANCE, "snapshot", new LuckDefinitions.Snapshot(previousLuck.pools(), LuckModel.DEFAULT));
            EventDefinition marked = event(true, ScopeType.PLAYER);
            EventDefinition unmarked = event(false, ScopeType.PLAYER);
            EventDefinition serverEvent = event(true, ScopeType.SERVER);
            EventDefinition teamEvent = event(true, ScopeType.TEAM);
            var personal = StoryScopeKey.player(player.getUUID());
            var team = StoryScopeKey.team(UUID.randomUUID());
            int ordinaryGood = 0;
            var unmarkedBefore = new java.util.ArrayList<ResourceLocation>();
            var serverBefore = new java.util.ArrayList<ResourceLocation>();
            var teamBefore = new java.util.ArrayList<ResourceLocation>();
            for (int sequence = 1; sequence <= 256; sequence++) {
                if (selected(player.server, marked, personal, sequence).equals(GOOD)) ordinaryGood++;
                unmarkedBefore.add(selected(player.server, unmarked, personal, sequence));
                serverBefore.add(selected(player.server, serverEvent, StoryScopeKey.server(), sequence));
                teamBefore.add(selected(player.server, teamEvent, team, sequence));
            }
            player.addEffect(new MobEffectInstance(MobEffects.LUCK, 20_000, 16));
            int luckyGood = 0;
            for (int sequence = 1; sequence <= 256; sequence++) {
                if (selected(player.server, marked, personal, sequence).equals(GOOD)) luckyGood++;
                helper.assertValueEqual(selected(player.server, unmarked, personal, sequence), unmarkedBefore.get(sequence - 1),
                        "Unmarked event must preserve its original deterministic choice");
                helper.assertValueEqual(selected(player.server, serverEvent, StoryScopeKey.server(), sequence), serverBefore.get(sequence - 1),
                        "Server event must not borrow personal luck");
                helper.assertValueEqual(selected(player.server, teamEvent, team, sequence), teamBefore.get(sequence - 1),
                        "Team event must not guess a representative player's luck");
            }
            helper.assertTrue(luckyGood > 235 && luckyGood > ordinaryGood,
                    "Active luck XVII did not improve authored positive personal outcomes");
            install(previous, marked);
            reset(player.server);
            var started = StoryEventService.INSTANCE.triggerRegistered(player.server, EVENT, Optional.of(player));
            helper.assertValueEqual(started.status(), StoryEventService.Status.STARTED, "Registered personal random event starts");
            var state = StoryRuntimeState.get(player.server);
            var instance = state.eventInstance(started.startedInstanceIds().getFirst()).orElseThrow();
            helper.assertTrue(instance.selectedOutcomeId().isPresent() && instance.deterministicRoll().isPresent(),
                    "Story must store its selected outcome and luck-adjusted roll");
            helper.assertValueEqual(state.fact(personal, FACT), instance.selectedOutcomeId().orElseThrow().equals(GOOD),
                    "Stored outcome drives the actual game effect");
            var saved = state.save(new CompoundTag(), player.registryAccess());
            player.removeEffect(MobEffects.LUCK);
            var restored = StoryRuntimeState.load(saved, player.registryAccess()).eventInstance(instance.instanceId()).orElseThrow();
            helper.assertValueEqual(restored.selectedOutcomeId(), instance.selectedOutcomeId(), "Restart preserves selected outcome after luck changes");
            helper.assertValueEqual(restored.deterministicRoll(), instance.deterministicRoll(), "Restart preserves the original roll");
            helper.assertValueEqual(StoryEventService.INSTANCE.triggerRegistered(player.server, EVENT, Optional.of(player)).status(),
                    StoryEventService.Status.REJECTED, "Once-event cannot reroll after luck changes");
            helper.succeed();
        } finally {
            reset(player.server);
            field(StoryDefinitionManager.INSTANCE, "snapshot", previous);
            field(LuckDefinitions.INSTANCE, "snapshot", previousLuck);
            player.server.getPlayerList().remove(player);
            channel.finishAndReleaseAll();
        }
    }

    private static EventDefinition event(boolean marked, ScopeType scope) {
        var good = new OutcomeDefinition(GOOD, 100, Optional.empty(),
                List.of(new Effect.SetFact(id("test_luck_good_effect"), FACT, ScopeSelector.EVENT_SCOPE, true)),
                Optional.empty(), Optional.empty(), marked ? OptionalInt.of(5) : OptionalInt.empty());
        var bad = new OutcomeDefinition(BAD, 100, Optional.empty(),
                List.of(new Effect.SetFact(id("test_luck_bad_effect"), FACT, ScopeSelector.EVENT_SCOPE, false)),
                Optional.empty(), Optional.empty(), marked ? OptionalInt.of(-5) : OptionalInt.empty());
        return new EventDefinition(EVENT, NarrativeRole.SIDE, scope,
                List.of(new TriggerDefinition(StorySignalTypes.ADMIN_TRIGGERED, Optional.empty(), TriggerMode.IMMEDIATE, 0)),
                new AlwaysCondition(BuiltinConditionTypes.ALWAYS), List.of(), new RepeatPolicy(RepeatType.ONCE, 1, 0),
                ResolutionPolicy.WEIGHTED_ONCE, false, List.of(good, bad), Optional.empty(), FailurePolicy.BLOCK_AND_REPORT,
                marked ? "test-marked-luck-v1" : "test-unmarked-luck-v1");
    }
    private static void install(StoryDefinitionSnapshot previous, EventDefinition event) {
        var facts = new LinkedHashMap<>(previous.facts());
        facts.put(FACT, new FactDefinition(FACT, Set.of(ScopeType.PLAYER), false, "test-only",
                List.of(new FactKnowledgeLevel(1, "test.luck")), Set.of()));
        var events = new LinkedHashMap<>(previous.events()); events.put(EVENT, event);
        field(StoryDefinitionManager.INSTANCE, "snapshot", new StoryDefinitionSnapshot(previous.actors(), previous.locations(), facts,
                previous.coverStories(), previous.disclosurePolicies(), events, previous.hooks(), previous.presentations(),
                previous.eventsBySignal(), previous.generation() + 1));
    }
    private static ResourceLocation selected(MinecraftServer server, EventDefinition event, StoryScopeKey scope, int sequence) {
        try {
            Method select = StoryEventService.class.getDeclaredMethod("select", MinecraftServer.class, EventDefinition.class,
                    StoryScopeKey.class, int.class, List.class);
            select.setAccessible(true);
            Object selection = select.invoke(StoryEventService.INSTANCE, server, event, scope, sequence, event.outcomes());
            Method outcome = selection.getClass().getDeclaredMethod("outcome"); outcome.setAccessible(true);
            return ((OutcomeDefinition) outcome.invoke(selection)).id();
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException("Cannot inspect Story selection fixture", exception); }
    }
    private static void field(Object target, String name, Object value) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException("Cannot install isolated luck fixture", exception); }
    }
    private static void reset(MinecraftServer server) {
        try {
            var reset = StoryEventService.class.getDeclaredMethod("resetScenarioForTesting", MinecraftServer.class,
                    Set.class, Set.class, Set.class, Set.class);
            reset.setAccessible(true);
            reset.invoke(StoryEventService.INSTANCE, server, Set.of(EVENT), Set.of(), Set.of(FACT), Set.of());
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException("Cannot reset isolated luck fixture", exception); }
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mythictrpg", path); }
}
