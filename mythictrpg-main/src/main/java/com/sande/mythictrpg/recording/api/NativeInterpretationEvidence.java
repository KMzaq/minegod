package com.sande.mythictrpg.recording.api;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.recording.api.ProjectionRecords.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Syntax-only contract for a game-issued interpretation proof; constructing it does not issue permission.
 * A matching persisted issuance, current DONE job, schema-10 input manifest, exact output and every live
 * source/receipt permission must be checked by a game issuer. Hashes and this codec cannot grant authority. */
public final class NativeInterpretationEvidence {
    public static final String KIND="RECORDED_NATIVE_INTERPRETATION_V1";
    public static final int VERSION=1,MAX_PAGES=8,MAX_CANDIDATES=64,MAX_INPUTS_PER_CANDIDATE=6;
    public static final int MAX_REFERENCE_BYTES=1024,MAX_MANIFEST_BYTES=65536;
    private static final Gson JSON=new Gson();
    private NativeInterpretationEvidence(){ }

    public record Reference(int version,UUID worldId,UUID datasetId,UUID sealId,String manifestHash){
        public Reference {if(version!=VERSION)throw invalid();Objects.requireNonNull(worldId);Objects.requireNonNull(datasetId);
            Objects.requireNonNull(sealId);digest(manifestHash);}
    }
    /** No free summary, relationship value, subject inference or gameplay effect. Payload remains CANDIDATE.
     * inputManifestHash binds the complete commit-time inputs (including unquoted context) and output set;
     * payloadHash binds the exact quote/link classification. Neither is sufficient without its real DB row. */
    public record CandidateBinding(UUID memoryId,UUID jobId,String observerGodId,String extractorVersion,
            Layer layer,ClaimKind kind,String payloadHash,String inputManifestHash,long createdSequence,Set<UUID> inputMessageIds){
        public CandidateBinding {
            Objects.requireNonNull(memoryId);Objects.requireNonNull(jobId);new ActorRef(ActorKind.GOD,observerGodId);
            ProjectionRecords.version(extractorVersion);Objects.requireNonNull(layer);Objects.requireNonNull(kind);
            digest(payloadHash);digest(inputManifestHash);inputMessageIds=Set.copyOf(inputMessageIds);
            if(createdSequence<1||inputMessageIds.isEmpty()||inputMessageIds.size()>MAX_INPUTS_PER_CANDIDATE
                    ||layer!=Layer.EVENT&&kind!=ClaimKind.DIALOGUE_EPISODE)throw invalid();
        }
        public InterpretationReadRecords.Authority authority(){return InterpretationReadRecords.Authority.CANDIDATE;}
    }
    /** Reuses the immutable source DAG shape, NOT a separately issued RAW seal or RAW authority.
     * The exact source roots are selected whole speech pages plus ALL inputs of selected whole candidate pages.
     * Runtime projectionGeneration is deliberately absent: it is a session fence, not a restart-stable version. */
    public record Manifest(int version,NativeMemoryEvidence.Manifest sources,Set<UUID> speechRoots,List<CandidateBinding> candidates){
        public Manifest {
            Objects.requireNonNull(sources);speechRoots=Set.copyOf(speechRoots);
            candidates=List.copyOf(candidates).stream().sorted(Comparator.comparing(c->c.memoryId().toString())).toList();
            if(version!=VERSION||speechRoots.size()>NativeMemoryEvidence.MAX_ROOTS||candidates.isEmpty()||candidates.size()>MAX_CANDIDATES)throw invalid();
            var identities=new HashSet<UUID>();var layers=new HashSet<String>();var jobs=new HashMap<UUID,CandidateBinding>();
            var roots=new HashSet<>(speechRoots);
            for(var candidate:candidates){
                if(!identities.add(candidate.memoryId())||!layers.add(candidate.jobId()+"/"+candidate.layer())
                        ||!candidate.observerGodId().equals(sources.recipientGodId())||candidate.createdSequence()>sources.originalWatermark())throw invalid();
                var prior=jobs.putIfAbsent(candidate.jobId(),candidate);
                if(prior!=null&&(!prior.extractorVersion().equals(candidate.extractorVersion())
                        ||!prior.inputManifestHash().equals(candidate.inputManifestHash())||prior.createdSequence()!=candidate.createdSequence()
                        ||!prior.inputMessageIds().equals(candidate.inputMessageIds())))throw invalid();
                roots.addAll(candidate.inputMessageIds());
            }
            if(!roots.equals(sources.roots()))throw invalid();
            bounded(JSON.toJson(manifestValues(version,sources,speechRoots,candidates)),MAX_MANIFEST_BYTES);
        }
    }
    public static RoomEvidenceReference encode(Reference reference){
        Objects.requireNonNull(reference);String payload=JSON.toJson(referenceValues(reference));bounded(payload,MAX_REFERENCE_BYTES);
        return new RoomEvidenceReference(KIND,payload);
    }
    public static Reference decode(RoomEvidenceReference reference){
        Objects.requireNonNull(reference);if(!KIND.equals(reference.kind()))throw invalid();bounded(reference.payload(),MAX_REFERENCE_BYTES);
        var value=object(reference.payload());keys(value,"version","worldId","datasetId","sealId","manifestHash");
        var parsed=new Reference(integer(value,"version"),uuid(value,"worldId"),uuid(value,"datasetId"),uuid(value,"sealId"),string(value,"manifestHash"));
        if(!encode(parsed).payload().equals(reference.payload()))throw invalid();return parsed;
    }
    public static String encodeManifest(Manifest manifest){
        Objects.requireNonNull(manifest);String value=JSON.toJson(manifestValues(manifest.version(),manifest.sources(),manifest.speechRoots(),manifest.candidates()));
        bounded(value,MAX_MANIFEST_BYTES);return value;
    }
    public static Manifest decodeManifest(String serialized){
        bounded(serialized,MAX_MANIFEST_BYTES);var value=object(serialized);keys(value,"version","sources","speechRoots","candidates");
        var sources=NativeMemoryEvidence.decodeManifest(JSON.toJson(value.getAsJsonObject("sources")));
        var candidates=new ArrayList<CandidateBinding>();
        for(var item:array(value,"candidates",MAX_CANDIDATES)){
            var candidate=item.getAsJsonObject();keys(candidate,"memoryId","jobId","observerGodId","extractorVersion","authority",
                    "layer","kind","payloadHash","inputManifestHash","createdSequence","inputMessageIds");
            if(!string(candidate,"authority").equals("CANDIDATE"))throw invalid();
            candidates.add(new CandidateBinding(uuid(candidate,"memoryId"),uuid(candidate,"jobId"),string(candidate,"observerGodId"),
                    string(candidate,"extractorVersion"),Layer.valueOf(string(candidate,"layer")),ClaimKind.valueOf(string(candidate,"kind")),
                    string(candidate,"payloadHash"),string(candidate,"inputManifestHash"),number(candidate,"createdSequence"),
                    uuids(candidate,"inputMessageIds",MAX_INPUTS_PER_CANDIDATE)));
        }
        var parsed=new Manifest(integer(value,"version"),sources,uuids(value,"speechRoots",NativeMemoryEvidence.MAX_ROOTS),candidates);
        if(!encodeManifest(parsed).equals(serialized))throw invalid();return parsed;
    }
    /** Integrity only: the game must also find the actual issuance and revalidate its current provenance. */
    public static String hash(Manifest manifest){return RecordingRecords.sha256(encodeManifest(manifest));}
    private static Map<String,Object> referenceValues(Reference reference){
        var value=new LinkedHashMap<String,Object>();value.put("version",reference.version());value.put("worldId",reference.worldId().toString());
        value.put("datasetId",reference.datasetId().toString());value.put("sealId",reference.sealId().toString());value.put("manifestHash",reference.manifestHash());return value;
    }
    private static Map<String,Object> manifestValues(int version,NativeMemoryEvidence.Manifest sources,Set<UUID> speech,List<CandidateBinding> candidates){
        var value=new LinkedHashMap<String,Object>();value.put("version",version);
        value.put("sources",JsonParser.parseString(NativeMemoryEvidence.encodeManifest(sources)));value.put("speechRoots",sorted(speech));
        value.put("candidates",candidates.stream().map(NativeInterpretationEvidence::candidateValues).toList());return value;
    }
    private static Map<String,Object> candidateValues(CandidateBinding candidate){
        var value=new LinkedHashMap<String,Object>();value.put("memoryId",candidate.memoryId().toString());value.put("jobId",candidate.jobId().toString());
        value.put("observerGodId",candidate.observerGodId());value.put("extractorVersion",candidate.extractorVersion());value.put("authority",candidate.authority().name());
        value.put("layer",candidate.layer().name());value.put("kind",candidate.kind().name());value.put("payloadHash",candidate.payloadHash());
        value.put("inputManifestHash",candidate.inputManifestHash());value.put("createdSequence",candidate.createdSequence());
        value.put("inputMessageIds",sorted(candidate.inputMessageIds()));return value;
    }
    private static List<String> sorted(Set<UUID> ids){return ids.stream().map(UUID::toString).sorted().toList();}
    private static JsonObject object(String value){return JsonParser.parseString(value).getAsJsonObject();}
    private static void keys(JsonObject value,String...fields){if(value==null||!value.keySet().equals(Set.of(fields)))throw invalid();}
    private static String string(JsonObject value,String key){var item=value.get(key);
        if(item==null||!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isString())throw invalid();return item.getAsString();}
    private static UUID uuid(JsonObject value,String key){return uuid(string(value,key));}
    private static UUID uuid(String text){var id=UUID.fromString(text);if(!id.toString().equals(text))throw invalid();return id;}
    private static long number(JsonObject value,String key){var item=value.get(key);
        if(item==null||!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isNumber()||!item.toString().matches("0|[1-9][0-9]*"))throw invalid();
        try{return Long.parseLong(item.toString());}catch(NumberFormatException ignored){throw invalid();}}
    private static int integer(JsonObject value,String key){long parsed=number(value,key);if(parsed>Integer.MAX_VALUE)throw invalid();return (int)parsed;}
    private static JsonArray array(JsonObject value,String key,int limit){var array=value.getAsJsonArray(key);if(array==null||array.size()>limit)throw invalid();return array;}
    private static Set<UUID> uuids(JsonObject value,String key,int limit){var ids=new HashSet<UUID>();for(var item:array(value,key,limit)){
        if(!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isString()||!ids.add(uuid(item.getAsString())))throw invalid();}return Set.copyOf(ids);}
    private static void digest(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    private static void bounded(String value,int limit){if(value==null||value.isBlank()||value.length()>limit||value.getBytes(StandardCharsets.UTF_8).length>limit)throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("INVALID_NATIVE_INTERPRETATION_EVIDENCE");}
}
