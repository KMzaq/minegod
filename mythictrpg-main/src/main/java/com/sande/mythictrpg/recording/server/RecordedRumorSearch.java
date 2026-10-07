package com.sande.mythictrpg.recording.server;

import com.google.gson.*;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.MemoryReadSession.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Archived permission candidates only. The game must attach and revalidate the current native rumor snapshot. */
final class RecordedRumorSearch {
    record Position(long beforeSequence,long beforeSource,String beforeReceipt) {
        static Position initial(){return new Position(Long.MAX_VALUE,Long.MAX_VALUE,"");}
        static Position end(){return new Position(0,0,"");}
    }
    record Candidate(SourceRef source,UUID knowledgeReceiptId,UUID lineageId,String recipientGodId,
                     UUID subjectPlayerId,String claim,String epithet,Set<UUID> disclosureAudience) {
        Candidate {disclosureAudience=Set.copyOf(disclosureAudience);}
    }
    record Result(List<Candidate> candidates,Position position) { }
    private record Source(long key,long sequence,String id,long revision,String hash,boolean live,
                          String lineage,long origin,long stateCursor,String registeredLineage,long cutoff,long confirmed) { }
    private record Receipt(String id,String god,String acquisition,long policy,String hash,long bytes,Long acquiredCursor,boolean live) { }
    private static final Gson JSON=new Gson();
    private static final int MAX_SCAN=128,MAX_PROJECTION=32768,MAX_READ_BYTES=262144;
    private final Connection db;private final UUID world;private final RecordedRoomSearch.Scope scope;private final long watermark,deadline;
    private int readBytes;
    private RecordedRumorSearch(Connection db,UUID world,RecordedRoomSearch.Scope scope,long watermark,long deadline){this.db=db;this.world=world;this.scope=scope;this.watermark=watermark;this.deadline=deadline;}
    static boolean supported(RecordedRoomSearch.Scope scope){return !scope.publicRoom()&&scope.memoryMode().equals("RUMOR_TEST")
            &&scope.audience().size()>=2&&scope.audience().size()<=17&&scope.audience().contains(new ActorRef(ActorKind.GOD,scope.speaker()))
            &&scope.audience().stream().filter(a->a.kind()==ActorKind.GOD).count()==1;}
    static Result query(Connection db,UUID world,RecordedRoomSearch.Scope scope,Query query,Budget budget,long watermark,Position position)throws Exception {
        return query(db,world,scope,query,budget,watermark,position,System.nanoTime()+250_000_000L);
    }
    static Result query(Connection db,UUID world,RecordedRoomSearch.Scope scope,Query query,Budget budget,long watermark,Position position,long deadline)throws Exception {
        if(!supported(scope)||!query.actorSelection().isAny()||query.fromInclusive().isPresent()||query.untilExclusive().isPresent())return new Result(List.of(),Position.end());
        var reader=new RecordedRumorSearch(db,world,scope,watermark,deadline);try(var ignored=new SqlReadBudget(db,deadline)){return reader.search(query,budget,position);}
    }
    private Result search(Query query,Budget budget,Position initial)throws Exception {
        var selected=new ArrayList<Candidate>();Position cursor=initial;int scanned=0,used=0;String term=query.text().strip().toLowerCase(Locale.ROOT);
        if(initial.beforeSequence()==0||expired())return result(selected,cursor);
        try {
            var sources=sources(initial);int sourceIndex=0;boolean exhausted=true;
            for(var source:sources){
                if(sourceIndex++>=MAX_SCAN||scanned>=MAX_SCAN||expired()){exhausted=false;break;}
                if(!source.live()){scanned++;cursor=after(source);continue;}
                String before=source.sequence()==initial.beforeSequence()&&source.key()==initial.beforeSource()&&!initial.beforeReceipt().isEmpty()?initial.beforeReceipt():"~";
                var receipts=receipts(source,before,MAX_SCAN-scanned+1);int receiptIndex=0;
                if(receipts.isEmpty()){scanned++;cursor=after(source);continue;}
                for(var receipt:receipts){
                    if(scanned>=MAX_SCAN||expired()||selected.size()>=budget.rows())return result(selected,cursor);
                    if(receipt.bytes()>0&&receipt.bytes()<=MAX_PROJECTION+4096L&&readBytes+receipt.bytes()>MAX_READ_BYTES)return result(selected,cursor);
                    scanned++;receiptIndex++;readBytes+=(int)Math.max(0,Math.min(receipt.bytes(),MAX_PROJECTION+4096L));
                    Optional<Candidate> candidate;
                    try{candidate=receipt.bytes()<=0||receipt.bytes()>MAX_PROJECTION+4096L||!receipt.live()||!acquired(source,receipt)
                            ?Optional.empty():parse(source,receipt);}catch(RuntimeException malformed){candidate=Optional.empty();}
                    if(expired())return result(selected,cursor);
                    if(candidate.isPresent()){
                        var value=candidate.orElseThrow();
                        if(term.isEmpty()||(value.claim()+"\n"+value.epithet()).toLowerCase(Locale.ROOT).contains(term)){
                            int size=RumorReadRecords.maximumWireByteSize(value.source(),value.knowledgeReceiptId(),value.lineageId(),value.recipientGodId(),value.subjectPlayerId(),value.claim(),value.epithet(),value.disclosureAudience());
                            if(size<=budget.utf8Bytes()) {if(used+size>budget.utf8Bytes())return result(selected,cursor);selected.add(value);used+=size;}
                        }
                    }
                    cursor=new Position(source.sequence(),source.key(),receipt.id());if(receiptIndex==receipts.size())cursor=after(source);
                }
                if(scanned>=MAX_SCAN||selected.size()>=budget.rows())return result(selected,cursor);
            }
            if(exhausted&&sources.size()<=MAX_SCAN)cursor=Position.end();
        }catch(SQLException failure){if(failure.getErrorCode()!=9||!expired())throw failure;}
        return result(selected,cursor);
    }
    private List<Source> sources(Position cursor)throws SQLException {
        String sql="WITH hits AS MATERIALIZED(SELECT * FROM source_refs WHERE owner='rumor-saved-data-v1' AND dataset_id=?"
                +" AND ingest_sequence<=? AND (ingest_sequence<? OR (ingest_sequence=? AND (id<? OR (id=? AND ?=1)))) ORDER BY ingest_sequence DESC,id DESC LIMIT 129)"
                +" SELECT s.id,s.ingest_sequence,s.source_id,s.source_revision,s.source_hash,CASE WHEN s.revoked=0 AND s.kind='RUMOR_RECEIVED'"
                +" AND NOT EXISTS(SELECT 1 FROM invalidations i WHERE i.dataset_id=s.dataset_id AND i.kind=s.kind AND i.owner=s.owner AND i.source_id=s.source_id AND i.source_revision=s.source_revision)"
                +" AND NOT EXISTS(SELECT 1 FROM source_refs n WHERE n.dataset_id=s.dataset_id AND n.kind=s.kind AND n.owner=s.owner AND n.source_id=s.source_id AND n.source_revision>s.source_revision) THEN 1 ELSE 0 END,"
                +" o.lineage_id,o.origin_cursor,o.state_cursor,c.lineage_id,c.cutoff,c.latest_confirmed FROM hits s"
                +" LEFT JOIN source_origins o ON o.source_ref=s.id LEFT JOIN source_cutovers c ON c.dataset_id=s.dataset_id AND c.owner=s.owner ORDER BY s.ingest_sequence DESC,s.id DESC";
        var found=new ArrayList<Source>();try(var q=prepare(sql,scope.dataset(),watermark,cursor.beforeSequence(),cursor.beforeSequence(),cursor.beforeSource(),cursor.beforeSource(),cursor.beforeReceipt().isEmpty()?0:1);var rows=q.executeQuery()){
            while(rows.next())found.add(new Source(rows.getLong(1),rows.getLong(2),rows.getString(3),rows.getLong(4),rows.getString(5),rows.getInt(6)==1,rows.getString(7),rows.getLong(8),rows.getLong(9),rows.getString(10),rows.getLong(11),rows.getLong(12)));}
        return found;
    }
    private List<Receipt> receipts(Source source,String before,int limit)throws SQLException {
        String sql="SELECT k.id,k.god_id,k.acquisition,k.policy_revision,k.receipt_hash,length(CAST(k.projection AS BLOB))+length(CAST(k.audience_json AS BLOB)),o.acquired_cursor,"
                +" CASE WHEN NOT EXISTS(SELECT 1 FROM knowledge_invalidations i WHERE i.dataset_id=k.dataset_id AND i.receipt_id=k.id) THEN 1 ELSE 0 END"
                +" FROM knowledge_receipts k LEFT JOIN knowledge_origins o ON o.receipt_id=k.id WHERE k.dataset_id=? AND k.god_id=? AND k.source_ref=? AND k.id<? ORDER BY k.id DESC LIMIT ?";
        var found=new ArrayList<Receipt>();try(var q=prepare(sql,scope.dataset(),scope.speaker(),source.key(),before,limit);var rows=q.executeQuery()){
            while(rows.next()){long acquired=rows.getLong(7);Long optional=rows.wasNull()?null:acquired;found.add(new Receipt(rows.getString(1),rows.getString(2),rows.getString(3),rows.getLong(4),rows.getString(5),rows.getLong(6),optional,rows.getInt(8)==1));}}
        return found;
    }
    private boolean acquired(Source source,Receipt receipt)throws SQLException {
        if(!receipt.acquisition().equals("RUMOR_RECEIVED")||receipt.policy()!=source.revision()||receipt.acquiredCursor()==null||source.lineage()==null
                ||!source.lineage().equals(source.registeredLineage())||source.origin()<=source.cutoff()||source.stateCursor()<source.origin()
                ||receipt.acquiredCursor()<source.stateCursor()||receipt.acquiredCursor()>source.confirmed())return false;
        canonical(source.lineage());
        try(var q=prepare("SELECT 1 FROM work_items WHERE dataset_id=? AND source_ref=? AND kind='KNOWLEDGE_ACQUIRED'"
                +" AND source_version=? AND state<>'INVALIDATED' AND created_sequence>=? AND created_sequence<=?",
                scope.dataset(),source.key(),receipt.id()+":"+receipt.hash(),source.sequence(),watermark);var rows=q.executeQuery()){return rows.next()&&!rows.next();}
    }
    private Optional<Candidate> parse(Source source,Receipt receipt)throws SQLException {
        String projection,audienceJson;
        try(var q=prepare("SELECT projection,audience_json FROM knowledge_receipts WHERE id=? AND source_ref=? AND dataset_id=? AND god_id=? AND receipt_hash=?"
                +" AND length(CAST(projection AS BLOB))<=32768 AND length(CAST(audience_json AS BLOB))<=4096",receipt.id(),source.key(),scope.dataset(),scope.speaker(),receipt.hash());var rows=q.executeQuery()){
            if(!rows.next())return Optional.empty();projection=rows.getString(1);audienceJson=rows.getString(2);}
        var audience=new HashSet<ActorRef>();for(var entry:JsonParser.parseString(audienceJson).getAsJsonArray()){
            String key=entry.getAsString();int split=key.indexOf(':');audience.add(new ActorRef(ActorKind.valueOf(key.substring(0,split)),key.substring(split+1)));}
        var keys=audience.stream().map(ActorRef::key).sorted().toList();
        if(!JSON.toJson(keys).equals(audienceJson)||!audience.containsAll(scope.audience())||audience.stream().filter(a->a.kind()==ActorKind.GOD).count()!=1
                ||!audience.contains(new ActorRef(ActorKind.GOD,scope.speaker())))return Optional.empty();
        UUID receiptId=canonical(receipt.id()),lineage=canonical(source.lineage()),root=canonical(source.id());
        String stable=scope.dataset()+"/"+RumorRecordingCapture.PRODUCER+"/"+lineage+"/"+root+"/"+source.revision()+"/"+receipt.god();
        if(!UUID.nameUUIDFromBytes(stable.getBytes(StandardCharsets.UTF_8)).equals(receiptId)
                ||!RecordingRecords.sha256(JSON.toJson(List.of(receiptId,receipt.god(),receipt.acquisition(),projection,keys,receipt.policy()))).equals(receipt.hash()))return Optional.empty();
        var object=JsonParser.parseString(projection).getAsJsonObject();
        if(!object.keySet().equals(Set.of("memoryMode","rootId","subjectPlayerId","godId","claimRevision","claim","epithet","acquisition","assessmentStatus")))return Optional.empty();
        UUID subject=canonical(object.get("subjectPlayerId").getAsString());String claim=object.get("claim").getAsString(),epithet=object.get("epithet").getAsString();
        if(!scope.audience().contains(new ActorRef(ActorKind.PLAYER,subject.toString()))||claim.isBlank()||claim.length()>300||epithet.length()>60)return Optional.empty();
        var canonicalProjection=new TreeMap<String,Object>();canonicalProjection.put("memoryMode","RUMOR_TEST");canonicalProjection.put("rootId",root);
        canonicalProjection.put("subjectPlayerId",subject);canonicalProjection.put("godId",scope.speaker());canonicalProjection.put("claimRevision",source.revision());
        canonicalProjection.put("claim",claim);canonicalProjection.put("epithet",epithet);canonicalProjection.put("acquisition","RUMOR_RECEIVED");canonicalProjection.put("assessmentStatus","CURRENT_GAME_LOOKUP_REQUIRED");
        if(!JSON.toJson(canonicalProjection).equals(projection))return Optional.empty();
        var players=audience.stream().filter(a->a.kind()==ActorKind.PLAYER).map(a->canonical(a.id())).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if(players.isEmpty()||players.size()>16||!players.contains(subject))return Optional.empty();
        var ref=new SourceRef(world,scope.dataset(),SourceKind.RUMOR_RECEIVED,RumorRecordingCapture.PRODUCER,root.toString(),source.revision(),source.hash());
        return Optional.of(new Candidate(ref,receiptId,lineage,scope.speaker(),subject,claim,epithet,players));
    }
    private PreparedStatement prepare(String sql,Object...values)throws SQLException {
        if(expired())throw new SQLException("RUMOR_READ_BUDGET",null,9);var query=db.prepareStatement(sql);query.setQueryTimeout(1);
        for(int i=0;i<values.length;i++){if(values[i] instanceof Number n)query.setLong(i+1,n.longValue());else query.setString(i+1,values[i].toString());}return query;
    }
    private boolean expired(){return System.nanoTime()>deadline||Thread.currentThread().isInterrupted();}
    private static UUID canonical(String value){UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException("NONCANONICAL_UUID");return id;}
    private static Position after(Source source){return new Position(source.sequence(),source.key(),"");}
    private static Result result(List<Candidate> values,Position position){return new Result(List.copyOf(values),position);}
    static int wireByteSize(RumorReadRecords.Entry entry){return RumorReadRecords.wireByteSize(entry);}
}
