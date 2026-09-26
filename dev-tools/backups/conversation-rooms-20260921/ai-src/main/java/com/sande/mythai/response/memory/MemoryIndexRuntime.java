package com.sande.mythai.response.memory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional worker-side v2 pipeline. No live Minecraft references and no hidden global retrieval. */
public final class MemoryIndexRuntime implements AutoCloseable {
    private final MemoryJournal raw;private final MemoryIndexSettings settings;private final MemoryIndexStore store;private final OllamaMemoryBackend backend;
    private final ThreadPoolExecutor maintenance,queries;private final AtomicBoolean running=new AtomicBoolean();private volatile boolean closed;
    private final Map<String,Integer> attempts=new ConcurrentHashMap<>();private volatile String diagnostic="NOT_STARTED";private volatile int backlog;
    private final Set<CompletableFuture<?>> outstanding=ConcurrentHashMap.newKeySet();
    private volatile CompletableFuture<Void> maintenanceDone=CompletableFuture.completedFuture(null);
    public MemoryIndexRuntime(Path directory,MemoryJournal raw,MemoryIndexSettings settings,OllamaMemoryBackend backend){
        this.raw=raw;this.settings=settings;this.backend=backend;store=new MemoryIndexStore(directory,raw,settings);
        maintenance=worker("mythai-memory-idle",1);queries=worker("mythai-memory-query",4);
    }
    private static ThreadPoolExecutor worker(String name,int capacity){return new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(capacity),r->{var t=new Thread(r,name);t.setDaemon(true);return t;});}
    private String identity(MemoryJournal.Entry e){return e.id()+"/"+DerivedMemory.fingerprint(e)+"/"+(settings.semanticMode()==MemoryIndexSettings.Mode.OFF?"":settings.fingerprint())+"/"+(settings.consolidate()?settings.extractionVersion():"none");}
    public String diagnostic(){return "index="+store.status()+"; backlog="+backlog+"; semantic="+diagnostic+"; mode="+settings.semanticMode();}
    public MemoryIndexStore.Status status(){return store.status();}
    /** Called with a tiny tick signal. Scanning, HTTP and file writes are all worker-only. */
    public synchronized void pump(){
        if(closed||!settings.enabled()||!settings.consolidate()&&settings.semanticMode()==MemoryIndexSettings.Mode.OFF
                ||ModelAdmission.status().onlinePlayers()!=0||!running.compareAndSet(false,true))return;
        var done=new CompletableFuture<Void>();maintenanceDone=done;
        try{maintenance.execute(()->{try{maintainOne();}finally{running.set(false);done.complete(null);}});}catch(RejectedExecutionException busy){running.set(false);done.complete(null);}
    }
    void maintainOne(){
        if(closed||!raw.ready()||!store.status().state().equals("READY"))return;
        if(Set.of("BYTE_LIMIT","ENTRY_LIMIT","VECTOR_MEMORY_LIMIT").contains(store.status().reason())){diagnostic="CAPACITY_REQUIRES_REVIEW";return;}
        var missing=raw.view().entries().stream().filter(e->e.source()==MemoryJournal.Source.PLAYER_STATEMENT&&!store.contains(identity(e)))
                .sorted(Comparator.comparingLong(MemoryJournal.Entry::occurredAt).thenComparing(MemoryJournal.Entry::id)).toList();backlog=missing.size();
        var remainingKeys=new HashSet<String>();missing.forEach(e->remainingKeys.add(identity(e)));attempts.keySet().retainAll(remainingKeys);
        var next=missing.stream().filter(e->attempts.getOrDefault(identity(e),0)<3).findFirst();if(next.isEmpty()){diagnostic=backlog>0?"RETRY_LIMIT_REQUIRES_REVIEW":"INDEXED";return;}
        var e=next.get();var permitted=raw.readView(e.key(),e.audience(),e.godAudience());
        var context=new ArrayList<>(permitted.entries().stream().filter(p->p.source()==MemoryJournal.Source.PLAYER_STATEMENT&&p.audience().equals(e.audience())
                &&p.godAudience().equals(e.godAudience())&&!p.id().equals(e.id())&&p.occurredAt()<=e.occurredAt()&&!permitted.pending().contains(p.id()))
                .sorted(Comparator.comparingLong(MemoryJournal.Entry::occurredAt).reversed()).limit(5).sorted(Comparator.comparingLong(MemoryJournal.Entry::occurredAt)).toList());context.add(e);
        try {
            float[] vector=new float[0];String fingerprint="",extractor="none",kind="UNPROCESSED";List<MemoryIndexRow.Link> links=List.of();
            if(settings.semanticMode()!=MemoryIndexSettings.Mode.OFF){
                var cached=store.view(permitted).stream().filter(r->r.source().id().equals(e.id())
                        &&r.fingerprint().equals(settings.fingerprint())&&r.vector().length==settings.dimensions()).findFirst();
                if(cached.isPresent())vector=cached.get().vector();
                else try(var lease=ModelAdmission.optional(true)){if(lease==null){diagnostic="DEFERRED_BUSY";return;}vector=backend.embed(e.text(),true).values();}
                fingerprint=settings.fingerprint();
            }
            if(settings.consolidate()){
                try(var lease=ModelAdmission.optional(true)){
                    if(lease==null){retainVector(e,vector,fingerprint);diagnostic="DEFERRED_BUSY";return;}
                    var a=backend.annotate(context);kind=a.kind();links=a.links();extractor=settings.extractionVersion();
                }catch(Exception extractionFailure){
                    boolean interrupted=Thread.interrupted();
                    try {retainVector(e,vector,fingerprint);}finally{if(interrupted)Thread.currentThread().interrupt();}
                    if(extractionFailure instanceof InterruptedException){diagnostic="DEFERRED_FOREGROUND";return;}
                    attempts.merge(identity(e),1,Integer::sum);diagnostic="EXTRACTION_FAILED_VECTOR_RETAINED:"+extractionFailure.getClass().getSimpleName();return;
                }
            }
            if(closed)return;
            var row=new MemoryIndexRow(2,e,DerivedMemory.fingerprint(e),fingerprint,vector,extractor,kind,links);
            diagnostic=store.put(row,context).get(5,TimeUnit.SECONDS);
            if(!Set.of("STORED","DUPLICATE","STALE","STALE_LINK").contains(diagnostic))attempts.merge(identity(e),1,Integer::sum);
        }catch(Exception failure){if(failure instanceof InterruptedException){Thread.currentThread().interrupt();diagnostic="DEFERRED_FOREGROUND";return;}
            attempts.merge(identity(e),1,Integer::sum);diagnostic="BACKGROUND_FAILED:"+failure.getClass().getSimpleName();}
    }
    private void retainVector(MemoryJournal.Entry source,float[] vector,String fingerprint)throws Exception {
        if(closed||vector.length==0)return;
        store.put(new MemoryIndexRow(2,source,DerivedMemory.fingerprint(source),fingerprint,vector,"none","UNPROCESSED",List.of()),List.of(source)).get(5,TimeUnit.SECONDS);
    }
    public RecallSearch.Result search(MemoryJournal.ReadView view,RecallSearch.Result base,RecallSettings recall,long now){
        if(closed||!settings.enabled()||!view.ready()||!raw.stillCurrent(view.entries().stream().filter(e->!view.pending().contains(e.id())).toList()))return base;
        var available=store.view(view);
        var rows=available.stream().filter(r->r.extractor().equals(settings.consolidate()?settings.extractionVersion():"none")).toList();var result=base;
        if(settings.semanticMode()!=MemoryIndexSettings.Mode.OFF && !(settings.execution().skipSemanticWhenFound()&&base.status()==RecallSearch.Status.FOUND)){
            var vectorSources=new LinkedHashMap<UUID,MemoryIndexRow>();
            available.stream().filter(r->r.fingerprint().equals(settings.fingerprint())&&r.vector().length==settings.dimensions())
                    .forEach(r->vectorSources.put(r.source().id(),r));
            var vectors=vectorSources.values().stream()
                    .map(r->new SemanticIndex.Row(DerivedMemory.project(r.source(),recall.timeBasis()),new SemanticIndex.Vector(r.fingerprint(),r.vector()))).toList();
            var provider=new SemanticIndex.Provider(){public String fingerprint(){return settings.fingerprint();}
                public CompletableFuture<SemanticIndex.Vector> embed(String text){var future=new CompletableFuture<SemanticIndex.Vector>();
                    outstanding.add(future);future.whenComplete((v,e)->outstanding.remove(future));
                    try{queries.execute(()->{if(future.isDone())return;if(closed){future.cancel(false);return;}try(var lease=ModelAdmission.optional(false)){
                        if(lease==null)throw new RejectedExecutionException("foreground busy");future.complete(backend.embed(text,false));
                    }catch(Exception e){future.completeExceptionally(e);}});}catch(RejectedExecutionException e){future.completeExceptionally(e);}return future;}};
            var hybrid=HybridRetrieval.search(view,base,recall,new SemanticIndex(vectors),provider,now,TimeUnit.MILLISECONDS.toNanos(settings.queryTimeoutMs()),settings.execution().minimumSimilarity());
            diagnostic=hybrid.semanticState()+"; candidates="+hybrid.candidates();
            if(settings.semanticMode()==MemoryIndexSettings.Mode.ON)result=hybrid.recall();
        } else if(settings.semanticMode()!=MemoryIndexSettings.Mode.OFF)diagnostic="SKIPPED_FOUND";
        // SHADOW truly leaves foreground selection/phrasing untouched, including candidate correction metadata.
        if(settings.semanticMode()==MemoryIndexSettings.Mode.SHADOW)return base;
        // A raw edit/forget racing a query must not return stale derived evidence even to non-game consumers.
        if(closed||!raw.stillCurrent(view.entries().stream().filter(e->!view.pending().contains(e.id())).toList()))return base;
        return settings.consolidate()?withLabels(withRelations(view,result,rows,recall,now),rows):result;
    }
    private static RecallSearch.Result withLabels(RecallSearch.Result base,List<MemoryIndexRow> rows){
        var kinds=new HashMap<UUID,String>();rows.forEach(r->kinds.put(r.source().id(),r.kind()));
        var reasons=new LinkedHashMap<>(base.reasons());
        for(var e:base.selected()){String kind=kinds.get(e.id());if(kind!=null&&!kind.equals("UNPROCESSED"))
            reasons.put(e.id(),reasons.getOrDefault(e.id(),"selected")+"|kind="+kind);}
        return new RecallSearch.Result(base.query(),base.status(),base.selected(),reasons,base.pending(),base.elapsedNanos(),base.reason());
    }
    static RecallSearch.Result withRelations(MemoryJournal.ReadView view,RecallSearch.Result base,List<MemoryIndexRow> rows,RecallSettings settings,long now){
        var selected=new LinkedHashMap<UUID,MemoryJournal.Entry>();for(var e:base.selected())selected.put(e.id(),e);
        var reasons=new LinkedHashMap<>(base.reasons());boolean changed=false;
        outer: for(var row:rows.stream().sorted(Comparator.comparingLong((MemoryIndexRow r)->r.source().occurredAt()).reversed()).toList()){if(!row.current(view))continue;
            for(var link:row.links())if(row.linkCurrent(link,view)&&(selected.containsKey(link.older())||selected.containsKey(row.source().id()))
                    &&RecallSearch.semanticEligible(row.source(),base.query(),settings,now)){
                var old=view.entries().stream().filter(e->e.id().equals(link.older())).findFirst().orElseThrow();
                if(!RecallSearch.semanticEligible(old,base.query(),settings,now))continue;
                // Pair first, never claim the model decided that the old record is false/deleted.
                var paired=new LinkedHashMap<UUID,MemoryJournal.Entry>();paired.put(old.id(),old);paired.put(row.source().id(),row.source());
                for(var item:selected.entrySet())paired.putIfAbsent(item.getKey(),item.getValue());
                selected=paired;String reason="candidate_"+link.relation().toLowerCase(Locale.ROOT)+"_not_verified";
                reasons.put(old.id(),reason);reasons.put(row.source().id(),reason);changed=true;break outer;
            }
        }
        if(!changed)return base;
        var kept=selected.values().stream().limit(3).sorted(Comparator.comparingLong(MemoryJournal.Entry::occurredAt)).toList();
        var ids=new HashSet<UUID>();kept.forEach(e->ids.add(e.id()));reasons.keySet().retainAll(ids);var pending=new HashSet<>(view.pending());pending.retainAll(ids);
        return new RecallSearch.Result(base.query(),RecallSearch.Status.AMBIGUOUS,kept,reasons,pending,base.elapsedNanos(),"candidate_relation_raw_pair");
    }
    public void awaitIdle()throws Exception{store.awaitIdle();maintenanceDone.get(10,TimeUnit.SECONDS);store.awaitIdle();}
    public boolean drained(){return maintenance.isTerminated()&&queries.isTerminated()&&Set.of("CLOSED","FAILED").contains(store.status().state());}
    public void close(){closed=true;maintenance.shutdownNow();queries.shutdownNow();maintenanceDone.cancel(false);outstanding.forEach(f->f.cancel(false));
        try{maintenance.awaitTermination(5,TimeUnit.SECONDS);queries.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        store.close();}
}
