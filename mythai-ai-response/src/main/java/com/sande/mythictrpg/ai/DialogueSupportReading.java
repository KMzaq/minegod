package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.intent.*;
import com.sande.mythictrpg.ai.example.DialogueExampleTag;
import com.sande.mythictrpg.understanding.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Reuses the existing classifier. Does not add a model request or expose the profile to dialogue generation. */
final class DialogueSupportReading {
    private DialogueSupportReading() { }
    static SupportSignal.Kind style(ConversationIntent intent) {
        if(intent==null||intent.confidence()<70)return null;
        boolean asks=Set.of(TurnInterpretation.Mode.QUESTION,TurnInterpretation.Mode.REQUEST).contains(intent.turnInterpretation().mode());
        if(asks&&(intent.tags().contains(DialogueExampleTag.S_HELP_REQUEST)||intent.tags().contains(DialogueExampleTag.S_QUEST_INQUIRY)))return SupportSignal.Kind.DIALOGUE_HELP;
        if(asks&&intent.tags().contains(DialogueExampleTag.S_INFORMATION_REQUEST))return SupportSignal.Kind.DIALOGUE_INFORMATION;
        if(Set.of(ConversationAct.GREETING,ConversationAct.SEEKING_COMPANY,ConversationAct.CASUAL_BANTER).contains(intent.conversationAct()))
            return SupportSignal.Kind.DIALOGUE_SOCIAL;
        return null;
    }
    static CharacterInterest goal(ConversationIntent intent,String text) {
        if(intent==null||intent.source()!=ConversationIntent.Source.LOCAL_LLM||intent.confidence()<80)return CharacterInterest.NONE;
        var mode=intent.turnInterpretation().validatedFor(text).mode();
        if(Set.of(TurnInterpretation.Mode.QUOTED,TurnInterpretation.Mode.HYPOTHETICAL,TurnInterpretation.Mode.BANTER).contains(mode))return CharacterInterest.NONE;
        var goal=intent.characterInterest().validatedFor(text);
        // Reuse the existing recall policy; explicit present corrections/withdrawals still take precedence.
        if(goal.present()&&com.sande.mythai.response.memory.RecallQuery.explicitRecall(text)
                &&!Set.of(PurposeEvidence.Signal.DISOWN,PurposeEvidence.Signal.WITHDRAW,PurposeEvidence.Signal.PAUSE).contains(goal.signal()))return CharacterInterest.NONE;
        if(goal.present()&&!Set.of(PurposeEvidence.Signal.DISOWN,PurposeEvidence.Signal.WITHDRAW,PurposeEvidence.Signal.PAUSE).contains(goal.signal())
                &&intent.turnInterpretation().validatedFor(text).subject()!=TurnInterpretation.Referent.CURRENT_PLAYER)return CharacterInterest.NONE;
        return goal;
    }
    static void capture(ServerPlayer player,RoomConversationEngine.Request request,ConversationIntent intent) {
        var goal=goal(intent,request.currentText());
        try {
            DialogueSupportCapture.classified(player,request,style(intent.withValidatedInterpretation(request.currentText())),goal.targetName(),goal.kind(),goal.signal(),goal.fullQuote());
        }catch(RuntimeException invalid) {
            var r=com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService.current(player.server);
            if(r!=null&&r.ledger()!=null)r.ledger().gap("DIALOGUE_SUPPORT_CAPTURE_FAILED");
        }
    }
    static List<AiDialogueModels.OllamaMessage> classification(ServerPlayer player,String text,List<AiDialogueModels.OllamaMessage> original) {
        if(!MinecraftActivityCapture.enrolled(player))return original;
        var names=SupportCharacterNames.candidates(text);
        return classification(original,names);
    }
    static List<AiDialogueModels.OllamaMessage> classification(List<AiDialogueModels.OllamaMessage> original,List<String> names) {
        if(names.isEmpty())return original; // Ordinary turns reuse their existing style classification without extra goal instructions/budget.
        var messages=new ArrayList<>(original);
        messages.add(new AiDialogueModels.OllamaMessage("system",CharacterInterest.instruction()
                +"\nGame-resolved names explicitly present in the current message: "+new com.google.gson.Gson().toJson(names)
                +"\nSelect targetName exactly from this list, in its original Korean spelling. Do not translate/transliterate to English. Empty list means NONE."));
        return List.copyOf(messages);
    }
}
