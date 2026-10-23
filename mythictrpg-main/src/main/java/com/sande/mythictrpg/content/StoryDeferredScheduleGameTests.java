package com.sande.mythictrpg.content;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.condition.builtin.AlwaysCondition;
import com.sande.mythictrpg.condition.builtin.BuiltinConditionTypes;
import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import com.sande.mythictrpg.story.api.StoryStateView.StoryKnowledgeHolder;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitionSnapshot;
import com.sande.mythictrpg.story.definition.StoryDefinitions.*;
import com.sande.mythictrpg.story.runtime.StoryEventService;
import com.sande.mythictrpg.story.signal.StorySignalTypes;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.*;

import static com.sande.mythictrpg.content.ContentSystemsGameTests.*;

@GameTestHolder("mythictrpg_content_systems")
@PrefixGameTestTemplate(false)
public final class StoryDeferredScheduleGameTests {
    @GameTest(templateNamespace = "mythictrpg_content_systems", template = "empty",
            batch = "content_deferred_schedule", timeoutTicks = 100)
    public static void offlineScheduledSourcesSurviveAndDoNotStarveOnlinePlayers(GameTestHelper helper) throws Exception {
        var fixture = new Fixture(helper);
        try {
            fixture.start();
            helper.runAfterDelay(6, () -> {
                try {
                    fixture.checkDueAndReconnect();
                    helper.succeed();
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                } finally {
                    fixture.close();
                }
            });
        } catch (Throwable failure) {
            fixture.close();
            throw failure;
        }
    }

    private static final class Fixture {
        private final GameTestHelper helper;
        private final MinecraftServer server;
        private final List<EmbeddedChannel> channels = new ArrayList<>();
        private final StoryDefinitionSnapshot priorDefinitions;
        private final CompoundTag priorState;
        private final ServerPlayer offline;
        private final ServerPlayer online;
        private ServerPlayer reconnected;
        private final ResourceLocation offlineTarget = id("offline_schedule_target");
        private final ResourceLocation onlineTarget = id("online_schedule_target");
        private final ResourceLocation fact = id("scheduled_personal_clue");
        private final ResourceLocation firstSource = id("schedule_batch");
        private final ResourceLocation overflowSource = id("schedule_overflow");
        private final ResourceLocation onlineSource = id("schedule_online");
        private com.sande.mythictrpg.story.state.StoryRuntimeModels.ScheduledSignal sourceSnapshot;
        private int onlineGoldBefore;
        private boolean offlineDisconnected;
        private boolean closed;

        private Fixture(GameTestHelper helper) {
            this.helper = helper;
            server = helper.getLevel().getServer();
            priorDefinitions = StoryDefinitionManager.INSTANCE.snapshot();
            priorState = StoryRuntimeState.get(server).save(new CompoundTag(), server.registryAccess());
            offline = connect(helper, "DeferredOwner", channels);
            online = connect(helper, "DeferredOnline", channels);
        }

        private void start() throws Exception {
            var facts = new HashMap<>(priorDefinitions.facts());
            facts.put(fact, new FactDefinition(fact, Set.of(ScopeType.PLAYER), false, "scheduler fixture",
                    List.of(new FactKnowledgeLevel(1, "Personal scheduled clue")), Set.of()));
            var events = new HashMap<>(priorDefinitions.events());
            events.put(offlineTarget, scheduledTarget(offlineTarget));
            events.put(onlineTarget, scheduledTarget(onlineTarget));
            var effects = new ArrayList<Effect>();
            for (int index = 0; index < StoryEventService.MAX_DUE_PER_TICK; index++)
                effects.add(new Effect.ScheduleEvent(id("schedule_" + index), offlineTarget, 1));
            events.put(firstSource, event(firstSource, false, effects));
            events.put(overflowSource, event(overflowSource, false,
                    List.of(new Effect.ScheduleEvent(id("overflow"), offlineTarget, 1))));
            // A later deadline guarantees the 65 offline sources precede this online source.
            events.put(onlineSource, event(onlineSource, false,
                    List.of(new Effect.ScheduleEvent(id("online_schedule"), onlineTarget, 2))));
            swapStory(new StoryDefinitionSnapshot(priorDefinitions.actors(), priorDefinitions.locations(), facts,
                    priorDefinitions.coverStories(), priorDefinitions.disclosurePolicies(), events,
                    priorDefinitions.hooks(), priorDefinitions.presentations(), priorDefinitions.eventsBySignal(),
                    priorDefinitions.generation() + 1));
            onlineGoldBefore = online.getInventory().countItem(Items.GOLD_NUGGET);
            for (var source : List.of(firstSource, overflowSource))
                check(StoryEventService.INSTANCE.triggerRegistered(server, source, Optional.of(offline)).status()
                        == StoryEventService.Status.STARTED, "offline-player schedule producer failed");
            check(StoryEventService.INSTANCE.triggerRegistered(server, onlineSource, Optional.of(online)).status()
                    == StoryEventService.Status.STARTED, "online-player schedule producer failed");
            check(offlineSchedules() == 65, "fixture did not create more than one tick budget of scheduled sources");
            sourceSnapshot = StoryRuntimeState.get(server).schedules().values().stream()
                    .filter(value -> value.targetEventId().equals(offlineTarget)).findFirst().orElseThrow();
            server.getPlayerList().remove(offline);
            offlineDisconnected = true;
        }

