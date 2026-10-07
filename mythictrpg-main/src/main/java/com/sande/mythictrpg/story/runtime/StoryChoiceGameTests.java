package com.sande.mythictrpg.story.runtime;

import com.sande.mythictrpg.condition.builtin.AlwaysCondition;
import com.sande.mythictrpg.condition.builtin.BuiltinConditionTypes;
import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitionSnapshot;
import com.sande.mythictrpg.story.definition.StoryDefinitions.*;
import com.sande.mythictrpg.story.signal.StorySignalTypes;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.EventStatus;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Isolated synthetic fixture: no authored story or installed content is changed. */
@GameTestHolder("mythictrpg_story_choice")
@PrefixGameTestTemplate(false)
public final class StoryChoiceGameTests {
    private static final ResourceLocation EVENT = id("test_story_choice_ui");
    private static final ResourceLocation TIMEOUT_EVENT = id("test_story_choice_timeout");
    private static final ResourceLocation FACT = id("test_story_choice_fact");
    private static final ResourceLocation YES = id("test_story_choice_yes");
    private static final ResourceLocation NO = id("test_story_choice_no");

    private StoryChoiceGameTests() {}

    @SuppressWarnings("removal")
    @GameTest(templateNamespace = "mythictrpg_story_choice", template = "empty", timeoutTicks = 80)
    public static void pendingChoiceIsPrivateDurableAndCommittedOnce(GameTestHelper helper) {
        var channels = new ArrayList<io.netty.channel.embedded.EmbeddedChannel>();
        ServerPlayer owner = connect(helper, "StoryOwner", channels);
        ServerPlayer outsider = connect(helper, "StoryOutsider", channels);
        drainPages(channels.get(0)); drainPages(channels.get(1));
        StoryDefinitionSnapshot previous = StoryDefinitionManager.INSTANCE.snapshot();
        StoryRuntimeState state = StoryRuntimeState.get(owner.server);
        try {
            installFixture(previous);
            StoryEventService.INSTANCE.resetScenarioForTesting(owner.server,
                    Set.of(EVENT, TIMEOUT_EVENT), Set.of(), Set.of(FACT), Set.of());
            var started = StoryEventService.INSTANCE.triggerRegistered(owner.server, EVENT, Optional.of(owner));
            helper.assertValueEqual(started.status(), StoryEventService.Status.STARTED, "choice event start");
            String instanceId = started.startedInstanceIds().getFirst();
            var pending = state.eventInstance(instanceId).orElseThrow();
            helper.assertValueEqual(pending.status(), EventStatus.WAITING_FOR_CHOICE, "pending status");
            helper.assertTrue(StoryChoiceUiService.INSTANCE.pendingFor(outsider).isEmpty(),
                    "non-audience player saw a private Story choice");
            var page = StoryChoiceUiService.INSTANCE.pageFor(owner, 0, false);
            helper.assertValueEqual(page.total(), 1, "pending page count");
            helper.assertValueEqual(page.offer().orElseThrow().revision(), pending.revision(), "pending revision");
            helper.assertValueEqual(page.offer().orElseThrow().options().size(), 2, "eligible option count");
            helper.assertTrue(drainPages(channels.get(0)).stream().anyMatch(p -> p.offer().isPresent()),
                    "No registered Story choice packet reached the audience connection");
            helper.assertTrue(drainPages(channels.get(1)).stream().noneMatch(p -> p.offer().isPresent()),
                    "Private Story choice packet leaked to another connection");
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), owner.registryAccess());
            try {
                com.sande.mythictrpg.network.StoryChoicePagePayload.STREAM_CODEC.encode(buffer, page);
                helper.assertValueEqual(com.sande.mythictrpg.network.StoryChoicePagePayload.STREAM_CODEC.decode(buffer),
                        page, "Story choice wire round trip");
            } finally { buffer.release(); }
            helper.assertValueEqual(page.offer().orElseThrow().options().getFirst().displayName(), YES.toString(),
                    "legacy ID display fallback");
            CompoundTag saved = state.save(new CompoundTag(), owner.server.registryAccess());
            var reloaded = StoryRuntimeState.load(saved, owner.server.registryAccess());
            helper.assertValueEqual(reloaded.eventInstance(instanceId).orElseThrow().status(),
                    EventStatus.WAITING_FOR_CHOICE, "pending choice persistence");
            helper.assertTrue(!StoryEventService.INSTANCE.choose(outsider, instanceId, pending.revision(), YES).accepted(),
                    "non-audience choice was accepted");
            helper.assertTrue(!StoryEventService.INSTANCE.choose(owner, instanceId, pending.revision() - 1, YES).accepted(),
                    "stale choice revision was accepted");
            helper.assertTrue(!StoryEventService.INSTANCE.choose(owner, instanceId, pending.revision(), id("unknown")).accepted(),
                    "unknown outcome was accepted");
            helper.assertTrue(StoryEventService.INSTANCE.choose(owner, instanceId, pending.revision(), YES).accepted(),
                    "valid audience choice was rejected");
            helper.assertValueEqual(state.eventInstance(instanceId).orElseThrow().status(),
                    EventStatus.RESOLVED, "choice resolution");
            helper.assertTrue(state.fact(StoryScopeKey.player(owner.getUUID()), FACT), "selected effect did not apply");
            helper.assertTrue(!StoryEventService.INSTANCE.choose(owner, instanceId, pending.revision(), YES).accepted(),
                    "duplicate choice was accepted");
            helper.assertTrue(StoryChoiceUiService.INSTANCE.pendingFor(owner).isEmpty(),
                    "resolved choice remained visible");

