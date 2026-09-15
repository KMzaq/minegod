package com.sande.mythictrpg.ai.tag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Keeps raw tags as the source of truth while retaining both explicit and resolved classifications.
 * Explicit categories override auto-classification only for those categories.
 */
public record NpcTagProfile(List<String> rawTags, NpcTagClassification explicitClassification,
        NpcTagClassification classification) {
    public NpcTagProfile {
        Objects.requireNonNull(rawTags, "rawTags");
        rawTags = rawTags.stream().filter(Objects::nonNull).map(String::trim).filter(tag -> !tag.isEmpty())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        Objects.requireNonNull(explicitClassification, "explicitClassification");
        Objects.requireNonNull(classification, "classification");
    }
}
