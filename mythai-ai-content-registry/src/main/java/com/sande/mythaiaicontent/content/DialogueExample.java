package com.sande.mythaiaicontent.content;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Static writing example, available globally when knownBy is empty or only to listed existing God IDs otherwise. */
public record DialogueExample(ResourceLocation id, Set<String> tags, Set<ResourceLocation> knownBy,
        List<DialogueExampleTurn> dialogue, ContentDisclosure disclosure) {
    public DialogueExample(ResourceLocation id, Set<String> tags, Set<ResourceLocation> knownBy,
            List<DialogueExampleTurn> dialogue) {
        this(id, tags, knownBy, dialogue, ContentDisclosure.PUBLIC);
    }
    public DialogueExample {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(disclosure, "disclosure");
        tags = tags == null ? Set.of() : tags.stream().filter(Objects::nonNull).map(String::trim)
                .filter(tag -> !tag.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (tags.isEmpty()) {
            throw new IllegalArgumentException("Dialogue example " + id + " requires at least one tag");
        }
        knownBy = knownBy == null ? Set.of() : Set.copyOf(knownBy);
        dialogue = dialogue == null ? List.of() : List.copyOf(dialogue);
        // Intent-classifier samples intentionally contain only the triggering player utterance. They are never used
        // as dialogue-generation references, so inventing an NPC reply would only add irrelevant training material.
        int minimumTurns = id.getPath().startsWith("intent_classifier/") ? 1 : 2;
        if (dialogue.size() < minimumTurns) {
            throw new IllegalArgumentException("Dialogue example " + id + " requires at least " + minimumTurns + " turn(s)");
        }
    }

    public boolean availableTo(ResourceLocation godId) {
        return knownBy.isEmpty() || knownBy.contains(godId);
    }
}
