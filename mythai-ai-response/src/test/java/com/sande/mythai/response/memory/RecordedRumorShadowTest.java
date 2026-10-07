package com.sande.mythai.response.memory;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import com.sande.mythictrpg.rumor.NativeRumorReadAccess;
import com.sande.mythictrpg.rumor.ReputationLedger;
import java.util.*;
import java.util.concurrent.*;

/** No models/world. Only consumer contract, bounded diagnostics and independently changing assessments. */
public final class RecordedRumorShadowTest {
    private static int checks;
    private static final UUID ROOT=UUID.randomUUID(),PLAYER=UUID.randomUUID(),LINEAGE=UUID.randomUUID();
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static RumorReadRecords.Entry entry(NativeRumorReadAccess.Assessment assessment){
        return new RumorReadRecords.Entry(new SourceRef(UUID.randomUUID(),UUID.randomUUID(),SourceKind.RUMOR_RECEIVED,
                "rumor-saved-data-v1",ROOT.toString(),1,"a".repeat(64)),UUID.randomUUID(),LINEAGE,"test:athena",PLAYER,
                "그가 문을 열어 주었다는 소문", "도움을 준 자",Set.of(PLAYER),"CAUTIOUS",assessment);
    }
    private static final class Session implements MemoryReadSession {
        final List<RumorReadRecords.Page> issued=new ArrayList<>();
        final List<CompletableFuture<RumorReadRecords.Page>> pending=new ArrayList<>();
        boolean failure,unsupported,tooMany,async,assessmentChanged;
        NativeRumorReadAccess.Assessment assessment=NativeRumorReadAccess.Assessment.unassessed();
        @Override public CompletableFuture<Page> query(Query query,Optional<Cursor> cursor,Budget budget){throw new AssertionError("a rumor is not a fabricated direct utterance");}
        @Override public boolean current(Page page){throw new AssertionError("no fake raw pages");}
        @Override public CompletableFuture<RumorReadRecords.Page> rumors(Query query,Optional<RumorReadRecords.Cursor> cursor,Budget budget){
            check(query.text().isEmpty()&&query.fromInclusive().isEmpty()&&query.untilExclusive().isEmpty()&&query.actorSelection().isAny(),
                    "bounded recent claim lookup, no unsupported actor/UTC reinterpretation");
            check(issued.isEmpty()?cursor.isEmpty():cursor.equals(issued.getLast().next()),"exact opaque continuation");
            check(budget.rows()<=4&&budget.utf8Bytes()<=8192,"bounded call budget");
            if(failure)return CompletableFuture.failedFuture(new IllegalStateException("fixture"));
            var entries=unsupported?List.<RumorReadRecords.Entry>of():tooMany?Collections.nCopies(8,entry(assessment)):List.of(entry(assessment));
            var page=new RumorReadRecords.Page(unsupported?Status.UNAVAILABLE:Status.PARTIAL,entries,Optional.of(RumorReadRecords.Cursor.unregistered()));
            issued.add(page);
            if(async){var future=new CompletableFuture<RumorReadRecords.Page>();pending.add(future);return future;}
            return CompletableFuture.completedFuture(page);
        }
        @Override public boolean current(RumorReadRecords.Page page){
            return !assessmentChanged&&issued.stream().anyMatch(p->p==page);
        }
    }
    public static void main(String[] args){
        for(var assessment:List.of(NativeRumorReadAccess.Assessment.unknown(),NativeRumorReadAccess.Assessment.unassessed(),
                new NativeRumorReadAccess.Assessment(NativeRumorReadAccess.AssessmentAvailability.ASSESSED,Optional.of(ReputationLedger.Outcome.RECOVERED),2))){
            var s=new Session();s.assessment=assessment;var before=RecordedRumorShadow.diagnostics();
            RecordedRumorShadow.compare(()->Optional.of(s),Set.of(ROOT),Runnable::run);
            var after=RecordedRumorShadow.diagnostics();
            check(after.get("completed")==before.get("completed")+1&&s.issued.size()==3,"all typed assessments remain diagnostic-only");
            check(after.get("legacyMatches")==before.get("legacyMatches")+1,"same root is deduplicated, not counted as repeated evidence");
            check(RumorReadRecords.wireByteSize(entry(assessment))>0,"full-card wire budget handles Optional outcome without reflection");
        }
        for(int error=0;error<3;error++){
            var s=new Session();s.failure=error==0;s.unsupported=error==1;s.tooMany=error==2;var before=RecordedRumorShadow.diagnostics();
            RecordedRumorShadow.compare(()->Optional.of(s),Set.of(ROOT),Runnable::run);
            var after=RecordedRumorShadow.diagnostics();
            check(after.get("unavailable")==before.get("unavailable")+1&&after.get("completed").equals(before.get("completed")),"failed/unsupported/oversized pages cannot complete");
        }
        var old=new Session();old.async=true;var independent=new Session();independent.async=true;
        var before=RecordedRumorShadow.diagnostics();
        RecordedRumorShadow.compare(()->Optional.of(old),Set.of(ROOT),Runnable::run);
        RecordedRumorShadow.compare(()->Optional.of(independent),Set.of(ROOT),Runnable::run);
        old.pending.getFirst().complete(old.issued.getFirst());
        old.assessmentChanged=true; // Game reputation changed independently from archive generation.
        old.pending.get(1).complete(old.issued.get(1));
        for(int i=0;i<3;i++)independent.pending.get(i).complete(independent.issued.get(i));
        var after=RecordedRumorShadow.diagnostics();
        check(after.get("unavailable")==before.get("unavailable")+1,"later valid source pages cannot launder an older assessment");
        check(after.get("completed")==before.get("completed")+1,"independent room/god comparison remains usable");
        before=RecordedRumorShadow.diagnostics();
        RecordedRumorShadow.compare(Optional::empty,Set.of(ROOT),Runnable::run);
        check(RecordedRumorShadow.diagnostics().equals(before),"absent optional authority does nothing");
        RecordedRumorShadow.compare(()->{throw new IllegalStateException("offline");},Set.of(),Runnable::run);
        check(RecordedRumorShadow.diagnostics().get("unavailable")==before.get("unavailable")+1,"optional opening failure does not block normal dialogue");
        var s=new Session();before=RecordedRumorShadow.diagnostics();
        RecordedRumorShadow.compare(()->Optional.of(s),Set.of(),task->{throw new RejectedExecutionException("stopped");});
        check(RecordedRumorShadow.diagnostics().get("unavailable")==before.get("unavailable")+1,"closed dispatcher is isolated");
        check(RecordedRumorShadow.diagnostics().keySet().equals(Set.of("completed","unavailable","legacySelected","legacyMatches")),
                "no rumor bodies, subject identifiers, judgments or secret existence in diagnostics");
        System.out.println("RecordedRumorShadowTest: "+checks+" checks passed; no server/LLM");
    }
}
