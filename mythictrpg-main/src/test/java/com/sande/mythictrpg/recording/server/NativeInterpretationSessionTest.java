package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.ProjectionRecords.*;
import com.sande.mythictrpg.recording.api.ProjectionRecords.Candidate;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Actual archive, projection, issued pages and async guards. No Minecraft runtime or model calls. */
public final class NativeInterpretationSessionTest {
    private static int checks;
    private static final ActorRef PLAYER=new ActorRef(ActorKind.PLAYER,UUID.randomUUID().toString());
    private static final ActorRef GOD=new ActorRef(ActorKind.GOD,"test:a");
    private static final Set<ActorRef> AUDIENCE=Set.of(PLAYER,GOD);
    private static final MemoryReadSession.Budget BUDGET=new MemoryReadSession.Budget(8,32768);
    private static final RecordingSettings SETTINGS=new RecordingSettings(RecordingSettings.Mode.SHADOW,256_000_000,2_000_000,.90,.95);
    private static final WorldRecordingService.CutoverBoundary BOUNDARY=new WorldRecordingService.CutoverBoundary("typed-session",Map.of("room-publication-v2",0L));
    private record Fixture(Path root,UUID world,WorldRecordingService store,ProducerCapability producer,
                           ProjectionWorkerCapability worker,UUID conversation) implements AutoCloseable {
        public void close()throws Exception{await(store.closeAsync());}
    }
    private record Selection(RecordedMemoryAccess.Session session,MemoryReadSession.Page raw,InterpretationReadRecords.Page candidates) { }

