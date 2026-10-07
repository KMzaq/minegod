package com.sande.mythictrpg.recording.api;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.recording.api.NativeInterpretationEvidence.*;
import com.sande.mythictrpg.recording.api.ProjectionRecords.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Pure syntax/immutable contract only. No SQL issuance, permission, model, server or retrieval activation. */
public final class NativeInterpretationEvidenceTest {
    private static final Gson JSON=new Gson();
    private static final UUID WORLD=UUID.fromString("abcdef01-2345-4678-9abc-def012345678"),DATASET=UUID.randomUUID(),PLAYER=UUID.randomUUID();
    private static final UUID ROOM=UUID.randomUUID(),TURN=UUID.randomUUID(),A=UUID.randomUUID(),B=UUID.randomUUID(),C=UUID.randomUUID();
    private static final String GOD="test:athena",HASH="a".repeat(64);
    private static final Set<ActorRef> AUDIENCE=Set.of(new ActorRef(ActorKind.GOD,GOD),new ActorRef(ActorKind.PLAYER,PLAYER.toString()));
    private static int checks;
    private static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    private static void rejects(Runnable action,String reason){checks++;try{action.run();}catch(RuntimeException expected){return;}throw new AssertionError(reason);}
    private static NativeMemoryEvidence.SpeechDependency dependency(UUID id,Set<UUID> parents){
        return new NativeMemoryEvidence.SpeechDependency(id,new SourceRef(WORLD,DATASET,SourceKind.DIALOGUE_DIRECT,"room-publication-v2",id.toString(),1,HASH),
                named(id+"/knowledge"),"b".repeat(64),named(id+"/delivery"),"c".repeat(64),"d".repeat(64),"e".repeat(64),parents);
    }
    private static UUID named(String name){return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));}
    private static NativeMemoryEvidence.Manifest sources(Set<UUID> roots,List<NativeMemoryEvidence.SpeechDependency> dependencies){
        return new NativeMemoryEvidence.Manifest(1,WORLD,DATASET,100,ROOM,3,TURN,PLAYER,GOD,false,"STANDARD","PERSONAL",AUDIENCE,roots,dependencies);
    }
    private static CandidateBinding binding(UUID memory,UUID job,Layer layer,ClaimKind kind,Set<UUID> inputs){
        return new CandidateBinding(memory,job,GOD,"extractor-v2",layer,kind,HASH,"f".repeat(64),40,inputs);
    }
    private static Manifest fixture(){
        return new Manifest(1,sources(Set.of(A,B,C),List.of(dependency(C,Set.of(B)),dependency(B,Set.of(A)),dependency(A,Set.of()))),Set.of(C),
                List.of(binding(UUID.randomUUID(),UUID.randomUUID(),Layer.EVENT,ClaimKind.CORRECTION_OR_EXPLANATION,Set.of(A,B))));
    }
    private static JsonObject object(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private static String mutate(String text,Consumer<JsonObject> change){var value=object(text);change.accept(value);return JSON.toJson(value);}
    private static JsonObject candidate(JsonObject manifest){return manifest.getAsJsonArray("candidates").get(0).getAsJsonObject();}
    public static void main(String[] args){
        roundTrip();strictReferences();strictManifests();bindingAndClosure();bounds();noAuthority();
        System.out.println("NativeInterpretationEvidenceTest: "+checks+" checks passed; codec/default-denial only");
    }
    private static void roundTrip(){
        var value=fixture();String encoded=NativeInterpretationEvidence.encodeManifest(value);
        check(NativeInterpretationEvidence.decodeManifest(encoded).equals(value),"typed candidate/source manifest roundtrips all fields");
        check(NativeInterpretationEvidence.hash(value).equals(RecordingRecords.sha256(encoded)),"checksum includes sources, full-input hash and candidate payload identity");
        var ref=new Reference(1,WORLD,DATASET,UUID.randomUUID(),NativeInterpretationEvidence.hash(value));var portable=NativeInterpretationEvidence.encode(ref);
        check(NativeInterpretationEvidence.decode(portable).equals(ref)&&portable.kind().equals(NativeInterpretationEvidence.KIND),"separate kind and canonical compact pointer");
        check(portable.payload().getBytes(StandardCharsets.UTF_8).length<=1024&&encoded.getBytes(StandardCharsets.UTF_8).length<=65536,"reference and manifest byte ceilings");
        check(value.candidates().getFirst().authority()==InterpretationReadRecords.Authority.CANDIDATE,"authority cannot be selected by caller");
        check(object(encoded).keySet().equals(Set.of("version","sources","speechRoots","candidates")),"only source graph and candidate fingerprints, no free summary or affinity");
        check(!encoded.contains("projectionGeneration")&&!encoded.contains("summaryText")&&!encoded.contains("reward"),"runtime fence and gameplay facts are not durable authority fields");
        var first=binding(UUID.randomUUID(),UUID.randomUUID(),Layer.EVENT,ClaimKind.JOKE,Set.of(A,B));
        var second=binding(UUID.randomUUID(),UUID.randomUUID(),Layer.EVENT,ClaimKind.CONDITIONAL,Set.of(A,B));
        var left=new Manifest(1,value.sources(),Set.of(C),List.of(first,second));var right=new Manifest(1,value.sources(),new LinkedHashSet<>(Set.of(C)),List.of(second,first));
        check(left.equals(right)&&NativeInterpretationEvidence.encodeManifest(left).equals(NativeInterpretationEvidence.encodeManifest(right)),"UUID lexical ordering makes candidate ordering canonical");
        var inputs=new HashSet<>(Set.of(A,B));var copied=binding(UUID.randomUUID(),UUID.randomUUID(),Layer.EVENT,ClaimKind.SPEAKER_CLAIM,inputs);inputs.clear();
        check(copied.inputMessageIds().equals(Set.of(A,B)),"input source set snapshots caller mutation");
        var speech=new HashSet<>(Set.of(C));var candidates=new ArrayList<>(List.of(copied));var immutable=new Manifest(1,value.sources(),speech,candidates);speech.clear();candidates.clear();
        check(immutable.speechRoots().equals(Set.of(C))&&immutable.candidates().size()==1,"manifest snapshots caller collections");
        rejects(()->immutable.speechRoots().clear(),"speech roots immutable");rejects(()->immutable.candidates().clear(),"candidate list immutable");
        rejects(()->copied.inputMessageIds().clear(),"all-input set immutable");
        var changed=new CandidateBinding(copied.memoryId(),copied.jobId(),GOD,copied.extractorVersion(),copied.layer(),copied.kind(),"0".repeat(64),copied.inputManifestHash(),copied.createdSequence(),copied.inputMessageIds());
        check(!NativeInterpretationEvidence.hash(immutable).equals(NativeInterpretationEvidence.hash(new Manifest(1,value.sources(),Set.of(C),List.of(changed)))),"payload changes alter manifest integrity binding");
    }
    private static void strictReferences(){
        var portable=NativeInterpretationEvidence.encode(new Reference(1,WORLD,DATASET,UUID.randomUUID(),HASH));
        rejects(()->NativeInterpretationEvidence.decode(new RoomEvidenceReference(NativeMemoryEvidence.KIND,portable.payload())),"raw speech kind cannot be promoted to interpretation proof");
        rejects(()->NativeMemoryEvidence.decode(portable),"interpretation kind cannot be demoted to raw speech proof");
        for(Consumer<JsonObject> change:List.<Consumer<JsonObject>>of(v->v.addProperty("version",2),v->v.addProperty("version","1"),v->v.addProperty("version",1.0),
                v->v.addProperty("manifestHash","A".repeat(64)),v->v.addProperty("worldId",WORLD.toString().toUpperCase(Locale.ROOT)),
                v->v.addProperty("current",true),v->v.remove("sealId"),v->v.add("datasetId",JsonNull.INSTANCE)))
            rejects(()->NativeInterpretationEvidence.decode(new RoomEvidenceReference(NativeInterpretationEvidence.KIND,mutate(portable.payload(),change))),"reference shape/type/version/hash canonical");
        rejects(()->NativeInterpretationEvidence.decode(new RoomEvidenceReference(NativeInterpretationEvidence.KIND,portable.payload()+" ")),"reference trailing whitespace rejected");
        rejects(()->NativeInterpretationEvidence.decode(new RoomEvidenceReference(NativeInterpretationEvidence.KIND,"{\"version\":1,"+portable.payload().substring(1))),"duplicate reference keys rejected");
        rejects(()->NativeInterpretationEvidence.decode(new RoomEvidenceReference(NativeInterpretationEvidence.KIND,"가".repeat(400))),"UTF-8 reference bound before parsing");
    }
    private static void strictManifests(){
        String good=NativeInterpretationEvidence.encodeManifest(fixture());
        for(Consumer<JsonObject> change:List.<Consumer<JsonObject>>of(v->v.addProperty("version",2),v->v.addProperty("version","1"),v->v.addProperty("trusted",true),
                v->v.remove("sources"),v->v.getAsJsonObject("sources").addProperty("worldId",UUID.randomUUID().toString()),
                v->v.getAsJsonObject("sources").addProperty("originalWatermark",0),v->v.getAsJsonObject("sources").addProperty("memoryMode","OFF"),
                v->candidate(v).addProperty("authority","FACT"),v->candidate(v).addProperty("authority","CANDIDATE_ACTION"),
                v->candidate(v).addProperty("kind","GAME_EXECUTED"),v->candidate(v).addProperty("layer","AUTHORITATIVE"),
                v->candidate(v).addProperty("summaryText","false new world fact"),v->candidate(v).addProperty("affinity",100),
                v->candidate(v).addProperty("observerGodId","test:other"),v->candidate(v).addProperty("createdSequence",101),
                v->candidate(v).addProperty("createdSequence",0),v->candidate(v).addProperty("createdSequence","40"),v->candidate(v).addProperty("createdSequence",40.0),
                v->candidate(v).addProperty("createdSequence",new java.math.BigInteger("9223372036854775808")),
                v->candidate(v).addProperty("extractorVersion","illegal version"),v->candidate(v).addProperty("payloadHash","x".repeat(64)),
                v->candidate(v).addProperty("inputManifestHash","F".repeat(64)),v->candidate(v).remove("inputManifestHash"),
                v->candidate(v).add("jobId",JsonNull.INSTANCE),v->v.getAsJsonArray("candidates").add(candidate(v)),
                v->candidate(v).getAsJsonArray("inputMessageIds").add(candidate(v).getAsJsonArray("inputMessageIds").get(0)),
                v->v.getAsJsonArray("speechRoots").add(v.getAsJsonArray("speechRoots").get(0))))
            rejects(()->NativeInterpretationEvidence.decodeManifest(mutate(good,change)),"malformed/extra/authority/unsupported fields fail closed");
        rejects(()->NativeInterpretationEvidence.decodeManifest(good+"{}"),"multiple JSON documents rejected");
        rejects(()->NativeInterpretationEvidence.decodeManifest(" "+good),"noncanonical whitespace rejected");
        rejects(()->NativeInterpretationEvidence.decodeManifest("{\"version\":1,"+good.substring(1)),"duplicate manifest keys rejected");
        rejects(()->NativeInterpretationEvidence.decodeManifest(good.replace("\"authority\":\"CANDIDATE\"","\"authority\":\"FACT\",\"authority\":\"CANDIDATE\"")),"duplicate candidate authority cannot hide FACT");
        rejects(()->NativeInterpretationEvidence.decodeManifest(good.replace("\"sources\":{\"version\":1,","\"sources\":{\"version\":1,\"version\":1,")),"nested source duplicate keys rejected");
    }
    private static void bindingAndClosure(){
        var value=fixture();var first=value.candidates().getFirst();
        rejects(()->new Manifest(1,value.sources(),Set.of(),value.candidates()),"unaccounted raw source root rejected");
        rejects(()->new Manifest(1,value.sources(),Set.of(C),List.of(binding(first.memoryId(),first.jobId(),first.layer(),first.kind(),Set.of(A)))),"missing complete candidate input cannot shrink source root binding");
        rejects(()->new Manifest(1,value.sources(),Set.of(C),List.of(binding(first.memoryId(),first.jobId(),first.layer(),first.kind(),Set.of(A,B,UUID.randomUUID())))),"input outside verified source closure rejected");
        rejects(()->new Manifest(1,value.sources(),Set.of(C,UUID.randomUUID()),value.candidates()),"extra unbound speech root rejected");
        rejects(()->new Manifest(1,value.sources(),Set.of(A,B,C),List.of()),"no candidate cannot impersonate typed proof");
        rejects(()->binding(UUID.randomUUID(),UUID.randomUUID(),Layer.SUMMARY,ClaimKind.JOKE,Set.of(A)) ,"non-event summaries cannot carry arbitrary event classification");
        rejects(()->binding(UUID.randomUUID(),UUID.randomUUID(),Layer.EVENT,ClaimKind.JOKE,Set.of()),"empty input fingerprints not valid");
        var candidates=new ArrayList<>(value.candidates());candidates.add(binding(UUID.randomUUID(),first.jobId(),first.layer(),first.kind(),first.inputMessageIds()));
        rejects(()->new Manifest(1,value.sources(),Set.of(C),candidates),"same job/layer duplicate output rejected");
        var companion=binding(UUID.randomUUID(),first.jobId(),Layer.SUMMARY,ClaimKind.DIALOGUE_EPISODE,first.inputMessageIds());
        var pair=new Manifest(1,value.sources(),Set.of(C),List.of(first,companion));check(pair.candidates().size()==2,"one committed job may have separate grounded layers");
        for(int mode=0;mode<4;mode++){
            var changed=new CandidateBinding(companion.memoryId(),companion.jobId(),GOD,mode==0?"extractor-other":companion.extractorVersion(),companion.layer(),companion.kind(),
                    companion.payloadHash(),mode==1?"0".repeat(64):companion.inputManifestHash(),mode==2?41:companion.createdSequence(),mode==3?Set.of(A):companion.inputMessageIds());
            rejects(()->new Manifest(1,value.sources(),Set.of(C),List.of(first,changed)),"same job must preserve one commit-time version/input manifest/sequence/full-input set");
        }
        var candidateOnly=new Manifest(1,sources(Set.of(A,B),List.of(dependency(A,Set.of()),dependency(B,Set.of(A)))),Set.of(),List.of(first));
        check(candidateOnly.speechRoots().isEmpty()&&candidateOnly.sources().roots().equals(Set.of(A,B)),"candidate-only selection still binds every raw input and ancestor");
    }
    private static void bounds(){
        var ids=new ArrayList<UUID>();for(int i=0;i<64;i++)ids.add(named("large-source-"+i));
        rejects(()->binding(UUID.randomUUID(),UUID.randomUUID(),Layer.EVENT,ClaimKind.JOKE,new HashSet<>(ids.subList(0,7))),"at most six extraction inputs");
        var root=ids.getFirst();var one=sources(Set.of(root),List.of(dependency(root,Set.of())));var candidates=new ArrayList<CandidateBinding>();
        for(int i=0;i<64;i++)candidates.add(binding(named("memory-"+i),named("job-"+i),Layer.EVENT,ClaimKind.JOKE,Set.of(root)));
        var bounded=new Manifest(1,one,Set.of(),candidates);check(bounded.candidates().size()==64,"bounded 64 candidate maximum supported when total bytes fit");
        candidates.add(binding(UUID.randomUUID(),UUID.randomUUID(),Layer.EVENT,ClaimKind.JOKE,Set.of(root)));
        rejects(()->new Manifest(1,one,Set.of(),candidates),"65 candidate descriptors rejected");
        var all=sources(new HashSet<>(ids),ids.stream().map(id->dependency(id,Set.of())).toList());
        var heavy=candidates.subList(0,64);
        rejects(()->new Manifest(1,all,new HashSet<>(ids),heavy),"source and candidate envelopes share one 64KiB limit, no partial manifest");
        rejects(()->NativeInterpretationEvidence.decodeManifest(" ".repeat(65537)),"oversize manifest rejected before parse");
        rejects(()->NativeInterpretationEvidence.decodeManifest("가".repeat(23000)),"UTF-8 manifest bound independent of chars");
    }
    private static void noAuthority(){
        var reference=NativeInterpretationEvidence.encode(new Reference(1,WORLD,DATASET,UUID.randomUUID(),NativeInterpretationEvidence.hash(fixture())));
        var a=NativeInterpretationSeal.unregistered(reference);var b=NativeInterpretationSeal.unregistered(reference);
        check(a!=b&&!a.equals(b)&&a.reference().equals(b.reference()),"same syntax does not create registered game identity");
        check(!a.toString().contains(reference.payload()),"opaque diagnostic never logs source descriptors");
        rejects(()->NativeMemorySeal.unregistered(reference),"interpretation pointer cannot mint a raw seal");
        var rawRef=NativeMemoryEvidence.encode(new NativeMemoryEvidence.Reference(1,WORLD,DATASET,UUID.randomUUID(),HASH));
        rejects(()->NativeInterpretationSeal.unregistered(rawRef),"raw pointer cannot mint an interpretation seal");
        MemoryReadSession port=new MemoryReadSession(){
            public CompletableFuture<Page> query(Query q,Optional<Cursor> c,Budget budget){return CompletableFuture.completedFuture(new Page(MemoryReadSession.Status.UNAVAILABLE,List.of(),Optional.empty()));}
            public boolean current(Page page){return false;}
        };
        check(port.sealInterpretations(List.of(),List.of(),List.of()).join().isEmpty()&&!port.current(a),"default API always unsupported and unregistered token never current");
        check(port.sealInterpretations(List.of(new MemoryReadSession.Page(MemoryReadSession.Status.FOUND,List.of(),Optional.empty())),List.of(),
                List.of(new InterpretationReadRecords.Page(MemoryReadSession.Status.FOUND,List.of(),Optional.empty()))).join().isEmpty(),"caller fabricated page objects do not activate default issuance");
        check(port.seal(List.of(),List.of()).join().isEmpty()&&!port.current(NativeMemorySeal.unregistered(rawRef)),"existing raw default behavior unchanged");
    }
}
