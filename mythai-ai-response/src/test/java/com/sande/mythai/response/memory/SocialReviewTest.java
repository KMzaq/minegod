package com.sande.mythai.response.memory;

import com.google.gson.*;
import com.sande.mythictrpg.rumor.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Injected transport only. Never opens a socket, starts a server or uses a model. */
public final class SocialReviewTest {
    static int checks;
    static void check(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
    interface Throwing {void run()throws Exception;}
    static void rejects(Throwing r,String label)throws Exception {try{r.run();throw new AssertionError(label);}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
    static String response(String content){return new Gson().toJson(Map.of("model","test:model","done",true,"done_reason","stop","message",Map.of("content",content)));}
    static String answer(){return new Gson().toJson(Map.of("verdict","RECOVER","quote","무기 제작에 필요했어","claim","","epithet","","reason","의도가 구체적으로 설명됨","needsWorldVerification",false,"otherSubjects",false));}
    public static void main(String[] args)throws Exception {backend();prompts();admission();wiring(Path.of(args[0]));System.out.println("SocialReviewTest: "+checks+" assertions passed (mock transport; no real LLM)");}
    static void backend()throws Exception {
        AtomicInteger requests=new AtomicInteger();AtomicReference<JsonObject> body=new AtomicReference<>();
        var model=new OllamaSocialReview((url,json,timeout)->{requests.incrementAndGet();body.set(JsonParser.parseString(json).getAsJsonObject());return response(answer());});
        var request=new SocialReview.Request(UUID.randomUUID(),SocialReview.Kind.RECOVERY,"test:god",120,"비늘을 달라고 했다는 전언",
                List.of(new SocialReview.Line("PLAYER","무기 제작에 필요했어"),new SocialReview.Line("GOD","용도를 설명했어야지")),"");
        var url=URI.create("http://127.0.0.1:11434/api/chat");var settings=new OllamaSocialReview.Settings(true,8000,700);
        rejects(()->model.review(request,"persona",url,"test:model",OllamaSocialReview.Settings.OFF),"OFF stops before transport");
        check(requests.get()==0,"OFF no calls");
        var result=model.review(request,"persona",url,"test:model",settings);
        check(SocialReview.valid(request,result),"actual JSON request/response parser to game validator");
        check(requests.get()==1,"one call no repair/retry chain");
        check(!body.get().get("stream").getAsBoolean()&&!body.get().get("think").getAsBoolean(),"bounded nonstreaming response");
        check(body.get().getAsJsonObject("format").getAsJsonObject("properties").has("needsWorldVerification"),"structured uncertainty field");
        check(body.get().getAsJsonObject("options").get("num_predict").getAsInt()==700,"bounded output tokens");
        String input=body.get().getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
        check(input.contains("120")&&input.contains("persona")&&!input.contains(request.id().toString()),"scoped persona and raw affinity; no mutation ticket in prompt");
        for(String endpoint:List.of("http://example.com/api/chat","http://127.0.0.1:11434/api/chat?x=1","http://user@127.0.0.1/api/chat","http://127.0.0.1:11434/api/pull"))
            rejects(()->model.review(request,"",URI.create(endpoint),"test:model",settings),"remote/redirect/install route denied");
        check(requests.get()==1,"invalid endpoints never reach transport");
        for(String invalid:List.of(answer().replace("RECOVER","UNKNOWN"),answer().replace("false","\"false\""),answer().replace("\"claim\":\"\"","\"claim\":42"),"{}","not json")) {
            try{OllamaSocialReview.parse(invalid);throw new AssertionError("malformed output accepted");}catch(RuntimeException expected){checks++;}
        }
        var more=JsonParser.parseString(answer()).getAsJsonObject();more.addProperty("affinity_delta",-100);
        rejects(()->OllamaSocialReview.parse(more.toString()),"extra game effect output rejected");
        var partial=new OllamaSocialReview((u,j,t)->response(answer()).replace("\"stop\"","\"length\""));
        rejects(()->partial.review(request,"",url,"test:model",settings),"truncated generation not accepted");
        var wrong=new OllamaSocialReview((u,j,t)->response(answer()).replace("test:model","other:model"));
        rejects(()->wrong.review(request,"",url,"test:model",settings),"wrong model rejected");
        check(OllamaSocialReview.Settings.load(Path.of("nonexistent-social-review.json")).equals(OllamaSocialReview.Settings.OFF),"absent settings OFF");
    }
    static void prompts(){
        String system=OllamaSocialReview.instruction();
        check(system.contains("친절하게 만들지")&&system.contains("별도 업적 없이도"),"character-aware conversational recovery allowed");
        check(system.contains("동의한 대사 한 줄")&&system.contains("needsWorldVerification")&&system.contains("otherSubjects"),"not mere AI assent/world claim/other player");
        var root=UUID.randomUUID();
        var old=new RumorLedger.HeardRumor(root,1,"비늘을 달라고 했다는 전언","오해받은 여행자","CAUTIOUS","ACCEPTED",1);
        var recovered=new RumorLedger.HeardRumor(root,1,old.text(),old.epithet(),old.reception(),"RECOVERED",2);
        var row=DialogueMemoryBridge.rumorRow(recovered);
        check(!row.containsKey("claim")&&!row.containsKey("epithet")&&row.containsKey("historical_claim_not_current_belief"),"recovered tag not delivered as current accusation");
        check(DialogueMemoryBridge.prompt(List.of(),List.of(recovered)).contains("RECOVERED"),"actual memory prompt consumes recovered status");
        check(!old.equals(recovered),"stance change invalidates stored rumor provenance references");
        check(MemoryRecallPolicy.generationSystem("base",true).contains("your agreement alone does not execute"),"generation does not claim early game success");
        check(MemoryRecallPolicy.generationSystem("base",false).equals("base"),"unrelated no-memory LP prompt unchanged");
        var history=new ExperienceHistory();var current=new AtomicReference<>(old);
        history.record("그 소문을 믿었지",List.of(ExperienceHistory.Reference.guarded(()->current.get().equals(old))));
        current.set(recovered);check(history.excluded(List.of("그 소문을 믿었지")).size()==1,"old accusation cannot survive as independent transcript memory");
    }
    static void admission()throws Exception {
        ModelAdmission.players(6);
        CountDownLatch entered=new CountDownLatch(1),interrupted=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread worker=new Thread(()->{
            try(var lease=ModelAdmission.followup()){
                if(lease==null)throw new AssertionError("online followup unavailable");entered.countDown();
                try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException expected){interrupted.countDown();release.await(5,TimeUnit.SECONDS);}
            }catch(Throwable error){failure.set(error);entered.countDown();}
        });worker.start();check(entered.await(5,TimeUnit.SECONDS)&&failure.get()==null,"followup works with six online players");
        ModelAdmission.players(6);check(!interrupted.await(30,TimeUnit.MILLISECONDS),"ordinary player-count refresh does not cancel followup");
        try(var ticket=ModelAdmission.foreground()) {
            check(interrupted.await(5,TimeUnit.SECONDS),"foreground interrupts optional inference");
            check(ModelAdmission.status().optionalActive(),"lease retained until transport unwinds");
            check(ModelAdmission.followup()==null,"no parallel optional job while foreground pending");
            release.countDown();worker.join(5000);check(!worker.isAlive()&&failure.get()==null,"cooperative shutdown released worker");
            check(!ModelAdmission.status().optionalActive(),"transport completion releases permit");
        }finally{release.countDown();worker.interrupt();worker.join(5000);ModelAdmission.players(-1);}
    }
    static void wiring(Path generated)throws Exception{
        String source=Files.readString(generated);
        check(source.contains("SocialRuntime.delivered(player, godId.toString(), text)"),"central actual speech hooks review");
        int central=source.indexOf("private static void showDialogue("),mirror=source.indexOf("private static void showDialogueOnly");
        check(source.indexOf("SocialRuntime.delivered",central)<mirror,"not a listener mirror hook");
        check(source.contains("SocialRuntime.cancelPlayer(listener.getUUID())")&&source.contains("SocialRuntime.cancelPlayer(player.getUUID())"),"peer turns invalidate pending reviews");
        check(source.contains("DialogueMemoryBridge.beginAsync(player"),"actual accepted input hook preserved");
    }
}
