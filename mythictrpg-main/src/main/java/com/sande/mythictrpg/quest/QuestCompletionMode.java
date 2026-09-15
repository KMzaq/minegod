package com.sande.mythictrpg.quest;

import java.util.Locale;

/** Determines who may perform the authoritative final completion check. */
public enum QuestCompletionMode {
    AUTO,
    NPC_VISIT_PLAYER,
    PLAYER_RETURN_TO_NPC;

    public static QuestCompletionMode parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing completionMode");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown completionMode: " + value, exception);
        }
    }
}