            var timeout = StoryEventService.INSTANCE.triggerRegistered(owner.server, TIMEOUT_EVENT, Optional.of(owner));
            helper.assertValueEqual(timeout.status(), StoryEventService.Status.STARTED, "timeout event start");
            String timeoutId = timeout.startedInstanceIds().getFirst();
            long timeoutRevision = state.eventInstance(timeoutId).orElseThrow().revision();
            helper.runAfterDelay(4, () -> {
                try {
                    helper.assertValueEqual(state.eventInstance(timeoutId).orElseThrow().status(),
                            EventStatus.RESOLVED, "timeout default did not resolve");
                    helper.assertTrue(!StoryEventService.INSTANCE.choose(owner, timeoutId, timeoutRevision, YES).accepted(),
                            "late click after timeout was accepted");
                    helper.assertTrue(!state.fact(StoryScopeKey.player(owner.getUUID()), FACT),
                            "timeout default effect did not apply");
                    helper.assertTrue(StoryChoiceUiService.INSTANCE.pendingFor(owner).isEmpty(),
                            "timed-out choice remained visible");
                    helper.succeed();
                } finally {
                    cleanup(owner, outsider, previous, channels);
                }
            });
        } catch (RuntimeException failure) {
            cleanup(owner, outsider, previous, channels);
            throw failure;
        }
    }

    private static void installFixture(StoryDefinitionSnapshot previous) {
        Map<ResourceLocation, FactDefinition> facts = new LinkedHashMap<>(previous.facts());
        facts.put(FACT, new FactDefinition(FACT, Set.of(ScopeType.PLAYER), false, "test-only",
                List.of(new FactKnowledgeLevel(1, "test.story.choice")), Set.of()));
        Map<ResourceLocation, EventDefinition> events = new LinkedHashMap<>(previous.events());
        events.put(EVENT, event(EVENT, -1, Optional.empty()));
        events.put(TIMEOUT_EVENT, event(TIMEOUT_EVENT, 2, Optional.of(NO)));
        var index = new LinkedHashMap<>(previous.eventsBySignal());
        var key = new StoryDefinitionSnapshot.SignalKey(StorySignalTypes.ADMIN_TRIGGERED, Optional.empty());
        var indexed = new ArrayList<>(index.getOrDefault(key, List.of()));
        indexed.add(events.get(EVENT)); indexed.add(events.get(TIMEOUT_EVENT));
        index.put(key, indexed);
        swap(new StoryDefinitionSnapshot(previous.actors(), previous.locations(), facts,
                previous.coverStories(), previous.disclosurePolicies(), events, previous.hooks(),
                previous.presentations(), index, previous.generation() + 1));
    }

    private static EventDefinition event(ResourceLocation eventId, long timeout, Optional<ResourceLocation> fallback) {
        var yes = new OutcomeDefinition(YES, 1, Optional.empty(),
                List.of(new Effect.SetFact(id("test_yes_effect"), FACT, ScopeSelector.EVENT_SCOPE, true)));
        var no = new OutcomeDefinition(NO, 1, Optional.empty(),
                List.of(new Effect.SetFact(id("test_no_effect"), FACT, ScopeSelector.EVENT_SCOPE, false)));
        return new EventDefinition(eventId, NarrativeRole.SIDE, ScopeType.PLAYER,
                List.of(new TriggerDefinition(StorySignalTypes.ADMIN_TRIGGERED, Optional.empty(),
                        TriggerMode.IMMEDIATE, 0)), new AlwaysCondition(BuiltinConditionTypes.ALWAYS), List.of(),
                new RepeatPolicy(RepeatType.ONCE, 1, 0), ResolutionPolicy.PLAYER_CHOICE, false,
                List.of(yes, no), Optional.of(new ChoicePolicy(timeout, fallback)),
                FailurePolicy.BLOCK_AND_REPORT, "test-choice-fixture-v1");
    }

    private static void swap(StoryDefinitionSnapshot next) {
        try {
            Field field = StoryDefinitionManager.class.getDeclaredField("snapshot");
            field.setAccessible(true);
            field.set(StoryDefinitionManager.INSTANCE, next);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot install isolated Story choice fixture", failure);
        }
    }

    private static ServerPlayer connect(GameTestHelper helper, String name,
            List<io.netty.channel.embedded.EmbeddedChannel> channels) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), name), false);
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        channels.add(new io.netty.channel.embedded.EmbeddedChannel(connection));
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static List<com.sande.mythictrpg.network.StoryChoicePagePayload> drainPages(
            io.netty.channel.embedded.EmbeddedChannel channel) {
        var result = new ArrayList<com.sande.mythictrpg.network.StoryChoicePagePayload>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            if (outbound instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket packet
                    && packet.payload() instanceof com.sande.mythictrpg.network.StoryChoicePagePayload page) result.add(page);
            io.netty.util.ReferenceCountUtil.release(outbound);
        }
        return result;
    }

    private static void cleanup(ServerPlayer owner, ServerPlayer outsider, StoryDefinitionSnapshot previous,
            List<io.netty.channel.embedded.EmbeddedChannel> channels) {
        try {
            StoryEventService.INSTANCE.resetScenarioForTesting(owner.server,
                    Set.of(EVENT, TIMEOUT_EVENT), Set.of(), Set.of(FACT), Set.of());
        } finally {
            swap(previous);
            for (ServerPlayer player : List.of(owner, outsider)) {
                if (player.server.getPlayerList().getPlayer(player.getUUID()) != null)
                    player.server.getPlayerList().remove(player);
            }
            channels.forEach(io.netty.channel.embedded.EmbeddedChannel::finishAndReleaseAll);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mythictrpg", path);
    }
}
