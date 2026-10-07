package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.ProjectionRecords.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.ActorKind;
import com.sande.mythictrpg.recording.api.RecordingRecords.ActorRef;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Real SQLite authority, transactional extraction and restart/lease fixtures; no model or operational world. */
public final class RecordingProjectionStoreTest {
    private static int checks;
    private static final String GOD="test:a",OTHER="test:b";
    private static final ActorRef PLAYER=new ActorRef(ActorKind.PLAYER,UUID.randomUUID().toString());
    private static final RecordingSettings SETTINGS=new RecordingSettings(RecordingSettings.Mode.RECORD_ONLY,128_000_000,2_000_000,.90,.95);
    private static final WorldRecordingService.CutoverBoundary BOUNDARY=new WorldRecordingService.CutoverBoundary("projection-test",Map.of());
    private record Fixture(Path root,UUID world,WorldRecordingService store,ProducerCapability producer,UUID conversation){ }
    public static void main(String[] args)throws Exception {
        Path parent=Path.of(args.length==0?"build/recording-projection-test":args[0]).toAbsolutePath().normalize();
        if(!parent.toString().replace('\\','/').contains("/build/"))throw new IllegalArgumentException("TEST_REQUIRES_BUILD_DIRECTORY");
        Files.createDirectories(parent);Path root=Files.createTempDirectory(parent,"projection-");
        grounding(root.resolve("grounding"));contextWithdrawal(root.resolve("context-withdrawal"));retries(root.resolve("retries"));restart(root.resolve("restart"));
        unsupported(root.resolve("unsupported"));nativeAncestry(root.resolve("native-ancestry"));
        rejectedAncestry(root.resolve("rejected-ancestry"));lateAncestorReceipt(root.resolve("late-ancestor"));
        deferredFairness(root.resolve("deferred-fairness"));
        quota(root.resolve("quota"));migration(root.resolve("migration"));
        System.out.println("RecordingProjectionStoreTest: "+checks+" checks passed; fixtures="+root);
    }
    private static void grounding(Path root)throws Exception {
        var f=fixture(root);var port=f.store().projectionPort();
        check(await(port.claimWork(ProjectionWorkerCapability.unregistered(),budget())).status()==ProjectionRecords.Status.REJECTED,"unregistered worker cannot read source text");
        UUID message=capture(f,PLAYER,GOD,"내일 바다의 심장을 가져올게.",List.of());
        var worker=f.store().registerProjectionWorker("fixture","v1");
        check(f.store().registerProjectionWorker("fixture","v1")==worker,"same game worker/version registration idempotent");
        Work work=claim(f,worker);var e=work.evidence().getFirst();
        check(e.actualActor().equals(PLAYER)&&e.observerGodId().equals(GOD)&&e.source().kind()==SourceKind.DIALOGUE_DIRECT,"actual speaker not relabelled as observer");
        check(e.messageId().equals(message)&&!e.excerpt()&&e.totalCharacters()==e.text().length(),"native exact quote and full-source coverage preserved");
        check(await(port.claimWork(worker,budget())).status()==ProjectionRecords.Status.DEFERRED,"one outstanding work per capability");
        var candidates=three(work);check(await(port.commitProjection(work.token(),candidates)).status()==ProjectionRecords.Status.STORED,"three grounded projection layers atomically persist");
        check(count(f,"SELECT count(*) FROM memories WHERE status='CANDIDATE'")==3&&count(f,"SELECT count(*) FROM memory_sources")==3,"each projection retains actual receipt and source provenance");
        check(count(f,"SELECT count(*) FROM work_items WHERE state='DONE'")==1,"work acknowledged with projection in same transaction");
        check(count(f,"SELECT count(*) FROM projection_input_manifests")==1,"one immutable all-input manifest commits with all three layers and DONE");
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+database(f));var q=db.createStatement();var rows=q.executeQuery("SELECT manifest_json,manifest_hash FROM projection_input_manifests")){
            check(rows.next(),"commit-time manifest exists");String json=rows.getString(1);var manifest=ProjectionInputManifest.decode(json);
            check(rows.getString(2).equals(RecordingRecords.sha256(json))&&manifest.inputs().size()==1&&manifest.outputs().size()==3
                    &&manifest.inputs().getFirst().prefixHash().equals(RecordingRecords.sha256(e.text()))
                    &&manifest.inputs().getFirst().knowledgeReceiptId().equals(e.knowledgeReceiptId()),"manifest binds exact model input, actual receipt and complete output set");}
        check(await(port.commitProjection(work.token(),candidates)).status()==ProjectionRecords.Status.DUPLICATE,"same committed result retry idempotent");
        check(count(f,"SELECT count(*) FROM projection_input_manifests")==1,"duplicate retry cannot rewrite or duplicate immutable input manifest");
        check(await(port.finishWork(work.token(),WorkOutcome.DEFERRED,"LATE_TIMEOUT")).status()==ProjectionRecords.Status.STALE
                &&count(f,"SELECT count(*) FROM work_items WHERE state='DONE'")==1,"late timeout cannot requeue committed work");
        capture(f,new ActorRef(ActorKind.GOD,GOD),GOD,"좋아. 기다리지.",List.of());Work god=claim(f,worker);
        var target=target(god);check(target.actualActor().kind()==ActorKind.GOD&&target.source().kind()==SourceKind.DERIVED_SPEECH,"God words remain derived speech, never observed player action");
        check(god.evidence().size()==2&&god.evidence().getFirst().messageId().equals(message),"bounded earlier exact-scope dialogue context included");
        check(await(port.commitProjection(god.token(),three(god))).status()==ProjectionRecords.Status.STORED,"derived speech stores only grounded candidate interpretation");
        capture(f,PLAYER,OTHER,"다른 신에게만 한 비밀.",List.of());Work privateWork=claim(f,worker);
        check(privateWork.evidence().size()==1&&target(privateWork).observerGodId().equals(OTHER),"another observer/audience partition never inherits first conversation proof");
        var revoked=target(privateWork);
        check(await(f.store().invalidateKnowledge(f.producer(),new KnowledgeInvalidation(revoked.source(),revoked.knowledgeReceiptId(),1,"TEST_WITHDRAWAL"))).status()==RecordingRecords.Status.STORED,"actual knowledge withdrawal commits");
        check(await(port.commitProjection(privateWork.token(),three(privateWork))).status()==ProjectionRecords.Status.STALE,"withdrawn proof denies in-flight extraction");
        check(count(f,"SELECT count(*) FROM memories WHERE observer_god='test:b'")==0&&count(f,"SELECT count(*) FROM memories WHERE status='CANDIDATE'")==6,"receipt withdrawal leaves unrelated God memories intact");
        check(await(f.store().invalidate(f.producer(),new SourceInvalidation(e.source(),2,"SOURCE_WITHDRAWAL"))).status()==RecordingRecords.Status.STORED,"source withdrawal also durable");
        check(count(f,"SELECT count(*) FROM memories WHERE status='INVALIDATED'")==6,"withdrawal invalidates quoted and unquoted model input dependencies");
        check(await(port.commitProjection(work.token(),candidates)).status()==ProjectionRecords.Status.STALE,"cached retry checks authority generation after revoke");
        check(await(f.store().inspectMessage(message,4096)).orElseThrow().equals(e.text()),"projection and invalidation never delete raw message");
        await(f.store().closeAsync());
    }
    private static void contextWithdrawal(Path root)throws Exception {
        var f=fixture(root);var port=f.store().projectionPort();var worker=f.store().registerProjectionWorker("fixture","v1");
        capture(f,PLAYER,GOD,"앞에서 한 말.",List.of());Work first=claim(f,worker);await(port.commitProjection(first.token(),three(first)));
        capture(f,PLAYER,GOD,"지금 하는 말.",List.of());Work next=claim(f,worker);check(next.evidence().size()==2,"fixture includes older context");
        var old=target(first);await(f.store().invalidateKnowledge(f.producer(),new KnowledgeInvalidation(old.source(),old.knowledgeReceiptId(),1,"WITHDRAW_OLD_CONTEXT")));
        var stale=await(port.commitProjection(next.token(),three(next)));
        check(stale.status()==ProjectionRecords.Status.STALE&&stale.reasonCode().equals("CONTEXT_AUTHORITY_CHANGED"),"withdrawn context cancels draft, not valid target source");
        check(count(f,"SELECT count(*) FROM work_items WHERE kind='ROOM_KNOWLEDGE_CAPTURED' AND state='PENDING'")==1,"valid target remains durably retryable");
        sql(f,"UPDATE work_items SET next_attempt_utc=NULL");Work fresh=claim(f,worker);
        check(fresh.evidence().size()==1&&target(fresh).messageId().equals(target(next).messageId()),"fresh lease excludes revoked context and preserves target");
        check(await(port.commitProjection(fresh.token(),three(fresh))).status()==ProjectionRecords.Status.STORED,"refreshed current-authority target commits");
        check(count(f,"SELECT sum(attempt_count) FROM work_items")==0,"context revocation not charged as model failure");
        await(f.store().closeAsync());
    }
    private static void retries(Path root)throws Exception {
        var f=fixture(root);capture(f,PLAYER,GOD,"그 말은 취소할게.",List.of());var port=f.store().projectionPort();
        var worker=f.store().registerProjectionWorker("fixture","v1");
        for(int attempt=1;attempt<=3;attempt++){
            Work work=claim(f,worker);var forged=List.of(new Candidate(Layer.EVENT,ClaimKind.SPEAKER_CLAIM,List.of(new Quote(work.targetAlias(),"원문에 없는 사실")),List.of()));
            var result=await(port.commitProjection(work.token(),forged));check(result.status()==ProjectionRecords.Status.REJECTED&&result.reasonCode().equals("UNGROUNDED_PROJECTION_QUOTE"),"ungrounded quote rejected attempt "+attempt);
            check(count(f,"SELECT attempt_count FROM work_items WHERE kind='ROOM_KNOWLEDGE_CAPTURED'")==attempt,"failed model attempt durably counted "+attempt);
            check(count(f,"SELECT count(*) FROM memories")==0,"rejected result cannot partially persist");
            check(f.store().health().state()==WorldRecordingService.State.READY,"invalid model output cannot disable authoritative archive capture");
            if(attempt<3){check(await(port.claimWork(worker,budget())).status()==ProjectionRecords.Status.EMPTY,"retry respects persisted backoff");sql(f,"UPDATE work_items SET next_attempt_utc=NULL");}
        }
        check(count(f,"SELECT count(*) FROM work_items WHERE state='FAILED'")==1,"poison job bounded at three attempts");
        check(await(port.claimWork(worker,budget())).status()==ProjectionRecords.Status.EMPTY,"same extractor cannot spin forever on poison job");
        var newWorker=f.store().registerProjectionWorker("fixture","v2");
        check(await(port.claimWork(worker,budget())).status()==ProjectionRecords.Status.REJECTED,"changed extractor revokes old capability");
        Work retry=claim(f,newWorker);check(await(port.commitProjection(retry.token(),three(retry))).status()==ProjectionRecords.Status.STORED,"new extractor version can reprocess prior failed work");
        capture(f,PLAYER,GOD,"아직 처리하지 않은 다음 문장.",List.of());Work revoked=claim(f,newWorker);port.revokeWorker(newWorker);
        check(await(port.commitProjection(revoked.token(),three(revoked))).status()==ProjectionRecords.Status.STALE,"explicit worker revocation immediately rejects late result");
        await(f.store().closeAsync());
    }
    private static void restart(Path root)throws Exception {
        var f=fixture(root);UUID message=capture(f,PLAYER,GOD,"재시작해도 원문은 남아야 해.",List.of());
        var oldPort=f.store().projectionPort();var oldWorker=f.store().registerProjectionWorker("fixture","v1");Work old=claim(f,oldWorker);
        await(f.store().closeAsync());var store=await(WorldRecordingService.open(root,f.world(),SETTINGS,BOUNDARY));
        check(store.health().state()==WorldRecordingService.State.READY,"leased projection store reopens cleanly");
        var reopened=new Fixture(root,f.world(),store,store.registerProducer("room-publication-v2",Set.of("ROOM_PRIVATE"),Set.of(SourceKind.DIALOGUE_DIRECT,SourceKind.DERIVED_SPEECH)),f.conversation());
        check(await(store.projectionPort().commitProjection(old.token(),three(old))).status()==ProjectionRecords.Status.STALE,"pre-restart token has no new runtime authority");
        var worker=store.registerProjectionWorker("fixture","v1");Work work=claim(reopened,worker);
        check(target(work).messageId().equals(message),"restart recovers unacknowledged leased job without MAX checkpoint skip");
        check(await(store.projectionPort().commitProjection(work.token(),three(work))).status()==ProjectionRecords.Status.STORED,"recovered job commits once");
        capture(reopened,PLAYER,GOD,"기한이 지난 작업.",List.of());
        Work expires=await(store.projectionPort().claimWork(worker,new WorkBudget(6,16384,1))).work().orElseThrow();Thread.sleep(1100);
        check(await(store.projectionPort().commitProjection(expires.token(),three(expires))).status()==ProjectionRecords.Status.STALE,"expired lease cannot apply late model result");
        check(store.health().state()==WorldRecordingService.State.READY,"projection expiry cannot disable raw capture");
        Work newLease=claim(reopened,worker);check(target(newLease).messageId().equals(target(expires).messageId()),"expired job remains retryable with new nonce");
        check(await(store.projectionPort().finishWork(newLease.token(),WorkOutcome.DEFERRED,"FOREGROUND_PREEMPTED")).status()==ProjectionRecords.Status.STORED,"preemption durably defers without model error");
        check(count(reopened,"SELECT sum(attempt_count) FROM work_items")==0,"foreground preemption/expiry does not fabricate failed model attempts");
        await(store.closeAsync());
    }
    private static void unsupported(Path root)throws Exception {
        var f=fixture(root);UUID message=capture(f,PLAYER,GOD,"외부 원본이 필요한 발언.",List.of(new EvidencePointer("rumor","opaque")));
        var worker=f.store().registerProjectionWorker("fixture","v1");
        check(await(f.store().projectionPort().claimWork(worker,budget())).status()==ProjectionRecords.Status.EMPTY,"unsupported ancestry never becomes model input");
        check(count(f,"SELECT count(*) FROM work_items WHERE kind='ROOM_KNOWLEDGE_CAPTURED' AND state='SKIPPED_UNSUPPORTED' AND last_failure='EXTERNAL_EVIDENCE_UNSUPPORTED'")==1,"unsupported source retained as explicit diagnostic, not DONE");
        check(await(f.store().inspectMessage(message,4096)).isPresent()&&count(f,"SELECT count(*) FROM memories")==0,"unsupported projection does not erase archive or invent knowledge");
        await(f.store().closeAsync());
    }
    private static void nativeAncestry(Path root)throws Exception {
        var f=fixture(root);var port=f.store().projectionPort();var worker=f.store().registerProjectionWorker("fixture","v2");
        UUID original=capture(f,PLAYER,GOD,"내일 돌아오겠다고 약속할게.",List.of());Work first=claim(f,worker);
        await(port.commitProjection(first.token(),three(first)));
        UUID reply=capture(f,new ActorRef(ActorKind.GOD,GOD),GOD,"네가 내일 돌아오겠다는 말은 들었다.",List.of(),Set.of(original),UUID.randomUUID());
        // Simulate a pre-support skipped job, without relabelling its raw data or hearing receipts.
        sql(f,"UPDATE work_items SET state='SKIPPED_UNSUPPORTED',extractor_version='v1',last_failure='EXTERNAL_OR_ANCESTRY_EVIDENCE_UNSUPPORTED' WHERE kind='ROOM_KNOWLEDGE_CAPTURED' AND message_id='"+reply+"'");
        Work second=claim(f,worker);
        check(second.evidence().size()==2&&target(second).messageId().equals(reply)
                &&second.evidence().getFirst().messageId().equals(original),"new extractor can reprocess old unsupported native ancestry, mandatory parent precedes target");
        check(await(port.commitProjection(second.token(),three(second))).status()==ProjectionRecords.Status.STORED,"native child commits extractive candidates with original dependency");
        UUID followup=capture(f,PLAYER,GOD,"그 약속은 취소할게.",List.of(),Set.of(original,reply),UUID.randomUUID());
        Work third=claim(f,worker);
        check(third.evidence().size()==3&&third.evidence().stream().map(Evidence::messageId).distinct().count()==3
                &&target(third).messageId().equals(followup),"diamond ancestry is deduplicated while retaining every mandatory source");
        var old=target(first);await(f.store().invalidateKnowledge(f.producer(),new KnowledgeInvalidation(old.source(),old.knowledgeReceiptId(),1,"WITHDRAW_ANCESTOR")));
        check(await(port.commitProjection(third.token(),three(third))).status()==ProjectionRecords.Status.STALE,"ancestor revoked during extraction rejects the whole late draft");
        check(count(f,"SELECT count(*) FROM memories WHERE status='INVALIDATED'")==6,"original withdrawal invalidates already stored child interpretation even when child-only quote was selected");
        sql(f,"UPDATE work_items SET next_attempt_utc=NULL");
        check(await(port.claimWork(worker,budget())).status()==ProjectionRecords.Status.EMPTY
                &&count(f,"SELECT count(*) FROM work_items WHERE last_failure='NATIVE_PARENT_AUTHORITY_UNAVAILABLE'")==1,
                "retry does not launder child whose mandatory ancestor is now withdrawn");
        check(count(f,"SELECT count(*) FROM messages")==3,"ancestry processing never rewrites or erases original messages");
        await(f.store().closeAsync());
    }
    private static void rejectedAncestry(Path root)throws Exception {
        var f=fixture(root.resolve("self"));UUID self=UUID.randomUUID();
        capture(f,PLAYER,GOD,"자기 자신을 출처로 삼지 않는다.",List.of(),Set.of(self),self);
        var worker=f.store().registerProjectionWorker("fixture","v2");
        check(await(f.store().projectionPort().claimWork(worker,budget())).status()==ProjectionRecords.Status.EMPTY
                &&count(f,"SELECT count(*) FROM work_items WHERE last_failure='FORWARD_OR_CYCLIC_NATIVE_ANCESTRY'")==1,"self-cycle fails closed before model input");
        await(f.store().closeAsync());

        f=fixture(root.resolve("other-room"));var otherRoom=new Fixture(f.root(),f.world(),f.store(),f.producer(),UUID.randomUUID());
        UUID parent=capture(otherRoom,PLAYER,GOD,"다른 방의 비밀.",List.of());worker=f.store().registerProjectionWorker("fixture","v2");
        Work known=claim(f,worker);await(f.store().projectionPort().commitProjection(known.token(),three(known)));
        capture(f,PLAYER,GOD,"이 방으로 옮긴 주장.",List.of(),Set.of(parent),UUID.randomUUID());
        check(await(f.store().projectionPort().claimWork(worker,budget())).status()==ProjectionRecords.Status.EMPTY
                &&count(f,"SELECT count(*) FROM work_items WHERE last_failure='NATIVE_PARENT_SCOPE_MISMATCH'")==1,"same God cannot merge a different-room parent through new child publication");
        await(f.store().closeAsync());

        f=fixture(root.resolve("unheard"));parent=capture(f,PLAYER,OTHER,"다른 신만 들은 발언.",List.of());worker=f.store().registerProjectionWorker("fixture","v2");
        known=claim(f,worker);await(f.store().projectionPort().commitProjection(known.token(),three(known)));
        capture(f,PLAYER,GOD,"듣지 못한 출처를 인용.",List.of(),Set.of(parent),UUID.randomUUID());
        check(await(f.store().projectionPort().claimWork(worker,budget())).status()==ProjectionRecords.Status.EMPTY
                &&count(f,"SELECT count(*) FROM work_items WHERE state='PENDING' AND last_failure='AWAITING_NATIVE_PARENT_RECEIPT'")==1,"missing observer receipt defers and never invents original knowledge");
        await(f.store().closeAsync());

        f=fixture(root.resolve("budget"));worker=f.store().registerProjectionWorker("fixture","v2");var parents=new HashSet<UUID>();
        for(int i=0;i<6;i++){parents.add(capture(f,PLAYER,GOD,"독립된 원문 "+i,List.of()));known=claim(f,worker);await(f.store().projectionPort().commitProjection(known.token(),three(known)));}
        capture(f,PLAYER,GOD,"여섯 원문을 모두 참조한 대상.",List.of(),parents,UUID.randomUUID());
        check(await(f.store().projectionPort().claimWork(worker,budget())).status()==ProjectionRecords.Status.EMPTY
                &&count(f,"SELECT count(*) FROM work_items WHERE last_failure='NATIVE_ANCESTRY_SOURCE_BUDGET'")==1,"mandatory ancestry exceeding six-source input is explicitly unsupported, never silently truncated");
        await(f.store().closeAsync());
    }
    private static void quota(Path root)throws Exception {
        Files.createDirectories(root);var world=UUID.randomUUID();var settings=new RecordingSettings(RecordingSettings.Mode.RECORD_ONLY,128_000_000,2_000_000,.00001,.00002);
        var store=await(WorldRecordingService.open(root,world,settings,BOUNDARY));check(store.health().state()==WorldRecordingService.State.READY,"quota fixture opens before background check");
        var worker=store.registerProjectionWorker("fixture","v1");
        check(await(store.projectionPort().claimWork(worker,budget())).status()==ProjectionRecords.Status.DEFERRED,"background consumer respects defer ratio, not maintenance headroom");
        await(store.closeAsync());
    }
    private static void deferredFairness(Path root)throws Exception {
        var f=fixture(root);var port=f.store().projectionPort();
        for(int i=0;i<161;i++)capture(f,PLAYER,GOD,"아직 확인하지 못한 원문 "+i,List.of(),Set.of(UUID.randomUUID()),UUID.randomUUID());
        // Model the persisted eligible cohort from earlier wakeups without waiting 5 real seconds
        // between every batch. A real pending source retains this scheduled retry timestamp.
        sql(f,"UPDATE work_items SET next_attempt_utc='2000-01-01T00:00:00Z',last_failure='AWAITING_NATIVE_PARENT_RECEIPT' WHERE kind='ROOM_KNOWLEDGE_CAPTURED'");
        UUID fresh=capture(f,PLAYER,GOD,"현재 근거가 충분한 새 대화.",List.of());
        var worker=f.store().registerProjectionWorker("fixture","v2");Work found=null;int scans=0;
        // RAW/delivery generic work markers are also present. Allow their bounded scans, but make
        // every already-deferred missing-parent job due again to deterministically model sustained
        // source-probe backlog instead of depending on the machine taking five real seconds.
        for(;scans<32;scans++){
            var next=await(port.claimWork(worker,budget()));
            if(next.work().isPresent()){found=next.work().get();break;}
            check(next.status()==ProjectionRecords.Status.EMPTY,"bounded unsupported-marker scan stays nonfatal");
            sql(f,"UPDATE work_items SET next_attempt_utc='2000-01-01T00:00:00Z' WHERE state='PENDING' AND next_attempt_utc IS NOT NULL");
        }
        check(found!=null&&target(found).messageId().equals(fresh),"over161 eligible missing-proof jobs cannot starve a later fresh valid target");
        check(await(port.commitProjection(found.token(),three(found))).status()==ProjectionRecords.Status.STORED,"fresh work still commits without importing missing ancestors from optional context");
        await(f.store().closeAsync());
    }
    private static void lateAncestorReceipt(Path root)throws Exception {
        var f=fixture(root);String text="나중에 청취 확정될 원문.";UUID parent=UUID.randomUUID();
        capture(f,PLAYER,GOD,text,List.of(),Set.of(),parent,false);
        UUID child=capture(f,new ActorRef(ActorKind.GOD,GOD),GOD,"그 발언을 언급한 말.",List.of(),Set.of(parent),UUID.randomUUID());
        var port=f.store().projectionPort();var worker=f.store().registerProjectionWorker("fixture","v2");
        check(await(port.claimWork(worker,budget())).status()==ProjectionRecords.Status.EMPTY
                &&count(f,"SELECT count(*) FROM work_items WHERE last_failure='AWAITING_NATIVE_PARENT_RECEIPT'")==1,
                "planned audience without actual parent GAME_HEARD never authorizes extraction");
        var receipt=new DeliveryReceipt(UUID.randomUUID(),new ActorRef(ActorKind.GOD,GOD),"GAME_HEARD",Instant.now(),1,
                DeliveryStatus.SERVER_DISPATCHED,new DeliveryView(text,List.of(text)),Set.of(0));
        check(await(f.store().recordDeliveries(f.producer(),new DeliveryBatch(UUID.randomUUID(),parent,"late-parent",List.of(receipt))))
                .status()==RecordingRecords.Status.STORED,"actual late parent hearing receipt commits through producer port");
        Work first=claim(f,worker);check(target(first).messageId().equals(parent),"newly grounded parent work is eligible before retried child");
        await(port.commitProjection(first.token(),three(first)));sql(f,"UPDATE work_items SET next_attempt_utc=NULL");
        Work next=claim(f,worker);
        check(target(next).messageId().equals(child)&&next.evidence().size()==2,"child deferred for missing proof resumes with actual parent receipt");
        check(await(port.commitProjection(next.token(),three(next))).status()==ProjectionRecords.Status.STORED,"late acquisition is recorded without fabricated backdated hearing");
        await(f.store().closeAsync());
    }
    private static void migration(Path root)throws Exception {
        var f=fixture(root);capture(f,PLAYER,GOD,"구 버전 원문 보존.",List.of());await(f.store().closeAsync());
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+database(f));var q=db.createStatement()){
            q.execute("DROP TABLE IF EXISTS native_memory_evidence");
            q.execute("DROP TABLE IF EXISTS projection_input_manifests");
            q.execute("DROP TABLE IF EXISTS native_interpretation_evidence");
            for(String table:List.of("embedding_rows","embedding_jobs","embedding_seed_progress"))q.execute("DROP TABLE "+table);
            q.execute("DROP INDEX embedding_source_seed");
            for(String table:List.of("recording_lexical_fts","recording_lexical_manifest","recording_lexical_progress","recording_lexical_skips"))q.execute("DROP TABLE IF EXISTS "+table);
            for(String table:List.of("memory_links","memory_subjects","memory_sources","memories"))q.execute("DROP TABLE "+table);
            for(String column:List.of("extractor_version","attempt_count","next_attempt_utc","last_failure","lease_nonce"))q.execute("ALTER TABLE work_items DROP COLUMN "+column);
            q.execute("PRAGMA user_version=5");q.execute("UPDATE recording_meta SET schema_version=5");}
        var store=await(WorldRecordingService.open(root,f.world(),SETTINGS,BOUNDARY));
        check(store.health().state()==WorldRecordingService.State.READY,"schema5 upgrades to current version");
        var upgraded=new Fixture(root,f.world(),store,null,f.conversation());
        check(count(upgraded,"PRAGMA user_version")==RecordingSchema.VERSION&&count(upgraded,"SELECT count(*) FROM messages")==1,"schema migration preserves RAW and pending original work");
        Work work=claim(upgraded,store.registerProjectionWorker("fixture","v1"));
        check(await(store.projectionPort().commitProjection(work.token(),three(work))).status()==ProjectionRecords.Status.STORED,"migrated pending job gains validated projection");
        await(store.closeAsync());
    }
    private static Fixture fixture(Path root)throws Exception{Files.createDirectories(root);UUID world=UUID.randomUUID();var store=await(WorldRecordingService.open(root,world,SETTINGS,BOUNDARY));
        check(store.health().state()==WorldRecordingService.State.READY,"fixture READY: "+store.health().reasonCode());
        var producer=store.registerProducer("room-publication-v2",Set.of("ROOM_PRIVATE"),Set.of(SourceKind.DIALOGUE_DIRECT,SourceKind.DERIVED_SPEECH));
        return new Fixture(root,world,store,producer,UUID.randomUUID());}
    private static UUID capture(Fixture f,ActorRef actor,String god,String text,List<EvidencePointer> evidence)throws Exception {
        return capture(f,actor,god,text,evidence,Set.of(),UUID.randomUUID());
    }
    private static UUID capture(Fixture f,ActorRef actor,String god,String text,List<EvidencePointer> evidence,Set<UUID> parents,UUID message)throws Exception {
        return capture(f,actor,god,text,evidence,parents,message,true);
    }
    private static UUID capture(Fixture f,ActorRef actor,String god,String text,List<EvidencePointer> evidence,Set<UUID> parents,UUID message,boolean heardGod)throws Exception {
        var observer=new ActorRef(ActorKind.GOD,god);var audience=new HashSet<>(Set.of(PLAYER,observer));audience.add(actor);
        var context=new PublicationContext(f.store().runtimeEpoch(),1,"STANDARD",audience,audience,Map.of(),evidence,parents,Map.of(),"UNKNOWN","PERSONAL");
        var envelope=new ConversationEnvelope(f.world(),f.store().datasetId().orElseThrow(),f.conversation(),"ROOM_PRIVATE","ACTUAL_LISTENERS_ONLY",1,1,true,false,"test");
        Instant now=Instant.now();var raw=new RawMessage(message,Optional.empty(),0,actor,text,now,actor.kind()==ActorKind.PLAYER?MessageKind.ACCEPTED_INPUT:MessageKind.DELIVERED_OUTPUT,message.toString(),context);
        var view=new DeliveryView(text,List.of(text));var deliveries=new ArrayList<DeliveryReceipt>();
        for(var member:audience)if(heardGod||member.kind()!=ActorKind.GOD)
            deliveries.add(new DeliveryReceipt(UUID.randomUUID(),member,member.kind()==ActorKind.GOD?"GAME_HEARD":"CHAT",now,1,DeliveryStatus.SERVER_DISPATCHED,view,Set.of(0)));
        check(await(f.store().capture(f.producer(),envelope,raw,deliveries)).status()==RecordingRecords.Status.STORED,"native fixture capture committed");return message;
    }
    private static Work claim(Fixture f,ProjectionWorkerCapability worker)throws Exception{var result=await(f.store().projectionPort().claimWork(worker,budget()));check(result.status()==ProjectionRecords.Status.CLAIMED,"claim succeeds: "+result);return result.work().orElseThrow();}
    private static Evidence target(Work work){return work.evidence().stream().filter(e->e.alias().equals(work.targetAlias())).findFirst().orElseThrow();}
    private static List<Candidate> three(Work work){var quote=new Quote(work.targetAlias(),target(work).text());return List.of(new Candidate(Layer.EVENT,ClaimKind.DIALOGUE_EPISODE,List.of(quote),List.of()),new Candidate(Layer.RELATIONSHIP,ClaimKind.DIALOGUE_EPISODE,List.of(quote),List.of()),new Candidate(Layer.SUMMARY,ClaimKind.DIALOGUE_EPISODE,List.of(quote),List.of()));}
    private static WorkBudget budget(){return new WorkBudget(6,16384,45);}
    private static Path database(Fixture f){return f.root().resolve("mythictrpg-recording-v2").resolve(f.store().datasetId().orElseThrow().toString()).resolve("recording.sqlite");}
    private static long count(Fixture f,String query)throws Exception{try(var db=DriverManager.getConnection("jdbc:sqlite:"+database(f));var q=db.createStatement();var row=q.executeQuery(query)){row.next();return row.getLong(1);}}
    private static void sql(Fixture f,String query)throws Exception{try(var db=DriverManager.getConnection("jdbc:sqlite:"+database(f));var q=db.createStatement()){q.execute(query);}}
    private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(20,TimeUnit.SECONDS);}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
