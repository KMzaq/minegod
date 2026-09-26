package com.sande.mythictrpg.quest.structure;

/** Future non-authoritative extension boundary; MVP scoring never calls it. */
public interface ExternalStructureEvaluator {
    java.util.Optional<String> advisoryComment(StructureSnapshot snapshot,
            StructureEvaluationReport authoritativeReport);
}
