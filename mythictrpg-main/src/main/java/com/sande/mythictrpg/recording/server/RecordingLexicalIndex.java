package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.recording.api.RecordingRecords;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional contentless candidate index. Membership is append-only and NEVER establishes God knowledge. */
public final class RecordingLexicalIndex {
    public static final String NORMALIZATION_VERSION="java-root-lower-trigram-v1";
    public static final int MAX_MESSAGES=16,MAX_SOURCE_BYTES=1_048_576,MAX_BATCH_BYTES=1_048_576;
    private static final String RETRY_CONSUMER="lexical-time-budget-v1";
    private static final int MAX_TIMEOUT_ATTEMPTS=3;
    public record BatchResult(String state,String reasonCode,int indexed,int skipped,long throughMessageSequence,boolean caughtUp){ }
    public record IndexStatus(String state,String reasonCode,long throughMessageSequence,long indexedMessages,long skippedMessages,boolean caughtUp,boolean active){ }
    record Progress(long cursor,long indexed,long skipped){ }
    record Message(long sequence,String id,String hash,long bytes){ }
    record Plan(Progress progress,List<Message> messages,long rawBytes){Plan{messages=List.copyOf(messages);}}
    record Applied(BatchResult result,Progress progress,boolean changed){ }
    static final class IndexFailure extends RuntimeException {
        final String state,code;
        IndexFailure(String state,String code){super(code);this.state=state;this.code=code;}
    }
    private final WorldRecordingService store;
    private final AtomicBoolean active=new AtomicBoolean();
    private volatile IndexStatus status=new IndexStatus("OFF","INDEX_NOT_PUMPED",0,0,0,false,false);
    RecordingLexicalIndex(WorldRecordingService store){this.store=store;}
    /** Shared by writer and query builder; SQLite lower() does not perform this Unicode folding. */
    public static String normalize(String text){return Objects.requireNonNull(text).toLowerCase(Locale.ROOT);}
    IndexStatus status(){var current=status;return new IndexStatus(current.state(),current.reasonCode(),current.throughMessageSequence(),current.indexedMessages(),current.skippedMessages(),current.caughtUp(),active.get());}
    CompletableFuture<BatchResult> pump(){
        if(!store.lexicalMaintenanceEnabled())return immediate("OFF","LEXICAL_REQUIRES_SHADOW");
        if(!store.projectionAdmission())return immediate("DEFERRED","BACKGROUND_ADMISSION_DEFERRED");
        if(!active.compareAndSet(false,true))return CompletableFuture.completedFuture(new BatchResult("BUSY","LEXICAL_PUMP_IN_PROGRESS",0,0,status.throughMessageSequence(),status.caughtUp()));
        final CompletableFuture<Applied> operation;
        try{operation=store.lexicalPlan().thenCompose(plan->store.applyLexicalPlan(plan).exceptionallyCompose(failure->{
            Throwable cause=cause(failure);
            return cause instanceof IndexFailure index&&index.code.equals("LEXICAL_TIME_BUDGET")&&!plan.messages().isEmpty()
                    ?store.recordLexicalTimeout(plan):CompletableFuture.failedFuture(failure);
        }));}
        catch(RuntimeException failure){active.set(false);return immediate("UNAVAILABLE","LEXICAL_SCHEDULING_FAILED");}
        var internal=operation.handle((applied,failure)->{
            try{
                if(failure!=null){Throwable cause=cause(failure);
                    String state=cause instanceof IndexFailure index?index.state:"UNAVAILABLE";
                    String code=cause instanceof IndexFailure index?index.code:"LEXICAL_READ_UNAVAILABLE";
                    if(code.equals("LEXICAL_TIME_BUDGET")||code.equals("LEXICAL_DATABASE_BUSY")){state="DEFERRED";}
                    return updateStatus(state,code,0,0,status.throughMessageSequence(),status.indexedMessages(),status.skippedMessages(),false);
                }
                var result=applied.result();var progress=applied.progress();
                return updateStatus(result.state(),result.reasonCode(),result.indexed(),result.skipped(),progress.cursor(),progress.indexed(),progress.skipped(),result.caughtUp());
            }finally{active.set(false);}
        });
        // A caller may stop waiting, but must not cancel the internal cleanup stage and permanently
        // leave this store BUSY. Cancellation never cancels an already admitted SQLite transaction.
        var exposed=new CompletableFuture<BatchResult>();
        internal.whenComplete((value,failure)->{if(failure==null)exposed.complete(value);else exposed.completeExceptionally(failure);});
        return exposed;
    }
    private CompletableFuture<BatchResult> immediate(String state,String code){
        var current=status;return CompletableFuture.completedFuture(updateStatus(state,code,0,0,current.throughMessageSequence(),current.indexedMessages(),current.skippedMessages(),false));
    }
    private BatchResult updateStatus(String state,String code,int indexed,int skipped,long cursor,long totalIndexed,long totalSkipped,boolean caughtUp){
        status=new IndexStatus(state,code,cursor,totalIndexed,totalSkipped,caughtUp,false);return new BatchResult(state,code,indexed,skipped,cursor,caughtUp);
    }
    static Plan plan(Connection db,UUID dataset)throws SQLException {
        Progress progress=progress(db,dataset);var messages=new ArrayList<Message>();long bytes=0;
        int limit=timeouts(db,dataset,progress.cursor())>0?1:MAX_MESSAGES;
        try(var q=prepare(db,"SELECT ingest_sequence,id,body_hash,body_bytes FROM messages WHERE dataset_id=? AND ingest_sequence>? ORDER BY ingest_sequence LIMIT 16",dataset,progress.cursor());var r=q.executeQuery()){
            while(r.next()){
                var message=new Message(r.getLong(1),r.getString(2),r.getString(3),r.getLong(4));
                if(message.bytes()<0)throw new IndexFailure("UNAVAILABLE","INVALID_MESSAGE_SIZE");
                long required=message.bytes()<=MAX_SOURCE_BYTES?message.bytes():0;
                if(bytes+required>MAX_BATCH_BYTES)break;
                messages.add(message);bytes+=required;
                if(messages.size()>=limit)break;
            }
        }
        return new Plan(progress,messages,bytes);
    }
    static Applied apply(Connection db,UUID dataset,Plan plan,long sequence)throws SQLException {
        Progress previous=progress(db,dataset);
        if(!previous.equals(plan.progress()))throw new IndexFailure("DEFERRED","LEXICAL_CURSOR_CHANGED");
        long cursor=previous.cursor();int indexed=0,skipped=0;boolean changed=false;
        long deadline=System.nanoTime()+200_000_000L;
        for(Message message:plan.messages()){
            if(System.nanoTime()>deadline)break;
            if(message.sequence()<=cursor||message.sequence()>=sequence)throw new IndexFailure("UNAVAILABLE","INVALID_INDEX_SNAPSHOT");
            // Cursor, immutable RAW identity and manifest are checked together in the writer transaction.
            try(var q=prepare(db,"SELECT id,body_hash,body_bytes FROM messages WHERE dataset_id=? AND ingest_sequence=?",dataset,message.sequence());var r=q.executeQuery()){
                if(!r.next()||!message.id().equals(r.getString(1))||!message.hash().equals(r.getString(2))||message.bytes()!=r.getLong(3))throw new IndexFailure("UNAVAILABLE","INDEX_SOURCE_CHANGED");
            }
            if(message.bytes()>MAX_SOURCE_BYTES){
                try(var q=prepare(db,"SELECT body_hash FROM recording_lexical_skips WHERE message_sequence=?",message.sequence());var r=q.executeQuery()){
                    if(r.next()){if(!message.hash().equals(r.getString(1)))throw new IndexFailure("UNAVAILABLE","INDEX_SKIP_CHANGED");}
                    else{update(db,"INSERT INTO recording_lexical_skips VALUES(?,?,?,?,?)",message.sequence(),dataset,message.id(),message.hash(),"SOURCE_TOO_LARGE");skipped++;}
                }
                cursor=message.sequence();changed=true;continue;
            }
            boolean existing=false;
            try(var q=prepare(db,"SELECT dataset_id,message_id,body_hash,normalization_version FROM recording_lexical_manifest WHERE message_sequence=?",message.sequence());var r=q.executeQuery()){
                if(r.next()){
                    if(!dataset.toString().equals(r.getString(1))||!message.id().equals(r.getString(2))||!message.hash().equals(r.getString(3))||!NORMALIZATION_VERSION.equals(r.getString(4)))
                        throw new IndexFailure("UNAVAILABLE","INDEX_MANIFEST_CHANGED");
                    existing=true;
                }
            }
            if(!existing){
                var body=new StringBuilder();int index=0;
                try(var q=prepare(db,"SELECT part_index,body FROM message_parts WHERE message_id=? ORDER BY part_index",message.id());var r=q.executeQuery()){
                    while(r.next()){
                        if(System.nanoTime()>deadline)throw new IndexFailure("DEFERRED","LEXICAL_TIME_BUDGET");
                        if(r.getInt(1)!=index++)throw new IndexFailure("UNAVAILABLE","INDEX_SOURCE_INCOMPLETE");
                        body.append(r.getString(2));if(body.length()>message.bytes())throw new IndexFailure("UNAVAILABLE","INDEX_SOURCE_SIZE_MISMATCH");
                    }
                }
                String text=body.toString();
                if(text.getBytes(StandardCharsets.UTF_8).length!=message.bytes()||!RecordingRecords.sha256(text).equals(message.hash()))
                    throw new IndexFailure("UNAVAILABLE","INDEX_SOURCE_HASH_MISMATCH");
                update(db,"INSERT INTO recording_lexical_fts(rowid,body) VALUES(?,?)",message.sequence(),normalize(text));
                update(db,"INSERT INTO recording_lexical_manifest VALUES(?,?,?,?,?,?)",message.sequence(),dataset,message.id(),message.hash(),NORMALIZATION_VERSION,sequence);
                indexed++;
            }
            cursor=message.sequence();changed=true;
        }
        Progress progress=new Progress(cursor,previous.indexed()+indexed,previous.skipped()+skipped);
        if(changed)update(db,"INSERT INTO recording_lexical_progress VALUES(?,?,?,?,?,?) ON CONFLICT(dataset_id) DO UPDATE SET after_message_sequence=excluded.after_message_sequence,indexed_messages=excluded.indexed_messages,skipped_messages=excluded.skipped_messages,reason_code=excluded.reason_code",
                dataset,NORMALIZATION_VERSION,progress.cursor(),progress.indexed(),progress.skipped(),progress.skipped()>0?"PARTIAL_SOURCE_COVERAGE":"INDEX_COMMITTED");
        if(changed)update(db,"DELETE FROM consumer_cursors WHERE dataset_id=? AND consumer=?",dataset,RETRY_CONSUMER);
        boolean more;
        try(var q=prepare(db,"SELECT 1 FROM messages WHERE dataset_id=? AND ingest_sequence>? LIMIT 1",dataset,cursor);var r=q.executeQuery()){more=r.next();}
        String state=progress.skipped()>0?"PARTIAL":"READY";
        String reason=progress.skipped()>0?"PARTIAL_SOURCE_COVERAGE":more?"BOUNDED_BATCH_COMMITTED":"INDEX_CAUGHT_UP";
        return new Applied(new BatchResult(state,reason,indexed,skipped,cursor,!more),progress,changed);
    }
    /** Failed SQL work was rolled back first. Record only bounded timeout bookkeeping in a fresh transaction. */
    static Applied recordTimeout(Connection db,UUID dataset,Plan plan,long sequence)throws SQLException {
        Progress previous=progress(db,dataset);
        if(previous.cursor()!=plan.progress().cursor()||plan.messages().isEmpty())
            return new Applied(new BatchResult("DEFERRED","LEXICAL_CURSOR_CHANGED",0,0,previous.cursor(),false),previous,false);
        int attempts=timeouts(db,dataset,previous.cursor())+1;
        if(attempts<MAX_TIMEOUT_ATTEMPTS||plan.messages().size()!=1){
            update(db,"INSERT INTO consumer_cursors VALUES(?,?,?,?) ON CONFLICT DO UPDATE SET cursor=excluded.cursor",dataset,RETRY_CONSUMER,"after_sequence",previous.cursor());
            update(db,"INSERT INTO consumer_cursors VALUES(?,?,?,?) ON CONFLICT DO UPDATE SET cursor=excluded.cursor",dataset,RETRY_CONSUMER,"attempts",attempts);
            return new Applied(new BatchResult("DEFERRED","LEXICAL_SINGLE_SOURCE_RETRY",0,0,previous.cursor(),false),previous,true);
        }
        Message message=plan.messages().getFirst();
        if(message.sequence()<=previous.cursor()||message.sequence()>=sequence)throw new IndexFailure("UNAVAILABLE","INVALID_INDEX_SNAPSHOT");
        try(var q=prepare(db,"SELECT body_hash FROM messages WHERE dataset_id=? AND ingest_sequence=? AND id=?",dataset,message.sequence(),message.id());var r=q.executeQuery()){
            if(!r.next()||!message.hash().equals(r.getString(1)))throw new IndexFailure("UNAVAILABLE","INDEX_SOURCE_CHANGED");
        }
        // Never pretend the skipped source is indexed. It stays in the unindexed/raw query partition.
        update(db,"INSERT INTO recording_lexical_skips VALUES(?,?,?,?,?)",message.sequence(),dataset,message.id(),message.hash(),"INDEX_TIME_BUDGET_EXCEEDED");
        Progress progress=new Progress(message.sequence(),previous.indexed(),previous.skipped()+1);
        update(db,"INSERT INTO recording_lexical_progress VALUES(?,?,?,?,?,?) ON CONFLICT(dataset_id) DO UPDATE SET after_message_sequence=excluded.after_message_sequence,indexed_messages=excluded.indexed_messages,skipped_messages=excluded.skipped_messages,reason_code=excluded.reason_code",
                dataset,NORMALIZATION_VERSION,progress.cursor(),progress.indexed(),progress.skipped(),"PARTIAL_SOURCE_COVERAGE");
        update(db,"DELETE FROM consumer_cursors WHERE dataset_id=? AND consumer=?",dataset,RETRY_CONSUMER);
        boolean more;try(var q=prepare(db,"SELECT 1 FROM messages WHERE dataset_id=? AND ingest_sequence>? LIMIT 1",dataset,progress.cursor());var r=q.executeQuery()){more=r.next();}
        return new Applied(new BatchResult("PARTIAL","INDEX_TIME_BUDGET_EXCEEDED",0,1,progress.cursor(),!more),progress,true);
    }
    private static int timeouts(Connection db,UUID dataset,long after)throws SQLException {
        long recordedAfter=-1,attempts=0;
        try(var q=prepare(db,"SELECT stream,cursor FROM consumer_cursors WHERE dataset_id=? AND consumer=?",dataset,RETRY_CONSUMER);var r=q.executeQuery()){
            while(r.next()){if(r.getString(1).equals("after_sequence"))recordedAfter=r.getLong(2);else if(r.getString(1).equals("attempts"))attempts=r.getLong(2);}
        }
        return recordedAfter==after?(int)Math.min(MAX_TIMEOUT_ATTEMPTS,Math.max(0,attempts)):0;
    }
    private static Throwable cause(Throwable failure){while(failure.getCause()!=null&&(failure instanceof CompletionException||failure instanceof ExecutionException))failure=failure.getCause();return failure;}
    private static Progress progress(Connection db,UUID dataset)throws SQLException {
        try(var q=prepare(db,"SELECT normalization_version,after_message_sequence,indexed_messages,skipped_messages FROM recording_lexical_progress WHERE dataset_id=?",dataset);var r=q.executeQuery()){
            if(!r.next())return new Progress(0,0,0);
            if(!NORMALIZATION_VERSION.equals(r.getString(1)))throw new IndexFailure("UNAVAILABLE","UNSUPPORTED_INDEX_NORMALIZATION");
            return new Progress(r.getLong(2),r.getLong(3),r.getLong(4));
        }
    }
    private static PreparedStatement prepare(Connection db,String sql,Object...values)throws SQLException{
        var q=db.prepareStatement(sql);q.setQueryTimeout(1);for(int i=0;i<values.length;i++){Object v=values[i];if(v instanceof Number)q.setObject(i+1,v);else q.setString(i+1,v==null?null:v.toString());}return q;
    }
    private static void update(Connection db,String sql,Object...values)throws SQLException{try(var q=prepare(db,sql,values)){q.executeUpdate();}}
}
