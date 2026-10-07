package com.sande.mythictrpg.recording.server;

import com.google.gson.*;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.ProjectionRecords.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.ActorKind;
import com.sande.mythictrpg.recording.api.RecordingRecords.ActorRef;
import com.sande.mythictrpg.recording.api.RecordingRecords.DeliveryView;
import com.sande.mythictrpg.recording.api.RecordingRecords.SourceKind;
import com.sande.mythictrpg.recording.api.RecordingRecords.SourceRef;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.sande.mythictrpg.recording.api.RecordingRecords.sha256;

/** Bounded native-room projection worker. All mutations use the recording writer and its quota transaction. */
final class RecordingProjectionStore implements MemoryProjectionPort {
    record Mutation<T>(T value, boolean changed, java.util.function.BooleanSupplier valid) {
        Mutation(T value,boolean changed){this(value,changed,()->true);}
    }
    static final class StorageFailure extends RuntimeException {
        final Status status; final String code;
        StorageFailure(Status status, String code) { super(code); this.status=status; this.code=code; }
    }
    private static final Gson JSON=new Gson();
    private static final int SCAN_LIMIT=32, MAX_ATTEMPTS=3;
    private final WorldRecordingService store;
    private final Map<ProjectionWorkerCapability,Worker> workers=new IdentityHashMap<>();
    private final Map<ProjectionWorkToken,Lease> leases=new IdentityHashMap<>();
    private final LinkedHashMap<ProjectionWorkToken,Completed> completed=new LinkedHashMap<>();
    private boolean closed;
    private static final class Worker {
        final String id,version; boolean claiming; ProjectionWorkToken token;
        Worker(String id,String version) {this.id=id;this.version=version;}
    }
    private record Lease(Worker worker,String job,String nonce,Work work,Map<String,Node> sources) { }
    private record Completed(String fingerprint,Result result,long authorityGeneration) { }
    private record Job(String id,String kind,String message,String receipt,long source,String version,String state,int attempts) { }
    private record Node(long sourceKey,SourceRef source,UUID receipt,String receiptHash,UUID message,UUID conversation,
                        ActorRef actor,String god,Set<ActorRef> audience,String disclosure,String policy,String mode,
                        Instant occurred,String text,long sequence,Set<UUID> parents) { }
    private static final class Unsupported extends Exception { final String code; Unsupported(String code){this.code=code;} }
    private static final class SourceDeferred extends Exception { final String code; SourceDeferred(String code){this.code=code;} }
    private static final class ReadWindow {long bytes;final long deadline=System.nanoTime()+250_000_000L;}

    RecordingProjectionStore(WorldRecordingService store) {this.store=store;}
    /** Shared native authority check for a verbatim embedding, never a fabricated journal record. */
    record EmbeddingSource(Evidence target,String reasonCode,boolean retryable) { }
    EmbeddingSource embeddingSource(Connection db,String roomWorkId)throws Exception {
        var window=new ReadWindow();
        try {
            Node target=load(db,roomWorkId,window);
            if(target==null)return new EmbeddingSource(null,"SOURCE_AUTHORITY_UNAVAILABLE",false);
            var required=new LinkedHashMap<UUID,Node>();required.put(target.message(),target);
            ancestors(db,target,target,required,new HashSet<>(),window,6);
            String text=EmbeddingRecords.prefix(target.text());
            if(text.isBlank())return new EmbeddingSource(null,"EMPTY_EMBEDDING_PREFIX",false);
            return new EmbeddingSource(evidence("e0",target,text),"NATIVE_SOURCE_VALID",false);
        } catch(Unsupported unsupported){return new EmbeddingSource(null,unsupported.code,false);}
        catch(SourceDeferred deferred){return new EmbeddingSource(null,deferred.code,true);}
    }
    ProjectionWorkerCapability register(String id,String version) {
        ProjectionRecords.version(version);
        if (id==null || !id.matches("[a-z0-9_.:-]{1,128}")
                || store.health().state()!=WorldRecordingService.State.READY) throw new IllegalStateException("PROJECTION_REGISTRATION_UNAVAILABLE");
        synchronized(this) {
        if(closed)throw new IllegalStateException("PROJECTION_REGISTRATION_UNAVAILABLE");
        for(var entry:new ArrayList<>(workers.entrySet())) if(entry.getValue().id.equals(id)) {
            if(entry.getValue().version.equals(version)) return entry.getKey();
            Worker old=entry.getValue(); workers.remove(entry.getKey());
            leases.entrySet().removeIf(e->e.getValue().worker()==old);
        }
        if(workers.size()>=4) throw new IllegalStateException("PROJECTION_WORKER_LIMIT");
        var capability=ProjectionWorkerCapability.unregistered(); workers.put(capability,new Worker(id,version)); return capability;
        }
    }
    synchronized void close(){closed=true;workers.clear();leases.clear();completed.clear();}
    @Override public synchronized void revokeWorker(ProjectionWorkerCapability capability){
        Worker worker=workers.remove(capability);if(worker!=null){leases.entrySet().removeIf(e->e.getValue().worker()==worker);worker.token=null;}
        // Completed retries are harmless, but no revoked capability may return another result token.
        completed.clear();
    }
    private synchronized boolean live(Worker worker){return !closed&&workers.containsValue(worker);}

