package com.sande.mythictrpg.ai.example;

import java.util.Objects;

/** One bounded, prompt-safe turn within a reusable dialogue example. */
public record DialogueExampleTurn(DialogueExampleRole role, String text) {
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
