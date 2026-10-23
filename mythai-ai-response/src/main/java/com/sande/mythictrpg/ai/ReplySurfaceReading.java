package com.sande.mythictrpg.ai;

import com.google.gson.*;
import java.util.*;

/** Output-only diagnostic reading, never persisted or promoted to authoritative game/player facts. */
final class ReplySurfaceReading {
    private static final Gson JSON=new Gson();
    private static final List<String> MODES=List.of("NO_DECISION_CLAIM","WISH_ONLY","OPEN_CONDITION","SETTLED_PURSUIT","SETTLED_STOP","UNCERTAIN");
    private ReplySurfaceReading(){ }
    static List<AiDialogueModels.OllamaMessage> messages(AiDialogueModels.StructuredAiResult draft) {
        return List.of(new AiDialogueModels.OllamaMessage("system", """
                [REPLY_SURFACE_READING_V1]
                Read only the literal meaning of the supplied NPC reply, not whether it is true or supported.
                No source conversation is provided. Do not assume a polite reply must be correct. Do not roleplay.
                In this primary reply 'I' is NPC and 'you/네/당신' is PLAYER unless explicitly attributed otherwise.
                For each exact unit classify the strongest asserted decision meaning using neighbours to resolve ellipsis.
                SETTLED_PURSUIT: says someone already chose/resolved/clearly set a goal to pursue an action.
                WISH_ONLY: says they wish/want/hope, without a settled decision. A clearly SET goal is not merely a wish.
                SETTLED_STOP: says someone chose/decided NOT to pursue or to stop. Do not lose the negation.
                OPEN_CONDITION: decision is conditional ('if you decide'), not asserted. NO_DECISION_CLAIM for other content.
                UNCERTAIN when the literal meaning is genuinely ambiguous. owner is PLAYER/NPC/OTHER/NONE/UNCERTAIN.
                literalAction is a short plain paraphrase of what the person would DO, preserving negation and conditions.
                Also summarize the current reply's conversational move: who is invited to tell their story next?
                nextStorySpeaker PLAYER/NPC/NONE/UNCERTAIN. explicitlyDeclinesOwnStory true only if NPC actually says
                it doesn't wish to tell its own story or explicitly defers it, not a mere thanks or an invitation to player.
                Copy every unit exactly. These are readings, not a pass/fail verdict or replacement reply.
                """),new AiDialogueModels.OllamaMessage("user",JSON.toJson(Map.of("reply",draft,"units",RoomEvidenceAudit.units(draft),"schema",schema(draft)))));
    }
    static Map<String,Object> schema(AiDialogueModels.StructuredAiResult draft) {
        var units=new LinkedHashMap<String,Object>();
        RoomEvidenceAudit.units(draft).forEach((id,text)->units.put(id,Map.of("type","object","additionalProperties",false,"properties",Map.of(
                "text",strings(List.of(text)),"mode",strings(MODES),"owner",strings(List.of("PLAYER","NPC","OTHER","NONE","UNCERTAIN")),
                "literalAction",Map.of("type","string","maxLength",240)),"required",List.of("text","mode","owner","literalAction"))));
        return Map.of("type","object","additionalProperties",false,"properties",Map.of(
                "units",Map.of("type","object","additionalProperties",false,"properties",units,"required",new ArrayList<>(units.keySet())),
                "nextStorySpeaker",strings(List.of("PLAYER","NPC","NONE","UNCERTAIN")),"explicitlyDeclinesOwnStory",Map.of("type","boolean")),
                "required",List.of("units","nextStorySpeaker","explicitlyDeclinesOwnStory"));
    }
    static JsonObject parse(JsonObject o,AiDialogueModels.StructuredAiResult draft) {
        if(!o.keySet().equals(Set.of("units","nextStorySpeaker","explicitlyDeclinesOwnStory")))throw new IllegalArgumentException("SURFACE_SHAPE");
        var expected=RoomEvidenceAudit.units(draft);var units=o.getAsJsonObject("units");
        if(!units.keySet().equals(expected.keySet()))throw new IllegalArgumentException("SURFACE_COVERAGE");
        for(var entry:expected.entrySet()) {
            var v=units.getAsJsonObject(entry.getKey());
            if(!v.keySet().equals(Set.of("text","mode","owner","literalAction"))||!text(v,"text").equals(entry.getValue())
                    ||!MODES.contains(text(v,"mode"))||!Set.of("PLAYER","NPC","OTHER","NONE","UNCERTAIN").contains(text(v,"owner"))
                    ||text(v,"literalAction").length()>240)throw new IllegalArgumentException("SURFACE_UNIT");
        }
        if(!Set.of("PLAYER","NPC","NONE","UNCERTAIN").contains(text(o,"nextStorySpeaker"))
                ||!o.get("explicitlyDeclinesOwnStory").isJsonPrimitive()||!o.getAsJsonPrimitive("explicitlyDeclinesOwnStory").isBoolean())
            throw new IllegalArgumentException("SURFACE_MOVE");
        return o.deepCopy();
    }
    static int tokens(AiDialogueModels.StructuredAiResult draft){return 256+256*RoomEvidenceAudit.units(draft).size();}
    private static Map<String,Object> strings(List<String> values){return Map.of("type","string","enum",values);}
    private static String text(JsonObject o,String k){var v=o.get(k);if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString())throw new IllegalArgumentException("SURFACE_STRING");return v.getAsString();}
}
