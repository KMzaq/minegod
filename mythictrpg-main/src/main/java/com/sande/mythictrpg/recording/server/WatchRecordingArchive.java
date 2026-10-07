package com.sande.mythictrpg.recording.server;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.recording.api.ProducerCapability;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Package-private game repair access, never an NPC search or model-visible archive browser. */
final class WatchRecordingArchive implements WatchKnowledgeReconciler.ArchiveAccess {
    private final WorldRecordingService store;
    private final ProducerCapability producer;
    WatchRecordingArchive(WorldRecordingService store, ProducerCapability producer) { this.store=store; this.producer=producer; }
    public UUID worldId() { return store.worldId(); }
    public UUID datasetId() { return store.datasetId().orElseThrow(); }
    public long committedWatermark() { return store.watchKnowledgeWatermark(); }
    public CompletableFuture<Optional<WatchKnowledgeReconciler.Checkpoint>> loadCheckpoint() { return store.watchCheckpoint(producer); }
    public CompletableFuture<WatchKnowledgeReconciler.Page> page(long watermark,String after,int limit) {
        return store.watchKnowledgePage(producer,watermark,after,limit);
    }
    public CompletableFuture<WriteReceipt> invalidate(KnowledgeInvalidation invalidation) {
        return store.invalidateKnowledge(producer,invalidation).toCompletableFuture();
    }
    public CompletableFuture<Boolean> checkpoint(WatchKnowledgeReconciler.Checkpoint checkpoint) {
        return store.checkpointWatch(producer,checkpoint);
    }
    static WatchKnowledgeReconciler.Page readPage(Connection db,UUID world,UUID dataset,long watermark,String after,int limit) throws Exception {
        if (limit<1||limit>WatchKnowledgeReconciler.PAGE_SIZE||watermark<0||!after.isEmpty()&&!UUID.fromString(after).toString().equals(after))
            throw new IllegalArgumentException("WATCH_PAGE_BUDGET");
        db.setAutoCommit(false);
        try(var budget=new SqlReadBudget(db,System.nanoTime()+250_000_000L);
                var query=db.prepareStatement("SELECT s.kind,s.source_id,s.source_revision,s.source_hash,k.id,k.god_id,k.acquisition,"
                        +"CASE WHEN length(k.projection)<=32768 THEN k.projection END,k.audience_json,k.policy_revision"
                        +" FROM source_refs s JOIN knowledge_receipts k ON k.source_ref=s.id"
                        +" WHERE s.dataset_id=? AND s.owner='action-ledger-v1' AND s.kind IN ('ACTION_OBSERVED','ACTIVITY_OBSERVED')"
                        +" AND s.ingest_sequence<=? AND s.revoked=0 AND k.acquisition='DIRECT_WATCH' AND k.id>?"
                        +" AND NOT EXISTS(SELECT 1 FROM knowledge_invalidations i WHERE i.dataset_id=k.dataset_id AND i.receipt_id=k.id)"
                        +" ORDER BY k.id LIMIT ?")) {
            query.setString(1,dataset.toString());query.setLong(2,watermark);query.setString(3,after);query.setInt(4,limit+1);
            var entries=new ArrayList<WatchKnowledgeReconciler.Entry>();boolean more=false;
            try(var rows=query.executeQuery()) { while(rows.next()) {
                if(entries.size()==limit){more=true;break;}
                String projection=rows.getString(8),audienceJson=rows.getString(9);
                if(projection==null||audienceJson.length()>65536)throw new SQLException("WATCH_PROJECTION_BUDGET");
                var audience=new HashSet<ActorRef>();
                for(var actor:JsonParser.parseString(audienceJson).getAsJsonArray()) {
                    String key=actor.getAsString();int split=key.indexOf(':');
                    audience.add(new ActorRef(ActorKind.valueOf(key.substring(0,split)),key.substring(split+1)));
                }
                var source=new SourceRef(world,dataset,SourceKind.valueOf(rows.getString(1)),WatchRecordingCapture.PRODUCER,
                        rows.getString(2),rows.getLong(3),rows.getString(4));
                var knowledge=new KnowledgeReceipt(UUID.fromString(rows.getString(5)),rows.getString(6),rows.getString(7),projection,audience,rows.getLong(10));
                entries.add(new WatchKnowledgeReconciler.Entry(source,knowledge));
            } }
            return new WatchKnowledgeReconciler.Page(entries,more?entries.getLast().knowledge().receiptId().toString():"");
        } finally { db.rollback(); }
    }
    static Optional<WatchKnowledgeReconciler.Checkpoint> readCheckpoint(Connection db,UUID dataset) throws SQLException {
        Long watch=null,archive=null;
        try(var query=db.prepareStatement("SELECT stream,cursor FROM consumer_cursors WHERE dataset_id=? AND consumer='watch-reconcile-v1'")) {
            query.setString(1,dataset.toString());
            try(var rows=query.executeQuery()) {while(rows.next()) {
                switch(rows.getString(1)){case "watch-journal"->watch=rows.getLong(2);case "archive-watermark"->archive=rows.getLong(2);default->throw new SQLException("UNKNOWN_WATCH_CHECKPOINT");}
            }}
        }
        if(watch==null&&archive==null)return Optional.empty();
        if(watch==null||archive==null)throw new SQLException("INCOMPLETE_WATCH_CHECKPOINT");
        return Optional.of(new WatchKnowledgeReconciler.Checkpoint(watch,archive));
    }
}
