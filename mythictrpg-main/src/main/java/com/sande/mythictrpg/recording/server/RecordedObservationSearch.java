package com.sande.mythictrpg.recording.server;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.ai.experiencecontract.ExperienceRoomEvidence;
import com.sande.mythictrpg.ai.experiencecontract.ExperienceView;
import com.sande.mythictrpg.recording.api.MemoryReadSession.*;
import com.sande.mythictrpg.recording.api.ObservationReadRecords;
import com.sande.mythictrpg.recording.api.RecordingRecords;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Bounded archive candidate read. Every returned candidate still needs the existing live Watch proof. */
final class RecordedObservationSearch {
    /** A nonempty receipt cursor resumes within this source; empty means that source is fully scanned. */
    record Position(long beforeSequence, long beforeSource, String beforeReceipt) {
        static Position initial() { return new Position(Long.MAX_VALUE,Long.MAX_VALUE,""); }
        static Position end() { return new Position(0,0,""); }
    }
    record Candidate(ObservationReadRecords.Entry entry, List<RoomEvidenceReference> evidence) { }
    record Result(List<Candidate> candidates, Position position) { }
    private record Source(long key,long sequence,String kind,String id,long revision,String hash,boolean live,
                          String lineage,long origin,long stateCursor,String registeredLineage,long cutoff,long confirmed) { }
    private record Receipt(String id,String god,String acquisition,String projection,String audience,long policy,String hash,
                           long bytes,Long acquiredCursor,boolean live) { }
    private record Parsed(ObservationReadRecords.Entry entry, RoomEvidenceReference reference) { }
    private static final Gson JSON=new Gson();
    private static final int MAX_SCAN=128, MAX_PROJECTION=32768, MAX_READ_BYTES=262144;
    private static final Set<String> EVENT_FIELDS=Set.of("observationId","eventId","sourceRevision","acquisitionKind",
            "actionType","subjectType","outcome","gameTime");
    private final Connection db;
    private final UUID world;
    private final RecordedRoomSearch.Scope scope;
    private final long watermark,deadline;
    private int readBytes;
    private RecordedObservationSearch(Connection db,UUID world,RecordedRoomSearch.Scope scope,long watermark,long deadline) {
        this.db=db;this.world=world;this.scope=scope;this.watermark=watermark;this.deadline=deadline;
    }
    static boolean supported(RecordedRoomSearch.Scope scope) {
        return scope.memoryMode().equals("PERSONAL")&&!scope.publicRoom()&&scope.audience().size()==2
                &&scope.audience().contains(new ActorRef(ActorKind.GOD,scope.speaker()))
                &&scope.audience().stream().filter(a->a.kind()==ActorKind.PLAYER).count()==1;
    }
    static Result query(Connection db,UUID world,RecordedRoomSearch.Scope scope,Query query,Budget budget,long watermark,Position position) throws Exception {
        return query(db,world,scope,query,budget,watermark,position,System.nanoTime()+250_000_000L);
    }
    /** Deadline seam for bounded real-SQL tests; no game-thread I/O. */
    static Result query(Connection db,UUID world,RecordedRoomSearch.Scope scope,Query query,Budget budget,long watermark,Position position,long deadline) throws Exception {
        if(!supported(scope)||query.fromInclusive().isPresent()||query.untilExclusive().isPresent())
            return new Result(List.of(),Position.end());
        var reader=new RecordedObservationSearch(db,world,scope,watermark,deadline);
        try(var ignored=new SqlReadBudget(db,deadline)){return reader.search(query,budget,position);}
    }
    private Result search(Query query,Budget budget,Position initial)throws Exception {
        var selected=new ArrayList<Candidate>(); Position cursor=initial;int scanned=0,used=0;
        String term=query.text().strip().toLowerCase(Locale.ROOT);
        if(initial.beforeSequence()==0||expired())return new Result(List.of(),cursor);
        try {
            var sources=sources(initial);int sourceIndex=0;boolean exhaustedSources=true;
            for(var source:sources) {
                if(sourceIndex++>=MAX_SCAN||scanned>=MAX_SCAN||expired()){exhaustedSources=false;break;}
                String before=source.sequence()==initial.beforeSequence()&&source.key()==initial.beforeSource()
                        &&!initial.beforeReceipt().isEmpty()?initial.beforeReceipt():"~";
                if(!source.live()||!Set.of("ACTION_OBSERVED","ACTIVITY_OBSERVED").contains(source.kind())) {
                    scanned++;cursor=after(source);continue;
                }
                var receipts=receipts(source,before,MAX_SCAN-scanned+1);int receiptIndex=0;
                if(receipts.isEmpty()){scanned++;cursor=after(source);continue;}
                for(var receipt:receipts) {
                    if(scanned>=MAX_SCAN||expired()||selected.size()>=budget.rows())return result(selected,cursor);
                    // Preserve the last fully evaluated position if a request-wide budget expires.
                    if(receipt.bytes()>0&&receipt.bytes()<=MAX_PROJECTION+4096L&&readBytes+receipt.bytes()>MAX_READ_BYTES)
                        return result(selected,cursor);
                    scanned++;receiptIndex++;readBytes+=(int)Math.max(0,Math.min(receipt.bytes(),MAX_PROJECTION+4096L));
                    Position evaluated=new Position(source.sequence(),source.key(),receipt.id());
                    Optional<Parsed> parsed;
                    try {parsed=receipt.bytes()<=0||receipt.bytes()>MAX_PROJECTION+4096L||!receipt.live()
                            ?Optional.empty():parse(source,loadProjection(source,receipt));}
                    catch(RuntimeException malformed){parsed=Optional.empty();}
                    if(expired())return result(selected,cursor);
                    if(parsed.isPresent()) {
                        var value=parsed.orElseThrow();var event=value.entry().experience();
                        // Search only the already-disclosed experience, never IDs, fingerprints or hidden proof payloads.
                        String searchable=String.join("\n",event.actionType(),event.subjectType(),event.outcome(),event.gameTime()).toLowerCase(Locale.ROOT);
                        if(term.isEmpty()||searchable.contains(term)) {
                            int bytes=wireByteSize(value.entry());
                            if(bytes<=budget.utf8Bytes()) {
                                if(used+bytes>budget.utf8Bytes())return result(selected,cursor);
                                selected.add(new Candidate(value.entry(),List.of(value.reference())));used+=bytes;
                            }
                        }
                    }
                    cursor=evaluated;
                    if(receiptIndex==receipts.size())cursor=after(source);
                }
                if(scanned>=MAX_SCAN||selected.size()>=budget.rows())return result(selected,cursor);
            }
            if(exhaustedSources&&sources.size()<=MAX_SCAN)cursor=Position.end();
        }catch(SQLException failure){
            if(failure.getErrorCode()!=9||!expired())throw failure;
            // SQL interruption cannot permanently skip an unevaluated source/receipt.
        }
        return result(selected,cursor);
    }
    private static Result result(List<Candidate> selected,Position cursor){return new Result(List.copyOf(selected),cursor);}
    private static Position after(Source source){return new Position(source.sequence(),source.key(),"");}
    private boolean expired(){return System.nanoTime()>deadline;}

