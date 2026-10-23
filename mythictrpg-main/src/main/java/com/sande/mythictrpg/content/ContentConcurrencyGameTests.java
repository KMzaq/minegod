package com.sande.mythictrpg.content;

import com.sande.mythictrpg.ai.api.RoomConversationEngine;
import com.sande.mythictrpg.ai.api.RoomConversationEngineRouter;
import com.sande.mythictrpg.ai.room.RecordingScope;
import com.sande.mythictrpg.ai.room.RoomType;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.condition.builtin.AlwaysCondition;
import com.sande.mythictrpg.condition.builtin.BuiltinConditionTypes;
import com.sande.mythictrpg.story.definition.StoryDefinitions.ScopeType;
import com.sande.mythictrpg.story.api.StoryStateView.StoryKnowledgeHolder;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitionSnapshot;
import com.sande.mythictrpg.story.definition.StoryDefinitions.*;
import com.sande.mythictrpg.story.runtime.StoryEventService;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.EventStatus;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.sande.mythictrpg.content.ContentSystemsGameTests.*;

/** Ordinary room authority and pending AI transport must not become a global Story/investigation lock. */
@GameTestHolder("mythictrpg_content_systems")
@PrefixGameTestTemplate(false)
public final class ContentConcurrencyGameTests {
    private ContentConcurrencyGameTests() { }

    @GameTest(templateNamespace = "mythictrpg_content_systems", template = "empty", timeoutTicks = 150)
    public static void normalConversationAndChoiceDoNotBlockDistantInvestigation(GameTestHelper helper) throws Exception {
        var channels = new ArrayList<EmbeddedChannel>();
        ServerPlayer talking = connect(helper, "TalkingExplorer", channels);
        ServerPlayer exploring = connect(helper, "DistantExplorer", channels);
        var contentBefore = ContentDefinitions.INSTANCE.snapshot();
        var storyBefore = StoryDefinitionManager.INSTANCE.snapshot();
        // Attach game-owned room state before replacing only the optional AI transport.
        ConversationRooms.INSTANCE.memberships(talking);
        var router = RoomConversationEngineRouter.INSTANCE;
        var originalEngine = router.engine();
        boolean originallyInstalled = router.available();
        var heldEngine = new HeldEngine();
        String pendingInstance = null;
        try {
            talking.setGameMode(GameType.SURVIVAL);
            exploring.setGameMode(GameType.SURVIVAL);
            BlockPos talkingSite = helper.absolutePos(new BlockPos(2, 2, 2));
            BlockPos distantSite = talkingSite.offset(64, 0, 0);
            placeBySite(helper, talking, talkingSite);
            placeBySite(helper, exploring, distantSite);

            var choiceFact = id("concurrent_choice_fact");
            var distantFact = id("concurrent_distant_fact");
            var waiting = event(id("concurrent_waiting"), true, List.of(
                    new Effect.SetFact(id("concurrent_choice_effect"), choiceFact, ScopeSelector.TRIGGER_PLAYER, true)));
            var independent = event(id("concurrent_independent"), false, List.of(
                    new Effect.GrantKnowledge(id("concurrent_learn"), HolderSelector.TRIGGER_PLAYER, Optional.empty(),
                            distantFact, 1, ResourceLocation.parse("mythictrpg:full"))));
            var facts = new HashMap<>(storyBefore.facts());
            facts.put(choiceFact, new FactDefinition(choiceFact, Set.of(ScopeType.PLAYER), false, "Choice fixture",
                    List.of(new FactKnowledgeLevel(1, "Choice fixture")), Set.of()));
            facts.put(distantFact, new FactDefinition(distantFact, Set.of(ScopeType.PLAYER), false, "Unpublished fixture summary",
                    List.of(new FactKnowledgeLevel(1, "Only this explorer found the distant clue")), Set.of()));
            swapStory(new StoryDefinitionSnapshot(storyBefore.actors(), storyBefore.locations(), facts,
                    storyBefore.coverStories(), storyBefore.disclosurePolicies(), Map.of(waiting.id(), waiting, independent.id(), independent),
                    Map.of(), Map.of(), Map.of(), storyBefore.generation() + 1));
            var talkingPoint = site(id("concurrent_talking_site"), talkingSite, talking, waiting.id());
            var distantPoint = site(id("concurrent_distant_site"), distantSite, exploring, independent.id());
            ContentDefinitions.INSTANCE.apply(new ContentDefinitions.Snapshot(
                    Map.of(talkingPoint.id(), talkingPoint, distantPoint.id(), distantPoint),
                    Map.of(waiting.id(), new ContentDefinitions.Admission(true, Set.of(id("choice_resource"))),
                            independent.id(), new ContentDefinitions.Admission(true, Set.of(id("distant_resource")))),
                    Map.of()), null, null);

            setEngine(heldEngine, true);
            var room = ConversationRooms.INSTANCE.create(talking, RoomType.PUBLIC_MOBILE,
                    List.of(ResourceLocation.parse("mythictrpg:demeter")), RecordingScope.STANDARD);
            check(!room.recordingScope().isTest() && room.playerIds().equals(Set.of(talking.getUUID())),
                    "fixture did not create an ordinary game-owned conversation");
            check(ConversationRooms.INSTANCE.publicText(talking, "주변을 살피면서 이야기할게."),
                    "ordinary public speech was not routed");
            check(heldEngine.requests.size() == 1 && !heldEngine.pending.getFirst().isDone(),
                    "fixture did not hold an actually issued asynchronous room turn");
            var issued = heldEngine.requests.getFirst();
            check(ConversationRooms.INSTANCE.memoryReadCurrent(talking.server, issued),
                    "held turn lost its game-issued room authority");

            var dispatcher = talking.server.getCommands().getDispatcher();
            check(dispatcher.execute("mythinvestigate " + talkingPoint.id(), talking.createCommandSourceStack().withPermission(0)) == 1,
                    "ordinary conversation prevented its player from entering a Story choice");
            var state = StoryRuntimeState.get(talking.server);
            var choice = state.eventInstances().values().stream().filter(e -> e.eventId().equals(waiting.id())).findFirst().orElseThrow();
            pendingInstance = choice.instanceId();
            check(choice.status() == EventStatus.WAITING_FOR_CHOICE && !choice.reservedResources().isEmpty(),
                    "first player's choice was not actually holding Story resources");
            check(dispatcher.execute("mythinvestigate " + distantPoint.id(), exploring.createCommandSourceStack().withPermission(0)) == 1,
                    "another player's pending conversation/choice globally blocked distant investigation");
            var resolved = state.eventInstances().values().stream().filter(e -> e.eventId().equals(independent.id())).findFirst().orElseThrow();
            check(resolved.status() == EventStatus.RESOLVED
                            && resolved.initiatingPlayerId().equals(Optional.of(exploring.getUUID()))
                            && resolved.frozenAudiencePlayerIds().equals(Set.of(exploring.getUUID())),
                    "independent encounter did not resolve with its own player/audience");
            check(state.knowledgeLevel(StoryKnowledgeHolder.player(exploring.getUUID()), distantFact) == 1
                            && state.knowledgeLevel(StoryKnowledgeHolder.player(talking.getUUID()), distantFact) == 0,
                    "distant discovery leaked into the other conversation");
            check(state.eventInstances().get(choice.instanceId()).status() == EventStatus.WAITING_FOR_CHOICE,
                    "independent event resolved or cancelled the first player's choice");
            check(heldEngine.requests.size() == 1 && !heldEngine.pending.getFirst().isDone()
                            && ConversationRooms.INSTANCE.memoryReadCurrent(talking.server, issued),
                    "independent event consumed or invalidated the other player's pending room turn");
            check(ConversationRooms.INSTANCE.memberships(exploring).isEmpty()
                            && ConversationRooms.INSTANCE.memberships(talking).getFirst().playerIds().equals(Set.of(talking.getUUID())),
                    "independent investigation changed conversation membership");
            check(StoryEventService.INSTANCE.choose(talking, choice.instanceId(), choice.revision(),
                    waiting.outcomes().getFirst().id()).accepted(), "owner could not finish their independent pending choice");
            pendingInstance = null;
            helper.succeed();
        } finally {
            if (pendingInstance != null) {
                var state = StoryRuntimeState.get(talking.server);
                var entry = state.eventInstances().get(pendingInstance);
                if (entry != null) state.putEvent(entry.withStatus(EventStatus.CANCELLED));
            }
            for (var room : ConversationRooms.INSTANCE.memberships(talking))
                ConversationRooms.INSTANCE.leave(talking, room, "CONTENT_CONCURRENCY_FIXTURE_END");
            heldEngine.stop();
            setEngine(originalEngine, originallyInstalled);
            ContentDefinitions.INSTANCE.apply(contentBefore, null, null);
            swapStory(storyBefore);
            disconnect(talking, channels);
            disconnect(exploring, List.of());
        }
    }

