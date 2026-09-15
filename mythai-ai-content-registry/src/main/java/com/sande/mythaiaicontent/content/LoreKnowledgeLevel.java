package com.sande.mythaiaicontent.content;

/** One cumulative, fixed-information tier inside a lore entry. */
public record LoreKnowledgeLevel(int level, String content, boolean revealKnowledgeHolders) {
    public LoreKnowledgeLevel {
        if (level < 1) {
            throw new IllegalArgumentException("Lore knowledge level must be at least 1");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Lore knowledge level content must not be blank");
        }
        content = content.trim();
    }
}
