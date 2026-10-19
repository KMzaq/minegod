package com.sande.mythictrpg.ai;

import com.google.gson.*;
import java.util.*;

/** Constrains generation to the existing game-routed room speech contract; never grants permission. */
final class RoomSpeechSchema {
    private static final Gson JSON=new Gson();
    private RoomSpeechSchema() { }

    static Object forMessages(List<AiDialogueModels.OllamaMessage> messages,Map<String,Object> legacy) {
        if(messages.isEmpty()||!messages.getFirst().role().equals("system"))return legacy;
        String system=messages.getFirst().content();
        boolean primary=system.startsWith("Portray the authorized God NPC in an ongoing Minecraft RPG conversation.");
        boolean secondary=system.startsWith("You portray ONE God in a Minecraft RPG. This is an OPTIONAL SECONDARY reaction");
        if(!primary&&!secondary)return legacy;
        String speaker=null;
        for(var message:messages) {
            if(!message.role().equals("user"))continue;
            JsonObject scene;
            try {scene=JsonParser.parseString(message.content()).getAsJsonObject();}catch(RuntimeException nonScene){continue;}
            String key=primary?"authorizedSpeakerId":"speaker";
            if(!scene.has(key)||!scene.has(primary?"gameContextWithSourceBoundaries":"ownAuthoritativeContext"))continue;
            if(speaker!=null)throw new IllegalArgumentException("AMBIGUOUS_ROOM_SPEECH_SCHEMA");
            var value=scene.get(key);
            if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("INVALID_ROOM_SPEAKER");
            speaker=value.getAsString();
            if(speaker.isBlank()||speaker.length()>256)throw new IllegalArgumentException("INVALID_ROOM_SPEAKER");
        }
        if(speaker==null)throw new IllegalArgumentException("MISSING_ROOM_SPEECH_SCHEMA");
        // Clone per request: concurrent rooms and retries must not mutate the shared legacy schema.
        JsonObject schema=JSON.toJsonTree(legacy).getAsJsonObject();
        var properties=schema.getAsJsonObject("properties").getAsJsonObject("speech")
                .getAsJsonObject("items").getAsJsonObject("properties");
        properties.add("speakerId",JSON.toJsonTree(Map.of("type","string","enum",List.of(speaker))));
        // Primary and Secondary both accept []. Primary's legacy ["player"] parser remains compatible.
        // The game routes the actual audience. Do not make an enum of player IDs or silently strip bad output.
        properties.add("audienceParticipantIds",JSON.toJsonTree(Map.of("type","array","items",Map.of("type","string"),"maxItems",0)));
        return schema;
    }
}
