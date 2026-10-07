package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Real writer/read transactions; no fake page or caller-provided dependency IDs can mint a seal. */
public final class RecordedNativeEvidenceStoreTest {
    private static int checks;
    private static final ActorRef PLAYER=new ActorRef(ActorKind.PLAYER,UUID.randomUUID().toString());
    private static final ActorRef A=new ActorRef(ActorKind.GOD,"test:a"),B=new ActorRef(ActorKind.GOD,"test:b"),C=new ActorRef(ActorKind.GOD,"test:c");
    private static final MemoryReadSession.Budget BUDGET=new MemoryReadSession.Budget(8,32768);
    private static final RecordingSettings SETTINGS=new RecordingSettings(RecordingSettings.Mode.SHADOW,256_000_000,2_000_000,.90,.95);
    private static final WorldRecordingService.CutoverBoundary BOUNDARY=new WorldRecordingService.CutoverBoundary("native-seal-test",Map.of("room-publication-v2",0L));
    private record Fixture(Path root,UUID world,WorldRecordingService store,ProducerCapability producer)implements AutoCloseable{
        public void close()throws Exception{await(store.closeAsync());}
    }
    private record Captured(UUID id,Map<ActorRef,UUID> deliveries) { }
    public static void main(String[] args)throws Exception{
        Path parent=Path.of(args.length==0?"build/recorded-native-evidence-test":args[0]).toAbsolutePath().normalize();
        if(!parent.toString().replace('\\','/').contains("/build/"))throw new IllegalArgumentException("TEST_REQUIRES_BUILD_DIRECTORY");
        Files.createDirectories(parent);Path root=Files.createTempDirectory(parent,"native-seal-");
        roundtrip(root.resolve("roundtrip"));capabilities(root.resolve("capabilities"));withdrawal(root.resolve("withdrawal"));
        late(root.resolve("late"));completionRace(root.resolve("cancel"),true);completionRace(root.resolve("turn"),false);
        restart(root.resolve("restart"));semantic(root.resolve("semantic"));unsupported(root.resolve("unsupported"));
        eightReadsAndQuota(root.resolve("limits"));latePlayer(root.resolve("late-player"));malformed(root.resolve("malformed"));
        worldTamper(root.resolve("world"));migration(root.resolve("migration"));
        contentRoundtrip(root.resolve("content"));contentFailures(root.resolve("content-failure"));
        contentRace(root.resolve("content-cancel"),true);contentRace(root.resolve("content-reload"),false);
        contentLimits(root.resolve("content-limits"));
        questRoundtrip(root.resolve("quest"));
        storyRoundtrip(root.resolve("story"));
        System.out.println("RecordedNativeEvidenceStoreTest: "+checks+" checks passed; fixtures="+root);
    }
    private static void roundtrip(Path root)throws Exception{
        try(var f=fixture(root)){
            var source=capture(f,PLAYER,"needle original promise",Set.of(PLAYER,A,B),List.of(),Set.of(),Set.of(A,B));
            var request=request(A,Set.of(PLAYER,A,B));var session=session(f,request);var page=raw(session,"needle");
            check(page.entries().size()==1&&session.current(page),"actual issued RAW page contains original source");
            long before=f.store().health().highWatermark();var seal=await(session.seal(List.of(page),List.of())).orElseThrow();
            check(session.current(seal)&&session.issuedFor(request),"durable seal receives exact session identity authority");
            check(f.store().health().highWatermark()>before,"seal has separate durable issuance sequence after original W");
            var ref=NativeMemoryEvidence.decode(seal.reference());var manifest=manifest(f,ref);
            check(manifest.originalWatermark()==before&&manifest.roots().equals(Set.of(source.id()))&&manifest.dependencies().size()==1,"manifest stores frozen roots and exact source closure");
            check(manifest.recipientGodId().equals(A.id())&&manifest.audience().equals(Set.of(PLAYER,A,B)),"original recipient and full audience retained");
            check(!NativeMemoryEvidence.encodeManifest(manifest).contains("original promise"),"durable seal does not duplicate raw text");
            var next=session(f,request(B,Set.of(PLAYER,B)));
            check(await(next.prepareNativeReference(ref))&&next.currentNativeReference(ref),"original audience B can validate A-issued evidence in a new room without A present");
            var denied=session(f,request(C,Set.of(PLAYER,C)));
            check(!await(denied.prepareNativeReference(ref)),"God outside original manifest audience gains no authority");
            var derived=capture(f,B,"answer remembered promise",Set.of(PLAYER,B),List.of(seal.reference()),Set.of(),Set.of(B));
            var rereader=session(f,request(B,Set.of(PLAYER,B)));var result=raw(rereader,"answer");
            check(result.entries().size()==1&&result.entries().getFirst().messageId().equals(derived.id()),"stored response with native portable proof becomes readable RAW with verified native edges");
            var nested=await(rereader.seal(List.of(result),List.of())).orElseThrow();var nestedManifest=manifest(f,NativeMemoryEvidence.decode(nested.reference()));
            check(nestedManifest.dependencies().size()==2&&nestedManifest.dependencies().stream().filter(d->d.messageId().equals(derived.id())).findFirst().orElseThrow().parentMessageIds().equals(Set.of(source.id())),"second seal preserves transitive native dependency edges");
            check(await(session(f,request(B,Set.of(PLAYER,B))).prepareNativeReference(NativeMemoryEvidence.decode(nested.reference()))),"transitive proof validates without broad external evidence fallback");
            check(f.store().health().state()==WorldRecordingService.State.READY,"optional provenance writes preserve healthy raw archive");
        }
    }
    private static void capabilities(Path root)throws Exception{
        try(var f=fixture(root)){
            capture(f,PLAYER,"needle capability",Set.of(PLAYER,A),List.of(),Set.of(),Set.of(A));
            var request=request(A,Set.of(PLAYER,A));var s=session(f,request);var p=raw(s,"needle");
            var clone=new MemoryReadSession.Page(p.status(),p.entries(),p.next());
            check(await(s.seal(List.of(clone),List.of())).isEmpty(),"equal cloned RAW page is not an issued capability");
            check(await(session(f,request).seal(List.of(p),List.of())).isEmpty(),"foreign session rejects original page");
            var proper=session(f,request);var actual=raw(proper,"needle");var seal=await(proper.seal(List.of(actual),List.of())).orElseThrow();
            check(!proper.current(NativeMemorySeal.unregistered(seal.reference())),"syntactically copied seal does not inherit live capability identity");
            check(await(proper.seal(List.of(actual),List.of())).isEmpty(),"only one bounded seal write attempt per session");
            check(!proper.issuedFor(request(A,Set.of(PLAYER,A))),"new turn/room cannot reuse live issued seal");
            var ref=NativeMemoryEvidence.decode(seal.reference());
            check(!await(session(f,request).prepareNativeReference(new NativeMemoryEvidence.Reference(1,ref.worldId(),ref.datasetId(),UUID.randomUUID(),ref.manifestHash()))),"caller-created seal ID cannot mint durable permission");
            check(!await(session(f,request).prepareNativeReference(new NativeMemoryEvidence.Reference(1,ref.worldId(),ref.datasetId(),ref.sealId(),"0".repeat(64)))),"wrong manifest hash is rejected");
            check(!await(session(f,request).prepareNativeReference(new NativeMemoryEvidence.Reference(1,UUID.randomUUID(),ref.datasetId(),ref.sealId(),ref.manifestHash()))),"foreign world descriptor is rejected");
            var scope=scope(f,request);var legacy=new RecordedMemoryAccess.Session(f.store(),scope,()->true,Runnable::run,()->true,refs->CompletableFuture.completedFuture(true),refs->true);
            check(await(legacy.seal(List.of(raw(legacy,"needle")),List.of())).isEmpty(),"compat test constructor cannot silently become trusted issuance path");
        }
    }
    private static void withdrawal(Path root)throws Exception{
        try(var f=fixture(root)){
            var capture=capture(f,PLAYER,"needle withdrawal",Set.of(PLAYER,A,B),List.of(),Set.of(),Set.of(A,B));
            var s=session(f,request(A,Set.of(PLAYER,A,B)));var seal=await(s.seal(List.of(raw(s,"needle")),List.of())).orElseThrow();
            var ref=NativeMemoryEvidence.decode(seal.reference());var dependency=manifest(f,ref).dependencies().getFirst();
            var b=session(f,request(B,Set.of(PLAYER,B)));check(await(b.prepareNativeReference(ref)),"B initially has current durable proof");
            UUID bKnowledge=UUID.nameUUIDFromBytes((capture.deliveries().get(B)+"/knowledge-v1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var withdrawn=f.store().invalidateKnowledge(f.producer(),new KnowledgeInvalidation(dependency.source(),bKnowledge,1,"WITHDRAW_B"));
            check(!s.current(seal)&&!b.currentNativeReference(ref),"queued receipt withdrawal immediately fences issued seals and portable preparation");
            check(await(withdrawn).status()==RecordingRecords.Status.STORED,"actual B receipt withdrawal committed");
            check(!await(session(f,request(B,Set.of(PLAYER,B))).prepareNativeReference(ref)),"fresh B cannot use A binding to bypass own withdrawn receipt");
            check(await(session(f,request(A,Set.of(PLAYER,A))).prepareNativeReference(ref)),"A-only fresh audience does not require currently absent B receipt");
            var replacement=new SourceRef(dependency.source().worldId(),dependency.source().datasetId(),dependency.source().kind(),dependency.source().owner(),dependency.source().sourceId(),2,RecordingRecords.sha256("replaced"));
            var replaced=await(f.store().captureSource(f.producer(),new SourceCapture(replacement,2,List.of())));
            check(replaced.status()==RecordingRecords.Status.STORED,"source replacement recorded through actual producer: "+replaced);
            check(!await(session(f,request(A,Set.of(PLAYER,A))).prepareNativeReference(ref)),"superseded native source cannot revive old portable proof");
        }
    }
    private static void late(Path root)throws Exception{
        try(var f=fixture(root)){
            var capture=capture(f,PLAYER,"needle late God",Set.of(PLAYER,A,B),List.of(),Set.of(),Set.of(A));
            var frozen=session(f,request(A,Set.of(PLAYER,A,B)));
            var view=new DeliveryView("needle late God",List.of("needle late God"));
            check(await(f.store().recordDeliveries(f.producer(),new DeliveryBatch(UUID.randomUUID(),capture.id(),"late-B",List.of(new DeliveryReceipt(UUID.randomUUID(),B,"GAME_HEARD",Instant.now(),1,DeliveryStatus.SERVER_DISPATCHED,view,Set.of(0)))))).status()==RecordingRecords.Status.STORED,"B actual receipt arrives after frozen read watermark");
            var old=raw(frozen,"needle");check(old.entries().isEmpty(),"late God knowledge is not joined into an earlier watermark");
            check(await(frozen.seal(List.of(old),List.of())).isEmpty(),"empty late-fenced page cannot issue evidence");
            var fresh=session(f,request(A,Set.of(PLAYER,A,B)));check(await(fresh.seal(List.of(raw(fresh,"needle")),List.of())).isPresent(),"fresh watermark includes real late acquisition");
        }
    }
    private static void completionRace(Path root,boolean cancel)throws Exception{
        try(var f=fixture(root)){
            capture(f,PLAYER,"needle delayed callback",Set.of(PLAYER,A),List.of(),Set.of(),Set.of(A));
            var tasks=new LinkedBlockingQueue<Runnable>();var live=new AtomicBoolean(true);
            var s=session(f,request(A,Set.of(PLAYER,A)),live,tasks::add,()->true);
            var query=s.query(query("needle"),Optional.empty(),BUDGET);runNext(tasks);runNext(tasks);var p=await(query);
            var pending=s.seal(List.of(p),List.of());Runnable completion=tasks.poll(2,TimeUnit.SECONDS);check(completion!=null,"durable commit waits at game dispatcher");
            if(cancel)check(pending.cancel(false),"caller cancels waiter after durable write");else live.set(false);
            completion.run();
            check(count(f,"native_memory_evidence")==1,"orphan issuance metadata remains atomically durable, without live authority");
            if(!cancel)check(await(pending).isEmpty(),"changed turn prevents late committed seal from being issued");
            check(!s.current(NativeMemorySeal.unregistered(reference(f))),"unregistered orphan descriptor is never a live session seal");
        }
    }
    private static void restart(Path root)throws Exception{
        UUID world;NativeMemoryEvidence.Reference ref;UUID dataset;
        try(var f=fixture(root)){
            world=f.world();dataset=f.store().datasetId().orElseThrow();capture(f,PLAYER,"needle reopen",Set.of(PLAYER,A),List.of(),Set.of(),Set.of(A));
            var s=session(f,request(A,Set.of(PLAYER,A)));ref=NativeMemoryEvidence.decode(await(s.seal(List.of(raw(s,"needle")),List.of())).orElseThrow().reference());
        }
        try(var f=reopen(root,world)){
            check(f.store().datasetId().orElseThrow().equals(dataset)&&count(f,"native_memory_evidence")==1,"reopen retains original dataset and one immutable manifest");
            check(await(session(f,request(A,Set.of(PLAYER,A))).prepareNativeReference(ref)),"new request after restart validates historical issuance without old room lease");
            try(var db=db(f);var q=db.createStatement()){q.executeUpdate("UPDATE native_memory_evidence SET issued_sequence=original_watermark+10000");}
            check(!await(session(f,request(A,Set.of(PLAYER,A))).prepareNativeReference(ref)),"malformed issuance chronology fails closed");
        }
    }
    private static void semantic(Path root)throws Exception{
        try(var f=fixture(root)){
            capture(f,PLAYER,"needle semantic origin",Set.of(PLAYER,A),List.of(),Set.of(),Set.of(A));
            var space=new EmbeddingRecords.ModelSpace("fixture:seal","a".repeat(64),3,EmbeddingRecords.ENCODER_VERSION);
            var worker=f.store().registerEmbeddingWorker("native-seal",space);var claim=await(f.store().embeddingPort().claimWork(worker,45));
            check(claim.status()==EmbeddingRecords.Status.CLAIMED,"real embedding source lease issued");
            check(await(f.store().embeddingPort().commitEmbedding(claim.work().orElseThrow().token(),new float[]{1,0,0})).status()==EmbeddingRecords.Status.STORED,"fake vector committed against exact native receipt");
            var enabled=new AtomicBoolean(true);var s=session(f,request(A,Set.of(PLAYER,A)),new AtomicBoolean(true),Runnable::run,enabled::get);
            var vector=new EmbeddingRecords.QueryVector(space,RecordingRecords.sha256("different"),new float[]{1,0,0});
            var page=await(s.semantic(query("different"),vector,Optional.empty(),BUDGET));check(page.entries().size()==1,"independent semantic retrieval issues actual native page");
            var seal=await(s.seal(List.of(),List.of(page))).orElseThrow();check(s.current(seal),"semantic page seals without constructing a counterfeit RAW page");
            enabled.set(false);check(!s.current(seal),"semantic gate withdrawal propagates through live issued seal");
        }
    }
    private static void unsupported(Path root)throws Exception{
        try(var f=fixture(root)){
            capture(f,PLAYER,"needle external lore",Set.of(PLAYER,A),List.of(new RoomEvidenceReference("STATIC_LORE","{}")),Set.of(),Set.of(A));
            var s=session(f,request(A,Set.of(PLAYER,A)));var p=raw(s,"needle");check(p.entries().size()==1,"fixture issuer permits ordinary external proof for diagnostic raw read");
            check(await(s.seal(List.of(p),List.of())).isEmpty(),"native speech seal rejects unsupported external dependency even when diagnostic preparation passed");
            check(count(f,"native_memory_evidence")==0&&f.store().health().state()==WorldRecordingService.State.READY,"unsupported provenance creates no manifest and does not disable raw capture");
        }
    }
    private static void eightReadsAndQuota(Path root)throws Exception{
        try(var f=fixture(root)){
            capture(f,PLAYER,"needle eight reads",Set.of(PLAYER,A),List.of(),Set.of(),Set.of(A));
            var s=session(f,request(A,Set.of(PLAYER,A)));var pages=new ArrayList<MemoryReadSession.Page>();
            for(int i=0;i<8;i++)pages.add(raw(s,"needle"));
            check(await(s.seal(pages,List.of())).isPresent(),"eight diagnostic reads do not consume the separate bounded seal allowance");
            var limited=session(f,request(A,Set.of(PLAYER,A)));var page=raw(limited,"needle");
            var field=WorldRecordingService.class.getDeclaredField("budget");field.setAccessible(true);var budget=(WorldRecordingBudget)field.get(f.store());
            var snapshot=budget.snapshot();var held=budget.reserve(ManagedStoreRegistry.RECORDING,(long)(snapshot.limitBytes()*.96)-snapshot.usedPhysicalBytes(),true);
            try{check(await(limited.seal(List.of(page),List.of())).isEmpty()&&count(f,"native_memory_evidence")==1,"quota-constrained optional seal writes grant no permission or partial manifest");}
            finally{budget.cancelUnstarted(held);}
            check(f.store().health().state()==WorldRecordingService.State.READY,"optional quota refusal leaves raw archive READY");
            check(!raw(session(f,request(A,Set.of(PLAYER,A))),"needle").entries().isEmpty(),"raw remains readable after optional seal quota refusal");
        }
    }
    private static void latePlayer(Path root)throws Exception{
        try(var f=fixture(root)){
            var capture=capture(f,PLAYER,"needle late player",Set.of(PLAYER,A),List.of(),Set.of(),Set.of(A),false);
            var frozen=session(f,request(A,Set.of(PLAYER,A)));var view=new DeliveryView("needle late player",List.of("needle late player"));
            check(await(f.store().recordDeliveries(f.producer(),new DeliveryBatch(UUID.randomUUID(),capture.id(),"late-player",List.of(new DeliveryReceipt(UUID.randomUUID(),PLAYER,"CHAT",Instant.now(),1,DeliveryStatus.SERVER_DISPATCHED,view,Set.of(0)))))).status()==RecordingRecords.Status.STORED,"late actual player dispatch committed");
            var older=raw(frozen,"needle");
            check(older.entries().isEmpty(),"frozen RAW page also excludes a private player delivery acquired after W");
            check(await(frozen.seal(List.of(older),List.of())).isEmpty(),"a planned private player whose actual dispatch arrived after W cannot be sealed into original disclosure");
            var fresh=session(f,request(A,Set.of(PLAYER,A)));check(await(fresh.seal(List.of(raw(fresh,"needle")),List.of())).isPresent(),"new W includes exact player delivery work binding");
        }
    }
    private static void malformed(Path root)throws Exception{
        try(var f=fixture(root)){
            UUID old=capture(f,PLAYER,"needle healthy older",Set.of(PLAYER,A),List.of(),Set.of(),Set.of(A)).id();
            capture(f,A,"needle malformed reference",Set.of(PLAYER,A),List.of(new RoomEvidenceReference(NativeMemoryEvidence.KIND,"not canonical json")),Set.of(),Set.of(A));
            UUID recent=capture(f,PLAYER,"needle healthy recent",Set.of(PLAYER,A),List.of(),Set.of(),Set.of(A)).id();
            var result=raw(session(f,request(A,Set.of(PLAYER,A))),"needle");
            check(result.entries().stream().map(MemoryReadSession.Entry::messageId).collect(java.util.stream.Collectors.toSet()).equals(Set.of(old,recent)),"bad native descriptor denies just that candidate, not healthy older/newer sources or progress");
        }
    }
    private static void worldTamper(Path root)throws Exception{
        try(var f=fixture(root)){
            capture(f,PLAYER,"needle world-bound",Set.of(PLAYER,A),List.of(),Set.of(),Set.of(A));
            var s=session(f,request(A,Set.of(PLAYER,A)));var seal=await(s.seal(List.of(raw(s,"needle")),List.of())).orElseThrow();
            var ref=NativeMemoryEvidence.decode(seal.reference());var original=manifest(f,ref);UUID foreign=UUID.randomUUID();
            var dependencies=original.dependencies().stream().map(d->{var source=d.source();return new NativeMemoryEvidence.SpeechDependency(d.messageId(),new SourceRef(foreign,source.datasetId(),source.kind(),source.owner(),source.sourceId(),source.revision(),source.hash()),d.knowledgeReceiptId(),d.receiptHash(),d.deliveryReceiptId(),d.deliveryHash(),d.bodyHash(),d.disclosureHash(),d.parentMessageIds());}).toList();
            var changed=new NativeMemoryEvidence.Manifest(1,foreign,original.datasetId(),original.originalWatermark(),original.roomId(),original.revision(),original.turnId(),original.playerId(),original.recipientGodId(),original.publicRoom(),original.recordingPolicy(),original.memoryMode(),original.audience(),original.roots(),dependencies);
            String hash=NativeMemoryEvidence.hash(changed);var changedRef=new NativeMemoryEvidence.Reference(1,foreign,ref.datasetId(),ref.sealId(),hash);
            try(var db=db(f);var q=db.prepareStatement("UPDATE native_memory_evidence SET world_id=?,manifest_json=?,manifest_hash=? WHERE seal_id=?")){q.setString(1,foreign.toString());q.setString(2,NativeMemoryEvidence.encodeManifest(changed));q.setString(3,hash);q.setString(4,ref.sealId().toString());check(q.executeUpdate()==1,"fixture corrupts internally consistent descriptor/row to a foreign world");}
            check(!await(session(f,request(A,Set.of(PLAYER,A))).prepareNativeReference(changedRef)),"portable preparation binds actual store world, not caller descriptor");
            capture(f,A,"output foreign seal",Set.of(PLAYER,A),List.of(NativeMemoryEvidence.encode(changedRef)),Set.of(),Set.of(A));
            check(raw(session(f,request(A,Set.of(PLAYER,A))),"output").entries().isEmpty(),"historical RAW re-read joins actual recording_meta world and rejects foreign row even with consistent descriptor/hash");
        }
    }
    private static void migration(Path root)throws Exception{
        UUID world=UUID.randomUUID();var f=reopen(root,world);UUID original=capture(f,PLAYER,"needle schema8 preserve",Set.of(PLAYER,A),List.of(),Set.of(),Set.of(A)).id();await(f.store().closeAsync());
        try(var db=db(f);var q=db.createStatement()){q.execute("DROP TABLE native_memory_evidence");q.execute("DROP TABLE projection_input_manifests");q.execute("DROP TABLE native_interpretation_evidence");q.execute("PRAGMA user_version=8");q.execute("UPDATE recording_meta SET schema_version=8");}
        try(var current=reopen(root,world)){
            check(count(current,"native_memory_evidence")==0&&await(current.store().inspectMessage(original,4096)).orElseThrow().equals("needle schema8 preserve"),"schema8 migration adds empty manifests while preserving exact raw text");
            var session=session(current,request(A,Set.of(PLAYER,A)));check(await(session.seal(List.of(raw(session,"needle")),List.of())).isPresent(),"preserved native current-v2 source can be explicitly sealed after migration");
        }
    }
    private static void contentRoundtrip(Path root)throws Exception{
        UUID world;NativeMemoryEvidence.Reference portable;var profileA=content("profile-A");var profileB=content("profile-B");
        var allowed=new HashSet<>(Set.of(profileA,profileB));var seen=new CopyOnWriteArrayList<List<RoomEvidenceReference>>();
        java.util.function.Function<List<RoomEvidenceReference>,CompletableFuture<Boolean>> prepare=refs->{seen.add(List.copyOf(refs));return CompletableFuture.completedFuture(true);};
        java.util.function.Predicate<List<RoomEvidenceReference>> current=refs->allowed.containsAll(refs);
        try(var f=fixture(root)){
            world=f.world();var original=capture(f,A,"profiled original speech",Set.of(PLAYER,A,B),List.of(profileA),Set.of(),Set.of(A,B));
            var issuer=ownedSession(f,request(A,Set.of(PLAYER,A,B)),prepare,current);var raw=raw(issuer,"profiled");
            var first=await(issuer.seal(List.of(raw),List.of())).orElseThrow();
            check(issuer.current(first)&&seen.stream().filter(refs->refs.equals(List.of(profileA))).count()>=2,"actual RAW page plus seal each prepare the hash-bound content owner leaf");
            var b=ownedSession(f,request(B,Set.of(PLAYER,B)),prepare,current);
            check(await(b.prepareNativeReference(NativeMemoryEvidence.decode(first.reference()))),"B previously in original audience revalidates A content leaf through current owner");
            var answer=capture(f,B,"nested profiled answer",Set.of(PLAYER,B),List.of(first.reference(),profileB),Set.of(),Set.of(B));
            var next=ownedSession(f,request(B,Set.of(PLAYER,B)),prepare,current);var answerPage=raw(next,"nested");
            check(answerPage.entries().size()==1&&answerPage.entries().getFirst().messageId().equals(answer.id()),"content-bearing native response is re-readable without treating leaf kind as permission");
            portable=NativeMemoryEvidence.decode(await(next.seal(List.of(answerPage),List.of())).orElseThrow().reference());
            check(seen.stream().anyMatch(refs->new HashSet<>(refs).equals(Set.of(profileA,profileB))),"nested ancestry prepares the full deduplicated leaf union, not just newest profile");
            try(var db=db(f)){
                var scope=scope(f,request(B,Set.of(PLAYER,B)));long w=f.store().health().highWatermark();
                check(new RecordedRoomSearch(db,scope,w,System.nanoTime()+250_000_000L).nativeSource(original.id()).isEmpty(),"background single-source native path still rejects content-bearing inputs");
                check(new RecordedRoomSearch(db,scope,w,System.nanoTime()+250_000_000L).nativeSources(Set.of(original.id(),answer.id())).isEmpty(),"background multi-source path does not inherit foreground owner permission");
            }
            check(!await(ownedSession(f,request(C,Set.of(PLAYER,C)),prepare,current).prepareNativeReference(portable)),"content owner success cannot grant unread God access");
        }
        try(var f=reopen(root,world)){
            var restored=ownedSession(f,request(B,Set.of(PLAYER,B)),prepare,current);
            check(await(restored.prepareNativeReference(portable))&&restored.currentNativeReference(portable),"restart restores native source proof and re-prepares current content owner from exact original context");
            allowed.remove(profileA);
            check(!restored.currentNativeReference(portable),"original profile reload/permission change invalidates prepared nested proof without SQL mutation");
            check(!await(ownedSession(f,request(B,Set.of(PLAYER,B)),prepare,current).prepareNativeReference(portable)),"fresh preparation cannot revive removed ancestor content permission");
            check(raw(ownedSession(f,request(B,Set.of(PLAYER,B)),prepare,current),"nested").entries().isEmpty(),"ordinary RAW page also respects withdrawn ancestor owner permission");
        }
    }
    private static void contentFailures(Path root)throws Exception{
        try(var f=fixture(root)){
            var leaf=content("denial");capture(f,A,"owned denial source",Set.of(PLAYER,A),List.of(leaf),Set.of(),Set.of(A));
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var s=ownedSession(f,request(A,Set.of(PLAYER,A)),refs->CompletableFuture.completedFuture(calls.incrementAndGet()==1),refs->true);
            var p=raw(s,"owned");check(p.entries().size()==1,"fixture initial owner preparation accepts the actual page");
            check(await(s.seal(List.of(p),List.of())).isEmpty(),"owner prepare refusal after durable issuance never grants a seal");
            var ref=NativeMemoryEvidence.decode(reference(f));
            var denied=ownedSession(f,request(A,Set.of(PLAYER,A)),refs->CompletableFuture.completedFuture(false),refs->true);
            check(!await(denied.prepareNativeReference(ref))&&!denied.currentNativeReference(ref),"persisted manifest existence cannot bypass owner prepare refusal");
            for(String kind:List.of("STORY_DISCLOSURE_V2","WATCH_OBSERVATION_V1","LEGACY_PERSONAL_V1")){
                capture(f,A,"unsupported "+kind,Set.of(PLAYER,A),List.of(new RoomEvidenceReference(kind,"{}")),Set.of(),Set.of(A));
                var unknown=session(f,request(A,Set.of(PLAYER,A)));check(await(unknown.seal(List.of(raw(unknown,kind)),List.of())).isEmpty(),"unsupported owner remains excluded: "+kind);
            }
        }
    }
    private static void contentRace(Path root,boolean cancel)throws Exception{
        try(var f=fixture(root)){
            var leaf=content("pending");capture(f,A,"pending owner source",Set.of(PLAYER,A),List.of(leaf),Set.of(),Set.of(A));
            var gate=new CompletableFuture<Boolean>();var called=new CountDownLatch(1);var calls=new java.util.concurrent.atomic.AtomicInteger();var allowed=new AtomicBoolean(true);
            var s=ownedSession(f,request(A,Set.of(PLAYER,A)),refs->{if(calls.incrementAndGet()==1)return CompletableFuture.completedFuture(true);called.countDown();return gate;},refs->allowed.get());
            var pending=s.seal(List.of(raw(s,"pending")),List.of());check(called.await(2,TimeUnit.SECONDS),"seal waits for actual owner preparation after durable source validation");
            if(cancel)check(pending.cancel(false),"cancelled owner preparation waiter grants no authority");else allowed.set(false);
            gate.complete(true);
            if(!cancel)check(await(pending).isEmpty(),"owner reload between prepare and callback rejects otherwise successful proof");
            check(!s.current(NativeMemorySeal.unregistered(reference(f))),"late owner success cannot register an unissued/cancelled identity");
        }
    }
    private static void contentLimits(Path root)throws Exception{
        try(var f=fixture(root)){
            var oversized=new RoomEvidenceReference("CONTENT_DISCLOSURE_V1","x".repeat(16385));
            capture(f,A,"oversized leaf",Set.of(PLAYER,A),List.of(oversized),Set.of(),Set.of(A));
            var s=session(f,request(A,Set.of(PLAYER,A)));check(await(s.seal(List.of(raw(s,"oversized")),List.of())).isEmpty(),"content leaf exceeding sixteen KiB cannot be sealed even if fake owner accepts it");
            var aggregate=new ArrayList<RoomEvidenceReference>();for(int i=0;i<5;i++)aggregate.add(new RoomEvidenceReference("CONTENT_DISCLOSURE_V1",i+"x".repeat(14000)));
            capture(f,A,"aggregate leaves",Set.of(PLAYER,A),aggregate,Set.of(),Set.of(A));
            var many=session(f,request(A,Set.of(PLAYER,A)));check(await(many.seal(List.of(raw(many,"aggregate")),List.of())).isEmpty(),"aggregate content payload exceeds sixty-four KiB without truncating individual leafs");
            check(count(f,"native_memory_evidence")==0&&f.store().health().state()==WorldRecordingService.State.READY,"leaf limits deny complete issuance and preserve raw store availability");
        }
    }
    private static RoomEvidenceReference content(String id){return new RoomEvidenceReference("CONTENT_DISCLOSURE_V1","{\"fixture_profile\":\""+id+"\"}");}
    private static void questRoundtrip(Path root)throws Exception{
        UUID world;NativeMemoryEvidence.Reference reference;var profile=content("quest-author");
        var quest=new RoomEvidenceReference("QUEST_CONTENT_DISCLOSURE_V1","{\"sourceGodId\":\"test:a\",\"questId\":\"test:quest\",\"sha256\":\""+"1".repeat(64)+"\"}");
        var currentDefinition=new AtomicBoolean(true);var ownerCalls=new CopyOnWriteArrayList<List<RoomEvidenceReference>>();
        java.util.function.Function<List<RoomEvidenceReference>,CompletableFuture<Boolean>> prepare=refs->{ownerCalls.add(List.copyOf(refs));return CompletableFuture.completedFuture(true);};
        java.util.function.Predicate<List<RoomEvidenceReference>> current=refs->refs.stream().allMatch(ref->ref.equals(profile)||ref.equals(quest)&&currentDefinition.get());
        try(var f=fixture(root)){
            world=f.world();var captured=capture(f,A,"authored quest asks for a heart",Set.of(PLAYER,A,B),List.of(profile,quest),Set.of(),Set.of(A,B));
            var original=ownedSession(f,request(A,Set.of(PLAYER,A,B)),prepare,current);
            var seal=await(original.seal(List.of(raw(original,"authored")),List.of())).orElseThrow();reference=NativeMemoryEvidence.decode(seal.reference());
            check(original.current(seal)&&ownerCalls.stream().anyMatch(refs->new HashSet<>(refs).equals(Set.of(profile,quest))),"profile and item-scoped quest leaves are both prepared and retained, without selecting just one owner");
            check(manifest(f,reference).dependencies().getFirst().source().kind()==SourceKind.DERIVED_SPEECH,"quest prose remains NPC speech provenance, not accepted quest or confirmed reward state");
            var listener=ownedSession(f,request(B,Set.of(PLAYER,B)),prepare,current);
            check(await(listener.prepareNativeReference(reference)),"original author's permitted quest text can be recalled by actual listening God B");
            capture(f,B,"quest retelling answer",Set.of(PLAYER,B),List.of(seal.reference()),Set.of(),Set.of(B));
            check(raw(ownedSession(f,request(B,Set.of(PLAYER,B)),prepare,current),"retelling").entries().size()==1,"quest leaf survives native response roundtrip with current owner check");
            try(var db=db(f)){check(new RecordedRoomSearch(db,scope(f,request(A,Set.of(PLAYER,A))),f.store().health().highWatermark(),System.nanoTime()+250_000_000L).nativeSource(captured.id()).isEmpty(),"quest-content support does not authorize idle projection/embedding input");}
            var oversized=new RoomEvidenceReference("QUEST_CONTENT_DISCLOSURE_V1","x".repeat(4097));capture(f,A,"oversize quest leaf",Set.of(PLAYER,A),List.of(oversized),Set.of(),Set.of(A));
            var rejected=session(f,request(A,Set.of(PLAYER,A)));check(await(rejected.seal(List.of(raw(rejected,"oversize")),List.of())).isEmpty(),"quest leaf uses its own four-KiB limit, not the wider profile limit");
            var deniedRequest=request(C,Set.of(PLAYER,C));check(!await(ownedSession(f,deniedRequest,prepare,current).prepareNativeReference(reference)),"static quest owner permission cannot grant an unhearing God source knowledge");
        }
        try(var f=reopen(root,world)){
            var restored=ownedSession(f,request(B,Set.of(PLAYER,B)),prepare,current);
            check(await(restored.prepareNativeReference(reference)),"quest explanation proof revalidates current static owner after restart");
            currentDefinition.set(false);
            check(!restored.currentNativeReference(reference),"quest definition/ownership/disclosure change invalidates already-prepared proof");
            check(!await(ownedSession(f,request(B,Set.of(PLAYER,B)),prepare,current).prepareNativeReference(reference)),"fresh preparation cannot bypass changed quest fingerprint or policy");
            check(raw(ownedSession(f,request(B,Set.of(PLAYER,B)),prepare,current),"retelling").entries().isEmpty(),"descendant RAW retelling also loses invalid quest-content permission");
            check(f.store().health().state()==WorldRecordingService.State.READY,"quest owner rejection is not an archive failure");
        }
    }
    /** These are owner-seam fixtures, not manufactured Story permissions. The actual Story parser/state is tested separately. */
    private static void storyRoundtrip(Path root)throws Exception{
        UUID world;NativeMemoryEvidence.Reference reference;
        var leaves=List.of("FACT","COVER","PRESENTATION","HOOK").stream()
                .map(type->new RoomEvidenceReference("STORY_DISCLOSURE_V1","{\"fixture_type\":\""+type+"\"}")).toList();
        var permitted=new HashSet<>(leaves);var prepared=new CopyOnWriteArrayList<List<RoomEvidenceReference>>();
        java.util.function.Function<List<RoomEvidenceReference>,CompletableFuture<Boolean>> prepare=refs->{prepared.add(List.copyOf(refs));return CompletableFuture.completedFuture(permitted.containsAll(refs));};
        java.util.function.Predicate<List<RoomEvidenceReference>> current=permitted::containsAll;
        try(var f=fixture(root)){
            world=f.world();var message=capture(f,A,"storymarker I told an authored story",Set.of(PLAYER,A,B),leaves,Set.of(),Set.of(A,B));
            var reader=ownedSession(f,request(A,Set.of(PLAYER,A,B)),prepare,current);
            var seal=await(reader.seal(List.of(raw(reader,"storymarker")),List.of())).orElseThrow();reference=NativeMemoryEvidence.decode(seal.reference());
            check(reader.current(seal)&&prepared.stream().anyMatch(refs->new HashSet<>(refs).equals(new HashSet<>(leaves))),"all Story leaves preserved and revalidated by owner; not selected by subtype alone");
            check(manifest(f,reference).roots().equals(Set.of(message.id())),"story disclosure remains provenance of actual speech, not a Story state write");
            var b=ownedSession(f,request(B,Set.of(PLAYER,B)),prepare,current);
            check(await(b.prepareNativeReference(reference)),"actual listening God can recall original Story disclosure without original speaker present");
            capture(f,B,"storychild I remember that earlier story",Set.of(PLAYER,B),List.of(seal.reference()),Set.of(),Set.of(B));
            check(raw(ownedSession(f,request(B,Set.of(PLAYER,B)),prepare,current),"storychild").entries().size()==1,"nested speech retains entire Story owner closure");
            try(var db=db(f)){check(new RecordedRoomSearch(db,scope(f,request(A,Set.of(PLAYER,A))),f.store().health().highWatermark(),System.nanoTime()+250_000_000L).nativeSource(message.id()).isEmpty(),"Story foreground leaf does not enable background interpretation or embedding");}
            var oversized=new RoomEvidenceReference("STORY_DISCLOSURE_V1","x".repeat(16385));
            capture(f,A,"storyoversize bounded leaf",Set.of(PLAYER,A),List.of(oversized),Set.of(),Set.of(A));
            var over=session(f,request(A,Set.of(PLAYER,A)));check(await(over.seal(List.of(raw(over,"storyoversize")),List.of())).isEmpty(),"Story leaf is bounded even if test owner returns true");
            check(!await(ownedSession(f,request(C,Set.of(PLAYER,C)),prepare,current).prepareNativeReference(reference)),"Story owner cannot give source knowledge to a God outside original hearing audience");
        }
        try(var f=reopen(root,world)){
            for(var leaf:leaves){
                var reader=ownedSession(f,request(B,Set.of(PLAYER,B)),prepare,current);
                check(await(reader.prepareNativeReference(reference)),"stored Story source is revalidated after restart");
                permitted.remove(leaf);
                check(!reader.currentNativeReference(reference),"one changed fact/cover/presentation/hook leaf immediately denies prepared native reference");
                check(!await(ownedSession(f,request(B,Set.of(PLAYER,B)),prepare,current).prepareNativeReference(reference)),"fresh prepare cannot skip the changed Story owner leaf");
                check(raw(ownedSession(f,request(B,Set.of(PLAYER,B)),prepare,current),"storychild").entries().isEmpty(),"Story owner withdrawal also removes dependent dialogue from normal recall");
                permitted.add(leaf);
            }
            check(f.store().health().state()==WorldRecordingService.State.READY,"Story disclosure refusal does not disable RAW storage");
        }
    }
    private static RecordedMemoryAccess.Session ownedSession(Fixture f,Request request,java.util.function.Function<List<RoomEvidenceReference>,CompletableFuture<Boolean>> prepare,java.util.function.Predicate<List<RoomEvidenceReference>> current){return new RecordedMemoryAccess.Session(f.store(),scope(f,request),()->true,Runnable::run,()->true,prepare,current,()->true,id->Optional.empty(),request);}
    private static Fixture fixture(Path root)throws Exception{return reopen(root,UUID.randomUUID());}
    private static Fixture reopen(Path root,UUID world)throws Exception{
        Files.createDirectories(root);var store=await(WorldRecordingService.open(root,world,SETTINGS,BOUNDARY));check(store.health().state()==WorldRecordingService.State.READY,"fixture archive READY");
        return new Fixture(root,world,store,store.registerProducer("room-publication-v2",Set.of("ROOM_PRIVATE"),Set.of(SourceKind.DIALOGUE_DIRECT,SourceKind.DERIVED_SPEECH)));
    }
    private static Captured capture(Fixture f,ActorRef actor,String text,Set<ActorRef> audience,List<RoomEvidenceReference> refs,Set<UUID> parents,Set<ActorRef> heard)throws Exception{
        return capture(f,actor,text,audience,refs,parents,heard,true);
    }
    private static Captured capture(Fixture f,ActorRef actor,String text,Set<ActorRef> audience,List<RoomEvidenceReference> refs,Set<UUID> parents,Set<ActorRef> heard,boolean playerDelivered)throws Exception{
        UUID id=UUID.randomUUID();Instant now=Instant.now();var delivered=new HashSet<>(heard);if(playerDelivered)delivered.add(PLAYER);
        var context=new PublicationContext(f.store().runtimeEpoch(),1,"STANDARD",audience,audience,Map.of(),refs.stream().map(r->new EvidencePointer(r.kind(),r.payload())).toList(),parents,Map.of(),"UNKNOWN","PERSONAL");
        var envelope=new ConversationEnvelope(f.world(),f.store().datasetId().orElseThrow(),UUID.randomUUID(),"ROOM_PRIVATE","ACTUAL_LISTENERS_ONLY",1,1,true,false,"fixture");
        var raw=new RawMessage(id,Optional.empty(),0,actor,text,now,actor.kind()==ActorKind.PLAYER?MessageKind.ACCEPTED_INPUT:MessageKind.DELIVERED_OUTPUT,id.toString(),context);
        var view=new DeliveryView(text,List.of(text));var receipts=new ArrayList<DeliveryReceipt>();var ids=new HashMap<ActorRef,UUID>();
        for(var member:delivered){var receiptId=UUID.randomUUID();ids.put(member,receiptId);
            String displayed="[room-A] Player: "+text;var actualView=member.kind()==ActorKind.GOD?view:new DeliveryView(displayed,List.of(displayed));
            receipts.add(new DeliveryReceipt(receiptId,member,member.kind()==ActorKind.GOD?"GAME_HEARD":"CHAT",now,1,DeliveryStatus.SERVER_DISPATCHED,actualView,Set.of(0)));}
        check(await(f.store().capture(f.producer(),envelope,raw,receipts)).status()==RecordingRecords.Status.STORED,"native source and actual delivery receipts committed");return new Captured(id,Map.copyOf(ids));
    }
    private static Request request(ActorRef god,Set<ActorRef> audience){
        var godId=ResourceLocation.parse(god.id());var gods=audience.stream().filter(a->a.kind()==ActorKind.GOD).map(a->ResourceLocation.parse(a.id())).toList();
        var players=audience.stream().filter(a->a.kind()==ActorKind.PLAYER).map(a->UUID.fromString(a.id())).collect(java.util.stream.Collectors.toSet());
        return new Request(UUID.randomUUID(),1,UUID.randomUUID(),UUID.fromString(PLAYER.id()),"Player",gods,godId,"remember",List.of(),true,true,false,List.of(new GodState(godId,"R_NEUTRAL","E_NEUTRAL","",null)),false,players);
    }
    private static RecordedRoomSearch.Scope scope(Fixture f,Request request){var audience=new HashSet<ActorRef>();request.audiencePlayerIds().forEach(p->audience.add(new ActorRef(ActorKind.PLAYER,p.toString())));request.godIds().forEach(g->audience.add(new ActorRef(ActorKind.GOD,g.toString())));return new RecordedRoomSearch.Scope(f.store().datasetId().orElseThrow(),request.speakerGodId().toString(),audience,false,"STANDARD","PERSONAL");}
    private static RecordedMemoryAccess.Session session(Fixture f,Request request){return session(f,request,new AtomicBoolean(true),Runnable::run,()->true);}
    private static RecordedMemoryAccess.Session session(Fixture f,Request request,AtomicBoolean live,Consumer<Runnable> dispatch,java.util.function.BooleanSupplier semantic){return new RecordedMemoryAccess.Session(f.store(),scope(f,request),()->true,dispatch,live::get,refs->CompletableFuture.completedFuture(true),refs->true,semantic,id->Optional.empty(),request);}
    private static MemoryReadSession.Query query(String text){return new MemoryReadSession.Query(text,Optional.empty(),Optional.empty());}
    private static MemoryReadSession.Page raw(RecordedMemoryAccess.Session session,String text)throws Exception{return await(session.query(query(text),Optional.empty(),BUDGET));}
    private static NativeMemoryEvidence.Manifest manifest(Fixture f,NativeMemoryEvidence.Reference ref)throws Exception{try(var db=db(f);var q=db.prepareStatement("SELECT manifest_json FROM native_memory_evidence WHERE seal_id=?")){q.setString(1,ref.sealId().toString());try(var rows=q.executeQuery()){check(rows.next(),"issued manifest exists");return NativeMemoryEvidence.decodeManifest(rows.getString(1));}}}
    private static RoomEvidenceReference reference(Fixture f)throws Exception{try(var db=db(f);var q=db.createStatement();var rows=q.executeQuery("SELECT world_id,dataset_id,seal_id,manifest_hash FROM native_memory_evidence LIMIT 1")){check(rows.next(),"committed orphan manifest exists");return NativeMemoryEvidence.encode(new NativeMemoryEvidence.Reference(1,UUID.fromString(rows.getString(1)),UUID.fromString(rows.getString(2)),UUID.fromString(rows.getString(3)),rows.getString(4)));}}
    private static Connection db(Fixture f)throws Exception{return DriverManager.getConnection("jdbc:sqlite:"+f.root().resolve("mythictrpg-recording-v2").resolve(f.store().datasetId().orElseThrow().toString()).resolve("recording.sqlite"));}
    private static long count(Fixture f,String table)throws Exception{try(var db=db(f);var q=db.createStatement();var rows=q.executeQuery("SELECT count(*) FROM "+table)){rows.next();return rows.getLong(1);}}
    private static void runNext(LinkedBlockingQueue<Runnable> tasks)throws Exception{var task=tasks.poll(2,TimeUnit.SECONDS);check(task!=null,"game dispatcher callback queued");task.run();}
    private static <T>T await(CompletionStage<T> future)throws Exception{return future.toCompletableFuture().get(10,TimeUnit.SECONDS);}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
