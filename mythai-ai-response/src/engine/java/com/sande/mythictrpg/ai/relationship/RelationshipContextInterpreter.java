package com.sande.mythictrpg.ai.relationship;

import java.util.List;

/** Converts metrics to explainable, prompt-ready relationship meaning without changing the metrics. */
@FunctionalInterface
public interface RelationshipContextInterpreter {
    List<String> describe(RelationshipMetrics relationship);
}
