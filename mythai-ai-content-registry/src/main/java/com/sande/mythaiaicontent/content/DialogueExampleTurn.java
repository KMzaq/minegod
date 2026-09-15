package com.sande.mythaiaicontent.content;

import java.util.Locale;
import java.util.Objects;

/** One static turn in a writing example. It is never a live player or NPC participant. */
public record DialogueExampleTurn(Role role, String text) {
    public enum Role {
        PLAYER,
        NPC;

        public static Role parse(String raw) {
            return valueOf(Objects.requireNonNull(raw, "role").trim().toUpperCase(Locale.ROOT));
        }
    }

    public DialogueExampleTurn {
        Objects.requireNonNull(role, "role");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Dialogue example text must not be blank");
        }
        text = text.trim();
        if (text.codePointCount(0, text.length()) > 750) {
            throw new IllegalArgumentException("Dialogue example text must be at most 750 characters");
        }
    }
}
