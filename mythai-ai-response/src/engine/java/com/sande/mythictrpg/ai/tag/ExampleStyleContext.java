package com.sande.mythictrpg.ai.tag;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Separate style-selection context derived from NPC classification; it is not the raw character tag list. */
public record ExampleStyleContext(Set<ExampleStyleTag> styleTags, List<String> humanAttitudes) {
    public ExampleStyleContext {
        styleTags = Set.copyOf(Objects.requireNonNull(styleTags, "styleTags"));
        humanAttitudes = List.copyOf(Objects.requireNonNull(humanAttitudes, "humanAttitudes"));
    }
}