        private EventDefinition scheduledTarget(ResourceLocation target) {
            var effects = List.<Effect>of(
                    new Effect.GrantKnowledge(id("scheduled_knowledge"), HolderSelector.TRIGGER_PLAYER,
                            Optional.empty(), fact, 1, ResourceLocation.parse("mythictrpg:full")),
                    new Effect.GrantRewards(id("scheduled_reward"), ResourceLocation.parse("mythictrpg:demeter"),
                            List.of(new NpcRewardEntry(ResourceLocation.parse("minecraft:gold_nugget"), 2))));
            return new EventDefinition(target, NarrativeRole.SIDE, ScopeType.PLAYER,
                    List.of(new TriggerDefinition(StorySignalTypes.SCHEDULED_DUE, Optional.of(target),
                            TriggerMode.IMMEDIATE, 0)),
                    new AlwaysCondition(BuiltinConditionTypes.ALWAYS), List.of(), new RepeatPolicy(RepeatType.ONCE, 1, 0),
                    ResolutionPolicy.FIXED, false,
                    List.of(new OutcomeDefinition(id("scheduled_outcome"), 1, Optional.empty(), effects)),
                    Optional.empty(), FailurePolicy.BLOCK_AND_REPORT, target.toString());
        }

        private void checkDueAndReconnect() throws ReflectiveOperationException {
            check(offlineSchedules() == 65, "offline scheduled sources were lost at their due time");
            check(online.getInventory().countItem(Items.GOLD_NUGGET) == onlineGoldBefore + 2,
                    "more than 64 offline schedules starved a later online reward");
            check(StoryRuntimeState.get(server).knowledgeLevel(StoryKnowledgeHolder.player(offline.getUUID()), fact) == 0,
                    "offline target was resolved or its knowledge applied without its player");
            var saved = StoryRuntimeState.get(server).save(new CompoundTag(), server.registryAccess());
            var restored = StoryRuntimeState.load(saved, server.registryAccess());
            check(restored.isReady() && restored.schedules().values().stream()
                    .filter(value -> value.targetEventId().equals(offlineTarget)).count() == 65,
                    "offline scheduled sources did not survive NBT restore");
            server.overworld().getDataStorage().set("mythictrpg_story", restored);
            invokeScheduler("rebuildScheduleQueue", server);
            invokeScheduler("processDue", server);
            invokeScheduler("processDue", server);
            check(offlineSchedules() == 65, "restart queue processing discarded offline sources");

            reconnected = reconnect(helper, offline.getGameProfile(), channels);
            int before = reconnected.getInventory().countItem(Items.GOLD_NUGGET);
            // Real placeNewPlayer fires the login subscriber that requeues persisted sources.
            invokeScheduler("processDue", server);
            invokeScheduler("processDue", server);
            invokeScheduler("processDue", server);
            check(offlineSchedules() == 0, "real same-UUID login did not requeue deferred schedules");
            check(reconnected.getInventory().countItem(Items.GOLD_NUGGET) == before + 2,
                    "reconnected player did not receive exactly one ONCE reward");
            check(StoryRuntimeState.get(server).knowledgeLevel(
                    StoryKnowledgeHolder.player(reconnected.getUUID()), fact) == 1,
                    "reconnected player did not receive the scheduled personal clue");
            var resolved = StoryRuntimeState.get(server).eventInstances().values().stream()
                    .filter(value -> value.eventId().equals(offlineTarget)).toList();
            check(resolved.size() == 1 && resolved.getFirst().scope().equals(sourceSnapshot.scope())
                    && resolved.getFirst().frozenAudiencePlayerIds().equals(sourceSnapshot.frozenAudiencePlayerIds()),
                    "resumption duplicated the event or changed its frozen scope/audience");
            check(online.getInventory().countItem(Items.GOLD_NUGGET) == onlineGoldBefore + 2,
                    "offline owner replay paid the unrelated online player");
        }

        private long offlineSchedules() {
            return StoryRuntimeState.get(server).schedules().values().stream()
                    .filter(value -> value.targetEventId().equals(offlineTarget)).count();
        }

        private void close() {
            if (closed) return;
            closed = true;
            try {
                swapStory(priorDefinitions);
                server.overworld().getDataStorage().set("mythictrpg_story",
                        StoryRuntimeState.load(priorState, server.registryAccess()));
                invokeScheduler("rebuildScheduleQueue", server);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            } finally {
                if (reconnected != null) server.getPlayerList().remove(reconnected);
                if (!offlineDisconnected) server.getPlayerList().remove(offline);
                server.getPlayerList().remove(online);
                channels.forEach(EmbeddedChannel::finishAndReleaseAll);
            }
        }
    }

    private static void invokeScheduler(String name, MinecraftServer server) throws ReflectiveOperationException {
        var method = StoryEventService.class.getDeclaredMethod(name, MinecraftServer.class);
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
