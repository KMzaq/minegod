package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.EmbeddingRecords.*;
import com.sande.mythictrpg.recording.api.ProjectionRecords.Evidence;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Native-only durable embedding work. No inference, legacy import, gameplay mutation or extra writer. */
final class RecordingEmbeddingStore implements MemoryEmbeddingPort {
    record Mutation<T>(T value,boolean changed,java.util.function.BooleanSupplier valid){
        Mutation(T value,boolean changed){this(value,changed,()->true);}
    }
    static final class StorageFailure extends RuntimeException {
        final Status status;final String code;
        StorageFailure(Status status,String code){super(code);this.status=status;this.code=code;}
    }
    private static final class Worker {
        final String id;final ModelSpace space;boolean claiming;EmbeddingWorkToken token;
        Worker(String id,ModelSpace space){this.id=id;this.space=space;}
    }
    private record Lease(Worker worker,String job,String roomJob,String nonce,Work work,Evidence evidence) { }
    private record Completed(ModelSpace space,String hash,long authorityGeneration) { }
    private record Job(String id,String roomJob,int attempts) { }
    private final WorldRecordingService store;
    private final Map<EmbeddingWorkerCapability,Worker> workers=new IdentityHashMap<>();
    private final Map<EmbeddingWorkToken,Lease> leases=new IdentityHashMap<>();
    private final LinkedHashMap<EmbeddingWorkToken,Completed> completed=new LinkedHashMap<>();
    private boolean closed;
    RecordingEmbeddingStore(WorldRecordingService store){this.store=store;}
    EmbeddingWorkerCapability register(String id,ModelSpace space){
        Objects.requireNonNull(space);
        if(id==null||!id.matches("[a-z0-9_.:-]{1,128}")||store.health().state()!=WorldRecordingService.State.READY)
            throw new IllegalStateException("EMBEDDING_REGISTRATION_UNAVAILABLE");
        synchronized(this){
            if(closed)throw new IllegalStateException("EMBEDDING_REGISTRATION_UNAVAILABLE");
            for(var entry:new ArrayList<>(workers.entrySet()))if(entry.getValue().id.equals(id)){
                if(entry.getValue().space.equals(space))return entry.getKey();
                Worker old=workers.remove(entry.getKey());leases.entrySet().removeIf(e->e.getValue().worker()==old);completed.clear();
            }
            if(workers.size()>=4)throw new IllegalStateException("EMBEDDING_WORKER_LIMIT");
            var key=EmbeddingWorkerCapability.unregistered();workers.put(key,new Worker(id,space));return key;
        }
    }
    synchronized void close(){closed=true;workers.clear();leases.clear();completed.clear();}
    @Override public synchronized void revokeWorker(EmbeddingWorkerCapability key){
        Worker worker=workers.remove(key);if(worker!=null)leases.entrySet().removeIf(e->e.getValue().worker()==worker);completed.clear();
    }
    private synchronized boolean live(Worker worker){return !closed&&workers.containsValue(worker);}
    @Override public CompletionStage<ClaimResult> claimWork(EmbeddingWorkerCapability capability,int leaseSeconds){
        if(leaseSeconds<1||leaseSeconds>120)throw new IllegalArgumentException("EMBEDDING_LEASE_BUDGET");
        final Worker worker;
        synchronized(this){worker=workers.get(capability);if(closed||worker==null)return claim(Status.REJECTED,"INVALID_EMBEDDING_WORKER");
            if(worker.token!=null){var lease=leases.get(worker.token);if(lease==null||lease.work().leaseDeadlineEpochMillis()<=System.currentTimeMillis()){leases.remove(worker.token);worker.token=null;}}
            if(worker.claiming||worker.token!=null)return claim(Status.DEFERRED,"WORKER_ALREADY_BUSY");
            if(!store.projectionAdmission())return claim(Status.DEFERRED,"BACKGROUND_ADMISSION_DEFERRED");worker.claiming=true;}
        var attempted=new java.util.concurrent.atomic.AtomicReference<Job>();
        return store.embeddingTransaction("claim",32768,false,(db,sequence)->claim(db,worker,leaseSeconds,sequence,attempted))
                .exceptionallyCompose(failure->{
                    // Only a definite precommit VM abort, not writer contention or an uncertain COMMIT,
                    // may charge this source. The original batch was rolled back before this tiny write.
                    Job job=attempted.get();
                    if(job!=null&&failureCode(failure).equals("EMBEDDING_VM_BUDGET")&&store.health().state()==WorldRecordingService.State.READY)
                        return store.embeddingTransaction("read-budget",4096,true,(db,sequence)->readBudget(db,worker,job,sequence));
                    return CompletableFuture.failedFuture(failure);
                })
                .handle((value,failure)->{synchronized(this){worker.claiming=false;
                    if((failure!=null||value.status()!=Status.CLAIMED)&&worker.token!=null){leases.remove(worker.token);worker.token=null;}}
                    return failure==null?value:new ClaimResult(failureStatus(failure),Optional.empty(),failureCode(failure));})
                .minimalCompletionStage();
    }
    private Mutation<ClaimResult> claim(Connection db,Worker worker,int seconds,long sequence,java.util.concurrent.atomic.AtomicReference<Job> attempted)throws Exception{
        if(!live(worker)||!store.readAuthorityStable())return new Mutation<>(empty(Status.STALE,"EMBEDDING_AUTHORITY_CHANGED"),false);
        long scanDeadlineNanos=System.nanoTime()+200_000_000L;
        boolean changed=seed(db,worker.space,sequence);long now=System.currentTimeMillis();var jobs=new ArrayList<Job>();
        try(var q=prepare(db,"SELECT id,room_work_id,attempt_count FROM embedding_jobs WHERE dataset_id=? AND model_fingerprint=?"
                +" AND ((state='PENDING' AND next_attempt_millis<=?) OR (state='LEASED' AND lease_deadline_millis<=?))"
                +" ORDER BY CASE WHEN next_attempt_millis=0 THEN 0 ELSE 1 END,next_attempt_millis,created_sequence,id LIMIT 16",
                dataset(),worker.space.fingerprint(),now,now);var r=q.executeQuery()){
            while(r.next())jobs.add(new Job(r.getString(1),r.getString(2),r.getInt(3)));}
        for(Job job:jobs){
            // Commit already-classified unavailable sources before entering another source's budget.
            // A slow preceding source must not make the next healthy one inherit its VM timeout.
            if(System.nanoTime()>scanDeadlineNanos)break;
            attempted.set(job);
            var source=store.embeddingSource(db,job.roomJob());
            if(source.target()==null){
                if(source.retryable())defer(db,job.id(),source.reasonCode());
                else terminal(db,job.id(),"SKIPPED_UNSUPPORTED",source.reasonCode());changed=true;continue;
            }
            Evidence e=source.target();var token=EmbeddingWorkToken.unregistered();String nonce=UUID.randomUUID().toString();
            long deadline=System.currentTimeMillis()+seconds*1000L;
            var work=new Work(token,worker.space,e.messageId(),e.source(),e.knowledgeReceiptId(),e.receiptHash(),e.actualActor(),e.observerGodId(),
                    e.audience(),e.disclosureHash(),e.text(),RecordingRecords.sha256(e.text()),e.text().length(),e.totalCharacters(),deadline);
            update(db,"UPDATE embedding_jobs SET state='LEASED',lease_epoch=?,lease_nonce=?,lease_deadline_millis=?,last_failure='' WHERE id=?",
                    store.runtimeEpoch(),nonce,deadline,job.id());
            synchronized(this){if(!live(worker))return new Mutation<>(empty(Status.STALE,"EMBEDDING_WORKER_REPLACED"),true,()->false);
                leases.put(token,new Lease(worker,job.id(),job.roomJob(),nonce,work,e));worker.token=token;}
            return new Mutation<>(new ClaimResult(Status.CLAIMED,Optional.of(work),"EMBEDDING_LEASED"),true,
                    ()->live(worker)&&store.readAuthorityStable());
        }
        return new Mutation<>(empty(Status.EMPTY,changed?"BOUNDED_EMBEDDING_WORK_PROGRESS":"NO_ELIGIBLE_EMBEDDING"),changed);
    }
    private Mutation<ClaimResult> readBudget(Connection db,Worker worker,Job job,long sequence)throws SQLException{
        if(!live(worker)||!store.readAuthorityStable())return new Mutation<>(empty(Status.STALE,"EMBEDDING_AUTHORITY_CHANGED"),false);
        String expected=UUID.nameUUIDFromBytes((dataset()+"/"+worker.space.fingerprint()+"/"+job.roomJob()).getBytes(StandardCharsets.UTF_8)).toString();
        if(!expected.equals(job.id()))return new Mutation<>(empty(Status.STALE,"EMBEDDING_RETRY_SCOPE_CHANGED"),false);
        try(var q=prepare(db,"SELECT 1 FROM work_items WHERE id=? AND dataset_id=? AND kind='ROOM_KNOWLEDGE_CAPTURED'",job.roomJob(),dataset());var r=q.executeQuery()){
            if(!r.next())return new Mutation<>(empty(Status.STALE,"EMBEDDING_RETRY_SCOPE_CHANGED"),false);}
        // Seeding may also have rolled back. Restore this ONE known native job, never a lossy cursor.
        update(db,"INSERT OR IGNORE INTO embedding_jobs(id,dataset_id,model_fingerprint,room_work_id,state,created_sequence) VALUES(?,?,?,?,'PENDING',?)",
                job.id(),dataset(),worker.space.fingerprint(),job.roomJob(),sequence);
        try(var q=prepare(db,"SELECT dataset_id,model_fingerprint,room_work_id,state,lease_deadline_millis FROM embedding_jobs WHERE id=?",job.id());var r=q.executeQuery()){
            if(!r.next()||!dataset().toString().equals(r.getString(1))||!worker.space.fingerprint().equals(r.getString(2))||!job.roomJob().equals(r.getString(3)))
                return new Mutation<>(empty(Status.STALE,"EMBEDDING_RETRY_SCOPE_CHANGED"),false);
            if(Set.of("DONE","FAILED","INVALIDATED","SKIPPED_UNSUPPORTED").contains(r.getString(4)))return new Mutation<>(empty(Status.EMPTY,"EMBEDDING_WORK_ALREADY_TERMINAL"),false);
            // Another registered worker may have acquired this job after the failed transaction
            // rolled back but before this queued repair. Never cancel that worker's live lease.
            if(!"PENDING".equals(r.getString(4))&&!("LEASED".equals(r.getString(4))
                    &&r.getLong(5)>0&&r.getLong(5)<=System.currentTimeMillis()))
                return new Mutation<>(empty(Status.EMPTY,"EMBEDDING_RETRY_ALREADY_CLAIMED"),false);
        }
        String consumer="embedding-read-budget/"+worker.space.fingerprint();int attempts=0;
        try(var q=prepare(db,"SELECT cursor FROM consumer_cursors WHERE dataset_id=? AND consumer=? AND stream=?",dataset(),consumer,job.id());var r=q.executeQuery()){
            if(r.next())attempts=(int)Math.min(3,r.getLong(1));}
        attempts++;
        update(db,"INSERT INTO consumer_cursors VALUES(?,?,?,?) ON CONFLICT DO UPDATE SET cursor=excluded.cursor",dataset(),consumer,job.id(),attempts);
        if(attempts>=3)terminal(db,job.id(),"SKIPPED_UNSUPPORTED","NATIVE_READ_BUDGET_EXCEEDED");
        else defer(db,job.id(),"NATIVE_READ_BUDGET_RETRY");
        return new Mutation<>(empty(Status.DEFERRED,attempts>=3?"NATIVE_READ_BUDGET_EXCEEDED":"NATIVE_READ_BUDGET_RETRY"),true,
                ()->live(worker)&&store.readAuthorityStable());
    }
    /** Composite cursor preserves every receipt/work id sharing one native capture sequence. */
    private boolean seed(Connection db,ModelSpace space,long sequence)throws SQLException{
        long after=0;String afterId="";
        try(var q=prepare(db,"SELECT after_sequence,after_work_id FROM embedding_seed_progress WHERE dataset_id=? AND model_fingerprint=?",dataset(),space.fingerprint());var r=q.executeQuery()){
            if(r.next()){after=r.getLong(1);afterId=r.getString(2);}}
        record Seed(String id,long sequence){}var found=new ArrayList<Seed>();
        try(var q=prepare(db,"SELECT id,created_sequence FROM work_items WHERE dataset_id=? AND kind='ROOM_KNOWLEDGE_CAPTURED'"
                +" AND (created_sequence>? OR (created_sequence=? AND id>?)) ORDER BY created_sequence,id LIMIT 32",dataset(),after,after,afterId);var r=q.executeQuery()){
            while(r.next())found.add(new Seed(r.getString(1),r.getLong(2)));}
        for(var seed:found){String id=UUID.nameUUIDFromBytes((dataset()+"/"+space.fingerprint()+"/"+seed.id()).getBytes(StandardCharsets.UTF_8)).toString();
            update(db,"INSERT OR IGNORE INTO embedding_jobs(id,dataset_id,model_fingerprint,room_work_id,state,created_sequence) VALUES(?,?,?,?,'PENDING',?)",
                    id,dataset(),space.fingerprint(),seed.id(),sequence);}
        if(found.isEmpty())return false;var last=found.getLast();
        update(db,"INSERT INTO embedding_seed_progress VALUES(?,?,?,?) ON CONFLICT DO UPDATE SET after_sequence=excluded.after_sequence,after_work_id=excluded.after_work_id",
                dataset(),space.fingerprint(),last.sequence(),last.id());return true;
    }
    @Override public CompletionStage<Result> commitEmbedding(EmbeddingWorkToken token,float[] input){
        final Lease lease;final Completed done;
        synchronized(this){done=completed.get(token);lease=leases.get(token);}
        if(done!=null){
            if(!store.readAuthorityStable()||done.authorityGeneration()!=store.authorityGeneration())return result(Status.STALE,"SOURCE_AUTHORITY_CHANGED");
            try{return result(done.hash().equals(EmbeddingRecords.vectorHash(bytes(EmbeddingRecords.vector(input,done.space().dimensions()))))?Status.DUPLICATE:Status.REJECTED,"EMBEDDING_ALREADY_COMMITTED");}
            catch(RuntimeException invalid){return result(Status.REJECTED,"INVALID_EMBEDDING_VECTOR");}
        }
        if(lease==null)return result(Status.STALE,"UNKNOWN_EMBEDDING_LEASE");
        final byte[] vector;
        try{vector=bytes(EmbeddingRecords.vector(input,lease.work().modelSpace().dimensions()));}
        catch(RuntimeException invalid){return finishWork(token,WorkOutcome.FAILED,"INVALID_EMBEDDING_VECTOR")
                .thenApply(r->r.status()==Status.STORED?new Result(Status.REJECTED,"INVALID_EMBEDDING_VECTOR"):r);}
        if(!store.projectionAdmission())return result(Status.DEFERRED,"BACKGROUND_ADMISSION_DEFERRED");
        String hash=EmbeddingRecords.vectorHash(vector);
        return store.embeddingTransaction(lease.job(),32768L+vector.length,false,(db,sequence)->{
            if(!current(db,lease))return new Mutation<>(new Result(Status.STALE,"STALE_EMBEDDING_LEASE"),false);
            var current=store.embeddingSource(db,lease.roomJob());
            if(current.target()==null||!lease.evidence().equals(current.target())){
                if(current.retryable())defer(db,lease.job(),current.reasonCode());else terminal(db,lease.job(),"INVALIDATED","SOURCE_AUTHORITY_CHANGED");
                return new Mutation<>(new Result(Status.STALE,"SOURCE_AUTHORITY_CHANGED"),true);
            }
            long sourceKey;try(var q=prepare(db,"SELECT source_ref FROM work_items WHERE id=?",lease.roomJob());var r=q.executeQuery()){if(!r.next())throw new SQLException("EMBEDDING_SOURCE_MISSING");sourceKey=r.getLong(1);}
            Work w=lease.work();ModelSpace space=w.modelSpace();
            try(var q=prepare(db,"SELECT vector_hash,input_hash,source_hash,receipt_hash FROM embedding_rows WHERE dataset_id=? AND knowledge_receipt_id=? AND model_fingerprint=?",
                    dataset(),w.knowledgeReceiptId(),space.fingerprint());var r=q.executeQuery()){
                if(r.next()){
                    if(!hash.equals(r.getString(1))||!w.inputHash().equals(r.getString(2))||!w.source().hash().equals(r.getString(3))||!w.receiptHash().equals(r.getString(4))){
                        terminal(db,lease.job(),"FAILED","IMMUTABLE_EMBEDDING_CONFLICT");
                        return new Mutation<>(new Result(Status.REJECTED,"IMMUTABLE_EMBEDDING_CONFLICT"),true);
                    }
                    terminal(db,lease.job(),"DONE","");return new Mutation<>(new Result(Status.DUPLICATE,"EMBEDDING_ALREADY_COMMITTED"),true);
                }
            }
            update(db,"INSERT INTO embedding_rows VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",UUID.nameUUIDFromBytes((lease.job()+"/vector").getBytes(StandardCharsets.UTF_8)),
                    dataset(),lease.job(),lease.roomJob(),w.messageId(),sourceKey,w.source().hash(),w.knowledgeReceiptId(),w.receiptHash(),w.observerGodId(),w.actualActor().kind(),w.actualActor().id(),
                    w.disclosureHash(),space.fingerprint(),space.modelName(),space.modelDigest(),space.dimensions(),space.encoderVersion(),w.inputHash(),w.coveredCharacters(),w.totalCharacters(),vector,hash,sequence);
            terminal(db,lease.job(),"DONE","");
            return new Mutation<>(new Result(Status.STORED,"EMBEDDING_DURABLE_COMMIT"),true,
                    ()->live(lease.worker())&&store.readAuthorityStable()&&w.leaseDeadlineEpochMillis()>System.currentTimeMillis());
        }).handle((value,failure)->{
            if(failure!=null)return new Result(failureStatus(failure),failureCode(failure));
            if(Set.of(Status.STORED,Status.DUPLICATE,Status.STALE,Status.REJECTED).contains(value.status()))consume(token,lease);
            if(value.status()==Status.STORED)synchronized(this){completed.put(token,new Completed(lease.work().modelSpace(),hash,store.authorityGeneration()));
                while(completed.size()>32)completed.remove(completed.keySet().iterator().next());}
            return value;
        }).minimalCompletionStage();
    }
    @Override public CompletionStage<Result> finishWork(EmbeddingWorkToken token,WorkOutcome outcome,String reason){
        Objects.requireNonNull(outcome);ProjectionRecords.reason(reason);final Lease lease;
        synchronized(this){lease=leases.get(token);}if(lease==null)return result(Status.STALE,"UNKNOWN_EMBEDDING_LEASE");
        return store.embeddingTransaction(lease.job(),4096,true,(db,sequence)->{
            if(!current(db,lease))return new Mutation<>(new Result(Status.STALE,"STALE_EMBEDDING_LEASE"),false);
            if(outcome==WorkOutcome.DEFERRED)defer(db,lease.job(),reason);
            else {
                int attempts;try(var q=prepare(db,"SELECT attempt_count FROM embedding_jobs WHERE id=?",lease.job());var r=q.executeQuery()){r.next();attempts=r.getInt(1)+1;}
                update(db,"UPDATE embedding_jobs SET attempt_count=?,state=?,next_attempt_millis=?,last_failure=?,lease_epoch=NULL,lease_nonce=NULL,lease_deadline_millis=NULL WHERE id=?",
                        attempts,attempts>=3?"FAILED":"PENDING",System.currentTimeMillis()+Math.min(30,attempts*2)*1000L,reason,lease.job());
            }
            return new Mutation<>(new Result(Status.STORED,"EMBEDDING_OUTCOME_RECORDED"),true);
        }).handle((value,failure)->{if(failure!=null)return new Result(failureStatus(failure),failureCode(failure));consume(token,lease);return value;}).minimalCompletionStage();
    }
    private synchronized void consume(EmbeddingWorkToken token,Lease lease){leases.remove(token);if(lease.worker().token==token)lease.worker().token=null;}
    private boolean current(Connection db,Lease lease)throws SQLException{
        if(!live(lease.worker())||!store.readAuthorityStable()||lease.work().leaseDeadlineEpochMillis()<=System.currentTimeMillis())return false;
        try(var q=prepare(db,"SELECT state,lease_epoch,lease_nonce,model_fingerprint FROM embedding_jobs WHERE id=?",lease.job());var r=q.executeQuery()){
            return r.next()&&"LEASED".equals(r.getString(1))&&store.runtimeEpoch().toString().equals(r.getString(2))&&lease.nonce().equals(r.getString(3))&&lease.worker().space.fingerprint().equals(r.getString(4));}
    }
    private static void defer(Connection db,String id,String reason)throws SQLException{update(db,"UPDATE embedding_jobs SET state='PENDING',next_attempt_millis=?,last_failure=?,lease_epoch=NULL,lease_nonce=NULL,lease_deadline_millis=NULL WHERE id=?",System.currentTimeMillis()+1000,reason,id);}
    private static void terminal(Connection db,String id,String state,String reason)throws SQLException{update(db,"UPDATE embedding_jobs SET state=?,last_failure=?,lease_epoch=NULL,lease_nonce=NULL,lease_deadline_millis=NULL WHERE id=?",state,reason,id);}
    private UUID dataset(){return store.datasetId().orElseThrow();}
    private static byte[] bytes(float[] values){var buffer=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN);for(float value:values)buffer.putFloat(value);return buffer.array();}
    private static ClaimResult empty(Status status,String reason){return new ClaimResult(status,Optional.empty(),reason);}
    private static CompletionStage<ClaimResult> claim(Status status,String reason){return CompletableFuture.completedFuture(empty(status,reason));}
    private static CompletionStage<Result> result(Status status,String reason){return CompletableFuture.completedFuture(new Result(status,reason));}
    private static Throwable cause(Throwable error){while((error instanceof CompletionException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();return error;}
    private static Status failureStatus(Throwable error){return cause(error) instanceof StorageFailure failure?failure.status:Status.UNAVAILABLE;}
    private static String failureCode(Throwable error){return cause(error) instanceof StorageFailure failure?failure.code:"EMBEDDING_STORAGE_UNAVAILABLE";}
    private static PreparedStatement prepare(Connection db,String sql,Object...values)throws SQLException{
        var q=db.prepareStatement(sql);q.setQueryTimeout(1);for(int i=0;i<values.length;i++){Object value=values[i];
            if(value instanceof byte[] bytes)q.setBytes(i+1,bytes);else if(value instanceof Number number)q.setLong(i+1,number.longValue());else q.setString(i+1,value==null?null:value.toString());}return q;
    }
    private static int update(Connection db,String sql,Object...values)throws SQLException{try(var q=prepare(db,sql,values)){return q.executeUpdate();}}
}
