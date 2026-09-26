package com.sande.mythictrpg.quest.structure;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record StructureEvaluationReport(ResourceLocation policyId, ResourceLocation godId,
        int score, double buildScore, double environmentScore, List<CriterionScore> criteria,
        StructureSnapshot snapshot, String evidenceSummary) {
    public StructureEvaluationReport {
        score = Math.max(0, Math.min(100, score)); criteria = List.copyOf(criteria);
        evidenceSummary = evidenceSummary == null ? "" : evidenceSummary;
    }
    public record CriterionScore(String id, StructureEvaluationPolicy.Scope scope, String type,
            double rawValue, double normalizedValue, double awardedPoints, double availablePoints) {}
}
