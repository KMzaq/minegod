package com.sande.mythictrpg.recording.server;

import com.google.gson.Gson;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Independent bounded native-vector lane. Similarity never grants knowledge or gameplay authority. */
final class RecordedSemanticSearch {
    private static final Gson JSON=new Gson();
    record Position(long beforeSequence,String beforeId) {
        Position {if(beforeSequence<0)throw new IllegalArgumentException("SEMANTIC_POSITION");Objects.requireNonNull(beforeId);}
        static Position initial(){return new Position(Long.MAX_VALUE,"~");}
    }
    record Result(List<SemanticReadRecords.Entry> entries,Position position) { }
    private record Metadata(String id,long sequence,UUID message,long sourceKey,String sourceHash,UUID receipt,String receiptHash,
                            ActorRef actor,String disclosure,String inputHash,int covered,int total,EmbeddingRecords.ModelSpace space,
                            Instant occurred,boolean eligible) { }
    private final Connection db;
    private final RecordedRoomSearch.Scope scope;
    private final long watermark,deadline;
    private final RecordedRoomSearch raw;
    private final RecordedInterpretationSearch bindings;
    private RecordedSemanticSearch(Connection db,RecordedRoomSearch.Scope scope,long watermark,long deadline){
        this.db=db;this.scope=scope;this.watermark=watermark;this.deadline=deadline;
        raw=new RecordedRoomSearch(db,scope,watermark,deadline);bindings=new RecordedInterpretationSearch(db,scope,watermark,deadline);
    }
    static Result query(Connection db,RecordedRoomSearch.Scope scope,MemoryReadSession.Query query,EmbeddingRecords.QueryVector vector,
                        MemoryReadSession.Budget budget,long watermark,Position position)throws Exception {
        if(query.text().isBlank()||query.text().length()>1600||!RecordingRecords.sha256(query.text()).equals(vector.inputHash()))
            throw new IllegalArgumentException("SEMANTIC_QUERY_BINDING");
        var reader=new RecordedSemanticSearch(db,scope,watermark,System.nanoTime()+250_000_000L);
        try(var ignored=new SqlReadBudget(db,reader.deadline)){return reader.search(query,vector,budget,position);}
    }
    private Result search(MemoryReadSession.Query query,EmbeddingRecords.QueryVector vector,MemoryReadSession.Budget budget,Position start)throws Exception {
        var matches=new ArrayList<SemanticReadRecords.Entry>();Position next=start;List<Metadata> window;
        try {window=metadata(vector.modelSpace(),start);}catch(SQLException exhausted){if(exhausted.getErrorCode()==9&&expired())return new Result(List.of(),start);throw exhausted;}
        float[] needle=vector.values();int scanned=0;
        for(var candidate:window){
            if(scanned++>=128||expired())break;
            var after=new Position(candidate.sequence(),candidate.id());
            if(!candidate.eligible()||!candidate.space().equals(vector.modelSpace())||!query.actorSelection().matches(candidate.actor())
                    ||query.fromInclusive().filter(t->candidate.occurred().isBefore(t)).isPresent()
                    ||query.untilExclusive().filter(t->!candidate.occurred().isBefore(t)).isPresent()){next=after;continue;}
            try {
                var source=raw.nativeSource(candidate.message());if(source.isEmpty()){next=after;continue;}
                var node=source.orElseThrow();var binding=bindings.binding(node);
                if(binding==null||binding.sourceKey()!=candidate.sourceKey()||!binding.sourceHash().equals(candidate.sourceHash())
                        ||!binding.receipt().equals(candidate.receipt())||!binding.receiptHash().equals(candidate.receiptHash())
                        ||!binding.disclosure().equals(candidate.disclosure())||!node.speaker().equals(candidate.actor())||!query.actorSelection().matches(node.speaker())
                        ||!node.occurred().equals(candidate.occurred())||node.body().length()!=candidate.total()){next=after;continue;}
                String prefix=EmbeddingRecords.prefix(node.body());
                if(prefix.isBlank()||prefix.length()!=candidate.covered()||!RecordingRecords.sha256(prefix).equals(candidate.inputHash())){next=after;continue;}
                // Blob loading and cosine happen ONLY AFTER current source/receipt/audience/native ancestry checks.
                var values=storedVector(candidate);if(values.isEmpty()){next=after;continue;}
                double score=cosine(needle,values.orElseThrow());
                if(expired())break; // Current candidate must be retried with a fresh request budget.
                matches.add(new SemanticReadRecords.Entry(node.id(),node.speaker(),node.occurred(),prefix,score,prefix.length(),node.body().length()));
                next=after;
            }catch(RecordedRoomSearch.RequestLimit exhausted){break;}
            catch(RecordedRoomSearch.SourceLimit unsupported){next=after;}
            catch(SQLException failure){if(failure.getErrorCode()==9&&expired())break;throw failure;}
            catch(RuntimeException malformed){next=after;}
        }
        // A request-wide byte/node limit can stop on the LAST candidate without a clock timeout.
        // Only a cursor that actually consumed that final row may declare this window exhausted.
        if(!expired()&&(window.isEmpty()||window.size()<129
                &&next.equals(new Position(window.getLast().sequence(),window.getLast().id()))))next=new Position(0,"");
        // Top-k WITHIN this bounded chronological window, not a global nearest-neighbour claim.
        matches.sort(Comparator.comparingDouble(SemanticReadRecords.Entry::similarity).reversed()
                .thenComparing(e->e.messageId().toString()));
        var selected=new ArrayList<SemanticReadRecords.Entry>();int used=0;
        for(var entry:matches){int size=wireByteSize(entry);if(selected.size()>=budget.rows())break;
            if(size>budget.utf8Bytes()-used)continue;used+=size;selected.add(entry);}
        return new Result(List.copyOf(selected),next);
    }
    private List<Metadata> metadata(EmbeddingRecords.ModelSpace space,Position before)throws SQLException {
        String allowed="s.id IS NOT NULL AND s.dataset_id=e.dataset_id AND s.owner='room-publication-v2' AND s.source_id=e.message_id"
                +" AND s.source_revision=1 AND s.revoked=0 AND s.source_hash=e.source_hash"
                +" AND k.id=e.knowledge_receipt_id AND k.source_ref=s.id AND k.dataset_id=e.dataset_id AND k.god_id=e.observer_god"
                +" AND k.receipt_hash=e.receipt_hash AND k.acquisition='DIRECT_HEARD' AND m.dataset_id=e.dataset_id"
                +" AND m.actor_kind=e.actual_actor_kind AND m.actor_id=e.actual_actor_id"
                +" AND m.producer='room-publication-v2' AND m.ingest_sequence<=? AND length(x.context_json)<=262144"
                +" AND json_extract(x.context_json,'$.recordingPolicy')=? AND json_extract(x.context_json,'$.memoryMode')=?"
                +" AND ((c.channel='ROOM_PUBLIC' AND c.policy='PUBLIC_SPEECH') OR (?=0 AND c.channel='ROOM_PRIVATE' AND c.policy='ACTUAL_LISTENERS_ONLY'))"
                +" AND NOT EXISTS(SELECT 1 FROM knowledge_invalidations i WHERE i.dataset_id=k.dataset_id AND i.receipt_id=k.id)"
                +" AND NOT EXISTS(SELECT 1 FROM json_each(?) a WHERE (substr(a.value,1,4)='GOD:' OR c.channel='ROOM_PRIVATE')"
                +" AND (NOT EXISTS(SELECT 1 FROM json_each(x.context_json,'$.fullAudience') h WHERE h.value=a.value)"
                +" OR NOT EXISTS(SELECT 1 FROM deliveries d WHERE d.message_id=m.id AND d.actor_kind||':'||d.actor_id=a.value"
                +" AND d.status='SERVER_DISPATCHED' AND (d.actor_kind!='GOD' OR d.kind='GAME_HEARD'))))";
        String sql="WITH candidates AS MATERIALIZED (SELECT id,created_sequence,message_id,source_ref,source_hash,knowledge_receipt_id,receipt_hash,"
                +"dataset_id,observer_god,actual_actor_kind,actual_actor_id,disclosure_hash,input_hash,covered_characters,total_characters,"
                +"model_name,model_digest,dimensions,encoder_version FROM embedding_rows INDEXED BY embedding_scope"
                +" WHERE dataset_id=? AND observer_god=? AND model_fingerprint=? AND created_sequence<=?"
                +" AND (created_sequence<? OR (created_sequence=? AND id<?)) ORDER BY created_sequence DESC,id DESC LIMIT 129)"
                +" SELECT e.id,e.created_sequence,e.message_id,e.source_ref,e.source_hash,e.knowledge_receipt_id,e.receipt_hash,"
                +"e.actual_actor_kind,e.actual_actor_id,e.disclosure_hash,e.input_hash,e.covered_characters,e.total_characters,"
                +"e.model_name,e.model_digest,e.dimensions,e.encoder_version,m.occurred_utc,CASE WHEN "+allowed+" THEN 1 ELSE 0 END"
                +" FROM candidates e LEFT JOIN source_refs s ON s.id=e.source_ref LEFT JOIN knowledge_receipts k ON k.id=e.knowledge_receipt_id"
                +" LEFT JOIN messages m ON m.id=e.message_id LEFT JOIN conversations c ON c.id=m.conversation_id"
                +" LEFT JOIN message_contexts x ON x.message_id=m.id ORDER BY e.created_sequence DESC,e.id DESC";
        String audience=JSON.toJson(scope.audience().stream().map(ActorRef::key).sorted().toList());
        var result=new ArrayList<Metadata>();
        try(var q=prepare(sql,scope.dataset(),scope.speaker(),space.fingerprint(),watermark,before.beforeSequence(),before.beforeSequence(),before.beforeId(),
                watermark,scope.recordingPolicy(),scope.memoryMode(),scope.publicRoom()?1:0,audience);var rows=q.executeQuery()){
            while(rows.next()){
                try {result.add(new Metadata(rows.getString(1),rows.getLong(2),UUID.fromString(rows.getString(3)),rows.getLong(4),rows.getString(5),
                        UUID.fromString(rows.getString(6)),rows.getString(7),new ActorRef(ActorKind.valueOf(rows.getString(8)),rows.getString(9)),
                        rows.getString(10),rows.getString(11),rows.getInt(12),rows.getInt(13),
                        new EmbeddingRecords.ModelSpace(rows.getString(14),rows.getString(15),rows.getInt(16),rows.getString(17)),
                        rows.getString(18)==null?Instant.EPOCH:Instant.parse(rows.getString(18)),rows.getInt(19)==1));}
                catch(RuntimeException malformed){
                    // Preserve progress across unsupported metadata without loading/scoring its vector.
                    result.add(new Metadata(rows.getString(1),rows.getLong(2),new UUID(0,0),0,"",new UUID(0,0),"",
                            new ActorRef(ActorKind.GOD,scope.speaker()),"","",0,0,space,Instant.EPOCH,false));}
            }
        }
        return List.copyOf(result);
    }
    private Optional<float[]> storedVector(Metadata metadata)throws SQLException {
        try(var q=prepare("SELECT vector,vector_hash FROM embedding_rows WHERE id=? AND dataset_id=? AND length(vector)=?",
                metadata.id(),scope.dataset(),metadata.space().dimensions()*4);var rows=q.executeQuery()){
            if(!rows.next())return Optional.empty();byte[] bytes=rows.getBytes(1);
            if(!EmbeddingRecords.vectorHash(bytes).equals(rows.getString(2)))return Optional.empty();
            var input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);float[] values=new float[metadata.space().dimensions()];
            for(int i=0;i<values.length;i++)values[i]=input.getFloat();
            return Optional.of(EmbeddingRecords.vector(values,values.length));
        }
    }
    static String vectorFingerprint(EmbeddingRecords.QueryVector vector){
        float[] values=vector.values();var bytes=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN);
        for(float value:values)bytes.putFloat(value);
        return RecordingRecords.sha256(vector.modelSpace().fingerprint()+":"+vector.inputHash()+":"+EmbeddingRecords.vectorHash(bytes.array()));
    }
    private static double cosine(float[] a,float[] b){
        double dot=0,aa=0,bb=0;for(int i=0;i<a.length;i++){dot+=(double)a[i]*b[i];aa+=(double)a[i]*a[i];bb+=(double)b[i]*b[i];}
        double score=dot/Math.sqrt(aa*bb);if(!Double.isFinite(score))throw new IllegalArgumentException("SEMANTIC_SCORE");
        return Math.max(-1,Math.min(1,score));
    }
    static int wireByteSize(SemanticReadRecords.Entry entry){
        return JSON.toJson(Map.of("messageId",entry.messageId().toString(),"speaker",Map.of("kind",entry.speaker().kind().name(),"id",entry.speaker().id()),
                "occurredAt",entry.occurredAt().toString(),"text",entry.text(),"similarity",entry.similarity(),
                "coveredCharacters",entry.coveredCharacters(),"totalCharacters",entry.totalCharacters(),"excerpt",entry.excerpt()))
                .getBytes(StandardCharsets.UTF_8).length;
    }
    private PreparedStatement prepare(String sql,Object...values)throws SQLException {
        if(expired())throw new SQLException("SEMANTIC_READ_BUDGET",null,9);var q=db.prepareStatement(sql);q.setQueryTimeout(1);
        for(int i=0;i<values.length;i++){if(values[i] instanceof Number n)q.setLong(i+1,n.longValue());else q.setString(i+1,values[i].toString());}return q;
    }
    private boolean expired(){return System.nanoTime()>deadline||Thread.currentThread().isInterrupted();}
}
