package com.sande.mythictrpg.ai;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.HistoryLine;
import java.util.*;

public final class PlayerIntentPlanTest {
    private static int checks;
    private static void check(boolean c,String why){checks++;if(!c)throw new AssertionError(why);}
    public static void main(String[] args) {
        var r=PlayerUnderstandingPromptTest.request("짐작 가?",List.of(
                new HistoryLine("PLAYER",PlayerUnderstandingPromptTest.PLAYER.toString(),"나","OOO을 찾고 있어."),
                new HistoryLine("PLAYER",UUID.randomUUID().toString(),"다른 사람","숨겨진 목적")),false);
        var messages=PlayerUnderstandingPromptTest.messages(r,List.of());
        var input=PlayerIntentPlan.input(r,messages);
        check(input.sources().size()==2,"own speaker only");
        check(input.sources().stream().noneMatch(s->s.text().contains("숨겨진")),"no other player goal");
        var raw=JsonParser.parseString("{\"frame\":\"ACTIVE_SELF\",\"stance\":\"INFERRED\",\"meaning\":\"OOO을 찾는 중일 수 있음\",\"basis\":[{\"sourceId\":\"h0\",\"text\":\"OOO을 찾고 있어.\"}]}").getAsJsonObject();
        var plan=PlayerIntentPlan.parse(raw,input);
        check(plan.mode()==PlayerIntentPlan.Mode.INFERRED_GOAL,"typed hypothesis");
        var attempt=RoomDialogueGrounding.Attempt.initial(messages).withUnderstanding(plan);
        check(attempt.evidenceMessages().equals(messages),"interpretation cannot certify itself");
        check(!attempt.evidenceMessages().toString().contains("중일 수 있음"),"frozen evidence excludes inference");
        check(attempt.generationMessages().size()==messages.size()+1,"guidance separate from facts");
        check(attempt.generationMessages().getFirst().content().contains("[TENTATIVE_PLAYER_READING]"),"trusted policy makes ordinary inference explicit");
        check(!attempt.generationMessages().getFirst().content().contains(plan.meaning()),"model candidate never becomes system instructions");
        check(attempt.understandingPrepared()&&attempt.understandingPresent(),"prepared once and review required");
        try {new PlayerIntentPlan.Plan(PlayerIntentPlan.Mode.STATED_GOAL,PlayerIntentPlan.Stance.DESIRED,"목적",List.of(new PlayerIntentPlan.Quote("h0","원문에 없는 말"))).validated(input);throw new AssertionError("fake quote");}
        catch(IllegalArgumentException expected){checks++;}
        try {new PlayerIntentPlan.Plan(PlayerIntentPlan.Mode.STATED_GOAL,PlayerIntentPlan.Stance.DESIRED,"목적",List.of(new PlayerIntentPlan.Quote("secret","OOO을 찾고 있어."))).validated(input);throw new AssertionError("foreign source");}
        catch(IllegalArgumentException expected){checks++;}
        var secondary=PlayerUnderstandingPromptTest.request("아직 전달하지 않은 비밀",r.history(),true);
        var projected=PlayerIntentPlan.input(secondary,PlayerUnderstandingPromptTest.messages(secondary,List.of()));
        check(projected.current().isEmpty()&&projected.sources().stream().noneMatch(s->s.text().contains("전달하지")),"secondary never sees pending input");
        var none=RoomDialogueGrounding.Attempt.initial(messages).withUnderstanding(PlayerIntentPlan.Plan.none());
        check(none.understandingPresent()&&none.generationMessages().getFirst().content().contains("mode=NONE"),"successful NONE keeps bounded uncertainty guidance");
        check(none.evidenceMessages().equals(messages),"NONE not added as evidence of no goals");
        var skipped=RoomDialogueGrounding.Attempt.initial(messages).withoutUnderstanding();
        check(skipped.generationMessages().equals(messages)&&!skipped.understandingPresent()&&skipped.understandingPrepared(),"disabled/failure path keeps original generation");
        var silence=new AiDialogueModels.StructuredAiResult(List.of(),"",List.of());
        var spoken=new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech(r.speakerGodId().toString(),"알겠어.",List.of())),"",List.of());
        check(!none.requiresUnderstandingReview(silence),"NONE interpretation does not turn secondary silence into an audit failure");
        check(!attempt.requiresUnderstandingReview(silence),"positive interpretation also preserves secondary silence");
        check(none.requiresUnderstandingReview(spoken),"successful NONE still reviews actual speech");
        check(!skipped.requiresUnderstandingReview(spoken),"disabled path keeps risk-based review selection");
        try {skipped.withUnderstanding(plan);throw new AssertionError("repeated preparation");}catch(IllegalStateException expected){checks++;}
        var view=PlayerIntentPlan.messages(input);
        check(!view.getFirst().content().contains("OOO"),"untrusted source never instructions");
        check(PlayerIntentPlan.guide(messages,plan).getLast().content().contains("no new action"),"no new execution authority");
        for(var frame:PlayerIntentPlan.Frame.values())if(frame!=PlayerIntentPlan.Frame.ACTIVE_SELF) {
            var conditional=raw.deepCopy();conditional.addProperty("frame",frame.name());
            check(PlayerIntentPlan.parse(conditional,input).mode()==PlayerIntentPlan.Mode.NONE,"non-personal/conditional frame cannot create goal");
        }
        var invalid=raw.deepCopy();invalid.addProperty("meaning",42);
        try {PlayerIntentPlan.parse(invalid,input);throw new AssertionError("coerced primitive");}
        catch(IllegalArgumentException expected){checks++;}
        check(!PlayerIntentPlan.ENABLED,"failed quality planner remains disabled");
        var schema=new com.google.gson.Gson().toJsonTree(PlayerIntentPlan.schema()).getAsJsonObject();
        check(new ArrayList<>(schema.getAsJsonObject("properties").keySet()).equals(List.of("frame","basis","stance","meaning")),"stable evidence-first schema order");
        check(new ArrayList<>(schema.getAsJsonObject("properties").getAsJsonObject("basis").getAsJsonObject("items").getAsJsonObject("properties").keySet()).equals(List.of("sourceId","text")),"stable quote field order");
        var excluded=raw.deepCopy();excluded.addProperty("stance","NONE");excluded.addProperty("meaning","사용하지 않을 설명");
        check(PlayerIntentPlan.parse(excluded,input).equals(PlayerIntentPlan.Plan.none()),"NONE cannot leak commentary/evidence");
        excluded.addProperty("frame","QUOTED");excluded.addProperty("stance","DESIRED");
        excluded.getAsJsonArray("basis").get(0).getAsJsonObject().addProperty("text","출처에 없는 무시할 문장");
        check(PlayerIntentPlan.parse(excluded,input).equals(PlayerIntentPlan.Plan.none()),"excluded frame cannot emit goal even with noncanonical discarded fields");
        excluded.addProperty("frame","ACTIVE_SELF");
        try {PlayerIntentPlan.parse(excluded,input);throw new AssertionError("active candidate escaped source validation");}
        catch(IllegalArgumentException expected){checks++;}
        for(var stance:PlayerIntentPlan.Stance.values())for(var frame:PlayerIntentPlan.Frame.values()) {
            var sample=raw.deepCopy();sample.addProperty("stance",stance.name());sample.addProperty("frame",frame.name());
            if(stance==PlayerIntentPlan.Stance.WITHDRAWN) {
                var quote=sample.getAsJsonArray("basis").get(0).getAsJsonObject();
                quote.addProperty("sourceId","current");quote.addProperty("text",input.current());
            }
            var expected=switch(stance) {
                case NONE,UNDECIDED,REQUESTED_BY_OTHER -> PlayerIntentPlan.Mode.NONE;
                case INTEREST -> PlayerIntentPlan.Mode.INTEREST;
                case DESIRED,DECIDED -> PlayerIntentPlan.Mode.STATED_GOAL;
                case INFERRED -> PlayerIntentPlan.Mode.INFERRED_GOAL;
                case WITHDRAWN -> PlayerIntentPlan.Mode.CORRECTED_GOAL;
            };
            var actual=PlayerIntentPlan.parse(sample,input);
            check(actual.mode()==(frame==PlayerIntentPlan.Frame.ACTIVE_SELF?expected:PlayerIntentPlan.Mode.NONE),"stance/frame matrix");
            if(actual.mode()==PlayerIntentPlan.Mode.NONE)check(actual.equals(PlayerIntentPlan.Plan.none()),"excluded stance cannot retain target");
        }
        try {new PlayerIntentPlan.Plan(PlayerIntentPlan.Mode.STATED_GOAL,PlayerIntentPlan.Stance.REQUESTED_BY_OTHER,"목적",plan.basis());throw new AssertionError("inconsistent internal state");}
        catch(IllegalArgumentException expected){checks++;}
        var staleWithdrawal=raw.deepCopy();staleWithdrawal.addProperty("stance","WITHDRAWN");
        try {PlayerIntentPlan.parse(staleWithdrawal,input);throw new AssertionError("old desire cannot prove withdrawal");}
        catch(IllegalArgumentException expected){checks++;}
        var legacy=raw.deepCopy();legacy.remove("stance");legacy.addProperty("mode","STATED_GOAL");
        try {PlayerIntentPlan.parse(legacy,input);throw new AssertionError("obsolete wire contract silently accepted");}
        catch(IllegalArgumentException expected){checks++;}
        try {new PlayerIntentPlan.Input(input.playerId(),"",List.of(input.sources().getFirst(),input.sources().getFirst()),false);throw new AssertionError("ambiguous source id");}
        catch(IllegalArgumentException expected){checks++;}
        try {new PlayerIntentPlan.Input(input.playerId(),"pending",input.sources(),true);throw new AssertionError("secondary pending input");}
        catch(IllegalArgumentException expected){checks++;}
        try {new PlayerIntentPlan.Input(input.playerId(),"x".repeat(1001),List.of(),false);throw new AssertionError("oversize input");}
        catch(IllegalArgumentException expected){checks++;}
        System.out.println("PlayerIntentPlanTest: PASS ("+checks+" checks)");
    }
}
