package com.sande.mythictrpg.ai.tag;

import java.util.Objects;

/** One standardized source tag and its primary classification category. */
public record CharacterTagDefinition(String name, CharacterTagCategory category) {
    public CharacterTagDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Character tag name must not be blank");
        }
        name = name.trim();
        Objects.requireNonNull(category, "category");
    }
}
