package com.sande.mythai.response.memory;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic vectors are explicit fixtures, not model quality results. No network/model/server work. */
public final class DerivedMemoryTest {
    private static int checks;private static Path root;
    private static final long NOW=Instant.parse("2026-09-16T08:00:00Z").toEpochMilli();
    private static final UUID WORLD=UUID.randomUUID(),PLAYER=UUID.randomUUID(),SESSION=UUID.randomUUID();
    private static final MemoryJournal.Key KEY=new MemoryJournal.Key(WORLD,"mythictrpg:fortuna",PLAYER);
    private static final RecallSettings SETTINGS=new RecallSettings(true,RecallSettings.TimeBasis.REAL_KST);
    private static final DerivedSettings ON=new DerivedSettings(true,false,2000000,1000);
    private static synchronized void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static <T>T get(CompletableFuture<T> value)throws Exception{return value.get(10,TimeUnit.SECONDS);}
    private static MemoryJournal.Entry entry(String text){return entry(KEY,text,NOW-86400000,Set.of(PLAYER),false);}
    private static MemoryJournal.Entry entry(MemoryJournal.Key key,String text,long when,Set<UUID> audience,boolean pin){return new MemoryJournal.Entry(UUID.randomUUID(),key,SESSION,1,MemoryJournal.Source.PLAYER_STATEMENT,audience,when,text,pin);}
    private static MemoryJournal.ReadView view(List<MemoryJournal.Entry> entries){return new MemoryJournal.ReadView(KEY,Set.of(PLAYER),entries,Set.of(),true,false);}
    private static RecallQuery query(String text){return RecallQuery.plan(new RecallQuery.Scope(KEY,SESSION,Set.of(PLAYER)),text,2,NOW,null);}
    private static RecallSearch.Result base(MemoryJournal.ReadView v,RecallQuery q){return RecallSearch.search(v,q,SETTINGS,Set.of(),List.of(),NOW,1000000000L);}
    private static SemanticIndex.Row row(MemoryJournal.Entry e,float x,float y){return new SemanticIndex.Row(DerivedMemory.project(e,SETTINGS.timeBasis()),new SemanticIndex.Vector("fixture-model-v1",new float[]{x,y}));}
    private static SemanticIndex.Provider provider(CompletableFuture<SemanticIndex.Vector> future){return new SemanticIndex.Provider(){public String fingerprint(){return "fixture-model-v1";}public CompletableFuture<SemanticIndex.Vector> embed(String query){return future;}};}
    private static void invalid(Runnable r,String why){try{r.run();}catch(RuntimeException expected){check(true,why);return;}throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        root=Files.createTempDirectory(Files.createDirectories(Path.of(args[0])),"derived-");
        projection();store();corruption();capacity();retrieval();lifecycle();for(int n:new int[]{1,4,6})fixture(n);
        com.sande.mythictrpg.ai.experiencecontract.ObservedSummaryFixture.run();
        System.out.println("DerivedMemoryTest: PASS ("+checks+" checks + summary fixture); artifacts="+root+"; semantic backend NOT connected, no LLM latency/quality claim");
    }
    private static void projection() {
        var plan=entry("내일 바다에 갈 계획이야");var n=DerivedMemory.project(plan,SETTINGS.timeBasis());
        check(n.kind()==DerivedMemory.Kind.PLAN_OR_PROMISE_HINT&&n.eventDate().equals("2026-09-16"),"relative plan anchored to source date not consolidation time");
        check(DerivedMemory.project(plan,RecallSettings.TimeBasis.UNSPECIFIED).eventDate().equals("UNSPECIFIED"),"no arbitrary world/real calendar");
        var quoted=DerivedMemory.project(entry("친구가 내일 바다 간다고 했어"),SETTINGS.timeBasis());
        check(quoted.kind()==DerivedMemory.Kind.REPORTED_OR_CONDITIONAL_HINT,"reported plan not personal promise");
        var conditional=DerivedMemory.project(entry("시간이 된다면 내일 바다에 갈 계획이야"),SETTINGS.timeBasis());
        check(conditional.kind()==DerivedMemory.Kind.REPORTED_OR_CONDITIONAL_HINT,"conditional not unconditional promise");
        check(n.quote().equals(plan.text())&&DerivedMemory.valid(n,plan,Set.of(PLAYER)),"verbatim provenance");
        UUID guest=UUID.randomUUID();check(!DerivedMemory.valid(n,plan,Set.of(PLAYER,guest)),"audience isolation");
        var a=entry(KEY,"공유한 이야기",NOW,new LinkedHashSet<>(List.of(PLAYER,guest)),false);
        var b=new MemoryJournal.Entry(a.id(),a.key(),a.session(),a.turn(),a.source(),new LinkedHashSet<>(List.of(guest,PLAYER)),a.occurredAt(),a.text(),a.important());
        check(DerivedMemory.fingerprint(a).equals(DerivedMemory.fingerprint(b)),"stable hash for set order");
        var later=entry(KEY,"내일은 산에 갈 계획으로 변경할래",NOW,Set.of(PLAYER),false);
        check(new DerivedMemory.ReviewedLink(n,DerivedMemory.project(later,SETTINGS.timeBasis()),"CORRECTS").older().equals(n),"reviewed correction retains both evidence nodes");
        var other=entry(new MemoryJournal.Key(WORLD,"mythictrpg:amphitrite",PLAYER),"다른 신 비밀",NOW,Set.of(PLAYER),false);
        invalid(()->new DerivedMemory.ReviewedLink(n,DerivedMemory.project(other,SETTINGS.timeBasis()),"CORRECTS"),"cross-god correction denied");
        invalid(()->new SemanticIndex.Vector("m",new float[]{Float.NaN}),"nonfinite vector");
        invalid(()->new SemanticIndex.Vector("m",new float[]{0,0}),"zero vector");
        var v=new SemanticIndex.Vector("m",new float[]{1,2});var values=v.values();values[0]=0;check(v.values()[0]==1,"immutable vector");
        check(Double.isNaN(v.cosine(new SemanticIndex.Vector("other",new float[]{1,2}))),"fingerprint mismatch");
        check(Double.isNaN(v.cosine(new SemanticIndex.Vector("m",new float[]{1}))),"dimension mismatch");
    }
    private static void store()throws Exception {
        Path path=root.resolve("store");var e=entry("수영을 못해");
        try(var raw=new MemoryJournal(root.resolve("raw"))) {
            check(get(raw.append(e))==MemoryJournal.Result.STORED,"raw durable");
            try(var d=new DerivedStore(path,raw,ON)) {
                d.awaitIdle();check(get(d.append(e,SETTINGS.timeBasis()))==DerivedStore.Result.STORED,"derived commit");
                check(get(d.append(e,SETTINGS.timeBasis()))==DerivedStore.Result.DUPLICATE,"idempotent restart key");
                check(d.view(raw.readView(KEY,Set.of(PLAYER))).size()==1,"live raw evidence only");
                var pending=new MemoryJournal.ReadView(KEY,Set.of(PLAYER),List.of(e),Set.of(e.id()),true,false);
                check(d.view(pending).isEmpty(),"pending raw not durable derived");
                try(var locked=new DerivedStore(path,raw,ON)){locked.awaitIdle();check(locked.status().state().equals("FAILED"),"second writer rejected");}
            }
            try(var d=new DerivedStore(path,raw,ON)) {
                d.awaitIdle();check(d.view(raw.readView(KEY,Set.of(PLAYER))).size()==1,"sidecar restart");
                var oldView=raw.readView(KEY,Set.of(PLAYER));get(raw.pin(e.id(),raw.view().revision(),true));
                check(d.view(oldView).isEmpty(),"stale snapshot cannot reuse pre-pin revision");
                check(get(d.append(e,SETTINGS.timeBasis()))==DerivedStore.Result.STALE,"late extraction cannot overwrite corrected source");
                var pinned=raw.readView(KEY,Set.of(PLAYER)).entries().getFirst();get(d.append(pinned,SETTINGS.timeBasis()));
                check(d.view(raw.readView(KEY,Set.of(PLAYER))).size()==1,"latest fingerprint only");
                get(raw.delete(e.id(),raw.view().revision()));check(d.view(oldView).isEmpty()&&d.view(raw.readView(KEY,Set.of(PLAYER))).isEmpty(),"forget cannot resurrect from physical sidecar");
            }
        }
        try(var raw=new MemoryJournal(root.resolve("raw"));var d=new DerivedStore(path,raw,ON)) {
            // Queue a raw fence with an inert deletion; readiness is asynchronous, not sleep-based.
            get(raw.delete(UUID.randomUUID(),0));d.awaitIdle();check(d.view(raw.readView(KEY,Set.of(PLAYER))).isEmpty(),"deleted evidence stays absent across both restarts");
        }
    }
    private static void corruption()throws Exception {
        try(var raw=new MemoryJournal(root.resolve("corrupt-raw"))) {
            var e=entry("중요한 사건");get(raw.append(e));
            for(String suffix:List.of("truncated","oversize","checksum","unknown")) {
                Path p=root.resolve(suffix);try(var d=new DerivedStore(p,raw,ON)){d.awaitIdle();get(d.append(e,SETTINGS.timeBasis()));}
                Path file=p.resolve("derived-v1.jsonl");String text=Files.readString(file);
                if(suffix.equals("truncated"))text=text.substring(0,text.length()-1);
                else if(suffix.equals("oversize"))text="x".repeat(32769)+"\n";
                else if(suffix.equals("checksum"))text=text.replace("중요한 사건","위조한 사건");
                else text=text.replace("\"version\":1","\"version\":99");
                Files.writeString(file,text);byte[] before=Files.readAllBytes(file);
                try(var d=new DerivedStore(p,raw,ON)){d.awaitIdle();check(d.status().state().equals("FAILED")&&d.view(raw.readView(KEY,Set.of(PLAYER))).isEmpty(),"sealed corruption "+suffix);check(get(d.append(e,SETTINGS.timeBasis()))==DerivedStore.Result.UNAVAILABLE,"no append to corrupt sidecar");}
                check(Arrays.equals(before,Files.readAllBytes(file)),"corrupt bytes not overwritten");check(raw.stillCurrent(List.of(e)),"raw fallback unaffected");
            }
        }
    }
    private static void capacity()throws Exception {
        Path p=root.resolve("off");try(var raw=new MemoryJournal(root.resolve("quota-raw"))) {
            var a=entry("첫번째 원문");var b=entry("두번째 원문");get(raw.append(a));get(raw.append(b));
            try(var off=new DerivedStore(p,raw,DerivedSettings.OFF)){off.awaitIdle();check(off.status().state().equals("OFF")&&!Files.exists(p),"OFF creates no sidecar");}
            try(var d=new DerivedStore(root.resolve("quota"),raw,new DerivedSettings(true,false,4096,1))) {
                d.awaitIdle();get(d.append(a,SETTINGS.timeBasis()));check(get(d.append(b,SETTINGS.timeBasis()))==DerivedStore.Result.FULL,"quota refusal explicit");
                check(d.status().rejected()==1&&d.view(raw.readView(KEY,Set.of(PLAYER))).size()==1,"no automatic eviction");check(raw.stillCurrent(List.of(a,b)),"full derived retains raw");
            }
            Path f=root.resolve("derived-config.json");check(DerivedSettings.load(f).equals(DerivedSettings.OFF),"missing config OFF");
            Files.writeString(f,"{\"schemaVersion\":1,\"enabled\":true,\"semanticRetrieval\":false,\"maxStorageBytes\":0,\"maxEntries\":20}");
            check(DerivedSettings.load(f).equals(DerivedSettings.OFF),"no arbitrary default quota");
        }
    }
    private static void retrieval()throws Exception {
        var e=entry("나는 수영을 못해");var v=view(List.of(e));var q=query("바다에 못 들어가는 이유 기억나?");var lexical=base(v,q);
        var index=new SemanticIndex(List.of(row(e,1,0)));var ready=provider(CompletableFuture.completedFuture(new SemanticIndex.Vector("fixture-model-v1",new float[]{1,0})));
        var result=HybridRetrieval.search(v,lexical,SETTINGS,index,ready,NOW,1000000000L);
        check(result.semanticState().equals("READY")&&result.recall().selected().contains(e),"fixture synonym retrieved");
        check(result.recall().status()==RecallSearch.Status.AMBIGUOUS&&result.recall().selected().getFirst().text().contains("못해"),"similarity not truth or negation erasure");
        check(HybridRetrieval.search(v,lexical,SETTINGS,index,null,NOW,1000000).recall()==lexical,"no provider identical fallback");
        var timeout=new CompletableFuture<SemanticIndex.Vector>();check(HybridRetrieval.search(v,lexical,SETTINGS,index,provider(timeout),NOW,1000000).semanticState().equals("TIMEOUT"),"timeout fallback");
        check(timeout.isCancelled(),"cancel optional query on timeout");
        check(HybridRetrieval.search(v,lexical,SETTINGS,index,provider(CompletableFuture.failedFuture(new IllegalStateException("fixture"))),NOW,100000000).recall()==lexical,"provider failure fallback");
        check(HybridRetrieval.search(v,lexical,SETTINGS,index,provider(CompletableFuture.completedFuture(new SemanticIndex.Vector("changed",new float[]{1,0}))),NOW,100000000).semanticState().equals("MODEL_MISMATCH"),"model change no stale vectors");
        check(HybridRetrieval.search(v,base(v,query("심심해")),SETTINGS,index,ready,NOW,100000000).semanticState().equals("DISABLED_OR_NO_PROVIDER"),"no semantic call for ordinary talk");
        AtomicInteger calls=new AtomicInteger();var spy=new SemanticIndex.Provider(){public String fingerprint(){return "fixture-model-v1";}public CompletableFuture<SemanticIndex.Vector> embed(String text){calls.incrementAndGet();return new CompletableFuture<>();}};
        var hidden=entry(new MemoryJournal.Key(WORLD,"mythictrpg:amphitrite",PLAYER),"수영을 못한다는 비밀",NOW-100,Set.of(PLAYER),false);
        var badIndex=new SemanticIndex(List.of(row(hidden,1,0)));check(HybridRetrieval.search(v,lexical,SETTINGS,badIndex,spy,NOW,100000000).semanticState().equals("PENDING_INDEX")&&calls.get()==0,"filter before semantic work/top-k");
        for(var foreign:List.of(new MemoryJournal.Key(UUID.randomUUID(),KEY.god(),PLAYER),new MemoryJournal.Key(WORLD,KEY.god(),UUID.randomUUID()))) {
            var denied=entry(foreign,"숨은 기억",NOW-100,Set.of(foreign.player()),false);
            check(new SemanticIndex(List.of(row(denied,1,0))).allowed(v).isEmpty(),"world/player filter before search");
        }
        check(index.allowed(new MemoryJournal.ReadView(KEY,Set.of(),List.of(e),Set.of(),true,false)).isEmpty(),"empty audience not wildcard");
        check(index.allowed(new MemoryJournal.ReadView(KEY,Set.of(PLAYER),List.of(e),Set.of(),true,true)).isEmpty(),"failed raw never evidence");
        var extended=new MemoryJournal.ReadView(KEY,Set.of(PLAYER,UUID.randomUUID()),List.of(e),Set.of(),true,false);check(index.allowed(extended).isEmpty(),"hidden audience excluded presearch");
        check(index.allowed(new MemoryJournal.ReadView(KEY,Set.of(PLAYER),List.of(e),Set.of(e.id()),true,false)).isEmpty(),"uncommitted embeddings excluded");
        check(index.allowed(view(List.of())).isEmpty(),"deleted source excluded");
        var edited=new MemoryJournal.Entry(e.id(),e.key(),e.session(),e.turn(),e.source(),e.audience(),e.occurredAt(),"이제 수영을 배웠어",false);check(index.allowed(view(List.of(edited))).isEmpty(),"source revision invalidates vector");
        var expired=entry(KEY,"나는 수영을 못해",NOW-Duration.ofDays(31).toMillis(),Set.of(PLAYER),false);
        var oldView=view(List.of(expired));check(HybridRetrieval.search(oldView,base(oldView,q),SETTINGS,new SemanticIndex(List.of(row(expired,1,0))),ready,NOW,100000000).recall().selected().contains(expired),"explicit recall may retrieve older raw evidence without restoring casual salience");
        check(!RecallSearch.semanticEligible(expired,query("수영 이야기"),SETTINGS,NOW),"old casual association still fades");
        var future=entry(KEY,"나는 수영을 못해",NOW+1000,Set.of(PLAYER),false);check(!RecallSearch.semanticEligible(future,q,SETTINGS,NOW),"future record not past evidence");
        var npc=new MemoryJournal.Entry(UUID.randomUUID(),KEY,SESSION,1,MemoryJournal.Source.NPC_UTTERANCE,Set.of(PLAYER),NOW-100,"네가 수영을 못한다",false);
        invalid(()->row(npc,1,0),"NPC paraphrase not independent player fact");
        for(int i=0;i<20;i++)check(HybridRetrieval.search(v,lexical,SETTINGS,index,ready,NOW,100000000).recall().selected().equals(result.recall().selected()),"deterministic bounded fusion");
    }
    private static void lifecycle()throws Exception {
        Path config=root.resolve("lifecycle-config.json");Files.writeString(config,"{\"schemaVersion\":1,\"enabled\":true,\"semanticRetrieval\":false,\"maxStorageBytes\":2000000,\"maxEntries\":1000}");
        try(var raw=new MemoryJournal(root.resolve("lifecycle-raw"))) {
            get(raw.append(entry("회상할 말")));var v=raw.readView(KEY,Set.of(PLAYER));
            check(DerivedService.close(List.of(raw)),"close queued/unopened journal");
            DerivedService.search(raw,config,root.resolve("must-not-reopen"),v,query("기억나?"),SETTINGS,Set.of(),List.of(),NOW);
            check(!Files.exists(root.resolve("must-not-reopen"))&&DerivedService.status(raw).state().equals("OFF"),"late worker cannot reopen after shutdown");
        }
    }
    private static void fixture(int players)throws Exception {
        long begin=System.nanoTime();int count=0;long bytes;
        try(var raw=new MemoryJournal(root.resolve("load-raw-"+players));var d=new DerivedStore(root.resolve("load-derived-"+players),raw,ON)) {
            d.awaitIdle();
            try(var workers=Executors.newFixedThreadPool(players)) {
                List<Future<?>> jobs=new ArrayList<>();
                for(int p=0;p<players;p++)jobs.add(workers.submit(()->{
                    UUID player=UUID.randomUUID();var key=new MemoryJournal.Key(WORLD,"mythictrpg:fortuna",player);
                    try {
                        for(int i=0;i<30;i++){var e=entry(key,"상세 합성 회상 "+i,NOW-1000,Set.of(player),false);get(raw.append(e));check(get(d.append(e,SETTINGS.timeBasis()))==DerivedStore.Result.STORED,"synthetic concurrent durable");}
                        check(d.view(raw.readView(key,Set.of(player))).size()==30,"concurrent isolated player bucket");
                    }catch(Exception failure){throw new RuntimeException(failure);}
                }));
                for(var job:jobs)job.get(30,TimeUnit.SECONDS);count=30*players;
            }
            bytes=d.status().usedBytes();check(d.status().rejected()==0,"no synthetic sidecar loss");
        }
        String line="players="+players+",notes="+count+",bytes="+bytes+",elapsedMs="+(System.nanoTime()-begin)/1000000+"; synthetic disk only, NOT LLM or Minecraft timing";
        Files.writeString(root.resolve("fixture-"+players+".txt"),line);System.out.println(line);
    }
}
