package com.sande.mythictrpg.ai.relationship;

import com.sande.mythictrpg.ai.AiDialogueModels;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Filters persona examples by derived relationship tags. A persona example may begin with
 * [R_FRIENDLY,R_CLOSE] (or any RelationshipTag); untagged examples are always eligible.
 */
public final class RelationshipExampleRetriever {
    private final RelationshipStateResolver resolver;

    public RelationshipExampleRetriever(RelationshipStateResolver resolver) {
        this.resolver = resolver;
    }

    public List<String> retrieve(AiDialogueModels.GodPersona persona, RelationshipMetrics relationship,
            int maximumExamples) {
        Set<String> activeTags = resolver.resolve(relationship).stream().map(Enum::name)
                .collect(java.util.stream.Collectors.toSet());
        List<RankedExample> accepted = new ArrayList<>();
        for (String raw : persona.examples()) {
            ParsedExample parsed = parse(raw);
            int matchCount = (int) parsed.tags().stream().filter(activeTags::contains).count();
            if (parsed.tags().isEmpty() || matchCount > 0) {
                accepted.add(new RankedExample(parsed.text(), matchCount));
            }
        }
        return accepted.stream().sorted(Comparator.comparingInt(RankedExample::matchCount).reversed())
                .limit(Math.max(0, maximumExamples)).map(RankedExample::text).toList();
    }

    public Set<RelationshipTag> tags(RelationshipMetrics relationship) {
        return Set.copyOf(resolver.resolve(relationship));
    }

    private static ParsedExample parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (!text.startsWith("[")) {
            return new ParsedExample(Set.of(), text);
        }
        int closing = text.indexOf(']');
        if (closing < 0) {
            return new ParsedExample(Set.of(), text);
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String candidate : text.substring(1, closing).split(",")) {
            String tag = candidate.trim().toUpperCase(Locale.ROOT);
            try {
                RelationshipTag.valueOf(tag);
                tags.add(tag);
            } catch (IllegalArgumentException ignored) {
                // The bracket is prose rather than relationship metadata.
                return new ParsedExample(Set.of(), text);
            }
        }
        String body = text.substring(closing + 1).trim();
        return body.isEmpty() ? new ParsedExample(Set.of(), text) : new ParsedExample(Set.copyOf(tags), body);
    }

    private record ParsedExample(Set<String> tags, String text) {
    }

    private record RankedExample(String text, int matchCount) {
    }
}
