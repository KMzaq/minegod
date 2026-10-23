package com.sande.mythictrpg.content;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;
import com.sande.mythictrpg.story.definition.*;
import com.sande.mythictrpg.story.definition.StoryDefinitions.*;
import com.sande.mythictrpg.story.runtime.StoryEventService;
import com.sande.mythictrpg.story.signal.*;
import com.sande.mythictrpg.story.state.*;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.*;
import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import static com.sande.mythictrpg.content.ContentSystemsGameTests.*;

@GameTestHolder("mythictrpg_content_systems")
@PrefixGameTestTemplate(false)
public final class StoryReservationGameplayGameTests {
    @GameTest(templateNamespace="mythictrpg_content_systems", template="empty", timeoutTicks=200)
    public static void latchedRewardsSurviveLogoutAndResolveOnceWithoutRecursiveReplay(GameTestHelper h) throws Exception {
        var channels = new ArrayList<EmbeddedChannel>();
        var owner = connect(h, "LatchOwner", channels);
        var waiting = connect(h, "LatchWaiting", channels);
        var originalStory = StoryDefinitionManager.INSTANCE.snapshot();
        var originalContent = ContentDefinitions.INSTANCE.snapshot();
        var lock = event(id("lock"), true, mark());
        var first = latched(event(id("reward_a"), false, reward()), ScopeType.PLAYER, 3);
        var second = latched(event(id("reward_b"), false, reward()), ScopeType.PLAYER, 3);
        Set<ResourceLocation> ids = Set.of(lock.id(), first.id(), second.id());
        try {
            install(originalStory, List.of(lock, first, second), Map.of());
            var resource = id("shared");
            var admission = new ContentDefinitions.Admission(false, Set.of(resource));
            ContentDefinitions.INSTANCE.apply(new ContentDefinitions.Snapshot(Map.of(),
                    Map.of(lock.id(), admission, first.id(), admission, second.id(), admission), Map.of()), null, null);
            var server = owner.server;
            StoryEventService.INSTANCE.triggerRegistered(server, lock.id(), Optional.of(owner));
            signal(waiting, first);
            signal(waiting, second);
            var state = StoryRuntimeState.get(server);
            check(state.latchedTriggers().values().stream().filter(x -> ids.contains(x.eventId())).count() == 2,
                    "busy resource did not retain both ordinary gameplay signals");
            var restored = StoryRuntimeState.load(state.save(new CompoundTag(), owner.registryAccess()), owner.registryAccess());
            check(restored.isReady(), "pending reservations did not load");
            server.overworld().getDataStorage().set("mythictrpg_story", restored);
            disconnect(waiting, List.of());
            choose(owner, lock);
            check(restored.latchedTriggers().values().stream().filter(x -> ids.contains(x.eventId())).count() == 2,
                    "offline player lost pending events when another player released the resource");
            check(restored.resolvedCount(first.id(), StoryScopeKey.player(waiting.getUUID())) == 0,
                    "offline reward was executed without its recipient");
            waiting = reconnect(h, waiting.getGameProfile(), channels);
            reevaluate(server);
            check(waiting.getInventory().countItem(Items.GOLD_NUGGET) == 2,
                    "latched reward delivery was lost or recursively duplicated");
            check(restored.resolvedCount(first.id(), StoryScopeKey.player(waiting.getUUID())) == 1
                    && restored.resolvedCount(second.id(), StoryScopeKey.player(waiting.getUUID())) == 1,
                    "one signal produced multiple repeatable instances");
            reevaluate(server);
            check(waiting.getInventory().countItem(Items.GOLD_NUGGET) == 2
                    && restored.latchedTriggers().values().stream().noneMatch(x -> ids.contains(x.eventId())),
                    "completed source replayed on the next evaluation");
            h.succeed();
        } finally {
            StoryRuntimeState.get(owner.server).resetForTesting(ids, Set.of(), Set.of(), Set.of());
            swapStory(originalStory);
            ContentDefinitions.INSTANCE.apply(originalContent, null, null);
            disconnect(owner, channels);
            disconnect(waiting, List.of());
        }
    }

