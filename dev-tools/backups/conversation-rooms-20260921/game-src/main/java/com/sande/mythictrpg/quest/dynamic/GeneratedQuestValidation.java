package com.sande.mythictrpg.quest.dynamic;

/** Validation result safe to expose through the AI action gateway. */
public record GeneratedQuestValidation(boolean allowed, String reason) {
    public static GeneratedQuestValidation allow() {
        return new GeneratedQuestValidation(true, "");
    }

    public static GeneratedQuestValidation reject(String reason) {
        return new GeneratedQuestValidation(false, reason);
    }
}