    @Override public CompletionStage<ClaimResult> claimWork(ProjectionWorkerCapability capability,WorkBudget budget) {
        Objects.requireNonNull(budget); final Worker worker;
        synchronized(this) {
            worker=workers.get(capability);
            if(worker==null||closed)return claim(Status.REJECTED,"INVALID_PROJECTION_WORKER");
            if(worker.token!=null){var lease=leases.get(worker.token);
                if(lease==null||lease.work().leaseDeadlineEpochMillis()<=System.currentTimeMillis()){leases.remove(worker.token);worker.token=null;}
            }
            if(worker.claiming||worker.token!=null)return claim(Status.DEFERRED,"WORKER_ALREADY_BUSY");
            if(!store.projectionAdmission())return claim(Status.DEFERRED,"BACKGROUND_ADMISSION_DEFERRED");
            worker.claiming=true;
        }
        // Cancelling a caller's wait must not suppress internal worker/lease cleanup. The exposed
        // minimal stage produces independent futures; an abandoned granted lease still expires.
        return store.projectionTransaction("claim",32768,false,(db,sequence)->claim(db,worker,budget,sequence))
                .handle((result,failure)->{
                    synchronized(this){worker.claiming=false;}
                    if(failure!=null){synchronized(this){if(worker.token!=null){leases.remove(worker.token);worker.token=null;}}
                        return new ClaimResult(failureStatus(failure),Optional.empty(),failureCode(failure));}
                    return result;
                }).minimalCompletionStage();
    }
    private Mutation<ClaimResult> claim(Connection db,Worker worker,WorkBudget budget,long sequence)throws Exception {
        if(!live(worker)||!store.readAuthorityStable())return new Mutation<>(empty(Status.STALE,"PROJECTION_AUTHORITY_CHANGED"),false);
        String now=Instant.now().toString(); var jobs=new ArrayList<Job>();
        try(var q=prepare(db,"SELECT id,kind,message_id,receipt_id,source_ref,extractor_version,state,attempt_count FROM work_items WHERE dataset_id=?"
                +" AND state<>'INVALIDATED' AND ("
                +" (state='PENDING' AND (next_attempt_utc IS NULL OR julianday(next_attempt_utc)<=julianday(?)))"
                +" OR (state='LEASED' AND (julianday(lease_deadline)<=julianday(?) OR extractor_version<>?))"
                +" OR (state IN ('DONE','FAILED') AND extractor_version<>?)"
                +" OR (state='SKIPPED_UNSUPPORTED' AND last_failure='EXTERNAL_OR_ANCESTRY_EVIDENCE_UNSUPPORTED' AND extractor_version<>?))"
                // Permanently missing proofs must not monopolize the oldest 32 slots on every
                // wake-up. New work first; due deferred work rotates by its persisted retry time.
                +" ORDER BY CASE WHEN state='PENDING' AND next_attempt_utc IS NULL THEN 0 ELSE 1 END,"
                +" next_attempt_utc,created_sequence,id LIMIT 32",
                store.datasetId().orElseThrow(),now,now,worker.version,worker.version,worker.version);var r=q.executeQuery()) {
            while(r.next())jobs.add(new Job(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getString(6),r.getString(7),r.getInt(8)));
        }
        boolean changed=false;var window=new ReadWindow();
        for(var job:jobs) {
            if(!job.kind().equals("ROOM_KNOWLEDGE_CAPTURED")){
                terminal(db,job.id(),"SKIPPED_UNSUPPORTED","SOURCE_KIND_NOT_SUPPORTED",worker.version);changed=true;continue;
            }
            Node target;
            try {target=load(db,job.id(),window);}
            catch(Unsupported unsupported){terminal(db,job.id(),"SKIPPED_UNSUPPORTED",unsupported.code,worker.version);changed=true;continue;}
            catch(SourceDeferred deferred){deferSource(db,job.id(),deferred.code);changed=true;continue;}
            if(target==null){terminal(db,job.id(),"INVALIDATED","SOURCE_AUTHORITY_UNAVAILABLE",worker.version);changed=true;continue;}
            // A derived native utterance cannot lose the sources it depends on. Resolve required
            // ancestors BEFORE adding optional recent context, within the same narrow partition.
            var required=new LinkedHashMap<UUID,Node>();required.put(target.message(),target);
            try { ancestors(db,target,target,required,new HashSet<>(),window,budget.maxSources()); }
            catch(Unsupported unsupported){terminal(db,job.id(),"SKIPPED_UNSUPPORTED",unsupported.code,worker.version);changed=true;continue;}
            catch(SourceDeferred deferred){deferSource(db,job.id(),deferred.code);changed=true;continue;}
            var nodes=new ArrayList<>(required.values());nodes.sort(Comparator.comparingLong(Node::sequence));
            int bytes=0;var excerpts=new HashMap<UUID,String>();
            // Share input budget so a long child cannot squeeze every mandatory ancestor out.
            int perRequired=budget.maxUtf8Bytes()/nodes.size();boolean missingExcerpt=false;
            for(Node node:nodes){String text=prefix(node.text(),perRequired);if(text.isBlank()){missingExcerpt=true;break;}
                excerpts.put(node.receipt(),text);bytes+=utf8(text);}
            if(missingExcerpt){terminal(db,job.id(),"SKIPPED_UNSUPPORTED","ANCESTRY_INPUT_BUDGET",worker.version);changed=true;continue;}
            var contextIds=new ArrayList<String>();
            if(budget.maxSources()>1 && bytes<budget.maxUtf8Bytes())try(var q=prepare(db,
                    "SELECT w.id FROM work_items w JOIN messages m ON m.id=w.message_id JOIN knowledge_receipts k ON k.source_ref=w.source_ref"
                    +" AND json_extract(k.projection,'$.deliveryReceiptId')=w.receipt_id"
                    +" WHERE w.dataset_id=? AND w.kind='ROOM_KNOWLEDGE_CAPTURED' AND w.id<>? AND m.conversation_id=? AND k.god_id=?"
                    +" AND julianday(m.occurred_utc)<=julianday(?) AND m.ingest_sequence<(SELECT ingest_sequence FROM messages WHERE id=?)"
                    +" ORDER BY m.ingest_sequence DESC LIMIT 32",store.datasetId().orElseThrow(),job.id(),target.conversation(),target.god(),target.occurred(),target.message());var r=q.executeQuery()) {
                while(r.next())contextIds.add(r.getString(1));
            }
            for(String contextId:contextIds){
                if(nodes.size()>=budget.maxSources()||budget.maxUtf8Bytes()-bytes<256)break;
                Node node;try{node=load(db,contextId,window);}catch(Unsupported unsupported){continue;}catch(SourceDeferred deferred){break;}
                // Optional episodes with their own ancestry are not flattened into this work.
                if(node==null||required.containsKey(node.message())||!node.parents().isEmpty()
                        ||node.occurred().isAfter(target.occurred())||!sameScope(target,node))continue;
                String excerpt=prefix(node.text(),budget.maxUtf8Bytes()-bytes);if(excerpt.isBlank())continue;
                nodes.add(node);excerpts.put(node.receipt(),excerpt);bytes+=utf8(excerpt);
            }
            nodes.sort(Comparator.comparingLong(Node::sequence));
            var evidence=new ArrayList<Evidence>();var bindings=new LinkedHashMap<String,Node>();
            for(Node node:nodes){String alias="e"+evidence.size();String text=excerpts.get(node.receipt());
                evidence.add(evidence(alias,node,text));bindings.put(alias,node);}
            var token=ProjectionWorkToken.unregistered();long deadline=System.currentTimeMillis()+budget.leaseSeconds()*1000L;
            var work=new Work(token,worker.version,evidence,evidence.getLast().alias(),deadline);String nonce=UUID.randomUUID().toString();
            update(db,"UPDATE work_items SET state='LEASED',extractor_version=?,attempt_count=?,next_attempt_utc=NULL,last_failure='',lease_epoch=?,lease_deadline=?,lease_nonce=? WHERE id=?",
                    worker.version,job.version().equals(worker.version)?job.attempts():0,store.runtimeEpoch(),Instant.ofEpochMilli(deadline),nonce,job.id());
            // The enclosing writer completes the returned future only after COMMIT. No token is visible before that future.
            synchronized(this){if(!live(worker))return new Mutation<>(empty(Status.STALE,"PROJECTION_WORKER_REPLACED"),true);
                leases.put(token,new Lease(worker,job.id(),nonce,work,Map.copyOf(bindings)));worker.token=token;}
            return new Mutation<>(new ClaimResult(Status.CLAIMED,Optional.of(work),"PROJECTION_LEASED"),true,()->live(worker)&&store.readAuthorityStable());
        }
        return new Mutation<>(empty(Status.EMPTY,jobs.size()==SCAN_LIMIT?"SCAN_BOUND_REACHED":"NO_ELIGIBLE_PROJECTION"),changed);
    }