    @GameTest(templateNamespace="mythictrpg_content_systems", template="empty", timeoutTicks=200)
    public static void pendingEventsKeepOriginalAudienceExpireAndWaitForOpenChoices(GameTestHelper h) throws Exception {
        var channels = new ArrayList<EmbeddedChannel>();
        var owner = connect(h, "FreezeOwner", channels);
        var other = connect(h, "FreezeOther", channels);
        var original = StoryDefinitionManager.INSTANCE.snapshot();
        var lock = event(id("scope_lock"), true, mark());
        var team = latched(event(id("team"), true, mark()), ScopeType.TEAM, 3);
        var expired = latched(event(id("expired"), false, reward()), ScopeType.PLAYER, 3);
        var repeat = latched(event(id("repeat"), true, mark()), ScopeType.PLAYER, 3);
        var changed = latched(event(id("changed"), false, mark()), ScopeType.PLAYER, 1);
        Set<ResourceLocation> ids = Set.of(lock.id(), team.id(), expired.id(), repeat.id(), changed.id());
        try {
            install(original, List.of(lock, team, expired, repeat, changed), Map.of());
            var server = owner.server;
            var state = StoryRuntimeState.get(server);
            long now = server.overworld().getGameTime();
            UUID oldTeam = UUID.randomUUID();
            state.putLatched(new LatchedTrigger("frozen-team", team.id(), StoryScopeKey.team(oldTeam),
                    Set.of(owner.getUUID()), Optional.of(owner.getUUID()), UUID.randomUUID(), Optional.of(team.id()),
                    now, OptionalLong.empty()));
            state.putLatched(new LatchedTrigger("already-expired", expired.id(), StoryScopeKey.player(owner.getUUID()),
                    Set.of(owner.getUUID()), Optional.of(owner.getUUID()), UUID.randomUUID(), Optional.of(expired.id()),
                    now, OptionalLong.of(now)));
            state.putLatched(new LatchedTrigger("changed-scope", changed.id(), StoryScopeKey.server(),
                    Set.of(), Optional.empty(), UUID.randomUUID(), Optional.of(changed.id()), now, OptionalLong.empty()));
            StoryEventService.INSTANCE.triggerRegistered(server, lock.id(), Optional.of(owner));
            choose(owner, lock); // Re-evaluation also happens on resolution, before the normal expiry tick.
            var frozen = state.eventInstances().values().stream().filter(x -> x.eventId().equals(team.id())).findFirst().orElseThrow();
            check(frozen.scope().equals(StoryScopeKey.team(oldTeam))
                    && frozen.frozenAudiencePlayerIds().equals(Set.of(owner.getUUID())),
                    "queued event rebound to the player's current team/audience");
            check(!StoryEventService.INSTANCE.choose(other, frozen.instanceId(), frozen.revision(),
                    team.outcomes().getFirst().id()).accepted(), "new bystander gained a pending team's choice");
            check(state.resolvedCount(expired.id(), StoryScopeKey.player(owner.getUUID())) == 0
                    && owner.getInventory().countItem(Items.GOLD_NUGGET) == 0
                    && !state.latchedTriggers().containsKey("already-expired"), "expired event ran during resource release");
            check(!state.latchedTriggers().containsKey("changed-scope"), "scope change became an impossible offline wait");
            choose(owner, team);

            StoryEventService.INSTANCE.triggerRegistered(server, repeat.id(), Optional.of(owner));
            signal(owner, repeat);
            check(state.latchedTriggers().values().stream().anyMatch(x -> x.eventId().equals(repeat.id())),
                    "fresh LATCHED signal was discarded while an earlier choice remained open");
            choose(owner, repeat);
            check(state.eventInstances().values().stream().filter(x -> x.eventId().equals(repeat.id())).count() == 2,
                    "pending signal did not start after open instance resolved");
            choose(owner, repeat);
            h.succeed();
        } finally {
            StoryRuntimeState.get(owner.server).resetForTesting(ids, Set.of(), Set.of(), Set.of());
            swapStory(original);
            disconnect(owner, channels);
            disconnect(other, List.of());
        }
    }

