package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.EmbeddingRecords.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Real SQLite, fake numeric vectors, no model/network/server. */
public final class EmbeddingStoreTest {
    private static int checks;
    private static final ActorRef PLAYER=new ActorRef(ActorKind.PLAYER,UUID.randomUUID().toString());
    private static final ModelSpace SPACE=new ModelSpace("fixture:embed","a".repeat(64),3,EmbeddingRecords.ENCODER_VERSION);
    private static final float[] VECTOR={1,.5f,-.25f};
    private static final RecordingSettings SETTINGS=new RecordingSettings(RecordingSettings.Mode.SHADOW,128_000_000,2_000_000,.90,.95);
    private static final WorldRecordingService.CutoverBoundary BOUNDARY=new WorldRecordingService.CutoverBoundary("embedding-test",Map.of());
    private record Fixture(Path root,UUID world,WorldRecordingService store,ProducerCapability producer,UUID conversation)implements AutoCloseable{
        public void close()throws Exception{await(store.closeAsync());}
    }
    public static void main(String[] args)throws Exception{
        Path parent=Path.of(args.length==0?"build/embedding-store-test":args[0]).toAbsolutePath().normalize();
        if(!parent.toString().replace('\\','/').contains("/build/"))throw new IllegalArgumentException("TEST_REQUIRES_BUILD_DIRECTORY");
        Files.createDirectories(parent);Path root=Files.createTempDirectory(parent,"embedding-");
        basic(root.resolve("basic"));withdrawal(root.resolve("withdrawal"));retries(root.resolve("retries"));
        superseded(root.resolve("superseded"));
        readBudget(root.resolve("read-budget"));
        recoveryLeaseRace(root.resolve("recovery-lease-race"));
        atomic(root.resolve("atomic"));restart(root.resolve("restart"));lifecycle(root.resolve("lifecycle"));migration(root.resolve("migration"));
        System.out.println("EmbeddingStoreTest: "+checks+" checks passed; fixtures="+root);
    }
    private static void basic(Path root)throws Exception{
        try(var f=fixture(root,UUID.randomUUID())){
            String text="p".repeat(1599)+"🌌"+" tail";UUID message=capture(f,text,Set.of("test:a","test:b"),Set.of());
            var worker=f.store().registerEmbeddingWorker("fixture",SPACE);
            check(f.store().registerEmbeddingWorker("fixture",SPACE)==worker,"same worker and space registration idempotent");
            check(await(f.store().embeddingPort().claimWork(EmbeddingWorkerCapability.unregistered(),45)).status()==EmbeddingRecords.Status.REJECTED,"unregistered worker denied");
            long rawAuthority=f.store().authorityGeneration(),projection=f.store().projectionGeneration();var gods=new HashSet<String>();
            for(int i=0;i<2;i++){
                Work work=claim(f,worker);gods.add(work.observerGodId());
                check(work.text().equals("p".repeat(1599))&&work.excerpt()&&work.totalCharacters()==text.length(),"prefix respects surrogate pair and reports real coverage");
                check(work.messageId().equals(message)&&work.inputHash().equals(RecordingRecords.sha256(work.text())),"leased native identity and input hash exact");
                check(await(f.store().embeddingPort().claimWork(worker,45)).status()==EmbeddingRecords.Status.DEFERRED,"at most one active lease per capability");
                check(await(f.store().embeddingPort().commitEmbedding(work.token(),VECTOR)).status()==EmbeddingRecords.Status.STORED,"vector and DONE committed");
                check(await(f.store().embeddingPort().commitEmbedding(work.token(),VECTOR)).status()==EmbeddingRecords.Status.DUPLICATE,"same token vector retry idempotent");
                check(await(f.store().embeddingPort().commitEmbedding(work.token(),new float[]{0,1,0})).status()==EmbeddingRecords.Status.REJECTED,"committed vector immutable");
                check(await(f.store().embeddingPort().finishWork(work.token(),WorkOutcome.DEFERRED,"LATE_CALLBACK")).status()==EmbeddingRecords.Status.STALE,"late finish never requeues committed work");
            }
            check(gods.equals(Set.of("test:a","test:b"))&&count(f,"SELECT count(*) FROM embedding_rows")==2,"same capture sequence seeds every actual God receipt without checkpoint loss");
            check(f.store().authorityGeneration()==rawAuthority&&f.store().projectionGeneration()==projection,"append-only vector indexing does not invalidate RAW or interpretation leases");
            check(await(f.store().inspectMessage(message,10000)).orElseThrow().equals(text),"embedding never truncates or rewrites RAW");
            try(var db=db(f);var q=db.createStatement();var r=q.executeQuery("SELECT vector,vector_hash,dimensions FROM embedding_rows LIMIT 1")){
                r.next();byte[] blob=r.getBytes(1);var floats=ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
                check(blob.length==12&&r.getInt(3)==3&&r.getString(2).equals(EmbeddingRecords.vectorHash(blob)),"finite vector dimensions and binary hash persisted");
                for(float value:VECTOR)check(floats.getFloat()==value,"float32 little endian encoding exact");
            }
            var changed=new ModelSpace("fixture:embed","b".repeat(64),3,EmbeddingRecords.ENCODER_VERSION);
            var next=f.store().registerEmbeddingWorker("fixture",changed);Work work=claim(f,next);
            check(work.modelSpace().equals(changed)&&await(f.store().embeddingPort().claimWork(worker,45)).status()==EmbeddingRecords.Status.REJECTED,"digest change replaces old capability and creates separate model space");
            check(await(f.store().embeddingPort().commitEmbedding(work.token(),VECTOR)).status()==EmbeddingRecords.Status.STORED&&count(f,"SELECT count(DISTINCT model_fingerprint) FROM embedding_rows")==2,"new digest cannot overwrite old vector space");
        }
    }
    private static void withdrawal(Path root)throws Exception{
        try(var f=fixture(root,UUID.randomUUID())){
            UUID parent=capture(f,"native parent",Set.of("test:a"),Set.of());var worker=f.store().registerEmbeddingWorker("fixture",SPACE);
            Work first=claim(f,worker);check(await(f.store().embeddingPort().commitEmbedding(first.token(),VECTOR)).status()==EmbeddingRecords.Status.STORED,"parent vector stored");
            capture(f,"native child",Set.of("test:a"),Set.of(parent));Work child=claim(f,worker);
            check(await(f.store().invalidateKnowledge(f.producer(),new KnowledgeInvalidation(first.source(),first.knowledgeReceiptId(),1,"WITHDRAW_PARENT"))).status()==RecordingRecords.Status.STORED,"actual parent proof withdrawn");
            check(await(f.store().embeddingPort().commitEmbedding(child.token(),VECTOR)).status()==EmbeddingRecords.Status.STALE,"late child vector cannot bypass native parent DAG withdrawal");
            check(count(f,"SELECT count(*) FROM embedding_rows")==1,"rejected late embedding inserts no vector");
            check(await(f.store().embeddingPort().commitEmbedding(first.token(),VECTOR)).status()==EmbeddingRecords.Status.STALE,"old completed cache invalidated by receipt withdrawal");
            check(f.store().health().state()==WorldRecordingService.State.READY,"authority rejection leaves authoritative RAW store ready");
        }
    }
    private static void retries(Path root)throws Exception{
        try(var f=fixture(root,UUID.randomUUID())){
            capture(f,"retry exact native source",Set.of("test:a"),Set.of());var worker=f.store().registerEmbeddingWorker("fixture",SPACE);
            for(float[] vector:List.of(new float[]{1},new float[]{Float.NaN,1,1},new float[]{0,0,0})){
                Work work=claim(f,worker);check(await(f.store().embeddingPort().commitEmbedding(work.token(),vector)).status()==EmbeddingRecords.Status.REJECTED,"invalid dimensions/nonfinite/zero vector denied");
                sql(f,"UPDATE embedding_jobs SET next_attempt_millis=0");
            }
            check(count(f,"SELECT count(*) FROM embedding_jobs WHERE state='FAILED' AND attempt_count=3")==1,"three actual invalid model results durably exhaust bounded retry");
            check(await(f.store().embeddingPort().claimWork(worker,45)).status()==EmbeddingRecords.Status.EMPTY&&count(f,"SELECT count(*) FROM embedding_rows")==0,"failed poison source neither loops nor emits fake vector");
            check(f.store().health().state()==WorldRecordingService.State.READY,"model mistakes never disable raw recording");
        }
    }
    private static void superseded(Path root)throws Exception{
        try(var f=fixture(root,UUID.randomUUID())){
            capture(f,"revision one source",Set.of("test:a"),Set.of());Work work=claim(f,f.store().registerEmbeddingWorker("fixture",SPACE));
            // Simulate a future game producer replacing a source revision, without forging a new receipt.
            sql(f,"INSERT INTO source_refs(dataset_id,kind,owner,source_id,source_revision,source_hash,request_hash,ingest_sequence)"
                    +" SELECT dataset_id,kind,owner,source_id,2,source_hash,request_hash,ingest_sequence FROM source_refs");
            check(await(f.store().embeddingPort().commitEmbedding(work.token(),VECTOR)).status()==EmbeddingRecords.Status.STALE,
                    "new source revision invalidates an otherwise matching leased native source");
            check(count(f,"SELECT count(*) FROM embedding_rows")==0&&f.store().health().state()==WorldRecordingService.State.READY,
                    "superseded source never persists stale embedding or disables RAW");
        }
    }
    private static void readBudget(Path root)throws Exception{
        try(var f=fixture(root,UUID.randomUUID())){
            UUID expensive=capture(f,"expensive native source remains RAW",Set.of("test:a"),Set.of());var worker=f.store().registerEmbeddingWorker("fixture",SPACE);
            sql(f,"CREATE TRIGGER fixture_native_budget BEFORE UPDATE OF state ON embedding_jobs WHEN NEW.state='LEASED' AND NEW.room_work_id IN"
                    +" (SELECT id FROM work_items WHERE message_id='"+expensive+"') BEGIN SELECT sum(x) FROM"
                    +" (WITH RECURSIVE count(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM count WHERE x<100000000) SELECT x FROM count); END");
            var first=await(f.store().embeddingPort().claimWork(worker,45));
            check(first.status()==EmbeddingRecords.Status.DEFERRED&&first.reasonCode().equals("NATIVE_READ_BUDGET_RETRY"),"definite VM abort records bounded per-source retry: "+first);
            check(count(f,"SELECT count(*) FROM embedding_rows")==0&&count(f,"SELECT count(*) FROM embedding_seed_progress")==0,"VM abort rolls back original seeding/claim/vector transaction");
            UUID healthy=capture(f,"healthy later source",Set.of("test:a"),Set.of());Work later=claim(f,worker);
            check(later.messageId().equals(healthy)&&await(f.store().embeddingPort().commitEmbedding(later.token(),VECTOR)).status()==EmbeddingRecords.Status.STORED,"expensive source rotates behind unrelated later work");
            for(int attempt=2;attempt<=3;attempt++){
                sql(f,"UPDATE embedding_jobs SET next_attempt_millis=0 WHERE state='PENDING'");var result=await(f.store().embeddingPort().claimWork(worker,45));
                check(result.status()==EmbeddingRecords.Status.DEFERRED&&result.reasonCode().equals(attempt==3?"NATIVE_READ_BUDGET_EXCEEDED":"NATIVE_READ_BUDGET_RETRY"),"read-budget attempts durably bounded: "+result);
            }
            check(count(f,"SELECT count(*) FROM embedding_jobs WHERE state='SKIPPED_UNSUPPORTED' AND attempt_count=0")==1,"read cost exhaustion is explicit incomplete coverage, not a failed model inference");
            check(count(f,"SELECT count(*) FROM embedding_rows")==1&&await(f.store().inspectMessage(expensive,4096)).isPresent()&&f.store().health().state()==WorldRecordingService.State.READY,"skipped expensive embedding never loses RAW or poisons whole archive");
        }
        try(var f=fixture(root.resolveSibling("busy"),UUID.randomUUID())){
            capture(f,"transient locked source",Set.of("test:a"),Set.of());var worker=f.store().registerEmbeddingWorker("fixture",SPACE);
            try(var db=db(f);var q=db.createStatement()){
                q.execute("BEGIN IMMEDIATE");
                for(int i=0;i<3;i++)check(await(f.store().embeddingPort().claimWork(worker,45)).status()==EmbeddingRecords.Status.DEFERRED,"SQLite writer contention defers without declaring source expensive");
                q.execute("ROLLBACK");
            }
            check(count(f,"SELECT count(*) FROM consumer_cursors WHERE consumer LIKE 'embedding-read-budget/%'")==0,"busy lock never consumes durable source read retry");
            Work work=claim(f,worker);check(await(f.store().embeddingPort().commitEmbedding(work.token(),VECTOR)).status()==EmbeddingRecords.Status.STORED,"normal source resumes after lock release");
        }
    }
    @SuppressWarnings("unchecked")
    private static void recoveryLeaseRace(Path root)throws Exception{
        try(var f=fixture(root,UUID.randomUUID())){
            capture(f,"live lease must survive another worker recovery",Set.of("test:a"),Set.of());
            var repairing=f.store().registerEmbeddingWorker("repairing",SPACE);
            Work held=claim(f,f.store().registerEmbeddingWorker("rightful",SPACE));
            // Deterministically place the old recovery AFTER a second worker obtained the lease.
            // Reflection invokes only the private recovery path; all changes still use the real writer.
            Object port=f.store().embeddingPort();var workers=RecordingEmbeddingStore.class.getDeclaredField("workers");workers.setAccessible(true);
            Object worker=((Map<?,?>)workers.get(port)).get(repairing);
            Class<?> jobClass=Class.forName(RecordingEmbeddingStore.class.getName()+"$Job");
            var constructor=jobClass.getDeclaredConstructor(String.class,String.class,int.class);constructor.setAccessible(true);Object job;
            try(var db=db(f);var q=db.createStatement();var rows=q.executeQuery("SELECT id,room_work_id,attempt_count FROM embedding_jobs WHERE state='LEASED'")){
                rows.next();job=constructor.newInstance(rows.getString(1),rows.getString(2),rows.getInt(3));}
            var recovery=RecordingEmbeddingStore.class.getDeclaredMethod("readBudget",Connection.class,worker.getClass(),jobClass,long.class);recovery.setAccessible(true);
            var result=await(f.store().embeddingTransaction("recovery-race",4096,true,(db,sequence)->
                    (RecordingEmbeddingStore.Mutation<ClaimResult>)recovery.invoke(port,db,worker,job,sequence)));
            check(result.status()==EmbeddingRecords.Status.EMPTY&&result.reasonCode().equals("EMBEDDING_RETRY_ALREADY_CLAIMED"),"stale recovery cannot replace a new live lease");
            check(count(f,"SELECT count(*) FROM consumer_cursors WHERE consumer LIKE 'embedding-read-budget/%'")==0
                    &&count(f,"SELECT count(*) FROM embedding_jobs WHERE state='LEASED'")==1,"stale recovery changes neither retry counters nor current lease");
            check(await(f.store().embeddingPort().commitEmbedding(held.token(),VECTOR)).status()==EmbeddingRecords.Status.STORED,"rightful worker can still commit after stale recovery");
        }
    }
    private static void atomic(Path root)throws Exception{
        try(var f=fixture(root,UUID.randomUUID())){
            capture(f,"atomic exact native source",Set.of("test:a"),Set.of());var worker=f.store().registerEmbeddingWorker("fixture",SPACE);Work work=claim(f,worker);
            long watermark=f.store().health().highWatermark();sql(f,"CREATE TRIGGER fixture_embedding_abort BEFORE UPDATE OF state ON embedding_jobs WHEN NEW.state='DONE' BEGIN SELECT RAISE(ABORT,'fixture'); END");
            check(await(f.store().embeddingPort().commitEmbedding(work.token(),VECTOR)).status()==EmbeddingRecords.Status.UNAVAILABLE,"optional SQL failure reported without success");
            check(count(f,"SELECT count(*) FROM embedding_rows")==0&&count(f,"SELECT count(*) FROM embedding_jobs WHERE state='LEASED'")==1&&f.store().health().highWatermark()==watermark,"vector and work completion rollback atomically");
            check(f.store().health().state()==WorldRecordingService.State.READY,"failed optional embedding does not disable RAW");
            sql(f,"DROP TRIGGER fixture_embedding_abort");check(await(f.store().embeddingPort().commitEmbedding(work.token(),VECTOR)).status()==EmbeddingRecords.Status.STORED,"same leased work safely retried after storage failure");
        }
    }
    private static void restart(Path root)throws Exception{
        UUID world=UUID.randomUUID();var f=fixture(root,world);capture(f,"restart source",Set.of("test:a"),Set.of());
        var worker=f.store().registerEmbeddingWorker("fixture",SPACE);Work old=claim(f,worker);await(f.store().closeAsync());
        try(var fresh=fixture(root,world)){
            check(await(fresh.store().embeddingPort().commitEmbedding(old.token(),VECTOR)).status()==EmbeddingRecords.Status.STALE,"old process token cannot commit after restart");
            Work work=claim(fresh,fresh.store().registerEmbeddingWorker("fixture",SPACE));
            check(work.messageId().equals(old.messageId())&&work.knowledgeReceiptId().equals(old.knowledgeReceiptId()),"recovered lease retains exact source proof");
            check(await(fresh.store().embeddingPort().commitEmbedding(work.token(),VECTOR)).status()==EmbeddingRecords.Status.STORED,"recovered native work completes");
            check(count(fresh,"SELECT count(*) FROM embedding_jobs")==1,"durable composite seeding idempotent across restart");
        }
        try(var fresh=fixture(root,world)){
            check(await(fresh.store().embeddingPort().claimWork(fresh.store().registerEmbeddingWorker("fixture",SPACE),45)).status()==EmbeddingRecords.Status.EMPTY&&count(fresh,"SELECT count(*) FROM embedding_rows")==1,"committed vector persists without repeated inference");
        }
    }
    private static void lifecycle(Path root)throws Exception{
        try(var f=fixture(root,UUID.randomUUID())){
            var worker=f.store().registerEmbeddingWorker("fixture",SPACE);var field=WorldRecordingService.class.getDeclaredField("writer");field.setAccessible(true);
            var writer=(ThreadPoolExecutor)field.get(f.store());var enter=new CountDownLatch(1);var release=new CountDownLatch(1);
            writer.execute(()->{enter.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
            check(enter.await(2,TimeUnit.SECONDS),"fixture writer gate ready");
            try{check(f.store().embeddingPort().claimWork(worker,45).toCompletableFuture().cancel(false),"caller may cancel outward empty claim");}finally{release.countDown();}
            await(f.store().embeddingTransaction("barrier",4096,false,(db,seq)->new RecordingEmbeddingStore.Mutation<>(true,false)));
            check(await(f.store().embeddingPort().claimWork(worker,45)).status()==EmbeddingRecords.Status.EMPTY,"cancelled empty claim cannot strand worker busy");
            capture(f,"bounded expired source",Set.of("test:a"),Set.of());var expiring=await(f.store().embeddingPort().claimWork(worker,1)).work().orElseThrow();
            Thread.sleep(1100);
            check(await(f.store().embeddingPort().commitEmbedding(expiring.token(),VECTOR)).status()==EmbeddingRecords.Status.STALE,"expired lease cannot commit");
            Work reclaimed=claim(f,worker);check(reclaimed.messageId().equals(expiring.messageId()),"expired durable source remains claimable");
            var budgetField=WorldRecordingService.class.getDeclaredField("budget");budgetField.setAccessible(true);var budget=(WorldRecordingBudget)budgetField.get(f.store());
            var snapshot=budget.snapshot();var held=budget.reserve(ManagedStoreRegistry.RECORDING,(long)(snapshot.limitBytes()*.96)-snapshot.usedPhysicalBytes(),true);
            try{check(await(f.store().embeddingPort().commitEmbedding(reclaimed.token(),VECTOR)).status()==EmbeddingRecords.Status.DEFERRED&&f.store().health().state()==WorldRecordingService.State.READY,"95 percent quota defers optional inference writes without disabling RAW");}
            finally{budget.cancelUnstarted(held);}
            check(await(f.store().embeddingPort().finishWork(reclaimed.token(),WorkOutcome.DEFERRED,"QUOTA_DEFERRED")).status()==EmbeddingRecords.Status.STORED,"deferred work checkpoint uses maintenance transaction");
            check(count(f,"SELECT attempt_count FROM embedding_jobs")==0,"preemption/quota deferral does not count as a model failure");
            f.store().embeddingPort().revokeWorker(worker);check(await(f.store().embeddingPort().claimWork(worker,45)).status()==EmbeddingRecords.Status.REJECTED,"game can immediately revoke worker");
        }
    }
    private static void migration(Path root)throws Exception{
        UUID world=UUID.randomUUID();var f=fixture(root,world);UUID original=capture(f,"schema7 original",Set.of("test:a"),Set.of());await(f.store().closeAsync());
        try(var db=db(f);var q=db.createStatement()){
            q.execute("DROP TABLE IF EXISTS native_memory_evidence");
            q.execute("DROP TABLE IF EXISTS projection_input_manifests");
            q.execute("DROP TABLE IF EXISTS native_interpretation_evidence");
            for(String table:List.of("embedding_rows","embedding_jobs","embedding_seed_progress"))q.execute("DROP TABLE "+table);q.execute("DROP INDEX embedding_source_seed");
            q.execute("PRAGMA user_version=7");q.execute("UPDATE recording_meta SET schema_version=7");}
        try(var fresh=fixture(root,world)){
            check(count(fresh,"PRAGMA user_version")==RecordingSchema.VERSION&&count(fresh,"SELECT count(*) FROM embedding_rows")==0,"schema7 migration creates optional index without startup backfill");
            check(await(fresh.store().inspectMessage(original,4096)).orElseThrow().equals("schema7 original"),"current schema migration preserves exact RAW");
            Work work=claim(fresh,fresh.store().registerEmbeddingWorker("fixture",SPACE));check(work.messageId().equals(original),"explicit bounded native backfill can claim preserved v2 source");
        }
    }
    private static Fixture fixture(Path root,UUID world)throws Exception{
        Files.createDirectories(root);var store=await(WorldRecordingService.open(root,world,SETTINGS,BOUNDARY));check(store.health().state()==WorldRecordingService.State.READY,"fixture READY: "+store.health().reasonCode());
        return new Fixture(root,world,store,store.registerProducer("room-publication-v2",Set.of("ROOM_PRIVATE"),Set.of(SourceKind.DIALOGUE_DIRECT,SourceKind.DERIVED_SPEECH)),UUID.randomUUID());
    }
    private static UUID capture(Fixture f,String text,Set<String> gods,Set<UUID> parents)throws Exception{
        UUID id=UUID.randomUUID();Instant now=Instant.now();var audience=new HashSet<ActorRef>();audience.add(PLAYER);gods.forEach(g->audience.add(new ActorRef(ActorKind.GOD,g)));
        var context=new PublicationContext(f.store().runtimeEpoch(),1,"STANDARD",audience,audience,Map.of(),List.of(),parents,Map.of(),"UNKNOWN","PERSONAL");
        var envelope=new ConversationEnvelope(f.world(),f.store().datasetId().orElseThrow(),f.conversation(),"ROOM_PRIVATE","ACTUAL_LISTENERS_ONLY",1,1,true,false,"fixture");
        var message=new RawMessage(id,Optional.empty(),0,PLAYER,text,now,MessageKind.ACCEPTED_INPUT,id.toString(),context);var view=new DeliveryView(text,List.of(text));var receipts=new ArrayList<DeliveryReceipt>();
        for(var actor:audience)receipts.add(new DeliveryReceipt(UUID.randomUUID(),actor,actor.kind()==ActorKind.GOD?"GAME_HEARD":"CHAT",now,1,DeliveryStatus.SERVER_DISPATCHED,view,Set.of(0)));
        check(await(f.store().capture(f.producer(),envelope,message,receipts)).status()==RecordingRecords.Status.STORED,"native capture stored");return id;
    }
    private static Work claim(Fixture f,EmbeddingWorkerCapability capability)throws Exception{var result=await(f.store().embeddingPort().claimWork(capability,45));check(result.status()==EmbeddingRecords.Status.CLAIMED,"native embedding claimed: "+result.reasonCode());return result.work().orElseThrow();}
    private static Connection db(Fixture f)throws Exception{return DriverManager.getConnection("jdbc:sqlite:"+f.root().resolve("mythictrpg-recording-v2").resolve(f.store().datasetId().orElseThrow().toString()).resolve("recording.sqlite"));}
    private static long count(Fixture f,String sql)throws Exception{try(var db=db(f);var q=db.createStatement();var r=q.executeQuery(sql)){r.next();return r.getLong(1);}}
    private static void sql(Fixture f,String sql)throws Exception{try(var db=db(f);var q=db.createStatement()){q.execute(sql);}}
    private static <T>T await(CompletionStage<T> future)throws Exception{return future.toCompletableFuture().get(20,TimeUnit.SECONDS);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