    private static void placeBySite(GameTestHelper helper, ServerPlayer player, BlockPos position) {
        helper.getLevel().setBlockAndUpdate(position, Blocks.STONE.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(position.offset(0, 0, 1), Blocks.AIR.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(position.offset(0, 1, 1), Blocks.AIR.defaultBlockState());
        player.setPos(position.getX() + 0.5, position.getY(), position.getZ() + 2.5);
    }

    private static ContentDefinitions.Investigation site(ResourceLocation id, BlockPos position,
            ServerPlayer player, ResourceLocation event) {
        return new ContentDefinitions.Investigation(id, player.level().dimension().location(), position,
                ResourceLocation.parse("minecraft:stone"), 4, event, Optional.empty(), 1, 20,
                new AlwaysCondition(BuiltinConditionTypes.ALWAYS));
    }

    private static void setEngine(RoomConversationEngine engine, boolean installed) throws ReflectiveOperationException {
        var field = RoomConversationEngineRouter.class.getDeclaredField("engine");
        field.setAccessible(true);
        field.set(RoomConversationEngineRouter.INSTANCE, engine);
        field = RoomConversationEngineRouter.class.getDeclaredField("installed");
        field.setAccessible(true);
        field.setBoolean(RoomConversationEngineRouter.INSTANCE, installed);
    }

    private static final class HeldEngine implements RoomConversationEngine {
        final List<Request> requests = new ArrayList<>();
        final List<CompletableFuture<Result>> pending = new ArrayList<>();
        public CompletableFuture<Result> respond(Request request) {
            requests.add(request);
            var future = new CompletableFuture<Result>();
            pending.add(future);
            return future;
        }
        public CompletableFuture<SplitResult> chooseSplit(SplitRequest request) {
            return CompletableFuture.completedFuture(new SplitResult(request.roomId(), request.revision(),
                    request.godId(), "", "", "UNUSED_CONTENT_FIXTURE"));
        }
        public void stop() {
            for (int i = 0; i < pending.size(); i++) pending.get(i).complete(Result.failed(requests.get(i), "CONTENT_FIXTURE_END"));
        }
    }
}
