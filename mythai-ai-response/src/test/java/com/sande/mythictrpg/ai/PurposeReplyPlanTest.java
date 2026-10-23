package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sande.mythictrpg.understanding.*;
import java.util.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

public final class PurposeReplyPlanTest {
    private static int checks;
    static PurposeEvidence evidence(long n,PurposeEvidence.Signal signal,String text) {
        return new PurposeEvidence(PurposeDialogueTest.WORLD,PlayerUnderstandingPromptTest.PLAYER,new UUID(0,n),n,new UUID(1,n),
                new PurposeEvidence.Goal(PurposeEvidence.Kind.MEET,PurposeDialogueTest.TARGET),PurposeEvidence.Origin.SELF_SPEECH,
                PurposeEvidence.Attribution.DIRECT_SELF,signal,PurposeEvidence.Context.DIRECTED,false,false,text);
    }
    static PurposeDialogueContext.Prepared prepared(List<PurposeEvidence> e,boolean complete){
        return PurposeDialogueContext.prepare(PurposeDialogueTest.WORLD,PlayerUnderstandingPromptTest.PLAYER,0,e,complete,x->true);
    }
    static PurposeReplyPlan.Plan plan(PurposeDialogueContext.Prepared p){return PurposeReplyPlan.compile(PlayerUnderstandingPromptTest.GOD.toString(),p,
            List.of(new PurposeUtterance.Target(PurposeDialogueTest.TARGET,"루브라스")));}
    static JsonObject reply(String id,String text){var o=new JsonObject();o.addProperty("anchorId",id);o.addProperty("continuation",text);return o;}
    static JsonObject ok(){var o=new JsonObject();for(String d:ReplyMeaningAudit.DIMENSIONS){var f=new JsonObject();f.addProperty("verdict","OK");f.addProperty("excerpt","");f.addProperty("correction","");o.add(d,f);}return o;}
    public static void main(String[] args)throws Exception {
        var wish=evidence(1,PurposeEvidence.Signal.DESIRE,"루브라스를 만나고 싶어.");
        var p=prepared(List.of(wish),true);var plan=plan(p);
        check(plan.anchors().size()==2,"casual and polite alternatives, one meaning");
        for(var a:plan.anchors()){
            check(a.text().contains("만나고 싶다고")&&!a.text().contains("결정"),"wish not a positive choice");
            check(plan.realize(reply(a.id(),"그 마음은 알겠어.")).speech().getFirst().text().startsWith(a.text()),"code preserves anchor verbatim");
        }
        check(plan.realize(reply("NONE","오늘은 내 얘기를 하고 싶지 않아.")).speech().size()==1,"NPC refusal allowed, no forced purpose");
        var anchored=plan.realize(reply(plan.anchors().getFirst().id(),"그 마음은 알겠어."));
        plan.requirePreserved(anchored,plan.realize(reply(plan.anchors().getFirst().id(),"그렇군.")));
        check(true,"repair may change continuation without changing semantic anchor");
        rejects(()->plan.requirePreserved(anchored,plan.realize(reply("NONE","이미 결심했군."))),"repair cannot remove or strengthen anchor");
        rejects(()->plan.realize(reply("invented","")),"unknown anchor");
        rejects(()->plan.realize(reply("NONE","")),"empty reply");
        var injected=reply("NONE","안녕");injected.addProperty("speakerId","test:other");rejects(()->plan.realize(injected),"model cannot change speaker");
        rejects(()->plan.realize(reply("NONE","가".repeat(1601))),"bounded output");
        rejects(()->plan.realize(reply(plan.anchors().getFirst().id(),"그 집념이 대단하군."),true),"bounded purpose response forbids free intensity claims");
        check(plan.realize(reply(plan.anchors().getFirst().id(),plan.continuations().get(1)),true).speech().getFirst().text().contains("싶다고"),"bounded realization preserves wish");
        plan.requireBounded(plan.realize(reply(plan.anchors().getFirst().id(),""),true));check(true,"protected kernel alone permitted");
        rejects(()->plan.requireBounded(plan.realize(reply(plan.anchors().getFirst().id(),"넌 결심했어."))),"repair cannot escape bounded meaning set");
        check(plan(prepared(List.of(wish,evidence(2,PurposeEvidence.Signal.DESIRE,"아직 만나고 싶어")),true)).anchors().stream()
                .noneMatch(a->a.text().contains("결정")),"repetition no decision");
        check(plan(prepared(List.of(wish,evidence(2,PurposeEvidence.Signal.WITHDRAW,"더는 찾지 않을래")),true)).anchors().stream()
                .allMatch(a->a.text().contains("그만두겠다고")),"negative choice not positive commitment");
        check(plan(prepared(List.of(evidence(1,PurposeEvidence.Signal.DECIDE,"만나기로 결정했어")),true)).anchors().stream()
                .allMatch(a->a.text().contains("결정했다고")),"explicit decision acknowledged immediately");
        check(plan(prepared(List.of(wish),false)).anchors().isEmpty(),"incomplete evidence cannot certify current meaning");
        check(PurposeReplyPlan.compile("test:god",p,List.of()).anchors().isEmpty(),"unresolved display name no invented name");
        var denied=PurposeDialogueContext.prepare(PurposeDialogueTest.WORLD,PlayerUnderstandingPromptTest.PLAYER,0,List.of(wish),true,x->false);
        check(plan(denied).anchors().isEmpty(),"filtered source does not leak into anchor");
        var wrong=new PurposeDialogueContext.Prepared(p.projection(),List.of());rejects(()->plan(wrong),"forged projection");
        var foreign=new PurposeEvidence(wish.worldId(),UUID.randomUUID(),new UUID(0,99),99,new UUID(1,99),wish.goal(),wish.origin(),
                wish.attribution(),PurposeEvidence.Signal.DECIDE,wish.context(),false,false,"다른 사람의 결심");
        rejects(()->plan(new PurposeDialogueContext.Prepared(p.projection(),List.of(wish,foreign))),"foreign source cannot win latest-source selection");
        var r=PlayerUnderstandingPromptTest.request("그 마음을 이해했어?",List.of(),false);
        var messages=PurposeDialogueContext.attach(PlayerUnderstandingPromptTest.messages(r,List.of()),p);
        check(PurposeReplyPlan.messages(messages,plan).getLast().content().contains("availableAnchors"),"candidate schema and choices supplied");
        rejects(()->PurposeReplyPlan.messages(messages,PurposeReplyPlan.compile("test:other",p,List.of())),"cross NPC blocked");
        rejects(()->PurposeReplyPlan.messages(PlayerUnderstandingPromptTest.messages(r,List.of()),plan),"missing evidence blocked");
        var changed=prepared(List.of(wish,evidence(2,PurposeEvidence.Signal.WITHDRAW,"그만둘래")),true);
        rejects(()->PurposeReplyPlan.messages(PurposeDialogueContext.attach(PlayerUnderstandingPromptTest.messages(r,List.of()),changed),plan),"stale purpose plan blocked");
        var draft=plan.realize(reply("NONE","그를 만나기로 결심했구나."));
        check(ReplyMeaningAudit.parse(ok(),draft).pass(),"all five OK needed");
        var violation=ok();var f=violation.getAsJsonObject("commitment");f.addProperty("verdict","VIOLATION");f.addProperty("excerpt","만나기로 결심했구나.");f.addProperty("correction","희망을 결정으로 올리지 말 것.");
        check(!ReplyMeaningAudit.parse(violation,draft).pass()&&ReplyMeaningAudit.parse(violation,draft).repairReview().orElseThrow().issues().size()==1,"localized repair");
        violation.add("polarity",f.deepCopy());
        check(ReplyMeaningAudit.parse(violation,draft).repairReview().orElseThrow().issues().size()==1,"overlapping dimensions share one edit slot");
        f.addProperty("excerpt","존재하지 않는 말");rejects(()->ReplyMeaningAudit.parse(violation,draft),"unquoted finding rejected");
        var unknown=ok();f=unknown.getAsJsonObject("owner");f.addProperty("verdict","UNKNOWN");f.addProperty("correction","생략된 주어를 해소할 수 없음.");
        var uncertainty=ReplyMeaningAudit.parse(unknown,draft);
        check(!uncertainty.pass()&&uncertainty.uncertain()&&uncertainty.repairReview().isEmpty(),"unknown not publish or automatic rewrite");
        var invalid=ok();invalid.remove("agency");rejects(()->ReplyMeaningAudit.parse(invalid,draft),"no omitted dimension");
        check(!PurposeReplyPlan.PRODUCTION_ENABLED&&!PurposeUtterance.PRODUCTION_ENABLED,"production stays disabled");
        var surface=surface(draft);
        check(ReplySurfaceReading.parse(surface,draft).equals(surface),"surface diagnostic preserves exact draft");
        var broken=surface.deepCopy();broken.getAsJsonObject("units").getAsJsonObject("u0").addProperty("text","바꾼 대사");
        rejects(()->ReplySurfaceReading.parse(broken,draft),"no changed literal reading quote");
        check(!ReplySurfaceReading.messages(draft).toString().contains("sourceBoundPurposeState"),"surface reading cannot normalize toward source state");
        transport(messages,plan);
        System.out.println("PurposeReplyPlanTest: PASS ("+checks+" checks)");
    }
    private static JsonObject surface(AiDialogueModels.StructuredAiResult draft){
        var result=new JsonObject();var units=new JsonObject();
        RoomEvidenceAudit.units(draft).forEach((id,text)->{var u=new JsonObject();u.addProperty("text",text);u.addProperty("mode","NO_DECISION_CLAIM");u.addProperty("owner","NONE");u.addProperty("literalAction","");units.add(id,u);});
        result.add("units",units);result.addProperty("nextStorySpeaker","NONE");result.addProperty("explicitlyDeclinesOwnStory",false);return result;
    }
    private static void transport(List<AiDialogueModels.OllamaMessage> messages,PurposeReplyPlan.Plan plan)throws Exception {
        var count=new AtomicInteger();var error=new AtomicReference<Throwable>();var gson=new Gson();
        var draft=plan.realize(reply(plan.anchors().getFirst().id(),"그렇군."));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/chat",exchange->{
            try {
                int n=count.incrementAndGet();var body=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                if(!body.get("model").getAsString().equals("gemma4:12b"))throw new AssertionError("model changed");
                if(!body.get("format").isJsonObject())throw new AssertionError("schema missing");
                Object value=switch(n){case 1->reply(plan.anchors().getFirst().id(),"그렇군.");case 2->surface(draft);case 3->ok();default->throw new AssertionError("extra retry");};
                if(n==2&&body.get("messages").toString().contains("sourceBoundPurposeState"))throw new AssertionError("source leak into independent reading");
                byte[] bytes=gson.toJson(Map.of("message",Map.of("content",gson.toJson(value)))).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable failure){error.set(failure);exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}
        });server.start();
        try(var llm=new LocalOllamaClient()) {
            var settings=new AiDialogueConfig.Settings(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/chat"),"gemma4:12b",20,600,420,20,260,180,true,96,3,1,16,2,3,3,120,3,false);
            var actual=llm.submitPlannedReply(UUID.randomUUID(),messages,plan,settings).completion().get(25,TimeUnit.SECONDS).value();
            check(actual.equals(draft),"wire output realized with fixed actor and core");
            var audit=llm.submitMeaningAudit(UUID.randomUUID(),messages,actual,settings).completion().get(45,TimeUnit.SECONDS).value();
            check(audit.pass()&&count.get()==3&&error.get()==null,"bounded source-independent reading and audit use same model");
        }finally{server.stop(0);}
    }
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void rejects(Runnable r,String why){try{r.run();throw new AssertionError(why);}catch(IllegalArgumentException expected){checks++;}}
}
