package com.sande.mythaiaicontent.content;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

/** Public reference facts, never secret lore, personality guidance, or current game state. */
public record CommonKnowledgeEntry(ResourceLocation id, String title, List<String> keywords, String content) {
    static final int MAX_ENTRIES = 128;
    static final int MAX_TITLE_LENGTH = 100;
    static final int MAX_CONTENT_LENGTH = 1200;
    static final int MAX_KEYWORDS = 24;
    static final int MAX_KEYWORD_LENGTH = 80;

    public CommonKnowledgeEntry {
        Objects.requireNonNull(id, "id");
        title = boundedText(title, "title", MAX_TITLE_LENGTH);
        content = boundedText(content, "content", MAX_CONTENT_LENGTH);
        Objects.requireNonNull(keywords, "keywords");
        if (keywords.isEmpty() || keywords.size() > MAX_KEYWORDS) {
            throw new IllegalArgumentException("keywords must contain 1.." + MAX_KEYWORDS + " strings");
        }
        keywords = keywords.stream().map(keyword -> boundedText(keyword, "keyword", MAX_KEYWORD_LENGTH)).toList();
    }

    private static String boundedText(String text, String field, int maximum) {
        if (text == null || text.isBlank() || text.length() > maximum) {
            throw new IllegalArgumentException(field + " must contain 1.." + maximum + " nonblank characters");
        }
        return text.trim();
    }
}
