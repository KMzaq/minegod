package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.sande.mythictrpg.recording.server.RecordingRetrievalSettings.*;

/** Startup policy and real SQLite read gates. Only synthetic build data; no operating config or model work. */
public final class RecordingRetrievalSettingsTest {
    private static int checks;
    private static final String OWNER="retrieval-switch-fixture",GOD="test:reader";
    private static final UUID PLAYER=new UUID(0,1);
    private static final WorldRecordingService.CutoverBoundary CUTOVER=new WorldRecordingService.CutoverBoundary("retrieval-switch-test",Map.of(OWNER,0L));
    private static WorldRecordingService.CutoverBoundary confirmed(long cursor){
        return new WorldRecordingService.CutoverBoundary("retrieval-switch-test",Map.of(OWNER,cursor));
    }
    private static final MemoryReadSession.Query QUERY=new MemoryReadSession.Query("test",Optional.empty(),Optional.empty());
    private static final MemoryReadSession.Budget BUDGET=new MemoryReadSession.Budget(1,4096);
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static <T>T await(CompletionStage<T> future)throws Exception{return future.toCompletableFuture().get(10,TimeUnit.SECONDS);}
    private static RecordingSettings archive(RecordingSettings.Mode mode){return new RecordingSettings(mode,128_000_000,2_000_000,.90,.95);}
    private static Policy explicit(RecordingSettings.Mode archive,Mode mode){return resolve(archive,Optional.of(new RecordingRetrievalSettings(mode)));}
    public static void main(String[] args)throws Exception {
        Path parent=(args.length==0?Path.of("build/recording-retrieval-test"):Path.of(args[0])).toAbsolutePath().normalize();
        if(!parent.toString().replace('\\','/').contains("/build/"))throw new IllegalArgumentException("TEST_REQUIRES_BUILD_DIRECTORY");
        Files.createDirectories(parent);Path root=Files.createTempDirectory(parent,"retrieval-");
        configuration(root.resolve("config"));matrix();storeGates(root.resolve("stores"));offNoFiles(root.resolve("off"));
        System.out.println("RecordingRetrievalSettingsTest: "+checks+" checks passed; fixtures="+root);
    }
    private static void configuration(Path directory)throws Exception {
        var file=directory.resolve("recording-retrieval.json");
        check(load(file).isEmpty()&&!Files.exists(directory),"missing new config stays absent and distinguishable from explicit LEGACY");
        Files.createDirectories(directory);
        for(var mode:Mode.values()){
            Files.writeString(file,"{\"schemaVersion\":1,\"retrievalMode\":\""+mode+"\"}");
            check(load(file).orElseThrow().retrievalMode()==mode,"all authored selections parsed without implicit activation");
        }
        for(var text:List.of("{}","null","[]","{\"schemaVersion\":1,\"retrievalMode\":\"ON\"}",
                "{\"schemaVersion\":1.1,\"retrievalMode\":\"SHADOW\"}","{\"schemaVersion\":\"1\",\"retrievalMode\":\"SHADOW\"}",
                "{\"schemaVersion\":1,\"retrievalMode\":true}","{\"schemaVersion\":1,\"retrievalMode\":\"SHADOW\",\"extra\":0}","x".repeat(4097))){
            Files.writeString(file,text);boolean rejected=false;try{load(file);}catch(IOException expected){rejected=true;}
            check(rejected,"malformed config rejected, not silently converted to legacy or compat shadow");
        }
        check(invalid().foregroundBlocked()&&invalid().requestedMode().isEmpty()&&!invalid().shadowReadsAllowed(),"invalid policy preserves unavailable identity");
        for(var mode:RecordingSettings.Mode.values()){
            var old=directory.resolve("recording-v2.json");
            var json="{\"schemaVersion\":2,\"archiveMode\":\""+mode+"\",\"worldRecordingLimitBytes\":128000000,\"maintenanceHeadroomBytes\":2000000,\"warningRatio\":0.90,\"deferBackgroundRatio\":0.95,\"overflowPolicy\":\"STOP_NEW_RECORDS\",\"automaticRawDeletion\":false,\"importLegacyTestData\":false}";
            Files.writeString(old,json);check(RecordingSettings.load(old).archiveMode()==mode&&Files.readString(old).equals(json),"schema2 archive still parses without rewrite");
        }
    }
    private static void matrix(){
        for(var archive:RecordingSettings.Mode.values()) {
            var missing=resolve(archive,Optional.empty());
            check(missing.shadowReadsAllowed()==(archive==RecordingSettings.Mode.SHADOW)&&!missing.foregroundBlocked(),"absent newfile preserves only old explicit shadow");
            check(missing.origin()==(archive==RecordingSettings.Mode.SHADOW?Origin.ARCHIVE_SCHEMA2_COMPAT:Origin.DEFAULT),"compat origin remains visible");
            for(var mode:Mode.values()) {
                var policy=explicit(archive,mode);
                check(policy.requestedMode().orElseThrow()==mode&&policy.origin()==Origin.EXPLICIT,"explicit request never rewritten");
                check(policy.shadowReadsAllowed()==(mode==Mode.SHADOW&&archive!=RecordingSettings.Mode.OFF),"independent native read policy");
                check(policy.foregroundBlocked()==(mode==Mode.NEW),"unsupported NEW never falls back to LEGACY");
                if(mode==Mode.NEW)check(policy.state()==State.BLOCKED_CONTRACT_NOT_READY,"NEW reports missing foreground/proof contract even with archive OFF");
            }
        }
    }
    private static void storeGates(Path base)throws Exception {
        Files.createDirectories(base);Path root=Files.createDirectory(base.resolve("record-only"));UUID world=UUID.randomUUID();
        var settings=archive(RecordingSettings.Mode.RECORD_ONLY);
        var store=await(WorldRecordingService.open(root,world,settings,CUTOVER));
        check(store.health().state()==WorldRecordingService.State.READY,"ordinary RECORD_ONLY store ready");
        UUID dataset=store.datasetId().orElseThrow();await(capture(store,1));readGate(store,false);
        check(!store.lexicalMaintenanceEnabled()&&await(store.pumpLexicalIndex()).state().equals("OFF"),"record-only does not start background index");
        await(store.closeAsync());
        store=await(WorldRecordingService.open(root,world,settings,confirmed(1),explicit(settings.archiveMode(),Mode.SHADOW)));
        check(store.datasetId().orElseThrow().equals(dataset)&&await(store.statistics()).sources()==1,"separate read selection preserves dataset/source history");
        readGate(store,true);
        check(!store.lexicalMaintenanceEnabled()&&await(store.pumpLexicalIndex()).state().equals("OFF"),"explicit SHADOW plus RECORD_ONLY uses raw fallback, leaves index background off");
        await(store.closeAsync());
        store=await(WorldRecordingService.open(root,world,settings,confirmed(1),explicit(settings.archiveMode(),Mode.NEW)));
        check(store.retrievalPolicy().foregroundBlocked()&&store.retrievalPolicy().state()==State.BLOCKED_CONTRACT_NOT_READY,"NEW remains explicitly blocked in actual store");
        readGate(store,false);await(capture(store,2));
        check(await(store.statistics()).sources()==2,"blocked retrieval does not stop existing archive writes");await(store.closeAsync());
        store=await(WorldRecordingService.open(root,world,settings,confirmed(2),explicit(settings.archiveMode(),Mode.LEGACY)));
        check(store.datasetId().orElseThrow().equals(dataset)&&await(store.statistics()).sources()==2,"latest committed game boundary reopens retained records without resetting cutover");
        await(store.closeAsync());

        root=Files.createDirectory(base.resolve("archive-shadow"));world=UUID.randomUUID();settings=archive(RecordingSettings.Mode.SHADOW);
        store=await(WorldRecordingService.open(root,world,settings,CUTOVER,explicit(settings.archiveMode(),Mode.LEGACY)));
        readGate(store,false);await(capture(store,1));dataset=store.datasetId().orElseThrow();
        check(store.lexicalMaintenanceEnabled()&&!await(store.pumpLexicalIndex()).state().equals("OFF"),"archive SHADOW plus explicit LEGACY preserves old background index policy");
        await(store.closeAsync());
        store=await(WorldRecordingService.open(root,world,settings,confirmed(1)));readGate(store,true);
        check(store.retrievalPolicy().origin()==Origin.ARCHIVE_SCHEMA2_COMPAT&&store.datasetId().orElseThrow().equals(dataset),"old API uses explicit old schema2 opt-in compat without new dataset");
        await(store.closeAsync());
        store=await(WorldRecordingService.open(root,world,settings,confirmed(1),invalid()));readGate(store,false);
        check(store.retrievalPolicy().foregroundBlocked()&&store.lexicalMaintenanceEnabled(),"invalid explicit read config is blocked without rewriting background/archive");
        check(await(store.statistics()).sources()==1,"invalid read policy never deletes/imports/replaces source state");await(store.closeAsync());
    }
    private static CompletionStage<WriteReceipt> capture(WorldRecordingService store,int cursor){
        var producer=store.registerProducer(OWNER,Set.of(),Set.of(SourceKind.ACTION_OBSERVED));
        return store.captureSource(producer,new SourceCapture(new SourceRef(store.worldId(),store.datasetId().orElseThrow(),
                SourceKind.ACTION_OBSERVED,OWNER,"event-"+cursor,1,RecordingRecords.sha256("event-"+cursor)),cursor,List.of()));
    }
    private static void readGate(WorldRecordingService store,boolean expected)throws Exception {
        check(store.shadowReadsEnabled()==expected,"stored policy controls native reads");
        var scope=new RecordedRoomSearch.Scope(store.datasetId().orElseThrow(),GOD,
                Set.of(new ActorRef(ActorKind.GOD,GOD),new ActorRef(ActorKind.PLAYER,PLAYER.toString())),false,"STANDARD","PERSONAL");
        var rumor=new RecordedRoomSearch.Scope(scope.dataset(),GOD,scope.audience(),false,"STANDARD","RUMOR_TEST");
        long w=store.health().highWatermark();
        var vector=new EmbeddingRecords.QueryVector(new EmbeddingRecords.ModelSpace("test:fake","a".repeat(64),1,EmbeddingRecords.ENCODER_VERSION),RecordingRecords.sha256(QUERY.text()),new float[]{1});
        var attempts=List.of(store.readRoom(scope,QUERY,BUDGET,w,RecordedRoomSearch.SearchPosition.initial()),
                store.readInterpretations(scope,List.of(),BUDGET,w,RecordedInterpretationSearch.Position.initial()),
                store.readSemantic(scope,QUERY,vector,BUDGET,w,RecordedSemanticSearch.Position.initial()),
                store.readObservations(scope,QUERY,BUDGET,w,RecordedObservationSearch.Position.initial()),
                store.readRumors(rumor,QUERY,BUDGET,w,RecordedRumorSearch.Position.initial()));
        for(var attempt:attempts){boolean success=false;try{await(attempt);success=true;}catch(ExecutionException rejected){
            check(rejected.getCause() instanceof IllegalStateException&&rejected.getCause().getMessage().equals("MEMORY_READ_DISABLED"),"bounded read denied before SQL, not an unrelated query failure");}
            check(success==expected,"all five native typed read families share independent gate");}
    }
    private static void offNoFiles(Path base)throws Exception {
        for(var mode:Mode.values()){
            Path path=base.resolve(mode.name());var settings=RecordingSettings.off();
            var store=await(WorldRecordingService.open(path,UUID.randomUUID(),settings,CUTOVER,explicit(settings.archiveMode(),mode)));
            check(store.health().state()==WorldRecordingService.State.OFF&&store.datasetId().isEmpty()&&!store.shadowReadsEnabled(),"archive OFF cannot be overridden by retrieval");
            check(!Files.exists(path),"archive OFF creates no world directory, manifest, dataset or config");await(store.closeAsync());
        }
    }
}
