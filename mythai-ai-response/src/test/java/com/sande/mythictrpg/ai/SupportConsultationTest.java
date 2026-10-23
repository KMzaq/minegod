package com.sande.mythictrpg.ai;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.intent.*;
import java.util.Set;

public final class SupportConsultationTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static ConversationIntent intent(SupportConsultation hint, TurnInterpretation.Mode mode, int confidence, ConversationIntent.Source source) {
        return new ConversationIntent(Set.of(),Set.of(),Set.of(),ConversationAct.UNSPECIFIED,confidence,source,
                new TurnInterpretation(TurnInterpretation.Referent.CURRENT_PLAYER,TurnInterpretation.Referent.UNSPECIFIED,
                        mode,"자기 활동을 질문함","내 행동"),hint);
    }
    public static void main(String[] args) {
        String input="네가 본 내 행동을 알려줘";
        var hint=new SupportConsultation(SupportConsultation.Use.OBSERVED_ACTION_RECALL,"내 행동");
        var good=intent(hint,TurnInterpretation.Mode.REQUEST,95,ConversationIntent.Source.LOCAL_LLM);
        check(SupportConsultation.forTurn(good,input,false,false,1).equals(hint),"current request can consult");
        check(SupportConsultation.forTurn(good,"오늘은 비가 오네",false,false,1).use()==SupportConsultation.Use.NONE,"old quote cannot carry to new topic");
        for (var mode : new TurnInterpretation.Mode[]{TurnInterpretation.Mode.QUOTED,TurnInterpretation.Mode.HYPOTHETICAL,TurnInterpretation.Mode.CORRECTION,TurnInterpretation.Mode.REFUSAL})
            check(SupportConsultation.forTurn(intent(hint,mode,95,ConversationIntent.Source.LOCAL_LLM),input,false,false,1).use()==SupportConsultation.Use.NONE,"no consultation for "+mode);
        check(SupportConsultation.forTurn(good,input,true,false,1).use()==SupportConsultation.Use.NONE,"secondary");
        check(SupportConsultation.forTurn(good,input,false,true,1).use()==SupportConsultation.Use.NONE,"public");
        check(SupportConsultation.forTurn(good,input,false,false,2).use()==SupportConsultation.Use.NONE,"multiple gods");
        check(SupportConsultation.forTurn(intent(hint,TurnInterpretation.Mode.REQUEST,69,ConversationIntent.Source.LOCAL_LLM),input,false,false,1).use()==SupportConsultation.Use.NONE,"uncertain");
        check(SupportConsultation.forTurn(intent(hint,TurnInterpretation.Mode.REQUEST,99,ConversationIntent.Source.HEURISTIC),input,false,false,1).use()==SupportConsultation.Use.NONE,"fallback");
        for(String raw:new String[]{"null","{}","[]","{\"use\":\"ALL\",\"evidence\":\"내 행동\"}","{\"use\":\"SELF_ASSESSMENT\",\"evidence\":4}","{\"use\":\"SELF_ASSESSMENT\",\"evidence\":\"\"}"})
            check(SupportConsultation.fromJson(JsonParser.parseString(raw)).equals(SupportConsultation.NONE),"bad optional extension "+raw);
        check(new SupportConsultation(SupportConsultation.Use.SELF_ASSESSMENT,"가".repeat(161)).equals(SupportConsultation.NONE),"bounded quote");
        check(good.withValidatedInterpretation("new topic").supportConsultation().equals(SupportConsultation.NONE),"validated copy resets hint");
        check(good.withConversationAct(ConversationAct.UNSPECIFIED).supportConsultation().equals(hint),"act copy preserves hint");
        check(ConversationIntent.heuristicFallback().supportConsultation().equals(SupportConsultation.NONE),"old constructor defaults off");
        System.out.println("SupportConsultationTest: "+checks+" checks passed");
    }
}
