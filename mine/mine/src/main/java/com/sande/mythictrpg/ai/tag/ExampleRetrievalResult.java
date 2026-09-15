package com.sande.mythictrpg.ai.tag;

import java.util.List;
import java.util.Objects;

/** Returned independently from reaction-guideline selection so future prompt assembly can combine both. */
public record ExampleRetrievalResult(ExampleStyleContext styleContext, List<String> selectedExamples) {
    public ExampleRetrievalResult {
        Objects.requireNonNull(styleContext, "styleContext");
        selectedExamples = List.copyOf(Objects.requireNonNull(selectedExamples, "selectedExamples"));
    }
}
