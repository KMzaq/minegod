package com.sande.mythictrpg.quest;

import com.google.gson.JsonObject;
import java.util.Set;

/** Opt-in rules, frozen when a quest roster is first accepted. */
public record QuestReorganizationPolicy(boolean allowWithdrawal, long absentAfterTicks,
        boolean allowReplacement, int minimumParticipants, boolean refundItems) {
    public QuestReorganizationPolicy {
        if (absentAfterTicks < 0 || absentAfterTicks > 630_720_000L || minimumParticipants < 1 || minimumParticipants > 16)
            throw new IllegalArgumentException("Invalid quest reorganization limits");
        if (!allowWithdrawal && absentAfterTicks == 0)
            throw new IllegalArgumentException("Reorganization must allow withdrawal or absence removal");
        if (minimumParticipants > 1 && !allowReplacement)
            throw new IllegalArgumentException("A minimum above one requires replacement to avoid a permanently blocked quest");
    }
    public static QuestReorganizationPolicy parse(JsonObject json) {
        Set<String> fields = Set.of("allowWithdrawal", "absentAfterTicks", "allowReplacement", "minimumParticipants", "refundItems");
        if (!json.keySet().equals(fields)) throw new IllegalArgumentException("All reorganization fields must be explicitly specified");
        for (String key : Set.of("allowWithdrawal", "allowReplacement", "refundItems"))
            if (!json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isBoolean())
                throw new IllegalArgumentException("Expected boolean " + key);
        for (String key : Set.of("absentAfterTicks", "minimumParticipants"))
            if (!json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isNumber())
                throw new IllegalArgumentException("Expected integer " + key);
        return new QuestReorganizationPolicy(json.get("allowWithdrawal").getAsBoolean(),
                json.get("absentAfterTicks").getAsBigDecimal().longValueExact(),
                json.get("allowReplacement").getAsBoolean(), json.get("minimumParticipants").getAsBigDecimal().intValueExact(),
                json.get("refundItems").getAsBoolean());
    }
}
