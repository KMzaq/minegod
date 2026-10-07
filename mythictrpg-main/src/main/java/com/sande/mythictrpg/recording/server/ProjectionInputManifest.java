package com.sande.mythictrpg.recording.server;

import com.google.gson.Gson;
import com.sande.mythictrpg.recording.api.ProjectionRecords.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.sande.mythictrpg.recording.api.RecordingRecords.sha256;

/** Commit-time integrity, not knowledge or game authority. Never reconstructed from old stored rows. */
final class ProjectionInputManifest {
    private static final Gson JSON = new Gson();
    static final int MAX_BYTES = 65536;
    record Input(String alias, SourceRef source, UUID knowledgeReceiptId, String receiptHash,
                 UUID messageId, UUID conversationId, ActorRef actualActor, String observerGodId,
                 List<ActorRef> audience, String disclosureHash, String recordingPolicy, String memoryMode,
                 String occurredUtc, boolean excerpt, int coveredCharacters, int totalCharacters, String prefixHash) {
        Input {
            Objects.requireNonNull(source); Objects.requireNonNull(knowledgeReceiptId); Objects.requireNonNull(messageId);
            Objects.requireNonNull(conversationId); Objects.requireNonNull(actualActor); audience=List.copyOf(audience);
            check(alias!=null&&alias.matches("e[0-5]")); hash(receiptHash); hash(disclosureHash); hash(prefixHash);
            new ActorRef(ActorKind.GOD,observerGodId); Instant.parse(occurredUtc);
            check(source.owner().equals("room-publication-v2")&&source.revision()==1&&source.sourceId().equals(messageId.toString()));
            check(source.kind()==(actualActor.kind()==ActorKind.PLAYER?SourceKind.DIALOGUE_DIRECT:SourceKind.DERIVED_SPEECH));
            check(!audience.isEmpty()&&audience.size()<=256&&new HashSet<>(audience).size()==audience.size()
                    &&audience.equals(ordered(audience))&&audience.contains(new ActorRef(ActorKind.GOD,observerGodId)));
            check(Set.of("STANDARD","TEST_RECORDING").contains(recordingPolicy)&&Set.of("PERSONAL","RUMOR_TEST").contains(memoryMode));
            check(coveredCharacters>0&&coveredCharacters<=32768&&coveredCharacters<=totalCharacters
                    &&excerpt==(coveredCharacters<totalCharacters));
        }
        static Input from(Evidence e) {
            return new Input(e.alias(),e.source(),e.knowledgeReceiptId(),e.receiptHash(),e.messageId(),e.conversationId(),
                    e.actualActor(),e.observerGodId(),ordered(e.audience()),e.disclosureHash(),e.recordingPolicy(),e.memoryMode(),
                    e.occurredAt().toString(),e.excerpt(),e.text().length(),e.totalCharacters(),sha256(e.text()));
        }
    }
    record Output(UUID memoryId, Layer layer, ClaimKind kind, String payloadHash) {
        Output { Objects.requireNonNull(memoryId);Objects.requireNonNull(layer);Objects.requireNonNull(kind);hash(payloadHash); }
    }
    record Manifest(int version, UUID datasetId, String jobId, String extractorVersion, String observerGodId,
                    String disclosureHash, String targetAlias, List<Input> inputs, List<Output> outputs, long createdSequence) {
        Manifest {
            Objects.requireNonNull(datasetId);UUID.fromString(jobId);com.sande.mythictrpg.recording.api.ProjectionRecords.version(extractorVersion);
            new ActorRef(ActorKind.GOD,observerGodId);hash(disclosureHash);inputs=List.copyOf(inputs);outputs=List.copyOf(outputs);
            check(version==1&&createdSequence>0&&!inputs.isEmpty()&&inputs.size()<=6&&!outputs.isEmpty()&&outputs.size()<=3);
            check(inputs.equals(inputs.stream().sorted(Comparator.comparing(Input::alias)).toList()));
            check(outputs.equals(outputs.stream().sorted(Comparator.comparing(o->o.layer().name())).toList()));
            check(inputs.stream().map(Input::alias).distinct().count()==inputs.size()
                    &&inputs.stream().map(Input::messageId).distinct().count()==inputs.size()
                    &&inputs.stream().map(Input::knowledgeReceiptId).distinct().count()==inputs.size());
            check(outputs.stream().map(Output::layer).distinct().count()==outputs.size()
                    &&outputs.stream().anyMatch(o->o.layer()==Layer.EVENT));
            Input first=inputs.getFirst();check(inputs.stream().anyMatch(i->i.alias().equals(targetAlias)));
            for(int n=0;n<inputs.size();n++) { Input i=inputs.get(n);
                check(i.alias().equals("e"+n)&&i.source().datasetId().equals(datasetId)&&i.source().worldId().equals(first.source().worldId())
                        &&i.observerGodId().equals(observerGodId)&&i.disclosureHash().equals(disclosureHash)
                        &&i.conversationId().equals(first.conversationId())&&i.audience().equals(first.audience())
                        &&i.recordingPolicy().equals(first.recordingPolicy())&&i.memoryMode().equals(first.memoryMode())); }
            for(Output o:outputs)check(o.memoryId().equals(memoryId(datasetId,jobId,extractorVersion,o.layer())));
        }
        Input input(String alias) { return inputs.stream().filter(i->i.alias().equals(alias)).findFirst().orElseThrow(); }
        Input target() { return input(targetAlias); }
    }
    static UUID memoryId(UUID dataset,String job,String version,Layer layer) {
        return UUID.nameUUIDFromBytes((dataset+"/"+job+"/"+version+"/"+layer).getBytes(StandardCharsets.UTF_8));
    }
    static Manifest create(UUID dataset,String job,Work work,List<Candidate> candidates,long sequence) {
        var inputs=work.evidence().stream().map(Input::from).sorted(Comparator.comparing(Input::alias)).toList();
        var outputs=candidates.stream().map(c->new Output(memoryId(dataset,job,work.extractorVersion(),c.layer()),c.layer(),c.kind(),sha256(JSON.toJson(c))))
                .sorted(Comparator.comparing(o->o.layer().name())).toList();
        var target=inputs.stream().filter(i->i.alias().equals(work.targetAlias())).findFirst().orElseThrow();
        return new Manifest(1,dataset,job,work.extractorVersion(),target.observerGodId(),target.disclosureHash(),work.targetAlias(),inputs,outputs,sequence);
    }
    static String encode(Manifest manifest) { String json=JSON.toJson(manifest);check(json.getBytes(StandardCharsets.UTF_8).length<=MAX_BYTES);return json; }
    static Manifest decode(String json) {
        check(json!=null&&json.getBytes(StandardCharsets.UTF_8).length<=MAX_BYTES);
        Manifest manifest=JSON.fromJson(json,Manifest.class);check(encode(manifest).equals(json));return manifest;
    }
    static void insert(Connection db,Manifest manifest)throws SQLException {
        String json=encode(manifest);
        try(var q=prepare(db,"INSERT INTO projection_input_manifests VALUES(?,?,?,?,?,?)",manifest.datasetId(),manifest.jobId(),
                manifest.extractorVersion(),sha256(json),json,manifest.createdSequence())) {q.executeUpdate();}
    }
    /** Exact stored set check includes unquoted inputs and sibling layer metadata. Live ACL is checked separately. */
    static Optional<Manifest> load(Connection db,UUID dataset,String job,String version,long watermark)throws SQLException {
        Manifest manifest;
        try(var q=prepare(db,"SELECT p.manifest_json,p.manifest_hash,p.created_sequence,r.world_id FROM projection_input_manifests p"
                +" JOIN recording_meta r ON r.singleton=1 AND r.dataset_id=p.dataset_id"
                +" WHERE p.dataset_id=? AND p.job_id=? AND p.extractor_version=? AND p.created_sequence<=? AND length(p.manifest_json)<=65536",dataset,job,version,watermark);
            var rows=q.executeQuery()) {
            if(!rows.next())return Optional.empty();String json=rows.getString(1);
            if(!sha256(json).equals(rows.getString(2)))return Optional.empty();
            manifest=decode(json);
            if(!manifest.datasetId().equals(dataset)||!manifest.jobId().equals(job)||!manifest.extractorVersion().equals(version)
                    ||manifest.createdSequence()!=rows.getLong(3)||!manifest.inputs().getFirst().source().worldId().toString().equals(rows.getString(4)))return Optional.empty();
        }
        var remaining=new HashMap<UUID,Output>();manifest.outputs().forEach(o->remaining.put(o.memoryId(),o));
        try(var q=prepare(db,"SELECT id,layer,kind,payload_json,projection_hash,observer_god,disclosure_hash,created_sequence,status"
                +" FROM memories WHERE dataset_id=? AND job_id=? AND extractor_version=? AND length(payload_json)<=65536 LIMIT 4",dataset,job,version);var rows=q.executeQuery()) {
            while(rows.next()) {
                Output output=remaining.remove(UUID.fromString(rows.getString(1)));
                if(output==null||!output.layer().name().equals(rows.getString(2))||!output.kind().name().equals(rows.getString(3))
                        ||!output.payloadHash().equals(rows.getString(5))||!output.payloadHash().equals(sha256(rows.getString(4)))
                        ||!manifest.observerGodId().equals(rows.getString(6))||!manifest.disclosureHash().equals(rows.getString(7))
                        ||manifest.createdSequence()!=rows.getLong(8)||!rows.getString(9).equals("CANDIDATE"))return Optional.empty();
            }
        }
        if(!remaining.isEmpty())return Optional.empty();
        for(Output output:manifest.outputs()) {
            var inputs=new HashMap<String,Input>();manifest.inputs().forEach(i->inputs.put(i.alias(),i));
            try(var q=prepare(db,"SELECT ms.source_alias,ms.message_id,ms.knowledge_receipt_id,ms.source_hash,ms.receipt_hash,"
                    +"ms.actual_actor_kind,ms.actual_actor_id,ms.occurred_utc,ms.excerpt,ms.total_characters,ms.covered_characters,"
                    +"s.dataset_id,s.kind,s.owner,s.source_id,s.source_revision,s.source_hash FROM memory_sources ms"
                    +" LEFT JOIN source_refs s ON s.id=ms.source_ref WHERE ms.memory_id=? LIMIT 7",output.memoryId());var rows=q.executeQuery()) {
                while(rows.next()) {
                    Input i=inputs.remove(rows.getString(1));
                    if(i==null||!i.messageId().toString().equals(rows.getString(2))||!i.knowledgeReceiptId().toString().equals(rows.getString(3))
                            ||!i.source().hash().equals(rows.getString(4))||!i.receiptHash().equals(rows.getString(5))
                            ||!i.actualActor().kind().name().equals(rows.getString(6))||!i.actualActor().id().equals(rows.getString(7))
                            ||!i.occurredUtc().equals(rows.getString(8))||(i.excerpt()?1:0)!=rows.getInt(9)
                            ||i.totalCharacters()!=rows.getInt(10)||i.coveredCharacters()!=rows.getInt(11)
                            ||!i.source().datasetId().toString().equals(rows.getString(12))||!i.source().kind().name().equals(rows.getString(13))
                            ||!i.source().owner().equals(rows.getString(14))||!i.source().sourceId().equals(rows.getString(15))
                            ||i.source().revision()!=rows.getLong(16)||!i.source().hash().equals(rows.getString(17)))return Optional.empty();
                }
            }
            if(!inputs.isEmpty())return Optional.empty();
        }
        return Optional.of(manifest);
    }
    private static List<ActorRef> ordered(Collection<ActorRef> values) {
        return values.stream().sorted(Comparator.comparing((ActorRef a)->a.kind().name()).thenComparing(ActorRef::id)).toList();
    }
    private static void hash(String value) {check(value!=null&&value.matches("[a-f0-9]{64}"));}
    private static void check(boolean valid) {if(!valid)throw new IllegalArgumentException("PROJECTION_INPUT_MANIFEST");}
    private static PreparedStatement prepare(Connection db,String sql,Object...values)throws SQLException {
        var q=db.prepareStatement(sql);q.setQueryTimeout(1);for(int n=0;n<values.length;n++){Object value=values[n];if(value instanceof Number)q.setObject(n+1,value);else q.setString(n+1,value.toString());}return q;
    }
}