    private List<Source> sources(Position cursor)throws SQLException {
        String sql="WITH hits AS MATERIALIZED (SELECT * FROM source_refs WHERE owner='action-ledger-v1' AND dataset_id=?"
                +" AND ingest_sequence<=? AND (ingest_sequence<? OR (ingest_sequence=? AND (id<? OR (id=? AND ?=1))))"
                +" ORDER BY ingest_sequence DESC,id DESC LIMIT 129)"
                +" SELECT s.id,s.ingest_sequence,s.kind,s.source_id,s.source_revision,s.source_hash,"
                +" CASE WHEN s.revoked=0 AND NOT EXISTS(SELECT 1 FROM invalidations i WHERE i.dataset_id=s.dataset_id"
                +" AND i.kind=s.kind AND i.owner=s.owner AND i.source_id=s.source_id AND i.source_revision=s.source_revision)"
                +" AND NOT EXISTS(SELECT 1 FROM source_refs newer WHERE newer.dataset_id=s.dataset_id AND newer.kind=s.kind"
                +" AND newer.owner=s.owner AND newer.source_id=s.source_id AND newer.source_revision>s.source_revision) THEN 1 ELSE 0 END,"
                +" o.lineage_id,o.origin_cursor,o.state_cursor,c.lineage_id,c.cutoff,c.latest_confirmed"
                +" FROM hits s LEFT JOIN source_origins o ON o.source_ref=s.id"
                +" LEFT JOIN source_cutovers c ON c.dataset_id=s.dataset_id AND c.owner=s.owner ORDER BY s.ingest_sequence DESC,s.id DESC";
        var result=new ArrayList<Source>();
        try(var statement=db.prepareStatement(sql)) {
            statement.setQueryTimeout(1);statement.setString(1,scope.dataset().toString());statement.setLong(2,watermark);
            statement.setLong(3,cursor.beforeSequence());statement.setLong(4,cursor.beforeSequence());
            statement.setLong(5,cursor.beforeSource());statement.setLong(6,cursor.beforeSource());statement.setInt(7,cursor.beforeReceipt().isEmpty()?0:1);
            try(var rows=statement.executeQuery()){while(rows.next())result.add(new Source(rows.getLong(1),rows.getLong(2),rows.getString(3),
                    rows.getString(4),rows.getLong(5),rows.getString(6),rows.getInt(7)==1,rows.getString(8),rows.getLong(9),rows.getLong(10),
                    rows.getString(11),rows.getLong(12),rows.getLong(13)));}
        }
        return result;
    }
    private List<Receipt> receipts(Source source,String before,int limit)throws SQLException {
        String sql="SELECT k.id,k.god_id,k.acquisition,NULL,NULL,k.policy_revision,k.receipt_hash,"
                +" length(CAST(k.projection AS BLOB))+length(CAST(k.audience_json AS BLOB)),o.acquired_cursor,"
                +" CASE WHEN NOT EXISTS(SELECT 1 FROM knowledge_invalidations i WHERE i.dataset_id=k.dataset_id AND i.receipt_id=k.id) THEN 1 ELSE 0 END"
                +" FROM knowledge_receipts k LEFT JOIN knowledge_origins o ON o.receipt_id=k.id"
                +" WHERE k.dataset_id=? AND k.god_id=? AND k.source_ref=? AND k.id<? ORDER BY k.id DESC LIMIT ?";
        var result=new ArrayList<Receipt>();
        try(var statement=db.prepareStatement(sql)) {
            statement.setQueryTimeout(1);statement.setString(1,scope.dataset().toString());statement.setString(2,scope.speaker());
            statement.setLong(3,source.key());statement.setString(4,before);statement.setInt(5,limit);
            try(var rows=statement.executeQuery()){while(rows.next()) {
                long acquisition=rows.getLong(9);Long acquired=rows.wasNull()?null:acquisition;
                result.add(new Receipt(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5),
                        rows.getLong(6),rows.getString(7),rows.getLong(8),acquired,rows.getInt(10)==1));
            }}
        }
        return result;
    }
    /** Load the bounded payload only after the request's cumulative byte admission, in this same snapshot. */
    private Receipt loadProjection(Source source,Receipt receipt)throws SQLException {
        try(var statement=db.prepareStatement("SELECT projection,audience_json FROM knowledge_receipts WHERE id=? AND dataset_id=?"
                +" AND source_ref=? AND god_id=? AND receipt_hash=? AND length(CAST(projection AS BLOB))<=32768"
                +" AND length(CAST(audience_json AS BLOB))<=4096")) {
            statement.setQueryTimeout(1);statement.setString(1,receipt.id());statement.setString(2,scope.dataset().toString());
            statement.setLong(3,source.key());statement.setString(4,scope.speaker());statement.setString(5,receipt.hash());
            try(var row=statement.executeQuery()) {
                if(!row.next())return receipt;
                return new Receipt(receipt.id(),receipt.god(),receipt.acquisition(),row.getString(1),row.getString(2),
                        receipt.policy(),receipt.hash(),receipt.bytes(),receipt.acquiredCursor(),receipt.live());
            }
        }
    }
    private Optional<Parsed> parse(Source source,Receipt receipt)throws SQLException {
        if(!receipt.live()||!receipt.acquisition().equals("DIRECT_WATCH")||receipt.projection()==null||receipt.audience()==null
                ||receipt.policy()<0||!scope.speaker().equals(receipt.god()))return Optional.empty();
        UUID receiptId=canonicalUuid(receipt.id());
        var audience=new HashSet<ActorRef>();
        for(var actor:JsonParser.parseString(receipt.audience()).getAsJsonArray()) {
            String key=actor.getAsString();int separator=key.indexOf(':');
            audience.add(new ActorRef(ActorKind.valueOf(key.substring(0,separator)),key.substring(separator+1)));
        }
        var keys=audience.stream().map(ActorRef::key).sorted().toList();
        if(!audience.equals(scope.audience())||!JSON.toJson(keys).equals(receipt.audience()))return Optional.empty();
        String hash=RecordingRecords.sha256(JSON.toJson(List.of(receiptId,receipt.god(),receipt.acquisition(),receipt.projection(),keys,receipt.policy())));
        if(!hash.equals(receipt.hash())||!acquiredBeforeWatermark(source,receipt))return Optional.empty();
        var projection=JsonParser.parseString(receipt.projection()).getAsJsonObject();
        if(!projection.keySet().equals(Set.of("memoryMode","experience","evidence"))
                ||!projection.get("memoryMode").getAsString().equals("PERSONAL"))return Optional.empty();
        var rawEvent=projection.getAsJsonObject("experience");
        if(!rawEvent.keySet().equals(EVENT_FIELDS))return Optional.empty();
        var event=JSON.fromJson(rawEvent,ExperienceView.Event.class);
        if(!JSON.toJsonTree(event).equals(rawEvent))return Optional.empty();
        var evidence=projection.getAsJsonObject("evidence");
        if(!evidence.keySet().equals(Set.of("kind","payload")))return Optional.empty();
        var reference=new RoomEvidenceReference(evidence.get("kind").getAsString(),evidence.get("payload").getAsString());
        if(!reference.kind().equals(ExperienceRoomEvidence.KIND)||reference.payload().length()>4096)return Optional.empty();
        var descriptor=JsonParser.parseString(reference.payload()).getAsJsonObject();
        if(!descriptor.keySet().equals(Set.of("worldId","godId","subjectId","observationId","sha256")))return Optional.empty();
        UUID subject=canonicalUuid(descriptor.get("subjectId").getAsString());
        if(!canonicalUuid(descriptor.get("worldId").getAsString()).equals(world)
                ||!descriptor.get("godId").getAsString().equals(receipt.god())
                ||!canonicalUuid(descriptor.get("observationId").getAsString()).equals(event.observationId())
                ||!descriptor.get("sha256").getAsString().matches("[0-9a-f]{64}")
                ||!audience.equals(Set.of(new ActorRef(ActorKind.GOD,receipt.god()),new ActorRef(ActorKind.PLAYER,subject.toString()))))return Optional.empty();
        var portable=new LinkedHashMap<String,Object>();portable.put("worldId",world.toString());portable.put("godId",receipt.god());
        portable.put("subjectId",subject.toString());portable.put("observationId",event.observationId().toString());
        portable.put("sha256",descriptor.get("sha256").getAsString());
        if(!JSON.toJson(portable).equals(reference.payload()))return Optional.empty();
        // Reject extra/coerced/duplicate fields and retain the exact canonical capture format.
        String canonical=JSON.toJson(new TreeMap<>(Map.of("memoryMode","PERSONAL","experience",event,"evidence",reference)));
        if(!canonical.equals(receipt.projection()))return Optional.empty();
        var ref=new SourceRef(world,scope.dataset(),SourceKind.valueOf(source.kind()),WatchRecordingCapture.PRODUCER,
                canonicalUuid(source.id()).toString(),source.revision(),source.hash());
        return Optional.of(new Parsed(new ObservationReadRecords.Entry(ref,receiptId,receipt.god(),subject,event),reference));
    }
    private boolean acquiredBeforeWatermark(Source source,Receipt receipt)throws SQLException {
        String kind,version;
        if(receipt.acquiredCursor()==null) {
            if(source.lineage()!=null)return false;
            kind="SOURCE_CAPTURED";version=source.hash()+":"+source.key();
        }else {
            if(source.lineage()==null||!source.lineage().equals(source.registeredLineage())||source.origin()<=source.cutoff()
                    ||source.stateCursor()<source.origin()||receipt.acquiredCursor()<source.stateCursor()
                    ||source.confirmed()<receipt.acquiredCursor())return false;
            canonicalUuid(source.lineage());
            kind="KNOWLEDGE_ACQUIRED";version=receipt.id()+":"+receipt.hash();
        }
        try(var statement=db.prepareStatement("SELECT created_sequence FROM work_items WHERE dataset_id=? AND source_ref=? AND kind=?"
                +" AND source_version=? AND state<>'INVALIDATED' AND created_sequence<=?")) {
            statement.setQueryTimeout(1);statement.setString(1,scope.dataset().toString());statement.setLong(2,source.key());
            statement.setString(3,kind);statement.setString(4,version);statement.setLong(5,watermark);
            try(var row=statement.executeQuery()) {
                return row.next()&&(receipt.acquiredCursor()!=null||row.getLong(1)==source.sequence())&&!row.next();
            }
        }
    }
    private static UUID canonicalUuid(String value){UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException("NONCANONICAL_UUID");return id;}
    static int wireByteSize(ObservationReadRecords.Entry entry) {
        var source=entry.source();var event=entry.experience();
        var wire=new LinkedHashMap<String,Object>();
        wire.put("source",Map.of("worldId",source.worldId().toString(),"datasetId",source.datasetId().toString(),"kind",source.kind().name(),
                "owner",source.owner(),"sourceId",source.sourceId(),"revision",source.revision(),"hash",source.hash()));
        wire.put("knowledgeReceiptId",entry.knowledgeReceiptId().toString());wire.put("observerGodId",entry.observerGodId());wire.put("subjectPlayerId",entry.subjectPlayerId().toString());
        wire.put("experience",Map.of("observationId",event.observationId().toString(),"eventId",event.eventId().toString(),"sourceRevision",event.sourceRevision(),
                "acquisitionKind",event.acquisitionKind(),"actionType",event.actionType(),"subjectType",event.subjectType(),"outcome",event.outcome(),"gameTime",event.gameTime()));
        return JSON.toJson(wire).getBytes(StandardCharsets.UTF_8).length;
    }
}
