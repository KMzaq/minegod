package com.sande.mythai.response.memory;

import com.google.gson.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** All model responses below are synthetic transport fixtures. No sockets, models, Minecraft or GameTest. */
public final class MemoryIndexRuntimeTest {
    private static final Gson JSON=new Gson();
    private static final String DIGEST="a".repeat(64),EXTRACT="b".repeat(64);
    private static final long NOW=Instant.parse("2026-09-17T01:00:00Z").toEpochMilli();
    private static final UUID WORLD=UUID.randomUUID(),PLAYER=UUID.randomUUID(),SESSION=UUID.randomUUID();
    private static final MemoryJournal.Key KEY=new MemoryJournal.Key(WORLD,"mythictrpg:fortuna",PLAYER);
    private static final RecallSettings RECALL=new RecallSettings(true,RecallSettings.TimeBasis.UNSPECIFIED);
    private static Path root;private static int checks;
    private static synchronized void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private interface Checked {void run()throws Exception;}
    private static void invalid(Checked test,String label)throws Exception{try{test.run();}catch(Exception expected){check(true,label);return;}throw new AssertionError(label);}
    private static <T>T get(CompletableFuture<T> f)throws Exception{return f.get(10,TimeUnit.SECONDS);}
    private static MemoryJournal.Entry entry(String text,long time){return entry(KEY,text,time,Set.of(PLAYER));}
    private static MemoryJournal.Entry entry(MemoryJournal.Key key,String text,long time,Set<UUID> audience){return new MemoryJournal.Entry(UUID.randomUUID(),key,SESSION,1,MemoryJournal.Source.PLAYER_STATEMENT,audience,time,text,false);}
    private static MemoryIndexSettings settings(MemoryIndexSettings.Mode mode,boolean consolidate){return new MemoryIndexSettings(true,mode,consolidate,4_000_000,1000,URI.create("http://127.0.0.1:11434"),"fixture-embed:1",DIGEST,2,"fixture-extract:1",EXTRACT,1500,3000);}
    private static RecallQuery query(String text){return RecallQuery.plan(new RecallQuery.Scope(KEY,SESSION,Set.of(PLAYER)),text,2,NOW,null);}
    private static RecallSearch.Result base(MemoryJournal.ReadView view,RecallQuery q){return RecallSearch.search(view,q,RECALL,Set.of(),List.of(),NOW,1_000_000_000L);}
    private static MemoryIndexRow row(MemoryJournal.Entry e,MemoryIndexSettings s){return new MemoryIndexRow(2,e,DerivedMemory.fingerprint(e),s.fingerprint(),new float[]{1,0},s.extractionVersion(),"SELF_CLAIM",List.of());}
    private static final class Fake implements OllamaMemoryBackend.Transport {
        final AtomicInteger posts=new AtomicInteger(),tags=new AtomicInteger();
        volatile boolean broken,changeDigest,unanchored;volatile CountDownLatch entered,release;
        public String request(URI uri,String body,int timeout)throws Exception {
            check(timeout>0,"positive bounded timeout");
            if(uri.getPath().equals("/api/tags")){tags.incrementAndGet();check(body==null,"metadata GET");return JSON.toJson(Map.of("models",List.of(
                    Map.of("name","fixture-embed:1","digest",changeDigest?"c".repeat(64):DIGEST),Map.of("name","fixture-extract:1","digest",EXTRACT))));}
            posts.incrementAndGet();if(entered!=null)entered.countDown();if(release!=null&&!release.await(5,TimeUnit.SECONDS))throw new TimeoutException("fixture release");
            if(broken)throw new java.io.IOException("synthetic failure");var request=JsonParser.parseString(body).getAsJsonObject();
            if(uri.getPath().equals("/api/embed")){
                check(!request.get("truncate").getAsBoolean()&&!request.has("keep_alive"),"no silent truncation or forced unload");
                String text=request.get("input").getAsString();return JSON.toJson(Map.of("model","fixture-embed:1","embeddings",List.of(text.contains("산")?List.of(0,1):List.of(1,0))));
            }
            check(uri.getPath().equals("/api/chat")&&!request.get("stream").getAsBoolean(),"bounded extraction endpoint");
            var messages=request.getAsJsonArray("messages");var context=JsonParser.parseString(messages.get(1).getAsJsonObject().get("content").getAsString()).getAsJsonArray();
            String text=context.get(context.size()-1).getAsJsonObject().get("text").getAsString();
            String relation=context.size()>1&&text.contains("배웠")?"CORRECTS":text.contains("취소")&&context.size()>1?"CANCELS":"NONE";
            String content=JSON.toJson(Map.of("kind",text.contains("친구가")?"REPORTED":"SELF_CLAIM","quote",unanchored?"invented":text.substring(0,Math.min(300,text.length())),"relation",relation,"older",relation.equals("NONE")?-1:0));
            return JSON.toJson(Map.of("model","fixture-extract:1","done",true,"done_reason","stop","message",Map.of("content",content)));
        }
    }
    public static void main(String[] args)throws Exception {
        root=Files.createTempDirectory(Files.createDirectories(Path.of(args[0])),"index-");
        configuration();backend();malformedBackend();store();corruption();pipeline();fallbackAndLifecycle();timeoutAndReindex();service();admission();bodyBudget();
        for(int n:new int[]{1,4,6})fixture(n);
        com.sande.mythictrpg.ai.ModelAdmissionSchedulerFixture.run();
        MemoryModelConnectionTest.run(root.resolve("connection"));
        String overlay=Files.readString(Path.of(args[1]));check(overlay.contains("ModelAdmission.foreground()")&&overlay.contains("admission.close()")&&overlay.contains("ModelAdmission.run("),"actual legacy scheduler overlay connected");
        ModelAdmission.players(-1);
        System.out.println("MemoryIndexRuntimeTest: PASS ("+checks+" checks + scheduler fixture); NO NETWORK/MODEL/SERVER; artifacts="+root);
    }
    private static void configuration()throws Exception {
        check(MemoryIndexSettings.load(root.resolve("absent")).equals(MemoryIndexSettings.OFF),"missing config OFF");
        var config=root.resolve("config.json");Files.writeString(config,"{\"enabled\":true}");check(!MemoryIndexSettings.load(config).enabled(),"incomplete config OFF");
        for(String endpoint:List.of("https://127.0.0.1:11434","http://localhost:11434","http://example.com","http://127.0.0.1/a","http://user:pass@127.0.0.1"))
            invalid(()->new MemoryIndexSettings(true,MemoryIndexSettings.Mode.ON,true,4096,10,URI.create(endpoint),"m",DIGEST,2,"e",EXTRACT,10,10),"reject unapproved origin");
        invalid(()->new MemoryIndexSettings(true,MemoryIndexSettings.Mode.ON,false,4096,10,URI.create("http://127.0.0.1"),"m","unverified-label",2,"","",10,10),"actual digest required");
        var off=root.resolve("off-index");try(var raw=new MemoryJournal(root.resolve("off-raw"));var runtime=new MemoryIndexRuntime(off,raw,MemoryIndexSettings.OFF,new OllamaMemoryBackend(MemoryIndexSettings.OFF,(u,b,t)->{throw new AssertionError("OFF contacted model");}))){runtime.awaitIdle();runtime.pump();runtime.awaitIdle();check(!Files.exists(off),"OFF creates no v2 files");}
    }
    private static void backend()throws Exception {
        var s=settings(MemoryIndexSettings.Mode.ON,true);var fake=new Fake();var backend=new OllamaMemoryBackend(s,fake);
        check(backend.embed("물놀이",false).values()[0]==1&&fake.tags.get()==2,"digest checked before and after embedding");
        var old=entry("수영을 못해",NOW-20);var newer=entry("이제 수영을 배웠어",NOW-10);
        var a=backend.annotate(List.of(old,newer));check(a.links().size()==1&&a.links().getFirst().older().equals(old.id()),"correction candidates anchored to supplied raw only");
        check(backend.annotate(List.of(old,entry("친구가 수영을 배웠어",NOW))).links().isEmpty(),"reported claim never corrects personal state");
        fake.unanchored=true;invalid(()->backend.annotate(List.of(old)),"invented quote rejected");fake.unanchored=false;
        fake.changeDigest=true;int prior=fake.posts.get();invalid(()->backend.embed("수영",false),"changed model digest rejected");check(prior==fake.posts.get(),"digest rejection before model generation");
        fake.changeDigest=false;
        var race=new OllamaMemoryBackend(s,(u,b,t)->{String reply=fake.request(u,b,t);if(u.getPath().equals("/api/embed"))fake.changeDigest=true;return reply;});
        var changing=race;invalid(()->changing.embed("수영",false),"tag replacement during request rejected");fake.changeDigest=false;
        invalid(()->backend.annotate(List.of(old,entry(new MemoryJournal.Key(WORLD,"mythictrpg:amphitrite",PLAYER),"비밀",NOW,Set.of(PLAYER)))),"cross-god extraction blocked");
        invalid(()->backend.embed("x".repeat(1601),true),"input budget");
    }
    private static void store()throws Exception {
        var s=settings(MemoryIndexSettings.Mode.ON,true);var p=root.resolve("store");
        try(var raw=new MemoryJournal(root.resolve("raw-store"))){var e=entry("수영을 못해",NOW-10);get(raw.append(e));
            try(var store=new MemoryIndexStore(p,raw,s)){store.awaitIdle();check(get(store.put(row(e,s),List.of(e))).equals("STORED"),"durable vector+annotation");
                check(get(store.put(row(e,s),List.of(e))).equals("DUPLICATE"),"idempotent fingerprint");
                try(var locked=new MemoryIndexStore(p,raw,s)){locked.awaitIdle();check(locked.status().state().equals("FAILED"),"second writer denied");}}
            try(var store=new MemoryIndexStore(p,raw,s)){store.awaitIdle();var v=raw.readView(KEY,Set.of(PLAYER));check(store.view(v).size()==1,"v2 restart replay");
                check(store.view(raw.readView(KEY,Set.of(PLAYER,UUID.randomUUID()))).isEmpty(),"audience prefilter");
                get(raw.pin(e.id(),raw.view().revision(),true));check(store.view(v).isEmpty(),"old raw revision removed from index");
                check(get(store.put(row(e,s),List.of(e))).equals("STALE"),"late index job blocked");
                var updated=raw.readView(KEY,Set.of(PLAYER)).entries().getFirst();get(store.put(row(updated,s),List.of(updated)));check(store.view(raw.readView(KEY,Set.of(PLAYER))).size()==1,"new source hash rebuild only once");
                get(raw.delete(e.id(),raw.view().revision()));check(store.view(v).isEmpty(),"forget cannot resurrect physical index bytes");}
            var one=new MemoryIndexSettings(true,MemoryIndexSettings.Mode.ON,true,4096,1,s.endpoint(),s.embeddingModel(),DIGEST,2,s.extractionModel(),EXTRACT,1000,3000);
            var a=entry("a",NOW-20);var b=entry("b",NOW-10);get(raw.append(a));get(raw.append(b));
            try(var store=new MemoryIndexStore(root.resolve("quota"),raw,one)){store.awaitIdle();get(store.put(row(a,one),List.of(a)));check(get(store.put(row(b,one),List.of(b))).equals("FULL"),"quota explicit refusal no eviction");check(raw.stillCurrent(List.of(a,b)),"raw unmodified by index full");}
        }
    }
    private static void malformedBackend()throws Exception {
        var s=settings(MemoryIndexSettings.Mode.ON,true);var e=entry("수영을 배웠어",NOW-1);
        for(String failure:List.of("dimension","zero","numeric_string","model","done","extra_field","fractional_index","wrong_quote")){
            var fake=new Fake();var backend=new OllamaMemoryBackend(s,(u,b,t)->{
                var value=JsonParser.parseString(fake.request(u,b,t)).getAsJsonObject();
                if(u.getPath().equals("/api/embed"))switch(failure){case "dimension"->value.add("embeddings",JsonParser.parseString("[[1]]"));case "zero"->value.add("embeddings",JsonParser.parseString("[[0,0]]"));case "numeric_string"->value.add("embeddings",JsonParser.parseString("[[\"1\",0]]"));case "model"->value.addProperty("model","wrong");default->{}}
                if(u.getPath().equals("/api/chat")){
                    if(failure.equals("done"))value.addProperty("done",false);
                    var content=JsonParser.parseString(value.getAsJsonObject("message").get("content").getAsString()).getAsJsonObject();
                    switch(failure){case "extra_field"->content.addProperty("reward","forged");case "fractional_index"->content.addProperty("older",-1.5);case "wrong_quote"->content.addProperty("quote","fabricated");default->{}}
                    value.getAsJsonObject("message").addProperty("content",JSON.toJson(content));
                }return JSON.toJson(value);
            });
            if(Set.of("dimension","zero","numeric_string","model").contains(failure))invalid(()->backend.embed("바다",false),"malformed vector "+failure);
            else invalid(()->backend.annotate(List.of(e)),"malformed annotation "+failure);
        }
        var s0=settings(MemoryIndexSettings.Mode.ON,true);var fake=new Fake();
        try(var raw=new MemoryJournal(root.resolve("raw-links"))){var a=entry("내일 바다에 갈거야",NOW-30);var b=entry("바다 약속은 취소할래",NOW-20);get(raw.append(a));get(raw.append(b));
            var backend=new OllamaMemoryBackend(s0,fake);var annotation=backend.annotate(List.of(a,b));check(annotation.links().getFirst().relation().equals("CANCELS"),"explicit cancellation separate from success");
            var bad=new MemoryIndexRow(2,b,DerivedMemory.fingerprint(b),s0.fingerprint(),new float[]{1,0},s0.extractionVersion(),"SELF_CLAIM",List.of(new MemoryIndexRow.Link(a.id(),"d".repeat(64),"CORRECTS")));
            try(var store=new MemoryIndexStore(root.resolve("bad-link"),raw,s0)){store.awaitIdle();check(get(store.put(bad,List.of(a,b))).equals("STALE_LINK"),"mismatched related source hash rejected");}
            var foreign=entry(new MemoryJournal.Key(UUID.randomUUID(),KEY.god(),PLAYER),"비밀",NOW-20,Set.of(PLAYER));get(raw.append(foreign));
            var guarded=row(foreign,s0);check(!guarded.current(raw.readView(KEY,Set.of(PLAYER))),"cross-world view rejected");
            var otherPlayer=UUID.randomUUID();var other=entry(new MemoryJournal.Key(WORLD,KEY.god(),otherPlayer),"남의 말",NOW-10,Set.of(otherPlayer));get(raw.append(other));check(!row(other,s0).current(raw.readView(KEY,Set.of(PLAYER))),"cross-player view rejected");
            var pending=new MemoryJournal.ReadView(KEY,Set.of(PLAYER),List.of(a),Set.of(a.id()),true,false);check(!row(a,s0).current(pending),"pending cannot become durable derivation");
        }
    }
    private static void corruption()throws Exception {
        var s=settings(MemoryIndexSettings.Mode.ON,true);
        try(var raw=new MemoryJournal(root.resolve("raw-corrupt"))){var e=entry("원문",NOW-10);get(raw.append(e));
            for(String kind:List.of("tail","hash","version","oversize")){Path p=root.resolve("corrupt-"+kind);try(var store=new MemoryIndexStore(p,raw,s)){store.awaitIdle();get(store.put(row(e,s),List.of(e)));}
                Path file=p.resolve("index-v2.jsonl");String text=Files.readString(file);text=switch(kind){case "tail"->text.substring(0,text.length()-1);case "hash"->text.replace("원문","변조");case "version"->text.replace("\"version\":2","\"version\":99");default->"x".repeat(262145)+"\n";};Files.writeString(file,text);byte[] before=Files.readAllBytes(file);
                try(var store=new MemoryIndexStore(p,raw,s)){store.awaitIdle();check(store.status().state().equals("FAILED"),"corrupt sealed "+kind);check(get(store.put(row(e,s),List.of(e))).equals("UNAVAILABLE"),"corrupt no writes");}
                check(Arrays.equals(before,Files.readAllBytes(file))&&raw.stillCurrent(List.of(e)),"corrupt bytes and raw preserved");}
        }
    }
    private static void pipeline()throws Exception {
        var s=settings(MemoryIndexSettings.Mode.ON,true);var fake=new Fake();ModelAdmission.players(0);
        try(var raw=new MemoryJournal(root.resolve("raw-pipeline"));var runtime=new MemoryIndexRuntime(root.resolve("pipeline"),raw,s,new OllamaMemoryBackend(s,fake))){
            var a=entry("수영을 못해",NOW-2000);var b=entry("이제 수영을 배웠어",NOW-1000);get(raw.append(a));get(raw.append(b));runtime.awaitIdle();
            ModelAdmission.players(1);runtime.pump();runtime.awaitIdle();check(fake.posts.get()==0,"background requires zero players");
            ModelAdmission.players(0);try(var ticket=ModelAdmission.foreground()){runtime.pump();runtime.awaitIdle();check(fake.posts.get()==0,"queued foreground defers maintenance");}
            runtime.pump();runtime.awaitIdle();runtime.pump();runtime.awaitIdle();check(runtime.status().records()==2&&fake.posts.get()==4,"automatic embed/extract/durable index path");
            var v=raw.readView(KEY,Set.of(PLAYER));var q=query("물놀이 이야기 기억해?");var base=base(v,q);var found=runtime.search(v,base,RECALL,NOW);
            check(found.selected().containsAll(List.of(a,b))&&found.status()==RecallSearch.Status.AMBIGUOUS,"semantic recall plus candidate correction pair");
            var prompt=MemoryRecallPolicy.pack(found,List.of(),RECALL);check(prompt.selected().size()==2&&prompt.prompt().contains("candidate_corrects_not_verified")&&prompt.prompt().contains("수영을 못해")&&prompt.prompt().contains("이제 수영을 배웠어"),"both verbatim alternatives consumed by actual prompt pack");
            check(raw.view().entries().equals(List.of(a,b)),"no model raw rewrite or forced pin");
            int prior=fake.posts.get();var expanded=raw.readView(KEY,Set.of(PLAYER,UUID.randomUUID()));runtime.search(expanded,base(expanded,q),RECALL,NOW);check(fake.posts.get()==prior,"unauthorized audience filtered before embedding");
            fake.broken=true;var fallback=runtime.search(v,base,RECALL,NOW);check(!fallback.selected().isEmpty(),"model failure preserves lexical path");fake.broken=false;
            get(raw.delete(a.id(),raw.view().revision()));var fresh=raw.readView(KEY,Set.of(PLAYER));var noOld=runtime.search(fresh,base(fresh,q),RECALL,NOW);check(!noOld.selected().contains(a)&&!noOld.reasons().values().toString().contains("candidate_corrects"),"deletion invalidates candidate link immediately");
        }
        try(var raw=new MemoryJournal(root.resolve("raw-pipeline"));var runtime=new MemoryIndexRuntime(root.resolve("pipeline"),raw,s,new OllamaMemoryBackend(s,fake))){get(raw.delete(UUID.randomUUID(),0));runtime.awaitIdle();int prior=fake.posts.get();runtime.pump();runtime.awaitIdle();check(prior==fake.posts.get(),"restart resumes without duplicate extraction");}
    }
    private static void fallbackAndLifecycle()throws Exception {
        var fake=new Fake();var s=settings(MemoryIndexSettings.Mode.SHADOW,true);ModelAdmission.players(0);
        try(var raw=new MemoryJournal(root.resolve("raw-shadow"));var runtime=new MemoryIndexRuntime(root.resolve("shadow"),raw,s,new OllamaMemoryBackend(s,fake))){get(raw.append(entry("수영을 못해",NOW-1)));runtime.awaitIdle();runtime.pump();runtime.awaitIdle();var v=raw.readView(KEY,Set.of(PLAYER));var base=base(v,query("물놀이 기억해?"));
            check(runtime.search(v,base,RECALL,NOW)==base,"SHADOW returns exact old result including labels");
            fake.changeDigest=true;check(runtime.search(v,base,RECALL,NOW)==base,"digest mismatch fallback");}
        fake=new Fake();fake.broken=true;var limited=fake;var on=settings(MemoryIndexSettings.Mode.ON,true);
        try(var raw=new MemoryJournal(root.resolve("raw-retry"));var runtime=new MemoryIndexRuntime(root.resolve("retry"),raw,on,new OllamaMemoryBackend(on,limited))){get(raw.append(entry("원문",NOW-1)));runtime.awaitIdle();for(int i=0;i<5;i++){runtime.pump();runtime.awaitIdle();}check(limited.posts.get()==3&&runtime.diagnostic().contains("RETRY_LIMIT"),"failure retry cap explicit");}
        var blocked=new Fake();blocked.entered=new CountDownLatch(1);blocked.release=new CountDownLatch(1);
        try(var raw=new MemoryJournal(root.resolve("raw-close"))){get(raw.append(entry("종료 경쟁",NOW-1)));var runtime=new MemoryIndexRuntime(root.resolve("close"),raw,on,new OllamaMemoryBackend(on,blocked));runtime.awaitIdle();runtime.pump();check(blocked.entered.await(2,TimeUnit.SECONDS),"background started");runtime.close();blocked.release.countDown();check(runtime.drained()&&!ModelAdmission.status().optionalActive(),"shutdown drains worker and releases admission");check(runtime.status().records()==0,"late interrupted extraction not committed");}
    }
    private static void service()throws Exception {
        var p=Files.createDirectories(root.resolve("service-config"));var s=settings(MemoryIndexSettings.Mode.ON,true);writeConfig(p.resolve("ai-memory-index.json"),s);var fake=new Fake();ModelAdmission.players(0);
        try(var raw=new MemoryJournal(root.resolve("raw-service"))){get(raw.append(entry("수영을 못해",NOW-1)));Path config=p.resolve("ai-derived-memory.json"),dir=root.resolve("service-world/derived-v1");
            DerivedService.initialize(raw,config,dir,settings->new OllamaMemoryBackend(settings,fake));DerivedService.awaitIdle(raw);DerivedService.pump(raw);DerivedService.awaitIdle(raw);
            var q=query("물놀이 기억해?");var result=DerivedService.search(raw,config,dir,raw.readView(KEY,Set.of(PLAYER)),q,RECALL,Set.of(),List.of(),NOW);
            check(result.reasons().values().stream().anyMatch(x->x.contains("semantic")),"real DerivedService attachment consumes runtime result");check(!Files.exists(dir)&&Files.exists(dir.resolveSibling("index-v2/index-v2.jsonl")),"v2 independent of disabled v1 sidecar");
            check(DerivedService.close(List.of(raw)),"service drained");int prior=fake.posts.get();DerivedService.initialize(raw,config,dir,settings->new OllamaMemoryBackend(settings,fake));DerivedService.pump(raw);check(!DerivedService.initialized(raw)&&fake.posts.get()==prior,"late readers cannot reopen closed service");}
    }
    private static void timeoutAndReindex()throws Exception {
        var slow=new MemoryIndexSettings(true,MemoryIndexSettings.Mode.ON,false,4_000_000,1000,URI.create("http://127.0.0.1:11434"),"fixture-embed:1",DIGEST,2,"","",50,3000);
        var fake=new Fake();ModelAdmission.players(0);Path p=root.resolve("timeout-index");
        try(var raw=new MemoryJournal(root.resolve("raw-timeout"))){var e=entry("수영을 못해",NOW-1);get(raw.append(e));
            try(var runtime=new MemoryIndexRuntime(p,raw,slow,new OllamaMemoryBackend(slow,fake))){runtime.awaitIdle();runtime.pump();runtime.awaitIdle();
                fake.entered=new CountDownLatch(1);fake.release=new CountDownLatch(1);var view=raw.readView(KEY,Set.of(PLAYER));var base=base(view,query("물놀이 기억해?"));
                long start=System.nanoTime();var result=runtime.search(view,base,RECALL,NOW);long elapsed=(System.nanoTime()-start)/1_000_000;
                check(result==base&&elapsed<1000,"query timeout returns lexical fallback promptly");check(fake.entered.getCount()==0&&ModelAdmission.status().optionalActive(),"timeout does not release hardware permit while transport still running");
                fake.release.countDown();}
            check(!ModelAdmission.status().optionalActive(),"query shutdown cleans active permit");fake.entered=null;fake.release=null;fake.changeDigest=true;
            var changed=new MemoryIndexSettings(true,MemoryIndexSettings.Mode.ON,false,4_000_000,1000,slow.endpoint(),slow.embeddingModel(),"c".repeat(64),2,"","",1000,3000);
            try(var runtime=new MemoryIndexRuntime(p,raw,changed,new OllamaMemoryBackend(changed,fake))){runtime.awaitIdle();var v=raw.readView(KEY,Set.of(PLAYER));var base=base(v,query("물놀이 기억해?"));int prior=fake.posts.get();
                check(runtime.search(v,base,RECALL,NOW)==base&&fake.posts.get()==prior,"old model rows excluded before query call");runtime.pump();runtime.awaitIdle();check(runtime.status().records()==2,"changed model builds new version without deleting old bytes");
                check(runtime.search(v,base,RECALL,NOW).reasons().values().contains("semantic"),"new model namespace used after reindex");}
        }
    }
    private static void writeConfig(Path p,MemoryIndexSettings s)throws Exception {
        var j=new JsonObject();j.addProperty("schemaVersion",1);j.addProperty("enabled",s.enabled());j.addProperty("semanticMode",s.semanticMode().name());j.addProperty("consolidate",s.consolidate());j.addProperty("maxStorageBytes",s.maxStorageBytes());j.addProperty("maxEntries",s.maxEntries());j.addProperty("endpoint",s.endpoint().toString());j.addProperty("embeddingModel",s.embeddingModel());j.addProperty("modelRevision",s.modelRevision());j.addProperty("dimensions",s.dimensions());j.addProperty("extractionModel",s.extractionModel());j.addProperty("extractionRevision",s.extractionRevision());j.addProperty("queryTimeoutMs",s.queryTimeoutMs());j.addProperty("backgroundTimeoutMs",s.backgroundTimeoutMs());Files.writeString(p,JSON.toJson(j));
        check(MemoryIndexSettings.load(p).equals(s),"config roundtrip");
    }
    private static void admission()throws Exception {
        ModelAdmission.players(-1);check(ModelAdmission.optional(true)==null,"unknown server state fails closed");ModelAdmission.players(6);check(ModelAdmission.optional(true)==null,"six connected users defer optional consolidation");
        try(var optional=ModelAdmission.optional(false)){check(optional!=null,"bounded foreground query may run while players online");
            var ticket=ModelAdmission.foreground();invalid(()->ModelAdmission.run(ticket,1,1,()->1),"foreground admission deadline");ticket.close();}
        try(var cancelled=ModelAdmission.foreground()){cancelled.close();invalid(()->ModelAdmission.run(cancelled,1,100,()->1),"cancelled queue item never invokes model");}
        check(ModelAdmission.status().foregroundPending()==0&&ModelAdmission.status().foregroundActive()==0&&!ModelAdmission.status().optionalActive(),"admission counter cleanup");
    }
    private static void bodyBudget()throws Exception {
        var cancelled=new AtomicBoolean();var body=new OllamaMemoryBackend.LimitedBody();body.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){cancelled.set(true);}});body.onNext(List.of(ByteBuffer.wrap(new byte[262145])));
        check(cancelled.get()&&body.getBody().toCompletableFuture().isCompletedExceptionally(),"oversize response cancelled before unbounded buffering");
        body=new OllamaMemoryBackend.LimitedBody();body.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){}});body.onNext(List.of(ByteBuffer.wrap(new byte[]{(byte)0xff})));body.onComplete();check(body.getBody().toCompletableFuture().isCompletedExceptionally(),"invalid UTF8 rejected");
    }
    private static void fixture(int players)throws Exception {
        ModelAdmission.players(0);var fake=new Fake();var s=settings(MemoryIndexSettings.Mode.ON,true);long start=System.nanoTime();
        try(var raw=new MemoryJournal(root.resolve("raw-fixture-"+players));var runtime=new MemoryIndexRuntime(root.resolve("fixture-"+players),raw,s,new OllamaMemoryBackend(s,fake))){
            for(int i=0;i<players;i++){var id=UUID.randomUUID();var key=new MemoryJournal.Key(WORLD,"mythictrpg:fortuna",id);for(int n=0;n<12;n++)get(raw.append(entry(key,"합성 발언 "+n,NOW-100+n,Set.of(id))));}
            runtime.awaitIdle();for(int i=0;i<players*12;i++){runtime.pump();runtime.awaitIdle();}
            check(runtime.status().records()==players*12&&runtime.status().rejected()==0,"synthetic "+players+" player index completion");
            System.out.println("Index fixture players="+players+" records="+runtime.status().records()+" bytes="+runtime.status().bytes()+" elapsed_ms="+(System.nanoTime()-start)/1_000_000+" (fake models, not in-game latency)");}
    }
}
