package com.sande.mythaiaicontent.content;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

/**
 * Lore already filtered to one God's knowledge limit. AI callers must use this instead of a raw LoreEntry when
 * constructing an LLM prompt.
 */
public record ResolvedLoreKnowledge(ResourceLocation id, String title, LoreSecrecy secrecy, List<String> keywords,
        int knowledgeLevel, List<LoreKnowledgeLevel> accessibleLevels, List<LoreKnowledgeHolder> knowledgeHolders) {
    public ResolvedLoreKnowledge {
        Objects.requireNonNull(id, "id");
        if (title == null || title.isBlank() || knowledgeLevel < 1) {
            throw new IllegalArgumentException("Resolved lore requires title and a positive knowledge level");
        }
        title = title.trim();
        secrecy = secrecy == null ? LoreSecrecy.PUBLIC : secrecy;
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        accessibleLevels = accessibleLevels == null ? List.of() : List.copyOf(accessibleLevels);
        knowledgeHolders = knowledgeHolders == null ? List.of() : List.copyOf(knowledgeHolders);
        if (accessibleLevels.isEmpty()) {
            throw new IllegalArgumentException("Resolved lore must contain at least one accessible level");
        }
    }
}
