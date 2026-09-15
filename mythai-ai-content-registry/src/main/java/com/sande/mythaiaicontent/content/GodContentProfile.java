package com.sande.mythaiaicontent.content;

import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable base content for one existing God ID. It contains no relationship, quest progress, memory, or runtime state. */
public record GodContentProfile(ResourceLocation contentId, ResourceLocation godId, String displayName,
        String identity, String description, List<String> personality, List<String> values,
        List<String> speechStyles, List<String> dialogueGuidelines, Map<String, List<String>> situationGuidelines,
        Map<String, List<String>> repetitionGuidelines,
        List<String> restrictions, List<String> characterTags, List<LoreKnowledge> loreKnowledge,
        List<ResourceLocation> questListIds,
        List<ResourceLocation> signatureExampleIds, Map<RelationshipTier, List<String>> relationshipGuidelines) {
    public GodContentProfile {
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(godId, "godId");
        displayName = required(displayName, "displayName");
        identity = required(identity, "identity");
        description = description == null ? "" : description.trim();
        personality = texts(personality);
        values = texts(values);
        speechStyles = texts(speechStyles);
        dialogueGuidelines = texts(dialogueGuidelines);
        situationGuidelines = immutableSituationGuidelines(situationGuidelines);
        repetitionGuidelines = immutableRepetitionGuidelines(repetitionGuidelines);
        restrictions = texts(restrictions);
        characterTags = texts(characterTags);
        loreKnowledge = immutableLoreKnowledge(loreKnowledge);
        questListIds = immutableIds(questListIds);
        signatureExampleIds = immutableIds(signatureExampleIds);
        relationshipGuidelines = immutableRelationshipGuidelines(relationshipGuidelines);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("God content " + field + " must not be blank");
        }
        return value.trim();
    }

    private static List<String> texts(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).distinct().toList();
    }

    private static List<ResourceLocation> immutableIds(List<ResourceLocation> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static List<LoreKnowledge> immutableLoreKnowledge(List<LoreKnowledge> values) {
        if (values == null) {
            return List.of();
        }
        HashSet<ResourceLocation> loreIds = new HashSet<>();
        for (LoreKnowledge knowledge : values) {
            Objects.requireNonNull(knowledge, "loreKnowledge contains null");
            if (!loreIds.add(knowledge.loreId())) {
                throw new IllegalArgumentException("A God profile may declare each lore ID only once: " + knowledge.loreId());
            }
        }
        return List.copyOf(values);
    }

    private static Map<RelationshipTier, List<String>> immutableRelationshipGuidelines(
            Map<RelationshipTier, List<String>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<RelationshipTier, List<String>> copy = new LinkedHashMap<>();
        values.forEach((tier, guidelines) -> copy.put(Objects.requireNonNull(tier, "relationship tier"), texts(guidelines)));
        return Map.copyOf(copy);
    }

    /** Situation guidance is static content. Runtime intent decides which one or two entries are selected per turn. */
    private static Map<String, List<String>> immutableSituationGuidelines(Map<String, List<String>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((tag, guidelines) -> {
            String key = required(tag, "situation guideline tag");
            if (!key.startsWith("S_")) {
                throw new IllegalArgumentException("Situation guidance key must use an S_ tag: " + key);
            }
            copy.put(key, texts(guidelines));
        });
        return Map.copyOf(copy);
    }

    /** Per-persona guidance for a repeated conversational pattern; never stores a player's persistent state. */
    private static Map<String, List<String>> immutableRepetitionGuidelines(Map<String, List<String>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((key, guidelines) -> {
            String normalized = required(key, "repetition guideline key");
            if (!normalized.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("Invalid repetition guidance key: " + normalized);
            }
            copy.put(normalized, texts(guidelines));
        });
        return Map.copyOf(copy);
    }
}
