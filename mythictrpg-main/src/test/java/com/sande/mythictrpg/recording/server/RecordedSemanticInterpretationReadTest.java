package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.ProjectionRecords.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Actual native RAW -> fake vector -> issued semantic page -> existing grounded interpretation reader. */
public final class RecordedSemanticInterpretationReadTest {
    private static int checks;
    private static final String GOD="test:a",QUERY="지난 결심이 달라졌니?";
    private static final ActorRef PLAYER=new ActorRef(ActorKind.PLAYER,UUID.randomUUID().toString());
    private static final EmbeddingRecords.ModelSpace SPACE=new EmbeddingRecords.ModelSpace("fixture:semantic","a".repeat(64),3,EmbeddingRecords.ENCODER_VERSION);
    private static final float[] VECTOR={1,0,0};
    private static final MemoryReadSession.Budget BUDGET=new MemoryReadSession.Budget(8,32768);
    private record Fixture(Path root,UUID world,WorldRecordingService store,ProducerCapability producer,UUID conversation,
                           UUID promise,UUID cancellation,Evidence original)implements AutoCloseable {
        public void close()throws Exception{await(store.closeAsync());}
    }
    public static void main(String[] args)throws Exception{
        Path parent=Path.of(args.length==0?"build/recorded-semantic-interpretation-read-test":args[0]).toAbsolutePath().normalize();
        if(!parent.toString().replace('\\','/').contains("/build/"))throw new IllegalArgumentException("TEST_REQUIRES_BUILD_DIRECTORY");
        Files.createDirectories(parent);Path root=Files.createTempDirectory(parent,"semantic-interpretations-");
        discovery(root.resolve("discovery"));capabilities(root.resolve("capabilities"));
        gate(root.resolve("gate"));projection(root.resolve("projection"));withdrawal(root.resolve("withdrawal"));
        supersession(root.resolve("supersession"));lateGate(root.resolve("late-gate"));defaults();
        System.out.println("RecordedSemanticInterpretationReadTest: "+checks+" checks passed; fixtures="+root);
    }
    private static void discovery(Path root)throws Exception{
        try(var f=fixture(root)){
            var session=session(f,new AtomicBoolean(true),Runnable::run);
            var exact=await(session.query(query(),Optional.empty(),BUDGET));
            check(exact.entries().isEmpty(),"semantic query deliberately has no literal RAW match");
            var seeds=semantic(session);check(seeds.entries().size()==1&&seeds.entries().getFirst().messageId().equals(f.promise()),"independent semantic index finds only the indexed promise");
            var page=await(session.interpretations(seeds,Optional.empty(),BUDGET));
            check(page.status()==MemoryReadSession.Status.PARTIAL&&page.entries().size()==6&&session.current(page),"semantic-only seed retrieves all grounded promise and later cancellation layers");
            var linked=page.entries().stream().filter(e->!e.links().isEmpty()).findFirst().orElseThrow();
            var link=linked.links().getFirst();
            check(link.relation()==Relation.CANCELS&&linked.authority()==InterpretationReadRecords.Authority.CANDIDATE,"later cancellation remains a candidate interpretation, not an authoritative fact");
            check(linked.quotes().stream().anyMatch(q->q.messageId().equals(f.promise())&&q.sourceAlias().equals(link.olderAlias()))
                    &&linked.quotes().stream().anyMatch(q->q.messageId().equals(f.cancellation())&&q.sourceAlias().equals(link.newerAlias())),"cancellation preserves actual old/new quote provenance without indexing the cancellation itself");
            check(page.entries().stream().mapToInt(RecordedInterpretationSearch::wireByteSize).sum()<=BUDGET.utf8Bytes(),"existing complete-card budget still applies to semantic-seeded interpretations");
        }
    }
    private static void capabilities(Path root)throws Exception{
        try(var f=fixture(root)){
            var session=session(f,new AtomicBoolean(true),Runnable::run);var seeds=semantic(session);
            var first=await(session.interpretations(seeds,Optional.empty(),new MemoryReadSession.Budget(1,32768)));
            check(first.entries().size()==1&&first.next().isPresent(),"issued semantic page receives a registered interpretation cursor");
            var clone=new SemanticReadRecords.Page(seeds.status(),seeds.entries(),seeds.next());
            check(await(session.interpretations(clone,Optional.empty(),BUDGET)).status()==MemoryReadSession.Status.STALE,"equal copied semantic page is not authority");
            var foreign=session(f,new AtomicBoolean(true),Runnable::run);
            check(await(foreign.interpretations(seeds,first.next(),BUDGET)).status()==MemoryReadSession.Status.STALE,"foreign session rejects original page and cursor");
            var otherSeeds=semantic(session);
            check(await(session.interpretations(otherSeeds,first.next(),BUDGET)).status()==MemoryReadSession.Status.STALE,"cursor binds original page identity, not equal semantic results");
            var raw=await(session.query(new MemoryReadSession.Query("약속",Optional.empty(),Optional.empty()),Optional.empty(),BUDGET));
            check(await(session.interpretations(raw,first.next(),BUDGET)).status()==MemoryReadSession.Status.STALE,"semantic cursor cannot be transferred into raw-seeded path");
            var next=await(session.interpretations(seeds,first.next(),new MemoryReadSession.Budget(1,32768)));
            check(next.entries().size()==1&&!next.entries().getFirst().memoryId().equals(first.entries().getFirst().memoryId()),"same issued semantic page resumes without repeated interpretation");
            check(!session.current(new InterpretationReadRecords.Page(first.status(),first.entries(),first.next())),"copied interpretation page remains unissued");
            check(await(session.interpretations(seeds,Optional.of(InterpretationReadRecords.Cursor.unregistered()),BUDGET)).status()==MemoryReadSession.Status.STALE,"unregistered interpretation cursor fails closed");
        }
    }
    private static void gate(Path root)throws Exception{
        try(var f=fixture(root)){
            var enabled=new AtomicBoolean(true);var session=session(f,enabled,Runnable::run);var seeds=semantic(session);
            var page=await(session.interpretations(seeds,Optional.empty(),BUDGET));
            enabled.set(false);
            check(!session.current(seeds)&&!session.current(page),"native semantic OFF invalidates both original seed and its interpretations");
            check(await(session.interpretations(seeds,page.next(),BUDGET)).status()==MemoryReadSession.Status.STALE,"semantic OFF rejects continuation before another read");
            var raw=await(session.query(new MemoryReadSession.Query("약속",Optional.empty(),Optional.empty()),Optional.empty(),BUDGET));
            check(session.current(raw)&&!await(session.interpretations(raw,Optional.empty(),BUDGET)).entries().isEmpty(),"semantic OFF does not disable independently authorized raw interpretation path");
        }
    }
    private static void projection(Path root)throws Exception{
        try(var f=fixture(root)){
            var session=session(f,new AtomicBoolean(true),Runnable::run);var seeds=semantic(session);
            var page=await(session.interpretations(seeds,Optional.empty(),BUDGET));
            var newer=f.store().registerProjectionWorker("fixture","v2");
            var claim=await(f.store().projectionPort().claimWork(newer,new WorkBudget(6,16384,45)));
            check(claim.status()==ProjectionRecords.Status.CLAIMED,"new extraction version changes authoritative projection work state");
            check(session.current(seeds)&&!session.current(page),"re-extraction stales interpretations without invalidating immutable semantic seed");
            check(await(session.interpretations(seeds,Optional.empty(),BUDGET)).status()==MemoryReadSession.Status.STALE,"session must reopen after projection generation changes");
        }
    }
    private static void withdrawal(Path root)throws Exception{
        try(var f=fixture(root)){
            var session=session(f,new AtomicBoolean(true),Runnable::run);var seeds=semantic(session);
            var page=await(session.interpretations(seeds,Optional.empty(),BUDGET));
            var pending=f.store().invalidateKnowledge(f.producer(),new KnowledgeInvalidation(f.original().source(),f.original().knowledgeReceiptId(),1,"WITHDRAW_SEMANTIC_SEED"));
            check(!session.current(seeds)&&!session.current(page),"queued source proof withdrawal immediately stales both page kinds");
            check(await(pending).status()==RecordingRecords.Status.STORED,"source proof withdrawal committed");
            check(semantic(session(f,new AtomicBoolean(true),Runnable::run)).entries().isEmpty(),"fresh semantic session cannot resurrect the withdrawn seed");
        }
    }
    private static void lateGate(Path root)throws Exception{
        try(var f=fixture(root)){
            var tasks=new LinkedBlockingQueue<Runnable>();var enabled=new AtomicBoolean(true);var session=session(f,enabled,tasks::add);
            var seedFuture=session.semantic(query(),vector(),Optional.empty(),BUDGET);runNext(tasks);var seed=await(seedFuture);
            check(seed.entries().size()==1,"fixture semantic page issued through queued game-thread dispatcher");
            var pageFuture=session.interpretations(seed,Optional.empty(),BUDGET);
            Runnable callback=tasks.poll(2,TimeUnit.SECONDS);check(callback!=null,"interpretation read callback waits at dispatcher");
            enabled.set(false);callback.run();
            check(await(pageFuture).status()==MemoryReadSession.Status.STALE,"semantic OFF before late callback prevents issuing otherwise valid interpretation rows");
        }
    }
    private static void supersession(Path root)throws Exception{
        try(var f=fixture(root)){
            var session=session(f,new AtomicBoolean(true),Runnable::run);var seeds=semantic(session);
            var page=await(session.interpretations(seeds,Optional.empty(),BUDGET));
            var raw=await(session.query(new MemoryReadSession.Query("약속",Optional.empty(),Optional.empty()),Optional.empty(),BUDGET));
            SourceRef source=f.original().source();long generation=f.store().authorityGeneration();
            var unrelated=new SourceRef(source.worldId(),source.datasetId(),source.kind(),source.owner(),UUID.randomUUID().toString(),1,RecordingRecords.sha256("unrelated"));
            check(await(f.store().captureSource(f.producer(),new SourceCapture(unrelated,1,List.of()))).status()==RecordingRecords.Status.STORED,"ordinary unrelated source uses real capture API");
            check(f.store().authorityGeneration()==generation&&session.current(raw)&&session.current(seeds)&&session.current(page),"unrelated capture does not stale issued pages");
            var dynamic=f.store().registerProducer("fixture-dynamic",Set.of(),Set.of(SourceKind.ACTION_OBSERVED));UUID lineage=UUID.randomUUID();
            check(await(f.store().registerSource(dynamic,new SourceRegistration(f.world(),source.datasetId(),"fixture-dynamic",lineage,0))).receipt().status()==RecordingRecords.Status.STORED,"dynamic source cutover registered");
            check(await(f.store().registerSource(dynamic,new SourceRegistration(f.world(),source.datasetId(),"fixture-dynamic",lineage,3))).receipt().status()==RecordingRecords.Status.STORED,"durable owner cursor confirmed");
            var external=new SourceRef(f.world(),source.datasetId(),SourceKind.ACTION_OBSERVED,"fixture-dynamic",UUID.randomUUID().toString(),1,RecordingRecords.sha256("dynamic"));
            var audience=Set.of(PLAYER,new ActorRef(ActorKind.GOD,GOD));
            var initial=new KnowledgeReceipt(UUID.randomUUID(),GOD,"DIRECT_WATCH","{}",audience,1);
            check(await(f.store().appendKnowledge(dynamic,new SourceKnowledgeCapture(external,lineage,1,1,List.of(new AcquiredKnowledge(initial,1))))).status()==RecordingRecords.Status.STORED,"unrelated incremental source captured");
            var late=new KnowledgeReceipt(UUID.randomUUID(),GOD,"DIRECT_WATCH","{}",audience,1);
            check(await(f.store().appendKnowledge(dynamic,new SourceKnowledgeCapture(external,lineage,1,1,List.of(new AcquiredKnowledge(late,3))))).status()==RecordingRecords.Status.STORED,"late actual receipt appended to original source");
            check(f.store().authorityGeneration()==generation&&session.current(raw)&&session.current(seeds)&&session.current(page),"new incremental source and late receipt do not invalidate unrelated issued pages");
            var replacement=new SourceRef(source.worldId(),source.datasetId(),source.kind(),source.owner(),source.sourceId(),2,RecordingRecords.sha256("replacement"));
            var field=WorldRecordingService.class.getDeclaredField("writer");field.setAccessible(true);var writer=(ThreadPoolExecutor)field.get(f.store());
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            writer.execute(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
            check(entered.await(2,TimeUnit.SECONDS),"replacement writer fixture gate ready");
            try{check(f.store().captureSource(f.producer(),new SourceCapture(replacement,2,List.of())).toCompletableFuture().cancel(false),"caller cancels outward replacement future only");}
            finally{release.countDown();}
            await(f.store().embeddingTransaction("replacement-barrier",4096,true,(db,sequence)->new RecordingEmbeddingStore.Mutation<>(true,false)));
            check(!session.current(raw)&&!session.current(seeds)&&!session.current(page)&&f.store().readAuthorityStable(),"superseding source invalidates raw semantic and interpretation pages and releases pending guard");
            check(semantic(session(f,new AtomicBoolean(true),Runnable::run)).entries().isEmpty(),"fresh semantic read excludes replaced native source");
            var fresh=session(f,new AtomicBoolean(true),Runnable::run);
            check(await(fresh.query(new MemoryReadSession.Query("약속",Optional.empty(),Optional.empty()),Optional.empty(),BUDGET)).entries().isEmpty(),
                    "fresh RAW read excludes replaced source and descendants rather than reviving revision one");
            generation=f.store().authorityGeneration();var newerExternal=new SourceRef(external.worldId(),external.datasetId(),external.kind(),external.owner(),external.sourceId(),2,RecordingRecords.sha256("dynamic revision2"));
            check(await(f.store().appendKnowledge(dynamic,new SourceKnowledgeCapture(newerExternal,lineage,1,2,List.of()))).status()==RecordingRecords.Status.STORED,"incremental producer also persists superseding source revision");
            check(f.store().authorityGeneration()>generation&&f.store().readAuthorityStable(),"incremental supersession uses the same authority fence and balanced cleanup");
        }
    }
    private static void defaults()throws Exception{
        MemoryReadSession unsupported=new MemoryReadSession(){
            public CompletableFuture<Page> query(Query query,Optional<Cursor> cursor,Budget budget){return CompletableFuture.completedFuture(new Page(Status.UNAVAILABLE,List.of(),Optional.empty()));}
            public boolean current(Page page){return false;}
        };
        var forged=new SemanticReadRecords.Page(MemoryReadSession.Status.PARTIAL,List.of(),Optional.empty());
        check(await(unsupported.interpretations(forged,Optional.empty(),BUDGET)).status()==MemoryReadSession.Status.UNAVAILABLE,"old implementations safely reject new overload by default");
    }
    private static Fixture fixture(Path root)throws Exception{
        Files.createDirectories(root);UUID world=UUID.randomUUID();var settings=new RecordingSettings(RecordingSettings.Mode.SHADOW,128_000_000,2_000_000,.90,.95);
        var store=await(WorldRecordingService.open(root,world,settings,new WorldRecordingService.CutoverBoundary("semantic-interpretation-test",Map.of("room-publication-v2",0L))));
        check(store.health().state()==WorldRecordingService.State.READY,"fixture READY");
        var producer=store.registerProducer("room-publication-v2",Set.of("ROOM_PRIVATE"),Set.of(SourceKind.DIALOGUE_DIRECT,SourceKind.DERIVED_SPEECH));UUID conversation=UUID.randomUUID();
        var worker=store.registerProjectionWorker("fixture","v1");UUID promise=capture(store,producer,world,conversation,"내일 돌아오겠다고 약속할게.",Set.of());
        Work old=claim(store,worker);commit(store,old,three(old));
        var embedding=store.registerEmbeddingWorker("fixture",SPACE);var leased=await(store.embeddingPort().claimWork(embedding,45));
        check(leased.status()==EmbeddingRecords.Status.CLAIMED,"promise embedding work leased");
        check(await(store.embeddingPort().commitEmbedding(leased.work().orElseThrow().token(),VECTOR)).status()==EmbeddingRecords.Status.STORED,"fake vector indexed against real source proof");
        UUID cancellation=capture(store,producer,world,conversation,"그 약속은 취소할게.",Set.of(promise));Work recent=claim(store,worker);
        Evidence older=recent.evidence().stream().filter(e->e.messageId().equals(promise)).findFirst().orElseThrow();
        var candidates=new ArrayList<>(three(recent));candidates.set(0,new Candidate(Layer.EVENT,ClaimKind.CORRECTION_OR_EXPLANATION,
                List.of(new Quote(recent.targetAlias(),target(recent).text()),new Quote(older.alias(),older.text())),List.of(new Link(recent.targetAlias(),older.alias(),Relation.CANCELS))));
        commit(store,recent,candidates);return new Fixture(root,world,store,producer,conversation,promise,cancellation,target(old));
    }
    private static UUID capture(WorldRecordingService store,ProducerCapability producer,UUID world,UUID conversation,String text,Set<UUID> parents)throws Exception{
        UUID id=UUID.randomUUID();Instant now=Instant.now();var audience=Set.of(PLAYER,new ActorRef(ActorKind.GOD,GOD));
        var context=new PublicationContext(store.runtimeEpoch(),1,"STANDARD",audience,audience,Map.of(),List.of(),parents,Map.of(),"UNKNOWN","PERSONAL");
        var envelope=new ConversationEnvelope(world,store.datasetId().orElseThrow(),conversation,"ROOM_PRIVATE","ACTUAL_LISTENERS_ONLY",1,1,true,false,"fixture");
        var raw=new RawMessage(id,Optional.empty(),0,PLAYER,text,now,MessageKind.ACCEPTED_INPUT,id.toString(),context);var view=new DeliveryView(text,List.of(text));var deliveries=new ArrayList<DeliveryReceipt>();
        for(var actor:audience)deliveries.add(new DeliveryReceipt(UUID.randomUUID(),actor,actor.kind()==ActorKind.GOD?"GAME_HEARD":"CHAT",now,1,DeliveryStatus.SERVER_DISPATCHED,view,Set.of(0)));
        check(await(store.capture(producer,envelope,raw,deliveries)).status()==RecordingRecords.Status.STORED,"native capture committed");return id;
    }
    private static Work claim(WorldRecordingService store,ProjectionWorkerCapability worker)throws Exception{
        var result=await(store.projectionPort().claimWork(worker,new WorkBudget(6,16384,45)));check(result.status()==ProjectionRecords.Status.CLAIMED,"projection work leased");return result.work().orElseThrow();
    }
    private static Evidence target(Work work){return work.evidence().stream().filter(e->e.alias().equals(work.targetAlias())).findFirst().orElseThrow();}
    private static List<Candidate> three(Work work){var quote=new Quote(work.targetAlias(),target(work).text());
        return List.of(new Candidate(Layer.EVENT,ClaimKind.DIALOGUE_EPISODE,List.of(quote),List.of()),new Candidate(Layer.RELATIONSHIP,ClaimKind.DIALOGUE_EPISODE,List.of(quote),List.of()),new Candidate(Layer.SUMMARY,ClaimKind.DIALOGUE_EPISODE,List.of(quote),List.of()));}
    private static void commit(WorldRecordingService store,Work work,List<Candidate> candidates)throws Exception{
        check(await(store.projectionPort().commitProjection(work.token(),candidates)).status()==ProjectionRecords.Status.STORED,"grounded projection committed");}
    private static RecordedMemoryAccess.Session session(Fixture f,AtomicBoolean enabled,Consumer<Runnable> dispatch){
        var scope=new RecordedRoomSearch.Scope(f.store().datasetId().orElseThrow(),GOD,Set.of(PLAYER,new ActorRef(ActorKind.GOD,GOD)),false,"STANDARD","PERSONAL");
        return new RecordedMemoryAccess.Session(f.store(),scope,()->true,dispatch,()->true,refs->CompletableFuture.completedFuture(refs.isEmpty()),List::isEmpty,enabled::get);
    }
    private static SemanticReadRecords.Page semantic(MemoryReadSession session)throws Exception{
        var page=await(session.semantic(query(),vector(),Optional.empty(),BUDGET));check(page.status()==MemoryReadSession.Status.PARTIAL&&session.current(page),"actual semantic page issued and current");return page;}
    private static MemoryReadSession.Query query(){return new MemoryReadSession.Query(QUERY,Optional.empty(),Optional.empty());}
    private static EmbeddingRecords.QueryVector vector(){return new EmbeddingRecords.QueryVector(SPACE,RecordingRecords.sha256(QUERY),VECTOR);}
    private static void runNext(BlockingQueue<Runnable> tasks)throws Exception{Runnable next=tasks.poll(2,TimeUnit.SECONDS);check(next!=null,"read callback queued");next.run();}
    private static <T>T await(CompletionStage<T> future)throws Exception{return future.toCompletableFuture().get(20,TimeUnit.SECONDS);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
