package com.sande.mythai.response.memory;

import com.google.gson.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Offline regressions for hardware options, partial progress and dialogue priority. No sockets. */
public final class MemoryModelConnectionTest {
    private static final Gson JSON=new Gson();private static final String D="a".repeat(64),X="b".repeat(64);
    private static final long NOW=1789891200000L;private static final UUID PLAYER=UUID.randomUUID(),SESSION=UUID.randomUUID();
    private static final MemoryJournal.Key KEY=new MemoryJournal.Key(UUID.randomUUID(),"mythictrpg:fortuna",PLAYER);
    private static final RecallSettings RECALL=new RecallSettings(true,RecallSettings.TimeBasis.REAL_KST);
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static MemoryIndexSettings settings(boolean consolidate,MemoryIndexSettings.Execution execution){return new MemoryIndexSettings(true,MemoryIndexSettings.Mode.ON,consolidate,
            4_000_000,1000,URI.create("http://127.0.0.1:11434"),"embed:1",D,2,"extract:1",X,500,3000,execution);}
    private static MemoryJournal.Entry entry(String text,long time){return new MemoryJournal.Entry(UUID.randomUUID(),KEY,SESSION,1,MemoryJournal.Source.PLAYER_STATEMENT,Set.of(PLAYER),time,text,false);}
    private static final class Fake implements OllamaMemoryBackend.Transport {
        int embeddings,annotations;boolean invalidAnnotation,interruptAnnotation;String forcedRelation="NONE";JsonObject lastEmbed,lastChat;
        final CountDownLatch annotationEntered=new CountDownLatch(1);
        public String request(URI uri,String body,int timeout)throws Exception {
            if(body==null)return JSON.toJson(Map.of("models",List.of(Map.of("name","embed:1","digest",D),Map.of("name","extract:1","digest",X))));
            var request=JsonParser.parseString(body).getAsJsonObject();
            if(uri.getPath().equals("/api/embed")){embeddings++;lastEmbed=request;return "{\"model\":\"embed:1\",\"embeddings\":[[1,0]]}";}
            annotations++;lastChat=request;annotationEntered.countDown();if(interruptAnnotation)new CountDownLatch(1).await(5,TimeUnit.SECONDS);
            var input=JsonParser.parseString(request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString()).getAsJsonArray();
            String text=input.get(input.size()-1).getAsJsonObject().get("text").getAsString();
            String content=JSON.toJson(Map.of("kind","SELF_CLAIM","quote",invalidAnnotation?"invented":text.substring(0,Math.min(300,text.length())),"relation",forcedRelation,"older",forcedRelation.equals("NONE")?-1:0));
            return JSON.toJson(Map.of("model","extract:1","done",true,"done_reason","stop","message",Map.of("content",content)));
        }
    }
    public static void run(Path root)throws Exception {
        Files.createDirectories(root);options(root);partial(root);runtimeInterruption(root);preemption(false);preemption(true);deduplication();storageSize(root);
        ModelAdmission.players(-1);System.out.println("MemoryModelConnectionTest: PASS ("+checks+" checks); OFFLINE");
    }
    private static void options(Path root)throws Exception {
        var execution=new MemoryIndexSettings.Execution(true,4,600,true,4,8192,512,60,0.60,true);var s=settings(true,execution);
        Path config=root.resolve("settings.json");var j=JSON.toJsonTree(s).getAsJsonObject();j.addProperty("schemaVersion",1);Files.writeString(config,JSON.toJson(j));
        check(MemoryIndexSettings.load(config).equals(s),"complete hardware execution config round trip");
        j.remove("execution");Files.writeString(config,JSON.toJson(j));check(MemoryIndexSettings.load(config).execution().equals(MemoryIndexSettings.Execution.DEFAULT),"legacy configuration preserved");
        j.add("execution",JsonParser.parseString("{}"));Files.writeString(config,JSON.toJson(j));check(!MemoryIndexSettings.load(config).enabled(),"incomplete hardware execution fails closed");
        var fake=new Fake();var backend=new OllamaMemoryBackend(s,fake);backend.embed("합성",true);backend.annotate(List.of(entry("나는 수영을 못해.",NOW)));
        check(fake.lastEmbed.getAsJsonObject("options").get("num_gpu").getAsInt()==0,"embedding CPU explicit");
        check(fake.lastEmbed.getAsJsonObject("options").get("num_thread").getAsInt()==4,"embedding CPU thread cap");
        check(fake.lastEmbed.get("keep_alive").getAsString().equals("600s"),"bounded embedding residency");
        check(!fake.lastEmbed.get("truncate").getAsBoolean()&&fake.lastEmbed.get("dimensions").getAsInt()==2,"embedding size and no silent truncation");
        var opts=fake.lastChat.getAsJsonObject("options");check(opts.get("num_gpu").getAsInt()==0&&opts.get("num_thread").getAsInt()==4,"extractor CPU budget");
        check(opts.get("num_ctx").getAsInt()==8192&&opts.get("num_predict").getAsInt()==512,"extractor token/context cap");
        check(fake.lastChat.get("keep_alive").getAsString().equals("60s")&&!fake.lastChat.get("think").getAsBoolean(),"bounded non-thinking extraction");
        var schema=fake.lastChat.getAsJsonObject("format");check(!schema.get("additionalProperties").getAsBoolean()&&schema.getAsJsonArray("required").size()==4,"strict extraction schema");
        check(schema.getAsJsonObject("properties").getAsJsonObject("older").get("maximum").getAsInt()==-1,"single statement cannot reference nonexistent older record");
        fake.forcedRelation="REPORTS_FULFILLMENT";var old=entry("내일 바다에 갈게.",NOW-10);
        check(backend.annotate(List.of(old,entry("바다에 가기로 했어.",NOW))).links().isEmpty(),"plan repetition cannot be reported completion");
        check(backend.annotate(List.of(old,entry("약속대로 바다에 다녀왔어.",NOW))).links().size()==1,"explicit report preserved as candidate");
    }
    private static RecallSearch.Result base(MemoryJournal.ReadView view){var q=RecallQuery.plan(new RecallQuery.Scope(KEY,SESSION,Set.of(PLAYER)),"물놀이 이야기 기억해?",2,NOW,null);
        return RecallSearch.search(view,q,RECALL,Set.of(),List.of(),NOW,1_000_000_000L);}
    private static void partial(Path root)throws Exception {
        ModelAdmission.players(0);var s=settings(true,MemoryIndexSettings.Execution.DEFAULT);var fake=new Fake();fake.invalidAnnotation=true;
        var path=root.resolve("partial-index");try(var raw=new MemoryJournal(root.resolve("partial-raw"))){var e=entry("나는 수영을 못해.",NOW-100);raw.append(e).get(5,TimeUnit.SECONDS);
            try(var runtime=new MemoryIndexRuntime(path,raw,s,new OllamaMemoryBackend(s,fake))){runtime.awaitIdle();runtime.pump();runtime.awaitIdle();
                check(runtime.status().records()==1&&fake.embeddings==1&&fake.annotations==1,"annotation failure retains vector-only row");
                check(runtime.diagnostic().contains("EXTRACTION_FAILED_VECTOR_RETAINED"),"partial failure visible");
                runtime.pump();runtime.awaitIdle();check(fake.embeddings==1&&fake.annotations==2,"retry reuses vector");
                var v=raw.readView(KEY,Set.of(PLAYER));var found=runtime.search(v,base(v),RECALL,NOW);
                check(found.selected().contains(e)&&found.reasons().values().stream().anyMatch(r->r.contains("semantic")),"partial vector usable before extractor recovers");
                int previous=fake.embeddings;fake.invalidAnnotation=false;runtime.pump();runtime.awaitIdle();
                check(fake.embeddings==previous&&fake.annotations==3&&runtime.status().records()==2,"combined annotation recovery without repeated embedding");
                runtime.pump();runtime.awaitIdle();check(fake.annotations==3,"completed identity no more jobs");check(raw.view().entries().equals(List.of(e)),"raw unchanged");}
            var execution=new MemoryIndexSettings.Execution(false,0,0,false,0,0,768,0,0.6,true);var fast=settings(true,execution);
            try(var runtime=new MemoryIndexRuntime(path,raw,fast,new OllamaMemoryBackend(fast,fake))){runtime.awaitIdle();var v=raw.readView(KEY,Set.of(PLAYER));var b=base(v);
                var certain=new RecallSearch.Result(b.query(),RecallSearch.Status.FOUND,List.of(e),Map.of(e.id(),"lexical"),Set.of(),0,"lexical");int previous=fake.embeddings;
                var result=runtime.search(v,certain,RECALL,NOW);check(fake.embeddings==previous&&runtime.diagnostic().contains("SKIPPED_FOUND"),"confident lexical result avoids network");
                check(result.selected().equals(List.of(e))&&result.reasons().get(e.id()).contains("kind=SELF_CLAIM"),"fast path still attaches guarded annotation");}
        }
    }
    private static void runtimeInterruption(Path root)throws Exception {
        ModelAdmission.players(0);var s=settings(true,MemoryIndexSettings.Execution.DEFAULT);var fake=new Fake();fake.interruptAnnotation=true;
        try(var raw=new MemoryJournal(root.resolve("interrupt-raw"));var runtime=new MemoryIndexRuntime(root.resolve("interrupt-index"),raw,s,new OllamaMemoryBackend(s,fake))){
            var e=entry("수영을 못해.",NOW-20);raw.append(e).get(5,TimeUnit.SECONDS);runtime.awaitIdle();runtime.pump();
            check(fake.annotationEntered.await(5,TimeUnit.SECONDS),"background extraction started");
            try(var ticket=ModelAdmission.foreground()){runtime.awaitIdle();check(runtime.status().records()==1&&runtime.diagnostic().contains("DEFERRED_FOREGROUND"),"foreground interruption retains vector");}
            fake.interruptAnnotation=false;runtime.pump();runtime.awaitIdle();check(fake.embeddings==1&&fake.annotations==2&&runtime.status().records()==2,"interrupted maintenance resumes without stale interrupt or reembedding");
            check(raw.view().entries().equals(List.of(e)),"interruption never rewrites raw");
        }
    }
    private static void preemption(boolean playerJoin)throws Exception {
        ModelAdmission.players(0);var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);var release=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Thread background=new Thread(()->{try(var lease=ModelAdmission.optional(true)){if(lease==null)throw new AssertionError("missing lease");entered.countDown();
            while(release.getCount()>0)try{release.await();}catch(InterruptedException signal){interrupted.countDown();}}
            catch(Throwable t){failure.set(t);}},"offline-priority-fixture");background.start();ModelAdmission.Ticket ticket=null;
        try {check(entered.await(5,TimeUnit.SECONDS),"background lease acquired");if(playerJoin)ModelAdmission.players(1);else ticket=ModelAdmission.foreground();
            check(interrupted.await(5,TimeUnit.SECONDS),"join/queued dialogue signals background cancellation");
            check(ModelAdmission.status().optionalActive(),"permit held until transport actually unwinds");check(ModelAdmission.optional(false)==null,"no overlapping optional model while transport unwinds");
        }finally{release.countDown();background.join(5000);if(ticket!=null)ticket.close();}
        check(!background.isAlive()&&failure.get()==null&&!ModelAdmission.status().optionalActive(),"clean admission release");
        try(var t=ModelAdmission.foreground()){check(ModelAdmission.run(t,1,100,()->42)==42,"foreground executes after release");}
        check(ModelAdmission.status().foregroundPending()==0&&ModelAdmission.status().foregroundActive()==0,"no ticket leak");
    }
    private static void deduplication(){
        var entries=List.of(entry("수영을 못해.",NOW-40),entry("수영을 못해.",NOW-30),entry("수영을 못해.",NOW-20),entry("이제 수영을 배웠어.",NOW-10));
        var view=new MemoryJournal.ReadView(KEY,Set.of(PLAYER),entries,Set.of(),true,false);var b=base(view);String fp="fixture";
        var index=new SemanticIndex(entries.stream().map(e->new SemanticIndex.Row(DerivedMemory.project(e,RECALL.timeBasis()),new SemanticIndex.Vector(fp,new float[]{1,0}))).toList());
        var provider=new SemanticIndex.Provider(){public String fingerprint(){return fp;}public CompletableFuture<SemanticIndex.Vector> embed(String text){return CompletableFuture.completedFuture(new SemanticIndex.Vector(fp,new float[]{1,0}));}};
        var result=HybridRetrieval.search(view,b,RECALL,index,provider,NOW,1_000_000_000L,0.60).recall();
        check(result.selected().size()==2&&result.selected().contains(entries.getLast()),"duplicate raw statements cannot crowd out distinct memory");
        check(result.status()==RecallSearch.Status.AMBIGUOUS,"similarity never verifies truth");
    }
    private static void storageSize(Path root)throws Exception {
        var basic=settings(true,MemoryIndexSettings.Execution.DEFAULT);
        var s=new MemoryIndexSettings(true,MemoryIndexSettings.Mode.ON,true,268435456,12000,basic.endpoint(),basic.embeddingModel(),D,1024,basic.extractionModel(),X,350,30000);
        float[] vector=new float[1024];for(int i=0;i<vector.length;i++)vector[i]=(float)Math.sin(i+1)/32;
        try(var raw=new MemoryJournal(root.resolve("size-raw"));var store=new MemoryIndexStore(root.resolve("size-index"),raw,s)){
            store.awaitIdle();for(int i=0;i<6;i++){
                var e=entry(("합성 개인 발언 "+i+". ").repeat(150).substring(0,1200),NOW-10+i);raw.append(e).get(5,TimeUnit.SECONDS);
                var row=new MemoryIndexRow(2,e,DerivedMemory.fingerprint(e),s.fingerprint(),vector,s.extractionVersion(),"SELF_CLAIM",List.of());
                check(store.put(row,List.of(e)).get(5,TimeUnit.SECONDS).equals("STORED"),"1024-dimensional full-text fixture stored");
            }
            check(store.status().records()==6&&store.status().bytes()<6*24576,"fixture fits explicit cap without treating it as a universal upper bound");
            System.out.println("Connection storage fixture: 1024 dimensions, 1200-char sources, 6 rows, bytes="+store.status().bytes());
        }
    }
}
