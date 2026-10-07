package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.MemoryReadSession.*;
import com.sande.mythictrpg.recording.api.ProjectionRecords.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Exact actor selectors are bounded retrieval predicates, never extra knowledge or provenance authority. */
public final class RecordedActorSelectionTest {
    private static int checks;
    private static final ActorRef A=new ActorRef(ActorKind.PLAYER,UUID.randomUUID().toString()),B=new ActorRef(ActorKind.PLAYER,UUID.randomUUID().toString()),GOD=new ActorRef(ActorKind.GOD,"test:a");
    private static final EmbeddingRecords.ModelSpace SPACE=new EmbeddingRecords.ModelSpace("fixture:actor","a".repeat(64),3,EmbeddingRecords.ENCODER_VERSION);
    private static final Set<ActorRef> AUDIENCE=Set.of(A,B,GOD);
    private static final Budget BUDGET=new Budget(8,32768);
    private record Fixture(Path root,UUID world,WorldRecordingService store,ProducerCapability producer,UUID room)implements AutoCloseable{
        public void close()throws Exception{await(store.closeAsync());}
    }
    public static void main(String[] args)throws Exception{
        Path parent=Path.of(args.length==0?"build/recorded-actor-selection-test":args[0]).toAbsolutePath().normalize();
        if(!parent.toString().replace('\\','/').contains("/build/"))throw new IllegalArgumentException("TEST_REQUIRES_BUILD_DIRECTORY");
        Files.createDirectories(parent);Path root=Files.createTempDirectory(parent,"actors-");
        contract();filters(root.resolve("filters"));payloadBudget(root.resolve("payload"));longtail(root.resolve("longtail"));
        System.out.println("RecordedActorSelectionTest: "+checks+" checks passed; fixtures="+root);
    }
    private static void contract(){
        check(new Query("x",Optional.empty(),Optional.empty()).actorSelection().equals(ActorSelection.ANY),"old constructor retains ANY semantics");
        var source=new HashSet<>(Set.of(A));var selected=new ActorSelection(Optional.of(ActorKind.PLAYER),source,Set.of(B));source.clear();
        check(selected.matches(A)&&!selected.matches(B)&&!selected.matches(GOD)&&selected.include().equals(Set.of(A)),"kind/include/exclude are immutable AND predicates");
        illegal(()->new ActorSelection(Optional.empty(),Set.of(A),Set.of(A)),"overlapping include and exclude rejected");
        var many=new HashSet<ActorRef>();for(int i=0;i<17;i++)many.add(new ActorRef(ActorKind.PLAYER,UUID.randomUUID().toString()));
        illegal(()->new ActorSelection(Optional.empty(),many,Set.of()),"selector entries are bounded at sixteen");
        illegal(()->new ActorRef(ActorKind.PLAYER,"Steve"),"player display names are not accepted as actor identity");
    }
    private static void filters(Path root)throws Exception{
        try(var f=fixture(root)){
            var projection=f.store().registerProjectionWorker("actor","v1");var embedding=f.store().registerEmbeddingWorker("actor",SPACE);
            UUID first=capture(f,A,"needle player A promise",Set.of());process(f,projection,embedding);
            capture(f,B,"needle player B reply",Set.of());process(f,projection,embedding);
            capture(f,GOD,"needle divine answer",Set.of(first));process(f,projection,embedding);
            var onlyA=include(A);var onlyGod=kind(ActorKind.GOD);var playersExceptA=new ActorSelection(Optional.of(ActorKind.PLAYER),Set.of(),Set.of(A));
            check(actors(raw(session(f),query(ActorSelection.ANY))).equals(AUDIENCE),"default literal search keeps all authorized speakers");
            check(actors(raw(session(f),query(onlyA))).equals(Set.of(A)),"RAW exact included actor only");
            check(actors(raw(session(f),query(playersExceptA))).equals(Set.of(B)),"RAW kind plus exclusion only");
            check(actors(raw(session(f),query(onlyGod))).equals(Set.of(GOD)),"RAW God selector refers to actual speaker, not observer of every message");
            // Real optional FTS backfill preserves the same actor filter and bounded lane partition.
            for(int i=0;i<4;i++)if(await(f.store().pumpLexicalIndex()).caughtUp())break;
            check(actors(raw(session(f),query(onlyA))).equals(Set.of(A)),"indexed lexical lane uses the same metadata actor selection");
            var selected=semantic(session(f),query(onlyA),1);
            check(selected.entries().size()==1&&selected.entries().getFirst().speaker().equals(A),"semantic excluded high-score candidates cannot consume selected actor result budget");
            check(semantic(session(f),query(playersExceptA),8).entries().stream().map(SemanticReadRecords.Entry::speaker).collect(java.util.stream.Collectors.toSet()).equals(Set.of(B)),"semantic kind/exclusion uses actual actor metadata");
            var scoped=session(f);var seed=semantic(scoped,query(onlyA),8);var interpreted=await(scoped.interpretations(seed,Optional.empty(),BUDGET));
            check(interpreted.entries().stream().flatMap(e->e.quotes().stream()).anyMatch(q->q.actualActor().equals(GOD)),"seed actor selection does not strip later God interpretation or required quote provenance");
            check(interpreted.entries().stream().anyMatch(e->e.inputs().size()>1)&&scoped.current(interpreted),"all-input interpretation closure remains authorized and intact");
            var cursors=session(f);var any=raw(cursors,query(ActorSelection.ANY));
            check(await(cursors.query(query(onlyA),any.next(),BUDGET)).status()==MemoryReadSession.Status.STALE,"RAW continuation cannot change actor selector");
            var anySemantic=semantic(cursors,query(ActorSelection.ANY),8);
            check(await(cursors.semantic(query(onlyA),vector(),anySemantic.next(),BUDGET)).status()==MemoryReadSession.Status.STALE,"semantic continuation binds full selector as well as vector/query text");
            var scope=new RecordedRoomSearch.Scope(f.store().datasetId().orElseThrow(),"test:unheard",Set.of(A,new ActorRef(ActorKind.GOD,"test:unheard")),false,"STANDARD","PERSONAL");
            var denied=new RecordedMemoryAccess.Session(f.store(),scope,()->true,Runnable::run,()->true,refs->CompletableFuture.completedFuture(refs.isEmpty()),List::isEmpty);
            check(raw(denied,query(onlyA)).entries().isEmpty()&&semantic(denied,query(onlyA),8).entries().isEmpty(),"actor selector cannot grant another God access");
            var personal=new RecordedRoomSearch.Scope(f.store().datasetId().orElseThrow(),GOD.id(),Set.of(A,GOD),false,"STANDARD","PERSONAL");
            var watch=new RecordedMemoryAccess.Session(f.store(),personal,()->true,Runnable::run,()->true,refs->CompletableFuture.completedFuture(refs.isEmpty()),List::isEmpty);
            check(await(watch.observations(query(onlyA),Optional.empty(),BUDGET)).status()==MemoryReadSession.Status.UNAVAILABLE,"Watch does not confuse observation subject with actual dialogue speaker");
            check(await(watch.observations(query(ActorSelection.ANY),Optional.empty(),BUDGET)).status()==MemoryReadSession.Status.PARTIAL,"ANY Watch query retains existing optional read behavior");
            // Corrupt embedding actor metadata must fail before native body/vector processing.
            try(var db=db(f);var q=db.prepareStatement("UPDATE embedding_rows SET actual_actor_kind=?,actual_actor_id=? WHERE actual_actor_kind='GOD'")){
                q.setString(1,A.kind().name());q.setString(2,A.id());check(q.executeUpdate()==1,"fixture changes one stored actor binding");}
            check(semantic(session(f),query(onlyA),8).entries().size()==1,"spoofed embedding actor is denied by messages metadata equality");
            var frozen=session(f);capture(f,A,"needle late player A",Set.of());process(f,projection,embedding);
            check(raw(frozen,query(onlyA)).entries().size()==1&&semantic(frozen,query(onlyA),8).entries().size()==1,"actor selectors preserve frozen archive watermark across later matching captures/indexing");
        }
    }
    private static void payloadBudget(Path root)throws Exception{
        try(var f=fixture(root)){
            capture(f,A,"needle small selected player message",Set.of());
            for(int i=0;i<4;i++)capture(f,GOD,"needle "+i+"x".repeat(550000),Set.of());
            var page=raw(session(f),query(include(A)));
            check(page.entries().size()==1&&page.entries().getFirst().speaker().equals(A),"excluded actor bodies cannot exhaust the shared two-MiB source budget before a matching actor");
        }
    }
    private static void longtail(Path root)throws Exception{
        try(var f=fixture(root)){
            var worker=f.store().registerEmbeddingWorker("longtail",SPACE);
            UUID target=capture(f,A,"needle older selected player",Set.of());indexOnly(f,worker);
            for(int i=0;i<129;i++){capture(f,GOD,"needle later excluded God "+i,Set.of());indexOnly(f,worker);}
            Query wanted=query(include(A));var exact=session(f);
            var first=await(exact.query(wanted,Optional.empty(),BUDGET));
            check(first.status()==MemoryReadSession.Status.PARTIAL&&first.entries().isEmpty()&&first.next().isPresent(),"RAW actor exclusion stops at bounded 128-row window with continuation");
            var next=await(exact.query(wanted,first.next(),BUDGET));
            check(next.entries().size()==1&&next.entries().getFirst().messageId().equals(target),"RAW continuation reaches selected actor beyond 129 excluded speakers without skipping it");
            var semantic=session(f);var vectorFirst=await(semantic.semantic(wanted,vector(),Optional.empty(),BUDGET));
            check(vectorFirst.status()==MemoryReadSession.Status.PARTIAL&&vectorFirst.entries().isEmpty()&&vectorFirst.next().isPresent(),"semantic actor exclusion retains bounded independent vector continuation");
            var vectorNext=await(semantic.semantic(wanted,vector(),vectorFirst.next(),BUDGET));
            check(vectorNext.entries().size()==1&&vectorNext.entries().getFirst().messageId().equals(target),"semantic continuation reaches selected actor beyond 129 excluded vector candidates");
        }
    }
    private static void indexOnly(Fixture f,EmbeddingWorkerCapability worker)throws Exception{
        var claim=await(f.store().embeddingPort().claimWork(worker,45));
        check(claim.status()==EmbeddingRecords.Status.CLAIMED,"long-tail native vector leased");
        check(await(f.store().embeddingPort().commitEmbedding(claim.work().orElseThrow().token(),new float[]{1,0,0})).status()==EmbeddingRecords.Status.STORED,"long-tail native vector committed");
    }
    private static Fixture fixture(Path root)throws Exception{
        Files.createDirectories(root);UUID world=UUID.randomUUID();var store=await(WorldRecordingService.open(root,world,
                new RecordingSettings(RecordingSettings.Mode.SHADOW,512_000_000,2_000_000,.90,.95),new WorldRecordingService.CutoverBoundary("actor-test",Map.of())));
        check(store.health().state()==WorldRecordingService.State.READY,"actor fixture READY");
        return new Fixture(root,world,store,store.registerProducer("room-publication-v2",Set.of("ROOM_PRIVATE"),Set.of(SourceKind.DIALOGUE_DIRECT,SourceKind.DERIVED_SPEECH)),UUID.randomUUID());
    }
    private static UUID capture(Fixture f,ActorRef actor,String text,Set<UUID> parents)throws Exception{
        UUID id=UUID.randomUUID();Instant now=Instant.now();var context=new PublicationContext(f.store().runtimeEpoch(),1,"STANDARD",AUDIENCE,AUDIENCE,Map.of(),List.of(),parents,Map.of(),"UNKNOWN","PERSONAL");
        var envelope=new ConversationEnvelope(f.world(),f.store().datasetId().orElseThrow(),f.room(),"ROOM_PRIVATE","ACTUAL_LISTENERS_ONLY",1,1,true,false,"fixture");
        var raw=new RawMessage(id,Optional.empty(),0,actor,text,now,actor.kind()==ActorKind.PLAYER?MessageKind.ACCEPTED_INPUT:MessageKind.DELIVERED_OUTPUT,id.toString(),context);
        var view=new DeliveryView(text,List.of(text));var deliveries=new ArrayList<DeliveryReceipt>();
        for(var member:AUDIENCE)deliveries.add(new DeliveryReceipt(UUID.randomUUID(),member,member.kind()==ActorKind.GOD?"GAME_HEARD":"CHAT",now,1,DeliveryStatus.SERVER_DISPATCHED,view,Set.of(0)));
        check(await(f.store().capture(f.producer(),envelope,raw,deliveries)).status()==RecordingRecords.Status.STORED,"native actor fixture capture stored");return id;
    }
    private static void process(Fixture f,ProjectionWorkerCapability projection,EmbeddingWorkerCapability embedding)throws Exception{
        var claimed=await(f.store().projectionPort().claimWork(projection,new WorkBudget(6,16384,45)));check(claimed.status()==ProjectionRecords.Status.CLAIMED,"actor projection claimed");
        var work=claimed.work().orElseThrow();var target=work.evidence().stream().filter(e->e.alias().equals(work.targetAlias())).findFirst().orElseThrow();
        check(await(f.store().projectionPort().commitProjection(work.token(),List.of(new Candidate(Layer.EVENT,ClaimKind.DIALOGUE_EPISODE,List.of(new Quote(work.targetAlias(),target.text())),List.of())))).status()==ProjectionRecords.Status.STORED,"grounded actual actor projection stored");
        var indexed=await(f.store().embeddingPort().claimWork(embedding,45));check(indexed.status()==EmbeddingRecords.Status.CLAIMED,"actor embedding claimed");
        check(await(f.store().embeddingPort().commitEmbedding(indexed.work().orElseThrow().token(),new float[]{1,0,0})).status()==EmbeddingRecords.Status.STORED,"fake actor vector stored");
    }
    private static RecordedMemoryAccess.Session session(Fixture f){
        var scope=new RecordedRoomSearch.Scope(f.store().datasetId().orElseThrow(),GOD.id(),AUDIENCE,false,"STANDARD","PERSONAL");
        return new RecordedMemoryAccess.Session(f.store(),scope,()->true,Runnable::run,()->true,refs->CompletableFuture.completedFuture(refs.isEmpty()),List::isEmpty);
    }
    private static ActorSelection include(ActorRef actor){return new ActorSelection(Optional.empty(),Set.of(actor),Set.of());}
    private static ActorSelection kind(ActorKind kind){return new ActorSelection(Optional.of(kind),Set.of(),Set.of());}
    private static Query query(ActorSelection actors){return new Query("needle",Optional.empty(),Optional.empty(),actors);}
    private static EmbeddingRecords.QueryVector vector(){return new EmbeddingRecords.QueryVector(SPACE,RecordingRecords.sha256("needle"),new float[]{1,0,0});}
    private static MemoryReadSession.Page raw(MemoryReadSession session,Query query)throws Exception{var result=await(session.query(query,Optional.empty(),BUDGET));check(result.status()==MemoryReadSession.Status.PARTIAL,"RAW actor page partial");return result;}
    private static Set<ActorRef> actors(MemoryReadSession.Page page){return page.entries().stream().map(MemoryReadSession.Entry::speaker).collect(java.util.stream.Collectors.toSet());}
    private static SemanticReadRecords.Page semantic(MemoryReadSession session,Query query,int rows)throws Exception{var result=await(session.semantic(query,vector(),Optional.empty(),new Budget(rows,32768)));check(result.status()==MemoryReadSession.Status.PARTIAL,"semantic actor page partial");return result;}
    private static Connection db(Fixture f)throws Exception{return DriverManager.getConnection("jdbc:sqlite:"+f.root().resolve("mythictrpg-recording-v2").resolve(f.store().datasetId().orElseThrow().toString()).resolve("recording.sqlite"));}
    private static void illegal(Runnable task,String reason){boolean failed=false;try{task.run();}catch(IllegalArgumentException expected){failed=true;}check(failed,reason);}
    private static <T>T await(CompletionStage<T> future)throws Exception{return future.toCompletableFuture().get(20,TimeUnit.SECONDS);}
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
}