    private static List<Effect> reward() {
        return List.of(new Effect.GrantRewards(id("reward"), ResourceLocation.parse("mythictrpg:demeter"),
                List.of(new NpcRewardEntry(ResourceLocation.parse("minecraft:gold_nugget"), 1))));
    }

    private static List<Effect> mark() {
        return List.of(new Effect.SetFact(id("marker_effect"), id("marker"), ScopeSelector.SERVER, true));
    }

    private static EventDefinition latched(EventDefinition base, ScopeType scope, int maximum) {
        return new EventDefinition(base.id(), base.narrativeRole(), scope,
                List.of(new TriggerDefinition(StorySignalTypes.GOD_IDENTIFIED, Optional.of(base.id()), TriggerMode.LATCHED, -1)),
                base.prerequisites(), base.actors(), new RepeatPolicy(RepeatType.LIMITED, maximum, 0),
                base.resolutionPolicy(), false, base.outcomes(), base.choicePolicy(), base.failurePolicy(), base.fingerprint());
    }

    private static void install(StoryDefinitionSnapshot before, List<EventDefinition> definitions,
            Map<ResourceLocation, FactDefinition> facts) throws Exception {
        var events = new LinkedHashMap<ResourceLocation, EventDefinition>();
        var index = new LinkedHashMap<StoryDefinitionSnapshot.SignalKey, List<EventDefinition>>();
        definitions.forEach(e -> {
            events.put(e.id(), e);
            e.triggers().forEach(t -> index.computeIfAbsent(
                    new StoryDefinitionSnapshot.SignalKey(t.signalType(), t.subjectId()), x -> new ArrayList<>()).add(e));
        });
        var allFacts = new HashMap<>(before.facts());
        allFacts.putAll(facts);
        definitions.stream().flatMap(e -> e.outcomes().stream()).flatMap(o -> o.effects().stream())
                .filter(Effect.SetFact.class::isInstance).map(Effect.SetFact.class::cast)
                .forEach(e -> allFacts.put(e.factId(), new FactDefinition(e.factId(), Set.of(ScopeType.SERVER),
                        false, "marker", List.of(new FactKnowledgeLevel(1, "marker")), Set.of())));
        swapStory(new StoryDefinitionSnapshot(before.actors(), before.locations(), allFacts, before.coverStories(),
                before.disclosurePolicies(), events, Map.of(), Map.of(), index, before.generation() + 1));
    }

    private static void signal(ServerPlayer player, EventDefinition event) {
        StoryEventService.INSTANCE.submit(player.server, StorySignal.of(StorySignalTypes.GOD_IDENTIFIED,
                Optional.of(player.getUUID()), Optional.of(event.id()), player.server.overworld().getGameTime()));
    }

    private static void choose(ServerPlayer player, EventDefinition definition) {
        var current = StoryRuntimeState.get(player.server).eventInstances().values().stream()
                .filter(x -> x.eventId().equals(definition.id()) && x.status() == EventStatus.WAITING_FOR_CHOICE)
                .findFirst().orElseThrow();
        check(StoryEventService.INSTANCE.choose(player, current.instanceId(), current.revision(),
                definition.outcomes().getFirst().id()).accepted(), "owner could not finish pending choice");
    }

    private static void reevaluate(MinecraftServer server) throws Exception {
        var method = StoryEventService.class.getDeclaredMethod("reevaluateLatched", MinecraftServer.class);
        method.setAccessible(true);
        method.invoke(StoryEventService.INSTANCE, server);
    }

    private static ServerPlayer reconnect(GameTestHelper helper, GameProfile profile, List<EmbeddedChannel> channels) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile, cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        channels.add(new EmbeddedChannel(connection));
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }
}