    public static void main(String[] args)throws Exception {
        Path parent=Path.of(args.length==0?"build/native-interpretation-session-test":args[0]).toAbsolutePath().normalize();
        if(!parent.toString().replace('\\','/').contains("/build/"))throw new IllegalArgumentException("TEST_REQUIRES_BUILD_DIRECTORY");
        Files.createDirectories(parent);Path root=Files.createTempDirectory(parent,"typed-session-");
        identity(root.resolve("identity"));projectionFence(root.resolve("projection"));
        withdrawal(root.resolve("withdrawal"));completionRace(root.resolve("cancel"),true);
        completionRace(root.resolve("turn"),false);ownerRace(root.resolve("owner"));
        descendant(root.resolve("descendant"));semanticSeed(root.resolve("semantic"));cacheKinds();
        System.out.println("NativeInterpretationSessionTest: "+checks+" checks passed; fixtures="+root);
    }
    private static void identity(Path root)throws Exception {
        try(var f=fixture(root)){
            Work original=project(f,"needle original promise");
            var s=selected(f);var copied=new InterpretationReadRecords.Page(s.candidates().status(),s.candidates().entries(),s.candidates().next());
            check(await(s.session().sealInterpretations(List.of(s.raw()),List.of(),List.of(copied))).isEmpty(),"equal candidate page is not issued identity");
            check(await(s.session().seal(List.of(s.raw()),List.of())).isEmpty(),"failed typed attempt consumes the shared RAW allowance");
            var foreign=selected(f);
            check(await(foreign.session().sealInterpretations(List.of(foreign.raw()),List.of(),List.of(s.candidates()))).isEmpty(),"foreign session candidate page rejected");
            var repeat=selected(f);
            check(await(repeat.session().sealInterpretations(List.of(),List.of(),List.of(repeat.candidates(),repeat.candidates()))).isEmpty(),"same actual page twice rejected before writer");
            var a=selected(f);var second=await(a.session().interpretations(a.raw(),Optional.empty(),BUDGET));
            check(second.entries().equals(a.candidates().entries())&&a.session().current(second),"separate actual page can contain the same candidates");
            long generation=f.store().projectionGeneration();
            var seal=await(a.session().sealInterpretations(List.of(a.raw()),List.of(),List.of(a.candidates(),second))).orElseThrow();
            check(a.session().current(seal)&&f.store().projectionGeneration()==generation,"typed evidence write does not mutate projection generation or invalidate itself");
            check(!a.session().current(NativeInterpretationSeal.unregistered(seal.reference())),"syntax-only copy cannot inherit live seal identity");
            check(await(a.session().seal(List.of(a.raw()),List.of())).isEmpty(),"successful typed issuance blocks a second RAW issuance");
            var rawFirst=selected(f);check(await(rawFirst.session().seal(List.of(rawFirst.raw()),List.of())).isPresent(),"ordinary RAW issuance still works");
            check(await(rawFirst.session().sealInterpretations(List.of(),List.of(),List.of(rawFirst.candidates()))).isEmpty(),"RAW-first blocks typed second attempt");
            var ref=NativeInterpretationEvidence.decode(seal.reference());var next=session(f,request(),Runnable::run,new AtomicBoolean(true),refs->CompletableFuture.completedFuture(true),refs->true);
            check(await(next.prepareNativeInterpretationReference(ref))&&next.currentNativeInterpretationReference(ref),"new turn prepares actual persisted typed descriptor");
            check(!await(next.prepareNativeInterpretationReference(new NativeInterpretationEvidence.Reference(1,ref.worldId(),ref.datasetId(),UUID.randomUUID(),ref.manifestHash()))),"fabricated typed descriptor cannot create issuance");
            var legacy=new RecordedMemoryAccess.Session(f.store(),scope(f),()->true,Runnable::run,()->true,refs->CompletableFuture.completedFuture(true),refs->true);
            var legacyRaw=raw(legacy,"needle");var legacyCandidates=await(legacy.interpretations(legacyRaw,Optional.empty(),BUDGET));
            check(await(legacy.sealInterpretations(List.of(),List.of(),List.of(legacyCandidates))).isEmpty(),"compat constructor cannot mint typed evidence without a game request");
            check(await(f.store().inspectMessage(target(original).messageId(),4096)).orElseThrow().equals(target(original).text()),"typed issuance leaves full RAW unchanged");
        }
    }
    private static void projectionFence(Path root)throws Exception {
        try(var f=fixture(root)){
            project(f,"needle initial classification");var selected=selected(f);
            var seal=await(selected.session().sealInterpretations(List.of(selected.raw()),List.of(),List.of(selected.candidates()))).orElseThrow();
            var ref=NativeInterpretationEvidence.decode(seal.reference());var prepared=newSession(f);
            check(await(prepared.prepareNativeInterpretationReference(ref)),"typed portable proof initially current");
            var rawOnly=newSession(f);var ordinary=raw(rawOnly,"needle");var rawSeal=await(rawOnly.seal(List.of(ordinary),List.of())).orElseThrow();
            project(f,"unrelated later classification");
            check(!selected.session().current(selected.candidates())&&!selected.session().current(seal)
                    &&!prepared.currentNativeInterpretationReference(ref),"projection change invalidates old typed page, seal and portable cache");
            check(rawOnly.current(ordinary)&&rawOnly.current(rawSeal),"unrelated projection change does not invalidate independent ordinary RAW");
            check(await(newSession(f).prepareNativeInterpretationReference(ref)),"new request revalidates unchanged persisted candidate rather than treating runtime generation as durable revision");
        }
    }
    private static void withdrawal(Path root)throws Exception {
        try(var f=fixture(root)){
            Work context=project(f,"unquoted contextual statement");Work main=project(f,"needle actual target");
            check(main.evidence().size()==2,"target extraction has an unquoted optional input");
            var selected=selected(f);var seal=await(selected.session().sealInterpretations(List.of(selected.raw()),List.of(),List.of(selected.candidates()))).orElseThrow();
            var ref=NativeInterpretationEvidence.decode(seal.reference());var prepared=newSession(f);
            check(await(prepared.prepareNativeInterpretationReference(ref)),"all-input candidate initially accepted");
            var old=target(context);var pending=f.store().invalidateKnowledge(f.producer(),new KnowledgeInvalidation(old.source(),old.knowledgeReceiptId(),1,"OPTIONAL_INPUT_WITHDRAWN"));
            check(!selected.session().current(seal)&&!prepared.currentNativeInterpretationReference(ref),"pending unquoted withdrawal fences live typed authorities before completion");
            check(await(pending).status()==RecordingRecords.Status.STORED,"actual optional input withdrawal persisted");
            check(!await(newSession(f).prepareNativeInterpretationReference(ref)),"fresh typed preparation cannot ignore removed unquoted context");
            check(raw(newSession(f),"needle").entries().size()==1,"independent target speech remains readable");
        }
    }
    private static void completionRace(Path root,boolean cancel)throws Exception {
        try(var f=fixture(root)){
            project(f,"needle asynchronous issuance");var delayed=new AtomicBoolean(false);var queue=new LinkedBlockingQueue<Runnable>();var live=new AtomicBoolean(true);
            Consumer<Runnable> dispatch=task->{if(delayed.get())queue.add(task);else task.run();};
            var session=session(f,request(),dispatch,live,refs->CompletableFuture.completedFuture(true),refs->true);
            var seed=raw(session,"needle");var page=await(session.interpretations(seed,Optional.empty(),BUDGET));delayed.set(true);
            long before=f.store().health().highWatermark();var result=session.sealInterpretations(List.of(seed),List.of(),List.of(page));
            var callback=queue.poll(5,TimeUnit.SECONDS);check(callback!=null,"typed durable completion queued on game dispatcher");
            if(cancel)check(result.cancel(false),"caller cancels typed result before dispatch");else live.set(false);
            callback.run();
            check(f.store().health().highWatermark()>before,"late bounded issuance may persist without giving live permission");
            check(cancel?result.isCancelled():await(result).isEmpty(),"cancel or changed request blocks late typed capability");
            check(await(session.seal(List.of(seed),List.of())).isEmpty(),"late completion cannot reopen the shared single issuance allowance");
        }
    }
    private static void ownerRace(Path root)throws Exception {
        try(var f=fixture(root)){
            project(f,"needle candidate for mixed proof");
            var leaf=new RoomEvidenceReference("CONTENT_DISCLOSURE_V1","{\"fixture\":\"profile\"}");
            capture(f,GOD,"profiled source speech",List.of(leaf),Set.of());
            var allow=new AtomicBoolean(true);var hold=new AtomicBoolean(false);var waiting=new CompletableFuture<Boolean>();var ownerStarted=new CountDownLatch(1);
            Function<List<RoomEvidenceReference>,CompletableFuture<Boolean>> prepare=refs->{
                if(hold.get()&&refs.contains(leaf)){ownerStarted.countDown();return waiting;}return CompletableFuture.completedFuture(true);};
            var session=session(f,request(),Runnable::run,new AtomicBoolean(true),prepare,refs->!refs.contains(leaf)||allow.get());
            var seed=raw(session,"needle");var page=await(session.interpretations(seed,Optional.empty(),BUDGET));var profiled=raw(session,"profiled");
            check(profiled.entries().size()==1,"actual owner-approved profiled RAW can accompany independent native candidate");
            hold.set(true);var pending=session.sealInterpretations(List.of(seed,profiled),List.of(),List.of(page));
            check(ownerStarted.await(5,TimeUnit.SECONDS),"typed issuer returns complete mixed owner leaves for async preparation");
            allow.set(false);waiting.complete(true);
            check(await(pending).isEmpty(),"owner current denial after successful async preparation cannot issue typed seal");
            var good=newSession(f);var goodSeed=raw(good,"needle");var goodPage=await(good.interpretations(goodSeed,Optional.empty(),BUDGET));
            check(await(good.sealInterpretations(List.of(),List.of(),List.of(goodPage))).isPresent(),"unrelated candidate-only issuance remains usable");
        }
    }
    private static void descendant(Path root)throws Exception {
        try(var f=fixture(root)){
            project(f,"needle descendant foundation");var selected=selected(f);
            var typed=await(selected.session().sealInterpretations(List.of(),List.of(),List.of(selected.candidates()))).orElseThrow();
            capture(f,GOD,"retelling my candidate interpretation",List.of(typed.reference()),Set.of());
            var reader=newSession(f);var raw=raw(reader,"retelling");
            check(raw.entries().size()==1&&reader.current(raw),"actual typed-dependent output is readable through verified ancestry");
            var rawSeal=await(reader.seal(List.of(raw),List.of())).orElseThrow();
            var portable=NativeMemoryEvidence.decode(rawSeal.reference());var prepared=newSession(f);
            check(await(prepared.prepareNativeReference(portable))&&prepared.currentNativeReference(portable),"RAW seal preserves transitive typed authority");
            project(f,"new projection changes runtime fence");
            check(!reader.current(raw)&&!reader.current(rawSeal)&&!prepared.currentNativeReference(portable),"typed descendant RAW page, seal and portable cache inherit projection fence");
            check(!raw(newSession(f),"retelling").entries().isEmpty(),"fresh request can validate unchanged typed dependency again");
        }
    }
    private static void semanticSeed(Path root)throws Exception {
        try(var f=fixture(root)){
            project(f,"needle semantic candidate");
            var space=new EmbeddingRecords.ModelSpace("fixture:typed","a".repeat(64),3,EmbeddingRecords.ENCODER_VERSION);
            var worker=f.store().registerEmbeddingWorker("typed-session",space);var claim=await(f.store().embeddingPort().claimWork(worker,45));
            check(claim.status()==EmbeddingRecords.Status.CLAIMED,"actual semantic input claimed");
            check(await(f.store().embeddingPort().commitEmbedding(claim.work().orElseThrow().token(),new float[]{1,0,0})).status()==EmbeddingRecords.Status.STORED,"fixture vector persisted through normal worker");
            var enabled=new AtomicBoolean(true);var request=request();var session=new RecordedMemoryAccess.Session(f.store(),scope(f),()->true,Runnable::run,()->true,
                    refs->CompletableFuture.completedFuture(true),refs->true,enabled::get,id->Optional.empty(),request);
            var query=query("different words");var vector=new EmbeddingRecords.QueryVector(space,RecordingRecords.sha256(query.text()),new float[]{1,0,0});
            var seed=await(session.semantic(query,vector,Optional.empty(),BUDGET));var page=await(session.interpretations(seed,Optional.empty(),BUDGET));
            check(seed.entries().size()==1&&page.entries().size()==3,"actual semantic seeds produce actual candidate page");
            var seal=await(session.sealInterpretations(List.of(),List.of(seed),List.of(page))).orElseThrow();
            check(session.current(seal),"semantic plus interpretation pages seal without forged RAW adapter");
            enabled.set(false);check(!session.current(seal),"original semantic seed gate remains part of candidate seal authority");
        }
    }
    private static void cacheKinds(){
        var world=UUID.randomUUID();var dataset=UUID.randomUUID();String hash="b".repeat(64);
        var raw=NativeMemoryEvidence.encode(new NativeMemoryEvidence.Reference(1,world,dataset,UUID.randomUUID(),hash));
        var typed=NativeInterpretationEvidence.encode(new NativeInterpretationEvidence.Reference(1,world,dataset,UUID.randomUUID(),hash));
        var cache=new NativeRoomEvidence.PreparedCache<String>();var current=new AtomicBoolean(true);var seen=new ArrayList<RoomEvidenceReference>();
        var port=new NativeRoomEvidence.Port(){
            public CompletableFuture<Boolean> prepare(RoomEvidenceReference ref){seen.add(ref);return CompletableFuture.completedFuture(true);}
            public boolean current(RoomEvidenceReference ref){return !ref.equals(typed)||current.get();}
        };
        check(cache.prepare("scope-A",List.of(raw,typed),()->Optional.of(port),()->true,Runnable::run).join(),"mixed RAW and typed descriptor kinds use one atomic bounded cache batch");
        check(seen.equals(List.of(raw,typed))&&cache.current("scope-A",typed)&&cache.current("scope-A",raw),"both exact descriptors prepared, never treated interchangeably");
        check(!cache.current("scope-B",typed),"typed cache remains scope isolated");current.set(false);
        check(!cache.current("scope-A",typed)&&cache.current("scope-A",raw),"typed invalidation does not revoke unrelated RAW entry");
        var malformed=new RoomEvidenceReference(NativeInterpretationEvidence.KIND,"{}");
        check(!cache.prepare("malformed",List.of(malformed),()->Optional.of(port),()->true,Runnable::run).join()&&seen.size()==2,"malformed typed proof denied before opener/owner work");
        check(!cache.prepare("unknown",List.of(new RoomEvidenceReference("UNSUPPORTED","{}")),()->Optional.of(port),()->true,Runnable::run).join(),"unknown descriptor kind never becomes cache authority");
        var late=new CompletableFuture<Boolean>();var pending=cache.prepare("late",List.of(typed),()->Optional.of(new NativeRoomEvidence.Port(){
            public CompletableFuture<Boolean> prepare(RoomEvidenceReference ref){return late;}
            public boolean current(RoomEvidenceReference ref){return true;}
        }),()->true,Runnable::run);pending.cancel(false);late.complete(true);
        check(!cache.current("late",typed),"cancelled typed portable preparation cannot register a late permission");
    }
    private static Fixture fixture(Path root)throws Exception {
        Files.createDirectories(root);UUID world=UUID.randomUUID();var store=await(WorldRecordingService.open(root,world,SETTINGS,BOUNDARY));
        check(store.health().state()==WorldRecordingService.State.READY,"fixture archive READY");
        return new Fixture(root,world,store,store.registerProducer("room-publication-v2",Set.of("ROOM_PRIVATE"),Set.of(SourceKind.DIALOGUE_DIRECT,SourceKind.DERIVED_SPEECH)),
                store.registerProjectionWorker("typed-session","v1"),UUID.randomUUID());
    }
    private static UUID capture(Fixture f,ActorRef actor,String text,List<RoomEvidenceReference> refs,Set<UUID> parents)throws Exception {
        UUID id=UUID.randomUUID();Instant now=Instant.now();
        var context=new PublicationContext(f.store().runtimeEpoch(),1,"STANDARD",AUDIENCE,AUDIENCE,Map.of(),
                refs.stream().map(r->new EvidencePointer(r.kind(),r.payload())).toList(),parents,Map.of(),"UNKNOWN","PERSONAL");
        var envelope=new ConversationEnvelope(f.world(),f.store().datasetId().orElseThrow(),f.conversation(),"ROOM_PRIVATE","ACTUAL_LISTENERS_ONLY",1,1,true,false,"fixture");
        var raw=new RawMessage(id,Optional.empty(),0,actor,text,now,actor.kind()==ActorKind.PLAYER?MessageKind.ACCEPTED_INPUT:MessageKind.DELIVERED_OUTPUT,id.toString(),context);
        var receipts=new ArrayList<DeliveryReceipt>();for(var recipient:AUDIENCE){String display=recipient.kind()==ActorKind.GOD?text:"[A] Player: "+text;
            receipts.add(new DeliveryReceipt(UUID.randomUUID(),recipient,recipient.kind()==ActorKind.GOD?"GAME_HEARD":"CHAT",now,1,DeliveryStatus.SERVER_DISPATCHED,new DeliveryView(display,List.of(display)),Set.of(0)));}
        check(await(f.store().capture(f.producer(),envelope,raw,receipts)).status()==RecordingRecords.Status.STORED,"full-audience native message recorded");return id;
    }
    private static Work project(Fixture f,String text)throws Exception {
        UUID id=capture(f,PLAYER,text,List.of(),Set.of());
        var claim=await(f.store().projectionPort().claimWork(f.worker(),new WorkBudget(6,16384,45)));
        check(claim.status()==ProjectionRecords.Status.CLAIMED,"projection work claimed: "+claim.status());var work=claim.work().orElseThrow();
        check(target(work).messageId().equals(id),"leased target is fixture input, not an unrelated queued source");
        var quote=new Quote(work.targetAlias(),target(work).text());var candidates=new ArrayList<Candidate>();
        for(var layer:Layer.values())candidates.add(new Candidate(layer,ClaimKind.DIALOGUE_EPISODE,List.of(quote),List.of()));
        check(await(f.store().projectionPort().commitProjection(work.token(),candidates)).status()==ProjectionRecords.Status.STORED,"three bounded non-authoritative candidates committed");return work;
    }
    private static Evidence target(Work work){return work.evidence().stream().filter(e->e.alias().equals(work.targetAlias())).findFirst().orElseThrow();}
    private static Selection selected(Fixture f)throws Exception {var session=newSession(f);var raw=raw(session,"needle");
        var page=await(session.interpretations(raw,Optional.empty(),BUDGET));check(!page.entries().isEmpty()&&session.current(page),"actual candidate page issued from actual raw seed");return new Selection(session,raw,page);}
    private static Request request(){var god=ResourceLocation.parse(GOD.id());return new Request(UUID.randomUUID(),1,UUID.randomUUID(),UUID.fromString(PLAYER.id()),"Player",List.of(god),god,"remember",List.of(),true,true,false,
            List.of(new GodState(god,"R_NEUTRAL","E_NEUTRAL","",null)),false,Set.of(UUID.fromString(PLAYER.id())));}
    private static RecordedRoomSearch.Scope scope(Fixture f){return new RecordedRoomSearch.Scope(f.store().datasetId().orElseThrow(),GOD.id(),AUDIENCE,false,"STANDARD","PERSONAL");}
    private static RecordedMemoryAccess.Session newSession(Fixture f){return session(f,request(),Runnable::run,new AtomicBoolean(true),refs->CompletableFuture.completedFuture(true),refs->true);}
    private static RecordedMemoryAccess.Session session(Fixture f,Request request,Consumer<Runnable> dispatch,AtomicBoolean live,
            Function<List<RoomEvidenceReference>,CompletableFuture<Boolean>> prepare,Predicate<List<RoomEvidenceReference>> current){
        return new RecordedMemoryAccess.Session(f.store(),scope(f),()->true,dispatch,live::get,prepare,current,()->true,id->Optional.empty(),request);}
    private static MemoryReadSession.Query query(String text){return new MemoryReadSession.Query(text,Optional.empty(),Optional.empty());}
    private static MemoryReadSession.Page raw(RecordedMemoryAccess.Session session,String text)throws Exception {
        var page=await(session.query(query(text),Optional.empty(),BUDGET));check(page.status()==MemoryReadSession.Status.PARTIAL&&session.current(page),"actual RAW page current: "+page.status());return page;}
    private static <T>T await(CompletionStage<T> value)throws Exception{return value.toCompletableFuture().get(10,TimeUnit.SECONDS);}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
