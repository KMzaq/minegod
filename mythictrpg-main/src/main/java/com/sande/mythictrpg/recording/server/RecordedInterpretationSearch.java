package com.sande.mythictrpg.recording.server;

import com.google.gson.*;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.ProjectionRecords.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import static com.sande.mythictrpg.recording.api.RecordingRecords.sha256;

/** Native-only, bounded receipt-index lookup. These records remain interpretations, not game state. */
final class RecordedInterpretationSearch {
    private static final Gson JSON=new Gson();
    record Position(int seedIndex,String afterMemoryId) {
        Position { if(seedIndex<0||seedIndex>8)throw new IllegalArgumentException("INTERPRETATION_POSITION");Objects.requireNonNull(afterMemoryId); }
        static Position initial(){return new Position(0,"");}
    }
    record Result(List<InterpretationReadRecords.Entry> entries,Position position) { }
    record ExactCandidate(InterpretationReadRecords.Entry entry,NativeInterpretationEvidence.CandidateBinding binding) { }
    private record Expanded(ExactCandidate exact,ProjectionInputManifest.Manifest manifest) { }
    record Binding(RecordedRoomSearch.Node node,long sourceKey,UUID receipt,String sourceHash,
                           String receiptHash,UUID conversation,String disclosure,Set<ActorRef> audience,
                           String recordingPolicy,String memoryMode) { }
    private record Dependency(String alias,UUID message,UUID receipt,long sourceKey,String sourceHash,String receiptHash,
                              ActorRef actor,Instant occurred,boolean excerpt,int total,int covered) { }
    private final Connection db;
    private final RecordedRoomSearch.Scope scope;
    private final long watermark,deadline;
    private final RecordedRoomSearch raw;
    private final Map<String,Optional<ProjectionInputManifest.Manifest>> manifests=new HashMap<>();
    private final Map<String,Optional<Expanded>> expanded=new HashMap<>();
    private final Map<String,Boolean> checkedGroups=new HashMap<>();
    RecordedInterpretationSearch(Connection db,RecordedRoomSearch.Scope scope,long watermark,long deadline){
        this.db=db;this.scope=scope;this.watermark=watermark;this.deadline=deadline;raw=new RecordedRoomSearch(db,scope,watermark,deadline);
    }
    static Result query(Connection db,RecordedRoomSearch.Scope scope,List<UUID> seeds,MemoryReadSession.Budget budget,
                        long watermark,Position position)throws Exception {
        if(seeds.size()>8||new HashSet<>(seeds).size()!=seeds.size())throw new IllegalArgumentException("INTERPRETATION_SEEDS");
        var reader=new RecordedInterpretationSearch(db,scope,watermark,System.nanoTime()+250_000_000L);
        try(var ignored=new SqlReadBudget(db,reader.deadline)){return reader.search(List.copyOf(seeds),budget,position);}
    }
    private Result search(List<UUID> seeds,MemoryReadSession.Budget budget,Position start)throws Exception {
        int seedIndex=start.seedIndex(),scanned=0,used=0;String after=start.afterMemoryId();
        var selected=new ArrayList<InterpretationReadRecords.Entry>();
        outer:while(seedIndex<seeds.size()&&scanned<64&&selected.size()<budget.rows()&&!expired()){
            int priorSeed=seedIndex;String priorAfter=after;
            try {
                var seedNode=raw.nativeSource(seeds.get(seedIndex));
                if(seedNode.isEmpty()){seedIndex++;after="";continue;}
                var seed=binding(seedNode.orElseThrow());if(seed==null){seedIndex++;after="";continue;}
                // Existing receipt-leading PK index; limit BEFORE joining/filtering candidate metadata.
                var ids=new ArrayList<String>();
                try(var q=prepare("SELECT memory_id FROM memory_sources INDEXED BY memory_receipt_withdrawal"
                        +" WHERE knowledge_receipt_id=? AND memory_id>? ORDER BY memory_id LIMIT 33",seed.receipt(),after);var rows=q.executeQuery()){
                    while(rows.next())ids.add(rows.getString(1));}
                if(ids.isEmpty()){seedIndex++;after="";continue;}
                for(int i=0;i<Math.min(32,ids.size());i++){
                    if(scanned>=64||selected.size()>=budget.rows()||expired())break outer;
                    priorSeed=seedIndex;priorAfter=after;String id=ids.get(i);scanned++;
                    try {
                        var entry=entry(id,seeds,seedIndex);
                        if(expired())throw new RecordedRoomSearch.RequestLimit();
                        if(entry.isPresent()){
                            int bytes=wireByteSize(entry.orElseThrow());
                            if(bytes>budget.utf8Bytes()){after=id;continue;} // Cannot ever fit this request contract: skip whole card.
                            if(used+bytes>budget.utf8Bytes())break outer; // A fresh page can fit it. Do NOT advance.
                            used+=bytes;selected.add(entry.orElseThrow());
                        }
                        after=id;
                    }catch(RecordedRoomSearch.SourceLimit unsupported){after=id;}
                    catch(RuntimeException malformed){after=id;} // Corrupt candidate never disables unrelated RAW.
                }
                if(ids.size()<=32){seedIndex++;after="";}
            }catch(RecordedRoomSearch.RequestLimit exhausted){seedIndex=priorSeed;after=priorAfter;break;}
            catch(RecordedRoomSearch.SourceLimit unsupported){seedIndex++;after="";}
            catch(SQLException failure){if(failure.getErrorCode()!=9||!expired())throw failure;seedIndex=priorSeed;after=priorAfter;break;}
            catch(RuntimeException malformed){seedIndex++;after="";}
        }
        return new Result(List.copyOf(selected),new Position(seedIndex,after));
    }
    private Optional<InterpretationReadRecords.Entry> entry(String id,List<UUID> seeds,int seedIndex)throws Exception {
        var value=exact(UUID.fromString(id));if(value.isEmpty())return Optional.empty();
        var entry=value.orElseThrow().entry();var messages=value.orElseThrow().binding().inputMessageIds();
        if(!messages.contains(seeds.get(seedIndex)))return Optional.empty();
        // A card reached through several seeds belongs to its earliest seed, not a fabricated subset.
        for(int i=0;i<seedIndex;i++)if(messages.contains(seeds.get(i)))return Optional.empty();
        return Optional.of(entry);
    }
    /** Game-only exact lookup. Validates every sibling of this job, never reverse-searches shared inputs. */
    Optional<ExactCandidate> exact(UUID id)throws Exception {
        var value=expanded(id.toString());if(value.isEmpty())return Optional.empty();
        var manifest=value.orElseThrow().manifest();String key=manifest.jobId()+"/"+manifest.extractorVersion();
        Boolean checked=checkedGroups.get(key);
        if(checked==null){
            checked=true;
            try {for(var output:manifest.outputs())if(expanded(output.memoryId().toString()).isEmpty()){checked=false;break;}}
            catch(RuntimeException malformed){checked=false;}
            checkedGroups.put(key,checked);
        }
        return checked?Optional.of(value.orElseThrow().exact()):Optional.empty();
    }
    private Optional<Expanded> expanded(String id)throws Exception {
        var cached=expanded.get(id);if(cached!=null)return cached;
        var value=expand(id);expanded.put(id,value);return value;
    }
    private Optional<Expanded> expand(String id)throws Exception {
        String payload,disclosure,version,layer,kind,job;UUID target;long targetSource;String targetDelivery;
        try(var q=prepare("SELECT m.payload_json,m.projection_hash,m.disclosure_hash,m.extractor_version,m.layer,m.kind,"
                +"w.message_id,w.source_ref,w.receipt_id,m.job_id FROM memories m JOIN work_items w ON w.id=m.job_id"
                +" WHERE m.id=? AND m.dataset_id=? AND m.observer_god=? AND m.status='CANDIDATE' AND m.created_sequence<=?"
                +" AND w.dataset_id=m.dataset_id AND w.kind='ROOM_KNOWLEDGE_CAPTURED' AND w.state='DONE'"
                +" AND w.extractor_version=m.extractor_version AND length(m.payload_json)<=65536",id,scope.dataset(),scope.speaker(),watermark);var rows=q.executeQuery()){
            if(!rows.next())return Optional.empty();payload=rows.getString(1);if(!sha256(payload).equals(rows.getString(2)))return Optional.empty();
            disclosure=rows.getString(3);version=rows.getString(4);layer=rows.getString(5);kind=rows.getString(6);
            target=UUID.fromString(rows.getString(7));targetSource=rows.getLong(8);targetDelivery=rows.getString(9);
            job=rows.getString(10);
        }
        String manifestKey=job+"/"+version;
        var inputManifest=manifests.get(manifestKey);
        if(inputManifest==null){inputManifest=ProjectionInputManifest.load(db,scope.dataset(),job,version,watermark);manifests.put(manifestKey,inputManifest);}
        if(inputManifest.isEmpty())return Optional.empty();
        var manifest=inputManifest.orElseThrow();
        if(!manifest.target().messageId().equals(target)||!manifest.observerGodId().equals(scope.speaker())
                ||!manifest.disclosureHash().equals(disclosure))return Optional.empty();
        Candidate candidate=parse(payload);
        if(!candidate.layer().name().equals(layer)||!candidate.kind().name().equals(kind)
                ||candidate.layer()!=Layer.EVENT&&(candidate.kind()!=ClaimKind.DIALOGUE_EPISODE||!candidate.links().isEmpty()))return Optional.empty();
        var dependencies=new LinkedHashMap<String,Dependency>();var messages=new HashSet<UUID>();var receipts=new HashSet<UUID>();
        try(var q=prepare("SELECT source_alias,message_id,knowledge_receipt_id,source_ref,source_hash,receipt_hash,actual_actor_kind,actual_actor_id,"
                +"occurred_utc,excerpt,total_characters,covered_characters FROM memory_sources WHERE memory_id=? LIMIT 7",id);var rows=q.executeQuery()){
            while(rows.next()){
                var d=new Dependency(rows.getString(1),UUID.fromString(rows.getString(2)),UUID.fromString(rows.getString(3)),rows.getLong(4),rows.getString(5),rows.getString(6),
                        new ActorRef(ActorKind.valueOf(rows.getString(7)),rows.getString(8)),Instant.parse(rows.getString(9)),rows.getInt(10)==1,rows.getInt(11),rows.getInt(12));
                if(!d.alias().matches("e[0-5]")||dependencies.put(d.alias(),d)!=null||!messages.add(d.message())||!receipts.add(d.receipt()))return Optional.empty();
            }
        }
        if(dependencies.size()!=manifest.inputs().size()||!messages.contains(target))return Optional.empty();
        var nodes=raw.nativeSources(messages);if(nodes.isEmpty())return Optional.empty();
        var bindings=new HashMap<String,Binding>();String targetAlias=null;UUID conversation=null;
        for(var d:dependencies.values()){
            var n=nodes.orElseThrow().get(d.message());var b=binding(n);if(b==null)return Optional.empty();
            var input=manifest.input(d.alias());
            if(!b.receipt().equals(d.receipt())||b.sourceKey()!=d.sourceKey()||!b.sourceHash().equals(d.sourceHash())||!b.receiptHash().equals(d.receiptHash())
                    ||!n.speaker().equals(d.actor())||!n.occurred().equals(d.occurred())||!b.disclosure().equals(disclosure)
                    ||d.total()!=n.body().length()||d.covered()<1||d.covered()>d.total()||d.excerpt()!=(d.covered()<d.total()))return Optional.empty();
            if(!input.messageId().equals(n.id())||!input.conversationId().equals(b.conversation())
                    ||!Set.copyOf(input.audience()).equals(b.audience())||!input.recordingPolicy().equals(b.recordingPolicy())
                    ||!input.memoryMode().equals(b.memoryMode())||!input.prefixHash().equals(sha256(n.body().substring(0,d.covered()))))return Optional.empty();
            if(conversation==null)conversation=b.conversation();else if(!conversation.equals(b.conversation()))return Optional.empty();
            if(d.message().equals(target)){
                if(b.sourceKey()!=targetSource)return Optional.empty();targetAlias=d.alias();
                try(var q=prepare("SELECT 1 FROM knowledge_receipts WHERE id=? AND json_extract(projection,'$.deliveryReceiptId')=?",b.receipt(),targetDelivery);var r=q.executeQuery()){if(!r.next())return Optional.empty();}
            }
            bindings.put(d.alias(),b);
        }
        if(targetAlias==null||!targetAlias.equals(manifest.targetAlias()))return Optional.empty();
        String requiredTarget=targetAlias;
        if(candidate.quotes().stream().noneMatch(q->q.sourceAlias().equals(requiredTarget)))return Optional.empty();
        var quotes=new ArrayList<InterpretationReadRecords.Quote>();var quoted=new HashSet<String>();
        for(var quote:candidate.quotes()){
            var d=dependencies.get(quote.sourceAlias());if(d==null)return Optional.empty();var n=bindings.get(d.alias()).node();
            if(!n.body().substring(0,d.covered()).contains(quote.text()))return Optional.empty();
            quotes.add(new InterpretationReadRecords.Quote(d.alias(),n.id(),n.speaker(),n.occurred(),quote.text()));quoted.add(d.alias());
        }
        var expectedLinks=new HashSet<String>();var links=new ArrayList<InterpretationReadRecords.Link>();
        for(var link:candidate.links()){
            var newer=bindings.get(link.newerAlias());var older=bindings.get(link.olderAlias());
            if(newer==null||older==null||!link.newerAlias().equals(requiredTarget)||!quoted.contains(link.olderAlias())
                    ||older.node().occurred().isAfter(newer.node().occurred())
                    ||link.relation()!=Relation.CONTRADICTS&&!newer.node().speaker().equals(older.node().speaker())
                    ||Set.of(Relation.CANCELS,Relation.CORRECTS).contains(link.relation())&&candidate.kind()!=ClaimKind.CORRECTION_OR_EXPLANATION
                    ||link.relation()==Relation.REPORTS_FULFILLMENT&&candidate.kind()!=ClaimKind.SPEAKER_CLAIM
                    ||link.relation()==Relation.ALSO_PLANNED&&candidate.kind()!=ClaimKind.INTENTION_OR_PROMISE)return Optional.empty();
            if(!expectedLinks.add(newer.receipt()+"/"+older.receipt()+"/"+link.relation()))return Optional.empty();
            links.add(new InterpretationReadRecords.Link(link.newerAlias(),link.olderAlias(),link.relation()));
        }
        var storedLinks=new HashSet<String>();
        try(var q=prepare("SELECT newer_receipt_id,older_receipt_id,relation,status FROM memory_links WHERE memory_id=? LIMIT 3",id);var rows=q.executeQuery()){
            while(rows.next()){if(!rows.getString(4).equals("CANDIDATE"))return Optional.empty();storedLinks.add(rows.getString(1)+"/"+rows.getString(2)+"/"+rows.getString(3));}}
        if(!storedLinks.equals(expectedLinks))return Optional.empty();
        var inputs=dependencies.values().stream().sorted(Comparator.comparing(Dependency::alias))
                .map(d->new InterpretationReadRecords.Coverage(d.alias(),d.message(),d.covered(),d.total())).toList();
        var entry=new InterpretationReadRecords.Entry(UUID.fromString(id),candidate.layer(),candidate.kind(),version,quotes,links,inputs);
        var exact=new NativeInterpretationEvidence.CandidateBinding(UUID.fromString(id),UUID.fromString(job),scope.speaker(),version,
                candidate.layer(),candidate.kind(),sha256(payload),sha256(ProjectionInputManifest.encode(manifest)),manifest.createdSequence(),messages);
        return Optional.of(new Expanded(new ExactCandidate(entry,exact),manifest));
    }
    /** Extra stored-projection bindings; authority, actual audience and full RAW hashes were already validated by the shared reader. */
    Binding binding(RecordedRoomSearch.Node node)throws Exception {
        String sql="SELECT s.id,s.source_hash,s.kind,k.id,k.receipt_hash,k.audience_json,k.policy_revision,k.projection,m.conversation_id,"
                +"m.body_hash,x.context_json,c.channel,c.policy,d.id,d.view_hash,v.plain_text,v.parts_json,w.source_version"
                +" FROM source_refs s JOIN knowledge_receipts k ON k.source_ref=s.id JOIN messages m ON m.id=s.source_id"
                +" JOIN message_contexts x ON x.message_id=m.id JOIN conversations c ON c.id=m.conversation_id"
                +" JOIN deliveries d ON d.id=json_extract(k.projection,'$.deliveryReceiptId') AND d.message_id=m.id"
                +" JOIN delivery_views v ON v.message_id=m.id AND v.view_hash=d.view_hash"
                +" JOIN work_items w ON w.source_ref=s.id AND w.receipt_id=d.id AND w.message_id=m.id AND w.kind='ROOM_KNOWLEDGE_CAPTURED'"
                +" WHERE m.id=? AND s.dataset_id=? AND m.dataset_id=s.dataset_id AND k.dataset_id=s.dataset_id AND w.dataset_id=s.dataset_id"
                +" AND s.owner='room-publication-v2' AND s.source_revision=1 AND s.revoked=0 AND k.god_id=? AND k.acquisition='DIRECT_HEARD'"
                +" AND d.actor_kind='GOD' AND d.actor_id=k.god_id AND d.kind='GAME_HEARD' AND d.status='SERVER_DISPATCHED'"
                +" AND s.ingest_sequence<=? AND w.created_sequence<=?"
                +" AND NOT EXISTS(SELECT 1 FROM knowledge_invalidations i WHERE i.dataset_id=k.dataset_id AND i.receipt_id=k.id)"
                +" AND NOT EXISTS(SELECT 1 FROM invalidations i WHERE i.dataset_id=s.dataset_id AND i.kind=s.kind AND i.owner=s.owner AND i.source_id=s.source_id AND i.source_revision=s.source_revision)"
                +" AND NOT EXISTS(SELECT 1 FROM source_refs newer WHERE newer.dataset_id=s.dataset_id AND newer.kind=s.kind AND newer.owner=s.owner AND newer.source_id=s.source_id AND newer.source_revision>s.source_revision) LIMIT 2";
        try(var q=prepare(sql,node.id(),scope.dataset(),scope.speaker(),watermark,watermark);var rows=q.executeQuery()){
            if(!rows.next())return null;
            var context=JsonParser.parseString(rows.getString(11)).getAsJsonObject();var pointer=JsonParser.parseString(rows.getString(8)).getAsJsonObject();
            String sourceHash=sha256(rows.getString(10)+":"+JSON.toJson(context));
            if(!sourceHash.equals(rows.getString(2))||!rows.getString(3).equals(node.speaker().kind()==ActorKind.PLAYER?"DIALOGUE_DIRECT":"DERIVED_SPEECH"))return null;
            var full=strings(context.getAsJsonArray("fullAudience"));var allowed=strings(JsonParser.parseString(rows.getString(6)).getAsJsonArray());
            if(!allowed.equals(full)||full.isEmpty()||full.size()>256||!full.contains("GOD:"+scope.speaker())||context.getAsJsonArray("evidence").size()!=0)return null;
            String receipt=rows.getString(4),receiptHash=rows.getString(5),delivery=rows.getString(14),viewHash=rows.getString(15);
            if(!rows.getString(18).equals(receipt+":"+receiptHash)||!node.id().toString().equals(pointer.get("messageId").getAsString())
                    ||!delivery.equals(pointer.get("deliveryReceiptId").getAsString())||!viewHash.equals(pointer.get("viewHash").getAsString()))return null;
            var parts=new ArrayList<String>();JsonParser.parseString(rows.getString(17)).getAsJsonArray().forEach(p->parts.add(p.getAsString()));
            if(!node.body().equals(rows.getString(16))||!viewHash.equals(new DeliveryView(rows.getString(16),parts).hash()))return null;
            try(var pq=prepare("SELECT part_index,body FROM delivery_parts_resolved WHERE receipt_id=? ORDER BY part_index",delivery);var pr=pq.executeQuery()){
                int i=0;while(pr.next()){if(i>=parts.size()||pr.getInt(1)!=i||!parts.get(i++).equals(pr.getString(2)))return null;}if(i!=parts.size())return null;}
            // Extraction used the whole historical audience; its original permission intersection must still hold.
            var actors=new HashSet<ActorRef>();
            for(String key:full){int colon=key.indexOf(':');var actor=new ActorRef(ActorKind.valueOf(key.substring(0,colon)),key.substring(colon+1));actors.add(actor);
                try(var aq=prepare("SELECT 1 FROM deliveries WHERE message_id=? AND actor_kind=? AND actor_id=? AND status='SERVER_DISPATCHED'"
                        +(actor.kind()==ActorKind.GOD?" AND kind='GAME_HEARD'":"")+" LIMIT 1",node.id(),actor.kind(),actor.id());var ar=aq.executeQuery()){if(!ar.next())return null;}}
            String disclosure=sha256(JSON.toJson(List.of(scope.speaker(),new TreeSet<>(full),context.get("recordingPolicy").getAsString(),
                    context.get("memoryMode").getAsString(),rows.getString(12),rows.getString(13),rows.getLong(7))));
            var binding=new Binding(node,rows.getLong(1),UUID.fromString(receipt),sourceHash,receiptHash,UUID.fromString(rows.getString(9)),disclosure,
                    Set.copyOf(actors),context.get("recordingPolicy").getAsString(),context.get("memoryMode").getAsString());
            return rows.next()?null:binding;
        }
    }
    private static Candidate parse(String payload){
        var object=JsonParser.parseString(payload).getAsJsonObject();keys(object,Set.of("layer","kind","quotes","links"));
        if(object.getAsJsonArray("quotes").isEmpty()||object.getAsJsonArray("quotes").size()>8||object.getAsJsonArray("links").size()>2)
            throw new IllegalArgumentException("INTERPRETATION_SHAPE");
        var quotes=new ArrayList<Quote>();var links=new ArrayList<Link>();
        for(var value:object.getAsJsonArray("quotes")){var q=value.getAsJsonObject();keys(q,Set.of("sourceAlias","text"));quotes.add(new Quote(q.get("sourceAlias").getAsString(),q.get("text").getAsString()));}
        for(var value:object.getAsJsonArray("links")){var l=value.getAsJsonObject();keys(l,Set.of("newerAlias","olderAlias","relation"));links.add(new Link(l.get("newerAlias").getAsString(),l.get("olderAlias").getAsString(),Relation.valueOf(l.get("relation").getAsString())));}
        var candidate=new Candidate(Layer.valueOf(object.get("layer").getAsString()),ClaimKind.valueOf(object.get("kind").getAsString()),quotes,links);
        // The writer stores this exact canonical DTO. Reject duplicate keys, coerced JSON types,
        // unknown fields or non-canonical payloads even if somebody changed their stored hash too.
        if(!JSON.toJson(candidate).equals(payload))throw new IllegalArgumentException("INTERPRETATION_CANONICAL_PAYLOAD");
        return candidate;
    }
    private static void keys(JsonObject object,Set<String> keys){if(!object.keySet().equals(keys))throw new IllegalArgumentException("INTERPRETATION_KEYS");}
    /** Explicit wire-shaped values: Gson must not reflect into java.time.Instant on the module path. */
    static int wireByteSize(InterpretationReadRecords.Entry entry){
        var quotes=entry.quotes().stream().map(q->Map.of("sourceAlias",q.sourceAlias(),"messageId",q.messageId().toString(),
                "actualActor",Map.of("kind",q.actualActor().kind().name(),"id",q.actualActor().id()),
                "occurredAt",q.occurredAt().toString(),"text",q.text())).toList();
        var inputs=entry.inputs().stream().map(c->Map.of("sourceAlias",c.sourceAlias(),"messageId",c.messageId().toString(),
                "coveredCharacters",c.coveredCharacters(),"totalCharacters",c.totalCharacters())).toList();
        var links=entry.links().stream().map(l->Map.of("newerAlias",l.newerAlias(),"olderAlias",l.olderAlias(),"relation",l.relation().name())).toList();
        return JSON.toJson(Map.of("memoryId",entry.memoryId().toString(),"layer",entry.layer().name(),"kind",entry.kind().name(),
                "extractorVersion",entry.extractorVersion(),"authority",entry.authority().name(),"quotes",quotes,"links",links,"inputs",inputs))
                .getBytes(StandardCharsets.UTF_8).length;
    }
    private static Set<String> strings(JsonArray array){var values=new HashSet<String>();array.forEach(v->values.add(v.getAsString()));return Set.copyOf(values);}
    private PreparedStatement prepare(String sql,Object...values)throws SQLException {
        if(expired())throw new SQLException("INTERPRETATION_READ_BUDGET",null,9);
        var statement=db.prepareStatement(sql);statement.setQueryTimeout(1);
        for(int i=0;i<values.length;i++){Object value=values[i];if(value instanceof Number n)statement.setLong(i+1,n.longValue());else statement.setString(i+1,value.toString());}return statement;
    }
    private boolean expired(){return System.nanoTime()>deadline||Thread.currentThread().isInterrupted();}
}
