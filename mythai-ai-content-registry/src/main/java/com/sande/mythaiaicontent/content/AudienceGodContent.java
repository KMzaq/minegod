package com.sande.mythaiaicontent.content;

import java.util.List;
import java.util.Map;

/** Only prefiltered, audience-safe values. Unlike the raw profile this contains no hidden content references. */
public record AudienceGodContent(Profile profile, List<ResolvedLoreKnowledge> lore, List<DialogueExample> examples,
        List<String> relationshipGuidance, List<String> socialRelationTags, long generation) {
    public AudienceGodContent {
        lore = List.copyOf(lore);
        examples = List.copyOf(examples);
        relationshipGuidance = List.copyOf(relationshipGuidance);
        socialRelationTags = List.copyOf(socialRelationTags);
    }

    public record Profile(String displayName, String identity, String description, List<String> personality,
            List<String> values, List<String> speechStyles, List<String> dialogueGuidelines,
            Map<String, List<String>> situationGuidelines, Map<String, List<String>> repetitionGuidelines,
            List<String> restrictions, List<String> characterTags) { }
}
