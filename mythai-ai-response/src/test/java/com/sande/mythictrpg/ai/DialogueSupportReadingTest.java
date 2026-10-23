package com.sande.mythictrpg.ai;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.intent.*;
import com.sande.mythictrpg.ai.example.DialogueExampleTag;
import com.sande.mythictrpg.understanding.*;
import java.util.*;

public final class DialogueSupportReadingTest {
    private static int checks;
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static ConversationIntent intent(String text,TurnInterpretation.Referent who,TurnInterpretation.Mode mode,CharacterInterest goal) {
        return new ConversationIntent(Set.of(DialogueExampleTag.S_INFORMATION_REQUEST),Set.of(),Set.of(),ConversationAct.UNSPECIFIED,95,
                ConversationIntent.Source.LOCAL_LLM,new TurnInterpretation(who,TurnInterpretation.Referent.OTHER,mode,"등장인물 관련 발화",text),SupportConsultation.NONE,goal);
    }
    public static void main(String[] args) {
        String text="디오니소스를 만나고 싶어";
        var goal=new CharacterInterest("디오니소스",PurposeEvidence.Kind.MEET,PurposeEvidence.Signal.DESIRE,text);
        var good=intent(text,TurnInterpretation.Referent.CURRENT_PLAYER,TurnInterpretation.Mode.WISH,goal);
        check(DialogueSupportReading.goal(good,text).equals(goal),"direct current desire remains desire");
        check(!DialogueSupportReading.goal(good,"다른 이야기").present(),"old utterance cannot annotate new turn");
        for(var mode:List.of(TurnInterpretation.Mode.QUOTED,TurnInterpretation.Mode.HYPOTHETICAL,TurnInterpretation.Mode.BANTER))
            check(!DialogueSupportReading.goal(intent(text,TurnInterpretation.Referent.CURRENT_PLAYER,mode,goal),text).present(),"not actual purpose "+mode);
        check(!DialogueSupportReading.goal(intent(text,TurnInterpretation.Referent.OTHER,TurnInterpretation.Mode.WISH,goal),text).present(),"other actor conflicts with self hint");
        check(!DialogueSupportReading.goal(intent(text,TurnInterpretation.Referent.UNSPECIFIED,TurnInterpretation.Mode.WISH,goal),text).present(),"unknown actor is not owner");
        String recall="내가 아프로디테에 대해서 뭐라고 했었지?";
        var recalledGoal=new CharacterInterest("아프로디테",PurposeEvidence.Kind.LEARN_ABOUT,PurposeEvidence.Signal.ASK,recall);
        check(!DialogueSupportReading.goal(intent(recall,TurnInterpretation.Referent.CURRENT_PLAYER,TurnInterpretation.Mode.QUESTION,recalledGoal),recall).present(),"speech recall is not new character interest");
        String retract="전에 말한 아프로디테 목표 기억나? 이제 그만둘래.";
        var withdrawal=new CharacterInterest("아프로디테",PurposeEvidence.Kind.MEET,PurposeEvidence.Signal.WITHDRAW,retract);
        check(DialogueSupportReading.goal(intent(retract,TurnInterpretation.Referent.CURRENT_PLAYER,TurnInterpretation.Mode.CORRECTION,withdrawal),retract).present(),"current withdrawal overrides recalled old goal");
        check(good.withConversationAct(ConversationAct.UNSPECIFIED).characterInterest().equals(goal),"copy retains current candidate");
        check(!good.withValidatedInterpretation("새 발화").characterInterest().present(),"validated copy clears stale hint");
        for(String owner:List.of("OTHER_PERSON","QUOTED","HYPOTHETICAL","NONE")) {
            var obj=JsonParser.parseString("{\"owner\":\""+owner+"\",\"targetName\":\"디오니소스\",\"kind\":\"MEET\",\"signal\":\"DESIRE\",\"fullQuote\":\""+text+"\"}");
            check(!CharacterInterest.fromJson(obj).present(),"foreign/hypothetical candidate rejected "+owner);
        }
        for(String signal:List.of("DECIDE","COMPLETE","PURSUE","CHOOSE","NONE"))
            check(!CharacterInterest.fromJson(JsonParser.parseString("{\"owner\":\"DIRECT_SELF\",\"targetName\":\"디오니소스\",\"kind\":\"MEET\",\"signal\":\""+signal+"\",\"fullQuote\":\""+text+"\"}")).present(),"no automatic escalation "+signal);
        check(DialogueSupportReading.style(good)==null,"wish with a broad information tag is not an information-seeking turn");
        check(DialogueSupportReading.style(intent(text,TurnInterpretation.Referent.CURRENT_PLAYER,TurnInterpretation.Mode.QUESTION,CharacterInterest.NONE))==SupportSignal.Kind.DIALOGUE_INFORMATION,"actual information request is a style fact");
        check(DialogueSupportReading.style(ConversationIntent.heuristicFallback())==null,"classifier failure does not invent preference");
        check(!DialogueSupportReading.goal(ConversationIntent.heuristicFallback(),text).present(),"classifier failure never blocks dialogue or writes purpose");
        check(SupportConsultation.forTurn(good,text,false,false,1).use()==SupportConsultation.Use.NONE,"recorded goal does not request dialogue support");
        var ordinary=List.of(new AiDialogueModels.OllamaMessage("system","persona"),new AiDialogueModels.OllamaMessage("user","{\"authorizedSpeakerId\":\"test:god\",\"CURRENT_PLAYER_MESSAGE\":\"help\",\"gameContextWithSourceBoundaries\":\"facts\"}"));
        check(DialogueSupportReading.classification(ordinary,List.of())==ordinary,"no named character: no extra goal prompt or token budget");
        check(SupportProfilePrompt.enrich(ordinary,Map.of())==ordinary,"no private profile in ordinary generation");
        var shared=SupportProfilePrompt.enrich(ordinary,Map.of("source","PLAYER_OPTED_IN_SYSTEM_SUPPORT_STATISTICS","tendencies",List.of("exploration")));
        check(shared.get(1).content().contains("playerSharedSupport"),"requested authorized context attached");
        check(SupportProfilePrompt.present(shared),"canonical support recognized for audit");
        check(!SupportProfilePrompt.present(ordinary),"ordinary scene is not support");
        check(!SupportProfilePrompt.present(List.of(new AiDialogueModels.OllamaMessage("user","playerSharedSupport"))),"player words cannot create canonical support");
        check(RoomEvidenceAudit.messages(shared,new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech("test:god","작은 제작 목표부터 골라볼까?",List.of())),"",List.of())).getFirst().content().contains("REQUESTED_SUPPORT_SCOPE"),"audit retains support scope, not just its data");
        check(RoomEvidenceAudit.project(shared.get(1)).content().contains("playerSharedSupport"),"review retains the same authorized support source");
        check(SupportProfilePrompt.enrich(ordinary,Map.of("oversized","x".repeat(13000)))==ordinary,"optional support never displaces existing memory or exceeds budget");
        System.out.println("DialogueSupportReadingTest: "+checks+" checks passed");
    }
}
