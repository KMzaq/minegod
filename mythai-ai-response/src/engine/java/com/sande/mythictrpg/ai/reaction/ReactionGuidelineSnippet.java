package com.sande.mythictrpg.ai.reaction;

import java.util.List;
import java.util.Objects;

/** Bounded, non-executable reaction guidance that may be placed in an NPC's prompt context. */
public record ReactionGuidelineSnippet(String id, List<String> guidance, List<String> avoid,
        List<String> requiredContext) {
    public ReactionGuidelineSnippet {
        id = requireText(id, "id");
        guidance = clean(guidance);
        avoid = clean(avoid);
        requiredContext = clean(requiredContext);
    }

    public static ReactionGuidelineSnippet from(ReactionGuidelineMatch match) {
        Objects.requireNonNull(match, "match");
        ReactionGuideline guideline = match.guideline();
        return new ReactionGuidelineSnippet(guideline.id().name(), guideline.guidelines(), guideline.avoid(),
                guideline.requiredContext());
    }

    private static List<String> clean(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