    @Override public CompletionStage<Result> commitProjection(ProjectionWorkToken token,List<Candidate> candidates) {
        final List<Candidate> copy;try{copy=List.copyOf(candidates);}catch(RuntimeException invalid){return finishRejected(token,"INVALID_CANDIDATES");}
        String fingerprint=sha256(JSON.toJson(copy));final Lease lease;
        synchronized(this){var done=completed.get(token);if(done!=null&&!store.readAuthorityStable())return result(Status.STALE,"SOURCE_AUTHORITY_CHANGED",0);
            if(done!=null&&done.authorityGeneration()!=store.authorityGeneration())return result(Status.STALE,"SOURCE_AUTHORITY_CHANGED",0);
            if(done!=null)return CompletableFuture.completedFuture(done.fingerprint().equals(fingerprint)
                ?new Result(Status.DUPLICATE,"PROJECTION_ALREADY_COMMITTED",done.result().memories()):new Result(Status.REJECTED,"PROJECTION_RETRY_CONFLICT",0));lease=leases.get(token);}
        if(lease==null)return result(Status.STALE,"UNKNOWN_PROJECTION_LEASE",0);
        if(!store.projectionAdmission())return result(Status.DEFERRED,"BACKGROUND_ADMISSION_DEFERRED",0);
        long bytes=Math.min(512_000L,32768L+ProjectionInputManifest.MAX_BYTES*2L+utf8(JSON.toJson(copy))*4L);
        return store.projectionTransaction(lease.job(),bytes,false,(db,sequence)->{
            if(!current(db,lease))return new Mutation<>(new Result(Status.STALE,"STALE_PROJECTION_LEASE",0),false);
            String authority;try{authority=authority(db,lease);}catch(SourceDeferred deferred){return new Mutation<>(new Result(Status.DEFERRED,deferred.code,0),false);}
            if(authority.equals("TARGET_CHANGED")) {terminal(db,lease.job(),"INVALIDATED","SOURCE_AUTHORITY_CHANGED",lease.worker().version);
                return new Mutation<>(new Result(Status.STALE,"SOURCE_AUTHORITY_CHANGED",0),true);}
            if(authority.equals("CONTEXT_CHANGED")){deferSource(db,lease.job(),"CONTEXT_AUTHORITY_CHANGED");
                return new Mutation<>(new Result(Status.STALE,"CONTEXT_AUTHORITY_CHANGED",0),true);}
            String error=validate(lease.work(),copy);
            if(error!=null){failed(db,lease,error);return new Mutation<>(new Result(Status.REJECTED,error,0),true);}
            final ProjectionInputManifest.Manifest manifest;
            try {manifest=ProjectionInputManifest.create(store.datasetId().orElseThrow(),lease.job(),lease.work(),copy,sequence);
                ProjectionInputManifest.encode(manifest);}
            catch(RuntimeException invalid){failed(db,lease,"INPUT_MANIFEST_BOUNDS");return new Mutation<>(new Result(Status.REJECTED,"INPUT_MANIFEST_BOUNDS",0),true);}
            for(Candidate candidate:copy) persist(db,lease,candidate,sequence);
            ProjectionInputManifest.insert(db,manifest);
            terminal(db,lease.job(),"DONE","",lease.worker().version);
            return new Mutation<>(new Result(Status.STORED,"PROJECTION_DURABLE_COMMIT",copy.size()),true,
                    ()->live(lease.worker())&&store.readAuthorityStable()&&lease.work().leaseDeadlineEpochMillis()>System.currentTimeMillis());
        }).handle((value,failure)->{
            if(failure!=null)return new Result(failureStatus(failure),failureCode(failure),0);
            if(Set.of(Status.STORED,Status.REJECTED,Status.STALE).contains(value.status()))consume(token,lease);
            if(value.status()==Status.STORED)synchronized(this){completed.put(token,new Completed(fingerprint,value,store.authorityGeneration()));while(completed.size()>32)completed.remove(completed.keySet().iterator().next());}
            return value;
        }).minimalCompletionStage();
    }
    private CompletionStage<Result> finishRejected(ProjectionWorkToken token,String reason){
        return finishWork(token,WorkOutcome.FAILED,reason).thenApply(r->r.status()==Status.STORED?new Result(Status.REJECTED,reason,0):r);
    }
    @Override public CompletionStage<Result> finishWork(ProjectionWorkToken token,WorkOutcome outcome,String code) {
        Objects.requireNonNull(outcome);ProjectionRecords.reason(code);final Lease lease;
        synchronized(this){lease=leases.get(token);}if(lease==null)return result(Status.STALE,"UNKNOWN_PROJECTION_LEASE",0);
        return store.projectionTransaction(lease.job(),4096,true,(db,sequence)->{
            if(!current(db,lease))return new Mutation<>(new Result(Status.STALE,"STALE_PROJECTION_LEASE",0),false);
            if(outcome==WorkOutcome.FAILED)failed(db,lease,code);
            else if(outcome==WorkOutcome.SKIPPED_UNSUPPORTED)terminal(db,lease.job(),"SKIPPED_UNSUPPORTED",code,lease.worker().version);
            else update(db,"UPDATE work_items SET state='PENDING',lease_epoch=NULL,lease_deadline=NULL,lease_nonce=NULL,next_attempt_utc=?,last_failure=? WHERE id=?",
                    Instant.now().plusSeconds(1),code,lease.job());
            return new Mutation<>(new Result(Status.STORED,"WORK_OUTCOME_RECORDED",0),true);
        }).handle((value,failure)->{if(failure!=null)return new Result(failureStatus(failure),failureCode(failure),0);consume(token,lease);return value;})
                .minimalCompletionStage();
    }
    private synchronized void consume(ProjectionWorkToken token,Lease lease){leases.remove(token);if(lease.worker().token==token)lease.worker().token=null;}
    private boolean current(Connection db,Lease lease)throws SQLException {
        if(!live(lease.worker())||lease.work().leaseDeadlineEpochMillis()<=System.currentTimeMillis()||!store.readAuthorityStable())return false;
        try(var q=prepare(db,"SELECT state,lease_epoch,lease_nonce,extractor_version FROM work_items WHERE id=?",lease.job());var r=q.executeQuery()){
            return r.next()&&r.getString(1).equals("LEASED")&&store.runtimeEpoch().toString().equals(r.getString(2))
                    &&lease.nonce().equals(r.getString(3))&&lease.worker().version.equals(r.getString(4));}
    }
    private String authority(Connection db,Lease lease)throws Exception {
        var window=new ReadWindow();
        var ordered=new ArrayList<>(lease.sources().entrySet());ordered.sort(Comparator.comparingInt(e->e.getKey().equals(lease.work().targetAlias())?0:1));
        for(var e:ordered){
            Node old=e.getValue();String job=null;
            try(var q=prepare(db,"SELECT id FROM work_items WHERE source_version=? AND kind='ROOM_KNOWLEDGE_CAPTURED' AND dataset_id=?",
                    old.receipt()+":"+old.receiptHash(),store.datasetId().orElseThrow());var r=q.executeQuery()){if(r.next())job=r.getString(1);}
            String stale=e.getKey().equals(lease.work().targetAlias())?"TARGET_CHANGED":"CONTEXT_CHANGED";
            if(job==null)return stale;Node current;try{current=load(db,job,window);}catch(Unsupported ignored){return stale;}
            if(current==null||!old.equals(current))return stale;
        }
        return "CURRENT";
    }
    private void ancestors(Connection db,Node target,Node child,Map<UUID,Node> required,Set<UUID> path,
            ReadWindow window,int limit)throws Exception {
        if(!path.add(child.message()))throw new Unsupported("CYCLIC_NATIVE_ANCESTRY");
        for(UUID id:new TreeSet<>(child.parents())) {
            Node parent=required.get(id);
            if(parent==null) {
                if(required.size()>=limit)throw new Unsupported("NATIVE_ANCESTRY_SOURCE_BUDGET");
                String parentJob=null;
                try(var q=prepare(db,"SELECT w.id FROM work_items w JOIN knowledge_receipts k ON k.source_ref=w.source_ref"
                        +" AND json_extract(k.projection,'$.deliveryReceiptId')=w.receipt_id"
                        +" WHERE w.dataset_id=? AND w.kind='ROOM_KNOWLEDGE_CAPTURED' AND w.message_id=? AND k.god_id=? LIMIT 1",
                        store.datasetId().orElseThrow(),id,target.god());var r=q.executeQuery()){if(r.next())parentJob=r.getString(1);}
                // A parent's receipt batch may commit later. Preserve RAW and defer, never infer hearing.
                if(parentJob==null)throw new SourceDeferred("AWAITING_NATIVE_PARENT_RECEIPT");
                parent=load(db,parentJob,window);
                if(parent==null)throw new Unsupported("NATIVE_PARENT_AUTHORITY_UNAVAILABLE");
                required.put(id,parent);
            }
            if(parent.sequence()>=child.sequence()||path.contains(id))throw new Unsupported("FORWARD_OR_CYCLIC_NATIVE_ANCESTRY");
            if(!sameScope(target,parent)||parent.occurred().isAfter(target.occurred()))throw new Unsupported("NATIVE_PARENT_SCOPE_MISMATCH");
            ancestors(db,target,parent,required,path,window,limit);
        }
        path.remove(child.message());
    }
    private static void failed(Connection db,Lease lease,String code)throws SQLException {
        update(db,"UPDATE work_items SET attempt_count=attempt_count+1,state=CASE WHEN attempt_count+1>=? THEN 'FAILED' ELSE 'PENDING' END,"
                +"next_attempt_utc=?,last_failure=?,lease_epoch=NULL,lease_deadline=NULL,lease_nonce=NULL WHERE id=?",
                MAX_ATTEMPTS,Instant.now().plusSeconds(5),code,lease.job());
    }
    private static void terminal(Connection db,String job,String state,String code,String version)throws SQLException {
        update(db,"UPDATE work_items SET state=?,last_failure=?,extractor_version=?,lease_epoch=NULL,lease_deadline=NULL,lease_nonce=NULL,next_attempt_utc=NULL WHERE id=?",state,code,version,job);
    }
    private static void deferSource(Connection db,String job,String code)throws SQLException {
        update(db,"UPDATE work_items SET state='PENDING',next_attempt_utc=?,last_failure=?,lease_epoch=NULL,lease_deadline=NULL,lease_nonce=NULL WHERE id=?",Instant.now().plusSeconds(5),code,job);
    }

