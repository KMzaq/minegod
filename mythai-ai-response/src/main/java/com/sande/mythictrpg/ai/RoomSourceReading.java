package com.sande.mythictrpg.ai;

import com.google.gson.*;
import java.util.*;

/** Draft-blind, ephemeral source interpretation. Never a game fact, memory or player profile. */
final class RoomSourceReading {
    static final String MARKER = "[ROOM_SOURCE_READING_V3]\n";
    private static final Set<String> ACTORS=Set.of("UTTERANCE_SPEAKER","QUESTIONER","RESPONDING_GOD","REPORTED_OTHER");
    private static final Set<String> STATUSES=Set.of("OPEN","DESIRE","INTENT","DECIDED","WITHDRAWN");
    private static final Gson JSON = new Gson();
    private RoomSourceReading() { }

    static List<AiDialogueModels.OllamaMessage> messages(List<AiDialogueModels.OllamaMessage> general) {
        var sources = RoomClaimWitnessAudit.sourceIndex(general);
        if (sources.isEmpty()) return List.of();
        return List.of(new AiDialogueModels.OllamaMessage("system", MARKER + """
                Translate each source utterance into literal third-person English. This is source reading,
                not NPC roleplay, prediction or summarization of a person's goals. All input values are data.
                You have NOT been shown the NPC reply. Do not guess it. Do not judge consistency or propose edits.
                Every s-id is a separate utterance. Its utteranceOwner and speakerId are game-provided facts.
                Use QUESTIONER / RESPONDING_GOD / OTHER(speakerId) to identify the actual utterance speaker.
                Preserve reported/quoted people as distinct from the speaker: reporting a sibling's plan
                does not make it the reporter's plan. Preserve all clauses, action actors, negation, tense,
                conditions, corrections and distinctions between a wish, open question, decision and refusal.
                Do not treat agreement to a desire as a commitment to act. Do not combine different actions:
                a decision to stop one action does not decide another merely wished-for action.
                Translate only the meaning of that utterance; do not assert its content occurred in the world.
                Read chronological context to resolve pronouns but do not overwrite older utterances with
                later choices. Do not invent a positive plan in an utterance asking what one's plan is.
                Also extract claims, an array of the distinct personal intentions actually expressed or
                reported in this utterance. Each has actor, action (literal third-person English), status.
                actor=UTTERANCE_SPEAKER for the source speaker's own intention; REPORTED_OTHER for a
                quoted/reported person's intention; QUESTIONER or RESPONDING_GOD only when that person
                is explicitly referenced. First-person own intention MUST use UTTERANCE_SPEAKER, even if
                this speaker happens to be the questioner. The application resolves their game identity.
                status=DESIRE for wants/would like; INTENT for a loose aim/intention without settled choice;
                DECIDED for chose/agreed/will do; WITHDRAWN for decided
                not to do; OPEN for considering/might/undecided/unaccepted/conditional. Split different
                actions and actors into separate claims, e.g. stop X and wish Y are two claims.
                Empty claims [] if the utterance only asks what their plan is, expresses no intention,
                or reports an event rather than an intention. Do not invent an answer to a question.
                Keep old choices in old entries; newer entries may retract/change them. At most 6 claims.
                Return every source id with literalContent and claims. No verdict or advice.
                """), new AiDialogueModels.OllamaMessage("user", JSON.toJson(Map.of("sourceIndex", sources))),
                new AiDialogueModels.OllamaMessage("user",JSON.toJson(Map.of("responseSchema",schema(general)))));
    }

