package com.sande.mythictrpg.story.presentation;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.ai.api.*;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.story.api.StoryStateView.*;
import com.sande.mythictrpg.story.definition.*;
import com.sande.mythictrpg.story.definition.StoryDefinitions.*;
import com.sande.mythictrpg.story.runtime.StoryEventService;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Actual game room publication and Story state. Authored fixtures test authority, not emergent narrative quality. */
@GameTestHolder("mythictrpg_room_integration")
@PrefixGameTestTemplate(false)
public final class StoryRoomGameTests {
    private static final ResourceLocation GOD = id("demeter"), LISTENER = id("fortuna");
    private static final ResourceLocation ACTOR = id("000_room_test_speaker"), OTHER = id("000_room_test_listener");
    private static final ResourceLocation FACT = id("000_room_test_fact"), COVER_FACT = id("000_room_test_cover_fact");
    private static final ResourceLocation FULL = id("000_room_test_full"), COVER_POLICY = id("000_room_test_cover_policy"), COVER = id("000_room_test_cover");
    private static final ResourceLocation LEAVE_EVENT = id("000_room_test_leave_event"), LEAVE_PRESENTATION = id("000_room_test_leave_presentation");

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", batch="story_room_disclosure", timeoutTicks=100)
    public static void canonicalReceiptsCoverStoriesAndPortableAuthority(GameTestHelper helper) throws Exception {
        var manager = StoryDefinitionManager.INSTANCE;
        var original = manager.snapshot();
        var snapshotField = StoryDefinitionManager.class.getDeclaredField("snapshot"); snapshotField.setAccessible(true);
        var rooms = ConversationRooms.INSTANCE;
        var oldEngine = RoomConversationEngineRouter.INSTANCE.engine();
        var routerEngine = RoomConversationEngineRouter.class.getDeclaredField("engine"); routerEngine.setAccessible(true);
        var routerInstalled = RoomConversationEngineRouter.class.getDeclaredField("installed"); routerInstalled.setAccessible(true);
        boolean wasInstalled = RoomConversationEngineRouter.INSTANCE.available();
        var observed = new ArrayList<RoomDialogueEvent>();
        routerEngine.set(RoomConversationEngineRouter.INSTANCE, new RoomConversationEngine() {
            public CompletableFuture<Result> respond(Request r) { return CompletableFuture.completedFuture(Result.failed(r,"NO_MODEL_IN_TEST")); }
            public CompletableFuture<SplitResult> chooseSplit(SplitRequest r) { return CompletableFuture.completedFuture(new SplitResult(r.roomId(),r.revision(),r.godId(),"","","NO_MODEL_IN_TEST")); }
            public void dialogueObserved(RoomDialogueEvent event) { observed.add(event); }
        });
        routerInstalled.setBoolean(RoomConversationEngineRouter.INSTANCE,true);
        var players = new ArrayList<ServerPlayer>();
        try {
            var owner = connectedPlayer(helper,"StoryOwner"); players.add(owner);
            var peer = connectedPlayer(helper,"StoryPeer"); players.add(peer);
            var state = StoryRuntimeState.get(owner.server);
            var actors = new LinkedHashMap<>(original.actors());
            actors.put(ACTOR, new ActorDefinition(ACTOR,ActorType.GOD,Optional.of(GOD),ExistenceState.ACTIVE,AvailabilityState.AVAILABLE,Optional.empty(),Set.of()));
            actors.put(OTHER, new ActorDefinition(OTHER,ActorType.GOD,Optional.of(LISTENER),ExistenceState.ACTIVE,AvailabilityState.AVAILABLE,Optional.empty(),Set.of()));
            var publicPolicy = new RoomAudiencePolicy(RoomAudienceMode.PUBLIC,Set.of());
            var facts = new LinkedHashMap<>(original.facts());
            facts.put(FACT,new FactDefinition(FACT,Set.of(ScopeType.SERVER),false,"test only",List.of(
                    new FactKnowledgeLevel(1,"gui.yes",Optional.of(publicPolicy)),new FactKnowledgeLevel(2,"gui.no",Optional.of(publicPolicy))),Set.of()));
            facts.put(COVER_FACT,new FactDefinition(COVER_FACT,Set.of(ScopeType.SERVER),false,"test hidden truth",List.of(
                    new FactKnowledgeLevel(1,"gui.done",Optional.of(publicPolicy))),Set.of()));
            var policies = new LinkedHashMap<>(original.disclosurePolicies());
            policies.put(FULL,new DisclosurePolicy(FULL,DisclosureMode.FULL,OptionalInt.empty(),Optional.empty(),Optional.empty(),Optional.empty()));
            policies.put(COVER_POLICY,new DisclosurePolicy(COVER_POLICY,DisclosureMode.COVER_STORY,OptionalInt.empty(),Optional.empty(),Optional.empty(),Optional.of(COVER)));
            var covers = new LinkedHashMap<>(original.coverStories());
            covers.put(COVER,new CoverStoryDefinition(COVER,List.of("gui.cancel"),Set.of(ACTOR),publicPolicy));
            var presentations=new LinkedHashMap<>(original.presentations());
            presentations.put(LEAVE_PRESENTATION,new PresentationDefinition(LEAVE_PRESENTATION,PresentationKind.DIRECT_DIALOGUE,
                    GenerationPolicy.SCRIPTED_ONLY,CanonicalDeliveryPolicy.FLAVOR_ONLY,Optional.of(ACTOR),List.of(),List.of("gui.yes"),
                    Set.of(),List.of(),OfflineDeliveryPolicy.ON_NEXT_LOGIN,1,Optional.of(publicPolicy)));
            var fixture = new StoryDefinitionSnapshot(actors,original.locations(),facts,covers,policies,original.events(),Map.of(),
                    presentations,original.eventsBySignal(),original.generation()+1);
            snapshotField.set(manager,fixture);
            state.grantKnowledge(StoryKnowledgeHolder.actor(ACTOR),FACT,2,FULL,"room_test_source",0);
            state.grantKnowledge(StoryKnowledgeHolder.actor(ACTOR),COVER_FACT,1,COVER_POLICY,"room_test_source",0);
            var room = rooms.create(owner,RoomType.PUBLIC_MOBILE,List.of(GOD,LISTENER),RecordingScope.STANDARD);
            var service = StoryRoomConversationService.INSTANCE;
            var context = service.snapshot(owner,room.roomId(),room.revision(),GOD,"",false).orElseThrow();
            helper.assertValueEqual(context.snapshot().allowedStatements().size(),3,"public truth plus safe authored cover only");
            var secondAlias = context.statements().entrySet().stream().filter(e->e.getValue().proof().id().equals(FACT.toString())
                    &&e.getValue().proof().level()==2).map(Map.Entry::getKey).findFirst().orElseThrow();
            var canonical = service.prepareDisclosures(owner,context.snapshot().requestId(),room.roomId(),room.revision(),GOD,List.of(secondAlias));
            helper.assertValueEqual(canonical.size(),2,"level two selection expands its lower canonical level");
            helper.assertValueEqual(state.knowledgeLevel(StoryKnowledgeHolder.player(owner.getUUID()),FACT),0,"prompt construction is not knowledge grant");
            var receipts = canonical.stream().map(line->rooms.publishStory(owner,room.roomId(),room.revision(),GOD,line.text(),List.of(line.evidence())).orElseThrow()).toList();
            service.commitDisclosures(owner,context.snapshot().requestId(),receipts);
            helper.assertValueEqual(state.knowledgeLevel(StoryKnowledgeHolder.player(owner.getUUID()),FACT),2,"owner learned actually dispatched canonical text");
            helper.assertValueEqual(state.knowledgeLevel(StoryKnowledgeHolder.player(peer.getUUID()),FACT),2,"public observer learned actually dispatched canonical text");
            helper.assertValueEqual(state.knowledgeLevel(StoryKnowledgeHolder.actor(OTHER),FACT),2,"listening God learned canonical text");
            helper.assertTrue(service.current(owner,context),"ordinary knowledge grant did not revoke its own context");
            var coverAlias=context.statements().entrySet().stream().filter(e->e.getValue().proof().type().equals("COVER")).map(Map.Entry::getKey).findFirst().orElseThrow();
            var coverLines=service.prepareDisclosures(owner,context.snapshot().requestId(),room.roomId(),room.revision(),GOD,List.of(coverAlias));
            var coverReceipts=coverLines.stream().map(line->rooms.publishStory(owner,room.roomId(),room.revision(),GOD,line.text(),List.of(line.evidence())).orElseThrow()).toList();
            service.commitDisclosures(owner,context.snapshot().requestId(),coverReceipts);
            helper.assertValueEqual(state.knowledgeLevel(StoryKnowledgeHolder.player(owner.getUUID()),COVER_FACT),0,"authored cover never grants the hidden true fact");
            helper.assertValueEqual(state.knowledgeLevel(StoryKnowledgeHolder.actor(OTHER),COVER_FACT),0,"cover hearing is not true God knowledge");
            var newcomer=connectedPlayer(helper,"StoryNewcomer");players.add(newcomer);
            helper.assertTrue(!service.current(owner,context),"live public generation rejected a changed online audience");
            var request=new RoomConversationEngine.Request(room.roomId(),room.revision(),UUID.randomUUID(),owner.getUUID(),"StoryOwner",
                    List.of(GOD,LISTENER),LISTENER,"",List.of(),true,false,true,
                    List.of(new RoomConversationEngine.GodState(LISTENER,"STRANGER","CALM","",null)),false,
                    Set.of(owner.getUUID(),peer.getUUID(),newcomer.getUUID()));
            service.clear(); // Portable refs survive loss of transient request maps (server restart boundary).
            helper.assertTrue(service.evidenceCurrent(owner.server,request,canonical.getFirst().evidence()),"explicit public proof survives request cache reset and a different reader/new audience");
            helper.assertValueEqual(state.knowledgeLevel(StoryKnowledgeHolder.player(newcomer.getUUID()),FACT),0,"new observer did not retroactively receive knowledge");
            state.grantKnowledge(StoryKnowledgeHolder.actor(ACTOR),COVER_FACT,1,FULL,"room_test_source",0);
            var testRoom=rooms.create(owner,RoomType.PRIVATE,List.of(GOD,LISTENER),RecordingScope.TEST_EPHEMERAL);
            var testContext=service.snapshot(owner,testRoom.roomId(),testRoom.revision(),GOD,"",false).orElseThrow();
            var secretAlias=testContext.statements().entrySet().stream().filter(e->e.getValue().proof().id().equals(COVER_FACT.toString()))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            var testLines=service.prepareDisclosures(owner,testContext.snapshot().requestId(),testRoom.roomId(),testRoom.revision(),GOD,List.of(secretAlias));
            var testReceipts=testLines.stream().map(line->rooms.publishStory(owner,testRoom.roomId(),testRoom.revision(),GOD,line.text(),List.of(line.evidence())).orElseThrow()).toList();
            service.commitDisclosures(owner,testContext.snapshot().requestId(),testReceipts);
            helper.assertValueEqual(state.knowledgeLevel(StoryKnowledgeHolder.player(owner.getUUID()),COVER_FACT),0,"read-only test canonical receipt cannot grant player knowledge");
            helper.assertValueEqual(state.knowledgeLevel(StoryKnowledgeHolder.actor(OTHER),COVER_FACT),0,"read-only test cannot grant listening God knowledge");
            state.setFact(StoryScopeKey.server(),FACT,true);
            helper.assertTrue(!service.evidenceCurrent(owner.server,request,canonical.getFirst().evidence()),"corrected fact state revokes old evidence");
            helper.assertTrue(observed.stream().allMatch(e->e.worldId()!=null&&!e.evidenceRefs().isEmpty()),"game publication retained portable Story provenance");
            var privatePolicy=new RoomAudiencePolicy(RoomAudienceMode.PRIVATE_ROOM,Set.of(LISTENER));
            helper.assertTrue(privatePolicy.permits(false,2,Set.of(GOD,LISTENER),GOD),"authored private permission accepts multiple players/Gods");
            helper.assertTrue(!privatePolicy.permits(true,2,Set.of(GOD,LISTENER),GOD),"private permission never becomes public by affinity");
            helper.assertTrue(!privatePolicy.permits(false,2,Set.of(GOD,id("lubras")),GOD),"unlisted God prevents disclosure");
            helper.assertTrue(!RoomAudiencePolicy.ownerOnly().permits(false,1,Set.of(GOD,LISTENER),GOD),"legacy Hook/cover owner-only cannot leak to another God");
            var event=new EventInstance("room_test_leave_instance",LEAVE_EVENT,StoryScopeKey.server(),Set.of(owner.getUUID()),
                    Optional.of(owner.getUUID()),UUID.randomUUID(),EventStatus.RESOLVED,1,"test_only",Optional.empty(),OptionalLong.empty(),
                    0,OptionalLong.empty(),Set.of(),1);
            state.putEvent(event);
            var opportunity=new PresentationOpportunity(UUID.randomUUID(),event.instanceId(),event.revision(),LEAVE_PRESENTATION,
                    owner.getUUID(),PresentationStatus.PENDING,0);
            state.putActorState(ACTOR,ExistenceState.ACTIVE,AvailabilityState.ABSENT,Optional.empty(),0,LEAVE_EVENT);
            var departure=service.forOpportunity(owner,opportunity).orElseThrow();
            helper.assertValueEqual(departure.roomId(),testRoom.roomId(),"authored presentation deterministically prefers its safe private room");
            helper.assertTrue(rooms.publishStory(owner,departure.roomId(),departure.roomRevision(),GOD,"departure",departure.evidenceReferences()).isEmpty(),"ordinary absent God cannot speak");
            helper.assertTrue(rooms.publishStoryPresentation(owner,departure.roomId(),departure.roomRevision(),GOD,"departure",departure.evidenceReferences(),departure.snapshot().requestId()).isEmpty(),"inactive presentation context is not a publication capability");
            helper.assertTrue(service.beginPresentationDelivery(owner,departure),"game can activate authored departure delivery");
            var departureReceipt=rooms.publishStoryPresentation(owner,departure.roomId(),departure.roomRevision(),GOD,"departure",departure.evidenceReferences(),departure.snapshot().requestId()).orElseThrow();
            helper.assertTrue(departureReceipt.heardGodIds().contains(LISTENER.toString())&&!departureReceipt.heardGodIds().contains(GOD.toString()),"absent speaker announcement reaches listener but does not make absent God hear");
            service.endPresentationDelivery(departure.snapshot().requestId());
            helper.assertTrue(!service.roomDeliveryAuthorized(owner,departure.snapshot().requestId(),departure.roomId(),departure.roomRevision(),GOD),"presentation publication authority expires at delivery end");
            helper.succeed();
        } finally {
            rooms.clear(); StoryRoomConversationService.INSTANCE.clear();
            if(!players.isEmpty()) {
                var reset=StoryEventService.class.getDeclaredMethod("resetScenarioForTesting",net.minecraft.server.MinecraftServer.class,
                        Set.class,Set.class,Set.class,Set.class);reset.setAccessible(true);
                reset.invoke(StoryEventService.INSTANCE,players.getFirst().server,Set.of(LEAVE_EVENT),Set.of(ACTOR,OTHER),Set.of(FACT,COVER_FACT),Set.of());
            }
            snapshotField.set(manager,original);
            for(var player:players)player.server.getPlayerList().remove(player);
            routerEngine.set(RoomConversationEngineRouter.INSTANCE,oldEngine);
            routerInstalled.setBoolean(RoomConversationEngineRouter.INSTANCE,wasInstalled);
        }
    }
    private static ServerPlayer connectedPlayer(GameTestHelper helper,String name) {
        var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),name),false);
        var player=new ServerPlayer(helper.getLevel().getServer(),helper.getLevel(),cookie.gameProfile(),cookie.clientInformation());
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        player.server.getPlayerList().placeNewPlayer(connection,player,cookie);return player;
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mythictrpg",path); }
}