    private Node load(Connection db,String jobId,ReadWindow window)throws Exception {
        if(System.nanoTime()>window.deadline)throw new SourceDeferred("PROJECTION_READ_BUDGET");
        String sql="SELECT s.id sid,s.kind skind,s.owner,s.source_id,s.source_revision,s.source_hash,s.revoked,k.id kid,k.god_id,k.receipt_hash,k.audience_json,k.projection,k.policy_revision,"
                +"m.id mid,m.conversation_id,m.actor_kind,m.actor_id,m.occurred_utc,m.body_hash,m.body_bytes,m.ingest_sequence,x.context_json,c.channel,c.policy,"
                +"d.id did,d.actor_kind dak,d.actor_id dai,d.kind dkind,d.status,d.view_hash,v.plain_text,v.parts_json,w.source_version"
                +" FROM work_items w JOIN source_refs s ON s.id=w.source_ref JOIN knowledge_receipts k ON k.source_ref=s.id"
                +" AND json_extract(k.projection,'$.deliveryReceiptId')=w.receipt_id JOIN messages m ON m.id=w.message_id"
                +" JOIN message_contexts x ON x.message_id=m.id JOIN conversations c ON c.id=m.conversation_id"
                +" JOIN deliveries d ON d.id=w.receipt_id JOIN delivery_views v ON v.message_id=m.id AND v.view_hash=d.view_hash"
                +" WHERE w.id=? AND w.dataset_id=? AND k.dataset_id=w.dataset_id AND s.dataset_id=w.dataset_id AND m.dataset_id=w.dataset_id"
                +" AND k.acquisition='DIRECT_HEARD' AND m.producer='room-publication-v2'"
                +" AND NOT EXISTS(SELECT 1 FROM knowledge_invalidations ki WHERE ki.dataset_id=k.dataset_id AND ki.receipt_id=k.id)"
                +" AND NOT EXISTS(SELECT 1 FROM source_refs newer WHERE newer.dataset_id=s.dataset_id AND newer.kind=s.kind AND newer.owner=s.owner AND newer.source_id=s.source_id AND newer.source_revision>s.source_revision)"
                +" AND NOT EXISTS(SELECT 1 FROM invalidations i WHERE i.dataset_id=s.dataset_id AND i.kind=s.kind AND i.owner=s.owner AND i.source_id=s.source_id AND i.source_revision=s.source_revision)";
        try(var q=prepare(db,sql,jobId,store.datasetId().orElseThrow());var r=q.executeQuery()) {
            if(!r.next()||r.getInt("revoked")!=0)return null;
            if(!r.getString("owner").equals("room-publication-v2")||r.getLong("source_revision")!=1)throw new Unsupported("NON_NATIVE_SOURCE");
            long bodyBytes=r.getLong("body_bytes");String contextJson=r.getString("context_json");
            if(bodyBytes>1_048_576||bodyBytes<1||contextJson.length()>262144)throw new Unsupported("SOURCE_BUDGET_EXCEEDED");
            if(window.bytes+bodyBytes>2_097_152)throw new SourceDeferred("PROJECTION_READ_BUDGET");window.bytes+=bodyBytes;
            JsonObject context=JsonParser.parseString(contextJson).getAsJsonObject();
            if(!context.has("memoryMode")||!Set.of("PERSONAL","RUMOR_TEST").contains(context.get("memoryMode").getAsString()))throw new Unsupported("MEMORY_POLICY_UNSUPPORTED");
            if(context.getAsJsonArray("evidence").size()!=0)throw new Unsupported("EXTERNAL_EVIDENCE_UNSUPPORTED");
            if(context.getAsJsonArray("sourceMessages").size()>6)throw new Unsupported("NATIVE_ANCESTRY_SOURCE_BUDGET");
            var parents=new HashSet<UUID>();for(String id:strings(context.getAsJsonArray("sourceMessages")))parents.add(UUID.fromString(id));
            String policy=context.get("recordingPolicy").getAsString(),mode=context.get("memoryMode").getAsString();
            if(!Set.of("STANDARD","TEST_RECORDING").contains(policy))return null;
            String channel=r.getString("channel"),privacy=r.getString("policy");
            if(!(channel.equals("ROOM_PUBLIC")&&privacy.equals("PUBLIC_SPEECH")||channel.equals("ROOM_PRIVATE")&&privacy.equals("ACTUAL_LISTENERS_ONLY")))return null;
            UUID message=UUID.fromString(r.getString("mid"));String god=r.getString("god_id");
            var actor=new ActorRef(ActorKind.valueOf(r.getString("actor_kind")),r.getString("actor_id"));
            SourceKind kind=SourceKind.valueOf(r.getString("skind"));
            if(kind!=(actor.kind()==ActorKind.PLAYER?SourceKind.DIALOGUE_DIRECT:SourceKind.DERIVED_SPEECH))return null;
            if(!r.getString("source_id").equals(message.toString())||!r.getString("dak").equals("GOD")||!r.getString("dai").equals(god)
                    ||!r.getString("dkind").equals("GAME_HEARD")||!r.getString("status").equals("SERVER_DISPATCHED"))return null;
            String sourceHash=sha256(r.getString("body_hash")+":"+JSON.toJson(context));if(!sourceHash.equals(r.getString("source_hash")))return null;
            String receipt=r.getString("kid"),receiptHash=r.getString("receipt_hash");
            if(!r.getString("source_version").equals(receipt+":"+receiptHash))return null;
            JsonObject pointer=JsonParser.parseString(r.getString("projection")).getAsJsonObject();
            if(!pointer.get("messageId").getAsString().equals(message.toString())||!pointer.get("deliveryReceiptId").getAsString().equals(r.getString("did"))
                    ||!pointer.get("viewHash").getAsString().equals(r.getString("view_hash")))return null;
            Set<String> allowed=strings(JsonParser.parseString(r.getString("audience_json")).getAsJsonArray());
            if(allowed.isEmpty()||allowed.size()>256||!allowed.equals(strings(context.getAsJsonArray("fullAudience")))||!allowed.contains("GOD:"+god))return null;
            Set<ActorRef> audience=new HashSet<>();
            for(String key:allowed){int sep=key.indexOf(':');var member=new ActorRef(ActorKind.valueOf(key.substring(0,sep)),key.substring(sep+1));audience.add(member);
                try(var aq=prepare(db,"SELECT 1 FROM deliveries WHERE message_id=? AND actor_kind=? AND actor_id=? AND status='SERVER_DISPATCHED'"
                        +(member.kind()==ActorKind.GOD?" AND kind='GAME_HEARD'":"")+" LIMIT 1",message,member.kind(),member.id());var ar=aq.executeQuery()){if(!ar.next())throw new SourceDeferred("AWAITING_ACTUAL_AUDIENCE_RECEIPTS");}}
            var raw=new StringBuilder();int index=0;try(var pq=prepare(db,"SELECT part_index,body FROM message_parts WHERE message_id=? ORDER BY part_index",message);var pr=pq.executeQuery()){
                while(pr.next()){if(System.nanoTime()>window.deadline)throw new SourceDeferred("PROJECTION_READ_BUDGET");if(pr.getInt(1)!=index++)return null;raw.append(pr.getString(2));if(raw.length()>bodyBytes)return null;}}
            if(utf8(raw.toString())!=bodyBytes||!sha256(raw.toString()).equals(r.getString("body_hash")))return null;
            String text=r.getString("plain_text");List<String> parts=new ArrayList<>();JsonParser.parseString(r.getString("parts_json")).getAsJsonArray().forEach(p->parts.add(p.getAsString()));
            var view=new DeliveryView(text,parts);if(!view.hash().equals(r.getString("view_hash"))||!text.equals(raw.toString()))return null;
            // GAME_HEARD must prove the complete view, not only a planned recipient or partial HUD.
            try(var pq=prepare(db,"SELECT part_index,body FROM delivery_parts_resolved WHERE receipt_id=? ORDER BY part_index",r.getString("did"));var pr=pq.executeQuery()){
                int n=0;while(pr.next()){if(n>=parts.size()||pr.getInt(1)!=n||!parts.get(n++).equals(pr.getString(2)))return null;}if(n!=parts.size())return null;}
            String disclosure=sha256(JSON.toJson(List.of(god,new TreeSet<>(allowed),policy,mode,channel,privacy,r.getLong("policy_revision"))));
            var source=new SourceRef(store.worldId(),store.datasetId().orElseThrow(),kind,"room-publication-v2",message.toString(),1,sourceHash);
            return new Node(r.getLong("sid"),source,UUID.fromString(receipt),receiptHash,message,UUID.fromString(r.getString("conversation_id")),actor,god,
                    Set.copyOf(audience),disclosure,policy,mode,Instant.parse(r.getString("occurred_utc")),text,r.getLong("ingest_sequence"),Set.copyOf(parents));
        } catch(RuntimeException invalid){throw new Unsupported("MALFORMED_SOURCE_METADATA");}
    }
    private static boolean sameScope(Node a,Node b){return a.god().equals(b.god())&&a.disclosure().equals(b.disclosure())&&a.audience().equals(b.audience())&&a.conversation().equals(b.conversation());}
    private static Evidence evidence(String alias,Node n,String text){return new Evidence(alias,n.source(),n.receipt(),n.receiptHash(),n.message(),n.conversation(),n.actor(),n.god(),n.audience(),n.disclosure(),n.policy(),n.mode(),n.occurred(),text,text.length()<n.text().length(),n.text().length());}
    private static String validate(Work work,List<Candidate> candidates){
        if(candidates.isEmpty()||candidates.size()>3||candidates.stream().map(Candidate::layer).distinct().count()!=candidates.size()
                ||candidates.stream().noneMatch(c->c.layer()==Layer.EVENT))return "INVALID_PROJECTION_LAYERS";
        Map<String,Evidence> evidence=new HashMap<>();work.evidence().forEach(e->evidence.put(e.alias(),e));
        for(Candidate c:candidates){
            if(new HashSet<>(c.links()).size()!=c.links().size())return "DUPLICATE_PROJECTION_LINK";
            if(c.quotes().stream().noneMatch(q->q.sourceAlias().equals(work.targetAlias())))return "TARGET_QUOTE_REQUIRED";
            if(c.layer()!=Layer.EVENT&&(c.kind()!=ClaimKind.DIALOGUE_EPISODE||!c.links().isEmpty()))return "NON_EVENT_INTERPRETATION_SCOPE";
            for(Quote q:c.quotes()){Evidence e=evidence.get(q.sourceAlias());if(e==null||!e.text().contains(q.text()))return "UNGROUNDED_PROJECTION_QUOTE";}
            for(Link link:c.links()){
                Evidence newer=evidence.get(link.newerAlias()),older=evidence.get(link.olderAlias());
                if(newer==null||older==null||!link.newerAlias().equals(work.targetAlias())||older.occurredAt().isAfter(newer.occurredAt()))return "INVALID_PROJECTION_LINK";
                if(link.relation()!=Relation.CONTRADICTS&&!newer.actualActor().equals(older.actualActor()))return "CROSS_ACTOR_LINK_REJECTED";
                if(c.quotes().stream().noneMatch(q->q.sourceAlias().equals(link.olderAlias())))return "LINK_QUOTE_REQUIRED";
                if(Set.of(Relation.CORRECTS,Relation.CANCELS).contains(link.relation())&&c.kind()!=ClaimKind.CORRECTION_OR_EXPLANATION)return "CORRECTION_EVIDENCE_REQUIRED";
                if(link.relation()==Relation.REPORTS_FULFILLMENT&&c.kind()!=ClaimKind.SPEAKER_CLAIM)return "FULFILLMENT_IS_ONLY_A_CLAIM";
                if(link.relation()==Relation.ALSO_PLANNED&&c.kind()!=ClaimKind.INTENTION_OR_PROMISE)return "INTENTION_EVIDENCE_REQUIRED";
            }
        }
        return null;
    }
    private void persist(Connection db,Lease lease,Candidate candidate,long sequence)throws SQLException {
        UUID id=ProjectionInputManifest.memoryId(store.datasetId().orElseThrow(),lease.job(),lease.worker().version,candidate.layer());
        String payload=JSON.toJson(candidate),hash=sha256(payload);
        Node target=lease.sources().get(lease.work().targetAlias());
        update(db,"INSERT INTO memories VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,store.datasetId().orElseThrow(),lease.job(),target.god(),candidate.layer(),candidate.kind(),"CANDIDATE",target.disclosure(),lease.worker().version,payload,hash,sequence);
        // Every input may influence classification even when the output quotes only the target.
        // Dependencies are not a claim that a summary reproduced or fully covered these texts.
        Set<String> aliases=new TreeSet<>(lease.sources().keySet());
        for(String alias:aliases){Node n=lease.sources().get(alias);Evidence e=lease.work().evidence().stream().filter(v->v.alias().equals(alias)).findFirst().orElseThrow();
            update(db,"INSERT INTO memory_sources VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",id,n.receipt(),n.sourceKey(),n.source().hash(),n.receiptHash(),n.message(),alias,n.actor().kind(),n.actor().id(),n.occurred(),e.excerpt()?1:0,e.totalCharacters(),e.text().length());
            update(db,"INSERT OR IGNORE INTO memory_subjects VALUES(?,?,?)",id,n.actor().kind(),n.actor().id());}
        for(Link link:candidate.links())update(db,"INSERT INTO memory_links VALUES(?,?,?,?,?)",id,lease.sources().get(link.newerAlias()).receipt(),lease.sources().get(link.olderAlias()).receipt(),link.relation(),"CANDIDATE");
    }
    static void invalidateSources(Connection db,SourceRef source)throws SQLException {
        update(db,"UPDATE memories SET status='INVALIDATED' WHERE id IN (SELECT ms.memory_id FROM memory_sources ms JOIN source_refs s ON s.id=ms.source_ref WHERE s.dataset_id=? AND s.kind=? AND s.owner=? AND s.source_id=? AND s.source_revision=?)",
                source.datasetId(),source.kind(),source.owner(),source.sourceId(),source.revision());
        update(db,"UPDATE memory_links SET status='INVALIDATED' WHERE memory_id IN (SELECT ms.memory_id FROM memory_sources ms JOIN source_refs s ON s.id=ms.source_ref WHERE s.dataset_id=? AND s.kind=? AND s.owner=? AND s.source_id=? AND s.source_revision=?)",
                source.datasetId(),source.kind(),source.owner(),source.sourceId(),source.revision());
    }
    static void invalidateReceipt(Connection db,String receipt)throws SQLException {
        update(db,"UPDATE memories SET status='INVALIDATED' WHERE id IN (SELECT memory_id FROM memory_sources WHERE knowledge_receipt_id=?)",receipt);
        update(db,"UPDATE memory_links SET status='INVALIDATED' WHERE memory_id IN (SELECT memory_id FROM memory_sources WHERE knowledge_receipt_id=?)",receipt);
    }
    private static Set<String> strings(JsonArray array){var result=new HashSet<String>();array.forEach(e->result.add(e.getAsString()));return result;}
    private static int utf8(String text){return text.getBytes(StandardCharsets.UTF_8).length;}
    private static String prefix(String text,int max){int end=0,used=0;while(end<text.length()&&end<32768){int cp=text.codePointAt(end),n=cp<=127?1:cp<=2047?2:cp<=65535?3:4;if(used+n>max)break;used+=n;end+=Character.charCount(cp);}return text.substring(0,end);}
    private static PreparedStatement prepare(Connection db,String sql,Object...values)throws SQLException{var q=db.prepareStatement(sql);q.setQueryTimeout(1);for(int i=0;i<values.length;i++){Object v=values[i];if(v instanceof Number)q.setObject(i+1,v);else q.setString(i+1,v==null?null:v.toString());}return q;}
    private static int update(Connection db,String sql,Object...values)throws SQLException{try(var q=prepare(db,sql,values)){return q.executeUpdate();}}
    private static ClaimResult empty(Status status,String reason){return new ClaimResult(status,Optional.empty(),reason);}
    private static CompletionStage<ClaimResult> claim(Status status,String reason){return CompletableFuture.completedFuture(empty(status,reason));}
    private static CompletionStage<Result> result(Status status,String reason,int count){return CompletableFuture.completedFuture(new Result(status,reason,count));}
    private static Throwable cause(Throwable error){while(error.getCause()!=null&&(error instanceof CompletionException||error instanceof ExecutionException))error=error.getCause();return error;}
    private static Status failureStatus(Throwable error){return cause(error) instanceof StorageFailure s?s.status:Status.UNAVAILABLE;}
    private static String failureCode(Throwable error){return cause(error) instanceof StorageFailure s?s.code:"PROJECTION_STORAGE_UNAVAILABLE";}
}
