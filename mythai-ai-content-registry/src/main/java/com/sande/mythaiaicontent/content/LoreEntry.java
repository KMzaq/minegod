package com.sande.mythaiaicontent.content;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

/** Immutable lore definition. God-specific knowledge levels are declared by GodContentProfile, not here. */
public record LoreEntry(ResourceLocation id, String title, List<LoreKnowledgeLevel> knowledgeLevels,
        LoreSecrecy secrecy, List<String> keywords) {
    public LoreEntry {
        Objects.requireNonNull(id, "id");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Lore entry requires a title");
        }
        title = title.trim();
        knowledgeLevels = knowledgeLevels == null ? List.of() : List.copyOf(knowledgeLevels);
        if (knowledgeLevels.isEmpty()) {
            throw new IllegalArgumentException("Lore entry requires at least one knowledge level");
        }
        for (int index = 0; index < knowledgeLevels.size(); index++) {
            LoreKnowledgeLevel level = Objects.requireNonNull(knowledgeLevels.get(index), "knowledgeLevels contains null");
            if (level.level() != index + 1) {
                throw new IllegalArgumentException("Lore knowledge levels must be consecutive and start at 1");
            }
        }
        secrecy = secrecy == null ? LoreSecrecy.PUBLIC : secrecy;
        keywords = keywords == null ? List.of() : keywords.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).distinct().toList();
    }

    public int maxKnowledgeLevel() {
        return knowledgeLevels.size();
    }

    public List<LoreKnowledgeLevel> levelsThrough(int knowledgeLevel) {
        int resolvedLevel = Math.min(knowledgeLevel, maxKnowledgeLevel());
        return knowledgeLevels.subList(0, resolvedLevel);
    }

    public boolean revealsKnowledgeHoldersThrough(int knowledgeLevel) {
        return levelsThrough(knowledgeLevel).stream().anyMatch(LoreKnowledgeLevel::revealKnowledgeHolders);
    }
}
