package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.ai.api.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.ProjectionRecords.*;
import com.sande.mythictrpg.recording.api.ProjectionRecords.Candidate;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual game-issued request, capture, projection lease and publication. Deterministic extractor, no model/network. */
@GameTestHolder("mythictrpg_native_interpretation")
@PrefixGameTestTemplate(false)
public final class NativeInterpretationGameTests {
    private static final String BATCH="native_interpretation_runtime", MARKER="typed_original_runtime", REPLY="typed_reply_runtime";
    private static Fixture active;
    @GameTest(templateNamespace="mythictrpg_native_interpretation",template="empty",timeoutTicks=20000,batch=BATCH)
    public static void typedPublicationRevalidatesItsActualProjection(GameTestHelper helper){
        if(active!=null)throw new IllegalStateException("Isolated serial fixture required");
        active=new Fixture(helper);helper.onEachTick(active::tick);
    }
    @AfterBatch(batch=BATCH)
    public static void cleanup(ServerLevel level){if(active!=null)active.close();}
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;final MinecraftServer server;final HoldingEngine engine;
        ServerPlayer player;io.netty.channel.embedded.EmbeddedChannel channel;
        RoomConversationEngine original;boolean installed,closed;WorldRecordingService store;
        ConversationRoomSnapshot room;Request first,later;MemoryReadSession access,descendantAccess;
        MemoryReadSession.Page page,descendant;InterpretationReadRecords.Page candidates;
        NativeInterpretationSeal seal;NativeMemorySeal nested;List<RoomEvidenceReference> refs;
        ProjectionWorkerCapability worker;Work work;
        CompletableFuture<ClaimResult> claiming;CompletableFuture<ProjectionRecords.Result> committing;
        CompletableFuture<MemoryReadSession.Page> reading;CompletableFuture<InterpretationReadRecords.Page> interpreting;
        CompletableFuture<Optional<NativeInterpretationSeal>> sealing;CompletableFuture<Optional<NativeMemorySeal>> nesting;
        CompletableFuture<Boolean> preparing;int phase,attempts,reprocessAttempts;long capturedBefore;UUID sourceId;
        Fixture(GameTestHelper helper){this.helper=helper;server=helper.getLevel().getServer();engine=new HoldingEngine(server);}
        void tick(){
            if(closed)return;java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            try{advance();}catch(Exception|AssertionError failure){int failed=phase;close();helper.fail("Native interpretation phase "+failed+": "+failure);}
        }
        void advance()throws Exception{
            if(phase==0){
                require(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString().replace('\\','/').contains("/build/"),"Build fixture only");
                store=RecordingRuntime.current(server).orElse(null);if(RecordedMemoryAccess.readableDataset(store).isEmpty())return;
                require(MemoryFoundationSettings.mode()==MemoryFoundationSettings.Mode.PERSONAL,"PERSONAL fixture");
                require(!RecordingRuntime.projectionEnabled(server)&&!RecordingRuntime.embeddingEnabled(server),"Background model workers remain OFF");
                require(!RecordingRuntime.retrievalForegroundBlocked(server),"Not NEW activation");
                var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(),"TypedReader"),false);
                player=new ServerPlayer(server,helper.getLevel(),cookie.gameProfile(),cookie.clientInformation());
                var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
                channel=new io.netty.channel.embedded.EmbeddedChannel(connection);
                net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
                server.getPlayerList().placeNewPlayer(connection,player,cookie);ConversationRooms.INSTANCE.memberships(player);
                original=RoomConversationEngineRouter.INSTANCE.engine();installed=RoomConversationEngineRouter.INSTANCE.available();setEngine(engine,true);
                room=ConversationRooms.INSTANCE.create(player,RoomType.PRIVATE,List.of(ResourceLocation.parse("mythictrpg:demeter")),RecordingScope.TEST_RECORDING);
                ConversationRooms.INSTANCE.selectPrivate(player,room.roomId());capturedBefore=store.health().highWatermark();
                ConversationRooms.INSTANCE.privateText(player,MARKER);first=engine.requests.getFirst();
                worker=store.registerProjectionWorker("native-interpretation-runtime","fixture-v1");phase=1;
            }else if(phase==1){
                if(store.health().highWatermark()<=capturedBefore)return;
                claiming=store.projectionPort().claimWork(worker,new WorkBudget(1,4096,60)).toCompletableFuture();phase=2;
            }else if(phase==2){
                if(!claiming.isDone())return;var claim=claiming.join();
                if(claim.work().isEmpty()&&++attempts<16){phase=1;return;}
                work=claim.work().orElseThrow();var target=target(work);require(target.text().equals(MARKER),"Only actual captured player input is extracted");sourceId=target.messageId();
                committing=store.projectionPort().commitProjection(work.token(),cards(work)).toCompletableFuture();phase=3;
            }else if(phase==3){
                if(!committing.isDone())return;require(committing.join().status()==ProjectionRecords.Status.STORED,"Actual lease commits input manifest and candidates");
                access=RecordedMemoryAccess.open(server,first).orElseThrow();reading=access.query(query(MARKER),Optional.empty(),new MemoryReadSession.Budget(4,16384));phase=4;
            }else if(phase==4){
                if(!reading.isDone())return;page=reading.join();require(page.entries().size()==1&&page.entries().getFirst().messageId().equals(sourceId)&&access.current(page),"Actual source Page");
                interpreting=access.interpretations(page,Optional.empty(),new MemoryReadSession.Budget(4,16384));phase=5;
            }else if(phase==5){
                if(!interpreting.isDone())return;candidates=interpreting.join();
                require(candidates.entries().size()==2&&access.current(candidates),"Two actual non-authoritative candidate layers");
                require(candidates.entries().stream().allMatch(e->e.authority()==InterpretationReadRecords.Authority.CANDIDATE),"No world fact promotion");
                sealing=access.sealInterpretations(List.of(page),List.of(),List.of(candidates));phase=6;
            }else if(phase==6){
                if(!sealing.isDone())return;seal=sealing.join().orElseThrow();require(access.current(seal),"Actually issued typed seal");
                require(!access.current(NativeInterpretationSeal.unregistered(seal.reference())),"Copied descriptor is not live authority");
                refs=NativeRoomEvidence.references(server,first,access,seal);preparing=NativeRoomEvidence.prepare(server,first,refs);phase=7;
            }else if(phase==7){
                if(!preparing.isDone())return;require(preparing.join()&&NativeRoomEvidence.current(server,first,refs.getFirst()),"Actual game owner revalidates typed proof");
                engine.reference=refs.getFirst();capturedBefore=store.health().highWatermark();
                engine.pending.getFirst().complete(new RoomConversationEngine.Result(first.roomId(),first.revision(),first.turnId(),List.of(new Speech(first.speakerGodId(),REPLY)),"[]",List.of(),""));phase=8;
            }else if(phase==8){
                if(engine.publications.stream().noneMatch(e->e.role().equals("NPC")&&e.text().equals(REPLY))||store.health().highWatermark()<=capturedBefore)return;
                require(engine.publications.stream().anyMatch(e->e.text().equals(REPLY)&&e.evidenceRefs().contains(refs.getFirst())),"Typed proof retained in actual emitted event");
                ConversationRooms.INSTANCE.privateText(player,"typed_followup_runtime");require(engine.requests.size()==2,"New actual turn");later=engine.requests.getLast();
                require(!access.current(seal)&&!NativeRoomEvidence.current(server,first,refs.getFirst()),"Previous turn cannot retain live authority");
                preparing=NativeRoomEvidence.prepare(server,later,refs);phase=9;
            }else if(phase==9){
                if(!preparing.isDone())return;require(preparing.join()&&NativeRoomEvidence.current(server,later,refs.getFirst()),"Same observer revalidates portable typed proof for new request");
                descendantAccess=RecordedMemoryAccess.open(server,later).orElseThrow();reading=descendantAccess.query(query(REPLY),Optional.empty(),new MemoryReadSession.Budget(4,16384));phase=10;
            }else if(phase==10){
                if(!reading.isDone())return;descendant=reading.join();
                require(descendant.entries().size()==1&&descendant.entries().getFirst().text().equals(REPLY)&&descendantAccess.current(descendant),"Captured typed reply is readable with original projection dependencies");
                nesting=descendantAccess.seal(List.of(descendant),List.of());phase=11;
            }else if(phase==11){
                if(!nesting.isDone())return;nested=nesting.join().orElseThrow();require(descendantAccess.current(nested),"RAW seal preserves transitive typed dependency");
                worker=store.registerProjectionWorker("native-interpretation-reprocess","fixture-v2");claiming=store.projectionPort().claimWork(worker,new WorkBudget(1,4096,60)).toCompletableFuture();phase=12;
            }else if(phase==12){
                if(!claiming.isDone())return;work=claiming.join().work().orElseThrow();
                // Production rightly prioritizes new pending work before DONE jobs from an older extractor.
                // Process those actual leases too; never fabricate a token or change queue priority for this test.
                if(!target(work).messageId().equals(sourceId)){
                    require(++reprocessAttempts<=8,"Bounded actual work reaches original target");
                    committing=store.projectionPort().commitProjection(work.token(),cards(work)).toCompletableFuture();phase=121;return;
                }
                require(!descendantAccess.current(descendant)&&!descendantAccess.current(nested)&&!NativeRoomEvidence.current(server,later,refs.getFirst()),"Projection mutation revokes typed-derived RAW page, RAW seal and portable guard");
                committing=store.projectionPort().commitProjection(work.token(),cards(work)).toCompletableFuture();phase=13;
            }else if(phase==121){
                if(!committing.isDone())return;require(committing.join().status()==ProjectionRecords.Status.STORED,"Pending actual work processed before old job");
                claiming=store.projectionPort().claimWork(worker,new WorkBudget(1,4096,60)).toCompletableFuture();phase=12;
            }else if(phase==13){
                if(!committing.isDone())return;require(committing.join().status()==ProjectionRecords.Status.STORED,"New extractor commits without rewriting RAW");
                preparing=NativeRoomEvidence.prepare(server,later,refs);phase=14;
            }else if(phase==14){
                if(!preparing.isDone())return;require(!preparing.join(),"Old candidate proof does not follow a replacement extractor");
                descendantAccess=RecordedMemoryAccess.open(server,later).orElseThrow();reading=descendantAccess.query(query(REPLY),Optional.empty(),new MemoryReadSession.Budget(4,16384));phase=15;
            }else{
                if(!reading.isDone())return;require(reading.join().entries().isEmpty(),"Dependent reply cannot launder replaced projection into fresh RAW recall");close();helper.succeed();
            }
        }
        public void close(){if(closed)return;closed=true;try{NativeRoomEvidence.clear(server);if(original!=null){ConversationRooms.INSTANCE.clear();setEngine(original,installed);}engine.stop();}
            finally{try{if(player!=null&&server.getPlayerList().getPlayer(player.getUUID())==player)server.getPlayerList().remove(player);}
                finally{if(channel!=null)channel.finishAndReleaseAll();if(active==this)active=null;}}}
    }
    private static Evidence target(Work work){return work.evidence().stream().filter(e->e.alias().equals(work.targetAlias())).findFirst().orElseThrow();}
    private static List<Candidate> cards(Work work){var quote=new Quote(work.targetAlias(),target(work).text());return List.of(new Candidate(Layer.EVENT,ClaimKind.SPEAKER_CLAIM,List.of(quote),List.of()),new Candidate(Layer.SUMMARY,ClaimKind.DIALOGUE_EPISODE,List.of(quote),List.of()));}
    private static MemoryReadSession.Query query(String text){return new MemoryReadSession.Query(text,Optional.empty(),Optional.empty());}
    private static final class HoldingEngine implements RoomConversationEngine {
        final MinecraftServer server;final List<Request> requests=new ArrayList<>();final List<CompletableFuture<RoomConversationEngine.Result>> pending=new ArrayList<>();
        final List<RoomDialogueEvent> publications=new ArrayList<>();RoomEvidenceReference reference;
        HoldingEngine(MinecraftServer server){this.server=server;}
        public CompletableFuture<RoomConversationEngine.Result> respond(Request request){requests.add(request);var future=new CompletableFuture<RoomConversationEngine.Result>();pending.add(future);return future;}
        public RoomDialogueEvent preparePublication(RoomDialogueEvent event){if(!event.role().equals("NPC")||!event.text().equals(REPLY))return event;
            var request=requests.stream().filter(r->event.turnId().filter(r.turnId()::equals).isPresent()).findFirst().orElseThrow();
            require(reference!=null&&NativeRoomEvidence.current(server,request,reference),"Publication requires current real proof before capture");return event.withEvidence(List.of(reference),Set.of());}
        public CompletableFuture<Boolean> prepareRecordedEvidence(Request request,List<RoomEvidenceReference> refs){return NativeRoomEvidence.prepare(server,request,refs);}
        public boolean recordedEvidenceCurrent(Request request,List<RoomEvidenceReference> refs){return refs.stream().allMatch(ref->NativeRoomEvidence.current(server,request,ref));}
        public void dialoguePublished(RoomDialogueEvent event){publications.add(event);}
        public CompletableFuture<SplitResult> chooseSplit(SplitRequest r){return CompletableFuture.completedFuture(new SplitResult(r.roomId(),r.revision(),r.godId(),"","","FIXTURE"));}
        public void stop(){for(int i=0;i<pending.size();i++)pending.get(i).complete(RoomConversationEngine.Result.failed(requests.get(i),"FIXTURE_END"));}
    }
    private static void setEngine(RoomConversationEngine engine,boolean installed){try{var field=RoomConversationEngineRouter.class.getDeclaredField("engine");field.setAccessible(true);field.set(RoomConversationEngineRouter.INSTANCE,engine);field=RoomConversationEngineRouter.class.getDeclaredField("installed");field.setAccessible(true);field.set(RoomConversationEngineRouter.INSTANCE,installed);}catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
