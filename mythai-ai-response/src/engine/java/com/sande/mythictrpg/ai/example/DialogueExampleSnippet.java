package com.sande.mythictrpg.ai.example;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A selected NPC-authorized example exposed to prompt assembly, without any live-game authority. */
public record DialogueExampleSnippet(String exampleId, Set<DialogueExampleTag> tags,
        List<DialogueExampleTurn> dialogue) {
    public DialogueExampleSnippet {
        Objects.requireNonNull(exampleId, "exampleId");
        tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
        dialogue = List.copyOf(Objects.requireNonNull(dialogue, "dialogue"));
    }

    public static DialogueExampleSnippet from(DialogueExample source) {
        return new DialogueExampleSnippet(source.exampleId(), source.tags(), source.dialogue());
    }
}
