package com.sande.mythictrpg.recording.server;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.recording.*;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.ProjectionRecords.*;
import com.sande.mythictrpg.recording.api.ProjectionRecords.Candidate;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Actual shared-writer SQLite fixtures. Public page/cancel identity is covered by the separate Session suite. */
public final class RecordedNativeInterpretationStoreTest {
    private static int checks;
    private static final Gson JSON=new Gson();
    private static final ActorRef PLAYER=new ActorRef(ActorKind.PLAYER,UUID.randomUUID().toString());
    private static final ActorRef STRANGER=new ActorRef(ActorKind.PLAYER,UUID.randomUUID().toString());
    private static final ActorRef A=new ActorRef(ActorKind.GOD,"test:a"),B=new ActorRef(ActorKind.GOD,"test:b");
    private static final Set<ActorRef> AUDIENCE=Set.of(PLAYER,A);
    private static final RecordingSettings SETTINGS=new RecordingSettings(RecordingSettings.Mode.SHADOW,128_000_000,2_000_000,.90,.95);
    private static final WorldRecordingService.CutoverBoundary BOUNDARY=new WorldRecordingService.CutoverBoundary("typed-interpretation-test",Map.of());
    private record Fixture(Path root,UUID world,WorldRecordingService store,ProducerCapability producer,UUID conversation) implements AutoCloseable {
        @Override public void close()throws Exception{await(store.closeAsync());}
    }
    private record Chain(UUID earlier,UUID promise,UUID cancellation,Work work,List<InterpretationReadRecords.Entry> entries,long watermark) { }
    public static void main(String[] args)throws Exception {
        Path parent=Path.of(args.length==0?"build/recorded-native-interpretation-store-test":args[0]).toAbsolutePath().normalize();
        if(!parent.toString().replace('\\','/').contains("/build/"))throw new IllegalArgumentException("TEST_REQUIRES_BUILD_DIRECTORY");
        Files.createDirectories(parent);Path root=Files.createTempDirectory(parent,"typed-seal-");
        roundtrip(root.resolve("roundtrip"));versionChange(root.resolve("version"));forgedAndQuota(root.resolve("forged"));
        for(String kind:List.of("sibling-link","unquoted-input","payload","manifest","future-issued","world"))tamper(root.resolve(kind),kind);
        migration(root.resolve("migration"));
        System.out.println("RecordedNativeInterpretationStoreTest: "+checks+" checks passed; fixtures="+root);
    }
    private static void roundtrip(Path root)throws Exception {
        UUID world;NativeInterpretationEvidence.Reference ref;Chain chain;
        try(var f=fixture(root,UUID.randomUUID())) {
            world=f.world();chain=chain(f);long generation=f.store().projectionGeneration();
            var issued=issue(f,chain,chain.entries(),Set.of(chain.promise())).orElseThrow();ref=NativeInterpretationEvidence.decode(issued.seal().reference());
            check(issued.ownerReferences().isEmpty(),"native candidate inputs do not fabricate external owner permission");
            check(f.store().projectionGeneration()==generation,"ordinary proof issuance does not invalidate its own projection generation");
            var stored=manifest(f,ref);var sources=stored.sources();
            check(stored.candidates().size()==3&&stored.speechRoots().equals(Set.of(chain.promise()))
                    &&sources.roots().equals(Set.of(chain.earlier(),chain.promise(),chain.cancellation()))
                    &&sources.dependencies().size()==3,"typed manifest binds all selected layers and all inputs, including unquoted optional context");
            check(stored.candidates().stream().allMatch(c->c.authority()==InterpretationReadRecords.Authority.CANDIDATE)
                    &&chain.entries().stream().anyMatch(e->e.links().stream().anyMatch(l->l.relation()==Relation.CANCELS)),
                    "cancellation remains an attributed CANDIDATE, not a gameplay cancellation");
            check(count(f,"native_interpretation_evidence")==1&&count(f,"native_memory_evidence")==0,"typed proof has its own row; no synthetic RAW seal is minted");
            check(await(f.store().validateNativeInterpretationEvidence(scope(f,A,AUDIENCE,false),ref)).isPresent(),"durable typed reference revalidates in same observer scope");
            for(var denied:List.of(scope(f,B,Set.of(PLAYER,B),false),scope(f,A,Set.of(PLAYER,A,STRANGER),false),scope(f,A,AUDIENCE,true)))
                check(await(f.store().validateNativeInterpretationEvidence(denied,ref)).isEmpty(),"other observer, added listener or public expansion denied");
            capture(f,A,"descendantmarker 취소했다는 말을 기억한다.",List.of(issued.seal().reference()));
            var descendants=raw(f,"descendantmarker");
            check(descendants.candidates().size()==1&&descendants.candidates().getFirst().requiresProjection(),"typed-backed actual speech retains projection-dependent ancestry");
            try(var db=db(f)) {
                var reader=new RecordedRoomSearch(db,scope(f,A,AUDIENCE,false),f.store().health().highWatermark(),System.nanoTime()+250_000_000L);
                check(reader.nativeSource(descendants.candidates().getFirst().entry().messageId()).isEmpty(),"typed descendant is not silently enabled for background/native semantic extraction");
            }
        }
        try(var f=fixture(root,world)) {
            check(await(f.store().validateNativeInterpretationEvidence(scope(f,A,AUDIENCE,false),ref)).isPresent(),"same observer can reprepare after restart without the old room or runtime generation");
            check(raw(f,"descendantmarker").candidates().size()==1,"persisted typed ancestry survives a clean restart");
            Evidence prior=chain.work().evidence().stream().filter(e->e.messageId().equals(chain.earlier())).findFirst().orElseThrow();
            check(await(f.store().invalidateKnowledge(f.producer(),new KnowledgeInvalidation(prior.source(),prior.knowledgeReceiptId(),1,"WITHDRAW_UNQUOTED"))).status()==RecordingRecords.Status.STORED,
                    "actual source owner withdraws unquoted historical input");
            check(await(f.store().validateNativeInterpretationEvidence(scope(f,A,AUDIENCE,false),ref)).isEmpty(),"unquoted receipt withdrawal invalidates persisted typed proof");
            check(raw(f,"descendantmarker").candidates().isEmpty(),"typed descendant speech also denied after interpretation input withdrawal");
            check(raw(f,"cancelmarker").candidates().size()==1,"independently authorized cancellation RAW is preserved");
            check(f.store().health().state()==WorldRecordingService.State.READY,"invalid proof never disables unrelated archive");
        }
    }
    private static void versionChange(Path root)throws Exception {
        try(var f=fixture(root,UUID.randomUUID())) {
            var chain=chain(f);var issued=issue(f,chain,chain.entries(),Set.of()).orElseThrow();var ref=NativeInterpretationEvidence.decode(issued.seal().reference());
            var next=f.store().registerProjectionWorker("fixture","v2");
            boolean target=false;
            for(int n=0;n<3;n++){
                Work work=claim(f,next);target=target(work).messageId().equals(chain.cancellation());
                check(await(f.store().validateNativeInterpretationEvidence(scope(f,A,AUDIENCE,false),ref)).isPresent()!=target,
                        "only changed selected job invalidates durable candidate; unrelated projection generation is not persistent authority");
                commit(f,work);if(target)break;
            }
            check(target,"selected job re-extraction was exercised");
            check(await(f.store().validateNativeInterpretationEvidence(scope(f,A,AUDIENCE,false),ref)).isEmpty(),"old binding cannot resume after selected extractor is replaced even with identical quoted text");
            var fresh=entries(f,chain.cancellation(),f.store().health().highWatermark());
            var refreshed=new Chain(chain.earlier(),chain.promise(),chain.cancellation(),chain.work(),fresh,f.store().health().highWatermark());
            check(issue(f,refreshed,fresh,Set.of()).isPresent(),"fresh exact version can receive its own new proof");
        }
    }
    private static void forgedAndQuota(Path root)throws Exception {
        try(var f=fixture(root,UUID.randomUUID())) {
            var chain=chain(f);var selected=chain.entries().stream().filter(e->e.layer()==Layer.SUMMARY).findFirst().orElseThrow();
            var quote=selected.quotes().getFirst();var changed=new InterpretationReadRecords.Quote(quote.sourceAlias(),quote.messageId(),quote.actualActor(),quote.occurredAt(),"모델이 만들어낸 사실");
            var forged=new InterpretationReadRecords.Entry(selected.memoryId(),selected.layer(),selected.kind(),selected.extractorVersion(),List.of(changed),selected.links(),selected.inputs());
            check(issue(f,chain,List.of(forged),Set.of()).isEmpty(),"Entry body supplied by caller must exactly match strict expanded DB candidate");
            check(issue(f,chain,List.of(),Set.of(chain.promise())).isEmpty(),"empty candidate selection cannot mint a typed proof");
            var old=new Chain(chain.earlier(),chain.promise(),chain.cancellation(),chain.work(),chain.entries(),1);
            check(issue(f,old,chain.entries(),Set.of()).isEmpty(),"projection/receipts after original read W cannot backdate proof");
            var good=issue(f,chain,List.of(selected),Set.of()).orElseThrow();var ref=NativeInterpretationEvidence.decode(good.seal().reference());
            check(manifest(f,ref).candidates().size()==1,"strict sibling verification does not force unselected siblings into prompt/proof selection");
            var fake=new NativeInterpretationEvidence.Reference(1,f.world(),f.store().datasetId().orElseThrow(),UUID.randomUUID(),ref.manifestHash());
            check(await(f.store().validateNativeInterpretationEvidence(scope(f,A,AUDIENCE,false),fake)).isEmpty(),"well-formed caller-created reference has no issuance authority");
            var field=WorldRecordingService.class.getDeclaredField("budget");field.setAccessible(true);var budget=(WorldRecordingBudget)field.get(f.store());
            var snapshot=budget.snapshot();var held=budget.reserve(ManagedStoreRegistry.RECORDING,(long)(snapshot.limitBytes()*.96)-snapshot.usedPhysicalBytes(),true);
            try{check(issue(f,chain,chain.entries(),Set.of()).isEmpty()&&count(f,"native_interpretation_evidence")==1,"optional FULL grants no partial typed manifest");}
            finally{budget.cancelUnstarted(held);}
            check(f.store().health().state()==WorldRecordingService.State.READY&&raw(f,"cancelmarker").candidates().size()==1,"quota refusal leaves RAW availability unchanged");
        }
    }
    private static void tamper(Path root,String kind)throws Exception {
        try(var f=fixture(root,UUID.randomUUID())) {
            var chain=chain(f);var summary=chain.entries().stream().filter(e->e.layer()==Layer.SUMMARY).findFirst().orElseThrow();
            var issue=issue(f,chain,List.of(summary),Set.of()).orElseThrow();var ref=NativeInterpretationEvidence.decode(issue.seal().reference());
            String job=manifest(f,ref).candidates().getFirst().jobId().toString();
            try(var db=db(f);var q=db.createStatement()) {
                switch(kind) {
                    case "sibling-link" -> check(q.executeUpdate("DELETE FROM memory_links WHERE memory_id IN (SELECT id FROM memories WHERE job_id='"+job+"')")==1,"fixture removes unselected EVENT sibling's cancellation link");
                    case "unquoted-input" -> q.executeUpdate("DELETE FROM memory_sources WHERE message_id='"+chain.earlier()+"' AND memory_id IN (SELECT id FROM memories WHERE job_id='"+job+"')");
                    case "payload" -> q.executeUpdate("UPDATE memories SET projection_hash='"+"0".repeat(64)+"' WHERE job_id='"+job+"' AND layer='EVENT'");
                    case "manifest" -> q.executeUpdate("UPDATE projection_input_manifests SET manifest_hash='"+"0".repeat(64)+"' WHERE job_id='"+job+"'");
                    case "future-issued" -> q.executeUpdate("UPDATE native_interpretation_evidence SET issued_sequence=original_watermark+10000");
                    case "world" -> q.executeUpdate("UPDATE native_interpretation_evidence SET world_id='"+UUID.randomUUID()+"'");
                    default -> throw new AssertionError(kind);
                }
            }
            check(await(f.store().validateNativeInterpretationEvidence(scope(f,A,AUDIENCE,false),ref)).isEmpty(),"persisted typed proof rejects corruption: "+kind);
            if(Set.of("sibling-link","unquoted-input","payload","manifest").contains(kind))
                check(issue(f,chain,List.of(summary),Set.of()).isEmpty(),"unchanged selected SUMMARY cannot bypass invalid sibling/input group: "+kind);
            check(f.store().health().state()==WorldRecordingService.State.READY,"optional corrupt proof never poisons raw store: "+kind);
        }
    }
    private static void migration(Path root)throws Exception {
        UUID world=UUID.randomUUID();Chain chain;
        try(var f=fixture(root,world)){chain=chain(f);}
        Path database;
        try(var directories=Files.list(root.resolve("mythictrpg-recording-v2"))){database=directories.filter(Files::isDirectory).findFirst().orElseThrow().resolve("recording.sqlite");}
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+database);var q=db.createStatement()){
            q.execute("DROP TABLE native_interpretation_evidence");q.execute("PRAGMA user_version=10");q.execute("UPDATE recording_meta SET schema_version=10");}
        try(var f=fixture(root,world)) {
            check(f.store().health().state()==WorldRecordingService.State.READY&&count(f,"native_interpretation_evidence")==0,"schema10 migration adds empty typed issuance table without backfill");
            var fresh=entries(f,chain.cancellation(),f.store().health().highWatermark());
            check(fresh.size()==3&&count(f,"projection_input_manifests")==3,"schema10 candidates and exact commit-time inputs survive migration");
            check(issue(f,new Chain(chain.earlier(),chain.promise(),chain.cancellation(),chain.work(),fresh,f.store().health().highWatermark()),fresh,Set.of()).isPresent(),"preserved valid candidate can be explicitly issued after migration");
        }
    }
    private static Fixture fixture(Path root,UUID world)throws Exception {
        Files.createDirectories(root);var store=await(WorldRecordingService.open(root,world,SETTINGS,BOUNDARY));
        check(store.health().state()==WorldRecordingService.State.READY,"fixture READY: "+store.health().reasonCode());
        var producer=store.registerProducer("room-publication-v2",Set.of("ROOM_PRIVATE"),Set.of(SourceKind.DIALOGUE_DIRECT,SourceKind.DERIVED_SPEECH));
        return new Fixture(root,world,store,producer,UUID.randomUUID());
    }
    private static Chain chain(Fixture f)throws Exception {
        var worker=f.store().registerProjectionWorker("fixture","v1");
        UUID earlier=capture(f,PLAYER,"auxmarker 잠시 망설였다.",List.of());commit(f,claim(f,worker));
        UUID promise=capture(f,PLAYER,"promisemarker 내일 물건을 가져올게.",List.of());commit(f,claim(f,worker));
        UUID cancellation=capture(f,PLAYER,"cancelmarker 아까 약속은 취소할게.",List.of());Work work=claim(f,worker);commit(f,work);
        long watermark=f.store().health().highWatermark();var entries=entries(f,cancellation,watermark);
        check(entries.size()==3&&work.evidence().size()==3,"fixture has three valid candidates and complete quoted/unquoted inputs");
        return new Chain(earlier,promise,cancellation,work,entries,watermark);
    }
    private static UUID capture(Fixture f,ActorRef actor,String text,List<RoomEvidenceReference> evidence)throws Exception {
        UUID id=UUID.randomUUID();Instant now=Instant.now();
        var context=new PublicationContext(f.store().runtimeEpoch(),1,"STANDARD",AUDIENCE,AUDIENCE,Map.of(),evidence.stream().map(e->new EvidencePointer(e.kind(),e.payload())).toList(),Set.of(),Map.of(),"UNKNOWN","PERSONAL");
        var envelope=new ConversationEnvelope(f.world(),f.store().datasetId().orElseThrow(),f.conversation(),"ROOM_PRIVATE","ACTUAL_LISTENERS_ONLY",1,1,true,false,"fixture");
        var raw=new RawMessage(id,Optional.empty(),0,actor,text,now,actor.kind()==ActorKind.PLAYER?MessageKind.ACCEPTED_INPUT:MessageKind.DELIVERED_OUTPUT,id.toString(),context);
        var receipts=new ArrayList<DeliveryReceipt>();for(var recipient:AUDIENCE){String visible=recipient.kind()==ActorKind.PLAYER?"[A] Player: "+text:text;
            receipts.add(new DeliveryReceipt(UUID.randomUUID(),recipient,recipient.kind()==ActorKind.GOD?"GAME_HEARD":"CHAT",now,1,DeliveryStatus.SERVER_DISPATCHED,new DeliveryView(visible,List.of(visible)),Set.of(0)));}
        check(await(f.store().capture(f.producer(),envelope,raw,receipts)).status()==RecordingRecords.Status.STORED,"native actual source capture committed");return id;
    }
    private static Work claim(Fixture f,ProjectionWorkerCapability worker)throws Exception {
        var value=await(f.store().projectionPort().claimWork(worker,new WorkBudget(6,16384,45)));check(value.status()==ProjectionRecords.Status.CLAIMED,"projection work claimed: "+value);return value.work().orElseThrow();
    }
    private static Evidence target(Work work){return work.evidence().stream().filter(e->e.alias().equals(work.targetAlias())).findFirst().orElseThrow();}
    private static void commit(Fixture f,Work work)throws Exception {
        var quote=new Quote(work.targetAlias(),target(work).text());
        var event=new Candidate(Layer.EVENT,ClaimKind.DIALOGUE_EPISODE,List.of(quote),List.of());
        if(target(work).text().contains("cancelmarker")) {var older=work.evidence().stream().filter(e->e.text().contains("promisemarker")).findFirst().orElseThrow();
            event=new Candidate(Layer.EVENT,ClaimKind.CORRECTION_OR_EXPLANATION,List.of(quote,new Quote(older.alias(),older.text())),List.of(new Link(work.targetAlias(),older.alias(),Relation.CANCELS)));}
        var candidates=List.of(event,new Candidate(Layer.RELATIONSHIP,ClaimKind.DIALOGUE_EPISODE,List.of(quote),List.of()),new Candidate(Layer.SUMMARY,ClaimKind.DIALOGUE_EPISODE,List.of(quote),List.of()));
        check(await(f.store().projectionPort().commitProjection(work.token(),candidates)).status()==ProjectionRecords.Status.STORED,"real projection commit includes schema10 integrity");
    }
    private static List<InterpretationReadRecords.Entry> entries(Fixture f,UUID source,long watermark)throws Exception {
        return await(f.store().readInterpretations(scope(f,A,AUDIENCE,false),List.of(source),new MemoryReadSession.Budget(8,32768),watermark,RecordedInterpretationSearch.Position.initial())).entries();
    }
    private static Optional<RecordedNativeInterpretationStore.IssuedInterpretation> issue(Fixture f,Chain chain,List<InterpretationReadRecords.Entry> entries,Set<UUID> speech)throws Exception {
        return await(f.store().issueNativeInterpretationEvidence(scope(f,A,AUDIENCE,false),request(),chain.watermark(),speech,entries));
    }
    private static Request request(){var god=ResourceLocation.parse(A.id());return new Request(UUID.randomUUID(),1,UUID.randomUUID(),UUID.fromString(PLAYER.id()),"Player",List.of(god),god,"remember",List.of(),true,true,false,List.of(new GodState(god,"R_NEUTRAL","E_NEUTRAL","",null)),false,Set.of(UUID.fromString(PLAYER.id())));}
    private static RecordedRoomSearch.Scope scope(Fixture f,ActorRef god,Set<ActorRef> audience,boolean publicRoom){return new RecordedRoomSearch.Scope(f.store().datasetId().orElseThrow(),god.id(),audience,publicRoom,"STANDARD","PERSONAL");}
    private static RecordedRoomSearch.Result raw(Fixture f,String text)throws Exception{return await(f.store().readRoom(scope(f,A,AUDIENCE,false),new MemoryReadSession.Query(text,Optional.empty(),Optional.empty()),new MemoryReadSession.Budget(8,32768),f.store().health().highWatermark(),Long.MAX_VALUE));}
    private static NativeInterpretationEvidence.Manifest manifest(Fixture f,NativeInterpretationEvidence.Reference ref)throws Exception {
        try(var db=db(f);var q=db.prepareStatement("SELECT manifest_json FROM native_interpretation_evidence WHERE seal_id=?")){q.setString(1,ref.sealId().toString());try(var rows=q.executeQuery()){check(rows.next(),"typed issued row exists");return NativeInterpretationEvidence.decodeManifest(rows.getString(1));}}
    }
    private static Connection db(Fixture f)throws Exception{return DriverManager.getConnection("jdbc:sqlite:"+f.root().resolve("mythictrpg-recording-v2").resolve(f.store().datasetId().orElseThrow().toString()).resolve("recording.sqlite"));}
    private static long count(Fixture f,String table)throws Exception{try(var db=db(f);var q=db.createStatement();var rows=q.executeQuery("SELECT count(*) FROM "+table)){rows.next();return rows.getLong(1);}}
    private static <T>T await(CompletionStage<T> value)throws Exception{return value.toCompletableFuture().get(20,TimeUnit.SECONDS);}
    private static void check(boolean valid,String message){checks++;if(!valid)throw new AssertionError(message);}
}
