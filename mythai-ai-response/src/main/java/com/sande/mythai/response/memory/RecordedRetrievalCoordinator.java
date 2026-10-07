package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.MemoryReadSession.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One bounded, sequential native retrieval pass. SHADOW/fixtures only; no model, legacy search or prompt injection. */
public final class RecordedRetrievalCoordinator {
    public record SemanticQuery(EmbeddingRecords.QueryVector vector,double minimumSimilarity) {
        public SemanticQuery { Objects.requireNonNull(vector);if(!Double.isFinite(minimumSimilarity)||minimumSimilarity< -1||minimumSimilarity>1)
            throw new IllegalArgumentException("SEMANTIC_THRESHOLD"); }
    }
    public record Options(int maxCalls,int maxBytes,int maxRowsPerLane,int maxPagesPerLane,long timeoutMillis,
                          boolean includeObservations,boolean includeRumors) {
        public Options {if(maxCalls<1||maxCalls>8||maxBytes<256||maxBytes>65536||maxRowsPerLane<1||maxRowsPerLane>8
                ||maxPagesPerLane<1||maxPagesPerLane>3||timeoutMillis<1||timeoutMillis>30000)throw new IllegalArgumentException("RETRIEVAL_OPTIONS");}
        public static Options defaults(boolean observations,boolean rumors){return new Options(8,32768,4,2,15000,observations,rumors);}
    }
    private RecordedRetrievalCoordinator() { }
    public static CompletableFuture<RecordedRetrievalBundle> collect(MemoryReadSession session,Request request,
            RecordedRecallQuery.Prepared prepared,Optional<SemanticQuery> semantic,Options options,Consumer<Runnable> dispatch) {
        return collect(session,request,prepared,semantic,options,dispatch,action->CompletableFuture.delayedExecutor(options.timeoutMillis(),TimeUnit.MILLISECONDS).execute(action));
    }
    /** Injected timer is for deterministic timeout/cancellation fixtures, not another worker pool. */
    static CompletableFuture<RecordedRetrievalBundle> collect(MemoryReadSession session,Request request,
            RecordedRecallQuery.Prepared prepared,Optional<SemanticQuery> semantic,Options options,Consumer<Runnable> dispatch,Consumer<Runnable> timer) {
        var run=new Run(session,request,prepared,semantic,options,dispatch);
        try {timer.accept(()->run.timeout());run.next();}catch(RuntimeException failure){run.fail(Status.UNAVAILABLE);}
        return run.result;
    }
    /** Explicit foreground preparation only. Never called by SHADOW; collection itself does not write a seal. */
    public static CompletableFuture<NativeSpeechCollection> collectNativeSpeech(MemoryReadSession session,Request request,
            RecordedRecallQuery.Prepared prepared,Optional<SemanticQuery> semantic,Options options,Consumer<Runnable> dispatch) {
        return collectNativeSpeech(session,request,prepared,semantic,options,dispatch,
                action->CompletableFuture.delayedExecutor(options.timeoutMillis(),TimeUnit.MILLISECONDS).execute(action));
    }
    static CompletableFuture<NativeSpeechCollection> collectNativeSpeech(MemoryReadSession session,Request request,
            RecordedRecallQuery.Prepared prepared,Optional<SemanticQuery> semantic,Options options,Consumer<Runnable> dispatch,Consumer<Runnable> timer) {
        var run=new Run(session,request,prepared,semantic,options,dispatch,true);
        var result=new CompletableFuture<NativeSpeechCollection>();
        result.whenComplete((value,failure)->{if(result.isCancelled())run.result.cancel(false);});
        run.result.whenComplete((bundle,failure)->{
            if(failure!=null)result.completeExceptionally(failure);
            else if(bundle.current())result.complete(new NativeSpeechCollection(session,bundle,run.issuedRaw,run.issuedSemantic,dispatch));
            else result.complete(new NativeSpeechCollection(session,bundle,List.of(),List.of(),dispatch));
        });
        try {timer.accept(run::timeout);run.next();}catch(RuntimeException failure){run.fail(Status.UNAVAILABLE);}
        return result;
    }
    /** Opaque actual-page envelope. It cannot be made from a Bundle, entry IDs, cloned pages or rendered text. */
    public static final class NativeSpeechCollection {
        private final MemoryReadSession session;
        private final RecordedRetrievalBundle bundle;
        private final List<Page> raw;
        private final List<SemanticReadRecords.Page> semantic;
        private final Consumer<Runnable> dispatch;
        private NativeSpeechCollection(MemoryReadSession session,RecordedRetrievalBundle bundle,List<Page> raw,
                List<SemanticReadRecords.Page> semantic,Consumer<Runnable> dispatch) {
            this.session=session;this.bundle=bundle;this.raw=List.copyOf(raw);this.semantic=List.copyOf(semantic);this.dispatch=dispatch;
        }
        public RecordedRetrievalBundle.Scope scope(){return bundle.scope();}
        public boolean current(){return bundle.current();}
        RecordedRetrievalBundle bundle(){return bundle;}
        List<Page> rawPages(){return raw;}
        List<SemanticReadRecords.Page> semanticPages(){return semantic;}
        MemoryReadSession issuer(){return session;}
        Consumer<Runnable> dispatcher(){return dispatch;}
        @Override public String toString(){return "NativeSpeechCollection[current="+current()+"]";}
    }
    /** Explicit typed foreground collection. Same pass/budgets; no Watch/Rumor reads or automatic issuance. */
    public static CompletableFuture<NativeInterpretationCollection> collectNativeInterpretations(MemoryReadSession session,Request request,
            RecordedRecallQuery.Prepared prepared,Optional<SemanticQuery> semantic,Options options,Consumer<Runnable> dispatch) {
        return collectNativeInterpretations(session,request,prepared,semantic,options,dispatch,
                action->CompletableFuture.delayedExecutor(options.timeoutMillis(),TimeUnit.MILLISECONDS).execute(action));
    }
    static CompletableFuture<NativeInterpretationCollection> collectNativeInterpretations(MemoryReadSession session,Request request,
            RecordedRecallQuery.Prepared prepared,Optional<SemanticQuery> semantic,Options options,Consumer<Runnable> dispatch,Consumer<Runnable> timer) {
        var run=new Run(session,request,prepared,semantic,options,dispatch,true);
        var result=new CompletableFuture<NativeInterpretationCollection>();
        result.whenComplete((value,failure)->{if(result.isCancelled())run.result.cancel(false);});
        run.result.whenComplete((bundle,failure)->{
            if(failure!=null)result.completeExceptionally(failure);
            else if(bundle.current())result.complete(new NativeInterpretationCollection(session,bundle,run.issuedRaw,run.issuedSemantic,run.issuedInterpretations,dispatch));
            else result.complete(new NativeInterpretationCollection(session,bundle,List.of(),List.of(),List.of(),dispatch));
        });
        try {timer.accept(run::timeout);run.next();}catch(RuntimeException failure){run.fail(Status.UNAVAILABLE);}
        return result;
    }
    /** Cannot be reconstructed from cards/IDs. Exact issued pages are the only sealing inputs. */
    public static final class NativeInterpretationCollection {
        private final MemoryReadSession session;
        private final RecordedRetrievalBundle bundle;
        private final List<Page> raw;
        private final List<SemanticReadRecords.Page> semantic;
        private final List<InterpretationReadRecords.Page> interpretations;
        private final Consumer<Runnable> dispatch;
        private NativeInterpretationCollection(MemoryReadSession session,RecordedRetrievalBundle bundle,List<Page> raw,
                List<SemanticReadRecords.Page> semantic,List<InterpretationReadRecords.Page> interpretations,Consumer<Runnable> dispatch) {
            this.session=session;this.bundle=bundle;this.raw=List.copyOf(raw);this.semantic=List.copyOf(semantic);
            this.interpretations=List.copyOf(interpretations);this.dispatch=dispatch;
        }
        public RecordedRetrievalBundle.Scope scope(){return bundle.scope();}
        public boolean current(){return bundle.current();}
        RecordedRetrievalBundle bundle(){return bundle;}
        List<Page> rawPages(){return raw;}
        List<SemanticReadRecords.Page> semanticPages(){return semantic;}
        List<InterpretationReadRecords.Page> interpretationPages(){return interpretations;}
        MemoryReadSession issuer(){return session;}
        Consumer<Runnable> dispatcher(){return dispatch;}
        @Override public String toString(){return "NativeInterpretationCollection[current="+current()+"]";}
    }
    private static final class LaneState<T> {
        final List<T> entries=new ArrayList<>();final Set<Object> identities=new HashSet<>();
        Status status=Status.UNAVAILABLE;boolean attempted,blocked;int pages;
        RecordedRetrievalBundle.Lane<T> freeze(){return new RecordedRetrievalBundle.Lane<>(status,entries,attempted);}
        void unavailable(){status=Status.UNAVAILABLE;entries.clear();blocked=true;}
    }
    private static final class Run {
        final MemoryReadSession session;final RecordedRetrievalBundle.Scope scope;final Query query;final Options options;
        final Optional<SemanticQuery> semantic;final Consumer<Runnable> dispatch;final CompletableFuture<RecordedRetrievalBundle> result=new CompletableFuture<>();
        final Deque<Runnable> jobs=new ArrayDeque<>();final List<BooleanSupplier> guards=new ArrayList<>();
        final Set<UUID> discardedInterpretationInputs=new HashSet<>();
        final List<Page> issuedRaw=new ArrayList<>();final List<SemanticReadRecords.Page> issuedSemantic=new ArrayList<>();
        final List<InterpretationReadRecords.Page> issuedInterpretations=new ArrayList<>();
        final LaneState<Entry> raw=new LaneState<>();final LaneState<SemanticReadRecords.Entry> vectors=new LaneState<>();
        final LaneState<InterpretationReadRecords.Entry> interpretations=new LaneState<>();
        final LaneState<ObservationReadRecords.Entry> observations=new LaneState<>();final LaneState<RumorReadRecords.Entry> rumors=new LaneState<>();
        int calls,chargedBytes;boolean inFlight,budgetExhausted;
        Run(MemoryReadSession session,Request request,RecordedRecallQuery.Prepared prepared,Optional<SemanticQuery> semantic,Options options,Consumer<Runnable> dispatch) {
            this(session,request,prepared,semantic,options,dispatch,false);
        }
        Run(MemoryReadSession session,Request request,RecordedRecallQuery.Prepared prepared,Optional<SemanticQuery> semantic,Options options,Consumer<Runnable> dispatch,boolean nativeSpeechOnly) {
            this.session=Objects.requireNonNull(session);this.scope=RecordedRetrievalBundle.Scope.of(request);query=Objects.requireNonNull(prepared).query();
            this.semantic=Objects.requireNonNull(semantic);this.options=Objects.requireNonNull(options);this.dispatch=Objects.requireNonNull(dispatch);
            jobs.add(()->raw(Optional.empty()));
            if(prepared.semanticEligible()&&semantic.isPresent()&&!query.text().isBlank()&&query.text().length()<=1600
                    &&semantic.get().vector().inputHash().equals(com.sande.mythictrpg.recording.api.RecordingRecords.sha256(query.text())))jobs.add(()->semantic(Optional.empty()));
            // Observation subjects/rumor subjects are not utterance actors. Never widen a restricted query to ANY.
            boolean typedScope=!request.publicRoom()&&request.godIds().size()==1&&query.actorSelection().isAny()
                    &&query.fromInclusive().isEmpty()&&query.untilExclusive().isEmpty();
            if(!nativeSpeechOnly&&options.includeObservations()&&typedScope)jobs.add(()->observations(Optional.empty()));
            if(!nativeSpeechOnly&&options.includeRumors()&&typedScope)jobs.add(()->rumors(Optional.empty()));
        }
        boolean current(){try{return guards.stream().allMatch(BooleanSupplier::getAsBoolean);}catch(RuntimeException failure){return false;}}
        void next(){
            if(result.isDone())return;
            if(!current()){fail(Status.STALE);return;}
            if(inFlight)throw new IllegalStateException("CONCURRENT_RETRIEVAL");
            if(budgetExhausted||calls>=options.maxCalls()||options.maxBytes()-chargedBytes<256||jobs.isEmpty()){finish();return;}
            jobs.removeFirst().run();
        }
        <T> Budget budget(LaneState<T> lane){return new Budget(Math.max(1,options.maxRowsPerLane()-lane.entries.size()),Math.max(256,options.maxBytes()-chargedBytes));}
        <T> Budget speechBudget(LaneState<T> lane){
            // Native speech readers budget UTF-8 body, while this consumer charges complete JSON cards.
            // Escaping can expand an ASCII byte to six bytes; actor/UUID/time/coverage metadata fits 1KiB per row.
            int rows=options.maxRowsPerLane()-lane.entries.size(),remaining=options.maxBytes()-chargedBytes;
            while(rows>0&&(remaining-rows*1024)/6<256)rows--;
            if(rows==0)return null;
            return new Budget(rows,(remaining-rows*1024)/6);
        }
        <T> boolean exhausted(LaneState<T> lane){return lane.blocked||lane.pages>=options.maxPagesPerLane()||lane.entries.size()>=options.maxRowsPerLane();}
        void unavailable(LaneState<?> lane){
            // A later failed page must not erase a known correction while leaving its old raw promise.
            if(lane==interpretations)for(var entry:List.copyOf(interpretations.entries))discardGroup(entry);
            lane.unavailable();
        }
        void raw(Optional<Cursor> cursor){
            if(exhausted(raw)){next();return;}var budget=speechBudget(raw);if(budget==null){next();return;}
            read(raw,()->session.query(query,cursor,budget),Page::status,Page::entries,session::current,budget,e->e.messageId(),e->true,
                    page->{issuedRaw.add(page);if(!page.entries().isEmpty())jobs.add(()->interpret(page,Optional.empty(),0));
                        if(page.next().isPresent()&&!exhausted(raw))jobs.add(()->raw(page.next()));});
        }
        void semantic(Optional<SemanticReadRecords.Cursor> cursor){
            if(exhausted(vectors)){next();return;}var budget=speechBudget(vectors);if(budget==null){next();return;}var supplied=semantic.orElseThrow();
            read(vectors,()->session.semantic(query,supplied.vector(),cursor,budget),SemanticReadRecords.Page::status,SemanticReadRecords.Page::entries,session::current,budget,
                    SemanticReadRecords.Entry::messageId,e->e.similarity()>=supplied.minimumSimilarity(),
                    page->{issuedSemantic.add(page);if(page.entries().stream().anyMatch(e->e.similarity()>=supplied.minimumSimilarity()))jobs.add(()->interpret(page,Optional.empty(),0));
                        if(page.next().isPresent()&&!exhausted(vectors))jobs.add(()->semantic(page.next()));});
        }
        void interpret(Object seed,Optional<InterpretationReadRecords.Cursor> cursor,int pageCount){
            if(interpretations.blocked||pageCount>=options.maxPagesPerLane()||interpretations.entries.size()>=options.maxRowsPerLane()){next();return;}
            var budget=budget(interpretations);
            Supplier<CompletableFuture<InterpretationReadRecords.Page>> supplier=()->seed instanceof Page page
                    ?session.interpretations(page,cursor,budget):session.interpretations((SemanticReadRecords.Page)seed,cursor,budget);
            read(interpretations,supplier,InterpretationReadRecords.Page::status,InterpretationReadRecords.Page::entries,session::current,budget,
                    InterpretationReadRecords.Entry::memoryId,e->true,
                    page->{issuedInterpretations.add(page);if(page.next().isPresent()&&pageCount+1<options.maxPagesPerLane())jobs.add(()->interpret(seed,page.next(),pageCount+1));});
        }
        void observations(Optional<ObservationReadRecords.Cursor> cursor){
            if(exhausted(observations)){next();return;}var budget=budget(observations);
            read(observations,()->session.observations(query,cursor,budget),ObservationReadRecords.Page::status,ObservationReadRecords.Page::entries,session::current,budget,
                    ObservationReadRecords.Entry::knowledgeReceiptId,e->true,
                    page->{if(page.next().isPresent()&&!exhausted(observations))jobs.add(()->observations(page.next()));});
        }
        void rumors(Optional<RumorReadRecords.Cursor> cursor){
            if(exhausted(rumors)){next();return;}var budget=budget(rumors);
            read(rumors,()->session.rumors(query,cursor,budget),RumorReadRecords.Page::status,RumorReadRecords.Page::entries,session::current,budget,
                    RumorReadRecords.Entry::knowledgeReceiptId,e->true,
                    page->{if(page.next().isPresent()&&!exhausted(rumors))jobs.add(()->rumors(page.next()));});
        }
        <T,P> void read(LaneState<T> lane,Supplier<CompletableFuture<P>> call,Function<P,Status> status,Function<P,List<T>> entries,
                       Predicate<P> valid,Budget budget,Function<T,Object> identity,Predicate<T> select,Consumer<P> after) {
            if(result.isDone())return;if(!current()){fail(Status.STALE);return;}
            if(inFlight||calls>=options.maxCalls()){finish();return;}
            calls++;lane.attempted=true;inFlight=true;
            try {Objects.requireNonNull(call.get()).whenComplete((page,failure)->{
                if(result.isDone())return;
                try {dispatch.accept(()->{
                    if(result.isDone())return;inFlight=false;
                    try {
                        if(!current()){fail(Status.STALE);return;}
                        if(failure!=null||page==null){unavailable(lane);next();return;}
                        var pageStatus=status.apply(page);
                        if(pageStatus==Status.STALE){fail(Status.STALE);return;}
                        if(!Set.of(Status.FOUND,Status.EMPTY,Status.PARTIAL).contains(pageStatus)){unavailable(lane);next();return;}
                        var rows=List.copyOf(entries.apply(page));
                        if(rows.size()>budget.rows()||pageStatus==Status.EMPTY&&!rows.isEmpty()){unavailable(lane);next();return;}
                        if(!valid.test(page)){fail(Status.STALE);return;}
                        guards.add(()->valid.test(page));lane.pages++;
                        if(lane.status!=Status.PARTIAL)lane.status=pageStatus;
                        int suppliedBytes=0;
                        for(T row:rows){
                            int body=row instanceof Entry e?e.text().getBytes(StandardCharsets.UTF_8).length
                                    :row instanceof SemanticReadRecords.Entry e?e.text().getBytes(StandardCharsets.UTF_8).length
                                    :row instanceof RumorReadRecords.Entry e?RumorReadRecords.wireByteSize(e):RecordedRetrievalBundle.wireByteSize(row);
                            suppliedBytes=Math.addExact(suppliedBytes,body);
                        }
                        if(suppliedBytes>budget.utf8Bytes()){
                            if(lane==interpretations)for(T row:rows)discardGroup((InterpretationReadRecords.Entry)row);
                            unavailable(lane);next();return;}
                        for(T row:rows){
                            Object id=Objects.requireNonNull(identity.apply(row));
                            int size=RecordedRetrievalBundle.wireByteSize(row);
                            if(size>options.maxBytes()-chargedBytes){
                                budgetExhausted=true;lane.status=Status.PARTIAL;
                                if(row instanceof InterpretationReadRecords.Entry e)discardGroup(e);continue;}
                            chargedBytes+=size; // Duplicate and below-threshold responses consume the same total budget.
                            if(lane.identities.contains(id)||!select.test(row))continue;
                            if(lane.entries.size()>=options.maxRowsPerLane()||discarded(row)){
                                lane.status=Status.PARTIAL;if(row instanceof InterpretationReadRecords.Entry e)discardGroup(e);continue;}
                            lane.identities.add(id);lane.entries.add(row);
                        }
                        if(lane.status!=Status.PARTIAL)lane.status=lane.entries.isEmpty()?Status.EMPTY:Status.FOUND;
                        after.accept(page);next();
                    }catch(RuntimeException invalid){fail(Status.UNAVAILABLE);}
                });}catch(RuntimeException rejected){fail(Status.UNAVAILABLE);}
            });}catch(RuntimeException unavailable){inFlight=false;unavailable(lane);next();}
        }
        boolean discarded(Object row){
            if(row instanceof Entry e)return discardedInterpretationInputs.contains(e.messageId());
            if(row instanceof SemanticReadRecords.Entry e)return discardedInterpretationInputs.contains(e.messageId());
            return row instanceof InterpretationReadRecords.Entry e&&e.inputs().stream().anyMatch(i->discardedInterpretationInputs.contains(i.messageId()));
        }
        /** Dropping a known correction's card must not leave a linked old promise looking complete. */
        void discardGroup(InterpretationReadRecords.Entry dropped){
            dropped.inputs().forEach(i->discardedInterpretationInputs.add(i.messageId()));
            boolean changed;do{changed=false;for(var entry:interpretations.entries)if(discarded(entry))
                for(var input:entry.inputs())if(discardedInterpretationInputs.add(input.messageId()))changed=true;}while(changed);
            interpretations.entries.removeIf(this::discarded);
            if(raw.entries.removeIf(this::discarded))raw.status=Status.PARTIAL;
            if(vectors.entries.removeIf(this::discarded))vectors.status=Status.PARTIAL;
        }
        void finish(){
            if(result.isDone())return;if(!current()){fail(Status.STALE);return;}
            if(guards.isEmpty()){fail(Status.UNAVAILABLE);return;}
            var frozen=List.copyOf(guards);
            int retained=java.util.stream.Stream.of(raw.entries,vectors.entries,interpretations.entries,observations.entries,rumors.entries)
                    .flatMap(Collection::stream).mapToInt(RecordedRetrievalBundle::wireByteSize).sum();
            var bundle=new RecordedRetrievalBundle(scope,query,raw.freeze(),vectors.freeze(),interpretations.freeze(),observations.freeze(),rumors.freeze(),retained,calls,
                    ()->!result.isCancelled()&&!frozen.isEmpty()&&frozen.stream().allMatch(BooleanSupplier::getAsBoolean));
            if(!bundle.current()){fail(Status.STALE);return;}result.complete(bundle);
        }
        void timeout(){if(!result.isDone())fail(Status.UNAVAILABLE);}
        /** Can run from a rejecting dispatcher/timer: it neither invokes game guards nor exposes partial data. */
        void fail(Status status){
            result.complete(new RecordedRetrievalBundle(scope,query,new RecordedRetrievalBundle.Lane<>(status,List.of(),raw.attempted),
                    new RecordedRetrievalBundle.Lane<>(status,List.of(),vectors.attempted),new RecordedRetrievalBundle.Lane<>(status,List.of(),interpretations.attempted),
                    new RecordedRetrievalBundle.Lane<>(status,List.of(),observations.attempted),new RecordedRetrievalBundle.Lane<>(status,List.of(),rumors.attempted),0,Math.min(8,calls),()->false));
        }
    }
}
