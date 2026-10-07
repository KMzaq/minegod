package com.sande.mythictrpg.recording.server;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.NativeMemoryEvidence.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import static com.sande.mythictrpg.recording.api.RecordingRecords.sha256;

/** Game-only durable issuance and exact native speech provenance. No raw text duplication or AI truth storage. */
final class RecordedNativeEvidenceStore {
    private static final Gson JSON=new Gson();
    record Stored(Reference reference,Manifest manifest,long issuedSequence) { }
    record IssuedNative(NativeMemorySeal seal,List<RoomEvidenceReference> ownerReferences,boolean requiresProjection) {
        IssuedNative {ownerReferences=List.copyOf(ownerReferences);}
        IssuedNative(NativeMemorySeal seal,List<RoomEvidenceReference> owners){this(seal,owners,false);}
    }
    record ValidatedNative(Reference reference,Manifest manifest,List<RoomEvidenceReference> ownerReferences,boolean requiresProjection) {
        ValidatedNative {ownerReferences=List.copyOf(ownerReferences);}
        ValidatedNative(Reference ref,Manifest manifest,List<RoomEvidenceReference> owners){this(ref,manifest,owners,false);}
    }
    private RecordedNativeEvidenceStore() { }
    static Optional<IssuedNative> issue(Connection db,UUID world,RecordedRoomSearch.Scope scope,Request request,
            long watermark,Set<UUID> roots,UUID sealId,long sequence,long deadline)throws Exception {
        if(watermark<1||sequence<=watermark||roots.isEmpty()||roots.size()>NativeMemoryEvidence.MAX_ROOTS)return Optional.empty();
        var reader=new RecordedRoomSearch(db,scope,watermark,deadline);
        var closure=reader.contentAwareClosure(roots);if(closure.isEmpty())return Optional.empty();
        var dependencies=new ArrayList<SpeechDependency>();
        for(var node:closure.orElseThrow().nodes().values()){
            var value=bind(db,world,scope,watermark,node,deadline);if(value.isEmpty())return Optional.empty();dependencies.add(value.orElseThrow());
        }
        var manifest=new Manifest(NativeMemoryEvidence.VERSION,world,scope.dataset(),watermark,request.roomId(),request.revision(),request.turnId(),request.playerId(),
                scope.speaker(),scope.publicRoom(),scope.recordingPolicy(),scope.memoryMode(),scope.audience(),roots,dependencies);
        String json=NativeMemoryEvidence.encodeManifest(manifest),hash=NativeMemoryEvidence.hash(manifest);
        try(var q=prepare(db,deadline,"INSERT INTO native_memory_evidence VALUES(?,?,?,?,?,?,?)",sealId,scope.dataset(),world,hash,json,watermark,sequence)){q.executeUpdate();}
        var seal=NativeMemorySeal.unregistered(NativeMemoryEvidence.encode(new Reference(NativeMemoryEvidence.VERSION,world,scope.dataset(),sealId,hash)));
        return Optional.of(new IssuedNative(seal,closure.orElseThrow().ownerReferences(),closure.orElseThrow().requiresProjection()));
    }
    static Optional<ValidatedNative> validate(Connection db,UUID world,RecordedRoomSearch.Scope scope,Reference reference,long watermark,long deadline)throws Exception {
        var stored=load(db,world,scope,reference,watermark,Long.MAX_VALUE,deadline);if(stored.isEmpty())return Optional.empty();
        var value=stored.orElseThrow();
        var reader=new RecordedRoomSearch(db,scope,value.manifest().originalWatermark(),deadline);
        var closure=reader.contentAwareClosure(value.manifest().roots());
        return closure.isPresent()&&matches(db,scope,value.manifest(),closure.orElseThrow().nodes(),deadline)
                ?Optional.of(new ValidatedNative(reference,value.manifest(),closure.orElseThrow().ownerReferences(),closure.orElseThrow().requiresProjection())):Optional.empty();
    }
    /** Loading checks issuance, scope and chronology. Sources are checked separately inside the same SQLite snapshot. */
    static Optional<Stored> load(Connection db,UUID world,RecordedRoomSearch.Scope scope,Reference reference,
            long watermark,long childSequence,long deadline)throws Exception {
        if(!world.equals(reference.worldId())||!scope.dataset().equals(reference.datasetId()))return Optional.empty();
        try(var q=prepare(db,deadline,"SELECT n.manifest_json,n.manifest_hash,n.original_watermark,n.issued_sequence FROM native_memory_evidence n JOIN recording_meta m"
                +" ON m.singleton=1 AND n.world_id=m.world_id AND n.dataset_id=m.dataset_id"
                +" WHERE n.seal_id=? AND n.world_id=? AND n.dataset_id=? AND n.issued_sequence<=? AND n.issued_sequence<? AND length(CAST(n.manifest_json AS BLOB))<=65536",
                reference.sealId(),world,scope.dataset(),watermark,childSequence);var rows=q.executeQuery()){
            if(!rows.next())return Optional.empty();var manifest=NativeMemoryEvidence.decodeManifest(rows.getString(1));
            if(!reference.manifestHash().equals(rows.getString(2))||!reference.manifestHash().equals(NativeMemoryEvidence.hash(manifest))
                    ||!manifest.worldId().equals(world)||!manifest.datasetId().equals(scope.dataset())||manifest.originalWatermark()!=rows.getLong(3)
                    ||manifest.originalWatermark()>=rows.getLong(4)
                    ||!manifest.recordingPolicy().equals(scope.recordingPolicy())||!manifest.memoryMode().equals(scope.memoryMode())
                    ||scope.publicRoom()&&!manifest.publicRoom())return Optional.empty();
            for(var member:scope.audience())if((member.kind()==ActorKind.GOD||!manifest.publicRoom())&&!manifest.audience().contains(member))return Optional.empty();
            return Optional.of(new Stored(reference,manifest,rows.getLong(4)));
        }
    }
    static boolean matches(Connection db,RecordedRoomSearch.Scope scope,Manifest manifest,
            Map<UUID,RecordedRoomSearch.Node> closure,long deadline)throws Exception {
        var expected=new HashMap<UUID,SpeechDependency>();manifest.dependencies().forEach(d->expected.put(d.messageId(),d));
        if(!expected.keySet().equals(closure.keySet()))return false;
        // Preserve the exact original recipient's binding, while independently proving every current God.
        // A -> B reuse is lawful only when B belonged to the issued audience and actually heard all inputs at W.
        var audience=new HashSet<>(scope.audience());audience.add(new ActorRef(ActorKind.GOD,manifest.recipientGodId()));
        var bindingScope=new RecordedRoomSearch.Scope(scope.dataset(),manifest.recipientGodId(),audience,
                scope.publicRoom(),scope.recordingPolicy(),scope.memoryMode());
        for(var node:closure.values()){
            var actual=bind(db,manifest.worldId(),bindingScope,manifest.originalWatermark(),node,deadline);
            if(actual.isEmpty()||!actual.orElseThrow().equals(expected.get(node.id())))return false;
        }
        return true;
    }
    /** Full bindings for recipient, plus exact original-W receipt checks for EVERY current audience God. */
    static Optional<SpeechDependency> bind(Connection db,UUID world,RecordedRoomSearch.Scope scope,long watermark,
            RecordedRoomSearch.Node node,long deadline)throws Exception {
        if(node.sequence()>watermark||!RecordedRoomSearch.contentLeaves(node.evidence().stream().filter(r->!RecordedRoomSearch.nativeProof(r)).toList()))return Optional.empty();
        JsonObject context;String bodyHash,channel,policy;
        try(var q=prepare(db,deadline,"SELECT m.body_hash,x.context_json,c.channel,c.policy FROM messages m JOIN message_contexts x ON x.message_id=m.id"
                +" JOIN conversations c ON c.id=m.conversation_id WHERE m.id=? AND m.dataset_id=? AND m.ingest_sequence<=?",node.id(),scope.dataset(),watermark);var rows=q.executeQuery()){
            if(!rows.next())return Optional.empty();bodyHash=rows.getString(1);context=JsonParser.parseString(rows.getString(2)).getAsJsonObject();channel=rows.getString(3);policy=rows.getString(4);
        }
        if(!bodyHash.equals(sha256(node.body())))return Optional.empty();
        String sourceHash=sha256(bodyHash+":"+JSON.toJson(context));
        var full=strings(context.getAsJsonArray("fullAudience"));
        if(full.size()!=context.getAsJsonArray("fullAudience").size()||full.isEmpty()||full.size()>256)return Optional.empty();
        if(channel.equals("ROOM_PRIVATE"))for(var player:scope.audience())if(player.kind()==ActorKind.PLAYER
                &&!playerDispatched(db,scope.dataset(),node,player,watermark,deadline))return Optional.empty();
        SpeechDependency recipient=null;
        for(var god:scope.audience().stream().filter(a->a.kind()==ActorKind.GOD).toList()){
            String sql="SELECT s.kind,k.id,k.receipt_hash,k.audience_json,k.policy_revision,k.projection,"
                    +"d.id,d.receipt_hash,d.dispatched_utc,d.audience_revision,d.view_hash,v.plain_text,v.parts_json,w.source_version"
                    +" FROM source_refs s JOIN knowledge_receipts k ON k.source_ref=s.id"
                    +" JOIN deliveries d ON d.id=json_extract(k.projection,'$.deliveryReceiptId') AND d.message_id=s.source_id AND d.actor_id=k.god_id"
                    +" JOIN delivery_views v ON v.message_id=d.message_id AND v.view_hash=d.view_hash"
                    +" JOIN work_items w ON w.message_id=d.message_id AND w.receipt_id=d.id AND w.source_ref=s.id AND w.kind='ROOM_KNOWLEDGE_CAPTURED'"
                    +" WHERE s.dataset_id=? AND k.dataset_id=s.dataset_id AND d.dataset_id=s.dataset_id AND w.dataset_id=s.dataset_id"
                    +" AND s.owner='room-publication-v2' AND s.source_id=? AND s.source_revision=1 AND s.source_hash=? AND s.revoked=0 AND s.ingest_sequence<=?"
                    +" AND k.god_id=? AND k.acquisition='DIRECT_HEARD' AND d.actor_kind='GOD' AND d.kind='GAME_HEARD' AND d.status='SERVER_DISPATCHED'"
                    +" AND w.created_sequence<=? AND w.state<>'INVALIDATED'"
                    +" AND NOT EXISTS(SELECT 1 FROM knowledge_invalidations i WHERE i.dataset_id=k.dataset_id AND i.receipt_id=k.id)"
                    +" AND NOT EXISTS(SELECT 1 FROM invalidations i WHERE i.dataset_id=s.dataset_id AND i.kind=s.kind AND i.owner=s.owner AND i.source_id=s.source_id AND i.source_revision=s.source_revision)"
                    +" AND NOT EXISTS(SELECT 1 FROM source_refs n WHERE n.dataset_id=s.dataset_id AND n.kind=s.kind AND n.owner=s.owner AND n.source_id=s.source_id AND n.source_revision>s.source_revision) LIMIT 2";
            try(var q=prepare(db,deadline,sql,scope.dataset(),node.id(),sourceHash,watermark,god.id(),watermark);var rows=q.executeQuery()){
                if(!rows.next())return Optional.empty();var kind=SourceKind.valueOf(rows.getString(1));
                if(kind!=(node.speaker().kind()==ActorKind.PLAYER?SourceKind.DIALOGUE_DIRECT:SourceKind.DERIVED_SPEECH))return Optional.empty();
                var knowledge=UUID.fromString(rows.getString(2));String knowledgeHash=rows.getString(3),audience=rows.getString(4);long policyRevision=rows.getLong(5);
                var pointer=JsonParser.parseString(rows.getString(6)).getAsJsonObject();var delivery=UUID.fromString(rows.getString(7));String deliveryHash=rows.getString(8);
                var parts=new ArrayList<String>();JsonParser.parseString(rows.getString(13)).getAsJsonArray().forEach(p->parts.add(p.getAsString()));
                var view=new DeliveryView(rows.getString(12),parts);var indices=new TreeSet<Integer>();
                try(var pq=prepare(db,deadline,"SELECT part_index,body FROM delivery_parts_resolved WHERE receipt_id=? ORDER BY part_index",delivery);var pr=pq.executeQuery()){
                    int index=0;while(pr.next()){if(index>=parts.size()||pr.getInt(1)!=index||!parts.get(index).equals(pr.getString(2)))return Optional.empty();indices.add(index++);}
                    if(indices.size()!=parts.size())return Optional.empty();
                }
                var data=List.of(delivery,god,"GAME_HEARD",Instant.parse(rows.getString(9)).toString(),rows.getLong(10),DeliveryStatus.SERVER_DISPATCHED,view,indices);
                if(!knowledge.equals(UUID.nameUUIDFromBytes((delivery+"/knowledge-v1").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        ||!JSON.toJson(context.getAsJsonArray("fullAudience")).equals(audience)||policyRevision!=rows.getLong(10)
                        ||!node.body().equals(view.plainText())||!view.hash().equals(rows.getString(11))
                        ||!deliveryHash.equals(sha256(JSON.toJson(data)))||!knowledgeHash.equals(sha256(sourceHash+":"+JSON.toJson(data)))
                        ||!(knowledge+":"+knowledgeHash).equals(rows.getString(14))
                        ||!pointer.keySet().equals(Set.of("messageId","deliveryReceiptId","viewHash","projectionKind"))
                        ||!node.id().toString().equals(pointer.get("messageId").getAsString())||!delivery.toString().equals(pointer.get("deliveryReceiptId").getAsString())
                        ||!view.hash().equals(pointer.get("viewHash").getAsString())||!"DIRECT_HEARD_POINTER".equals(pointer.get("projectionKind").getAsString()))return Optional.empty();
                String disclosure=sha256(JSON.toJson(List.of(god.id(),new TreeSet<>(full),context.get("recordingPolicy").getAsString(),
                        context.get("memoryMode").getAsString(),channel,policy,policyRevision)));
                if(god.id().equals(scope.speaker()))recipient=new SpeechDependency(node.id(),new SourceRef(world,scope.dataset(),kind,"room-publication-v2",node.id().toString(),1,sourceHash),
                        knowledge,knowledgeHash,delivery,deliveryHash,bodyHash,disclosure,node.parents());
                if(rows.next())return Optional.empty();
            }
        }
        return Optional.ofNullable(recipient);
    }
    private static boolean playerDispatched(Connection db,UUID dataset,RecordedRoomSearch.Node node,ActorRef player,long watermark,long deadline)throws Exception {
        // A delivery arriving after W cannot backdate the original private disclosure, even if planned audience already contained the player.
        try(var q=prepare(db,deadline,"SELECT d.id,d.receipt_hash,d.kind,d.dispatched_utc,d.audience_revision,d.view_hash,v.parts_json,w.source_version,v.plain_text"
                +" FROM deliveries d JOIN delivery_views v ON v.message_id=d.message_id AND v.view_hash=d.view_hash"
                +" JOIN work_items w ON w.message_id=d.message_id AND w.receipt_id=d.id AND w.kind='DELIVERY_CAPTURED'"
                +" WHERE d.dataset_id=? AND w.dataset_id=d.dataset_id AND d.message_id=? AND d.actor_kind='PLAYER' AND d.actor_id=?"
                +" AND d.status='SERVER_DISPATCHED' AND d.kind IN ('CHAT','HUD') AND w.created_sequence<=? AND w.state<>'INVALIDATED'"
                +" AND length(CAST(v.plain_text AS BLOB))<=2097152 AND length(CAST(v.parts_json AS BLOB))<=2097152 LIMIT 16",dataset,node.id(),player.id(),watermark);var rows=q.executeQuery()){
            while(rows.next()){
                UUID id=UUID.fromString(rows.getString(1));String hash=rows.getString(2);var parts=new ArrayList<String>();
                String displayed=rows.getString(9),kind=rows.getString(3);
                // The existing RoomDialogueEvent completeRecipients contract preserves the CHAT prefix;
                // HUD is exact logical speech, CHAT is an exact dispatched view ending in that speech.
                if(!(kind.equals("HUD")?displayed.equals(node.body()):displayed.endsWith(node.body())))continue;
                JsonParser.parseString(rows.getString(7)).getAsJsonArray().forEach(p->parts.add(p.getAsString()));
                var view=new DeliveryView(displayed,parts);var indices=new TreeSet<Integer>();boolean complete=true;
                try(var pq=prepare(db,deadline,"SELECT part_index,body FROM delivery_parts_resolved WHERE receipt_id=? ORDER BY part_index",id);var pr=pq.executeQuery()){
                    int index=0;while(pr.next()) {if(index>=parts.size()||pr.getInt(1)!=index||!parts.get(index).equals(pr.getString(2))){complete=false;break;}indices.add(index++);}
                }
                var data=List.of(id,player,kind,Instant.parse(rows.getString(4)).toString(),rows.getLong(5),DeliveryStatus.SERVER_DISPATCHED,view,indices);
                if(complete&&indices.size()==parts.size()&&view.hash().equals(rows.getString(6))
                        &&hash.equals(sha256(JSON.toJson(data)))&&(id+":"+hash).equals(rows.getString(8)))return true;
            }
            return false;
        }
    }
    private static Set<String> strings(JsonArray array){var result=new HashSet<String>();array.forEach(v->result.add(v.getAsString()));return result;}
    private static PreparedStatement prepare(Connection db,long deadline,String sql,Object...values)throws SQLException {
        if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted())throw new SQLException("NATIVE_EVIDENCE_TIMEOUT",null,9);
        var statement=db.prepareStatement(sql);statement.setQueryTimeout(1);for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i] instanceof UUID?values[i].toString():values[i]);return statement;
    }
}
