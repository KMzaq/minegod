package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.NativeInterpretationEvidence.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.sql.*;
import java.util.*;

/** Durable provenance of fallible candidate interpretations. Never certifies speech/world truth or gameplay authority. */
final class RecordedNativeInterpretationStore {
    record Stored(Reference reference,Manifest manifest,long issuedSequence) { }
    record IssuedInterpretation(NativeInterpretationSeal seal,List<RoomEvidenceReference> ownerReferences) {
        IssuedInterpretation {ownerReferences=List.copyOf(ownerReferences);}
    }
    record ValidatedInterpretation(Reference reference,Manifest manifest,List<RoomEvidenceReference> ownerReferences) {
        ValidatedInterpretation {ownerReferences=List.copyOf(ownerReferences);}
    }
    private RecordedNativeInterpretationStore() { }

    static Optional<IssuedInterpretation> issue(Connection db,UUID world,RecordedRoomSearch.Scope scope,Request request,
            long watermark,Set<UUID> speechRoots,List<InterpretationReadRecords.Entry> actualEntries,
            UUID sealId,long sequence,long deadline)throws Exception {
        if(watermark<1||sequence<=watermark||speechRoots.size()>NativeMemoryEvidence.MAX_ROOTS||actualEntries.isEmpty()
                ||actualEntries.size()>NativeInterpretationEvidence.MAX_CANDIDATES||!requestMatches(scope,request))return Optional.empty();
        var selected=new LinkedHashMap<UUID,InterpretationReadRecords.Entry>();
        for(var entry:actualEntries){var prior=selected.putIfAbsent(entry.memoryId(),entry);if(prior!=null&&!prior.equals(entry))return Optional.empty();}
        var interpretationReader=new RecordedInterpretationSearch(db,scope,watermark,deadline);
        var candidates=new ArrayList<CandidateBinding>();var roots=new HashSet<>(speechRoots);
        for(var entry:selected.values()) {
            var exact=interpretationReader.exact(entry.memoryId());
            if(exact.isEmpty()||!exact.orElseThrow().entry().equals(entry))return Optional.empty();
            candidates.add(exact.orElseThrow().binding());roots.addAll(exact.orElseThrow().binding().inputMessageIds());
            if(roots.size()>NativeMemoryEvidence.MAX_ROOTS)return Optional.empty();
        }
        var reader=new RecordedRoomSearch(db,scope,watermark,deadline);
        var closure=reader.contentAwareClosure(roots);if(closure.isEmpty())return Optional.empty();
        var dependencies=new ArrayList<NativeMemoryEvidence.SpeechDependency>();
        for(var node:closure.orElseThrow().nodes().values()) {
            var bound=RecordedNativeEvidenceStore.bind(db,world,scope,watermark,node,deadline);
            if(bound.isEmpty())return Optional.empty();dependencies.add(bound.orElseThrow());
        }
        var sources=new NativeMemoryEvidence.Manifest(NativeMemoryEvidence.VERSION,world,scope.dataset(),watermark,
                request.roomId(),request.revision(),request.turnId(),request.playerId(),scope.speaker(),scope.publicRoom(),
                scope.recordingPolicy(),scope.memoryMode(),scope.audience(),roots,dependencies);
        var manifest=new Manifest(NativeInterpretationEvidence.VERSION,sources,speechRoots,candidates);
        String json=NativeInterpretationEvidence.encodeManifest(manifest),hash=NativeInterpretationEvidence.hash(manifest);
        try(var q=prepare(db,deadline,"INSERT INTO native_interpretation_evidence VALUES(?,?,?,?,?,?,?)",
                sealId,scope.dataset(),world,hash,json,watermark,sequence)){q.executeUpdate();}
        var reference=new Reference(NativeInterpretationEvidence.VERSION,world,scope.dataset(),sealId,hash);
        return Optional.of(new IssuedInterpretation(NativeInterpretationSeal.unregistered(NativeInterpretationEvidence.encode(reference)),
                closure.orElseThrow().ownerReferences()));
    }
    static Optional<ValidatedInterpretation> validate(Connection db,UUID world,RecordedRoomSearch.Scope scope,
            Reference reference,long watermark,long deadline)throws Exception {
        var loaded=load(db,world,scope,reference,watermark,Long.MAX_VALUE,deadline);if(loaded.isEmpty())return Optional.empty();
        var manifest=loaded.orElseThrow().manifest();
        var reader=new RecordedRoomSearch(db,scope,manifest.sources().originalWatermark(),deadline);
        var closure=reader.contentAwareClosure(manifest.sources().roots());
        return closure.isPresent()&&matches(db,scope,manifest,closure.orElseThrow().nodes(),deadline)
                ?Optional.of(new ValidatedInterpretation(reference,manifest,closure.orElseThrow().ownerReferences())):Optional.empty();
    }
    /** Issuance/scope/chronology only. The caller MUST verify matches in the same snapshot before use. */
    static Optional<Stored> load(Connection db,UUID world,RecordedRoomSearch.Scope scope,Reference reference,
            long watermark,long childSequence,long deadline)throws Exception {
        if(!world.equals(reference.worldId())||!scope.dataset().equals(reference.datasetId()))return Optional.empty();
        try(var q=prepare(db,deadline,"SELECT n.manifest_json,n.manifest_hash,n.original_watermark,n.issued_sequence"
                +" FROM native_interpretation_evidence n JOIN recording_meta m ON m.singleton=1 AND n.world_id=m.world_id AND n.dataset_id=m.dataset_id"
                +" WHERE n.seal_id=? AND n.world_id=? AND n.dataset_id=? AND n.issued_sequence<=? AND n.issued_sequence<?"
                +" AND length(CAST(n.manifest_json AS BLOB))<=65536",reference.sealId(),world,scope.dataset(),watermark,childSequence);var rows=q.executeQuery()) {
            if(!rows.next())return Optional.empty();var manifest=NativeInterpretationEvidence.decodeManifest(rows.getString(1));var sources=manifest.sources();
            if(!reference.manifestHash().equals(rows.getString(2))||!reference.manifestHash().equals(NativeInterpretationEvidence.hash(manifest))
                    ||!sources.worldId().equals(world)||!sources.datasetId().equals(scope.dataset())
                    ||sources.originalWatermark()!=rows.getLong(3)||sources.originalWatermark()>=rows.getLong(4)
                    ||!sources.recipientGodId().equals(scope.speaker()) // Unlike RAW, this slice does not transfer A's private interpretation to B.
                    ||!sources.recordingPolicy().equals(scope.recordingPolicy())||!sources.memoryMode().equals(scope.memoryMode())
                    ||scope.publicRoom()&&!sources.publicRoom())return Optional.empty();
            for(var member:scope.audience())if((member.kind()==ActorKind.GOD||!sources.publicRoom())&&!sources.audience().contains(member))return Optional.empty();
            return Optional.of(new Stored(reference,manifest,rows.getLong(4)));
        }
    }
    /** No reverse shared-input search. Validates the selected bindings and their full original source closure only. */
    static boolean matches(Connection db,RecordedRoomSearch.Scope scope,Manifest manifest,
            Map<UUID,RecordedRoomSearch.Node> closure,long deadline)throws Exception {
        if(!manifest.sources().recipientGodId().equals(scope.speaker())
                ||!RecordedNativeEvidenceStore.matches(db,scope,manifest.sources(),closure,deadline))return false;
        var reader=new RecordedInterpretationSearch(db,scope,manifest.sources().originalWatermark(),deadline);
        for(var expected:manifest.candidates()){
            var actual=reader.exact(expected.memoryId());
            if(actual.isEmpty()||!actual.orElseThrow().binding().equals(expected))return false;
        }
        return true;
    }
    private static boolean requestMatches(RecordedRoomSearch.Scope scope,Request request) {
        if(!request.speakerGodId().toString().equals(scope.speaker())||request.publicRoom()!=scope.publicRoom())return false;
        var audience=new HashSet<ActorRef>();request.godIds().forEach(g->audience.add(new ActorRef(ActorKind.GOD,g.toString())));
        request.audiencePlayerIds().forEach(p->audience.add(new ActorRef(ActorKind.PLAYER,p.toString())));
        return audience.equals(scope.audience());
    }
    private static PreparedStatement prepare(Connection db,long deadline,String sql,Object...values)throws SQLException {
        if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted())throw new SQLException("NATIVE_INTERPRETATION_TIMEOUT",null,9);
        var q=db.prepareStatement(sql);q.setQueryTimeout(1);for(int i=0;i<values.length;i++)q.setObject(i+1,values[i] instanceof UUID?values[i].toString():values[i]);return q;
    }
}
