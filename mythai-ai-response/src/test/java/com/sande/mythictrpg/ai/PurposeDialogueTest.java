package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sande.mythictrpg.understanding.*;
import java.util.*;

public final class PurposeDialogueTest {
    private static int checks;
    static final UUID WORLD=UUID.nameUUIDFromBytes("purpose-world".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    static final String TARGET="test:rubras";
    static PurposeUtterance.Input input(String text,long n) {
        return new PurposeUtterance.Input(WORLD,PlayerUnderstandingPromptTest.PLAYER,new UUID(0,n),n,new UUID(1,n),text,
                List.of(new PurposeUtterance.Target(TARGET,"루브라스")),false);
    }
    private static JsonObject output(PurposeUtterance.Input input,String frame,String signal) {
        var value=new JsonObject();value.addProperty("frame",frame);value.addProperty("fullQuote",input.text());
        value.addProperty("targetId",signal.equals("NONE")?"NONE":TARGET);value.addProperty("kind",signal.equals("NONE")?"NONE":"MEET");value.addProperty("signal",signal);return value;
    }
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void rejects(Runnable r,String why){try{r.run();throw new AssertionError(why);}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args) {
        var i=input("언젠가 루브라스를 만나고 싶어.",1);
        var e=PurposeUtterance.parse(output(i,"DIRECT_SELF","DESIRE"),i).orElseThrow();
        check(e.playerId().equals(i.speakerId())&&e.sourceId().equals(i.sourceId()),"game binds actor and source");
        check(e.sourceDescription().equals(i.text()),"retain whole source, no negation truncation");
        for(String frame:List.of("OTHER_PERSON","HYPOTHETICAL","QUOTED","UNRESOLVED"))
            check(PurposeUtterance.parse(output(i,frame,"NONE"),i).isEmpty(),"no personal goal from "+frame);
        var wrong=output(i,"DIRECT_SELF","DESIRE");wrong.addProperty("targetId","test:hidden");rejects(()->PurposeUtterance.parse(wrong,i),"unknown target");
        var copied=output(i,"OTHER_PERSON","DECIDE");rejects(()->PurposeUtterance.parse(copied,i),"another actor's plan");
        var quote=output(i,"DIRECT_SELF","DESIRE");quote.addProperty("fullQuote","만나기로 했어");rejects(()->PurposeUtterance.parse(quote,i),"changed quotation");
        var completed=output(i,"DIRECT_SELF","COMPLETE");rejects(()->PurposeUtterance.parse(completed,i),"LLM cannot emit action completion");
        var injected=output(i,"DIRECT_SELF","DESIRE");injected.addProperty("actorId",UUID.randomUUID().toString());rejects(()->PurposeUtterance.parse(injected,i),"no model actor field");
        var prepared=PurposeDialogueContext.prepare(WORLD,i.speakerId(),0,List.of(e),true,x->true);
        var request=PlayerUnderstandingPromptTest.request("내가 왜 그 사람 얘기를 하는 것 같아?",List.of(),false);
        var messages=PurposeDialogueContext.attach(PlayerUnderstandingPromptTest.messages(request,List.of()),prepared);
        check(messages.getLast().content().contains("DESIRED"),"typed desired stage supplied");
        var draft=new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech(PlayerUnderstandingPromptTest.GOD.toString(),"만나고 싶은 거구나.",List.of())),"",List.of());
        var review=RoomDialogueGrounding.reviewMessages(messages,draft);
        check(review.stream().anyMatch(m->m.content().contains(PurposeDialogueContext.KEY)),"review retains state and source");
        check(BaselineRoomClaimWitnessAudit.sourceIndex(review).values().stream().anyMatch(v->v.toString().contains("DERIVED_PURPOSE")),"claim reviewer can see source-bound state");
        var attempt=RoomDialogueGrounding.Attempt.initial(messages).withoutUnderstanding();
        var repair=attempt.repair(draft,new RoomDialogueGrounding.Review(false,List.of(new RoomDialogueGrounding.Issue(RoomDialogueGrounding.Code.CONTEXT_CONTRADICTION,"만나고 싶은 거구나.","fixture"))));
        check(repair.evidenceMessages().equals(messages),"repair cannot replace original evidence");
        check(repair.generationMessages().stream().anyMatch(m->m.content().contains(PurposeDialogueContext.KEY)),"repair keeps constraints");
        var patch=RoomDialoguePatch.fromMessages(repair.generationMessages());
        var replacement=new JsonObject();replacement.addProperty("e0","만나고 싶다는 마음은 알겠어.");
        check(patch.apply(replacement).speech().getFirst().text().equals("만나고 싶다는 마음은 알겠어."),
                "derived purpose context is not a spoken role; bounded repair accepts it without NPE");
        var denied=PurposeDialogueContext.prepare(WORLD,i.speakerId(),0,List.of(e),true,x->false);
        check(denied.sources().isEmpty()&&denied.projection().goals().isEmpty(),"filter before state, no private goal leak");
        check(PurposeDialogueContext.attach(messages,denied).equals(messages),"unavailable purpose doesn't add forced denial");
        check(!PurposeUtterance.PRODUCTION_ENABLED&&!PlayerIntentPlan.ENABLED,"quality gate stays off");
        System.out.println("PurposeDialogueTest: PASS ("+checks+" checks); no real model calls");
    }
}
