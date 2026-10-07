package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.experiencecontract.ExperienceView;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.MemoryReadSession.*;
import com.sande.mythictrpg.recording.api.MemoryReadSession.Status;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import com.sande.mythictrpg.rumor.NativeRumorReadAccess;
import net.minecraft.resources.ResourceLocation;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** No model/DB/server. Fake issued pages exercise only the typed consumer's bound/cancellation contract. */
public final class RecordedRetrievalCoordinatorTest {
    private static int checks;
    private static final UUID PLAYER=UUID.randomUUID(),WORLD=UUID.randomUUID(),DATASET=UUID.randomUUID(),FIRST=UUID.randomUUID(),SECOND=UUID.randomUUID();
    private static final ResourceLocation GOD=ResourceLocation.parse("test:athena");
    private static final ActorRef ACTOR=new ActorRef(ActorKind.PLAYER,PLAYER.toString());
    private static final Instant AT=Instant.parse("2026-01-01T00:00:00Z");
    private static final Query QUERY=new Query("약속",Optional.empty(),Optional.empty());
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static Request request(){return new Request(UUID.randomUUID(),2,UUID.randomUUID(),PLAYER,"fixture",List.of(GOD),GOD,"약속",List.of(),false,true,false,
            List.of(new GodState(GOD,"R_NEUTRAL","E_NEUTRAL","",null)),false,Set.of(PLAYER));}
    private static RecordedRetrievalCoordinator.SemanticQuery vector(Query query){return new RecordedRetrievalCoordinator.SemanticQuery(
            new EmbeddingRecords.QueryVector(new EmbeddingRecords.ModelSpace("fixture","a".repeat(64),2,EmbeddingRecords.ENCODER_VERSION),
                    RecordingRecords.sha256(query.text()),new float[]{1,0}),.75);}
    private static Entry raw(UUID id,String text){return new Entry(id,ACTOR,AT,text,false);}
    private static SemanticReadRecords.Entry semantic(UUID id,String text,double score){return new SemanticReadRecords.Entry(id,ACTOR,AT,text,score,text.length(),text.length());}
    private static InterpretationReadRecords.Entry interpretation(String text){return new InterpretationReadRecords.Entry(UUID.randomUUID(),ProjectionRecords.Layer.EVENT,
            ProjectionRecords.ClaimKind.CORRECTION_OR_EXPLANATION,"fixture-v1",List.of(new InterpretationReadRecords.Quote("e0",SECOND,ACTOR,AT,text)),
            List.of(new InterpretationReadRecords.Link("e0","e1",ProjectionRecords.Relation.CANCELS)),
            List.of(new InterpretationReadRecords.Coverage("e0",SECOND,text.length(),text.length()),new InterpretationReadRecords.Coverage("e1",FIRST,2,2)));}
    private static SourceRef source(SourceKind kind,String owner,UUID id){return new SourceRef(WORLD,DATASET,kind,owner,id.toString(),1,"b".repeat(64));}
    private static ObservationReadRecords.Entry observation(){var id=UUID.randomUUID();return new ObservationReadRecords.Entry(source(SourceKind.ACTION_OBSERVED,"action-ledger-v1",id),
            UUID.randomUUID(),GOD.toString(),PLAYER,new ExperienceView.Event(UUID.randomUUID(),id,1,"DIRECT_WATCH","MATURE_CROP_REMOVED","minecraft:wheat",
                    "BLOCK_REMOVED_NOT_ITEM_ACQUISITION","tick:10"));}
    private static RumorReadRecords.Entry rumor(){return new RumorReadRecords.Entry(source(SourceKind.RUMOR_RECEIVED,"rumor-saved-data-v1",UUID.randomUUID()),UUID.randomUUID(),
            UUID.randomUUID(),GOD.toString(),PLAYER,"들었다는 주장","별칭",Set.of(PLAYER),"CAUTIOUS",NativeRumorReadAccess.Assessment.unknown());}
    private static final class Session implements MemoryReadSession {
        final Set<Object> issued=Collections.newSetFromMap(new IdentityHashMap<>()),revoked=Collections.newSetFromMap(new IdentityHashMap<>());
        final List<String> order=new ArrayList<>();final Deque<Runnable> completions=new ArrayDeque<>();
        final List<Page> rawPages=new ArrayList<>();final List<SemanticReadRecords.Page> semanticPages=new ArrayList<>();
        final List<Object> seeds=new ArrayList<>();final List<Budget> budgets=new ArrayList<>();
        Query expected=QUERY;boolean delayed,next,revokeAll,failRaw,throwRaw,extraRows,oversizedRaw,emptyRaw,lowSemantic;
        boolean failInterpret,failSecondInterpret;int active,maxActive,rawCount,semanticCount,observationCount,rumorCount,interpretCount;
        String rawText="처음 약속";InterpretationReadRecords.Entry interpretation=interpretation("약속은 취소");
        <P>CompletableFuture<P> issue(String lane,P page,Budget budget){
            check(active==0,"reads are strictly sequential");active++;maxActive=Math.max(maxActive,active);order.add(lane);budgets.add(budget);issued.add(page);
            var f=new CompletableFuture<P>();Runnable done=()->{active--;f.complete(page);};if(delayed)completions.add(done);else done.run();return f;
        }
        public CompletableFuture<Page> query(Query query,Optional<Cursor> cursor,Budget budget){
            check(query.equals(expected),"exact query/actor/time metadata retained");rawCount++;
            if(throwRaw)throw new IllegalStateException("fixture");if(failRaw){order.add("RAW");return CompletableFuture.failedFuture(new IllegalStateException("fixture"));}
            if(cursor.isPresent())check(rawPages.stream().anyMatch(p->p.next().equals(cursor)),"raw cursor belongs to original session/page");
            var entries=emptyRaw?List.<Entry>of():List.of(raw(rawCount==1?FIRST:UUID.randomUUID(),oversizedRaw?"가".repeat(2000):rawText));
            if(extraRows)entries=Collections.nCopies(budget.rows()+1,entries.getFirst());
            var page=new Page(Status.PARTIAL,entries,next?Optional.of(Cursor.unregistered()):Optional.empty());rawPages.add(page);return issue("RAW",page,budget);
        }
        public boolean current(Page page){return valid(page);}
        public CompletableFuture<SemanticReadRecords.Page> semantic(Query query,EmbeddingRecords.QueryVector vector,Optional<SemanticReadRecords.Cursor> cursor,Budget budget){
            check(query.equals(expected)&&vector.inputHash().equals(RecordingRecords.sha256(query.text())),"semantic exact plan and vector hash");semanticCount++;
            if(cursor.isPresent())check(semanticPages.stream().anyMatch(p->p.next().equals(cursor)),"semantic cursor stays native");
            var page=new SemanticReadRecords.Page(Status.PARTIAL,List.of(RecordedRetrievalCoordinatorTest.semantic(SECOND,"약속은 취소",lowSemantic?.1:.95)),
                    next?Optional.of(SemanticReadRecords.Cursor.unregistered()):Optional.empty());semanticPages.add(page);return issue("SEMANTIC",page,budget);
        }
        public boolean current(SemanticReadRecords.Page page){return valid(page);}
        public CompletableFuture<InterpretationReadRecords.Page> interpretations(Page seed,Optional<InterpretationReadRecords.Cursor> cursor,Budget budget){
            check(rawPages.stream().anyMatch(p->p==seed),"raw interpretation uses actual issued raw page");seeds.add(seed);return interpret(budget);}
        public CompletableFuture<InterpretationReadRecords.Page> interpretations(SemanticReadRecords.Page seed,Optional<InterpretationReadRecords.Cursor> cursor,Budget budget){
            check(semanticPages.stream().anyMatch(p->p==seed),"semantic interpretation never fabricates raw page");seeds.add(seed);return interpret(budget);}
        private CompletableFuture<InterpretationReadRecords.Page> interpret(Budget budget){
            interpretCount++;
            if(failInterpret||failSecondInterpret&&interpretCount==2){order.add("INTERPRETATION");return CompletableFuture.failedFuture(new IllegalStateException("fixture"));}
            return issue("INTERPRETATION",new InterpretationReadRecords.Page(Status.PARTIAL,List.of(interpretation),
                    next?Optional.of(InterpretationReadRecords.Cursor.unregistered()):Optional.empty()),budget);}
        public boolean current(InterpretationReadRecords.Page page){return valid(page);}
        public CompletableFuture<ObservationReadRecords.Page> observations(Query query,Optional<ObservationReadRecords.Cursor> cursor,Budget budget){
            check(query.equals(expected),"Watch never silently widens query");observationCount++;
            return issue("OBSERVATION",new ObservationReadRecords.Page(Status.PARTIAL,List.of(observation()),Optional.empty()),budget);}
        public boolean current(ObservationReadRecords.Page page){return valid(page);}
        public CompletableFuture<RumorReadRecords.Page> rumors(Query query,Optional<RumorReadRecords.Cursor> cursor,Budget budget){
            check(query.equals(expected),"Rumor never silently widens query");rumorCount++;
            return issue("RUMOR",new RumorReadRecords.Page(Status.PARTIAL,List.of(rumor()),Optional.empty()),budget);}
        public boolean current(RumorReadRecords.Page page){return valid(page);}
        boolean valid(Object page){return !revokeAll&&!revoked.contains(page)&&issued.contains(page);}
        void drain(){while(!completions.isEmpty())completions.removeFirst().run();}
    }
    private static CompletableFuture<RecordedRetrievalBundle> collect(Session session,Request request,Query query,boolean semantic,
            RecordedRetrievalCoordinator.Options options,Consumer<Runnable> dispatch,List<Runnable> timers){
        session.expected=query;
        return RecordedRetrievalCoordinator.collect(session,request,new RecordedRecallQuery.Prepared(query,semantic),
                semantic?Optional.of(vector(query)):Optional.empty(),options,dispatch,timers::add);
    }
    private static RecordedRetrievalBundle run(Session session){return collect(session,request(),QUERY,true,RecordedRetrievalCoordinator.Options.defaults(true,true),Runnable::run,new ArrayList<>()).join();}
    public static void main(String[] args){
        var normal=new Session();var request=request();var bundle=collect(normal,request,QUERY,true,
                RecordedRetrievalCoordinator.Options.defaults(true,true),Runnable::run,new ArrayList<>()).join();
        check(bundle.current()&&bundle.scope().equals(RecordedRetrievalBundle.Scope.of(request)),"exact request scope bound to typed bundle");
        check(normal.order.equals(List.of("RAW","SEMANTIC","OBSERVATION","RUMOR","INTERPRETATION","INTERPRETATION")),"single scheduler fairly visits initial lanes then typed seeds");
        check(bundle.calls()==6&&normal.maxActive==1,"one session and six sequential calls, no extra legacy queries");
        check(bundle.raw().entries().size()==1&&bundle.semantic().entries().size()==1&&bundle.interpretations().entries().size()==1
                &&bundle.observations().entries().size()==1&&bundle.rumors().entries().size()==1,"all typed cards retained and duplicate interpretation deduplicated");
        check(bundle.interpretations().entries().getFirst().inputs().size()==2&&bundle.interpretations().entries().getFirst().links().size()==1,"correction entire inputs and links retained");
        int bytes=java.util.stream.Stream.of(bundle.raw().entries(),bundle.semantic().entries(),bundle.interpretations().entries(),bundle.observations().entries(),bundle.rumors().entries())
                .flatMap(Collection::stream).mapToInt(RecordedRetrievalBundle::wireByteSize).sum();
        check(bytes==bundle.utf8Bytes()&&bytes<=32768,"whole-card bytes include typed metadata, not just quoted text");
        check(RecordedRetrievalBundle.card(bundle.rumors().entries().getFirst()).toString().contains("UNKNOWN"),"unknown assessment preserved rather than invented");
        normal.revoked.add(normal.rawPages.getFirst());check(!bundle.current(),"issued original page withdrawal invalidates completed bundle");
        try{bundle.raw().entries().clear();throw new AssertionError("mutable entries");}catch(UnsupportedOperationException expected){checks++;}

        var bounded=new Session();bounded.next=true;bundle=run(bounded);
        check(bounded.order.size()<=8&&bundle.calls()==8,"all lane continuations share total eight-call cap");
        check(bounded.observationCount==1&&bounded.rumorCount==1,"early pagination does not starve typed lanes");
        check(bundle.raw().status()==Status.PARTIAL&&bundle.semantic().status()==Status.PARTIAL,"bounded pagination never claims complete recall");
        var one=new Session();bundle=collect(one,request(),QUERY,true,new RecordedRetrievalCoordinator.Options(1,4096,4,2,15000,true,true),Runnable::run,new ArrayList<>()).join();
        check(bundle.calls()==1&&!bundle.semantic().attempted()&&bundle.semantic().status()==Status.UNAVAILABLE
                &&!bundle.interpretations().attempted()&&!bundle.observations().attempted(),"unvisited lanes are unavailable, not empty");

        for(Query restricted:List.of(new Query("약속",Optional.empty(),Optional.empty(),new ActorSelection(Optional.of(ActorKind.PLAYER),Set.of(),Set.of())),
                new Query("약속",Optional.of(AT),Optional.empty()),new Query("약속",Optional.empty(),Optional.of(AT)))){
            var s=new Session();bundle=collect(s,request(),restricted,true,RecordedRetrievalCoordinator.Options.defaults(true,true),Runnable::run,new ArrayList<>()).join();
            check(s.observationCount==0&&s.rumorCount==0&&!bundle.observations().attempted()&&!bundle.rumors().attempted(),"unsupported typed actor/time filters never broaden to recent ANY");
        }
        var noSemantic=new Session();bundle=collect(noSemantic,request(),QUERY,false,RecordedRetrievalCoordinator.Options.defaults(false,false),Runnable::run,new ArrayList<>()).join();
        check(noSemantic.semanticCount==0&&!bundle.semantic().attempted(),"no vector means no inference/fallback invented by coordinator");
        var low=new Session();low.lowSemantic=true;bundle=run(low);
        check(bundle.semantic().entries().isEmpty()&&low.seeds.stream().noneMatch(SemanticReadRecords.Page.class::isInstance),"weak semantic page is not an interpretation seed");
        var mismatch=new Session();var wrongVector=vector(new Query("다른 질문",Optional.empty(),Optional.empty()));
        bundle=RecordedRetrievalCoordinator.collect(mismatch,request(),new RecordedRecallQuery.Prepared(QUERY,true),Optional.of(wrongVector),
                RecordedRetrievalCoordinator.Options.defaults(false,false),Runnable::run,t->{}).join();
        check(mismatch.semanticCount==0&&bundle.semantic().status()==Status.UNAVAILABLE,"mismatched model query hash does not enter lane");

        for(int mode=0;mode<4;mode++){
            var failure=new Session();failure.failRaw=mode==0;failure.throwRaw=mode==1;failure.extraRows=mode==2;failure.oversizedRaw=mode==3;
            bundle=collect(failure,request(),QUERY,true,new RecordedRetrievalCoordinator.Options(8,4096,4,2,15000,false,false),Runnable::run,new ArrayList<>()).join();
            check(bundle.raw().entries().isEmpty()&&bundle.raw().status()==Status.UNAVAILABLE,"malformed/failed lane never enters bundle");
            check(failure.semanticCount==1,"independent lawful semantic lane survives raw failure without widening");
        }
        var tiny=new Session();tiny.rawText="가".repeat(50);bundle=collect(tiny,request(),QUERY,false,
                new RecordedRetrievalCoordinator.Options(8,256,4,2,15000,false,false),Runnable::run,new ArrayList<>()).join();
        check(bundle.raw().entries().isEmpty()&&bundle.raw().status()==Status.UNAVAILABLE&&bundle.utf8Bytes()==0&&tiny.rawCount==0,
                "tiny budget cannot safely admit body plus worst-case metadata/escaping, never crops a card");
        var lostCorrection=new Session();lostCorrection.failSecondInterpret=true;bundle=run(lostCorrection);
        check(bundle.interpretations().status()==Status.UNAVAILABLE&&bundle.interpretations().entries().isEmpty()
                &&bundle.raw().entries().isEmpty()&&bundle.semantic().entries().isEmpty(),"later interpretation failure cannot leave an already-known cancelled promise alone");
        var repeated=new Session();repeated.next=true;repeated.lowSemantic=true;bundle=run(repeated);
        int firstInterpret=repeated.order.indexOf("INTERPRETATION"),secondInterpret=repeated.order.subList(firstInterpret+1,repeated.order.size()).indexOf("INTERPRETATION")+firstInterpret+1;
        check(secondInterpret>firstInterpret&&repeated.budgets.get(secondInterpret).utf8Bytes()<repeated.budgets.get(firstInterpret).utf8Bytes()
                -RecordedRetrievalBundle.wireByteSize(repeated.interpretation),"duplicate and rejected semantic response cards still consume shared returned-byte budget");

        var a=new Session();a.delayed=true;var b=new Session();b.delayed=true;var at=new ArrayList<Runnable>();var bt=new ArrayList<Runnable>();
        var ar=request();var br=request();var af=collect(a,ar,QUERY,true,RecordedRetrievalCoordinator.Options.defaults(true,true),Runnable::run,at);
        var bf=collect(b,br,QUERY,true,RecordedRetrievalCoordinator.Options.defaults(true,true),Runnable::run,bt);
        a.revokeAll=true;a.drain();b.drain();
        check(!af.join().current()&&af.join().raw().entries().isEmpty()&&bf.join().current(),"Session A stale cannot poison Session B");
        check(!bf.join().scope().equals(RecordedRetrievalBundle.Scope.of(ar))&&bf.join().scope().equals(RecordedRetrievalBundle.Scope.of(br)),"separate turn/room scopes remain distinct");
        var late=new Session();late.delayed=true;var timers=new ArrayList<Runnable>();var future=collect(late,request(),QUERY,true,RecordedRetrievalCoordinator.Options.defaults(true,true),Runnable::run,timers);
        timers.getFirst().run();late.drain();check(!future.join().current()&&future.join().raw().status()==Status.UNAVAILABLE&&late.order.size()==1,"timeout blocks late result and additional read");
        var cancelled=new Session();cancelled.delayed=true;future=collect(cancelled,request(),QUERY,true,RecordedRetrievalCoordinator.Options.defaults(true,true),Runnable::run,new ArrayList<>());
        future.cancel(false);cancelled.drain();check(future.isCancelled()&&cancelled.order.size()==1,"cancelled waiter cannot schedule later work");
        var rejected=new Session();future=collect(rejected,request(),QUERY,true,RecordedRetrievalCoordinator.Options.defaults(true,true),r->{throw new RejectedExecutionException();},new ArrayList<>());
        check(!future.join().current()&&rejected.order.size()==1,"dispatch rejection fails closed without leaking page");
        var queued=new Session();var callbacks=new ArrayDeque<Runnable>();future=collect(queued,request(),QUERY,true,RecordedRetrievalCoordinator.Options.defaults(true,true),callbacks::add,new ArrayList<>());
        queued.revokeAll=true;callbacks.removeFirst().run();check(!future.join().current()&&queued.order.size()==1,"stale between read completion and game dispatch is denied");
        for(int invalid:new int[]{0,9})try{new RecordedRetrievalCoordinator.Options(invalid,4096,4,2,1000,true,true);throw new AssertionError("invalid options");}
            catch(IllegalArgumentException expected){checks++;}
        System.out.println("RecordedRetrievalCoordinatorTest: "+checks+" checks passed");
    }
}
