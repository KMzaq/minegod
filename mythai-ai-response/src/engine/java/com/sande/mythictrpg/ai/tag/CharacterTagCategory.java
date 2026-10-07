package com.sande.mythictrpg.ai.tag;

import java.util.Locale;
import java.util.Optional;

/** Categories of source character tags. Categories are independent from speech-style tags. */
public enum CharacterTagCategory {
    MYTHOLOGY,
    EXISTENCE,
    HIERARCHY,
    GENDER,
    ALIGNMENT,
    DOMAINS,
    ATTRIBUTES,
    WORLD_OR_AFFILIATION,
    PERSONALITY,
    HUMAN_ATTITUDE,
    WEAPONS,
    COMBAT_STYLE,
    APPEARANCE,
    SYMBOLS,
    RELATIONSHIPS,
    ORIGIN,
    CURRENT_STATUS,
    DANGER_LEVEL,
    NARRATIVE_ROLE,
    ROLE,
    FAITH,
    DIVINITY,
    SCALE,
    ABILITY;

    public static Optional<CharacterTagCategory> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return Optional.of(valueOf(normalized));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
