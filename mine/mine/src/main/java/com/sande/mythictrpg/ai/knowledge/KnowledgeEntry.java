package com.sande.mythictrpg.ai.knowledge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** One world-knowledge record. It is only a candidate until disclosure policy approves it for a specific audience. */
public record KnowledgeEntry(String id, String title, String content, List<String> knownBy, KnowledgeSecrecy secrecy,
        String category, String parentId, List<String> keys, int priority, boolean alwaysActive) {
    public KnowledgeEntry {
        id = requireIdentifier(id, "knowledge ID");
        title = requireText(title, "knowledge title");
        content = requireText(content, "knowledge content");
        knownBy = immutableIdentifiers(knownBy);
        if (knownBy.isEmpty()) {
            throw new IllegalArgumentException("Knowledge entry '" + id + "' requires at least one knownBy NPC ID");
        }
        Objects.requireNonNull(secrecy, "secrecy");
        category = normalizeCategory(category);
        parentId = parentId == null ? "" : parentId.trim();
        keys = immutableIdentifiers(keys);
        if (priority < 0 || priority > 10) {
            throw new IllegalArgumentException("Knowledge entry '" + id + "' priority must be between 0 and 10");
        }
    }

    /** Compatibility constructor for the pre-hierarchy five-field format. */
    public KnowledgeEntry(String id, String title, String content, List<String> knownBy, KnowledgeSecrecy secrecy) {
        this(id, title, content, knownBy, secrecy, "WORLD", "", List.of(), 1, false);
    }

    public boolean knownBy(String npcId) {
        return knownBy.contains("*") || knownBy.contains(npcId);
    }

    static String requireIdentifier(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static List<String> immutableIdentifiers(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        for (String value : raw) {
            identifiers.add(requireIdentifier(value, "knownBy NPC ID"));
        }
        return List.copyOf(identifiers);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeCategory(String value) {
        String normalized = value == null ? "WORLD" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,31}")) {
            throw new IllegalArgumentException("Knowledge category must be an uppercase identifier: " + value);
        }
        return normalized;
    }
}
