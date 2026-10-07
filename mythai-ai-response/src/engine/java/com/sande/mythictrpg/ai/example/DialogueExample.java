package com.sande.mythictrpg.ai.example;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

/**
 * A tagged dialogue example. Empty {@code knownBy} means a reusable global example; otherwise it is visible only to
 * the listed NPC IDs, mirroring the knowledge engine's {@code known_by} boundary.
 */
public record DialogueExample(String exampleId, Set<DialogueExampleTag> tags, Set<ResourceLocation> knownBy,
        List<DialogueExampleTurn> dialogue) {
    public DialogueExample {
        if (exampleId == null || !exampleId.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Dialogue example ID must be a short identifier: " + exampleId);
        }
        exampleId = exampleId.trim();
        if (tags == null || tags.isEmpty()) {
            throw new IllegalArgumentException("Dialogue example " + exampleId + " requires at least one tag");
        }
        tags = Set.copyOf(new LinkedHashSet<>(tags));
        knownBy = knownBy == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(knownBy));
        dialogue = List.copyOf(Objects.requireNonNull(dialogue, "dialogue"));
        if (dialogue.size() < 2) {
            throw new IllegalArgumentException("Dialogue example " + exampleId + " requires at least two turns");
        }
    }

    /** Compatibility constructor for existing globally reusable examples. */
    public DialogueExample(String exampleId, Set<DialogueExampleTag> tags, List<DialogueExampleTurn> dialogue) {
        this(exampleId, tags, Set.of(), dialogue);
    }

    /** A query without a target is intentionally broad for diagnostics and legacy callers. */
    public boolean availableTo(ResourceLocation npcId) {
        return npcId == null || knownBy.isEmpty() || knownBy.contains(npcId);
    }
}