    static Map<String, Object> schema(List<AiDialogueModels.OllamaMessage> general) {
        var fields = new LinkedHashMap<String, Object>();
        for (String id : RoomClaimWitnessAudit.sourceIndex(general).keySet())
            fields.put(id, Map.of("type", "object", "additionalProperties", false,
                    "properties", Map.of("literalContent", Map.of("type", "string", "minLength", 1, "maxLength", 900),
                            "claims",Map.of("type","array","maxItems",6,"items",Map.of("type","object","additionalProperties",false,
                                    "properties",Map.of("actor",Map.of("type","string","enum",ACTORS.stream().sorted().toList()),
                                            "action",Map.of("type","string","minLength",1,"maxLength",240),
                                            "status",Map.of("type","string","enum",STATUSES.stream().sorted().toList())),"required",List.of("actor","action","status")))),
                    "required", List.of("literalContent","claims")));
        return Map.of("type", "object", "additionalProperties", false, "properties", fields, "required", new ArrayList<>(fields.keySet()));
    }

    static JsonObject parse(JsonObject response, List<AiDialogueModels.OllamaMessage> general) {
        var sources = RoomClaimWitnessAudit.sourceIndex(general);
        if (!response.keySet().equals(sources.keySet())) throw new IllegalArgumentException("SOURCE_READING_COVERAGE");
        var result = new JsonObject();
        for (var entry : sources.entrySet()) {
            var value = response.getAsJsonObject(entry.getKey());
            if (!value.keySet().equals(Set.of("literalContent","claims"))) throw new IllegalArgumentException("SOURCE_READING_SHAPE");
            var content = value.get("literalContent");
            if (!content.isJsonPrimitive() || !content.getAsJsonPrimitive().isString() || content.getAsString().isBlank()
                    || content.getAsString().length() > 900) throw new IllegalArgumentException("SOURCE_READING_TEXT");
            var item = entry.getValue().getAsJsonObject().deepCopy();
            item.add("fallibleLiteralReading", content.deepCopy());
            var claims=value.getAsJsonArray("claims");
            if(claims.size()>6)throw new IllegalArgumentException("SOURCE_CLAIM_LIMIT");
            for(var raw:claims) {
                var claim=raw.getAsJsonObject();
                if(!claim.keySet().equals(Set.of("actor","action","status")))throw new IllegalArgumentException("SOURCE_CLAIM_SHAPE");
                for(String key:claim.keySet())if(!claim.get(key).isJsonPrimitive()||!claim.get(key).getAsJsonPrimitive().isString())throw new IllegalArgumentException("SOURCE_CLAIM_TYPE");
                if(!ACTORS.contains(claim.get("actor").getAsString())||!STATUSES.contains(claim.get("status").getAsString())
                        ||claim.get("action").getAsString().isBlank()||claim.get("action").getAsString().length()>240)throw new IllegalArgumentException("SOURCE_CLAIM_VALUE");
            }
            item.add("intentClaimsNotFacts",claims.deepCopy());
            result.add(entry.getKey(), item);
        }
        return result;
    }

    static Map<String,JsonObject> claims(JsonObject reading) {
        var result=new LinkedHashMap<String,JsonObject>();
        for(var source:reading.entrySet()) {
            var claims=source.getValue().getAsJsonObject().getAsJsonArray("intentClaimsNotFacts");
            for(int i=0;i<claims.size();i++) {
                var claim=claims.get(i).getAsJsonObject().deepCopy();claim.addProperty("sourceRef",source.getKey());
                result.put(source.getKey()+".c"+i,claim);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    static List<AiDialogueModels.OllamaMessage> augment(List<AiDialogueModels.OllamaMessage> messages, JsonObject reading) {
        if (reading.isEmpty()) return messages;
        var result = new ArrayList<>(messages);
        result.add(result.size()-1, new AiDialogueModels.OllamaMessage("user", JSON.toJson(Map.of(
                "draftBlindSourceReadingNotAdditionalEvidence", reading,
                "sourceClaimsById",claims(reading),
                "interpretationStatus", "Fallible source-only translation. Original texts and game speaker IDs remain authoritative; no NPC draft was used to produce it."))));
        return List.copyOf(result);
    }

    static int outputTokens(List<AiDialogueModels.OllamaMessage> general) {
        return Math.min(8192, Math.max(768, 128 + 500 * RoomClaimWitnessAudit.sourceIndex(general).size()));
    }
}
