package com.sande.mythictrpg.quest;

import java.util.Locale;

/** Server-authoritative narrative access class for an authored quest. */
public enum QuestNarrativeRole {
    SIDE,
    MAIN_ENTRY,
    MAIN;

    public static QuestNarrativeRole parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unknown quest narrativeRole '" + value
                    + "' (expected SIDE, MAIN_ENTRY, or MAIN)", exception);
        }
    }

    public boolean isMainQuest() {
        return this != SIDE;
    }
}
