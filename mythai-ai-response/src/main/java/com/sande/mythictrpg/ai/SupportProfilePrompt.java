package com.sande.mythictrpg.ai;

import com.google.gson.*;
import java.util.*;

/** Optional, already authorized self-shared statistics. Never added on ordinary turns. */
final class SupportProfilePrompt {
    private static final String POLICY="""
            REQUESTED_PLAYER_SUPPORT
            playerSharedSupport contains system statistics the player explicitly opted to share for this private
            consultation. Use only if relevant to the current request. Tendencies are contextual, tentative and
            can coexist; they are not fixed personality, skill, morality or your eyewitness memory. A goal's
            DESIRED is a wish, CONSIDERING is undecided, observedProgress is behavior, never a decision to meet.
            Offer a small number of optional next steps. Current explicit choices override old tendencies.
            Do not repeat the profile as a diagnosis, claim unobserved past actions, invent routes/rewards,
            start a quest or execute an action just because a tendency matches. Keep your normal persona.
            """;
    private SupportProfilePrompt() { }
    static final String AUDIT_RULES="""
            REQUESTED_SUPPORT_SCOPE: playerSharedSupport is the game-authorized, player-consented support
            input for this turn. supportOptions are future optional choices, not proof of past activity,
            personality, enjoyment, skill, possession or an executed action. Suggesting a supplied option
            needs no evidence the player already enjoys or performed it. Named goals retain their supplied
            owner and intention stage; DESIRED/CONSIDERING are not decisions. This source is not eyewitness
            memory. Prior NPC praise or imagery does not add evidence to the player's statistics or goals.
            """;
    static boolean present(List<AiDialogueModels.OllamaMessage> messages) {
        for(var message:messages)if(message.role().equals("user"))try {
            var scene=JsonParser.parseString(message.content()).getAsJsonObject();
            if(scene.has("authorizedSpeakerId")&&scene.has("CURRENT_PLAYER_MESSAGE")
                    &&scene.has("playerSharedSupport")&&scene.get("playerSharedSupport").isJsonObject())return true;
        }catch(RuntimeException notScene){ }
        return false;
    }
    static final String CANDIDATE_POLICY="""
            SUPPORT_OPTIONS_ONLY: playerSharedSupport.supportOptions are small optional next steps selected by
            the game from accumulated play records. Use relevant options to answer the current request; do not
            introduce them by evaluating the player's personality, emotion, passion, skill or fixed preferences.
            The candidate list is NOT evidence that they love, enjoy, prepared or already performed an activity.
            If a named goal is supplied and relevant, acknowledge that specific interest at its stated stage and
            offer what to learn/check next. Do not ask the player to restate an already supplied interest.
            These are choices, not commands or executed actions. Do not invent a named game feature, route or reward.
            """;
    static List<AiDialogueModels.OllamaMessage> enrich(List<AiDialogueModels.OllamaMessage> messages,Map<String,Object> shared) {
        if(shared.isEmpty())return messages;
        if(new Gson().toJson(shared).length()>4096)return messages;
        var result=new ArrayList<AiDialogueModels.OllamaMessage>();boolean added=false;
        for(var m:messages) {
            if(m.role().equals("system")){result.add(new AiDialogueModels.OllamaMessage("system",m.content()+"\n"+POLICY));continue;}
            try {
                var scene=JsonParser.parseString(m.content()).getAsJsonObject();
                if(scene.has("authorizedSpeakerId")&&scene.has("CURRENT_PLAYER_MESSAGE")) {
                    scene.add("playerSharedSupport",new Gson().toJsonTree(shared));result.add(new AiDialogueModels.OllamaMessage(m.role(),scene.toString()));added=true;continue;
                }
            }catch(RuntimeException notScene){ }
            result.add(m);
        }
        if(added)result.add(new AiDialogueModels.OllamaMessage("system",CANDIDATE_POLICY));
        // Support is optional; do not displace persona, current request or existing dialogue memory.
        int originalCharacters=messages.stream().mapToInt(m->m.content().length()).sum();
        return added&&result.stream().mapToInt(m->m.content().length()).sum()-originalCharacters<=5200?List.copyOf(result):messages;
    }
}
